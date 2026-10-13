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
        )
    }

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
