package com.shrey.buddy.ui.life

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Book
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material.icons.rounded.PieChart
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.StickyNote2
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shrey.buddy.AppContainer
import com.shrey.buddy.data.items.Document
import com.shrey.buddy.data.items.Goal
import com.shrey.buddy.data.items.Holding
import com.shrey.buddy.data.items.Insight
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Journal
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Note
import com.shrey.buddy.data.items.Person
import com.shrey.buddy.data.items.ReadingItem
import com.shrey.buddy.data.items.Vehicle
import com.shrey.buddy.data.items.VehicleLog
import com.shrey.buddy.data.items.Wish
import com.shrey.buddy.ui.theme.BuddyColors
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import java.time.LocalDate
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class LifeModule(val label: String, val icon: ImageVector, val color: Color, val blurb: String, val inLifeGrid: Boolean = true) {
    RECURRING("Recurring", Icons.Rounded.EventRepeat, BuddyColors.Lime, "Rent, SIPs, EMIs, subscriptions", inLifeGrid = false),
    BUDGETS("Budgets", Icons.Rounded.PieChart, BuddyColors.Lime, "Monthly limits", inLifeGrid = false),
    JOURNAL("Journal", Icons.Rounded.Book, Color(0xFFFFD60A), "Mood & daily notes"),
    NOTES("Notes", Icons.Rounded.StickyNote2, Color(0xFF64D2FF), "Second brain"),
    GOALS("Goals", Icons.Rounded.EmojiEvents, BuddyColors.Lime, "Targets & progress"),
    PEOPLE("People", Icons.Rounded.People, Color(0xFFFF6FAE), "Birthdays & catch-ups"),
    READING("Reading", Icons.AutoMirrored.Rounded.MenuBook, Color(0xFF30D5C8), "Books & courses"),
    VEHICLES("Vehicles", Icons.Rounded.DirectionsCar, BuddyColors.Fitness, "Fuel, service, renewals"),
    WISHLIST("Wishlist", Icons.Rounded.ShoppingBag, Color(0xFFBF5AF2), "Think before you buy"),
    VAULT("Documents", Icons.Rounded.Folder, Color(0xFFB0B4BC), "IDs, insurance, warranties"),
    NET_WORTH("Net worth", Icons.AutoMirrored.Rounded.TrendingUp, Color(0xFF34C759), "Investments & balances"),
    INSIGHTS("Insights", Icons.Rounded.AutoAwesome, BuddyColors.Voice, "Briefings & reviews"),
}

data class Reminder(val title: String, val detail: String, val date: LocalDate, val module: LifeModule)

class LifeViewModel(private val c: AppContainer) : ViewModel() {
    private val items get() = c.items
    private fun <T> obs(kind: String, s: KSerializer<T>): StateFlow<List<Item<T>>> =
        items.observe(kind, s).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val journal = obs(Kinds.JOURNAL, Journal.serializer())
    val notes = obs(Kinds.NOTE, Note.serializer())
    val goals = obs(Kinds.GOAL, Goal.serializer())
    val people = obs(Kinds.PERSON, Person.serializer())
    val reading = obs(Kinds.READING, ReadingItem.serializer())
    val vehicles = obs(Kinds.VEHICLE, Vehicle.serializer())
    val vehicleLogs = obs(Kinds.VEHICLE_LOG, VehicleLog.serializer())
    val wishes = obs(Kinds.WISH, Wish.serializer())
    val documents = obs(Kinds.DOCUMENT, Document.serializer())
    val holdings = obs(Kinds.HOLDING, Holding.serializer())
    val insights = obs(Kinds.INSIGHT, Insight.serializer())
    val recurring = obs(Kinds.RECURRING, com.shrey.buddy.data.items.Recurring.serializer())
    val budgets = obs(Kinds.BUDGET, com.shrey.buddy.data.items.Budget.serializer())
    val subcategories = obs(Kinds.SUBCATEGORY, com.shrey.buddy.data.items.Subcategory.serializer())
    val transactions = c.finance.observeTransactions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts = c.finance.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Investments: live prices ----

    fun refreshPrices() = viewModelScope.launch {
        _busy.value = "Updating prices…"
        try {
            c.planning.refreshHoldings(force = true)?.let { _message.value = it }
        } finally {
            _busy.value = null
        }
    }

    suspend fun searchFunds(q: String) = runCatching { com.shrey.buddy.finance.MarketData.searchFunds(q) }.getOrDefault(emptyList())
    suspend fun searchStocks(q: String) = runCatching { com.shrey.buddy.finance.MarketData.searchStocks(q) }.getOrDefault(emptyList())

    /**
     * Saves a holding. Live ones are priced right away; if [currentValueMinor] is given instead of units,
     * units are derived from today's price.
     */
    fun saveHolding(id: String?, h: Holding, currentValueMinor: Long?) = viewModelScope.launch {
        if (h.source == "manual" || h.symbol.isBlank()) {
            items.put(Kinds.HOLDING, h.copy(updatedOn = LocalDate.now().toString()), Holding.serializer(), id = id)
            return@launch
        }
        _busy.value = "Fetching price…"
        try {
            val q = com.shrey.buddy.finance.MarketData.quote(h.source, h.symbol)
            val units = if (h.units > 0) h.units else (currentValueMinor ?: 0) / 100.0 / q.price
            items.put(Kinds.HOLDING, c.planning.priced(h.copy(units = units), q.price, q.previous, q.date), Holding.serializer(), id = id)
        } catch (e: Exception) {
            _message.value = "Couldn't fetch the price (${e.message}). Saved without it."
            items.put(Kinds.HOLDING, h, Holding.serializer(), id = id)
        } finally {
            _busy.value = null
        }
    }

    /** Mark one occurrence handled without a bank record (paid in cash, skipped this month…). */
    fun settle(item: Item<com.shrey.buddy.data.items.Recurring>, date: LocalDate, logTransaction: Boolean) = viewModelScope.launch {
        val r = item.data
        if (logTransaction) {
            c.finance.addManualTransaction(
                direction = r.direction, amountMinor = r.amountMinor, payee = r.name,
                category = com.shrey.buddy.finance.Category.of(r.category), accountHint = null, note = "Recurring",
                source = "manual", accountId = r.accountId,
                occurredAt = date.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            )
        }
        items.put(Kinds.RECURRING, r.copy(settled = (r.settled + date.toString()).distinct().takeLast(60)), com.shrey.buddy.data.items.Recurring.serializer(), id = item.id)
    }

    /** A fill-up logged from the Vehicles screen: also a Fuel transaction in Money, linked to it. */
    fun addFuelWithTransaction(vehicleId: String, vehicleName: String, amountMinor: Long, litres: Double, km: Int, day: LocalDate) = viewModelScope.launch {
        val id = java.util.UUID.randomUUID().toString()
        val at = if (day == LocalDate.now()) System.currentTimeMillis() else day.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        c.finance.addManualTransaction(
            direction = "debit", amountMinor = amountMinor, payee = "Fuel · $vehicleName", category = com.shrey.buddy.finance.Category.FUEL,
            accountHint = null, note = null, source = "manual", occurredAt = at, id = id,
        )
        c.fuel.save(id, amountMinor, day, com.shrey.buddy.finance.FuelEntry(vehicleId, litres, km))
    }

    private val _busy = MutableStateFlow<String?>(null)
    /** Current long-running action label (AI generation, upload), or null. */
    val busy = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    fun clearMessage() { _message.value = null }

    fun <T> save(kind: String, data: T, serializer: KSerializer<T>, id: String? = null, date: LocalDate? = null, refId: String? = null) =
        viewModelScope.launch { items.put(kind, data, serializer, id = id, occurredOn = date, refId = refId) }

    fun delete(id: String) = viewModelScope.launch { items.delete(id) }

    /** Journal: one entry per day. */
    fun saveJournal(day: LocalDate, entry: Journal) =
        save(Kinds.JOURNAL, entry, Journal.serializer(), id = ItemStore.stableId("journal:$day"), date = day)

    // ---- Reminders (for Today + the daily notification) ----

    val reminders: StateFlow<List<Reminder>> = combine(people, documents, vehicles, wishes, goals) { ppl, docs, cars, wish, gls ->
        buildReminders(ppl, docs, cars, wish, gls)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- AI ----

    fun generate(kind: String) = viewModelScope.launch {
        _busy.value = when (kind) { "weekly" -> "Writing your weekly review…"; "insights" -> "Looking for patterns…"; else -> "Writing your briefing…" }
        try {
            if (kind == "insights") {
                val list = c.briefings.insights()
                if (list.isEmpty()) _message.value = "Not enough data for insights yet — keep logging for a few days."
            } else {
                c.briefings.briefing(kind)
            }
        } catch (e: Exception) {
            _message.value = e.message ?: "Couldn't reach Buddy AI"
        } finally {
            _busy.value = null
        }
    }

    // ---- Document vault (Supabase Storage, private per-user folder) ----

    fun upload(context: Context, uri: Uri, title: String, category: String, expiry: LocalDate?, notes: String) = viewModelScope.launch {
        _busy.value = "Uploading…"
        try {
            val uid = c.supabase.auth.currentUserOrNull()?.id ?: error("Sign in to upload documents")
            val (name, size) = withContext(Dispatchers.IO) {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) cur.getString(0) to cur.getLong(1) else null
                } ?: ("document" to 0L)
            }
            if (size > 25L * 1024 * 1024) error("Files up to 25 MB, please")
            val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
            val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val path = "$uid/${UUID.randomUUID()}-$safeName"
            c.supabase.storage.from("vault").upload(path, bytes) { upsert = false }
            items.put(
                Kinds.DOCUMENT,
                Document(title.ifBlank { name }, category, expiry?.toString(), path, mime, bytes.size.toLong(), notes),
                Document.serializer(),
            )
        } catch (e: Exception) {
            _message.value = e.message?.lineSequence()?.firstOrNull() ?: "Upload failed"
        } finally {
            _busy.value = null
        }
    }

    /** Downloads to the app cache and opens it in a viewer app. */
    fun open(context: Context, doc: Document) = viewModelScope.launch {
        _busy.value = "Opening…"
        try {
            val bytes = c.supabase.storage.from("vault").downloadAuthenticated(doc.storagePath)
            val file = withContext(Dispatchers.IO) {
                File(File(context.cacheDir, "vault").apply { mkdirs() }, doc.storagePath.substringAfterLast('/')).apply { writeBytes(bytes) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, doc.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            _message.value = e.message?.lineSequence()?.firstOrNull() ?: "Couldn't open"
        } finally {
            _busy.value = null
        }
    }

    fun deleteDocument(item: Item<Document>) = viewModelScope.launch {
        runCatching { c.supabase.storage.from("vault").delete(item.data.storagePath) }
        items.delete(item.id)
    }
}

fun nextBirthday(mmdd: String?, today: LocalDate = LocalDate.now()): LocalDate? {
    val md = mmdd?.let { runCatching { MonthDay.parse("--$it") }.getOrNull() } ?: return null
    val thisYear = md.atYear(today.year)
    return if (thisYear.isBefore(today)) md.atYear(today.year + 1) else thisYear
}

fun parseDate(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

fun buildReminders(
    people: List<Item<Person>>,
    docs: List<Item<Document>>,
    cars: List<Item<Vehicle>>,
    wishes: List<Item<Wish>>,
    goals: List<Item<Goal>>,
    today: LocalDate = LocalDate.now(),
): List<Reminder> {
    val out = mutableListOf<Reminder>()
    fun within(d: LocalDate?, days: Long) = d != null && !d.isBefore(today.minusDays(1)) && ChronoUnit.DAYS.between(today, d) <= days
    people.forEach { p ->
        nextBirthday(p.data.birthday, today)?.takeIf { within(it, 7) }?.let { out += Reminder("${p.data.name}'s birthday", "Birthday", it, LifeModule.PEOPLE) }
        if (p.data.contactEveryDays > 0) {
            val last = parseDate(p.data.lastContact)
            val due = (last ?: today.minusDays(p.data.contactEveryDays.toLong())).plusDays(p.data.contactEveryDays.toLong())
            if (!due.isAfter(today)) out += Reminder("Catch up with ${p.data.name}", "Keep in touch", today, LifeModule.PEOPLE)
        }
    }
    docs.forEach { d -> parseDate(d.data.expiry)?.takeIf { within(it, 30) }?.let { out += Reminder("${d.data.title} expires", "Document", it, LifeModule.VAULT) } }
    cars.forEach { v ->
        listOf("insurance" to v.data.insuranceExpiry, "PUC" to v.data.pucExpiry, "service" to v.data.serviceDue).forEach { (what, date) ->
            parseDate(date)?.takeIf { within(it, 30) }?.let { out += Reminder("${v.data.name}: $what due", "Vehicle", it, LifeModule.VEHICLES) }
        }
    }
    wishes.filter { it.data.status == "waiting" }.forEach { w ->
        parseDate(w.data.coolOffUntil)?.takeIf { !it.isAfter(today) }?.let { out += Reminder("Still want “${w.data.title}”?", "Cooling-off over", it, LifeModule.WISHLIST) }
    }
    goals.filter { !it.data.done }.forEach { g -> parseDate(g.data.due)?.takeIf { within(it, 7) }?.let { out += Reminder("Goal: ${g.data.title}", "Due", it, LifeModule.GOALS) } }
    return out.sortedBy { it.date }
}
