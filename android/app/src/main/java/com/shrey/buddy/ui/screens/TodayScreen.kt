package com.shrey.buddy.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.ui.AppViewModel
import com.shrey.buddy.ui.HealthUi
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.ListRow
import com.shrey.buddy.ui.components.Pill
import com.shrey.buddy.ui.components.SectionHeader
import com.shrey.buddy.ui.components.StatTile
import com.shrey.buddy.ui.components.UpdateCard
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.greeting
import com.shrey.buddy.ui.health.HealthViewModel
import com.shrey.buddy.ui.inr
import com.shrey.buddy.ui.life.LifeModule
import com.shrey.buddy.ui.life.LifeViewModel
import com.shrey.buddy.ui.tasks.TaskRow
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val longDate = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)

@Composable
fun TodayScreen(
    vm: AppViewModel,
    hvm: HealthViewModel,
    lvm: LifeViewModel,
    onOpenMoney: () -> Unit,
    onOpenBuddy: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenModule: (LifeModule) -> Unit,
) {
    val money by vm.money.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val health by vm.health.collectAsStateWithLifecycle()
    val voice by vm.voiceStatus.collectAsStateWithLifecycle()
    val habits by hvm.habits.collectAsStateWithLifecycle()
    val logs by hvm.habitLogs.collectAsStateWithLifecycle()
    val spendDays by hvm.spendDays.collectAsStateWithLifecycle()
    val insights by lvm.insights.collectAsStateWithLifecycle()
    val reminders by lvm.reminders.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val stepDays by vm.stepDays.collectAsStateWithLifecycle()
    val stepsByDay = stepDays.mapValues { it.value.steps }
    val briefing = insights.firstOrNull { it.data.period == "daily" && it.date == today }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(today.format(longDate), style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                    Text("${greeting(LocalTime.now().hour)}, Shrey", style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                VoiceChip(voice, onClick = onOpenBuddy)
                FilledTonalIconButton(onClick = onOpenChat, modifier = Modifier.padding(start = 8.dp)) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = "Ask Buddy", tint = BuddyColors.Voice)
                }
            }
        }

        item { UpdateCard(vm, compact = true) }

        if (briefing != null) {
            item {
                BuddyCard(Modifier.fillMaxWidth(), color = BuddyColors.Voice.copy(alpha = 0.12f), onClick = { onOpenModule(LifeModule.INSIGHTS) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = BuddyColors.Voice, modifier = Modifier.size(18.dp))
                        Text("Your briefing", style = MaterialTheme.typography.labelLarge, color = BuddyColors.Voice, modifier = Modifier.padding(start = 8.dp))
                    }
                    Text(briefing.data.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp), maxLines = 8, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        item {
            BuddyCard(Modifier.fillMaxWidth(), onClick = onOpenMoney) {
                Text("Spent this month", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                Text(inr(money.spentThisMonth), style = MaterialTheme.typography.displayMedium.numeric(), modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("+${inr(money.receivedThisMonth)} in", BuddyColors.Lime)
                    Spacer(Modifier.width(10.dp))
                    Text("Last month ${inr(money.spentLastMonth)}", style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary)
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val steps = stepsByDay[today]
                StatTile(
                    label = "Steps today",
                    value = steps?.let { "%,d".format(it) } ?: "—",
                    icon = Icons.AutoMirrored.Rounded.DirectionsWalk,
                    accent = BuddyColors.Fitness,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenHealth,
                )
                val open = tasks.count { it.status != "done" }
                StatTile(
                    label = if (open == 1) "Open task" else "Open tasks",
                    value = open.toString(),
                    icon = Icons.Rounded.CheckCircle,
                    accent = BuddyColors.Tasks,
                    modifier = Modifier.weight(1f),
                    onClick = onOpenTasks,
                )
            }
        }

        if (habits.isNotEmpty()) {
            item { SectionHeader("Habits today", action = "All", onAction = onOpenHealth) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(habits, key = { it.id }) { h ->
                        val done = habitDone(h, today, logs, stepsByDay, spendDays)
                        Box(
                            Modifier.clip(RoundedCornerShape(18.dp))
                                .background(if (done) BuddyColors.Fitness.copy(alpha = 0.2f) else BuddyColors.Surface)
                                .then(if (h.data.auto == null) Modifier.clickable { hvm.toggleHabit(h.id) } else Modifier)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                        ) {
                            Text(
                                "${if (done) "✓" else h.data.emoji} ${h.data.name}",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (done) BuddyColors.Fitness else BuddyColors.TextPrimary,
                            )
                        }
                    }
                }
            }
        }

        item { SectionHeader("My Day", action = "All tasks", onAction = onOpenTasks) }
        item {
            val myDay = filterTasks(tasks, TaskView.MY_DAY)
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 8.dp) {
                if (myDay.isEmpty()) EmptyNote("Nothing planned. Tap ☀ on a task to add it to today.", Modifier.padding(12.dp))
                myDay.take(6).forEach { t -> TaskRow(task = t, onToggle = { vm.toggle(t) }, onMyDay = { vm.toggleMyDay(t) }, onClick = onOpenTasks) }
            }
        }

        val soon = reminders.filter { !it.date.isAfter(today.plusDays(14)) }
        if (soon.isNotEmpty() || money.upcoming.isNotEmpty()) {
            item { SectionHeader("Coming up") }
            item {
                BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                    soon.take(5).forEach { r ->
                        ListRow(
                            Icons.Rounded.NotificationsActive, r.module.color, r.title,
                            "${friendlyDay(r.date)} · ${r.detail}",
                            Modifier.padding(horizontal = 8.dp).clickable { onOpenModule(r.module) },
                        )
                    }
                    money.upcoming.take(3).forEach { u ->
                        ListRow(
                            Icons.Rounded.Event, BuddyColors.Lime, u.payee,
                            listOfNotNull(u.dueDate?.let { friendlyDay(it) }, u.bank).joinToString(" · "),
                            Modifier.padding(horizontal = 8.dp),
                        ) { Text(inr(u.amountMinor), style = MaterialTheme.typography.titleSmall.numeric()) }
                    }
                }
            }
        }

        item { SectionHeader("Recent", action = "See all", onAction = onOpenMoney) }
        item {
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 12.dp) {
                if (money.transactions.isEmpty()) EmptyNote("No transactions yet — scan your SMS from the Money tab.", Modifier.padding(horizontal = 8.dp))
                money.transactions.take(5).forEach { TxnRow(it, Modifier.padding(horizontal = 8.dp)) }
            }
        }
    }
}
