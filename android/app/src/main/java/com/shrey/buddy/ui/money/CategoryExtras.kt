package com.shrey.buddy.ui.money

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.finance.Category
import com.shrey.buddy.finance.FuelEntry
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.inr
import com.shrey.buddy.ui.theme.BuddyColors

/** Chips for the sub-groups you've made under [category], plus "+ New". Tap a selected chip to clear it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubcategoryPicker(vm: AppViewModel, category: Category, selected: String?, onSelect: (String?) -> Unit) {
    val all by vm.subcategories.collectAsStateWithLifecycle()
    val subs = remember(all, category, selected) {
        (all.filter { it.data.parent == category.slug }.map { it.data.name } + listOfNotNull(selected)).distinct().sortedBy { it.lowercase() }
    }
    var creating by remember { mutableStateOf(false) }
    Column {
        Text("Sub-category", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            subs.forEach { s ->
                FilterChip(
                    selected = selected == s,
                    onClick = { onSelect(if (selected == s) null else s) },
                    label = { Text(s) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = category.color.copy(alpha = 0.18f),
                        selectedLabelColor = BuddyColors.TextPrimary,
                    ),
                )
            }
            FilterChip(
                selected = false,
                onClick = { creating = true },
                label = { Text("New") },
                leadingIcon = { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(16.dp)) },
            )
        }
    }
    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            containerColor = BuddyColors.Surface,
            title = { Text("New sub-category in ${category.label}") },
            text = {
                OutlinedTextField(name, { name = it.take(30) }, singleLine = true, placeholder = { Text("e.g. Coffee") })
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = { vm.addSubcategory(category, name); onSelect(name.trim().replace("/", "-")); creating = false },
                ) { Text("Add", color = BuddyColors.Lime) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel", color = BuddyColors.TextSecondary) } },
        )
    }
}

/** Holds what's typed in the fuel section of a transaction sheet. */
class FuelFormState(vehicleId: String?, litres: String, odometer: String) {
    var vehicleId by mutableStateOf(vehicleId)
    var litres by mutableStateOf(litres)
    var odometer by mutableStateOf(odometer)

    fun entry(): FuelEntry? = vehicleId?.let { FuelEntry(it, litres.toDoubleOrNull() ?: 0.0, odometer.toIntOrNull() ?: 0) }
}

/** Remembers the fuel section for a transaction — prefilled from its existing fill-up, if it has one. */
@Composable
fun rememberFuelForm(vm: AppViewModel, txnId: String?): FuelFormState {
    val logs by vm.vehicleLogs.collectAsStateWithLifecycle()
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    val state = remember(txnId) { FuelFormState(null, "", "") }
    var loaded by remember(txnId) { mutableStateOf(false) }
    LaunchedEffect(logs, vehicles, txnId) {
        if (loaded || vehicles.isEmpty()) return@LaunchedEffect
        val existing = txnId?.let { id -> logs.firstOrNull { it.data.txnId == id } }
        state.vehicleId = existing?.refId
            ?: vm.lastFuelVehicle()?.takeIf { last -> vehicles.any { it.id == last } }
            ?: vehicles.first().id
        if (existing != null) {
            state.litres = existing.data.litres.takeIf { it > 0 }?.toString().orEmpty()
            state.odometer = existing.data.odometerKm.takeIf { it > 0 }?.toString().orEmpty()
        }
        loaded = true
    }
    return state
}

/**
 * Shown when a transaction's category is Fuel: which vehicle, litres and odometer.
 * Saved as that vehicle's fuel fill-up, so mileage and running cost stay up to date.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FuelFields(vm: AppViewModel, state: FuelFormState, amountMinor: Long, onOpenVehicles: (() -> Unit)? = null) {
    val vehicles by vm.vehicles.collectAsStateWithLifecycle()
    val accent = Category.FUEL.color
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            Icon(Icons.Rounded.LocalGasStation, null, tint = accent, modifier = Modifier.size(18.dp))
            Text("Fuel fill-up", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 6.dp))
        }
        if (vehicles.isEmpty()) {
            Text(
                "Add your bike or car under Life → Vehicles and Buddy will track litres, odometer and mileage from here.",
                style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
            )
            onOpenVehicles?.let { TextButton(onClick = it) { Text("Add a vehicle", color = accent) } }
            return@Column
        }
        if (vehicles.size > 1) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                vehicles.forEach { v ->
                    FilterChip(
                        selected = state.vehicleId == v.id,
                        onClick = { state.vehicleId = v.id },
                        label = { Text(v.data.name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = accent.copy(alpha = 0.2f), selectedLabelColor = BuddyColors.TextPrimary),
                    )
                }
            }
        } else {
            Text(vehicles.first().data.name, style = MaterialTheme.typography.bodyMedium, color = BuddyColors.TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Field(state.litres, { state.litres = it.filter { c -> c.isDigit() || c == '.' } }, "Litres", Modifier.weight(1f), keyboard = KeyboardType.Decimal, accent = accent)
            Field(state.odometer, { state.odometer = it.filter(Char::isDigit) }, "Odometer km", Modifier.weight(1f), keyboard = KeyboardType.Number, accent = accent)
        }
        val litres = state.litres.toDoubleOrNull() ?: 0.0
        val last = vehicles.firstOrNull { it.id == state.vehicleId }?.data?.odometerKm ?: 0
        val hints = listOfNotNull(
            if (litres > 0 && amountMinor > 0) "${inr((amountMinor / litres).toLong(), paise = true)}/L" else null,
            if (last > 0) "last odometer %,d km".format(last) else null,
        )
        if (hints.isNotEmpty()) {
            Text(hints.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * On money that came in: "this is money back for something I paid". Turning it on files it under a spending
 * category (so it reduces that category's spend and isn't counted as income); picking the payment links the two
 * and copies its category.
 */
/** Search by payee, category, sub-category, note, account, date ("2 nov") or amount ("900", "₹1,200"). */
internal fun matchesPayment(t: TxnItem, query: String): Boolean {
    val q = query.lowercase().replace("₹", "").replace(",", "").trim()
    if (q.isEmpty()) return true
    val rupees = t.amountMinor / 100
    val amountText = listOf(rupees.toString(), "%.2f".format(t.amountMinor / 100.0))
    if (q.toDoubleOrNull() != null) return amountText.any { it.startsWith(q) }
    val hay = listOfNotNull(
        t.title, t.category.label, t.sub, t.note, t.accountName, t.channel,
        t.date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)),
    ).joinToString(" ").lowercase()
    return q.split(' ').filter { it.isNotBlank() }.all { it in hay }
}

@Composable
fun MoneyBackSection(
    vm: AppViewModel,
    credit: TxnItem,
    on: Boolean,
    linkedId: String?,
    onToggle: (Boolean) -> Unit,
    onPick: (TxnItem?) -> Unit,
) {
    val money by vm.money.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    // Any payment up to 2 days after the money back (you can't be paid back for something not yet paid).
    val payments = remember(money.transactions, credit.id) {
        money.transactions.filter { !it.isCredit && !it.isTransfer && !it.date.isAfter(credit.date.plusDays(2)) }
    }
    val candidates = remember(payments, query, showAll) {
        val q = query.trim()
        when {
            q.isNotEmpty() -> payments.filter { matchesPayment(it, q) }.sortedByDescending { it.at }.take(50)
            showAll -> payments.sortedByDescending { it.at }.take(100)
            else -> payments
                .filter { !it.date.isBefore(credit.date.minusDays(60)) }
                // Payments at least as big as the money back first, then the closest in time.
                .sortedWith(compareBy<TxnItem> { if (it.amountMinor >= credit.amountMinor) 0 else 1 }.thenBy { kotlin.math.abs(it.date.toEpochDay() - credit.date.toEpochDay()) })
                .take(8)
        }
    }
    val linked = linkedId?.let { id -> payments.firstOrNull { it.id == id } }
    Column(Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Money back for something I paid", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Someone paid you back, or a refund. It reduces that category's spending instead of counting as income.",
                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
                )
            }
            androidx.compose.material3.Switch(
                checked = on, onCheckedChange = onToggle,
                colors = androidx.compose.material3.SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
            )
        }
        if (on) {
            Text("Which payment? (optional)", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search payments: name, category, note or amount") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            val q = query.trim()
            Text(
                when {
                    q.isNotEmpty() -> if (candidates.isEmpty()) "No payments match \"$q\"." else "${candidates.size} matching${if (candidates.size == 50) " (newest 50)" else ""}"
                    showAll -> "All payments, newest first"
                    candidates.isEmpty() -> "No payments in the 2 months before this. Search, or just pick the category below."
                    else -> "Suggested: similar amounts around that date"
                },
                style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 4.dp),
            )
            // The chosen payment stays visible even when it isn't in the current list.
            val shown = if (linked != null && candidates.none { it.id == linked.id }) listOf(linked) + candidates else candidates
            shown.forEach { d ->
                val selected = d.id == linkedId
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) BuddyColors.Lime.copy(alpha = 0.12f) else BuddyColors.Surface)
                        .clickable { onPick(if (selected) null else d) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.RadioButton(selected = selected, onClick = { onPick(if (selected) null else d) })
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        Text(
                            listOfNotNull(listOfNotNull(d.category.label, d.sub).joinToString(" › "), d.date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                        )
                    }
                    Text("−${inr(d.amountMinor)}", style = MaterialTheme.typography.labelLarge)
                }
            }
            if (q.isEmpty()) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show suggestions" else "Show all payments") }
            }
            linkedId?.let { id ->
                linked?.let { d ->
                    val net = d.amountMinor - credit.amountMinor - d.backMinor.let { if (credit.backForId == id) it - credit.amountMinor else it }
                    Text(
                        "${d.title}: ${inr(d.amountMinor)} paid, ${inr(d.amountMinor - net)} back → you spent ${inr(net.coerceAtLeast(0))}",
                        style = MaterialTheme.typography.labelMedium, color = BuddyColors.Lime, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

