package io.github.szymonbonkowski.apexcompanion

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.szymonbonkowski.apexcompanion.history.HistoryDocuments
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.matches.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Compose controls with synthetic results and isolated stores. Not native persistence or OCR evidence. */
@RunWith(AndroidJUnit4::class)
class MatchScreenTest {
    private class Store: RotationStore {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    }
    private class Documents: HistoryDocuments {
        var exported: String? = null
        var input: String? = null
        override suspend fun exportJson(value: String): Boolean { exported = value; return true }
        override suspend fun importJson() = input
    }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var scenario: ActivityScenario<MainActivity>? = null
    private val now = System.currentTimeMillis() / 1000 - 3600

    @Test fun manualResultCorrectionStatisticsAndRepeatRestoreUseActualControls() = runBlocking {
        val store = Store(); val documents = Documents()
        val repository = MatchRepository(store, RotationClock { System.currentTimeMillis() / 1000 }); repository.initialize()
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            scenario = activity
            attach(activity, repository, documents)
            navigateMatches()
            scrollTo(By.text("Add match")); click("Add match")
            scrollTo(By.text("RANKED")); click("RANKED")
            field("Map (optional)", "Synthetic test map")
            field("Legend (optional)", "Synthetic test legend")
            field("Kills (0–100, optional)", "2")
            field("Assists (0–100, optional)", "1")
            field("Damage (0–100000, optional)", "500")
            field("Placement (1–100, optional)", "3")
            field("RP delta (-10000–10000, optional)", "-25")
            scrollTo(By.text("Review match before saving")); click("Review match before saving")
            assertTrue(device.wait(Until.hasObject(By.text("Confirm this match?")), 5000))
            assertEquals(0, repository.state.value.records.size)
            click("Confirm match")
            await { repository.state.value.records.size == 1 }
            val first = repository.state.value.records.single()
            assertEquals(MatchSource.MANUAL, first.source)
            assertEquals(MatchQuality.USER_CONFIRMED, first.quality)
            assertEquals(2, first.kills); assertEquals(-25, first.rpDelta)
            scrollTo(By.text("Match details")); capture("v04-match-detail.png")
            scrollTo(By.text("Correct and confirm")); click("Correct and confirm")
            field("Kills (0–100, optional)", "4")
            scrollTo(By.text("Review match before saving")); click("Review match before saving"); click("Confirm match")
            await { repository.state.value.records.single().revision == 1 }
            assertEquals(first.id, repository.state.value.records.single().id)
            assertEquals(4, repository.state.value.records.single().kills)
            scrollTo(By.text("Statistics")); click("Statistics")
            scrollTo(By.text("Average kills 4.0 · n=1"))
            assertTrue(device.hasObject(By.text("Average kills 4.0 · n=1")))
            capture("v04-match-statistics.png")
            scrollTo(By.text("Export match backup")); click("Export match backup")
            await { documents.exported != null }
            documents.input = documents.exported
            scrollTo(By.text("Delete all match history")); click("Delete all match history")
            click("Confirm match deletion"); await { repository.state.value.records.isEmpty() }
            restore()
            assertEquals(1, repository.state.value.records.size)
            restore()
            assertEquals(1, repository.state.value.records.size)
            assertEquals(4, repository.state.value.records.single().kills)
            val restored = MatchRepository(store, RotationClock { System.currentTimeMillis() / 1000 }); restored.initialize()
            assertEquals(repository.state.value.records, restored.state.value.records)
            activity.recreate(); attach(activity, restored, documents); navigateMatches()
            scrollTo(By.text("1 recorded matches")); capture("v04-match-list.png")
        }
    }

    @Test fun automaticAggregatesRequireOptInAndScreenshotDraftRequiresConfirmation() = runBlocking {
        val store = Store(); val repository = MatchRepository(store, RotationClock { System.currentTimeMillis() / 1000 }); repository.initialize()
        val auto = MatchRecord("synthetic-auto", mode = MatchMode.RANKED, kills = 8, timestampSeconds = now - 60,
            timestampSource = MatchTimestampSource.RESULT_OBSERVED, source = MatchSource.WINDOWS_CAPTURE,
            quality = MatchQuality.AUTO_ACCEPTED, evidenceEventId = "synthetic-pc:event1", parserVersion = "synthetic-test-v1", fieldConfidence = mapOf("kills" to 1.0))
        assertTrue(repository.applySync("synthetic-pc", 1, listOf(auto), null))
        val picker = object: MatchScreenshotPicker {
            override suspend fun pick() = MatchScreenshotDraft("synthetic-image-digest", "synthetic-picker-test-v1", mapOf("kills" to "2"), warning = "Synthetic picker draft. No OCR quality claim.")
        }
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            scenario = activity; attach(activity, repository, Documents(), picker); navigateMatches()
            scrollTo(By.text("Statistics")); click("Statistics")
            scrollTo(By.text("Average kills Unknown · n=0"))
            assertTrue(device.hasObject(By.text("Average kills Unknown · n=0")))
            scrollTo(By.desc("Include automatically accepted matches")); activate(description = "Include automatically accepted matches"); device.waitForIdle(500); refresh()
            scrollTo(By.text("Average kills 8.0 · n=1"))
            assertTrue(device.hasObject(By.text("Average kills 8.0 · n=1")))
            scrollTo(By.text("Choose results screenshot")); click("Choose results screenshot")
            assertEquals(1, repository.state.value.records.size)
            scrollTo(By.text("Cancel match entry")); click("Cancel match entry")
            assertEquals(1, repository.state.value.records.size)
            scrollTo(By.text("Choose results screenshot")); click("Choose results screenshot")
            scrollTo(By.text("Review match before saving")); click("Review match before saving")
            assertEquals(1, repository.state.value.records.size)
            assertTrue(device.wait(Until.hasObject(By.text("Confirm this match?")), 5000))
            click("Confirm match"); await { repository.state.value.records.size == 2 }
            val screenshot = repository.state.value.records.single { it.source == MatchSource.SCREENSHOT }
            assertEquals(2, screenshot.kills); assertNull(screenshot.damage)
            assertEquals(MatchQuality.USER_CONFIRMED, screenshot.quality)
            assertEquals("synthetic-image-digest", screenshot.evidenceEventId)
            scrollTo(By.text("Choose results screenshot")); click("Choose results screenshot")
            scrollTo(By.text("Screenshot already recorded"))
            assertEquals(2, repository.state.value.records.size)
            scrollTo(By.text("Cancel match entry")); click("Cancel match entry")
        }
    }

    private fun attach(activity: ActivityScenario<MainActivity>, repository: MatchRepository, documents: Documents, picker: MatchScreenshotPicker? = null) {
        activity.onActivity { it.setContent { App(matchRepository = repository, matchDocuments = documents, matchScreenshots = picker) } }
        device.waitForIdle(250); refresh()
        assertTrue(device.wait(Until.hasObject(By.text("Maps")), 10000))
    }
    private fun navigateMatches() {
        val until = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < until) {
            refresh()
            val node = device.findObject(By.text("Matches"))
            if (node != null) {
                try { var click = node; while (!click.isClickable && click.parent != null) click = click.parent; click.click(); device.waitForIdle(250); return }
                catch (_: androidx.test.uiautomator.StaleObjectException) { }
            }
            android.os.SystemClock.sleep(100)
        }
        fail("Missing Matches navigation")
    }
    private fun field(label: String, value: String) {
        scrollTo(By.desc(label))
        var editor: androidx.test.uiautomator.UiObject2? = null
        repeat(5) {
            refresh()
            val node = device.findObject(By.desc(label))
            editor = if (node?.className == "android.widget.EditText") node else node?.findObject(By.clazz("android.widget.EditText"))
            if (editor == null) editor = device.findObjects(By.clazz("android.widget.EditText")).firstOrNull { candidate ->
                val bounds = candidate.visibleBounds
                val labelBounds = node?.visibleBounds
                labelBounds != null && bounds.top <= labelBounds.bottom && bounds.bottom >= labelBounds.top
            }
            if (editor != null && editor!!.visibleBounds.height() > 80) return@repeat
            swipe(false)
        }
        if (editor == null) {
            dump("v04-editor-failure.xml")
            error("Editor missing: $label")
        }
        editor!!.click(); editor!!.text = value
        hideKeyboard()
    }
    private fun hideKeyboard() {
        scenario?.onActivity { activity ->
            activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(activity.currentFocus?.windowToken ?: activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
            if (android.os.Build.VERSION.SDK_INT >= 30) activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        device.waitForIdle(250); refresh()
    }
    private fun click(text: String) {
        refresh()
        if (!device.wait(Until.hasObject(By.text(text)), 5000)) {
            capture("v04-control-failure.png"); dump("v04-control-failure.xml")
            fail("Missing control: $text")
        }
        activate(text = text)
        device.waitForIdle(500); refresh()
    }
    private fun activate(text: String? = null, description: String? = null) {
        // Invoke the real accessibility action rather than a coordinate captured during scroll settling.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun find(node: android.view.accessibility.AccessibilityNodeInfo): android.view.accessibility.AccessibilityNodeInfo? {
            if ((text != null && node.text?.toString() == text) || (description != null && node.contentDescription?.toString() == description)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { child -> find(child)?.let { return it } }
            return null
        }
        var node = automation.rootInActiveWindow?.let(::find) ?: error("Missing accessible action: ${text ?: description}")
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue(node.isEnabled && node.isClickable)
        assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun restore() {
        scrollTo(By.text("Preview match import")); click("Preview match import")
        assertTrue(device.wait(Until.hasObject(By.text("Confirm match restore?")), 5000))
        click("Confirm restore")
        assertTrue(device.wait(Until.gone(By.text("Confirm match restore?")), 5000))
    }
    private fun scrollTo(selector: BySelector) {
        var attempts = 0
        while (attempts++ < 20) { refresh(); if (fullyVisible(selector)) return; if (!swipe(true)) break }
        attempts = 0
        while (attempts++ < 65) { refresh(); if (fullyVisible(selector)) return; if (!swipe(false)) break }
        refresh(); if (fullyVisible(selector)) return
        capture("v04-ui-failure.png"); dump("v04-ui-failure.xml")
        fail("Missing UI: $selector")
    }
    private fun fullyVisible(selector: BySelector): Boolean {
        val node = device.findObject(selector) ?: return false
        val bounds = node.visibleBounds
        val scroll = device.findObject(By.clazz("android.widget.ScrollView"))?.visibleBounds ?: return true
        // Accessibility includes clipped nodes; do not tap a one-pixel edge at the viewport boundary.
        return bounds.height() >= 30 && bounds.width() >= 30 && bounds.top >= scroll.top + 8 && bounds.bottom <= scroll.bottom - 70
    }
    private fun swipe(top: Boolean): Boolean {
        val scroller = device.findObject(By.clazz("android.widget.ScrollView")) ?: return false
        val bounds = scroller.visibleBounds
        val before = scroller.findObjects(By.clazz("android.widget.TextView")).map { runCatching { it.text to it.visibleBounds.top }.getOrNull() }
        val x = bounds.right - 12
        val start = if (top) bounds.top + bounds.height() / 6 else bounds.bottom - bounds.height() / 6
        val end = start + if (top) bounds.height() / 4 else -bounds.height() / 4
        device.swipe(x, start, x, end, 80); device.waitForIdle(250); refresh()
        val after = device.findObject(By.clazz("android.widget.ScrollView"))?.findObjects(By.clazz("android.widget.TextView"))?.map { runCatching { it.text to it.visibleBounds.top }.getOrNull() }
        return before != after
    }
    private fun refresh() { if (android.os.Build.VERSION.SDK_INT >= 37) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache() }
    private fun output(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }.resolve(name)
    }
    private fun capture(name: String) { device.takeScreenshot(output(name)) }
    private fun dump(name: String) { device.dumpWindowHierarchy(output(name)) }
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < until) android.os.SystemClock.sleep(50)
        assertTrue("Durable state not published", condition())
    }
}
