package io.github.szymonbonkowski.apexcompanion.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val preferencesSaver =
    Saver<AppearancePreferences, String>(
        save = { AppearanceCodec.encode(AppearanceRecord(preferences = it)) },
        restore = { runCatching { AppearanceCodec.decode(it).preferences }.getOrNull() },
    )

/** A local draft is previewed using the same theme and components as the application. */
@Composable
fun ColumnScope.AppearanceStudio(
    repository: AppearanceRepository,
    environment: AppearanceEnvironment = AppearanceEnvironment(),
) {
    val state by repository.state.collectAsState()
    val current = state.record.preferences
    var draft by
        rememberSaveable(state.initialized, stateSaver = preferencesSaver) {
            mutableStateOf(current)
        }
    var previousWire by rememberSaveable { mutableStateOf<String?>(null) }
    val previous =
        remember(previousWire) {
            previousWire?.let { runCatching { AppearanceCodec.decode(it).preferences }.getOrNull() }
        }
    var lastSaved by
        rememberSaveable(state.initialized, stateSaver = preferencesSaver) {
            mutableStateOf(current)
        }
    var category by rememberSaveable { mutableStateOf("Design") }
    var customHex by rememberSaveable { mutableStateOf(current.accentHex.orEmpty()) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    var presetDialog by rememberSaveable { mutableStateOf<String?>(null) }
    var presetId by rememberSaveable { mutableStateOf<String?>(null) }
    var presetName by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var recover by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(current) {
        if (current != lastSaved) {
            draft = current
            customHex = current.accentHex.orEmpty()
            lastSaved = current
        }
    }
    fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }
    fun apply() {
        perform {
            previousWire = AppearanceCodec.encode(AppearanceRecord(preferences = current))
            message =
                if (repository.savePreferences(draft))
                    "Appearance applied and saved on this device."
                else "Appearance could not be saved. Previous settings retained."
        }
    }
    Text("Appearance Studio", style = MaterialTheme.typography.headlineLarge)
    Text("Make this space yours.", style = MaterialTheme.typography.titleMedium)
    Text(
        "Preview a design, tune the details, then apply. Your profiles, goals and history stay independent of appearance.",
        style = MaterialTheme.typography.bodyMedium,
    )
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.readOnly) {
        Text(
            "Saved appearance is unreadable or from a newer version. Changes are disabled to preserve the original file.",
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = { recover = true }) { Text("Reset unreadable appearance") }
    }

    DesignTheme(draft, environment) {
        DesignCard(
            Modifier.fillMaxWidth().semantics {
                contentDescription = "Appearance preview ${draft.style.title}. DEMO values only."
            }
        ) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(draft.style.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "DEMO · PREVIEW",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                if (draft.style == DesignStyle.EDITORIAL) "A little closer, every day."
                else if (draft.style == DesignStyle.TACTICAL) "RANKED / OBJECTIVE"
                else "Your next milestone",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text("13,460 RP", style = MaterialTheme.typography.displaySmall)
            LinearProgressIndicator(
                progress = { 0.68f },
                modifier =
                    Modifier.fillMaxWidth().semantics {
                        contentDescription = "DEMO goal progress 68 percent"
                    },
            )
            Text("116 RP/day · Example goal", style = MaterialTheme.typography.bodySmall)
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Button(onClick = { apply() }, enabled = !busy && !state.readOnly && draft != current) {
            Text("Apply appearance")
        }
        OutlinedButton(
            onClick = {
                draft = current
                customHex = current.accentHex.orEmpty()
                message = "Preview returned to your saved appearance."
            },
            enabled = draft != current,
        ) {
            Text("Discard preview")
        }
        previous?.let { last ->
            TextButton(
                onClick = {
                    draft = last
                    customHex = last.accentHex.orEmpty()
                }
            ) {
                Text("Preview previous")
            }
        }
    }
    message?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.semantics { contentDescription = it },
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        listOf("Design", "Colors", "Geometry", "Typography & motion", "Saved presets").forEach {
            name ->
            FilterChip(
                selected = category == name,
                onClick = { category = name },
                label = { Text(name) },
            )
        }
    }
    when (category) {
        "Design" -> {
            Text("Four ways to feel at home", style = MaterialTheme.typography.titleLarge)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns =
                    if (maxWidth >= 500.dp && LocalDensity.current.fontScale < 1.5f) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DesignStyle.entries.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { style ->
                                Box(Modifier.weight(1f)) {
                                    DesignTheme(
                                        AppearancePreferences(
                                            style = style,
                                            mode =
                                                if (style == DesignStyle.EDITORIAL)
                                                    AppearanceMode.LIGHT
                                                else AppearanceMode.SYSTEM,
                                        ),
                                        environment,
                                    ) {
                                        DesignCard(
                                            Modifier.fillMaxWidth().semantics {
                                                selected = draft.style == style
                                            },
                                            onClick = {
                                                draft =
                                                    AppearancePreferences(
                                                        style = style,
                                                        mode =
                                                            if (style == DesignStyle.EDITORIAL)
                                                                AppearanceMode.LIGHT
                                                            else AppearanceMode.SYSTEM,
                                                    )
                                                customHex = ""
                                            },
                                        ) {
                                            Text(
                                                if (draft.style == style) "SELECTED FOR PREVIEW"
                                                else "PREVIEW ${style.title.uppercase()}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                            Text(
                                                style.title,
                                                style = MaterialTheme.typography.headlineMedium,
                                            )
                                            Text(
                                                style.description,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            HorizontalDivider()
                                            Text(
                                                "DEMO · 2,540 RP to go",
                                                style = MaterialTheme.typography.titleMedium,
                                            )
                                            LinearProgressIndicator(
                                                progress = { 0.68f },
                                                modifier = Modifier.fillMaxWidth(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = {
                    draft =
                        AppearancePreferences(
                            style = draft.style,
                            mode =
                                if (draft.style == DesignStyle.EDITORIAL) AppearanceMode.LIGHT
                                else AppearanceMode.SYSTEM,
                        )
                    customHex = ""
                }
            ) {
                Text("Reset design defaults")
            }
            Text(
                "Liquid uses lightweight translucent surfaces and gradients. It does not claim native Liquid Glass or live background blur.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        "Colors" -> {
            StudioChoices(
                "Appearance mode",
                AppearanceMode.entries,
                draft.mode,
                { it.name.lowercase().replaceFirstChar(Char::uppercase) },
            ) {
                draft = draft.copy(mode = it)
            }
            Text("Accent palette", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(
                        "Theme default" to null,
                        "Turquoise" to "44BFA8",
                        "Violet" to "9D8CF5",
                        "Orange" to "EC9564",
                        "Ocean" to "5C9FDB",
                        "Rose" to "D77F9E",
                    )
                    .forEach { (label, hex) ->
                        FilterChip(
                            selected = draft.accentHex == hex && !draft.dynamicColor,
                            onClick = {
                                draft = draft.copy(accentHex = hex, dynamicColor = false)
                                customHex = hex.orEmpty()
                            },
                            label = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (hex != null)
                                        Box(
                                            Modifier.size(12.dp)
                                                .background(
                                                    Color(0xFF000000 or hex.toLong(16)),
                                                    CircleShape,
                                                )
                                        )
                                    Text(label)
                                }
                            },
                        )
                    }
            }
            OutlinedTextField(
                value = customHex,
                onValueChange = { customHex = it.take(7).removePrefix("#") },
                label = { Text("Custom RGB accent · six hex digits") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = customHex.isNotEmpty() && !customHex.matches(Regex("[0-9A-Fa-f]{6}")),
            )
            OutlinedButton(
                onClick = {
                    draft = draft.copy(accentHex = customHex.uppercase(), dynamicColor = false)
                },
                enabled = customHex.matches(Regex("[0-9A-Fa-f]{6}")),
            ) {
                Text("Preview custom accent")
            }
            Text(
                "Text and controls use contrast-safe resolved colors. Some very light or dark accents are adjusted for readability.",
                style = MaterialTheme.typography.bodySmall,
            )
            StudioToggle(
                "Android dynamic accent",
                draft.dynamicColor,
                environment.dynamicAccentHex != null,
            ) {
                draft = draft.copy(dynamicColor = it)
            }
            if (environment.dynamicAccentHex == null)
                Text(
                    "Dynamic color is unavailable on this device. Your selected palette remains in use.",
                    style = MaterialTheme.typography.bodySmall,
                )
            StudioChoices("Surface temperature", SurfaceVariant.entries, draft.surfaceVariant) {
                draft = draft.copy(surfaceVariant = it)
            }
            StudioToggle("True black in dark appearance", draft.trueBlack) {
                draft = draft.copy(trueBlack = it)
            }
            StudioToggle("High contrast", draft.highContrast) {
                draft = draft.copy(highContrast = it)
            }
        }
        "Geometry" -> {
            StudioChoices("Layout density", LayoutDensity.entries, draft.density) {
                draft = draft.copy(density = it)
            }
            StudioChoices("Corner size", CornerScale.entries, draft.corners) {
                draft = draft.copy(corners = it)
            }
            StudioChoices("Card treatment", CardTreatment.entries, draft.cards) {
                draft = draft.copy(cards = it)
            }
            StudioSlider("Border strength", draft.borderStrength, 0..3, 2) {
                draft = draft.copy(borderStrength = it)
            }
            StudioSlider("Liquid surface opacity", draft.surfaceOpacity, 70..100, 5) {
                draft = draft.copy(surfaceOpacity = it)
            }
            StudioSlider("Liquid gradient intensity", draft.gradientIntensity, 0..100, 9) {
                draft = draft.copy(gradientIntensity = it)
            }
            Text(
                "Opacity and gradients affect Liquid. Other styles retain their own surface treatment.",
                style = MaterialTheme.typography.bodySmall,
            )
            StudioToggle("Reduced decorative effects", draft.reducedEffects) {
                draft = draft.copy(reducedEffects = it)
            }
            StudioToggle("Reduced transparency", draft.reducedTransparency) {
                draft = draft.copy(reducedTransparency = it)
            }
        }
        "Typography & motion" -> {
            StudioSlider("UI text size", draft.textScalePercent, 85..130, 8, "%") {
                draft = draft.copy(textScalePercent = it)
            }
            Text(
                "This adjustment multiplies your system font size. Essential content wraps and scrolls; system accessibility scaling stays active.",
                style = MaterialTheme.typography.bodySmall,
            )
            StudioToggle("Emphasize numerical values", draft.numericEmphasis) {
                draft = draft.copy(numericEmphasis = it)
            }
            StudioChoices("Motion", MotionPreference.entries, draft.motion) {
                draft = draft.copy(motion = it)
            }
            Text(
                "System reduced motion and low-power settings take priority. There are no continuously animated decorative backgrounds.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (environment.reduceMotion || environment.lowPower)
                Text(
                    "Reduced effects are currently enforced by your device.",
                    style = MaterialTheme.typography.labelLarge,
                )
            Text(
                "Typography uses platform/system font families. No proprietary typeface is bundled.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        "Saved presets" -> {
            Text("Your personal collection", style = MaterialTheme.typography.titleLarge)
            Text(
                "Presets save the applied appearance and dashboard on this device. Apply your preview before saving a preset.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                enabled = !state.readOnly && state.record.presets.size < 24 && !busy,
                onClick = {
                    presetName = "${current.style.title} / Custom"
                    presetId = null
                    presetDialog = "Create"
                },
            ) {
                Text("Save current as preset")
            }
            if (state.record.presets.isEmpty())
                Text("No presets yet. Save a look you love, then restore it whenever you want.")
            state.record.presets.forEach { preset ->
                DesignCard(Modifier.fillMaxWidth()) {
                    Text(preset.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${preset.preferences.style.title} · ${preset.preferences.mode.name.lowercase()} · ${preset.preferences.density.name.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Button(
                            enabled = !state.readOnly && !busy,
                            onClick = {
                                perform {
                                    previousWire =
                                        AppearanceCodec.encode(
                                            AppearanceRecord(preferences = current)
                                        )
                                    message =
                                        if (repository.restorePreset(preset.id)) "Preset restored."
                                        else "Preset could not be restored."
                                }
                            },
                        ) {
                            Text("Restore")
                        }
                        TextButton(
                            onClick = {
                                presetId = preset.id
                                presetName = preset.name
                                presetDialog = "Rename"
                            },
                            enabled = !state.readOnly && !busy,
                        ) {
                            Text("Rename")
                        }
                        TextButton(
                            onClick = {
                                perform {
                                    message =
                                        if (repository.duplicatePreset(preset.id))
                                            "Preset duplicated."
                                        else
                                            "Preset could not be duplicated. Check the 24-preset limit."
                                }
                            },
                            enabled = !state.readOnly && !busy && state.record.presets.size < 24,
                        ) {
                            Text("Duplicate")
                        }
                        TextButton(
                            onClick = {
                                presetId = preset.id
                                presetDialog = "Delete"
                            },
                            enabled = !state.readOnly && !busy,
                        ) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
    presetDialog?.let { operation ->
        AlertDialog(
            onDismissRequest = { if (!busy) presetDialog = null },
            title = { Text("$operation appearance preset") },
            text = {
                if (operation == "Delete")
                    Text(
                        "Delete this saved appearance preset? Your applied appearance and game data stay as they are."
                    )
                else
                    OutlinedTextField(
                        presetName,
                        { presetName = it.take(60) },
                        label = { Text("Preset name") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
            },
            confirmButton = {
                TextButton(
                    enabled =
                        !busy &&
                            !state.readOnly &&
                            (operation == "Delete" || presetName.isNotBlank()),
                    onClick = {
                        perform {
                            val ok =
                                when (operation) {
                                    "Create" -> repository.createPreset(presetName.trim())
                                    "Rename" ->
                                        presetId?.let {
                                            repository.renamePreset(it, presetName.trim())
                                        } == true
                                    else -> presetId?.let { repository.deletePreset(it) } == true
                                }
                            message =
                                if (ok) "Preset ${operation.lowercase()} completed."
                                else "Preset change could not be saved. Previous presets retained."
                            presetDialog = null
                        }
                    },
                ) {
                    Text(operation)
                }
            },
            dismissButton = {
                TextButton(onClick = { presetDialog = null }, enabled = !busy) { Text("Cancel") }
            },
        )
    }
    if (recover)
        AlertDialog(
            onDismissRequest = { if (!busy) recover = false },
            title = { Text("Reset unreadable appearance?") },
            text = {
                Text(
                    "This replaces the unreadable preferences file with Carbon defaults, removes its saved presets and dashboard arrangement, and resets favorite-map preferences. Profiles, goals, RP history, matches and pairing stay in their separate stores."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        perform {
                            message =
                                if (repository.resetAll(confirmCorruptReset = true))
                                    "Appearance reset. Game data retained."
                                else "Appearance could not be reset."
                            recover = false
                        }
                    },
                    enabled = !busy,
                ) {
                    Text("Reset appearance")
                }
            },
            dismissButton = {
                TextButton(onClick = { recover = false }, enabled = !busy) { Text("Cancel") }
            },
        )
}

@Composable
private fun <T> StudioChoices(
    label: String,
    values: List<T>,
    selected: T,
    title: (T) -> String = {
        it.toString().lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
    },
    change: (T) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.titleMedium)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        values.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { change(value) },
                label = { Text(title(value)) },
                modifier = Modifier.semantics { contentDescription = "$label: ${title(value)}" },
            )
        }
    }
}

@Composable
private fun StudioToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    change: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(
            checked = checked,
            onCheckedChange = change,
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun StudioSlider(
    label: String,
    value: Int,
    bounds: IntRange,
    steps: Int,
    suffix: String = "",
    change: (Int) -> Unit,
) {
    Text("$label · $value$suffix", style = MaterialTheme.typography.titleMedium)
    Slider(
        value.toFloat(),
        { change(it.toInt().coerceIn(bounds)) },
        valueRange = bounds.first.toFloat()..bounds.last.toFloat(),
        steps = steps,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = label },
    )
}
