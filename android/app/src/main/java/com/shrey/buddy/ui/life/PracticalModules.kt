package com.shrey.buddy.ui.life

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.items.Document
import com.shrey.buddy.data.items.Holding
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Vehicle
import com.shrey.buddy.data.items.VehicleLog
import com.shrey.buddy.data.items.Wish
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.Choices
import com.shrey.buddy.ui.components.DateField
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.components.FormSheet
import com.shrey.buddy.ui.components.ListRow
import com.shrey.buddy.ui.components.Pill
import com.shrey.buddy.ui.components.SectionHeader
import com.shrey.buddy.ui.components.minorToRupeesText
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.components.parseRupeesToMinor
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.inr
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private fun expiryColor(d: LocalDate?, today: LocalDate = LocalDate.now()) = when {
    d == null -> BuddyColors.TextTertiary
    d.isBefore(today) -> BuddyColors.Red
    ChronoUnit.DAYS.between(today, d) <= 30 -> BuddyColors.Fitness
    else -> BuddyColors.TextSecondary
}

// ---------------------------------------------------------------- Vehicles

private val logTypes = listOf("fuel" to "Fuel", "service" to "Service", "repair" to "Repair", "other" to "Other")

@Composable
fun VehiclesScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val vehicles by lvm.vehicles.collectAsStateWithLifecycle()
    val logs by lvm.vehicleLogs.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Item<Vehicle>?>(null) }
    var adding by remember { mutableStateOf(false) }
    var logFor by remember { mutableStateOf<Item<Vehicle>?>(null) }
    var editLog by remember { mutableStateOf<Item<VehicleLog>?>(null) }
    val color = LifeModule.VEHICLES.color

    ModuleScaffold(LifeModule.VEHICLES, lvm, onBack, onAdd = { adding = true }) {
        if (vehicles.isEmpty()) item { EmptyNote("Add your bike or car to track fuel, service and renewals.") }
        vehicles.forEach { v ->
            val vLogs = logs.filter { it.refId == v.id }.sortedByDescending { it.date }
            // Fuel efficiency from consecutive full-tank fuel logs: distance / litres of the later fill.
            val fuel = vLogs.filter { it.data.type == "fuel" && it.data.odometerKm > 0 && it.data.litres > 0 }.sortedBy { it.data.odometerKm }
            val kmpl = fuel.zipWithNext { a, b -> (b.data.odometerKm - a.data.odometerKm) / b.data.litres }.lastOrNull()
            item(key = v.id) {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.data.name, style = MaterialTheme.typography.titleLarge)
                            Text(
                                listOfNotNull(v.data.regNo.ifBlank { null }, if (v.data.odometerKm > 0) "%,d km".format(v.data.odometerKm) else null,
                                    kmpl?.let { "%.1f km/l".format(it) }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary,
                            )
                        }
                        TextButton(onClick = { editing = v }) { Text("Edit", color = color) }
                    }
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Insurance" to v.data.insuranceExpiry, "PUC" to v.data.pucExpiry, "Service" to v.data.serviceDue).forEach { (label, d) ->
                            val date = parseDate(d)
                            if (date != null) Pill("$label ${friendlyDay(date)}", expiryColor(date))
                        }
                    }
                    val spent = vLogs.filter { it.date?.year == LocalDate.now().year }.sumOf { it.data.amountMinor }
                    if (spent > 0) Text("Spent this year: ${inr(spent)}", style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 10.dp))
                    vLogs.take(5).forEach { l ->
                        ListRow(
                            icon = if (l.data.type == "fuel") Icons.Rounded.LocalGasStation else Icons.Rounded.Build,
                            tint = color,
                            title = "${logTypes.first { it.first == l.data.type }.second} · ${inr(l.data.amountMinor)}",
                            subtitle = listOfNotNull(l.date?.let { friendlyDay(it) }, if (l.data.litres > 0) "${l.data.litres} L" else null,
                                if (l.data.odometerKm > 0) "%,d km".format(l.data.odometerKm) else null, l.data.note.ifBlank { null }).joinToString(" · "),
                            modifier = Modifier,
                        ) { TextButton(onClick = { editLog = l; logFor = v }) { Text("Edit", color = BuddyColors.TextTertiary) } }
                    }
                    OutlinedButton(onClick = { editLog = null; logFor = v }, modifier = Modifier.padding(top = 8.dp)) { Text("Add fuel / service") }
                }
            }
        }
    }
    if (adding || editing != null) {
        val v = editing
        val d = v?.data ?: Vehicle()
        var name by remember { mutableStateOf(d.name) }
        var reg by remember { mutableStateOf(d.regNo) }
        var odo by remember { mutableStateOf(if (d.odometerKm > 0) d.odometerKm.toString() else "") }
        var ins by remember { mutableStateOf(parseDate(d.insuranceExpiry)) }
        var puc by remember { mutableStateOf(parseDate(d.pucExpiry)) }
        var svc by remember { mutableStateOf(parseDate(d.serviceDue)) }
        FormSheet(
            title = if (v == null) "Add vehicle" else "Edit vehicle",
            onDismiss = { adding = false; editing = null },
            accent = color,
            saveEnabled = name.isNotBlank(),
            onDelete = v?.let { { lvm.delete(it.id); editing = null } },
            onSave = {
                lvm.save(Kinds.VEHICLE, Vehicle(name.trim(), reg.trim().uppercase(), ins?.toString(), puc?.toString(), svc?.toString(), odo.toIntOrNull() ?: 0), Vehicle.serializer(), id = v?.id)
                adding = false; editing = null
            },
        ) {
            Field(name, { name = it }, "Name (e.g. Activa, City)", accent = color)
            Field(reg, { reg = it }, "Registration number", accent = color)
            Field(odo, { odo = it.filter(Char::isDigit) }, "Odometer km", keyboard = KeyboardType.Number, accent = color)
            DateField("Insurance expiry", ins, { ins = it })
            DateField("PUC expiry", puc, { puc = it })
            DateField("Next service", svc, { svc = it })
        }
    }
    logFor?.let { v ->
        val l = editLog
        val d = l?.data ?: VehicleLog()
        var type by remember { mutableStateOf(d.type) }
        var amount by remember { mutableStateOf(if (d.amountMinor > 0) minorToRupeesText(d.amountMinor) else "") }
        var litres by remember { mutableStateOf(if (d.litres > 0) d.litres.toString() else "") }
        var odo by remember { mutableStateOf(if (d.odometerKm > 0) d.odometerKm.toString() else "") }
        var note by remember { mutableStateOf(d.note) }
        var day by remember { mutableStateOf(l?.date ?: LocalDate.now()) }
        var toMoney by remember { mutableStateOf(true) }
        FormSheet(
            title = "${v.data.name}: ${if (l == null) "add" else "edit"} entry",
            onDismiss = { logFor = null; editLog = null },
            accent = color,
            onDelete = l?.let { { lvm.delete(it.id); logFor = null; editLog = null } },
            onSave = {
                val km = odo.toIntOrNull() ?: 0
                val minor = parseRupeesToMinor(amount) ?: 0
                if (l == null && type == "fuel" && toMoney && minor > 0) {
                    // One entry, both places: a Fuel transaction in Money linked to this fill-up.
                    lvm.addFuelWithTransaction(v.id, v.data.name, minor, litres.toDoubleOrNull() ?: 0.0, km, day)
                } else
                lvm.save(Kinds.VEHICLE_LOG, VehicleLog(type, minor, km, litres.toDoubleOrNull() ?: 0.0, note.trim(), txnId = l?.data?.txnId), VehicleLog.serializer(), id = l?.id, date = day, refId = v.id)
                if (km > v.data.odometerKm) lvm.save(Kinds.VEHICLE, v.data.copy(odometerKm = km), Vehicle.serializer(), id = v.id)
                logFor = null; editLog = null
            },
        ) {
            Choices(logTypes, type, { type = it }, color)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(amount, { amount = it }, "Amount ₹", Modifier.weight(1f), keyboard = KeyboardType.Decimal, accent = color)
                if (type == "fuel") Field(litres, { litres = it }, "Litres", Modifier.weight(1f), keyboard = KeyboardType.Decimal, accent = color)
            }
            Field(odo, { odo = it.filter(Char::isDigit) }, "Odometer km", keyboard = KeyboardType.Number, accent = color)
            Field(note, { note = it }, "Note", accent = color)
            DateField("Date", day, { day = it ?: LocalDate.now() }, allowClear = false)
            if (l == null && type == "fuel") com.shrey.buddy.ui.components.ToggleField("Also add to Money", toMoney, { toMoney = it }, subtitle = "As a Fuel transaction on your primary account")
        }
    }
}

// ---------------------------------------------------------------- Wishlist

@Composable
fun WishlistScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val wishes by lvm.wishes.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Item<Wish>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val color = LifeModule.WISHLIST.color
    val today = LocalDate.now()

    ModuleScaffold(LifeModule.WISHLIST, lvm, onBack, onAdd = { adding = true }) {
        item {
            Text(
                "Add things you want instead of buying on impulse. Buddy asks again after a cooling-off period " +
                    "(7 days, or 30 for anything over ₹5,000).",
                style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
            )
        }
        val waiting = wishes.filter { it.data.status == "waiting" }
        if (waiting.isNotEmpty()) item { SectionHeader("Thinking about · ${inr(waiting.sumOf { it.data.priceMinor })}") }
        items(waiting, key = { it.id }) { w ->
            val until = parseDate(w.data.coolOffUntil)
            val ready = until == null || !until.isAfter(today)
            BuddyCard(Modifier.fillMaxWidth(), onClick = { editing = w }, contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(w.data.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (ready) "Cooling-off over — still want it?" else "Think until ${friendlyDay(until!!)} (${ChronoUnit.DAYS.between(today, until)} days)",
                            style = MaterialTheme.typography.bodySmall, color = if (ready) color else BuddyColors.TextSecondary,
                        )
                    }
                    Text(inr(w.data.priceMinor), style = MaterialTheme.typography.titleMedium.numeric())
                }
                if (ready) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                        Button(
                            onClick = { lvm.save(Kinds.WISH, w.data.copy(status = "bought"), Wish.serializer(), id = w.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = BuddyColors.Background),
                        ) { Text("Bought it") }
                        OutlinedButton(onClick = { lvm.save(Kinds.WISH, w.data.copy(status = "dropped"), Wish.serializer(), id = w.id) }) { Text("Don't need it") }
                    }
                }
                if (w.data.url.isNotBlank()) {
                    TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(w.data.url))) } }) { Text("Open link", color = color) }
                }
            }
        }
        val dropped = wishes.filter { it.data.status == "dropped" }
        if (dropped.isNotEmpty()) item {
            BuddyCard(Modifier.fillMaxWidth(), color = BuddyColors.LimeContainer) {
                Text("Saved by not buying", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                Text(inr(dropped.sumOf { it.data.priceMinor }), style = MaterialTheme.typography.headlineMedium.numeric(), color = BuddyColors.Lime)
                Text("${dropped.size} things you decided you didn't need", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary)
            }
        }
        val bought = wishes.filter { it.data.status == "bought" }
        if (bought.isNotEmpty()) {
            item { SectionHeader("Bought") }
            items(bought, key = { it.id }) { w ->
                ListRow(Icons.Rounded.Verified, color, w.data.title, inr(w.data.priceMinor), Modifier)
            }
        }
    }
    if (adding || editing != null) {
        val w = editing
        val d = w?.data ?: Wish()
        var title by remember { mutableStateOf(d.title) }
        var price by remember { mutableStateOf(if (d.priceMinor > 0) minorToRupeesText(d.priceMinor) else "") }
        var url by remember { mutableStateOf(d.url) }
        FormSheet(
            title = if (w == null) "Add to wishlist" else "Edit",
            onDismiss = { adding = false; editing = null },
            accent = color,
            saveEnabled = title.isNotBlank(),
            onDelete = w?.let { { lvm.delete(it.id); editing = null } },
            onSave = {
                val minor = parseRupeesToMinor(price) ?: 0
                val coolOff = d.coolOffUntil ?: today.plusDays(if (minor >= 5_000_00) 30 else 7).toString()
                lvm.save(Kinds.WISH, d.copy(title = title.trim(), priceMinor = minor, url = url.trim(), coolOffUntil = coolOff), Wish.serializer(), id = w?.id)
                adding = false; editing = null
            },
        ) {
            Field(title, { title = it }, "What do you want?", accent = color)
            Field(price, { price = it }, "Price ₹", keyboard = KeyboardType.Decimal, accent = color)
            Field(url, { url = it }, "Link (optional)", keyboard = KeyboardType.Uri, accent = color)
        }
    }
}

// ---------------------------------------------------------------- Documents (vault)

private val docCategories = listOf("id" to "ID", "insurance" to "Insurance", "warranty" to "Warranty", "bill" to "Bill", "medical" to "Medical", "other" to "Other")

private fun docIcon(category: String) = when (category) {
    "id" -> Icons.Rounded.Badge
    "insurance", "medical" -> Icons.Rounded.HealthAndSafety
    "bill" -> Icons.Rounded.Receipt
    else -> Icons.Rounded.Description
}

@Composable
fun VaultScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val docs by lvm.documents.collectAsStateWithLifecycle()
    var picked by remember { mutableStateOf<Uri?>(null) }
    var editing by remember { mutableStateOf<Item<Document>?>(null) }
    val color = LifeModule.VAULT.color
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> picked = uri }

    ModuleScaffold(LifeModule.VAULT, lvm, onBack, onAdd = { picker.launch(arrayOf("*/*")) }) {
        item {
            Text(
                "Stored privately in your Supabase account (only you can read them). Never sent to Gemini.",
                style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
            )
        }
        if (docs.isEmpty()) item { EmptyNote("Tap ＋ to add a PDF or photo: Aadhaar, PAN, policies, warranties, bills.") }
        docCategories.forEach { (cat, label) ->
            val group = docs.filter { it.data.category == cat }
            if (group.isNotEmpty()) {
                item { SectionHeader(label) }
                items(group, key = { it.id }) { d ->
                    val exp = parseDate(d.data.expiry)
                    BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp, onClick = { lvm.open(context, d.data) }) {
                        ListRow(
                            icon = docIcon(cat), tint = color, title = d.data.title,
                            subtitle = listOfNotNull(exp?.let { "Expires ${friendlyDay(it)}" }, "%.1f MB".format(d.data.sizeBytes / 1_048_576.0)).joinToString(" · "),
                        ) {
                            TextButton(onClick = { editing = d }) { Text("Edit", color = expiryColor(exp)) }
                        }
                    }
                }
            }
        }
    }
    picked?.let { uri ->
        var title by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("other") }
        var expiry by remember { mutableStateOf<LocalDate?>(null) }
        var notes by remember { mutableStateOf("") }
        FormSheet(
            title = "Add document",
            onDismiss = { picked = null },
            accent = color,
            saveLabel = "Upload",
            onSave = { lvm.upload(context, uri, title.trim(), category, expiry, notes.trim()); picked = null },
        ) {
            Field(title, { title = it }, "Title (defaults to the file name)", accent = color)
            Choices(docCategories, category, { category = it }, color)
            DateField("Expiry", expiry, { expiry = it })
            Field(notes, { notes = it }, "Notes (policy no. etc.)", accent = color)
        }
    }
    editing?.let { d ->
        var title by remember { mutableStateOf(d.data.title) }
        var category by remember { mutableStateOf(d.data.category) }
        var expiry by remember { mutableStateOf(parseDate(d.data.expiry)) }
        var notes by remember { mutableStateOf(d.data.notes) }
        FormSheet(
            title = "Edit document",
            onDismiss = { editing = null },
            accent = color,
            onDelete = { lvm.deleteDocument(d); editing = null },
            onSave = {
                lvm.save(Kinds.DOCUMENT, d.data.copy(title = title.trim(), category = category, expiry = expiry?.toString(), notes = notes.trim()), Document.serializer(), id = d.id)
                editing = null
            },
        ) {
            Field(title, { title = it }, "Title", accent = color)
            Choices(docCategories, category, { category = it }, color)
            DateField("Expiry", expiry, { expiry = it })
            Field(notes, { notes = it }, "Notes", accent = color)
        }
    }
}

// ---------------------------------------------------------------- Insights

@Composable
fun InsightsScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val insights by lvm.insights.collectAsStateWithLifecycle()
    val busy by lvm.busy.collectAsStateWithLifecycle()
    val color = LifeModule.INSIGHTS.color

    ModuleScaffold(LifeModule.INSIGHTS, lvm, onBack) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("daily" to "Briefing", "weekly" to "Weekly review", "insights" to "Find patterns").forEach { (kind, label) ->
                    OutlinedButton(onClick = { lvm.generate(kind) }, enabled = busy == null, modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
        item {
            Text(
                "A briefing arrives every morning and a weekly review on Sunday evening. Gemini only sees totals and " +
                    "categories — never account numbers, names, journal text or documents.",
                style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
            )
        }
        if (insights.isEmpty()) item { EmptyNote("Nothing yet — tap a button above.") }
        items(insights.sortedByDescending { it.entity.updatedAt }, key = { it.id }) { i ->
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 18.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(i.data.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Pill(when (i.data.period) { "daily" -> "Daily"; "weekly" -> "Weekly"; else -> "Insight" }, color)
                }
                i.date?.let { Text(friendlyDay(it), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary) }
                Text(i.data.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                TextButton(onClick = { lvm.delete(i.id) }) { Text("Dismiss", color = BuddyColors.TextTertiary) }
            }
        }
    }
}
