package io.github.szymonbonkowski.apexcompanion.ranked

import io.github.szymonbonkowski.apexcompanion.network.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

private const val PROFILE = """{"global":{"uid":"123456789","name":"Fixture Player","platform":"PC","level":42,"rank":{"rankScore":13460,"rankName":"Diamond","rankDiv":3,"ladderPosPlatform":-1,"rankedSeason":"fixture-season"}}}"""
private fun profileFixture() = PROFILE
private fun lookup(byUid: Boolean = false) = ProfileLookup(if (byUid) "123456789" else "Fixture Player", ApexPlatform.PC, byUid)
private const val PREDATOR = """{"RP":{"PC":{"foundRank":750,"val":20000,"uid":"99999999","updateTimestamp":900,"totalMastersAndPreds":1700},"SWITCH":{"foundRank":400,"val":16000,"updateTimestamp":950,"totalMastersAndPreds":400}},"AP":{"PC":{"val":999999}}}"""

class RankedProviderTests {
    @Test fun observedBridgeV5ContractNormalizesOnlyNecessaryData() {
        val result = ApexRankedParser.profile(profileFixture(), lookup(), 1000)
        assertEquals(PlayerIdentity("123456789", ApexPlatform.PC, "Fixture Player"), result.identity)
        assertEquals(13460L, result.rp)
        assertEquals("Diamond", result.rankName)
        assertEquals(3, result.division)
        assertEquals(42, result.level)
        assertNull(result.ladderPosition)
        assertEquals("fixture-season", result.season)
        assertNull(result.split)
        assertEquals(1000L, result.observedAtSeconds)
    }
    @Test fun missingOrPrivateRankDoesNotBecomeZero() {
        for (rank in listOf("null", "{}", "[]", "5", "\"private\"")) {
            val body = """{"global":{"uid":"123456789","name":"Fixture Player","platform":"PC","rank":$rank}}"""
            val value = ApexRankedParser.profile(body, lookup(), 1000)
            assertNull(value.rp)
            assertNull(value.rankName)
            assertNull(value.division)
        }
    }
    @Test fun unknownRankIsRetainedWithUnknownDivision() {
        val result = ApexRankedParser.profile(profileFixture().replace("Diamond", "Future Rank").replace("\"rankDiv\":3", "\"rankDiv\":9"), lookup(), 1000)
        assertEquals("Future Rank", result.rankName)
        assertNull(result.division)
    }
    @Test fun malformedRankValuesNeverCoerceToStatistics() {
        for (rp in listOf("null", "\"13460\"", "-1", "1000000001", "1.5", "true", "{}")) {
            val result = ApexRankedParser.profile(profileFixture().replace("\"rankScore\":13460", "\"rankScore\":$rp"), lookup(), 1000)
            assertNull(result.rp)
        }
        for (division in listOf("0", "-1", "5", "\"3\"", "true", "null")) {
            assertNull(ApexRankedParser.profile(profileFixture().replace("\"rankDiv\":3", "\"rankDiv\":$division"), lookup(), 1000).division)
        }
    }
    @Test fun malformedOrMismatchedIdentityIsRejected() {
        for (body in listOf("{}", "[]", "not JSON", profileFixture().replace("123456789", "not-a-uid"),
            profileFixture().replace("\"PC\"", "\"ANDROID\""), profileFixture().replace("\"PC\"", "\"PS4\""),
            profileFixture().replace("\"uid\":\"123456789\"", "\"uid\":123456789"))) {
            assertFailsWith<IllegalArgumentException> { ApexRankedParser.profile(body, lookup(), 1000) }
        }
        assertFailsWith<IllegalArgumentException> {
            ApexRankedParser.profile(profileFixture(), ProfileLookup("987654321", ApexPlatform.PC, true), 1000)
        }
    }
    @Test fun missingNameIsUnknownAndMalformedDisplayFieldsAreDiscarded() {
        val result = ApexRankedParser.profile(profileFixture().replace("\"name\":\"Fixture Player\"", "\"name\":false")
            .replace("\"rankName\":\"Diamond\"", "\"rankName\":3"), lookup(true), 1000)
        assertEquals("", result.identity.name)
        assertNull(result.rankName)
    }
    @Test fun allSupportedPlatformCodesAreValidated() {
        for (platform in ApexPlatform.entries) {
            val body = profileFixture().replace("\"PC\"", "\"${platform.code}\"")
            assertEquals(platform, ApexRankedParser.profile(body, ProfileLookup("123456789", platform, true), 1000).identity.platform)
        }
    }
    @Test fun predatorUsesRpAndUpstreamTimestampAndCombinedPopulation() {
        val values = ApexRankedParser.predator(PREDATOR, 1000)
        assertEquals(2, values.size)
        assertEquals(PredatorThreshold(ApexPlatform.PC, 20000, 900, 1700), values.first())
        assertEquals(16000L, values.last().rp)
        assertTrue(values.none { it.rp == 999999L })
    }
    @Test fun predatorMissingMalformedOrFutureEntriesAreRejectedIndividually() {
        val partial = PREDATOR.replace("\"val\":20000", "\"val\":-1")
        assertEquals(listOf(ApexPlatform.SWITCH), ApexRankedParser.predator(partial, 1000).map { it.platform })
        for (body in listOf("{}", "{\"RP\":{}}", PREDATOR.replace("\"updateTimestamp\":900", "\"updateTimestamp\":2000")
            .replace("\"updateTimestamp\":950", "\"updateTimestamp\":2000"))) {
            assertFailsWith<IllegalArgumentException> { ApexRankedParser.predator(body, 1000) }
        }
    }
    @Test fun nameAndUidRequestsUseV5AndHeaderAuthentication() = runTest {
        for (byUid in listOf(false, true)) {
            val client = rotationHttpClient(MockEngine { request ->
                assertEquals("api.apexlegendsstatus.com", request.url.host)
                assertEquals("/bridge", request.url.encodedPath)
                assertEquals("5", request.url.parameters["version"])
                assertEquals("PC", request.url.parameters["platform"])
                assertEquals(lookup(byUid).value, request.url.parameters[if (byUid) "uid" else "player"])
                assertNull(request.url.parameters[if (byUid) "player" else "uid"])
                assertNull(request.url.parameters["auth"])
                assertNull(request.url.parameters["history"])
                assertEquals("fixture-placeholder", request.headers[HttpHeaders.Authorization])
                respond(profileFixture())
            })
            try { assertIs<RankedResult.Success<RankedSnapshot>>(ApexStatusRankedProvider(client,
                RotationAccess.AuthorizedDeveloper { "fixture-placeholder" }, { 1000 }).profile(lookup(byUid))) }
            finally { client.close() }
        }
    }
    @Test fun switchNameIsUnsupportedWithoutRequest() = runTest {
        val client = rotationHttpClient(MockEngine { error("must not request") })
        try { assertEquals(RankedResult.Failure(RankedError.UNSUPPORTED_PLATFORM), ApexStatusRankedProvider(client,
            RotationAccess.AuthorizedDeveloper { "fixture-placeholder" }).profile(ProfileLookup("fixture", ApexPlatform.SWITCH, false))) }
        finally { client.close() }
    }
    @Test fun predatorRequestsOnlyDocumentedEndpoint() = runTest {
        val client = rotationHttpClient(MockEngine { request ->
            assertEquals("/predator", request.url.encodedPath)
            assertTrue(request.url.parameters.isEmpty())
            respond(PREDATOR)
        })
        try { assertIs<RankedResult.Success<List<PredatorThreshold>>>(ApexStatusRankedProvider(client,
            RotationAccess.AuthorizedDeveloper { "fixture-placeholder" }, { 1000 }).predator()) }
        finally { client.close() }
    }
    @Test fun errorsAreTypedWithoutExposingFailureBodies() = runTest {
        for ((code, expected) in listOf(401 to RankedError.FORBIDDEN, 403 to RankedError.FORBIDDEN,
            404 to RankedError.NOT_FOUND, 410 to RankedError.UNSUPPORTED_PLATFORM, 429 to RankedError.RATE_LIMITED,
            500 to RankedError.SERVER, 405 to RankedError.SERVER, 400 to RankedError.SERVER, 302 to RankedError.SCHEMA)) {
            val client = rotationHttpClient(MockEngine { respond("private body", HttpStatusCode.fromValue(code), headersOf(HttpHeaders.RetryAfter,"100")) })
            try {
                val result = assertIs<RankedResult.Failure>(ApexStatusRankedProvider(client,
                    RotationAccess.AuthorizedDeveloper { "fixture-placeholder" }).profile(lookup()))
                assertEquals(expected, result.error)
                if (code == 429 || code == 500) assertEquals(100L, result.retryAfterSeconds)
            } finally { client.close() }
        }
    }
    @Test fun retryAfterDateIsSupported() = runTest {
        val client = rotationHttpClient(MockEngine { respond("", HttpStatusCode.TooManyRequests,
            headersOf(HttpHeaders.RetryAfter, "Thu, 01 Jan 1970 00:20:00 GMT")) })
        try {
            assertEquals(200L, assertIs<RankedResult.Failure>(ApexStatusRankedProvider(client,
                RotationAccess.AuthorizedDeveloper { "fixture-placeholder" }, {1000}).predator()).retryAfterSeconds)
        } finally { client.close() }
    }
    @Test fun invalidOversizedAndMalformedBodiesAreSafeSchemaFailures() = runTest {
        for (body in listOf("not json", "{}", "x".repeat(524_289))) {
            val client = rotationHttpClient(MockEngine { respond(body) })
            try { assertEquals(RankedError.SCHEMA, assertIs<RankedResult.Failure>(ApexStatusRankedProvider(client,
                RotationAccess.AuthorizedDeveloper { "fixture-placeholder" }).profile(lookup())).error) }
            finally { client.close() }
        }
    }
    @Test fun timeoutIsDistinctFromNetworkFailure() = runTest {
        val client = rotationHttpClient(MockEngine { request -> throw HttpRequestTimeoutException(request) })
        try { assertEquals(RankedResult.Failure(RankedError.TIMEOUT), ApexStatusRankedProvider(client,
            RotationAccess.AuthorizedDeveloper { "fixture-placeholder" }).predator()) }
        finally { client.close() }
    }
    @Test fun noKeyOrMapOnlyGatewayCannotActivateRanked() = runTest {
        val client = rotationHttpClient(MockEngine { error("must not request") })
        try {
            for (route in listOf(RotationAccess.AuthorizedDeveloper { "" }, RotationAccess.ApprovedGateway("https://fixture.invalid/maps", "fixture"))) {
                assertEquals(RankedResult.Failure(RankedError.ACCESS_NOT_CONFIGURED), ApexStatusRankedProvider(client, route).predator())
            }
        } finally { client.close() }
    }
    @Test fun cancellationPropagatesAndOtherExceptionsAreRedacted() = runTest {
        val cancelled = rotationHttpClient(MockEngine { throw CancellationException("fixture") })
        try { assertFailsWith<CancellationException> { ApexStatusRankedProvider(cancelled,
            RotationAccess.AuthorizedDeveloper { "fixture-placeholder" }).profile(lookup()) } }
        finally { cancelled.close() }
        val failed = rotationHttpClient(MockEngine { error("sensitive diagnostic") })
        try { assertEquals(RankedResult.Failure(RankedError.NETWORK), ApexStatusRankedProvider(failed,
            RotationAccess.AuthorizedDeveloper { "fixture-placeholder" }).predator()) }
        finally { failed.close() }
    }
}
