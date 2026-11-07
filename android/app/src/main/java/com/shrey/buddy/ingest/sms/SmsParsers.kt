package com.shrey.buddy.ingest.sms

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * Entry point for bank SMS (DESIGN.md §5). Pure Kotlin so it is unit-testable on the JVM.
 *
 * Indian bank SMS come from DLT headers like "VM-INDUSB-S": a 2-letter operator/circle prefix
 * (varies per message: VM, AD, CP, JD, JX, AX...), a 6-character entity header (stable), and a
 * category suffix (-S service, -T transactional...).
 */
object SmsParsers {
    private val headerPattern = Regex("^[A-Z]{2}-([A-Z0-9]{6})(?:-[A-Z])?$")

    /** All observed on the owner's phone except ATMSBI, which is a known SBI header kept as a precaution. */
    private val allowlist = mapOf(
        "INDUSB" to Bank.INDUSIND,
        "SBIUPI" to Bank.SBI,
        "SBIINB" to Bank.SBI,
        "CBSSBI" to Bank.SBI,
        "SBIBNK" to Bank.SBI,
        "SBIPSG" to Bank.SBI,
        "ATMSBI" to Bank.SBI,
    )

    /** "VM-INDUSB-S" -> "INDUSB"; null if the sender isn't a DLT header (e.g. a phone number). */
    fun header(sender: String): String? = headerPattern.matchEntire(sender.trim().uppercase())?.groupValues?.get(1)

    fun bankFor(sender: String): Bank? = header(sender)?.let { allowlist[it] }

    /** Returns null for senders that aren't allowlisted banks; those messages are never looked at. */
    fun parse(sender: String, body: String): SmsParse? {
        val bank = bankFor(sender) ?: return null
        val text = body.replace(Regex("\\s+"), " ").trim()
        if (OTP.containsMatchIn(text) || NOT_A_NEW_TXN.containsMatchIn(text)) return SmsParse.NotTransaction(bank)
        val parsed = when (bank) {
            Bank.INDUSIND -> IndusIndParser.parse(text)
            Bank.SBI -> SbiParser.parse(text)
        } ?: parseUpcomingAutopay(bank, text)
        return parsed ?: if (MONEY.containsMatchIn(text)) SmsParse.Unrecognised(bank) else SmsParse.NotTransaction(bank)
    }

    private val OTP = Regex("\\bOTP\\b|one[- ]time password|verification code", RegexOption.IGNORE_CASE)

    /**
     * Money-mentioning messages that must not become transactions:
     * the NEFT "credited to beneficiary" confirmation repeats an outgoing transfer already captured,
     * and failed payments moved no money.
     */
    private val NOT_A_NEW_TXN = Regex("credited to beneficiary|could not be processed", RegexOption.IGNORE_CASE)

    // Unknown messages are flagged only if they mention an amount AND a money-movement verb;
    // promotions like "payments up to Rs. 1000 with UPI Lite" are not worth a parser update.
    private val AMOUNT_MENTION = Regex("\\bINR\\b|\\bRs\\.?\\s?\\d", RegexOption.IGNORE_CASE)
    private val MOVEMENT = Regex("debited|credited|\\bdebit\\b|\\bcredit\\b|withdrawn|\\bspent\\b|\\bpaid\\b|\\bsent\\b|received|transferred", RegexOption.IGNORE_CASE)
    private val MONEY = object {
        fun containsMatchIn(text: String) = AMOUNT_MENTION.containsMatchIn(text) && MOVEMENT.containsMatchIn(text)
    }

    // Both banks: "UPI AutoPay for GOOGLE ... debit of Rs.89.00 is scheduled on .10/10/25, <umn>@okaxis."
    private val upcomingAutopay = Regex(
        """UPI Autopay for (.+?) debit of Rs\.?\s?$AMOUNT is scheduled on \.?([\d/]+),\s?(?:UMN\s?)?$VPA""",
        RegexOption.IGNORE_CASE,
    )

    private fun parseUpcomingAutopay(bank: Bank, text: String): SmsParse? =
        upcomingAutopay.find(text)?.let { m ->
            val (payee, amount, date, umn) = m.destructured
            SmsParse.UpcomingDebit(bank, parseMinor(amount), payee.trim(), isoDate(date), umn)
        }
}

internal const val AMOUNT = """([\d,]+(?:\.\d{1,2})?)"""
internal const val VPA = """([\w.\-]+@[A-Za-z0-9]+)"""

/** "2,297.06" -> 229706 paise. */
internal fun parseMinor(amount: String): Long =
    BigDecimal(amount.replace(",", "")).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

/** Last 4 digits of a masked account like "999***991234", "X1234" or "99XXXXX1234". */
internal fun last4(masked: String): String? = masked.filter(Char::isDigit).takeLast(4).ifEmpty { null }

private val honorific = Regex("^(Mr|Mrs|Ms|Miss|Dr)\\.?\\s+", RegexOption.IGNORE_CASE)
internal fun cleanParty(raw: String): String = raw.trim().replace(honorific, "").trim()

private val dateFormats: List<DateTimeFormatter> =
    listOf("ddMMMyy", "d/M/yy", "d/M/yyyy", "d-M-yy", "d-M-yyyy", "yyyy-MM-dd").map {
        DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH)
    }

/** Any date format seen in bank SMS -> ISO "yyyy-MM-dd", or null. */
internal fun isoDate(raw: String): String? {
    val s = raw.trim().trim('.')
    return dateFormats.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(s, f).toString() }.getOrNull() }
}

private fun rx(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

internal object IndusIndParser {
    // A/c *XX1234 debited by Rs. 1999.00 towards Google UPI AutoPay, UMN XXXX000aaa@ybl - IndusInd Bank
    private val autopayDebit = rx("""A/c \*?X*(\d{3,6}) debited by Rs\.?\s?$AMOUNT towards (.+?) UPI AutoPay, UMN $VPA""")

    // A/C *XX1234 debited by Rs 2799.00 towards shop.rzp@mairtel. RRN:100000000001. Avl Bal:1234.50. Not you? ...
    private val upiDebit = rx("""A/c \*?X*(\d{3,6}) debited by Rs\.?\s?$AMOUNT towards (\S+?)\.\s?RRN:\s?(\d+)(?:\.\s?Avl Bal:\s?$AMOUNT)?""")

    // A/C *XX1234 credited by Rs 1000.00 from someone@okaxis. RRN:100000000002. Avl Bal:5678.50. Not you? ...
    private val upiCredit = rx("""A/c \*?X*(\d{3,6}) credited by Rs\.?\s?$AMOUNT from (\S+?)\.\s?RRN:\s?(\d+)(?:\.\s?Avl Bal:\s?$AMOUNT)?""")

    // INR 2,297.06 debited from your A/C 999***991234 towards Debit Card Purchase. Avl BAL INR 12,345.67 - Not you? ...
    private val cardDebit = rx("""INR\s?$AMOUNT debited from your A/C (\S+) towards (.+?)\.\s?Avl BAL INR\s?$AMOUNT""")

    // IndusInd A/C Credited; INR 500.00 Ref-SAOAO1000000000000000001.Bal INR 500.00.Dispute-Call ...
    private val accountCredit = rx("""IndusInd A/C Credited;\s?INR\s?$AMOUNT Ref-(\S+?)\.\s?Bal INR\s?$AMOUNT""")

    // Your account XXXXXXX1234 debited with Rs. 20000 on 02-07-26 and account XXXXXXX9999/K PERSON will be credited.(IMPS Ref no. 600000000004)
    private val impsDebit = rx(
        """Your account X*(\d{3,6}) debited with Rs\.?\s?$AMOUNT on ([\d-]+) and account \S*?/(.+?) will be credited\.?\s?\(IMPS Ref no\.?\s?(\d+)\)""",
    )

    // Your IndusInd Account 99XXXXX1234 has been debited for INR 100000 towards N/INDBH00000000001/ICIC/PERSON. Call ...
    private val transferDebit = rx("""Your IndusInd Account (\S+) has been debited for INR\s?$AMOUNT towards (.+?)\.?\s?Call""")

    // Your account has been successfully debited with Rs.20000.0 on 2026-09-02 towards 999999991234 with standing instruction <id> for e-mandate - INDIE ...
    private val standingInstruction = rx("""successfully debited with Rs\.?\s?$AMOUNT on ([\d-]+) towards (\S+) with standing instruction (\S+)""")

    // Dear Customer, Rs. 20000.0 for the month of AUGUST will be deducted on 2026-08-02 towards the scheduled payment. ...
    private val scheduledPayment = rx("""Rs\.?\s?$AMOUNT for the month of [A-Za-z]+ will be deducted on ([\d-]+) towards the scheduled payment""")

    // UPI autopay ASPRESENTED mandate has been successfully created towards Google for amt upto Rs 1999.00. UMN XXXX000aaa@ybl- IndusInd Bank
    private val mandate = rx("""mandate has been successfully created towards (.+?) for amt upto Rs\.?\s?$AMOUNT\.?\s?UMN\s?$VPA""")

    private val transferChannels = mapOf("N" to "NEFT", "R" to "RTGS", "I" to "IMPS")

    private fun debit(amount: String, account: String?, counterparty: String? = null, channel: String? = null,
                      reference: String? = null, balance: String? = null, date: String? = null) =
        SmsParse.Transaction(
            bank = Bank.INDUSIND, direction = Direction.DEBIT, amountMinor = parseMinor(amount),
            accountLast4 = account?.let(::last4), counterparty = counterparty, channel = channel,
            reference = reference, balanceMinor = balance?.ifEmpty { null }?.let(::parseMinor),
            txnDate = date?.let(::isoDate),
        )

    fun parse(text: String): SmsParse? {
        autopayDebit.find(text)?.let { m ->
            val (account, amount, payee, umn) = m.destructured
            return debit(amount, account, counterparty = payee.trim(), channel = "UPI AutoPay", reference = umn)
        }
        upiDebit.find(text)?.let { m ->
            val (account, amount, vpa, rrn, balance) = m.destructured
            return debit(amount, account, counterparty = vpa, channel = "UPI", reference = rrn, balance = balance)
        }
        upiCredit.find(text)?.let { m ->
            val (account, amount, vpa, rrn, balance) = m.destructured
            return debit(amount, account, counterparty = vpa, channel = "UPI", reference = rrn, balance = balance)
                .copy(direction = Direction.CREDIT)
        }
        cardDebit.find(text)?.let { m ->
            val (amount, account, channel, balance) = m.destructured
            return debit(amount, account, channel = channel.trim(), balance = balance)
        }
        accountCredit.find(text)?.let { m ->
            val (amount, ref, balance) = m.destructured
            return debit(amount, null, reference = ref, balance = balance).copy(direction = Direction.CREDIT)
        }
        impsDebit.find(text)?.let { m ->
            val (account, amount, date, payee, ref) = m.destructured
            return debit(amount, account, counterparty = payee.trim(), channel = "IMPS", reference = ref, date = date)
        }
        transferDebit.find(text)?.let { m ->
            val (account, amount, details) = m.destructured
            val parts = details.split("/")
            return debit(
                amount, account,
                counterparty = parts.lastOrNull()?.ifEmpty { null },
                channel = transferChannels[parts.first().uppercase()],
                reference = parts.getOrNull(1),
            )
        }
        standingInstruction.find(text)?.let { m ->
            val (amount, date, toAccount, siId) = m.destructured
            val payee = if (toAccount.all(Char::isDigit)) "A/c ••${toAccount.takeLast(4)}" else toAccount
            return debit(amount, null, counterparty = payee, channel = "Standing instruction", reference = siId, date = date)
        }
        scheduledPayment.find(text)?.let { m ->
            val (amount, date) = m.destructured
            return SmsParse.UpcomingDebit(Bank.INDUSIND, parseMinor(amount), payee = "Scheduled payment", dueDate = isoDate(date))
        }
        mandate.find(text)?.let { m ->
            val (payee, amount, umn) = m.destructured
            return SmsParse.Mandate(Bank.INDUSIND, parseMinor(amount), payee.trim(), accountLast4 = null, umn = umn)
        }
        return null
    }
}

internal object SbiParser {
    // Dear UPI user A/C X1234 debited by 1000.00 on date 07Jun26 trf to G Person Refno 600000000001 If not u? ...
    private val upiDebit = rx("""A/C X?(\d{3,6}) debited by\s?$AMOUNT on date (\d{2}[A-Za-z]{3}\d{2}) trf to (.+?) Refno (\d+)""")

    // Dear SBI User, your A/c X1234-credited by Rs.1000 on 10Jun26 transfer from SOME PERSON Ref No 600000000002 -SBI
    private val upiCredit = rx("""A/c X?(\d{3,6})-credited by Rs\.?\s?$AMOUNT on (\d{2}[A-Za-z]{3}\d{2}) transfer from (.+?) Ref No (\d+)""")

    // Dear Customer, Your a/c no. XXXXXXXX1234 is credited by Rs.50000.00 on 10-11-25 by a/c linked to mobile 0XXXXXX000-SOME CO (IMPS Ref no 600000000003).
    // (SBIPSG variant writes "IMPS Ref# 600000000003)-SBI")
    private val impsCredit = rx(
        """a/c no\.\s?X*(\d{3,6}) is credited by Rs\.?\s?$AMOUNT on ([\d-]+) by a/c linked to mobile \S+?-(.+?) \(IMPS Ref(?: no\.?|#)\s?(\d+)\)""",
    )

    // Dear Customer, Your A/C ending with 1234 has been debited for INR 236.0 on 12-12-25 towards annual maintenance charges for your SBI Debit Card ending with 9999
    private val chargeDebit = rx(
        """Your A/C ending with (\d{3,6}) has been debited for INR\s?$AMOUNT on ([\d-]+) towards (.+?)(?: for your .*|\.\s.*|\.?)$""",
    )

    // Your A/C XXXXX991234 has credit for ITDTAX REFUND 2026-27 ABCDE123 of Rs 10,000.00 on 03/09/26. Avl Bal Rs 12,345.67.-SBI
    private val cbs = rx("""Your A/C X*(\d{3,6}) has (credit|debit) for (.+?) of Rs\.?\s?$AMOUNT on ([\d/]+)\.\s?Avl Bal Rs\.?\s?$AMOUNT""")

    // Your UPI-Mandate for Rs.149.00 is successfully created towards Google from A/c No: XXXXXX1234. UMN:abc@ibl. If not you...
    private val mandate = rx(
        """UPI-Mandate for Rs\.?\s?$AMOUNT is successfully created towards (.+?) from A/c No:\s?X*(\d{3,6})\.(?:\s?UMN:\s?(\S+?)\.(?:\s|$))?""",
    )

    // UPI-Mandate successfully created towards SOME FUND LTD for Rs10000.00. Funds blocked frm A/cXXXXXX1234.0000...@yesg -SBI
    private val blockMandate = rx(
        """UPI-Mandate successfully created towards (.+?) for Rs\.?\s?$AMOUNT\.\s?Funds blocked frm A/c\s?X*(\d{3,6})\.?(?:$VPA)?""",
    )

    fun parse(text: String): SmsParse? {
        upiDebit.find(text)?.let { m ->
            val (account, amount, date, payee, ref) = m.destructured
            return SmsParse.Transaction(
                Bank.SBI, Direction.DEBIT, parseMinor(amount), last4(account),
                counterparty = cleanParty(payee), channel = "UPI", reference = ref, txnDate = isoDate(date),
            )
        }
        upiCredit.find(text)?.let { m ->
            val (account, amount, date, payer, ref) = m.destructured
            return SmsParse.Transaction(
                Bank.SBI, Direction.CREDIT, parseMinor(amount), last4(account),
                counterparty = cleanParty(payer), reference = ref, txnDate = isoDate(date),
            )
        }
        impsCredit.find(text)?.let { m ->
            val (account, amount, date, payer, ref) = m.destructured
            return SmsParse.Transaction(
                Bank.SBI, Direction.CREDIT, parseMinor(amount), last4(account),
                counterparty = cleanParty(payer), channel = "IMPS", reference = ref, txnDate = isoDate(date),
            )
        }
        chargeDebit.find(text)?.let { m ->
            val (account, amount, date, purpose) = m.destructured
            return SmsParse.Transaction(
                Bank.SBI, Direction.DEBIT, parseMinor(amount), last4(account),
                counterparty = purpose.trim().replaceFirstChar(Char::uppercase),
                channel = "Bank charge",
                txnDate = isoDate(date),
            )
        }
        cbs.find(text)?.let { m ->
            val (account, kind, details, amount, date, balance) = m.destructured
            val desc = details.trim().trimEnd('/')
            return SmsParse.Transaction(
                Bank.SBI,
                if (kind.equals("debit", ignoreCase = true)) Direction.DEBIT else Direction.CREDIT,
                parseMinor(amount), last4(account),
                counterparty = desc,
                channel = if (desc.startsWith("UPI/", ignoreCase = true)) "UPI" else null,
                balanceMinor = parseMinor(balance),
                txnDate = isoDate(date),
            )
        }
        mandate.find(text)?.let { m ->
            val (amount, payee, account, umn) = m.destructured
            return SmsParse.Mandate(Bank.SBI, parseMinor(amount), payee.trim(), last4(account), umn.ifEmpty { null })
        }
        blockMandate.find(text)?.let { m ->
            val (payee, amount, account, umn) = m.destructured
            return SmsParse.Mandate(Bank.SBI, parseMinor(amount), payee.trim(), last4(account), umn.ifEmpty { null })
        }
        return null
    }
}
