package io.github.szymonbonkowski.apexcompanion

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.szymonbonkowski.apexcompanion.history.*
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Synthetic in-memory stores isolate these actual shared Compose controls from private app data.
 * Native AtomicFile/process death and SAF are covered by separate instrumentation, not simulated here.
 */
@RunWith(AndroidJUnit4::class)
class HistoryScreenTest {
    private class Store : RotationStore {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    }
    private class Fixture(val historyStore: Store = Store(), val profileStore: Store = Store(), var now: Long = System.currentTimeMillis() / 1000 - 3600) {
        val clock = RotationClock { now }
        val history = HistoryRepository(historyStore, clock)
        val ranked = RankedRepository(UnconfiguredRankedProvider, profileStore, clock, history = history)
        suspend fun initialize() { ranked.initialize() }
    }
    private class Documents(var input: String? = null): HistoryDocuments {
        var exported: String? = null
        var importRequests = 0
        override suspend fun exportJson(value: String): Boolean { exported = value; return true }
        override suspend fun importJson(): String? { importRequests++; return input }
    }
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var activityScenario: ActivityScenario<MainActivity>? = null

    @Test fun optInContextAndManualSamplesRenderUnknownAndCompatibleIntervals() = runBlocking {
        val fixture = Fixture(); fixture.initialize()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario, fixture)
            navigate("History")
            scrollTo("No saved observations")
            assertTrue(device.hasObject(By.text("Local history recording OFF")))
            assertEquals(0, fixture.history.state.value.record.observations.size)
            navigate("Settings")
            scrollToSelector(By.desc("Record local RP history"), "recording switch")
            device.findObject(By.desc("Record local RP history")).click()
            await { fixture.history.state.value.record.recording }
            scrollTo("Local history recording ON")
            assertTrue(device.hasObject(By.text("Local history recording ON")))
            saveManualUi("13000")
            await { fixture.history.state.value.record.observations.size == 1 }
            assertEquals(ContextVerification.UNKNOWN, fixture.history.state.value.record.observations.single().verification)
            fixture.now += 30
            navigate("Settings")
            scrollTo("Review context confirmation")
            val fields = device.findObjects(By.clazz("android.widget.EditText"))
            assertTrue("Context fields are visible", fields.size >= 2)
            fields[fields.size - 2].click(); fields[fields.size - 2].text = "Synthetic season"
            hideKeyboard()
            val split = device.findObjects(By.clazz("android.widget.EditText")).last()
            split.click(); split.text = "Synthetic split"; hideKeyboard()
            scrollTo("Review context confirmation")
            clickText("Review context confirmation")
            assertTrue(device.wait(Until.hasObject(By.text("Confirm current context?")), 5_000))
            assertNull(fixture.history.state.value.record.currentContextId)
            clickText("Confirm context")
            assertTrue(device.wait(Until.gone(By.text("Confirm current context?")), 5_000))
            await { fixture.history.state.value.record.currentContextId != null }
            assertTrue(fixture.ranked.setManualRp(13000))
            fixture.now += 30
            assertTrue(fixture.ranked.setManualRp(13150))
            navigate("History")
            scrollTo("RP over time")
            assertTrue(device.hasObject(By.textContains("3 saved observations")))
            scrollTo("Points are recorded observations. Lines connect compatible contexts only. Unknown contexts, quarantined samples and long gaps remain visually separate.")
            capture("v03-history-chart.png")
            scrollTo("RP unknown")
            scrollTo("+150 RP")
            assertTrue(device.hasObject(By.text("+150 RP")))
            assertTrue(device.hasObject(By.text("User-confirmed season/split · observation interval")))
            val observations = fixture.history.state.value.record.observations
            assertEquals(listOf(13000L, 13000L, 13150L), observations.map { it.rp })
            assertTrue(observations.all { it.source == ObservationSource.USER_RECORDED && it.identity == null })
            assertEquals(150L, rpIntervals(observations).last().deltaRp)
        }
    }

    @Test fun sessionUiRestoresActiveStateAndDistinguishesManualFromPartial() = runBlocking {
        val first = Fixture(); first.initialize()
        assertTrue(first.history.setRecording(true))
        assertTrue(first.history.confirmContext(null, ObservationSource.USER_RECORDED, "Synthetic season", "Synthetic split"))
        assertTrue(first.ranked.setManualRp(13000))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario, first)
            navigate("Sessions")
            scrollTo("Start session")
            clickText("Start session")
            assertTrue(device.wait(Until.hasObject(By.text("ACTIVE SESSION")), 5_000))
            val active = first.history.state.value.record.sessions.single()
            first.now += 30
            assertTrue(first.ranked.setManualRp(13150))
            scenario.recreate()
            val restored = Fixture(first.historyStore, first.profileStore, first.now)
            restored.initialize()
            assertEquals(active.id, restored.history.state.value.record.sessions.single().id)
            assertEquals(active.startedAtSeconds, restored.history.state.value.record.sessions.single().startedAtSeconds)
            attach(scenario, restored)
            navigate("Sessions")
            assertTrue(device.wait(Until.hasObject(By.text("ACTIVE SESSION")), 5_000))
            scrollTo("Stop session")
            clickText("Stop session")
            await { restored.history.state.value.record.sessions.single().endedAtSeconds != null }
            scrollTo("Session details")
            clickText("Session details")
            assertTrue(device.wait(Until.hasObject(By.text("MANUAL · +150 RP")), 5_000))
            assertTrue(device.hasObject(By.textContains("Observation window:")))
            capture("v03-session-detail.png")
            clickText("Close")
            navigate("History"); navigate("Sessions")
            scrollTo("Start session")
            restored.now += 30
            clickText("Start session")
            assertTrue(device.wait(Until.hasObject(By.text("ACTIVE SESSION")), 5_000))
            restored.now += 30
            scrollTo("Stop session")
            clickText("Stop session")
            await { restored.history.state.value.record.sessions.none { it.endedAtSeconds == null } }
            scrollTo("PARTIAL · RP unknown")
            assertTrue(device.hasObject(By.text("PARTIAL · RP unknown")))
            assertEquals(2, restored.history.state.value.record.sessions.size)
        }
    }

    @Test fun backupPreviewRequiresConfirmationAndDeletePreservesRestoredGoal() = runBlocking {
        val source = Fixture(); source.initialize()
        assertTrue(source.history.setRecording(true))
        assertTrue(source.history.confirmContext(null, ObservationSource.USER_RECORDED, "Synthetic season", "Synthetic split"))
        assertTrue(source.ranked.setManualRp(13000))
        assertTrue(source.ranked.saveGoal(RankGoal(16000, 13000)))
        source.now += 30; assertTrue(source.ranked.setManualRp(13150))
        val backup = source.history.exportBackup()
        val target = Fixture(now = source.now); target.initialize()
        val documents = Documents(backup)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario, target, documents)
            navigate("Settings")
            scrollTo("Import backup")
            clickText("Import backup")
            assertTrue(device.wait(Until.hasObject(By.text("Import preview")), 5_000))
            assertTrue(device.hasObject(By.text("2 observations · 0 sessions")))
            assertEquals(0, target.history.state.value.record.observations.size)
            assertNull(target.ranked.state.value.goal)
            capture("v03-import-preview.png")
            clickText("Cancel")
            assertEquals(0, target.history.state.value.record.observations.size)
            importThroughUi()
            await { target.history.state.value.record.observations.size == 2 }
            assertEquals(16000L, target.ranked.state.value.goal?.targetRp)
            assertFalse(target.history.state.value.record.recording)
            importThroughUi()
            await { documents.importRequests == 3 }
            assertEquals(2, target.history.state.value.record.observations.size)
            assertEquals(1, target.history.state.value.record.importedBatchIds.size)
            scrollTo("Export backup")
            clickText("Export backup")
            await { documents.exported != null }
            assertEquals(2, HistoryCodec.import(documents.exported!!, target.now).record.observations.size)
            assertFalse(documents.exported!!.contains("rankName"))
            scrollTo("Delete saved history", clickable = true)
            device.findObject(By.clickable(true).hasDescendant(By.text("Delete saved history"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("Delete all saved history?")), 5_000))
            assertEquals(2, target.history.state.value.record.observations.size)
            clickText("Cancel")
            assertEquals(2, target.history.state.value.record.observations.size)
            device.findObject(By.clickable(true).hasDescendant(By.text("Delete saved history"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("Delete all saved history?")), 5_000))
            clickText("Delete history")
            await { target.history.state.value.record.observations.isEmpty() }
            assertEquals(16000L, target.ranked.state.value.goal?.targetRp)
            assertEquals(13150L, target.ranked.state.value.manualRp)
            importThroughUi()
            await { target.history.state.value.record.observations.size == 2 }
            importThroughUi()
            assertEquals(2, target.history.state.value.record.observations.size)
        }
    }

    private fun attach(scenario: ActivityScenario<MainActivity>, fixture: Fixture, documents: HistoryDocuments? = null) {
        activityScenario = scenario
        scenario.onActivity { activity -> activity.setContent {
            App(rankedRepository = fixture.ranked, historyRepository = fixture.history, historyDocuments = documents)
        } }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        device.waitForIdle(250)
        assertTrue(device.wait(Until.hasObject(By.text("Maps")), 10_000))
    }
    private fun navigate(destination: String) {
        repeat(5) {
            if (device.hasObject(By.text(destination))) {
                val node = device.findObject(By.text(destination))
                if (node.parent.isChecked) return
                node.parent.click()
                device.waitForIdle(250)
                val until = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < until) {
                    if (device.findObject(By.text(destination))?.parent?.isChecked == true) return
                    android.os.SystemClock.sleep(50)
                }
            }
            device.findObjects(By.scrollable(true)).firstOrNull()?.scroll(
                if (destination == "Maps" || destination == "Ranked progress") Direction.LEFT else Direction.RIGHT, 0.8f, 1000)
        }
        fail("Navigation destination not visible: $destination")
    }
    private fun saveManualUi(rp: String) {
        navigate("Ranked progress")
        scrollTo("Save and use manual RP")
        val field = device.findObjects(By.clazz("android.widget.EditText")).last()
        field.click(); field.text = rp; hideKeyboard()
        scrollTo("Save and use manual RP")
        clickText("Save and use manual RP")
        device.waitForIdle(250)
    }
    private fun hideKeyboard() {
        activityScenario?.onActivity { activity ->
            val input = activity.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            input.hideSoftInputFromWindow(activity.currentFocus?.windowToken ?: activity.window.decorView.windowToken, 0)
            activity.currentFocus?.clearFocus()
            if (android.os.Build.VERSION.SDK_INT >= 30)
                activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        device.waitForIdle(250)
        refreshAccessibility()
    }
    private fun clickText(text: String) {
        refreshAccessibility()
        var node = device.findObject(By.text(text)) ?: error("Missing control: $text")
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Control is not clickable: $text", node.isClickable)
        node.click()
        device.waitForIdle(250)
        refreshAccessibility()
    }
    private fun importThroughUi() {
        scrollTo("Import backup")
        clickText("Import backup")
        assertTrue(device.wait(Until.hasObject(By.text("Import preview")), 5_000))
        clickText("Confirm import")
        assertTrue(device.wait(Until.gone(By.text("Import preview")), 5_000))
        device.waitForIdle(250)
    }
    private fun scrollTo(text: String, clickable: Boolean = false) = scrollToSelector(
        if (clickable) By.clickable(true).hasDescendant(By.text(text)) else By.text(text), text)
    private fun scrollToSelector(selector: BySelector, description: String) {
        // Begin at the top so helpers also handle earlier controls after inspecting a later section.
        var attempts = 0
        while (attempts++ < 12) {
            refreshAccessibility()
            if (device.hasObject(selector)) return
            if (!verticalSwipe(towardTop = true)) break
        }
        attempts = 0
        while (attempts++ < 45) {
            refreshAccessibility()
            if (device.hasObject(selector)) return
            if (!verticalSwipe(towardTop = false)) break
        }
        if (device.hasObject(selector)) return
        capture("v03-ui-failure.png")
        device.dumpWindowHierarchy(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "v03-ui-failure.xml"))
        fail("Missing expected UI: $description")
    }
    private fun verticalSwipe(towardTop: Boolean): Boolean {
        val scroller = device.findObject(By.clazz("android.widget.ScrollView")) ?: return false
        val bounds = scroller.visibleBounds
        val before = scroller.findObjects(By.clazz("android.widget.TextView")).map { runCatching { it.text to it.visibleBounds.top }.getOrNull() }
        // Swipe along the content's right padding so text editors cannot consume the gesture.
        val x = bounds.right - 12
        val top = bounds.top + bounds.height() / 6
        val bottom = bounds.bottom - bounds.height() / 6
        val distance = bounds.height() / 5
        device.swipe(x, if (towardTop) top else bottom, x,
            if (towardTop) top + distance else bottom - distance, 120)
        device.waitForIdle(250)
        refreshAccessibility()
        val after = device.findObject(By.clazz("android.widget.ScrollView"))
            ?.findObjects(By.clazz("android.widget.TextView"))?.map { runCatching { it.text to it.visibleBounds.top }.getOrNull() }
        return before != after
    }
    private fun refreshAccessibility() {
        // API37 exposes cache clearing; refresh virtual Compose nodes after scroll/recomposition.
        if (android.os.Build.VERSION.SDK_INT >= 37)
            InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
    }
    private fun capture(name: String) {
        device.takeScreenshot(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, name))
    }
    private fun await(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!condition() && System.currentTimeMillis() < until) android.os.SystemClock.sleep(50)
        assertTrue("Expected durable state was not published", condition())
    }
}
