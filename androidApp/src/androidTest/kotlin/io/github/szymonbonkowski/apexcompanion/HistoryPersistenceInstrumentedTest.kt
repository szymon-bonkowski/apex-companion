package io.github.szymonbonkowski.apexcompanion

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.szymonbonkowski.apexcompanion.history.*
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.platform.AndroidRotationStore
import io.github.szymonbonkowski.apexcompanion.ranked.RankGoal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real Android private AtomicFile adapter; all identities/times/RP are synthetic. */
@RunWith(AndroidJUnit4::class)
class HistoryPersistenceInstrumentedTest {
    private fun directory(name: String) = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
        "history-native-$name").apply { deleteRecursively(); mkdirs() }

    @Test fun activeSessionAndObservationAnchorsSurviveReopenWithoutExtraSamples() = runBlocking {
        val directory = directory("restart")
        val file = File(directory, "history.json")
        var now = 1_793_650_000L
        fun repository() = HistoryRepository(AndroidRotationStore(file, HISTORY_MAX_BYTES), RotationClock { now })
        try {
            val maps = File(directory, "maps.json").apply { writeText("untouched maps") }
            val predator = File(directory, "predator.json").apply { writeText("untouched predator") }
            val first = repository()
            first.initialize()
            assertTrue(first.adoptLegacy(PortableRankedSettings(RankGoal(16000, 13000), 13000, true, now)))
            assertTrue(first.setRecording(true))
            assertTrue(first.confirmContext(null, ObservationSource.USER_RECORDED, "Synthetic season", "Synthetic split"))
            assertTrue(first.captureManual(13000, now))
            assertTrue(first.startSession(null, ObservationSource.USER_RECORDED))
            val active = first.state.value.record.sessions.single()
            now += 90
            val restarted = repository()
            restarted.initialize()
            assertEquals(active, restarted.state.value.record.sessions.single())
            assertEquals(1, restarted.state.value.record.observations.size)
            assertFalse(restarted.startSession(null, ObservationSource.USER_RECORDED))
            assertTrue(restarted.captureManual(13150, now))
            assertTrue(restarted.stopSession())
            val stopped = repository()
            stopped.initialize()
            val session = stopped.state.value.record.sessions.single()
            assertEquals(active.startedAtSeconds, session.startedAtSeconds)
            assertEquals(now, session.endedAtSeconds)
            val result = sessionResult(session, stopped.state.value.record.observations)
            assertEquals(SessionClassification.MANUAL, result.classification)
            assertEquals(150L, result.deltaRp)
            assertEquals(now - 90, result.windowStartSeconds)
            assertEquals(now, result.windowEndSeconds)
            assertEquals("untouched maps", maps.readText())
            assertEquals("untouched predator", predator.readText())
        } finally { directory.deleteRecursively() }
    }

    @Test fun interruptedImportKeepsCommittedBytesThenRestoresIdempotently() = runBlocking {
        val directory = directory("import")
        val file = File(directory, "history.json")
        var now = 1_793_650_000L
        val clock = RotationClock { now }
        val native = AndroidRotationStore(file, HISTORY_MAX_BYTES)
        var failImport = false
        val failingStore = object : RotationStore {
            override suspend fun read() = native.read()
            override suspend fun write(value: String) {
                if (failImport) {
                    // A process dies before finishWrite; reopen must discard this .new file.
                    AtomicFile(file).startWrite().use { it.write("{interrupted-import".toByteArray()) }
                    throw IllegalStateException("Synthetic interrupted write")
                }
                native.write(value)
            }
        }
        try {
            val history = HistoryRepository(failingStore, clock)
            history.initialize()
            assertTrue(history.adoptLegacy(PortableRankedSettings(RankGoal(16000, 13000), 13000, true, now)))
            assertTrue(history.setRecording(true))
            assertTrue(history.captureManual(13000, now))
            assertTrue(history.startSession(null, ObservationSource.USER_RECORDED))
            now += 60
            assertTrue(history.captureManual(13050, now))
            assertTrue(history.stopSession())
            val backup = history.exportBackup()
            val original = history.state.value.record
            assertTrue(history.deleteHistory())
            val beforeBytes = native.read()!!
            val preview = history.previewImport(backup, null)
            failImport = true
            assertFalse(history.applyImport(preview, null))
            assertTrue(history.state.value.record.observations.isEmpty())
            assertEquals(beforeBytes, native.read())
            val reopened = HistoryRepository(AndroidRotationStore(file, HISTORY_MAX_BYTES), clock)
            reopened.initialize()
            assertTrue(reopened.state.value.record.observations.isEmpty())
            assertEquals(16000L, reopened.state.value.record.settings?.goal?.targetRp)
            assertTrue(reopened.applyImport(reopened.previewImport(backup, null), null))
            val afterFirst = reopened.state.value.record
            assertTrue(reopened.applyImport(reopened.previewImport(backup, null), null))
            assertEquals(afterFirst, reopened.state.value.record)
            assertEquals(original.observations, afterFirst.observations)
            assertEquals(original.sessions, afterFirst.sessions)
            assertFalse(afterFirst.recording)
            assertEquals(1, afterFirst.importedBatchIds.size)
        } finally { directory.deleteRecursively() }
    }

    @Test fun oversizedInvalidUtf8AndFutureRecordsFailClosedWithoutOverwrite() = runBlocking {
        val directory = directory("invalid")
        val file = File(directory, "history.json")
        val now = 1_793_650_000L
        try {
            // History exceeds legacy 512 KiB safely when explicitly configured, default maps bound stays unchanged.
            val large = " ".repeat(600_000)
            AndroidRotationStore(file, HISTORY_MAX_BYTES).write(large)
            assertEquals(large, AndroidRotationStore(file, HISTORY_MAX_BYTES).read())
            try { AndroidRotationStore(file).read(); fail("Legacy store accepted oversized data") } catch (_: IllegalArgumentException) {}
            val future = HistoryCodec.encode(HistoryRecord()).replace("\"schemaVersion\":1", "\"schemaVersion\":99")
            for (bytes in listOf(future.toByteArray(), byteArrayOf(0xC3.toByte(), 0x28))) {
                file.writeBytes(bytes)
                val history = HistoryRepository(AndroidRotationStore(file, HISTORY_MAX_BYTES), RotationClock { now })
                history.initialize()
                assertTrue(history.state.value.readOnly)
                assertFalse(history.setRecording(true))
                assertArrayEquals(bytes, file.readBytes())
                assertTrue(history.deleteHistory())
                assertFalse(history.state.value.readOnly)
                assertTrue(history.state.value.record.observations.isEmpty())
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun offlineStopWithMissingEndRemainsPartialInsteadOfZero() = runBlocking {
        val directory = directory("partial")
        val file = File(directory, "history.json")
        var now = 1_793_650_000L
        try {
            val history = HistoryRepository(AndroidRotationStore(file, HISTORY_MAX_BYTES), RotationClock { now })
            history.initialize()
            assertTrue(history.adoptLegacy(PortableRankedSettings()))
            assertTrue(history.setRecording(true))
            assertTrue(history.startSession(null, ObservationSource.USER_RECORDED))
            now += 7200
            val reopened = HistoryRepository(AndroidRotationStore(file, HISTORY_MAX_BYTES), RotationClock { now })
            reopened.initialize()
            assertTrue(reopened.stopSession())
            val result = sessionResult(reopened.state.value.record.sessions.single(), reopened.state.value.record.observations)
            assertEquals(SessionClassification.PARTIAL, result.classification)
            assertNull(result.deltaRp)
            assertNull(result.windowStartSeconds)
            assertNull(result.windowEndSeconds)
        } finally { directory.deleteRecursively() }
    }
}
