package com.shrey.buddy

import androidx.health.connect.client.records.ExerciseRoute
import com.shrey.buddy.data.health.HealthSync
import com.shrey.buddy.data.health.SessionInfo
import com.shrey.buddy.data.items.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WatchRunTest {
    private val min = 60_000L

    @Test fun `the same run from two apps becomes one, preferring the copy with a route`() {
        val a = SessionInfo("a", 0, 40 * min, hasRoute = false, source = "com.other.app")
        val b = SessionInfo("b", 1 * min, 41 * min, hasRoute = true, source = "com.garmin.android.apps.connectmobile")
        val kept = HealthSync.dedupe(listOf(a, b))
        assertEquals(listOf("b"), kept.map { it.id })
    }

    @Test fun `separate runs on the same day both stay`() {
        val morning = SessionInfo("m", 0, 30 * min, false, "x")
        val evening = SessionInfo("e", 600 * min, 640 * min, false, "x")
        assertEquals(2, HealthSync.dedupe(listOf(morning, evening)).size)
    }

    @Test fun `a route fills the map track, splits and drops pauses`() {
        val start = Instant.parse("2026-11-20T01:00:00Z")
        // ~11 m per point every 4 s (about 6 min/km) heading north, with a 2-minute stop in the middle.
        val pts = (0 until 200).map { i ->
            val t = if (i < 100) i * 4L else i * 4L + 120
            ExerciseRoute.Location(time = start.plusSeconds(t), latitude = 12.9 + i * 0.0001, longitude = 77.6)
        }
        val r = HealthSync.runFromRoute(RunSummary(durationS = 920, startedAt = start.toEpochMilli()), start, pts)
        assertTrue(r.track.isNotEmpty())
        assertEquals(200, r.tSec.size)
        assertTrue("pause removed: ${r.tSec.last()}", r.tSec.last() < 820)
        assertTrue("about 2.2 km: ${r.distanceM}", r.distanceM in 2100.0..2300.0)
        assertEquals(3, r.splits.size)
    }
}
