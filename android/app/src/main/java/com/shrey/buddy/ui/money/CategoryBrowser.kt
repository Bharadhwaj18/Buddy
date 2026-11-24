package com.shrey.buddy.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shrey.buddy.finance.Category
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.inr
import com.shrey.buddy.ui.screens.TxnRow
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Money back = money in filed under a spending category; it lowers that category's spend. */
private fun TxnItem.isMoneyBack() = isCredit && !isTransfer && !category.isIncome && category != Category.TRANSFER

/** What this transaction adds to its category: payments count up, money back counts down. */
private fun TxnItem.spend(): Long = when {
    isTransfer -> 0
    !isCredit -> amountMinor
    isMoneyBack() -> -amountMinor
    else -> 0
}

private val monthFmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)

/**
 * Every transaction by category: pick a month (or all time), a category and optionally one of
 * its sub-groups, or search. Tapping a transaction opens it for editing.
 */
@Composable
fun CategoryBrowser(
    transactions: List<TxnItem>,
    initial: Category?,
    onEdit: (TxnItem) -> Unit,
    onDismiss: () -> Unit,
) {
    var month by remember { mutableStateOf<YearMonth?>(YearMonth.now()) }
    var category by remember { mutableStateOf(initial) }
    var sub by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    val inPeriod = remember(transactions, month) {
        transactions.filter { !it.isTransfer && (month == null || YearMonth.from(it.date) == month) }
    }
    // Categories present this period, biggest spend first, income at the end.
    val totals = remember(inPeriod) {
        inPeriod.groupBy { it.category }.map { (c, l) ->
            c to if (c.isIncome) l.sumOf { it.amountMinor } else l.sumOf { it.spend() }
        }.sortedWith(compareBy<Pair<Category, Long>> { it.first.isIncome }.thenByDescending { it.second })
    }
    val subs = remember(inPeriod, category) {
        category?.let { c -> inPeriod.filter { it.category == c }.mapNotNull { it.sub }.distinct().sorted() }.orEmpty()
    }
    val shown = remember(inPeriod, category, sub, query) {
        inPeriod
            .filter { category == null || it.category == category }
            .filter { sub == null || it.sub == sub }
            .filter { matchesPayment(it, query) }
            .sortedByDescending { it.at }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(BuddyColors.Background).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("By category", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            }

            LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = (month ?: YearMonth.now()).minusMonths(1); sub = null }) {
                            Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month")
                        }
                        Text(month?.format(monthFmt) ?: "All time", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(
                            onClick = { month = month?.plusMonths(1); sub = null },
                            enabled = month != null && month!! < YearMonth.now(),
                        ) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month") }
                        FilterChip(
                            selected = month == null,
                            onClick = { month = if (month == null) YearMonth.now() else null; sub = null },
                            label = { Text("All time") },
                        )
                    }
                }

                item {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        placeholder = { Text("Search: name, note or amount") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            FilterChip(selected = category == null, onClick = { category = null; sub = null }, label = { Text("All") })
                        }
                        items(totals, key = { it.first.name }) { (c, total) ->
                            FilterChip(
                                selected = category == c,
                                onClick = { category = if (category == c) null else c; sub = null },
                                label = { Text("${c.label} · ${inr(total.coerceAtLeast(0))}") },
                                leadingIcon = { Icon(c.icon, null, tint = c.color, modifier = Modifier.size(16.dp)) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.color.copy(alpha = 0.18f), selectedLabelColor = BuddyColors.TextPrimary),
                            )
                        }
                    }
                }

                if (subs.isNotEmpty()) item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChip(selected = sub == null, onClick = { sub = null }, label = { Text("All ${category?.label.orEmpty()}") }) }
                        items(subs) { s -> FilterChip(selected = sub == s, onClick = { sub = if (sub == s) null else s }, label = { Text(s) }) }
                    }
                }

                item {
                    val paid = shown.filter { !it.isCredit }.sumOf { it.amountMinor }
                    val back = shown.filter { it.isMoneyBack() }.sumOf { it.amountMinor }
                    val received = shown.filter { it.isCredit && !it.isMoneyBack() }.sumOf { it.amountMinor }
                    BuddyCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                        Text(
                            "${shown.size} transaction${if (shown.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary,
                        )
                        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                            if (paid > 0 || received == 0L) Stat("Paid", inr(paid))
                            if (back > 0) Stat("Money back", inr(back), BuddyColors.Lime)
                            if (back > 0) Stat("Net spent", inr((paid - back).coerceAtLeast(0)))
                            if (received > 0) Stat("Received", inr(received), BuddyColors.Lime)
                        }
                    }
                }

                if (shown.isEmpty()) item {
                    Text("Nothing here for this period.", color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                }
                shown.groupBy { it.date }.forEach { (day, list) ->
                    item(key = day.toString()) {
                        Column {
                            Row(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp)) {
                                Text(friendlyDay(day), style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary, modifier = Modifier.weight(1f))
                                val net = list.sumOf { it.spend() }
                                if (net > 0) Text("−${inr(net)}", style = MaterialTheme.typography.labelLarge.numeric(), color = BuddyColors.TextTertiary)
                            }
                            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                                list.forEach { t -> TxnRow(t, Modifier.padding(horizontal = 8.dp), onClick = { onEdit(t) }) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: androidx.compose.ui.graphics.Color = BuddyColors.TextPrimary) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium.numeric(), color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}
