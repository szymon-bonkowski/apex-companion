package io.github.szymonbonkowski.apexcompanion.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.time.Instant

private data class HistorySelection(val identity: HistoryIdentity?, val source: ObservationSource)
private fun historySelection(state: RankedRepositoryState): HistorySelection =
    if (state.useManual || state.profile == null) HistorySelection(null, ObservationSource.USER_RECORDED)
    else HistorySelection(state.profile.identity.historyIdentity(), ObservationSource.PROVIDER)

private fun selectedObservations(record: HistoryRecord, selected: HistorySelection) = record.observations
    .filter { it.identity == selected.identity && it.source == selected.source }
    .sortedWith(observationOrder)

private fun sourceLabel(source: ObservationSource) = when (source) {
    ObservationSource.PROVIDER -> "PROVIDER · Apex Legends Status"
    ObservationSource.USER_RECORDED -> "USER_RECORDED · Manual RP"
}
private fun historyTime(epochSeconds: Long) = Instant.fromEpochSeconds(epochSeconds).toString().replace("T", " ").replace("Z", " UTC")
private fun signedRp(value: Long?) = when {
    value == null -> "RP unknown"
    value > 0 -> "+$value RP"
    else -> "$value RP"
}
private fun elapsed(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    return "${safe / 3600}h ${(safe % 3600) / 60}m ${safe % 60}s"
}
private fun intervalExplanation(interval: RpInterval): String = when (interval.quality) {
    IntervalQuality.VERIFIED -> "Verified provider context · observation interval"
    IntervalQuality.USER_CONFIRMED -> "User-confirmed season/split · observation interval"
    IntervalQuality.LONG_GAP -> "Long observation gap · the interval spans ${elapsed(interval.durationSeconds)}"
    IntervalQuality.UNKNOWN_CONTEXT -> "Season/split context is unknown. Confirm context in Settings for future observations."
    IntervalQuality.CONTEXT_BOUNDARY -> "New context or split baseline. No RP delta across this boundary."
    IntervalQuality.IDENTITY_BOUNDARY -> "Different identity, platform or ranked mode. RP cannot be compared."
    IntervalQuality.SOURCE_BOUNDARY -> "Manual and provider observations are separate sources. RP cannot be compared."
    IntervalQuality.ORDERING -> "Observation timestamps do not establish a positive window. RP unknown."
    IntervalQuality.QUARANTINED -> "Possible discontinuity requires context review. Original RP is retained. Confirm the current context in Settings to establish a new baseline."
}

@Composable
private fun HistoryIdentityLabel(selected: HistorySelection) {
    Text(sourceLabel(selected.source), style = MaterialTheme.typography.labelLarge)
    Text(selected.identity?.let { "${it.platform.label} · UID ${it.uid}" } ?: "Independent manual history · no linked player identity")
    Text("Battle Royale Ranked", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RecordingStatus(record: HistoryRecord, ranked: RankedRepositoryState) {
    Text(if (record.recording) "Local history recording ON" else "Local history recording OFF",
        style = MaterialTheme.typography.titleMedium)
    Text(if (!record.recording) "Enable recording in Settings to save future RP observations and start sessions. Existing history remains on this device."
        else if (ranked.useManual || ranked.profile == null) "Saving manual RP records a USER_RECORDED observation. Recording does not create a provider connection."
        else "Only successful validated profile refreshes save provider observations. Recording does not mean a live connection; source availability and freshness remain visible in Ranked progress.",
        style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ColumnScope.HistoryScreen(history: HistoryRepository, ranked: RankedRepository, now: Long) {
    val historyState by history.state.collectAsState()
    val rankedState by ranked.state.collectAsState()
    val record = historyState.record
    val selected = historySelection(rankedState)
    val observations = selectedObservations(record, selected)
    var page by rememberSaveable(selected.identity, selected.source) { mutableStateOf(0) }
    val pages = ((observations.size + 29) / 30).coerceAtLeast(1)
    val currentPage = page.coerceIn(0, pages - 1)
    val end = (observations.size - currentPage * 30).coerceAtLeast(0)
    val start = (end - 30).coerceAtLeast(0)
    val visible = observations.subList(start, end)
    Text("Ranked history", style = MaterialTheme.typography.headlineLarge)
    HistoryIdentityLabel(selected)
    RecordingStatus(record, rankedState)
    historyState.error?.let { HistoryNotice("History storage", it) }
    Text("Saved RP samples are historical evidence, not live player state. Times are shown in UTC, including observation seconds. Match count, wins, kills, damage and placement are unknown.")
    val context = record.contexts.firstOrNull { it.id == record.currentContextId && it.identity == selected.identity && it.source == selected.source }
    Text(context?.let { "Current context: ${it.season} / ${it.split} · explicitly USER_CONFIRMED" }
        ?: "Current season/split context: unknown. Future observations need explicit context confirmation in Settings before intervals can be linked.")
    if (observations.isEmpty()) {
        HistoryNotice("No saved observations", "Enable local recording in Settings, then save manual RP or successfully refresh a linked profile. Cached snapshots and opening this screen do not create observations.")
    } else {
        Text("RP over time", style = MaterialTheme.typography.titleLarge)
        Text("${observations.size} saved observations for this source. Chart and list show ${start + 1}–$end, in chronological order.")
        RpTrend(visible)
        Text("Points are recorded observations. Lines connect compatible contexts only. Unknown contexts, quarantined samples and long gaps remain visually separate.",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = currentPage < pages - 1, onClick = { page = currentPage + 1 }) { Text("Older samples") }
            OutlinedButton(enabled = currentPage > 0, onClick = { page = currentPage - 1 }) { Text("Newer samples") }
        }
        visible.forEachIndexed { index, observation ->
            val previous = observations.getOrNull(start + index - 1)
            val interval = previous?.let { rpInterval(it, observation) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${observation.rp} RP · ${historyTime(observation.observedAtSeconds)}", style = MaterialTheme.typography.titleMedium)
                    Text("${sourceLabel(observation.source)} · ${observation.quality.name}", style = MaterialTheme.typography.labelMedium)
                    Text("${observation.season ?: "Season unknown"} / ${observation.split ?: "Split unknown"} · ${observation.verification.name}")
                    Text(if (interval == null) "Baseline · RP interval unknown" else signedRp(interval.deltaRp), style = MaterialTheme.typography.titleMedium)
                    interval?.let { Text(intervalExplanation(it), style = MaterialTheme.typography.bodySmall) }
                    if (observation.observedAtSeconds > now) Text("Observation time is ahead of the device clock. Check the device time.")
                }
            }
        }
    }
}

@Composable
private fun RpTrend(observations: List<RpObservation>) {
    if (observations.isEmpty()) return
    val low = observations.minOf { it.rp }
    val high = observations.maxOf { it.rp }
    val start = observations.first().observedAtSeconds
    val end = observations.last().observedAtSeconds
    val primary = MaterialTheme.colorScheme.primary
    val unknown = MaterialTheme.colorScheme.tertiary
    val quarantine = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    val description = "RP observation chart. ${observations.size} points. Range $low to $high RP. ${historyTime(start)} to ${historyTime(end)}. Exact values and quality appear in the list below."
    Text("$high RP", style = MaterialTheme.typography.labelMedium)
    Canvas(Modifier.fillMaxWidth().height(180.dp).semantics { contentDescription = description }) {
        val inset = 8.dp.toPx()
        val width = (size.width - inset * 2).coerceAtLeast(1f)
        val height = (size.height - inset * 2).coerceAtLeast(1f)
        fun position(o: RpObservation) = Offset(
            inset + if (end == start) width / 2 else ((o.observedAtSeconds - start).toDouble() / (end - start) * width).toFloat(),
            inset + if (high == low) height / 2 else (1.0 - (o.rp - low).toDouble() / (high - low)).toFloat() * height,
        )
        drawLine(grid, Offset(inset, inset + height), Offset(inset + width, inset + height), 1.dp.toPx())
        observations.zipWithNext().forEach { (older, newer) ->
            val interval = rpInterval(older, newer)
            if (interval.deltaRp != null && interval.quality != IntervalQuality.LONG_GAP)
                drawLine(primary, position(older), position(newer), 2.dp.toPx())
        }
        observations.forEach { o ->
            val color = when (o.quality) {
                ObservationQuality.VALID -> primary
                ObservationQuality.UNKNOWN_CONTEXT -> unknown
                ObservationQuality.QUARANTINED -> quarantine
            }
            drawCircle(color, 4.dp.toPx(), position(o))
        }
    }
    Text("$low RP", style = MaterialTheme.typography.labelMedium)
    Text("${historyTime(start)} → ${historyTime(end)}", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ColumnScope.SessionsScreen(history: HistoryRepository, ranked: RankedRepository, now: Long) {
    val historyState by history.state.collectAsState()
    val rankedState by ranked.state.collectAsState()
    val record = historyState.record
    val selected = historySelection(rankedState)
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf<String?>(null) }
    var detailsId by rememberSaveable { mutableStateOf<String?>(null) }
    var visibleCount by rememberSaveable(selected.identity, selected.source) { mutableStateOf(15) }
    val active = record.sessions.firstOrNull { it.endedAtSeconds == null }
    val sessions = record.sessions.filter { it.identity == selected.identity && it.source == selected.source }
        .sortedByDescending { it.startedAtSeconds }
    val observations = selectedObservations(record, selected)
    Text("Ranked sessions", style = MaterialTheme.typography.headlineLarge)
    HistoryIdentityLabel(selected)
    RecordingStatus(record, rankedState)
    Text("You choose when a session starts and ends. Session boundaries are not match boundaries. One session can be active at a time. All timestamps below are UTC.")
    historyState.error?.let { HistoryNotice("History storage", it) }
    actionError?.let { HistoryNotice("Session not saved", it) }
    if (active == null) {
        Button(enabled = record.recording && !historyState.readOnly, onClick = { scope.launch {
            try {
                actionError = if (history.startSession(selected.identity, selected.source)) null else "Could not start a durable session. Check history storage and recording settings."
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { actionError = "Could not start a durable session. Previous saved data is retained." }
        } }) { Text("Start session") }
        Text("Starting does not create an RP sample or make a network request. A valid available sample may be used as an anchor.", style = MaterialTheme.typography.bodySmall)
    } else {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ACTIVE SESSION", style = MaterialTheme.typography.labelLarge)
                // Never display another player's observations after a profile/source switch.
                if (active.identity == selected.identity && active.source == selected.source) {
                    Text("Started ${historyTime(active.startedAtSeconds)}")
                    Text("Elapsed ${elapsed(now - active.startedAtSeconds)}", style = MaterialTheme.typography.headlineSmall)
                    val latest = observations.lastOrNull { it.observedAtSeconds <= now && it.contextId == active.contextId }
                    latest?.let { Text("Latest saved observation: ${it.rp} RP · ${historyTime(it.observedAtSeconds)}") }
                    val result = sessionResult(active.copy(endedAtSeconds = maxOf(now, active.startedAtSeconds), endObservationId = latest?.id), record.observations)
                    Text("Current observation result: ${signedRp(result.deltaRp)}")
                    Text(result.explanation)
                } else Text("A session is active for another identity or source. Its observations are hidden. Stop it before starting a session here.")
                Button(enabled = !historyState.readOnly, onClick = { scope.launch {
                    try {
                        actionError = if (history.stopSession()) null else "Could not stop and save this session. The active session remains available."
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { actionError = "Could not stop and save this session. The active session remains available." }
                } }) { Text("Stop session") }
                Text("Stopping works offline using saved observations. A missing or incompatible end sample leaves RP unknown.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text("Previous sessions", style = MaterialTheme.typography.titleLarge)
    val completed = sessions.filter { it.endedAtSeconds != null }
    if (completed.isEmpty()) Text("No completed sessions for this identity and source.")
    completed.take(visibleCount).forEach { session ->
        val result = sessionResult(session, record.observations)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(historyTime(session.startedAtSeconds), style = MaterialTheme.typography.titleMedium)
                Text("${result.classification.name} · ${signedRp(result.deltaRp)}")
                Text("Session duration ${elapsed(session.endedAtSeconds!! - session.startedAtSeconds)}")
                TextButton(onClick = { detailsId = session.id }) { Text("Session details") }
            }
        }
    }
    if (completed.size > visibleCount) TextButton(onClick = { visibleCount += 15 }) { Text("Show older sessions") }
    val detail = sessions.firstOrNull { it.id == detailsId }
    if (detail != null) {
        val result = sessionResult(detail, record.observations)
        AlertDialog(onDismissRequest = { detailsId = null }, title = { Text("Session details") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sourceLabel(detail.source))
                Text("${result.classification.name} · ${signedRp(result.deltaRp)}")
                Text("Started ${historyTime(detail.startedAtSeconds)}")
                Text("Ended ${detail.endedAtSeconds?.let { historyTime(it) } ?: "Still active"}")
                detail.endedAtSeconds?.let { Text("Entire session: ${elapsed(it - detail.startedAtSeconds)}") }
                Text("Observation window: ${result.windowStartSeconds?.let { historyTime(it) } ?: "Unknown"} → ${result.windowEndSeconds?.let { historyTime(it) } ?: "Unknown"}")
                Text(result.explanation)
                Text("Time-complete does not mean RP-complete. No individual match result is inferred.")
            }
        }, confirmButton = { TextButton(onClick = { detailsId = null }) { Text("Close") } })
    }
}

@Composable
fun ColumnScope.HistorySettingsScreen(history: HistoryRepository, ranked: RankedRepository, documents: HistoryDocuments?, now: Long) {
    val historyState by history.state.collectAsState()
    val rankedState by ranked.state.collectAsState()
    val record = historyState.record
    val selected = historySelection(rankedState)
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImportPreview?>(null) }
    val providerSeason = if (selected.source == ObservationSource.PROVIDER) rankedState.profile?.season else null
    var season by rememberSaveable(selected.identity, selected.source, providerSeason) { mutableStateOf(providerSeason.orEmpty()) }
    var split by rememberSaveable(selected.identity, selected.source) { mutableStateOf("") }
    var confirmContext by rememberSaveable(selected.identity, selected.source, providerSeason) { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) { scope.launch {
        busy = true
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { message = "The operation did not complete. No unconfirmed import was applied. Check the selected file and local storage." }
        finally { busy = false }
    } }
    Text("Local history and backup", style = MaterialTheme.typography.titleLarge)
    HistoryIdentityLabel(selected)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Record local RP history", Modifier.weight(1f).padding(end = 12.dp))
        Switch(checked = record.recording, enabled = !busy && !historyState.readOnly,
            onCheckedChange = { enabled -> action {
                message = if (history.setRecording(enabled)) (if (enabled) "Local recording enabled for future observations." else "Recording stopped. Saved history is retained.")
                    else "Recording settings could not be saved."
            } }, modifier = Modifier.semantics { contentDescription = "Record local RP history" })
    }
    RecordingStatus(record, rankedState)
    Text("Opt-in history stores RP, UTC observation times, source, season/split context, player UID/platform for provider samples, and session boundaries privately on this device. It is never uploaded or placed in widget snapshots. Turning recording off retains saved history and closes an active session with its uncertainty visible.")
    historyState.error?.let { HistoryNotice("History storage", it) }
    message?.let { HistoryNotice("History action", it) }
    HorizontalDivider()
    Text("Confirm season and split", style = MaterialTheme.typography.titleMedium)
    val current = record.contexts.firstOrNull { it.id == record.currentContextId && it.identity == selected.identity && it.source == selected.source }
    Text(current?.let { "USER_CONFIRMED · ${it.season} / ${it.split} · ${historyTime(it.confirmedAtSeconds)}" }
        ?: "No confirmed current context for this identity and source.")
    Text(providerSeason?.let { "Provider season label: $it. The provider does not verify the split; your confirmation remains user-supplied." }
        ?: "No provider-verified season/split context is available. Confirm only a context you know; dates and rank labels do not prove a split.")
    val seasonMismatch = providerSeason != null && season.trim() != providerSeason
    OutlinedTextField(value = season, onValueChange = { if (it.length <= 100) season = it }, label = { Text("Confirmed season identity") },
        isError = seasonMismatch, singleLine = true, modifier = Modifier.fillMaxWidth())
    if (seasonMismatch) Text("Use the exact provider season label: $providerSeason. A conflicting season cannot be confirmed.", color = MaterialTheme.colorScheme.error)
    OutlinedTextField(value = split, onValueChange = { if (it.length <= 100) split = it }, label = { Text("Confirmed split identity") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(enabled = record.recording && !busy && !historyState.readOnly && !seasonMismatch && validHistoryText(season.trim()) && validHistoryText(split.trim()),
        onClick = { confirmContext = true }) { Text("Review context confirmation") }
    Text("Enable local recording before confirming context. Each confirmation establishes a new baseline for future samples. Unknown or quarantined historical observations keep their original context and RP. Reconfirm after an actual split/reset or season change.", style = MaterialTheme.typography.bodySmall)
    if (confirmContext) AlertDialog(onDismissRequest = { confirmContext = false }, title = { Text("Confirm current context?") }, text = {
        Text("I confirm season ${season.trim()} / split ${split.trim()} for this identity and source. This is USER_CONFIRMED information, not upstream verification. Existing observations will not be rewritten; the next saved observation starts a new baseline.", modifier = Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = {
        confirmContext = false
        action {
            message = if (history.confirmContext(selected.identity, selected.source, season.trim(), split.trim(), providerSeason)) "Context confirmed. Future observations use a new baseline." else "Context could not be saved."
        }
    }) { Text("Confirm context") } }, dismissButton = { TextButton(onClick = { confirmContext = false }) { Text("Cancel") } })
    HorizontalDivider()
    Text("Portable JSON backup", style = MaterialTheme.typography.titleMedium)
    Text("Backup includes saved observations and sessions, context/identity metadata, personal goals and manual settings. It may contain player UIDs. API keys, raw provider responses, caches, screenshots and device widget identifiers are excluded. Choose where to save the file; it is never shared automatically.")
    Button(enabled = documents != null && !busy && !historyState.readOnly, onClick = { action {
        message = if (documents!!.exportJson(history.exportBackup())) "Backup saved to the selected document." else "Export canceled or could not be saved."
    } }) { Text("Export backup") }
    Button(enabled = documents != null && !busy && !historyState.readOnly, onClick = { action {
        val value = documents!!.importJson()
        if (value == null) message = "Import canceled."
        else {
            preview = history.previewImport(value, rankedState.profile?.identity?.historyIdentity())
            message = null
        }
    } }) { Text("Import backup") }
    if (documents == null) Text("The native document picker is unavailable in this host.")
    Text("Import previews the file before confirmation. A foreign linked identity is rejected. Restoring never connects a profile or enables recording automatically. Existing valid history is merged by stable IDs; conflicting IDs are rejected. Portable goal/manual settings from the backup replace those settings only after confirmation.", style = MaterialTheme.typography.bodySmall)
    preview?.let { candidate ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Import preview") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${candidate.observationCount} observations · ${candidate.sessionCount} sessions")
                Text("Exported ${historyTime(candidate.backup.exportedAtSeconds)}")
                if (candidate.alreadyImported) Text("This backup was already imported. Repeating the import adds no duplicates.")
                candidate.warnings.forEach { Text(it) }
                Text("Confirm to merge this history and restore the included personal goal/manual settings. No profile will be linked.")
            }
        }, confirmButton = { TextButton(onClick = {
            preview = null
            action { message = if (ranked.importHistory(candidate)) "Backup imported. Duplicate records were not added." else "Import could not be applied. Existing saved data is retained." }
        }) { Text("Confirm import") } }, dismissButton = { TextButton(onClick = { preview = null }) { Text("Cancel") } })
    }
    HorizontalDivider()
    Text("Delete saved history", style = MaterialTheme.typography.titleMedium)
    Text("All saved identities: ${record.observations.size} observations · ${record.sessions.size} sessions. Disconnecting a profile retains isolated history; another linked player does not see it. You can delete it here. Your independent personal goal and manual RP are preserved.")
    OutlinedButton(enabled = !busy, onClick = { confirmDelete = true }) { Text("Delete saved history") }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete all saved history?") }, text = {
        Text("Delete every saved RP observation, session (including an active session), context confirmation and import marker for all players on this device, and turn recording off. Your personal goal/manual RP, current profile and Maps/Predator caches are preserved. Existing exported files are not deleted. This cannot be undone without your backup.", modifier = Modifier.verticalScroll(rememberScrollState()))
    }, confirmButton = { TextButton(onClick = {
        confirmDelete = false
        action { message = if (history.deleteHistory()) "Saved history deleted. Recording is off; goal and manual RP remain." else "Saved history could not be deleted. Existing records are retained." }
    }) { Text("Delete history") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun HistoryNotice(title: String, body: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body)
        }
    }
}
