package io.github.szymonbonkowski.apexcompanion.ranked

import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.history.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate

/** Current profile and independent goal state. Optional private history owns portable user settings. */
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
    private val history: HistoryRepository? = null,
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
        history?.let { h ->
            h.initialize()
            if (next.error != RankedError.STORAGE && !h.adoptLegacy(next.portableSettings())) next = next.copy(error = RankedError.STORAGE)
            h.state.value.record.settings?.let { next = next.withSettings(it) }
            if (h.state.value.readOnly) next = next.copy(error = RankedError.STORAGE)
        }
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
                    if (profile.identity.platform != lookup.platform || (lookup.byUid && profile.identity.uid != lookup.value) ||
                        profile.observedAtSeconds < 0 || profile.observedAtSeconds > completedAt) {
                        failProfile(before, RankedError.SCHEMA, null, completedAt)
                    } else {
                        val next = before.copy(profile = profile, useManual = if (connecting) false else before.useManual,
                            error = null, refreshing = false, retryAtSeconds = null, failureCount = 0,
                            forbidden = false, lastAttemptSeconds = completedAt)
                        if (!commit(next)) RefreshOutcome.FAILED
                        else if (history != null) {
                            val changed = before.profile?.identity?.historyIdentity() != profile.identity.historyIdentity() || (before.useManual && !next.useManual)
                            if (history.acceptProfile(profile, next.portableSettings(), changed)) RefreshOutcome.UPDATED
                            else { mutable.value = state.value.copy(error = RankedError.STORAGE); RefreshOutcome.FAILED }
                        } else RefreshOutcome.UPDATED
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
    suspend fun disconnect(): Boolean = lock.withLock {
        initializeLocked()
        if (history?.contextChanged() == false) return@withLock false
        if (!savePortableLocked(state.value.portableSettings().copy(useManual = true))) return@withLock false
        commit(state.value.copy(profile = null, useManual = true, error = null, retryAtSeconds = null, lastAttemptSeconds = null,
            failureCount = 0, forbidden = false))
    }
    suspend fun saveGoal(goal: RankGoal?) = changeSettings { it.copy(goal = goal) }
    suspend fun completeGoal() = changeSettings { it.copy(goal = it.goal?.copy(completed = true)) }
    suspend fun setManualRp(rp: Long?): Boolean = lock.withLock {
        require(rp == null || rp in 0..MAX_RP)
        initializeLocked()
        val time = if (rp == null) null else clock.nowSeconds()
        val settings = state.value.portableSettings().copy(manualRp = rp, manualUpdatedAtSeconds = time)
        if (history == null) savePortableLocked(settings)
        else if (!history.saveManualSettings(settings)) false
        else { mutable.value = state.value.withSettings(settings); true }
    }
    suspend fun setUseManual(value: Boolean): Boolean = lock.withLock {
        initializeLocked()
        if (value != state.value.useManual && history?.contextChanged() == false) return@withLock false
        savePortableLocked(state.value.portableSettings().copy(useManual = value))
    }
    suspend fun importHistory(preview: ImportPreview): Boolean = lock.withLock {
        initializeLocked()
        val h = history ?: return@withLock false
        if (!h.applyImport(preview, state.value.profile?.identity?.historyIdentity())) return@withLock false
        h.state.value.record.settings?.let { mutable.value = state.value.withSettings(it) }
        true
    }
    private suspend fun changeSettings(change: (PortableRankedSettings) -> PortableRankedSettings): Boolean = lock.withLock {
        initializeLocked(); savePortableLocked(change(state.value.portableSettings()))
    }
    private suspend fun savePortableLocked(settings: PortableRankedSettings): Boolean {
        val h = history
        return if (h == null) commit(state.value.withSettings(settings)) else {
            if (!h.saveSettings(settings)) false else { mutable.value = state.value.withSettings(settings); true }
        }
    }
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

private fun RankedRepositoryState.portableSettings() = PortableRankedSettings(goal, manualRp, useManual, manualUpdatedAtSeconds)
private fun RankedRepositoryState.withSettings(s: PortableRankedSettings) = copy(goal = s.goal, manualRp = s.manualRp, useManual = s.useManual, manualUpdatedAtSeconds = s.manualUpdatedAtSeconds)
