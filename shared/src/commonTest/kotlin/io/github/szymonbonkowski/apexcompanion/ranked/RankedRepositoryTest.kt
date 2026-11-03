package io.github.szymonbonkowski.apexcompanion.ranked

import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.network.ProviderRequestBudget
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.*
import kotlin.time.Instant
import kotlin.test.*

private class MemoryStore: RotationStore {
    var value: String? = null
    var fail = false
    override suspend fun read() = value
    override suspend fun write(value: String) { check(!fail); this.value = value }
}
private class FixtureProvider: RankedProvider {
    var calls = 0; var predatorCalls = 0
    var lookup: ProfileLookup? = null
    var result: RankedResult<RankedSnapshot> = RankedResult.Success(snapshot())
    var cutoff: RankedResult<List<PredatorThreshold>> = RankedResult.Success(listOf(PredatorThreshold(ApexPlatform.PC, 16000, 1000, 100)))
    override suspend fun profile(lookup: ProfileLookup): RankedResult<RankedSnapshot> { calls++; this.lookup = lookup; return result }
    override suspend fun predator(): RankedResult<List<PredatorThreshold>> { predatorCalls++; return cutoff }
}
private fun snapshot(uid: String = "123", rp: Long? = 13460, at: Long = 1000) = RankedSnapshot(PlayerIdentity(uid, ApexPlatform.PC, "Fixture Player"), rp, "FixtureRank", null, at)
private val date = LocalDate(2026, 11, 2)
private val lookup = ProfileLookup("Fixture Player", ApexPlatform.PC, false)

class RankedRepositoryTest {
    @Test fun goalArithmeticAndDeadlineCases() {
        val goal = RankGoal(16000, 13460, LocalDate(2026, 11, 24))
        val p = calculateGoal(goal, 13460, date)
        assertEquals(2540, p.remainingRp); assertEquals(116, p.rpPerDay); assertEquals(22, p.daysRemaining)
        assertEquals(0f, p.fraction)
        assertEquals(GoalStatus.EXPIRED, calculateGoal(goal.copy(deadline = date), 14000, date).status)
        assertNull(calculateGoal(goal.copy(deadline = date), 14000, date).rpPerDay)
        assertEquals(GoalStatus.EXPIRED, calculateGoal(goal.copy(deadline = LocalDate(2026, 10, 1)), 14000, date).status)
        assertEquals(GoalStatus.ACHIEVED, calculateGoal(goal, 17000, date).status)
        assertEquals(1f, calculateGoal(goal, 17000, date).fraction)
        assertEquals(0, calculateGoal(goal, 17000, date).remainingRp)
        assertEquals(GoalStatus.COMPLETED, calculateGoal(goal.copy(completed = true), 14000, date).status)
        assertNull(calculateGoal(goal.copy(completed = true), 14000, date).rpPerDay)
        assertEquals(GoalStatus.NO_CURRENT, calculateGoal(goal, null, date).status)
        assertNull(calculateGoal(goal.copy(deadline = null), 14000, date).rpPerDay)
        assertEquals(0f, calculateGoal(goal, 1000, date).fraction)
        assertFailsWith<IllegalArgumentException> { RankGoal(-1, 0) }
        assertFailsWith<IllegalArgumentException> { RankGoal(MAX_RP + 1, 0) }
    }
    @Test fun localCalendarBoundariesRespectDstAndTimezone() {
        val instant = Instant.parse("2026-03-29T22:30:00Z")
        val local = instant.toLocalDateTime(TimeZone.of("Europe/Warsaw")).date
        assertEquals(LocalDate(2026, 3, 30), local)
        assertEquals(1, calculateGoal(RankGoal(100, 0, LocalDate(2026, 3, 31)), 0, local).daysRemaining)
        val west = instant.toLocalDateTime(TimeZone.of("America/Los_Angeles")).date
        assertEquals(2, calculateGoal(RankGoal(100, 0, LocalDate(2026, 3, 31)), 0, west).daysRemaining)
    }
    @Test fun nameConnectionResolvesUidAndRefreshUsesIt() = runTest {
        var now = 1000L; val provider = FixtureProvider(); val repo = RankedRepository(provider, MemoryStore(), RotationClock { now })
        assertEquals(RefreshOutcome.UPDATED, repo.connect(lookup))
        assertEquals("123", repo.state.value.profile?.identity?.uid)
        now += 301; repo.refresh(); assertEquals(ProfileLookup("123", ApexPlatform.PC, true), provider.lookup)
    }
    @Test fun failedLookupRetainsExistingIdentityAndGoal() = runTest {
        var now = 1000L; val provider = FixtureProvider(); val repo = RankedRepository(provider, MemoryStore(), RotationClock { now })
        repo.saveGoal(RankGoal(16000, 10000)); repo.connect(lookup); now += 31
        provider.result = RankedResult.Failure(RankedError.NOT_FOUND)
        repo.connect(lookup.copy(value = "Missing Fixture"))
        assertEquals("123", repo.state.value.profile?.identity?.uid)
        assertEquals(16000, repo.state.value.goal?.targetRp)
        assertEquals(DataState.STALE, repo.view(now, date).state)
    }
    @Test fun successfulProfileChangeReplacesOnlyPrivateSnapshot() = runTest {
        var now = 1000L; val provider = FixtureProvider(); val repo = RankedRepository(provider, MemoryStore(), RotationClock { now })
        repo.setManualRp(9000); repo.saveGoal(RankGoal(16000, 9000)); repo.connect(lookup); now += 31
        provider.result = RankedResult.Success(snapshot("456"))
        repo.connect(lookup.copy(value = "Other Fixture"))
        assertEquals("456", repo.state.value.profile?.identity?.uid)
        assertEquals(9000, repo.state.value.manualRp); assertEquals(16000, repo.state.value.goal?.targetRp)
    }
    @Test fun mismatchUidAndPlatformAreSchemaFailures() = runTest {
        val repo = RankedRepository(FixtureProvider(), MemoryStore(), RotationClock { 1000 })
        repo.connect(ProfileLookup("456", ApexPlatform.PC, true))
        assertNull(repo.state.value.profile); assertEquals(RankedError.SCHEMA, repo.state.value.error)
    }
    @Test fun manualToLiveAndExplicitFallbackRetainsManualValue() = runTest {
        val repo = RankedRepository(FixtureProvider(), MemoryStore(), RotationClock { 1000 })
        repo.setManualRp(12000); repo.setUseManual(true)
        assertEquals(DataState.MANUAL, repo.view(1000, date).state)
        repo.connect(lookup)
        assertEquals(13460, repo.view(1000, date).currentRp)
        assertEquals(DataState.LIVE, repo.view(1000, date).state)
        repo.setUseManual(true)
        assertEquals(12000, repo.view(1000, date).currentRp)
        assertEquals(DataState.MANUAL, repo.view(1000, date).state)
    }
    @Test fun disconnectRemovesPrivateIdentityButPreservesIndependentGoalAndGlobalCutoff() = runTest {
        val store = MemoryStore(); val global = MemoryStore(); val provider = FixtureProvider()
        val repo = RankedRepository(provider, store, RotationClock { 1000 }, global)
        repo.setManualRp(9000); repo.saveGoal(RankGoal(16000, 9000)); repo.connect(lookup); repo.refreshPredator(); repo.disconnect()
        assertFalse(store.value!!.contains("Fixture Player")); assertFalse(store.value!!.contains("123"))
        assertFalse(global.value!!.contains("Fixture Player"))
        val restored = RankedRepository(provider, store, RotationClock { 1000 }, global); restored.initialize()
        assertNull(restored.state.value.profile); assertEquals(1, restored.state.value.predator.size)
        assertEquals(9000, restored.view(1000, date).currentRp)
        assertEquals(16000, restored.state.value.goal?.targetRp)
    }
    @Test fun latestSnapshotRestoresWithoutNetwork() = runTest {
        val store = MemoryStore(); val provider = FixtureProvider()
        val repo = RankedRepository(provider, store, RotationClock { 1000 }); repo.connect(lookup)
        val reopened = RankedRepository(provider, store, RotationClock { 1100 }); reopened.initialize()
        assertEquals(DataState.LIVE, reopened.view(1100, date).state); assertEquals(1, provider.calls)
        assertEquals(DataState.STALE, reopened.view(1300, date).state)
        assertEquals(DataState.STALE, reopened.view(999, date).state)
    }
    @Test fun unknownRpIsNotZeroOrManualSubstitution() = runTest {
        val provider = FixtureProvider(); provider.result = RankedResult.Success(snapshot(rp = null))
        val repo = RankedRepository(provider, MemoryStore(), RotationClock { 1000 }); repo.setManualRp(5000); repo.connect(lookup)
        assertNull(repo.view(1000, date).currentRp); assertEquals(DataState.UNAVAILABLE, repo.view(1000, date).state)
    }
    @Test fun networkFailureRetainsCacheAndBackoffAcrossRestart() = runTest {
        var now = 1000L; val provider = FixtureProvider(); val store = MemoryStore()
        val repo = RankedRepository(provider, store, RotationClock { now }); repo.connect(lookup); now += 301
        provider.result = RankedResult.Failure(RankedError.NETWORK); repo.refresh()
        val restored = RankedRepository(provider, store, RotationClock { now }); restored.initialize(); restored.refresh(true)
        assertEquals(2, provider.calls); assertEquals(DataState.STALE, restored.view(now, date).state)
        assertEquals(13460, restored.view(now, date).currentRp)
    }
    @Test fun concurrentRefreshesAndForcedTapsAreDeduplicated() = runTest {
        val provider = FixtureProvider(); val repo = RankedRepository(provider, MemoryStore(), RotationClock { 1000 })
        (1..10).map { async { repo.connect(lookup) } }.awaitAll()
        (1..10).map { async { repo.refresh(true) } }.awaitAll()
        assertEquals(1, provider.calls)
    }
    @Test fun forbiddenAndRateLimitsPersist() = runTest {
        var now = 1000L; val provider = FixtureProvider(); val store = MemoryStore()
        provider.result = RankedResult.Failure(RankedError.FORBIDDEN)
        val repo = RankedRepository(provider, store, RotationClock { now }); repo.connect(lookup); now += 9999
        val restored = RankedRepository(provider, store, RotationClock { now }); restored.initialize(); restored.connect(lookup)
        assertEquals(1, provider.calls); assertTrue(restored.state.value.forbidden)
        restored.resetAccessFailure(); provider.result = RankedResult.Failure(RankedError.RATE_LIMITED, 3600); restored.connect(lookup)
        assertEquals(now + 3600, restored.state.value.retryAtSeconds)
    }
    @Test fun corruptPrivateRecordDoesNotEraseValidGlobalCache() = runTest {
        val store = MemoryStore(); store.value = "{truncated"
        val global = MemoryStore(); global.value = RankedRecordCodec.encodePredator(RankedRepositoryState(predator = listOf(PredatorThreshold(ApexPlatform.PC, 15000, 1000))))
        val repo = RankedRepository(FixtureProvider(), store, RotationClock { 1000 }, global); repo.initialize()
        assertEquals(RankedError.STORAGE, repo.state.value.error); assertEquals(1, repo.state.value.predator.size)
        assertEquals("{truncated", store.value)
    }
    @Test fun failedPersistenceDoesNotExposeUncommittedProfileOrGoal() = runTest {
        val store = MemoryStore(); store.fail = true
        val repo = RankedRepository(FixtureProvider(), store, RotationClock { 1000 })
        assertFalse(repo.saveGoal(RankGoal(15000, 0))); assertNull(repo.state.value.goal)
        repo.connect(lookup); assertNull(repo.state.value.profile); assertEquals(RankedError.STORAGE, repo.state.value.error)
    }
    @Test fun predatorCacheRetainedOnFailureAndDeduplicated() = runTest {
        var now = 1000L; val provider = FixtureProvider(); val store = MemoryStore(); val global = MemoryStore()
        val repo = RankedRepository(provider, store, RotationClock { now }, global)
        (1..10).map { async { repo.refreshPredator() } }.awaitAll(); assertEquals(1, provider.predatorCalls)
        now += 1801; provider.cutoff = RankedResult.Failure(RankedError.SERVER); repo.refreshPredator()
        val restored = RankedRepository(provider, store, RotationClock { now }, global); restored.initialize()
        assertEquals(16000, restored.state.value.predator.single().rp); assertEquals(RankedError.SERVER, restored.state.value.predatorError)
    }
    @Test fun schemaVersionAndRpCorruptionRejected() {
        assertFails { RankedRecordCodec.decode("""{"schemaVersion":2}""") }
        assertFails { RankedRecordCodec.decode("""{"schemaVersion":1,"manualRp":-1}""") }
        assertFails { RankedRecordCodec.decode("""{"schemaVersion":1,"manualRp":"12000"}""") }
        assertFails { RankedRecordCodec.decode("""{"schemaVersion":1,"manualRp":false}""") }
        assertFails { RankedRecordCodec.decode("""{"schemaVersion":1,"useManual":"true"}""") }
        assertFails { RankedRecordCodec.decode("""{"schemaVersion":1,"useManual":123}""") }
    }
    @Test fun crossEndpointRateBudgetBlocksMapsAndPredatorAfterProfile429() = runTest {
        var now = 1000L; val provider = FixtureProvider(); provider.result = RankedResult.Failure(RankedError.RATE_LIMITED, 120)
        val budget = ProviderRequestBudget(RotationClock { now }, pause = {})
        budget.ranked(provider).profile(lookup)
        assertEquals(RankedError.RATE_LIMITED, (budget.ranked(provider).predator() as RankedResult.Failure).error)
        var mapCalls = 0
        val maps = budget.rotation(RotationProvider { mapCalls++; ProviderResult.Failure(RotationError.NETWORK) })
        assertEquals(RotationError.RATE_LIMITED, (maps.fetch() as ProviderResult.Failure).error)
        assertEquals(0, provider.predatorCalls); assertEquals(0, mapCalls)
        now += 121; maps.fetch(); assertEquals(1, mapCalls)
    }
    @Test fun seasonalOrSplitResetOnlyReplacesCurrentStateNeverGoal() = runTest {
        var now = 1000L; val provider = FixtureProvider(); val repo = RankedRepository(provider, MemoryStore(), RotationClock { now })
        repo.saveGoal(RankGoal(16000, 13460)); repo.connect(lookup); now += 301
        provider.result = RankedResult.Success(snapshot(rp = 5000, at = now).copy(season = "new-season", split = "new-split")); repo.refresh()
        assertEquals(5000, repo.view(now, date).currentRp); assertEquals(13460, repo.state.value.goal?.baselineRp)
        assertEquals(0f, repo.view(now, date).progress?.fraction)
    }
    @Test fun accessRevisionResetPersistsBothEndpointLatchesAcrossRestart() = runTest {
        val privateStore = MemoryStore(); val globalStore = MemoryStore(); val provider = FixtureProvider()
        provider.result = RankedResult.Failure(RankedError.FORBIDDEN)
        provider.cutoff = RankedResult.Failure(RankedError.FORBIDDEN)
        val repo = RankedRepository(provider, privateStore, RotationClock { 1000 }, globalStore)
        repo.connect(lookup); repo.refreshPredator()
        assertTrue(repo.state.value.forbidden); assertTrue(repo.state.value.predatorForbidden)
        assertTrue(repo.resetAccessFailure())
        val reopened = RankedRepository(provider, privateStore, RotationClock { 1000 }, globalStore)
        reopened.initialize()
        assertFalse(reopened.state.value.forbidden); assertFalse(reopened.state.value.predatorForbidden)
        assertNull(reopened.state.value.predatorError); assertNull(reopened.state.value.predatorRetryAtSeconds)
        assertNull(reopened.state.value.error); assertNull(reopened.state.value.retryAtSeconds)
    }
    @Test fun partialPredatorResponseRetainsMissingPlatformAsStale() = runTest {
        var now = 1000L; val provider = FixtureProvider()
        provider.cutoff = RankedResult.Success(listOf(PredatorThreshold(ApexPlatform.PC, 16000, now), PredatorThreshold(ApexPlatform.XBOX, 15000, now)))
        val repo = RankedRepository(provider, MemoryStore(), RotationClock { now }, MemoryStore())
        repo.refreshPredator(); now += 1801
        provider.cutoff = RankedResult.Success(listOf(PredatorThreshold(ApexPlatform.PC, 17000, now)))
        repo.refreshPredator()
        assertEquals(15000, repo.state.value.predator.first { it.platform == ApexPlatform.XBOX }.rp)
        assertEquals(1000, repo.state.value.predator.first { it.platform == ApexPlatform.XBOX }.observedAtSeconds)
        assertEquals(RankedError.SCHEMA, repo.state.value.predatorError)
    }

}
