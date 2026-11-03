package io.github.szymonbonkowski.apexcompanion.matches

import io.github.szymonbonkowski.apexcompanion.history.*
import io.github.szymonbonkowski.apexcompanion.ranked.ApexPlatform
import kotlinx.serialization.json.*

/** Strict allowlisted wire/store encoding. Private credentials and screenshots have no representation. */
object MatchCodec {
    private val recordKeys = setOf("schemaVersion", "id", "identity", "mode", "map", "legend", "kills", "assists", "damage", "placement", "rpDelta", "timestampSeconds", "timestampSource", "source", "quality", "evidenceEventId", "parserVersion", "fieldConfidence", "revision")
    fun recordJson(r: MatchRecord): JsonObject = buildJsonObject {
        put("schemaVersion", 1); put("id", r.id)
        put("identity", r.identity?.let { buildJsonObject { put("uid", it.uid); put("platform", it.platform.name) } } ?: JsonNull)
        put("mode", r.mode.name); optional("map", r.map); optional("legend", r.legend)
        optional("kills", r.kills); optional("assists", r.assists); optional("damage", r.damage)
        optional("placement", r.placement); optional("rpDelta", r.rpDelta); put("timestampSeconds", r.timestampSeconds)
        put("timestampSource", r.timestampSource.name); put("source", r.source.name); put("quality", r.quality.name)
        optional("evidenceEventId", r.evidenceEventId); optional("parserVersion", r.parserVersion)
        put("fieldConfidence", buildJsonObject { r.fieldConfidence.toSortedMap().forEach { (k,v) -> put(k,v) } }); put("revision", r.revision)
    }
    fun decodeRecord(value: String, now: Long): MatchRecord = parseRecord(root(value, MATCH_BATCH_BYTES), now)
    fun parseRecord(o: JsonObject, now: Long): MatchRecord {
        require(o.keys == recordKeys && o.long("schemaVersion") == 1L)
        val identity = o.getValue("identity").takeUnless { it == JsonNull }?.jsonObject?.let {
            require(it.keys == setOf("uid", "platform")); HistoryIdentity(it.text("uid"), ApexPlatform.valueOf(it.text("platform")))
        }
        fun integer(key: String): Int? = o.getValue(key).takeUnless { it == JsonNull }?.let {
            it.jsonPrimitive.also { p -> require(!p.isString) }.int
        }
        val record = MatchRecord(o.text("id"), identity, MatchMode.valueOf(o.text("mode")), o.nullText("map"), o.nullText("legend"),
            integer("kills"), integer("assists"), integer("damage"), integer("placement"), integer("rpDelta"), o.long("timestampSeconds"),
            MatchTimestampSource.valueOf(o.text("timestampSource")), MatchSource.valueOf(o.text("source")), MatchQuality.valueOf(o.text("quality")),
            o.nullText("evidenceEventId"), o.nullText("parserVersion"), o.getValue("fieldConfidence").jsonObject.mapValues {
                it.value.jsonPrimitive.also { p -> require(!p.isString) }.double
            }, integer("revision") ?: error("Revision required"))
        require(record.timestampSeconds <= now) { "Future result timestamp." }
        return record
    }
    fun encode(store: MatchStoreRecord): String {
        validate(store)
        return buildJsonObject {
            put("schemaVersion", 1); put("records", recordsJson(store.records)); put("originals", recordsJson(store.originals.values.toList()))
            put("syncTimes", buildJsonObject { store.syncTimes.toSortedMap().forEach { (k,v) -> put(k,v) } })
            put("cursors", buildJsonObject { store.cursors.toSortedMap().forEach { (k,v) -> put(k,v) } })
            put("deletedIds", buildJsonArray { store.deletedIds.sorted().forEach { add(it) } })
            put("deletedEvents", buildJsonArray { store.deletedEvents.sorted().forEach { add(it) } })
        }.toString().also { require(it.encodeToByteArray().size <= MATCH_MAX_BYTES) }
    }
    fun decode(value: String, now: Long): MatchStoreRecord {
        val o = root(value, MATCH_MAX_BYTES)
        require(o.keys == setOf("schemaVersion", "records", "originals", "cursors", "deletedIds", "deletedEvents", "syncTimes") && o.long("schemaVersion") == 1L)
        val originals = parseRecords(o.getValue("originals"), now)
        val store = MatchStoreRecord(parseRecords(o.getValue("records"), now), o.getValue("cursors").jsonObject.mapValues {
            it.value.jsonPrimitive.also { p -> require(!p.isString) }.long
        }, stringSet(o.getValue("deletedIds")), stringSet(o.getValue("deletedEvents")), originals.associateBy { it.id }, o.getValue("syncTimes").jsonObject.mapValues {
            it.value.jsonPrimitive.also { p -> require(!p.isString) }.long.also { time -> require(time in 1..now) }
        })
        require(originals.size == store.originals.size); validate(store); return store
    }
    fun export(records: List<MatchRecord>, originals: List<MatchRecord> = emptyList()): String = buildJsonObject {
        put("format", "APEX_COMPANION_MATCH_BACKUP"); put("schemaVersion", 1); put("records", recordsJson(records)); put("originals", recordsJson(originals))
    }.toString().also { require(it.encodeToByteArray().size <= MATCH_MAX_BYTES) }
    fun import(value: String, now: Long): MatchStoreRecord {
        val o = root(value, MATCH_MAX_BYTES)
        require(o.keys == setOf("format", "schemaVersion", "records", "originals") && o.text("format") == "APEX_COMPANION_MATCH_BACKUP" && o.long("schemaVersion") == 1L)
        val originals = parseRecords(o.getValue("originals"), now)
        return MatchStoreRecord(parseRecords(o.getValue("records"), now), originals = originals.associateBy { it.id }).also {
            require(originals.size == it.originals.size); validate(it)
        }
    }
    fun decodeBatch(value: String, now: Long): MatchSyncBatch {
        val o = root(value, MATCH_BATCH_BYTES)
        require(o.keys == setOf("schemaVersion", "companionId", "cursor", "records") && o.long("schemaVersion") == 1L)
        return MatchSyncBatch(o.text("companionId").also { require(validMatchId(it)) }, o.long("cursor").also { require(it >= 0) }, parseRecords(o.getValue("records"), now, 100))
    }
    fun validate(store: MatchStoreRecord) {
        require(store.records.size <= MATCH_MAX_RECORDS && store.originals.size <= MATCH_MAX_RECORDS && store.cursors.size <= 8)
        require(store.deletedIds.size <= MATCH_MAX_RECORDS && store.deletedEvents.size <= MATCH_MAX_RECORDS)
        require(store.cursors.all { validMatchId(it.key) && it.value >= 0 })
        require(store.syncTimes.size <= 8 && store.syncTimes.all { it.key in store.cursors && it.value in 1..MAX_HISTORY_TIME })
        require((store.deletedIds + store.deletedEvents).all(::validMatchId))
        require(store.records.map { it.id }.distinct().size == store.records.size)
        val events = store.records.mapNotNull { it.evidenceEventId }; require(events.distinct().size == events.size)
        require(store.records.none { it.id in store.deletedIds || it.evidenceEventId in store.deletedEvents })
        store.originals.forEach { (id, original) ->
            val edited = store.records.single { it.id == id }
            require(original.revision == 0 && original.source != MatchSource.MANUAL && edited.revision > 0 && sameEvidence(edited, original))
        }
        require(store.records.all { it.revision == 0 || it.source == MatchSource.MANUAL || it.id in store.originals })
    }
    fun sameEvidence(a: MatchRecord, b: MatchRecord) = a.id == b.id && a.identity == b.identity && a.source == b.source && a.evidenceEventId == b.evidenceEventId && a.parserVersion == b.parserVersion
    private fun recordsJson(records: List<MatchRecord>) = buildJsonArray { records.sortedBy { it.id }.forEach { add(recordJson(it)) } }
    private fun parseRecords(value: JsonElement, now: Long, maximum: Int = MATCH_MAX_RECORDS): List<MatchRecord> = value.jsonArray.also { require(it.size <= maximum) }.map { parseRecord(it.jsonObject, now) }.also { records ->
        require(records.map { it.id }.distinct().size == records.size)
        val events = records.mapNotNull { it.evidenceEventId }; require(events.distinct().size == events.size)
    }
    private fun stringSet(value: JsonElement): Set<String> = value.jsonArray.also { require(it.size <= MATCH_MAX_RECORDS) }.map { it.jsonPrimitive.also { p -> require(p.isString) }.content }.let { require(it.distinct().size == it.size); it.toSet() }
    private fun root(value: String, maximum: Int): JsonObject {
        require(value.length <= maximum && value.encodeToByteArray().size <= maximum)
        // Reject duplicate object keys, including escaped spellings, before map parsing
        // could erase contradictory values. Json still validates the complete grammar.
        data class Scope(val objectScope: Boolean, val keys: MutableSet<String> = mutableSetOf(), var expectsKey: Boolean = true)
        val scopes = mutableListOf<Scope>()
        var quoted = false; var escape = false; var stringStart = 0
        for ((index, c) in value.withIndex()) {
            if (quoted) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') {
                    quoted = false
                    scopes.lastOrNull()?.takeIf { it.objectScope && it.expectsKey }?.let { scope ->
                        val key = Json.parseToJsonElement(value.substring(stringStart, index + 1)).jsonPrimitive.content
                        require(scope.keys.add(key)) { "Duplicate JSON key." }
                        scope.expectsKey = false
                    }
                }
            } else when (c) {
                '"' -> { quoted = true; stringStart = index }
                '{', '[' -> { scopes.add(Scope(c == '{')); require(scopes.size <= 12) }
                '}', ']' -> { require(scopes.isNotEmpty()); scopes.removeAt(scopes.lastIndex) }
                ',' -> scopes.lastOrNull()?.takeIf { it.objectScope }?.let { it.expectsKey = true }
            }
        }
        require(scopes.isEmpty() && !quoted)
        return Json.parseToJsonElement(value).jsonObject
    }
    private fun JsonObject.text(k: String) = getValue(k).jsonPrimitive.also { require(it.isString) }.content
    private fun JsonObject.nullText(k: String) = getValue(k).takeUnless { it == JsonNull }?.jsonPrimitive?.also { require(it.isString) }?.content
    private fun JsonObject.long(k: String) = getValue(k).jsonPrimitive.also { require(!it.isString) }.long
    private fun JsonObjectBuilder.optional(k: String, v: String?) = put(k, v?.let(::JsonPrimitive) ?: JsonNull)
    private fun JsonObjectBuilder.optional(k: String, v: Int?) = put(k, v?.let(::JsonPrimitive) ?: JsonNull)
}
