package com.ngjhd.sgpm25widget

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.OffsetDateTime

/**
 * One update: locate the device, fetch the feed, pick the nearest region,
 * save it, and push every widget.
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

        val ok = try {
            update(ctx)
        } catch (e: Throwable) {
            Log.e(TAG, "update threw", e)
            false
        }
        if (isStopped) {
            // Cancelled from the widget; the broadcast already restored it.
            return Result.failure()
        }
        if (ok) {
            Scheduling.cancelRefreshWatchdog(ctx)
            return Result.success()
        }
        val lastAttempt = runAttemptCount >= MAX_ATTEMPTS - 1 || backstop
        // Never leave the spinner up after a failed attempt.
        WidgetViews.updateAll(ctx, failed = lastAttempt)
        if (lastAttempt) Scheduling.cancelRefreshWatchdog(ctx)
        return if (lastAttempt) Result.failure() else Result.retry()
    }

    private fun update(ctx: Context): Boolean {
        val store = Store(ctx)

        // Location: fresh fix -> OS last known -> our saved fix -> Central.
        var source = Store.Source.CURRENT
        val loc = Locator.current(ctx) { isStopped }
            ?: Locator.lastKnown(ctx)?.also { source = Store.Source.LAST_KNOWN }
        if (isStopped) return false
        val latLon = if (loc != null) {
            store.saveLocation(loc.latitude, loc.longitude, loc.time)
            loc.latitude to loc.longitude
        } else {
            source = Store.Source.LAST_KNOWN
            store.loadLocation()
        }

        val reading = try {
            Pm25.fetch()
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: $e")
            return false
        }
        if (isStopped) return false

        val region = if (latLon != null) {
            Pm25.nearestRegion(reading.regions, latLon.first, latLon.second).name
        } else {
            source = Store.Source.DEFAULT
            Pm25.DEFAULT_REGION
        }
        val value = reading.values[region] ?: run {
            Log.w(TAG, "no value for $region in ${reading.values}")
            return false
        }
        Log.i(TAG, "region=$region value=$value source=$source reading=${reading.timestamp} at=$latLon")

        store.saveShown(
            Store.Shown(
                region = region,
                value = value,
                timestamp = reading.timestamp,
                fetchedAt = System.currentTimeMillis(),
                source = source,
                all = reading.values,
            ),
        )
        WidgetViews.updateAll(ctx)

        // The feed publishes ~X:15; if X:20 still got last hour's reading,
        // look again shortly instead of an hour from now.
        if (isLate(reading.timestamp)) Scheduling.armLateRetry(ctx)
        return true
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

        /** 30 s exponential backoff: ~30 + 60 + 120 s of trying. */
        const val MAX_ATTEMPTS = 4
    }
}
