package io.github.szymonbonkowski.apexcompanion.maps

import kotlinx.serialization.json.*

/** KMP pre-resolves timeline entries; native widgets validate expiry without inventing schedule logic. */
object RotationWidgetCodec {
    fun encode(cache: RotationCache?, nowSeconds: Long, error: RotationError? = null): String = buildJsonObject {
        put("schemaVersion", 1)
        put("generatedAtEpochSeconds", nowSeconds)
        putJsonArray("modes") {
            val modes = cache?.playlists?.map { Playlist(it.id, it.label) }?.takeIf { it.isNotEmpty() } ?: defaultPlaylists
            modes.forEach { mode -> add(buildJsonObject {
                put("id", mode.id); put("label", mode.label)
                val observedAt = cache?.playlists?.firstOrNull { it.id == mode.id }?.observedAtSeconds ?: cache?.fetchedAtSeconds
                val boundaries = (listOf(nowSeconds) +
                    cache?.playlists?.firstOrNull { it.id == mode.id }?.segments.orEmpty().flatMap { listOf(it.startSeconds, it.endSeconds) } +
                    listOfNotNull(observedAt?.plus(ROTATION_FRESH_SECONDS)))
                    .filter { it >= nowSeconds }.distinct().sorted()
                putJsonArray("entries") {
                    boundaries.forEachIndexed { index, at ->
                        val view = projectRotation(cache, mode.id, at, error)
                        add(buildJsonObject {
                            put("effectiveAtEpochSeconds", at)
                            put("state", view.state.name); put("source", view.source)
                            put("lastSyncedAtEpochSeconds", view.lastSyncedAtSeconds?.let(::JsonPrimitive) ?: JsonNull)
                            put("validUntilEpochSeconds", boundaries.getOrNull(index + 1)?.let(::JsonPrimitive) ?: JsonNull)
                            put("current", view.current?.let(::widgetSegment) ?: JsonNull)
                            put("next", view.next?.let(::widgetSegment) ?: JsonNull)
                        })
                    }
                }
            }) }
        }
    }.toString()

    private fun widgetSegment(segment: MapSegment) = buildJsonObject {
        put("mapId", segment.mapId); put("mapName", segment.mapName)
        put("startEpochSeconds", segment.startSeconds); put("endEpochSeconds", segment.endSeconds)
        put("isActive", segment.isActive?.let(::JsonPrimitive) ?: JsonNull)
        put("eventName", segment.eventName?.let(::JsonPrimitive) ?: JsonNull)
    }
}
