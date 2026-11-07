package com.shrey.buddy.finance

private val gatewayTokens = setOf("rzp", "payu", "razorpay", "pg", "pay", "upi", "online", "in")

/** Turn VPAs, ALL-CAPS bank text and gateway noise into something readable. */
fun prettyParty(raw: String?, channel: String?): String {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return channel ?: "Transaction"
    if ('@' in s) {
        val local = s.substringBefore('@').lowercase()
        return when {
            local.startsWith("paytmqr") || local.startsWith("paytm") -> "Paytm merchant"
            local.startsWith("gpay-") -> "Google Pay merchant"
            Regex("^q\\d{6,}$").matches(local) -> "PhonePe merchant"
            Regex("^\\d{10}(-\\d+)?$").matches(local) -> "UPI ••${local.take(10).takeLast(4)}"
            else -> local.split(Regex("[._\\-]+"))
                .map { it.trimEnd { ch -> ch.isDigit() } }
                .filter { it.isNotEmpty() && it !in gatewayTokens }
                .ifEmpty { listOf(local) }
                .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        }
    }
    return if (s == s.uppercase() && s.any(Char::isLetter)) {
        s.lowercase().split(' ').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
    } else {
        s
    }
}
