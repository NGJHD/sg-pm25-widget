package com.ngjhd.sgpm25widget

import android.content.Context
import org.json.JSONObject

/**
 * The app's only state: the last reading (shared by every widget - they all
 * show the device's region) and the last location the app obtained.
 *
 * The location is kept here rather than relying on
 * LocationManager.getLastKnownLocation, because that call itself needs
 * location access - in the background, "Allow all the time". A location
 * saved while the app was open still works when only "While using the app"
 * was granted.
 */
class Store(context: Context) {

    companion object {
        const val MIN_TEXT_SCALE = 80
        const val MAX_TEXT_SCALE = 160
        const val TEXT_SCALE_STEP = 10
        const val DEFAULT_TEXT_SCALE = 100
    }

    private val prefs = context.applicationContext.getSharedPreferences("pm25", Context.MODE_PRIVATE)

    /** Where the location used for the shown value came from. */
    enum class Source { CURRENT, LAST_KNOWN, DEFAULT }

    data class Shown(
        val region: String,
        val value: Int,
        /** Hour the reading is for (ISO-8601). */
        val timestamp: String,
        /** Wall-clock ms when it was fetched. */
        val fetchedAt: Long,
        val source: Source,
        /** All five regions, for the app screen. */
        val all: Map<String, Int>,
        /** Diagnostics for the app screen: total and location time. */
        val tookMs: Long = -1,
        val locMs: Long = -1,
        /** Accuracy of the fix used, metres; -1 if unknown or none. */
        val accuracyM: Int = -1,
    )

    fun saveShown(s: Shown) {
        val all = JSONObject().apply { s.all.forEach { (k, v) -> put(k, v) } }
        prefs.edit()
            .putString("region", s.region)
            .putInt("value", s.value)
            .putString("timestamp", s.timestamp)
            .putLong("fetchedAt", s.fetchedAt)
            .putString("source", s.source.name)
            .putString("all", all.toString())
            .putLong("tookMs", s.tookMs)
            .putLong("locMs", s.locMs)
            .putInt("accuracyM", s.accuracyM)
            .remove("error")
            .apply()
    }

    fun loadShown(): Shown? {
        val region = prefs.getString("region", null) ?: return null
        val all = mutableMapOf<String, Int>()
        prefs.getString("all", null)?.let { str ->
            val o = JSONObject(str)
            for (k in o.keys()) all[k] = o.getInt(k)
        }
        return Shown(
            region = region,
            value = prefs.getInt("value", 0),
            timestamp = prefs.getString("timestamp", "") ?: "",
            fetchedAt = prefs.getLong("fetchedAt", 0L),
            source = runCatching { Source.valueOf(prefs.getString("source", "")!!) }
                .getOrDefault(Source.DEFAULT),
            all = all,
            tookMs = prefs.getLong("tookMs", -1),
            locMs = prefs.getLong("locMs", -1),
            accuracyM = prefs.getInt("accuracyM", -1),
        )
    }

    /** Why the last attempt failed (cleared by the next success). */
    fun saveError(message: String) {
        prefs.edit().putString("error", message).putLong("errorAt", System.currentTimeMillis()).apply()
    }

    /** (message, when) of the last failure since the last success, or null. */
    fun loadError(): Pair<String, Long>? {
        val m = prefs.getString("error", null) ?: return null
        return m to prefs.getLong("errorAt", 0L)
    }

    /** Wall-clock ms when the saved fix was taken, or 0. */
    fun locationTime(): Long = prefs.getLong("locAt", 0L)

    /**
     * Widget text size, percent of the layout's sizes (user, 10-13).
     * Stored as a pref so every widget and the in-app preview agree.
     */
    var textScalePercent: Int
        get() = prefs.getInt("textScale", DEFAULT_TEXT_SCALE)
            .coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)
        set(v) = prefs.edit().putInt("textScale", v.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE)).apply()

    fun saveLocation(lat: Double, lon: Double, at: Long) {
        prefs.edit()
            .putLong("lat", java.lang.Double.doubleToRawLongBits(lat))
            .putLong("lon", java.lang.Double.doubleToRawLongBits(lon))
            .putLong("locAt", at)
            .apply()
    }

    /** (lat, lon), or null if the app has never had a location. */
    fun loadLocation(): Pair<Double, Double>? {
        if (!prefs.contains("lat")) return null
        return java.lang.Double.longBitsToDouble(prefs.getLong("lat", 0)) to
            java.lang.Double.longBitsToDouble(prefs.getLong("lon", 0))
    }
}
