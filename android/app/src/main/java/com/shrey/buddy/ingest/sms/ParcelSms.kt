package com.shrey.buddy.ingest.sms

import java.time.LocalDate
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

/** Order / delivery progress, in the order it happens. */
enum class ParcelStatus(val label: String) {
    ORDERED("Ordered"), SHIPPED("On the way"), OUT_FOR_DELIVERY("Out for delivery"), DELIVERED("Delivered"), CANCELLED("Cancelled");
}

data class ParcelUpdate(
    val merchant: String,
    val status: ParcelStatus,
    val title: String? = null,
    val orderId: String? = null,
    val eta: LocalDate? = null,
    /** The code to give the delivery person. */
    val deliveryCode: String? = null,
)

/** Reads shop and courier SMS ("Your Amazon package … will be delivered today"). Pure; unit-tested. */
object ParcelSms {
    /** Sender header fragment / name in the text → shop or courier. */
    private val MERCHANTS = linkedMapOf(
        "AMAZON" to "Amazon", "AMZN" to "Amazon", "AMZ" to "Amazon",
        "FLPKRT" to "Flipkart", "FLIPKT" to "Flipkart", "FLIPKART" to "Flipkart", "FKRT" to "Flipkart", "EKART" to "Ekart",
        "MYNTRA" to "Myntra", "MEESHO" to "Meesho", "AJIO" to "AJIO", "NYKAA" to "Nykaa", "TATACQ" to "Tata CLiQ", "TATA CLIQ" to "Tata CLiQ",
        "SWIGGY" to "Swiggy", "INSTMT" to "Swiggy Instamart", "BLNKIT" to "Blinkit", "BLINKT" to "Blinkit", "BLINKIT" to "Blinkit",
        "ZEPTO" to "Zepto", "BBASKT" to "BigBasket", "BIGBSK" to "BigBasket", "BIGBASKET" to "BigBasket", "JIOMRT" to "JioMart", "JIOMART" to "JioMart",
        "DELHVY" to "Delhivery", "DLHVRY" to "Delhivery", "DELHIVERY" to "Delhivery", "BLUDRT" to "Blue Dart", "BLUEDT" to "Blue Dart", "BLUE DART" to "Blue Dart",
        "ECOMXP" to "Ecom Express", "ECOM EXPRESS" to "Ecom Express", "XPRSBS" to "Xpressbees", "XPRESSBEES" to "Xpressbees",
        "SHDWFX" to "Shadowfax", "SHADOWFAX" to "Shadowfax", "DTDC" to "DTDC", "INPOST" to "India Post", "INDPST" to "India Post", "INDIA POST" to "India Post",
        "SMARTR" to "Smartr", "DUNZO" to "Dunzo", "DECATHLON" to "Decathlon", "CROMA" to "Croma", "LENSKART" to "Lenskart", "FIRSTCRY" to "FirstCry",
    )

    private fun rx(s: String) = Regex(s, RegexOption.IGNORE_CASE)
    private val CANCELLED = rx("""\b(?:has been|was|is) cancell?ed\b|\border cancell?ed\b|cancellation (?:of|for) your""")
    private val DELIVERED = rx("""\b(?:has been|was|is|got|successfully) delivered\b|\bdelivered successfully\b|\bdelivered to you\b""")
    private val OUT = rx("""\bout for delivery\b|\bwill be delivered today\b|\barriving today\b|\bdelivery today\b|\bdelivering (?:it )?today\b|\bon (?:the|its) way to you today\b""")
    private val SHIPPED = rx("""\bshipped\b|\bdispatched\b|\bin transit\b|\bon (?:its|the) way\b|\bpicked up\b""")
    /** Only says when it will come: shipped, unless the message is an order confirmation. */
    private val ARRIVING = rx("""\bwill be delivered\b|\barriving\b|\bexpected (?:delivery|by)\b""")
    private val ORDERED = rx("""\border (?:is |has been )?(?:placed|confirmed|received)\b|\bthank(?:s| you) for (?:your )?(?:order|shopping)\b|\breceived your order\b""")
    private val NOT_PARCEL = rx("""\breturn(?:ed)? (?:request|pickup|initiated)\b|\brefund\b|\breplacement request\b|\b\d{1,2}% off\b|\bsale\b|\bcoupon\b|\buse code\b""")
    private val DELIVERY_CODE = rx("""(?:otp|code|pin)\D{0,25}?(\d{4,6})\b""")
    private val ORDER_ID = rx("""\b(?:order|awb|tracking|shipment|consignment)(?:\s*(?:id|no\.?|number|#))?\s*[:#-]?\s*([A-Z0-9][A-Z0-9-]{5,})""")
    private val TITLES = listOf(
        rx("""package (?:with|containing) (.+?) (?:will be|has been|is|was|shipped|arriving|out for)"""),
        rx("""(?:order|item|package|shipment) (?:for|of) (.+?) (?:will be|has been|is|was|has|shipped|arriving|out for)"""),
        rx("""your (?:flipkart|myntra|ajio|meesho|nykaa) (?:order|item|package)? ?(?:for )?(.+?) (?:will be|has been|is|was|has)"""),
    )

    fun parse(sender: String, body: String, received: LocalDate = LocalDate.now()): ParcelUpdate? {
        val text = body.replace(Regex("\\s+"), " ").trim()
        val merchant = merchant(sender, text) ?: return null
        if (NOT_PARCEL.containsMatchIn(text) && !OUT.containsMatchIn(text) && !DELIVERED.containsMatchIn(text)) return null
        val status = when {
            CANCELLED.containsMatchIn(text) -> ParcelStatus.CANCELLED
            DELIVERED.containsMatchIn(text) && !rx("""will be delivered|to be delivered""").containsMatchIn(text) -> ParcelStatus.DELIVERED
            OUT.containsMatchIn(text) -> ParcelStatus.OUT_FOR_DELIVERY
            SHIPPED.containsMatchIn(text) -> ParcelStatus.SHIPPED
            ORDERED.containsMatchIn(text) -> ParcelStatus.ORDERED
            ARRIVING.containsMatchIn(text) -> ParcelStatus.SHIPPED
            else -> return null
        }
        val code = if (status == ParcelStatus.OUT_FOR_DELIVERY || status == ParcelStatus.SHIPPED ||
            rx("""deliver|agent|executive|rider""").containsMatchIn(text)
        ) DELIVERY_CODE.find(text)?.groupValues?.get(1) else null
        val orderId = ORDER_ID.findAll(text).map { it.groupValues[1].trimEnd('-', '.') }
            .firstOrNull { id -> id.any(Char::isDigit) && id != code && id.length <= 30 }
        return ParcelUpdate(
            merchant = merchant,
            status = status,
            title = title(text),
            orderId = orderId,
            eta = when (status) {
                ParcelStatus.OUT_FOR_DELIVERY -> received
                ParcelStatus.DELIVERED, ParcelStatus.CANCELLED -> null
                else -> eta(text, received)
            },
            deliveryCode = code,
        )
    }

    private fun merchant(sender: String, text: String): String? {
        val header = SmsParsers.header(sender) ?: sender.trim().uppercase()
        MERCHANTS.entries.firstOrNull { header.contains(it.key) }?.let { return it.value }
        // Not from a shop's own header: only trust the text if it clearly talks about an order / package.
        if (!rx("""\b(?:order|package|parcel|shipment|delivery)\b""").containsMatchIn(text)) return null
        val upper = text.uppercase()
        return MERCHANTS.entries.firstOrNull { (k, _) -> k.length >= 4 && Regex("""\b${Regex.escape(k)}\b""").containsMatchIn(upper) }?.value
    }

    private fun title(text: String): String? {
        for (r in TITLES) {
            val t = r.find(text)?.groupValues?.get(1)?.trim()?.trim('"', '\'', '.', ',') ?: continue
            if (t.length < 3 || t.count(Char::isDigit) > t.length / 2) continue
            if (rx("""^(?:your|the|an?|order|id|no)\b""").containsMatchIn(t) && t.split(' ').size <= 2) continue
            return if (t.length > 48) t.take(45).trimEnd() + "…" else t
        }
        return null
    }

    private val MONTHS = Month.entries.associateBy { it.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase() }

    /** "today", "tomorrow", "by Tue, 26 Nov", "on 27th November", "Nov 28", "28/11". */
    fun eta(text: String, received: LocalDate): LocalDate? {
        val t = text.lowercase()
        if (rx("""\b(?:delivered|arriving|delivery|expected)\b.{0,25}\btomorrow\b""").containsMatchIn(t)) return received.plusDays(1)
        if (rx("""\b(?:delivered|arriving|delivery|expected)\b.{0,25}\btoday\b""").containsMatchIn(t)) return received
        val cue = """(?:by|on|before|arriving|expected|delivery date|delivered)\D{0,14}?"""
        rx("""$cue(\d{1,2})(?:st|nd|rd|th)?[\s-]+(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*""").find(t)?.let { m ->
            return dated(received, m.groupValues[2], m.groupValues[1].toInt())
        }
        rx("""$cue(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s+(\d{1,2})\b""").find(t)?.let { m ->
            return dated(received, m.groupValues[1], m.groupValues[2].toInt())
        }
        rx("""$cue(\d{1,2})/(\d{1,2})(?:/\d{2,4})?\b""").find(t)?.let { m ->
            val month = m.groupValues[2].toInt()
            if (month in 1..12) return dated(received, Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH).lowercase(), m.groupValues[1].toInt())
        }
        return null
    }

    /** Day + month near [received]: a date that looks long past means next year (Dec → Jan). */
    private fun dated(received: LocalDate, mon: String, day: Int): LocalDate? {
        val month = MONTHS[mon.take(3)] ?: return null
        val d = runCatching { LocalDate.of(received.year, month, day) }.getOrNull() ?: return null
        return if (d.isBefore(received.minusDays(60))) d.plusYears(1) else d
    }
}
