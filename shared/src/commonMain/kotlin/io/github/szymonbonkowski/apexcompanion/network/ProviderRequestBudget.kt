package io.github.szymonbonkowski.apexcompanion.network

import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One app-process budget for all ALS endpoints. Serial requests stay below the documented 5/s ceiling. */
class ProviderRequestBudget(private val clock: RotationClock, private val pause: suspend (Long) -> Unit = { delay(it) }) {
    private val lock = Mutex()
    private var lastRequestMillis: Long? = null
    private var retryAtSeconds = 0L
    private var forbidden = false
    fun restore(retryAt: Long?, denied: Boolean) { retryAtSeconds = maxOf(retryAtSeconds, retryAt ?: 0); forbidden = forbidden || denied }
    fun rotation(provider: RotationProvider): RotationProvider = RotationProvider {
        lock.withLock {
            if (forbidden) return@withLock ProviderResult.Failure(RotationError.FORBIDDEN)
            if (clock.nowSeconds() < retryAtSeconds) return@withLock ProviderResult.Failure(RotationError.RATE_LIMITED, retryAtSeconds - clock.nowSeconds())
            pace()
            provider.fetch().also { result -> if (result is ProviderResult.Failure) {
                if (result.error == RotationError.FORBIDDEN) forbidden = true
                if (result.error == RotationError.RATE_LIMITED) retryAtSeconds = clock.nowSeconds() + maxOf(30, result.retryAfterSeconds ?: 0)
            } }
        }
    }
    fun ranked(provider: RankedProvider): RankedProvider = object: RankedProvider {
        override suspend fun profile(lookup: ProfileLookup): RankedResult<RankedSnapshot> = request { provider.profile(lookup) }
        override suspend fun predator(): RankedResult<List<PredatorThreshold>> = request { provider.predator() }
    }
    private suspend fun <T> request(action: suspend () -> RankedResult<T>): RankedResult<T> = lock.withLock {
        if (forbidden) return@withLock RankedResult.Failure(RankedError.FORBIDDEN)
        if (clock.nowSeconds() < retryAtSeconds) return@withLock RankedResult.Failure(RankedError.RATE_LIMITED, retryAtSeconds - clock.nowSeconds())
        pace()
        action().also { result -> if (result is RankedResult.Failure) {
            if (result.error == RankedError.FORBIDDEN) forbidden = true
            if (result.error == RankedError.RATE_LIMITED) retryAtSeconds = clock.nowSeconds() + maxOf(30, result.retryAfterSeconds ?: 0)
        } }
    }
    private suspend fun pace() {
        // Seconds-resolution clock uses a conservative full-second gap between different calls.
        val now = clock.nowSeconds() * 1000
        lastRequestMillis?.let { pause((1000 - (now - it)).coerceIn(0, 1000)) }
        lastRequestMillis = clock.nowSeconds() * 1000
    }
}
