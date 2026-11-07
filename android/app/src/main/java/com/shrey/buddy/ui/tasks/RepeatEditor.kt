package com.shrey.buddy.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shrey.buddy.data.tasks.RepeatRule
import com.shrey.buddy.data.tasks.RepeatRule.Companion.short
import com.shrey.buddy.reminders.formatTime
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class RepeatChoice(val rule: RepeatRule, val start: LocalDate, val time: LocalTime?, val ring: Boolean)

/**
 * Full repeat editor: how often (every N days/weeks/months/years), which weekdays, which day of the month,
 * the time it happens, and whether Buddy rings then. `onApply(null)` = stop repeating.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepeatEditorSheet(
    initialRule: RepeatRule?,
    initialStart: LocalDate?,
    initialTime: LocalTime?,
    initialRing: Boolean,
    onDismiss: () -> Unit,
    onApply: (RepeatChoice?) -> Unit,
) {
    val today = LocalDate.now()
    var freq by remember { mutableStateOf(initialRule?.freq ?: RepeatRule.Freq.DAILY) }
    var interval by remember { mutableStateOf(initialRule?.interval ?: 1) }
    var days by remember {
        mutableStateOf(initialRule?.days?.takeIf { it.isNotEmpty() } ?: setOf((initialStart ?: today).dayOfWeek))
    }
    var lastDay by remember { mutableStateOf(initialRule?.monthDay == -1) }
    var monthDay by remember { mutableStateOf(initialRule?.monthDay?.takeIf { it > 0 } ?: (initialStart ?: today).dayOfMonth) }
    var start by remember { mutableStateOf(initialStart ?: today) }
    var time by remember { mutableStateOf(initialTime ?: LocalTime.of(9, 0)) }
    var hasTime by remember { mutableStateOf(initialTime != null || initialRule == null) }
    var ring by remember { mutableStateOf(initialRing) }
    var pickingTime by remember { mutableStateOf(false) }
    var pickingStart by remember { mutableStateOf(false) }

    fun rule() = RepeatRule(
        freq = freq,
        interval = interval,
        days = if (freq == RepeatRule.Freq.WEEKLY) days else emptySet(),
        monthDay = if (freq == RepeatRule.Freq.MONTHLY) (if (lastDay) -1 else monthDay) else null,
    )
    // The first occurrence that actually matches the rule, on/after the chosen start.
    fun firstDate(): LocalDate = rule().firstOnOrAfter(start).let { d ->
        // Today's time already gone → begin with the next one.
        if (d == today && hasTime && !time.isAfter(LocalTime.now())) rule().next(d.atTime(time)).toLocalDate() else d
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = BuddyColors.Surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text("Repeat", style = MaterialTheme.typography.titleLarge)
            Text(
                rule().describe(firstDate().atTime(time), withTime = hasTime),
                style = MaterialTheme.typography.titleMedium, color = BuddyColors.Lime, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 4.dp),
            )

            Section("How often")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    RepeatRule.Freq.DAILY to "Daily", RepeatRule.Freq.WEEKLY to "Weekly",
                    RepeatRule.Freq.MONTHLY to "Monthly", RepeatRule.Freq.YEARLY to "Yearly",
                ).forEach { (f, label) -> Chip(label, freq == f) { freq = f } }
            }

            // Every N …
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Every", style = MaterialTheme.typography.bodyLarge)
                FilledTonalIconButton(onClick = { if (interval > 1) interval-- }, modifier = Modifier.padding(start = 12.dp).size(36.dp)) {
                    Icon(Icons.Rounded.Remove, contentDescription = "Less often")
                }
                Text("$interval", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 14.dp))
                FilledTonalIconButton(onClick = { if (interval < 99) interval++ }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = "More often")
                }
                val unit = when (freq) {
                    RepeatRule.Freq.DAILY -> "day"
                    RepeatRule.Freq.WEEKLY -> "week"
                    RepeatRule.Freq.MONTHLY -> "month"
                    RepeatRule.Freq.YEARLY -> "year"
                }
                Text(if (interval == 1) unit else "${unit}s", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
            }

            if (freq == RepeatRule.Freq.WEEKLY) {
                Section("On")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    DayOfWeek.entries.forEach { d ->
                        val on = d in days
                        Surface(
                            onClick = { days = if (on && days.size > 1) days - d else days + d },
                            shape = CircleShape,
                            color = if (on) BuddyColors.Lime else BuddyColors.SurfaceHigh,
                            modifier = Modifier.size(42.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    d.short().take(2),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (on) BuddyColors.OnLime else BuddyColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                    Chip("Weekdays", days == RepeatRule.WEEKDAYS) { days = RepeatRule.WEEKDAYS }
                    Chip("Weekends", days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)) { days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
                    Chip("Every day", days.size == 7) { days = DayOfWeek.entries.toSet() }
                }
            }

            if (freq == RepeatRule.Freq.MONTHLY) {
                Section("On day")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (1..31).forEach { n ->
                        val on = !lastDay && monthDay == n
                        Surface(
                            onClick = { lastDay = false; monthDay = n },
                            shape = CircleShape,
                            color = if (on) BuddyColors.Lime else BuddyColors.SurfaceHigh,
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("$n", style = MaterialTheme.typography.labelLarge, color = if (on) BuddyColors.OnLime else BuddyColors.TextSecondary)
                            }
                        }
                    }
                }
                Box(Modifier.padding(top = 8.dp)) { Chip("Last day of the month", lastDay) { lastDay = !lastDay } }
            }

            Section("Time")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ValueButton(Icons.Rounded.Schedule, if (hasTime) formatTime(today.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()) else "Any time") {
                    pickingTime = true
                }
                if (hasTime) Chip("Any time", false) { hasTime = false }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Ring at this time", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (hasTime) "Buddy calls you each time it comes round" else "Rings at 9 am on the day",
                        style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                    )
                }
                Switch(
                    checked = ring,
                    onCheckedChange = { ring = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = BuddyColors.OnLime, checkedTrackColor = BuddyColors.Lime),
                )
            }

            Section("Starts")
            ValueButton(Icons.Rounded.Event, friendlyDay(firstDate(), today)) { pickingStart = true }

            Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (initialRule != null) {
                    OutlinedButton(onClick = { onApply(null) }, modifier = Modifier.height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Text("Stop repeating", color = BuddyColors.Red)
                    }
                }
                Button(
                    onClick = { onApply(RepeatChoice(rule(), firstDate(), if (hasTime) time else null, ring)) },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Tasks, contentColor = BuddyColors.Background),
                ) { Text("Done") }
            }
        }
    }

    if (pickingTime) {
        TimePickDialog(time, onDismiss = { pickingTime = false }) {
            time = it
            hasTime = true
            pickingTime = false
        }
    }
    if (pickingStart) {
        DatePickDialog(start, onDismiss = { pickingStart = false }) {
            start = it
            pickingStart = false
        }
    }
}

@Composable
private fun Section(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BuddyColors.Tasks.copy(alpha = 0.2f), selectedLabelColor = BuddyColors.TextPrimary),
    )
}

@Composable
private fun ValueButton(icon: ImageVector, text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = BuddyColors.SurfaceHigh) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = BuddyColors.Tasks, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
