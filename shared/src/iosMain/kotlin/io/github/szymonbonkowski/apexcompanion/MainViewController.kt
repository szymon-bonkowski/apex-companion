package io.github.szymonbonkowski.apexcompanion

import androidx.compose.ui.window.ComposeUIViewController
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.maps.RotationRepository
import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import io.github.szymonbonkowski.apexcompanion.maps.UnconfiguredRotationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/** Implemented in Swift; only sanitized JSON crosses this bridge, never provider credentials. */
interface IosRotationStorageBridge {
    fun readCache(): String?
    fun writeCache(value: String): Boolean
    fun publishWidgetSnapshot(value: String): Boolean
}

private class IosRotationStore(private val bridge: IosRotationStorageBridge) : RotationStore {
    override suspend fun read(): String? = withContext(Dispatchers.Default) { bridge.readCache() }

    override suspend fun write(value: String) = withContext(Dispatchers.Default) {
        check(bridge.writeCache(value)) { "Local rotation cache could not be committed" }
    }
}

fun MainViewController(storage: IosRotationStorageBridge) = run {
    // Public access remains disabled until an approved, credential-isolating topology exists.
    // Foreground refresh/backoff/freshness are shared; no unsupported iOS polling is scheduled.
    val repository = RotationRepository(
        provider = UnconfiguredRotationProvider,
        store = IosRotationStore(storage),
        clock = RotationClock { Clock.System.now().epochSeconds },
    )
    ComposeUIViewController {
        App(repository = repository, onProjectionChanged = { projection ->
            withContext(Dispatchers.Default) {
                check(storage.publishWidgetSnapshot(projection)) { "Widget snapshot could not be committed" }
            }
        })
    }
}
