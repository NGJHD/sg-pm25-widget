package com.ngjhd.sgpm25widget

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.OffsetDateTime
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One update: locate the device and fetch the feed (in parallel), pick the
 * nearest region, save it, and push every widget.
 */
class FetchWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val userInitiated = inputData.getBoolean(Scheduling.EXTRA_USER_INITIATED, false)
        val backstop = inputData.getBoolean(Scheduling.EXTRA_BACKSTOP, false)
        Log.i(TAG, "start userInitiated=$userInitiated backstop=$backstop attempt=$runAttemptCount")

        if (!WidgetViews.hasWidgets(ctx) && !userInitiated) {
            Log.i(TAG, "no widgets placed; nothing to do")
            return Result.success()
        }
        if (!userInitiated && Scheduling.shouldSkipWhileAsleep(ctx)) {
            Log.i(TAG, "skipped (screen off); the held alarm catches up on wake")
            return Result.success()
        }
        if (backstop) {
            // Runs on its own hourly cadence; only acts if the chain broke.
            if (!Scheduling.isStale(ctx)) return Result.success()
            Log.w(TAG, "backstop found a stale value; restarting the chain")
            Scheduling.armNextTick(ctx)
        }

        val error = try {
            update(ctx)
        } catch (e: Throwable) {
            Log.e(TAG, "update threw", e)
            e.toString()
        }
        if (isStopped) {
            // Cancelled from the widget; the broadcast already restored it.
            return Result.failure()
        }
        if (error == null) {
            Scheduling.cancelRefreshWatchdog(ctx)
            return Result.success()
        }
        Store(ctx).saveError(error)
        // A tap gets one attempt: the person is watching, and a retry 30 s
        // later would only keep the spinner up. Scheduled updates retry.
        val lastAttempt = userInitiated || backstop || runAttemptCount >= MAX_ATTEMPTS - 1
        // Never leave the spinner up after a failed attempt.
        WidgetViews.updateAll(ctx, failed = lastAttempt)
        if (lastAttempt) Scheduling.cancelRefreshWatchdog(ctx)
        return if (lastAttempt) Result.failure() else Result.retry()
    }

    /** Returns null on success, else what went wrong (shown on the app screen). */
    private fun update(ctx: Context): String? {
        val store = Store(ctx)
        val t0 = System.currentTimeMillis()

        // The download does not depend on the location: start it first and
        // let it run while the location is found.
        val pool = Executors.newSingleThreadExecutor()
        val download = pool.submit(Callable { Pm25.fetch() })
        pool.shutdown()

        // Location: recent OS fix -> new fix -> old OS fix -> our saved fix -> Central.
        val fix = try {
            Locator.locate(ctx) { isStopped }
        } catch (e: Exception) {
            Log.w(TAG, "locate failed: $e")
            null
        }
        val locMs = System.currentTimeMillis() - t0
        if (isStopped) {
            download.cancel(true)
            return "cancelled"
        }
        var source = when {
            fix == null -> Store.Source.LAST_KNOWN
            fix.fresh -> Store.Source.CURRENT
            else -> Store.Source.LAST_KNOWN
        }
        val latLon = if (fix != null) {
            store.saveLocation(fix.location.latitude, fix.location.longitude, fix.location.time)
            fix.location.latitude to fix.location.longitude
        } else {
            store.loadLocation()
        }

        val reading = try {
            download.get(20, TimeUnit.SECONDS)
        } catch (e: Exception) {
            download.cancel(true)
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            Log.w(TAG, "fetch failed: $cause")
            return "download failed: ${cause.javaClass.simpleName}" +
                (cause.message?.let { ": $it" } ?: "")
        }
        if (isStopped) return "cancelled"

        val region = if (latLon != null) {
            Pm25.nearestRegion(reading.regions, latLon.first, latLon.second).name
        } else {
            source = Store.Source.DEFAULT
            Pm25.DEFAULT_REGION
        }
        val value = reading.values[region] ?: return "no value for $region"
        val tookMs = System.currentTimeMillis() - t0
        Log.i(TAG, "region=$region value=$value source=$source reading=${reading.timestamp} took=${tookMs}ms loc=${locMs}ms")

        store.saveShown(
            Store.Shown(
                region = region,
                value = value,
                timestamp = reading.timestamp,
                fetchedAt = System.currentTimeMillis(),
                source = source,
                all = reading.values,
                tookMs = tookMs,
                locMs = locMs,
                accuracyM = fix?.location?.takeIf { it.hasAccuracy() }?.accuracy?.toInt() ?: -1,
            ),
        )
        WidgetViews.updateAll(ctx)

        // The feed publishes ~X:15; if X:20 still got last hour's reading,
        // look again shortly instead of an hour from now.
        if (isLate(reading.timestamp)) Scheduling.armLateRetry(ctx)
        return null
    }

    /** True when [timestamp] is older than the top of the current fetch hour. */
    private fun isLate(timestamp: String): Boolean {
        val readingMs = runCatching { OffsetDateTime.parse(timestamp).toInstant().toEpochMilli() }
            .getOrNull() ?: return false
        val hourStart = Scheduling.lastFetchTime(System.currentTimeMillis()) -
            Scheduling.FETCH_MINUTE * 60_000L
        return readingMs < hourStart
    }

    companion object {
        private const val TAG = "PM_FETCH"

        /** Scheduled updates: 30 s exponential backoff, ~30 + 60 + 120 s. */
        const val MAX_ATTEMPTS = 4
    }
}
