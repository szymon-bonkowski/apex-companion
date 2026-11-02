package io.github.szymonbonkowski.apexcompanion.widget

import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import io.github.szymonbonkowski.apexcompanion.MainActivity
import io.github.szymonbonkowski.apexcompanion.maps.RotationView
import io.github.szymonbonkowski.apexcompanion.maps.RotationRepository
import io.github.szymonbonkowski.apexcompanion.maps.projectRotation
import io.github.szymonbonkowski.apexcompanion.platform.AndroidMapRuntime
import java.text.DateFormat
import java.util.Date

/** Native presentation only. The shared repository owns all schedule and freshness policy. */
class MapRotationWidget(
    private val repositoryOverride: RotationRepository? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
) : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(setOf(DpSize(150.dp, 150.dp), DpSize(250.dp, 180.dp), DpSize(300.dp, 220.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val runtime = AndroidMapRuntime.get(context)
        val repository = repositoryOverride ?: runtime.repository
        repository.initialize() // durable app-owned projection; never performs an HTTP request
        if (repositoryOverride == null) MapSyncScheduler.scheduleBoundary(context)
        provideContent {
            val repositoryState by repository.state.collectAsState()
            val modeId = currentState<Preferences>()[MODE_KEY] ?: DEFAULT_MODE
            val cache = repositoryState.cache
            val modeLabel = cache?.playlists?.firstOrNull { it.id == modeId }?.label ?: fallbackModeLabel(modeId)
            val view = projectRotation(cache, modeId, nowSeconds(), repositoryState.error)
            MapCard(view, modeLabel)
        }
    }

    companion object {
        val MODE_KEY = stringPreferencesKey("rotation_mode")
        const val DEFAULT_MODE = "battle_royale"
        val FALLBACK_MODES = listOf("battle_royale", "ranked", "ltm")
        fun fallbackModeLabel(id: String) = when (id) {
            "battle_royale" -> "Pubs"
            "ranked" -> "Ranked"
            "ltm" -> "Mixtape"
            else -> id.replace('_', ' ').take(40)
        }
    }
}

@Composable
private fun MapCard(view: RotationView, modeLabel: String) {
    val roomy = LocalSize.current.width >= 250.dp
    val tall = LocalSize.current.height >= 200.dp
    val foreground = ColorProvider(Color(0xFFF2F5F7))
    val secondary = ColorProvider(Color(0xFFB5C4CE))
    val accent = ColorProvider(Color(0xFF7AE4D5))
    Column(
        modifier = GlanceModifier.fillMaxSize().background(Color(0xFF151E28)).padding(if (roomy) 16.dp else 12.dp),
        verticalAlignment = Alignment.Vertical.Top,
    ) {
        if (roomy) {
            Text("APEX COMPANION", style = TextStyle(color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
        Text("${view.state.name} · $modeLabel", style = TextStyle(color = secondary, fontSize = 12.sp), maxLines = 1)
        Spacer(GlanceModifier.height(if (roomy) 6.dp else 4.dp))
        Column(GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>())) {
            Text(view.current?.mapName ?: "Map unavailable", style = TextStyle(color = foreground, fontSize = if (roomy) 22.sp else 17.sp, fontWeight = FontWeight.Bold), maxLines = if (roomy) 2 else 1)
            Text(view.current?.let { "Ends ${localTime(it.endSeconds)}" } ?: "Open app for source status", style = TextStyle(color = secondary, fontSize = 12.sp), maxLines = 1)
            if (roomy) Spacer(GlanceModifier.height(4.dp))
            Text(view.next?.let { "Next: ${it.mapName} · ${localTime(it.startSeconds)}" } ?: "Next map not supplied", style = TextStyle(color = foreground, fontSize = if (roomy) 13.sp else 11.sp), maxLines = if (roomy) 2 else 1)
            if (tall) {
                Text(view.lastSyncedAtSeconds?.let { "Synced ${localTime(it)} · cached schedule" } ?: "No successful synchronization", style = TextStyle(color = secondary, fontSize = 11.sp), maxLines = 1)
            }
            Text(view.source, style = TextStyle(color = secondary, fontSize = 10.sp), maxLines = 1)
        }
        Spacer(GlanceModifier.defaultWeight())
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text("Mode", modifier = GlanceModifier.clickable(actionRunCallback<CycleMapModeAction>()).padding(vertical = 10.dp, horizontal = 6.dp), style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.defaultWeight())
            Text("Refresh", modifier = GlanceModifier.clickable(actionRunCallback<RefreshMapsAction>()).padding(vertical = 10.dp, horizontal = 6.dp), style = TextStyle(color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold))
        }
    }
}

private fun localTime(seconds: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(seconds * 1000L))

class CycleMapModeAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val runtime = AndroidMapRuntime.get(context)
        runtime.initialize()
        val modes = (MapRotationWidget.FALLBACK_MODES + runtime.repository.state.value.cache?.playlists.orEmpty().map { it.id }).distinct()
        updateAppWidgetState(context, glanceId) { preferences ->
            val current = preferences[MapRotationWidget.MODE_KEY] ?: MapRotationWidget.DEFAULT_MODE
            preferences[MapRotationWidget.MODE_KEY] = modes[(modes.indexOf(current) + 1).mod(modes.size)]
        }
        MapRotationWidget().update(context, glanceId)
    }
}

class RefreshMapsAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        MapSyncScheduler.requestRefresh(context)
    }
}

class MapRotationWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = MapRotationWidget()
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        MapSyncScheduler.ensurePeriodic(context)
        MapSyncScheduler.requestLocalUpdate(context)
    }
}

class MapClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED)) return
        context.getSharedPreferences("map-widget-scheduler", Context.MODE_PRIVATE).edit().remove("boundary").apply()
        MapSyncScheduler.requestLocalUpdate(context)
    }
}
