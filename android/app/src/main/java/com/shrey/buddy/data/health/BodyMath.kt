package com.shrey.buddy.data.health

import com.shrey.buddy.data.items.BodyProfile
import java.time.LocalDate
import kotlin.math.log10
import kotlin.math.roundToInt

/** BMI, healthy range, BMR / daily calories, body-fat estimate and weight trend. */
object BodyMath {
    data class BmiBand(val label: String, val from: Double, val to: Double)

    /**
     * Asian-Indian cut-offs (WHO expert consultation / Indian consensus guidelines), which are lower
     * than the global ones because risk rises at lower BMI for South Asians.
     */
    val bands = listOf(
        BmiBand("Underweight", 0.0, 18.5),
        BmiBand("Healthy", 18.5, 23.0),
        BmiBand("Overweight", 23.0, 25.0),
        BmiBand("Obese", 25.0, 100.0),
    )

    fun bmi(kg: Double, heightCm: Double): Double = kg / ((heightCm / 100) * (heightCm / 100))
    fun band(bmi: Double): BmiBand = bands.first { bmi < it.to }

    /** Weight range for a healthy BMI at this height. */
    fun healthyRangeKg(heightCm: Double): ClosedFloatingPointRange<Double> {
        val m2 = (heightCm / 100) * (heightCm / 100)
        return (18.5 * m2)..(22.9 * m2)
    }

    fun age(p: BodyProfile, today: LocalDate = LocalDate.now()) = p.birthYear?.let { today.year - it }

    /** Mifflin–St Jeor resting energy (kcal/day). */
    fun bmr(kg: Double, p: BodyProfile): Int? {
        val h = p.heightCm ?: return null
        val a = age(p) ?: return null
        val base = 10 * kg + 6.25 * h - 5 * a
        return (base + if (p.sex == "female") -161 else 5).roundToInt()
    }

    private val activityFactor = mapOf(
        "sedentary" to 1.2, "light" to 1.375, "moderate" to 1.55, "active" to 1.725, "very_active" to 1.9,
    )

    /** Calories to maintain weight (BMR × activity). */
    fun maintenanceKcal(kg: Double, p: BodyProfile): Int? = bmr(kg, p)?.let { (it * (activityFactor[p.activity] ?: 1.375)).roundToInt() }

    /** US Navy body-fat estimate from tape measurements (needs waist + neck, and hips for women). */
    fun navyBodyFat(p: BodyProfile, waistCm: Double?, neckCm: Double?, hipsCm: Double?): Double? {
        val h = p.heightCm ?: return null
        if (waistCm == null || neckCm == null || waistCm <= neckCm) return null
        return if (p.sex == "female") {
            if (hipsCm == null) return null
            495 / (1.29579 - 0.35004 * log10(waistCm + hipsCm - neckCm) + 0.22100 * log10(h)) - 450
        } else {
            495 / (1.0324 - 0.19077 * log10(waistCm - neckCm) + 0.15456 * log10(h)) - 450
        }
    }

    /** Exponentially smoothed trend (like Happy Scale / Libra) so daily water swings don't dominate. */
    fun trend(points: List<Pair<LocalDate, Double>>, alpha: Double = 0.1): List<Pair<LocalDate, Double>> {
        var t: Double? = null
        return points.sortedBy { it.first }.map { (d, kg) ->
            t = t?.let { it + alpha * (kg - it) } ?: kg
            d to t!!
        }
    }

    /** Trend change per week over the last [days] days (negative = losing). */
    fun weeklyRate(points: List<Pair<LocalDate, Double>>, days: Long = 28): Double? {
        val tr = trend(points)
        if (tr.size < 2) return null
        val end = tr.last()
        val start = tr.lastOrNull { it.first <= end.first.minusDays(days) } ?: tr.first()
        val span = java.time.temporal.ChronoUnit.DAYS.between(start.first, end.first)
        if (span < 7) return null
        return (end.second - start.second) / span * 7
    }

    /** When the goal is reached at the current rate, or null if moving the wrong way / too slow. */
    fun goalEta(current: Double, goal: Double, weeklyRate: Double?, today: LocalDate = LocalDate.now()): LocalDate? {
        val rate = weeklyRate ?: return null
        val diff = goal - current
        if (rate == 0.0 || diff / rate <= 0) return null
        val weeks = diff / rate
        if (weeks > 260) return null
        return today.plusDays((weeks * 7).roundToInt().toLong())
    }

    /**
     * Calories burned for an activity: MET × kg × hours (Compendium of Physical Activities).
     * Falls back to 70 kg if no weight is logged.
     */
    fun activityKcal(met: Double, minutes: Double, kg: Double?) = met * (kg ?: 70.0) * minutes / 60.0
}
