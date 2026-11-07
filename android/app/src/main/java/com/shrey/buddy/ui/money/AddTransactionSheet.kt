package com.shrey.buddy.ui.money

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.finance.Category
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.ImageInput
import com.shrey.buddy.ui.components.Choices
import com.shrey.buddy.ui.components.DateField
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.components.FormSheet
import com.shrey.buddy.ui.components.minorToRupeesText
import com.shrey.buddy.ui.components.parseRupeesToMinor
import com.shrey.buddy.ui.theme.BuddyColors
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Manual entry (cash, other banks) with optional receipt scan that pre-fills everything. */
@Composable
fun AddTransactionSheet(vm: AppViewModel, accounts: List<AccountItem>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scanning by vm.receiptState.collectAsStateWithLifecycle()
    var credit by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var payee by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(Category.FOOD) }
    var sub by remember { mutableStateOf<String?>(null) }
    val fuel = rememberFuelForm(vm, null)
    var accountId by remember { mutableStateOf((accounts.firstOrNull { it.isPrimary } ?: accounts.firstOrNull())?.id) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var note by remember { mutableStateOf("") }
    var captureUri by remember { mutableStateOf<Uri?>(null) }

    fun read(uri: Uri) = scope.launch {
        vm.scanReceipt(ImageInput.toBase64Jpeg(context, uri)) { r ->
            credit = false
            amount = minorToRupeesText(Math.round(r.totalRupees * 100))
            payee = r.merchant
            category = Category.of(r.category)
            sub = null
            r.date?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { date = it } }
            note = r.items.take(8).joinToString(", ") { it.name }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { read(it) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (ok) captureUri?.let { read(it) } }

    FormSheet(
        title = "Add transaction",
        onDismiss = { vm.clearReceipt(); onDismiss() },
        saveEnabled = (parseRupeesToMinor(amount) ?: 0) > 0,
        onSave = {
            vm.addTransaction(
                credit, parseRupeesToMinor(amount) ?: 0, payee.trim(), category, accountId, note.trim().ifEmpty { null }, date,
                sub = sub, fuel = if (category == Category.FUEL) fuel.entry() else null,
            )
            vm.clearReceipt()
            onDismiss()
        },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { ImageInput.newCaptureUri(context).also { captureUri = it; camera.launch(it) } }, enabled = scanning != "") {
                Icon(Icons.Rounded.PhotoCamera, null, tint = BuddyColors.Voice, modifier = Modifier.size(18.dp))
                Text("Scan receipt", modifier = Modifier.padding(start = 6.dp))
            }
            OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = scanning != "") {
                Icon(Icons.Rounded.PhotoLibrary, null, tint = BuddyColors.Voice, modifier = Modifier.size(18.dp))
            }
            if (scanning == "") CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (!scanning.isNullOrEmpty()) Text(scanning!!, color = BuddyColors.Red, style = MaterialTheme.typography.bodySmall)
        Choices(listOf(false to "Money out", true to "Money in"), credit, { credit = it; category = if (it) Category.INCOME else Category.FOOD; sub = null })
        Field(amount, { amount = it }, "Amount ₹", keyboard = KeyboardType.Decimal)
        Field(payee, { payee = it }, if (credit) "From" else "Paid to")
        Choices((if (credit) Category.income else Category.spending).map { it to it.label }, category, { category = it; sub = null }, label = "Category")
        if (!credit) SubcategoryPicker(vm, category, sub) { sub = it }
        if (!credit && category == Category.FUEL) FuelFields(vm, fuel, parseRupeesToMinor(amount) ?: 0)
        if (accounts.isNotEmpty()) Choices(accounts.map { it.id to it.name }, accountId ?: "", { accountId = it }, label = "Account")
        DateField("Date", date, { date = it ?: LocalDate.now() }, allowClear = false)
        Field(note, { note = it }, "Note")
    }
}
