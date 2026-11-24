package com.shrey.buddy.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------------------------------------------------------------- sign in

@Composable
fun LoginScreen(app: BuddyState, c: BuddyTheme) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun go() {
        if (busy || email.isBlank() || password.isBlank()) return
        busy = true; error = null
        scope.launch {
            runCatching { app.cloud.signIn(email, password) }
                .onFailure { error = "That email and password didn't work. Try again." }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        androidx.compose.foundation.Image(AppIcons.window, null, Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(16.dp))
        Text("Welcome to Buddy", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        Text("Sign in with the same account as your phone", color = c.dim, fontSize = 13.sp)
        Spacer(Modifier.height(24.dp))
        Field(email, { email = it }, "Email", c)
        Spacer(Modifier.height(10.dp))
        Field(password, { password = it }, "Password", c, secret = true, onDone = ::go)
        error?.let { Text(it, color = c.late, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)) }
        Spacer(Modifier.height(18.dp))
        PillButton(if (busy) "Signing in…" else "Sign in", c, primary = true, onClick = ::go)
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String, c: BuddyTheme, secret: Boolean = false, onDone: () -> Unit = {}) {
    Box(
        Modifier.width(300.dp).clip(RoundedCornerShape(12.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (value.isEmpty()) Text(hint, color = c.faint, fontSize = 14.sp)
        BasicTextField(
            value, onChange, singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 14.sp), cursorBrush = SolidColor(c.accent),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().onEnter(onDone),
        )
    }
}

private fun Modifier.onEnter(action: () -> Unit) = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { action(); true } else false
}

// ---------------------------------------------------------------- home

@Composable
fun HomeScreen(app: BuddyState, updater: Updater, c: BuddyTheme, onQuickAdd: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val online by app.online.collectAsState()
    val settings by app.settings.collectAsState()
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(greeting(), color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.ENGLISH)), color = c.dim, fontSize = 13.sp)
            }
            if (!online) {
                Icon(Icons.Rounded.CloudOff, "Offline", tint = c.dim, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(c.accent).clickable(onClick = onQuickAdd).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Add, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add", color = c.onAccent, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.clip(RoundedCornerShape(50)).background(c.chip).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf("Today", "Upcoming", "Settings").forEachIndexed { i, label ->
                Text(
                    label, color = if (tab == i) c.text else c.dim, fontSize = 13.sp,
                    fontWeight = if (tab == i) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (tab == i) c.surface else Color.Transparent)
                        .clickable { tab = i }.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        when (tab) {
            0 -> TodayTab(app, c, settings.hotkey.label)
            1 -> UpcomingTab(app, c)
            else -> SettingsTab(app, updater, c)
        }
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

@Composable
private fun Card(c: BuddyTheme, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.line, shape).padding(8.dp), content = content)
}

@Composable
private fun TodayTab(app: BuddyState, c: BuddyTheme, hotkey: String) {
    val a by app.agenda.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (a.total > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)).background(c.divider)) {
                    Box(Modifier.fillMaxWidth(a.done.size.toFloat() / a.total).height(6.dp).clip(RoundedCornerShape(50)).background(c.accent))
                }
                Spacer(Modifier.width(10.dp))
                Text("${a.done.size} of ${a.total} done", color = c.accentText, fontSize = 12.sp)
            }
        }
        if (a.left == 0) {
            Card(c) {
                Text(
                    if (a.total == 0) "Nothing planned for today. Press $hotkey to add something." else "All done for today. Nice.",
                    color = c.dim, fontSize = 13.sp, modifier = Modifier.padding(10.dp),
                )
            }
        }
        Group("Overdue", a.overdue, app, c, late = true)
        Group("Reminders", a.reminders, app, c)
        Group("Tasks", a.tasks, app, c)
        Group("Done", a.done, app, c)
    }
}

@Composable
private fun Group(title: String, items: List<Task>, app: BuddyState, c: BuddyTheme, late: Boolean = false, showDay: Boolean = false) {
    if (items.isEmpty()) return
    Column {
        Text(title, color = if (late) c.late else c.dim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 6.dp))
        Card(c) { items.forEach { TaskRow(app, it, c, late = late, showDay = showDay) } }
    }
}

@Composable
private fun UpcomingTab(app: BuddyState, c: BuddyTheme) {
    val tasks by app.tasks.collectAsState()
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val byDay = remember(tasks) {
        tasks.filter { !it.isDone && (it.remindAt ?: it.dueAt) != null }
            .groupBy { Instant.ofEpochMilli(it.remindAt ?: it.dueAt!!).atZone(zone).toLocalDate() }
            .filterKeys { it.isAfter(today) && !it.isAfter(today.plusDays(14)) }
            .toSortedMap()
    }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (byDay.isEmpty()) Card(c) { Text("Nothing in the next two weeks.", color = c.dim, fontSize = 13.sp, modifier = Modifier.padding(10.dp)) }
        byDay.forEach { (day, list) ->
            val label = if (day == today.plusDays(1)) "Tomorrow" else day.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH))
            Group(label, list.sortedBy { it.remindAt ?: it.dueAt }, app, c)
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
private fun SettingsTab(app: BuddyState, updater: Updater, c: BuddyTheme) {
    val s by app.settings.collectAsState()
    val update by updater.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(c) {
            SettingRow("Hotkey", "Tap to type. Hold to talk through Wispr Flow, then press Enter.", c) {
                Choice(s.hotkey.label, Hotkey.entries.map { it.label }, c) { i -> app.updateSettings { it.copy(hotkey = Hotkey.entries[i]) } }
            }
            Toggle("Hold to talk", "Holding the hotkey also holds Wispr's talk key", s.holdToTalk, c) { v -> app.updateSettings { it.copy(holdToTalk = v) } }
            Toggle("Today pill", "The small pill at the top of the screen", s.showPill, c) { v -> app.updateSettings { it.copy(showPill = v) } }
            if (s.pillX != null) {
                SettingRow("Pill position", "You moved it; put it back at the top centre", c) {
                    PillButton("Reset", c) { app.updateSettings { it.copy(pillX = null, pillY = null) } }
                }
            }
        }
        Card(c) {
            SettingRow("Look", null, c) {
                Choice(s.theme.label, ThemeMode.entries.map { it.label }, c) { i -> app.updateSettings { it.copy(theme = ThemeMode.entries[i]) } }
            }
            if (s.theme == ThemeMode.BY_TIME) {
                val times = (5..23).flatMap { h -> listOf("%02d:00".format(h), "%02d:30".format(h)) }
                SettingRow("Dark from", null, c) { Choice(pretty(s.darkFrom), times.map(::pretty), c) { i -> app.updateSettings { it.copy(darkFrom = times[i]) } } }
                SettingRow("Light from", null, c) { Choice(pretty(s.lightFrom), times.map(::pretty), c) { i -> app.updateSettings { it.copy(lightFrom = times[i]) } } }
            }
            Toggle("Reminder sound", null, s.sound, c) { v -> app.updateSettings { it.copy(sound = v) } }
            Toggle("Start with Windows", null, s.startWithWindows, c) { v ->
                app.updateSettings { it.copy(startWithWindows = v) }
                Win.setStartWithWindows(v)
            }
        }
        Card(c) {
            SettingRow(
                "Buddy ${BuildInfo.VERSION_NAME}",
                when (val u = update) {
                    UpdateState.Checking -> "Checking…"
                    UpdateState.UpToDate -> "Up to date"
                    is UpdateState.Available -> "Version ${u.release.versionName} is ready" + (u.release.notes.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
                    is UpdateState.Installing -> "Installing ${u.release.versionName}…"
                    is UpdateState.Failed -> u.message
                    UpdateState.Idle -> null
                },
                c,
            ) {
                val u = update
                if (u is UpdateState.Available) PillButton("Update", c, primary = true) { scope.launch { updater.install(u.release) } }
                else PillButton("Check for updates", c) { scope.launch { updater.check() } }
            }
            SettingRow("Account", app.cloud.email, c) { PillButton("Sign out", c) { scope.launch { app.cloud.signOut() } } }
            val pending = app.cloud.pendingWrites
            if (pending > 0) Text("$pending change(s) waiting to sync", color = c.dim, fontSize = 12.sp, modifier = Modifier.padding(10.dp))
        }
    }
}

private fun pretty(hhmm: String): String = runCatching {
    LocalTime.parse(hhmm).format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)).lowercase()
}.getOrDefault(hhmm)

@Composable
private fun SettingRow(title: String, sub: String?, c: BuddyTheme, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontSize = 14.sp)
            sub?.let { Text(it, color = c.dim, fontSize = 12.sp) }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun Toggle(title: String, sub: String?, value: Boolean, c: BuddyTheme, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(!value) }) {
        SettingRow(title, sub, c) {
            Switch(
                value, onChange,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = c.accent, checkedThumbColor = c.onAccent,
                    uncheckedTrackColor = c.chip, uncheckedThumbColor = c.faint, uncheckedBorderColor = c.line,
                ),
            )
        }
    }
}

@Composable
private fun Choice(current: String, options: List<String>, c: BuddyTheme, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        PillButton("$current  ▾", c) { open = true }
        DropdownMenu(open, { open = false }, modifier = Modifier.background(c.surface)) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(text = { Text(o, color = c.text, fontSize = 13.sp) }, onClick = { open = false; onPick(i) })
            }
        }
    }
}
