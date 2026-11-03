package io.github.szymonbonkowski.apexcompanion.ranked

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

/** Normalized allowlist only. Global Predator data has a separate record from the private profile. */
object RankedRecordCodec {
    fun encode(s: RankedRepositoryState): String = buildJsonObject {
        put("schemaVersion", 1)
        put("profile", s.profile?.let { p -> buildJsonObject {
            put("uid", p.identity.uid); put("platform", p.identity.platform.name); put("name", p.identity.name)
            putNullable("rp", p.rp); putNullable("rankName", p.rankName); putNullable("division", p.division)
            put("observedAtSeconds", p.observedAtSeconds); putNullable("season", p.season); putNullable("split", p.split)
            putNullable("ladderPosition", p.ladderPosition); putNullable("level", p.level)
        } } ?: JsonNull)
        put("goal", s.goal?.let { g -> buildJsonObject {
            put("targetRp", g.targetRp); put("baselineRp", g.baselineRp)
            putNullable("deadline", g.deadline?.toString()); put("completed", g.completed)
        } } ?: JsonNull)
        putNullable("manualRp", s.manualRp); put("useManual", s.useManual)
        putNullable("manualUpdatedAtSeconds", s.manualUpdatedAtSeconds)
        putNullable("error", s.error?.name); putNullable("retryAtSeconds", s.retryAtSeconds)
        putNullable("lastAttemptSeconds", s.lastAttemptSeconds); put("failureCount", s.failureCount); put("forbidden", s.forbidden)
    }.toString()
    fun decode(value: String): RankedRepositoryState {
        val r = root(value)
        val profile = r.obj("profile")?.let { p -> RankedSnapshot(
            PlayerIdentity(p.string("uid")!!, ApexPlatform.valueOf(p.string("platform")!!), p.string("name")!!),
            p.long("rp"), p.string("rankName")?.also { require(it.length <= 100) }, p.int("division"),
            p.long("observedAtSeconds")!!.also { require(it >= 0) },
            p.string("season")?.also { require(it.length <= 100) }, p.string("split")?.also { require(it.length <= 100) },
            p.long("ladderPosition")?.also { require(it >= 0) }, p.int("level")?.also { require(it >= 0) }) }
        val goal = r.obj("goal")?.let { g -> RankGoal(g.long("targetRp")!!, g.long("baselineRp")!!,
            g.string("deadline")?.let(LocalDate::parse), g.boolean("completed")) }
        return RankedRepositoryState(profile = profile, goal = goal,
            manualRp = r.long("manualRp")?.also { require(it in 0..MAX_RP) }, useManual = r.boolean("useManual"),
            manualUpdatedAtSeconds = r.long("manualUpdatedAtSeconds"), error = r.string("error")?.let(RankedError::valueOf),
            retryAtSeconds = r.long("retryAtSeconds"), lastAttemptSeconds = r.long("lastAttemptSeconds"),
            failureCount = (r.int("failureCount") ?: 0).also { require(it in 0..10) }, forbidden = r.boolean("forbidden"))
    }
    fun encodePredator(s: RankedRepositoryState): String = buildJsonObject {
        put("schemaVersion", 1)
        put("thresholds", buildJsonArray { s.predator.forEach { p -> add(buildJsonObject {
            put("platform", p.platform.name); put("rp", p.rp); put("observedAtSeconds", p.observedAtSeconds)
            putNullable("masterPopulation", p.masterPopulation)
        }) } })
        putNullable("error", s.predatorError?.name); putNullable("retryAtSeconds", s.predatorRetryAtSeconds)
        putNullable("lastAttemptSeconds", s.predatorLastAttemptSeconds)
        put("failureCount", s.predatorFailureCount); put("forbidden", s.predatorForbidden)
    }.toString()
    fun withPredator(s: RankedRepositoryState, value: String): RankedRepositoryState {
        val r = root(value)
        val values = (r["thresholds"] as? JsonArray ?: error("Invalid thresholds")).also { require(it.size <= 4) }.map { item ->
            val p = item.jsonObject
            PredatorThreshold(ApexPlatform.valueOf(p.string("platform")!!), p.long("rp")!!,
                p.long("observedAtSeconds")!!.also { require(it >= 0) }, p.long("masterPopulation"))
        }
        require(values.map { it.platform }.distinct().size == values.size)
        return s.copy(predator = values, predatorError = r.string("error")?.let(RankedError::valueOf),
            predatorRetryAtSeconds = r.long("retryAtSeconds"), predatorLastAttemptSeconds = r.long("lastAttemptSeconds"),
            predatorFailureCount = (r.int("failureCount") ?: 0).also { require(it in 0..10) }, predatorForbidden = r.boolean("forbidden"))
    }
    private fun root(value: String): JsonObject {
        require(value.length <= 65536)
        return Json.parseToJsonElement(value).jsonObject.also { require(it.int("schemaVersion") == 1) }
    }
}
private fun JsonObject.obj(key: String): JsonObject? = this[key]?.takeUnless { it is JsonNull }?.jsonObject
private fun JsonObject.string(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
private fun JsonObject.long(key: String): Long? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.let { require(!it.isString); requireNotNull(it.longOrNull) }
private fun JsonObject.int(key: String): Int? = long(key)?.let { require(it in Int.MIN_VALUE..Int.MAX_VALUE); it.toInt() }
private fun JsonObject.boolean(key: String): Boolean = this[key]?.jsonPrimitive?.let {
    require(!it.isString)
    requireNotNull(it.booleanOrNull)
} ?: false
private fun JsonObjectBuilder.putNullable(key: String, value: Any?) {
    put(key, when (value) { null -> JsonNull; is String -> JsonPrimitive(value); is Number -> JsonPrimitive(value); else -> error("Unsupported value") })
}
