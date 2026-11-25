package com.shrey.buddy.finance

import com.shrey.buddy.data.db.TransactionEntity
import com.shrey.buddy.data.items.Iou
import kotlin.math.abs
import kotlin.math.roundToLong

/** Splitting payments with friends, and spotting when they pay you back. Pure; unit-tested. */
object Splits {
    /** Each person's share of [totalMinor] split equally between [people] others (plus you, if [includingMe]). */
    fun share(totalMinor: Long, people: Int, includingMe: Boolean = true): Long {
        val heads = people + if (includingMe) 1 else 0
        if (heads <= 0) return 0
        // Round to whole rupees: nobody pays back ₹333.33.
        return (totalMinor / heads.toDouble() / 100).roundToLong() * 100
    }

    /** "Ravi, Amit and Neha" / "ravi & amit" → ["Ravi", "Amit", "Neha"]. */
    fun people(s: String): List<String> = s.split(Regex("""\s*(?:,|&|\band\b|\+)\s*""", RegexOption.IGNORE_CASE))
        .map { it.trim().trim('.') }
        .filter { it.isNotBlank() && !it.equals("me", true) && !it.equals("myself", true) }
        .map { w -> w.split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) } }
        .distinct()

    data class Draft(val totalMinor: Long, val people: List<String>, val note: String, val theyOwe: Boolean, val split: Boolean)

    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)
    private const val AMT = """(?:rs\.?|₹|inr)?\s*([\d,]+(?:\.\d{1,2})?)\s*(?:rs|rupees|bucks)?"""
    private const val NAMES = """([a-z][a-z .,&+]*?)"""
    private const val FOR = """(?:\s+(?:for|on)\s+(.+?))?"""

    /**
     * Plain English, no AI:
     *  - "split 1200 with Ravi and Amit (for dinner)" / "I paid 1200 for dinner, split with Ravi"
     *  - "Ravi owes me 500 (for petrol)"
     *  - "I owe Ravi 300 (for tickets)"
     */
    fun parse(text: String): Draft? {
        val t = text.trim().trimEnd('.', '!', '?').replace(Regex("""^(?:hey|hi) buddy[, ]*""", RegexOption.IGNORE_CASE), "")
        fun amt(s: String) = s.replace(",", "").toDoubleOrNull()?.let { (it * 100).roundToLong() }?.takeIf { it > 0 }

        rx("""^split\s+$AMT\s+(?:with|between|among)\s+$NAMES$FOR$""").find(t)?.let { m ->
            val total = amt(m.groupValues[1]) ?: return null
            return Draft(total, people(m.groupValues[2]), m.groupValues[3], theyOwe = true, split = true)
        }
        rx("""^(?:i\s+)?paid\s+$AMT(?:\s+(?:for|on)\s+(.+?))?[,.]?\s+(?:and\s+)?split(?:\s+it)?\s+(?:with|between|among)\s+$NAMES$""").find(t)?.let { m ->
            val total = amt(m.groupValues[1]) ?: return null
            return Draft(total, people(m.groupValues[3]), m.groupValues[2], theyOwe = true, split = true)
        }
        rx("""^$NAMES\s+owes?\s+me\s+$AMT$FOR$""").find(t)?.let { m ->
            val total = amt(m.groupValues[2]) ?: return null
            return Draft(total, people(m.groupValues[1]), m.groupValues[3], theyOwe = true, split = false)
        }
        rx("""^i\s+owe\s+$NAMES\s+$AMT$FOR$""").find(t)?.let { m ->
            val total = amt(m.groupValues[2]) ?: return null
            return Draft(total, people(m.groupValues[1]), m.groupValues[3], theyOwe = false, split = false)
        }
        return null
    }

    /** The IOUs a draft turns into (each person's share, or the full amount when it isn't a split). */
    fun ious(d: Draft, now: Long, txnId: String? = null): List<Iou> {
        if (d.people.isEmpty()) return emptyList()
        val each = if (d.split) share(d.totalMinor, d.people.size) else d.totalMinor / d.people.size
        return d.people.map { Iou(person = it, amountMinor = each, theyOwe = d.theyOwe, note = d.note.trim(), txnId = txnId, createdAt = now) }
    }

    /** First name, lower case, for matching against UPI names ("RAVI KUMAR S"). */
    private fun firstName(p: String) = p.trim().split(' ').first().lowercase()

    /**
     * Money in that pays back open IOUs: from someone whose first name appears in the sender's name, for one IOU's
     * amount or the total they owe (±₹1). Returns the ids it settles (empty if none).
     */
    fun settles(t: TransactionEntity, open: List<Pair<String, Iou>>): List<String> {
        if (t.direction != "credit" || t.isTransfer || t.deletedAt != null) return emptyList()
        val who = "${t.payee.orEmpty()} ${t.counterpartyRaw.orEmpty()}".lowercase()
        val mine = open.filter { (_, i) ->
            i.theyOwe && i.settledAt == null && i.createdAt <= t.occurredAt + 60_000 &&
                firstName(i.person).length >= 3 && Regex("""\b${Regex.escape(firstName(i.person))}""").containsMatchIn(who)
        }
        if (mine.isEmpty()) return emptyList()
        fun close(a: Long) = abs(a - t.amountMinor) <= 100
        mine.sortedBy { it.second.createdAt }.firstOrNull { close(it.second.amountMinor) }?.let { return listOf(it.first) }
        // Paid everything owed in one go (per person).
        for ((_, list) in mine.groupBy { firstName(it.second.person) }) {
            if (list.size > 1 && close(list.sumOf { it.second.amountMinor })) return list.map { it.first }
        }
        return emptyList()
    }

    /** Totals: (owed to you, you owe). */
    fun totals(ious: List<Iou>): Pair<Long, Long> =
        ious.filter { it.settledAt == null }.let { o -> o.filter { it.theyOwe }.sumOf { it.amountMinor } to o.filter { !it.theyOwe }.sumOf { it.amountMinor } }

    /** A friendly nudge to send. */
    fun askText(person: String, amountMinor: Long, notes: List<String>): String {
        val what = notes.filter { it.isNotBlank() }.distinct().joinToString(", ").ifBlank { null }
        val first = person.split(' ').first()
        return "Hey $first, just a reminder about ₹${"%,d".format(amountMinor / 100)}" + (what?.let { " for $it" } ?: "") + " when you get a chance 🙂"
    }
}
