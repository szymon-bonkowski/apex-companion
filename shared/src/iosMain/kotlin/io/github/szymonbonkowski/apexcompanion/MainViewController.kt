package io.github.szymonbonkowski.apexcompanion

import androidx.compose.ui.window.ComposeUIViewController
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.szymonbonkowski.apexcompanion.appearance.*
import io.github.szymonbonkowski.apexcompanion.maps.*
import io.github.szymonbonkowski.apexcompanion.ranked.*
import io.github.szymonbonkowski.apexcompanion.history.*
import io.github.szymonbonkowski.apexcompanion.matches.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Explicit object callback avoids Kotlin function-type/Swift Unit bridging ambiguity. */
interface IosHistoryDocumentCompletion { fun complete(value: String?) }

/** Implemented — Not Verified. Swift owns atomic private files and the sanitized App Group copy. */
interface IosRotationStorageBridge {
    fun readAppearanceCache(): String?
    fun writeAppearanceCache(value: String): Boolean
    fun publishAppearanceSnapshot(value: String): Boolean
    fun readCache(): String?
    fun writeCache(value: String): Boolean
    fun publishWidgetSnapshot(value: String): Boolean
    fun readRankedCache(): String?
    fun writeRankedCache(value: String): Boolean
    fun readPredatorCache(): String?
    fun writePredatorCache(value: String): Boolean
    fun publishRankedWidgetSnapshot(value: String): Boolean
    fun readHistoryCache(): String?
    fun writeHistoryCache(value: String): Boolean
    fun exportHistoryDocument(value: String, completion: IosHistoryDocumentCompletion)
    fun importHistoryDocument(completion: IosHistoryDocumentCompletion)
    fun cancelHistoryDocument()
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
/** Implemented — Not Verified. Full private history never enters the WidgetKit App Group. */
private class IosHistoryStore(private val bridge: IosRotationStorageBridge) : RotationStore {
    override suspend fun read(): String? = withContext(Dispatchers.Default) { bridge.readHistoryCache() }
    override suspend fun write(value: String) = withContext(Dispatchers.Default) {
        check(value.encodeToByteArray().size <= HISTORY_MAX_BYTES && bridge.writeHistoryCache(value)) {
            "Local history could not be committed"
        }
    }
}
private class IosHistoryDocuments(private val bridge: IosRotationStorageBridge) : HistoryDocuments {
    override suspend fun exportJson(value: String): Boolean = withContext(Dispatchers.Main) {
        require(value.encodeToByteArray().size <= HISTORY_MAX_BYTES)
        val result = suspendCancellableCoroutine<String?> { continuation ->
            continuation.invokeOnCancellation { bridge.cancelHistoryDocument() }
            bridge.exportHistoryDocument(value, object : IosHistoryDocumentCompletion {
                override fun complete(value: String?) { if (continuation.isActive) continuation.resume(value) }
            })
        }
        check(result == null || result == "SAVED") { "Backup document could not be saved" }
        result == "SAVED"
    }
    override suspend fun importJson(): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { bridge.cancelHistoryDocument() }
            bridge.importHistoryDocument(object : IosHistoryDocumentCompletion {
                override fun complete(value: String?) { if (continuation.isActive) continuation.resume(value) }
            })
        }
    }
}
/** Implemented — Not Verified. SwiftUI forwards only allowlisted widget URLs. */
object IosWidgetNavigation {
    internal val request = MutableStateFlow<WidgetNavigationRequest?>(null)
    fun open(url: String) {
        widgetDestination(url)?.let { request.value = WidgetNavigationRequest(it, (request.value?.sequence ?: 0L) + 1L) }
    }
}

fun MainViewController(storage: IosRotationStorageBridge, matchStorage: IosMatchStorageBridge) = run {
    // Public access stays disabled. No developer credential route is installed on iOS.
    val appearance = AppearanceRepository(object : RotationStore {
        override suspend fun read(): String? = withContext(Dispatchers.Default) { storage.readAppearanceCache() }
        override suspend fun write(value: String) = withContext(Dispatchers.Default) {
            check(storage.writeAppearanceCache(value)) { "Appearance could not be committed" }
        }
    })
    val clock = RotationClock { Clock.System.now().epochSeconds }
    val repository = RotationRepository(UnconfiguredRotationProvider, IosRotationStore(storage), clock)
    val history = HistoryRepository(IosHistoryStore(storage), clock)
    val documents = IosHistoryDocuments(storage)
    val matches = MatchRepository(IosMatchStore(matchStorage), clock)
    val matchSync = MatchSyncController(matches, IosMatchSyncPlatform(matchStorage), clock)
    val ranked = RankedRepository(UnconfiguredRankedProvider, IosRankedStore(storage, false), clock, IosRankedStore(storage, true), history = history)
    ComposeUIViewController {
        val widgetNavigation by IosWidgetNavigation.request.collectAsState()
        App(widgetNavigation = widgetNavigation, repository = repository, rankedRepository = ranked,
            appearanceRepository = appearance,
            onAppearanceProjectionChanged = { projection -> withContext(Dispatchers.Default) {
                check(storage.publishAppearanceSnapshot(projection)) { "Widget appearance could not be committed" }
            } },
            historyRepository = history, historyDocuments = documents,
            matchRepository = matches, matchSync = matchSync, matchDocuments = IosMatchDocuments(matchStorage),
            matchScreenshots = IosMatchScreenshots(matchStorage),
            onProjectionChanged = { projection -> withContext(Dispatchers.Default) {
                check(storage.publishWidgetSnapshot(projection)) { "Widget snapshot could not be committed" }
            } },
            onRankedProjectionChanged = { projection -> withContext(Dispatchers.Default) {
                check(storage.publishRankedWidgetSnapshot(projection)) { "Ranked widget snapshot could not be committed" }
            } })
    }
}
