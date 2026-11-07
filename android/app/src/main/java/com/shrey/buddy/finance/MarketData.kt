package com.shrey.buddy.finance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class SearchHit(val symbol: String, val name: String, val source: String)

/** price on [date]; [previous] = the prior trading day's price, for day change. */
data class Quote(val price: Double, val previous: Double?, val date: LocalDate, val name: String?)

/**
 * Public market prices, fetched directly by the phone (only the fund code / ticker leaves the device):
 *  - Mutual funds: AMFI daily NAVs via mfapi.in (free, no key).
 *  - Stocks & ETFs (NSE/BSE): Yahoo Finance chart API (unofficial, no key).
 */
object MarketData {
    private val json = Json { ignoreUnknownKeys = true }
    private val navDate = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    private suspend fun get(url: String): JsonElement = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Buddy")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            json.parseToJsonElement(conn.inputStream.bufferedReader().readText())
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun searchFunds(query: String): List<SearchHit> =
        (get("https://api.mfapi.in/mf/search?q=${enc(query)}") as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val code = o["schemeCode"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            SearchHit(code, o["schemeName"]?.jsonPrimitive?.contentOrNull.orEmpty(), "mf")
        }.take(20)

    suspend fun searchStocks(query: String): List<SearchHit> {
        val res = get("https://query1.finance.yahoo.com/v1/finance/search?q=${enc(query)}&quotesCount=10&newsCount=0").jsonObject
        return res["quotes"]?.jsonArray.orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            val symbol = o["symbol"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val exchange = o["exchange"]?.jsonPrimitive?.contentOrNull
            if (exchange !in setOf("NSI", "BSE") && !symbol.endsWith(".NS") && !symbol.endsWith(".BO")) return@mapNotNull null
            val name = o["longname"]?.jsonPrimitive?.contentOrNull ?: o["shortname"]?.jsonPrimitive?.contentOrNull ?: symbol
            SearchHit(symbol, name, "stock")
        }
    }

    suspend fun quote(source: String, symbol: String): Quote = when (source) {
        "mf" -> fundNav(symbol)
        "stock" -> stockQuote(symbol)
        else -> error("Manual holding")
    }

    private suspend fun fundNav(code: String): Quote {
        val to = LocalDate.now()
        val res = get("https://api.mfapi.in/mf/$code?startDate=${to.minusDays(12)}&endDate=$to").jsonObject
        val rows = res["data"]?.jsonArray.orEmpty().map { it.jsonObject }
        val latest = rows.firstOrNull() ?: error("No NAV for $code")
        fun nav(o: JsonObject) = o["nav"]!!.jsonPrimitive.content.toDouble()
        return Quote(
            price = nav(latest),
            previous = rows.getOrNull(1)?.let(::nav),
            date = LocalDate.parse(latest["date"]!!.jsonPrimitive.content, navDate),
            name = res["meta"]?.jsonObject?.get("scheme_name")?.jsonPrimitive?.contentOrNull,
        )
    }

    private suspend fun stockQuote(symbol: String): Quote {
        val res = get("https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?range=5d&interval=1d").jsonObject
        val meta = res["chart"]!!.jsonObject["result"]!!.jsonArray.first().jsonObject["meta"]!!.jsonObject
        val price = meta["regularMarketPrice"]!!.jsonPrimitive.doubleOrNull ?: error("No price for $symbol")
        val time = meta["regularMarketTime"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        return Quote(
            price = price,
            previous = meta["chartPreviousClose"]?.jsonPrimitive?.doubleOrNull ?: meta["previousClose"]?.jsonPrimitive?.doubleOrNull,
            date = time?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.of("Asia/Kolkata")).toLocalDate() } ?: LocalDate.now(),
            name = meta["longName"]?.jsonPrimitive?.contentOrNull,
        )
    }
}
