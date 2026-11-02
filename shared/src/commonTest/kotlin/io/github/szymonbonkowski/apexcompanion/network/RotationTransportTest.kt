package io.github.szymonbonkowski.apexcompanion.network

import io.github.szymonbonkowski.apexcompanion.maps.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import io.github.szymonbonkowski.apexcompanion.formatRotationTime
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class RotationTransportTest {
    @Test fun nonStringMapFieldsCannotBecomeInventedMapNames() {
        for (map in listOf("7", "true", "null", "{}", "[]")) {
            assertFailsWith<IllegalArgumentException> {
                ApexRotationParser.parse("""{"ranked":{"current":{"map":$map,"start":1793620740,"end":1793620920}}}""", 1793620800)
            }
        }
    }
    @Test fun documentedEndpointUsesV2AndHeaderOnly() = runTest {
        val engine = MockEngine { request ->
            assertEquals("api.apexlegendsstatus.com", request.url.host)
            assertEquals("/maprotation", request.url.encodedPath)
            assertEquals("2", request.url.parameters["version"])
            assertNull(request.url.parameters["auth"])
            assertEquals("synthetic-test-placeholder", request.headers[HttpHeaders.Authorization])
            respond("{\"ranked\":{}}", HttpStatusCode.OK)
        }
        val client = rotationHttpClient(engine)
        try {
            val result = ApexStatusRotationProvider(client, RotationAccess.AuthorizedDeveloper { "synthetic-test-placeholder" }).fetch()
            assertIs<ProviderResult.Success>(result)
        } finally { client.close() }
    }
    @Test fun gatewaySendsNoUpstreamCredential() = runTest {
        val client = rotationHttpClient(MockEngine { request ->
            assertEquals("approved.invalid", request.url.host)
            assertNull(request.headers[HttpHeaders.Authorization])
            respond("{}")
        })
        try { assertIs<ProviderResult.Success>(ApexStatusRotationProvider(client,
            RotationAccess.ApprovedGateway("https://approved.invalid/maps", "synthetic-test-only")).fetch()) }
        finally { client.close() }
    }
    @Test fun unsafeGatewayConfigurationRejected() {
        for (url in listOf("http://host.invalid/maps", "https://user:secret@host.invalid/maps", "https://host.invalid/maps?auth=secret")) {
            assertFailsWith<IllegalArgumentException> { RotationAccess.ApprovedGateway(url, "test") }
        }
        assertFailsWith<IllegalArgumentException> { RotationAccess.ApprovedGateway("https://host.invalid", "") }
    }
    @Test fun noConfigurationMakesNoNetworkRequest() = runTest {
        assertEquals(RotationError.ACCESS_NOT_CONFIGURED, (UnconfiguredRotationProvider.fetch() as ProviderResult.Failure).error)
    }
    @Test fun httpErrorsAndRetryAfterAreSafe() = runTest {
        for ((status, error) in listOf(403 to RotationError.FORBIDDEN, 401 to RotationError.FORBIDDEN,
            429 to RotationError.RATE_LIMITED, 503 to RotationError.SERVER, 302 to RotationError.SCHEMA, 404 to RotationError.SCHEMA)) {
            var calls = 0
            val client = rotationHttpClient(MockEngine {
                calls++
                respond("sensitive body never exposed", HttpStatusCode.fromValue(status), headersOf(HttpHeaders.RetryAfter, "123"))
            })
            try {
                val result = ApexStatusRotationProvider(client, RotationAccess.AuthorizedDeveloper { "placeholder" }).fetch()
                assertEquals(error, assertIs<ProviderResult.Failure>(result).error)
                if (status == 429 || status == 503) assertEquals(123, result.retryAfterSeconds)
                assertEquals(1, calls)
            } finally { client.close() }
        }
    }
    @Test fun retryAfterHttpDateSupported() = runTest {
        val client = rotationHttpClient(MockEngine { respond("", HttpStatusCode.TooManyRequests,
            headersOf(HttpHeaders.RetryAfter, "Mon, 02 Nov 2026 12:02:00 GMT")) })
        try {
            val now = Instant.parse("2026-11-02T12:00:00Z").epochSeconds
            val result = ApexStatusRotationProvider(client, RotationAccess.AuthorizedDeveloper { "placeholder" }, { now }).fetch()
            assertEquals(120, assertIs<ProviderResult.Failure>(result).retryAfterSeconds)
        } finally { client.close() }
    }
    @Test fun oversizedSuccessRejected() = runTest {
        val client = rotationHttpClient(MockEngine { respond("x".repeat(524_289)) })
        try { assertEquals(RotationError.SCHEMA, assertIs<ProviderResult.Failure>(ApexStatusRotationProvider(client,
            RotationAccess.AuthorizedDeveloper { "placeholder" }).fetch()).error) }
        finally { client.close() }
    }
    @Test fun cancellationIsPropagated() = runTest {
        val client = rotationHttpClient(MockEngine { throw CancellationException("stop") })
        try { assertFailsWith<CancellationException> { ApexStatusRotationProvider(client,
            RotationAccess.AuthorizedDeveloper { "placeholder" }).fetch() } }
        finally { client.close() }
    }
    @Test fun networkFailureDoesNotExposeExceptionText() = runTest {
        val client = rotationHttpClient(MockEngine { error("private diagnostic") })
        try { assertEquals(ProviderResult.Failure(RotationError.NETWORK), ApexStatusRotationProvider(client,
            RotationAccess.AuthorizedDeveloper { "placeholder" }).fetch()) }
        finally { client.close() }
    }
    @Test fun timezoneFormattingChangesDisplayOnlyAcrossDst() {
        val epoch = Instant.parse("2026-10-25T01:30:00Z").epochSeconds
        assertEquals("2026-10-25 01:30", formatRotationTime(epoch, TimeZone.UTC))
        assertEquals("2026-10-25 02:30", formatRotationTime(epoch, TimeZone.of("Europe/Warsaw")))
        assertEquals("2026-10-24 21:30", formatRotationTime(epoch, TimeZone.of("America/New_York")))
        assertEquals(epoch, Instant.parse("2026-10-25T01:30:00Z").epochSeconds)
    }
}
