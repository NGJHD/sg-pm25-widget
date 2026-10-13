package com.ngjhd.sgpm25widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.util.Log

class Pm25WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        WidgetViews.updateAll(context)
        // Self-heal: onUpdate is the one callback the system fires on its own
        // (placement, reboot, app update). Only safe while widget_info.xml has
        // no updatePeriodMillis.
        Scheduling.start(context)
        // A newly placed widget (or one whose value is old) gets data now.
        if (Scheduling.isStale(context)) {
            Scheduling.fetchNow(context, policy = androidx.work.ExistingWorkPolicy.KEEP)
        }
    }

    override fun onDisabled(context: Context) {
        // The last widget was removed.
        Scheduling.stop(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            WidgetViews.ACTION_REFRESH -> {
                Log.i(TAG, "refresh tapped")
                WidgetViews.showRefreshing(context)
                Scheduling.armRefreshWatchdog(context)
                Scheduling.fetchNow(context, userInitiated = true)
            }
            WidgetViews.ACTION_CANCEL_REFRESH -> {
                Log.i(TAG, "cancel tapped")
                Scheduling.cancelFetch(context)
                WidgetViews.updateAll(context)
            }
            WidgetViews.ACTION_ALARM_TICK -> {
                // Arm the next X:20 first, so the chain survives anything below.
                Scheduling.armNextTick(context)
                if (Scheduling.shouldSkipWhileAsleep(context)) {
                    Log.i(TAG, "tick skipped (screen off); the next one is held until wake")
                } else {
                    Log.i(TAG, "tick")
                    Scheduling.fetchNow(context)
                }
            }
            WidgetViews.ACTION_REFRESH_TIMEOUT -> {
                Log.w(TAG, "refresh timed out; clearing the overlay")
                WidgetViews.updateAll(context, failed = true)
            }
        }
    }

    companion object {
        private const val TAG = "PM_WIDGET"
    }
}
