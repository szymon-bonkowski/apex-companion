package io.github.szymonbonkowski.apexcompanion.platform

import android.content.Context
import android.util.AtomicFile
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.network.ProviderRequestBudget
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The application process owns one cache, cooldown and request lock for every consumer. */
class AndroidMapRuntime private constructor(private val context: Context) {
    private val config = rotationRuntimeConfig(context)
    val networkEnabled = config.networkEnabled
    internal val budget = ProviderRequestBudget(RotationClock { System.currentTimeMillis() / 1000 })
    internal val rankedProvider = budget.ranked(config.rankedProvider)
    internal val configurationRevision = config.configurationRevision
    val repository = RotationRepository(budget.rotation(config.provider),
        AndroidRotationStore(File(context.filesDir, "rotation-cache-v1.json")),
        RotationClock { System.currentTimeMillis() / 1000 })

    private val startupLock = Mutex()
    private var initialized = false
    suspend fun initialize() = startupLock.withLock {
        if (!initialized) {
            repository.initialize()
            val preferences = context.getSharedPreferences("map-provider-config", Context.MODE_PRIVATE)
            if (networkEnabled && preferences.getLong("revision", -1) != config.configurationRevision) {
                // Only a newly provisioned private developer file resets an access latch.
                try {
                    repository.resetAccessFailure()
                    preferences.edit().putLong("revision", config.configurationRevision).commit()
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { /* Keep the persisted failure and show the existing storage/access state. */ }
            }
            val state = repository.state.value
            budget.restore(if (state.error == RotationError.RATE_LIMITED) state.retryAtSeconds else null, state.forbidden)
            initialized = true
        }
    }
    suspend fun refresh() { initialize(); repository.refresh(force = true) }
    suspend fun widgetState(modeId: String): RotationView {
        initialize()
        return repository.view(modeId)
    }

    companion object {
        @Volatile private var instance: AndroidMapRuntime? = null
        fun get(context: Context): AndroidMapRuntime = instance ?: synchronized(this) {
            instance ?: AndroidMapRuntime(context.applicationContext).also { instance = it }
        }
    }
}

/** Versioned records include normalized schedules/retry metadata only. Never raw API JSON or auth. */
class AndroidRotationStore(file: File) : RotationStore {
    private val atomic = AtomicFile(file)
    override suspend fun read(): String? = withContext(Dispatchers.IO) {
        synchronized(atomic) {
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) null
            else atomic.openRead().use { input ->
                val bytes = ByteArray(524_289)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                require(count <= 524_288) { "Unsupported cache size" }
                bytes.decodeToString(0, count, throwOnInvalidSequence = true)
            }
        }
    }
    override suspend fun write(value: String) = withContext(Dispatchers.IO) {
        require(value.toByteArray(Charsets.UTF_8).size <= 524_288)
        synchronized(atomic) {
            val output = atomic.startWrite()
            try {
                output.write(value.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(output)
            } catch (failure: Exception) {
                atomic.failWrite(output)
                throw failure
            }
        }
    }
}
