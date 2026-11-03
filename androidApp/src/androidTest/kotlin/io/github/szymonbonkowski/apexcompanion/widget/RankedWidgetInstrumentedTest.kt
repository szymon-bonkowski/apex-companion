package io.github.szymonbonkowski.apexcompanion.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RankedWidgetInstrumentedTest {
    @Test fun nativeCardsShowCachedLiveStaleManualAndGoalValuesWithoutRequests() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val today = LocalDate(2026,11,2)
        var now = today.atStartOfDayIn(TimeZone.UTC).epochSeconds + 3600
        var calls = 0
        val repository = RankedRepository(object: RankedProvider {
            override suspend fun profile(lookup: ProfileLookup): RankedResult<RankedSnapshot> {
                calls++
                return RankedResult.Success(RankedSnapshot(PlayerIdentity("123456",ApexPlatform.PC,"Synthetic widget player"),13460,"Diamond",3,now))
            }
            override suspend fun predator(): RankedResult<List<PredatorThreshold>> {
                calls++
                return RankedResult.Success(listOf(PredatorThreshold(ApexPlatform.PC,20000,now,1234)))
            }
        }, object: RotationStore { override suspend fun read():String?=null; override suspend fun write(value:String){} }, RotationClock{now})
        repository.connect(ProfileLookup("123456",ApexPlatform.PC,true))
        repository.saveGoal(RankGoal(16000,13000,LocalDate(2026,11,24)))
        repository.refreshPredator()
        val wire = RankedWidgetCodec.encode(repository.state.value,now,today,TimeZone.UTC)
        suspend fun render(kind: RankedWidgetKind, size: DpSize = DpSize(300.dp,220.dp)):String {
            val widget = RankedWidget(kind,snapshotOverride={wire},nowSeconds={now})
            val remote = widget.compose(context,size=size,state=emptyPreferences())
            var text=""
            instrumentation.runOnMainSync {
                val rendered = remote.apply(context,FrameLayout(context))
                text=allText(rendered)
                val width = (size.width.value * context.resources.displayMetrics.density).toInt()
                val height = (size.height.value * context.resources.displayMetrics.density).toInt()
                rendered.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                rendered.layout(0, 0, width, height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                rendered.draw(Canvas(bitmap))
                File(context.filesDir, "ranked-${kind.name.lowercase()}-${size.width.value.toInt()}-${if(now % 3600 == 0L) "live" else "stale"}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            return text
        }
        val live=render(RankedWidgetKind.PROGRESS)
        assertTrue(live.contains("LIVE · Rank progress")); assertTrue(live.contains("Diamond 3"))
        assertTrue(live.contains("13460 RP")); assertTrue(live.contains("2540 left"))
        assertTrue(render(RankedWidgetKind.GOAL).contains("116 RP/day"))
        val predator=render(RankedWidgetKind.PREDATOR)
        assertTrue(predator.contains("20000 RP")); assertTrue(predator.contains("6540 RP to cutoff"))
        assertFalse(live.contains("Synthetic widget player")); assertFalse(live.contains("123456"))
        assertTrue(render(RankedWidgetKind.PROGRESS,DpSize(150.dp,150.dp)).contains("13460 RP"))
        assertEquals(2,calls)
        now+=PROFILE_FRESH_SECONDS
        assertTrue(render(RankedWidgetKind.PROGRESS).contains("STALE · Rank progress"))
        assertEquals(2,calls)
        repository.disconnect()
        repository.setManualRp(900)
        val manual=RankedWidgetCodec.decode(RankedWidgetCodec.encode(repository.state.value,now,today,TimeZone.UTC),now)
        assertEquals(DataState.MANUAL,manual.state); assertEquals(900L,manual.currentRp)
        assertEquals(2,calls)
    }

    @Test fun boundInstancesKeepIndependentConfiguration() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        val host=AppWidgetHost(context,73102)
        val manager=AppWidgetManager.getInstance(context)
        val ids=listOf(host.allocateAppWidgetId(),host.allocateAppWidgetId())
        try {
            ids.forEach { id ->
                val options=Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,300)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,300)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,220)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,220)
                }
                assertTrue(manager.bindAppWidgetIdIfAllowed(id,ComponentName(context,RankProgressWidgetReceiver::class.java),options))
            }
            val first=GlanceAppWidgetManager(context).getGlanceIdBy(ids[0])
            val second=GlanceAppWidgetManager(context).getGlanceIdBy(ids[1])
            ConfigureRankedWidgetAction().onAction(context,first,actionParametersOf(ActionParameters.Key<String>("ranked_kind") to "PROGRESS"))
            assertEquals(true,getAppWidgetState(context,PreferencesGlanceStateDefinition,first)[RankedWidget.DETAIL_KEY])
            assertNull(getAppWidgetState(context,PreferencesGlanceStateDefinition,second)[RankedWidget.DETAIL_KEY])
            ConfigureRankedWidgetAction().onAction(context,first,actionParametersOf(ActionParameters.Key<String>("ranked_kind") to "PREDATOR"))
            assertNotNull(getAppWidgetState(context,PreferencesGlanceStateDefinition,first)[RankedWidget.PLATFORM_KEY])
            assertNull(getAppWidgetState(context,PreferencesGlanceStateDefinition,second)[RankedWidget.PLATFORM_KEY])
        } finally {
            ids.forEach(host::deleteAppWidgetId); host.deleteHost()
            instrumentation.uiAutomation.executeShellCommand("appwidget revokebind --package ${context.packageName} --user 0").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }
    private fun allText(view:View):String=when(view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" "){allText(view.getChildAt(it))}
        else -> ""
    }
}
