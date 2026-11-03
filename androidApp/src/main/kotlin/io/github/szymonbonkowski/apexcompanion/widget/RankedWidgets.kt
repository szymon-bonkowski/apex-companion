package io.github.szymonbonkowski.apexcompanion.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.*
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.*
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import io.github.szymonbonkowski.apexcompanion.MainActivity
import io.github.szymonbonkowski.apexcompanion.platform.AndroidRankedRuntime
import io.github.szymonbonkowski.apexcompanion.ranked.*
import java.text.DateFormat
import java.util.Date

enum class RankedWidgetKind { PROGRESS, GOAL, PREDATOR }

/** Reads a validated app-generated cache only. Even configuration taps cannot call a provider. */
open class RankedWidget(
    private val kind: RankedWidgetKind = RankedWidgetKind.PROGRESS,
    private val snapshotOverride: (suspend () -> String)? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(150.dp, 150.dp), DpSize(250.dp, 180.dp), DpSize(300.dp, 220.dp)))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val encoded = snapshotOverride?.invoke() ?: AndroidRankedRuntime.get(context).widgetSnapshot()
        val snapshot = RankedWidgetCodec.decode(encoded, nowSeconds())
        provideContent {
            val preferences = currentState<Preferences>()
            val selectedPlatform = preferences[PLATFORM_KEY] ?: snapshot.platform ?: ApexPlatform.PC.code
            RankedCard(kind, snapshot, selectedPlatform, preferences[DETAIL_KEY] ?: false)
        }
    }
    companion object {
        val PLATFORM_KEY = stringPreferencesKey("ranked_platform")
        val DETAIL_KEY = booleanPreferencesKey("ranked_details")
    }
}
class RankProgressWidget : RankedWidget(RankedWidgetKind.PROGRESS)
class RankedGoalWidget : RankedWidget(RankedWidgetKind.GOAL)
class PredatorThresholdWidget : RankedWidget(RankedWidgetKind.PREDATOR)

@Composable
private fun RankedCard(kind: RankedWidgetKind, view: RankedWidgetView, platformCode: String, details: Boolean) {
    val roomy = LocalSize.current.width >= 250.dp
    val tall = LocalSize.current.height >= 200.dp
    val ink = ColorProvider(Color(0xFFF2F5F7))
    val muted = ColorProvider(Color(0xFFB5C4CE))
    val accent = ColorProvider(Color(0xFF7AE4D5))
    val threshold = view.predator.firstOrNull { it.platform.code == platformCode }
    val title = when (kind) { RankedWidgetKind.PROGRESS -> "Rank progress"; RankedWidgetKind.GOAL -> "Ranked goal"; RankedWidgetKind.PREDATOR -> "Predator cutoff" }
    val state = if (kind == RankedWidgetKind.PREDATOR) threshold?.state?.name ?: "UNAVAILABLE" else view.state.name
    Column(GlanceModifier.fillMaxSize().background(Color(0xFF151E28)).padding(if (roomy) 16.dp else 8.dp)) {
        Text(if (roomy) "$state · $title" else "$state · ${if (kind == RankedWidgetKind.PREDATOR) "Cutoff" else "Ranked"}", style = TextStyle(color = accent, fontSize = if (roomy) 13.sp else 10.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        Column(GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>())) {
            if (kind == RankedWidgetKind.PREDATOR) {
                Text(threshold?.let { "${it.rp} RP" } ?: "Cutoff unavailable", style = TextStyle(color = ink, fontSize = if (roomy) 24.sp else 17.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Text(ApexPlatform.entries.firstOrNull { it.code == platformCode }?.label ?: "Platform unavailable", style = TextStyle(color = muted, fontSize = 12.sp), maxLines = 1)
                Text(threshold?.remainingRp?.let { "$it RP to cutoff" } ?: "Player RP unavailable", style = TextStyle(color = ink, fontSize = 12.sp), maxLines = 1)
                if (roomy) Text("Dynamic cutoff · no position guaranteed", style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 2)
                if (tall) threshold?.masterPopulation?.let { Text("$it Master + Predator players", style = TextStyle(color = muted, fontSize = 11.sp)) }
            } else {
                if (kind == RankedWidgetKind.PROGRESS) Text(view.rankLabel, style = TextStyle(color = ink, fontSize = if (roomy) 20.sp else 16.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Text(view.currentRp?.let { "$it RP" } ?: "RP unavailable", style = TextStyle(color = ink, fontSize = if (roomy) 24.sp else 18.sp, fontWeight = FontWeight.Bold), maxLines = 1)
                Text(view.targetRp?.let { if (roomy) "Target $it · ${view.remainingRp?.toString() ?: "?"} left" else "Goal $it · ${view.remainingRp?.toString() ?: "?"} left" } ?: "Set a goal in the app", style = TextStyle(color = muted, fontSize = if (roomy) 13.sp else 10.sp), maxLines = if (roomy) 2 else 1)
                if (view.targetRp != null) {
                    LinearProgressIndicator(progress = view.fraction, modifier = GlanceModifier.fillMaxWidth().height(4.dp), color = accent, backgroundColor = ColorProvider(Color(0xFF384956)))
                    if (roomy || kind == RankedWidgetKind.GOAL) Text(view.rpPerDay?.let { "$it RP/day · ${(view.fraction * 100).toInt()}%" }
                        ?: "${view.goalStatus?.lowercase()?.replace('_', ' ') ?: "No deadline"} · ${(view.fraction * 100).toInt()}%", style = TextStyle(color = muted, fontSize = 11.sp), maxLines = 1)
                }
            }
            run {
                val observed = if (kind == RankedWidgetKind.PREDATOR) threshold?.observedAtSeconds else view.observedAtSeconds
                Text(observed?.let { "Updated ${(if (roomy || details) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) else DateFormat.getTimeInstance(DateFormat.SHORT)).format(Date(it * 1000))}" } ?: "No successful update", style = TextStyle(color = muted, fontSize = 10.sp), maxLines = 1)
            }
            Text(if (kind == RankedWidgetKind.PREDATOR) "Apex Legends Status" else view.source, style = TextStyle(color = muted, fontSize = 10.sp), maxLines = 1)
            if (details && roomy && kind != RankedWidgetKind.PREDATOR) view.goalStatus?.let {
                Text("Goal: ${it.lowercase().replace('_', ' ')}", style = TextStyle(color = muted, fontSize = 10.sp), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(if (kind == RankedWidgetKind.PREDATOR) "Platform" else "Details",
                GlanceModifier.clickable(actionRunCallback<ConfigureRankedWidgetAction>(actionParametersOf(KIND_PARAMETER to kind.name))).padding(vertical = if (roomy) 8.dp else 4.dp),
                style = TextStyle(color = accent, fontSize = 12.sp))
            Spacer(GlanceModifier.defaultWeight())
            Text("Open app", GlanceModifier.clickable(actionStartActivity<MainActivity>()).padding(vertical = if (roomy) 8.dp else 4.dp), style = TextStyle(color = accent, fontSize = 12.sp))
        }
    }
}
private val KIND_PARAMETER = ActionParameters.Key<String>("ranked_kind")
class ConfigureRankedWidgetAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val kind = runCatching { RankedWidgetKind.valueOf(parameters[KIND_PARAMETER] ?: "PROGRESS") }.getOrDefault(RankedWidgetKind.PROGRESS)
        val snapshot = if (kind == RankedWidgetKind.PREDATOR)
            RankedWidgetCodec.decode(AndroidRankedRuntime.get(context).widgetSnapshot(), System.currentTimeMillis() / 1000) else null
        updateAppWidgetState(context, glanceId) { preferences ->
            if (kind == RankedWidgetKind.PREDATOR) {
                val current = preferences[RankedWidget.PLATFORM_KEY] ?: snapshot?.platform ?: ApexPlatform.PC.code
                val platforms = ApexPlatform.entries
                preferences[RankedWidget.PLATFORM_KEY] = platforms[(platforms.indexOfFirst { it.code == current } + 1).mod(platforms.size)].code
            } else preferences[RankedWidget.DETAIL_KEY] = !(preferences[RankedWidget.DETAIL_KEY] ?: false)
        }
        RankedWidget(kind).update(context, glanceId)
    }
}
class RankProgressWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = RankProgressWidget() }
class RankedGoalWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = RankedGoalWidget() }
class PredatorThresholdWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = PredatorThresholdWidget() }

suspend fun updateAllRankedWidgets(context: Context) {
    RankProgressWidget().updateAll(context)
    RankedGoalWidget().updateAll(context)
    PredatorThresholdWidget().updateAll(context)
}
