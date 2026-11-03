package io.github.szymonbonkowski.apexcompanion.history

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HistorySessionTest {
    @Test fun activeSessionRestoresOriginalIdAndStartTimeAfterProcessRestart() = runTest {
        val clock = HistoryClock(); val store = HistoryMemoryStore(); val history = HistoryRepository(store, clock)
        history.setRecording(true); assertTrue(history.startSession(testIdentity, ObservationSource.PROVIDER))
        val original = history.state.value.record.sessions.single()
        clock.now += 3600
        val reopened = HistoryRepository(store, clock); reopened.initialize()
        assertEquals(original, reopened.state.value.record.sessions.single())
        assertFalse(reopened.startSession(testIdentity, ObservationSource.PROVIDER))
        assertEquals(1, reopened.state.value.record.sessions.size)
        assertTrue(reopened.stopSession())
        val stopped = reopened.state.value.record.sessions.single()
        assertEquals(original.startedAtSeconds, stopped.startedAtSeconds)
        assertEquals(clock.now, stopped.endedAtSeconds)
        assertNull(sessionResult(stopped, reopened.state.value.record.observations).deltaRp)
        assertEquals(SessionClassification.PARTIAL, sessionResult(stopped, emptyList()).classification)
    }
    @Test fun noAnchorsStopsSafelyOfflineWithUnknownRpRatherThanZero() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.startSession(null, ObservationSource.USER_RECORDED)
        clock.now += 60; assertTrue(history.stopSession())
        val result = sessionResult(history.state.value.record.sessions.single(), emptyList())
        assertNull(result.deltaRp); assertNull(result.windowStartSeconds); assertNull(result.windowEndSeconds)
        assertEquals(SessionClassification.PARTIAL, result.classification)
    }
    @Test fun validAvailableStartAndLaterEndAnchorsProduceObservedWindow() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.confirmContext(testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", "S-test")
        history.captureProvider(testSnapshot())
        clock.now += 10; history.startSession(testIdentity, ObservationSource.PROVIDER)
        val start = history.state.value.record.sessions.single()
        assertNotNull(start.startObservationId)
        clock.now += 50; history.captureProvider(testSnapshot(clock.now, 13150))
        clock.now += 120; history.stopSession()
        val session = history.state.value.record.sessions.single()
        val result = sessionResult(session, history.state.value.record.observations)
        assertEquals(SessionClassification.OBSERVED, result.classification)
        assertEquals(150L, result.deltaRp)
        assertEquals(TEST_NOW, result.windowStartSeconds); assertEquals(TEST_NOW + 60, result.windowEndSeconds)
        assertEquals(170L, session.endedAtSeconds!! - session.startedAtSeconds)
        assertEquals(60L, result.windowEndSeconds!! - result.windowStartSeconds!!)
    }
    @Test fun staleStartOrUnchangedCachedEndNeverFabricatesSessionZero() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.captureProvider(testSnapshot(split = "split-test"))
        clock.now += 301; history.startSession(testIdentity, ObservationSource.PROVIDER)
        assertNull(history.state.value.record.sessions.single().startObservationId)
        clock.now += 100; history.stopSession()
        val result = sessionResult(history.state.value.record.sessions.single(), history.state.value.record.observations)
        assertNull(result.deltaRp); assertEquals(SessionClassification.PARTIAL, result.classification)
    }
    @Test fun providerEqualRpLaterCanProduceSessionZeroWithTwoDistinctAnchors() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.captureProvider(testSnapshot(split = "split-test"))
        clock.now += 10; history.startSession(testIdentity, ObservationSource.PROVIDER)
        clock.now += 50; history.captureProvider(testSnapshot(clock.now, split = "split-test")); history.stopSession()
        val s = history.state.value.record.sessions.single()
        assertNotEquals(s.startObservationId, s.endObservationId)
        assertEquals(0L, sessionResult(s, history.state.value.record.observations).deltaRp)
    }
    @Test fun manualSessionsRetainUserRecordedClassification() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.confirmContext(null, ObservationSource.USER_RECORDED, "S-manual", "split-manual")
        history.captureManual(13000, clock.now)
        clock.now += 10; history.startSession(null, ObservationSource.USER_RECORDED)
        clock.now += 50; history.captureManual(13150, clock.now); history.stopSession()
        val result = sessionResult(history.state.value.record.sessions.single(), history.state.value.record.observations)
        assertEquals(SessionClassification.MANUAL, result.classification); assertEquals(150L, result.deltaRp)
    }
    @Test fun contextChangeClosesActiveSessionAsUncertainWithoutCrossIdentityAnchor() = runTest {
        val clock = HistoryClock(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        history.setRecording(true); history.captureProvider(testSnapshot(split = "split-test"))
        clock.now += 10; history.startSession(testIdentity, ObservationSource.PROVIDER)
        clock.now += 50; history.contextChanged()
        val s = history.state.value.record.sessions.single()
        assertTrue(s.interrupted); assertNotNull(s.endedAtSeconds)
        assertEquals(SessionClassification.UNCERTAIN, sessionResult(s, history.state.value.record.observations).classification)
        assertNull(sessionResult(s, history.state.value.record.observations).deltaRp)
    }
    @Test fun oneGlobalActiveSessionPreventsCrossSourceOverlaps() = runTest {
        val history = HistoryRepository(HistoryMemoryStore(), HistoryClock())
        history.setRecording(true); assertTrue(history.startSession(testIdentity, ObservationSource.PROVIDER))
        assertFalse(history.startSession(null, ObservationSource.USER_RECORDED))
        assertEquals(1, history.state.value.record.sessions.size)
    }
    @Test fun validMultiSampleSessionCanAccumulateBeyondSingleIntervalAnomalyThreshold() {
        val a = testObservation("a", 13000, TEST_NOW - 300)
        val b = testObservation("b", 13500, TEST_NOW - 200)
        val c = testObservation("c", 14000, TEST_NOW - 100)
        val d = testObservation("d", 14500, TEST_NOW)
        val s = RankedSession("session", testIdentity, ObservationSource.PROVIDER, a.contextId, TEST_NOW - 290, TEST_NOW, a.id, d.id)
        assertTrue(rpIntervals(listOf(a, b, c, d)).all { it.deltaRp != null })
        assertEquals(1500L, sessionResult(s, listOf(a, b, c, d)).deltaRp)
    }
}
