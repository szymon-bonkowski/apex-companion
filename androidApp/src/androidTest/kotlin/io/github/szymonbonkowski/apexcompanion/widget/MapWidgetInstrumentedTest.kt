package io.github.szymonbonkowski.apexcompanion.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.FrameLayout
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import io.github.szymonbonkowski.apexcompanion.maps.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercises the actual AppWidget service, Glance RemoteViews and durable instance preferences. */
@RunWith(AndroidJUnit4::class)
class MapWidgetInstrumentedTest {
    @Test
    fun sourceScheduleRendersCurrentNextAndNeverKeepsExpiredMapLive() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        var now = 1793620800L
        val end = now + 120
        val fixture = RotationCache(now, listOf(PlaylistSchedule("battle_royale", "Pubs", listOf(
            MapSegment("fixture-a", "Fixture Highlands", now - 60, end),
            MapSegment("fixture-b", "Fixture Harbor", end, end + 1800),
        ))))
        var stored: String? = RotationCacheCodec.encode(fixture)
        var providerCalls = 0
        val repository = RotationRepository(
            RotationProvider { providerCalls++; ProviderResult.Failure(RotationError.NETWORK) },
            object : RotationStore {
                override suspend fun read() = stored
                override suspend fun write(value: String) { stored = value }
            }, RotationClock { now },
        )
        val widget = MapRotationWidget(repositoryOverride = repository, nowSeconds = { now })
        suspend fun render(name: String): String {
            val remoteViews = widget.compose(context, size = DpSize(300.dp, 220.dp), state = emptyPreferences())
            var text = ""
            instrumentation.runOnMainSync {
                val rendered = remoteViews.apply(context, FrameLayout(context))
                text = allText(rendered)
                capture(rendered, 300, 220, File(context.filesDir, "$name.png"))
            }
            return text
        }
        val live = render("widget-fixture-live")
        assertTrue(live.contains("LIVE · Pubs"))
        assertTrue(live.contains("Fixture Highlands"))
        assertTrue(live.contains("Next: Fixture Harbor"))
        assertEquals("Rendering must never request the provider", 0, providerCalls)
        repository.refresh(force = true)
        val stale = render("widget-fixture-stale")
        assertTrue(stale.contains("STALE · Pubs"))
        assertTrue(stale.contains("Fixture Highlands"))
        assertEquals(1, providerCalls)
        now = end
        val boundary = render("widget-fixture-boundary")
        assertTrue(boundary.contains("STALE · Pubs"))
        assertTrue(boundary.contains("Fixture Harbor"))
        assertTrue(!boundary.contains("Fixture Highlands"))
        now = end + 1800
        val expired = render("widget-fixture-expired")
        assertTrue(expired.contains("UNAVAILABLE · Pubs"))
        assertTrue(!expired.contains("Fixture Harbor"))
        assertTrue(expired.contains("Map unavailable"))
        assertEquals("Boundary updates are local projection work", 1, providerCalls)
        Unit
    }

    @Test
    fun refreshWithoutAuthorizedProviderCreatesNoNetworkWork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        MapSyncScheduler.ensurePeriodic(context)
        repeat(10) { MapSyncScheduler.requestRefresh(context) }
        val manager = WorkManager.getInstance(context)
        val active = setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
        assertTrue(manager.getWorkInfosForUniqueWork(MapSyncScheduler.PERIODIC_NAME).get().none { it.state in active })
        assertTrue(manager.getWorkInfosForUniqueWork(MapSyncScheduler.REFRESH_NAME).get().none { it.state in active })
        assertTrue(manager.getWorkInfosForUniqueWork(MapSyncScheduler.LOCAL_NAME).get().count { it.state in active } <= 1)
    }

    @Test
    fun twoInstancesRenderAndKeepIndependentModes() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        val host = AppWidgetHost(context, 73101)
        val manager = AppWidgetManager.getInstance(context)
        val ids = listOf(host.allocateAppWidgetId(), host.allocateAppWidgetId())
        val views = mutableListOf<AppWidgetHostView>()
        try {
            instrumentation.runOnMainSync { host.startListening() }
            ids.forEachIndexed { index, id ->
                val options = Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, if (index == 0) 150 else 300)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, if (index == 0) 150 else 300)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, if (index == 0) 150 else 220)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, if (index == 0) 150 else 220)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderCategory.HOME_SCREEN)
                }
                assertTrue("Widget binding permission or receiver registration failed", manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, MapRotationWidgetReceiver::class.java), options))
                instrumentation.runOnMainSync { views += host.createView(context, id, manager.getAppWidgetInfo(id)) }
                val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(id)
                MapRotationWidget().update(context, glanceId)
            }
            awaitText(views[0], "UNAVAILABLE")
            awaitText(views[1], "UNAVAILABLE")
            awaitText(views[0], "Apex Legends Status")
            awaitText(views[1], "Apex Legends Status")
            instrumentation.runOnMainSync {
                capture(views[0], 150, 150, File(context.filesDir, "widget-compact.png"))
                capture(views[1], 300, 220, File(context.filesDir, "widget-wide.png"))
            }
            val first = GlanceAppWidgetManager(context).getGlanceIdBy(ids[0])
            val second = GlanceAppWidgetManager(context).getGlanceIdBy(ids[1])
            CycleMapModeAction().onAction(context, first, actionParametersOf())
            assertEquals("ranked", getAppWidgetState(context, PreferencesGlanceStateDefinition, first)[MapRotationWidget.MODE_KEY])
            assertEquals(null, getAppWidgetState(context, PreferencesGlanceStateDefinition, second)[MapRotationWidget.MODE_KEY])
            awaitText(views[0], "Ranked")
            awaitText(views[1], "Pubs")
        } finally {
            instrumentation.runOnMainSync { host.stopListening() }
            ids.forEach(host::deleteAppWidgetId)
            host.deleteHost()
            instrumentation.uiAutomation.executeShellCommand("appwidget revokebind --package ${context.packageName} --user 0").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }

    private fun awaitText(root: View, expected: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val end = System.currentTimeMillis() + 15_000L
        var found = false
        while (!found && System.currentTimeMillis() < end) {
            instrumentation.runOnMainSync { found = allText(root).contains(expected) }
            if (!found) Thread.sleep(100)
        }
        var actual = ""
        instrumentation.runOnMainSync { actual = allText(root) }
        assertTrue("Expected '$expected' in actual widget RemoteViews: $actual", found)
    }

    private fun allText(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { allText(view.getChildAt(it)) }
        else -> ""
    }

    private fun capture(view: View, widthDp: Int, heightDp: Int, file: File) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density).toInt()
        val height = (heightDp * density).toInt()
        view.setPadding(0, 0, 0, 0) // capture widget content; launchers supply their own outer padding
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private object AppWidgetProviderCategory { const val HOME_SCREEN = 1 }
}
