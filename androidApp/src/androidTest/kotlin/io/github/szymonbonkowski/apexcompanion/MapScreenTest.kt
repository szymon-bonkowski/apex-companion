package io.github.szymonbonkowski.apexcompanion

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.szymonbonkowski.apexcompanion.platform.AndroidRotationStore
import io.github.szymonbonkowski.apexcompanion.maps.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MapScreenTest {
    @Test fun unavailableAndDemoRemainDistinctAcrossModesAndRecreation() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertTrue(device.wait(Until.hasObject(By.text("Rotation unavailable")), 10_000))
            assertTrue(device.hasObject(By.text("UNAVAILABLE")))
            device.findObject(By.text("Preview demo")).click()
            assertTrue(device.wait(Until.hasObject(By.text("DEMO · Synthetic schedule")), 5_000))
            assertTrue(device.hasObject(By.text("Example Highlands")))
            device.findObject(By.text("Ranked")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Example Harbor")), 5_000))
            scenario.recreate()
            assertTrue(device.wait(Until.hasObject(By.text("DEMO · Synthetic schedule")), 5_000))
            assertTrue(device.hasObject(By.text("Example Harbor")))
            device.findObject(By.text("Live maps")).click()
            assertTrue(device.wait(Until.hasObject(By.text("Rotation unavailable")), 5_000))
            assertFalse(device.hasObject(By.text("DEMO · Synthetic schedule")))
        }
    }

    @Test fun durableCacheRecoversAfterNetworkFailureAndNeverExtendsExpiredMap() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "instrumentation-rotation-store.json")
        val store = AndroidRotationStore(file)
        var now = 1793620800L
        val segment = MapSegment("unknown-map", "Unknown Map From Source", now - 60, now + 120)
        val cache = RotationCache(now, listOf(PlaylistSchedule("ranked", "Ranked", listOf(segment))))
        store.write(RotationCacheCodec.encode(cache))
        val repo = RotationRepository(RotationProvider { ProviderResult.Failure(RotationError.NETWORK) },
            AndroidRotationStore(file), RotationClock { now })
        repo.initialize()
        assertEquals(DataState.LIVE, repo.view("ranked").state)
        repo.refresh(force = true)
        assertEquals(DataState.STALE, repo.view("ranked").state)
        val restarted = RotationRepository(RotationProvider { error("No request should occur on read") },
            AndroidRotationStore(file), RotationClock { now })
        restarted.initialize()
        assertEquals("Unknown Map From Source", restarted.view("ranked").current?.mapName)
        assertEquals(DataState.STALE, restarted.view("ranked").state)
        now += 120
        assertEquals(DataState.UNAVAILABLE, restarted.view("ranked").state)
        assertNull(restarted.view("ranked").current)
        file.delete()
        Unit
    }
}
