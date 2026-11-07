package com.shrey.buddy

import com.shrey.buddy.data.tasks.RepeatRule
import com.shrey.buddy.finance.Category
import com.shrey.buddy.voice.VoiceIntent
import com.shrey.buddy.voice.VoiceIntent.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class VoiceIntentTest {
    // Wednesday 7 Oct 2026, 2:30 pm.
    private val now = LocalDateTime.of(2026, 10, 7, 14, 30)
    private val today = now.toLocalDate()
    private val accounts = listOf("IndusInd ••4321", "SBI ••1111", "Cash")
    private fun p(s: String) = VoiceIntent.parse(s, accounts, now)!!

    // ---- money ----

    @Test fun `spent on lunch`() {
        val r = p("spent 450 on lunch")
        assertEquals(Kind.EXPENSE, r.kind)
        assertEquals(45_000L, r.amountMinor)
        assertEquals(Category.FOOD, r.category)
        assertEquals("Lunch", r.payee)
        assertNull(r.date)
        assertTrue(r.explicit)
    }

    @Test fun `spoken numbers and yesterday`() {
        val r = p("Hey Buddy, I spent four hundred and fifty rupees on dinner yesterday")
        assertEquals(Kind.EXPENSE, r.kind)
        assertEquals(45_000L, r.amountMinor)
        assertEquals(today.minusDays(1), r.date)
        assertEquals(Category.FOOD, r.category)
    }

    @Test fun `one fifty means 150`() = assertEquals(15_000L, p("paid one fifty for auto").amountMinor)

    @Test fun `k and payee and account`() {
        val r = p("paid 2k to Ramesh from SBI last friday")
        assertEquals(2_00_000L, r.amountMinor)
        assertEquals("Ramesh", r.payee)
        assertEquals("sbi", r.account)
        assertEquals(LocalDate.of(2026, 10, 2), r.date)
    }

    @Test fun `time of payment`() {
        val r = p("spent ₹1,250 at Decathlon at 11 am")
        assertEquals(1_25_000L, r.amountMinor)
        assertEquals("Decathlon", r.payee)
        assertEquals(LocalTime.of(11, 0), r.time)
        assertEquals(today, r.date)
    }

    @Test fun `a later time means yesterday`() = assertEquals(today.minusDays(1), p("paid 300 for cab at 11 pm").date)

    @Test fun `cash`() = assertEquals("cash", p("spent 80 on chai in cash").account)

    @Test fun `bare merchant and amount`() {
        val r = p("uber 230")
        assertEquals(Kind.EXPENSE, r.kind)
        assertEquals(23_000L, r.amountMinor)
        assertEquals(Category.TRANSPORT, r.category)
        assertFalse(r.explicit)
    }

    @Test fun `income`() {
        val r = p("received salary 85000")
        assertEquals(Kind.INCOME, r.kind)
        assertEquals(Category.SALARY, r.category)
        val gift = p("got 5000 from dad")
        assertEquals(Kind.INCOME, gift.kind)
        assertEquals("Dad", gift.payee)
    }

    @Test fun `on the 3rd points back`() = assertEquals(LocalDate.of(2026, 10, 3), p("paid 999 for netflix on the 3rd").date)

    @Test fun `fuel with litres and odometer`() {
        val r = p("filled petrol 5 litres for 520 at 12450 km")
        assertEquals(Kind.EXPENSE, r.kind)
        assertEquals(52_000L, r.amountMinor)
        assertEquals(Category.FUEL, r.category)
        assertEquals(5.0 to 12450, VoiceIntent.fuelDetails("filled petrol 5 litres for 520 at 12450 km"))
    }

    @Test fun `sub-category keys`() {
        assertEquals("food/Coffee", Category.key(Category.FOOD, " Coffee "))
        assertEquals("food", Category.key(Category.FOOD, null))
        assertEquals(Category.FOOD, Category.of("food/Coffee"))
        assertEquals("Coffee", Category.subOf("food/Coffee"))
        assertNull(Category.subOf("food"))
        assertTrue(Category.matches("food/Coffee", "food"))
        assertTrue(Category.matches("food/Coffee", "food/Coffee"))
        assertFalse(Category.matches("food", "food/Coffee"))
        assertFalse(Category.matches("fuel", "food"))
    }

    // ---- tasks & reminders ----

    @Test fun `future pay is a task`() {
        val r = p("pay 5000 rent tomorrow")
        assertEquals(Kind.TASK, r.kind)
        assertEquals(today.plusDays(1), r.date)
    }

    @Test fun `remind me with time`() {
        val r = p("remind me to call Mom at 7 pm")
        assertEquals(Kind.REMINDER, r.kind)
        assertEquals("Call Mom", r.title)
        assertEquals(LocalTime.of(19, 0), r.time)
        assertEquals(today, r.date)
        assertTrue(r.remind)
    }

    @Test fun `set a reminder for tomorrow`() {
        val r = p("set a reminder to renew passport tomorrow at 10")
        assertEquals(Kind.REMINDER, r.kind)
        assertEquals("Renew passport", r.title)
        assertEquals(today.plusDays(1), r.date)
        assertEquals(LocalTime.of(10, 0), r.time)
    }

    @Test fun `wake me up`() {
        val r = p("wake me up at 6 am")
        assertEquals(Kind.REMINDER, r.kind)
        assertEquals("Wake up", r.title)
        assertEquals(today.plusDays(1), r.date)
    }

    @Test fun `remind me in another ten minutes`() {
        val r = p("Hey Buddy, remind me to call the bank in another ten minutes.")
        assertEquals(Kind.REMINDER, r.kind)
        assertEquals("Call the bank", r.title)
        assertEquals(now.plusMinutes(10).toLocalTime(), r.time)
        assertEquals(today, r.date)
        assertTrue(r.remind)
    }

    @Test fun `can you remind me`() = assertEquals(Kind.REMINDER, p("can you remind me in 20 minutes to check the oven").kind)

    @Test fun `add task with repeat`() {
        val r = p("add task water the plants every monday")
        assertEquals(Kind.TASK, r.kind)
        assertEquals("Water the plants", r.title)
        assertEquals(RepeatRule.Freq.WEEKLY, r.repeat?.freq)
        assertTrue(r.explicit)
    }

    @Test fun `need to is a task but not explicit`() {
        val r = p("I need to book train tickets on friday")
        assertEquals(Kind.TASK, r.kind)
        assertEquals("Book train tickets", r.title)
        assertEquals(LocalDate.of(2026, 10, 9), r.date)
        assertFalse(r.explicit)
    }

    @Test fun `plain to-do verb`() = assertEquals("Buy milk", p("buy milk").title)

    @Test fun `to my list`() {
        val r = p("eggs and bread to my list")
        assertEquals(Kind.TASK, r.kind)
        assertEquals("Eggs and bread", r.title)
        assertTrue(r.explicit)
    }

    // ---- notes & questions ----

    @Test fun `note`() {
        val r = p("note: the wifi password is on the back of the router")
        assertEquals(Kind.NOTE, r.kind)
        assertEquals("The wifi password is on the back of the router", r.body)
        assertEquals("The wifi password is on the…", r.title)
    }

    @Test fun `make a note that`() = assertEquals("Parking is on level B2", p("make a note that parking is on level B2").body)

    @Test fun `questions`() {
        assertEquals(Kind.QUESTION, p("how much did I spend on food this month").kind)
        assertEquals(Kind.QUESTION, p("did I set a reminder for tomorrow").kind)
        assertEquals(Kind.QUESTION, p("what's due tomorrow").kind)
    }

    @Test fun `chit chat is left to the AI`() {
        assertNull(VoiceIntent.parse("I'm feeling tired today", accounts, now))
        assertNotNull(VoiceIntent.parse("thanks buddy that was great?", accounts, now)) // a question → chat
    }
}
