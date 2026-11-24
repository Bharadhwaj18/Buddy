package com.shrey.buddy

import com.shrey.buddy.data.items.Light
import com.shrey.buddy.data.items.LightAction
import com.shrey.buddy.data.items.LightRoutine
import com.shrey.buddy.lights.LightVoice
import com.shrey.buddy.lights.LightVoice.Command
import com.shrey.buddy.lights.Lights
import com.shrey.buddy.lights.Wiz
import com.shrey.buddy.lights.WizPilot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LightsTest {
    private val rooms = listOf("Bedroom", "Hall")
    private fun set(s: String) = (LightVoice.parse(s, rooms, listOf("Movie mode", "Good night")) as Command.Set).action

    @Test fun `on and off`() {
        assertEquals(false, set("hey buddy turn off the lights").on)
        assertEquals(false, set("lights off").on)
        assertEquals(true, set("turn on the lights").on)
        assertEquals(listOf("Bedroom"), set("switch off the bedroom lights").rooms)
    }

    @Test fun `brightness, warmth, colour and scenes`() {
        assertEquals(40, set("lights to 40 percent").brightness)
        assertEquals(30, set("dim the hall lights").brightness)
        assertEquals(listOf("Hall"), set("dim the hall lights").rooms)
        assertEquals(100, set("brighten the lights").brightness)
        assertEquals(2700, set("make the lights warm").warmth)
        assertEquals("#FF0000", set("lights red").color)
        assertEquals(16, set("set the lights to relax").sceneId)
        assertEquals(50, set("bedroom lights fifty percent").brightness)
    }

    @Test fun `routines by name`() {
        assertEquals(Command.Routine("Movie mode"), LightVoice.parse("movie mode", rooms, listOf("Movie mode")))
        assertEquals(Command.Routine("Movie mode"), LightVoice.parse("Hey Buddy, start movie mode", rooms, listOf("Movie mode")))
        assertEquals(Command.Routine("Good night"), LightVoice.parse("good night", rooms, listOf("Good night")))
    }

    @Test fun `not about lights`() {
        assertNull(LightVoice.parse("spent 450 on lunch", rooms))
        assertNull(LightVoice.parse("remind me to call mom at 7", rooms))
    }

    @Test fun `room targeting`() {
        val all = listOf(Light("a", "A", "Bedroom", "1"), Light("b", "B", "Hall", "2"), Light("c", "C", "", "3"))
        assertEquals(3, Lights.targets(all, emptyList()).size)
        assertEquals(listOf("a"), Lights.targets(all, listOf("bedroom")).map { it.mac })
    }

    @Test fun `next fire honours time and days`() {
        val wed = LocalDateTime.of(2026, 11, 25, 20, 0) // a Wednesday, 8 pm
        assertEquals(LocalDateTime.of(2026, 11, 26, 19, 0), Lights.nextFire(LightRoutine(time = "19:00"), wed))
        assertEquals(LocalDateTime.of(2026, 11, 25, 21, 30), Lights.nextFire(LightRoutine(time = "21:30"), wed))
        // Weekends only → Saturday.
        assertEquals(LocalDateTime.of(2026, 11, 28, 19, 0), Lights.nextFire(LightRoutine(time = "19:00", days = listOf(6, 7)), wed))
        assertNull(Lights.nextFire(LightRoutine(trigger = "manual"), wed))
        assertNull(Lights.nextFire(LightRoutine(enabled = false), wed))
    }

    @Test fun `wiz messages`() {
        val msg = Wiz.setPilotMessage(WizPilot(on = true, dimming = 5, temp = 9000))
        assertTrue(msg, msg.contains("\"method\":\"setPilot\"") && msg.contains("\"dimming\":10") && msg.contains("\"temp\":6500"))
        assertEquals("""{"id":1,"method":"setPilot","params":{"state":false}}""", Wiz.setPilotMessage(WizPilot(on = false)))
        val color = Wiz.setPilotMessage(Lights.pilotFor(LightAction(color = "#FF7A00")))
        assertTrue(color, color.contains("\"r\":255") && color.contains("\"g\":122") && !color.contains("temp"))
        val s = Wiz.parseState("""{"method":"getPilot","env":"pro","result":{"mac":"a8bb50000001","rssi":-60,"state":true,"sceneId":0,"temp":2700,"dimming":60}}""", "192.168.1.20")!!
        assertEquals("a8bb50000001", s.mac); assertEquals(true, s.on); assertEquals(60, s.dimming); assertNull(s.sceneId)
    }
}
