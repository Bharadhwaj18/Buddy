package com.shrey.buddy.desktop

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AgendaTest {
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private fun ms(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()
    private var n = 0

    private fun task(
        title: String,
        remind: LocalDateTime? = null,
        due: LocalDateTime? = null,
        done: Boolean = false,
        myDay: Boolean = false,
        priority: String = "normal",
    ) = Task(buildJsonObject {
        put("id", "00000000-0000-0000-0000-%012d".format(++n))
        put("title", title)
        put("status", if (done) "done" else "todo")
        put("remind_at", remind?.let { ms(it).iso() })
        put("due_at", due?.let { ms(it).iso() })
        put("completed_at", if (done) ms(today.atTime(8, 0)).iso() else null)
        put("my_day", if (myDay) today.toString() else null)
        put("priority", priority)
        put("updated_at", "2026-11-20T10:00:00+00:00")
    })

    @Test
    fun sortsTodayIntoSections() {
        val now = ms(today.atTime(12, 0))
        val all = listOf(
            task("Late from yesterday", remind = today.minusDays(1).atTime(9, 0)),
            task("Morning reminder", remind = today.atTime(9, 0)),
            task("Afternoon reminder", remind = today.atTime(16, 0)),
            task("Due today", due = today.atStartOfDay()),
            task("In My Day", myDay = true, priority = "high"),
            task("Finished", done = true),
            task("Tomorrow", remind = today.plusDays(1).atTime(9, 0)),
            task("No date"),
        )
        val a = Agenda.build(all, now, zone)
        assertEquals(listOf("Late from yesterday"), a.overdue.map { it.title })
        assertEquals(listOf("Morning reminder", "Afternoon reminder"), a.reminders.map { it.title })
        assertEquals("high priority first", listOf("In My Day", "Due today"), a.tasks.map { it.title })
        assertEquals(listOf("Finished"), a.done.map { it.title })
        assertEquals("Afternoon reminder", a.next?.title)
        assertEquals("overdue + this morning's", 2, a.late)
        assertEquals(1, a.tomorrow)
        assertEquals(5, a.left)
        assertEquals(6, a.total)
    }

    @Test
    fun themeFollowsTheClock() {
        val s = Settings(theme = ThemeMode.BY_TIME, darkFrom = "19:00", lightFrom = "07:00")
        assertTrue(!BuddyTheme.forNow(s, LocalTime.of(12, 0)).dark)
        assertTrue(BuddyTheme.forNow(s, LocalTime.of(19, 0)).dark)
        assertTrue(BuddyTheme.forNow(s, LocalTime.of(2, 0)).dark)
        assertTrue(!BuddyTheme.forNow(s, LocalTime.of(7, 0)).dark)
        assertTrue(BuddyTheme.forNow(s.copy(theme = ThemeMode.DARK), LocalTime.NOON).dark)
    }
}
