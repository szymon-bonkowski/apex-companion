package io.github.szymonbonkowski.apexcompanion.history

import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.serialization.json.*
import kotlin.test.*

class HistoryCodecTest {
    @Test fun normalizedRecordRoundTripsAndSortsByTimeThenStableId() {
        val a = testObservation("a", at = TEST_NOW - 60)
        val b = testObservation("b", 13150, TEST_NOW)
        val record = testRecord(listOf(b, a))
        val restored = HistoryCodec.decode(HistoryCodec.encode(record), TEST_NOW)
        assertEquals(record.copy(observations = listOf(a, b)), restored)
        assertEquals(HistoryCodec.encode(restored), HistoryCodec.encode(record))
    }
    @Test fun backupIsPortableAllowlistAndDoesNotEnableRecordingOrActivateContext() {
        val context = RankedContext("confirmed-context", testIdentity, ObservationSource.PROVIDER,
            "S-test", "split-test", TEST_NOW - 100, "S-test")
        val record = testRecord().copy(recording = true, contexts = listOf(context), currentContextId = context.id,
            importedBatchIds = listOf("old-batch"))
        val value = testBackup(record)
        val backup = HistoryCodec.import(value, TEST_NOW)
        assertFalse(backup.record.recording); assertNull(backup.record.currentContextId)
        assertTrue(backup.record.importedBatchIds.isEmpty())
        assertEquals(record.settings, backup.record.settings)
        assertEquals(record.observations, backup.record.observations)
        for (forbidden in listOf("authorization", "apiKey", "rawResponse", "filesDir", "widgetId", "predator", "map-cache")) assertFalse(value.contains(forbidden))
    }
    @Test fun t015MalformedOversizedDeepAndFutureSchemaRejected() {
        val good = testBackup()
        for (bad in listOf("{truncated", good.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
            good.replace("\"exportedAtSeconds\":$TEST_NOW", "\"exportedAtSeconds\":${TEST_NOW + 1}"),
            " ".repeat(HISTORY_MAX_BYTES + 1), "[".repeat(40) + "0" + "]".repeat(40))) {
            assertFails { HistoryCodec.import(bad, TEST_NOW) }
        }
    }
    @Test fun oversizedUtf8IsBoundedByBytesRatherThanCharCount() {
        assertFails { HistoryCodec.import("é".repeat(HISTORY_MAX_BYTES / 2 + 1), TEST_NOW) }
    }
    @Test fun strictTypesUnknownFieldsAndInvalidPortableSettingsAreRejected() {
        val good = testBackup()
        for (bad in listOf(
            good.replace("\"rp\":13000", "\"rp\":null"),
            good.replace("\"rp\":13000", "\"rp\":\"13000\""),
            good.replace("\"rp\":13000", "\"rp\":-1"),
            good.replace("\"rp\":13000", "\"rp\":${MAX_RP + 1}"),
            good.replace("\"rp\":13000", "\"rp\":1.5"),
            good.replace("\"recording\":false", "\"recording\":\"false\""),
            good.replace("\"useManual\":true", "\"useManual\":1"),
            good.replace("\"platform\":\"PC\"", "\"platform\":\"unsupported\""),
            good.replace("\"uid\":\"700001\"", "\"uid\":\"abc\""),
            good.replace("\"mode\":\"BATTLE_ROYALE\"", "\"mode\":\"ARENAS\""),
            good.replace("\"format\":", "\"secret\":\"forbidden\",\"format\":"),
            good.replace("\"observedAtSeconds\":${TEST_NOW - 60}", "\"observedAtSeconds\":${TEST_NOW + 60}"),
            good.replace("\"observedAtSeconds\":${TEST_NOW - 60}", "\"observedAtSeconds\":\"not-a-timestamp\""),
        )) assertFails("Should reject mutated normalized record") { HistoryCodec.import(bad, TEST_NOW) }
    }
    @Test fun danglingSessionAnchorsAndMismatchedIdentityAreRejected() {
        val a = testObservation(at = TEST_NOW - 120)
        val b = testObservation("later", 13150, TEST_NOW - 20)
        val session = RankedSession("session", testIdentity, ObservationSource.PROVIDER, a.contextId,
            TEST_NOW - 100, TEST_NOW, a.id, b.id)
        HistoryCodec.validate(testRecord(listOf(a, b), listOf(session)), TEST_NOW)
        assertFails { HistoryCodec.validate(testRecord(listOf(a), listOf(session)), TEST_NOW) }
        assertFails { HistoryCodec.validate(testRecord(listOf(a, b), listOf(session.copy(identity = HistoryIdentity("700002", ApexPlatform.PC)))), TEST_NOW) }
        assertFails { HistoryCodec.validate(testRecord(listOf(a, b), listOf(session.copy(startedAtSeconds = TEST_NOW - 150))), TEST_NOW) }
    }
    @Test fun userConfirmedObservationRequiresOriginalMatchingContext() {
        val observation = testObservation(verification = ContextVerification.USER_CONFIRMED, context = "context-1")
        assertFails { HistoryCodec.validate(testRecord(listOf(observation)), TEST_NOW) }
        val c = RankedContext("context-1", testIdentity, ObservationSource.PROVIDER, "S-test", "split-test", TEST_NOW - 100)
        HistoryCodec.validate(testRecord(listOf(observation)).copy(contexts = listOf(c)), TEST_NOW)
        assertFails { HistoryCodec.validate(testRecord(listOf(observation)).copy(contexts = listOf(c.copy(split = "different"))), TEST_NOW) }
        assertFails { HistoryCodec.validate(testRecord(listOf(observation)).copy(contexts = listOf(c.copy(confirmedAtSeconds = TEST_NOW))), TEST_NOW) }
    }
    @Test fun duplicateRecordIdsAndMultipleActiveSessionsAreRejected() {
        val observation = testObservation()
        assertFails { HistoryCodec.validate(testRecord(listOf(observation, observation)), TEST_NOW) }
        val session = RankedSession("session", testIdentity, ObservationSource.PROVIDER, null, TEST_NOW)
        assertFails { HistoryCodec.validate(testRecord(sessions = listOf(session, session.copy(id = "second-session"))), TEST_NOW) }
    }
    @Test fun capacityDoesNotSilentlyTrimRecords() {
        val tooMany = (0..HISTORY_MAX_OBSERVATIONS).map { testObservation("observation-$it") }
        assertFails { HistoryCodec.validate(testRecord(tooMany), TEST_NOW) }
        assertEquals(HISTORY_MAX_OBSERVATIONS + 1, tooMany.size)
    }
    @Test fun sessionContextMustBelongToItsIdentityAndSourceEvenWithoutAnchors() {
        val foreign = HistoryIdentity("700002", ApexPlatform.PC)
        val c = RankedContext("foreign-context", foreign, ObservationSource.PROVIDER, "S-test", "split-test", TEST_NOW - 100)
        val session = RankedSession("session", testIdentity, ObservationSource.PROVIDER, c.id, TEST_NOW)
        assertFails { HistoryCodec.validate(testRecord(sessions = listOf(session)).copy(contexts = listOf(c)), TEST_NOW) }
    }
    @Test fun userRecordedContextsAndSessionsCannotClaimLinkedIdentity() {
        assertFails {
            val context = RankedContext("manual-context", testIdentity, ObservationSource.USER_RECORDED, "S-test", "split-test", TEST_NOW - 100)
            HistoryCodec.validate(testRecord().copy(contexts = listOf(context)), TEST_NOW)
        }
        assertFails {
            val session = RankedSession("manual-session", testIdentity, ObservationSource.USER_RECORDED, null, TEST_NOW)
            HistoryCodec.validate(testRecord(sessions = listOf(session)), TEST_NOW)
        }
    }

    @Test fun backupCannotContainRecordsFromAfterItsClaimedExportTime() {
        val value = testBackup().replace("\"exportedAtSeconds\":$TEST_NOW", "\"exportedAtSeconds\":${TEST_NOW - 120}")
        assertFails { HistoryCodec.import(value, TEST_NOW) }
    }

    @Test fun staleSessionStartAnchorCannotBypassNativeStartFreshnessPolicyOnImport() {
        val observation = testObservation(at = TEST_NOW - 301)
        val session = RankedSession("stale-session", testIdentity, ObservationSource.PROVIDER,
            observation.contextId, TEST_NOW, startObservationId = observation.id)
        assertFails { HistoryCodec.import(testBackup(testRecord(sessions = listOf(session), observations = listOf(observation))), TEST_NOW) }
    }
    @Test fun sessionResultWindowAndCompletenessAreDurableAndRejectInconsistentSummary() {
        val a = testObservation("anchor-a", 13000, TEST_NOW - 60)
        val b = testObservation("anchor-b", 13150, TEST_NOW)
        val session = RankedSession("saved-session", testIdentity, ObservationSource.PROVIDER,
            a.contextId, TEST_NOW - 50, TEST_NOW, a.id, b.id)
        val record = testRecord(listOf(a, b), listOf(session))
        val encoded = HistoryCodec.encode(record)
        assertTrue(encoded.contains("\"deltaRp\":150"))
        assertTrue(encoded.contains("\"classification\":\"OBSERVED\""))
        assertTrue(encoded.contains("\"timeComplete\":true,\"rpComplete\":true"))
        assertEquals(record, HistoryCodec.decode(encoded, TEST_NOW))
        assertFails { HistoryCodec.decode(encoded.replace("\"deltaRp\":150", "\"deltaRp\":0"), TEST_NOW) }
    }
}
