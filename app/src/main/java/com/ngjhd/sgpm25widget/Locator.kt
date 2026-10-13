package com.ngjhd.sgpm25widget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Device location, with no Google Play services dependency (plain
 * LocationManager). Coarse location is plenty: the five regions are
 * kilometres apart.
 *
 * Order of preference, used by [FetchWorker]:
 *  1. a fresh fix ([current]) - needs "Allow all the time" when the app is in
 *     the background, which it is for every scheduled update;
 *  2. the OS's last known fix ([lastKnown]) - same permission rule;
 *  3. the last fix this app saved ([Store.loadLocation]);
 *  4. Central.
 */
object Locator {

    private const val TAG = "PM_LOC"
    private const val TIMEOUT_MS = 20_000L

    fun hasForeground(context: Context): Boolean =
        granted(context, Manifest.permission.ACCESS_COARSE_LOCATION) ||
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    /** "Allow all the time". Implied by foreground access before Android 10. */
    fun hasBackground(context: Context): Boolean =
        hasForeground(context) && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                granted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            )

    private fun granted(context: Context, perm: String) =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    private fun manager(context: Context) =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun isLocationOn(context: Context): Boolean {
        val lm = manager(context)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
        }
    }

    private fun providers(lm: LocationManager): List<String> {
        val out = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.hasProvider(LocationManager.FUSED_PROVIDER)) {
            out += LocationManager.FUSED_PROVIDER
        }
        out += LocationManager.NETWORK_PROVIDER
        out += LocationManager.GPS_PROVIDER
        return out.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    }

    /**
     * A fresh fix, or null. Blocks up to [TIMEOUT_MS] (plus [isStopped]
     * polling); never call it on the main thread.
     */
    @Suppress("MissingPermission")
    fun current(context: Context, isStopped: () -> Boolean): Location? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Locator.current on the main thread" }
        if (!hasForeground(context) || !isLocationOn(context)) return null
        val lm = manager(context)
        val provider = providers(lm).firstOrNull() ?: return null
        val latch = CountDownLatch(1)
        var result: Location? = null
        val cancel = CancellationSignal()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lm.getCurrentLocation(provider, cancel, context.mainExecutor) { loc ->
                    result = loc
                    latch.countDown()
                }
            } else {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(
                    provider,
                    { loc: Location -> result = loc; latch.countDown() },
                    Looper.getMainLooper(),
                )
            }
        } catch (e: SecurityException) {
            // Foreground-only permission while the app is in the background.
            Log.w(TAG, "current: no access from here ($e)")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "current: $provider failed ($e)")
            return null
        }
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!latch.await(250, TimeUnit.MILLISECONDS)) {
            if (isStopped() || System.currentTimeMillis() > deadline) {
                cancel.cancel()
                Log.w(TAG, "current: no fix from $provider within ${TIMEOUT_MS / 1000}s")
                return null
            }
        }
        Log.i(TAG, "current: fix from $provider")
        return result
    }

    /** The newest fix any provider still holds, or null. */
    @Suppress("MissingPermission")
    fun lastKnown(context: Context): Location? {
        if (!hasForeground(context)) return null
        val lm = manager(context)
        return try {
            lm.allProviders
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }
}
