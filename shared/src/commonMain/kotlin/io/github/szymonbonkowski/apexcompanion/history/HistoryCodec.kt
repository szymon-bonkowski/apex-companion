package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*

/** Explicit allowlist shared by both platforms. No raw provider/current-state/cache encoding reused. */
object HistoryCodec {
    fun encode(record: HistoryRecord): String = recordJson(record).toString().also { require(it.encodeToByteArray().size <= HISTORY_MAX_BYTES) }
    fun decode(value: String, now: Long): HistoryRecord = parseRecord(root(value), now)
    fun export(backup: HistoryBackup): String = buildJsonObject {
        put("format", "APEX_COMPANION_PRIVATE_BACKUP"); put("schemaVersion", 1)
        put("batchId", backup.batchId); put("exportedAtSeconds", backup.exportedAtSeconds)
        put("data", recordJson(backup.record.copy(recording = false, currentContextId = null, importedBatchIds = emptyList())))
    }.toString().also { require(it.encodeToByteArray().size <= HISTORY_MAX_BYTES) }
    fun import(value: String, now: Long): HistoryBackup {
        val r = root(value)
        require(r.text("format") == "APEX_COMPANION_PRIVATE_BACKUP")
        require(r.keys == setOf("format", "schemaVersion", "batchId", "exportedAtSeconds", "data"))
        val time = r.number("exportedAtSeconds"); require(time in 1..now)
        return HistoryBackup(r.text("batchId").also { require(validHistoryId(it)) }, time,
            parseRecord(r.getValue("data").jsonObject, time))
    }
    private fun recordJson(r: HistoryRecord) = buildJsonObject {
        put("schemaVersion", 1); put("recording", r.recording); put("nextSequence", r.nextSequence)
        nullable("currentContextId", r.currentContextId)
        put("settings", r.settings?.let { s -> buildJsonObject {
            nullable("manualRp", s.manualRp); put("useManual", s.useManual); nullable("manualUpdatedAtSeconds", s.manualUpdatedAtSeconds)
            put("goal", s.goal?.let { g -> buildJsonObject {
                put("targetRp", g.targetRp); put("baselineRp", g.baselineRp); nullable("deadline", g.deadline?.toString()); put("completed", g.completed)
            } } ?: JsonNull)
        } } ?: JsonNull)
        put("contexts", buildJsonArray { r.contexts.sortedBy { it.id }.forEach { c -> add(buildJsonObject {
            put("id", c.id); put("identity", identityJson(c.identity)); put("source", c.source.name); put("mode", c.mode.name)
            put("season", c.season); put("split", c.split); put("confirmedAtSeconds", c.confirmedAtSeconds); nullable("providerSeason", c.providerSeason)
        }) } })
        put("observations", buildJsonArray { r.observations.sortedWith(observationOrder).forEach { o -> add(buildJsonObject {
            put("id", o.id); put("identity", identityJson(o.identity)); put("source", o.source.name); put("mode", o.mode.name)
            put("rp", o.rp); put("observedAtSeconds", o.observedAtSeconds); nullable("season", o.season); nullable("split", o.split)
            nullable("contextId", o.contextId); put("verification", o.verification.name); put("quality", o.quality.name)
        }) } })
        put("sessions", buildJsonArray { r.sessions.sortedBy { it.id }.forEach { s -> add(buildJsonObject {
            put("id", s.id); put("identity", identityJson(s.identity)); put("source", s.source.name); put("mode", s.mode.name)
            nullable("contextId", s.contextId); put("startedAtSeconds", s.startedAtSeconds); nullable("endedAtSeconds", s.endedAtSeconds)
            nullable("startObservationId", s.startObservationId); nullable("endObservationId", s.endObservationId); put("interrupted", s.interrupted)
            put("result", sessionResultJson(s, r.observations))
        }) } })
        put("importedBatchIds", buildJsonArray { r.importedBatchIds.sorted().forEach { add(it) } })
    }
    /** Versioned derived result is durable too, and is validated against immutable anchors. */
    private fun sessionResultJson(session: RankedSession, observations: List<RpObservation>): JsonObject {
        val result = sessionResult(session, observations)
        return buildJsonObject {
            put("formulaVersion", 1); put("classification", result.classification.name)
            nullable("deltaRp", result.deltaRp); nullable("windowStartSeconds", result.windowStartSeconds); nullable("windowEndSeconds", result.windowEndSeconds)
            put("timeComplete", session.endedAtSeconds != null); put("rpComplete", result.deltaRp != null)
            put("explanation", result.explanation)
        }
    }
    private fun identityJson(i: HistoryIdentity?): JsonElement = i?.let { buildJsonObject { put("uid", it.uid); put("platform", it.platform.name) } } ?: JsonNull
    private fun identity(r: JsonObject): HistoryIdentity? = r.optionalObject("identity")?.let {
        require(it.keys == setOf("uid", "platform")); HistoryIdentity(it.text("uid"), ApexPlatform.valueOf(it.text("platform")))
    }
    private fun root(value: String): JsonObject {
        require(value.length <= HISTORY_MAX_BYTES && value.encodeToByteArray().size <= HISTORY_MAX_BYTES)
        // Bound nesting before the JSON parser to prevent adversarial stack exhaustion.
        var depth = 0; var quoted = false; var escape = false
        for (c in value) {
            if (quoted) { if (escape) escape = false else if (c == '\\') escape = true else if (c == '"') quoted = false }
            else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 16) }; '}', ']' -> { depth--; require(depth >= 0) } }
        }
        require(depth == 0 && !quoted)
        return Json.parseToJsonElement(value).jsonObject.also { require(it.number("schemaVersion") == 1L) }
    }
    private fun parseRecord(r: JsonObject, now: Long): HistoryRecord {
        require(r.keys == setOf("schemaVersion", "recording", "nextSequence", "currentContextId", "settings", "contexts", "observations", "sessions", "importedBatchIds"))
        require(r.number("schemaVersion") == 1L)
        val settings = r.optionalObject("settings")?.let { s ->
            require(s.keys == setOf("manualRp", "useManual", "manualUpdatedAtSeconds", "goal"))
            PortableRankedSettings(s.optionalObject("goal")?.let { g ->
                require(g.keys == setOf("targetRp", "baselineRp", "deadline", "completed"))
                RankGoal(g.number("targetRp"), g.number("baselineRp"), g.optionalText("deadline")?.let(LocalDate::parse), g.flag("completed"))
            }, s.optionalNumber("manualRp"), s.flag("useManual"), s.optionalNumber("manualUpdatedAtSeconds"))
        }
        val contexts = r.array("contexts", HISTORY_MAX_SESSIONS).map { item -> item.jsonObject.let { c ->
            require(c.keys == setOf("id", "identity", "source", "mode", "season", "split", "confirmedAtSeconds", "providerSeason"))
            RankedContext(c.text("id"), identity(c), ObservationSource.valueOf(c.text("source")), c.text("season"), c.text("split"), c.number("confirmedAtSeconds"), c.optionalText("providerSeason"), RankedMode.valueOf(c.text("mode")))
        } }
        val observations = r.array("observations", HISTORY_MAX_OBSERVATIONS).map { item -> item.jsonObject.let { o ->
            require(o.keys == setOf("id", "identity", "source", "mode", "rp", "observedAtSeconds", "season", "split", "contextId", "verification", "quality"))
            RpObservation(o.text("id"), identity(o), ObservationSource.valueOf(o.text("source")), o.number("rp"), o.number("observedAtSeconds"),
                o.optionalText("season"), o.optionalText("split"), o.optionalText("contextId"), ContextVerification.valueOf(o.text("verification")), ObservationQuality.valueOf(o.text("quality")), RankedMode.valueOf(o.text("mode")))
        } }
        val sessions = r.array("sessions", HISTORY_MAX_SESSIONS).map { item -> item.jsonObject.let { s ->
            require(s.keys == setOf("id", "identity", "source", "mode", "contextId", "startedAtSeconds", "endedAtSeconds", "startObservationId", "endObservationId", "interrupted", "result"))
            RankedSession(s.text("id"), identity(s), ObservationSource.valueOf(s.text("source")), s.optionalText("contextId"), s.number("startedAtSeconds"), s.optionalNumber("endedAtSeconds"),
                s.optionalText("startObservationId"), s.optionalText("endObservationId"), RankedMode.valueOf(s.text("mode")), s.flag("interrupted"))
        } }
        val batches = r.array("importedBatchIds", HISTORY_MAX_BATCHES).map { it.jsonPrimitive.also { p -> require(p.isString) }.content }
        val result = HistoryRecord(r.flag("recording"), settings, observations.sortedWith(observationOrder), sessions, contexts,
            r.optionalText("currentContextId"), batches, r.number("nextSequence"))
        validate(result, now)
        r.getValue("sessions").jsonArray.forEachIndexed { index, item ->
            require(item.jsonObject.getValue("result") == sessionResultJson(sessions[index], observations)) { "Session summary does not match its evidence." }
        }
        return result
    }
    fun validate(r: HistoryRecord, now: Long) {
        require(r.observations.size <= HISTORY_MAX_OBSERVATIONS && r.sessions.size <= HISTORY_MAX_SESSIONS && r.contexts.size <= HISTORY_MAX_SESSIONS && r.importedBatchIds.size <= HISTORY_MAX_BATCHES)
        require(r.nextSequence in 1 until Long.MAX_VALUE)
        require(r.observations.map { it.id }.distinct().size == r.observations.size)
        require(r.contexts.map { it.id }.distinct().size == r.contexts.size)
        require(r.sessions.map { it.id }.distinct().size == r.sessions.size)
        require(r.importedBatchIds.distinct().size == r.importedBatchIds.size && r.importedBatchIds.all(::validHistoryId))
        require(r.sessions.count { it.endedAtSeconds == null } <= 1)
        require(r.currentContextId == null || r.contexts.any { it.id == r.currentContextId })
        r.settings?.manualUpdatedAtSeconds?.let { require(it <= now) }
        r.contexts.forEach { require(it.confirmedAtSeconds <= now) }
        r.observations.forEach { o ->
            require(o.observedAtSeconds <= now)
            val c = r.contexts.firstOrNull { it.id == o.contextId }
            if (o.verification == ContextVerification.USER_CONFIRMED) requireNotNull(c)
            if (c != null) {
                require(c.identity == o.identity && c.source == o.source && c.mode == o.mode && c.season == o.season && c.split == o.split && o.observedAtSeconds >= c.confirmedAtSeconds)
            }
        }
        r.sessions.forEach { s ->
            require(s.startedAtSeconds <= now && (s.endedAtSeconds == null || s.endedAtSeconds <= now))
            s.contextId?.let { id ->
                val c = r.contexts.firstOrNull { it.id == id }
                if (c != null) require(c.identity == s.identity && c.source == s.source && c.mode == s.mode && c.confirmedAtSeconds <= s.startedAtSeconds)
                else require(r.observations.any { it.contextId == id && it.verification == ContextVerification.UPSTREAM && it.identity == s.identity && it.source == s.source && it.mode == s.mode })
            }
            listOfNotNull(s.startObservationId, s.endObservationId).forEach { id ->
                val o = r.observations.single { it.id == id }
                require(o.identity == s.identity && o.source == s.source && o.mode == s.mode)
            }
            val start = r.observations.firstOrNull { it.id == s.startObservationId }
            val end = r.observations.firstOrNull { it.id == s.endObservationId }
            require(start == null || (start.observedAtSeconds in (s.startedAtSeconds - PROFILE_FRESH_SECONDS)..s.startedAtSeconds && start.contextId == s.contextId && start.quality == ObservationQuality.VALID))
            require(end == null || (s.endedAtSeconds != null && end.observedAtSeconds <= s.endedAtSeconds && end.observedAtSeconds > s.startedAtSeconds))
        }
    }
}
private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.also { require(it.isString) }.content
private fun JsonObject.optionalText(key: String) = getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.long
private fun JsonObject.optionalNumber(key: String) = getValue(key).takeUnless { it is JsonNull }?.jsonPrimitive?.also { require(!it.isString) }?.long
private fun JsonObject.flag(key: String) = getValue(key).jsonPrimitive.also { require(!it.isString) }.boolean
private fun JsonObject.optionalObject(key: String) = getValue(key).takeUnless { it is JsonNull }?.jsonObject
private fun JsonObject.array(key: String, max: Int) = getValue(key).jsonArray.also { require(it.size <= max) }
private fun JsonObjectBuilder.nullable(key: String, value: Any?) { put(key, when(value) { null -> JsonNull; is String -> JsonPrimitive(value); is Long -> JsonPrimitive(value); else -> error("Unsupported field") }) }
