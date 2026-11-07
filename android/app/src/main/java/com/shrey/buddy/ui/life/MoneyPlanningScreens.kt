package com.shrey.buddy.ui.life

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.items.Budget
import com.shrey.buddy.data.items.Holding
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Recurring
import com.shrey.buddy.finance.BudgetLogic
import com.shrey.buddy.finance.Category
import com.shrey.buddy.finance.OccurrenceStatus
import com.shrey.buddy.finance.RecurringLogic
import com.shrey.buddy.finance.SearchHit
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.Choices
import com.shrey.buddy.ui.components.DateField
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.components.FormSheet
import com.shrey.buddy.ui.components.Pill
import com.shrey.buddy.ui.components.ToggleField
import com.shrey.buddy.ui.components.minorToRupeesText
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.components.parseRupeesToMinor
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.inr
import com.shrey.buddy.ui.money.color
import com.shrey.buddy.ui.money.icon
import com.shrey.buddy.ui.theme.BuddyColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import kotlin.math.abs

// ---------------------------------------------------------------- Net worth (live prices)

private val holdingKinds = listOf(
    "mutual_fund" to "Mutual fund", "stock" to "Stock / ETF", "gold" to "Gold", "fd" to "FD", "epf" to "EPF",
    "ppf" to "PPF", "crypto" to "Crypto", "property" to "Property", "other" to "Other",
)

@Composable
fun NetWorthScreen(lvm: LifeViewModel, vm: AppViewModel, onBack: () -> Unit) {
    val holdings by lvm.holdings.collectAsStateWithLifecycle()
    val money by vm.money.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Item<Holding>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val color = LifeModule.NET_WORTH.color
    val bank = money.accounts.filter { it.includeInTotals }.sumOf { it.balanceMinor ?: 0 }
    val invested = holdings.sumOf { it.data.investedMinor }
    val current = holdings.sumOf { it.data.currentMinor }
    // Day change: live holdings with a previous price.
    val dayChange = holdings.sumOf { h ->
        val prev = h.data.previousPrice
        if (h.data.source != "manual" && prev != null && h.data.units > 0) ((h.data.price - prev) * h.data.units * 100).toLong() else 0L
    }

    ModuleScaffold(LifeModule.NET_WORTH, lvm, onBack, onAdd = { adding = true }) {
        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Net worth", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { lvm.refreshPrices() }) {
                        Icon(Icons.Rounded.Refresh, null, tint = color)
                        Text("Update prices", color = color, modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Text(inr(bank + current), style = MaterialTheme.typography.displaySmall.numeric())
                if (dayChange != 0L) {
                    Text(
                        "${if (dayChange >= 0) "▲" else "▼"} ${inr(abs(dayChange))} today",
                        style = MaterialTheme.typography.labelLarge, color = if (dayChange >= 0) BuddyColors.Lime else BuddyColors.Red,
                    )
                }
                Row(Modifier.padding(top = 12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Bank balances", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                        Text(inr(bank), style = MaterialTheme.typography.titleMedium.numeric())
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Investments", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                        Text(inr(current), style = MaterialTheme.typography.titleMedium.numeric())
                        if (invested > 0) {
                            val gain = current - invested
                            Text(
                                "${if (gain >= 0) "+" else "−"}${inr(abs(gain))} (${"%.1f".format(gain * 100.0 / invested)}%) overall",
                                style = MaterialTheme.typography.labelSmall, color = if (gain >= 0) BuddyColors.Lime else BuddyColors.Red,
                            )
                        }
                    }
                }
            }
        }
        item {
            Text(
                "Mutual funds update from AMFI's daily NAV and stocks/ETFs from NSE prices — automatically every morning. " +
                    "Only the fund code or ticker leaves your phone.",
                style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
            )
        }
        if (holdings.isEmpty()) item { EmptyNote("Tap ＋ and search for a fund or stock.") }
        items(holdings.sortedByDescending { it.data.currentMinor }, key = { it.id }) { h ->
            val d = h.data
            val gain = d.currentMinor - d.investedMinor
            val day = d.previousPrice?.takeIf { d.source != "manual" && it > 0 }?.let { (d.price - it) / it * 100 }
            BuddyCard(Modifier.fillMaxWidth(), onClick = { editing = h }, contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text(
                            if (d.source == "manual") "${holdingKinds.firstOrNull { it.first == d.kind }?.second ?: d.kind} · value typed by you"
                            else "%.3f units × ₹%,.2f".format(d.units, d.price) + (parseDate(d.priceDate)?.let { " · ${friendlyDay(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(inr(d.currentMinor), style = MaterialTheme.typography.titleMedium.numeric())
                        if (d.investedMinor > 0) {
                            Text(
                                "${if (gain >= 0) "+" else "−"}${inr(abs(gain))}",
                                style = MaterialTheme.typography.labelSmall, color = if (gain >= 0) BuddyColors.Lime else BuddyColors.Red,
                            )
                        }
                        if (day != null) {
                            Text(
                                "%s%.2f%% today".format(if (day >= 0) "+" else "", day),
                                style = MaterialTheme.typography.labelSmall, color = if (day >= 0) BuddyColors.Lime else BuddyColors.Red,
                            )
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) HoldingSheet(lvm, editing, onDone = { adding = false; editing = null })
}

@Composable
private fun HoldingSheet(lvm: LifeViewModel, initial: Item<Holding>?, onDone: () -> Unit) {
    val d = initial?.data ?: Holding()
    val color = LifeModule.NET_WORTH.color
    var mode by remember { mutableStateOf(if (initial == null) "mf" else d.source) }
    var name by remember { mutableStateOf(d.name) }
    var symbol by remember { mutableStateOf(d.symbol) }
    var kind by remember { mutableStateOf(d.kind) }
    var units by remember { mutableStateOf(if (d.units > 0) "%.4f".format(d.units).trimEnd('0').trimEnd('.') else "") }
    var value by remember { mutableStateOf(if (d.source == "manual" && d.currentMinor > 0) minorToRupeesText(d.currentMinor) else "") }
    var invested by remember { mutableStateOf(if (d.investedMinor > 0) minorToRupeesText(d.investedMinor) else "") }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }

    // Debounced search as you type.
    LaunchedEffect(query, mode) {
        if (query.length < 3 || mode == "manual") { hits = emptyList(); return@LaunchedEffect }
        delay(400)
        hits = if (mode == "mf") lvm.searchFunds(query) else lvm.searchStocks(query)
    }

    FormSheet(
        title = if (initial == null) "Add investment" else "Edit investment",
        onDismiss = onDone,
        accent = color,
        saveEnabled = name.isNotBlank() && (mode == "manual" || symbol.isNotBlank()),
        onDelete = initial?.let { { lvm.delete(it.id); onDone() } },
        onSave = {
            val live = mode != "manual"
            lvm.saveHolding(
                initial?.id,
                d.copy(
                    name = name.trim(),
                    kind = if (mode == "mf") "mutual_fund" else if (mode == "stock" && kind == "mutual_fund") "stock" else kind,
                    source = mode,
                    symbol = if (live) symbol else "",
                    units = units.toDoubleOrNull() ?: 0.0,
                    investedMinor = parseRupeesToMinor(invested) ?: 0,
                    currentMinor = if (live) d.currentMinor else parseRupeesToMinor(value) ?: parseRupeesToMinor(invested) ?: 0,
                ),
                currentValueMinor = parseRupeesToMinor(value),
            )
            onDone()
        },
    ) {
        Choices(listOf("mf" to "Mutual fund", "stock" to "Stock / ETF", "manual" to "Other (manual)"), mode, { mode = it; hits = emptyList() }, color)
        if (mode != "manual") {
            Field(query, { query = it }, if (mode == "mf") "Search fund, e.g. parag parikh flexi direct" else "Search stock, e.g. infosys, goldbees", accent = color)
            hits.take(8).forEach { h ->
                Text(
                    "${h.name}  ·  ${h.symbol}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (h.symbol == symbol) color else BuddyColors.TextPrimary,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        symbol = h.symbol
                        name = h.name
                        hits = emptyList()
                        query = ""
                    }.padding(vertical = 8.dp, horizontal = 4.dp),
                )
            }
            if (symbol.isNotBlank()) Pill("Linked: $symbol", color)
        } else {
            Choices(holdingKinds.filter { it.first !in setOf("mutual_fund", "stock") }, kind, { kind = it }, color)
        }
        Field(name, { name = it }, "Name", accent = color)
        if (mode != "manual") {
            Field(units, { units = it }, "Units held", keyboard = KeyboardType.Decimal, accent = color)
            Field(value, { value = it }, "…or today's value ₹ (units worked out for you)", keyboard = KeyboardType.Decimal, accent = color)
        } else {
            Field(value, { value = it }, "Current value ₹", keyboard = KeyboardType.Decimal, accent = color)
        }
        Field(invested, { invested = it }, "Amount invested ₹ (for gain/loss)", keyboard = KeyboardType.Decimal, accent = color)
    }
}

// ---------------------------------------------------------------- Recurring

private val frequencies = listOf("monthly" to "Monthly", "weekly" to "Weekly", "quarterly" to "Quarterly", "yearly" to "Yearly")

@Composable
fun RecurringScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val list by lvm.recurring.collectAsStateWithLifecycle()
    val txns by lvm.transactions.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Item<Recurring>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    val active = list.filter { it.data.active }

    ModuleScaffold(LifeModule.RECURRING, lvm, onBack, onAdd = { adding = true }) {
        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Text("Committed every month", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                Text(inr(active.filter { it.data.direction == "debit" }.sumOf { RecurringLogic.monthlyMinor(it.data) }), style = MaterialTheme.typography.displaySmall.numeric())
                val income = active.filter { it.data.direction == "credit" }.sumOf { RecurringLogic.monthlyMinor(it.data) }
                if (income > 0) Text("+${inr(income)} expected in", style = MaterialTheme.typography.labelLarge, color = BuddyColors.Lime)
                Text(
                    "Payments are ticked off automatically when the matching bank SMS arrives.",
                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (list.isEmpty()) item { EmptyNote("Add rent, SIPs, EMIs, subscriptions, salary…") }
        items(list.sortedBy { r -> RecurringLogic.occurrences(r.data, emptyList(), today, back = 0, ahead = 400).firstOrNull()?.date ?: LocalDate.MAX }, key = { it.id }) { item ->
            val r = item.data
            val occ = RecurringLogic.occurrences(r, txns, today, back = 35, ahead = 40)
            val next = occ.firstOrNull { it.status != OccurrenceStatus.PAID && !it.date.isBefore(today) }
            val overdue = occ.filter { it.status == OccurrenceStatus.OVERDUE }
            val cat = Category.of(r.category)
            BuddyCard(Modifier.fillMaxWidth(), onClick = { editing = item }, contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(cat.icon, null, tint = cat.color)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(r.name + if (!r.active) " (paused)" else "", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${frequencies.firstOrNull { it.first == r.frequency }?.second} · " +
                                (next?.let { "next ${friendlyDay(it.date)}" } ?: "nothing due soon"),
                            style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary,
                        )
                    }
                    Text((if (r.direction == "credit") "+" else "") + inr(r.amountMinor), style = MaterialTheme.typography.titleMedium.numeric(), color = if (r.direction == "credit") BuddyColors.Lime else BuddyColors.TextPrimary)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    occ.filter { !it.date.isAfter(today.plusDays(31)) }.takeLast(3).forEach { o ->
                        val (label, c) = when (o.status) {
                            OccurrenceStatus.PAID -> "✓ ${friendlyDay(o.date)}" to BuddyColors.Lime
                            OccurrenceStatus.OVERDUE -> "Missed ${friendlyDay(o.date)}" to BuddyColors.Red
                            OccurrenceStatus.DUE -> "Due today" to BuddyColors.Fitness
                            OccurrenceStatus.UPCOMING -> friendlyDay(o.date) to BuddyColors.TextSecondary
                        }
                        Pill(label, c)
                    }
                }
                overdue.lastOrNull()?.let { o ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedButton(onClick = { lvm.settle(item, o.date, logTransaction = true) }) { Text("Paid (log it)") }
                        TextButton(onClick = { lvm.settle(item, o.date, logTransaction = false) }) { Text("Skip", color = BuddyColors.TextTertiary) }
                    }
                }
            }
        }
    }
    if (adding || editing != null) RecurringSheet(lvm, editing, onDone = { adding = false; editing = null })
}

@Composable
private fun RecurringSheet(lvm: LifeViewModel, initial: Item<Recurring>?, onDone: () -> Unit) {
    val accounts by lvm.accounts.collectAsStateWithLifecycle()
    val d = initial?.data ?: Recurring()
    var name by remember { mutableStateOf(d.name) }
    var amount by remember { mutableStateOf(if (d.amountMinor > 0) minorToRupeesText(d.amountMinor) else "") }
    var direction by remember { mutableStateOf(d.direction) }
    var category by remember { mutableStateOf(Category.of(d.category)) }
    var frequency by remember { mutableStateOf(d.frequency) }
    var start by remember { mutableStateOf(parseDate(d.startDate) ?: LocalDate.now()) }
    var accountId by remember { mutableStateOf(d.accountId) }
    var match by remember { mutableStateOf(d.match) }
    var autoLog by remember { mutableStateOf(d.autoLog) }
    var active by remember { mutableStateOf(d.active) }
    val color = LifeModule.RECURRING.color

    FormSheet(
        title = if (initial == null) "New recurring" else "Edit recurring",
        onDismiss = onDone,
        accent = color,
        saveEnabled = name.isNotBlank() && (parseRupeesToMinor(amount) ?: 0) > 0,
        onDelete = initial?.let { { lvm.delete(it.id); onDone() } },
        onSave = {
            lvm.save(
                Kinds.RECURRING,
                d.copy(
                    name = name.trim(), amountMinor = parseRupeesToMinor(amount) ?: 0, direction = direction, category = category.slug,
                    accountId = accountId, frequency = frequency,
                    // The schedule follows the first due date's day (and month, for yearly).
                    day = if (frequency == "weekly") start.dayOfWeek.value else start.dayOfMonth, month = start.monthValue,
                    startDate = start.toString(), match = match.trim(), autoLog = autoLog, active = active,
                ),
                Recurring.serializer(), id = initial?.id,
            )
            onDone()
        },
    ) {
        Field(name, { name = it }, "Name (Rent, Flexi cap SIP, Netflix…)", accent = color)
        Choices(listOf("debit" to "Money out", "credit" to "Money in"), direction, { direction = it; category = if (it == "credit") Category.SALARY else Category.BILLS }, color)
        Field(amount, { amount = it }, "Amount ₹", keyboard = KeyboardType.Decimal, accent = color)
        Choices(frequencies, frequency, { frequency = it }, color, label = "Repeats")
        DateField("First / next due date", start, { start = it ?: LocalDate.now() }, allowClear = false)
        Choices((if (direction == "credit") Category.income else Category.spending).map { it to it.label }, category, { category = it }, color, label = "Category")
        if (accounts.isNotEmpty()) {
            val options: List<Pair<String?, String>> = listOf<Pair<String?, String>>(null to "Any account") + accounts.filter { !it.archived }.map { it.id to it.name }
            Choices(options, accountId, { accountId = it }, color, label = "Paid from")
        }
        Field(match, { match = it }, "Bank text to recognise it (e.g. ICCL, landlord's name)", accent = color)
        ToggleField("Log it automatically on the due date", autoLog, { autoLog = it }, "For cash payments the bank won't report")
        if (initial != null) ToggleField("Active", active, { active = it })
    }
}

// ---------------------------------------------------------------- Budgets

@Composable
fun BudgetsScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val budgets by lvm.budgets.collectAsStateWithLifecycle()
    val txns by lvm.transactions.collectAsStateWithLifecycle()
    val accounts by lvm.accounts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Item<Budget>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val excluded = accounts.filter { !it.includeInTotals }.map { it.id }.toSet()
    val status = BudgetLogic.status(budgets.map { it.data }, txns, excluded)
    val today = LocalDate.now()
    val daysLeft = today.lengthOfMonth() - today.dayOfMonth + 1

    ModuleScaffold(LifeModule.BUDGETS, lvm, onBack, onAdd = { adding = true }) {
        item {
            Text(
                "Limits for this month. You'll get a notification at 80% and 100%. $daysLeft days left this month.",
                style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
            )
        }
        if (budgets.isEmpty()) item { EmptyNote("Start with an overall monthly budget, then add categories like Food or Shopping.") }
        // Budgets can share a category now (e.g. two named ones), so pair each with its own status.
        val rows = budgets.map { it to BudgetLogic.status(listOf(it.data), txns, excluded).first() }
            .sortedWith(compareByDescending<Pair<Item<Budget>, com.shrey.buddy.finance.BudgetStatus>> { it.first.data.category == "total" }.thenByDescending { it.second.fraction })
        items(rows, key = { it.first.id }) { (item, s) ->
            BudgetBar(s.budget, s.spentMinor, daysLeft) { editing = item }
        }
    }
    if (adding || editing != null) {
        val b = editing
        var category by remember { mutableStateOf(b?.data?.category ?: if (budgets.none { it.data.category == "total" }) "total" else Category.FOOD.slug) }
        var limit by remember { mutableStateOf(b?.data?.limitMinor?.let { minorToRupeesText(it) } ?: "") }
        var name by remember { mutableStateOf(b?.data?.name.orEmpty()) }
        val subs by lvm.subcategories.collectAsStateWithLifecycle()
        val color = LifeModule.BUDGETS.color
        FormSheet(
            title = if (b == null) "New budget" else "Edit budget",
            onDismiss = { adding = false; editing = null },
            accent = color,
            saveEnabled = (parseRupeesToMinor(limit) ?: 0) > 0,
            onDelete = b?.let { { lvm.delete(it.id); editing = null } },
            onSave = {
                // Unnamed: one budget per category (a stable ID, so re-adding replaces). Named ones are separate.
                lvm.save(Kinds.BUDGET, Budget(category, parseRupeesToMinor(limit) ?: 0, name.trim()), Budget.serializer(),
                    id = b?.id ?: if (name.isBlank()) com.shrey.buddy.data.items.ItemStore.stableId("budget:$category") else null)
                adding = false; editing = null
            },
        ) {
            Field(name, { name = it }, "Name (optional, e.g. Eating out)", accent = color)
            if (b == null) {
                Choices(
                    listOf("total" to "Overall") + Category.spending.flatMap { c ->
                        listOf(c.slug to c.label) + subs.filter { it.data.parent == c.slug }.map { Category.key(c, it.data.name) to "${c.label} › ${it.data.name}" }
                    },
                    category, { category = it }, color, label = "For",
                )
            }
            Field(limit, { limit = it }, "Monthly limit ₹", keyboard = KeyboardType.Decimal, accent = color)
        }
    }
}

@Composable
fun BudgetBar(budget: Budget, spentMinor: Long, daysLeft: Int, onClick: (() -> Unit)? = null) {
    val frac = if (budget.limitMinor > 0) spentMinor.toFloat() / budget.limitMinor else 0f
    val barColor = when {
        frac >= 1f -> BuddyColors.Red
        frac >= 0.8f -> BuddyColors.Fitness
        else -> BuddyColors.Lime
    }
    val base = if (budget.category == "total") "Overall" else listOfNotNull(Category.of(budget.category).label, Category.subOf(budget.category)).joinToString(" › ")
    val label = budget.name.ifBlank { base }
    val left = budget.limitMinor - spentMinor
    BuddyCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (budget.category != "total") Icon(Category.of(budget.category).icon, null, tint = Category.of(budget.category).color, modifier = Modifier.padding(end = 10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${inr(spentMinor)} / ${inr(budget.limitMinor)}", style = MaterialTheme.typography.labelLarge.numeric())
        }
        LinearProgressIndicator(
            progress = { frac.coerceIn(0f, 1f) }, color = barColor, trackColor = BuddyColors.SurfaceHigh,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
        )
        Text(
            if (left >= 0) "${inr(left)} left · about ${inr(left / daysLeft.coerceAtLeast(1))}/day" else "Over by ${inr(-left)}",
            style = MaterialTheme.typography.labelSmall, color = if (left >= 0) BuddyColors.TextSecondary else BuddyColors.Red,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}
