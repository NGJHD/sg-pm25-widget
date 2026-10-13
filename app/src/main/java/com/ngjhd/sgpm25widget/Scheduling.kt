package com.ngjhd.sgpm25widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The update schedule: every hour at X:20, while the screen is on.
 *
 * One schedule for the whole app, not per widget - every widget shows the
 * same thing (the device's region), so one fetch updates them all.
 *
 * Carried over from Webglance, where each part was measured:
 *  - **AlarmManager chain, not WorkManager periodic work.** A periodic job's
 *    interval is a minimum that the system batches and defers (Samsung parks
 *    jobs of non-Unrestricted apps), so it cannot hit a wall-clock minute.
 *    Each tick arms the next.
 *  - **Inexact and NON-wakeup** (`setWindow(RTC, ..., 60 s)`). Updates are
 *    skipped with the screen off anyway, so a wakeup alarm could only wake
 *    the phone to do nothing. A non-wakeup alarm that falls due while the
 *    phone sleeps is held and delivered at the next wake - which is exactly
 *    the "update when it wakes" catch-up. With battery Unrestricted, ticks
 *    landed 5-25 s after due.
 *  - The fetch itself is a WorkManager one-shot, which survives the process
 *    dying mid-fetch and gives retries with backoff.
 *  - A WorkManager periodic job is kept purely as a **backstop**: it does
 *    nothing unless the shown value is stale (the chain broke).
 */
object Scheduling {

    private const val TAG = "PM_SCHED"

    const val EXTRA_USER_INITIATED = "user_initiated"
    const val EXTRA_BACKSTOP = "backstop"

    /** Minute past each hour to fetch. The feed publishes at ~X:15. */
    const val FETCH_MINUTE = 20

    /**
     * When a fetch at X:20 still returns the previous hour (the feed was
     * late), try again this much later instead of waiting an hour.
     */
    private const val LATE_RETRY_MS = 10 * 60_000L

    /** How long the refresh spinner may show before it is taken down. */
    private const val REFRESH_TIMEOUT_MS = 60_000L

    private const val TICK_WINDOW_MS = 60_000L

    const val FETCH_WORK = "pm25-fetch"
    private const val BACKSTOP_WORK = "pm25-backstop"

    private fun alarms(context: Context) =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun tickPi(context: Context): PendingIntent {
        val intent = Intent(context, Pm25WidgetProvider::class.java)
            .setAction(WidgetViews.ACTION_ALARM_TICK)
        return PendingIntent.getBroadcast(
            context, 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun watchdogPi(context: Context): PendingIntent {
        val intent = Intent(context, Pm25WidgetProvider::class.java)
            .setAction(WidgetViews.ACTION_REFRESH_TIMEOUT)
        return PendingIntent.getBroadcast(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The next X:20 strictly after [now] (wall clock, device time zone). */
    fun nextFetchTime(now: Long): Long {
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.MINUTE, FETCH_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.HOUR_OF_DAY, 1)
        return c.timeInMillis
    }

    /** The most recent X:20 at or before [now]. */
    fun lastFetchTime(now: Long): Long = nextFetchTime(now) - 60 * 60_000L

    /** Arms the next X:20 tick (replacing any pending one). */
    fun armNextTick(context: Context) {
        val at = nextFetchTime(System.currentTimeMillis())
        alarms(context).setWindow(AlarmManager.RTC, at, TICK_WINDOW_MS, tickPi(context))
        Log.i(TAG, "armNextTick at ${java.util.Date(at)}")
    }

    /** The feed was late: try again in [LATE_RETRY_MS] instead of at X:20. */
    fun armLateRetry(context: Context) {
        val at = System.currentTimeMillis() + LATE_RETRY_MS
        alarms(context).setWindow(AlarmManager.RTC, at, TICK_WINDOW_MS, tickPi(context))
        Log.i(TAG, "armLateRetry at ${java.util.Date(at)}")
    }

    /** Scheduled updates are skipped while the screen is off. */
    fun shouldSkipWhileAsleep(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return !pm.isInteractive
    }

    /**
     * True when the shown value predates the most recent X:20, i.e. a
     * scheduled update has been missed (asleep, or the chain broke).
     */
    fun isStale(context: Context): Boolean {
        val fetchedAt = Store(context).loadShown()?.fetchedAt ?: return true
        return fetchedAt < lastFetchTime(System.currentTimeMillis())
    }

    /** Starts (or restarts) the whole schedule. Safe to call repeatedly. */
    fun start(context: Context) {
        armNextTick(context)
        val request = PeriodicWorkRequestBuilder<FetchWorker>(60, TimeUnit.MINUTES)
            .setInputData(workDataOf(EXTRA_BACKSTOP to true))
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(BACKSTOP_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Called when the last widget is removed. */
    fun stop(context: Context) {
        alarms(context).cancel(tickPi(context))
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(BACKSTOP_WORK)
        wm.cancelUniqueWork(FETCH_WORK)
        cancelRefreshWatchdog(context)
    }

    /**
     * Enqueues a fetch. [userInitiated] = the refresh button or the app
     * screen: always runs. Otherwise it is skipped with the screen off.
     */
    fun fetchNow(
        context: Context,
        userInitiated: Boolean = false,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
    ): java.util.UUID {
        val builder = OneTimeWorkRequestBuilder<FetchWorker>()
            .setInputData(workDataOf(EXTRA_USER_INITIATED to userInitiated))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        // A tap must start NOW, not when JobScheduler gets round to it.
        // Expedited only on Android 12+: below that WorkManager runs expedited
        // work as a foreground service, which this app does not have.
        if (userInitiated && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        val request = builder.build()
        WorkManager.getInstance(context).enqueueUniqueWork(FETCH_WORK, policy, request)
        return request.id
    }

    fun cancelFetch(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FETCH_WORK)
        cancelRefreshWatchdog(context)
        Log.i(TAG, "cancelFetch")
    }

    /**
     * The refresh spinner can never run forever: a main-looper post (alive as
     * long as the process is, which covers a running fetch), plus an inexact
     * alarm for the case where the process died mid-fetch.
     */
    fun armRefreshWatchdog(context: Context) {
        val app = context.applicationContext
        cancelRefreshWatchdog(app)
        val fire = Runnable {
            watchdog = null
            Log.w(TAG, "refresh watchdog fired (in-process)")
            WidgetViews.updateAll(app, failed = true)
        }
        watchdog = fire
        mainHandler.postDelayed(fire, REFRESH_TIMEOUT_MS)
        alarms(app).setWindow(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + REFRESH_TIMEOUT_MS,
            TICK_WINDOW_MS,
            watchdogPi(app),
        )
    }

    fun cancelRefreshWatchdog(context: Context) {
        watchdog?.let { mainHandler.removeCallbacks(it) }
        watchdog = null
        alarms(context).cancel(watchdogPi(context))
    }

    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    @Volatile
    private var watchdog: Runnable? = null
}
