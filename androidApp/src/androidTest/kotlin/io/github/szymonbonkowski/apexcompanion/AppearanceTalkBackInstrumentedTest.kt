package io.github.szymonbonkowski.apexcompanion

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.szymonbonkowski.apexcompanion.appearance.*
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Bound-service and focus/semantics smoke only. Spoken wording and reader usability are unaudited. */
@RunWith(AndroidJUnit4::class)
class AppearanceTalkBackInstrumentedTest {
    private class Store : RotationStore {
        private var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    }

    @Test fun boundTalkBackCanFocusNavigationAndProvenanceAcrossFourDesigns() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun shell(command: String) = automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes().toString(Charsets.UTF_8).trim() }
        }
        val originalServices = shell("settings get secure enabled_accessibility_services")
        val originalEnabled = shell("settings get secure accessibility_enabled")
        fun notificationPermission() = shell("dumpsys package com.google.android.marvin.talkback")
            .lineSequence().first { it.contains("android.permission.POST_NOTIFICATIONS: granted=") }.trim()
        val originalNotificationPermission = notificationPermission()
        val originalNotificationGranted = originalNotificationPermission.contains("granted=true")
        require(originalServices == "null" || originalServices.matches(Regex("[A-Za-z0-9_./:$-]+")))
        require(originalEnabled in listOf("0", "1", "null"))
        val service = "com.google.android.marvin.talkback/.TalkBackService"
        val services = if (originalServices == "null" || originalServices.isBlank()) service
            else (originalServices.split(':') + service).distinct().joinToString(":")
        val focusEvents = CopyOnWriteArrayList<String>()
        val evidence = JSONArray()
        var serviceBound = false
        var completed = false
        var restored = false
        var notificationRestored = false
        val appearance = AppearanceRepository(Store()); appearance.initialize()
        val ranked = RankedRepository(UnconfiguredRankedProvider, Store(), RotationClock { System.currentTimeMillis() / 1000 })
        ranked.initialize(); ranked.setManualRp(13460); ranked.setUseManual(true)
        automation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                focusEvents += event.source?.contentDescription?.toString() ?: event.text.joinToString(" ")
        }
        try {
            // Prevent a delayed reader-owned permission dialog from stealing focus during or after the smoke.
            // Shell grant/revoke preserves the existing user-decision flags, checked below.
            if (!originalNotificationGranted) shell("pm grant com.google.android.marvin.talkback android.permission.POST_NOTIFICATIONS")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent {
                    App(appearanceRepository = appearance, appearanceEnvironment = AppearanceEnvironment(systemDark = true), rankedRepository = ranked)
                } }
                shell("settings put secure enabled_accessibility_services $services")
                shell("settings put secure accessibility_enabled 1")
                await("TalkBack service did not bind") {
                    val dump = shell("dumpsys accessibility")
                    val bound = dump.substringAfter("Bound services:", "").substringBefore("Enabled services:")
                    serviceBound = bound.contains("label=TalkBack") || bound.contains("TalkBackService")
                    serviceBound
                }
                // A first-use reader tutorial may be above the test-owned app. Dismiss it without changing reader preferences.
                repeat(3) {
                    if (find(automation.rootInActiveWindow) { it.contentDescription?.toString() == "Navigate to Today" } == null) {
                        automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        SystemClock.sleep(500)
                    }
                }
                for (style in DesignStyle.entries) {
                    assertTrue(appearance.savePreferences(AppearancePreferences(style = style,
                        mode = if (style == DesignStyle.EDITORIAL) AppearanceMode.LIGHT else AppearanceMode.DARK)))
                    await("Design did not resolve in accessibility tree") {
                        if (android.os.Build.VERSION.SDK_INT >= 37) automation.clearCache()
                        find(automation.rootInActiveWindow) { it.text?.toString() == style.title } != null
                    }
                    for (destination in listOf("Maps", "Ranked progress")) {
                        val description = "Navigate to $destination"
                        val node = find(automation.rootInActiveWindow) { it.contentDescription?.toString() == description }
                        assertNotNull("Named navigation is accessible", node)
                        assertTrue("Navigation node is enabled", node!!.isEnabled)
                        node.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                        assertTrue("Navigation accepts accessibility focus", node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                        await("Accessibility focus did not move to labelled navigation") {
                            if (android.os.Build.VERSION.SDK_INT >= 37) automation.clearCache()
                            var focus = automation.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                            var matches = false
                            while (focus != null) {
                                if (focus.contentDescription?.toString() == description ||
                                    focus.text?.toString() == (if (destination == "Ranked progress") "Ranked" else destination)) matches = true
                                focus = focus.parent
                            }
                            matches
                        }
                    }
                    // Find the actual manual source text, scrolling the real shared content if needed.
                    // Each design has its own default order, so a previous design's scroll can put the source above us.
                    for (attempt in 0 until 15) {
                        if (android.os.Build.VERSION.SDK_INT >= 37) automation.clearCache()
                        val scroller = find(automation.rootInActiveWindow) { it.isScrollable && it.className?.toString() == "android.widget.ScrollView" }
                        if (scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != true) break
                        SystemClock.sleep(150)
                    }
                    var source: AccessibilityNodeInfo? = null
                    for (attempt in 0 until 15) {
                        if (android.os.Build.VERSION.SDK_INT >= 37) automation.clearCache()
                        source = find(automation.rootInActiveWindow) { it.text?.toString()?.contains("MANUAL") == true }
                        if (source != null) break
                        val scroller = find(automation.rootInActiveWindow) { it.isScrollable && it.className?.toString() == "android.widget.ScrollView" }
                        scroller?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                        SystemClock.sleep(200)
                    }
                    assertNotNull("Manual provenance remains in the accessible screen", source)
                    source!!.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    SystemClock.sleep(200)
                    source = find(automation.rootInActiveWindow) { it.text?.toString()?.contains("MANUAL") == true }
                    assertNotNull(source)
                    source!!.performAction(AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                    assertTrue("Source accepts accessibility focus", source.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS))
                    evidence.put(JSONObject().put("style", style.name).put("namedNavigationFocused", 2)
                        .put("manualProvenanceAccessible", true).put("sourceFocusActionAccepted", true))
                }
                assertTrue("Focus events were delivered while TalkBack was bound", focusEvents.size >= 8)
                completed = true
            }
        } finally {
            automation.setOnAccessibilityEventListener(null)
            if (originalServices == "null") shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $originalServices")
            if (originalEnabled == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $originalEnabled")
            restored = shell("settings get secure enabled_accessibility_services") == originalServices &&
                shell("settings get secure accessibility_enabled") == originalEnabled
            if (originalServices == "null" && originalEnabled == "0") shell("am force-stop com.google.android.marvin.talkback")
            if (!originalNotificationGranted) shell("pm revoke com.google.android.marvin.talkback android.permission.POST_NOTIFICATIONS")
            notificationRestored = notificationPermission() == originalNotificationPermission
            val output = File(instrumentation.targetContext.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
            File(output, "v05-talkback-evidence.json").writeText(JSONObject()
                .put("schemaVersion", 1).put("syntheticData", true).put("passed", completed)
                .put("serviceBound", serviceBound).put("settingsRestored", restored)
                .put("readerNotificationPermissionRestored", notificationRestored)
                .put("focusEventCount", focusEvents.size).put("focusEvents", JSONArray(focusEvents.toList())).put("styles", evidence)
                .put("speechAudited", false).put("fullUsabilityAudit", false)
                .put("scope", "Installed TalkBack bound alongside non-suppressing UiAutomation; real accessibility focus actions and source semantics. No speech-quality claim.")
                .toString(2))
            instrumentation.getUiAutomation() // Restore ordinary instrumentation automation flags for subsequent tests.
            assertTrue("Original accessibility settings must be restored", restored)
            assertTrue("Reader notification permission and decision flags must be restored", notificationRestored)
        }
    }

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        node ?: return null
        if (predicate(node)) return node
        for (index in 0 until node.childCount) find(node.getChild(index), predicate)?.let { return it }
        return null
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }
}
