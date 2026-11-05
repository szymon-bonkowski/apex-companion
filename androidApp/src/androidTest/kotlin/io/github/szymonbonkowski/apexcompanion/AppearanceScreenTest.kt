package io.github.szymonbonkowski.apexcompanion

import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.szymonbonkowski.apexcompanion.appearance.*
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.matches.*
import io.github.szymonbonkowski.apexcompanion.platform.AndroidAppearanceRuntime
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual shared Compose controls and emulator pixels, with synthetic isolated repositories only. */
@RunWith(AndroidJUnit4::class)
class AppearanceScreenTest {
    private class Store : RotationStore {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    }
    @Volatile private var observedInputMode: androidx.compose.ui.input.InputModeManager? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val device get() = UiDevice.getInstance(instrumentation)
    private val args get() = InstrumentationRegistry.getArguments()
    private fun captureMode(style: DesignStyle) = args.getString("v05Mode")?.let(AppearanceMode::valueOf)
        ?: if (style == DesignStyle.EDITORIAL) AppearanceMode.LIGHT else AppearanceMode.SYSTEM
    private val output get() = File(instrumentation.targetContext.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }

    @Test fun widgetLinksOpenTheirDestinationAndSurviveRecreation() {
        val context = instrumentation.targetContext
        fun link(destination: String) = android.content.Intent(context, MainActivity::class.java)
            .setData(android.net.Uri.parse("apexcompanion://$destination"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        ActivityScenario.launch<MainActivity>(link("maps")).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("Map rotation")), 10_000))
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("Map rotation")), 10_000))
            context.startActivity(link("ranked"))
            assertTrue(device.wait(Until.hasObject(By.text("Your next goal, one day at a time.")), 10_000))
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("Your next goal, one day at a time.")), 10_000))
            context.startActivity(link("maps"))
            assertTrue(device.wait(Until.hasObject(By.text("Map rotation")), 10_000))
            context.startActivity(link("pairing?token=ignored"))
            assertTrue(device.wait(Until.hasObject(By.text("Map rotation")), 5_000))
        }
    }

    @Test fun hardwareTabAndEnterNavigateToStudioAndBackWithoutTouchActions() = runBlocking {
        val appearance = AppearanceRepository(Store()); appearance.initialize()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario, appearance)
            fun focusedLabel(): String {
                refresh()
                fun focused(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                    if (node.isFocused && node.isFocusable) return node
                    for (index in 0 until node.childCount) {
                        val child = node.getChild(index) ?: continue
                        focused(child)?.let { return it }
                    }
                    return null
                }
                val node = instrumentation.uiAutomation.rootInActiveWindow?.let(::focused)
                val labels = mutableListOf<String>()
                node?.let { current ->
                    current.contentDescription?.let { labels += it.toString() }
                    current.text?.let { labels += it.toString() }
                    for (index in 0 until current.childCount) current.getChild(index)?.let { child ->
                        child.text?.let { labels += it.toString() }
                        child.contentDescription?.let { labels += it.toString() }
                    }
                }
                // A focused text child may expose its button label on the immediate parent.
                node?.parent?.contentDescription?.let { labels += it.toString() }
                return labels.joinToString(" | ")
            }
            fun tabTo(label: String): Int {
                val seen = mutableListOf<String>()
                for (count in 1..60) {
                    assertTrue("Hardware TAB injection", device.pressKeyCode(android.view.KeyEvent.KEYCODE_TAB))
                    instrumentation.waitForIdleSync()
                    device.waitForIdle(300)
                    val focused = focusedLabel()
                    seen += "$focused [mode=${observedInputMode?.inputMode}]"
                    if (focused.split(" | ").any { it == label || it == "Navigate to $label" }) return count
                }
                device.dumpWindowHierarchy(File(output, "v05-keyboard-failure.xml"))
                device.takeScreenshot(File(output, "v05-keyboard-failure.png"))
                fail("Hardware TAB could not reach named focusable control: $label; focus trace: $seen")
                return 0
            }
            val settingsTabs = tabTo("Settings")
            assertTrue(device.pressKeyCode(android.view.KeyEvent.KEYCODE_ENTER))
            assertTrue(device.wait(Until.hasObject(By.text("Your companion")), 5_000))
            val studioTabs = tabTo("Appearance Studio")
            assertTrue(device.pressKeyCode(android.view.KeyEvent.KEYCODE_ENTER))
            assertTrue(device.wait(Until.hasObject(By.text("Make this space yours.")), 5_000))
            assertTrue(device.pressKeyCode(android.view.KeyEvent.KEYCODE_BACK))
            assertTrue(device.wait(Until.hasObject(By.text("Your companion")), 5_000))
            assertEquals(AppearanceRecord(), appearance.state.value.record)
            File(output, "v05-keyboard-evidence.json").writeText("""{
                "schemaVersion":1,"syntheticData":true,"passed":true,
                "input":"Injected Android KEYCODE_TAB, KEYCODE_ENTER and KEYCODE_BACK; no touch or accessibility click action",
                "settingsTabCount":$settingsTabs,"studioTabCount":$studioTabs,
                "settingsActivated":true,"studioActivated":true,"backReturnedToSettings":true
            }""".trimIndent())
        }
    }

    @Test fun unappliedStudioDraftAndPresetDialogSurviveRealActivityRecreation() = runBlocking {
        val appearance = AndroidAppearanceRuntime.get(instrumentation.targetContext)
        appearance.initialize()
        val before = appearance.state.value.record
        val previewStyle = DesignStyle.entries.first { it != before.preferences.style }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.desc("Navigate to Today")), 10_000))
            navigate("Settings")
            scrollTo(By.text("Appearance Studio")); click("Appearance Studio")
            scrollTo(By.text(previewStyle.title)); click(previewStyle.title)
            scrollTo(By.text("Saved presets")); click("Saved presets")
            scrollTo(By.text("Save current as preset")); click("Save current as preset")
            assertTrue(device.wait(Until.hasObject(By.text("Create appearance preset")), 5_000))
            device.findObject(By.clazz("android.widget.EditText")).text = "Synthetic recreation draft"
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("Create appearance preset")), 10_000))
            assertTrue(device.hasObject(By.text("Synthetic recreation draft")))
            // Click the recreated dialog's current visible control, then verify dismissal
            // before querying the underlying scroll container.
            instrumentation.waitForIdleSync(); refresh()
            val cancel = device.wait(Until.findObject(By.text("Cancel")), 5_000)
            assertNotNull("Recreated dialog Cancel", cancel)
            cancel!!.click()
            assertTrue("Cancel dismisses recreated dialog", device.wait(Until.gone(By.text("Create appearance preset")), 5_000))
            scrollTo(By.desc("Appearance preview ${previewStyle.title}. DEMO values only."))
            assertTrue(device.hasObject(By.desc("Appearance preview ${previewStyle.title}. DEMO values only.")))
            assertEquals("Unapplied preview and canceled dialog must not alter saved preferences", before, appearance.state.value.record)
        }
    }

    @Test fun dashboardControlsSaveOrderVisibilitySizeAndResetWhileNotificationsRemainGated() = runBlocking {
        val appearance = AppearanceRepository(Store()); appearance.initialize()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario, appearance)
            scrollTo(By.text("Configure dashboard")); click("Configure dashboard")
            clickDescription("Show Current rotation")
            await { appearance.state.value.record.dashboard?.items?.single { it.module == DashboardModule.MAP_ROTATION }?.visible == false }
            clickDescription("Current rotation size compact")
            await { appearance.state.value.record.dashboard?.items?.single { it.module == DashboardModule.MAP_ROTATION }?.size == ModuleSize.COMPACT }
            clickDescription("Move Ranked goal up")
            await { appearance.state.value.record.dashboard?.items?.first()?.module == DashboardModule.GOAL_PROGRESS }
            clickDescription("Hide unavailable modules")
            await { appearance.state.value.record.dashboard?.hideUnavailable == true }
            scrollTo(By.text("Restore Carbon dashboard")); click("Restore Carbon dashboard")
            await { appearance.state.value.record.dashboard == null }
            assertEquals(AppearancePreferences(), appearance.state.value.record.preferences)
            navigate("Settings")
            scrollTo(By.text("Favorite map notifications")); click("Favorite map notifications")
            scrollTo(By.text("Delivery disabled"))
            assertTrue(device.hasObject(By.text("Unavailable · source approval required")))
            val toggles = device.findObjects(By.checkable(true))
            assertTrue("Delivery switch must be disabled and unchecked", toggles.any { !it.isEnabled && !it.isChecked })
            scrollTo(By.text("10 min")); click("10 min")
            await { appearance.state.value.record.favoriteMaps.advanceMinutes == 10 }
            assertFalse(appearance.state.value.record.favoriteMaps.enabled)
            assertTrue(appearance.state.value.record.favoriteMaps.favorites.isEmpty())
            scrollTo(By.text("Back to Settings")); click("Back to Settings")
            assertTrue(device.wait(Until.hasObject(By.text("Appearance Studio")), 5_000))
        }
    }

    @Test fun studioPreviewApplyAndPresetControlsMutateOnlyCosmeticState() = runBlocking {
        val store = Store()
        val appearance = AppearanceRepository(store)
        appearance.initialize()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario, appearance)
            navigate("Settings")
            scrollTo(By.text("Appearance Studio")); click("Appearance Studio")
            assertTrue(device.wait(Until.hasObject(By.text("Make this space yours.")), 5_000))
            for (style in listOf(DesignStyle.LIQUID, DesignStyle.EDITORIAL, DesignStyle.TACTICAL, DesignStyle.CARBON)) {
                scrollTo(By.text(style.title)); click(style.title)
                scrollTo(By.desc("Appearance preview ${style.title}. DEMO values only."))
                assertTrue(device.wait(Until.hasObject(By.desc("Appearance preview ${style.title}. DEMO values only.")), 5_000))
                val prior = appearance.state.value.record.preferences.style
                if (prior != style) assertNotEquals(style, appearance.state.value.record.preferences.style)
                scrollTo(By.text("Apply appearance")); click("Apply appearance")
                await { appearance.state.value.record.preferences.style == style }
                assertTrue(device.wait(Until.hasObject(By.text("Appearance applied and saved on this device.")), 5_000))
            }
            scrollTo(By.text("Saved presets")); click("Saved presets")
            scrollTo(By.text("Save current as preset")); click("Save current as preset")
            assertTrue(device.wait(Until.hasObject(By.text("Create appearance preset")), 5_000))
            click("Create")
            await { appearance.state.value.record.presets.size == 1 }
            val original = appearance.state.value.record.presets.single()
            scrollTo(By.text("Duplicate")); click("Duplicate")
            await { appearance.state.value.record.presets.size == 2 }
            scrollTo(By.text("Rename")); click("Rename")
            assertTrue(device.wait(Until.hasObject(By.text("Rename appearance preset")), 5_000))
            val field = device.findObject(By.clazz("android.widget.EditText"))
            field.text = "Synthetic renamed preset"
            click("Rename")
            await { appearance.state.value.record.presets.any { it.name == "Synthetic renamed preset" } }
            scrollTo(By.text("Delete")); click("Delete")
            assertTrue(device.wait(Until.hasObject(By.text("Delete appearance preset")), 5_000))
            click("Cancel")
            assertEquals(2, appearance.state.value.record.presets.size)
            // A fresh repository reads the bytes written by actual UI controls.
            val restarted = AppearanceRepository(store)
            restarted.initialize()
            assertEquals(appearance.state.value.record, restarted.state.value.record)
            assertTrue(restarted.state.value.record.presets.any { it.id == original.id })
        }
    }

    @Test fun fourStyleScreenshotMatrixUsesActualScreensAndPreservesProvenance() = runBlocking {
        val now = System.currentTimeMillis() / 1000
        val appearance = AppearanceRepository(Store()); appearance.initialize()
        val ranked = RankedRepository(UnconfiguredRankedProvider, Store(), RotationClock { System.currentTimeMillis() / 1000 })
        ranked.initialize(); ranked.setManualRp(13460); ranked.setUseManual(true); ranked.saveGoal(RankGoal(16000, 13000))
        val matches = MatchRepository(Store(), RotationClock { System.currentTimeMillis() / 1000 })
        matches.initialize()
        assertTrue(matches.saveManual(MatchRecord("synthetic-v05-qa", mode = MatchMode.RANKED,
            map = "Synthetic QA map", legend = "Synthetic QA legend", kills = 4, assists = 2,
            damage = 1240, placement = 3, rpDelta = 125, timestampSeconds = now - 3600)))
        val requestedStyle = args.getString("v05Style")
        val styles = DesignStyle.entries.filter { requestedStyle == null || it.name == requestedStyle }
        require(styles.isNotEmpty())
        val variant = args.getString("v05Variant") ?: "phone"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            attach(scenario, appearance, ranked, matches)
            for (style in styles) {
                if (args.getString("v05SettingsOnly") != "true")
                    device.executeShellCommand("dumpsys gfxinfo io.github.szymonbonkowski.apexcompanion reset")
                assertTrue(appearance.savePreferences(AppearancePreferences(style = style, mode = captureMode(style))))
                // This isolated composition replaces the host's normal appearance observer.
                // Keep its system bars aligned with the same fixture mode for the actual capture.
                scenario.onActivity { activity ->
                    val bars = if (captureMode(style) == AppearanceMode.LIGHT)
                        SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    else SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                }
                if (args.getString("v05SettingsOnly") == "true") {
                    navigate("Settings")
                    assertTrue(device.wait(Until.hasObject(By.text("Your companion")), 10_000))
                    for (attempt in 0 until 30) if (!swipe(true)) break
                    assertTrue(device.hasObject(By.text("Personalize your space")))
                    capture(style, variant, "settings")
                    continue
                }
                navigate("Today")
                capture(style, variant, "today")
                navigate("Maps")
                scrollTo(By.text("Rotation unavailable"))
                assertTrue(device.wait(Until.hasObject(By.text("Rotation unavailable")), 5_000))
                scrollTo(By.text("UNAVAILABLE"))
                assertTrue(device.wait(Until.hasObject(By.text("UNAVAILABLE")), 5_000))
                capture(style, variant, "maps-unavailable")
                scrollTo(By.text("Preview demo"))
                click("Preview demo")
                scrollTo(By.text("DEMO · Synthetic schedule"))
                assertTrue(device.wait(Until.hasObject(By.text("DEMO · Synthetic schedule")), 5_000))
                scrollTo(By.text("Example Highlands"))
                capture(style, variant, "maps-demo")
                scrollTo(By.text("Live maps"))
                click("Live maps")
                navigate("Ranked progress")
                scrollTo(By.text("13460 RP"))
                assertTrue(device.wait(Until.hasObject(By.text("13460 RP")), 5_000))
                scrollTo(By.text("MANUAL · User entered"))
                assertTrue(device.hasObject(By.text("MANUAL · User entered")))
                capture(style, variant, "ranked")
                navigate("Matches")
                scrollTo(By.text("1 recorded matches"))
                scrollTo(By.text("RANKED · Synthetic QA map · Synthetic QA legend")); capture(style, variant, "matches")
                click("RANKED · Synthetic QA map · Synthetic QA legend")
                scrollTo(By.text("Match details")); capture(style, variant, "match-detail")
                scrollTo(By.text("Statistics")); click("Statistics")
                scrollTo(By.text("Recorded match statistics")); capture(style, variant, "match-statistics")
                navigate("Settings")
                assertTrue(device.wait(Until.hasObject(By.text("Your companion")), 10_000))
                for (attempt in 0 until 30) if (!swipe(true)) break
                assertTrue(device.hasObject(By.text("Personalize your space")))
                capture(style, variant, "settings")
                scrollTo(By.text("Appearance Studio")); click("Appearance Studio")
                assertTrue(device.wait(Until.hasObject(By.text("Make this space yours.")), 5_000))
                capture(style, variant, "studio")
                assertEquals(style, appearance.state.value.record.preferences.style)
                assertEquals(1, matches.state.value.records.size)
                assertEquals(13460L, ranked.state.value.manualRp)
                File(output, "v05-${style.name.lowercase()}-$variant-gfxinfo.txt").writeText(
                    "Emulator UI navigation/capture observations, SwiftShader. Not a physical-device benchmark or FPS estimate.\n" +
                        device.executeShellCommand("dumpsys gfxinfo io.github.szymonbonkowski.apexcompanion")
                )
            }
        }
    }

    private fun attach(scenario: ActivityScenario<MainActivity>, appearance: AppearanceRepository,
                       ranked: RankedRepository? = null, matches: MatchRepository? = null) {
        scenario.onActivity { activity -> activity.setContent {
            val modeManager = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.SideEffect { observedInputMode = modeManager }
            App(appearanceRepository = appearance,
                backNavigation = { enabled, onBack -> androidx.activity.compose.BackHandler(enabled, onBack) },
                appearanceEnvironment = AppearanceEnvironment(systemDark = true),
                rankedRepository = ranked, matchRepository = matches)
        } }
        instrumentation.waitForIdleSync(); refresh()
        assertTrue(device.wait(Until.hasObject(By.desc("Navigate to Today")), 10_000))
    }

    private fun navigate(destination: String) {
        refresh()
        val selector = By.desc("Navigate to $destination")
        assertTrue("Navigation $destination", device.wait(Until.hasObject(selector), 5_000))
        activate(description = "Navigate to $destination")
        device.waitForIdle(500); refresh()
        // App keys the content by destination, so a new screen starts at its top.
    }

    private fun click(text: String) {
        refresh()
        assertTrue("Missing control $text", device.wait(Until.hasObject(By.text(text)), 5_000))
        activate(text = text)
        device.waitForIdle(500); refresh()
    }

    private fun clickDescription(description: String) {
        scrollTo(By.desc(description))
        activate(description = description)
        device.waitForIdle(500); refresh()
    }

    private fun activate(text: String? = null, description: String? = null) {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if ((text != null && node.text?.toString() == text) ||
                (description != null && node.contentDescription?.toString() == description)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { find(it)?.let { result -> return result } }
            return null
        }
        var node = instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
            ?: error("Missing accessible action ${text ?: description}")
        while (!node.isClickable && node.parent != null) node = node.parent
        assertTrue("Disabled control ${text ?: description}", node.isEnabled && node.isClickable)
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun scrollTo(selector: BySelector) {
        for (attempt in 0 until 30) { refresh(); if (fullyVisible(selector)) return; if (!swipe(true)) break }
        for (attempt in 0 until 85) { refresh(); if (fullyVisible(selector)) return; if (!swipe(false)) break }
        device.dumpWindowHierarchy(File(output, "v05-ui-failure.xml"))
        device.takeScreenshot(File(output, "v05-ui-failure.png"))
        fail("Missing fully visible UI $selector")
    }

    private fun fullyVisible(selector: BySelector): Boolean {
        return try { fullyVisibleOnce(selector) }
        catch (_: androidx.test.uiautomator.StaleObjectException) { refresh(); false }
    }

    private fun fullyVisibleOnce(selector: BySelector): Boolean {
        val target = device.findObject(selector) ?: return false
        val bounds = target.visibleBounds
        val viewport = device.findObject(By.clazz("android.widget.ScrollView"))?.visibleBounds ?: return bounds.height() >= 30
        return bounds.height() >= 30 && bounds.width() >= 30 && bounds.top >= viewport.top + 8 && bounds.bottom <= viewport.bottom - 50
    }

    private fun swipe(top: Boolean): Boolean {
        repeat(3) {
            try { return swipeOnce(top) }
            catch (_: androidx.test.uiautomator.StaleObjectException) { device.waitForIdle(250); refresh() }
        }
        return true // Retry the query in scrollTo; its final visibility assertion is still mandatory.
    }

    private fun swipeOnce(top: Boolean): Boolean {
        val scroller = device.findObject(By.clazz("android.widget.ScrollView")) ?: return false
        val bounds = scroller.visibleBounds
        val before = scroller.findObjects(By.clazz("android.widget.TextView")).map { runCatching { it.text to it.visibleBounds.top }.getOrNull() }
        val start = if (top) bounds.top + bounds.height() / 6 else bounds.bottom - bounds.height() / 6
        val end = start + if (top) bounds.height() / 4 else -bounds.height() / 4
        device.swipe(bounds.right - 12, start, bounds.right - 12, end, 60)
        device.waitForIdle(250); refresh()
        val after = device.findObject(By.clazz("android.widget.ScrollView"))?.findObjects(By.clazz("android.widget.TextView"))?.map { runCatching { it.text to it.visibleBounds.top }.getOrNull() }
        return before != after
    }

    private fun capture(style: DesignStyle, variant: String, page: String) {
        device.waitForIdle(750); refresh()
        val name = "v05-${style.name.lowercase()}-$variant-$page"
        assertTrue(device.takeScreenshot(File(output, "$name.png")))
        File(output, "$name.json").writeText("""{
            "style":"${style.name}","mode":"${captureMode(style).name}","variant":"$variant","page":"$page",
            "syntheticData":true,"providerConfigured":false,"screenWidthPixels":${device.displayWidth},
            "screenHeightPixels":${device.displayHeight},"androidApi":${android.os.Build.VERSION.SDK_INT},
            "systemFontScale":${instrumentation.targetContext.resources.configuration.fontScale},
            "capturedUtcMillis":${System.currentTimeMillis()},"visuallyInspected":false
        }""".trimIndent())
    }

    private fun refresh() { if (android.os.Build.VERSION.SDK_INT >= 37) instrumentation.uiAutomation.clearCache() }
    private fun await(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < end) android.os.SystemClock.sleep(50)
        assertTrue("Cosmetic state was not durably published", condition())
    }
}
