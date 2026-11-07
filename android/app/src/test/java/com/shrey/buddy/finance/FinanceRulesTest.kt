package com.shrey.buddy.finance

import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceRulesTest {

    private fun debit(raw: String?, channel: String? = "UPI") = Categorizer.categorize("debit", raw, channel, 50000)

    @Test fun `merchant VPAs map to spending categories`() {
        assertEquals(Category.FOOD, debit("swiggydinein@icici"))
        assertEquals(Category.FOOD, debit("dominos.payu@indus"))
        assertEquals(Category.GROCERIES, debit("zeptoonline@ybl"))
        assertEquals(Category.SHOPPING, debit("amznplcin000245@rapl"))
        assertEquals(Category.TRANSPORT, debit("gpaytoll@icici"))
        assertEquals(Category.INVESTMENTS, debit("ICCL - Mutual Fu"))
        assertEquals(Category.FEES, debit("annual maintenance charges", "Bank charge"))
    }

    @Test fun `Google Pay merchants are not Google subscriptions`() {
        assertEquals(Category.OTHER, debit("gpay-10000000000@okbizaxis"))
        assertEquals(Category.SUBSCRIPTIONS, debit("Google", "UPI AutoPay"))
    }

    @Test fun `channel fallbacks`() {
        assertEquals(Category.SUBSCRIPTIONS, debit("SomeService", "UPI AutoPay"))
        assertEquals(Category.PEOPLE, debit("A PERSON", "IMPS"))
        assertEquals(Category.OTHER, debit("someone@okaxis"))
    }

    @Test fun `credits`() {
        assertEquals(Category.SALARY, Categorizer.categorize("credit", "SOME COMPANY PTY LTD", "IMPS", 60_000_00))
        assertEquals(Category.INCOME, Categorizer.categorize("credit", "SOME COMPANY PTY LTD", "IMPS", 5_000_00))
        assertEquals(Category.REFUNDS, Categorizer.categorize("credit", "goog-payments@axisbank", "UPI", 1100))
        assertEquals(Category.REFUNDS, Categorizer.categorize("credit", "ITDTAX REFUND 2026-27", null, 18_030_00))
        assertEquals(Category.INCOME, Categorizer.categorize("credit", "friend@oksbi", "UPI", 1000_00))
    }

    @Test fun `payee prettifying`() {
        assertEquals("Dominos", prettyParty("dominos.payu@indus", "UPI"))
        assertEquals("Swiggydinein", prettyParty("swiggydinein@icici", "UPI"))
        assertEquals("Paytm merchant", prettyParty("paytmqr6q1pl7@ptys", "UPI"))
        assertEquals("PhonePe merchant", prettyParty("q000000000@ybl", "UPI"))
        assertEquals("UPI ••0000", prettyParty("9876540000-3@ibl", "UPI"))
        assertEquals("New Udupi Grand", prettyParty("NEW UDUPI GRAND", "UPI"))
        assertEquals("Debit Card Purchase", prettyParty(null, "Debit Card Purchase"))
    }
}
