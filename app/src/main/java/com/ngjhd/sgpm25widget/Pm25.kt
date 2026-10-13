package com.ngjhd.sgpm25widget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos

/**
 * The data.gov.sg real-time PM2.5 feed: parsing, nearest region, and the NEA
 * 1-hour PM2.5 bands. Pure logic apart from [fetch], so it is unit-tested on
 * the JVM.
 *
 * The feed is the 1-hour PM2.5 concentration in µg/m³ for five regions. It is
 * NOT the PSI (that is a separate endpoint, /psi, with different bands).
 * Each hourly reading appears about 15 minutes after the hour (e.g. the 11:00
 * reading's updatedTimestamp was 11:15:51), which is why the schedule fetches
 * at X:20.
 */
object Pm25 {

    const val URL_PM25 = "https://api-open.data.gov.sg/v2/real-time/api/pm25"

    data class Region(val name: String, val lat: Double, val lon: Double)

    /** One fetched reading for all five regions. */
    data class Reading(
        /** ISO-8601 hour the reading is for, e.g. "2026-10-13T11:00:00+08:00". */
        val timestamp: String,
        val values: Map<String, Int>,
        val regions: List<Region>,
    )

    /**
     * Label locations as the feed itself publishes them (regionMetadata), used
     * only if a response ever omits that block.
     */
    val DEFAULT_REGIONS = listOf(
        Region("north", 1.41803, 103.82),
        Region("south", 1.29587, 103.82),
        Region("east", 1.35735, 103.94),
        Region("west", 1.35735, 103.7),
        Region("central", 1.35735, 103.82),
    )

    const val DEFAULT_REGION = "central"

    fun parse(json: String): Reading {
        val root = JSONObject(json)
        val code = root.optInt("code", -1)
        require(code == 0) { "API code $code: ${root.optString("errorMsg")}" }
        val data = root.getJSONObject("data")

        val regions = mutableListOf<Region>()
        data.optJSONArray("regionMetadata")?.let { arr ->
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val loc = r.getJSONObject("labelLocation")
                regions += Region(r.getString("name"), loc.getDouble("latitude"), loc.getDouble("longitude"))
            }
        }

        val items = data.getJSONArray("items")
        require(items.length() > 0) { "API returned no items" }
        val item = items.getJSONObject(0)
        val readings = item.getJSONObject("readings").getJSONObject("pm25_one_hourly")
        val values = mutableMapOf<String, Int>()
        for (key in readings.keys()) values[key] = readings.getInt(key)
        require(values.isNotEmpty()) { "API returned no readings" }

        return Reading(
            timestamp = item.getString("timestamp"),
            values = values,
            regions = regions.ifEmpty { DEFAULT_REGIONS },
        )
    }

    /**
     * The region whose label location is closest to ([lat], [lon]).
     * Equirectangular distance: exact enough at Singapore's size, and the
     * longitude is scaled by cos(latitude) so east-west and north-south
     * degrees compare fairly.
     */
    fun nearestRegion(regions: List<Region>, lat: Double, lon: Double): Region {
        val k = cos(Math.toRadians(lat))
        return regions.minBy { r ->
            val dx = (r.lon - lon) * k
            val dy = r.lat - lat
            dx * dx + dy * dy
        }
    }

    /** Distance in km (equirectangular; fine at Singapore's scale). */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val k = cos(Math.toRadians((lat1 + lat2) / 2))
        val dx = (lon2 - lon1) * k
        val dy = lat2 - lat1
        return Math.sqrt(dx * dx + dy * dy) * 111.195
    }

    /**
     * NEA's 1-hour PM2.5 bands (µg/m³). [text] is the readable text colour
     * on [color].
     */
    enum class Band(val label: String, val color: Int, val text: Int) {
        NORMAL("Normal", 0xFF2E9E4F.toInt(), 0xFFFFFFFF.toInt()),
        ELEVATED("Elevated", 0xFFF2C230.toInt(), 0xFF2B2100.toInt()),
        HIGH("High", 0xFFE8772E.toInt(), 0xFFFFFFFF.toInt()),
        VERY_HIGH("Very high", 0xFFC62828.toInt(), 0xFFFFFFFF.toInt()),
    }

    fun band(value: Int): Band = when {
        value <= 55 -> Band.NORMAL
        value <= 150 -> Band.ELEVATED
        value <= 250 -> Band.HIGH
        else -> Band.VERY_HIGH
    }

    /** "central" -> "Central". */
    fun displayName(region: String): String =
        region.replaceFirstChar { it.uppercase() }

    /** Blocking GET; call off the main thread. */
    fun fetch(): Reading {
        val conn = URL(URL_PM25).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            if (status != 200) throw java.io.IOException("HTTP $status")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parse(body)
        } finally {
            conn.disconnect()
        }
    }
}
