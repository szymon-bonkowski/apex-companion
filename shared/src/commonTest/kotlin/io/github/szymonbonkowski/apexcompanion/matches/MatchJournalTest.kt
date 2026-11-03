package io.github.szymonbonkowski.apexcompanion.matches

import io.github.szymonbonkowski.apexcompanion.history.*
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.ranked.ApexPlatform
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class MatchJournalTest {
    private val now = 1_793_700_000L
    private val identity = HistoryIdentity("123", ApexPlatform.PC)
    private fun manual(id: String = "m1") = MatchRecord(id = id, timestampSeconds = now - 50, kills = 2)
    private fun auto(id: String = "m1", event: String = "pc:event1") = MatchRecord(id = id, identity = identity, mode = MatchMode.RANKED,
        kills = 2, damage = 500, rpDelta = 25, timestampSeconds = now - 50, timestampSource = MatchTimestampSource.RESULT_OBSERVED,
        source = MatchSource.WINDOWS_CAPTURE, quality = MatchQuality.AUTO_ACCEPTED, evidenceEventId = event,
        parserVersion = "synthetic-v1", fieldConfidence = mapOf("kills" to 0.99))
    private class Store(var value: String? = null): RotationStore {
        var fail = false; var writes = 0
        override suspend fun read() = value
        override suspend fun write(value: String) { if (fail) error("disk full"); this.value = value; writes++ }
    }
    private fun repo(store: Store = Store()) = MatchRepository(store, RotationClock { now })
    @Test fun validRecordPreservesUnknownRatherThanZero() {
        val m = manual().copy(kills = null)
        assertNull(m.kills); assertNull(m.assists); assertNull(m.rpDelta); assertEquals(MatchMode.UNKNOWN, m.mode)
        assertEquals(m, MatchCodec.decodeRecord(MatchCodec.recordJson(m).toString(), now))
    }
    @Test fun numericBoundsRejectImpossibleAndOverflow() {
        listOf(-1, 101, Int.MAX_VALUE).forEach { assertFailsWith<IllegalArgumentException> { manual().copy(kills = it) } }
        assertFailsWith<IllegalArgumentException> { manual().copy(assists = -1) }
        assertFailsWith<IllegalArgumentException> { manual().copy(damage = 100001) }
        assertFailsWith<IllegalArgumentException> { manual().copy(placement = 0) }
        assertFailsWith<IllegalArgumentException> { manual().copy(placement = 101) }
        assertFailsWith<IllegalArgumentException> { manual().copy(rpDelta = -10001) }
        assertFailsWith<IllegalArgumentException> { manual().copy(rpDelta = 10001) }
        assertEquals(-100, manual().copy(rpDelta = -100).rpDelta)
    }
    @Test fun confidenceMustDescribeKnownFieldAndBeFinite() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1.1).forEach { confidence ->
            assertFailsWith<IllegalArgumentException> { auto().copy(fieldConfidence = mapOf("kills" to confidence)) }
        }
        assertFailsWith<IllegalArgumentException> { auto().copy(fieldConfidence = mapOf("legend" to 0.9)) }
        assertFailsWith<IllegalArgumentException> { auto().copy(fieldConfidence = mapOf("privateName" to 0.9)) }
    }
    @Test fun provenanceCannotPretendManualIsAutomaticallyVerified() {
        assertFailsWith<IllegalArgumentException> { manual().copy(quality = MatchQuality.AUTO_ACCEPTED) }
        assertFailsWith<IllegalArgumentException> { auto().copy(evidenceEventId = null) }
        assertFailsWith<IllegalArgumentException> { auto().copy(parserVersion = null) }
        assertFailsWith<IllegalArgumentException> { auto().copy(mode = MatchMode.UNKNOWN) }
        assertFailsWith<IllegalArgumentException> { auto().copy(source = MatchSource.SCREENSHOT) }
        val review = auto().copy(quality = MatchQuality.REVIEW_REQUIRED, mode = MatchMode.UNKNOWN)
        assertEquals(review, MatchCandidate(review, listOf("Unknown mode")).record)
    }
    @Test fun strictCodecRejectsFutureSchemaUnexpectedKeysAndWrongTypes() {
        val json = MatchCodec.recordJson(manual()).toString()
        assertFails { MatchCodec.decodeRecord(json.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), now) }
        assertFails { MatchCodec.decodeRecord(json.dropLast(1) + ",\"token\":\"x\"}", now) }
        assertFails { MatchCodec.decodeRecord(json.replace("\"kills\":2", "\"kills\":\"2\""), now) }
        assertFails { MatchCodec.decodeRecord(json.replace("\"kills\":2", "\"kills\":2.5"), now) }
        assertFails { MatchCodec.decodeRecord(MatchCodec.recordJson(manual().copy(timestampSeconds = now + 1)).toString(), now) }
    }
    @Test fun duplicateJsonKeysCannotHideContradictoryFields() {
        val json = MatchCodec.recordJson(manual()).toString()
        assertFails { MatchCodec.decodeRecord(json.dropLast(1) + ",\"kills\":4}", now) }
        assertFails { MatchCodec.decodeRecord(json.dropLast(1) + ",\"ki\\u006cls\":4}", now) }
    }
    @Test fun codecBoundsNestedAndOversizedInputBeforeParsing() {
        assertFails { MatchCodec.decodeRecord("[".repeat(30) + "]".repeat(30), now) }
        assertFails { MatchCodec.decodeRecord(" ".repeat(MATCH_BATCH_BYTES + 1), now) }
        assertFails { MatchCodec.decodeRecord("{\"schemaVersion\":1", now) }
    }
    @Test fun metricsUseEligibleKnownDenominatorsAndNeverRpIntervals() {
        val records = listOf(manual(), manual("m2").copy(kills = null, assists = 4, damage = 100, placement = 4, rpDelta = -5), auto("m3"), auto("m4", "pc:event4").copy(quality = MatchQuality.REVIEW_REQUIRED))
        val confirmed = aggregateMatches(records)
        assertEquals(2, confirmed.total); assertEquals(MatchMetric(2, 1), confirmed.kills)
        assertEquals(MatchMetric(-5, 1), confirmed.rp); assertEquals(4.0, confirmed.placement.average)
        val combined = aggregateMatches(records, true)
        assertEquals(3, combined.total); assertEquals(1, combined.automatic); assertEquals(2, combined.confirmed)
        assertEquals(MatchMetric(20, 2), combined.rp); assertEquals(10.0, combined.rp.average)
        assertNull(aggregateMatches(emptyList()).kills.average)
    }
    @Test fun groupsExcludeUnknownCategoryAndPreservePolicy() {
        val values = listOf(manual().copy(map = "Example", legend = "Synthetic"), manual("m2").copy(kills = null), auto("a").copy(map = "Example"))
        assertEquals(1, matchGroups(values, false).getValue("Example").total)
        assertEquals(2, matchGroups(values, false, true).getValue("Example").total)
        assertEquals(setOf("Synthetic"), matchGroups(values, true).keys)
    }
    @Test fun duplicateFingerprintWarnsButDifferentMatchesRemainDistinct() = runTest {
        val r = repo(); assertTrue(r.saveManual(manual())); assertTrue(manual().possibleDuplicate(manual("m2")))
        assertTrue(r.saveManual(manual("m2"))); assertEquals(2, r.state.value.records.size)
        assertFalse(r.saveManual(manual())); assertEquals(2, r.state.value.records.size)
    }
    @Test fun atomicWriteFailurePreservesCommittedMemoryAndRestart() = runTest {
        val s = Store(); val r = repo(s); assertTrue(r.saveManual(manual())); val before = s.value
        s.fail = true; assertFalse(r.saveManual(manual("m2"))); assertEquals(before, s.value)
        assertEquals(listOf(manual()), r.state.value.records)
        val restarted = repo(s); restarted.initialize(); assertEquals(listOf(manual()), restarted.state.value.records)
    }
    @Test fun corruptOrFutureStoreNeverOverwritesOriginal() = runTest {
        for (bytes in listOf("broken", "{\"schemaVersion\":99}")) {
            val s = Store(bytes); val r = repo(s); r.initialize(); assertTrue(r.state.value.readOnly)
            assertFalse(r.saveManual(manual())); assertFalse(r.deleteHistory()); assertEquals(bytes, s.value); assertEquals(0, s.writes)
        }
    }
    @Test fun syncCommitsMatchesAndCursorTogetherThenReplaysSafely() = runTest {
        val s = Store(); val r = repo(s)
        assertTrue(r.applySync("pc", 1, listOf(auto()), identity)); assertEquals(1, r.cursor("pc"))
        assertTrue(r.applySync("pc", 2, listOf(auto()), identity)); assertEquals(1, r.state.value.records.size)
        val restarted = repo(s); restarted.initialize(); assertEquals(2, restarted.cursor("pc"))
        assertTrue(restarted.applySync("pc", 2, emptyList(), identity))
    }
    @Test fun syncFailedCommitNeverAdvancesCursor() = runTest {
        val s = Store(); val r = repo(s); r.initialize(); s.fail = true
        assertFalse(r.applySync("pc", 1, listOf(auto()), identity)); assertEquals(0, r.cursor("pc")); assertTrue(r.state.value.records.isEmpty())
        s.fail = false; assertTrue(r.applySync("pc", 1, listOf(auto()), identity))
    }
    @Test fun cursorRegressionSkipAndWrongEventNamespaceReject() = runTest {
        val r = repo(); assertFalse(r.applySync("pc", 1, emptyList(), identity))
        assertFalse(r.applySync("pc", 1, listOf(auto(event = "other:event")), identity))
        assertTrue(r.applySync("pc", 1, listOf(auto()), identity))
        assertFalse(r.applySync("pc", 0, emptyList(), identity)); assertFalse(r.applySync("pc", 1, listOf(auto()), identity))
    }
    @Test fun identityAndPlatformBoundariesRejectWithoutWriting() = runTest {
        val r = repo(); assertFalse(r.applySync("pc", 1, listOf(auto()), null))
        assertFalse(r.applySync("pc", 1, listOf(auto()), identity.copy(platform = ApexPlatform.XBOX)))
        assertFalse(r.applySync("pc", 1, listOf(auto()), identity.copy(uid = "456")))
        assertFalse(r.applySync("pc", 1, listOf(auto().copy(quality = MatchQuality.REVIEW_REQUIRED)), identity))
        assertTrue(r.state.value.records.isEmpty())
    }
    @Test fun sameEventDifferentIdAndConflictingIdRejectWholeBatch() = runTest {
        val r = repo(); assertTrue(r.applySync("pc", 1, listOf(auto()), identity))
        assertFalse(r.applySync("pc", 2, listOf(auto("different")), identity))
        assertFalse(r.applySync("pc", 3, listOf(auto("new", "pc:new"), auto().copy(kills = 4)), identity))
        assertEquals(1, r.cursor("pc")); assertEquals(listOf(auto()), r.state.value.records)
    }
    @Test fun userCorrectionRetainsSourceAndOriginalEvidenceAcrossReplayAndRestart() = runTest {
        val s = Store(); val r = repo(s); assertTrue(r.applySync("pc", 1, listOf(auto()), identity))
        val edited = auto().copy(kills = 7, quality = MatchQuality.USER_CONFIRMED, revision = 1, fieldConfidence = emptyMap())
        assertTrue(r.correct(edited)); assertTrue(r.applySync("pc", 2, listOf(auto()), identity))
        val restarted = repo(s); restarted.initialize(); assertEquals(edited, restarted.state.value.records.single())
        assertFalse(restarted.applySync("pc", 3, listOf(auto().copy(damage = 900)), identity))
        assertEquals(edited, restarted.state.value.records.single()); assertEquals(2, restarted.cursor("pc"))
    }
    @Test fun correctionCannotRelabelSourceIdentityOrEvent() = runTest {
        val r = repo(); assertTrue(r.applySync("pc", 1, listOf(auto()), identity))
        val edit = auto().copy(quality = MatchQuality.USER_CONFIRMED, revision = 1)
        assertFalse(r.correct(edit.copy(identity = null))); assertFalse(r.correct(edit.copy(evidenceEventId = "pc:other")))
        assertFalse(r.correct(edit.copy(parserVersion = "new-parser"))); assertFalse(r.correct(edit.copy(revision = 2)))
        assertEquals(auto(), r.state.value.records.single())
    }
    @Test fun deletePreservesCursorAndTombstonesButExplicitRestoreWorks() = runTest {
        val r = repo(); assertTrue(r.applySync("pc", 1, listOf(auto()), identity)); val backup = r.exportBackup()
        assertTrue(r.deleteHistory()); assertEquals(1, r.cursor("pc")); assertTrue(r.applySync("pc", 2, listOf(auto()), identity))
        assertTrue(r.state.value.records.isEmpty())
        assertTrue(r.applyImport(r.previewImport(backup, identity), identity)); assertEquals(listOf(auto()), r.state.value.records)
    }
    @Test fun backupRepeatedImportAndCorrectionsArePortable() = runTest {
        val r = repo(); assertTrue(r.applySync("pc", 1, listOf(auto()), identity))
        val edited = auto().copy(quality = MatchQuality.USER_CONFIRMED, revision = 1, rpDelta = 30)
        assertTrue(r.correct(edited)); val backup = r.exportBackup(); val s = Store(); val target = repo(s); target.initialize()
        val preview = target.previewImport(backup, identity); assertEquals(1, preview.newCount)
        assertTrue(target.applyImport(preview, identity)); val writes = s.writes
        assertTrue(target.applyImport(target.previewImport(backup, identity), identity)); assertEquals(writes, s.writes)
        assertEquals(edited, target.state.value.records.single()); assertTrue(target.applySync("pc", 1, listOf(auto()), identity))
        assertEquals(edited, target.state.value.records.single())
        assertFalse(backup.contains("cursors")); assertFalse(backup.contains("deletedIds")); assertFalse(backup.contains("certificatePin"))
    }
    @Test fun backupIdentityConflictAndWriteFailurePreserveJournal() = runTest {
        val s = Store(); val r = repo(s); assertTrue(r.saveManual(manual()))
        val foreign = MatchCodec.export(listOf(auto("auto")))
        assertFails { r.previewImport(foreign, identity.copy(platform = ApexPlatform.PLAYSTATION)) }
        val preview = r.previewImport(foreign, identity); val before = s.value; s.fail = true
        assertFalse(r.applyImport(preview, identity)); assertEquals(before, s.value); assertEquals(listOf(manual()), r.state.value.records)
    }
    @Test fun sessionAssociationRequiresActualIdentityTimeAndUserConfirmation() {
        val s = RankedSession("s", identity, ObservationSource.PROVIDER, null, now - 100, now)
        assertFalse(canAssociateMatch(auto(), s)); assertFalse(canAssociateMatch(manual(), s))
        assertTrue(canAssociateMatch(auto().copy(quality = MatchQuality.USER_CONFIRMED), s))
        assertFalse(canAssociateMatch(auto().copy(quality = MatchQuality.USER_CONFIRMED), s.copy(endedAtSeconds = null)))
        assertFalse(canAssociateMatch(auto().copy(quality = MatchQuality.USER_CONFIRMED), s.copy(identity = identity.copy(platform = ApexPlatform.XBOX))))
        assertFalse(canAssociateMatch(auto().copy(quality = MatchQuality.USER_CONFIRMED), s.copy(startedAtSeconds = now - 10)))
    }
    @Test fun capacityNeverSilentlyTrims() = runTest {
        val records = (1..MATCH_MAX_RECORDS).map { manual("m$it") }
        val s = Store(MatchCodec.encode(MatchStoreRecord(records))); val r = repo(s); r.initialize()
        assertFalse(r.saveManual(manual("overflow"))); assertEquals(MATCH_MAX_RECORDS, r.state.value.records.size); assertEquals(0, s.writes)
    }
    @Test fun screenshotConfirmationIsIndependentAndDuplicateEvidenceRejects() = runTest {
        val r = repo(); val screenshot = manual().copy(source = MatchSource.SCREENSHOT, evidenceEventId = "image:digest", parserVersion = "manual-from-image-v1")
        assertTrue(r.confirmScreenshot(screenshot)); assertFalse(r.confirmScreenshot(screenshot.copy(id = "new")))
        assertFalse(r.saveManual(screenshot)); assertEquals(1, r.state.value.records.size)
    }
}
