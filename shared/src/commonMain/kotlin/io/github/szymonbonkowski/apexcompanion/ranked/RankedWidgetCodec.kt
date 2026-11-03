package io.github.szymonbonkowski.apexcompanion.ranked

import io.github.szymonbonkowski.apexcompanion.maps.DataState
import kotlinx.datetime.*
import kotlinx.serialization.json.*
import kotlin.time.Instant

/** Compact allowlisted projection: no name, UID, raw response, access or retry credentials. */
object RankedWidgetCodec {
    fun encode(state: RankedRepositoryState, nowSeconds: Long, today: LocalDate,
               zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val boundaries = (listOf(nowSeconds) +
            listOfNotNull(state.profile?.observedAtSeconds?.plus(PROFILE_FRESH_SECONDS)) +
            state.predator.map { it.observedAtSeconds + PREDATOR_FRESH_SECONDS } +
            (1..8).map { today.plus(it, DateTimeUnit.DAY).atStartOfDayIn(zone).epochSeconds })
            .filter { it >= nowSeconds }.distinct().sorted()
        return buildJsonObject {
            put("schemaVersion", 1)
            put("generatedAtEpochSeconds", nowSeconds)
            put("zone", zone.id)
            putJsonArray("entries") {
                boundaries.forEachIndexed { index, at ->
                    val date = if (at == nowSeconds) today else Instant.fromEpochSeconds(at).toLocalDateTime(zone).date
                    val view = rankedView(state, at, date)
                    add(buildJsonObject {
                        put("effectiveAtEpochSeconds", at)
                        put("validUntilEpochSeconds", boundaries.getOrNull(index + 1)?.let(::JsonPrimitive) ?: JsonPrimitive(at + 86400))
                        put("state", view.state.name)
                        put("source", view.source)
                        put("observedAtEpochSeconds", view.observedAtSeconds?.let(::JsonPrimitive) ?: JsonNull)
                        put("rankLabel", if (state.useManual || state.profile == null) "Manual RP" else
                            view.snapshot?.let { listOfNotNull(it.rankName, it.division?.toString()).joinToString(" ").ifBlank { "Rank unavailable" } } ?: "Rank unavailable")
                        put("platform", state.profile?.identity?.platform?.code?.let(::JsonPrimitive) ?: JsonNull)
                        put("currentRp", view.currentRp?.let(::JsonPrimitive) ?: JsonNull)
                        put("targetRp", state.goal?.targetRp?.let(::JsonPrimitive) ?: JsonNull)
                        put("remainingRp", view.progress?.remainingRp?.let(::JsonPrimitive) ?: JsonNull)
                        put("rpPerDay", view.progress?.rpPerDay?.let(::JsonPrimitive) ?: JsonNull)
                        put("fraction", view.progress?.fraction ?: 0f)
                        put("goalStatus", view.progress?.status?.name?.let(::JsonPrimitive) ?: JsonNull)
                        putJsonArray("predator") {
                            state.predator.forEach { threshold -> add(buildJsonObject {
                                val fresh = state.predatorError == null && at >= threshold.observedAtSeconds &&
                                    at < threshold.observedAtSeconds + PREDATOR_FRESH_SECONDS
                                put("platform", threshold.platform.code)
                                put("state", if (fresh) "LIVE" else "STALE")
                                put("rp", threshold.rp)
                                put("remainingRp", view.currentRp?.takeIf { view.state != DataState.MANUAL && state.profile?.identity?.platform == threshold.platform }?.let { (threshold.rp - it).coerceAtLeast(0) }?.let(::JsonPrimitive) ?: JsonNull)
                                put("masterPopulation", threshold.masterPopulation?.let(::JsonPrimitive) ?: JsonNull)
                                put("observedAtEpochSeconds", threshold.observedAtSeconds)
                            }) }
                        }
                    })
                }
            }
        }.toString()
    }

    /** Select KMP-precomputed boundaries, then defensively downgrade aged snapshots. */
    fun decode(value: String?, nowSeconds: Long): RankedWidgetView = runCatching {
        require(value != null && value.length <= 131072)
        val root = Json.parseToJsonElement(value).jsonObject
        require(root["schemaVersion"]?.jsonPrimitive?.intOrNull == 1)
        val generated = root["generatedAtEpochSeconds"]!!.jsonPrimitive.long
        require(generated in 0..nowSeconds)
        val entries = root["entries"]!!.jsonArray
        require(entries.size in 1..32)
        val times = entries.map { it.jsonObject["effectiveAtEpochSeconds"]!!.jsonPrimitive.long }
        require(times.zipWithNext().all { (a,b) -> a < b })
        val entry = entries.last { it.jsonObject["effectiveAtEpochSeconds"]!!.jsonPrimitive.long <= nowSeconds }.jsonObject
        val expiry = entry["validUntilEpochSeconds"]!!.jsonPrimitive.long
        require(expiry > entry["effectiveAtEpochSeconds"]!!.jsonPrimitive.long)
        val source = entry["source"]!!.jsonPrimitive.content.also { require(it.length in 1..160) }
        val rank = entry["rankLabel"]!!.jsonPrimitive.content.also { require(it.length <= 160) }
        var dataState = DataState.valueOf(entry["state"]!!.jsonPrimitive.content)
        require(dataState != DataState.DEMO)
        val observed = entry.optionalLong("observedAtEpochSeconds")
        if (dataState == DataState.LIVE && (observed == null || nowSeconds < observed || nowSeconds >= observed + PROFILE_FRESH_SECONDS)) dataState = DataState.STALE
        val current = entry.optionalRp("currentRp")
        if (current == null) dataState = DataState.UNAVAILABLE
        val predators = entry["predator"]!!.jsonArray.also { require(it.size <= ApexPlatform.entries.size) }.map { item ->
            val p = item.jsonObject
            val platform = ApexPlatform.entries.first { it.code == p["platform"]!!.jsonPrimitive.content }
            val at = p["observedAtEpochSeconds"]!!.jsonPrimitive.long.also { require(it >= 0) }
            val fresh = p["state"]!!.jsonPrimitive.content == "LIVE" && nowSeconds >= at && nowSeconds < at + PREDATOR_FRESH_SECONDS
            RankedPredatorWidget(platform, p.optionalRp("rp")!!, p.optionalRp("remainingRp"), p.optionalLong("masterPopulation"), at, if (fresh) DataState.LIVE else DataState.STALE)
        }
        require(predators.map { it.platform }.distinct().size == predators.size)
        RankedWidgetView(dataState, source, observed, rank, entry["platform"]?.jsonPrimitive?.contentOrNull,
            current, entry.optionalRp("targetRp"), entry.optionalRp("remainingRp"),
            if (nowSeconds < expiry) entry.optionalRp("rpPerDay") else null,
            entry["fraction"]!!.jsonPrimitive.float.also { require(it.isFinite() && it in 0f..1f) },
            entry["goalStatus"]?.jsonPrimitive?.contentOrNull, predators, nowSeconds >= expiry)
    }.getOrElse { RankedWidgetView() }

    private fun JsonObject.optionalLong(key: String): Long? = get(key)?.takeUnless { it is JsonNull }?.jsonPrimitive?.long
    private fun JsonObject.optionalRp(key: String): Long? = optionalLong(key)?.also { require(it in 0..MAX_RP) }
}

data class RankedPredatorWidget(val platform: ApexPlatform, val rp: Long, val remainingRp: Long?, val masterPopulation: Long?, val observedAtSeconds: Long, val state: DataState)
data class RankedWidgetView(
    val state: DataState = DataState.UNAVAILABLE, val source: String = "Apex Legends Status / local user",
    val observedAtSeconds: Long? = null, val rankLabel: String = "Rank unavailable", val platform: String? = null,
    val currentRp: Long? = null, val targetRp: Long? = null, val remainingRp: Long? = null,
    val rpPerDay: Long? = null, val fraction: Float = 0f, val goalStatus: String? = null,
    val predator: List<RankedPredatorWidget> = emptyList(), val expiredCalculation: Boolean = false,
)
