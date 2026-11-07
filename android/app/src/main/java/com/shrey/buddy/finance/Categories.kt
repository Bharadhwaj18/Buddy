package com.shrey.buddy.finance

/** Fixed category set for v1 (slugs are what's stored and synced). */
enum class Category(val slug: String, val label: String, val isIncome: Boolean = false) {
    FOOD("food", "Food & dining"),
    GROCERIES("groceries", "Groceries"),
    SHOPPING("shopping", "Shopping"),
    TRANSPORT("transport", "Transport"),
    FUEL("fuel", "Fuel"),
    TRAVEL("travel", "Travel"),
    BILLS("bills", "Bills & utilities"),
    SUBSCRIPTIONS("subscriptions", "Subscriptions"),
    HEALTH("health", "Health & fitness"),
    ENTERTAINMENT("entertainment", "Entertainment"),
    EDUCATION("education", "Education"),
    RENT("rent", "Rent & home"),
    PEOPLE("people", "People"),
    INVESTMENTS("investments", "Investments"),
    FEES("fees", "Bank fees"),
    OTHER("other", "Other"),
    SALARY("salary", "Salary", isIncome = true),
    INCOME("income", "Income", isIncome = true),
    REFUNDS("refunds", "Refunds & cashback", isIncome = true),
    TRANSFER("transfer", "Transfer");

    companion object {
        /** Accepts a plain slug ("food") or one with a sub-category ("food/Coffee"). */
        fun of(slug: String?): Category = slug?.substringBefore('/').let { s -> entries.firstOrNull { it.slug == s } } ?: OTHER

        /** The sub-category part of a stored value ("food/Coffee" → "Coffee"), if any. */
        fun subOf(stored: String?): String? = stored?.substringAfter('/', "")?.trim()?.ifEmpty { null }

        /** What gets stored on a transaction: "food" or "food/Coffee". */
        fun key(category: Category, sub: String?): String = sub?.trim()?.replace("/", "-")?.ifEmpty { null }?.let { "${category.slug}/$it" } ?: category.slug

        /** Does a transaction stored as [stored] count towards a budget / filter for [target] ("food" or "food/Coffee")? */
        fun matches(stored: String, target: String): Boolean = stored == target || ('/' !in target && stored.startsWith("$target/"))
        val spending = entries.filter { !it.isIncome && it != TRANSFER }
        val income = entries.filter { it.isIncome }
    }
}

/**
 * Rule-based first pass (DESIGN.md §5). Matches on the raw counterparty (VPA / bank text) and channel,
 * not the prettified payee, so "Google Pay merchant" isn't mistaken for a Google subscription.
 * Gemini fallback and learning from corrections come later.
 */
object Categorizer {
    private fun rule(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    private val debitRules: List<Pair<Regex, Category>> = listOf(
        rule("maintenance charge|\\bcharges?\\b|\\bfee\\b|penalty|gst on") to Category.FEES,
        rule("iccl|mutual|\\bsip\\b|zerodha|groww|indmoney|sbi funds|\\bnps\\b|kuvera|upstox|smallcase") to Category.INVESTMENTS,
        rule("swiggy|zomato|eatclub|domino|pizza|kfc|mcdonald|burger|restaurant|udupi|cafe|bakery|food|dine|chai|biryani|darshini") to Category.FOOD,
        rule("blinkit|zepto|bigbasket|instamart|dmart|grocer|supermarket|jiomart|freshtohome|licious|milk") to Category.GROCERIES,
        rule("amazon|amzn|flipkart|myntra|ajio|nykaa|meesho|headphone|croma|reliance digital|decathlon|ikea|lenskart") to Category.SHOPPING,
        rule("fuel|petrol|diesel|hpcl|iocl|bpcl|indian ?oil|bharat petro|hindustan petro|\\bshell\\b|nayara|filling station|\\bcng\\b") to Category.FUEL,
        rule("uber|\\bola\\b|rapido|metro|irctc|redbus|fastag|toll|parking|namma") to Category.TRANSPORT,
        rule("makemytrip|goibibo|indigo|air ?india|vistara|akasa|booking\\.com|airbnb|\\boyo\\b|cleartrip|ixigo") to Category.TRAVEL,
        rule("netflix|spotify|youtube|prime ?video|hotstar|apple\\.com|openai|chatgpt|anthropic|notion|icloud|google india digital|\\bgoogle\\b") to Category.SUBSCRIPTIONS,
        rule("airtel|\\bjio\\b|vodafone|bescom|electricity|power|water|\\bgas\\b|broadband|fibernet|recharge|bbps|\\bbill") to Category.BILLS,
        rule("pharma|apollo|medplus|1mg|netmeds|hospital|clinic|diagnostic|cult\\.?fit|\\bgym\\b|fitness") to Category.HEALTH,
        rule("bookmyshow|\\bpvr\\b|inox|district|steam|playstation") to Category.ENTERTAINMENT,
        rule("udemy|coursera|unacademy|byju") to Category.EDUCATION,
        rule("\\brent\\b|nobroker|housing|society|maintenance") to Category.RENT,
    )

    private val creditRefund = rule("refund|reversal|cashback|goog-payments|itdtax")
    private val companyName = rule("\\b(pty|pvt|ltd|limited|llp|inc|payroll|technologies|solutions|payme)\\b")

    fun categorize(direction: String, counterpartyRaw: String?, channel: String?, amountMinor: Long): Category {
        val haystack = listOfNotNull(counterpartyRaw, channel).joinToString(" ")
        if (direction == "credit") {
            return when {
                creditRefund.containsMatchIn(haystack) -> Category.REFUNDS
                // Large bank-transfer credits from a company: almost always salary.
                channel in setOf("IMPS", "NEFT", "RTGS") && amountMinor >= 20_000_00 &&
                    companyName.containsMatchIn(counterpartyRaw.orEmpty()) -> Category.SALARY
                else -> Category.INCOME
            }
        }
        debitRules.firstOrNull { (re, _) -> re.containsMatchIn(haystack) }?.let { return it.second }
        return when {
            channel?.contains("AutoPay", ignoreCase = true) == true -> Category.SUBSCRIPTIONS
            channel in setOf("IMPS", "NEFT", "RTGS") -> Category.PEOPLE
            else -> Category.OTHER
        }
    }
}
