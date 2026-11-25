package com.shrey.buddy

import com.shrey.buddy.data.items.Hum
import com.shrey.buddy.music.Hums
import com.shrey.buddy.music.Key
import com.shrey.buddy.music.Midi
import com.shrey.buddy.music.NoteEvent
import com.shrey.buddy.music.NoteNames
import com.shrey.buddy.music.Tempo
import com.shrey.buddy.music.Theory
import com.shrey.buddy.music.Transcriber
import com.shrey.buddy.music.Yin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class MusicTest {
    private val rate = 22050
    private val hopMs = 256 * 1000f / rate

    private fun sine(hz: Double, n: Int, amp: Double = 0.4, start: Int = 0) =
        FloatArray(n) { i -> (amp * (sin(2 * PI * hz * (start + i) / rate) + 0.3 * sin(4 * PI * hz * (start + i) / rate))).toFloat() }

    @Test fun `yin finds the pitch of a hummed-like tone`() {
        for (hz in listOf(98.0, 220.0, 440.0, 660.0)) {
            val got = Yin.detect(sine(hz, 1024), rate)
            assertNotNull("no pitch at $hz", got)
            assertTrue("$hz → ${got!!.first}", abs(got.first - hz) / hz < 0.01)
        }
        val noise = Random(1).let { r -> FloatArray(1024) { r.nextFloat() - 0.5f } }
        assertNull(Yin.detect(noise, rate))
    }

    @Test fun `note names`() {
        assertEquals("C4", NoteNames.name(60))
        assertEquals("A4", NoteNames.name(69))
        assertEquals("Bb3", NoteNames.name(58, flats = true))
        assertEquals(69f, NoteNames.hzToMidi(440f), 0.001f)
        assertEquals(-20, NoteNames.cents(59.8f))
    }

    private fun frames(vararg notes: Pair<Float, Int>, gapFrames: Int = 3, jitter: Float = 0.08f): List<Float?> {
        val r = Random(7)
        val out = mutableListOf<Float?>()
        for ((m, ms) in notes) {
            repeat((ms / hopMs).toInt()) { out += m + (r.nextFloat() - 0.5f) * 2 * jitter }
            repeat(gapFrames) { out += null }
        }
        return out
    }

    @Test fun `frames become notes`() {
        val notes = Transcriber.notes(frames(60f to 300, 62f to 300, 64f to 600), hopMs)
        assertEquals(listOf(60, 62, 64), notes.map { it.midi })
        assertTrue(notes[2].len in 550..650)
    }

    @Test fun `a consistently flat hummer still lands on the meant notes`() {
        // Everything ~45 cents flat: without retuning, half the frames would round down a semitone.
        val notes = Transcriber.notes(frames(59.55f to 400, 61.55f to 400, 63.55f to 400, 64.55f to 400, jitter = 0.12f), hopMs)
        assertEquals(listOf(60, 62, 64, 65), notes.map { it.midi })
    }

    @Test fun `octave blips and tiny slides are cleaned up`() {
        val f = frames(57f to 500).toMutableList()
        f[10] = 69f; f[11] = 69.1f // octave jump for two frames
        val notes = Transcriber.notes(f, hopMs)
        assertEquals(listOf(57), notes.map { it.midi })
        // A 30 ms slide between two notes is not a note of its own.
        val slide = Transcriber.notes(frames(60f to 300, 61f to 30, 62f to 300, gapFrames = 0), hopMs)
        assertEquals(listOf(60, 62), slide.map { it.midi })
    }

    @Test fun `real pipeline from audio to notes`() {
        val tune = listOf(220.0 to 400, 246.94 to 400, 261.63 to 400, 329.63 to 800) // A3 B3 C4 E4
        val audio = mutableListOf<Float>()
        for ((hz, ms) in tune) {
            val n = ms * rate / 1000
            audio += sine(hz, n, start = audio.size).toList()
            audio += List(rate / 20) { 0f } // 50 ms breath
        }
        val pcm = audio.toFloatArray()
        val f = mutableListOf<Float?>()
        var i = 0
        while (i + 1024 <= pcm.size) {
            val w = pcm.copyOfRange(i, i + 1024)
            f += if (Yin.rms(w) < 0.01f) null else Yin.detect(w, rate)?.let { NoteNames.hzToMidi(it.first) }
            i += 256
        }
        assertEquals(listOf(57, 59, 60, 64), Transcriber.notes(f, hopMs).map { it.midi })
    }

    private fun seq(vararg m: Int, len: Int = 500) = m.mapIndexed { i, x -> NoteEvent(i * len, len - 50, x) }

    @Test fun `finds the key`() {
        assertEquals(Key(0, false), Theory.key(seq(60, 62, 64, 65, 67, 69, 71, 72, 67, 64, 60)))
        assertEquals("A minor", Theory.key(seq(57, 60, 64, 57, 59, 60, 62, 64, 57, 52, 57, 60, 57))!!.name)
        assertNull(Theory.key(seq(60, 62)))
    }

    @Test fun `key names read back, flat keys use flats`() {
        for (k in listOf(Key(10, false), Key(9, true), Key(6, false), Key(1, true))) assertEquals(k, Key.parse(k.name))
        assertEquals("Bb major", Key(10, false).name)
        assertEquals("F#", Theory.diatonic(Key(2, false)).first { it.roman == "iii" }.symbol(false).removeSuffix("m"))
        assertEquals("Bb", Theory.diatonic(Key(5, false)).first { it.roman == "IV" }.symbol(true))
    }

    @Test fun `one chord per bar that fits`() {
        // 120 bpm → a bar is 2 s. Bars: C E G | F A C | G B D | C E G
        val bars = listOf(listOf(60, 64, 67, 64), listOf(65, 69, 72, 69), listOf(67, 71, 74, 71), listOf(72, 67, 64, 60))
        val notes = bars.flatten().mapIndexed { i, m -> NoteEvent(i * 500, 450, m) }
        val chords = Theory.chords(notes, Key(0, false), 120)
        assertEquals(listOf("C", "F", "G", "C"), chords.map { it.symbol(false) })
    }

    @Test fun `tempo from note starts`() {
        val r = Random(3)
        val notes = (0 until 16).map { NoteEvent(it * 500 + r.nextInt(-20, 20), 400, 60) } // 120 bpm, a little loose
        assertEquals(120.0, Tempo.bpm(notes)!!.toDouble(), 3.0)
        val dotted = listOf(0, 750, 1000, 1500, 2250, 2500, 3000, 3750, 4000).map { NoteEvent(it, 200, 60) } // 120 bpm, long-short
        assertEquals(120.0, Tempo.bpm(dotted)!!.toDouble(), 3.0)
        assertNull(Tempo.bpm(seq(60, 62)))
    }

    @Test fun `snap to beat`() {
        val snapped = Tempo.snap(listOf(NoteEvent(0, 240, 60), NoteEvent(130, 110, 62), NoteEvent(510, 480, 64)), bpm = 120)
        // 16ths at 120 bpm are 125 ms.
        assertEquals(listOf(0, 125, 500), snapped.map { it.at })
        assertTrue(snapped.zipWithNext().all { (a, b) -> a.end <= b.at })
    }

    @Test fun `midi file is well formed`() {
        assertArrayEquals(byteArrayOf(0), Midi.vlq(0))
        assertArrayEquals(byteArrayOf(0x7F), Midi.vlq(127))
        assertArrayEquals(byteArrayOf(0x81.toByte(), 0), Midi.vlq(128))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0x7F), Midi.vlq(0x3FFF))

        val notes = seq(60, 62, 64)
        val bytes = Midi.file(notes, 120, Theory.chords(notes, Key(0, false), 120))
        assertEquals("MThd", String(bytes.copyOfRange(0, 4)))
        assertEquals(2, bytes[11].toInt()) // two tracks: tune + chords
        assertEquals(480, ((bytes[12].toInt() and 0xFF) shl 8) or (bytes[13].toInt() and 0xFF))
        // Both track chunks are present and their lengths add up to the file.
        var p = 14; var tracks = 0
        while (p < bytes.size) {
            assertEquals("MTrk", String(bytes.copyOfRange(p, p + 4)))
            val len = (0 until 4).fold(0) { acc, k -> (acc shl 8) or (bytes[p + 4 + k].toInt() and 0xFF) }
            p += 8 + len; tracks++
        }
        assertEquals(bytes.size, p)
        assertEquals(2, tracks)
        // Three note-ons for the tune.
        val noteOns = bytes.indices.count { it + 2 < bytes.size && bytes[it] == 0x90.toByte() && bytes[it + 2] == 96.toByte() }
        assertEquals(3, noteOns)
    }

    @Test fun `a take becomes a vault entry and tempo and transpose rework it`() {
        val f = frames(60f to 450, 64f to 450, 67f to 450, 72f to 900, 67f to 450, 64f to 450, 60f to 900)
        val hum = Hums.analyse(listOf<Float?>(null, null) + f, hopMs, "Test", "x.wav", now = 1L)!!
        assertEquals("C major", hum.key)
        assertEquals(0, hum.notes.first().at)
        assertTrue(hum.audioOffsetMs > 0)
        assertTrue(hum.chords.isNotEmpty() && hum.chords.first() == "C")
        val up = Hums.transpose(hum, 2)
        assertEquals("D major", up.key)
        assertEquals(hum.notes.map { it.midi + 2 }, up.notes.map { it.midi })
        assertEquals(60, Hums.withTempo(hum, 60).bpm)
        assertNull(Hums.analyse(frames(60f to 300), hopMs, "x", null))
        assertEquals(Hum().bpm, 100)
    }
}
