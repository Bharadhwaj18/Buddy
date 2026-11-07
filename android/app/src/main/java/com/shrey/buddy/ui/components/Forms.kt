package com.shrey.buddy.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Standard add/edit bottom sheet: scrollable fields, Save (and optional Delete) at the bottom. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    accent: Color = BuddyColors.Lime,
    saveEnabled: Boolean = true,
    saveLabel: String = "Save",
    onDelete: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // Scrolling the form must never drag the sheet closed; Back / tapping outside / Save still close it.
        sheetGesturesEnabled = false,
        containerColor = BuddyColors.Surface,
    ) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close", tint = BuddyColors.TextSecondary) }
            }
            content()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onDelete != null) {
                    OutlinedButton(onClick = onDelete, modifier = Modifier.height(52.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = BuddyColors.Red)
                    }
                }
                Button(
                    enabled = saveEnabled,
                    onClick = onSave,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = BuddyColors.Background),
                ) { Text(saveLabel) }
            }
        }
    }
}

@Composable
fun fieldColors(accent: Color = BuddyColors.Lime) = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = BuddyColors.SurfaceHigh,
    unfocusedContainerColor = BuddyColors.SurfaceHigh,
    focusedBorderColor = accent,
    unfocusedBorderColor = BuddyColors.SurfaceHigh,
)

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    accent: Color = BuddyColors.Lime,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = minLines,
        shape = RoundedCornerShape(14.dp),
        colors = fieldColors(accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> Choices(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    accent: Color = BuddyColors.Lime,
    label: String? = null,
) {
    Column {
        if (label != null) Text(label, style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(text) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = accent.copy(alpha = 0.2f),
                        selectedLabelColor = BuddyColors.TextPrimary,
                    ),
                )
            }
        }
    }
}

@Composable
fun ToggleField(title: String, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
        )
    }
}

/** Date button + picker. [allowClear] shows an ✕ to unset. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, date: LocalDate?, onChange: (LocalDate?) -> Unit, allowClear: Boolean = true) {
    var picking by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Event, null, tint = BuddyColors.TextSecondary)
        TextButton(onClick = { picking = true }) {
            Text(date?.let { "$label: ${friendlyDay(it)} (${it})" } ?: "Set $label", color = BuddyColors.Lime)
        }
        if (allowClear && date != null) {
            IconButton(onClick = { onChange(null) }) { Icon(Icons.Rounded.Close, contentDescription = "Clear $label", tint = BuddyColors.TextTertiary) }
        }
    }
    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    picking = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state = state) }
    }
}

/** 1–5 picker shown as emoji (mood, energy, ratings). */
@Composable
fun ScalePicker(label: String, value: Int, onChange: (Int) -> Unit, faces: List<String> = listOf("😞", "😕", "😐", "🙂", "😄")) {
    Column {
        Text(label, style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            faces.forEachIndexed { i, face ->
                val selected = value == i + 1
                TextButton(onClick = { onChange(i + 1) }) {
                    Text(face, style = if (selected) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                        color = if (selected) BuddyColors.TextPrimary else BuddyColors.TextTertiary)
                }
            }
        }
    }
}

/** "1,234.50" or "1234" -> paise; null if not a number. */
fun parseRupeesToMinor(text: String): Long? =
    text.replace(",", "").trim().toBigDecimalOrNull()?.movePointRight(2)?.toLong()

fun minorToRupeesText(minor: Long): String =
    if (minor % 100 == 0L) (minor / 100).toString() else "%.2f".format(minor / 100.0)
