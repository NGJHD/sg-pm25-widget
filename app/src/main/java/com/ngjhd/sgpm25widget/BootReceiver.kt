package com.ngjhd.sgpm25widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Alarms survive neither a reboot nor an app update: re-arm the chain. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (!WidgetViews.hasWidgets(app)) return
        Log.i("PM_SCHED", "BootReceiver: ${intent.action}; re-arming the schedule")
        Scheduling.start(app)
    }
}
