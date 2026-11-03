package io.github.szymonbonkowski.apexcompanion

import androidx.compose.ui.window.ComposeUIViewController
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.ranked.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Implemented — Not Verified. Swift owns atomic private files and the sanitized App Group copy. */
interface IosRotationStorageBridge {
    fun readCache(): String?
    fun writeCache(value: String): Boolean
    fun publishWidgetSnapshot(value: String): Boolean
    fun readRankedCache(): String?
    fun writeRankedCache(value: String): Boolean
    fun readPredatorCache(): String?
    fun writePredatorCache(value: String): Boolean
    fun publishRankedWidgetSnapshot(value: String): Boolean
}

private class IosRotationStore(private val bridge: IosRotationStorageBridge) : RotationStore {
    override suspend fun read(): String? = withContext(Dispatchers.Default) { bridge.readCache() }
    override suspend fun write(value: String) = withContext(Dispatchers.Default) {
        check(bridge.writeCache(value)) { "Local rotation cache could not be committed" }
    }
}
private class IosRankedStore(private val bridge: IosRotationStorageBridge, private val global: Boolean) : RotationStore {
    override suspend fun read(): String? = withContext(Dispatchers.Default) {
        if (global) bridge.readPredatorCache() else bridge.readRankedCache()
    }
    override suspend fun write(value: String) = withContext(Dispatchers.Default) {
        check(if (global) bridge.writePredatorCache(value) else bridge.writeRankedCache(value)) {
            "Local ranked data could not be committed"
        }
    }
}
fun MainViewController(storage: IosRotationStorageBridge) = run {
    // Public access stays disabled. No developer credential route is installed on iOS.
    val clock = RotationClock { Clock.System.now().epochSeconds }
    val repository = RotationRepository(UnconfiguredRotationProvider, IosRotationStore(storage), clock)
    val ranked = RankedRepository(UnconfiguredRankedProvider, IosRankedStore(storage, false), clock, IosRankedStore(storage, true))
    ComposeUIViewController {
        App(repository = repository, rankedRepository = ranked,
            onProjectionChanged = { projection -> withContext(Dispatchers.Default) {
                check(storage.publishWidgetSnapshot(projection)) { "Widget snapshot could not be committed" }
            } },
            onRankedProjectionChanged = { projection -> withContext(Dispatchers.Default) {
                check(storage.publishRankedWidgetSnapshot(projection)) { "Ranked widget snapshot could not be committed" }
            } })
    }
}
