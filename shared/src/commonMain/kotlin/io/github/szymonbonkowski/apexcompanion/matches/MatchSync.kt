package io.github.szymonbonkowski.apexcompanion.matches

import io.github.szymonbonkowski.apexcompanion.history.HistoryIdentity
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Opaque private credentials. Never include this object in logs or portable backups. */
class MatchPairing(
    val endpoint: String,
    val certificatePin: String,
    val token: String,
    val companionId: String,
    val identity: HistoryIdentity?,
) {
    init {
        require(endpoint.startsWith("https://") && endpoint.length <= 100)
        val authority = endpoint.removePrefix("https://")
        require(!authority.contains('/') && !authority.contains('@') && !authority.contains('?') && !authority.contains('#'))
        val parts = authority.split(':')
        require(parts.size == 2 && parts[1].toIntOrNull() in 1024..65535)
        val octets = parts[0].split('.').map { part ->
            require(part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' })
            require(part == "0" || !part.startsWith('0'))
            part.toInt().also { require(it in 0..255) }
        }
        require(octets.size == 4)
        require(octets[0] == 10 || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 192 && octets[1] == 168)) { "Use a private IPv4 LAN address." }
        require(certificatePin.length == 64 && certificatePin.all { it in "0123456789abcdef" })
        require(token.length == 64 && token.all { it in "0123456789abcdef" })
        require(validMatchId(companionId))
    }
    override fun toString() = "MatchPairing(<private>)"
}

/** Platform owns authenticated pinned TLS, bounded response IO and protected credential persistence. */
interface MatchSyncPlatform {
    suspend fun authorize(): Boolean = true
    suspend fun loadPairing(): MatchPairing?
    suspend fun savePairing(value: MatchPairing?)
    suspend fun request(pairing: MatchPairing, path: String, body: String? = null): String
}

data class MatchSyncState(
    val paired: Boolean = false,
    val busy: Boolean = false,
    val companionId: String? = null,
    val lastSyncSeconds: Long? = null,
    val pairedIdentity: HistoryIdentity? = null,
    val error: String? = null,
)

/** Foreground pull only. Batch+cursor commit precedes ACK; a failed ACK can safely replay. */
class MatchSyncController(
    private val repository: MatchRepository,
    private val platform: MatchSyncPlatform,
    private val clock: RotationClock,
) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(MatchSyncState())
    val state = mutable.asStateFlow()
    private var pairing: MatchPairing? = null
    private var initialized = false

    suspend fun initialize() = lock.withLock { initializeLocked() }
    private suspend fun initializeLocked() {
        if (initialized) return
        repository.initialize()
        try {
            pairing = platform.loadPairing()
            mutable.value = MatchSyncState(paired = pairing != null, companionId = pairing?.companionId, lastSyncSeconds = pairing?.let { repository.lastSyncSeconds(it.companionId) }, pairedIdentity = pairing?.identity)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mutable.value = MatchSyncState(error = "Pairing could not be read. Re-pair this device.") }
        initialized = true
    }
    suspend fun pair(value: MatchPairing): Boolean = lock.withLock {
        initializeLocked()
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            // Authorize local networking only after the explicit user pairing action.
            check(platform.authorize()) { "Local network permission denied" }
            // Authenticate the actual endpoint and pin before persisting credentials.
            val response = platform.request(value, "/v1/matches?after=${repository.cursor(value.companionId)}")
            val batch = MatchCodec.decodeBatch(response, clock.nowSeconds())
            require(batch.companionId == value.companionId)
            require(batch.records.all { it.identity == value.identity })
            platform.savePairing(value)
            pairing = value
            mutable.value = MatchSyncState(paired = true, companionId = value.companionId, lastSyncSeconds = repository.lastSyncSeconds(value.companionId))
            true
        } catch (e: CancellationException) { mutable.value = mutable.value.copy(busy = false); throw e }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(busy = false, error = "Pairing failed. Check local network permission, address, certificate pin, code and selected identity.")
            false
        }
    }
    suspend fun unpair(): Boolean = lock.withLock {
        initializeLocked()
        val previous = pairing
        var revoked = true
        if (previous != null) {
            try { platform.request(previous, "/v1/unpair", "{}") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { revoked = false }
        }
        try {
            platform.savePairing(null)
            pairing = null
            mutable.value = MatchSyncState(error = if (revoked) null else "Local pairing removed. PC was unreachable; revoke the device on the PC as well.")
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(error = "Pairing could not be deleted. Try again.")
            false
        }
    }
    suspend fun synchronize(): Boolean = lock.withLock {
        initializeLocked()
        val current = pairing ?: return@withLock false
        mutable.value = mutable.value.copy(busy = true, error = null)
        try {
            // Bounded foreground work. Another app open/manual sync drains larger queues.
            repeat(20) {
                val before = repository.cursor(current.companionId)
                val batch = MatchCodec.decodeBatch(platform.request(current, "/v1/matches?after=$before"), clock.nowSeconds())
                require(batch.companionId == current.companionId)
                require(batch.cursor >= before && (batch.records.isEmpty() || batch.cursor > before))
                require(batch.records.all { it.identity == current.identity })
                check(repository.applySync(current.companionId, batch.cursor, batch.records, current.identity))
                platform.request(current, "/v1/ack", "{\"cursor\":${batch.cursor}}")
                if (batch.records.isEmpty()) {
                    check(repository.markSyncComplete(current.companionId))
                    mutable.value = mutable.value.copy(busy = false, lastSyncSeconds = repository.lastSyncSeconds(current.companionId), error = null)
                    return@withLock true
                }
            }
            check(repository.markSyncComplete(current.companionId))
            mutable.value = mutable.value.copy(busy = false, lastSyncSeconds = repository.lastSyncSeconds(current.companionId), error = "More matches may remain queued. Sync again to continue.")
            true
        } catch (e: CancellationException) { mutable.value = mutable.value.copy(busy = false); throw e }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(busy = false, error = "Sync failed. Saved matches and PC queue retained. Check local network permission, connection, pairing, identity or storage capacity.")
            false
        }
    }
}
