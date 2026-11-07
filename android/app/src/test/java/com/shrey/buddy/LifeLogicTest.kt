package com.shrey.buddy

import com.shrey.buddy.data.db.ItemEntity
import com.shrey.buddy.data.items.Document
import com.shrey.buddy.data.items.Goal
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Person
import com.shrey.buddy.data.items.Vehicle
import com.shrey.buddy.data.items.Wish
import com.shrey.buddy.health.Polyline
import com.shrey.buddy.health.formatDuration
import com.shrey.buddy.health.formatPace
import com.shrey.buddy.ui.life.LifeModule
import com.shrey.buddy.ui.life.buildReminders
import com.shrey.buddy.ui.life.nextBirthday
import com.shrey.buddy.ui.screens.streak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LifeLogicTest {

    private fun <T> item(data: T, id: String = data.hashCode().toString()) =
        Item(ItemEntity(id, "x", "{}", null, null, 0, 0, null), data)

    @Test fun `polyline matches Google's reference example and round-trips`() {
        val pts = listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453)
        val encoded = Polyline.encode(pts)
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", encoded)
        Polyline.decode(encoded).zip(pts).forEach { (a, b) ->
            assertEquals(b.first, a.first, 1e-5)
            assertEquals(b.second, a.second, 1e-5)
        }
    }

    @Test fun `durations and paces`() {
        assertEquals("5:07", formatDuration(307_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("5:30 /km", formatPace(330))
        assertEquals("–:– /km", formatPace(null))
    }

    @Test fun `streak counts back from today, or from yesterday if today isn't done yet`() {
        val today = LocalDate.now()
        val done3 = setOf(today, today.minusDays(1), today.minusDays(2))
        assertEquals(3, streak { it in done3 })
        val doneUntilYesterday = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, streak { it in doneUntilYesterday })
        assertEquals(0, streak { false })
    }

    @Test fun `next birthday rolls into next year once passed`() {
        val today = LocalDate.of(2026, 10, 3)
        assertEquals(LocalDate.of(2026, 10, 10), nextBirthday("10-10", today))
        assertEquals(LocalDate.of(2027, 1, 5), nextBirthday("01-05", today))
        assertEquals(null, nextBirthday(null, today))
    }

    @Test fun `reminders pick up birthdays, expiries, renewals, cooled-off wishes and goals`() {
        val today = LocalDate.of(2026, 10, 3)
        val r = buildReminders(
            people = listOf(item(Person(name = "Asha", birthday = "10-06")), item(Person(name = "Far", birthday = "12-25"))),
            docs = listOf(item(Document(title = "Car insurance", expiry = "2026-10-20")), item(Document(title = "Passport", expiry = "2031-01-01"))),
            cars = listOf(item(Vehicle(name = "Activa", pucExpiry = "2026-10-15"))),
            wishes = listOf(item(Wish(title = "Headphones", coolOffUntil = "2026-10-01")), item(Wish(title = "Bought", status = "bought", coolOffUntil = "2026-10-01"))),
            goals = listOf(item(Goal(title = "Read 2 books", due = "2026-10-08"))),
            today = today,
        )
        val titles = r.map { it.title }
        assertTrue("Asha's birthday" in titles)
        assertTrue("Car insurance expires" in titles)
        assertTrue("Activa: PUC due" in titles)
        assertTrue("Still want “Headphones”?" in titles)
        assertTrue("Goal: Read 2 books" in titles)
        assertTrue(titles.none { "Far" in it || "Passport" in it || "Bought" in it })
        assertEquals(LifeModule.PEOPLE, r.first { it.title == "Asha's birthday" }.module)
        assertEquals(r.sortedBy { it.date }, r)
    }

    @Test fun `catch-up reminder when it's been too long`() {
        val today = LocalDate.of(2026, 10, 3)
        val r = buildReminders(
            listOf(item(Person(name = "Ravi", lastContact = "2026-09-01", contactEveryDays = 14)), item(Person(name = "Recent", lastContact = "2026-10-01", contactEveryDays = 14))),
            emptyList(), emptyList(), emptyList(), emptyList(), today,
        )
        assertEquals(listOf("Catch up with Ravi"), r.map { it.title })
    }
}
