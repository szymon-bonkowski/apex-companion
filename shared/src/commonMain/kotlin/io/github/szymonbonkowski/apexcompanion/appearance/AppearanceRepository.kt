package io.github.szymonbonkowski.apexcompanion.appearance

import io.github.szymonbonkowski.apexcompanion.maps.RotationStore
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Separate cosmetic transaction. Publication follows durable commit; no game repository is touched.
 */
class AppearanceRepository(private val store: RotationStore) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(AppearanceState())
    val state = mutable.asStateFlow()

    suspend fun initialize() = lock.withLock { initializeLocked() }

    private suspend fun initializeLocked() {
        if (state.value.initialized) return
        try {
            mutable.value =
                AppearanceState(
                    store.read()?.let(AppearanceCodec::decode) ?: AppearanceRecord(),
                    initialized = true,
                )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutable.value =
                AppearanceState(
                    initialized = true,
                    readOnly = true,
                    error =
                        "Appearance could not be read. Original file retained. Reset appearance explicitly to recover; game data is unaffected.",
                )
        }
    }

    private suspend fun change(transform: (AppearanceRecord) -> AppearanceRecord): Boolean =
        lock.withLock {
            initializeLocked()
            if (state.value.readOnly) return@withLock false
            try {
                commit(transform(state.value.record).validated())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.value =
                    state.value.copy(error = e.message ?: "Appearance could not be saved.")
                false
            }
        }

    private suspend fun commit(record: AppearanceRecord): Boolean {
        val wire = AppearanceCodec.encode(record)
        return withContext(NonCancellable) {
            try {
                store.write(wire)
                mutable.value = AppearanceState(record, initialized = true)
                true
            } catch (_: Exception) {
                mutable.value =
                    state.value.copy(
                        error = "Appearance could not be saved. Previous settings are retained."
                    )
                false
            }
        }
    }

    suspend fun savePreferences(preferences: AppearancePreferences) = change {
        it.copy(preferences = preferences.validated())
    }

    suspend fun saveDashboard(layout: DashboardLayout?) = change {
        it.copy(dashboard = layout?.validated())
    }

    suspend fun saveFavoriteMaps(preferences: FavoriteMapPreferences) = change {
        it.copy(favoriteMaps = preferences.validated())
    }

    suspend fun resetToStyleDefaults(style: DesignStyle) = change {
        it.copy(preferences = AppearancePreferences(style = style))
    }

    suspend fun createPreset(name: String) = change { r ->
        r.copy(
            presets =
                r.presets +
                    AppearancePreset(
                        "preset_${Random.nextLong().toULong()}",
                        name.trim(),
                        r.preferences,
                        r.dashboard,
                    )
        )
    }

    suspend fun renamePreset(id: String, name: String) = change { r ->
        require(r.presets.any { it.id == id })
        r.copy(presets = r.presets.map { if (it.id == id) it.copy(name = name.trim()) else it })
    }

    suspend fun duplicatePreset(id: String) = change { r ->
        val p = r.presets.single { it.id == id }
        r.copy(
            presets =
                r.presets +
                    p.copy(
                        id = "preset_${Random.nextLong().toULong()}",
                        name = (p.name.take(53) + " copy"),
                    )
        )
    }

    suspend fun deletePreset(id: String) = change { r ->
        require(r.presets.any { it.id == id })
        r.copy(presets = r.presets.filterNot { it.id == id })
    }

    suspend fun restorePreset(id: String) = change { r ->
        val p = r.presets.single { it.id == id }
        r.copy(preferences = p.preferences, dashboard = p.dashboard)
    }

    /** Only explicit UI confirmation may overwrite an unreadable cosmetic file. */
    suspend fun resetAll(confirmCorruptReset: Boolean = false): Boolean = lock.withLock {
        initializeLocked()
        if (state.value.readOnly && !confirmCorruptReset) false else commit(AppearanceRecord())
    }
}
