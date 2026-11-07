package com.shrey.buddy.ui

import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val india: Locale = Locale.Builder().setLanguage("en").setRegion("IN").build()

/** Indian grouping (₹1,74,874). Paise only when asked for or when non-zero in detail views. */
fun inr(minor: Long, paise: Boolean = false): String {
    val f = NumberFormat.getCurrencyInstance(india).apply {
        maximumFractionDigits = if (paise) 2 else 0
        minimumFractionDigits = if (paise) 2 else 0
    }
    return f.format(minor / 100.0)
}

/** Compact for tiles: ₹38.4K, ₹1.2L. */
fun inrCompact(minor: Long): String {
    val rupees = minor / 100.0
    return when {
        rupees >= 1_00_000 -> "₹%.1fL".format(rupees / 1_00_000)
        rupees >= 1_000 -> "₹%.1fK".format(rupees / 1_000)
        else -> inr(minor)
    }
}

private val dayHeader = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)

fun friendlyDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> date.format(dayHeader)
}

fun greeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hey"
}
