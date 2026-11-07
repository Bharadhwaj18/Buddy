package com.shrey.buddy.ui.money

import androidx.compose.foundation.layout.Arrangement
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
