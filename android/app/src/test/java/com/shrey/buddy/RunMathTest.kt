package com.shrey.buddy

import com.shrey.buddy.health.RunMath
import com.shrey.buddy.health.TrackPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RunMathTest {
    // Due north from Bengaluru: 1° latitude ≈ 111.195 km.
    private val metresPerDeg = 111_195.0

    /** A straight run north: [paces] s/km for each km, a point every 10 m. */
    private fun track(vararg paces: Int): List<TrackPoint> {
        val pts = mutableListOf(TrackPoint(12.97, 77.59, 900.0, 0.0))
        var t = 0.0
        paces.forEach { pace ->
            repeat(100) {
                t += pace / 100.0
                val last = pts.last()
                pts += TrackPoint(last.lat + 10 / metresPerDeg, 77.59, 900.0, t)
            }
        }
        return pts
    }

    @Test fun `splits per km`() {
        val s = RunMath.splits(track(330, 300, 315))
        assertEquals(3, s.size)
        assertEquals(330.0, (s[0].seconds).toDouble(), 1.0)
        assertEquals(300.0, (s[1].pace).toDouble(), 2.0)
        assertEquals(315.0, (s[2].pace).toDouble(), 2.0)
    }

    @Test fun `best 1k finds the fastest km anywhere`() {
        assertEquals(300.0, (RunMath.bestEffort(track(330, 300, 315), 1000.0)!!).toDouble(), 2.0)
        assertNull(RunMath.bestEffort(track(330), 5000.0))
    }

    @Test fun `moving time ignores standing still`() {
        val t = track(300).toMutableList()
        val last = t.last()
        t += last.copy(t = last.t + 120) // stood at a signal for 2 minutes
        assertEquals(300.0, (RunMath.movingSeconds(t)).toDouble(), 2.0)
    }

    @Test fun `calories scale with weight and distance`() {
        // 5 km in 25 min at 70 kg: VO2 = 3.5 + 0.2 × 200 = 43.5 ml/kg/min → 43.5 × 70 / 1000 × 5 × 25 ≈ 381 kcal.
        val k = RunMath.kcal("run", 5000.0, 1500, 70.0)
        assertEquals(381.0, (k).toDouble(), 2.0)
        assertEquals(true, RunMath.kcal("run", 5000.0, 1500, 85.0) > k)
    }
}
