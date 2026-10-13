package com.ngjhd.sgpm25widget

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast

/**
 * The setup gate: the two phone settings without which the widget silently
 * never updates.
 *
 * 1. **Battery "Unrestricted"**. Without it the inexact refresh alarms are
 *    parked ~a year out (measured in Webglance) and background starts are
 *    deferred - on a Fold 4 the schedule looked completely dead.
 * 2. **Data Saver exemption**. With Data Saver on, Android
 *    blocks network access for apps in the background unless they are
 *    exempt: netd refuses DNS for our uid (`isBlocked=true`), every scheduled
 *    fetch fails with a DNS error. (Measured in Webglance, where it hit the
 *    user on a fresh install.)
 *
 * Both are checked on every resume of the app screen and shown one at a time,
 * battery first (nothing runs at all without it). Deliberately unskippable:
 * no "Later" - see [warnIfRestricted].
 */
object PowerSetup {

    private const val TAG = "PM_POWER"

    enum class Problem { BATTERY, DATA_SAVER }

    /** True when the app is on the battery-optimisation allowlist. */
    fun isUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * True when Data Saver is on AND this app is not exempt from it.
     * RESTRICT_BACKGROUND_STATUS_WHITELISTED (exempt) and _DISABLED (Data
     * Saver off) are both fine. The status does not depend on the current
     * network, so this also catches a user on Wi-Fi who will be on mobile
     * data later. No permission needed.
     */
    fun isDataSaverBlocking(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    }

    /** The first setting that still needs fixing, or null when all is well. */
    fun currentProblem(context: Context): Problem? = when {
        !isUnrestricted(context) -> Problem.BATTERY
        isDataSaverBlocking(context) -> Problem.DATA_SAVER
        else -> null
    }

    /** The dialog currently up in each activity, and which problem it shows. */
    private val showing = java.util.WeakHashMap<Activity, Pair<Problem, AlertDialog>>()

    /**
     * Shows the dialog for [currentProblem] - EVERY time it is called while
     * something is wrong (09-23, user: "it should be naggy... it's a core
     * setting that is required for the app to work"). Callers invoke it from
     * onResume, so it comes back each time the user returns to the screen,
     * including straight back from Settings without having changed anything.
     * There is no "Later": the only ways out are the settings button, or Back,
     * which closes the screen (see the builder below).
     *
     * One dialog at a time: never a second copy over one already up (a resume
     * can happen with it still showing, e.g. back from the notification
     * permission prompt); a dialog for a problem that has been fixed is taken
     * down and replaced by the next one, if any.
     */
    fun warnIfRestricted(activity: Activity) {
        val problem = currentProblem(activity)
        val current = showing[activity]
        if (current != null && current.second.isShowing) {
            if (current.first == problem) return
            showing.remove(activity)
            current.second.setOnCancelListener(null) // replacing it must not finish()
            current.second.dismiss()
        }
        if (problem == null) {
            Log.i(TAG, "setup gate: battery Unrestricted and Data Saver OK")
            return
        }
        Log.w(TAG, "setup gate: $problem - refreshes will be delayed or dropped")
        val appName = activity.getString(R.string.app_name)
        val (title, message, button) = when (problem) {
            Problem.BATTERY -> Triple(
                "Battery setting blocks refreshes",
                "$appName needs its battery usage set to Unrestricted. " +
                    "Without it Android delays or skips the hourly update, " +
                    "so the widget updates late or not at all.\n\n" +
                    "Tap Open App info, then Battery ▸ Unrestricted.",
                "Open App info",
            )
            Problem.DATA_SAVER -> Triple(
                "Data Saver blocks refreshes",
                "Data Saver is on, and it stops $appName from fetching readings " +
                    "in the background - so the widget never updates.\n\n" +
                    "Tap Open settings and allow $appName to use data while " +
                    "Data Saver is on. (Or turn Data Saver off.)",
                "Open settings",
            )
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(button) { _, _ ->
                when (problem) {
                    Problem.BATTERY -> openSetting(activity)
                    Problem.DATA_SAVER -> openDataSaverSetting(activity)
                }
            }
            // No "Later": the app does not work without this, so there is no
            // way past the dialog into the app. BACK still works, but it
            // closes the screen rather than the dialog - leaving is allowed,
            // carrying on without the setting is not. (When the screen was
            // opened by placing a widget, the widget is kept either way.)
            .setCancelable(true)
            .setOnCancelListener { activity.finish() }
            .show()
            .also {
                // Back cancels; tapping outside must not.
                it.setCanceledOnTouchOutside(false)
                showing[activity] = problem to it
            }
    }

    /** Opens the right settings screen for [problem] (the list's banner). */
    fun openSettingFor(activity: Activity, problem: Problem) = when (problem) {
        Problem.BATTERY -> openSetting(activity)
        Problem.DATA_SAVER -> openDataSaverSetting(activity)
    }

    /** One-line banner text for the list screen. */
    fun bannerText(context: Context, problem: Problem): String {
        val appName = context.getString(R.string.app_name)
        return when (problem) {
            Problem.BATTERY ->
                "⚠  Battery usage is not set to Unrestricted, so refreshes will be " +
                    "late or skipped. Tap to open App info, then Battery ▸ Unrestricted."
            Problem.DATA_SAVER ->
                "⚠  Data Saver is blocking $appName, so the widget cannot update. " +
                    "Tap to allow it to use data while Data Saver is on."
        }
    }

    /**
     * Opens this app's App info page, where One UI puts Battery ▸
     * Unrestricted (no restricted permission needed).
     *
     * This used to try ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS first - a
     * one-tap system "Allow?" dialog - but that needs the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission, which Play restricts.
     * The App info page needs nothing. Falls back to the system allowlist
     * screen if App info cannot be opened.
     */
    fun openSetting(activity: Activity) {
        val details = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${activity.packageName}"),
        )
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        startFirst(activity, listOf(details, list),
            "Open Settings ▸ Apps ▸ ${activity.getString(R.string.app_name)} ▸ Battery ▸ Unrestricted")
    }

    /**
     * Opens the per-app "use data while Data Saver is on" screen
     * (ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS - no permission
     * needed), falling back to App info.
     */
    fun openDataSaverSetting(activity: Activity) {
        val pkg = Uri.parse("package:${activity.packageName}")
        val direct = Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, pkg)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        startFirst(activity, listOf(direct, details),
            "Open Settings ▸ Apps ▸ ${activity.getString(R.string.app_name)} ▸ Mobile data " +
                "▸ Allow data usage while Data Saver is on")
    }

    private fun startFirst(activity: Activity, intents: List<Intent>, fallbackHint: String) {
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return
            } catch (e: Exception) {
                Log.w(TAG, "could not start ${intent.action}: $e")
            }
        }
        Toast.makeText(activity, fallbackHint, Toast.LENGTH_LONG).show()
    }
}
