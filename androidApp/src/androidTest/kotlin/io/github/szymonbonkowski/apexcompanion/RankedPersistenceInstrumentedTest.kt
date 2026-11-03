package io.github.szymonbonkowski.apexcompanion

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.platform.AndroidRotationStore
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RankedPersistenceInstrumentedTest {
    @Test fun androidAtomicRecordsRestoreGoalsProfilesAndGlobalCacheWithoutTouchingMaps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "ranked-persistence-test").apply { mkdirs() }
        val privateFile = File(directory, "private.json")
        val globalFile = File(directory, "global.json")
        val mapsFile = File(directory, "maps.json")
        val now = 1793650000L
        val provider = object: RankedProvider {
            override suspend fun profile(lookup: ProfileLookup): RankedResult<RankedSnapshot> = RankedResult.Success(
                RankedSnapshot(PlayerIdentity("123456", ApexPlatform.PC, "Synthetic persistence player"), 13460, "Fixture rank", 2, now))
            override suspend fun predator() = RankedResult.Success(listOf(PredatorThreshold(ApexPlatform.PC, 20000, now, 1000)))
        }
        fun repository() = RankedRepository(provider, AndroidRotationStore(privateFile), RotationClock { now }, AndroidRotationStore(globalFile))
        try {
            AndroidRotationStore(mapsFile).write("Preserved v0.1 global cache bytes")
            val first = repository()
            assertTrue(first.setManualRp(13000))
            assertTrue(first.saveGoal(RankGoal(16000, 13000, LocalDate(2026, 11, 24))))
            first.connect(ProfileLookup("Fixture account", ApexPlatform.PC, false))
            first.refreshPredator()
            // Simulate a process dying while writing a replacement. AtomicFile must keep the committed record.
            AtomicFile(privateFile).startWrite().use { it.write("{partial".toByteArray()) }
            val reopened = repository(); reopened.initialize()
            assertEquals("123456", reopened.state.value.profile?.identity?.uid)
            assertEquals(16000L, reopened.state.value.goal?.targetRp)
            assertEquals(13460L, reopened.view(now, LocalDate(2026, 11, 2)).currentRp)
            assertEquals(20000L, reopened.state.value.predator.single().rp)
            assertTrue(reopened.disconnect())
            val unlinked = repository(); unlinked.initialize()
            assertNull(unlinked.state.value.profile)
            assertEquals(13000L, unlinked.view(now, LocalDate(2026, 11, 2)).currentRp)
            assertEquals(16000L, unlinked.state.value.goal?.targetRp)
            assertFalse(privateFile.readText().contains("123456"))
            assertFalse(privateFile.readText().contains("Synthetic persistence player"))
            assertEquals("Preserved v0.1 global cache bytes", mapsFile.readText())
            privateFile.writeText("{corrupt")
            val corrupt = repository(); corrupt.initialize()
            assertEquals(RankedError.STORAGE, corrupt.state.value.error)
            assertEquals(20000L, corrupt.state.value.predator.single().rp)
            assertEquals("Preserved v0.1 global cache bytes", mapsFile.readText())
        } finally { directory.deleteRecursively() }
    }
}
