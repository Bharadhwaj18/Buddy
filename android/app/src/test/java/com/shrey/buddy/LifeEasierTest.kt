package com.shrey.buddy

import com.shrey.buddy.data.db.TransactionEntity
import com.shrey.buddy.data.items.Iou
import com.shrey.buddy.data.items.Recurring
import com.shrey.buddy.finance.BillRadar
import com.shrey.buddy.finance.Splits
import com.shrey.buddy.ingest.sms.OtpSms
import com.shrey.buddy.ingest.sms.ParcelSms
import com.shrey.buddy.ingest.sms.ParcelStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** OTP relay, parcels, bill radar and splits. All messages are made up. */
class LifeEasierTest {
    // ---------------- OTP ----------------

    @Test fun `finds one-time codes`() {
        assertEquals("482913", OtpSms.find("VM-HDFCBK", "482913 is your OTP for txn of Rs 1,250.00 at AMAZON on HDFC Bank card xx1234. Valid 5 mins. Do not share.")?.code)
        assertEquals("HDFC Bank", OtpSms.find("VM-HDFCBK", "482913 is your OTP for txn of Rs 1,250.00 on HDFC Bank card xx1234.")?.source)
        assertEquals("7731", OtpSms.find("JD-SWIGGY", "Your Swiggy verification code is 7731")?.code)
        assertEquals("Swiggy", OtpSms.find("JD-SWIGGY", "Your Swiggy verification code is 7731")?.source)
        assertEquals("123456", OtpSms.find("GOOGLE", "G-123456 is your Google verification code.")?.code)
        assertEquals("559201", OtpSms.find("AX-ICICIT", "Dear Customer, OTP to log in to iMobile is 559201. Never share it.")?.code)
        assertEquals("90817", OtpSms.find("BZ-UBERIN", "Use code 90817 to sign in to Uber")?.code)
    }

    @Test fun `leaves other numbers and delivery codes alone`() {
        assertNull(OtpSms.find("VM-HDFCBK", "Rs 4500.00 debited from a/c xx1234 on 15-03-26 to VPA shop@okbank"))
        assertNull(OtpSms.find("AX-AMAZON", "Share OTP 4821 with the delivery agent to receive your package"))
        assertNull(OtpSms.find("AX-PROMOS", "Use promo 2024 code now and get 50% off"))
        // The amount isn't the code.
        assertEquals("664120", OtpSms.find("VM-SBIOTP", "OTP for Rs 9999 transfer is 664120. SBI")?.code)
    }

    // ---------------- Parcels ----------------

    private val nov25 = LocalDate.of(2026, 11, 25)

    @Test fun `reads shop and courier sms`() {
        val a = ParcelSms.parse("AX-AMAZON", "Your Amazon package with Wireless Earbuds will be delivered today. Share OTP 4821 with the delivery agent. Track: amzn.in/x", nov25)!!
        assertEquals("Amazon", a.merchant)
        assertEquals(ParcelStatus.OUT_FOR_DELIVERY, a.status)
        assertEquals("Wireless Earbuds", a.title)
        assertEquals("4821", a.deliveryCode)
        assertEquals(nov25, a.eta)

        val f = ParcelSms.parse("VM-FLPKRT", "Shipped: Your Flipkart item Cotton Bedsheet has been shipped and will be delivered by Fri, 28 Nov. Order ID OD12345678901", nov25)!!
        assertEquals(ParcelStatus.SHIPPED, f.status)
        assertEquals(LocalDate.of(2026, 11, 28), f.eta)
        assertEquals("OD12345678901", f.orderId)

        val d = ParcelSms.parse("JM-DELHVY", "Your shipment AWB 98765432 from Myntra has been delivered. Thank you!", nov25)!!
        assertEquals(ParcelStatus.DELIVERED, d.status)
        assertEquals("Delhivery", d.merchant)

        assertEquals(ParcelStatus.ORDERED, ParcelSms.parse("AD-MYNTRA", "Thank you for your order! Your order 1234-5678 is confirmed. Arriving by 2 Dec.", nov25)?.status)
        assertEquals(LocalDate.of(2026, 12, 2), ParcelSms.parse("AD-MYNTRA", "Thank you for your order! Your order 1234-5678 is confirmed. Arriving by 2 Dec.", nov25)?.eta)
        assertEquals(ParcelStatus.CANCELLED, ParcelSms.parse("AX-AMAZON", "Your order 403-1234567-1234567 has been cancelled.", nov25)?.status)
        assertEquals(LocalDate.of(2027, 1, 3), ParcelSms.parse("AX-AMAZON", "Your package will be delivered on 3 Jan", LocalDate.of(2026, 12, 29))?.eta)
    }

    @Test fun `ignores sales, returns and strangers`() {
        assertNull(ParcelSms.parse("AX-AMAZON", "Big sale! Up to 70% off on headphones. Shop now", nov25))
        assertNull(ParcelSms.parse("AX-AMAZON", "Your refund of Rs 499 has been processed", nov25))
        assertNull(ParcelSms.parse("VM-HDFCBK", "Rs 500 debited for Amazon order", nov25))
        assertNull(ParcelSms.parse("+919800000000", "Hey, are you coming tonight?", nov25))
    }

    // ---------------- Bill radar ----------------

    private val zone = ZoneId.of("Asia/Kolkata")
    private var n = 0
    private fun txn(day: LocalDate, rupees: Int, payee: String, dir: String = "debit") = TransactionEntity(
        id = "t${n++}", accountId = "acc", direction = dir, amountMinor = rupees * 100L,
        occurredAt = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), payee = payee, counterpartyRaw = null,
        channel = "upi", reference = null, category = "subscriptions", categorySource = "auto", note = null, isTransfer = false,
        transferGroup = null, source = "sms", dedupeKey = "k$n", balanceAfterMinor = null, captureId = null, createdAt = 0, updatedAt = 0, deletedAt = null,
    )

    private val today = LocalDate.of(2026, 11, 25)

    @Test fun `finds a monthly subscription`() {
        val txns = (1..5).map { txn(today.minusMonths(it.toLong()).withDayOfMonth(12), 649, "NETFLIX.COM") } +
            listOf(txn(today.minusDays(3), 230, "Chai Point"))
        val found = BillRadar.detect(txns, emptyList(), today, zone)
        assertEquals(1, found.size)
        val f = found.single()
        assertEquals("monthly", f.frequency)
        assertEquals(12, f.day)
        assertEquals(64900L, f.amountMinor)
        assertEquals("netflix", f.match)
        assertEquals(BillRadar.Kind.NEW, f.kind)
    }

    @Test fun `a daily cafe or a stopped bill is not on the radar`() {
        val cafe = (0 until 90).map { txn(today.minusDays(it.toLong()), 180, "Chai Point") }
        assertTrue(BillRadar.detect(cafe, emptyList(), today, zone).isEmpty())
        val old = (6..9).map { txn(today.minusMonths(it.toLong()).withDayOfMonth(5), 199, "Spotify") }
        assertTrue(BillRadar.detect(old, emptyList(), today, zone).isEmpty())
    }

    @Test fun `tracked bill that went up in price`() {
        val r = Recurring(name = "Netflix", amountMinor = 49900, match = "netflix", day = 12)
        val txns = listOf(3L, 2L).map { txn(today.minusMonths(it).withDayOfMonth(12), 499, "NETFLIX.COM") } +
            listOf(1L, 0L).map { txn(today.minusMonths(it).withDayOfMonth(12).let { d -> if (d.isAfter(today)) d.minusMonths(1) else d }, 649, "NETFLIX.COM") }
        val found = BillRadar.detect(txns.distinctBy { it.occurredAt }, listOf("r1" to r), today, zone)
        val p = found.single { it.kind == BillRadar.Kind.PRICE_CHANGE }
        assertEquals(49900L, p.oldAmountMinor)
        assertEquals(64900L, p.amountMinor)
        assertEquals("r1", p.recurringId)
        // Already tracked at the right price: nothing to say.
        assertTrue(BillRadar.detect(txns, listOf("r1" to r.copy(amountMinor = 64900)), today, zone).isEmpty())
    }

    @Test fun `due in two days and stopped`() {
        val r = Recurring(name = "Gym", amountMinor = 150000, day = 27, match = "gym")
        assertEquals(LocalDate.of(2026, 11, 27), BillRadar.dueIn(listOf("g" to r), emptyList(), 2, today).single().third)
        val stopped = BillRadar.stopped(listOf("g" to r.copy(day = 10)), emptyList(), today)
        assertEquals(1, stopped.size)
        val paid = listOf(txn(LocalDate.of(2026, 10, 10), 1500, "Gym"), txn(LocalDate.of(2026, 11, 10), 1500, "Gym"))
        assertTrue(BillRadar.stopped(listOf("g" to r.copy(day = 10)), paid, today).isEmpty())
    }

    // ---------------- Splits ----------------

    @Test fun `plain-english splits`() {
        val d = Splits.parse("split 1200 with Ravi and Amit for dinner")!!
        assertEquals(listOf("Ravi", "Amit"), d.people)
        assertEquals("dinner", d.note)
        assertEquals(40000L, Splits.ious(d, 0).first().amountMinor)

        val p = Splits.parse("I paid 900 for movie tickets, split with ravi")!!
        assertEquals(listOf("Ravi"), p.people)
        assertEquals("movie tickets", p.note)
        assertEquals(45000L, Splits.ious(p, 0).single().amountMinor)

        val o = Splits.parse("Neha owes me 350 for petrol")!!
        assertEquals(true, o.theyOwe)
        assertEquals(35000L, Splits.ious(o, 0).single().amountMinor)

        val i = Splits.parse("I owe Amit ₹250")!!
        assertEquals(false, i.theyOwe)
        assertEquals(listOf("Amit"), i.people)
        assertNull(Splits.parse("remind me to call Ravi"))
        assertEquals(33300L, Splits.share(100000, 2))
    }

    @Test fun `money in from a friend settles what they owe`() {
        val open = listOf(
            "a" to Iou("Ravi", 40000, createdAt = 0, note = "dinner"),
            "b" to Iou("Ravi", 25000, createdAt = 0, note = "cab"),
            "c" to Iou("Amit", 40000, createdAt = 0),
        )
        assertEquals(listOf("a"), Splits.settles(txn(today, 400, "RAVI KUMAR S", "credit"), open))
        assertEquals(listOf("a", "b"), Splits.settles(txn(today, 650, "Ravi K", "credit"), open))
        assertTrue(Splits.settles(txn(today, 300, "RAVI KUMAR", "credit"), open).isEmpty())
        assertTrue(Splits.settles(txn(today, 400, "RAVI KUMAR"), open).isEmpty()) // money out
        assertEquals((105000L to 0L), Splits.totals(open.map { it.second }))
    }
}
