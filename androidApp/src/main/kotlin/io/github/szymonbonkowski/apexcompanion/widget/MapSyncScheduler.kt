package io.github.szymonbonkowski.apexcompanion.widget

import android.content.Context
import android.content.ComponentName
import android.appwidget.AppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.szymonbonkowski.apexcompanion.maps.ROTATION_FRESH_SECONDS
import io.github.szymonbonkowski.apexcompanion.platform.AndroidMapRuntime
import java.util.concurrent.TimeUnit

/** One app-wide sync budget, regardless of the number of widget instances. OS timing is advisory. */
object MapSyncScheduler {
    const val PERIODIC_NAME = "map-rotation-periodic"
    const val REFRESH_NAME = "map-rotation-request"
    const val LOCAL_NAME = "map-rotation-local-update"
    const val BOUNDARY_NAME = "map-rotation-boundary"

    fun ensurePeriodic(context: Context) {
        if (!AndroidMapRuntime.get(context).networkEnabled) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<MapSyncWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun requestRefresh(context: Context) {
        // Disabled provider must not create pending network work or demand a connection.
        if (!AndroidMapRuntime.get(context).networkEnabled) {
            requestLocalUpdate(context)
            return
        }
        val request = OneTimeWorkRequestBuilder<MapSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(REFRESH_NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun requestLocalUpdate(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(LOCAL_NAME, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<MapLocalUpdateWorker>().build())
    }

    /** Local projection refresh at a known source/freshness boundary; never a provider poll. */
    @Synchronized
    fun scheduleBoundary(context: Context) {
        val hasWidgets = AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MapRotationWidgetReceiver::class.java)).isNotEmpty()
        val cache = if (hasWidgets) AndroidMapRuntime.get(context).repository.state.value.cache else null
        val now = System.currentTimeMillis() / 1000L
        val next = cache?.let {
            (it.playlists.flatMap { mode -> mode.segments.flatMap { segment -> listOf(segment.startSeconds, segment.endSeconds) } } +
                (it.fetchedAtSeconds + ROTATION_FRESH_SECONDS)).filter { time -> time > now }.minOrNull()
        }
        val preferences = context.getSharedPreferences("map-widget-scheduler", Context.MODE_PRIVATE)
        if (next == null) {
            WorkManager.getInstance(context).cancelUniqueWork(BOUNDARY_NAME)
            preferences.edit().remove("boundary").apply()
            return
        }
        if (preferences.getLong("boundary", -1L) == next) return
        preferences.edit().putLong("boundary", next).apply()
        val request = OneTimeWorkRequestBuilder<MapBoundaryWorker>().setInitialDelay(next - now, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork(BOUNDARY_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}

class MapSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val runtime = AndroidMapRuntime.get(applicationContext)
        runtime.initialize()
        if (runtime.networkEnabled) runtime.refresh()
        // Runtime persists before publishing. Every instance reads the same app-owned snapshot.
        MapRotationWidget().updateAll(applicationContext)
        MapSyncScheduler.scheduleBoundary(applicationContext)
        return Result.success() // shared repository owns retry-after/backoff and the 403 latch
    }
}

class MapLocalUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        AndroidMapRuntime.get(applicationContext).initialize()
        MapRotationWidget().updateAll(applicationContext)
        MapSyncScheduler.scheduleBoundary(applicationContext)
        return Result.success()
    }
}

class MapBoundaryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        AndroidMapRuntime.get(applicationContext).initialize()
        MapRotationWidget().updateAll(applicationContext)
        MapSyncScheduler.scheduleBoundary(applicationContext)
        return Result.success()
    }
}
