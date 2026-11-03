package io.github.szymonbonkowski.apexcompanion.matches

import io.github.szymonbonkowski.apexcompanion.history.*
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Separate atomic journal. Never reads/writes v0.3 history, settings, caches or widget data. */
class MatchRepository(private val store: RotationStore, private val clock: RotationClock) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(MatchState())
    val state = mutable.asStateFlow()
    private var data = MatchStoreRecord()
    private var initialized = false
    suspend fun initialize() = lock.withLock { initializeLocked() }
    private suspend fun initializeLocked() {
        if (initialized) return
        try {
            store.read()?.let { data = MatchCodec.decode(it, clock.nowSeconds()) }
            mutable.value = MatchState(data.records)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = MatchState(readOnly = true, error = "Match journal unreadable or newer than this app. Original bytes retained; recording and import disabled.") }
        initialized = true
    }
    fun cursor(companionId: String): Long = data.cursors[companionId] ?: 0L
    fun lastSyncSeconds(companionId: String): Long? = data.syncTimes[companionId]
    suspend fun markSyncComplete(companionId: String): Boolean = change { current ->
        require(companionId in current.cursors)
        current.copy(syncTimes = current.syncTimes + (companionId to clock.nowSeconds()))
    }
    suspend fun saveManual(record: MatchRecord): Boolean = change { current ->
        require(record.source == MatchSource.MANUAL && record.quality == MatchQuality.USER_CONFIRMED && record.revision == 0)
        require(current.records.none { it.id == record.id }) { "Already submitted." }
        require(record.id !in current.deletedIds)
        current.copy(records = current.records + record)
    }
    /** Screenshot predictions require explicit review; images are never passed to this repository. */
    suspend fun confirmScreenshot(record: MatchRecord): Boolean = change { current ->
        require(record.source == MatchSource.SCREENSHOT && record.quality == MatchQuality.USER_CONFIRMED && record.revision == 0)
        merge(current, listOf(record), emptyMap(), restoreDeleted = true)
    }
    suspend fun correct(record: MatchRecord): Boolean = change { current ->
        val previous = current.records.single { it.id == record.id }
        require(MatchCodec.sameEvidence(previous, record) && record.revision == previous.revision + 1 && record.quality == MatchQuality.USER_CONFIRMED)
        current.copy(records = current.records.map { if (it.id == record.id) record else it },
            originals = if (record.source != MatchSource.MANUAL && previous.revision == 0) current.originals + (record.id to previous) else current.originals)
    }
    suspend fun applySync(companionId: String, cursor: Long, records: List<MatchRecord>, expectedIdentity: HistoryIdentity?): Boolean = change { current ->
        require(validMatchId(companionId) && cursor >= (current.cursors[companionId] ?: 0L))
        require(records.size <= 100 && records.map { it.id }.distinct().size == records.size)
        require(records.isEmpty() || cursor > (current.cursors[companionId] ?: 0L)) { "Records without cursor progress." }
        require(records.all { it.identity == expectedIdentity && it.source == MatchSource.WINDOWS_CAPTURE &&
            it.quality != MatchQuality.REVIEW_REQUIRED && it.revision == 0 && it.evidenceEventId!!.startsWith("$companionId:") })
        if (records.isEmpty()) require(cursor == (current.cursors[companionId] ?: 0L)) { "Empty batch cannot skip events." }
        merge(current, records, emptyMap(), restoreDeleted = false).copy(cursors = current.cursors + (companionId to cursor))
    }
    /** Cursors and tombstones prevent reconnecting PCs from resurrecting explicitly deleted history. */
    suspend fun deleteHistory(): Boolean = change { current ->
        current.copy(records = emptyList(), originals = emptyMap(), deletedIds = current.deletedIds + current.records.map { it.id },
            deletedEvents = current.deletedEvents + current.records.mapNotNull { it.evidenceEventId })
    }
    fun exportBackup(): String {
        check(initialized && !state.value.readOnly)
        return MatchCodec.export(data.records, data.originals.values.toList())
    }
    fun previewImport(value: String, identity: HistoryIdentity?): MatchImportPreview {
        check(initialized && !state.value.readOnly)
        val incoming = MatchCodec.import(value, clock.nowSeconds())
        validateIdentity(incoming.records, identity)
        val merged = merge(data, incoming.records, incoming.originals, restoreDeleted = true)
        MatchCodec.encode(merged)
        val warnings = incoming.records.sumOf { match -> data.records.count { it.id != match.id && it.possibleDuplicate(match) } } +
            incoming.records.withIndex().sumOf { (index, match) -> incoming.records.drop(index + 1).count { it.possibleDuplicate(match) } }
        return MatchImportPreview(incoming.records, merged.records.size - data.records.size, warnings, listOf(
            "Match-only backup: RP observations, sessions, goals and settings are preserved. Restore those separately through the existing private history backup.",
            "Unsigned backup provenance is metadata, not independently authenticated game evidence. Pairing and capture stay under your current control.",
            "Identical IDs replay safely. Conflicting IDs or original evidence are rejected; saved corrections are never overwritten.",
            "Explicit restore may restore deleted matches. No screenshot, frame, credential or network configuration is included."
        ), incoming.originals)
    }
    suspend fun applyImport(preview: MatchImportPreview, identity: HistoryIdentity?): Boolean = change { current ->
        validateIdentity(preview.records, identity)
        MatchCodec.validate(MatchStoreRecord(preview.records, originals = preview.originals))
        merge(current, preview.records, preview.originals, restoreDeleted = true)
    }
    private fun validateIdentity(records: List<MatchRecord>, identity: HistoryIdentity?) {
        require(identity == null || records.all { it.identity == null || it.identity == identity }) { "Foreign linked identity; disconnect before archive restore." }
    }
    private fun merge(current: MatchStoreRecord, incoming: List<MatchRecord>, originals: Map<String, MatchRecord>, restoreDeleted: Boolean): MatchStoreRecord {
        require(incoming.map { it.id }.distinct().size == incoming.size)
        val result = current.records.toMutableList()
        val originalResult = current.originals.toMutableMap()
        for (record in incoming) {
            val previous = result.firstOrNull { it.id == record.id }
            val event = record.evidenceEventId?.let { id -> result.firstOrNull { it.evidenceEventId == id } }
            require(event == null || event.id == record.id) { "Evidence event already assigned to another record." }
            if (previous != null) {
                require(previous == record || (previous.revision > 0 && current.originals[record.id] == record)) { "Conflicting match ID; correction retained." }
                originals[record.id]?.let { require(current.originals[record.id] == it) }
            } else if (restoreDeleted || (record.id !in current.deletedIds && record.evidenceEventId !in current.deletedEvents)) {
                result.add(record); originals[record.id]?.let { originalResult[record.id] = it }
            }
        }
        val restoredIds = if (restoreDeleted) incoming.map { it.id }.toSet() else emptySet()
        val restoredEvents = if (restoreDeleted) incoming.mapNotNull { it.evidenceEventId }.toSet() else emptySet()
        return current.copy(records = result, originals = originalResult, deletedIds = current.deletedIds - restoredIds, deletedEvents = current.deletedEvents - restoredEvents)
    }
    private suspend fun change(transform: (MatchStoreRecord) -> MatchStoreRecord): Boolean = lock.withLock {
        initializeLocked()
        if (state.value.readOnly) return@withLock false
        try {
            val next = transform(data)
            require((next.records + next.originals.values).all { it.timestampSeconds <= clock.nowSeconds() })
            MatchCodec.validate(next)
            if (next == data) { mutable.value = state.value.copy(error = null); true }
            else withContext(NonCancellable) {
                store.write(MatchCodec.encode(next))
                data = next; mutable.value = MatchState(next.records); true
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            mutable.value = state.value.copy(error = "Match change rejected or storage unavailable. Check values, identity, duplicate IDs and capacity. Saved data retained."); false
        }
    }
}
