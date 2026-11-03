package io.github.szymonbonkowski.apexcompanion.platform

import android.content.Context
import io.github.szymonbonkowski.apexcompanion.history.HistoryRepository
import io.github.szymonbonkowski.apexcompanion.history.HISTORY_MAX_BYTES
import io.github.szymonbonkowski.apexcompanion.maps.RotationClock
import io.github.szymonbonkowski.apexcompanion.ranked.*
import io.github.szymonbonkowski.apexcompanion.widget.updateAllRankedWidgets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import java.io.File

/** Shares the map runtime's private route and global request budget; never starts a scheduler. */
class AndroidRankedRuntime private constructor(private val context: Context) {
    private val maps = AndroidMapRuntime.get(context)
    val history = HistoryRepository(
        AndroidRotationStore(File(context.filesDir, "ranked-history-v1.json"), HISTORY_MAX_BYTES),
        RotationClock { System.currentTimeMillis() / 1000 })
    val repository = RankedRepository(maps.rankedProvider,
        AndroidRotationStore(File(context.filesDir, "ranked-profile-v1.json")),
        RotationClock { System.currentTimeMillis() / 1000 },
        AndroidRotationStore(File(context.filesDir, "predator-cache-v1.json")), history = history)
    private val lock = Mutex()
    private var initialized = false
    suspend fun initialize() = lock.withLock {
        if (!initialized) {
            maps.initialize()
            repository.initialize()
            val preferences = context.getSharedPreferences("ranked-provider-config", Context.MODE_PRIVATE)
            if (maps.networkEnabled && preferences.getLong("revision", -1) != maps.configurationRevision) {
                if (repository.resetAccessFailure()) preferences.edit().putLong("revision", maps.configurationRevision).commit()
            }
            val s = repository.state.value
            maps.budget.restore(if (s.error == RankedError.RATE_LIMITED) s.retryAtSeconds else null, s.forbidden)
            maps.budget.restore(if (s.predatorError == RankedError.RATE_LIMITED) s.predatorRetryAtSeconds else null, s.predatorForbidden)
            initialized = true
        }
    }
    suspend fun widgetSnapshot(): String {
        initialize()
        val now = Clock.System.now()
        val zone = TimeZone.currentSystemDefault()
        return RankedWidgetCodec.encode(repository.state.value, now.epochSeconds, now.toLocalDateTime(zone).date, zone)
    }
    suspend fun publish() {
        initialize()
        // The durable normalized source is already committed by the repository. Every widget
        // reprojects it locally at render time to avoid persisting stale LIVE labels.
        updateAllRankedWidgets(context)
        io.github.szymonbonkowski.apexcompanion.widget.MapSyncScheduler.scheduleBoundary(context)
    }
    suspend fun refresh() {
        initialize()
        if (maps.networkEnabled) {
            repository.refresh()
            repository.refreshPredator()
        }
        publish()
    }
    companion object {
        @Volatile private var instance: AndroidRankedRuntime? = null
        fun get(context: Context): AndroidRankedRuntime = instance ?: synchronized(this) {
            instance ?: AndroidRankedRuntime(context.applicationContext).also { instance = it }
        }
    }
}
