package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.ranked.*

const val HISTORY_MAX_BYTES = 4_194_304
const val HISTORY_MAX_OBSERVATIONS = 4000
const val HISTORY_MAX_SESSIONS = 500
const val HISTORY_MAX_BATCHES = 100
const val LONG_GAP_SECONDS = 86_400L
// Conservative anomaly review threshold, not a claim about game rules or a split reset.
const val DISCONTINUITY_RP = 1000L
const val MAX_HISTORY_TIME = 253_402_300_799L

enum class RankedMode { BATTLE_ROYALE }
enum class ObservationSource { PROVIDER, USER_RECORDED }
enum class ContextVerification { UPSTREAM, USER_CONFIRMED, UNKNOWN }
enum class ObservationQuality { VALID, UNKNOWN_CONTEXT, QUARANTINED }
enum class IntervalQuality { VERIFIED, USER_CONFIRMED, LONG_GAP, UNKNOWN_CONTEXT, CONTEXT_BOUNDARY, IDENTITY_BOUNDARY, SOURCE_BOUNDARY, ORDERING, QUARANTINED }
enum class SessionClassification { OBSERVED, PARTIAL, MANUAL, UNCERTAIN }

data class HistoryIdentity(val uid: String, val platform: ApexPlatform) {
    init { require(uid.length in 1..64 && uid.all { it in '0'..'9' }) }
}
fun PlayerIdentity.historyIdentity() = HistoryIdentity(uid, platform)
fun validHistoryText(value: String) = value.isNotBlank() && value.length <= 100 && value.none(Char::isISOControl)
fun validHistoryId(value: String) = value.length in 1..180 && value.all { it.isLetterOrDigit() || it in "-_.:" }
fun requireHistoryTime(time: Long) { require(time in 1..MAX_HISTORY_TIME) }

data class RankedContext(
    val id: String, val identity: HistoryIdentity?, val source: ObservationSource,
    val season: String, val split: String, val confirmedAtSeconds: Long,
    val providerSeason: String? = null, val mode: RankedMode = RankedMode.BATTLE_ROYALE,
) {
    init {
        require(validHistoryId(id)); require(validHistoryText(season) && validHistoryText(split))
        requireHistoryTime(confirmedAtSeconds); require(providerSeason == null || validHistoryText(providerSeason))
        require(providerSeason == null || providerSeason == season) { "Provider season contradicts confirmed season." }
        require(source != ObservationSource.PROVIDER || identity != null)
        require(source != ObservationSource.USER_RECORDED || identity == null)
    }
}
data class RpObservation(
    val id: String, val identity: HistoryIdentity?, val source: ObservationSource,
    val rp: Long, val observedAtSeconds: Long,
    val season: String? = null, val split: String? = null, val contextId: String? = null,
    val verification: ContextVerification = ContextVerification.UNKNOWN,
    val quality: ObservationQuality = ObservationQuality.UNKNOWN_CONTEXT,
    val mode: RankedMode = RankedMode.BATTLE_ROYALE,
) {
    init {
        require(validHistoryId(id)); require(rp in 0..MAX_RP); requireHistoryTime(observedAtSeconds)
        require(source != ObservationSource.PROVIDER || identity != null)
        require(source != ObservationSource.USER_RECORDED || identity == null)
        require(season == null || validHistoryText(season)); require(split == null || validHistoryText(split))
        require(contextId == null || validHistoryId(contextId))
        require(verification == ContextVerification.UNKNOWN || (season != null && split != null && contextId != null))
        require(verification != ContextVerification.UPSTREAM || source == ObservationSource.PROVIDER)
        require(quality != ObservationQuality.VALID || verification != ContextVerification.UNKNOWN)
        require(quality != ObservationQuality.UNKNOWN_CONTEXT || verification == ContextVerification.UNKNOWN)
    }
}
data class RpInterval(
    val fromId: String, val toId: String, val deltaRp: Long?, val durationSeconds: Long,
    val quality: IntervalQuality,
)
val observationOrder = compareBy<RpObservation> { it.observedAtSeconds }.thenBy { it.id }

/** Adjacent points only. Never skip an unknown/quarantined point to draw a continuous series. */
fun rpInterval(older: RpObservation, newer: RpObservation, checkDiscontinuity: Boolean = true): RpInterval {
    val duration = newer.observedAtSeconds - older.observedAtSeconds
    val invalid = when {
        duration <= 0 -> IntervalQuality.ORDERING
        older.identity != newer.identity || older.mode != newer.mode -> IntervalQuality.IDENTITY_BOUNDARY
        older.source != newer.source -> IntervalQuality.SOURCE_BOUNDARY
        older.quality == ObservationQuality.QUARANTINED || newer.quality == ObservationQuality.QUARANTINED -> IntervalQuality.QUARANTINED
        older.verification == ContextVerification.UNKNOWN || newer.verification == ContextVerification.UNKNOWN -> IntervalQuality.UNKNOWN_CONTEXT
        older.season != newer.season || older.split != newer.split || older.contextId != newer.contextId -> IntervalQuality.CONTEXT_BOUNDARY
        checkDiscontinuity && kotlin.math.abs(newer.rp - older.rp) > DISCONTINUITY_RP -> IntervalQuality.QUARANTINED
        else -> null
    }
    return RpInterval(older.id, newer.id, if (invalid == null) newer.rp - older.rp else null, duration,
        invalid ?: when {
            duration > LONG_GAP_SECONDS -> IntervalQuality.LONG_GAP
            older.verification == ContextVerification.USER_CONFIRMED || newer.verification == ContextVerification.USER_CONFIRMED -> IntervalQuality.USER_CONFIRMED
            else -> IntervalQuality.VERIFIED
        })
}
fun rpIntervals(values: List<RpObservation>): List<RpInterval> = values.sortedWith(observationOrder).zipWithNext { older, newer -> rpInterval(older, newer) }

data class RankedSession(
    val id: String, val identity: HistoryIdentity?, val source: ObservationSource,
    val contextId: String?, val startedAtSeconds: Long, val endedAtSeconds: Long? = null,
    val startObservationId: String? = null, val endObservationId: String? = null,
    val mode: RankedMode = RankedMode.BATTLE_ROYALE,
    val interrupted: Boolean = false,
) {
    init {
        require(validHistoryId(id)); requireHistoryTime(startedAtSeconds)
        require(endedAtSeconds == null || endedAtSeconds in startedAtSeconds..MAX_HISTORY_TIME)
        require(source != ObservationSource.PROVIDER || identity != null)
        require(source != ObservationSource.USER_RECORDED || identity == null)
        require(listOfNotNull(contextId, startObservationId, endObservationId).all(::validHistoryId))
        require(endedAtSeconds != null || endObservationId == null)
    }
}
data class SessionResult(
    val classification: SessionClassification, val deltaRp: Long?,
    val windowStartSeconds: Long?, val windowEndSeconds: Long?, val explanation: String,
)
fun sessionResult(session: RankedSession, observations: List<RpObservation>): SessionResult {
    val start = observations.firstOrNull { it.id == session.startObservationId }
    val end = observations.firstOrNull { it.id == session.endObservationId }
    if (session.interrupted) return SessionResult(SessionClassification.UNCERTAIN, null, start?.observedAtSeconds, end?.observedAtSeconds, "Tracking context changed or recording stopped. RP unknown.")
    if (start == null || end == null || start.id == end.id) return SessionResult(SessionClassification.PARTIAL, null, start?.observedAtSeconds, end?.observedAtSeconds, "Two distinct observation anchors are unavailable. RP unknown.")
    val window = observations.filter { it.observedAtSeconds in start.observedAtSeconds..end.observedAtSeconds && it.identity == session.identity && it.source == session.source && it.mode == session.mode }.sortedWith(observationOrder)
    val interval = rpInterval(start, end, checkDiscontinuity = false)
    if (interval.deltaRp == null || rpIntervals(window).any { it.deltaRp == null }) return SessionResult(SessionClassification.UNCERTAIN, null, start.observedAtSeconds, end.observedAtSeconds, "Incompatible or quarantined observations. RP unknown.")
    return SessionResult(if (session.source == ObservationSource.USER_RECORDED) SessionClassification.MANUAL else SessionClassification.OBSERVED,
        interval.deltaRp, start.observedAtSeconds, end.observedAtSeconds,
        "RP covers the observation window only. Match count is unknown." + if (interval.durationSeconds > LONG_GAP_SECONDS) " Long observation gap." else "")
}

/** The only portable v0.2 fields: no provider snapshots, retries, credentials or native widget data. */
data class PortableRankedSettings(val goal: RankGoal? = null, val manualRp: Long? = null,
    val useManual: Boolean = true, val manualUpdatedAtSeconds: Long? = null) {
    init {
        require(manualRp == null || manualRp in 0..MAX_RP)
        manualUpdatedAtSeconds?.let(::requireHistoryTime)
        require(manualRp != null || manualUpdatedAtSeconds == null)
    }
}
data class HistoryRecord(
    val recording: Boolean = false, val settings: PortableRankedSettings? = null,
    val observations: List<RpObservation> = emptyList(), val sessions: List<RankedSession> = emptyList(),
    val contexts: List<RankedContext> = emptyList(), val currentContextId: String? = null,
    val importedBatchIds: List<String> = emptyList(), val nextSequence: Long = 1,
)
data class HistoryState(val record: HistoryRecord = HistoryRecord(), val error: String? = null, val readOnly: Boolean = false)
data class HistoryBackup(val batchId: String, val exportedAtSeconds: Long, val record: HistoryRecord)
/** batchPreviouslyImported is an advisory ID marker, not proof this file's contents were applied. */
data class ImportPreview(val backup: HistoryBackup, val observationCount: Int, val sessionCount: Int,
    val warnings: List<String>, val batchPreviouslyImported: Boolean)

/** Native system picker boundary. Cancellation is null/false; no automatic sharing. */
interface HistoryDocuments {
    suspend fun exportJson(value: String): Boolean
    suspend fun importJson(): String?
}
