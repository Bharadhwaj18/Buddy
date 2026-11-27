package com.shrey.buddy.ui.life

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalShipping
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.db.TransactionEntity
import com.shrey.buddy.data.items.Iou
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Parcel
import com.shrey.buddy.finance.BillRadar
import com.shrey.buddy.finance.Splits
import com.shrey.buddy.finance.prettyParty
import com.shrey.buddy.helpers.OtpRelay
import com.shrey.buddy.helpers.ParcelTracker
import com.shrey.buddy.ingest.sms.ParcelStatus
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.ListRow
import com.shrey.buddy.ui.components.Pill
import com.shrey.buddy.ui.components.SectionHeader
import com.shrey.buddy.ui.inr
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong

private val Indigo = Color(0xFF7C9CFF)
private val Orange = Color(0xFFFFB066)
private val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

private fun daysAgo(ms: Long): String = when (val d = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - ms)) {
    0L -> "today"; 1L -> "yesterday"; else -> "$d days ago"
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Send a reminder").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------------------------------------------------------- Splits

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SplitsScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val ious by lvm.ious.collectAsStateWithLifecycle()
    val txns by lvm.transactions.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    var quick by rememberSaveable { mutableStateOf("") }
    var showSettled by rememberSaveable { mutableStateOf(false) }
    val (owedToMe, iOwe) = Splits.totals(ious.map { it.data })
    val open = ious.filter { it.data.settledAt == null }
    val settled = ious.filter { it.data.settledAt != null }.sortedByDescending { it.data.settledAt }

    if (adding) SplitDialog(lvm, txns) { adding = false }

    fun addQuick() {
        val d = Splits.parse(quick)
        if (d == null || d.people.isEmpty()) lvm.say("Try: \"split 1200 with Ravi and Amit for dinner\", \"Neha owes me 350\" or \"I owe Amit 250\".")
        else { lvm.addSplit(d, null); quick = "" }
    }

    ModuleScaffold(LifeModule.SPLITS, lvm, onBack, onAdd = { adding = true }) {
        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("Owed to you", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                        Text(inr(owedToMe), style = MaterialTheme.typography.headlineMedium, color = BuddyColors.Lime)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("You owe", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                        Text(inr(iOwe), style = MaterialTheme.typography.headlineMedium)
                    }
                }
                Text(
                    "When a friend pays you back by UPI, Buddy spots it in the bank SMS and ticks it off, and counts only your share as spent.",
                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item {
            OutlinedTextField(
                quick, { quick = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("split 1200 with Ravi and Amit for dinner") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { addQuick() }),
                trailingIcon = { IconButton(onClick = { addQuick() }, enabled = quick.isNotBlank()) { Icon(Icons.AutoMirrored.Rounded.Send, "Add") } },
            )
        }
        if (open.isEmpty()) item { EmptyNote("Nobody owes anybody. Type a split above, tap ＋, or say \"Buddy listen, split 900 with Ravi\".") }
        val byPerson = open.groupBy { it.data.person to it.data.theyOwe }.entries.sortedByDescending { e -> e.value.sumOf { it.data.amountMinor } }
        items(byPerson.map { it.key to it.value }, key = { "${it.first.first}:${it.first.second}" }) { (k, list) ->
            val (person, theyOwe) = k
            val total = list.sumOf { it.data.amountMinor }
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(person, style = MaterialTheme.typography.titleMedium)
                        Text(if (theyOwe) "owes you" else "you owe", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary)
                    }
                    Text(inr(total), style = MaterialTheme.typography.titleLarge, color = if (theyOwe) BuddyColors.Lime else BuddyColors.TextPrimary)
                }
                list.sortedBy { it.data.createdAt }.forEach { i ->
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${i.data.note.ifBlank { "—" }} · ${daysAgo(i.data.createdAt)}",
                            style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.weight(1f),
                        )
                        Text(inr(i.data.amountMinor), style = MaterialTheme.typography.bodyMedium)
                        IconButton(onClick = { lvm.settleIou(i) }) { Icon(Icons.Rounded.Check, contentDescription = "Paid", tint = BuddyColors.TextSecondary) }
                    }
                }
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (theyOwe) {
                        OutlinedButton(onClick = { share(context, Splits.askText(person, total, list.map { it.data.note })) }) { Text("Ask") }
                    }
                    Button(
                        onClick = { list.forEach(lvm::settleIou) },
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo, contentColor = Color.Black),
                    ) { Text(if (theyOwe) "All paid" else "I paid") }
                }
            }
        }
        if (settled.isNotEmpty()) {
            item { SectionHeader("Settled", action = if (showSettled) "Hide" else "Show ${settled.size}", onAction = { showSettled = !showSettled }) }
            if (showSettled) {
                items(settled.take(20), key = { it.id }) { i ->
                    BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${i.data.person} · ${inr(i.data.amountMinor)}", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    listOfNotNull(i.data.note.ifBlank { null }, "paid ${daysAgo(i.data.settledAt ?: 0)}", if (i.data.settledTxnId != null) "spotted in SMS" else null).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
                                )
                            }
                            TextButton(onClick = { lvm.unsettleIou(i) }) { Text("Undo") }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitDialog(lvm: LifeViewModel, txns: List<TransactionEntity>, onDone: () -> Unit) {
    var mode by remember { mutableStateOf(0) } // 0 = I paid, split; 1 = they owe me; 2 = I owe them
    var amount by remember { mutableStateOf("") }
    var people by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var txnId by remember { mutableStateOf<String?>(null) }
    val recent = remember(txns) {
        val since = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7)
        txns.filter { it.direction == "debit" && it.deletedAt == null && !it.isTransfer && it.occurredAt >= since }
            .sortedByDescending { it.occurredAt }.take(6)
    }
    val names = Splits.people(people)
    val total = amount.replace(",", "").toDoubleOrNull()?.let { (it * 100).roundToLong() } ?: 0L
    val each = if (mode == 0) Splits.share(total, names.size) else if (names.isEmpty()) 0 else total / names.size

    AlertDialog(
        onDismissRequest = onDone,
        containerColor = BuddyColors.Surface,
        title = { Text("Add a split") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("I paid, split it", "They owe me", "I owe them").forEachIndexed { i, l ->
                        FilterChip(mode == i, { mode = i }, label = { Text(l) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Indigo, selectedLabelColor = Color.Black))
                    }
                }
                if (mode == 0 && recent.isNotEmpty()) {
                    Text("From a recent payment", style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        recent.forEach { t ->
                            FilterChip(
                                txnId == t.id,
                                {
                                    txnId = if (txnId == t.id) null else t.id
                                    if (txnId != null) {
                                        amount = (t.amountMinor / 100.0).let { if (it % 1.0 == 0.0) it.toLong().toString() else "%.2f".format(it) }
                                        if (note.isBlank()) note = prettyParty(t.payee ?: t.counterpartyRaw, t.channel)
                                    }
                                },
                                label = { Text("${prettyParty(t.payee ?: t.counterpartyRaw, t.channel).take(14)} ${inr(t.amountMinor)}", maxLines = 1) },
                            )
                        }
                    }
                }
                OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text(if (mode == 0) "Total paid (₹)" else "Amount (₹)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(people, { people = it }, label = { Text(if (mode == 2) "Who you owe" else "Who (Ravi, Amit)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("For (dinner, cab…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (names.isNotEmpty() && total > 0) {
                    Text(
                        when (mode) {
                            0 -> "${names.size + 1} ways: ${names.joinToString()} each owe you ${inr(each)}"
                            1 -> names.joinToString { "$it owes you ${inr(each)}" }
                            else -> names.joinToString { "You owe $it ${inr(each)}" }
                        },
                        style = MaterialTheme.typography.bodySmall, color = Indigo,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = names.isNotEmpty() && total > 0,
                onClick = {
                    lvm.addSplit(Splits.Draft(total, names, note, theyOwe = mode != 2, split = mode == 0), txnId.takeIf { mode == 0 })
                    onDone()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Parcels

private fun etaText(p: Parcel): String? {
    val s = ParcelStatus.valueOf(p.status)
    if (s == ParcelStatus.DELIVERED) return "Delivered ${daysAgo(p.updatedAt)}"
    if (s == ParcelStatus.CANCELLED) return "Cancelled"
    val eta = p.eta?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val today = LocalDate.now()
    return when {
        eta == today -> "Arriving today"
        eta == today.plusDays(1) -> "Arriving tomorrow"
        eta.isBefore(today) -> "Was due ${dayFmt.format(eta)}"
        else -> "Arriving ${dayFmt.format(eta)}"
    }
}

private fun copy(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
fun ParcelCard(lvm: LifeViewModel, item: Item<Parcel>, compact: Boolean = false, onClick: (() -> Unit)? = null) {
    val p = item.data
    val context = LocalContext.current
    val status = ParcelStatus.valueOf(p.status)
    val tint = when (status) {
        ParcelStatus.OUT_FOR_DELIVERY -> Orange
        ParcelStatus.DELIVERED -> BuddyColors.Lime
        ParcelStatus.CANCELLED -> BuddyColors.TextTertiary
        else -> Indigo
    }
    BuddyCard(Modifier.fillMaxWidth(), onClick = onClick, contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LocalShipping, null, tint = tint)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(p.title ?: p.merchant, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    listOfNotNull(if (p.title != null) p.merchant else null, etaText(p)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, maxLines = 1,
                )
            }
            Pill(status.label, tint)
        }
        p.deliveryCode?.takeIf { status != ParcelStatus.DELIVERED && status != ParcelStatus.CANCELLED }?.let { code ->
            Row(
                Modifier.padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Orange.copy(alpha = 0.12f))
                    .clickable { copy(context, "Delivery code", code); lvm.say("Copied $code") }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Delivery code ", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary)
                Text(code, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Orange, letterSpacing = 3.sp)
            }
        }
        if (!compact) {
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (status != ParcelStatus.DELIVERED && status != ParcelStatus.CANCELLED) {
                    TextButton(onClick = { lvm.markParcelDelivered(item) }) { Text("Got it") }
                }
                TextButton(onClick = { lvm.setParcelHidden(item, !p.hidden) }) { Text(if (p.hidden) "Show" else "Hide") }
                p.orderId?.let { id -> TextButton(onClick = { copy(context, "Order id", id); lvm.say("Copied order id") }) { Text("Copy order id") } }
            }
        }
    }
}

@Composable
fun ParcelsScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val parcels by lvm.parcels.collectAsStateWithLifecycle()
    var showOld by rememberSaveable { mutableStateOf(false) }
    val active = ParcelTracker.active(parcels)
    val old = parcels.filter { it !in active }.sortedByDescending { it.data.updatedAt }

    ModuleScaffold(LifeModule.PARCELS, lvm, onBack) {
        if (active.isEmpty()) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Nothing on the way", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Buddy reads order and delivery SMS (Amazon, Flipkart, Myntra, Swiggy, Blinkit, Delhivery, Blue Dart and more) and shows what's coming, when, and the code to give the delivery person.",
                        style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        items(active, key = { it.id }) { ParcelCard(lvm, it) }
        if (old.isNotEmpty()) {
            item { SectionHeader("Earlier", action = if (showOld) "Hide" else "Show ${old.size}", onAction = { showOld = !showOld }) }
            if (showOld) items(old.take(30), key = { it.id }) { ParcelCard(lvm, it) }
        }
    }
}

// ---------------------------------------------------------------- Bill radar (in Recurring)

fun LazyListScope.radarItems(lvm: LifeViewModel, found: List<BillRadar.Found>, stopped: List<Pair<String, com.shrey.buddy.data.items.Recurring>>) {
    if (found.isEmpty() && stopped.isEmpty()) return
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Radar, null, tint = BuddyColors.Lime, modifier = Modifier.size(18.dp))
            Text("Bill radar", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        }
    }
    items(found, key = { "radar:${it.key}" }) { f ->
        BuddyCard(Modifier.fillMaxWidth(), color = BuddyColors.LimeContainer.copy(alpha = 0.5f), contentPadding = 16.dp) {
            if (f.kind == BillRadar.Kind.PRICE_CHANGE) {
                Text("${f.name} went up", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${inr(f.oldAmountMinor ?: 0)} → ${inr(f.amountMinor)} since ${dayFmt.format(f.lastDate)}",
                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary,
                )
            } else {
                Text("${f.name} · ${inr(f.amountMinor)} ${f.frequency}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Paid ${f.count} times, last on ${dayFmt.format(f.lastDate)}. Track it?",
                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary,
                )
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { lvm.acceptBill(f) },
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
                ) { Text(if (f.kind == BillRadar.Kind.PRICE_CHANGE) "Update to ${inr(f.amountMinor)}" else "Track it") }
                TextButton(onClick = { lvm.dismissBill(f.key) }) { Text(if (f.kind == BillRadar.Kind.PRICE_CHANGE) "Ignore" else "Not a bill") }
            }
        }
    }
    items(stopped, key = { "stopped:${it.first}" }) { (id, r) ->
        BuddyCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
            Text("${r.name}: no payment for 2 ${if (r.frequency == "weekly") "weeks" else "times"}", style = MaterialTheme.typography.titleSmall)
            Text("Cancelled it? Pause it so it stops showing as due.", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { lvm.pauseBill(id, r) }) { Text("Pause it") }
                TextButton(onClick = { lvm.dismissBill("stopped:$id") }) { Text("Still paying") }
            }
        }
    }
}

// ---------------------------------------------------------------- Today + settings

/** "📦 2 on the way" and "₹1,050 owed to you" on Today; nothing when there's nothing. */
@Composable
fun TodayHelpers(lvm: LifeViewModel, onOpenModule: (LifeModule) -> Unit) {
    val parcels by lvm.parcels.collectAsStateWithLifecycle()
    val ious by lvm.ious.collectAsStateWithLifecycle()
    val coming = ParcelTracker.active(parcels).filter { it.data.status != ParcelStatus.DELIVERED.name && it.data.status != ParcelStatus.CANCELLED.name }
    val owed = Splits.totals(ious.map { it.data }).first
    if (coming.isEmpty() && owed == 0L) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        coming.take(2).forEach { ParcelCard(lvm, it, compact = true) { onOpenModule(LifeModule.PARCELS) } }
        if (coming.size > 2) {
            Text("+${coming.size - 2} more on the way", style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextTertiary,
                modifier = Modifier.clickable { onOpenModule(LifeModule.PARCELS) })
        }
        if (owed > 0) {
            val people = ious.filter { it.data.theyOwe && it.data.settledAt == null }.map { it.data.person }.distinct()
            BuddyCard(Modifier.fillMaxWidth(), onClick = { onOpenModule(LifeModule.SPLITS) }, contentPadding = 12.dp) {
                ListRow(
                    LifeModule.SPLITS.icon, Indigo, "${inr(owed)} owed to you",
                    people.take(3).joinToString() + if (people.size > 3) " +${people.size - 3}" else "",
                    Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
fun OtpSettingCard() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(OtpRelay.enabled(context)) }
    BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                ListRow(Icons.Rounded.Sms, BuddyColors.Tasks, "OTPs on the laptop", null)
                Text(
                    "While the Buddy laptop app is on, login and payment codes pop up there to copy. They're deleted within minutes.",
                    style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = on,
                onCheckedChange = { on = it; OtpRelay.setEnabled(context, it) },
                colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.Background, checkedTrackColor = BuddyColors.Tasks, uncheckedTrackColor = BuddyColors.SurfaceHigh),
            )
        }
    }
}
