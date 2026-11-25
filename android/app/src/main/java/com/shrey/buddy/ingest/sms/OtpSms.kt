package com.shrey.buddy.ingest.sms

/** A one-time code found in an SMS, for the laptop. [source] is who sent it ("HDFC Bank", "Swiggy"…). */
data class OtpCode(val code: String, val source: String)

/**
 * Finds login / payment one-time codes in any SMS. Delivery codes ("share OTP 4821 with the delivery agent")
 * are left to [ParcelSms]: those are for handing over at the door, not for typing on the laptop.
 */
object OtpSms {
    private val KEYWORD = Regex(
        """\b(otp|one[- ]?time (?:password|passcode|code|pin)|verification code|security code|login code|auth(?:entication)? code|passcode|code)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val DELIVERY = Regex("""deliver|delivery|courier|agent|rider|executive|handover|hand over""", RegexOption.IGNORE_CASE)
    private val MONEY_BEFORE = Regex("""(?:rs\.?|inr|₹|a/c|acct|account|xx|x{2,}|card)\s*\.?\s*$""", RegexOption.IGNORE_CASE)

    /** Google-style "G-123456" and plain 4–8 digit codes. */
    private val CODE = Regex("""(?<![\w/.,-])(?:[A-Z]-)?(\d{4,8})(?![\w/.,]?\d)""")

    fun find(sender: String, body: String): OtpCode? {
        val text = body.replace(Regex("\\s+"), " ").trim()
        val kw = KEYWORD.findAll(text).toList()
        if (kw.isEmpty() || DELIVERY.containsMatchIn(text)) return null
        // A bare "code" only counts with the usual phrasing around it.
        if (kw.all { it.value.equals("code", true) } &&
            !Regex("""(?:is your|your .{0,20}code is|code[:\s]+\d|use code|enter code)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
        ) return null
        val candidates = CODE.findAll(text).filter { m ->
            val before = text.substring(maxOf(0, m.range.first - 8), m.range.first)
            !MONEY_BEFORE.containsMatchIn(before)
        }.toList()
        if (candidates.isEmpty()) return null
        // The number nearest to an OTP keyword.
        val best = candidates.minBy { c -> kw.minOf { k -> distance(c.range, k.range) } }
        if (kw.minOf { distance(best.range, it.range) } > 60) return null
        return OtpCode(best.groupValues[1], source(sender, text))
    }

    private fun distance(a: IntRange, b: IntRange) = when {
        a.last < b.first -> b.first - a.last
        b.last < a.first -> a.first - b.last
        else -> 0
    }

    private val KNOWN = linkedMapOf(
        "HDFC" to "HDFC Bank", "ICICI" to "ICICI Bank", "SBI" to "SBI", "INDUS" to "IndusInd Bank", "AXIS" to "Axis Bank",
        "KOTAK" to "Kotak", "PAYTM" to "Paytm", "PHONEPE" to "PhonePe", "GPAY" to "Google Pay", "AMAZON" to "Amazon", "AMZN" to "Amazon",
        "FLPKRT" to "Flipkart", "FLIPKART" to "Flipkart", "SWIGGY" to "Swiggy", "ZOMATO" to "Zomato", "UBER" to "Uber", "OLA" to "Ola",
        "MYNTRA" to "Myntra", "GOOGLE" to "Google", "MICROSOFT" to "Microsoft", "APPLE" to "Apple", "WHATSAPP" to "WhatsApp",
        "INSTAGRAM" to "Instagram", "FACEBOOK" to "Facebook", "LINKEDIN" to "LinkedIn", "IRCTC" to "IRCTC", "DIGILOCKER" to "DigiLocker",
        "UIDAI" to "Aadhaar", "AADHAAR" to "Aadhaar", "INCOMETAX" to "Income Tax", "ZERODHA" to "Zerodha", "GROWW" to "Groww", "CRED" to "CRED",
    )

    /** "for HDFC Bank", "to log in to Swiggy", or the sender header ("VM-HDFCBK" → HDFC Bank). */
    fun source(sender: String, text: String): String {
        val upper = text.uppercase()
        KNOWN.entries.firstOrNull { (k, _) -> Regex("""\b$k""").containsMatchIn(upper) }?.let { return it.value }
        val header = SmsParsers.header(sender) ?: sender.trim().uppercase().takeIf { it.any(Char::isLetter) && it.length <= 12 }
        header?.let { h -> KNOWN.entries.firstOrNull { h.contains(it.key) }?.let { return it.value } }
        return header ?: "SMS"
    }
}
