package com.shrey.buddy.music

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One analysis frame: [midi] is fractional (60.3 = a little sharp of middle C), null when nothing is sung. */
data class PitchReading(val midi: Float?, val level: Float, val confidence: Float = 0f)

/**
 * The YIN pitch detector (de Cheveigné & Kawahara, 2002). Works on one voice at a time, which is
 * exactly what humming and singing are. Pure Kotlin, no libraries.
 */
object Yin {
    /** Lowest / highest pitch we look for: a low male hum (~D2) up to a high whistle-ish note. */
    const val MIN_HZ = 70f
    const val MAX_HZ = 1100f

    /** Hz of the frame, or null when it isn't a clear single pitch. Also returns the confidence (0–1). */
    fun detect(x: FloatArray, sampleRate: Int, threshold: Float = 0.15f): Pair<Float, Float>? {
        val n = x.size
        val tauMax = min(n / 2, (sampleRate / MIN_HZ).toInt())
        val tauMin = max(2, (sampleRate / MAX_HZ).toInt())
        val w = n - tauMax
        if (w <= 0 || tauMax <= tauMin) return null

        // Difference function.
        val d = FloatArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var s = 0f
            for (i in 0 until w) {
                val v = x[i] - x[i + tau]
                s += v * v
            }
            d[tau] = s
        }
        // Cumulative mean normalised difference.
        val cm = FloatArray(tauMax + 1)
        cm[0] = 1f
        var running = 0f
        for (tau in 1..tauMax) {
            running += d[tau]
            cm[tau] = if (running == 0f) 1f else d[tau] * tau / running
        }
        // First dip under the threshold, walked down to its bottom.
        var tau = -1
        var t = tauMin
        while (t <= tauMax) {
            if (cm[t] < threshold) {
                while (t + 1 <= tauMax && cm[t + 1] < cm[t]) t++
                tau = t
                break
            }
            t++
        }
        if (tau < 0) return null
        // Parabolic interpolation for a sub-sample period.
        val better = if (tau in 1 until tauMax) {
            val a = cm[tau - 1]; val b = cm[tau]; val c = cm[tau + 1]
            val den = a + c - 2 * b
            if (den != 0f) tau + (a - c) / (2 * den) else tau.toFloat()
        } else tau.toFloat()
        val hz = sampleRate / better
        if (hz < MIN_HZ || hz > MAX_HZ) return null
        return hz to (1f - cm[tau]).coerceIn(0f, 1f)
    }

    fun rms(x: FloatArray): Float {
        var s = 0.0
        for (v in x) s += v * v
        return sqrt(s / x.size.coerceAtLeast(1)).toFloat()
    }
}

object NoteNames {
    private val SHARP = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val FLAT = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    fun hzToMidi(hz: Float): Float = 69f + 12f * log2(hz / 440f)
    fun midiToHz(midi: Float): Float = 440f * 2f.pow((midi - 69f) / 12f)

    /** 0–11, C = 0. */
    fun pc(midi: Int): Int = ((midi % 12) + 12) % 12

    fun pcName(pc: Int, flats: Boolean = false): String = (if (flats) FLAT else SHARP)[((pc % 12) + 12) % 12]

    /** "C4" for 60. */
    fun name(midi: Int, flats: Boolean = false): String = pcName(pc(midi), flats) + (Math.floorDiv(midi, 12) - 1)

    /** How far off the nearest note, in cents (-50..50). */
    fun cents(midi: Float): Int = ((midi - midi.roundToInt()) * 100).roundToInt()

    fun isBlackKey(midi: Int) = pc(midi) in setOf(1, 3, 6, 8, 10)

    @Suppress("unused")
    fun closeTo(a: Float, b: Float, cents: Int) = abs(a - b) * 100 <= cents
}
