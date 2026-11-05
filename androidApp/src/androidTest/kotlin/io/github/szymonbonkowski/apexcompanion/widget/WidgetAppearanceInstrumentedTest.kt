package io.github.szymonbonkowski.apexcompanion.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Spinner
import android.widget.Button
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.preferencesOf
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.compose
import androidx.glance.appwidget.state.getAppWidgetState
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.szymonbonkowski.apexcompanion.appearance.*
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@RunWith(AndroidJUnit4::class)
class WidgetAppearanceInstrumentedTest {
    @Test fun concurrentPublishAndReadKeepCompleteCosmeticRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir,"widget-appearance-v1.json")
        val previous = file.takeIf { it.exists() }?.readBytes()
        try {
            val liquid = WidgetAppearance(WidgetDesign.LIQUID,"D3B9FF")
            val tactical = WidgetAppearance(WidgetDesign.TACTICAL,"FFAA75")
            WidgetAppearancePublisher.publish(context,WidgetAppearanceCodec.encode(liquid))
            coroutineScope {
                repeat(4) { index -> launch(Dispatchers.Default) {
                    repeat(3) { WidgetAppearancePublisher.publish(context,WidgetAppearanceCodec.encode(if (index % 2 == 0) liquid else tactical)) }
                } }
                launch(Dispatchers.Default) { repeat(30) {
                    assertTrue(WidgetAppearancePublisher.read(context).style in setOf(WidgetDesign.LIQUID,WidgetDesign.TACTICAL))
                } }
            }
            WidgetAppearancePublisher.publish(context,WidgetAppearanceCodec.encode(tactical))
            assertEquals(tactical,WidgetAppearanceCodec.decodeStrict(file.readText()))
        } finally { if (previous == null) file.delete() else file.writeBytes(previous) }
    }

    @Test fun nativeEditorRestoresDraftAndAppliesOnlySelectedInstance() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        val host = AppWidgetHost(context, 73106)
        val manager = AppWidgetManager.getInstance(context)
        val ids = List(2) { host.allocateAppWidgetId() }
        try {
            ids.forEach { assertTrue(manager.bindAppWidgetIdIfAllowed(it,ComponentName(context,MapRotationWidgetReceiver::class.java),Bundle())) }
            val intent = Intent(context,WidgetAppearanceActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,ids[0])
            ActivityScenario.launch<WidgetAppearanceActivity>(intent).use { scenario ->
                awaitCondition { var ready = false; scenario.onActivity { ready = it.findViewById<Spinner>(17001) != null }; ready }
                scenario.onActivity {
                    it.findViewById<Spinner>(17001).setSelection(4) // Tactical
                    it.findViewById<Spinner>(17003).setSelection(1) // Compact
                    it.findViewById<Spinner>(17004).setSelection(3) // OLED
                }
                scenario.recreate()
                awaitCondition { var ready = false; scenario.onActivity { ready = it.findViewById<Spinner>(17001) != null }; ready }
                scenario.onActivity {
                    assertEquals(4,it.findViewById<Spinner>(17001).selectedItemPosition)
                    assertEquals(1,it.findViewById<Spinner>(17003).selectedItemPosition)
                    assertEquals(3,it.findViewById<Spinner>(17004).selectedItemPosition)
                    findButton(it.window.decorView,"Apply to this widget")!!.performClick()
                }
                val first = GlanceAppWidgetManager(context).getGlanceIdBy(ids[0])
                val second = GlanceAppWidgetManager(context).getGlanceIdBy(ids[1])
                awaitCondition { runBlocking { getAppWidgetState(context,PreferencesGlanceStateDefinition,first)[WIDGET_STYLE_KEY] == "TACTICAL" } }
                val saved = widgetInstanceAppearance(getAppWidgetState(context,PreferencesGlanceStateDefinition,first))
                assertEquals(WidgetDesign.TACTICAL,saved.style); assertEquals(WidgetDensity.COMPACT,saved.density)
                assertEquals(WidgetSurface.OLED,saved.surface)
                assertNull(getAppWidgetState(context,PreferencesGlanceStateDefinition,second)[WIDGET_STYLE_KEY])
            }
        } finally {
            ids.forEach(host::deleteAppWidgetId); host.deleteHost()
            instrumentation.uiAutomation.executeShellCommand("appwidget revokebind --package ${context.packageName} --user 0").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }

    @Test fun everyDesignRendersNativeSmallAndWideFamiliesWithProvenance() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val now = 1793707200L
        var calls = 0
        val repository = RotationRepository(RotationProvider { calls++; ProviderResult.Failure(RotationError.NETWORK) },
            object : RotationStore { override suspend fun read(): String? = null; override suspend fun write(value: String) {} }, RotationClock { now })
        val demoRepository = RotationRepository(RotationProvider { calls++; ProviderResult.Failure(RotationError.NETWORK) },
            object : RotationStore { override suspend fun read() = RotationCacheCodec.encode(demoRotation(now)); override suspend fun write(value: String) {} }, RotationClock { now })
        val manual = RankedWidgetCodec.encode(RankedRepositoryState(manualRp = 13460, manualUpdatedAtSeconds = now, useManual = true,
            goal = RankGoal(16000,13000,LocalDate(2026,11,25))),now,LocalDate(2026,11,3),TimeZone.UTC)
        val unavailable = RankedWidgetCodec.encode(RankedRepositoryState(),now,LocalDate(2026,11,3),TimeZone.UTC)
        val livePredator = RankedWidgetCodec.encode(RankedRepositoryState(
            profile = RankedSnapshot(PlayerIdentity("123456",ApexPlatform.PC,"Synthetic widget fixture"),13460,"Diamond",3,now),
            predator = listOf(PredatorThreshold(ApexPlatform.PC,20000,now,1234)),
        ),now,LocalDate(2026,11,3),TimeZone.UTC)
        val widgets = listOf("map" to MapRotationWidget(repository, { now }),
            "progress" to RankedWidget(RankedWidgetKind.PROGRESS,{ unavailable },{ now }),
            "goal" to RankedWidget(RankedWidgetKind.GOAL,{ unavailable },{ now }),
            "predator" to RankedWidget(RankedWidgetKind.PREDATOR,{ unavailable },{ now }),
            "demo-map" to MapRotationWidget(demoRepository,{ now }),
            "manual-progress" to RankedWidget(RankedWidgetKind.PROGRESS,{ manual },{ now }),
            "manual-goal" to RankedWidget(RankedWidgetKind.GOAL,{ manual },{ now }),
            "live-predator" to RankedWidget(RankedWidgetKind.PREDATOR,{ livePredator },{ now }),
            "manual-progress-spacious-details" to RankedWidget(RankedWidgetKind.PROGRESS,{ manual },{ now }),
            "manual-goal-spacious-details" to RankedWidget(RankedWidgetKind.GOAL,{ manual },{ now }))
        val sizes = listOf(DpSize(150.dp,150.dp), DpSize(250.dp,180.dp), DpSize(300.dp,220.dp))
        val geometryEvidence = JSONObject()
        val geometryFailures = mutableListOf<String>()
        for (style in WidgetDesign.entries) for ((name, widget) in widgets) for (size in sizes) {
            val stress = name.endsWith("-spacious-details")
            val remote = widget.compose(context, size = size, state = preferencesOf(WIDGET_STYLE_KEY to style.name,
                WIDGET_DENSITY_KEY to (if (stress) WidgetDensity.SPACIOUS else WidgetDensity.COMFORTABLE).name,
                RankedWidget.DETAIL_KEY to stress,
                WIDGET_SURFACE_KEY to if (style == WidgetDesign.EDITORIAL) "LIGHT" else "DARK"))
            instrumentation.runOnMainSync {
                val rendered = remote.apply(context, FrameLayout(context))
                val text = allText(rendered)
                assertTrue("$style/$name missing provenance: $text", text.contains("UNAVAILABLE") || text.contains("MANUAL") || text.contains("DEMO") || text.contains("LIVE"))
                assertTrue("$style/$name missing source: $text", text.contains("Apex Legends Status") || text.contains("User entered") || text.contains("Synthetic demonstration"))
                assertTrue("$style/$name missing update: $text", text.contains("update") || text.contains("Updated") || text.contains("Synced"))
                assertTrue("$style/$name missing appearance editor: $text", text.contains("Look"))
                if (name.startsWith("manual")) { assertTrue(text.contains("13460 RP")); assertTrue(text.contains("16000")) }
                if (name == "demo-map") assertTrue(text.contains("Example Highlands"))
                if (name == "live-predator") {
                    assertTrue(text.contains("20000 RP")); assertTrue(text.contains("6540 RP to cutoff"))
                    if (size.height >= 200.dp) assertTrue(text.contains("1234 Master + Predator players"))
                    assertFalse(text.contains("123456")); assertFalse(text.contains("Synthetic widget fixture"))
                }
                val width = (size.width.value * context.resources.displayMetrics.density).toInt()
                val height = (size.height.value * context.resources.displayMetrics.density).toInt()
                rendered.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                rendered.layout(0, 0, width, height)
                val basename = "v05-widget-${style.name.lowercase()}-$name-${size.width.value.toInt()}.png"
                val clipped = criticalGlyphClipping(rendered)
                geometryEvidence.put(basename, JSONObject().put("pass",clipped.isEmpty()).put("findings",JSONArray(clipped)))
                geometryFailures += clipped.map { "$basename: $it" }
                val image = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
                rendered.draw(Canvas(image))
                File(context.filesDir,basename).outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
                image.recycle()
            }
        }
        // Finish all captures before failing so the same run supports visual diagnosis.
        File(context.filesDir,"v05-widget-geometry.json").writeText(geometryEvidence.toString(2))
        assertEquals("Appearance cannot initiate a provider request",0,calls)
        assertTrue("Critical native widget text is clipped:\n${geometryFailures.take(80).joinToString("\n")}",geometryFailures.isEmpty())
    }

    @Test fun independentNativePreferencesSurviveReloadAndAppAppearancePublication() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("appwidget grantbind --package ${context.packageName} --user 0").use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
        }
        val host = AppWidgetHost(context, 73105)
        val manager = AppWidgetManager.getInstance(context)
        val ids = List(2) { host.allocateAppWidgetId() }
        val previous = File(context.filesDir,"widget-appearance-v1.json").takeIf { it.exists() }?.readBytes()
        try {
            ids.forEach { id ->
                assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context,MapRotationWidgetReceiver::class.java), Bundle()))
            }
            val first = GlanceAppWidgetManager(context).getGlanceIdBy(ids[0])
            val second = GlanceAppWidgetManager(context).getGlanceIdBy(ids[1])
            updateAppWidgetState(context, first) {
                it[WIDGET_STYLE_KEY] = "TACTICAL"; it[WIDGET_SURFACE_KEY] = "OLED"; it[WIDGET_DENSITY_KEY] = "COMPACT"
            }
            updateAppWidgetState(context, second) { it[WIDGET_STYLE_KEY] = "EDITORIAL"; it[WIDGET_SURFACE_KEY] = "LIGHT" }
            WidgetAppearancePublisher.publish(context, WidgetAppearanceCodec.encode(WidgetAppearance(WidgetDesign.LIQUID,"D3B9FF")))
            val one = widgetInstanceAppearance(getAppWidgetState(context,PreferencesGlanceStateDefinition,first))
            val two = widgetInstanceAppearance(getAppWidgetState(context,PreferencesGlanceStateDefinition,second))
            assertEquals(WidgetDesign.TACTICAL,one.style); assertEquals(WidgetSurface.OLED,one.surface)
            assertEquals(WidgetDensity.COMPACT,one.density); assertEquals(WidgetDesign.EDITORIAL,two.style)
            assertEquals(WidgetSurface.LIGHT,two.surface); assertNull(two.density)
            assertEquals(WidgetDesign.LIQUID,WidgetAppearancePublisher.read(context).style)
            WidgetAppearancePublisher.publish(context, WidgetAppearanceCodec.encode(WidgetAppearance()))
            assertEquals(one,widgetInstanceAppearance(getAppWidgetState(context,PreferencesGlanceStateDefinition,first)))
            assertEquals(two,widgetInstanceAppearance(getAppWidgetState(context,PreferencesGlanceStateDefinition,second)))
        } finally {
            ids.forEach(host::deleteAppWidgetId); host.deleteHost()
            val file = File(context.filesDir,"widget-appearance-v1.json")
            if (previous == null) file.delete() else file.writeBytes(previous)
            instrumentation.uiAutomation.executeShellCommand("appwidget revokebind --package ${context.packageName} --user 0").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }

    private fun allText(view: View): String = when(view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { allText(view.getChildAt(it)) }
        else -> ""
    }
    /** Actual measured glyphs and ancestor clip bounds, independent of unattached global rect APIs. */
    private fun criticalGlyphClipping(root: View): List<String> {
        val findings = mutableListOf<String>()
        fun critical(text: String): Boolean =
            Regex("^(LIVE|STALE|UNAVAILABLE|DEMO|MANUAL)( ·|$)").containsMatchIn(text) ||
                text in setOf("Apex Legends Status","Synthetic demonstration","User entered","Map unknown","Map unavailable",
                    "RP unknown","RP unavailable","No cutoff","Cutoff unavailable","Example Highlands","No successful update",
                    "Details","Less","Look","Open","Open app","Mode","Refresh","Platform","PC") ||
                Regex("^[0-9]+ RP$").matches(text) ||
                listOf("Updated ","Synced ","Ends ","Next:","Next map ","Target ","Goal ","Goal:").any(text::startsWith) ||
                text.contains("RP/day") || text.contains("RP to cutoff") || text.contains("Master + Predator players")
        fun intersection(first: RectF, second: RectF): RectF = RectF(first).apply {
            if (!intersect(second)) setEmpty()
        }
        fun walk(view: View, left: Float, top: Float, inheritedClip: RectF) {
            if (view.visibility != View.VISIBLE || view.alpha <= 0f) return
            val frame = RectF(left,top,left+view.width,top+view.height)
            val visible = intersection(inheritedClip,frame)
            if (view is TextView && critical(view.text.toString())) {
                val layout = view.layout
                if (layout == null || layout.lineCount == 0) {
                    findings += "No measured text layout for '${view.text}'"
                } else for (line in 0 until layout.lineCount) {
                    if (layout.getEllipsisCount(line) > 0) {
                        findings += "Ellipsized '${view.text}' on line $line"
                        continue
                    }
                    val start = layout.getLineStart(line)
                    val end = layout.getLineEnd(line).coerceAtMost(view.text.length)
                    val text = view.text.subSequence(start,end).toString().trimEnd()
                    if (text.isEmpty()) continue
                    val ink = Rect()
                    view.paint.getTextBounds(text,0,text.length,ink)
                    val x = left + view.totalPaddingLeft - view.scrollX + layout.getLineLeft(line)
                    val baseline = top + view.totalPaddingTop - view.scrollY + layout.getLineBaseline(line)
                    val glyphs = RectF(x+ink.left,baseline+ink.top,x+ink.right,baseline+ink.bottom)
                    val tolerance = 1.5f // fractional dp/antialias rounding, not a text-line allowance
                    if (glyphs.left < visible.left-tolerance || glyphs.top < visible.top-tolerance ||
                        glyphs.right > visible.right+tolerance || glyphs.bottom > visible.bottom+tolerance) {
                        findings += "Clipped '${view.text}' line $line ink=$glyphs visible=$visible"
                    }
                }
            }
            if (view is ViewGroup) {
                var childClip = RectF(inheritedClip)
                if (view.clipChildren) childClip = intersection(childClip,frame)
                if (view.clipToPadding) childClip = intersection(childClip,RectF(
                    left+view.paddingLeft,top+view.paddingTop,left+view.width-view.paddingRight,top+view.height-view.paddingBottom))
                for (index in 0 until view.childCount) {
                    val child = view.getChildAt(index)
                    walk(child,left+child.left-view.scrollX+child.translationX,top+child.top-view.scrollY+child.translationY,childClip)
                }
            }
        }
        walk(root,0f,0f,RectF(0f,0f,root.width.toFloat(),root.height.toFloat()))
        return findings
    }
    private fun findButton(view: View,label: String): Button? = when(view) {
        is Button -> view.takeIf { it.text.toString() == label }
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findButton(view.getChildAt(it),label) }
        else -> null
    }
    private fun awaitCondition(check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(50)
        }
        assertTrue("Timed out waiting for native widget editor",check())
    }
}
