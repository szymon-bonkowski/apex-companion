package io.github.szymonbonkowski.apexcompanion.appearance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.szymonbonkowski.apexcompanion.maps.*
import kotlinx.coroutines.launch

/**
 * Public provider permission is unresolved. No OS permission request or scheduled alert while
 * closed.
 */
const val FAVORITE_MAP_DELIVERY_AUTHORIZED = false
const val FAVORITE_MAP_GATE_REASON =
    "Alerts are unavailable until an authorized source supplies a verified future schedule. DEMO, expired and speculative rotations never trigger notifications. No background polling or notification permission is requested."

data class FavoriteMapAlert(
    val id: String,
    val mapName: String,
    val playlist: String,
    val deliverAtSeconds: Long,
    val startsAtSeconds: Long,
)

/**
 * UTC-only planning. Native schedulers must cancel old IDs before replacing and enforce the same
 * rights gate.
 */
fun planFavoriteMapAlerts(
    cache: RotationCache?,
    preferences: FavoriteMapPreferences,
    now: Long,
    authorized: Boolean = FAVORITE_MAP_DELIVERY_AUTHORIZED,
    deliveredIds: Set<String> = emptySet(),
): List<FavoriteMapAlert> {
    preferences.validated()
    if (
        !authorized ||
            !preferences.enabled ||
            cache == null ||
            cache.demo ||
            now < cache.fetchedAtSeconds ||
            now >= cache.fetchedAtSeconds + ROTATION_FRESH_SECONDS
    )
        return emptyList()
    return cache.playlists
        .filter {
            !it.retained &&
                now >= (it.observedAtSeconds ?: cache.fetchedAtSeconds) &&
                now < (it.observedAtSeconds ?: cache.fetchedAtSeconds) + ROTATION_FRESH_SECONDS
        }
        .flatMap { playlist ->
            playlist.segments
                .filter { it.mapName in preferences.favorites && it.startSeconds > now }
                .mapNotNull { segment ->
                    val id = "${playlist.id}:${segment.startSeconds}:${segment.mapName}"
                    val whenTo = segment.startSeconds - preferences.advanceMinutes * 60L
                    if (whenTo <= now || id in deliveredIds) null
                    else
                        FavoriteMapAlert(
                            id,
                            segment.mapName,
                            playlist.id,
                            whenTo,
                            segment.startSeconds,
                        )
                }
        }
        .distinctBy { it.id }
        .sortedBy { it.deliverAtSeconds }
        .take(32)
}

@Composable
fun ColumnScope.FavoriteMapSettings(repository: AppearanceRepository, maps: RotationRepository) {
    val state by repository.state.collectAsState()
    val mapState by maps.state.collectAsState()
    val p = state.record.favoriteMaps
    val scope = rememberCoroutineScope()
    Text("Favorite map notifications", style = MaterialTheme.typography.headlineLarge)
    DesignCard(Modifier.fillMaxWidth()) {
        Text("Unavailable · source approval required", style = MaterialTheme.typography.titleLarge)
        Text(FAVORITE_MAP_GATE_REASON)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(checked = false, onCheckedChange = {}, enabled = false)
            Text("Delivery disabled", Modifier.padding(start = 12.dp))
        }
        Text(
            "Your preferences can be prepared locally. Enabling delivery will require explicit opt-in after the capability is available.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text("Advance notice", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0, 5, 10, 15).forEach { minutes ->
            FilterChip(
                selected = p.advanceMinutes == minutes,
                onClick = {
                    scope.launch {
                        repository.saveFavoriteMaps(
                            p.copy(advanceMinutes = minutes, enabled = false)
                        )
                    }
                },
                label = { Text(if (minutes == 0) "At rotation" else "$minutes min") },
            )
        }
    }
    val names =
        mapState.cache
            ?.takeUnless { it.demo }
            ?.playlists
            .orEmpty()
            .flatMap { it.segments }
            .map { it.mapName }
            .distinct()
            .sorted()
    Text("Favorite maps", style = MaterialTheme.typography.titleLarge)
    if (names.isEmpty())
        Text(
            "No verified map names are available yet. Open Maps when authorized source access is configured; no current season pool is assumed."
        )
    names.forEach { name ->
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(
                checked = name in p.favorites,
                onCheckedChange = { checked ->
                    scope.launch {
                        repository.saveFavoriteMaps(
                            p.copy(
                                enabled = false,
                                favorites =
                                    if (checked) (p.favorites + name).distinct().take(20)
                                    else p.favorites - name,
                            )
                        )
                    }
                },
            )
            Text(name)
        }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
