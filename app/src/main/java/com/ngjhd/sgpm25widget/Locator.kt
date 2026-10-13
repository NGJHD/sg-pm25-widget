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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Device location, with no Google Play services dependency (plain
 * LocationManager).
 *
 * PRECISE location is asked for, not just approximate (10-13): Android's
 * approximate location is only good to ~3 km, and the label-point boundary
 * between North and Central runs at latitude ~1.388, through Yishun /
 * Lower Seletar, so approximate can put a home in the wrong region.
 * Approximate still works, with that caveat.
 *
 * Order of preference ([locate], used by [FetchWorker]):
 *  1. the OS's last known fix, if under [FRESH_MS] old - instant;
 *  2. a fresh fix ([current]), waiting at most [TIMEOUT_MS]. Background
 *     apps get few location updates per hour, so this can be slow or fail;
 *  3. the OS's last known fix, however old;
 *  4. (FetchWorker) the last fix this app saved, then Central.
 * 1-3 need "Allow all the time" when the app is in the background.
 */
object Locator {

    private const val TAG = "PM_LOC"
    private const val TIMEOUT_MS = 10_000L

    /** A last known fix younger than this is used as-is. */
    private const val FRESH_MS = 10 * 60_000L

    /** A fix and whether it counts as current (fresh) rather than last known. */
    data class Fix(val location: Location, val fresh: Boolean)

    /** Steps 1-3 above. Blocks; never call on the main thread. */
    fun locate(context: Context, isStopped: () -> Boolean): Fix? {
        val last = lastKnown(context)
        if (last != null && ageMs(last) < FRESH_MS) {
            Log.i(TAG, "locate: last known fix is ${ageMs(last) / 1000}s old; using it")
            return Fix(last, fresh = true)
        }
        current(context, isStopped)?.let { return Fix(it, fresh = true) }
        return last?.let { Fix(it, fresh = false) }
    }

    private fun ageMs(l: Location) =
        (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000

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
     *
     * Asks EVERY enabled provider at once and takes the first fix (10-13).
     * Asking only "fused" was measured on the emulator to wait out the whole
     * timeout with no fix, and on a phone indoors with network location off
     * fused depends on GPS, which may never get a fix.
     */
    @Suppress("MissingPermission")
    fun current(context: Context, isStopped: () -> Boolean): Location? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Locator.current on the main thread" }
        if (!hasForeground(context) || !isLocationOn(context)) return null
        val lm = manager(context)
        val providers = providers(lm)
        if (providers.isEmpty()) return null
        val done = CountDownLatch(1)
        val result = AtomicReference<Pair<String, Location>?>(null)
        val pending = AtomicInteger(providers.size)
        val cancels = mutableListOf<CancellationSignal>()
        val listeners = mutableListOf<android.location.LocationListener>()

        fun deliver(provider: String, loc: Location?) {
            if (loc != null && result.compareAndSet(null, provider to loc)) done.countDown()
            // Every provider answered "no fix": stop waiting.
            if (pending.decrementAndGet() == 0) done.countDown()
        }

        for (provider in providers) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val cancel = CancellationSignal()
                    cancels += cancel
                    lm.getCurrentLocation(provider, cancel, context.mainExecutor) { deliver(provider, it) }
                } else {
                    val listener = android.location.LocationListener { deliver(provider, it) }
                    listeners += listener
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            } catch (e: SecurityException) {
                // Foreground-only permission while the app is in the background.
                Log.w(TAG, "current: no access to $provider from here ($e)")
                deliver(provider, null)
            } catch (e: Exception) {
                Log.w(TAG, "current: $provider failed ($e)")
                deliver(provider, null)
            }
        }

        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!done.await(250, TimeUnit.MILLISECONDS)) {
            if (isStopped() || System.currentTimeMillis() > deadline) break
        }
        cancels.forEach { it.cancel() }
        listeners.forEach { runCatching { lm.removeUpdates(it) } }
        val got = result.get()
        if (got == null) {
            Log.w(TAG, "current: no fix from $providers within ${TIMEOUT_MS / 1000}s")
            return null
        }
        Log.i(TAG, "current: fix from ${got.first} (±${got.second.accuracy.toInt()} m)")
        return got.second
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
