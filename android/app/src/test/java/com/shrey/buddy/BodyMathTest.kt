package com.shrey.buddy

import com.shrey.buddy.data.health.BodyMath
import com.shrey.buddy.data.items.BodyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class BodyMathTest {
    private val me = BodyProfile(heightCm = 175.0, sex = "male", birthYear = 2000, activity = "moderate")

    @Test fun `bmi and asian bands`() {
        val bmi = BodyMath.bmi(72.0, 175.0)
        assertEquals(23.5, bmi, 0.05)
        assertEquals("Overweight", BodyMath.band(bmi).label) // healthy tops out at 22.9 for South Asians
        assertEquals("Healthy", BodyMath.band(21.0).label)
        val range = BodyMath.healthyRangeKg(175.0)
        assertEquals(56.7, range.start, 0.1)
        assertEquals(70.1, range.endInclusive, 0.1)
    }

    @Test fun `bmr and maintenance`() {
        // 10*72 + 6.25*175 - 5*26 + 5 = 1688.75
        assertEquals(1689, BodyMath.bmr(72.0, me, ))
        assertEquals(2618, BodyMath.maintenanceKcal(72.0, me))
        assertNull(BodyMath.bmr(72.0, BodyProfile()))
    }

    @Test fun `navy body fat`() {
        val bf = BodyMath.navyBodyFat(me, waistCm = 85.0, neckCm = 38.0, hipsCm = null)
        assertNotNull(bf)
        assertEquals(17.0, bf!!, 1.5)
    }

    @Test fun `trend smooths and rate and eta`() {
        val start = LocalDate.of(2026, 9, 1)
        // Losing 0.5 kg a week, with ±0.6 kg daily noise.
        val pts = (0 until 35).map { i -> start.plusDays(i.toLong()) to (80.0 - 0.5 * i / 7 + if (i % 2 == 0) 0.6 else -0.6) }
        val rate = BodyMath.weeklyRate(pts)!!
        assertEquals(-0.5, rate, 0.15)
        val eta = BodyMath.goalEta(77.5, 75.0, rate, start.plusDays(35))
        assertNotNull(eta)
        assertNull(BodyMath.goalEta(77.5, 80.0, rate)) // goal is the other way
    }

    @Test fun `activity calories from MET`() {
        // Running ~9.8 MET, 30 min, 72 kg ≈ 353 kcal
        assertEquals(352.8, BodyMath.activityKcal(9.8, 30.0, 72.0), 0.1)
    }
}
