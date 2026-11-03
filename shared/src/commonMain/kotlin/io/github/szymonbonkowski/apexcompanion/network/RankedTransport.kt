package io.github.szymonbonkowski.apexcompanion.network

import io.github.szymonbonkowski.apexcompanion.ranked.*
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlin.time.Clock

/** Authorized local developer route only; public hosts retain their unconfigured provider. */
class ApexStatusRankedProvider(
    private val client: HttpClient,
    private val access: RotationAccess,
    private val nowSeconds: () -> Long = { Clock.System.now().epochSeconds },
) : RankedProvider {
    override suspend fun profile(lookup: ProfileLookup): RankedResult<RankedSnapshot> {
        if (lookup.platform == ApexPlatform.SWITCH && !lookup.byUid)
            return RankedResult.Failure(RankedError.UNSUPPORTED_PLATFORM)
        return request("bridge", {
            parameter("version", "5")
            parameter("platform", lookup.platform.code)
            parameter(if (lookup.byUid) "uid" else "player", lookup.value)
        }) { ApexRankedParser.profile(it, lookup, nowSeconds()) }
    }

    override suspend fun predator(): RankedResult<List<PredatorThreshold>> = request("predator") {
        ApexRankedParser.predator(it, nowSeconds())
    }

    private suspend fun <T> request(
        endpoint: String,
        parameters: HttpRequestBuilder.() -> Unit = {},
        parse: (String) -> T,
    ): RankedResult<T> = try {
        // An approved map gateway URL is not authorization to append arbitrary profile routes.
        val route = access as? RotationAccess.AuthorizedDeveloper
            ?: return RankedResult.Failure(RankedError.ACCESS_NOT_CONFIGURED)
        val secret = route.authorization()
        if (secret.isBlank()) return RankedResult.Failure(RankedError.ACCESS_NOT_CONFIGURED)
        client.prepareGet("https://api.apexlegendsstatus.com/$endpoint") {
            parameters()
            header(HttpHeaders.Authorization, secret)
            header(HttpHeaders.UserAgent, "ApexCompanion/0.2")
        }.execute { response ->
            when (response.status.value) {
                200 -> {
                    if ((response.contentLength() ?: 0) > MAX_BODY_BYTES) {
                        response.bodyAsChannel().cancel()
                        RankedResult.Failure(RankedError.SCHEMA)
                    } else {
                        val channel = response.bodyAsChannel()
                        val bytes = channel.readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
                        channel.cancel()
                        if (bytes.size > MAX_BODY_BYTES) RankedResult.Failure(RankedError.SCHEMA)
                        else try {
                            RankedResult.Success(parse(bytes.decodeToString(throwOnInvalidSequence = true)))
                        } catch (_: Exception) { RankedResult.Failure(RankedError.SCHEMA) }
                    }
                }
                401, 403 -> RankedResult.Failure(RankedError.FORBIDDEN)
                404 -> RankedResult.Failure(RankedError.NOT_FOUND)
                410 -> RankedResult.Failure(RankedError.UNSUPPORTED_PLATFORM)
                429 -> RankedResult.Failure(RankedError.RATE_LIMITED, retryAfter(response.headers[HttpHeaders.RetryAfter]))
                400, 405 -> RankedResult.Failure(RankedError.SERVER)
                in 500..599 -> RankedResult.Failure(RankedError.SERVER, retryAfter(response.headers[HttpHeaders.RetryAfter]))
                else -> RankedResult.Failure(RankedError.SCHEMA)
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: HttpRequestTimeoutException) { RankedResult.Failure(RankedError.TIMEOUT) }
    catch (_: ConnectTimeoutException) { RankedResult.Failure(RankedError.TIMEOUT) }
    catch (_: SocketTimeoutException) { RankedResult.Failure(RankedError.TIMEOUT) }
    catch (_: Exception) { RankedResult.Failure(RankedError.NETWORK) }

    private fun retryAfter(value: String?): Long? {
        if (value == null) return null
        value.toLongOrNull()?.let { return it.coerceIn(0, 86_400) }
        return runCatching { (value.fromHttpToGmtDate().timestamp / 1000 - nowSeconds()).coerceIn(0, 86_400) }.getOrNull()
    }

    companion object { private const val MAX_BODY_BYTES = 524_288 }
}
