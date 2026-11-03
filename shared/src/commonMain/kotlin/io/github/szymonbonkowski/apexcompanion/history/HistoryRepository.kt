package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs
import kotlin.random.Random

/** One private atomic record is the commit point for history AND restored user settings. */
class HistoryRepository(private val store: RotationStore, private val clock: RotationClock) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(HistoryState())
    val state = mutable.asStateFlow()
    private var initialized = false
    suspend fun initialize() = lock.withLock { initializeLocked() }
    private suspend fun initializeLocked() {
        if (initialized) return
        try { store.read()?.let { mutable.value = HistoryState(HistoryCodec.decode(it, clock.nowSeconds())) } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = HistoryState(error = "History could not be read. Original file retained. Restore from an external backup after explicitly deleting the unreadable history, or recover the original file before recording.", readOnly = true) }
        initialized = true
    }
    suspend fun adoptLegacy(settings: PortableRankedSettings): Boolean = lock.withLock {
        initializeLocked()
        if (state.value.readOnly) {
            // Keep readable independent v0.2 settings through an explicit corrupt-history
            // deletion, without overwriting the unreadable original file at initialization.
            if (state.value.record.settings == null) mutable.value = state.value.copy(record = state.value.record.copy(settings = settings))
            false
        } else if (state.value.record.settings != null) true else commit(state.value.record.copy(settings = settings))
    }
    suspend fun saveSettings(settings: PortableRankedSettings): Boolean = change { it.copy(settings = settings) }
    suspend fun setRecording(enabled: Boolean): Boolean = change { r ->
        r.copy(recording = enabled, currentContextId = if (enabled) r.currentContextId else null,
            sessions = if (enabled) r.sessions else interrupt(r.sessions))
    }
    suspend fun confirmContext(identity: HistoryIdentity?, source: ObservationSource, season: String, split: String, providerSeason: String? = null): Boolean = change { r ->
        require(r.recording) { "Enable local recording first." }
        require(providerSeason == null || season.trim() == providerSeason) { "Confirmed season must match supplied provider season." }
        val c = RankedContext(newId("context"), identity, source, season.trim(), split.trim(), clock.nowSeconds(), providerSeason)
        r.copy(contexts = r.contexts + c, currentContextId = c.id, sessions = interrupt(r.sessions))
    }
    /** No observation is manufactured when a context/source/profile changes. */
    suspend fun contextChanged(): Boolean = change { it.copy(currentContextId = null, sessions = interrupt(it.sessions)) }
    suspend fun acceptProfile(snapshot: RankedSnapshot, settings: PortableRankedSettings, contextChange: Boolean): Boolean = change { r ->
        val next = r.copy(settings = settings, currentContextId = if (contextChange) null else r.currentContextId,
            sessions = if (contextChange) interrupt(r.sessions) else r.sessions)
        providerObservation(next, snapshot)
    }
    suspend fun captureProvider(snapshot: RankedSnapshot): Boolean = change { r -> providerObservation(r, snapshot) }
    private fun providerObservation(r: HistoryRecord, snapshot: RankedSnapshot): HistoryRecord {
        if (!r.recording) return r
        require(snapshot.observedAtSeconds in 1..clock.nowSeconds())
        val identity = snapshot.identity.historyIdentity()
        // A delivery ID is tied to a validated fetch event, never to its RP value.
        val id = "provider:${identity.platform.name}:${identity.uid}:${snapshot.observedAtSeconds}"
        if (snapshot.rp != null && r.observations.any { it.id == id }) {
            val prior = r.observations.single { it.id == id }
            require(prior.identity == identity && prior.rp == snapshot.rp && (snapshot.season == null || prior.season == snapshot.season) && (snapshot.split == null || prior.split == snapshot.split)) { "Conflicting duplicate event." }
            return r
        }
        var current = r.contexts.firstOrNull { it.id == r.currentContextId }
        val last = r.observations.filter { it.identity == identity && it.source == ObservationSource.PROVIDER }.maxWithOrNull(observationOrder)
        val confirmationExpired = current != null && snapshot.observedAtSeconds - maxOf(last?.observedAtSeconds ?: 0, current.confirmedAtSeconds) > LONG_GAP_SECONDS
        val seasonChanged = current != null && current.source == ObservationSource.PROVIDER && snapshot.observedAtSeconds >= current.confirmedAtSeconds && (confirmationExpired || current.identity != identity ||
            (current.providerSeason != null && snapshot.season != current.providerSeason) ||
            (snapshot.season != null && snapshot.season != current.season) || (snapshot.split != null && snapshot.split != current.split))
        var next = if (seasonChanged) r.copy(currentContextId = null, sessions = interrupt(r.sessions)) else r
        if (seasonChanged) current = null
        // Missing RP creates no observation, but a validated season change still
        // invalidates an earlier confirmation/session immediately.
        if (snapshot.rp == null) return next
        val upstream = snapshot.season != null && snapshot.split != null
        val successor = r.observations.filter { it.identity == identity && it.source == ObservationSource.PROVIDER && it.observedAtSeconds > snapshot.observedAtSeconds }.minWithOrNull(observationOrder)
        // A late delivery inside an explicitly confirmed historical window can use
        // that original confirmation. Later reconfirmation never rewrites old records.
        val historical = successor?.takeIf { it.verification == ContextVerification.USER_CONFIRMED }?.let { nextObservation ->
            r.contexts.firstOrNull { it.id == nextObservation.contextId && snapshot.observedAtSeconds >= it.confirmedAtSeconds &&
                (it.providerSeason == null || it.providerSeason == snapshot.season) && (snapshot.season == null || snapshot.season == it.season) }
        }
        val use = current?.takeIf { it.source == ObservationSource.PROVIDER && it.identity == identity && snapshot.observedAtSeconds >= it.confirmedAtSeconds } ?: historical
        val o = RpObservation(id, identity, ObservationSource.PROVIDER, snapshot.rp, snapshot.observedAtSeconds,
            season = if (upstream) snapshot.season else use?.season ?: snapshot.season,
            split = if (upstream) snapshot.split else use?.split,
            contextId = if (upstream) "upstream:${snapshot.season.hashCode()}:${snapshot.split.hashCode()}" else use?.id,
            verification = if (upstream) ContextVerification.UPSTREAM else if (use != null) ContextVerification.USER_CONFIRMED else ContextVerification.UNKNOWN,
            quality = if (upstream || use != null) ObservationQuality.VALID else ObservationQuality.UNKNOWN_CONTEXT)
        next = append(next, o)
        return next
    }
    suspend fun saveManualSettings(settings: PortableRankedSettings): Boolean = change { r ->
        val next = r.copy(settings = settings)
        if (settings.manualRp == null || settings.manualUpdatedAtSeconds == null) next
        else manualObservation(next, settings.manualRp, settings.manualUpdatedAtSeconds)
    }
    suspend fun captureManual(rp: Long, timestamp: Long): Boolean = change { r -> manualObservation(r, rp, timestamp) }
    private fun manualObservation(r: HistoryRecord, rp: Long, timestamp: Long): HistoryRecord {
        if (!r.recording) return r
        val c = r.contexts.firstOrNull { it.id == r.currentContextId && it.source == ObservationSource.USER_RECORDED && it.identity == null && timestamp >= it.confirmedAtSeconds }
        return append(r, RpObservation(newId("manual"), null, ObservationSource.USER_RECORDED, rp, timestamp,
            c?.season, c?.split, c?.id, if (c == null) ContextVerification.UNKNOWN else ContextVerification.USER_CONFIRMED,
            if (c == null) ObservationQuality.UNKNOWN_CONTEXT else ObservationQuality.VALID))
    }
    private fun append(r: HistoryRecord, observation: RpObservation): HistoryRecord {
        val prior = r.observations.filter { it.identity == observation.identity && it.source == observation.source && it.mode == observation.mode && it.observedAtSeconds < observation.observedAtSeconds }.maxWithOrNull(observationOrder)
        val suspicious = prior != null && prior.contextId != null && prior.contextId == observation.contextId && abs(observation.rp - prior.rp) > DISCONTINUITY_RP
        // Quarantine belongs to the derived adjacent interval, not an immutable RP
        // sample. Late valid evidence must produce the same intervals in sorted order.
        return r.copy(observations = (r.observations + observation).sortedWith(observationOrder),
            currentContextId = if (suspicious && r.currentContextId == observation.contextId) null else r.currentContextId,
            sessions = if (suspicious) r.sessions.map { session ->
                if (session.endedAtSeconds == null && session.identity == observation.identity && session.source == observation.source && session.contextId == observation.contextId)
                    session.copy(endedAtSeconds = maxOf(clock.nowSeconds(), session.startedAtSeconds), interrupted = true) else session
            } else r.sessions)
    }
    suspend fun startSession(identity: HistoryIdentity?, source: ObservationSource): Boolean = change { r ->
        require(r.recording) { "Enable local recording first." }
        require(r.sessions.none { it.endedAtSeconds == null }) { "Stop the active session first." }
        val now = clock.nowSeconds()
        val context = r.contexts.firstOrNull { it.id == r.currentContextId && it.identity == identity && it.source == source }
        val anchor = r.observations.filter { it.identity == identity && it.source == source && it.observedAtSeconds in (now - PROFILE_FRESH_SECONDS)..now && it.quality == ObservationQuality.VALID &&
            (it.contextId == context?.id || it.verification == ContextVerification.UPSTREAM) }.maxWithOrNull(observationOrder)
        val s = RankedSession(newId("session"), identity, source, context?.id ?: anchor?.contextId, now, startObservationId = anchor?.id)
        r.copy(sessions = r.sessions + s)
    }
    /** Local stop is always available. No new network request, even offline or rate limited. */
    suspend fun stopSession(): Boolean = change { r ->
        val active = r.sessions.singleOrNull { it.endedAtSeconds == null } ?: return@change r
        val now = clock.nowSeconds(); require(now >= active.startedAtSeconds)
        val end = r.observations.filter { it.identity == active.identity && it.source == active.source && it.mode == active.mode &&
            it.observedAtSeconds > active.startedAtSeconds && it.observedAtSeconds <= now }.maxWithOrNull(observationOrder)
        r.copy(sessions = r.sessions.map { if (it.id == active.id) it.copy(endedAtSeconds = now, endObservationId = end?.id) else it })
    }
    private fun interrupt(sessions: List<RankedSession>): List<RankedSession> = sessions.map {
        if (it.endedAtSeconds == null) it.copy(endedAtSeconds = maxOf(clock.nowSeconds(), it.startedAtSeconds), interrupted = true) else it
    }
    /** Explicit destructive confirmation is required by the shared UI. Independent settings survive. */
    suspend fun deleteHistory(): Boolean = lock.withLock {
        initializeLocked()
        commit(HistoryRecord(settings = state.value.record.settings), allowReadOnly = true)
    }
    fun exportBackup(): String {
        check(initialized && !state.value.readOnly && state.value.record.settings != null) { "History or portable settings are unavailable." }
        val now = clock.nowSeconds()
        return HistoryCodec.export(HistoryBackup(newId("backup"), now, state.value.record))
    }
    fun previewImport(value: String, linkedIdentity: HistoryIdentity?): ImportPreview {
        check(initialized && !state.value.readOnly) { "History is unavailable." }
        val backup = HistoryCodec.import(value, clock.nowSeconds())
        require(backup.record.settings != null) { "Backup has no user settings." }
        validateIdentity(backup.record, linkedIdentity)
        val r = state.value.record
        merge(r, backup) // Detect collisions/capacity/relationships BEFORE confirmation.
        return ImportPreview(backup, backup.record.observations.size, backup.record.sessions.size,
            buildList {
                add("Personal goal and manual RP settings will be replaced by the backup. Recording stays under your current control; imported context is not activated.")
                add("Profile linking, provider caches and widgets are not restored. External backups remain your responsibility.")
                add("Backup provenance is recorded metadata from an unsigned user file; importing does not independently authenticate provider events.")
                if (backup.record.sessions.any { it.endedAtSeconds == null }) add("The backup contains an active session; it will resume its original start time.")
                if (linkedIdentity == null && backup.record.observations.any { it.identity != null }) add("Provider history is archived until you explicitly link its matching UID/platform.")
            }, backup.batchId in r.importedBatchIds)
    }
    suspend fun applyImport(preview: ImportPreview, linkedIdentity: HistoryIdentity?): Boolean = change { r ->
        require(validHistoryId(preview.backup.batchId) && preview.backup.exportedAtSeconds in 1..clock.nowSeconds())
        require(preview.backup.record.settings != null)
        HistoryCodec.validate(preview.backup.record, preview.backup.exportedAtSeconds)
        validateIdentity(preview.backup.record, linkedIdentity)
        merge(r, preview.backup)
    }
    private fun validateIdentity(r: HistoryRecord, linkedIdentity: HistoryIdentity?) {
        val identities = (r.observations.mapNotNull { it.identity } + r.contexts.mapNotNull { it.identity } + r.sessions.mapNotNull { it.identity }).distinct()
        require(linkedIdentity == null || identities.all { it == linkedIdentity }) { "Backup belongs to a different linked player. Disconnect first; foreign records will remain archived." }
        require(r.observations.none { it.source == ObservationSource.USER_RECORDED && it.identity != null }) { "Manual history must remain independent." }
    }
    private fun merge(current: HistoryRecord, backup: HistoryBackup): HistoryRecord {
        fun <T> combine(a: List<T>, b: List<T>, id: (T) -> String): List<T> {
            val byId = a.associateBy(id)
            b.forEach { v -> require(byId[id(v)] == null || byId[id(v)] == v) { "A record ID collides with different saved data." } }
            return (a + b).distinctBy(id)
        }
        val incoming = backup.record
        val observations = combine(current.observations, incoming.observations) { it.id }.sortedWith(observationOrder)
        val contexts = combine(current.contexts, incoming.contexts) { it.id }
        // An exported ACTIVE session may since have been stopped locally. Reimporting
        // that older backup must not reopen it or erase its later user-selected end.
        val normalizedSessions = incoming.sessions.map { incomingSession ->
            val local = current.sessions.firstOrNull { it.id == incomingSession.id }
            if (local != null && local.endedAtSeconds != null && incomingSession.endedAtSeconds == null &&
                incomingSession.copy(endedAtSeconds = local.endedAtSeconds, endObservationId = local.endObservationId, interrupted = local.interrupted) == local) local
            else incomingSession
        }
        var sessions = combine(current.sessions, normalizedSessions) { it.id }
        val sourceChanged = current.settings?.useManual != incoming.settings?.useManual
        if (sourceChanged) sessions = sessions.map { session ->
            if (current.sessions.any { it.id == session.id && it.endedAtSeconds == null } && session.endedAtSeconds == null)
                session.copy(endedAtSeconds = maxOf(clock.nowSeconds(), session.startedAtSeconds), interrupted = true) else session
        }
        val result = current.copy(settings = incoming.settings, observations = observations, contexts = contexts, sessions = sessions,
            currentContextId = if (sourceChanged) null else current.currentContextId,
            importedBatchIds = (current.importedBatchIds + backup.batchId).distinct(), nextSequence = maxOf(current.nextSequence, incoming.nextSequence))
        HistoryCodec.validate(result, clock.nowSeconds())
        HistoryCodec.encode(result)
        return result
    }
    private suspend fun change(block: (HistoryRecord) -> HistoryRecord): Boolean = lock.withLock {
        initializeLocked()
        if (state.value.readOnly) return@withLock false
        try {
            val next = block(state.value.record)
            if (next == state.value.record) { mutable.value = state.value.copy(error = null); true } else commit(next)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = state.value.copy(error = "History change rejected. Check context, capacity, ordering and record IDs. Saved data was retained."); false }
    }
    private suspend fun commit(record: HistoryRecord, allowReadOnly: Boolean = false): Boolean = withContext(NonCancellable) {
        // Once an atomic commit begins, publish its matching in-memory state even if
        // the initiating Activity/screen is canceled while native IO is finishing.
        try {
        check(!state.value.readOnly || allowReadOnly)
        HistoryCodec.validate(record, clock.nowSeconds())
        store.write(HistoryCodec.encode(record))
        mutable.value = HistoryState(record)
        true
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { mutable.value = state.value.copy(error = "History could not be saved. Previous committed data retained; storage may be full or history capacity reached."); false }
    }
    private fun newId(prefix: String): String = "$prefix:${Random.nextLong().toULong().toString(16)}:${Random.nextLong().toULong().toString(16)}"
}
