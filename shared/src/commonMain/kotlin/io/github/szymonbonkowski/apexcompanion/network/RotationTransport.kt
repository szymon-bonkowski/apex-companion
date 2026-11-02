package io.github.szymonbonkowski.apexcompanion.network

import io.github.szymonbonkowski.apexcompanion.maps.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.io.readByteArray
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/** Only the maintainer can supply this after the separately documented provider gate. */
sealed interface RotationAccess {
    class AuthorizedDeveloper(val authorization: suspend () -> String) : RotationAccess
    class ApprovedGateway(val url: String, val permissionReference: String) : RotationAccess {
        init {
            val parsed = Url(url)
            require(parsed.protocol == URLProtocol.HTTPS && parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty())
            require(parsed.parameters.isEmpty() && parsed.fragment.isEmpty())
            require(permissionReference.isNotBlank())
        }
    }
}

/** No logging plugin or redirects: upstream credentials never follow a redirect. */
fun rotationHttpClient(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout) {
        requestTimeoutMillis = 15_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 15_000
    }
}

class ApexStatusRotationProvider(
    private val client: HttpClient,
    private val access: RotationAccess,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : RotationProvider {
    override suspend fun fetch(): ProviderResult = try {
        when (val route = access) {
            is RotationAccess.AuthorizedDeveloper -> {
                val secret = route.authorization()
                if (secret.isBlank()) return ProviderResult.Failure(RotationError.ACCESS_NOT_CONFIGURED)
                client.prepareGet("https://api.apexlegendsstatus.com/maprotation") {
                    parameter("version", "2")
                    header(HttpHeaders.Authorization, secret)
                    header(HttpHeaders.UserAgent, "ApexCompanion/0.1")
                }.execute { response -> readResponse(response) }
            }
            is RotationAccess.ApprovedGateway -> client.prepareGet(route.url) {
                header(HttpHeaders.UserAgent, "ApexCompanion/0.1")
            }.execute { response -> readResponse(response) }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestTimeoutException) {
        ProviderResult.Failure(RotationError.TIMEOUT)
    } catch (_: ConnectTimeoutException) {
        ProviderResult.Failure(RotationError.TIMEOUT)
    } catch (_: SocketTimeoutException) {
        ProviderResult.Failure(RotationError.TIMEOUT)
    } catch (_: Exception) {
        // Exception text can contain request URLs/headers. Only expose a fixed safe error.
        ProviderResult.Failure(RotationError.NETWORK)
    }

    private suspend fun readResponse(response: HttpResponse): ProviderResult =
        when (response.status.value) {
            200 -> {
                // Bound both declared and streamed sizes; do not cache/log raw failure bodies.
                if ((response.contentLength() ?: 0L) > MAX_BODY_BYTES) {
                    response.bodyAsChannel().cancel()
                    ProviderResult.Failure(RotationError.SCHEMA)
                } else {
                    val channel = response.bodyAsChannel()
                    val bytes = channel.readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
                    channel.cancel()
                    if (bytes.size > MAX_BODY_BYTES) ProviderResult.Failure(RotationError.SCHEMA)
                    else runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }
                        .fold({ ProviderResult.Success(it) }, { ProviderResult.Failure(RotationError.SCHEMA) })
                }
            }
            401, 403 -> ProviderResult.Failure(RotationError.FORBIDDEN)
            429 -> ProviderResult.Failure(RotationError.RATE_LIMITED, retryAfterSeconds(response.headers[HttpHeaders.RetryAfter]))
            in 500..599 -> ProviderResult.Failure(RotationError.SERVER, retryAfterSeconds(response.headers[HttpHeaders.RetryAfter]))
            else -> ProviderResult.Failure(RotationError.SCHEMA)
        }


    private fun retryAfterSeconds(value: String?): Long? {
        if (value == null) return null
        value.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
        return runCatching { (value.fromHttpToGmtDate().timestamp / 1000 - nowSeconds()).coerceIn(0, 86_400) }.getOrNull()
    }

    companion object { private const val MAX_BODY_BYTES = 524_288 }
}
