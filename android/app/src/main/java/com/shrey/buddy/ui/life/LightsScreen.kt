package com.shrey.buddy.ui.life

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Light
import com.shrey.buddy.data.items.LightAction
import com.shrey.buddy.data.items.LightRoutine
import com.shrey.buddy.lights.LightVoice
import com.shrey.buddy.lights.Lights
import com.shrey.buddy.lights.Wiz
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.LocalTime

private val Amber = Color(0xFFFFC94D)

private val TRIGGERS = linkedMapOf(
    "time" to "At a time",
    "manual" to "When I say it",
    "arrive" to "Arriving home",
    "wake" to "Wake-up reminder",
    "reminder" to "Any reminder calls",
)

private fun hex(c: String) = Lights.hexRgb(c)?.let { (r, g, b) -> Color(r, g, b) } ?: Color.White

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LightsScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val lights by lvm.lights.collectAsStateWithLifecycle()
    val routines by lvm.lightRoutines.collectAsStateWithLifecycle()
    var editingLight by remember { mutableStateOf<Item<Light>?>(null) }
    var editingRoutine by remember { mutableStateOf<Pair<String?, LightRoutine>?>(null) }
    val rooms = lights.map { it.data.room }.filter { it.isNotBlank() }.distinct().sorted()
    var addingIp by remember { mutableStateOf(false) }
    if (addingIp) {
        var ip by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addingIp = false },
            containerColor = BuddyColors.Surface,
            title = { Text("Add a light by IP") },
            text = {
                Column {
                    Text(
                        "The bulb's IP address (like 192.168.1.23) is in the WiZ app's settings for that bulb, or in your router's list of connected devices.",
                        style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary,
                    )
                    OutlinedTextField(
                        ip, { ip = it.filter { c -> c.isDigit() || c == '.' } }, label = { Text("IP address") }, singleLine = true,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { lvm.addLightByIp(ip); addingIp = false },
                    enabled = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip),
                ) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addingIp = false }) { Text("Cancel") } },
        )
    }

    ModuleScaffold(LifeModule.LIGHTS, lvm, onBack, onAdd = if (lights.isEmpty()) null else ({ editingRoutine = null to LightRoutine() })) {
        if (lights.isEmpty()) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Connect your WiZ lights", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Buddy talks to the bulbs directly on your home Wi-Fi: no account, no cloud. Keep the phone on the same Wi-Fi as the bulbs, with them switched on.",
                        style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        "If nothing is found: in the WiZ app's settings, turn on \"Allow local communication\". WiZ blocks other apps on your Wi-Fi until you do.",
                        style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
                        Button(
                            onClick = { lvm.findLights() },
                            colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
                        ) { Text("Find my lights") }
                        TextButton(onClick = { addingIp = true }) { Text("Add by IP") }
                    }
                }
            }
            return@ModuleScaffold
        }

        // Controls: everything, then each room.
        item { GroupControl("All lights", emptyList(), lvm) }
        rooms.forEach { room -> item(key = "room:$room") { GroupControl(room, listOf(room), lvm) } }

        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Lights", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { addingIp = true }) { Text("Add by IP") }
                TextButton(onClick = { lvm.findLights() }) {
                    Icon(Icons.Rounded.Refresh, null, modifier = Modifier.size(16.dp)); Text(" Find again")
                }
            }
        }
        items(lights, key = { it.id }) { l ->
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 14.dp, onClick = { editingLight = l }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = Amber)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(l.data.name, style = MaterialTheme.typography.titleSmall)
                        Text(l.data.room.ifBlank { "No room · tap to set" }, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                    TextButton(onClick = { lvm.lightActionFor(l.data, true) }) { Text("On") }
                    TextButton(onClick = { lvm.lightActionFor(l.data, false) }) { Text("Off") }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Routines", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { editingRoutine = null to LightRoutine() }) { Text("Add") }
            }
            if (routines.isEmpty()) Text(
                "e.g. warm lights at 7 pm, \"movie mode\" by voice, lights on when you get home after dark, a gentle brighten for your wake-up reminder.",
                style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextTertiary,
            )
        }
        items(routines, key = { it.id }) { r ->
            BuddyCard(Modifier.fillMaxWidth(), contentPadding = 14.dp, onClick = { editingRoutine = r.id to r.data }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.data.name.ifBlank { "Routine" }, style = MaterialTheme.typography.titleSmall)
                        Text(describe(r.data), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                        Text(LightVoice.describe(r.data.action), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextSecondary)
                    }
                    IconButton(onClick = { lvm.runLightRoutine(r.data) }) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Run now") }
                    Switch(
                        checked = r.data.enabled, onCheckedChange = { lvm.saveLightRoutine(r.data.copy(enabled = it), r.id) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Amber),
                    )
                }
            }
        }
    }

    editingLight?.let { l ->
        var name by remember(l.id) { mutableStateOf(l.data.name) }
        var room by remember(l.id) { mutableStateOf(l.data.room) }
        AlertDialog(
            onDismissRequest = { editingLight = null },
            containerColor = BuddyColors.Surface,
            title = { Text("Light") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(room, { room = it }, label = { Text("Room (e.g. Bedroom)") }, singleLine = true, modifier = Modifier.padding(top = 8.dp))
                    if (rooms.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        rooms.forEach { r -> FilterChip(selected = room == r, onClick = { room = r }, label = { Text(r) }) }
                    }
                    Text("Say \"${room.ifBlank { "bedroom" }.lowercase()} lights off\" to control a room.", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    lvm.save(Kinds.LIGHT, l.data.copy(name = name.trim().ifBlank { l.data.name }, room = room.trim()), Light.serializer(), id = l.id)
                    editingLight = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { lvm.deleteItemAndReschedule(l.id); editingLight = null }) { Text("Remove", color = BuddyColors.Red) }
            },
        )
    }

    editingRoutine?.let { (id, start) ->
        RoutineEditor(id, start, rooms, onDismiss = { editingRoutine = null }, onDelete = id?.let { { lvm.deleteItemAndReschedule(it); editingRoutine = null } }) { r ->
            lvm.saveLightRoutine(r, id); editingRoutine = null
        }
    }
}

private fun describe(r: LightRoutine): String = when (r.trigger) {
    "time" -> "${r.time} · " + if (r.days.isEmpty()) "every day" else r.days.sorted().joinToString(" ") { Lights.dayName(it) }
    "manual" -> "Say \"${r.name.ifBlank { "its name" }}\" or tap ▶"
    "arrive" -> "When you get home" + (r.after?.let { " after $it" } ?: "")
    "wake" -> "When a wake-up / alarm reminder calls"
    "reminder" -> "When any reminder calls"
    else -> r.trigger
}

/** On / off, brightness, warmth, colours and scenes for a room (or everything). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupControl(title: String, rooms: List<String>, lvm: LifeViewModel) {
    var level by remember { mutableFloatStateOf(80f) }
    var showScenes by remember { mutableStateOf(false) }
    BuddyCard(Modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { lvm.lightAction(LightAction(on = false, rooms = rooms)) }) { Text("Off") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { lvm.lightAction(LightAction(on = true, brightness = level.toInt(), rooms = rooms)) },
                colors = ButtonDefaults.buttonColors(containerColor = Amber, contentColor = Color.Black),
            ) { Text("On") }
        }
        Slider(
            value = level, onValueChange = { level = it }, valueRange = 10f..100f,
            onValueChangeFinished = { lvm.lightAction(LightAction(on = true, brightness = level.toInt(), rooms = rooms)) },
            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(2700 to "Warm", 4200 to "Day", 6500 to "Cool").forEach { (k, label) ->
                FilterChip(selected = false, onClick = { lvm.lightAction(LightAction(on = true, warmth = k, brightness = level.toInt(), rooms = rooms)) }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            LightVoice.COLORS.values.forEach { c ->
                Box(
                    Modifier.size(28.dp).clip(CircleShape).background(hex(c)).border(1.dp, BuddyColors.SurfaceHigh, CircleShape)
                        .clickable { lvm.lightAction(LightAction(on = true, color = c, brightness = level.toInt(), rooms = rooms)) },
                )
            }
        }
        TextButton(onClick = { showScenes = !showScenes }) { Text(if (showScenes) "Hide scenes" else "Scenes") }
        if (showScenes) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Wiz.SCENES.forEach { (sid, name) ->
                FilterChip(selected = false, onClick = { lvm.lightAction(LightAction(on = true, sceneId = sid, rooms = rooms)) }, label = { Text(name) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RoutineEditor(
    id: String?, start: LightRoutine, rooms: List<String>,
    onDismiss: () -> Unit, onDelete: (() -> Unit)?, onSave: (LightRoutine) -> Unit,
) {
    val context = LocalContext.current
    var r by remember(id) { mutableStateOf(start) }
    // What the lights do: on with warmth / colour / scene, flash, or off.
    var mode by remember(id) {
        mutableStateOf(
            when {
                !start.action.on -> "off"
                start.action.effect == "flash" -> "flash"
                start.action.sceneId != null -> "scene"
                start.action.color != null -> "color"
                else -> "white"
            },
        )
    }
    fun pickTime(current: String?, set: (String) -> Unit) {
        val t = current?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: LocalTime.of(19, 0)
        android.app.TimePickerDialog(context, { _, h, m -> set("%02d:%02d".format(h, m)) }, t.hour, t.minute, false).show()
    }
    val a = r.action
    fun setAction(f: (LightAction) -> LightAction) { r = r.copy(action = f(r.action)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BuddyColors.Surface,
        title = { Text(if (id == null) "New routine" else "Routine") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(r.name, { r = r.copy(name = it) }, label = { Text("Name (say it to run it)") }, placeholder = { Text("Movie mode") }, singleLine = true)

                Label("When")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TRIGGERS.forEach { (k, label) -> FilterChip(selected = r.trigger == k, onClick = { r = r.copy(trigger = k) }, label = { Text(label) }) }
                }
                when (r.trigger) {
                    "time" -> {
                        TextButton(onClick = { pickTime(r.time) { r = r.copy(time = it) } }) { Text("At ${r.time}") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (1..7).forEach { d ->
                                FilterChip(
                                    selected = d in r.days,
                                    onClick = { r = r.copy(days = if (d in r.days) r.days - d else (r.days + d).sorted()) },
                                    label = { Text(Lights.dayName(d)) },
                                )
                            }
                        }
                        Text(if (r.days.isEmpty()) "Every day" else "Only on the days picked", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                    "arrive" -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Only after", modifier = Modifier.weight(1f))
                            TextButton(onClick = { pickTime(r.after ?: "18:00") { r = r.copy(after = it) } }) { Text(r.after ?: "any time") }
                            if (r.after != null) TextButton(onClick = { r = r.copy(after = null) }) { Text("Clear") }
                        }
                        Text(
                            "Buddy notices you're home when the phone joins the Wi-Fi your lights are on, after being away 20+ minutes.",
                            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary,
                        )
                    }
                    "manual" -> Text("Say \"Buddy listen, ${r.name.ifBlank { "movie mode" }.lowercase()}\" or tap ▶.", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    "wake" -> Text("Runs when a reminder with wake / alarm / get up / morning in its title calls. Tip: the Wake up scene brightens slowly.", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    "reminder" -> Text("Runs whenever a reminder calls. Tip: Flash, so you notice it.", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                }

                Label("Which lights")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = a.rooms.isEmpty(), onClick = { setAction { it.copy(rooms = emptyList()) } }, label = { Text("All") })
                    rooms.forEach { room ->
                        FilterChip(
                            selected = room in a.rooms,
                            onClick = { setAction { it.copy(rooms = if (room in it.rooms) it.rooms - room else it.rooms + room) } },
                            label = { Text(room) },
                        )
                    }
                }

                Label("Do")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("white" to "On (white)", "color" to "Colour", "scene" to "Scene", "flash" to "Flash", "off" to "Off").forEach { (k, label) ->
                        FilterChip(
                            selected = mode == k,
                            onClick = {
                                mode = k
                                setAction {
                                    when (k) {
                                        "off" -> it.copy(on = false, effect = null)
                                        "flash" -> it.copy(on = true, effect = "flash", sceneId = null, color = null)
                                        "scene" -> it.copy(on = true, effect = null, color = null, warmth = null, sceneId = it.sceneId ?: 11)
                                        "color" -> it.copy(on = true, effect = null, sceneId = null, warmth = null, color = it.color ?: LightVoice.COLORS.values.first())
                                        else -> it.copy(on = true, effect = null, sceneId = null, color = null, warmth = it.warmth ?: 2700)
                                    }
                                }
                            },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Amber.copy(alpha = 0.25f)),
                        )
                    }
                }
                when (mode) {
                    "white" -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        listOf(2700 to "Warm", 4200 to "Day", 6500 to "Cool").forEach { (k, label) ->
                            FilterChip(selected = a.warmth == k, onClick = { setAction { it.copy(warmth = k) } }, label = { Text(label) })
                        }
                    }
                    "color" -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                        LightVoice.COLORS.values.forEach { c ->
                            Box(
                                Modifier.size(30.dp).clip(CircleShape).background(hex(c))
                                    .border(if (a.color == c) 3.dp else 1.dp, if (a.color == c) Color.White else BuddyColors.SurfaceHigh, CircleShape)
                                    .clickable { setAction { it.copy(color = c) } },
                            )
                        }
                    }
                    "scene" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                        Wiz.SCENES.forEach { (sid, name) -> FilterChip(selected = a.sceneId == sid, onClick = { setAction { it.copy(sceneId = sid) } }, label = { Text(name) }) }
                    }
                }
                if (mode == "white" || mode == "color" || mode == "scene") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Brightness ${a.brightness ?: 100}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(120.dp))
                        Slider(
                            value = (a.brightness ?: 100).toFloat(), onValueChange = { v -> setAction { it.copy(brightness = v.toInt()) } }, valueRange = 10f..100f,
                            colors = SliderDefaults.colors(thumbColor = Amber, activeTrackColor = Amber),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(r.copy(name = r.name.trim())) }, enabled = r.name.isNotBlank() || r.trigger != "manual") { Text("Save") }
        },
        dismissButton = {
            Row {
                onDelete?.let { TextButton(onClick = it) { Text("Delete", color = BuddyColors.Red) } }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}
