package io.github.szymonbonkowski.apexcompanion.matches

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.szymonbonkowski.apexcompanion.appearance.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.szymonbonkowski.apexcompanion.history.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.round
import kotlin.random.Random
import kotlin.time.Instant

private fun matchTime(seconds: Long) = Instant.fromEpochSeconds(seconds).toString().replace("T", " ").replace("Z", " UTC")
private fun matchNumber(value: Int?) = value?.toString() ?: "Unknown"
private fun average(metric: MatchMetric): String = metric.average?.let { (round(it * 100) / 100).toString() } ?: "Unknown"
private fun provenance(record: MatchRecord) = "${record.source.name} · ${record.quality.name}"

@Composable
private fun MatchNotice(title: String, message: String) {
    ThemedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LocalThemeTokens.current.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(message)
        }
    }
}

@Composable
private fun MatchChoices(label: String, choices: List<String>, selected: String?, change: (String?) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected == null, { change(null) }, label = { Text("All $label") })
        choices.forEach { choice -> FilterChip(selected == choice, { change(choice) }, label = { Text(choice) }) }
    }
}

/** Existing parent provides the scrolling Column; no platform APIs or in-memory storage fallback. */
@Composable
fun ColumnScope.MatchHistoryScreen(repository: MatchRepository, linkedIdentity: HistoryIdentity?, documents: HistoryDocuments?, now: Long, screenshots: MatchScreenshotPicker? = null) {
    val state by repository.state.collectAsState()
    val scope = rememberCoroutineScope()
    var independent by rememberSaveable(linkedIdentity) { mutableStateOf(linkedIdentity == null) }
    val identity = if (independent) null else linkedIdentity
    val records = state.records.filter { it.identity == identity }.sortedByDescending { it.timestampSeconds }
    var page by rememberSaveable(identity) { mutableStateOf("list") }
    var selectedId by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    var filtersExpanded by rememberSaveable(identity) { mutableStateOf(false) }
    var mode by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    var map by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    var legend by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    var quality by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    var source by rememberSaveable(identity) { mutableStateOf<String?>(null) }
    var offset by rememberSaveable(identity, mode, map, legend, quality, source) { mutableStateOf(0) }
    var preview by remember { mutableStateOf<MatchImportPreview?>(null) }
    var delete by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var screenshotDraft by remember { mutableStateOf<MatchScreenshotDraft?>(null) }
    var screenshotActive by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(screenshotActive, screenshotDraft) {
        if (screenshotActive && screenshotDraft == null) {
            page = "list"; screenshotActive = false; message = "The transient screenshot draft was discarded after restart. Choose the image again; no match was saved."
        }
    }
    val selected = records.firstOrNull { it.id == selectedId }
    Text("Match History", style = MaterialTheme.typography.headlineLarge)
    Text(identity?.let { "${it.platform.label} · UID ${it.uid}" } ?: "Independent journal · no linked player")
    if (linkedIdentity != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(!independent, { independent = false; page = "list" }, label = { Text("Linked player") })
        FilterChip(independent, { independent = true; page = "list" }, label = { Text("Independent journal") })
    }
    val archived = state.records.count { it.identity != null && it.identity != linkedIdentity }
    if (archived > 0) Text("$archived records are archived for other identities. Link their matching player to view them.", style = MaterialTheme.typography.bodySmall)
    Text("Recorded results only. RP observations and sessions never manufacture matches. Missing values stay unknown.", style = MaterialTheme.typography.bodySmall)
    state.error?.let { MatchNotice("Match storage", it) }
    message?.let { Text(it) }
    if (state.readOnly) MatchNotice("Read-only match history", "The original store is retained. Recover the original store before writing. Deleting an unreadable store through this screen is blocked. RP history and goals are separate.")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(page == "list", { page = "list" }, label = { Text("Matches") })
        FilterChip(page == "statistics", { page = "statistics" }, label = { Text("Statistics") })
        Button(onClick = { selectedId = null; screenshotDraft = null; screenshotActive = false; page = "manual" }, enabled = !state.readOnly && !busy) { Text("Add match") }
    }
    if (screenshots != null) OutlinedButton(enabled = !state.readOnly && !busy, onClick = {
        busy = true; scope.launch {
            try {
                val picked = screenshots.pick()
                if (picked == null) message = "Screenshot selection cancelled. No match was saved."
                else { screenshotDraft = picked; screenshotActive = true; selectedId = null; page = "manual"; message = null }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Screenshot could not be read. No match was saved." }
            finally { busy = false }
        }
    }) { Text("Choose results screenshot") }
    when {
        page == "manual" && screenshotActive && screenshotDraft == null -> Text("Discarding transient screenshot draft…")
        page == "manual" || page == "edit" -> MatchEditor(
            original = if (page == "edit") selected else null, identity = identity, now = now,
            screenshot = if (page == "edit") null else screenshotDraft,
            records = records, onCancel = { screenshotDraft = null; screenshotActive = false; page = "list" },
            onSave = { record ->
                val saved = when { page == "edit" -> repository.correct(record); record.source == MatchSource.SCREENSHOT -> repository.confirmScreenshot(record); else -> repository.saveManual(record) }
                if (saved) { screenshotDraft = null; screenshotActive = false; selectedId = record.id; page = "detail" }
                saved
            })
        page == "detail" && selected != null -> {
            MatchDetail(selected, records.any { it.id != selected.id && selected.possibleDuplicate(it) })
            Button(onClick = { page = "edit" }, enabled = !state.readOnly) { Text("Correct and confirm") }
            TextButton(onClick = { page = "list" }) { Text("Back to matches") }
        }
        page == "statistics" -> MatchStatistics(records)
        else -> {
            val activeFilters = listOf(mode, map, legend, quality, source).count { it != null }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { filtersExpanded = !filtersExpanded }) { Text(if (filtersExpanded) "Hide filters" else "Filter matches") }
                if (activeFilters > 0) TextButton(onClick = { mode = null; map = null; legend = null; quality = null; source = null }) { Text("Clear $activeFilters filters") }
            }
            if (filtersExpanded) {
            MatchChoices("modes", records.map { it.mode.name }.distinct().sorted(), mode) { mode = it }
            if (records.any { it.map != null }) MatchChoices("maps", records.mapNotNull { it.map }.distinct().sorted(), map) { map = it }
            if (records.any { it.legend != null }) MatchChoices("legends", records.mapNotNull { it.legend }.distinct().sorted(), legend) { legend = it }
            MatchChoices("provenance", records.map { it.quality.name }.distinct().sorted(), quality) { quality = it }
            MatchChoices("sources", records.map { it.source.name }.distinct().sorted(), source) { source = it }
            }
            val filtered = records.filter { (mode == null || it.mode.name == mode) && (map == null || it.map == map) &&
                (legend == null || it.legend == legend) && (quality == null || it.quality.name == quality) && (source == null || it.source.name == source) }
            Text("${filtered.size} recorded matches", style = MaterialTheme.typography.titleMedium)
            if (filtered.isEmpty()) Text("No matching records. Add a manual result or pair your companion in Capture sync.")
            val safeOffset = offset.coerceIn(0, ((filtered.size - 1).coerceAtLeast(0) / 25) * 25)
            filtered.drop(safeOffset).take(25).forEach { record ->
                ThemedCard(onClick = { selectedId = record.id; page = "detail" }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(LocalThemeTokens.current.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(matchTime(record.timestampSeconds), style = MaterialTheme.typography.titleMedium)
                        Text("${record.mode.name} · ${record.map ?: "Map unknown"} · ${record.legend ?: "Legend unknown"}")
                        Text("Kills ${matchNumber(record.kills)} · Assists ${matchNumber(record.assists)} · Damage ${matchNumber(record.damage)}")
                        Text("Placement ${matchNumber(record.placement)} · RP ${matchNumber(record.rpDelta)}")
                        Text(provenance(record), style = MaterialTheme.typography.labelMedium)
                        if (record.quality == MatchQuality.REVIEW_REQUIRED) Text("Review required · excluded from statistics")
                        if (listOf(record.kills, record.assists, record.damage, record.placement, record.rpDelta).any { it == null }) Text("Incomplete result · some fields unknown", style = MaterialTheme.typography.bodySmall)
                        if (records.any { it.id != record.id && record.possibleDuplicate(it) }) Text("Possible duplicate · review event identity", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (filtered.size > 25) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ offset = (safeOffset - 25).coerceAtLeast(0) }, enabled = safeOffset > 0) { Text("Newer matches") }
                TextButton({ offset = safeOffset + 25 }, enabled = safeOffset + 25 < filtered.size) { Text("Older matches") }
            }
        }
    }
    HorizontalDivider()
    Text("Match backup and deletion", style = MaterialTheme.typography.titleMedium)
    Text("Match backups contain all identities in this journal. They contain no frames, screenshots, pairing secrets or capture settings. RP history and goals use their existing separate backup.", style = MaterialTheme.typography.bodySmall)
    Button(enabled = documents != null && !busy && !state.readOnly, onClick = {
        busy = true; scope.launch {
            try { message = if (documents!!.exportJson(repository.exportBackup())) "Match backup exported." else "Export cancelled." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Export failed. Saved records retained." }
            finally { busy = false }
        }
    }) { Text("Export match backup") }
    Button(enabled = documents != null && !busy && !state.readOnly, onClick = {
        busy = true; scope.launch {
            try { val value = documents!!.importJson(); if (value != null) preview = repository.previewImport(value, linkedIdentity) else message = "Import cancelled." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message = "Import rejected. Check schema, identity, record conflicts and capacity. Saved records retained." }
            finally { busy = false }
        }
    }) { Text("Preview match import") }
    OutlinedButton(enabled = !busy && !state.readOnly, onClick = { delete = true }) { Text("Delete all match history") }
    preview?.let { incoming ->
        AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("Confirm match restore?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${incoming.records.size} incoming records · ${incoming.newCount} new · ${incoming.duplicateWarnings} possible duplicates")
                incoming.warnings.forEach { Text(it) }
                Text("Restored provenance is metadata from an unsigned file. Capture and pairing stay off or unchanged. RP history and goals are preserved.")
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
                try { message = if (repository.applyImport(incoming, linkedIdentity)) "Match restore committed." else "Restore failed; previous committed records retained."; preview = null }
                finally { busy = false }
            } }) { Text("Confirm restore") } }, dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("Cancel") } })
    }
    if (delete) AlertDialog(onDismissRequest = { if (!busy) delete = false }, title = { Text("Delete all match records?") },
        text = { Text("Delete the match journal for every identity on this device. RP observations, sessions, goals and externally saved backups remain. Capture events already received remain suppressed during sync.") },
        confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
            try { message = if (repository.deleteHistory()) "Match history deleted." else "Deletion failed."; delete = false; page = "list" }
            finally { busy = false }
        } }) { Text("Confirm match deletion") } }, dismissButton = { TextButton(enabled = !busy, onClick = { delete = false }) { Text("Cancel") } })
}

@Composable
private fun ColumnScope.MatchDetail(record: MatchRecord, duplicate: Boolean) {
    var evidenceExpanded by rememberSaveable(record.id) { mutableStateOf(record.quality == MatchQuality.REVIEW_REQUIRED) }
    Text("Match details", style = MaterialTheme.typography.headlineSmall)
    DesignCard(Modifier.fillMaxWidth()) {
        Text(provenance(record), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("Map: ${record.map ?: "Unknown"}", style = MaterialTheme.typography.headlineMedium)
        Text("Legend: ${record.legend ?: "Unknown"}", style = MaterialTheme.typography.titleMedium)
        Text("Mode: ${record.mode.name}", style = MaterialTheme.typography.labelLarge)
        Text(matchTime(record.timestampSeconds), style = MaterialTheme.typography.bodySmall)
    }
    Text("Result", style = MaterialTheme.typography.titleLarge)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 420.dp && androidx.compose.ui.platform.LocalDensity.current.fontScale < 1.5f) 2 else 1
        val metrics = listOf("Kills: ${matchNumber(record.kills)}", "Assists: ${matchNumber(record.assists)}", "Damage: ${matchNumber(record.damage)}",
            "Placement: ${matchNumber(record.placement)}", "RP delta: ${matchNumber(record.rpDelta)}")
        Column(verticalArrangement = Arrangement.spacedBy(LocalThemeTokens.current.gap / 2)) {
            metrics.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(LocalThemeTokens.current.gap / 2)) {
                    row.forEach { value -> DesignCard(Modifier.weight(1f)) { Text(value, style = MaterialTheme.typography.titleLarge) } }
                    if (row.size < columns) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    if (record.source != MatchSource.MANUAL) Text("Machine-derived result. AUTO_ACCEPTED is not an official server result; USER_CONFIRMED means a user reviewed the saved fields.")
    if (record.quality == MatchQuality.REVIEW_REQUIRED) MatchNotice("Review required", "This record contributes to no aggregate until confirmed.")
    Text("Unknown means unavailable or unsupported by recognized evidence. It is never counted as zero.", style = MaterialTheme.typography.bodySmall)
    if (duplicate) MatchNotice("Possible duplicate", "Similar fields and nearby timestamps exist. Both events are retained; inspect their identities before correcting.")
    OutlinedButton(onClick = { evidenceExpanded = !evidenceExpanded }) { Text(if (evidenceExpanded) "Hide evidence details" else "Show evidence details") }
    if (evidenceExpanded) DesignCard(Modifier.fillMaxWidth()) {
        Text("Evidence details", style = MaterialTheme.typography.titleMedium)
        Text("Timestamp source: ${record.timestampSource.name}")
        Text("Revision: ${record.revision}")
        record.fieldConfidence.toSortedMap().forEach { (field, confidence) -> Text("$field: ${round(confidence * 100).toInt()}% extraction confidence") }
        Text("Record ID: ${record.id}", style = MaterialTheme.typography.bodySmall)
        record.evidenceEventId?.let { Text("Capture event: $it", style = MaterialTheme.typography.bodySmall) }
        record.parserVersion?.let { Text("Parser/layout: $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ColumnScope.MatchStatistics(records: List<MatchRecord>) {
    var automatic by rememberSaveable { mutableStateOf(false) }
    Text("Recorded match statistics", style = MaterialTheme.typography.headlineSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(automatic, { automatic = it }, modifier = Modifier.semantics { contentDescription = "Include automatically accepted matches" })
        Text("Include AUTO_ACCEPTED records")
    }
    val total = aggregateMatches(records, automatic)
    Text("Policy: ${if (automatic) "user-confirmed and automatically accepted" else "user-confirmed only"}. Review-required records excluded.")
    DesignCard(Modifier.fillMaxWidth()) {
        Text("${total.total} eligible recorded matches · ${total.confirmed} user-confirmed · ${total.automatic} automatically accepted", style = MaterialTheme.typography.titleLarge)
        Text("These are collected records, not total matches played. Each field uses its own known-value denominator.", style = MaterialTheme.typography.bodySmall)
    }
    DesignCard(Modifier.fillMaxWidth()) {
        Text("Your recorded averages", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        AggregateRows(total)
    }
    Text("Map performance", style = MaterialTheme.typography.titleMedium)
    matchGroups(records, false, automatic).filterValues { it.total > 0 }.forEach { (name, aggregate) ->
        DesignCard(Modifier.fillMaxWidth()) { Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); AggregateRows(aggregate) }
    }
    Text("Legend performance", style = MaterialTheme.typography.titleMedium)
    matchGroups(records, true, automatic).filterValues { it.total > 0 }.forEach { (name, aggregate) ->
        DesignCard(Modifier.fillMaxWidth()) { Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary); AggregateRows(aggregate) }
    }
    Text("Session RP and match RP have different coverage. Neither overwrites the other; no session association is inferred.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun AggregateRows(value: MatchAggregate) {
    Text("Average kills ${average(value.kills)} · n=${value.kills.count}")
    Text("Average assists ${average(value.assists)} · n=${value.assists.count}")
    Text("Average damage ${average(value.damage)} · n=${value.damage.count}")
    Text("Average placement ${average(value.placement)} · n=${value.placement.count}")
    Text("RP total ${if (value.rp.count == 0) "Unknown" else value.rp.sum.toString()} · average ${average(value.rp)} · n=${value.rp.count}")
}

@Composable
private fun ColumnScope.MatchEditor(original: MatchRecord?, identity: HistoryIdentity?, now: Long, screenshot: MatchScreenshotDraft?,
    records: List<MatchRecord>, onCancel: () -> Unit, onSave: suspend (MatchRecord) -> Boolean) {
    val scope = rememberCoroutineScope()
    val key = original?.id ?: screenshot?.evidenceEventId ?: "new"
    var mode by rememberSaveable(key) { mutableStateOf(original?.mode?.name ?: screenshot?.fields?.get("mode")?.takeIf { value -> MatchMode.entries.any { it.name == value } } ?: MatchMode.UNKNOWN.name) }
    var map by rememberSaveable(key) { mutableStateOf(original?.map ?: screenshot?.fields?.get("map") ?: "") }
    var legend by rememberSaveable(key) { mutableStateOf(original?.legend ?: screenshot?.fields?.get("legend") ?: "") }
    var kills by rememberSaveable(key) { mutableStateOf(original?.kills?.toString() ?: screenshot?.fields?.get("kills") ?: "") }
    var assists by rememberSaveable(key) { mutableStateOf(original?.assists?.toString() ?: screenshot?.fields?.get("assists") ?: "") }
    var damage by rememberSaveable(key) { mutableStateOf(original?.damage?.toString() ?: screenshot?.fields?.get("damage") ?: "") }
    var placement by rememberSaveable(key) { mutableStateOf(original?.placement?.toString() ?: screenshot?.fields?.get("placement") ?: "") }
    var rp by rememberSaveable(key) { mutableStateOf(original?.rpDelta?.toString() ?: screenshot?.fields?.get("rpDelta") ?: "") }
    var time by rememberSaveable(key) { mutableStateOf((original?.timestampSeconds ?: now).toString()) }
    var candidate by remember { mutableStateOf<MatchRecord?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val id = rememberSaveable(key) { original?.id ?: "manual:${Random.nextLong().toULong().toString(16)}:${Random.nextLong().toULong().toString(16)}" }
    Text(when { original != null -> "Correct match result"; screenshot != null -> "Review screenshot result"; else -> "Manual match entry" }, style = MaterialTheme.typography.headlineSmall)
    Text("Blank optional fields remain unknown. Enter only one observed match. ${if (original != null) "The original source and event identity are retained." else "Saving requires explicit confirmation."}")
    screenshot?.warning?.let { MatchNotice("Screenshot recognition", it) }
    screenshot?.confidence?.toSortedMap()?.forEach { (field, confidence) -> Text("Suggested $field: ${round(confidence * 100).toInt()}% extraction confidence. Confirm edited values explicitly.") }
    if (screenshot != null && records.any { it.evidenceEventId == screenshot.evidenceEventId })
        MatchNotice("Screenshot already recorded", "This image matches a saved evidence identity. Cancel and correct the existing record; saving a second record with this identity will be rejected.")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MatchMode.entries.forEach { item -> FilterChip(mode == item.name, { mode = item.name }, label = { Text(item.name) }, enabled = !busy) }
    }
    MatchTextField(map, { map = it }, "Map (optional)", !busy)
    MatchTextField(legend, { legend = it }, "Legend (optional)", !busy)
    MatchTextField(kills, { kills = it }, "Kills (0–100, optional)", !busy)
    MatchTextField(assists, { assists = it }, "Assists (0–100, optional)", !busy)
    MatchTextField(damage, { damage = it }, "Damage (0–100000, optional)", !busy)
    MatchTextField(placement, { placement = it }, "Placement (1–100, optional)", !busy)
    MatchTextField(rp, { rp = it }, "RP delta (-10000–10000, optional)", !busy)
    MatchTextField(time, { time = it }, "Result timestamp (UTC epoch seconds)", !busy)
    time.toLongOrNull()?.takeIf { it in 1..MAX_HISTORY_TIME }?.let { Text(matchTime(it), style = MaterialTheme.typography.bodySmall) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(enabled = !busy, onClick = {
        try {
            fun optionalNumber(value: String): Int? = if (value.isBlank()) null else value.trim().toInt()
            val timestamp = time.trim().toLong(); require(timestamp in 1..now)
            val parsed = MatchRecord(id, original?.identity ?: identity, MatchMode.valueOf(mode), map.trim().ifEmpty { null }, legend.trim().ifEmpty { null },
                optionalNumber(kills), optionalNumber(assists), optionalNumber(damage), optionalNumber(placement), optionalNumber(rp), timestamp,
                if (original == null || timestamp != original.timestampSeconds) MatchTimestampSource.USER_ENTERED else original.timestampSource,
                original?.source ?: if (screenshot != null) MatchSource.SCREENSHOT else MatchSource.MANUAL, MatchQuality.USER_CONFIRMED, original?.evidenceEventId ?: screenshot?.evidenceEventId, original?.parserVersion ?: screenshot?.parserVersion,
                emptyMap(), if (original == null) 0 else original.revision + 1)
            candidate = parsed; error = null
        } catch (_: Exception) { error = "Check integer values, bounds, text and timestamp. Future timestamps are rejected; blank fields are allowed." }
    }) { Text("Review match before saving") }
    TextButton(enabled = !busy, onClick = onCancel) { Text("Cancel match entry") }
    candidate?.let { parsed ->
        AlertDialog(onDismissRequest = { if (!busy) candidate = null }, title = { Text("Confirm this match?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${parsed.mode.name} · ${matchTime(parsed.timestampSeconds)}")
                Text("Kills ${matchNumber(parsed.kills)} · Assists ${matchNumber(parsed.assists)} · Damage ${matchNumber(parsed.damage)}")
                Text("Placement ${matchNumber(parsed.placement)} · RP ${matchNumber(parsed.rpDelta)}")
                if (records.any { it.id != parsed.id && it.possibleDuplicate(parsed) }) Text("Possible duplicate: a nearby record has matching statistics. Confirm only if this is a distinct match or an intentional correction.")
                Text("I confirm these fields describe one match. Unknown fields remain unknown.")
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = { busy = true; scope.launch {
                try { if (!onSave(parsed)) error = "Match could not be saved. Previous committed records retained."; candidate = null }
                finally { busy = false }
            } }) { Text("Confirm match") } }, dismissButton = { TextButton(enabled = !busy, onClick = { candidate = null }) { Text("Edit fields") } })
    }
}

@Composable
private fun MatchTextField(value: String, change: (String) -> Unit, label: String, enabled: Boolean, secret: Boolean = false) {
    OutlinedTextField(value, { if (it.length <= 180) change(it) }, label = { Text(label) }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
        enabled = enabled, singleLine = true, visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
}

@Composable
fun ColumnScope.MatchSyncScreen(controller: MatchSyncController, identity: HistoryIdentity?) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Pairing credentials are transient and deliberately excluded from saveable instance state.
    var endpoint by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var companion by remember { mutableStateOf("") }
    var consent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Capture sync", style = MaterialTheme.typography.headlineLarge)
    Text("Opt-in private LAN synchronization. Start the Windows companion and explicitly enable its listener; copy pairing details directly from its window. Validate the certificate pin there before pairing.")
    Text("Only accepted match fields are transferred. No screenshots, frames, API keys or RP observations are sent. Capture must be enabled separately on the PC.")
    Text("Windows live Apex capture is not verified. Automatic acceptance is disabled until representative screen accuracy is measured.", style = MaterialTheme.typography.bodySmall)
    if (state.paired) {
        Text("Paired companion: ${state.companionId ?: "Unknown"}", style = MaterialTheme.typography.titleMedium)
        Text(state.pairedIdentity?.let { "Paired scope: ${it.platform.label} UID ${it.uid}" } ?: "Paired scope: independent journal (no player identity)")
        if (state.pairedIdentity != identity) MatchNotice("Different journal scope", "This pairing keeps its original player scope. Records remain isolated; unpair and pair again to change the PC/player scope.")
        Text("Last successful sync: ${state.lastSyncSeconds?.let(::matchTime) ?: "Never"}")
        Button(enabled = !state.busy, onClick = { scope.launch { controller.synchronize() } }) { Text(if (state.busy) "Synchronizing…" else "Sync now") }
        OutlinedButton(enabled = !state.busy, onClick = { scope.launch { controller.unpair() } }) { Text("Unpair companion") }
        Text("Unpair also revoke this device in the companion if the PC is offline. Saved matches remain on this phone.", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(identity?.let { "Pair for ${it.platform.label} UID ${it.uid}" } ?: "Pair for the independent journal (no player identity)")
        MatchTextField(endpoint, { endpoint = it }, "Companion HTTPS endpoint", !state.busy)
        MatchTextField(pin, { pin = it }, "Certificate SHA-256 pin", !state.busy)
        MatchTextField(token, { token = it }, "Pairing token", !state.busy, secret = true)
        MatchTextField(companion, { companion = it }, "Companion ID", !state.busy)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(consent, { consent = it }, modifier = Modifier.semantics { contentDescription = "Allow local companion synchronization" }, enabled = !state.busy)
            Text("Allow this PC to send accepted match records for this identity")
        }
        Button(enabled = consent && !state.busy, onClick = { scope.launch {
            try { if (controller.pair(MatchPairing(endpoint.trim(), pin.trim().lowercase(), token.trim().lowercase(), companion.trim(), identity))) { token = ""; pin = ""; consent = false; controller.synchronize() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Pairing rejected. Check private HTTPS endpoint, certificate pin, token and companion ID." }
        } }) { Text("Pair and synchronize") }
    }
    state.error?.let { MatchNotice("Sync unavailable", it) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Text("Both devices must be on a reachable local network. Results are pulled when the app opens and on Sync now. Offline results remain queued on the PC; Android/iOS do not guarantee instant background delivery.", style = MaterialTheme.typography.bodySmall)
}
