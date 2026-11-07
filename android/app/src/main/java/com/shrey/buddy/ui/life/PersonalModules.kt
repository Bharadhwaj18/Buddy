package com.shrey.buddy.ui.life

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.items.Goal
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.Journal
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Note
import com.shrey.buddy.data.items.Person
import com.shrey.buddy.data.items.ReadingItem
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.Choices
import com.shrey.buddy.ui.components.DateField
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.components.FormSheet
import com.shrey.buddy.ui.components.ListRow
import com.shrey.buddy.ui.components.Pill
import com.shrey.buddy.ui.components.ScalePicker
import com.shrey.buddy.ui.components.SectionHeader
import com.shrey.buddy.ui.components.ToggleField
import com.shrey.buddy.ui.components.fieldColors
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val moodFaces = listOf("😞", "😕", "😐", "🙂", "😄")
private val energyFaces = listOf("🪫", "😴", "🙂", "⚡", "🚀")

// ---------------------------------------------------------------- Journal

@Composable
fun JournalScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val entries by lvm.journal.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val todays = entries.firstOrNull { it.date == today }?.data ?: Journal()
    var mood by remember(todays) { mutableStateOf(todays.mood) }
    var energy by remember(todays) { mutableStateOf(todays.energy) }
    var text by remember(todays) { mutableStateOf(todays.text) }
    var gratitude by remember(todays) { mutableStateOf(todays.gratitude) }
    var editing by remember { mutableStateOf<Item<Journal>?>(null) }
    val color = LifeModule.JOURNAL.color

    ModuleScaffold(LifeModule.JOURNAL, lvm, onBack) {
        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Text("Today", style = MaterialTheme.typography.titleMedium)
                ScalePicker("Mood", mood, { mood = it }, moodFaces)
                ScalePicker("Energy", energy, { energy = it }, energyFaces)
                Field(text, { text = it }, "How was today?", singleLine = false, minLines = 3, accent = color)
                Field(gratitude, { gratitude = it }, "One thing you're grateful for", accent = color, modifier = Modifier.padding(top = 8.dp))
                Button(
                    onClick = { lvm.saveJournal(today, Journal(mood, energy, text.trim(), gratitude.trim())) },
                    modifier = Modifier.padding(top = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = BuddyColors.Background),
                ) { Text(if (entries.any { it.date == today }) "Update" else "Save") }
            }
        }
        val recent = entries.filter { it.date != null && it.date!!.isAfter(today.minusDays(14)) }.sortedBy { it.date }
        if (recent.size >= 2) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Mood · last 2 weeks", style = MaterialTheme.typography.titleSmall)
                    Canvas(Modifier.fillMaxWidth().height(80.dp).padding(top = 10.dp)) {
                        val n = 14
                        val step = size.width / (n - 1)
                        val pts = recent.map { e ->
                            val x = (n - 1 - ChronoUnit.DAYS.between(e.date, today)).toFloat() * step
                            val y = size.height - (e.data.mood - 1) / 4f * size.height
                            Offset(x, y)
                        }
                        pts.zipWithNext { a, b -> drawLine(color, a, b, strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round) }
                        pts.forEach { drawCircle(color, 5.dp.toPx(), it) }
                    }
                }
            }
        }
        item { SectionHeader("Past entries") }
        items(entries.filter { it.date != today }, key = { it.id }) { e ->
            BuddyCard(Modifier.fillMaxWidth(), onClick = { editing = e }, contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(moodFaces.getOrElse(e.data.mood - 1) { "😐" }, style = MaterialTheme.typography.headlineSmall)
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(e.date?.let { friendlyDay(it) } ?: "", style = MaterialTheme.typography.titleSmall)
                        if (e.data.text.isNotBlank()) Text(e.data.text, style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    editing?.let { e ->
        var m by remember { mutableStateOf(e.data.mood) }
        var en by remember { mutableStateOf(e.data.energy) }
        var t by remember { mutableStateOf(e.data.text) }
        var g by remember { mutableStateOf(e.data.gratitude) }
        FormSheet(
            title = e.date?.let { friendlyDay(it) } ?: "Entry",
            onDismiss = { editing = null },
            accent = color,
            onDelete = { lvm.delete(e.id); editing = null },
            onSave = { lvm.saveJournal(e.date ?: today, Journal(m, en, t.trim(), g.trim())); editing = null },
        ) {
            ScalePicker("Mood", m, { m = it }, moodFaces)
            ScalePicker("Energy", en, { en = it }, energyFaces)
            Field(t, { t = it }, "Entry", singleLine = false, minLines = 3, accent = color)
            Field(g, { g = it }, "Grateful for", accent = color)
        }
    }
}

// ---------------------------------------------------------------- Notes

@Composable
fun NotesScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val notes by lvm.notes.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Item<Note>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val color = LifeModule.NOTES.color
    val shown = notes
        .filter { query.isBlank() || it.data.title.contains(query, true) || it.data.body.contains(query, true) || it.data.tags.any { t -> t.contains(query, true) } }
        .sortedWith(compareByDescending<Item<Note>> { it.data.pinned }.thenByDescending { it.entity.updatedAt })

    ModuleScaffold(LifeModule.NOTES, lvm, onBack, onAdd = { adding = true }) {
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, placeholder = { Text("Search notes") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), colors = fieldColors(color), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (shown.isEmpty()) item { EmptyNote(if (notes.isEmpty()) "Capture ideas, lists, anything. Tap ＋." else "No matches.") }
        items(shown, key = { it.id }) { n ->
            BuddyCard(Modifier.fillMaxWidth(), onClick = { editing = n }, contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.data.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (n.data.pinned) Icon(Icons.Rounded.PushPin, null, tint = color)
                }
                if (n.data.body.isNotBlank()) Text(n.data.body, style = MaterialTheme.typography.bodyMedium, color = BuddyColors.TextSecondary, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (n.data.tags.isNotEmpty()) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { n.data.tags.take(4).forEach { Pill("#$it", color) } }
            }
        }
    }
    if (adding || editing != null) {
        val n = editing
        val d = n?.data ?: Note()
        var title by remember { mutableStateOf(d.title) }
        var body by remember { mutableStateOf(d.body) }
        var tags by remember { mutableStateOf(d.tags.joinToString(", ")) }
        var pinned by remember { mutableStateOf(d.pinned) }
        FormSheet(
            title = if (n == null) "New note" else "Edit note",
            onDismiss = { adding = false; editing = null },
            accent = color,
            saveEnabled = title.isNotBlank() || body.isNotBlank(),
            onDelete = n?.let { { lvm.delete(it.id); editing = null } },
            onSave = {
                lvm.save(Kinds.NOTE, Note(title.trim(), body.trim(), pinned, tags.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }), Note.serializer(), id = n?.id)
                adding = false; editing = null
            },
        ) {
            Field(title, { title = it }, "Title", accent = color)
            Field(body, { body = it }, "Note", singleLine = false, minLines = 6, accent = color)
            Field(tags, { tags = it }, "Tags (comma separated)", accent = color)
            ToggleField("Pin to top", pinned, { pinned = it })
        }
    }
}

// ---------------------------------------------------------------- Goals

private val goalAreas = listOf("money" to "Money", "fitness" to "Fitness", "tasks" to "Work", "learning" to "Learning", "life" to "Life")

@Composable
fun GoalsScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val goals by lvm.goals.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Item<Goal>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val color = LifeModule.GOALS.color

    ModuleScaffold(LifeModule.GOALS, lvm, onBack, onAdd = { adding = true }) {
        if (goals.isEmpty()) item { EmptyNote("Set a target — “Save ₹1,00,000”, “Run 100 km”, “Read 12 books”.") }
        items(goals.sortedWith(compareBy<Item<Goal>> { it.data.done }.thenBy { it.data.due ?: "9999" }), key = { it.id }) { g ->
            val frac = if (g.data.target > 0) (g.data.current / g.data.target).toFloat().coerceIn(0f, 1f) else 0f
            BuddyCard(Modifier.fillMaxWidth(), onClick = { editing = g }, contentPadding = 16.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (g.data.done) "✅ ${g.data.title}" else g.data.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Pill(goalAreas.firstOrNull { it.first == g.data.area }?.second ?: g.data.area, color)
                }
                if (g.data.target > 0) {
                    LinearProgressIndicator(
                        progress = { frac }, color = color, trackColor = BuddyColors.SurfaceHigh,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(8.dp).clip(RoundedCornerShape(4.dp)),
                    )
                    Text(
                        "${fmtNum(g.data.current)} / ${fmtNum(g.data.target)} ${g.data.unit} · ${(frac * 100).toInt()}%" +
                            (parseDate(g.data.due)?.let { " · by ${friendlyDay(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
    if (adding || editing != null) {
        val g = editing
        val d = g?.data ?: Goal()
        var title by remember { mutableStateOf(d.title) }
        var area by remember { mutableStateOf(d.area) }
        var current by remember { mutableStateOf(if (d.current != 0.0) fmtNum(d.current) else "") }
        var target by remember { mutableStateOf(if (d.target != 0.0) fmtNum(d.target) else "") }
        var unit by remember { mutableStateOf(d.unit) }
        var due by remember { mutableStateOf(parseDate(d.due)) }
        var done by remember { mutableStateOf(d.done) }
        FormSheet(
            title = if (g == null) "New goal" else "Edit goal",
            onDismiss = { adding = false; editing = null },
            accent = color,
            saveEnabled = title.isNotBlank(),
            onDelete = g?.let { { lvm.delete(it.id); editing = null } },
            onSave = {
                lvm.save(Kinds.GOAL, Goal(title.trim(), area, target.toDoubleOrNull() ?: 0.0, current.toDoubleOrNull() ?: 0.0, unit.trim(), due?.toString(), done), Goal.serializer(), id = g?.id)
                adding = false; editing = null
            },
        ) {
            Field(title, { title = it }, "Goal", accent = color)
            Choices(goalAreas, area, { area = it }, color)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Field(current, { current = it }, "Now", Modifier.weight(1f), keyboard = KeyboardType.Decimal, accent = color)
                Field(target, { target = it }, "Target", Modifier.weight(1f), keyboard = KeyboardType.Decimal, accent = color)
                Field(unit, { unit = it }, "Unit", Modifier.weight(1f), accent = color)
            }
            DateField("Deadline", due, { due = it })
            ToggleField("Achieved", done, { done = it })
        }
    }
}

private fun fmtNum(v: Double) = if (v % 1.0 == 0.0) "%,d".format(v.toLong()) else "%,.1f".format(v)

// ---------------------------------------------------------------- People

private val mmdd = DateTimeFormatter.ofPattern("MM-dd")
private val birthdayLabel = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

@Composable
fun PeopleScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val people by lvm.people.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Item<Person>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val color = LifeModule.PEOPLE.color
    val today = LocalDate.now()

    ModuleScaffold(LifeModule.PEOPLE, lvm, onBack, onAdd = { adding = true }) {
        if (people.isEmpty()) item { EmptyNote("Add people who matter — Buddy reminds you of birthdays and to keep in touch.") }
        items(people.sortedBy { nextBirthday(it.data.birthday) ?: LocalDate.MAX }, key = { it.id }) { p ->
            val bday = nextBirthday(p.data.birthday)
            val last = parseDate(p.data.lastContact)
            val overdue = p.data.contactEveryDays > 0 && (last == null || ChronoUnit.DAYS.between(last, today) >= p.data.contactEveryDays)
            BuddyCard(Modifier.fillMaxWidth(), onClick = { editing = p }, contentPadding = 12.dp) {
                ListRow(
                    icon = Icons.Rounded.Cake,
                    tint = color,
                    title = p.data.name,
                    subtitle = listOfNotNull(
                        p.data.relation.ifBlank { null },
                        bday?.let { "🎂 ${it.format(birthdayLabel)} (in ${ChronoUnit.DAYS.between(today, it)} d)" },
                        last?.let { "talked ${friendlyDay(it).lowercase()}" },
                    ).joinToString(" · "),
                ) {
                    if (overdue) {
                        TextButton(onClick = { lvm.save(Kinds.PERSON, p.data.copy(lastContact = today.toString()), Person.serializer(), id = p.id) }) {
                            Text("Talked today", color = color)
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        val p = editing
        val d = p?.data ?: Person()
        var name by remember { mutableStateOf(d.name) }
        var relation by remember { mutableStateOf(d.relation) }
        var birthday by remember { mutableStateOf(d.birthday?.let { runCatching { java.time.MonthDay.parse("--$it").atYear(2000) }.getOrNull() }) }
        var last by remember { mutableStateOf(parseDate(d.lastContact)) }
        var every by remember { mutableStateOf(if (d.contactEveryDays > 0) d.contactEveryDays.toString() else "") }
        var notes by remember { mutableStateOf(d.notes) }
        FormSheet(
            title = if (p == null) "Add person" else "Edit person",
            onDismiss = { adding = false; editing = null },
            accent = color,
            saveEnabled = name.isNotBlank(),
            onDelete = p?.let { { lvm.delete(it.id); editing = null } },
            onSave = {
                lvm.save(Kinds.PERSON, Person(name.trim(), relation.trim(), birthday?.format(mmdd), last?.toString(), every.toIntOrNull() ?: 0, notes.trim()), Person.serializer(), id = p?.id)
                adding = false; editing = null
            },
        ) {
            Field(name, { name = it }, "Name", accent = color)
            Field(relation, { relation = it }, "Relation (friend, family, colleague…)", accent = color)
            DateField("Birthday", birthday, { birthday = it })
            DateField("Last spoke", last, { last = it })
            Field(every, { every = it.filter(Char::isDigit) }, "Remind me to catch up every N days (optional)", keyboard = KeyboardType.Number, accent = color)
            Field(notes, { notes = it }, "Notes (gift ideas, kids' names…)", singleLine = false, minLines = 2, accent = color)
        }
    }
}

// ---------------------------------------------------------------- Reading

private val readingTypes = listOf("book" to "Book", "course" to "Course", "article" to "Article", "podcast" to "Podcast")
private val readingStatus = listOf("want" to "Want to", "active" to "In progress", "done" to "Finished")

@Composable
fun ReadingScreen(lvm: LifeViewModel, onBack: () -> Unit) {
    val list by lvm.reading.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Item<ReadingItem>?>(null) }
    var adding by remember { mutableStateOf(false) }
    val color = LifeModule.READING.color

    ModuleScaffold(LifeModule.READING, lvm, onBack, onAdd = { adding = true }) {
        if (list.isEmpty()) item { EmptyNote("Track books, courses and things to learn.") }
        readingStatus.forEach { (status, label) ->
            val group = list.filter { it.data.status == status }
            if (group.isNotEmpty()) {
                item { SectionHeader("$label · ${group.size}") }
                items(group, key = { it.id }) { r ->
                    BuddyCard(Modifier.fillMaxWidth(), onClick = { editing = r }, contentPadding = 16.dp) {
                        Text(r.data.title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(r.data.author.ifBlank { null }, readingTypes.firstOrNull { it.first == r.data.type }?.second,
                                if (r.data.rating > 0) "★".repeat(r.data.rating) else null).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary,
                        )
                        if (status == "active") {
                            LinearProgressIndicator(
                                progress = { r.data.progress / 100f }, color = color, trackColor = BuddyColors.SurfaceHigh,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)),
                            )
                        }
                    }
                }
            }
        }
    }
    if (adding || editing != null) {
        val r = editing
        val d = r?.data ?: ReadingItem()
        var title by remember { mutableStateOf(d.title) }
        var author by remember { mutableStateOf(d.author) }
        var type by remember { mutableStateOf(d.type) }
        var status by remember { mutableStateOf(d.status) }
        var progress by remember { mutableStateOf(d.progress.toString()) }
        var rating by remember { mutableStateOf(d.rating) }
        var notes by remember { mutableStateOf(d.notes) }
        FormSheet(
            title = if (r == null) "Add" else "Edit",
            onDismiss = { adding = false; editing = null },
            accent = color,
            saveEnabled = title.isNotBlank(),
            onDelete = r?.let { { lvm.delete(it.id); editing = null } },
            onSave = {
                lvm.save(
                    Kinds.READING,
                    ReadingItem(title.trim(), author.trim(), type, status, (progress.toIntOrNull() ?: 0).coerceIn(0, 100).let { if (status == "done") 100 else it }, rating, notes.trim()),
                    ReadingItem.serializer(), id = r?.id,
                )
                adding = false; editing = null
            },
        ) {
            Field(title, { title = it }, "Title", accent = color)
            Field(author, { author = it }, "Author / platform", accent = color)
            Choices(readingTypes, type, { type = it }, color)
            Choices(readingStatus, status, { status = it }, color)
            if (status == "active") Field(progress, { progress = it.filter(Char::isDigit).take(3) }, "Progress %", keyboard = KeyboardType.Number, accent = color)
            ScalePicker("Rating", rating, { rating = it }, listOf("★", "★★", "★★★", "★★★★", "★★★★★"))
            Field(notes, { notes = it }, "Notes / key ideas", singleLine = false, minLines = 3, accent = color)
        }
    }
}
