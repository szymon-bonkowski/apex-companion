package io.github.szymonbonkowski.apexcompanion

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.glance.appwidget.updateAll
import io.github.szymonbonkowski.apexcompanion.appearance.platformAppearanceEnvironment
import io.github.szymonbonkowski.apexcompanion.appearance.resolveTheme
import io.github.szymonbonkowski.apexcompanion.platform.AndroidAppearanceRuntime
import io.github.szymonbonkowski.apexcompanion.platform.AndroidHistoryDocuments
import io.github.szymonbonkowski.apexcompanion.platform.AndroidMapRuntime
import io.github.szymonbonkowski.apexcompanion.platform.AndroidMatchNetworkPermission
import io.github.szymonbonkowski.apexcompanion.platform.AndroidMatchRuntime
import io.github.szymonbonkowski.apexcompanion.platform.AndroidMatchScreenshots
import io.github.szymonbonkowski.apexcompanion.platform.AndroidRankedRuntime
import io.github.szymonbonkowski.apexcompanion.widget.MapRotationWidget
import io.github.szymonbonkowski.apexcompanion.widget.MapSyncScheduler
import io.github.szymonbonkowski.apexcompanion.widget.WidgetAppearancePublisher

class MainActivity : ComponentActivity() {
    internal lateinit var historyDocuments: AndroidHistoryDocuments
        private set

    internal lateinit var matchScreenshots: AndroidMatchScreenshots
        private set

    private var widgetNavigation by mutableStateOf<WidgetNavigationRequest?>(null)
    private var navigationSequence = 0L

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Consume the navigation event; restored UI state owns subsequent recreation.
        acceptWidgetNavigation(intent)
    }

    private fun acceptWidgetNavigation(intent: Intent) {
        widgetDestination(intent.dataString)?.let {
            widgetNavigation = WidgetNavigationRequest(it, ++navigationSequence)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) acceptWidgetNavigation(intent)
        historyDocuments = AndroidHistoryDocuments(this)
        val matchDocuments =
            AndroidHistoryDocuments(this, 16_777_216, "apex-companion-matches-backup.json")
        matchScreenshots = AndroidMatchScreenshots(this)
        AndroidMatchNetworkPermission(this)
        val matches = AndroidMatchRuntime.get(applicationContext)
        val runtime = AndroidMapRuntime.get(this)
        MapSyncScheduler.ensurePeriodic(this)
        val ranked = AndroidRankedRuntime.get(applicationContext)
        val appearance = AndroidAppearanceRuntime.get(applicationContext)
        // Install the composition synchronously; asynchronous initialization must never
        // replace a newer composition (including after activity recreation).
        setContent {
            val initialized =
                produceState(false, runtime, ranked) {
                    runtime.initialize()
                    ranked.initialize()
                    matches.initialize()
                    appearance.initialize()
                    value = true
                }
            if (initialized.value) {
                val appearanceState by appearance.state.collectAsState()
                val environment = platformAppearanceEnvironment()
                val dark = resolveTheme(appearanceState.record.preferences, environment).dark
                SideEffect {
                    val bars =
                        if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                }
                App(
                    runtime.repository,
                    rankedRepository = ranked.repository,
                    appearanceRepository = appearance,
                    widgetNavigation = widgetNavigation,
                    appearanceEnvironment = environment,
                    backNavigation = { enabled, onBack -> BackHandler(enabled, onBack) },
                    onAppearanceProjectionChanged = {
                        WidgetAppearancePublisher.publish(applicationContext, it)
                    },
                    historyRepository = ranked.history,
                    historyDocuments = historyDocuments,
                    matchRepository = matches.repository,
                    matchSync = matches.sync,
                    matchDocuments = matchDocuments,
                    matchScreenshots = matchScreenshots,
                    onRankedProjectionChanged = {
                        ranked.publish()
                    },
                    onProjectionChanged = {
                        MapRotationWidget().updateAll(applicationContext)
                    },
                )
            }
        }
    }
}
