package io.github.szymonbonkowski.apexcompanion

import android.os.Bundle
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.github.szymonbonkowski.apexcompanion.platform.AndroidMapRuntime
import io.github.szymonbonkowski.apexcompanion.widget.MapRotationWidget
import io.github.szymonbonkowski.apexcompanion.widget.MapSyncScheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        val runtime = AndroidMapRuntime.get(this)
        MapSyncScheduler.ensurePeriodic(this)
        lifecycleScope.launch {
            runtime.initialize()
            setContent {
                App(runtime.repository, onProjectionChanged = {
                    MapRotationWidget().updateAll(applicationContext)
                })
            }
        }
    }
}
