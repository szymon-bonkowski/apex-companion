package io.github.szymonbonkowski.apexcompanion.matches

import io.github.szymonbonkowski.apexcompanion.history.*

const val MATCH_MAX_BYTES = 16_777_216
const val MATCH_MAX_RECORDS = 10_000
const val MATCH_BATCH_BYTES = 1_048_576

enum class MatchMode { UNKNOWN, BATTLE_ROYALE, RANKED, MIXTAPE }
enum class MatchSource { MANUAL, SCREENSHOT, WINDOWS_CAPTURE }
enum class MatchQuality { REVIEW_REQUIRED, AUTO_ACCEPTED, USER_CONFIRMED }
enum class MatchTimestampSource { RESULT_OBSERVED, USER_ENTERED }
val matchConfidenceFields = setOf("mode", "map", "legend", "kills", "assists", "damage", "placement", "rpDelta")

/** Null is unknown. Bounds are defensive storage bounds, never season/rank rules. */
data class MatchRecord(
    val id: String, val identity: HistoryIdentity? = null, val mode: MatchMode = MatchMode.UNKNOWN,
    val map: String? = null, val legend: String? = null,
    val kills: Int? = null, val assists: Int? = null, val damage: Int? = null,
    val placement: Int? = null, val rpDelta: Int? = null,
    val timestampSeconds: Long, val timestampSource: MatchTimestampSource = MatchTimestampSource.USER_ENTERED,
    val source: MatchSource = MatchSource.MANUAL, val quality: MatchQuality = MatchQuality.USER_CONFIRMED,
    val evidenceEventId: String? = null, val parserVersion: String? = null,
    val fieldConfidence: Map<String, Double> = emptyMap(), val revision: Int = 0,
) {
    init {
        require(validMatchId(id)); requireHistoryTime(timestampSeconds)
        require(map == null || validHistoryText(map)); require(legend == null || validHistoryText(legend))
        require(kills == null || kills in 0..100); require(assists == null || assists in 0..100)
        require(damage == null || damage in 0..100_000); require(placement == null || placement in 1..100)
        require(rpDelta == null || rpDelta in -10_000..10_000); require(revision in 0..1_000_000)
        require(evidenceEventId == null || validMatchId(evidenceEventId))
        require(parserVersion == null || validHistoryText(parserVersion))
        require(fieldConfidence.all { (k, v) -> k in matchConfidenceFields && v.isFinite() && v in 0.0..1.0 && hasField(k) })
        require(source != MatchSource.MANUAL || (quality == MatchQuality.USER_CONFIRMED && evidenceEventId == null && parserVersion == null && fieldConfidence.isEmpty()))
        require(source == MatchSource.MANUAL || (evidenceEventId != null && parserVersion != null))
        require(quality != MatchQuality.AUTO_ACCEPTED || (source == MatchSource.WINDOWS_CAPTURE && timestampSource == MatchTimestampSource.RESULT_OBSERVED && mode != MatchMode.UNKNOWN && fieldConfidence.isNotEmpty()))
    }
    fun hasField(key: String): Boolean = when(key) {
        "mode" -> mode != MatchMode.UNKNOWN; "map" -> map != null; "legend" -> legend != null
        "kills" -> kills != null; "assists" -> assists != null; "damage" -> damage != null
        "placement" -> placement != null; "rpDelta" -> rpDelta != null; else -> false
    }
    /** This heuristic is a review warning only, never a reason to merge separate events. */
    fun possibleDuplicate(other: MatchRecord): Boolean = identity == other.identity && mode == other.mode &&
        kotlin.math.abs(timestampSeconds - other.timestampSeconds) <= 120 &&
        listOf(kills, assists, damage, placement, rpDelta).any { it != null } &&
        kills == other.kills && assists == other.assists && damage == other.damage && placement == other.placement && rpDelta == other.rpDelta
}
fun validMatchId(value: String) = value.length in 1..180 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_.:" }
data class MatchCandidate(val record: MatchRecord, val warnings: List<String>) {
    init { require(record.quality == MatchQuality.REVIEW_REQUIRED); require(warnings.size <= 20 && warnings.all(::validHistoryText)) }
}
data class MatchSyncBatch(val companionId: String, val cursor: Long, val records: List<MatchRecord>)
data class MatchStoreRecord(val records: List<MatchRecord> = emptyList(), val cursors: Map<String, Long> = emptyMap(), val deletedIds: Set<String> = emptySet(), val deletedEvents: Set<String> = emptySet(), val originals: Map<String, MatchRecord> = emptyMap(), val syncTimes: Map<String, Long> = emptyMap())
data class MatchState(val records: List<MatchRecord> = emptyList(), val readOnly: Boolean = false, val error: String? = null)
data class MatchImportPreview(val records: List<MatchRecord>, val newCount: Int, val duplicateWarnings: Int, val warnings: List<String>, val originals: Map<String, MatchRecord> = emptyMap())

/** Association is explicit and conservative. Capture-observed time is not a known match start. */
fun canAssociateMatch(record: MatchRecord, session: RankedSession): Boolean = record.identity != null &&
    record.identity == session.identity && record.mode == MatchMode.RANKED && session.endedAtSeconds != null &&
    record.timestampSeconds in session.startedAtSeconds..session.endedAtSeconds && record.quality == MatchQuality.USER_CONFIRMED

data class MatchMetric(val sum: Long, val count: Int) { val average: Double? get() = if (count == 0) null else sum.toDouble() / count }
data class MatchAggregate(val total: Int, val automatic: Int, val confirmed: Int, val kills: MatchMetric,
    val assists: MatchMetric, val damage: MatchMetric, val placement: MatchMetric, val rp: MatchMetric)
fun aggregateMatches(records: List<MatchRecord>, includeAutomatic: Boolean = false): MatchAggregate {
    val eligible = records.filter { it.quality == MatchQuality.USER_CONFIRMED || (includeAutomatic && it.quality == MatchQuality.AUTO_ACCEPTED) }
    fun metric(field: (MatchRecord) -> Int?) = eligible.mapNotNull(field).let { MatchMetric(it.sumOf(Int::toLong), it.size) }
    return MatchAggregate(eligible.size, eligible.count { it.quality == MatchQuality.AUTO_ACCEPTED }, eligible.count { it.quality == MatchQuality.USER_CONFIRMED },
        metric { it.kills }, metric { it.assists }, metric { it.damage }, metric { it.placement }, metric { it.rpDelta })
}
fun matchGroups(records: List<MatchRecord>, byLegend: Boolean, includeAutomatic: Boolean = false): Map<String, MatchAggregate> =
    records.filter { if (byLegend) it.legend != null else it.map != null }.groupBy { (if (byLegend) it.legend else it.map)!! }
        .mapValues { aggregateMatches(it.value, includeAutomatic) }
