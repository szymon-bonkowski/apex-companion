package io.github.szymonbonkowski.apexcompanion.maps

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

fun interface RotationClock { fun nowSeconds(): Long }
interface RotationStore {
    suspend fun read(): String?
    /** Must commit atomically; throw on failure. Records never contain provider credentials or raw responses. */
    suspend fun write(value: String)
}
fun interface RotationProvider { suspend fun fetch(): ProviderResult }
sealed interface ProviderResult {
    data class Success(val body: String) : ProviderResult
    data class Failure(val error: RotationError, val retryAfterSeconds: Long? = null) : ProviderResult
}
object UnconfiguredRotationProvider : RotationProvider {
    override suspend fun fetch() = ProviderResult.Failure(RotationError.ACCESS_NOT_CONFIGURED)
}

data class RotationRepositoryState(
    val cache: RotationCache? = null,
    val refreshing: Boolean = false,
    val error: RotationError? = null,
    val retryAtSeconds: Long? = null,
    val forbidden: Boolean = false,
    val lastAttemptSeconds: Long? = null,
    val failureCount: Int = 0,
)
enum class RefreshOutcome { UPDATED, FAILED, SKIPPED }

/** One app-owned instance serves foreground, background and every widget. No widget owns a provider. */
class RotationRepository(
    private val provider: RotationProvider,
    private val store: RotationStore,
    private val clock: RotationClock,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(RotationRepositoryState())
    val state: StateFlow<RotationRepositoryState> = mutableState.asStateFlow()
    private var initialized = false

    suspend fun initialize() = lock.withLock { initializeLocked() }

    private suspend fun initializeLocked() {
        if (initialized) return
        try {
            store.read()?.let { mutableState.value = decodeRecord(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = RotationRepositoryState(error = RotationError.STORAGE)
        }
        initialized = true
    }

    fun availableModes(): List<Playlist> = state.value.cache?.playlists?.map { Playlist(it.id, it.label) }
        ?.takeIf { it.isNotEmpty() } ?: defaultPlaylists

    fun view(modeId: String, nowSeconds: Long = clock.nowSeconds()): RotationView =
        projectRotation(state.value.cache, modeId, nowSeconds, state.value.error)

    suspend fun refresh(force: Boolean = false): RefreshOutcome = lock.withLock {
        initializeLocked()
        val before = state.value
        val now = clock.nowSeconds()
        if (before.forbidden || before.retryAtSeconds?.let { now < it } == true) return@withLock RefreshOutcome.SKIPPED
        // Manual refresh cannot defeat cooldown/backoff; queued concurrent callers share the preceding result.
        if (before.lastAttemptSeconds?.let { now < it + 30 } == true) return@withLock RefreshOutcome.SKIPPED
        val cache = before.cache
        if (!force && before.error == null && cache != null && !cache.demo && now >= cache.fetchedAtSeconds &&
            now < cache.fetchedAtSeconds + ROTATION_FRESH_SECONDS &&
            cache.playlists.isNotEmpty() && cache.playlists.all { mode ->
                !mode.retained && mode.segments.any { it.contains(now) && it.contains(mode.observedAtSeconds ?: cache.fetchedAtSeconds) }
            }) {
            return@withLock RefreshOutcome.SKIPPED
        }
        mutableState.value = before.copy(refreshing = true, lastAttemptSeconds = now)
        try {
            val result = try { provider.fetch() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { ProviderResult.Failure(RotationError.NETWORK) }
            val completedAt = clock.nowSeconds()
            when (result) {
                is ProviderResult.Success -> {
                    val parsed = try { ApexRotationParser.parse(result.body, completedAt) } catch (_: Exception) { null }
                    if (parsed == null) {
                        persistFailure(before, RotationError.SCHEMA, null, completedAt)
                    } else {
                        // A malformed or omitted playlist does not erase its still usable prior cache.
                        // Retained modes keep their own observation time and can only be STALE.
                        val receivedIds = parsed.playlists.map { it.id }.toSet()
                        val retained = before.cache?.takeUnless { it.demo }?.playlists.orEmpty()
                            .filter { it.id !in receivedIds }
                            .map { it.copy(observedAtSeconds = it.observedAtSeconds ?: before.cache?.fetchedAtSeconds, retained = true) }
                        val combined = parsed.copy(playlists = (parsed.playlists + retained).take(40))
                        val next = RotationRepositoryState(cache = combined, lastAttemptSeconds = completedAt)
                        try {
                            store.write(encodeRecord(next))
                            mutableState.value = next
                            RefreshOutcome.UPDATED
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            mutableState.value = before.copy(error = RotationError.STORAGE, lastAttemptSeconds = now,
                                retryAtSeconds = completedAt + 60)
                            RefreshOutcome.FAILED
                        }
                    }
                }
                is ProviderResult.Failure -> persistFailure(before, result.error, result.retryAfterSeconds, completedAt)
            }
        } finally {
            if (state.value.refreshing) mutableState.value = state.value.copy(refreshing = false)
        }
    }

    private suspend fun persistFailure(before: RotationRepositoryState, error: RotationError, retryAfterSeconds: Long?, now: Long): RefreshOutcome {
        val failures = (before.failureCount + 1).coerceAtMost(10)
        val exponential = (30L * (1L shl (failures - 1))).coerceAtMost(1800)
        val wait = maxOf(exponential, retryAfterSeconds?.coerceIn(0, 86400) ?: 0,
            if (error == RotationError.ACCESS_NOT_CONFIGURED) 300 else 0)
        val next = before.copy(refreshing = false, error = error, retryAtSeconds = now + wait,
            forbidden = error == RotationError.FORBIDDEN, lastAttemptSeconds = now, failureCount = failures)
        try {
            store.write(encodeRecord(next))
            mutableState.value = next
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = next.copy(error = RotationError.STORAGE)
        }
        return RefreshOutcome.FAILED
    }

    /** Explicitly called after an authorized configuration change, never by a repeated Refresh tap. */
    suspend fun resetAccessFailure() = lock.withLock {
        initializeLocked()
        val next = state.value.copy(forbidden = false, error = null, retryAtSeconds = null, lastAttemptSeconds = null, failureCount = 0)
        store.write(encodeRecord(next))
        mutableState.value = next
    }
}

private fun encodeRecord(state: RotationRepositoryState): String = buildJsonObject {
    put("recordVersion", 1)
    put("cache", state.cache?.let(RotationCacheCodec::toJson) ?: JsonNull)
    put("error", state.error?.let { JsonPrimitive(it.name) } ?: JsonNull)
    put("retryAtSeconds", state.retryAtSeconds?.let(::JsonPrimitive) ?: JsonNull)
    put("lastAttemptSeconds", state.lastAttemptSeconds?.let(::JsonPrimitive) ?: JsonNull)
    put("forbidden", state.forbidden)
    put("failureCount", state.failureCount)
}.toString()

private fun decodeRecord(value: String): RotationRepositoryState {
    require(value.length <= 512_000)
    val root = Json.parseToJsonElement(value).jsonObject
    if (root["recordVersion"] == null) return RotationRepositoryState(cache = RotationCacheCodec.decode(value))
    require(root["recordVersion"]?.jsonPrimitive?.intOrNull == 1)
    val cache = root["cache"]?.takeUnless { it is JsonNull }?.let { RotationCacheCodec.decode(it.toString()) }
    return RotationRepositoryState(cache = cache,
        error = root["error"]?.jsonPrimitive?.contentOrNull?.let { RotationError.valueOf(it) },
        retryAtSeconds = root["retryAtSeconds"]?.jsonPrimitive?.longOrNull,
        lastAttemptSeconds = root["lastAttemptSeconds"]?.jsonPrimitive?.longOrNull,
        forbidden = root["forbidden"]?.jsonPrimitive?.booleanOrNull ?: false,
        failureCount = (root["failureCount"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, 10))
}
