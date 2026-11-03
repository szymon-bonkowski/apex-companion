package io.github.szymonbonkowski.apexcompanion

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import java.io.File
import androidx.test.uiautomator.Until
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic state only: no provider key or real player information is needed by these UI checks. */
@RunWith(AndroidJUnit4::class)
class RankedScreenTest {
    private class Store : RotationStore {
        var value: String? = null
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value }
    }
    private fun repository(store: Store) = RankedRepository(UnconfiguredRankedProvider, store,
        RotationClock { System.currentTimeMillis() / 1000 })

    @Test fun manualRankedGoalSurvivesRepositoryRestartAndKeepsMapsSeparate() = runBlocking {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val store = Store()
        val first = repository(store)
        first.initialize()
        first.setManualRp(13460)
        first.setUseManual(true)
        first.saveGoal(RankGoal(16000, 13000))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { App(rankedRepository = first) } }
            assertTrue(device.wait(Until.hasObject(By.text("Map rotation")), 10_000))
            clickNavigation(device, "Ranked progress")
            assertTrue(device.wait(Until.hasObject(By.text("13460 RP")), 5_000))
            assertTrue(device.hasObject(By.text("MANUAL · User entered")))
            scrollTo(device, "2540 RP remaining")
            assertTrue(device.hasObject(By.text("2540 RP remaining")))
            device.takeScreenshot(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ranked-manual-goal.png"))
            val restarted = repository(store)
            restarted.initialize()
            scenario.onActivity { it.setContent { App(rankedRepository = restarted) } }
            // Wait for replacement semantics before querying navigation nodes from the old composition.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            // A repository replacement keeps the screen's scroll position. Navigate away
            // and back before checking the top card rather than assuming it resets.
            clickNavigation(device, "Maps")
            clickNavigation(device, "Ranked progress")
            assertTrue(device.wait(Until.hasObject(By.text("13460 RP")), 5_000))
            clickNavigation(device, "Maps")
            assertTrue(device.wait(Until.hasObject(By.text("Rotation unavailable")), 5_000))
            assertFalse(device.hasObject(By.text("13460 RP")))
        }
    }

    @Test fun profileNameHintAndInvalidLookupAreVisible() = runBlocking {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val repo = repository(Store())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { App(rankedRepository = repo) } }
            assertTrue(device.wait(Until.hasObject(By.text("Profile")), 10_000))
            device.findObject(By.text("Profile")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Player profile")), 5_000))
            assertTrue(device.hasObject(By.text("For PC, use the linked EA account name. Your Steam display name may not work.")))
            scrollTo(device, "Connect profile")
            device.findObject(By.text("Connect profile")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Enter an account name (up to 100 characters).")), 5_000))
            assertNull(repo.state.value.profile)
        }
    }

    @Test fun profileScreenResolvesNameLinksUidAndDisconnectKeepsManualGoal() = runBlocking {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val store = Store()
        var attempts = 0
        val repo = RankedRepository(object: RankedProvider {
            override suspend fun profile(lookup: ProfileLookup): RankedResult<RankedSnapshot> {
                attempts++
                assertFalse(lookup.byUid)
                assertEquals("Synthetic account", lookup.value)
                return RankedResult.Success(RankedSnapshot(PlayerIdentity("123456", ApexPlatform.PC, "Synthetic player"),
                    13460, "Fixture rank", 2, System.currentTimeMillis()/1000))
            }
            override suspend fun predator() = RankedResult.Failure(RankedError.NETWORK)
        }, store, RotationClock { System.currentTimeMillis()/1000 })
        repo.setManualRp(9000); repo.saveGoal(RankGoal(16000, 9000))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { App(rankedRepository = repo) } }
            assertTrue(device.wait(Until.hasObject(By.text("Profile")), 10_000))
            device.findObject(By.text("Profile")).click()
            assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000))
            val field = device.findObject(By.clazz("android.widget.EditText"))
            field.click(); field.text = "Synthetic account"
            device.pressBack()
            scrollTo(device, "Connect profile")
            device.findObject(By.text("Connect profile")).click()
            repeat(4) { device.swipe(device.displayWidth/2, device.displayHeight/3, device.displayWidth/2, device.displayHeight*4/5, 20) }
            assertTrue(device.wait(Until.hasObject(By.text("UID: 123456")), 5_000))
            assertTrue(device.hasObject(By.text("Synthetic player")))
            device.takeScreenshot(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ranked-profile-synthetic.png"))
            assertEquals(1, attempts)
            scrollTo(device, "Disconnect profile")
            device.waitForIdle(750)
            if (android.os.Build.VERSION.SDK_INT >= 37) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            val disconnect = device.findObject(By.clickable(true).hasDescendant(By.text("Disconnect profile")))
            assertNotNull("Visible disconnect button", disconnect); disconnect.click()
            val end = System.currentTimeMillis() + 5000
            while (repo.state.value.profile != null && System.currentTimeMillis() < end) android.os.SystemClock.sleep(50)
            if (repo.state.value.profile != null) {
                val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
                device.dumpWindowHierarchy(File(folder, "v04-profile-disconnect-failure.xml"))
            }
            assertNull("Actual disconnect action must persist", repo.state.value.profile)
            if (android.os.Build.VERSION.SDK_INT >= 37) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            assertTrue(device.wait(Until.gone(By.text("UID: 123456")), 5_000))
            assertEquals(9000L, repo.state.value.manualRp)
            assertEquals(16000L, repo.state.value.goal?.targetRp)
            assertFalse(store.value!!.contains("Synthetic player"))
            assertFalse(store.value!!.contains("123456"))
        }
    }

    private fun clickNavigation(device: UiDevice, text: String) {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        do {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 37)
                    InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
                val chip = device.findObject(By.clickable(true).hasDescendant(By.text(text)))
                if (chip != null) { chip.click(); return }
            } catch (_: androidx.test.uiautomator.StaleObjectException) {
                // Re-query the clickable chip while replacement semantics settle.
            }
            android.os.SystemClock.sleep(50)
        } while (android.os.SystemClock.uptimeMillis() < deadline)
        fail("Navigation control unavailable after composition replacement: $text")
    }

    private fun scrollTo(device: UiDevice, text: String) {
        repeat(40) {
            if (android.os.Build.VERSION.SDK_INT >= 37) InstrumentationRegistry.getInstrumentation().uiAutomation.clearCache()
            val target = device.findObject(By.text(text))
            val viewport = device.findObject(By.clazz("android.widget.ScrollView"))?.visibleBounds
            if (target != null && (viewport == null || (target.visibleBounds.height() >= 30 && target.visibleBounds.top >= viewport.top + 8 && target.visibleBounds.bottom <= viewport.bottom - 50))) return
            // Nested platform choices and navigation rows are horizontal; scroll the
            // actual vertical content rather than the last arbitrary scrollable node.
            if (viewport != null) device.swipe(viewport.right - 12, viewport.bottom - viewport.height()/6,
                viewport.right - 12, viewport.bottom - viewport.height()/6 - viewport.height()/4, 80)
            device.waitForIdle(300)
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(context.externalMediaDirs.first(), "additional_test_output").apply { mkdirs() }
        device.takeScreenshot(File(folder, "v04-ranked-ui-failure.png"))
        device.dumpWindowHierarchy(File(folder, "v04-ranked-ui-failure.xml"))
        fail("Missing visible UI: $text")
    }
}
