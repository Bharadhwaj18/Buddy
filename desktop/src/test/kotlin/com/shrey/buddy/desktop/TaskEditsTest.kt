package com.shrey.buddy.desktop

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class TaskEditsTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun ms(dt: LocalDateTime) = dt.atZone(zone).toInstant().toEpochMilli()

    private fun row(remind: LocalDateTime?, repeat: String? = null, due: LocalDateTime? = null) = Task(
        buildJsonObject {
            put("id", "11111111-1111-1111-1111-111111111111")
            put("user_id", "someone")
            put("server_updated_at", "2026-11-20T10:00:00+00:00")
            put("title", "Weekly report")
            put("status", "todo")
            put("created_at", "2026-11-01T10:00:00+00:00")
            put("updated_at", "2026-11-20T10:00:00+00:00")
            put("remind_at", remind?.let { ms(it).iso() })
            put("due_at", due?.let { ms(it).iso() })
            put("repeat", repeat)
            put("checklist", JsonArray(listOf(buildJsonObject { put("id", "a"); put("text", "x"); put("done", true) })))
            put("some_future_column", 42)
        },
    )

    @Test
    fun completingRepeatingTaskSpawnsNextAndStopsTheOldOne() {
        val now = ms(LocalDateTime.of(2026, 11, 23, 10, 5))
        val t = row(LocalDateTime.of(2026, 11, 23, 10, 0), repeat = "FREQ=WEEKLY")
        val rows = TaskEdits.complete(t, now, zone)
        assertEquals(2, rows.size)
        val (done, next) = rows
        assertEquals("done", done.str("status"))
        assertNull(done.str("repeat"))
        assertFalse("server columns are never sent", "user_id" in done || "server_updated_at" in done)
        assertEquals("future columns survive", JsonPrimitive(42), done["some_future_column"])
        assertEquals("todo", next.str("status"))
        assertEquals("FREQ=WEEKLY", next.str("repeat"))
        assertNotEquals(t.id, next.str("id"))
        assertEquals(ms(LocalDateTime.of(2026, 11, 30, 10, 0)), next.str("remind_at")!!.isoMillis())
        val item = (next["checklist"] as JsonArray).first().jsonObject
        assertEquals(JsonPrimitive(false), item["done"])
    }

    @Test
    fun completingOneOffTaskIsOneRow() {
        val now = ms(LocalDateTime.of(2026, 11, 23, 10, 5))
        val rows = TaskEdits.complete(row(LocalDateTime.of(2026, 11, 23, 10, 0)), now, zone)
        assertEquals(1, rows.size)
        assertTrue(rows[0].str("updated_at")!!.isoMillis() > "2026-11-20T10:00:00+00:00".isoMillis())
    }

    @Test
    fun tomorrowKeepsTimeAndGapToDue() {
        val due = LocalDateTime.of(2026, 11, 23, 11, 0)
        val remind = LocalDateTime.of(2026, 11, 23, 10, 30)
        val moved = TaskEdits.tomorrow(row(remind, due = due), ms(LocalDateTime.of(2026, 11, 23, 10, 31)), zone)
        val tomorrow = LocalDate.now(zone).plusDays(1)
        assertEquals(ms(tomorrow.atTime(11, 0)), moved.str("due_at")!!.isoMillis())
        assertEquals(ms(tomorrow.atTime(10, 30)), moved.str("remind_at")!!.isoMillis())
    }

    @Test
    fun skipOnlyForRepeating() {
        assertNull(TaskEdits.skip(row(LocalDateTime.of(2026, 11, 23, 10, 0))))
        val skipped = TaskEdits.skip(row(LocalDateTime.of(2026, 11, 23, 10, 0), "FREQ=DAILY"), ms(LocalDateTime.of(2026, 11, 23, 10, 1)), zone)
        assertEquals(ms(LocalDateTime.of(2026, 11, 24, 10, 0)), skipped!!.str("remind_at")!!.isoMillis())
    }

    @Test
    fun newTaskHasEveryRequiredColumn() {
        val r: JsonObject = TaskEdits.newTask("Call Ravi", "p", "b", 3.0, null, true, ms(LocalDateTime.of(2026, 11, 23, 16, 0)), null)
        listOf("id", "title", "status", "created_at", "updated_at", "priority", "position", "checklist", "due_all_day").forEach {
            assertNotNull("missing $it", r[it])
        }
        assertEquals("Call Ravi", r.str("title"))
    }

    @Test
    fun aiActionBecomesReminder() {
        val t = Commands.timesFromAi(AiAction("create_task", title = "Send invoice", dueDate = "2026-11-24", dueTime = "16:00", remindAt = "2026-11-24T16:00"))!!
        assertEquals("Send invoice", t.title)
        assertFalse(t.dueAllDay)
        assertEquals(t.dueAt, t.remindAt)
    }

    @Test
    fun offlineParseUsesPhoneParser() {
        val t = Commands.timesFromText("remind me to pay rent tomorrow 10am", LocalDateTime.of(2026, 11, 23, 9, 0))!!
        assertTrue(t.title.contains("rent", ignoreCase = true))
        val at = java.time.Instant.ofEpochMilli(t.remindAt!!).atZone(ZoneId.systemDefault())
        assertEquals(LocalDate.of(2026, 11, 24), at.toLocalDate())
        assertEquals(LocalTime.of(10, 0), at.toLocalTime())
    }

    @Test
    fun wisprKeyCodes() {
        assertEquals(java.awt.event.KeyEvent.VK_CAPS_LOCK, Win.vkToAwt(20))
        assertEquals(java.awt.event.KeyEvent.VK_CONTROL, Win.vkToAwt(162))
        assertEquals(java.awt.event.KeyEvent.VK_WINDOWS, Win.vkToAwt(91))
        assertEquals(java.awt.event.KeyEvent.VK_F9, Win.vkToAwt(120))
    }
}
