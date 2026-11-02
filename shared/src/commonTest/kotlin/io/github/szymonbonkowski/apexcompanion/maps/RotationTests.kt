package io.github.szymonbonkowski.apexcompanion.maps

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import kotlin.test.*

private const val NOW = 1793650000L
private fun segment(name: String = "New Unknown Map", start: Long = NOW - 100, end: Long = NOW + 100) =
    """{"map":"$name","code":"map-$name","start":$start,"end":$end,"unknownField":true}"""
private fun fixture() = """{
  "battle_royale":{"current":${segment()},"next":${segment("Next Map", NOW + 100, NOW + 200)}},
  "ranked":{"current":${segment("Ranked Map", NOW - 200, NOW + 700)}},
  "ltm":{"current":${segment("Small Map", NOW - 50, NOW + 50)},"next":null},
  "future_playlist":{"current":${segment("Future Map")}},"metadata":{"version":2}
}"""

private class TestClock(var now: Long = NOW) : RotationClock { override fun nowSeconds() = now }
private class MemoryStore(var text: String? = null, var failWrites: Boolean = false) : RotationStore {
    var writes = 0
    override suspend fun read() = text
    override suspend fun write(value: String) { if (failWrites) error("Disk full"); text = value; writes++ }
}
private class FakeProvider(var result: ProviderResult = ProviderResult.Success(fixture())) : RotationProvider {
    var calls = 0
    override suspend fun fetch(): ProviderResult { calls++; return result }
}

class RotationParsingTests {
    @Test fun explicitInactiveCurrentIsUnavailableAndActiveNextRemainsSourceBacked() {
        val current = segment("Inactive").dropLast(1) + """, "isActive":false,"eventName":"Synthetic Playlist Event"}"""
        val next = segment("Future Active", NOW + 100, NOW + 200).dropLast(1) + """, "isActive":true,"eventName":"Next Synthetic Event"}"""
        val cache = ApexRotationParser.parse("""{"ltm":{"current":$current,"next":$next}}""", NOW)
        val view = projectRotation(cache, "ltm", NOW)
        assertEquals(DataState.UNAVAILABLE, view.state)
        assertNull(view.current)
        assertEquals("Future Active", view.next?.mapName)
        assertEquals("Next Synthetic Event", view.next?.eventName)
        val future = projectRotation(cache, "ltm", NOW + 100)
        assertEquals(DataState.STALE, future.state)
        assertEquals("Future Active", future.current?.mapName)
        assertEquals(cache, RotationCacheCodec.decode(RotationCacheCodec.encode(cache)))
        val projection = RotationWidgetCodec.encode(cache, NOW)
        assertFalse(projection.contains("Inactive"))
        assertTrue(projection.contains("Next Synthetic Event"))
    }

    @Test fun inactiveFutureSegmentsNeverAppearAsCurrentOrUpcoming() {
        val inactive = segment("Inactive", NOW + 100, NOW + 200).dropLast(1) + """, "isActive":false}"""
        val cache = ApexRotationParser.parse("""{"ltm":{"current":${segment()},"next":$inactive}}""", NOW)
        assertNull(projectRotation(cache, "ltm", NOW).next)
        assertEquals(DataState.UNAVAILABLE, projectRotation(cache, "ltm", NOW + 100).state)
        assertTrue(projectRotation(cache, "ltm", NOW + 100).upcoming.isEmpty())
    }

    @Test fun malformedActivityFieldsAbstainRatherThanClaimLive() {
        listOf("\"false\"", "\"true\"", "0", "1", "null", "{}", "[]").forEach { value ->
            val malformed = segment().dropLast(1) + """, "isActive":$value}"""
            assertFails { ApexRotationParser.parse("""{"ltm":{"current":$malformed}}""", NOW) }
        }
    }

    @Test fun eventNameIsOptionalValidatedSourceMetadataAndWildcardHasReadableLabel() {
        val active = segment().dropLast(1) + """, "isActive":true,"eventName":"  Example Event  "}"""
        val cache = ApexRotationParser.parse("""{"wildcard":{"current":$active}}""", NOW)
        assertEquals("Wildcard", cache.playlists.single().label)
        assertEquals("Example Event", projectRotation(cache, "wildcard", NOW).current?.eventName)
        assertEquals(DataState.LIVE, projectRotation(cache, "wildcard", NOW).state)
        assertNull(ApexRotationParser.parse(fixture(), NOW).playlists.first().segments.first().eventName)
        val badName = segment().dropLast(1) + """, "eventName":false}"""
        assertFails { ApexRotationParser.parse("""{"ltm":{"current":$badName}}""", NOW) }
    }

    @Test fun validMultipleModesRetainIndependentSchedulesAndUnknownMapMetadata() {
        val cache = ApexRotationParser.parse(fixture(), NOW)
        assertEquals(listOf("battle_royale", "ranked", "ltm", "future_playlist"), cache.playlists.map { it.id })
        assertEquals("New Unknown Map", projectRotation(cache, "battle_royale", NOW).current?.mapName)
        assertEquals(NOW + 700, projectRotation(cache, "ranked", NOW).current?.endSeconds)
        assertNull(projectRotation(cache, "ltm", NOW).next)
        assertEquals("future playlist", cache.playlists.last().label)
    }

    @Test fun missingCurrentWithValidNextDoesNotInventCurrent() {
        val cache = ApexRotationParser.parse("""{"ranked":{"next":${segment("Next", NOW + 100, NOW + 200)}}}""", NOW)
        val view = projectRotation(cache, "ranked", NOW)
        assertEquals(DataState.UNAVAILABLE, view.state)
        assertNull(view.current)
        assertEquals("Next", view.next?.mapName)
    }

    @Test fun malformedOnePlaylistDoesNotDiscardOtherPlaylists() {
        val body = """{"ranked":{"current":{"map":"Invalid","start":"bad","end":1}},"ltm":{"current":${segment()}}}"""
        assertEquals(listOf("ltm"), ApexRotationParser.parse(body, NOW).playlists.map { it.id })
    }

    @Test fun malformedOrMissingFieldsFailClosed() {
        listOf("{}", "[]", "broken", "null", """{"ranked":{"current":{"map":"A","end":1793650100}}}""",
            """{"ranked":{"current":{"map":"","start":1793649900,"end":1793650100}}}""")
            .forEach { assertFails { ApexRotationParser.parse(it, NOW) } }
    }

    @Test fun invertedAndMillisecondTimestampsAreRejected() {
        listOf(segment("Bad", NOW + 1, NOW), segment("Bad", NOW * 1000, (NOW + 100) * 1000), segment("Bad", NOW, NOW))
            .forEach { assertFails { ApexRotationParser.parse("""{"ranked":{"current":$it}}""", NOW) } }
    }

    @Test fun overlappingPlaylistSegmentsAreRejectedWithoutGuessing() {
        assertFails { ApexRotationParser.parse("""{"ranked":{"current":${segment()},"next":${segment("Overlap", NOW + 50, NOW + 200)}}}""", NOW) }
    }

    @Test fun knownBoundariesAndUnknownNextAreExact() {
        val cache = ApexRotationParser.parse(fixture(), NOW)
        assertEquals("New Unknown Map", projectRotation(cache, "battle_royale", NOW + 99).current?.mapName)
        val boundary = projectRotation(cache, "battle_royale", NOW + 100)
        assertEquals("Next Map", boundary.current?.mapName)
        assertEquals(DataState.STALE, boundary.state) // inferred from cached schedule, not a new upstream observation
        assertNull(boundary.next)
        assertNull(projectRotation(cache, "battle_royale", NOW + 200).current)
        assertEquals(DataState.UNAVAILABLE, projectRotation(cache, "battle_royale", NOW + 200).state)
    }

    @Test fun scheduleGapsRemainUnavailable() {
        val cache = RotationCache(NOW, listOf(PlaylistSchedule("ranked", "Ranked", listOf(
            MapSegment("a", "A", NOW - 100, NOW + 100), MapSegment("b", "B", NOW + 200, NOW + 300)))))
        val view = projectRotation(cache, "ranked", NOW + 150)
        assertEquals(DataState.UNAVAILABLE, view.state)
        assertEquals("B", view.next?.mapName)
    }

    @Test fun freshnessExpiresAtFiveMinutesEvenIfCurrentMapStillValid() {
        val cache = ApexRotationParser.parse(fixture(), NOW)
        assertEquals(DataState.LIVE, projectRotation(cache, "ranked", NOW + 299).state)
        assertEquals(DataState.STALE, projectRotation(cache, "ranked", NOW + 300).state)
        assertEquals(DataState.STALE, projectRotation(cache, "ranked", NOW, RotationError.NETWORK).state)
    }

    @Test fun clockRollbackCannotClaimLive() {
        val cache = ApexRotationParser.parse(fixture(), NOW)
        assertEquals(DataState.STALE, projectRotation(cache, "ranked", NOW - 1).state)
    }

    @Test fun unsupportedModeAndEmptyCacheAreUnavailable() {
        assertEquals(DataState.UNAVAILABLE, projectRotation(null, "ranked", NOW).state)
        assertEquals(DataState.UNAVAILABLE, projectRotation(ApexRotationParser.parse(fixture(), NOW), "no_such_mode", NOW).state)
    }

    @Test fun countdownUsesAbsoluteSecondsAndNeverGoesNegative() {
        assertEquals(1L, countdownSeconds(NOW + 1, NOW))
        assertEquals(0L, countdownSeconds(NOW - 1, NOW))
        assertNull(countdownSeconds(null, NOW))
        assertEquals("1:01:01", formatCountdown(3661))
        assertEquals("0:00", formatCountdown(-1))
        assertEquals("—", formatCountdown(null))
    }

    @Test fun normalizedCacheRoundTripAndFutureSchemaRejection() {
        val cache = ApexRotationParser.parse(fixture(), NOW)
        val encoded = RotationCacheCodec.encode(cache)
        assertEquals(cache, RotationCacheCodec.decode(encoded))
        assertFails { RotationCacheCodec.decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":99")) }
        assertFalse(encoded.contains("unknownField"))
    }

    @Test fun demonstrationRemainsDemonstrationAtEveryKnownBoundary() {
        val cache = demoRotation(NOW)
        assertEquals(DataState.DEMO, projectRotation(cache, "ranked", NOW).state)
        assertEquals(DataState.DEMO, projectRotation(cache, "ranked", cache.playlists.first().segments[1].startSeconds).state)
        assertEquals(DataState.UNAVAILABLE, projectRotation(cache, "ranked", cache.playlists.first().segments.last().endSeconds).state)
        assertTrue(RotationWidgetCodec.encode(cache, NOW).contains("DEMO"))
        assertFalse(RotationWidgetCodec.encode(cache, NOW).contains("LIVE"))
    }

    @Test fun widgetTimelineIsResolvedBySharedPolicyAndIncludesExpiryFallback() {
        val json = Json.parseToJsonElement(RotationWidgetCodec.encode(ApexRotationParser.parse(fixture(), NOW), NOW)).jsonObject
        assertEquals(1, json.getValue("schemaVersion").jsonPrimitive.int)
        val pubs = json.getValue("modes").jsonArray.first().jsonObject
        val entries = pubs.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(DataState.LIVE.name, entries.first().getValue("state").jsonPrimitive.content)
        assertEquals(NOW + 100, entries.first().getValue("validUntilEpochSeconds").jsonPrimitive.long)
        assertEquals(DataState.STALE.name, entries.first { it.getValue("effectiveAtEpochSeconds").jsonPrimitive.long == NOW + 100 }.getValue("state").jsonPrimitive.content)
        assertEquals(DataState.UNAVAILABLE.name, entries.last().getValue("state").jsonPrimitive.content)
    }
}

class RotationRepositoryTests {
    @Test fun successfulInactiveResponseDoesNotResurrectPreviouslyActiveCache() = runTest {
        val clock = TestClock()
        val provider = FakeProvider()
        val repo = RotationRepository(provider, MemoryStore(), clock)
        repo.refresh()
        assertEquals(DataState.LIVE, repo.view("ltm").state)
        clock.now += 31
        val inactive = segment("Known But Inactive", NOW - 100, NOW + 500).dropLast(1) + """, "isActive":false}"""
        provider.result = ProviderResult.Success("""{"ltm":{"current":$inactive}}""")
        repo.refresh(force = true)
        assertEquals(DataState.UNAVAILABLE, repo.view("ltm").state)
        assertNull(repo.view("ltm").current)
        assertFalse(repo.state.value.cache!!.playlists.first { it.id == "ltm" }.retained)
    }

    @Test fun successfulRefreshPersistsBeforePublishingAndRestoresWithoutRequest() = runTest {
        val store = MemoryStore()
        val provider = FakeProvider()
        val clock = TestClock()
        val repo = RotationRepository(provider, store, clock)
        assertEquals(RefreshOutcome.UPDATED, repo.refresh())
        assertEquals(1, store.writes)
        assertEquals(DataState.LIVE, repo.view("ranked").state)
        assertEquals(repo.state.value.cache, RotationCacheCodec.decode(store.text!!))
        val restored = RotationRepository(provider, store, clock)
        restored.initialize()
        assertEquals(repo.state.value.cache, restored.state.value.cache)
        assertEquals(RefreshOutcome.SKIPPED, restored.refresh())
        assertEquals(1, provider.calls)
    }

    @Test fun networkFailureRetainsCachedSchedulesAndLastSuccessfulTime() = runTest {
        val clock = TestClock()
        val provider = FakeProvider()
        val repo = RotationRepository(provider, MemoryStore(), clock)
        repo.refresh()
        val old = repo.state.value.cache
        clock.now += 31
        provider.result = ProviderResult.Failure(RotationError.NETWORK)
        assertEquals(RefreshOutcome.FAILED, repo.refresh(force = true))
        assertEquals(old, repo.state.value.cache)
        assertEquals(NOW, repo.view("ranked").lastSyncedAtSeconds)
        assertEquals(DataState.STALE, repo.view("ranked").state)
    }

    @Test fun boundaryOfOneModeTriggersSyncEvenWhenAnotherModeStillLive() = runTest {
        val clock = TestClock()
        val provider = FakeProvider()
        val repo = RotationRepository(provider, MemoryStore(), clock)
        repo.refresh()
        clock.now += 51 // Mixtape expired, Ranked remains in the same source segment.
        assertEquals(DataState.LIVE, repo.view("ranked").state)
        assertEquals(RefreshOutcome.UPDATED, repo.refresh())
        assertEquals(2, provider.calls)
    }

    @Test fun rejectedModeRetainsItsOwnOldProvenanceWithoutRelabelingLive() = runTest {
        val clock = TestClock()
        val provider = FakeProvider()
        val store = MemoryStore()
        val repo = RotationRepository(provider, store, clock)
        repo.refresh()
        clock.now += 31
        provider.result = ProviderResult.Success("""{"ranked":{"current":{"map":"Broken","start":"bad"}},"ltm":{"current":${segment("New Mixtape", NOW, NOW + 500)}}}""")
        assertEquals(RefreshOutcome.UPDATED, repo.refresh(force = true))
        assertEquals(DataState.STALE, repo.view("ranked").state)
        assertEquals(NOW, repo.view("ranked").lastSyncedAtSeconds)
        assertEquals(DataState.LIVE, repo.view("ltm").state)
        assertEquals(NOW + 31, repo.view("ltm").lastSyncedAtSeconds)
        assertEquals(repo.state.value.cache, RotationCacheCodec.decode(store.text!!))
        clock.now = NOW + 701
        assertEquals(DataState.UNAVAILABLE, repo.view("ranked").state)
    }

    @Test fun noCacheAndTimeoutGiveUnavailableThenRecover() = runTest {
        val clock = TestClock()
        val provider = FakeProvider(ProviderResult.Failure(RotationError.TIMEOUT))
        val repo = RotationRepository(provider, MemoryStore(), clock)
        repo.refresh()
        assertEquals(DataState.UNAVAILABLE, repo.view("ranked").state)
        assertEquals(RotationError.TIMEOUT, repo.state.value.error)
        clock.now += 31
        provider.result = ProviderResult.Success(fixture())
        assertEquals(RefreshOutcome.UPDATED, repo.refresh())
        assertNull(repo.state.value.error)
    }

    @Test fun rateLimitHonorsRetryAfterAcrossRepositoryRestartAndManualRefresh() = runTest {
        val clock = TestClock()
        val store = MemoryStore()
        val provider = FakeProvider(ProviderResult.Failure(RotationError.RATE_LIMITED, 120))
        val repo = RotationRepository(provider, store, clock)
        repo.refresh()
        assertEquals(NOW + 120, repo.state.value.retryAtSeconds)
        clock.now += 119
        val restored = RotationRepository(provider, store, clock)
        assertEquals(RefreshOutcome.SKIPPED, restored.refresh(force = true))
        assertEquals(1, provider.calls)
        clock.now++
        restored.refresh()
        assertEquals(2, provider.calls)
        assertEquals(NOW + 240, restored.state.value.retryAtSeconds)
    }

    @Test fun forbiddenLatchesAcrossRestartUntilExplicitAccessReset() = runTest {
        val clock = TestClock()
        val store = MemoryStore()
        val provider = FakeProvider(ProviderResult.Failure(RotationError.FORBIDDEN))
        val repo = RotationRepository(provider, store, clock)
        repo.refresh()
        clock.now += 86400
        val restored = RotationRepository(provider, store, clock)
        assertEquals(RefreshOutcome.SKIPPED, restored.refresh(force = true))
        assertTrue(restored.state.value.forbidden)
        assertEquals(1, provider.calls)
        restored.resetAccessFailure()
        restored.refresh()
        assertEquals(2, provider.calls)
    }

    @Test fun serverFailuresBackOffExponentially() = runTest {
        val clock = TestClock()
        val provider = FakeProvider(ProviderResult.Failure(RotationError.SERVER))
        val repo = RotationRepository(provider, MemoryStore(), clock)
        repo.refresh()
        assertEquals(NOW + 30, repo.state.value.retryAtSeconds)
        clock.now += 30
        repo.refresh()
        assertEquals(NOW + 90, repo.state.value.retryAtSeconds)
        clock.now += 60
        repo.refresh()
        assertEquals(NOW + 210, repo.state.value.retryAtSeconds)
    }

    @Test fun tenConcurrentRefreshesAndWidgetProjectionsDoNotMultiplyRequests() = runTest {
        val clock = TestClock()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val repo = RotationRepository(RotationProvider { calls++; gate.await(); ProviderResult.Success(fixture()) }, MemoryStore(), clock)
        val requests = List(10) { async { repo.refresh(force = true) } }
        yield()
        assertEquals(1, calls)
        // Even a slow in-flight call finishing after the manual cooldown is coalesced.
        clock.now += 45
        gate.complete(Unit)
        requests.forEach { it.await() }
        repeat(10) { projectRotation(repo.state.value.cache, "ranked", clock.now) }
        assertEquals(1, calls)
    }

    @Test fun malformedSuccessPreservesLastGoodCache() = runTest {
        val clock = TestClock()
        val provider = FakeProvider()
        val repo = RotationRepository(provider, MemoryStore(), clock)
        repo.refresh()
        val cache = repo.state.value.cache
        clock.now += 31
        provider.result = ProviderResult.Success("""{"unexpected":"schema"}""")
        repo.refresh(force = true)
        assertEquals(cache, repo.state.value.cache)
        assertEquals(RotationError.SCHEMA, repo.state.value.error)
    }

    @Test fun failedPersistenceDoesNotExposeUncommittedLiveData() = runTest {
        val repo = RotationRepository(FakeProvider(), MemoryStore(failWrites = true), TestClock())
        assertEquals(RefreshOutcome.FAILED, repo.refresh())
        assertNull(repo.state.value.cache)
        assertEquals(RotationError.STORAGE, repo.state.value.error)
        assertEquals(DataState.UNAVAILABLE, repo.view("ranked").state)
    }

    @Test fun malformedPersistedStateDoesNotCrashOrCallProviderOnInitialize() = runTest {
        val provider = FakeProvider()
        val repo = RotationRepository(provider, MemoryStore("broken"), TestClock())
        repo.initialize()
        assertEquals(RotationError.STORAGE, repo.state.value.error)
        assertEquals(0, provider.calls)
        assertEquals(RefreshOutcome.UPDATED, repo.refresh())
    }

    @Test fun unconfiguredProviderCannotFabricateLiveData() = runTest {
        val repo = RotationRepository(UnconfiguredRotationProvider, MemoryStore(), TestClock())
        repo.refresh()
        assertEquals(DataState.UNAVAILABLE, repo.view("ranked").state)
        assertEquals(RotationError.ACCESS_NOT_CONFIGURED, repo.state.value.error)
    }
}
