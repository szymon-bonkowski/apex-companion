package io.github.szymonbonkowski.apexcompanion

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.szymonbonkowski.apexcompanion.platform.AndroidRankedRuntime
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicit private developer check only. No real name/UID, response or auth is a fixture/report. */
@RunWith(AndroidJUnit4::class)
class RankedDeveloperLiveTest {
    @Test fun authorizedAndroidProfileUiCacheColdStoreAndDisconnect() = runBlocking {
        assumeTrue("Authorized developer test is opt-in",
            InstrumentationRegistry.getArguments().getString("APEX_RANKED_LIVE") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val inputFile = File(context.filesDir, "developer-profile-test.private.json")
        require(inputFile.isFile && inputFile.length() in 1..4096)
        val input = Json.parseToJsonElement(inputFile.readText()).jsonObject
        val account = input.getValue("name").jsonPrimitive.content
        require(input.getValue("platform").jsonPrimitive.content == "PC")
        val device = UiDevice.getInstance(instrumentation)
        val runtime = AndroidRankedRuntime.get(context)
        runtime.initialize()
        val goal = RankGoal(1_000_000, 0, LocalDate(2027, 1, 1))
        runtime.repository.setManualRp(1000)
        runtime.repository.saveGoal(goal)
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                assertTrue(device.wait(Until.hasObject(By.text("Profile")), 15_000))
                device.findObject(By.text("Profile")).click()
                assertTrue(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), 5_000))
                val field = device.findObject(By.clazz("android.widget.EditText"))
                field.click(); field.text = account; device.pressBack()
                repeat(6) {
                    if (!device.hasObject(By.text("Connect profile")))
                        device.swipe(device.displayWidth/2,device.displayHeight*4/5,device.displayWidth/2,device.displayHeight*3/5,20)
                }
                device.findObject(By.text("Connect profile")).click()
                val expires = System.currentTimeMillis() + 35_000
                while (runtime.repository.state.value.profile == null && System.currentTimeMillis() < expires) Thread.sleep(200)
                val profile = runtime.repository.state.value.profile
                assertNotNull("Authorized Android profile was not retrieved", profile)
                assertNotNull("Authorized Android RP was not supplied", profile!!.rp)
                assertNotNull("Authorized Android rank was not supplied", profile.rankName)
                assertEquals(ApexPlatform.PC, profile.identity.platform)
                repeat(5) { device.swipe(device.displayWidth/2, device.displayHeight/3, device.displayWidth/2, device.displayHeight*4/5,20) }
                assertTrue("Linked UID was not visible", device.wait(Until.hasObject(By.text("UID: ${profile.identity.uid}")),5_000))
                var rankedNavigation = device.findObject(By.desc("Navigate to Ranked progress"))
                while (!rankedNavigation.isClickable && rankedNavigation.parent != null) rankedNavigation = rankedNavigation.parent
                rankedNavigation.click()
                assertTrue("Ranked RP was not displayed", device.wait(Until.hasObject(By.text("${profile.rp} RP")),5_000))
                assertTrue(device.hasObject(By.text("LIVE · Apex Legends Status")))
                val privateRecord = File(context.filesDir,"ranked-profile-v1.json").readText()
                val restored = RankedRecordCodec.decode(privateRecord)
                assertEquals(true, restored.profile?.identity == profile.identity)
                assertEquals(goal, restored.goal)
                val projection = runtime.widgetSnapshot()
                assertFalse("UID leaked into widget projection", projection.contains(profile.identity.uid))
                assertFalse("Name leaked into widget projection", projection.contains(profile.identity.name))
                // A real offline provider attempt must keep the last normalized snapshot and goal.
                instrumentation.uiAutomation.executeShellCommand("svc wifi disable").close()
                instrumentation.uiAutomation.executeShellCommand("svc data disable").close()
                Thread.sleep(1000)
                Thread.sleep(31_000) // honor the normal repository attempt cooldown
                runtime.repository.refresh(force = true)
                assertTrue("Actual offline attempt should report network/timeout",
                    runtime.repository.state.value.error in setOf(RankedError.NETWORK, RankedError.TIMEOUT))
                assertTrue("Cached identity changed after offline failure", profile.identity == runtime.repository.state.value.profile?.identity)
                assertEquals(goal, runtime.repository.state.value.goal)
                assertEquals(io.github.szymonbonkowski.apexcompanion.maps.DataState.STALE,
                    runtime.repository.view(System.currentTimeMillis()/1000,LocalDate(2026,11,3)).state)

            }
        } finally {
            instrumentation.uiAutomation.executeShellCommand("svc wifi enable").close()
            instrumentation.uiAutomation.executeShellCommand("svc data enable").close()
            runtime.repository.disconnect()
            runtime.repository.saveGoal(null)
            runtime.repository.setManualRp(null)
            runtime.publish()
            inputFile.delete()
        }
        val record = File(context.filesDir,"ranked-profile-v1.json").readText()
        assertFalse(record.contains(account))
        assertNull(RankedRecordCodec.decode(record).profile)
        println("Authorized Android UI linked real provider identity/rank/RP; persisted normalized snapshot and goal; sanitized widget and disconnect checks passed. No private values reported.")
    }
}
