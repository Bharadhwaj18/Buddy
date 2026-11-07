package com.shrey.buddy.ui.screens

import android.Manifest
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.finance.Category
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.IconBadge
import com.shrey.buddy.ui.components.ListRow
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.inr
import com.shrey.buddy.ui.money.AccountItem
import com.shrey.buddy.ui.money.AddTransactionSheet
import com.shrey.buddy.ui.life.LifeModule
import com.shrey.buddy.ui.life.LifeViewModel
import com.shrey.buddy.ui.components.StatTile
import androidx.compose.material.icons.rounded.Add
import com.shrey.buddy.ui.money.TxnItem
import com.shrey.buddy.ui.money.color
import com.shrey.buddy.ui.money.icon
import com.shrey.buddy.ui.rememberPermissionGate
import com.shrey.buddy.ui.theme.BuddyColors
import java.text.DateFormat
import java.util.Date

@Composable
fun MoneyScreen(vm: AppViewModel, lvm: LifeViewModel, onOpenAccounts: () -> Unit, onOpenModule: (LifeModule) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    val holdings by lvm.holdings.collectAsStateWithLifecycle()
    val wishes by lvm.wishes.collectAsStateWithLifecycle()
    val money by vm.money.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val scanStatus by vm.scanStatus.collectAsStateWithLifecycle()
    val unrecognised by vm.unrecognisedCount.collectAsStateWithLifecycle()
    val sms = rememberPermissionGate(
        arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS, Manifest.permission.POST_NOTIFICATIONS),
        required = 2,
    )
    var editing by remember { mutableStateOf<TxnItem?>(null) }
    // Opened from a "Buddy captured" notification: show that transaction once it has loaded.
    val openTxn by vm.openTxn.collectAsStateWithLifecycle()
    LaunchedEffect(openTxn, money.transactions) {
        val id = openTxn ?: return@LaunchedEffect
        money.transactions.firstOrNull { it.id == id }?.let { editing = it; vm.openTransaction(null) }
    }
    val byDay = remember(money.transactions) { money.transactions.groupBy { it.date }.entries.toList() }
    val selectedName = money.accounts.firstOrNull { it.id == money.selectedAccountId }?.name

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Money", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
                FilledTonalIconButton(onClick = { adding = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = "Add transaction")
                }
                FilledTonalIconButton(onClick = onOpenAccounts) {
                    Icon(Icons.Rounded.AccountBalance, contentDescription = "Accounts")
                }
                if (sms.granted) {
                    FilledTonalIconButton(onClick = { vm.scanSms() }, enabled = !scanning) {
                        if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, contentDescription = "Scan SMS")
                    }
                }
            }
            scanStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary) }
        }

        if (!sms.granted) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    IconBadge(Icons.Rounded.Sms, BuddyColors.Lime)
                    Text("Capture bank SMS automatically", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    Text(
                        "Buddy reads only IndusInd and SBI transaction messages. Raw SMS never leave your phone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = BuddyColors.TextSecondary,
                    )
                    Button(
                        onClick = sms.request,
                        modifier = Modifier.padding(top = 12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
                    ) { Text("Allow SMS access") }
                }
            }
        }

        if (money.accounts.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        AccountCard(
                            title = "All accounts",
                            detail = "${money.accounts.count { it.includeInTotals }} counted",
                            balance = null,
                            selected = money.selectedAccountId == null,
                            onClick = { vm.selectAccount(null) },
                        )
                    }
                    items(money.accounts, key = { it.id }) { a ->
                        AccountCard(
                            title = a.name,
                            detail = a.detail,
                            balance = a.balanceMinor,
                            selected = money.selectedAccountId == a.id,
                            onClick = { vm.selectAccount(if (money.selectedAccountId == a.id) null else a.id) },
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BuddyCard(Modifier.weight(1f)) {
                    Text("Spent", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                    Text(inr(money.spentThisMonth), style = MaterialTheme.typography.headlineSmall.numeric())
                    Text(selectedName ?: "this month", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, maxLines = 1)
                }
                BuddyCard(Modifier.weight(1f)) {
                    Text("Received", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                    Text(inr(money.receivedThisMonth), style = MaterialTheme.typography.headlineSmall.numeric(), color = BuddyColors.Lime)
                    Text("excl. transfers", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                }
            }
        }

        if (money.topCategories.isNotEmpty()) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Where it went this month", style = MaterialTheme.typography.titleMedium)
                    val max = money.topCategories.first().amountMinor.coerceAtLeast(1)
                    money.topCategories.take(6).forEach { ct ->
                        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(ct.category.icon, null, tint = ct.category.color, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(ct.category.label, style = MaterialTheme.typography.bodySmall)
                                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(BuddyColors.SurfaceHigh)) {
                                    Box(
                                        Modifier.fillMaxHeight()
                                            .fillMaxWidth(ct.amountMinor.toFloat() / max)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(ct.category.color),
                                    )
                                }
                            }
                            Text(inr(ct.amountMinor), style = MaterialTheme.typography.labelLarge.numeric())
                        }
                        // Your sub-groups inside this category.
                        ct.subs.take(4).forEach { (name, amount) ->
                            Row(Modifier.padding(start = 28.dp, top = 4.dp)) {
                                Text(name, style = MaterialTheme.typography.labelMedium, color = BuddyColors.TextSecondary, modifier = Modifier.weight(1f))
                                Text(inr(amount), style = MaterialTheme.typography.labelMedium.numeric(), color = BuddyColors.TextSecondary)
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val bank = money.accounts.filter { it.includeInTotals }.sumOf { it.balanceMinor ?: 0 }
                StatTile(
                    label = "Net worth",
                    value = inr(bank + holdings.sumOf { it.data.currentMinor }),
                    icon = LifeModule.NET_WORTH.icon,
                    accent = LifeModule.NET_WORTH.color,
                    caption = if (holdings.isEmpty()) "Add investments" else "${holdings.size} investments",
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenModule(LifeModule.NET_WORTH) },
                )
                val waiting = wishes.filter { it.data.status == "waiting" }
                StatTile(
                    label = "Wishlist",
                    value = inr(waiting.sumOf { it.data.priceMinor }),
                    icon = LifeModule.WISHLIST.icon,
                    accent = LifeModule.WISHLIST.color,
                    caption = "${waiting.size} waiting",
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenModule(LifeModule.WISHLIST) },
                )
            }
        }

        item {
            val budgets by lvm.budgets.collectAsStateWithLifecycle()
            val allTxns by lvm.transactions.collectAsStateWithLifecycle()
            val accts by lvm.accounts.collectAsStateWithLifecycle()
            val excluded = accts.filter { !it.includeInTotals }.map { it.id }.toSet()
            val status = com.shrey.buddy.finance.BudgetLogic.status(budgets.map { it.data }, allTxns, excluded)
            val today = java.time.LocalDate.now()
            if (status.isEmpty()) {
                BuddyCard(Modifier.fillMaxWidth(), onClick = { onOpenModule(LifeModule.BUDGETS) }, contentPadding = 16.dp) {
                    Text("Set a monthly budget", style = MaterialTheme.typography.titleSmall)
                    Text("Overall and per category, with alerts at 80% and 100%", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.shrey.buddy.ui.components.SectionHeader("Budgets", action = "Manage", onAction = { onOpenModule(LifeModule.BUDGETS) })
                    status.take(3).forEach { s ->
                        com.shrey.buddy.ui.life.BudgetBar(s.budget, s.spentMinor, today.lengthOfMonth() - today.dayOfMonth + 1) { onOpenModule(LifeModule.BUDGETS) }
                    }
                }
            }
        }

        item {
            val recurring by lvm.recurring.collectAsStateWithLifecycle()
            val allTxns by lvm.transactions.collectAsStateWithLifecycle()
            val today = java.time.LocalDate.now()
            val upcoming = recurring.filter { it.data.active }.flatMap { r ->
                com.shrey.buddy.finance.RecurringLogic.occurrences(r.data, allTxns, today, back = 7, ahead = 14)
                    .filter { it.status != com.shrey.buddy.finance.OccurrenceStatus.PAID }
                    .map { r.data to it }
            }.sortedBy { it.second.date }
            BuddyCard(Modifier.fillMaxWidth(), onClick = { onOpenModule(LifeModule.RECURRING) }, contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Recurring", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(
                        if (recurring.isEmpty()) "Add rent, SIPs, EMIs"
                        else "${inr(recurring.filter { it.data.active && it.data.direction == "debit" }.sumOf { com.shrey.buddy.finance.RecurringLogic.monthlyMinor(it.data) })}/month",
                        style = MaterialTheme.typography.labelLarge.numeric(), color = BuddyColors.TextSecondary,
                    )
                }
                upcoming.take(3).forEach { (r, o) ->
                    Row(Modifier.padding(top = 8.dp)) {
                        Text(r.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            "${inr(r.amountMinor)} · ${if (o.status == com.shrey.buddy.finance.OccurrenceStatus.OVERDUE) "missed " else ""}${friendlyDay(o.date)}",
                            style = MaterialTheme.typography.bodySmall.numeric(),
                            color = if (o.status == com.shrey.buddy.finance.OccurrenceStatus.OVERDUE) BuddyColors.Red else BuddyColors.TextSecondary,
                        )
                    }
                }
            }
        }

        if (unrecognised > 0) {
            item {
                BuddyCard(Modifier.fillMaxWidth(), color = BuddyColors.Red.copy(alpha = 0.12f), contentPadding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.WarningAmber, null, tint = BuddyColors.Red)
                        Text(
                            "$unrecognised bank message${if (unrecognised == 1) "" else "s"} need a parser update",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        }

        if (money.upcoming.isNotEmpty() && money.selectedAccountId == null) {
            item {
                Text("Upcoming debits", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 10.dp)) {
                    items(money.upcoming) { u ->
                        BuddyCard(Modifier.width(170.dp), contentPadding = 16.dp) {
                            Text(u.payee, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(inr(u.amountMinor), style = MaterialTheme.typography.titleLarge.numeric(), modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                listOfNotNull(u.dueDate?.let { friendlyDay(it) }, u.bank).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = BuddyColors.TextSecondary,
                            )
                        }
                    }
                }
            }
        }

        if (byDay.isEmpty()) {
            item { EmptyNote("No transactions yet. Allow SMS access and tap scan.") }
        }
        byDay.forEach { (day, txns) ->
            item(key = day.toString()) {
                Column {
                    Row(Modifier.padding(bottom = 8.dp, start = 4.dp, end = 4.dp)) {
                        Text(friendlyDay(day), style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary, modifier = Modifier.weight(1f))
                        val spent = txns.filter { !it.isCredit && !it.isTransfer }.sumOf { it.amountMinor }
                        if (spent > 0) Text("−${inr(spent)}", style = MaterialTheme.typography.labelLarge.numeric(), color = BuddyColors.TextTertiary)
                    }
                    BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                        txns.forEach { t ->
                            TxnRow(t, Modifier.padding(horizontal = 8.dp), onClick = { editing = t })
                        }
                    }
                }
            }
        }
    }
    ExtendedFloatingActionButton(
        onClick = { adding = true },
        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
        text = { Text("Add") },
        containerColor = BuddyColors.Lime,
        contentColor = BuddyColors.OnLime,
        modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
    )
    com.shrey.buddy.ui.components.UndoBar(vm, Modifier.align(Alignment.BottomCenter))
    }

    if (adding) AddTransactionSheet(vm, money.accounts, onDismiss = { adding = false })

    editing?.let { t ->
        TxnEditSheet(
            vm, t,
            onDismiss = { editing = null },
            onDelete = { vm.deleteTransaction(t.id, t.title); editing = null },
            onOpenVehicles = { editing = null; onOpenModule(LifeModule.VEHICLES) },
        ) { category, sub, transfer, note, fuel ->
            vm.updateTransaction(t, category, sub, transfer, note, fuel)
            editing = null
        }
    }
}

@Composable
private fun AccountCard(title: String, detail: String, balance: Long?, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = BuddyColors.Surface,
        border = if (selected) BorderStroke(1.5.dp, BuddyColors.Lime) else null,
        modifier = Modifier.width(160.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, maxLines = 1)
            Text(
                balance?.let { inr(it) } ?: if (selected) "Showing all" else "—",
                style = MaterialTheme.typography.titleMedium.numeric(),
                color = if (balance != null) BuddyColors.TextPrimary else BuddyColors.TextSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (balance != null) Text("last reported", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
        }
    }
}

private val timeFormat: DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)

@Composable
fun TxnRow(t: TxnItem, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val subtitle = listOfNotNull(
        if (t.isTransfer) "Transfer" else listOfNotNull(t.category.label, t.sub).joinToString(" › "),
        t.accountName,
        timeFormat.format(Date(t.at)),
    ).joinToString(" · ")
    ListRow(
        icon = t.category.icon,
        tint = t.category.color,
        title = t.note?.let { "${t.title} — $it" } ?: t.title,
        subtitle = subtitle,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Text(
            (if (t.isCredit) "+" else "−") + inr(t.amountMinor, paise = t.amountMinor % 100 != 0L),
            style = MaterialTheme.typography.titleSmall.numeric(),
            color = when {
                t.isTransfer -> BuddyColors.TextTertiary
                t.isCredit -> BuddyColors.Lime
                else -> BuddyColors.TextPrimary
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TxnEditSheet(
    vm: AppViewModel, t: TxnItem, onDismiss: () -> Unit, onDelete: () -> Unit, onOpenVehicles: () -> Unit,
    onSave: (Category, String?, Boolean, String?, com.shrey.buddy.finance.FuelEntry?) -> Unit,
) {
    var category by remember { mutableStateOf(t.category) }
    var sub by remember { mutableStateOf(t.sub) }
    val fuel = com.shrey.buddy.ui.money.rememberFuelForm(vm, t.id)
    var transfer by remember { mutableStateOf(t.isTransfer) }
    var note by remember { mutableStateOf(t.note.orEmpty()) }
    val choices = if (t.isCredit) Category.income + Category.spending else Category.spending + Category.income

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BuddyColors.Surface,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Scrolling the form must never drag the sheet closed; Back / tapping outside / Save still close it.
        sheetGesturesEnabled = false,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(t.title, style = MaterialTheme.typography.titleLarge)
            Text(
                "${if (t.isCredit) "+" else "−"}${inr(t.amountMinor, paise = true)} · ${t.accountName ?: ""}",
                style = MaterialTheme.typography.bodyMedium.numeric(),
                color = BuddyColors.TextSecondary,
            )

            Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Transfer between my accounts", style = MaterialTheme.typography.titleSmall)
                    Text("Excluded from spending and income", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
                }
                Switch(
                    checked = transfer,
                    onCheckedChange = { transfer = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
                )
            }

            if (!transfer) {
                Text("Category", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    choices.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c; sub = null },
                            label = { Text(c.label) },
                            leadingIcon = { Icon(c.icon, null, tint = c.color, modifier = Modifier.size(16.dp)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = c.color.copy(alpha = 0.18f),
                                selectedLabelColor = BuddyColors.TextPrimary,
                            ),
                        )
                    }
                }
                if (!category.isIncome) {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(14.dp))
                    com.shrey.buddy.ui.money.SubcategoryPicker(vm, category, sub) { sub = it }
                }
                if (category == Category.FUEL && !t.isCredit) {
                    androidx.compose.foundation.layout.Spacer(Modifier.height(16.dp))
                    com.shrey.buddy.ui.money.FuelFields(vm, fuel, t.amountMinor, onOpenVehicles)
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                placeholder = { Text("Note (optional)") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BuddyColors.SurfaceHigh,
                    unfocusedContainerColor = BuddyColors.SurfaceHigh,
                    focusedBorderColor = BuddyColors.Lime,
                    unfocusedBorderColor = BuddyColors.SurfaceHigh,
                ),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            Button(
                onClick = { onSave(category, sub, transfer, note, if (category == Category.FUEL) fuel.entry() else null) },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
            ) { Text("Save") }

            TextButton(
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, null, tint = BuddyColors.Red, modifier = Modifier.size(18.dp))
                Text("Delete transaction", color = BuddyColors.Red, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}
