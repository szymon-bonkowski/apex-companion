package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import kotlin.test.*

class HistoryImportTest {
    @Test fun t014ExportDeleteImportTwiceRestoresExactlyOnceAndPreservesIndependentSettings() = runTest {
        val clock = HistoryClock(); val store = HistoryMemoryStore(); val history = HistoryRepository(store, clock)
        val settings = PortableRankedSettings(RankGoal(16000, 13000), 12000, true, TEST_NOW)
        history.adoptLegacy(settings); history.setRecording(true); history.captureProvider(testSnapshot(split = "split-test"))
        clock.now += 10; history.startSession(testIdentity, ObservationSource.PROVIDER)
        clock.now += 50; history.captureProvider(testSnapshot(clock.now, 13150, split = "split-test")); history.stopSession()
        val original = history.state.value.record
        val exported = history.exportBackup()
        assertTrue(history.deleteHistory()); assertEquals(settings, history.state.value.record.settings)
        assertTrue(history.state.value.record.observations.isEmpty())
        val preview = history.previewImport(exported, testIdentity)
        assertEquals(2, preview.observationCount); assertEquals(1, preview.sessionCount)
        assertTrue(preview.warnings.isNotEmpty()); assertFalse(preview.batchPreviouslyImported)
        assertTrue(history.applyImport(preview, testIdentity))
        val once = history.state.value.record
        assertEquals(original.observations, once.observations); assertEquals(original.sessions, once.sessions)
        assertEquals(settings, once.settings); assertFalse(once.recording)
        assertTrue(history.previewImport(exported, testIdentity).batchPreviouslyImported)
        assertTrue(history.applyImport(history.previewImport(exported, testIdentity), testIdentity))
        assertEquals(once, history.state.value.record)
        val reopened = HistoryRepository(store, clock); reopened.initialize()
        assertEquals(once, reopened.state.value.record)
    }
    @Test fun t015RejectedBackupsNeverMutateExistingRecords() = runTest {
        val store = HistoryMemoryStore(); val history = HistoryRepository(store, HistoryClock())
        history.adoptLegacy(PortableRankedSettings(manualRp = 10000)); history.setRecording(true)
        history.captureProvider(testSnapshot())
        val original = history.state.value.record; val bytes = store.value
        for (bad in listOf("{bad", testBackup().replace("\"schemaVersion\":1", "\"schemaVersion\":9"), " ".repeat(HISTORY_MAX_BYTES + 1))) {
            assertFails { history.previewImport(bad, testIdentity) }
            assertEquals(original, history.state.value.record); assertEquals(bytes, store.value)
        }
    }
    @Test fun failureAtImportCommitLeavesHistoryAndPortableSettingsUnchanged() = runTest {
        val store = HistoryMemoryStore(); val history = HistoryRepository(store, HistoryClock())
        history.adoptLegacy(PortableRankedSettings(manualRp = 9000))
        val before = history.state.value.record; val bytes = store.value
        val preview = history.previewImport(testBackup(), testIdentity)
        store.failWrites = true
        assertFalse(history.applyImport(preview, testIdentity))
        assertEquals(before, history.state.value.record); assertEquals(bytes, store.value)
        val reopened = HistoryRepository(store, HistoryClock()); reopened.initialize()
        assertEquals(before, reopened.state.value.record)
        store.failWrites = false
        assertTrue(history.applyImport(preview, testIdentity))
        assertEquals(12000L, history.state.value.record.settings?.manualRp)
    }
    @Test fun previewRejectsForeignLinkedIdentityButUnlinkedImportStaysArchived() = runTest {
        val history = HistoryRepository(HistoryMemoryStore(), HistoryClock()); history.initialize()
        val foreignCurrent = HistoryIdentity("700002", ApexPlatform.PC)
        assertFails { history.previewImport(testBackup(), foreignCurrent) }
        val preview = history.previewImport(testBackup(), null)
        assertTrue(preview.warnings.any { it.contains("archived") })
        assertTrue(history.applyImport(preview, null))
        assertEquals(testIdentity, history.state.value.record.observations.single().identity)
        assertNull(history.state.value.record.currentContextId)
    }
    @Test fun identityIsRevalidatedAtCommitIfProfileChangesAfterPreview() = runTest {
        val history = HistoryRepository(HistoryMemoryStore(), HistoryClock()); history.initialize()
        val preview = history.previewImport(testBackup(), testIdentity)
        assertFalse(history.applyImport(preview, HistoryIdentity("700002", ApexPlatform.PC)))
        assertTrue(history.state.value.record.observations.isEmpty())
    }
    @Test fun collidingObservationSessionAndContextIdsAreRejectedBeforeMutation() = runTest {
        val store = HistoryMemoryStore(HistoryCodec.encode(testRecord()))
        val history = HistoryRepository(store, HistoryClock()); history.initialize()
        val bytes = store.value
        assertFails { history.previewImport(testBackup(testRecord(listOf(testObservation(rp = 13150)))), testIdentity) }
        assertEquals(bytes, store.value)
    }
    @Test fun unrelatedExistingValidRecordsSurviveMergeAndImportOrderIsDeterministic() = runTest {
        val a = testObservation("a", 13000, TEST_NOW - 120)
        val b = testObservation("b", 13150, TEST_NOW - 60)
        val c = testObservation("c", 13050, TEST_NOW)
        val histories = List(2) { HistoryRepository(HistoryMemoryStore(), HistoryClock()) }
        for ((index, history) in histories.withIndex()) {
            history.initialize()
            val inputs = if (index == 0) listOf(testBackup(testRecord(listOf(a, c)), "batch-1"), testBackup(testRecord(listOf(b)), "batch-2"))
                else listOf(testBackup(testRecord(listOf(b)), "batch-2"), testBackup(testRecord(listOf(c, a)), "batch-1"))
            for (value in inputs) assertTrue(history.applyImport(history.previewImport(value, testIdentity), testIdentity))
        }
        assertEquals(histories[0].state.value.record.observations, histories[1].state.value.record.observations)
        assertEquals(listOf(150L, -100L), rpIntervals(histories[0].state.value.record.observations).map { it.deltaRp })
    }
    @Test fun foreignLinkedIdentityAndLinkedManualRecordsAreRejected() = runTest {
        val history = HistoryRepository(HistoryMemoryStore(), HistoryClock()); history.initialize()
        val other = testObservation("foreign", at = TEST_NOW, identity = HistoryIdentity("700002", ApexPlatform.PC))
        val archivedBackup = testBackup(testRecord(listOf(testObservation(), other)))
        assertFails { history.previewImport(archivedBackup, testIdentity) }
        assertTrue(history.applyImport(history.previewImport(archivedBackup, null), null))
        assertEquals(setOf(testIdentity, other.identity), history.state.value.record.observations.map { it.identity }.toSet())
        assertNull(history.state.value.record.currentContextId)
        assertFails {
            val manual = testObservation(source = ObservationSource.USER_RECORDED, verification = ContextVerification.UNKNOWN,
                quality = ObservationQuality.UNKNOWN_CONTEXT, season = null, split = null, context = null)
            history.previewImport(testBackup(testRecord(listOf(manual))), null)
        }
    }
    @Test fun activeSessionImportRetainsOriginalBoundaryAndRejectsOverlappingActiveSessions() = runTest {
        val history = HistoryRepository(HistoryMemoryStore(), HistoryClock()); history.initialize()
        val active = RankedSession("imported-session", testIdentity, ObservationSource.PROVIDER, null, TEST_NOW - 100)
        val input = testBackup(testRecord(sessions = listOf(active)))
        val preview = history.previewImport(input, testIdentity)
        assertTrue(preview.warnings.any { it.contains("active session") })
        assertTrue(history.applyImport(preview, testIdentity))
        assertEquals(active, history.state.value.record.sessions.single())
        assertFalse(history.startSession(null, ObservationSource.USER_RECORDED))
        val second = active.copy(id = "another-active")
        assertFails { history.previewImport(testBackup(testRecord(sessions = listOf(second)), "batch-2"), testIdentity) }
    }
    @Test fun replayOfActiveSessionBackupCannotReopenLocallyCompletedSession() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.adoptLegacy(PortableRankedSettings()); history.setRecording(true)
        history.startSession(null, ObservationSource.USER_RECORDED)
        val exported = history.exportBackup()
        clock.now += 60; history.stopSession()
        val completed = history.state.value.record.sessions.single()
        assertNotNull(completed.endedAtSeconds)
        val preview = history.previewImport(exported, null)
        assertTrue(history.applyImport(preview, null))
        assertEquals(completed, history.state.value.record.sessions.single())
        assertTrue(history.applyImport(history.previewImport(exported, null), null))
        assertEquals(completed, history.state.value.record.sessions.single())
    }
    @Test fun importingDifferentSelectedSourceInterruptsExistingSessionAtomically() = runTest {
        val clock = HistoryClock(); val store = HistoryMemoryStore(); val history = HistoryRepository(store, clock)
        history.adoptLegacy(PortableRankedSettings(useManual = false)); history.setRecording(true)
        history.confirmContext(testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", "S-test")
        history.startSession(testIdentity, ObservationSource.PROVIDER)
        clock.now += 60
        val imported = testRecord().copy(settings = PortableRankedSettings(useManual = true))
        val preview = history.previewImport(testBackup(imported), testIdentity)
        val before = history.state.value.record
        store.failWrites = true
        assertFalse(history.applyImport(preview, testIdentity))
        assertEquals(before, history.state.value.record)
        store.failWrites = false
        assertTrue(history.applyImport(preview, testIdentity))
        val saved = history.state.value.record
        assertTrue(saved.settings!!.useManual); assertNull(saved.currentContextId)
        assertTrue(saved.sessions.single().interrupted); assertNotNull(saved.sessions.single().endedAtSeconds)
    }

    @Test fun cancellationAfterConfirmedAtomicImportStartsKeepsMemoryAndDurableStateConsistent() = runTest {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var committed: String? = null
        var delayWrite = false
        val store = object : RotationStore {
            override suspend fun read() = committed
            override suspend fun write(value: String) {
                if (delayWrite) { started.complete(Unit); finish.await() }
                committed = value
            }
        }
        val history = HistoryRepository(store, HistoryClock())
        history.adoptLegacy(PortableRankedSettings())
        val preview = history.previewImport(testBackup(), null)
        delayWrite = true
        val importing = launch { history.applyImport(preview, null) }
        started.await()
        importing.cancel()
        finish.complete(Unit)
        importing.join()
        assertEquals(1, history.state.value.record.observations.size)
        assertEquals(HistoryCodec.decode(committed!!, TEST_NOW), history.state.value.record)
    }
}
