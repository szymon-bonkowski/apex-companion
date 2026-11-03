package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HistoryRepositoryTest {
    @Test fun recordingRequiresExplicitOptInAndDoesNotCaptureOnInitialization() = runTest {
        val store = HistoryMemoryStore(); val clock = HistoryClock()
        val history = HistoryRepository(store, clock)
        history.initialize()
        assertFalse(history.state.value.record.recording)
        assertTrue(history.captureProvider(testSnapshot()))
        assertTrue(history.captureManual(13000, clock.now))
        assertTrue(history.state.value.record.observations.isEmpty())
        assertTrue(history.setRecording(true))
        assertTrue(history.state.value.record.observations.isEmpty())
    }
    @Test fun repeatedRpAtDifferentTimesIsKeptButExactEventIsDeduplicated() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true)
        val first = testSnapshot()
        assertTrue(history.captureProvider(first)); assertTrue(history.captureProvider(first))
        assertEquals(1, history.state.value.record.observations.size)
        clock.now += 60
        assertTrue(history.captureProvider(testSnapshot(clock.now)))
        assertEquals(2, history.state.value.record.observations.size)
        assertEquals(listOf(13000L, 13000L), history.state.value.record.observations.map { it.rp })
        assertEquals(60L, rpIntervals(history.state.value.record.observations).single().durationSeconds)
    }
    @Test fun conflictingDuplicateEventCannotReplaceOriginalEvidence() = runTest {
        val history = HistoryRepository(HistoryMemoryStore(), HistoryClock())
        history.setRecording(true); history.captureProvider(testSnapshot())
        assertFalse(history.captureProvider(testSnapshot(rp = 14000)))
        assertEquals(13000L, history.state.value.record.observations.single().rp)
    }
    @Test fun userConfirmationOnlyAffectsFutureSamplesAndRemainsLabeled() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.captureProvider(testSnapshot())
        clock.now += 30
        assertTrue(history.confirmContext(testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", "S-test"))
        clock.now += 30; assertTrue(history.captureProvider(testSnapshot(clock.now, 13150)))
        val observations = history.state.value.record.observations
        assertEquals(ContextVerification.UNKNOWN, observations.first().verification)
        assertEquals(ContextVerification.USER_CONFIRMED, observations.last().verification)
        assertEquals(IntervalQuality.UNKNOWN_CONTEXT, rpIntervals(observations).single().quality)
        clock.now += 60; history.captureProvider(testSnapshot(clock.now, 13200))
        assertEquals(50L, rpIntervals(history.state.value.record.observations).last().deltaRp)
    }
    @Test fun seasonChangeInvalidatesConfirmedSplitWithoutRewritingHistory() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.confirmContext(testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", "S-test")
        history.captureProvider(testSnapshot())
        clock.now += 60; history.captureProvider(testSnapshot(clock.now, 12500, season = "S-next"))
        val record = history.state.value.record
        assertNull(record.currentContextId)
        assertEquals(ContextVerification.UNKNOWN, record.observations.last().verification)
        assertEquals(13000L, record.observations.first().rp)
        assertNull(rpIntervals(record.observations).single().deltaRp)
    }
    @Test fun ordinaryNegativeIsValidButSuspiciousDiscontinuityQuarantinesAndRequiresNewBaseline() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.confirmContext(testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", "S-test")
        history.captureProvider(testSnapshot())
        clock.now += 60; history.captureProvider(testSnapshot(clock.now, 12900))
        assertEquals(-100L, rpIntervals(history.state.value.record.observations).single().deltaRp)
        clock.now += 60; history.captureProvider(testSnapshot(clock.now, 9000))
        assertEquals(ObservationQuality.VALID, history.state.value.record.observations.last().quality)
        assertEquals(IntervalQuality.QUARANTINED, rpIntervals(history.state.value.record.observations).last().quality)
        assertNull(history.state.value.record.currentContextId)
        assertNull(rpIntervals(history.state.value.record.observations).last().deltaRp)
        clock.now += 60; history.confirmContext(testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", "S-test")
        clock.now += 60; history.captureProvider(testSnapshot(clock.now, 9100))
        assertNull(rpIntervals(history.state.value.record.observations).last().deltaRp)
    }
    @Test fun nullRpAndFutureSnapshotDoNotCreateObservations() = runTest {
        val history = HistoryRepository(HistoryMemoryStore(), HistoryClock())
        history.setRecording(true)
        assertTrue(history.captureProvider(testSnapshot(rp = null)))
        assertFalse(history.captureProvider(testSnapshot(TEST_NOW + 1)))
        assertTrue(history.state.value.record.observations.isEmpty())
    }
    @Test fun lateArrivalIsRetainedInStableChronologicalOrder() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.captureProvider(testSnapshot(TEST_NOW, 13000))
        clock.now += 120; history.captureProvider(testSnapshot(clock.now, 13150))
        history.captureProvider(testSnapshot(TEST_NOW + 60, 13100))
        assertEquals(listOf(TEST_NOW, TEST_NOW + 60, TEST_NOW + 120), history.state.value.record.observations.map { it.observedAtSeconds })
    }
    @Test fun failedWritePreservesCommittedStateAndSurvivesRepositoryReopening() = runTest {
        val clock = HistoryClock(); val store = HistoryMemoryStore(); val history = HistoryRepository(store, clock)
        history.setRecording(true); history.captureProvider(testSnapshot())
        val before = history.state.value.record; val bytes = store.value
        store.failWrites = true; clock.now += 60
        assertFalse(history.captureProvider(testSnapshot(clock.now, 13150)))
        assertEquals(before, history.state.value.record); assertEquals(bytes, store.value)
        val reopened = HistoryRepository(store, clock); reopened.initialize()
        assertEquals(before, reopened.state.value.record)
    }
    @Test fun corruptOrFutureStoreIsReadOnlyUntilExplicitDeletion() = runTest {
        for (value in listOf("{interrupted", HistoryCodec.encode(testRecord()).replace("\"schemaVersion\":1", "\"schemaVersion\":99"))) {
            val store = HistoryMemoryStore(value); val history = HistoryRepository(store, HistoryClock())
            history.initialize(); assertTrue(history.state.value.readOnly)
            assertFalse(history.setRecording(true)); assertEquals(value, store.value)
            assertTrue(history.deleteHistory()); assertFalse(history.state.value.readOnly)
            assertFalse(history.state.value.record.recording)
        }
    }
    @Test fun recordingOffInterruptsActiveSessionAndPreservesHistoryAndIndependentSettings() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        val settings = PortableRankedSettings(RankGoal(16000, 13000), 12000, true, TEST_NOW)
        history.adoptLegacy(settings); history.setRecording(true); history.captureProvider(testSnapshot())
        history.startSession(testIdentity, ObservationSource.PROVIDER); clock.now += 60
        history.setRecording(false)
        assertEquals(1, history.state.value.record.observations.size)
        assertTrue(history.state.value.record.sessions.single().interrupted)
        assertTrue(history.deleteHistory())
        assertEquals(settings, history.state.value.record.settings)
        assertTrue(history.state.value.record.sessions.isEmpty()); assertTrue(history.state.value.record.observations.isEmpty())
    }
    @Test fun confirmationWithoutAnySamplesExpiresBeforeFirstMuchLaterCapture() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.confirmContext(testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", "S-test")
        clock.now += LONG_GAP_SECONDS + 1
        history.captureProvider(testSnapshot(clock.now, 13000))
        assertNull(history.state.value.record.currentContextId)
        assertEquals(ContextVerification.UNKNOWN, history.state.value.record.observations.single().verification)
    }
    @Test fun confirmedContextExpiresAfterLongOfflineGapAndRequiresNewBaseline() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.confirmContext(testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", "S-test")
        history.captureProvider(testSnapshot())
        clock.now += LONG_GAP_SECONDS + 1; history.captureProvider(testSnapshot(clock.now, 13150))
        assertNull(history.state.value.record.currentContextId)
        assertEquals(ContextVerification.UNKNOWN, history.state.value.record.observations.last().verification)
        assertNull(rpIntervals(history.state.value.record.observations).single().deltaRp)
    }
    @Test fun fullHistoryCapacityRejectsNewSampleWithoutSilentTruncation() = runTest {
        val original = testRecord((0 until HISTORY_MAX_OBSERVATIONS).map { testObservation("saved-$it") }).copy(recording = true)
        val store = HistoryMemoryStore(HistoryCodec.encode(original)); val history = HistoryRepository(store, HistoryClock())
        history.initialize(); val committed = store.value
        assertFalse(history.captureProvider(testSnapshot()))
        assertEquals(HISTORY_MAX_OBSERVATIONS, history.state.value.record.observations.size)
        assertEquals(committed, store.value)
        val reopened = HistoryRepository(store, HistoryClock()); reopened.initialize()
        assertEquals(original.observations.size, reopened.state.value.record.observations.size)
    }
    @Test fun upstreamIntervalsDoNotDependOnLateArrivalOrderWhenIntermediateEvidenceBridgesJump() = runTest {
        val values = listOf(testSnapshot(TEST_NOW - 120, 13000, split = "split-test"),
            testSnapshot(TEST_NOW - 60, 14000, split = "split-test"), testSnapshot(TEST_NOW, 15000, split = "split-test"))
        val first = HistoryRepository(HistoryMemoryStore(), HistoryClock()); first.setRecording(true)
        val second = HistoryRepository(HistoryMemoryStore(), HistoryClock()); second.setRecording(true)
        for (value in values) assertTrue(first.captureProvider(value))
        for (value in listOf(values[0], values[2], values[1])) assertTrue(second.captureProvider(value))
        assertEquals(rpIntervals(first.state.value.record.observations), rpIntervals(second.state.value.record.observations))
    }

    @Test fun seasonEvidenceWithoutRpStillInvalidatesContextAndActiveSessionWithoutRecordingSample() = runTest {
        val clock = HistoryClock()
        val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true)
        history.confirmContext(testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", "S-test")
        history.captureProvider(testSnapshot())
        history.startSession(testIdentity, ObservationSource.PROVIDER)
        clock.now += 60
        assertTrue(history.captureProvider(testSnapshot(clock.now, rp = null, season = "S-next")))
        assertEquals(1, history.state.value.record.observations.size)
        assertNull(history.state.value.record.currentContextId)
        assertTrue(history.state.value.record.sessions.single().interrupted)
        assertEquals(SessionClassification.UNCERTAIN, sessionResult(history.state.value.record.sessions.single(), history.state.value.record.observations).classification)
    }
}
