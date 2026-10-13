package com.ngjhd.sgpm25widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Catches up the moment the phone is woken.
 *
 * A tick that fell due while the phone slept is a held non-wakeup alarm, so
 * it is delivered at the first wake on its own. This receiver covers the rest
 * (a tick that ran while the screen was off and was skipped, or a chain that
 * broke) whenever the process is alive: if the value predates the most recent
 * X:20, fetch now.
 *
 * Registered at runtime from [App] - ACTION_SCREEN_ON is never delivered to a
 * manifest-declared receiver.
 */
class ScreenOnReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SCREEN_ON) return
        val app = context.applicationContext
        if (!WidgetViews.hasWidgets(app) || !Scheduling.isStale(app)) return
        Log.i("PM_SCHED", "screen on with a stale value; catching up")
        Scheduling.armNextTick(app)
        // KEEP, not REPLACE: the held alarm fires on this same wake, so a fetch
        // may already be queued (Webglance measured the two 74 ms apart).
        Scheduling.fetchNow(app, policy = androidx.work.ExistingWorkPolicy.KEEP)
    }
}
