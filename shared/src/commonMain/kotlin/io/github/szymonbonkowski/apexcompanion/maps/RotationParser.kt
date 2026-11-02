package io.github.szymonbonkowski.apexcompanion.maps

import kotlinx.serialization.json.*

/** v2 shape checked against an authorized response; tests use independently synthesized payloads. */
object ApexRotationParser {
    fun parse(body: String, fetchedAtSeconds: Long): RotationCache {
        require(body.length <= 512_000) { "Response too large" }
        val root = Json.parseToJsonElement(body) as? JsonObject ?: error("Expected map rotation object")
        val playlists = root.entries.mapNotNull { (id, value) ->
            val mode = value as? JsonObject ?: return@mapNotNull null
            if (!mode.containsKey("current") && !mode.containsKey("next") && !mode.containsKey("upcoming")) return@mapNotNull null
            runCatching {
                val fields = listOfNotNull(mode["current"], mode["next"]) +
                    ((mode["upcoming"] as? JsonArray)?.toList().orEmpty())
                val segments = fields.mapNotNull { element ->
                    if (element is JsonNull) null else runCatching { parseSegment(element) }.getOrNull()
                }.distinct().sortedBy { it.startSeconds }
                require(segments.isNotEmpty())
                PlaylistSchedule(id, playlistLabel(id), segments, observedAtSeconds = fetchedAtSeconds)
            }.getOrNull()
        }
        require(playlists.isNotEmpty()) { "No valid rotation segments" }
        return RotationCache(fetchedAtSeconds, playlists)
    }

    private fun parseSegment(element: JsonElement): MapSegment {
        val obj = element as? JsonObject ?: error("Expected segment")
        val name = (obj["map"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Missing map name")
        val id = (obj["code"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            ?: (obj["mapId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: name
        // Numeric epoch seconds are authoritative. Human-readable Date fields are deliberately not guessed.
        val start = obj["start"]?.jsonPrimitive?.longOrNull ?: error("Missing start")
        val end = obj["end"]?.jsonPrimitive?.longOrNull ?: error("Missing end")
        val active = if (obj.containsKey("isActive")) strictBoolean(obj.getValue("isActive")) else null
        val eventName = optionalEventName(obj["eventName"])
        return MapSegment(id, name, start, end, active, eventName)
    }
}

object RotationCacheCodec {
    fun encode(cache: RotationCache): String = toJson(cache).toString()

    /** Also accepts repository records; native widgets only consume the allowlisted cache portion. */
    fun decode(value: String): RotationCache {
        require(value.length <= 512_000)
        val root = Json.parseToJsonElement(value).jsonObject
        val obj = if (root.containsKey("cache")) root["cache"]?.jsonObject ?: error("No cached rotation") else root
        require(obj["schemaVersion"]?.jsonPrimitive?.intOrNull == 1)
        val fetched = obj["fetchedAtSeconds"]?.jsonPrimitive?.longOrNull ?: error("Missing fetchedAt")
        val modes = obj["playlists"]?.jsonArray?.map { raw ->
            val mode = raw.jsonObject
            PlaylistSchedule(mode.getValue("id").jsonPrimitive.content, mode.getValue("label").jsonPrimitive.content,
                mode.getValue("segments").jsonArray.map { item ->
                    val s = item.jsonObject
                    MapSegment(s.getValue("mapId").jsonPrimitive.content, s.getValue("mapName").jsonPrimitive.content,
                        s.getValue("startSeconds").jsonPrimitive.long, s.getValue("endSeconds").jsonPrimitive.long,
                        s["isActive"]?.takeUnless { it is JsonNull }?.let(::strictBoolean), optionalEventName(s["eventName"]))
                }, observedAtSeconds = mode["observedAtSeconds"]?.jsonPrimitive?.longOrNull,
                retained = mode["retained"]?.jsonPrimitive?.booleanOrNull ?: false)
        } ?: error("Missing playlists")
        return RotationCache(fetched, modes, obj["demo"]?.jsonPrimitive?.booleanOrNull ?: false)
    }

    internal fun toJson(cache: RotationCache): JsonObject = buildJsonObject {
        put("schemaVersion", 1)
        put("fetchedAtSeconds", cache.fetchedAtSeconds)
        put("demo", cache.demo)
        putJsonArray("playlists") {
            cache.playlists.forEach { mode -> add(buildJsonObject {
                put("id", mode.id); put("label", mode.label)
                put("observedAtSeconds", mode.observedAtSeconds?.let(::JsonPrimitive) ?: JsonNull)
                put("retained", mode.retained)
                putJsonArray("segments") { mode.segments.forEach { add(segmentJson(it)) } }
            }) }
        }
    }
}

internal fun segmentJson(segment: MapSegment): JsonObject = buildJsonObject {
    put("mapId", segment.mapId); put("mapName", segment.mapName)
    put("startSeconds", segment.startSeconds); put("endSeconds", segment.endSeconds)
    put("isActive", segment.isActive?.let(::JsonPrimitive) ?: JsonNull)
    put("eventName", segment.eventName?.let(::JsonPrimitive) ?: JsonNull)
}

private fun strictBoolean(element: JsonElement): Boolean {
    val value = element as? JsonPrimitive ?: error("Expected boolean")
    require(!value.isString)
    return value.booleanOrNull ?: error("Expected boolean")
}

private fun optionalEventName(element: JsonElement?): String? {
    if (element == null || element is JsonNull) return null
    val value = element as? JsonPrimitive ?: error("Expected event name")
    require(value.isString)
    val text = value.content.trim().takeIf { it.isNotEmpty() } ?: return null
    require(text.length <= 120)
    return text
}
