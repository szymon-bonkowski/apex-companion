package io.github.szymonbonkowski.apexcompanion.appearance

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.szymonbonkowski.apexcompanion.formatRotationTime
import io.github.szymonbonkowski.apexcompanion.history.*
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.matches.*
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlin.math.roundToInt
import kotlin.time.Instant
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/** Presentation projection only. It does not fetch, mutate or reinterpret semantic evidence. */
data class DashboardCardData(
    val module: DashboardModule,
    val value: String,
    val state: String,
    val source: String,
    val observedAtSeconds: Long?,
    val details: List<String>,
    val available: Boolean,
    val destination: String,
    val progress: Float? = null,
    val observations: List<RpObservation> = emptyList(),
)

fun dashboardCards(
    rotation: RotationView,
    ranked: RankedView,
    rankedState: RankedRepositoryState,
    history: HistoryRecord?,
    matches: List<MatchRecord>,
    sync: MatchSyncState?,
    now: Long,
): Map<DashboardModule, DashboardCardData> {
    val manualHistory = rankedState.useManual || rankedState.profile == null
    val historyIdentity =
        if (manualHistory) null else rankedState.profile.identity.historyIdentity()
    val historySource =
        if (manualHistory) ObservationSource.USER_RECORDED else ObservationSource.PROVIDER
    val observations =
        history
            ?.observations
            .orEmpty()
            .filter {
                it.identity == historyIdentity &&
                    it.source == historySource &&
                    it.observedAtSeconds <= now
            }
            .sortedWith(observationOrder)
    val latestSession =
        history
            ?.sessions
            .orEmpty()
            .filter {
                it.identity == historyIdentity &&
                    it.source == historySource &&
                    it.startedAtSeconds <= now &&
                    (it.endedAtSeconds == null || it.endedAtSeconds <= now)
            }
            .maxByOrNull { it.startedAtSeconds }
    val session = latestSession?.let { sessionResult(it, observations) }
    val linkedIdentity = rankedState.profile?.identity?.historyIdentity()
    val latestMatch =
        matches
            .filter { it.identity == linkedIdentity && it.timestampSeconds <= now }
            .maxByOrNull { it.timestampSeconds }
    val platform = rankedState.profile?.identity?.platform
    val predator = rankedState.predator.firstOrNull { it.platform == platform }
    val freshPredator =
        predator != null &&
            rankedState.predatorError == null &&
            now >= predator.observedAtSeconds &&
            now < predator.observedAtSeconds + PREDATOR_FRESH_SECONDS
    fun signed(value: Long) = if (value > 0) "+$value" else value.toString()
    return listOf(
            DashboardCardData(
                DashboardModule.MAP_ROTATION,
                rotation.current?.mapName ?: "Rotation unavailable",
                rotation.state.name,
                rotation.source,
                rotation.lastSyncedAtSeconds,
                listOfNotNull(
                    rotation.current?.let { "Ends ${formatRotationTime(it.endSeconds)}" },
                    if (rotation.cached) "Cached schedule · source may have changed" else null,
                    if (rotation.current == null)
                        "Open Maps for setup or an explicitly labeled demo."
                    else null,
                ),
                rotation.current != null,
                "Maps",
            ),
            DashboardCardData(
                DashboardModule.UPCOMING_MAP,
                rotation.next?.mapName ?: "Next map unknown",
                if (rotation.next == null) "UNAVAILABLE" else rotation.state.name,
                rotation.source,
                rotation.lastSyncedAtSeconds,
                listOf(
                    rotation.next?.let {
                        "Source-supplied start ${formatRotationTime(it.startSeconds)}"
                    } ?: "No future rotation is assumed."
                ),
                rotation.next != null,
                "Maps",
            ),
            DashboardCardData(
                DashboardModule.RANKED_PROFILE,
                ranked.currentRp?.let { "$it RP" } ?: "RP unavailable",
                ranked.state.name,
                ranked.source,
                ranked.observedAtSeconds,
                listOfNotNull(
                    if (rankedState.useManual || rankedState.profile == null)
                        "Independent manual RP"
                    else
                        ranked.snapshot?.identity?.let {
                            "${it.name.ifBlank { "Linked profile" }} · ${it.platform.label}"
                        },
                    if (rankedState.useManual || rankedState.profile == null) "Entered by you"
                    else
                        listOfNotNull(
                                ranked.snapshot?.rankName,
                                ranked.snapshot?.division?.let { "Division $it" },
                            )
                            .joinToString(" · ")
                            .ifBlank { "Rank unknown" },
                ),
                ranked.currentRp != null,
                "Ranked progress",
            ),
            DashboardCardData(
                DashboardModule.GOAL_PROGRESS,
                ranked.goal?.let { "${it.targetRp} RP" } ?: "Set your next goal",
                if (ranked.goal == null) "UNAVAILABLE" else ranked.state.name,
                "User goal · ${ranked.source}",
                ranked.observedAtSeconds,
                listOfNotNull(
                    ranked.progress?.remainingRp?.let { "$it RP remaining" },
                    ranked.progress?.rpPerDay?.let { "$it RP/day required average" },
                    ranked.goal?.deadline?.let { "Target date $it" },
                    ranked.progress?.status?.name?.lowercase()?.replace('_', ' ')
                        ?: "Add a target and optional deadline in Ranked.",
                ),
                ranked.goal != null,
                "Ranked progress",
                ranked.progress?.takeIf { ranked.currentRp != null }?.fraction,
            ),
            DashboardCardData(
                DashboardModule.PREDATOR_THRESHOLD,
                predator?.let { "${it.rp} RP" } ?: "Cutoff unavailable",
                if (predator == null) "UNAVAILABLE" else if (freshPredator) "LIVE" else "STALE",
                "Apex Legends Status",
                predator?.observedAtSeconds,
                listOfNotNull(
                    platform?.label ?: "Link a profile to select its platform.",
                    if (
                        !rankedState.useManual &&
                            ranked.snapshot != null &&
                            predator != null &&
                            ranked.currentRp != null
                    )
                        "${signed(ranked.currentRp - predator.rp)} RP relative to displayed cutoff"
                    else null,
                    "A changing cutoff does not guarantee a position.",
                ),
                predator != null,
                "Ranked progress",
            ),
            DashboardCardData(
                DashboardModule.RECENT_MATCH,
                latestMatch?.let { it.map ?: "Map unknown" } ?: "Your journal starts here",
                if (latestMatch == null) "UNAVAILABLE"
                else if (latestMatch.source == MatchSource.MANUAL) "MANUAL"
                else latestMatch.quality.name,
                latestMatch?.source?.name ?: "Local match journal",
                latestMatch?.timestampSeconds,
                latestMatch?.let { m ->
                    listOf(
                        "${m.mode.name.replace('_', ' ')} · ${m.quality.name}",
                        "Kills ${m.kills ?: "unknown"} · Damage ${m.damage ?: "unknown"}",
                        "Placement ${m.placement ?: "unknown"} · RP ${m.rpDelta?.let { signed(it.toLong()) } ?: "unknown"}",
                    )
                }
                    ?: listOf(
                        "Add a manual result or pair your local PC companion.",
                        "Automatic Apex capture remains unverified.",
                    ),
                latestMatch != null,
                "Matches",
            ),
            DashboardCardData(
                DashboardModule.RP_TREND,
                observations.lastOrNull()?.let { "${it.rp} RP" } ?: "No RP observations",
                if (observations.isEmpty()) "UNAVAILABLE"
                else if (manualHistory) "MANUAL" else "RECORDED",
                if (manualHistory) "USER_RECORDED · Manual RP"
                else "Recorded provider observations",
                observations.lastOrNull()?.observedAtSeconds,
                listOf(
                    "${observations.size} recorded samples · ${if (history?.recording == true) "recording on" else "recording off"}",
                    "Intervals between observations are not match results.",
                ),
                observations.isNotEmpty(),
                "History",
                observations = observations.takeLast(12),
            ),
            DashboardCardData(
                DashboardModule.SESSION_SUMMARY,
                latestSession?.let {
                    if (it.endedAtSeconds == null) "Session in progress"
                    else
                        session?.deltaRp?.let { delta -> "${signed(delta)} RP" }
                            ?: "Session RP unknown"
                } ?: "No ranked sessions",
                if (latestSession == null) "UNAVAILABLE"
                else if (manualHistory) "MANUAL" else "RECORDED",
                if (manualHistory) "Manual RP session" else "Provider observation session",
                latestSession?.startedAtSeconds,
                listOfNotNull(
                    latestSession?.endedAtSeconds?.let { "Stopped ${formatRotationTime(it)}" },
                    session?.explanation ?: "Start and stop your own observation session.",
                    "Match count is unknown.",
                ),
                latestSession != null,
                "Sessions",
            ),
            DashboardCardData(
                DashboardModule.CAPTURE_STATUS,
                if (sync?.paired == true) "PC companion paired" else "Local sync not paired",
                if (sync?.paired == true) "MANUAL" else "UNAVAILABLE",
                "Foreground local HTTPS sync",
                sync?.lastSyncSeconds,
                listOfNotNull(
                    if (sync?.busy == true) "Sync in progress"
                    else "Pair explicitly in Capture sync",
                    sync?.error,
                    "Automatic Apex capture is unverified; auto-acceptance is disabled.",
                ),
                sync?.paired == true,
                "Capture sync",
            ),
        )
        .associateBy { it.module }
}

@Composable
fun ColumnScope.DashboardScreen(
    appearance: AppearanceRepository,
    rotation: RotationRepository,
    ranked: RankedRepository,
    history: HistoryRepository?,
    matches: MatchRepository?,
    sync: MatchSyncController?,
    now: Long,
    onNavigate: (String) -> Unit,
) {
    val appearanceState by appearance.state.collectAsState()
    val rotationState by rotation.state.collectAsState()
    val rankedState by ranked.state.collectAsState()
    val historyState = history?.state?.collectAsState()?.value
    val matchState = matches?.state?.collectAsState()?.value
    val syncState = sync?.state?.collectAsState()?.value
    val style = appearanceState.record.preferences.style
    val layout = appearanceState.record.dashboard ?: defaultDashboard(style)
    val date = Instant.fromEpochSeconds(now).toLocalDateTime(TimeZone.currentSystemDefault()).date
    val modeId = layout.mapModeId
    val rotationView = projectRotation(rotationState.cache, modeId, now, rotationState.error)
    val cards =
        dashboardCards(
            rotationView,
            rankedView(rankedState, now, date),
            rankedState,
            historyState?.record,
            matchState?.records.orEmpty(),
            syncState,
            now,
        )
    var configuring by rememberSaveable { mutableStateOf(false) }
    var savingLayout by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tokens = LocalThemeTokens.current

    Text(
        when (style) {
            DesignStyle.CARBON -> "Today"
            DesignStyle.LIQUID -> "Your daily orbit"
            DesignStyle.EDITORIAL -> "The daily brief."
            DesignStyle.TACTICAL -> "TODAY / OVERVIEW"
        },
        style = MaterialTheme.typography.headlineLarge,
    )
    Text(
        when (style) {
            DesignStyle.CARBON -> "A clear view of your next move."
            DesignStyle.LIQUID -> "Everything you need, in one space."
            DesignStyle.EDITORIAL -> "Your progress, with room to breathe."
            DesignStyle.TACTICAL -> "LOCAL OVERVIEW · EXPLICIT SOURCE STATES"
        },
        style = MaterialTheme.typography.bodyLarge,
    )
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(date.toString(), style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { configuring = !configuring }) {
            Text(if (configuring) "Done configuring" else "Configure dashboard")
        }
    }
    if (configuring) {
        if (savingLayout) Text("Saving dashboard…", style = MaterialTheme.typography.bodySmall)
        DashboardEditor(
            layout,
            appearanceState.readOnly || savingLayout,
            rotation.availableModes(),
            save = { updated ->
                if (!savingLayout) {
                    savingLayout = true
                    scope.launch {
                        try {
                            appearance.saveDashboard(updated)
                        } finally {
                            savingLayout = false
                        }
                    }
                }
            },
            reset = {
                if (!savingLayout) {
                    savingLayout = true
                    scope.launch {
                        try {
                            appearance.saveDashboard(null)
                        } finally {
                            savingLayout = false
                        }
                    }
                }
            },
        )
    }
    appearanceState.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val visible =
        layout.items.filter {
            it.visible && (!layout.hideUnavailable || cards[it.module]?.available == true)
        }
    if (visible.isEmpty()) {
        DesignCard(Modifier.fillMaxWidth()) {
            Text("Your dashboard, your choice", style = MaterialTheme.typography.titleLarge)
            Text(
                "All modules are hidden or currently unavailable. Configure the dashboard to show source status or add a module."
            )
            OutlinedButton(onClick = { configuring = true }) { Text("Choose modules") }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val largeText = LocalDensity.current.fontScale >= 1.5f
        val columns =
            when {
                style == DesignStyle.EDITORIAL || largeText -> 1
                style == DesignStyle.TACTICAL && maxWidth >= 540.dp -> 2
                maxWidth >= 720.dp -> 2
                else -> 1
            }
        Column(verticalArrangement = Arrangement.spacedBy(tokens.gap)) {
            val hero =
                visible.firstOrNull()?.takeIf {
                    it.size == ModuleSize.EXPANDED && style != DesignStyle.TACTICAL
                }
            hero?.let { item ->
                DashboardCard(cards.getValue(item.module), item.size, style, 1, onNavigate)
            }
            val remaining = if (hero != null) visible.drop(1) else visible
            remaining.chunked(columns).forEachIndexed { rowIndex, row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(tokens.gap),
                ) {
                    row.forEachIndexed { columnIndex, item ->
                        Box(Modifier.weight(1f)) {
                            DashboardCard(
                                cards.getValue(item.module),
                                item.size,
                                style,
                                rowIndex * columns + columnIndex + if (hero != null) 2 else 1,
                                onNavigate,
                            )
                        }
                    }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DashboardCard(
    data: DashboardCardData,
    size: ModuleSize,
    style: DesignStyle,
    index: Int,
    onNavigate: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement =
            Arrangement.spacedBy(if (style == DesignStyle.EDITORIAL) 12.dp else 0.dp),
    ) {
        if (style == DesignStyle.EDITORIAL) {
            HorizontalDivider()
            Text(
                "${index.toString().padStart(2, '0')} / ${data.module.title}",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        DesignCard(Modifier.fillMaxWidth(), onClick = { onNavigate(data.destination) }) {
            Text(
                if (style == DesignStyle.TACTICAL)
                    "${index.toString().padStart(2, '0')} // ${data.module.title.uppercase()}"
                else data.module.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                data.value,
                style =
                    when (size) {
                        ModuleSize.COMPACT -> MaterialTheme.typography.titleLarge
                        ModuleSize.STANDARD -> MaterialTheme.typography.headlineSmall
                        ModuleSize.EXPANDED -> MaterialTheme.typography.displaySmall
                    },
            )
            Text("${data.state} · ${data.source}", style = MaterialTheme.typography.labelMedium)
            data.progress?.let { progress ->
                val animated by
                    animateFloatAsState(
                        progress,
                        animationSpec = tween(LocalThemeTokens.current.motionMillis),
                    )
                LinearProgressIndicator(
                    progress = { animated },
                    modifier =
                        Modifier.fillMaxWidth().semantics {
                            contentDescription =
                                "Goal progress ${(progress * 100).roundToInt()} percent"
                        },
                )
            }
            if (data.observations.isNotEmpty() && size != ModuleSize.COMPACT)
                ObservationMiniChart(data.observations)
            val details = if (size == ModuleSize.COMPACT) data.details.take(1) else data.details
            details.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                "Updated ${data.observedAtSeconds?.let { formatRotationTime(it) } ?: "never"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Open ${data.destination} →",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun DashboardEditor(
    layout: DashboardLayout,
    readOnly: Boolean,
    modes: List<Playlist>,
    save: (DashboardLayout) -> Unit,
    reset: () -> Unit,
) {
    DesignCard(Modifier.fillMaxWidth()) {
        Text("Build your daily view", style = MaterialTheme.typography.titleLarge)
        Text("Use Move up and Move down to change reading order. Changes save immediately.")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Hide unavailable modules", Modifier.weight(1f))
            Switch(
                layout.hideUnavailable,
                { save(layout.copy(hideUnavailable = it)) },
                enabled = !readOnly,
                modifier = Modifier.semantics { contentDescription = "Hide unavailable modules" },
            )
        }
        Text("Dashboard map playlist", style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            modes.forEach { mode ->
                FilterChip(
                    layout.mapModeId == mode.id,
                    { save(layout.copy(mapModeId = mode.id)) },
                    label = { Text(mode.label) },
                    enabled = !readOnly,
                )
            }
        }
        OutlinedButton(onClick = reset, enabled = !readOnly) {
            Text("Restore ${LocalThemeTokens.current.style.title} dashboard")
        }
    }
    layout.items.forEachIndexed { index, item ->
        DesignCard(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "${index + 1}. ${item.module.title}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(
                    item.visible,
                    { visible ->
                        save(
                            layout.copy(
                                items =
                                    layout.items.map {
                                        if (it.module == item.module) it.copy(visible = visible)
                                        else it
                                    }
                            )
                        )
                    },
                    enabled = !readOnly,
                    modifier =
                        Modifier.semantics { contentDescription = "Show ${item.module.title}" },
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedButton(
                    onClick = { save(layout.move(item.module, -1)) },
                    enabled = index > 0 && !readOnly,
                    modifier =
                        Modifier.semantics { contentDescription = "Move ${item.module.title} up" },
                ) {
                    Text("Move up")
                }
                OutlinedButton(
                    onClick = { save(layout.move(item.module, 1)) },
                    enabled = index < layout.items.lastIndex && !readOnly,
                    modifier =
                        Modifier.semantics { contentDescription = "Move ${item.module.title} down" },
                ) {
                    Text("Move down")
                }
                ModuleSize.entries.forEach { size ->
                    FilterChip(
                        item.size == size,
                        {
                            save(
                                layout.copy(
                                    items =
                                        layout.items.map {
                                            if (it.module == item.module) it.copy(size = size)
                                            else it
                                        }
                                )
                            )
                        },
                        label = { Text(size.name.lowercase().replaceFirstChar(Char::uppercase)) },
                        enabled = !readOnly,
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    "${item.module.title} size ${size.name.lowercase()}"
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun ObservationMiniChart(observations: List<RpObservation>) {
    val low = observations.minOf { it.rp }
    val high = observations.maxOf { it.rp }
    val span = (high - low).coerceAtLeast(1)
    val start = observations.first().observedAtSeconds
    val end = observations.last().observedAtSeconds
    val accent = MaterialTheme.colorScheme.primary
    val unknown = MaterialTheme.colorScheme.tertiary
    val quarantine = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier.fillMaxWidth().height(80.dp).semantics {
            contentDescription =
                "Historical RP chart, ${observations.size} recorded points from ${observations.first().rp} to ${observations.last().rp} RP. Lines connect compatible observation contexts only; long gaps stay separate."
        }
    ) {
        val pad = 6.dp.toPx()
        fun point(index: Int) =
            Offset(
                pad +
                    (size.width - pad * 2) *
                        if (end == start) 0.5f
                        else
                            ((observations[index].observedAtSeconds - start).toDouble() /
                                    (end - start))
                                .toFloat(),
                pad +
                    (size.height - pad * 2) *
                        if (high == low) 0.5f
                        else 1f - (observations[index].rp - low).toFloat() / span,
            )
        repeat(3) { level ->
            val y = pad + (size.height - pad * 2) * level / 2
            drawLine(grid, Offset(pad, y), Offset(size.width - pad, y), 1.dp.toPx())
        }
        observations.indices.forEach { index ->
            if (index > 0) {
                val interval = rpInterval(observations[index - 1], observations[index])
                if (interval.deltaRp != null && interval.quality != IntervalQuality.LONG_GAP)
                    drawLine(accent, point(index - 1), point(index), 2.dp.toPx())
            }
            val color =
                when (observations[index].quality) {
                    ObservationQuality.VALID -> accent
                    ObservationQuality.UNKNOWN_CONTEXT -> unknown
                    ObservationQuality.QUARANTINED -> quarantine
                }
            drawCircle(color, 3.dp.toPx(), point(index))
        }
    }
}
