package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.*

class RankedHistoryIntegrationTest {
    private val lookup = ProfileLookup(testIdentity.uid, testIdentity.platform, true)
    @Test fun acceptedRefreshCapturesButCacheReadsViewsAndCooldownDoNot() = runTest {
        val clock = HistoryClock(); val provider = HistoryFixtureProvider(clock)
        val history = HistoryRepository(HistoryMemoryStore(), clock); history.setRecording(true)
        val profileStore = HistoryMemoryStore()
        val ranked = RankedRepository(provider, profileStore, clock, history = history)
        assertEquals(RefreshOutcome.UPDATED, ranked.connect(lookup))
        assertEquals(1, history.state.value.record.observations.size)
        repeat(5) { ranked.initialize(); ranked.view(clock.now, LocalDate(2026, 11, 3)); ranked.refresh(true) }
        assertEquals(1, provider.calls); assertEquals(1, history.state.value.record.observations.size)
        val reopenedHistory = HistoryRepository(HistoryMemoryStore(HistoryCodec.encode(history.state.value.record)), clock)
        val reopened = RankedRepository(provider, profileStore, clock, history = reopenedHistory)
        reopened.initialize()
        assertEquals(1, reopenedHistory.state.value.record.observations.size); assertEquals(1, provider.calls)
        clock.now += 301; assertEquals(RefreshOutcome.UPDATED, ranked.refresh())
        assertEquals(2, provider.calls); assertEquals(2, history.state.value.record.observations.size)
    }
    @Test fun failedRefreshAndInvalidRpDoNotCaptureStaleSnapshotAgain() = runTest {
        val clock = HistoryClock(); val provider = HistoryFixtureProvider(clock)
        val history = HistoryRepository(HistoryMemoryStore(), clock); history.setRecording(true)
        val ranked = RankedRepository(provider, HistoryMemoryStore(), clock, history = history)
        ranked.connect(lookup); clock.now += 301
        provider.result = RankedResult.Failure(RankedError.NETWORK)
        assertEquals(RefreshOutcome.FAILED, ranked.refresh())
        assertEquals(1, history.state.value.record.observations.size)
        clock.now += 100; provider.result = RankedResult.Success(testSnapshot(clock.now, rp = null))
        assertEquals(RefreshOutcome.UPDATED, ranked.refresh(true))
        assertEquals(1, history.state.value.record.observations.size)
        assertNull(ranked.state.value.profile?.rp)
    }
    @Test fun rejectedProfileWriteNeverCreatesProviderObservation() = runTest {
        val clock = HistoryClock(); val provider = HistoryFixtureProvider(clock)
        val profileStore = HistoryMemoryStore(); val history = HistoryRepository(HistoryMemoryStore(), clock)
        val ranked = RankedRepository(provider, profileStore, clock, history = history)
        ranked.initialize(); history.setRecording(true); profileStore.failWrites = true
        assertEquals(RefreshOutcome.FAILED, ranked.connect(lookup))
        assertTrue(history.state.value.record.observations.isEmpty()); assertNull(ranked.state.value.profile)
    }
    @Test fun rejectedHistoryWriteDoesNotClaimSuccessfulCapture() = runTest {
        val clock = HistoryClock(); val provider = HistoryFixtureProvider(clock)
        val historyStore = HistoryMemoryStore(); val history = HistoryRepository(historyStore, clock)
        val ranked = RankedRepository(provider, HistoryMemoryStore(), clock, history = history)
        ranked.initialize(); history.setRecording(true); historyStore.failWrites = true
        assertEquals(RefreshOutcome.FAILED, ranked.connect(lookup))
        assertTrue(history.state.value.record.observations.isEmpty())
        assertNotNull(history.state.value.error)
    }
    @Test fun v02GoalAndManualSettingsMigrateWithoutRewritingCurrentProfileRecord() = runTest {
        val clock = HistoryClock(); val provider = HistoryFixtureProvider(clock)
        val legacy = RankedRepositoryState(profile = testSnapshot(), goal = RankGoal(16000, 12000), manualRp = 12000,
            useManual = true, manualUpdatedAtSeconds = TEST_NOW - 10)
        val legacyBytes = RankedRecordCodec.encode(legacy)
        val profileStore = HistoryMemoryStore(legacyBytes); val historyStore = HistoryMemoryStore()
        val history = HistoryRepository(historyStore, clock)
        val ranked = RankedRepository(provider, profileStore, clock, history = history); ranked.initialize()
        assertEquals(legacyBytes, profileStore.value); assertEquals(0, provider.calls)
        assertEquals(legacy.goal, history.state.value.record.settings?.goal)
        assertEquals(12000L, history.state.value.record.settings?.manualRp)
        assertTrue(history.state.value.record.observations.isEmpty())
        ranked.saveGoal(RankGoal(17000, 12000)); ranked.setManualRp(12500)
        val reopened = RankedRepository(provider, profileStore, clock, history = HistoryRepository(historyStore, clock)); reopened.initialize()
        assertEquals(17000L, reopened.state.value.goal?.targetRp); assertEquals(12500L, reopened.state.value.manualRp)
        assertEquals(legacy.profile, reopened.state.value.profile)
    }
    @Test fun sourceSwitchInterruptsSessionAndKeepsManualProviderHistoriesSeparate() = runTest {
        val clock = HistoryClock(); val provider = HistoryFixtureProvider(clock)
        val history = HistoryRepository(HistoryMemoryStore(), clock); val ranked = RankedRepository(provider, HistoryMemoryStore(), clock, history = history)
        ranked.initialize(); history.setRecording(true); ranked.connect(lookup)
        history.startSession(testIdentity, ObservationSource.PROVIDER)
        clock.now += 60; ranked.setManualRp(12000); assertTrue(ranked.setUseManual(true))
        assertTrue(history.state.value.record.sessions.single().interrupted)
        assertEquals(setOf(ObservationSource.PROVIDER, ObservationSource.USER_RECORDED), history.state.value.record.observations.map { it.source }.toSet())
        assertTrue(history.state.value.record.observations.filter { it.source == ObservationSource.USER_RECORDED }.all { it.identity == null })
    }
    @Test fun disconnectionArchivesHistoryAndPreservesGoalAndManualRpAcrossRestart() = runTest {
        val clock = HistoryClock(); val provider = HistoryFixtureProvider(clock)
        val profileStore = HistoryMemoryStore(); val historyStore = HistoryMemoryStore(); val history = HistoryRepository(historyStore, clock)
        val ranked = RankedRepository(provider, profileStore, clock, history = history)
        ranked.initialize(); ranked.setManualRp(12000); ranked.saveGoal(RankGoal(16000, 12000))
        history.setRecording(true); ranked.connect(lookup); history.startSession(testIdentity, ObservationSource.PROVIDER)
        clock.now += 60; assertTrue(ranked.disconnect())
        assertNull(ranked.state.value.profile); assertEquals(1, history.state.value.record.observations.size)
        assertTrue(history.state.value.record.sessions.single().interrupted)
        val restored = RankedRepository(provider, profileStore, clock, history = HistoryRepository(historyStore, clock)); restored.initialize()
        assertNull(restored.state.value.profile); assertEquals(12000L, restored.state.value.manualRp)
        assertEquals(16000L, restored.state.value.goal?.targetRp)
    }
    @Test fun importedSettingsAndHistoryAreVisibleTogetherAfterSingleCommitAndRestart() = runTest {
        val clock = HistoryClock(); val historyStore = HistoryMemoryStore(); val profileStore = HistoryMemoryStore()
        val history = HistoryRepository(historyStore, clock)
        val ranked = RankedRepository(HistoryFixtureProvider(clock), profileStore, clock, history = history)
        ranked.initialize(); ranked.setManualRp(9000)
        val preview = history.previewImport(testBackup(), null)
        assertTrue(ranked.importHistory(preview))
        assertEquals(12000L, ranked.state.value.manualRp); assertEquals(1, history.state.value.record.observations.size)
        val restored = RankedRepository(HistoryFixtureProvider(clock), profileStore, clock, history = HistoryRepository(historyStore, clock)); restored.initialize()
        assertEquals(12000L, restored.state.value.manualRp); assertEquals(16000L, restored.state.value.goal?.targetRp)
    }
    @Test fun successfulConnectionPersistsSelectedProviderSourceAcrossColdRestart() = runTest {
        val clock = HistoryClock(); val provider = HistoryFixtureProvider(clock)
        val historyStore = HistoryMemoryStore(); val profileStore = HistoryMemoryStore()
        val ranked = RankedRepository(provider, profileStore, clock, history = HistoryRepository(historyStore, clock))
        ranked.initialize(); ranked.setManualRp(12000); ranked.setUseManual(true)
        assertEquals(RefreshOutcome.UPDATED, ranked.connect(lookup))
        assertFalse(ranked.state.value.useManual)
        val restored = RankedRepository(provider, profileStore, clock, history = HistoryRepository(historyStore, clock)); restored.initialize()
        assertFalse(restored.state.value.useManual)
        assertEquals(13000L, restored.view(clock.now, LocalDate(2026, 11, 3)).currentRp)
    }
    @Test fun backgroundProviderRefreshCannotInterruptIndependentManualSessionOrConfirmation() = runTest {
        val clock = HistoryClock(); val provider = HistoryFixtureProvider(clock)
        val history = HistoryRepository(HistoryMemoryStore(), clock)
        val ranked = RankedRepository(provider, HistoryMemoryStore(), clock, history = history)
        ranked.initialize(); ranked.connect(lookup); ranked.setUseManual(true); history.setRecording(true)
        history.confirmContext(null, ObservationSource.USER_RECORDED, "S-manual", "split-manual")
        ranked.setManualRp(12000)
        clock.now += 10; history.startSession(null, ObservationSource.USER_RECORDED)
        val confirmation = history.state.value.record.currentContextId
        clock.now += 301; assertEquals(RefreshOutcome.UPDATED, ranked.refresh())
        assertEquals(confirmation, history.state.value.record.currentContextId)
        assertNull(history.state.value.record.sessions.single().endedAtSeconds)
        assertFalse(history.state.value.record.sessions.single().interrupted)
    }

    @Test fun corruptOrFutureHistoryDeletionPreservesReadableLegacyGoalAndManualSettings() = runTest {
        val clock = HistoryClock(); val legacy = RankedRepositoryState(goal = RankGoal(16000, 12000), manualRp = 12000,
            useManual = true, manualUpdatedAtSeconds = TEST_NOW - 10)
        for (unreadable in listOf("{corrupt", HistoryCodec.encode(testRecord()).replace("\"schemaVersion\":1", "\"schemaVersion\":99"))) {
            val historyStore = HistoryMemoryStore(unreadable)
            val legacyBytes = RankedRecordCodec.encode(legacy); val profileStore = HistoryMemoryStore(legacyBytes)
            val history = HistoryRepository(historyStore, clock)
            val ranked = RankedRepository(HistoryFixtureProvider(clock), profileStore, clock, history = history)
            ranked.initialize()
            assertTrue(history.state.value.readOnly); assertEquals(unreadable, historyStore.value)
            assertEquals(legacyBytes, profileStore.value)
            assertEquals(legacy.goal, ranked.state.value.goal); assertEquals(12000L, ranked.state.value.manualRp)
            assertTrue(history.deleteHistory())
            assertEquals(legacy.goal, history.state.value.record.settings?.goal)
            assertEquals(12000L, history.state.value.record.settings?.manualRp)
            val restored = RankedRepository(HistoryFixtureProvider(clock), profileStore, clock, history = HistoryRepository(historyStore, clock)); restored.initialize()
            assertEquals(legacy.goal, restored.state.value.goal); assertEquals(12000L, restored.state.value.manualRp)
        }
    }

}
