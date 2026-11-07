package com.shrey.buddy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.db.AccountEntity
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.IconBadge
import com.shrey.buddy.ui.components.ListRow
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.inr
import com.shrey.buddy.ui.money.AccountItem
import com.shrey.buddy.ui.money.kindLabel
import com.shrey.buddy.ui.theme.BuddyColors

private val kinds = listOf("savings", "current", "credit_card", "wallet", "cash", "loan", "other")

private fun kindIcon(kind: String): ImageVector = when (kind) {
    "credit_card" -> Icons.Rounded.CreditCard
    "wallet" -> Icons.Rounded.AccountBalanceWallet
    "cash" -> Icons.Rounded.Payments
    else -> Icons.Rounded.AccountBalance
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = BuddyColors.SurfaceHigh,
    unfocusedContainerColor = BuddyColors.SurfaceHigh,
    focusedBorderColor = BuddyColors.Lime,
    unfocusedBorderColor = BuddyColors.SurfaceHigh,
)

@Composable
fun AccountsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val money by vm.money.collectAsStateWithLifecycle()
    val own by vm.ownIdentifiers.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<AccountEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var ownText by remember(own) { mutableStateOf(own.joinToString(", ")) }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Accounts", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            }
        }

        item {
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                if (money.accounts.isEmpty()) EmptyNote("Accounts appear here automatically from your bank SMS.", Modifier.padding(8.dp))
                money.accounts.forEach { a -> AccountRow(a) { editing = a.entity } }
            }
        }

        item {
            OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null)
                Text("Add account (cash, wallet, other bank)", modifier = Modifier.padding(start = 8.dp))
            }
        }

        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                IconBadge(Icons.Rounded.Person, BuddyColors.Lime)
                Text("You", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                Text(
                    "Your UPI IDs and the way banks write your name. Money moving to or from these is treated as a transfer, " +
                        "not spending or income. Separate with commas.",
                    style = MaterialTheme.typography.bodySmall,
                    color = BuddyColors.TextSecondary,
                )
                OutlinedTextField(
                    value = ownText,
                    onValueChange = { ownText = it },
                    placeholder = { Text("e.g. yourname@okaxis, YOUR NAME") },
                    shape = RoundedCornerShape(16.dp),
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
                Button(
                    onClick = { vm.setOwnIdentifiers(ownText) },
                    modifier = Modifier.padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
                ) { Text("Save & re-check transfers") }
            }
        }
    }

    editing?.let { a ->
        AccountSheet(
            title = "Edit account",
            initial = a,
            onDismiss = { editing = null },
            onSave = {
                vm.saveAccount(it)
                editing = null
            },
        )
    }
    if (adding) {
        AccountSheet(
            title = "New account",
            initial = null,
            onDismiss = { adding = false },
            onSave = {
                vm.saveAccount(it.copy(id = java.util.UUID.randomUUID().toString()))
                adding = false
            },
        )
    }
}

@Composable
private fun AccountRow(a: AccountItem, onClick: () -> Unit) {
    ListRow(
        icon = kindIcon(a.kind),
        tint = if (a.includeInTotals) BuddyColors.Lime else BuddyColors.TextTertiary,
        title = a.name,
        subtitle = listOfNotNull(
            a.detail,
            if (a.isPrimary) "Primary" else null,
            if (!a.includeInTotals) "Not in totals" else null,
        ).joinToString(" · "),
        modifier = Modifier.padding(horizontal = 8.dp).then(Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (a.isPrimary) Icon(Icons.Rounded.Star, null, tint = BuddyColors.Lime, modifier = Modifier.padding(end = 6.dp))
            Text(a.balanceMinor?.let { inr(it) } ?: "—", style = MaterialTheme.typography.titleSmall.numeric())
            IconButton(onClick = onClick) { Text("Edit", style = MaterialTheme.typography.labelMedium, color = BuddyColors.Lime) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AccountSheet(title: String, initial: AccountEntity?, onDismiss: () -> Unit, onSave: (AccountEntity) -> Unit) {
    val now = System.currentTimeMillis()
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var kind by remember { mutableStateOf(initial?.kind ?: "cash") }
    var primary by remember { mutableStateOf(initial?.isPrimary ?: false) }
    var inTotals by remember { mutableStateOf(initial?.includeInTotals ?: true) }
    var archived by remember { mutableStateOf(initial?.archived ?: false) }
    var balance by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BuddyColors.Surface,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Scrolling the form must never drag the sheet closed; Back / tapping outside / Save still close it.
        sheetGesturesEnabled = false,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Name") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )
            OutlinedTextField(
                value = balance,
                onValueChange = { balance = it },
                label = { Text("Set current balance ₹ (optional)") },
                supportingText = { Text("Buddy adds every later transaction to it automatically") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = fieldColors(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Text("Type", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                kinds.forEach { k ->
                    FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(kindLabel(k)) })
                }
            }
            ToggleRow("Primary account", "Shown first everywhere", primary) { primary = it }
            ToggleRow("Count in totals", "Include in spent / received", inTotals) { inTotals = it }
            if (initial != null) ToggleRow("Archived", "Hide this account", archived) { archived = it }
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(
                        (initial ?: AccountEntity(
                            id = "", name = "", bank = null, kind = kind, last4 = null, isPrimary = false,
                            includeInTotals = true, archived = false, createdAt = now, updatedAt = now, deletedAt = null,
                        )).copy(name = name.trim(), kind = kind, isPrimary = primary, includeInTotals = inTotals, archived = archived).let { a ->
                            val typed = com.shrey.buddy.ui.components.parseRupeesToMinor(balance)
                            if (typed != null) a.copy(manualBalanceMinor = typed, manualBalanceAt = System.currentTimeMillis()) else a
                        },
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Lime, contentColor = BuddyColors.OnLime),
            ) { Text("Save") }
        }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
        )
    }
}
