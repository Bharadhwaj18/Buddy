package com.shrey.buddy.ingest.sms

import java.text.NumberFormat
import java.util.Locale

private val inr: NumberFormat = NumberFormat.getCurrencyInstance(
    Locale.Builder().setLanguage("en").setRegion("IN").build(),
)

fun formatInr(minor: Long): String = inr.format(minor / 100.0)

private fun Bank.label() = when (this) {
    Bank.INDUSIND -> "IndusInd"
    Bank.SBI -> "SBI"
}

/** One-line human summary, used in notifications and the capture list. */
fun SmsParse.describe(): String = when (this) {
    is SmsParse.Transaction -> buildString {
        append(if (direction == Direction.DEBIT) "−" else "+")
        append(formatInr(amountMinor))
        (counterparty ?: channel)?.let { append(if (direction == Direction.DEBIT) " · $it" else " · from $it") }
        append(" · ${bank.label()}")
        accountLast4?.let { append(" ••$it") }
    }
    is SmsParse.Mandate -> "Mandate ${formatInr(amountMinor)} → $payee · ${bank.label()}"
    is SmsParse.UpcomingDebit ->
        "Upcoming ${formatInr(amountMinor)}${payee?.let { " → $it" }.orEmpty()}${dueDate?.let { " on $it" }.orEmpty()} · ${bank.label()}"
    is SmsParse.Unrecognised -> "Unrecognised ${bank.label()} message"
    is SmsParse.NotTransaction -> "${bank.label()} info message"
}
