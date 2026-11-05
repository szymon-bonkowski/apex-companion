package io.github.szymonbonkowski.apexcompanion.ranked

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import io.github.szymonbonkowski.apexcompanion.appearance.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.szymonbonkowski.apexcompanion.formatRotationTime
import io.github.szymonbonkowski.apexcompanion.maps.DataState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt

@Composable
fun ColumnScope.RankedScreen(repository: RankedRepository, now: Long, today: LocalDate) {
    val state by repository.state.collectAsState()
    val view = repository.view(now, today)
    val manualSelected = state.useManual || state.profile == null
    val scope = rememberCoroutineScope()
    var actionError by remember { mutableStateOf(false) }
    fun act(block: suspend () -> Unit) { scope.launch {
        try { block(); actionError = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { actionError = true }
    } }
    Text("Ranked", style = MaterialTheme.typography.headlineLarge)
    Text("Your next goal, one day at a time.", style = MaterialTheme.typography.bodyLarge)
    ThemedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LocalThemeTokens.current.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${view.state.name} · ${view.source}", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text(if (manualSelected) "Personal ranked progress" else
                view.snapshot?.identity?.let { "${it.name.ifBlank { "Linked player" }} · ${it.platform.label}" }
                    ?: "Personal ranked progress", style = MaterialTheme.typography.titleMedium)
            if (manualSelected && state.profile != null) Text("Linked profile is retained; its provider RP is not selected.",
                style = MaterialTheme.typography.bodySmall)
            // Original typographic badge; no proprietary rank artwork.
            Surface(shape = LocalThemeTokens.current.shape, color = MaterialTheme.colorScheme.primaryContainer) {
                Text(if (manualSelected) "Manual RP" else
                    listOfNotNull(view.snapshot?.rankName, view.snapshot?.division?.let { "Division $it" })
                        .joinToString(" · ").ifBlank { "Rank unavailable" },
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.titleLarge)
            }
            Text(view.currentRp?.let { "$it RP" } ?: "RP unavailable", style = MaterialTheme.typography.displaySmall)
            Text("Last successful update: ${view.observedAtSeconds?.let { formatRotationTime(it) } ?: "Never"}")
            if (manualSelected) Text("Entered by you. This value is separate from provider RP.")
            if (view.state == DataState.STALE) Text("Cached provider information may have changed, including after a season or split reset.")
            if (view.state == DataState.UNAVAILABLE) Text(
                if (!manualSelected && state.profile != null)
                    "The provider did not supply ranked RP. Unranked, private or temporarily unavailable data cannot be distinguished from these fields. No RP value is assumed."
                else "Connect a public profile or enter manual RP below.")
            if (!manualSelected) view.snapshot?.ladderPosition?.let { Text("Provider ladder position: $it") }
        }
    }
    state.error?.let { RankedNotice("Profile update", rankedErrorMessage(it, state.retryAtSeconds)) }
    if (actionError) RankedNotice("Changes not saved", "Local storage could not be updated. Your previous saved values are retained. Try again.")
    if (state.profile != null) {
        Button(enabled = !state.refreshing && state.error != RankedError.FORBIDDEN &&
            (state.retryAtSeconds == null || now >= state.retryAtSeconds!!),
            onClick = { act { repository.refresh(force = true) } }) {
            Text(if (state.refreshing) "Refreshing profile…" else "Refresh ranked profile")
        }
    }
    Text("Personal goal", style = MaterialTheme.typography.titleLarge)
    if (view.goal == null) Text("Set a target RP to calculate your progress. Your goal stays local and survives profile changes.")
    view.goal?.let { goal ->
        ThemedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(LocalThemeTokens.current.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Target ${goal.targetRp} RP", style = MaterialTheme.typography.titleLarge)
                Text(goal.deadline?.let { "Target date: $it" } ?: "No deadline")
                view.progress?.let { progress ->
                    val percent = (progress.fraction * 100).roundToInt()
                    Text("$percent% of goal progress", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Goal progress $percent percent" })
                    Text(progress.remainingRp?.let { "$it RP remaining" } ?: "Enter current RP to calculate remaining RP")
                    progress.rpPerDay?.let { Text("Required average: $it RP/day") }
                    Text(when (progress.status) {
                        GoalStatus.ACHIEVED -> "Target reached"
                        GoalStatus.COMPLETED -> "Goal marked complete"
                        GoalStatus.EXPIRED -> "Target date has passed. Choose a new date to recalculate your daily goal."
                        GoalStatus.NO_CURRENT -> "Current RP unavailable"
                        GoalStatus.ACTIVE -> if (goal.deadline == today) "Due today" else "Goal in progress"
                    })
                }
                if (!goal.completed) TextButton(onClick = { act { check(repository.completeGoal()) } }) { Text("Mark goal complete") }
                TextButton(onClick = { act { check(repository.saveGoal(null)) } }) { Text("Reset goal") }
            }
        }
    }
    GoalEditor(repository, view.goal, view.currentRp, today)
    ManualRpEditor(repository, state.manualRp, state.useManual, state.profile != null)
    Text("Apex Predator cutoff", style = MaterialTheme.typography.titleLarge)
    val platform = state.profile?.identity?.platform
    val cutoff = state.predator.firstOrNull { it.platform == platform }
    if (platform == null) {
        Text("Connect a profile to compare against the cutoff for its platform.")
    } else if (cutoff == null) {
        Text("No validated Predator cutoff is available for ${platform.label}.")
    } else {
        ThemedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(LocalThemeTokens.current.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val fresh = state.predatorError == null && now >= cutoff.observedAtSeconds &&
                    now - cutoff.observedAtSeconds < PREDATOR_FRESH_SECONDS
                Text("${if (fresh) "LIVE" else "STALE"} · Apex Legends Status · ${platform.label}",
                    style = MaterialTheme.typography.labelLarge)
                Text("${cutoff.rp} RP", style = MaterialTheme.typography.headlineMedium)
                view.currentRp?.let { rp ->
                    val difference = cutoff.rp - rp
                    Text(if (difference > 0) "$difference RP below the displayed cutoff" else
                        "${-difference} RP at or above the displayed cutoff")
                }
                cutoff.masterPopulation?.let { Text("Master + Predator population: $it") }
                Text("Last successful update: ${formatRotationTime(cutoff.observedAtSeconds)}")
            }
        }
    }
    Text("The cutoff changes. Reaching this displayed RP does not guarantee a Predator position.",
        style = MaterialTheme.typography.bodySmall)
    state.predatorError?.let { RankedNotice("Predator update", rankedErrorMessage(it, state.predatorRetryAtSeconds)) }
    Button(enabled = !state.predatorForbidden &&
        (state.predatorRetryAtSeconds == null || now >= state.predatorRetryAtSeconds!!),
        onClick = { act { repository.refreshPredator(force = true) } }) { Text("Refresh Predator cutoff") }
}

@Composable
private fun ManualRpEditor(repository: RankedRepository, manualRp: Long?, useManual: Boolean, linked: Boolean) {
    var input by rememberSaveable(manualRp) { mutableStateOf(manualRp?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ThemedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LocalThemeTokens.current.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Offline manual RP", style = MaterialTheme.typography.titleMedium)
            Text("Manual values stay separate from the linked profile and are never uploaded.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = input, onValueChange = { input = it; error = null }, label = { Text("Manual current RP") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Button(onClick = {
                val value = input.toLongOrNull()
                if (value == null || value !in 0..MAX_RP) { error = "Enter a whole RP value from 0 to $MAX_RP." }
                else scope.launch {
                    try { check(repository.setManualRp(value)); check(repository.setUseManual(true)); error = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Manual RP could not be saved." }
                }
            }) { Text("Save and use manual RP") }
            if (linked) {
                Text(if (useManual) "Goal source: MANUAL" else "Goal source: linked provider profile")
                TextButton(onClick = { scope.launch {
                    try { check(repository.setUseManual(!useManual)); error = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { error = "Source preference could not be saved." }
                } }) { Text(if (useManual) "Use linked profile RP" else "Use saved manual RP") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun GoalEditor(repository: RankedRepository, goal: RankGoal?, currentRp: Long?, today: LocalDate) {
    var target by rememberSaveable(goal?.targetRp) { mutableStateOf(goal?.targetRp?.toString().orEmpty()) }
    var date by rememberSaveable(goal?.deadline) { mutableStateOf(goal?.deadline?.toString().orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    ThemedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LocalThemeTokens.current.cardPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (goal == null) "Create goal" else "Edit goal", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = target, onValueChange = { target = it; error = null }, label = { Text("Target RP") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = date, onValueChange = { date = it; error = null }, label = { Text("Target date (YYYY-MM-DD, optional)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Today: $today · ${TimeZone.currentSystemDefault().id}. Leave the date empty for no deadline.",
                style = MaterialTheme.typography.bodySmall)
            Text("Verified season rank thresholds are unavailable. Set a personal numeric RP target.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = {
                val rp = target.toLongOrNull()
                val deadline = if (date.isBlank()) null else runCatching { LocalDate.parse(date.trim()) }.getOrNull()
                when {
                    rp == null || rp !in 0..MAX_RP -> error = "Enter a whole target RP from 0 to $MAX_RP."
                    date.isNotBlank() && deadline == null -> error = "Use a valid date in YYYY-MM-DD format."
                    currentRp == null && goal == null -> error = "Enter manual RP or connect a ranked profile before creating a goal."
                    else -> scope.launch {
                        try {
                            check(repository.saveGoal(RankGoal(rp, goal?.baselineRp ?: currentRp!!, deadline)))
                            error = null
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "The goal could not be saved." }
                    }
                }
            }) { Text("Save goal") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
fun ColumnScope.ProfileScreen(repository: RankedRepository, now: Long) {
    val state by repository.state.collectAsState()
    var value by rememberSaveable { mutableStateOf("") }
    var byUid by rememberSaveable { mutableStateOf(false) }
    var platformName by rememberSaveable { mutableStateOf(ApexPlatform.PC.name) }
    val platform = ApexPlatform.valueOf(platformName)
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Text("Player profile", style = MaterialTheme.typography.headlineLarge)
    Text("Link a public Apex profile. No Apex Companion account or EA password is required.")
    if (state.refreshing) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Looking up the selected public profile…")
    }
    state.profile?.let { profile ->
        ThemedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(LocalThemeTokens.current.cardPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val fresh = state.error == null && now >= profile.observedAtSeconds &&
                    now - profile.observedAtSeconds < PROFILE_FRESH_SECONDS
                Text("${if (fresh) "LIVE" else "STALE"} · Apex Legends Status", style = MaterialTheme.typography.labelLarge)
                Text(profile.identity.name.ifBlank { "Linked player" }, style = MaterialTheme.typography.headlineSmall)
                Text("Platform: ${profile.identity.platform.label}")
                Text("UID: ${profile.identity.uid}")
                profile.level?.let { Text("Provider level: $it") }
                Text("Last successful update: ${formatRotationTime(profile.observedAtSeconds)}")
                TextButton(onClick = { scope.launch {
                    try { check(repository.disconnect()); localError = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { localError = "Profile could not be disconnected from local storage." }
                } }) { Text("Disconnect profile") }
                Text("Disconnect removes this profile and its cached snapshot. Saved history is retained privately and hidden until its matching profile is linked again. Delete saved history in Settings. Your personal goal and manual RP remain.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Text(if (state.profile == null) "Find your profile" else "Change linked profile", style = MaterialTheme.typography.titleLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ApexPlatform.entries.forEach { option ->
            FilterChip(selected = platform == option, onClick = { platformName = option.name; if (option == ApexPlatform.SWITCH) byUid = true; localError = null },
                label = { Text(option.label) })
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !byUid, enabled = platform != ApexPlatform.SWITCH, onClick = { byUid = false }, label = { Text("EA account name") })
        FilterChip(selected = byUid, onClick = { byUid = true }, label = { Text("Player UID") })
    }
    OutlinedTextField(value = value, onValueChange = { value = it; localError = null },
        label = { Text(if (byUid) "Player UID" else "EA account name") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (byUid) KeyboardType.Number else KeyboardType.Text),
        modifier = Modifier.fillMaxWidth())
    if (platform == ApexPlatform.PC) Text("For PC, use the linked EA account name. Your Steam display name may not work.",
        style = MaterialTheme.typography.bodySmall)
    if (platform == ApexPlatform.SWITCH) Text("Nintendo Switch requires UID lookup; name lookup is unsupported by the provider.",
        style = MaterialTheme.typography.bodySmall)
    Text("Your selection stays on this device. Only the selected name or UID and platform are sent to the data provider when you connect or refresh.",
        style = MaterialTheme.typography.bodySmall)
    Button(enabled = !state.refreshing, onClick = {
        val lookup = runCatching { ProfileLookup(value.trim(), platform, byUid) }.getOrNull()
        if (lookup == null) localError = if (byUid) "Enter a numeric player UID (up to 64 digits)." else "Enter an account name (up to 100 characters)."
        else scope.launch {
            try {
                val outcome = repository.connect(lookup)
                localError = if (outcome == io.github.szymonbonkowski.apexcompanion.maps.RefreshOutcome.SKIPPED)
                    "Please wait before another lookup. Existing request cooldown applies across profile changes." else null
                if (outcome == io.github.szymonbonkowski.apexcompanion.maps.RefreshOutcome.UPDATED) repository.refreshPredator()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { localError = "Profile could not be saved. Your previous profile is retained." }
        }
    }) { Text(if (state.refreshing) "Connecting…" else "Connect profile") }
    localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    state.error?.let { RankedNotice("Profile connection", rankedErrorMessage(it, state.retryAtSeconds)) }
    Text("Subsequent synchronization uses the resolved UID. Display names can change.", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun ColumnScope.RankedSettingsScreen(repository: RankedRepository) {
    val state by repository.state.collectAsState()
    Text("Settings", style = MaterialTheme.typography.headlineLarge)
    Text("Ranked data source", style = MaterialTheme.typography.titleLarge)
    Text(if (state.useManual || state.profile == null) "MANUAL · Local user-entered RP" else "Linked profile · Apex Legends Status")
    ManualRpEditor(repository, state.manualRp, state.useManual, state.profile != null)
    RankedNotice("Local dates", "Goals use calendar dates in ${TimeZone.currentSystemDefault().id}. Changing your system timezone changes the current local day used for daily RP calculations.")
    RankedNotice("Privacy", "Profiles, goals and manual RP are stored locally. No analytics, advertisements, or mandatory account. Widget projections omit the player's name and UID.")
}

@Composable
private fun RankedNotice(title: String, body: String) {
    ThemedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(LocalThemeTokens.current.cardPadding), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

fun rankedErrorMessage(error: RankedError, retryAtSeconds: Long?): String {
    val message = when (error) {
        RankedError.ACCESS_NOT_CONFIGURED -> "Online access is unavailable in this build or device setup. Manual RP and goals work offline."
        RankedError.FORBIDDEN -> "Provider authentication or permission was denied. Access configuration must be resolved before retrying."
        RankedError.NOT_FOUND -> "No player was found for this name or UID and platform. Your existing profile has been retained."
        RankedError.PRIVATE -> "The provider could not supply this player's private or unavailable data."
        RankedError.UNSUPPORTED_PLATFORM -> "The provider does not support this platform and lookup method. Try a supported UID lookup."
        RankedError.GONE -> "The provider endpoint or profile is no longer available."
        RankedError.RATE_LIMITED -> "The provider asked us to wait. Cached data is retained."
        RankedError.TIMEOUT -> "The request timed out. Cached data is retained."
        RankedError.NETWORK -> "The provider could not be reached. Cached data is retained."
        RankedError.SERVER -> "The provider is temporarily unavailable. Cached data is retained."
        RankedError.SCHEMA -> "The provider returned unsupported data. The last validated snapshot is retained."
        RankedError.STORAGE -> "Local data could not be read or saved. Try again before leaving the app."
    }
    return message + if (retryAtSeconds != null && error !in listOf(RankedError.ACCESS_NOT_CONFIGURED, RankedError.FORBIDDEN))
        " Retry after ${formatRotationTime(retryAtSeconds)}." else ""
}
