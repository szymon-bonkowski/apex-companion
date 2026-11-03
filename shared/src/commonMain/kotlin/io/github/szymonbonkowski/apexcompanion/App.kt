package io.github.szymonbonkowski.apexcompanion

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.ranked.*
import io.github.szymonbonkowski.apexcompanion.history.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant

fun formatRotationTime(epochSeconds: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val local = Instant.fromEpochSeconds(epochSeconds).toLocalDateTime(zone)
    return "${local.date} ${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}"
}

private val appColors = darkColorScheme(
    primary = Color(0xFF6EE7D1), onPrimary = Color(0xFF00382F),
    background = Color(0xFF101519), surface = Color(0xFF1B242B),
    surfaceVariant = Color(0xFF28343E), onSurface = Color(0xFFE8EFF3),
)

/** Shared UI for Android and iOS; platform hosts supply durable repositories and snapshot publication. */
@Composable
fun App(
    repository: RotationRepository? = null,
    onProjectionChanged: suspend (String) -> Unit = {},
    rankedRepository: RankedRepository? = null,
    onRankedProjectionChanged: suspend (String) -> Unit = {},
    historyRepository: HistoryRepository? = null,
    historyDocuments: HistoryDocuments? = null,
) {
    val fallback = remember { RotationRepository(UnconfiguredRotationProvider, object : RotationStore {
        override suspend fun read(): String? = null
        override suspend fun write(value: String) {}
    }, RotationClock { Clock.System.now().epochSeconds }) }
    val rankedFallback = remember { RankedRepository(UnconfiguredRankedProvider, object : RotationStore {
        override suspend fun read(): String? = null
        override suspend fun write(value: String) {}
    }, RotationClock { Clock.System.now().epochSeconds }) }
    val ranked = rankedRepository ?: rankedFallback
    val rankedState by ranked.state.collectAsState()
    val maps = repository ?: fallback
    val state by maps.state.collectAsState()
    var now by remember { mutableStateOf(Clock.System.now().epochSeconds) }
    var screen by rememberSaveable { mutableStateOf("Maps") }
    var mode by rememberSaveable { mutableStateOf("battle_royale") }
    var demo by rememberSaveable { mutableStateOf(false) }
    val demoCache = remember(demo) { if (demo) demoRotation(now) else null }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var projectionError by remember { mutableStateOf(false) }
    var rankedProjectionError by remember { mutableStateOf(false) }

    LaunchedEffect(maps, ranked, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            maps.initialize()
            ranked.initialize()
            launch { ranked.refresh(); if (ranked.state.value.profile != null) ranked.refreshPredator() }
            launch { maps.refresh() }
            var lastTick = Clock.System.now().epochSeconds
            var nextSync = lastTick + ROTATION_FRESH_SECONDS
            var nextRankedSync = lastTick + PROFILE_FRESH_SECONDS
            while (isActive) {
                now = Clock.System.now().epochSeconds
                val crossedBoundary = maps.state.value.cache?.playlists.orEmpty().any { playlist ->
                    playlist.segments.any { segment ->
                        (segment.startSeconds > lastTick && segment.startSeconds <= now) ||
                            (segment.endSeconds > lastTick && segment.endSeconds <= now)
                    }
                }
                if ((now >= nextSync || crossedBoundary) && maps.state.value.error != RotationError.ACCESS_NOT_CONFIGURED) {
                    launch { maps.refresh() }
                    nextSync = now + ROTATION_FRESH_SECONDS
                }
                if (now >= nextRankedSync) {
                    launch { ranked.refresh(); if (ranked.state.value.profile != null) ranked.refreshPredator() }
                    nextRankedSync = now + PROFILE_FRESH_SECONDS
                }
                lastTick = now
                delay(1000)
            }
        }
    }
    LaunchedEffect(state.cache, state.error) {
        try {
            onProjectionChanged(RotationWidgetCodec.encode(state.cache, now, state.error))
            projectionError = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { projectionError = true }
    }
    val modes = if (demo) defaultPlaylists else maps.availableModes()
    val selected = modes.firstOrNull { it.id == mode } ?: modes.first()
    val view = if (demo) projectRotation(demoCache, selected.id, now) else maps.view(selected.id, now)
    val zone = TimeZone.currentSystemDefault()

    val today = Instant.fromEpochSeconds(now).toLocalDateTime(zone).date
    // Native projections include future freshness/midnight entries. Publish on state/day changes only.
    LaunchedEffect(rankedState, today) {
        try {
            onRankedProjectionChanged(RankedWidgetCodec.encode(rankedState, now, today))
            rankedProjectionError = false
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { rankedProjectionError = true }
    }

    MaterialTheme(colorScheme = appColors) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeContentPadding().fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("APEX COMPANION", style = MaterialTheme.typography.labelLarge, color = appColors.primary)
                Column {
                    listOf(listOf("Maps", "Ranked progress", "Profile", "Settings"), listOf("History", "Sessions")).forEach { destinations ->
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            destinations.forEach { destination ->
                                FilterChip(selected = screen == destination, onClick = { screen = destination }, label = { Text(destination) })
                            }
                        }
                    }
                }
                key(screen) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (screen == "Maps") {
                Text("Map rotation", style = MaterialTheme.typography.headlineLarge)
                Text("Your next drop, at a glance.", style = MaterialTheme.typography.bodyLarge)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !demo, onClick = { demo = false }, label = { Text("Live maps") })
                    FilterChip(selected = demo, onClick = { demo = true }, label = { Text("Preview demo") })
                }
                if (demo) Notice("DEMO · Synthetic schedule", "Preview only. These are example maps, not current Apex rotations. Widgets continue to use the provider cache.")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { playlist -> FilterChip(selected = selected.id == playlist.id,
                        onClick = { mode = playlist.id }, label = { Text(playlist.label) }) }
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = appColors.surfaceVariant)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${view.state.name}${if (view.cached) " · CACHED SCHEDULE" else ""}",
                            style = MaterialTheme.typography.labelLarge, color = appColors.primary)
                        Text(selected.label, style = MaterialTheme.typography.titleMedium)
                        Text(view.current?.mapName ?: "Rotation unavailable", style = MaterialTheme.typography.headlineMedium)
                        view.current?.eventName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                        if (view.current != null) {
                            Text(formatCountdown(countdownSeconds(view.current.endSeconds, now)), style = MaterialTheme.typography.displaySmall)
                            Text("Until rotation · ends ${formatRotationTime(view.current.endSeconds, zone)}")
                        } else {
                            Text("No validated map covers the current time.")
                            view.lastKnown?.let { Text("Last known: ${it.mapName} · ended ${formatRotationTime(it.endSeconds, zone)}") }
                        }
                        HorizontalDivider()
                        Text("NEXT", style = MaterialTheme.typography.labelMedium)
                        Text(view.next?.mapName ?: "Not supplied by source", style = MaterialTheme.typography.titleLarge)
                        view.next?.let { Text("Starts ${formatRotationTime(it.startSeconds, zone)}") }
                    }
                }
                if (!demo && state.error != null) Notice(
                    if (state.error == RotationError.ACCESS_NOT_CONFIGURED) "Live access pending" else "Sync did not complete",
                    errorMessage(state.error, state.retryAtSeconds, zone),
                )
                if (projectionError) Notice("Widget update unavailable", "The app could not publish the widget snapshot. Open the app again to retry.")
                if (view.state == DataState.STALE) Text("Cached schedule · server information may have changed.")
                if (view.upcoming.isNotEmpty()) {
                    Text("Upcoming rotations", style = MaterialTheme.typography.titleLarge)
                    view.upcoming.forEach { segment ->
                        Row(Modifier.fillMaxWidth().background(appColors.surface, RoundedCornerShape(12.dp)).padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(segment.mapName, style = MaterialTheme.typography.titleMedium)
                                Text("${formatRotationTime(segment.startSeconds, zone)} – ${formatRotationTime(segment.endSeconds, zone)}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Text("Last successful sync: ${view.lastSyncedAtSeconds?.let { formatRotationTime(it, zone) } ?: "Never"}", style = MaterialTheme.typography.bodyMedium)
                Text("Times shown in ${zone.id}. Background updates depend on your operating system.", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !demo && !state.refreshing && !state.forbidden &&
                    (state.retryAtSeconds == null || now >= state.retryAtSeconds!!), onClick = { scope.launch { maps.refresh(force = true) } }) {
                    Text(if (state.refreshing) "Refreshing…" else "Refresh maps")
                }
                TextButton(onClick = { uriHandler.openUri("https://apexlegendsstatus.com") }) {
                    Text(if (demo) "Source information: Apex Legends Status" else "Data provider: Apex Legends Status")
                }
                } else {
                    when (screen) {
                        "Ranked progress" -> RankedScreen(ranked, now, today)
                        "Profile" -> ProfileScreen(ranked, now)
                        "History" -> if (historyRepository != null) HistoryScreen(historyRepository, ranked, now) else Text("History requires durable platform storage.")
                        "Sessions" -> if (historyRepository != null) SessionsScreen(historyRepository, ranked, now) else Text("Sessions require durable platform storage.")
                        "Settings" -> {
                            RankedSettingsScreen(ranked)
                            if (historyRepository != null) HistorySettingsScreen(historyRepository, ranked, historyDocuments, now)
                        }
                    }
                    if (rankedProjectionError) Notice("Ranked widget update unavailable", "The app could not publish the ranked widget snapshot. Open the app again to retry.")
                }
                Text("Independent fan utility. This project is not endorsed by or affiliated with EA or its licensors.", style = MaterialTheme.typography.bodySmall)
                }
                }
            }
        }
    }
}

@Composable
private fun Notice(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun errorMessage(error: RotationError?, retryAt: Long?, zone: TimeZone): String {
    val message = when (error) {
        RotationError.ACCESS_NOT_CONFIGURED -> "Live data needs approved provider access. Use Preview demo to explore the layout."
        RotationError.FORBIDDEN -> "Provider access was denied. Refresh stays disabled until the access configuration is resolved."
        RotationError.RATE_LIMITED -> "The source asked us to wait. Cached data has been retained."
        RotationError.TIMEOUT -> "The request timed out. Cached data has been retained."
        RotationError.SCHEMA -> "The source returned an unsupported schedule. Cached data has been retained."
        RotationError.STORAGE -> "Local storage could not be read or saved. The last usable state is retained where possible."
        else -> "The source could not be reached. Cached data has been retained."
    }
    return message + if (retryAt != null && error != RotationError.ACCESS_NOT_CONFIGURED && error != RotationError.FORBIDDEN) " Retry after ${formatRotationTime(retryAt, zone)}." else ""
}
