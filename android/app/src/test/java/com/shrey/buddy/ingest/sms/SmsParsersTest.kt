package com.shrey.buddy.ingest.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Fixtures copy the exact wording of real messages; names, account numbers,
 * reference numbers and balances are fake.
 */
class SmsParsersTest {

    private val indus = "VM-INDUSB-S"
    private val sbi = "JD-SBIUPI-S"

    private fun txn(sender: String, body: String) = SmsParsers.parse(sender, body) as SmsParse.Transaction

    // --- Sender handling ---

    @Test fun `header strips operator prefix and category suffix`() {
        assertEquals("INDUSB", SmsParsers.header("VM-INDUSB-S"))
        assertEquals("SBIUPI", SmsParsers.header("jd-sbiupi-s"))
        assertEquals("SBIUPI", SmsParsers.header("AD-SBIUPI"))
        assertNull(SmsParsers.header("+919876543210"))
    }

    @Test fun `non-bank senders are ignored entirely`() {
        assertNull(SmsParsers.parse("VM-SWIGGY-S", "INR 500 debited from your A/C 1***2 towards X. Avl BAL INR 1"))
        assertNull(SmsParsers.parse("+919876543210", "Rs.500 credited"))
    }

    // --- IndusInd ---

    @Test fun `IndusInd debit card purchase`() {
        val t = txn(
            indus,
            "INR 2,297.06 debited from your A/C 999***991234 towards Debit Card Purchase. " +
                "Avl BAL INR 1,74,874.34 - Not you? Call 18602677777 to report issue - IndusInd Bank",
        )
        assertEquals(Bank.INDUSIND, t.bank)
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals(229706L, t.amountMinor)
        assertEquals("1234", t.accountLast4)
        assertEquals("Debit Card Purchase", t.channel)
        assertEquals(17487434L, t.balanceMinor)
    }

    @Test fun `IndusInd debit leaving a small balance`() {
        val t = txn(
            indus,
            "INR 2,368.98 debited from your A/C 999***991234 towards Debit Card Purchase. Avl BAL INR 255.36 - " +
                "Not you? Call 18602677777 to report issue - IndusInd Bank",
        )
        assertEquals(236898L, t.amountMinor)
        assertEquals(25536L, t.balanceMinor)
    }

    @Test fun `IndusInd credit with double space and reference glued to balance`() {
        val t = txn(indus, "IndusInd A/C  Credited; INR 500.00 Ref-SAOAO1000000000000000001.Bal INR 500.00.Dispute-Call 18602677777-IndusInd Bank")
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals(50000L, t.amountMinor)
        assertEquals("SAOAO1000000000000000001", t.reference)
        assertEquals(50000L, t.balanceMinor)
    }

    @Test fun `IndusInd informational messages are not transactions`() {
        listOf(
            "Thank you. The KYC requirements for Customer ID ending with XXXX1111 have been completed - IndusInd Bank",
            "Your Debit Card XXXX2222 has been TEMPORARILY BLOCKED. To unblock, visit https://www.indusind.com/dc-controls or call 18602677777 for assistance",
            "We have registered your Welcome Kit/ request 11111111. The same is being processed and will be dispatched to your communication address shortly. For any feedback or assistance, please call 1860 267 7777 - IndusInd Bank.",
            "Thank you for using our Mobile App services. Kindly share your feedback on Mobile App experience - https://gs.im/INDUSB/e/xxxx. - IndusInd Bank",
        ).forEach { body ->
            assertEquals(body, SmsParse.NotTransaction(Bank.INDUSIND), SmsParsers.parse(indus, body))
        }
    }

    @Test fun `unknown IndusInd money message is flagged, not dropped`() {
        val r = SmsParsers.parse(indus, "INR 450.00 sent via UPI from A/C 999***991234 to someone@okaxis RRN 123 - IndusInd Bank")
        assertEquals(SmsParse.Unrecognised(Bank.INDUSIND), r)
    }

    // --- SBI ---

    @Test fun `SBI UPI debit to a person`() {
        val t = txn(
            sbi,
            "Dear UPI user A/C X1234 debited by 80000.00 on date 07Jun26 trf to A Person Refno 600000000001 " +
                "If not u? call-1800111109 for other services-18001234-SBI",
        )
        assertEquals(Bank.SBI, t.bank)
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals(8000000L, t.amountMinor)
        assertEquals("1234", t.accountLast4)
        assertEquals("A Person", t.counterparty)
        assertEquals("UPI", t.channel)
        assertEquals("600000000001", t.reference)
        assertEquals("2026-06-07", t.txnDate)
        assertNull(t.balanceMinor)
    }

    @Test fun `SBI UPI debit without decimals`() {
        val t = txn(
            sbi,
            "Dear UPI user A/C X1234 debited by 407 on date 11Jan26 trf to EatClub Refno 600000000002 If not u? call-1800111109 for other services-18001234-SBI",
        )
        assertEquals(40700L, t.amountMinor)
        assertEquals("EatClub", t.counterparty)
        assertEquals("2026-01-11", t.txnDate)
    }

    @Test fun `SBI UPI debit to merchant with hyphen in truncated name`() {
        val t = txn(
            sbi,
            "Dear UPI user A/C X1234 debited by 4000.00 on date 22Sep26 trf to ICCL - Mutual Fu Refno 100000000003 If not u? call-1800111109 for other services-18001234-SBI",
        )
        assertEquals("ICCL - Mutual Fu", t.counterparty)
        assertEquals("2026-09-22", t.txnDate)
    }

    @Test fun `SBI credit strips honorific`() {
        val t = txn(sbi, "Dear SBI User, your A/c X1234-credited by Rs.98 on 29Sep26 transfer from Mr SOME PERSON Ref No 600000000004 -SBI")
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals(9800L, t.amountMinor)
        assertEquals("SOME PERSON", t.counterparty)
        assertEquals("600000000004", t.reference)
        assertEquals("2026-09-29", t.txnDate)
    }

    @Test fun `SBI credit whole rupees`() {
        val t = txn(sbi, "Dear SBI User, your A/c X1234-credited by Rs.10000 on 02Oct26 transfer from ANOTHER PERSON Ref No 700000000005 -SBI")
        assertEquals(1000000L, t.amountMinor)
        assertEquals("ANOTHER PERSON", t.counterparty)
    }

    @Test fun `SBI UPI mandate is captured as a subscription signal`() {
        val r = SmsParsers.parse(
            sbi,
            "Your UPI-Mandate for Rs.149.00 is successfully created towards Google from A/c No: XXXXXX1234. " +
                "UMN:0000aaaa1111bbbb2222cccc3333dddd@ibl. If not you, kindly report on 18001234. -SBI",
        ) as SmsParse.Mandate
        assertEquals(14900L, r.amountMinor)
        assertEquals("Google", r.payee)
        assertEquals("1234", r.accountLast4)
        assertEquals("0000aaaa1111bbbb2222cccc3333dddd@ibl", r.umn)
    }

    @Test fun `multi-line bodies are normalised before matching`() {
        val t = txn(sbi, "Dear UPI user A/C X1234 debited by\n89.00 on date 10Feb26 trf to Google\nPlay Refno 400000000006 If not u?")
        assertEquals(8900L, t.amountMinor)
        assertEquals("Google Play", t.counterparty)
    }

    // --- IndusInd UPI / transfers (formats seen in the phone's inbox scan) ---

    @Test fun `IndusInd UPI debit to VPA with balance, any operator prefix`() {
        val t = txn(
            "CP-INDUSB-T",
            "A/C *XX1234 debited by Rs 2799.00 towards shop.rzp@mairtel. RRN:100000000001. Avl Bal:3205.50. " +
                "Not you? Call 18602677777 - IndusInd bank",
        )
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals(279900L, t.amountMinor)
        assertEquals("1234", t.accountLast4)
        assertEquals("shop.rzp@mairtel", t.counterparty)
        assertEquals("UPI", t.channel)
        assertEquals("100000000001", t.reference)
        assertEquals(320550L, t.balanceMinor)
    }

    @Test fun `IndusInd UPI credit from VPA`() {
        val t = txn(
            "AD-INDUSB-S",
            "A/C *XX1234 credited by Rs 12329.00 from someone@okaxis. RRN:500000000002. Avl Bal:15534.50. Not you? Call 18602677777 - IndusInd bank",
        )
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals(1232900L, t.amountMinor)
        assertEquals("someone@okaxis", t.counterparty)
        assertEquals(1553450L, t.balanceMinor)
    }

    @Test fun `IndusInd UPI AutoPay debit`() {
        val t = txn(indus, "A/c *XX1234 debited by Rs. 1999.00 towards Google UPI AutoPay, UMN XXXX000aaa@ybl - IndusInd Bank")
        assertEquals(199900L, t.amountMinor)
        assertEquals("Google", t.counterparty)
        assertEquals("UPI AutoPay", t.channel)
        assertEquals("XXXX000aaa@ybl", t.reference)
    }

    @Test fun `IndusInd IMPS debit`() {
        val t = txn(
            indus,
            "Your account XXXXXXX1234 debited with Rs. 28000 on 02-07-26 and account XXXXXXX9999/A PERSON will be credited." +
                "(IMPS Ref no. 600000000003). Call 18602677777 to report issue - IndusInd Bank",
        )
        assertEquals(2800000L, t.amountMinor)
        assertEquals("A PERSON", t.counterparty)
        assertEquals("IMPS", t.channel)
        assertEquals("600000000003", t.reference)
        assertEquals("2026-07-02", t.txnDate)
    }

    @Test fun `IndusInd NEFT debit, and its beneficiary confirmation is not a second transaction`() {
        val t = txn(
            indus,
            "Your IndusInd Account 15XXXXX1234 has been debited for INR 200000 towards N/INDBH00000000001/ICIC/A PERSON. " +
                "Call 18602677777 to report issue-IndusInd Bank",
        )
        assertEquals(20000000L, t.amountMinor)
        assertEquals("1234", t.accountLast4)
        assertEquals("NEFT", t.channel)
        assertEquals("INDBH00000000001", t.reference)
        assertEquals("A PERSON", t.counterparty)

        val confirmation = SmsParsers.parse(
            indus,
            "Rs. 200000 credited to beneficiary A/c XXXXXXXX9999 on 22-11-25 12:03:01 via NEFT. UTR INDBH00000000001. Call 18602677777 to report issue- IndusInd Bank",
        )
        assertEquals(SmsParse.NotTransaction(Bank.INDUSIND), confirmation)
    }

    @Test fun `INDIE standing instruction debit, failure and advance notice`() {
        val t = txn(
            indus,
            "Dear Customer, Your account has been successfully debited with Rs.28000.0 on 2026-09-02 towards 999999991234 " +
                "with standing instruction abcDEF123 for e-mandate - INDIE by Indusind Bank",
        )
        assertEquals(2800000L, t.amountMinor)
        assertEquals("A/c ••1234", t.counterparty)
        assertEquals("Standing instruction", t.channel)
        assertEquals("2026-09-02", t.txnDate)

        val failed = SmsParsers.parse(
            indus,
            "Dear Customer, Your payment of Rs. 28000.0 towards 999999991234 with standing instruction abcDEF123 through your INDIE account could not be processed - INDIE by Indusind Bank.",
        )
        assertEquals(SmsParse.NotTransaction(Bank.INDUSIND), failed)

        val upcoming = SmsParsers.parse(
            indus,
            "Dear Customer, Rs. 28000.0 for the month of AUGUST will be deducted on 2026-08-02 towards the scheduled payment. " +
                "Kindly maintain sufficient balance in your account - INDIE by Indusind Bank",
        ) as SmsParse.UpcomingDebit
        assertEquals(2800000L, upcoming.amountMinor)
        assertEquals("2026-08-02", upcoming.dueDate)
    }

    @Test fun `IndusInd AutoPay pre-debit notice and mandate`() {
        val upcoming = SmsParsers.parse(
            indus,
            "UPI Autopay for Google debit of Rs.1999.00 is scheduled on 17/07/2026, UMN XXXX000aaa@ybl.Please ensure sufficient balance. -IndusInd Bank",
        ) as SmsParse.UpcomingDebit
        assertEquals(199900L, upcoming.amountMinor)
        assertEquals("Google", upcoming.payee)
        assertEquals("2026-07-17", upcoming.dueDate)
        assertEquals("XXXX000aaa@ybl", upcoming.umn)

        val mandate = SmsParsers.parse(
            indus,
            "UPI autopay ASPRESENTED mandate has been successfully created towards Google for amt upto Rs 1999.00. UMN XXXX000aaa@ybl- IndusInd Bank",
        ) as SmsParse.Mandate
        assertEquals(199900L, mandate.amountMinor)
        assertEquals("Google", mandate.payee)
        assertEquals("XXXX000aaa@ybl", mandate.umn)
    }

    // --- SBI, other headers ---

    @Test fun `SBI AutoPay pre-debit notice with leading dot in date`() {
        val r = SmsParsers.parse(
            "VA-SBIUPI-S",
            "Dear UPI User, UPI AutoPay for SOME DIGITAL SERVICES PVT LTD debit of Rs.89.00 is scheduled on .10/10/25, " +
                "0000aaaa1111bbbb@okaxis. Please ensure sufficient balance in your account. -SBI",
        ) as SmsParse.UpcomingDebit
        assertEquals(8900L, r.amountMinor)
        assertEquals("SOME DIGITAL SERVICES PVT LTD", r.payee)
        assertEquals("2025-10-10", r.dueDate)
        assertEquals("0000aaaa1111bbbb@okaxis", r.umn)
    }

    @Test fun `SBI IMPS credit from a company`() {
        val t = txn(
            "JD-SBIINB-S",
            "Dear Customer, Your a/c no. XXXXXXXX1234 is credited by Rs.60007.80 on 10-11-25 by a/c linked to mobile " +
                "0XXXXXX000-SOME COMPANY PTY LTD (IMPS Ref no 500000000007).If not done by you, call 1800111109. -SBI",
        )
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals(6000780L, t.amountMinor)
        assertEquals("1234", t.accountLast4)
        assertEquals("SOME COMPANY PTY LTD", t.counterparty)
        assertEquals("IMPS", t.channel)
        assertEquals("2025-11-10", t.txnDate)
    }

    @Test fun `SBI CBS credit carries a balance`() {
        val t = txn(
            "VA-CBSSBI-S",
            "Your A/C XXXXX991234 has credit for ITDTAX REFUND 2026-27 ABCDE123 of Rs 18,030.00 on 03/09/26. Avl Bal Rs 57,673.10.-SBI",
        )
        assertEquals(Direction.CREDIT, t.direction)
        assertEquals(1803000L, t.amountMinor)
        assertEquals("1234", t.accountLast4)
        assertEquals("ITDTAX REFUND 2026-27 ABCDE123", t.counterparty)
        assertEquals(5767310L, t.balanceMinor)
        assertEquals("2026-09-03", t.txnDate)

        val upi = txn("VA-CBSSBI-S", "Your A/C XXXXX991234 has credit for UPI/DRC/500000000008/11112025/ of Rs 50.00 on 12/11/25. Avl Bal Rs 1,80,673.95.-SBI")
        assertEquals("UPI", upi.channel)
        assertEquals("UPI/DRC/500000000008/11112025", upi.counterparty)
        assertEquals(18067395L, upi.balanceMinor)
    }

    @Test fun `SBI funds-blocked mandate`() {
        val r = SmsParsers.parse(
            sbi,
            "UPI-Mandate successfully created towards SOME FUNDS MANAGEMENT LTD for Rs29848.00. Funds blocked frm A/cXXXXXX1234.0000aaaa1111@yesg -SBI",
        ) as SmsParse.Mandate
        assertEquals(2984800L, r.amountMinor)
        assertEquals("SOME FUNDS MANAGEMENT LTD", r.payee)
        assertEquals("1234", r.accountLast4)
        assertEquals("0000aaaa1111@yesg", r.umn)
    }

    @Test fun `SBIPSG IMPS credit uses Ref# instead of Ref no`() {
        val t = txn(
            "AX-SBIPSG-S",
            "Dear Customer, Your a/c no. XXXXXXXX1234 is credited by Rs.60000.00 on 09-03-26 by a/c linked to mobile " +
                "0XXXXXX000-SOME PAYER (IMPS Ref# 600000000009)-SBI",
        )
        assertEquals(6000000L, t.amountMinor)
        assertEquals("SOME PAYER", t.counterparty)
        assertEquals("600000000009", t.reference)
        assertEquals("2026-03-09", t.txnDate)
    }

    @Test fun `SBI card annual fee is a debit`() {
        val t = txn(
            "VM-SBIBNK-S",
            "Dear Customer, Your A/C ending with 1234 has been debited for INR 236.0 on 12-12-25 towards annual maintenance charges " +
                "for your SBI Debit Card ending with 9999",
        )
        assertEquals(Direction.DEBIT, t.direction)
        assertEquals(23600L, t.amountMinor)
        assertEquals("1234", t.accountLast4)
        assertEquals("Annual maintenance charges", t.counterparty)
        assertEquals("Bank charge", t.channel)
        assertEquals("2025-12-12", t.txnDate)
    }

    @Test fun `promotions that mention an amount are not flagged`() {
        val r = SmsParsers.parse(
            "VM-SBIBNK-S",
            "Dear Customer, Make lightning-fast, one-tap payments up to Rs. 1000 with UPI Lite. Enable it on your UPI app now. -SBI",
        )
        assertEquals(SmsParse.NotTransaction(Bank.SBI), r)
    }

    @Test fun `newly allowlisted SBI headers are recognised`() {
        assertEquals(Bank.SBI, SmsParsers.bankFor("AX-SBIBNK-S"))
        assertEquals(Bank.SBI, SmsParsers.bankFor("VM-SBIPSG-T"))
        assertNull(SmsParsers.bankFor("VM-INDUSO-P"))
    }

    @Test fun `date formats`() {
        assertEquals("2026-06-07", isoDate("07Jun26"))
        assertEquals("2025-10-10", isoDate(".10/10/25"))
        assertEquals("2026-07-17", isoDate("17/07/2026"))
        assertEquals("2026-07-02", isoDate("02-07-26"))
        assertEquals("2026-08-02", isoDate("2026-08-02"))
        assertNull(isoDate("not a date"))
    }

    // --- Safety ---

    @Test fun `OTP messages are never treated as transactions`() {
        val r = SmsParsers.parse(sbi, "OTP for txn of Rs.5000.00 at AMAZON is 123456. Do not share it with anyone -SBI")
        assertEquals(SmsParse.NotTransaction(Bank.SBI), r)
    }

    @Test fun `amount parsing`() {
        assertEquals(17487434L, parseMinor("1,74,874.34"))
        assertEquals(40700L, parseMinor("407"))
        assertEquals(50L, parseMinor("0.5"))
    }
}
