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
            device.findObject(By.text("Ranked progress")).click()
            assertTrue(device.wait(Until.hasObject(By.text("13460 RP")), 5_000))
            assertTrue(device.hasObject(By.text("MANUAL · User entered")))
            scrollTo(device, "2540 RP remaining")
            assertTrue(device.hasObject(By.text("2540 RP remaining")))
            device.takeScreenshot(File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "ranked-manual-goal.png"))
            val restarted = repository(store)
            restarted.initialize()
            scenario.onActivity { it.setContent { App(rankedRepository = restarted) } }
            // A repository replacement keeps the screen's scroll position. Navigate away
            // and back before checking the top card rather than assuming it resets.
            device.findObject(By.text("Maps")).click()
            device.findObject(By.text("Ranked progress")).click()
            assertTrue(device.wait(Until.hasObject(By.text("13460 RP")), 5_000))
            device.findObject(By.text("Maps")).click()
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
            device.findObject(By.text("Disconnect profile")).click()
            assertTrue(device.wait(Until.gone(By.text("UID: 123456")), 5_000))
            assertNull(repo.state.value.profile)
            assertEquals(9000L, repo.state.value.manualRp)
            assertEquals(16000L, repo.state.value.goal?.targetRp)
            assertFalse(store.value!!.contains("Synthetic player"))
            assertFalse(store.value!!.contains("123456"))
        }
    }

    private fun scrollTo(device: UiDevice, text: String) {
        repeat(30) {
            if (device.hasObject(By.text(text))) return
            val scroller = device.findObjects(By.scrollable(true)).lastOrNull()
            if (scroller != null) scroller.scroll(Direction.DOWN, 0.2f, 700)
            else device.swipe(device.displayWidth / 2, device.displayHeight * 4 / 5,
                device.displayWidth / 2, device.displayHeight * 3 / 5, 80)
            device.waitForIdle()
            Thread.sleep(150)
        }
        if (!device.hasObject(By.text(text))) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            device.takeScreenshot(File(context.filesDir, "ranked-ui-failure.png"))
            device.dumpWindowHierarchy(File(context.filesDir, "ranked-ui-failure.xml"))
        }
        assertTrue("Missing expected UI: $text", device.hasObject(By.text(text)))
    }
}
