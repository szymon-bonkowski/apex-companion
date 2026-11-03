package io.github.szymonbonkowski.apexcompanion.maps

enum class DataState { LIVE, STALE, UNAVAILABLE, DEMO, MANUAL }
enum class RotationError { ACCESS_NOT_CONFIGURED, FORBIDDEN, RATE_LIMITED, NETWORK, TIMEOUT, SERVER, SCHEMA, STORAGE }

data class Playlist(val id: String, val label: String)
data class MapSegment(
    val mapId: String,
    val mapName: String,
    val startSeconds: Long,
    val endSeconds: Long,
    val isActive: Boolean? = null,
    val eventName: String? = null,
) {
    init {
        require(mapId.isNotBlank() && mapId.length <= 120)
        require(mapName.isNotBlank() && mapName.length <= 120)
        require(startSeconds in MIN_TIMESTAMP..MAX_TIMESTAMP && endSeconds in MIN_TIMESTAMP..MAX_TIMESTAMP)
        require(startSeconds < endSeconds)
        require(eventName == null || (eventName.isNotBlank() && eventName.length <= 120))
    }
    fun contains(nowSeconds: Long) = nowSeconds >= startSeconds && nowSeconds < endSeconds
}

internal const val MIN_TIMESTAMP = 946684800L
internal const val MAX_TIMESTAMP = 4102444800L
const val ROTATION_FRESH_SECONDS = 300L
const val ROTATION_SOURCE = "Apex Legends Status"

data class PlaylistSchedule(
    val id: String,
    val label: String,
    val segments: List<MapSegment>,
    val observedAtSeconds: Long? = null,
    val retained: Boolean = false,
) {
    init {
        require(id.isNotBlank() && id.length <= 80 && label.isNotBlank() && label.length <= 120)
        require(segments.size <= 100)
        require(segments.zipWithNext().all { (a, b) -> a.endSeconds <= b.startSeconds })
        require(observedAtSeconds == null || observedAtSeconds in MIN_TIMESTAMP..MAX_TIMESTAMP)
    }
}

data class RotationCache(val fetchedAtSeconds: Long, val playlists: List<PlaylistSchedule>, val demo: Boolean = false) {
    init {
        require(fetchedAtSeconds in MIN_TIMESTAMP..MAX_TIMESTAMP)
        require(playlists.size <= 40 && playlists.map { it.id }.distinct().size == playlists.size)
    }
}

data class RotationView(
    val state: DataState,
    val current: MapSegment?,
    val next: MapSegment?,
    val upcoming: List<MapSegment>,
    val lastSyncedAtSeconds: Long?,
    val source: String,
    val cached: Boolean,
    val error: RotationError? = null,
    val lastKnown: MapSegment? = null,
)

fun playlistLabel(id: String): String = when (id) {
    "battle_royale" -> "Pubs"
    "ranked" -> "Ranked"
    "ltm" -> "Mixtape"
    "wildcard" -> "Wildcard"
    "control" -> "Control"
    "arenas" -> "Arenas"
    "arenasRanked", "arenas_ranked" -> "Ranked Arenas"
    else -> id.replace('_', ' ').take(120)
}

val defaultPlaylists = listOf(Playlist("battle_royale", "Pubs"), Playlist("ranked", "Ranked"), Playlist("ltm", "Mixtape"))

/** A previously observed map is never advanced by guessing a cycle. Gaps remain unavailable. */
fun projectRotation(cache: RotationCache?, modeId: String, nowSeconds: Long, error: RotationError? = null): RotationView {
    val playlist = cache?.playlists?.firstOrNull { it.id == modeId }
    // Explicit source inactivity overrides time coverage. Keep it in cache so a successful inactive
    // response cannot accidentally resurrect that playlist's older active schedule through retention.
    val segments = playlist?.segments.orEmpty().filter { it.isActive != false }
    val observedAt = playlist?.observedAtSeconds ?: cache?.fetchedAtSeconds
    // A device clock moving behind the observation cannot establish current live provenance.
    val current = segments.firstOrNull { it.contains(nowSeconds) }
    val upcoming = segments.filter { it.startSeconds > nowSeconds }
    val fresh = cache != null && observedAt != null && playlist?.retained != true && nowSeconds >= observedAt &&
        nowSeconds < observedAt + ROTATION_FRESH_SECONDS &&
        current?.contains(observedAt) == true && error == null
    val state = when {
        cache?.demo == true && current != null -> DataState.DEMO
        current == null -> DataState.UNAVAILABLE
        fresh -> DataState.LIVE
        else -> DataState.STALE
    }
    return RotationView(state, current, upcoming.firstOrNull(), upcoming, observedAt,
        if (cache?.demo == true) "Synthetic demonstration" else ROTATION_SOURCE,
        state == DataState.STALE, error, segments.lastOrNull { it.endSeconds <= nowSeconds })
}

fun countdownSeconds(endSeconds: Long?, nowSeconds: Long): Long? = endSeconds?.let { (it - nowSeconds).coerceAtLeast(0) }

fun formatCountdown(seconds: Long?): String {
    if (seconds == null) return "—"
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remainder = safe % 60
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:${remainder.toString().padStart(2, '0')}"
    else "$minutes:${remainder.toString().padStart(2, '0')}"
}

/** Explicit synthetic names avoid an invented claim about the season's map pool. */
fun demoRotation(nowSeconds: Long): RotationCache {
    val start = nowSeconds - nowSeconds % 1800
    return RotationCache(nowSeconds, defaultPlaylists.mapIndexed { index, playlist ->
        PlaylistSchedule(playlist.id, playlist.label, (0..3).map { offset ->
            MapSegment("demo-${(offset + index) % 3}", listOf("Example Highlands", "Example Harbor", "Example Outpost")[(offset + index) % 3],
                start + offset * 1800L, start + (offset + 1) * 1800L)
        })
    }, demo = true)
}
