package com.ngjhd.sgpm25widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class Pm25Test {

    // A real response, 2026-10-13 11:00 SGT.
    private val sample = """
        {"code":0,"data":{"regionMetadata":[
        {"name":"north","labelLocation":{"latitude":1.41803,"longitude":103.82}},
        {"name":"south","labelLocation":{"latitude":1.29587,"longitude":103.82}},
        {"name":"east","labelLocation":{"latitude":1.35735,"longitude":103.94}},
        {"name":"west","labelLocation":{"latitude":1.35735,"longitude":103.7}},
        {"name":"central","labelLocation":{"latitude":1.35735,"longitude":103.82}}],
        "items":[{"date":"2026-10-13","updatedTimestamp":"2026-10-13T11:15:51+08:00",
        "timestamp":"2026-10-13T11:00:00+08:00",
        "readings":{"pm25_one_hourly":{"north":72,"south":82,"west":125,"east":101,"central":115}}}]},
        "errorMsg":""}
    """.trimIndent()

    @Test fun parsesTheFeed() {
        val r = Pm25.parse(sample)
        assertEquals("2026-10-13T11:00:00+08:00", r.timestamp)
        assertEquals(mapOf("north" to 72, "south" to 82, "west" to 125, "east" to 101, "central" to 115), r.values)
        assertEquals(5, r.regions.size)
    }

    @Test fun rejectsAnApiError() {
        assertThrows(IllegalArgumentException::class.java) {
            Pm25.parse("""{"code":4,"data":null,"errorMsg":"bad"}""")
        }
    }

    @Test fun nearestRegionForKnownPlaces() {
        val regions = Pm25.DEFAULT_REGIONS
        fun at(lat: Double, lon: Double) = Pm25.nearestRegion(regions, lat, lon).name
        assertEquals("north", at(1.4360, 103.7860))   // Woodlands
        assertEquals("south", at(1.2840, 103.8510))   // Raffles Place
        assertEquals("east", at(1.3530, 103.9450))    // Tampines
        assertEquals("west", at(1.3330, 103.7420))    // Jurong East
        assertEquals("central", at(1.3700, 103.8490)) // Ang Mo Kio
        assertEquals("east", at(1.3644, 103.9915))    // Changi Airport
    }

    @Test fun bandsFollowNea() {
        assertEquals(Pm25.Band.NORMAL, Pm25.band(0))
        assertEquals(Pm25.Band.NORMAL, Pm25.band(55))
        assertEquals(Pm25.Band.ELEVATED, Pm25.band(56))
        assertEquals(Pm25.Band.ELEVATED, Pm25.band(150))
        assertEquals(Pm25.Band.HIGH, Pm25.band(151))
        assertEquals(Pm25.Band.HIGH, Pm25.band(250))
        assertEquals(Pm25.Band.VERY_HIGH, Pm25.band(251))
    }

    @Test fun nextFetchIsTwentyPast() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Singapore"))
        fun ms(h: Int, m: Int, s: Int = 0) = Calendar.getInstance().apply {
            set(2026, Calendar.OCTOBER, 13, h, m, s); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals(ms(11, 20), Scheduling.nextFetchTime(ms(11, 5)))
        assertEquals(ms(12, 20), Scheduling.nextFetchTime(ms(11, 20)))
        assertEquals(ms(12, 20), Scheduling.nextFetchTime(ms(11, 21)))
        assertEquals(ms(0, 20) + 24 * 3600_000L, Scheduling.nextFetchTime(ms(23, 59)))
        assertEquals(ms(11, 20), Scheduling.lastFetchTime(ms(11, 59)))
        assertEquals(ms(10, 20), Scheduling.lastFetchTime(ms(11, 19)))
    }
}
