package io.github.szymonbonkowski.apexcompanion.ranked

import io.github.szymonbonkowski.apexcompanion.maps.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate

/** Single current state only: no inferred matches, deltas, seasons or observation history. */
data class RankedRepositoryState(
    val profile: RankedSnapshot? = null, val goal: RankGoal? = null, val manualRp: Long? = null,
    val useManual: Boolean = false, val manualUpdatedAtSeconds: Long? = null,
    val error: RankedError? = null, val refreshing: Boolean = false,
    val retryAtSeconds: Long? = null, val lastAttemptSeconds: Long? = null,
    val failureCount: Int = 0, val forbidden: Boolean = false,
    val predator: List<PredatorThreshold> = emptyList(), val predatorError: RankedError? = null,
    val predatorRetryAtSeconds: Long? = null, val predatorLastAttemptSeconds: Long? = null,
    val predatorFailureCount: Int = 0, val predatorForbidden: Boolean = false,
)

class RankedRepository(
    private val provider: RankedProvider, private val store: RotationStore, private val clock: RotationClock,
    private val predatorStore: RotationStore = object: RotationStore {
        private var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    },
) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(RankedRepositoryState())
    val state = mutable.asStateFlow()
    private var initialized = false
    suspend fun initialize() = lock.withLock { initializeLocked() }
    private suspend fun initializeLocked() {
        if (initialized) return
        var next = RankedRepositoryState()
        try { store.read()?.let { next = RankedRecordCodec.decode(it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { next = next.copy(error = RankedError.STORAGE) }
        try { predatorStore.read()?.let { next = RankedRecordCodec.withPredator(next, it) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { next = next.copy(predatorError = RankedError.STORAGE) }
        mutable.value = next
        initialized = true
    }
    fun view(nowSeconds: Long, today: LocalDate) = rankedView(state.value, nowSeconds, today)

    suspend fun connect(lookup: ProfileLookup) = lock.withLock {
        initializeLocked()
        fetchProfile(lookup, force = true, connecting = true)
    }
    suspend fun refresh(force: Boolean = false) = lock.withLock {
        initializeLocked()
        val identity = state.value.profile?.identity ?: return@withLock RefreshOutcome.SKIPPED
        fetchProfile(ProfileLookup(identity.uid, identity.platform, true), force, connecting = false)
    }
    private suspend fun fetchProfile(lookup: ProfileLookup, force: Boolean, connecting: Boolean): RefreshOutcome {
        val before = state.value
        val now = clock.nowSeconds()
        if (before.forbidden || before.retryAtSeconds?.let { now < it } == true ||
            before.lastAttemptSeconds?.let { now < it + 30 } == true) return RefreshOutcome.SKIPPED
        if (!force && before.error == null && before.profile?.let { now >= it.observedAtSeconds && now < it.observedAtSeconds + PROFILE_FRESH_SECONDS } == true) return RefreshOutcome.SKIPPED
        mutable.value = before.copy(refreshing = true)
        try {
            val result = try { provider.profile(lookup) } catch (e: CancellationException) { throw e }
                catch (_: Exception) { RankedResult.Failure(RankedError.NETWORK) }
            val completedAt = clock.nowSeconds()
            return when (result) {
                is RankedResult.Success -> {
                    val profile = result.value
                    if (profile.identity.platform != lookup.platform || (lookup.byUid && profile.identity.uid != lookup.value)) {
                        failProfile(before, RankedError.SCHEMA, null, completedAt)
                    } else {
                        val next = before.copy(profile = profile, useManual = if (connecting) false else before.useManual,
                            error = null, refreshing = false, retryAtSeconds = null, failureCount = 0,
                            forbidden = false, lastAttemptSeconds = completedAt)
                        if (commit(next)) RefreshOutcome.UPDATED else RefreshOutcome.FAILED
                    }
                }
                is RankedResult.Failure -> failProfile(before, result.error, result.retryAfterSeconds, completedAt)
            }
        } finally { if (state.value.refreshing) mutable.value = state.value.copy(refreshing = false) }
    }
    private suspend fun failProfile(before: RankedRepositoryState, error: RankedError, retry: Long?, now: Long): RefreshOutcome {
        val failures = (before.failureCount + 1).coerceAtMost(10)
        commit(before.copy(error = error, refreshing = false, retryAtSeconds = now + retryDelay(failures, retry),
            lastAttemptSeconds = now, failureCount = failures, forbidden = error == RankedError.FORBIDDEN))
        return RefreshOutcome.FAILED
    }
    suspend fun refreshPredator(force: Boolean = false) = lock.withLock {
        initializeLocked()
        val before = state.value
        val now = clock.nowSeconds()
        if (before.predatorForbidden || before.predatorRetryAtSeconds?.let { now < it } == true ||
            before.predatorLastAttemptSeconds?.let { now < it + 30 } == true) return@withLock RefreshOutcome.SKIPPED
        if (!force && before.predatorError == null && before.predator.isNotEmpty() &&
            before.predator.all { now >= it.observedAtSeconds && now < it.observedAtSeconds + PREDATOR_FRESH_SECONDS }) return@withLock RefreshOutcome.SKIPPED
        val result = try { provider.predator() } catch (e: CancellationException) { throw e }
            catch (_: Exception) { RankedResult.Failure(RankedError.NETWORK) }
        val completedAt = clock.nowSeconds()
        val next = when (result) {
            is RankedResult.Success -> if (result.value.isNotEmpty() && result.value.map { it.platform }.distinct().size == result.value.size) {
                val received = result.value.map { it.platform }.toSet()
                val retained = before.predator.filter { it.platform !in received }
                before.copy(predator = result.value + retained,
                    predatorError = if (retained.isEmpty()) null else RankedError.SCHEMA,
                    predatorFailureCount = if (retained.isEmpty()) 0 else (before.predatorFailureCount + 1).coerceAtMost(10),
                    predatorRetryAtSeconds = if (retained.isEmpty()) null else completedAt + 60,
                    predatorLastAttemptSeconds = completedAt)
            } else before.copy(predatorError = RankedError.SCHEMA, predatorLastAttemptSeconds = completedAt,
                    predatorRetryAtSeconds = completedAt + 60)
            is RankedResult.Failure -> {
                val failures = (before.predatorFailureCount + 1).coerceAtMost(10)
                before.copy(predatorError = result.error, predatorFailureCount = failures,
                    predatorRetryAtSeconds = completedAt + retryDelay(failures, result.retryAfterSeconds),
                    predatorForbidden = result.error == RankedError.FORBIDDEN, predatorLastAttemptSeconds = completedAt)
            }
        }
        try { predatorStore.write(RankedRecordCodec.encodePredator(next)); mutable.value = next }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = before.copy(predatorError = RankedError.STORAGE); return@withLock RefreshOutcome.FAILED }
        if (next.predatorError == null) RefreshOutcome.UPDATED else RefreshOutcome.FAILED
    }
    suspend fun disconnect() = mutate {
        it.copy(profile = null, useManual = true, error = null, retryAtSeconds = null, lastAttemptSeconds = null,
            failureCount = 0, forbidden = false)
    }
    suspend fun saveGoal(goal: RankGoal?) = mutate { it.copy(goal = goal) }
    suspend fun completeGoal() = mutate { it.copy(goal = it.goal?.copy(completed = true)) }
    suspend fun setManualRp(rp: Long?): Boolean {
        require(rp == null || rp in 0..MAX_RP)
        return mutate { it.copy(manualRp = rp, manualUpdatedAtSeconds = if (rp == null) null else clock.nowSeconds()) }
    }
    suspend fun setUseManual(value: Boolean) = mutate { it.copy(useManual = value) }
    /** Persist both endpoint latches before the platform records a new credential revision. */
    suspend fun resetAccessFailure(): Boolean = lock.withLock {
        initializeLocked()
        val next = state.value.copy(error = null, forbidden = false, retryAtSeconds = null,
            lastAttemptSeconds = null, failureCount = 0, predatorError = null, predatorForbidden = false,
            predatorRetryAtSeconds = null, predatorLastAttemptSeconds = null, predatorFailureCount = 0)
        try {
            predatorStore.write(RankedRecordCodec.encodePredator(next))
            store.write(RankedRecordCodec.encode(next))
            mutable.value = next
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = state.value.copy(error = RankedError.STORAGE); false }
    }
    private suspend fun mutate(change: (RankedRepositoryState) -> RankedRepositoryState): Boolean = lock.withLock {
        initializeLocked(); commit(change(state.value))
    }
    private suspend fun commit(next: RankedRepositoryState): Boolean = try {
        store.write(RankedRecordCodec.encode(next)); mutable.value = next; true
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) {
        mutable.value = state.value.copy(error = RankedError.STORAGE, refreshing = false,
            lastAttemptSeconds = next.lastAttemptSeconds,
            retryAtSeconds = maxOf(clock.nowSeconds() + 60, next.retryAtSeconds ?: 0))
        false
    }
    private fun retryDelay(failures: Int, retry: Long?) = maxOf((30L * (1L shl (failures - 1))).coerceAtMost(1800), retry?.coerceIn(0, 86400) ?: 0)
}
