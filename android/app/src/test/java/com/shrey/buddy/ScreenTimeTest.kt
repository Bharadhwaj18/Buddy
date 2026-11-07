package com.shrey.buddy

import android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED
import android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
import android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED
import android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE
import com.shrey.buddy.health.ScreenTime
import com.shrey.buddy.health.UsageEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenTimeTest {
    private val min = 60_000L
    private fun ev(t: Long, type: Int, pkg: String, cls: String = "Main") = UsageEvent(t * min, type, pkg, cls)
    private fun total(list: List<com.shrey.buddy.health.Session>, pkg: String) = list.filter { it.pkg == pkg }.sumOf { it.end - it.start } / min

    @Test fun `simple open and close`() {
        val s = ScreenTime.sessions(listOf(ev(10, ACTIVITY_RESUMED, "yt"), ev(25, ACTIVITY_PAUSED, "yt")), 0, 100 * min)
        assertEquals(15L, total(s, "yt"))
    }

    @Test fun `switching activities inside one app is one session`() {
        val s = ScreenTime.sessions(
            listOf(
                ev(0, ACTIVITY_RESUMED, "wa", "List"), ev(5, ACTIVITY_RESUMED, "wa", "Chat"),
                ev(5, ACTIVITY_PAUSED, "wa", "List"), ev(12, ACTIVITY_STOPPED, "wa", "Chat"),
            ),
            0, 100 * min,
        )
        assertEquals(1, s.size)
        assertEquals(12L, total(s, "wa"))
    }

    @Test fun `screen off ends the session even without a pause`() {
        val s = ScreenTime.sessions(listOf(ev(0, ACTIVITY_RESUMED, "ig"), ev(30, SCREEN_NON_INTERACTIVE, "android")), 0, 100 * min)
        assertEquals(30L, total(s, "ig"))
    }

    @Test fun `app open across midnight only counts from midnight`() {
        // Window is minute 60..120 ("today"); the app opened at 50 and closed at 70.
        val s = ScreenTime.sessions(listOf(ev(50, ACTIVITY_RESUMED, "yt"), ev(70, ACTIVITY_PAUSED, "yt")), 60 * min, 120 * min)
        assertEquals(10L, total(s, "yt"))
    }

    @Test fun `longest screen-off stretch is the sleep guess`() {
        val h = 60 * min
        // Off 23:00–07:30 with a 2-minute check at 03:00; window 20:00–12:00.
        val events = listOf(23 * h to false, 27 * h to true, 27 * h + 2 * min to false, 31 * h + 30 * min to true)
        val gap = ScreenTime.longestOff(events, 20 * h, 36 * h)!!
        assertEquals(23 * h, gap.first) // the 2-minute glance at 3 am is bridged
        assertEquals(31 * h + 30 * min, gap.second)
        // A long on-period (reading in bed for an hour) does split it.
        val split = listOf(23 * h to false, 25 * h to true, 26 * h to false, 31 * h to true)
        assertEquals(26 * h, ScreenTime.longestOff(split, 20 * h, 36 * h)!!.first)
    }

    @Test fun `app still open now counts up to now`() {
        val s = ScreenTime.sessions(listOf(ev(90, ACTIVITY_RESUMED, "chrome")), 0, 100 * min)
        assertEquals(10L, total(s, "chrome"))
    }
}
