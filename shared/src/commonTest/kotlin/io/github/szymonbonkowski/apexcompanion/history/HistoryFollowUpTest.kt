package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.ranked.RankGoal
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class HistoryFollowUpTest {
    private fun context() = RankedContext("confirmed-context", testIdentity, ObservationSource.PROVIDER,
        "S-test", "split-test", TEST_NOW - 120, "S-test")
    private fun confirmedRecord(): HistoryRecord {
        val c = context()
        val o = testObservation(context = c.id, verification = ContextVerification.USER_CONFIRMED)
        return testRecord(listOf(o)).copy(contexts = listOf(c))
    }
    private val localSettings = PortableRankedSettings(RankGoal(19000, 9000), 9000, true, TEST_NOW)

    @Test fun providerSeasonMustMatchConfirmedSeasonIncludingCopies() {
        val c = context()
        assertEquals(c.season, c.providerSeason)
        assertNull(c.copy(providerSeason = null).providerSeason)
        assertFailsWith<IllegalArgumentException> { c.copy(providerSeason = "S-other") }
        assertFailsWith<IllegalArgumentException> { c.copy(season = "S-other") }
    }

    @Test fun contradictoryProviderSeasonJsonRejectsWithoutChangingExistingDataOrSettings() = runTest {
        val store = HistoryMemoryStore(); val history = HistoryRepository(store, HistoryClock())
        history.adoptLegacy(localSettings)
        val before = history.state.value.record; val bytes = store.value; val writes = store.writes
        val bad = testBackup(confirmedRecord()).replace("\"providerSeason\":\"S-test\"", "\"providerSeason\":\"S-other\"")
        assertFails { history.previewImport(bad, testIdentity) }
        assertEquals(before, history.state.value.record)
        assertEquals(localSettings, history.state.value.record.settings)
        assertEquals(bytes, store.value); assertEquals(writes, store.writes)
    }

    @Test fun knownContextRelationshipsCannotBypassValidationWithDifferentVerificationLabels() = runTest {
        val store = HistoryMemoryStore(); val history = HistoryRepository(store, HistoryClock())
        history.adoptLegacy(localSettings)
        val before = history.state.value.record; val bytes = store.value; val writes = store.writes
        val valid = confirmedRecord()
        val preview = history.previewImport(testBackup(valid), testIdentity)
        for (verification in ContextVerification.entries) {
            val mismatched = valid.copy(observations = listOf(valid.observations.single().copy(
                season = "S-other", verification = verification,
                quality = if (verification == ContextVerification.UNKNOWN) ObservationQuality.UNKNOWN_CONTEXT else ObservationQuality.VALID)))
            assertFails { history.previewImport(testBackup(mismatched), testIdentity) }
            assertFalse(history.applyImport(preview.copy(backup = preview.backup.copy(record = mismatched)), testIdentity))
            assertEquals(before, history.state.value.record); assertEquals(localSettings, history.state.value.record.settings)
            assertEquals(bytes, store.value); assertEquals(writes, store.writes)
        }
    }

    @Test fun historicalObservationAndSessionCannotPrecedeTheirConfirmation() {
        val record = confirmedRecord(); val c = record.contexts.single()
        val older = record.copy(observations = listOf(record.observations.single().copy(observedAtSeconds = c.confirmedAtSeconds - 1)))
        assertFails { HistoryCodec.import(testBackup(older), TEST_NOW) }
        val session = RankedSession("too-early", testIdentity, ObservationSource.PROVIDER, c.id, c.confirmedAtSeconds - 1)
        assertFails { HistoryCodec.import(testBackup(record.copy(sessions = listOf(session))), TEST_NOW) }
    }

    @Test fun matchingAndUnknownProviderSeasonAndUpstreamOnlyContextsStillImport() {
        val record = confirmedRecord()
        assertEquals(record, HistoryCodec.import(testBackup(record), TEST_NOW).record)
        val unknown = record.copy(contexts = listOf(record.contexts.single().copy(providerSeason = null)))
        assertEquals(unknown, HistoryCodec.import(testBackup(unknown), TEST_NOW).record)
        val upstream = testRecord()
        assertEquals(upstream, HistoryCodec.import(testBackup(upstream), TEST_NOW).record)
    }

    @Test fun repeatedBatchIsAnAdvisoryMarkerAndExactRepeatIsAnIdempotentMerge() = runTest {
        val store = HistoryMemoryStore(); val history = HistoryRepository(store, HistoryClock())
        history.initialize()
        val backup = testBackup()
        assertFalse(history.previewImport(backup, testIdentity).batchPreviouslyImported)
        assertTrue(history.applyImport(history.previewImport(backup, testIdentity), testIdentity))
        val before = history.state.value.record; val bytes = store.value; val writes = store.writes
        val repeated = history.previewImport(backup, testIdentity)
        assertTrue(repeated.batchPreviouslyImported)
        assertTrue(repeated.warnings.any { it.contains("does not verify identical contents") && it.contains("validates and merges") })
        assertTrue(history.applyImport(repeated, testIdentity))
        assertEquals(before, history.state.value.record); assertEquals(bytes, store.value); assertEquals(writes, store.writes)
    }

    @Test fun sameBatchWithNewContentsIsExplicitlyPreviewedAndRequiresConfirmedMerge() = runTest {
        val history = HistoryRepository(HistoryMemoryStore(), HistoryClock()); history.initialize()
        assertTrue(history.applyImport(history.previewImport(testBackup(), testIdentity), testIdentity))
        val newObservation = testObservation(id = "new-point", rp = 13150, at = TEST_NOW)
        val changed = testRecord(listOf(testObservation(), newObservation)).copy(settings = localSettings)
        val preview = history.previewImport(testBackup(changed), testIdentity)
        assertTrue(preview.batchPreviouslyImported); assertEquals(2, preview.observationCount)
        assertTrue(preview.warnings.any { it.contains("new records may be added") && it.contains("goal/manual settings restored") })
        assertEquals(1, history.state.value.record.observations.size)
        assertEquals(12000L, history.state.value.record.settings?.manualRp)
        assertTrue(history.applyImport(preview, testIdentity))
        assertEquals(2, history.state.value.record.observations.size)
        assertEquals(localSettings, history.state.value.record.settings)
        assertEquals(listOf("fixture-batch"), history.state.value.record.importedBatchIds)
    }

    @Test fun repeatedBatchCannotBypassCollisionsOrOverwriteExistingGoalManualSettingsOnFailure() = runTest {
        val store = HistoryMemoryStore(); val history = HistoryRepository(store, HistoryClock()); history.initialize()
        val original = history.previewImport(testBackup(), testIdentity)
        assertTrue(history.applyImport(original, testIdentity)); assertTrue(history.saveSettings(localSettings))
        val before = history.state.value.record; val bytes = store.value; val writes = store.writes
        val changed = testRecord(listOf(testObservation(rp = 13150)))
        assertFails { history.previewImport(testBackup(changed), testIdentity) }
        assertFalse(history.applyImport(original.copy(backup = original.backup.copy(record = changed)), testIdentity))
        assertEquals(before, history.state.value.record); assertEquals(localSettings, history.state.value.record.settings)
        assertEquals(bytes, store.value); assertEquals(writes, store.writes)
    }

    @Test fun repeatedExplicitRestoreCanRestoreGoalManualSettingsAndFailedWriteIsAtomic() = runTest {
        val store = HistoryMemoryStore(); val clock = HistoryClock(); val history = HistoryRepository(store, clock)
        history.initialize()
        val backup = testBackup()
        assertTrue(history.applyImport(history.previewImport(backup, testIdentity), testIdentity))
        assertTrue(history.saveSettings(localSettings))
        val preview = history.previewImport(backup, testIdentity)
        assertTrue(preview.batchPreviouslyImported)
        val before = history.state.value.record; val bytes = store.value
        assertEquals(localSettings, before.settings) // Preview itself never restores settings.
        store.failWrites = true
        assertFalse(history.applyImport(preview, testIdentity))
        assertEquals(before, history.state.value.record); assertEquals(bytes, store.value)
        val reopened = HistoryRepository(store, clock); reopened.initialize()
        assertEquals(before, reopened.state.value.record)
        store.failWrites = false
        assertTrue(reopened.applyImport(reopened.previewImport(backup, testIdentity), testIdentity))
        assertEquals(testRecord().settings, reopened.state.value.record.settings)
        assertEquals(before.observations, reopened.state.value.record.observations)
    }
}
