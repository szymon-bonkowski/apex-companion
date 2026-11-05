package io.github.szymonbonkowski.apexcompanion.appearance

import io.github.szymonbonkowski.apexcompanion.history.*
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.matches.*
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.datetime.LocalDate
import kotlin.test.*

class DashboardProjectionTest {
    private val now = 1_800_000_000L
    private val identity = HistoryIdentity("123", ApexPlatform.PC)
    private val other = HistoryIdentity("456", ApexPlatform.XBOX)
    private val unavailable = projectRotation(null, "battle_royale", now)
    private fun profile() = RankedSnapshot(PlayerIdentity("123", ApexPlatform.PC, "Example"), 13000, null, null, now - 20)
    private fun cards(state: RankedRepositoryState = RankedRepositoryState(), history: HistoryRecord? = null, matches: List<MatchRecord> = emptyList(), rotation: RotationView = unavailable, sync: MatchSyncState? = null) =
        dashboardCards(rotation, rankedView(state, now, LocalDate(2027, 1, 15)), state, history, matches, sync, now)

    @Test fun unavailableDashboardNeverInventsValuesOrTimestamps() {
        val values = cards()
        assertEquals(DashboardModule.entries.toSet(), values.keys)
        assertTrue(values.values.all { !it.available && it.observedAtSeconds == null })
        assertEquals("RP unavailable", values.getValue(DashboardModule.RANKED_PROFILE).value)
        assertEquals("Next map unknown", values.getValue(DashboardModule.UPCOMING_MAP).value)
        assertTrue(values.getValue(DashboardModule.RECENT_MATCH).details.any { "unverified" in it })
    }

    @Test fun manualRpRemainsIndependentFromRetainedProviderProfile() {
        val state = RankedRepositoryState(profile = profile(), manualRp = 8000, useManual = true, manualUpdatedAtSeconds = now - 60)
        val value = cards(state).getValue(DashboardModule.RANKED_PROFILE)
        assertEquals("8000 RP", value.value)
        assertEquals("MANUAL", value.state)
        assertEquals("User entered", value.source)
        assertEquals(now - 60, value.observedAtSeconds)
        assertFalse(value.details.any { "Example" in it })
    }

    @Test fun staleProfileAndCutoffRemainStale() {
        val state = RankedRepositoryState(profile = profile(), error = RankedError.NETWORK, predator = listOf(PredatorThreshold(ApexPlatform.PC, 20000, now - PREDATOR_FRESH_SECONDS)))
        val values = cards(state)
        assertEquals("STALE", values.getValue(DashboardModule.RANKED_PROFILE).state)
        assertEquals("STALE", values.getValue(DashboardModule.PREDATOR_THRESHOLD).state)
        assertEquals(now - PREDATOR_FRESH_SECONDS, values.getValue(DashboardModule.PREDATOR_THRESHOLD).observedAtSeconds)
    }

    @Test fun manualRpIsNeverComparedToLinkedPlayersCutoff() {
        val state = RankedRepositoryState(profile = profile(), manualRp = 25000, useManual = true, predator = listOf(PredatorThreshold(ApexPlatform.PC, 20000, now - 10)))
        val value = cards(state).getValue(DashboardModule.PREDATOR_THRESHOLD)
        assertTrue(value.available)
        assertFalse(value.details.any { "relative to" in it })
    }

    @Test fun goalWithoutCurrentRpHasNoFabricatedProgress() {
        val state = RankedRepositoryState(goal = RankGoal(16000, 12000))
        val value = cards(state).getValue(DashboardModule.GOAL_PROGRESS)
        assertEquals("16000 RP", value.value)
        assertEquals("UNAVAILABLE", value.state)
        assertTrue(value.available)
        assertNull(value.progress)
        assertFalse(value.details.any { "RP remaining" in it })
    }

    @Test fun historyProjectionIsolatesIdentitySourceAndFutureSamples() {
        val history = HistoryRecord(observations = listOf(
            RpObservation("manual", null, ObservationSource.USER_RECORDED, 9000, now - 1),
            RpObservation("own", identity, ObservationSource.PROVIDER, 13000, now - 40),
            RpObservation("foreign", other, ObservationSource.PROVIDER, 14000, now - 10),
            RpObservation("future", identity, ObservationSource.PROVIDER, 15000, now + 100),
        ))
        val provider = cards(RankedRepositoryState(profile = profile()), history).getValue(DashboardModule.RP_TREND)
        assertEquals(listOf("own"), provider.observations.map { it.id })
        assertTrue(provider.details.any { "not match results" in it })
        val manual = cards(RankedRepositoryState(profile = profile(), useManual = true), history).getValue(DashboardModule.RP_TREND)
        assertEquals(listOf("manual"), manual.observations.map { it.id })
        assertEquals("MANUAL", manual.state)
    }

    @Test fun latestMatchComesOnlyFromSelectedJournalIdentityAndKeepsUnknowns() {
        val own = MatchRecord("own", identity, timestampSeconds = now - 80)
        val foreign = MatchRecord("foreign", other, kills = 20, timestampSeconds = now - 1)
        val independent = MatchRecord("independent", kills = 10, timestampSeconds = now - 2)
        val value = cards(RankedRepositoryState(profile = profile()), matches = listOf(own, foreign, independent)).getValue(DashboardModule.RECENT_MATCH)
        assertEquals(now - 80, value.observedAtSeconds)
        assertEquals("Map unknown", value.value)
        assertTrue(value.details.any { "Kills unknown" in it && "Damage unknown" in it })
        assertTrue(value.details.any { "RP unknown" in it })
        assertEquals("MANUAL", value.state)
    }

    @Test fun observedResultQualityDoesNotBecomeLiveProviderEvidence() {
        val result = MatchRecord("review", identity, mode = MatchMode.RANKED, timestampSeconds = now - 5,
            timestampSource = MatchTimestampSource.RESULT_OBSERVED, source = MatchSource.WINDOWS_CAPTURE,
            quality = MatchQuality.REVIEW_REQUIRED, evidenceEventId = "event", parserVersion = "synthetic-test")
        val value = cards(RankedRepositoryState(profile = profile()), matches = listOf(result)).getValue(DashboardModule.RECENT_MATCH)
        assertEquals("REVIEW_REQUIRED", value.state)
        assertEquals("WINDOWS_CAPTURE", value.source)
        assertTrue(value.details.any { "REVIEW_REQUIRED" in it })
        assertNotEquals("LIVE", value.state)
    }

    @Test fun mapGapsDoNotAdvanceOrInventNextMap() {
        val cache = RotationCache(now - 100, listOf(PlaylistSchedule("battle_royale", "Pubs", listOf(MapSegment("past", "Known past", now - 200, now - 1)))))
        val values = cards(rotation = projectRotation(cache, "battle_royale", now))
        assertEquals("UNAVAILABLE", values.getValue(DashboardModule.MAP_ROTATION).state)
        assertEquals("Rotation unavailable", values.getValue(DashboardModule.MAP_ROTATION).value)
        assertEquals("Next map unknown", values.getValue(DashboardModule.UPCOMING_MAP).value)
    }

    @Test fun sessionPreviewKeepsObservationWindowAndUnknownMatchCount() {
        val own = RankedSession("own", identity, ObservationSource.PROVIDER, null, now - 100)
        val foreign = RankedSession("foreign", other, ObservationSource.PROVIDER, null, now - 1)
        val value = cards(RankedRepositoryState(profile = profile()), HistoryRecord(sessions = listOf(own, foreign))).getValue(DashboardModule.SESSION_SUMMARY)
        assertEquals("Session in progress", value.value)
        assertEquals(now - 100, value.observedAtSeconds)
        assertTrue(value.details.any { "Match count is unknown" in it })
    }

    @Test fun pairedSyncDoesNotClaimLiveGameCapture() {
        val value = cards(sync = MatchSyncState(paired = true, lastSyncSeconds = now - 10)).getValue(DashboardModule.CAPTURE_STATUS)
        assertEquals("MANUAL", value.state)
        assertEquals(now - 10, value.observedAtSeconds)
        assertTrue(value.details.any { "auto-acceptance is disabled" in it })
    }

    @Test fun futureResultsAndSessionsNeverBecomeLatestCompletedActivity() {
        val pastMatch = MatchRecord("past", identity, timestampSeconds = now - 90)
        val futureMatch = MatchRecord("future", identity, timestampSeconds = now + 90)
        val pastSession = RankedSession("past", identity, ObservationSource.PROVIDER, null, now - 200, now - 100)
        val futureSession = RankedSession("future", identity, ObservationSource.PROVIDER, null, now + 1)
        val futureEnd = RankedSession("future-end", identity, ObservationSource.PROVIDER, null, now - 10, now + 10)
        val values = cards(RankedRepositoryState(profile = profile()), HistoryRecord(sessions = listOf(pastSession, futureSession, futureEnd)), listOf(pastMatch, futureMatch))
        assertEquals(now - 90, values.getValue(DashboardModule.RECENT_MATCH).observedAtSeconds)
        assertEquals(now - 200, values.getValue(DashboardModule.SESSION_SUMMARY).observedAtSeconds)
    }

    @Test fun futureSourceTimestampsCannotBecomeLive() {
        val state = RankedRepositoryState(profile = profile().copy(observedAtSeconds = now + 10), predator = listOf(PredatorThreshold(ApexPlatform.PC, 20000, now + 10)))
        val cache = RotationCache(now + 10, listOf(PlaylistSchedule("battle_royale", "Pubs", listOf(MapSegment("known", "Source map", now - 100, now + 200)))))
        val values = cards(state, rotation = projectRotation(cache, "battle_royale", now))
        assertEquals("STALE", values.getValue(DashboardModule.MAP_ROTATION).state)
        assertEquals("STALE", values.getValue(DashboardModule.RANKED_PROFILE).state)
        assertEquals("STALE", values.getValue(DashboardModule.PREDATOR_THRESHOLD).state)
    }
}
