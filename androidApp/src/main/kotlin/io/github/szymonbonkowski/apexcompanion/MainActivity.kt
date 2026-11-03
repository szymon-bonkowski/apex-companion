package io.github.szymonbonkowski.apexcompanion

import android.os.Bundle
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.glance.appwidget.updateAll
import androidx.compose.runtime.produceState
import io.github.szymonbonkowski.apexcompanion.platform.AndroidMapRuntime
import io.github.szymonbonkowski.apexcompanion.platform.AndroidRankedRuntime
import io.github.szymonbonkowski.apexcompanion.platform.AndroidHistoryDocuments
import io.github.szymonbonkowski.apexcompanion.widget.MapRotationWidget
import io.github.szymonbonkowski.apexcompanion.widget.MapSyncScheduler

class MainActivity : ComponentActivity() {
    internal lateinit var historyDocuments: AndroidHistoryDocuments
        private set
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        historyDocuments = AndroidHistoryDocuments(this)
        val runtime = AndroidMapRuntime.get(this)
        MapSyncScheduler.ensurePeriodic(this)
        val ranked = AndroidRankedRuntime.get(applicationContext)
        // Install the composition synchronously; asynchronous initialization must never
        // replace a newer composition (including after activity recreation).
        setContent {
            val initialized = produceState(false, runtime, ranked) {
                runtime.initialize()
                ranked.initialize()
                value = true
            }
            if (initialized.value) {
                App(runtime.repository, rankedRepository = ranked.repository,
                    historyRepository = ranked.history, historyDocuments = historyDocuments, onRankedProjectionChanged = {
                    ranked.publish()
                }, onProjectionChanged = {
                    MapRotationWidget().updateAll(applicationContext)
                })
            }
        }
    }
}
