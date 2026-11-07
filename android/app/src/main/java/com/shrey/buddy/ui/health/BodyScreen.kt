package com.shrey.buddy.ui.health

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shrey.buddy.data.health.BodyMath
import com.shrey.buddy.data.items.BodyMeasure
import com.shrey.buddy.data.items.BodyProfile
import com.shrey.buddy.data.items.Item
import com.shrey.buddy.data.items.WeightEntry
import com.shrey.buddy.ui.components.BuddyCard
import com.shrey.buddy.ui.components.Choices
import com.shrey.buddy.ui.components.DateField
import com.shrey.buddy.ui.components.EmptyNote
import com.shrey.buddy.ui.components.Field
import com.shrey.buddy.ui.components.FormSheet
import com.shrey.buddy.ui.components.numeric
import com.shrey.buddy.ui.friendlyDay
import com.shrey.buddy.ui.theme.BuddyColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val shortDate = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private fun kg(v: Double) = "%.1f kg".format(v)
private fun bandColor(label: String) = when (label) {
    "Healthy" -> BuddyColors.Lime
    "Underweight" -> BuddyColors.Tasks
    "Overweight" -> BuddyColors.Fitness
    else -> BuddyColors.Red
}

@Composable
fun BodyScreen(hvm: HealthViewModel, onBack: () -> Unit) {
    val profile by hvm.profile.collectAsStateWithLifecycle()
    val weights by hvm.weights.collectAsStateWithLifecycle()
    val measures by hvm.measures.collectAsStateWithLifecycle()
    var logging by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf(false) }
    var measuring by remember { mutableStateOf(false) }
    var range by rememberSaveable { mutableStateOf(90) }
    val today = LocalDate.now()
    val points = weights.mapNotNull { w -> w.date?.let { it to w.data.kg } }
    val latest = weights.lastOrNull()
    val rate = BodyMath.weeklyRate(points)

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                Text("Body", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { editingProfile = true }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit profile") }
            }
        }

        if (profile.heightCm == null) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Set up your profile", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Height, age and sex make BMI, calorie needs and the step / run / workout calorie estimates accurate.",
                        style = MaterialTheme.typography.bodySmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 4.dp),
                    )
                    Button(
                        onClick = { editingProfile = true },
                        colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.Background),
                        modifier = Modifier.padding(top = 10.dp),
                    ) { Text("Add height & details") }
                }
            }
        }

        // ---- Current weight, BMI, goal ----
        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text("Weight", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                        Text(latest?.let { kg(it.data.kg) } ?: "–", style = MaterialTheme.typography.displaySmall.numeric())
                        latest?.date?.let { Text("Logged ${friendlyDay(it, today).lowercase()}", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary) }
                    }
                    Button(
                        onClick = { logging = true },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BuddyColors.Fitness, contentColor = BuddyColors.Background),
                    ) {
                        Icon(Icons.Rounded.Add, null)
                        Text("Log", modifier = Modifier.padding(start = 4.dp))
                    }
                }
                rate?.let {
                    Text(
                        (if (it < 0) "▼ " else "▲ ") + "%.2f kg / week (4-week trend)".format(abs(it)),
                        style = MaterialTheme.typography.labelLarge,
                        color = BuddyColors.TextSecondary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }

                val h = profile.heightCm
                if (latest != null && h != null) {
                    val bmi = BodyMath.bmi(latest.data.kg, h)
                    val band = BodyMath.band(bmi)
                    Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("BMI %.1f".format(bmi), style = MaterialTheme.typography.titleMedium.numeric())
                        Text("  ${band.label}", style = MaterialTheme.typography.titleMedium, color = bandColor(band.label))
                    }
                    BmiGauge(bmi, Modifier.padding(top = 8.dp))
                    val healthy = BodyMath.healthyRangeKg(h)
                    Text(
                        "Healthy for your height: %.0f–%.0f kg (Asian BMI cut-offs)".format(healthy.start, healthy.endInclusive),
                        style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 6.dp),
                    )
                }

                profile.goalWeightKg?.let { goal ->
                    latest?.let { l ->
                        val left = l.data.kg - goal
                        val eta = BodyMath.goalEta(l.data.kg, goal, rate)
                        Text(
                            "Goal ${kg(goal)} · " + (if (abs(left) < 0.2) "reached 🎉" else "%.1f kg to go".format(abs(left))) +
                                (eta?.let { " · on track for ${it.format(shortDate)}" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = BuddyColors.Lime,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
        }

        // ---- Trend chart ----
        item {
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Trend", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    listOf(30 to "1M", 90 to "3M", 365 to "1Y", 3650 to "All").forEach { (d, label) ->
                        FilterChip(
                            selected = range == d,
                            onClick = { range = d },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BuddyColors.Fitness.copy(alpha = 0.25f), selectedLabelColor = BuddyColors.TextPrimary),
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                val shown = points.filter { !it.first.isBefore(today.minusDays(range.toLong())) }
                if (shown.size < 2) {
                    EmptyNote("Log your weight a few times to see the trend line.", Modifier.padding(top = 8.dp))
                } else {
                    WeightChart(shown, profile.goalWeightKg, Modifier.padding(top = 12.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(shown.first().first.format(shortDate), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                        Text("• weigh-ins  — trend", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                        Text(shown.last().first.format(shortDate), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
                    }
                }
            }
        }

        // ---- Calories ----
        if (latest != null && profile.heightCm != null && profile.birthYear != null) {
            item {
                val bmr = BodyMath.bmr(latest.data.kg, profile)
                val maintain = BodyMath.maintenanceKcal(latest.data.kg, profile)
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Daily calories", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("At rest", bmr?.let { "$it kcal" } ?: "–")
                        Stat("Maintain", maintain?.let { "$it kcal" } ?: "–")
                        Stat("Lose 0.5 kg/wk", maintain?.let { "${it - 550} kcal" } ?: "–")
                    }
                    Text(
                        "Based on your ${activityLabel(profile.activity).lowercase()} activity level.",
                        style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        // ---- Measurements ----
        item {
            val last = measures.lastOrNull()
            val first = measures.firstOrNull()
            BuddyCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Measurements", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { measuring = true }, shape = RoundedCornerShape(14.dp)) { Text("Update") }
                }
                if (last == null) {
                    EmptyNote("Track waist, chest, arms and more — progress shows even when the scale doesn't move.", Modifier.padding(top = 8.dp))
                } else {
                    last.date?.let { Text("Last measured ${friendlyDay(it, today).lowercase()}", style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary) }
                    val rows = listOf<Pair<String, (BodyMeasure) -> Double?>>(
                        "Waist" to { it.waistCm }, "Chest" to { it.chestCm }, "Hips" to { it.hipsCm },
                        "Arm" to { it.armCm }, "Thigh" to { it.thighCm }, "Neck" to { it.neckCm },
                    )
                    rows.forEach { (label, get) ->
                        val now = get(last.data) ?: return@forEach
                        val was = first?.let { get(it.data) }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("%.1f cm".format(now), style = MaterialTheme.typography.bodyMedium.numeric())
                            if (was != null && first != last && abs(now - was) >= 0.1) {
                                Text(
                                    "  %+.1f".format(now - was),
                                    style = MaterialTheme.typography.bodyMedium.numeric(),
                                    color = BuddyColors.TextTertiary,
                                )
                            }
                        }
                    }
                    BodyMath.navyBodyFat(profile, last.data.waistCm, last.data.neckCm, last.data.hipsCm)?.let { bf ->
                        Text(
                            "Estimated body fat ≈ %.0f%% (US Navy method)".format(bf),
                            style = MaterialTheme.typography.bodyMedium, color = BuddyColors.Lime, modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                }
            }
        }

        // ---- History ----
        if (weights.isNotEmpty()) {
            item {
                BuddyCard(Modifier.fillMaxWidth()) {
                    Text("Weigh-ins", style = MaterialTheme.typography.titleMedium)
                    weights.takeLast(20).reversed().forEachIndexed { i, w ->
                        val prev = weights.getOrNull(weights.size - 2 - i)
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(w.date?.let { friendlyDay(it, today) } ?: "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            prev?.let {
                                val d = w.data.kg - it.data.kg
                                if (abs(d) >= 0.05) Text("%+.1f  ".format(d), style = MaterialTheme.typography.bodySmall.numeric(), color = BuddyColors.TextTertiary)
                            }
                            Text(kg(w.data.kg), style = MaterialTheme.typography.bodyMedium.numeric())
                            Text("  ✕", color = BuddyColors.TextTertiary, modifier = Modifier.clickable { hvm.deleteWeight(w.id) })
                        }
                    }
                }
            }
        }
    }

    if (logging) WeightSheet(latest?.data?.kg, onDismiss = { logging = false }) { e, day -> hvm.saveWeight(e, day); logging = false }
    if (editingProfile) ProfileSheet(profile, onDismiss = { editingProfile = false }) { hvm.saveProfile(it); editingProfile = false }
    if (measuring) MeasureSheet(measures.lastOrNull()?.data, onDismiss = { measuring = false }) { m, day -> hvm.saveMeasure(m, day); measuring = false }
}

fun activityLabel(key: String) = when (key) {
    "sedentary" -> "Desk job, little exercise"
    "light" -> "Light (1–3 workouts/week)"
    "moderate" -> "Moderate (3–5/week)"
    "active" -> "Active (6–7/week)"
    else -> "Very active (hard daily training)"
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall.numeric())
        Text(label, style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}

/** Coloured BMI scale (15–35) with a marker. */
@Composable
private fun BmiGauge(bmi: Double, modifier: Modifier) {
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val min = 15.0
        val max = 35.0
        fun x(v: Double) = (((v - min) / (max - min)).coerceIn(0.0, 1.0) * size.width).toFloat()
        BodyMath.bands.forEach { b ->
            val from = x(maxOf(b.from, min))
            val to = x(minOf(b.to, max))
            drawRoundRect(bandColor(b.label).copy(alpha = 0.55f), Offset(from, 4.dp.toPx()), Size(to - from - 2.dp.toPx(), 6.dp.toPx()), CornerRadius(3.dp.toPx()))
        }
        val mx = x(bmi)
        drawCircle(Color.White, 7.dp.toPx(), Offset(mx, size.height / 2))
        drawCircle(bandColor(BodyMath.band(bmi).label), 4.dp.toPx(), Offset(mx, size.height / 2))
    }
}

/** Raw weigh-ins as dots, the smoothed trend as a line, the goal as a dashed line. */
@Composable
private fun WeightChart(points: List<Pair<LocalDate, Double>>, goal: Double?, modifier: Modifier) {
    val trend = BodyMath.trend(points)
    val values = points.map { it.second } + trend.map { it.second } + listOfNotNull(goal)
    val lo = values.min() - 0.5
    val hi = values.max() + 0.5
    val start = points.first().first.toEpochDay()
    val span = (points.last().first.toEpochDay() - start).coerceAtLeast(1)
    Canvas(modifier.fillMaxWidth().height(160.dp)) {
        fun x(d: LocalDate) = (d.toEpochDay() - start).toFloat() / span * size.width
        fun y(v: Double) = (size.height - (v - lo) / (hi - lo) * size.height).toFloat()
        goal?.let {
            drawLine(BuddyColors.Lime.copy(alpha = 0.6f), Offset(0f, y(it)), Offset(size.width, y(it)), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
        }
        points.forEach { (d, v) -> drawCircle(BuddyColors.TextTertiary, 3.dp.toPx(), Offset(x(d), y(v))) }
        val path = Path()
        trend.forEachIndexed { i, (d, v) -> if (i == 0) path.moveTo(x(d), y(v)) else path.lineTo(x(d), y(v)) }
        drawPath(path, BuddyColors.Fitness, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun WeightSheet(last: Double?, onDismiss: () -> Unit, onSave: (WeightEntry, LocalDate) -> Unit) {
    var text by remember { mutableStateOf(last?.let { "%.1f".format(it) } ?: "") }
    var fat by remember { mutableStateOf("") }
    var day by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    val value = text.replace(',', '.').toDoubleOrNull()
    FormSheet(
        title = "Log weight",
        onDismiss = onDismiss,
        onSave = { onSave(WeightEntry(kg = value!!, bodyFatPct = fat.replace(',', '.').toDoubleOrNull()), day ?: LocalDate.now()) },
        saveEnabled = value != null && value in 25.0..300.0,
        accent = BuddyColors.Fitness,
    ) {
        Field(text, { text = it }, "Weight (kg)", keyboard = KeyboardType.Decimal, accent = BuddyColors.Fitness)
        Spacer(Modifier.height(10.dp))
        Field(fat, { fat = it }, "Body fat % (optional, from a smart scale)", keyboard = KeyboardType.Decimal, accent = BuddyColors.Fitness)
        DateField("Date", day, { day = it }, allowClear = false)
        Text("Tip: weigh in the morning after the bathroom — the trend line evens out the daily ups and downs.",
            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}

@Composable
private fun ProfileSheet(initial: BodyProfile, onDismiss: () -> Unit, onSave: (BodyProfile) -> Unit) {
    var height by remember { mutableStateOf(initial.heightCm?.let { "%.0f".format(it) } ?: "") }
    var year by remember { mutableStateOf(initial.birthYear?.toString() ?: "") }
    var sex by remember { mutableStateOf(initial.sex ?: "male") }
    var goal by remember { mutableStateOf(initial.goalWeightKg?.let { "%.1f".format(it) } ?: "") }
    var activity by remember { mutableStateOf(initial.activity) }
    FormSheet(
        title = "Your profile",
        onDismiss = onDismiss,
        onSave = {
            onSave(
                initial.copy(
                    heightCm = height.toDoubleOrNull()?.takeIf { it in 100.0..250.0 },
                    birthYear = year.toIntOrNull()?.takeIf { it in 1920..LocalDate.now().year },
                    sex = sex,
                    goalWeightKg = goal.replace(',', '.').toDoubleOrNull()?.takeIf { it in 25.0..300.0 },
                    activity = activity,
                ),
            )
        },
        accent = BuddyColors.Fitness,
    ) {
        Row {
            Field(height, { height = it }, "Height (cm)", Modifier.weight(1f), keyboard = KeyboardType.Number, accent = BuddyColors.Fitness)
            Spacer(Modifier.width(10.dp))
            Field(year, { year = it }, "Birth year", Modifier.weight(1f), keyboard = KeyboardType.Number, accent = BuddyColors.Fitness)
        }
        Spacer(Modifier.height(12.dp))
        Choices(listOf("male" to "Male", "female" to "Female"), sex, { sex = it })
        Spacer(Modifier.height(12.dp))
        Field(goal, { goal = it }, "Goal weight (kg, optional)", keyboard = KeyboardType.Decimal, accent = BuddyColors.Fitness)
        Text("Activity level", style = MaterialTheme.typography.titleSmall, color = BuddyColors.TextSecondary, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        Choices(
            listOf("sedentary", "light", "moderate", "active", "very_active").map { it to activityLabel(it) },
            activity, { activity = it },
        )
    }
}

@Composable
private fun MeasureSheet(last: BodyMeasure?, onDismiss: () -> Unit, onSave: (BodyMeasure, LocalDate) -> Unit) {
    fun s(v: Double?) = v?.let { "%.1f".format(it) } ?: ""
    fun d(v: String) = v.replace(',', '.').toDoubleOrNull()?.takeIf { it in 10.0..250.0 }
    var waist by remember { mutableStateOf(s(last?.waistCm)) }
    var chest by remember { mutableStateOf(s(last?.chestCm)) }
    var hips by remember { mutableStateOf(s(last?.hipsCm)) }
    var arm by remember { mutableStateOf(s(last?.armCm)) }
    var thigh by remember { mutableStateOf(s(last?.thighCm)) }
    var neck by remember { mutableStateOf(s(last?.neckCm)) }
    FormSheet(
        title = "Measurements (cm)",
        onDismiss = onDismiss,
        onSave = { onSave(BodyMeasure(d(waist), d(chest), d(hips), d(arm), d(thigh), d(neck)), LocalDate.now()) },
        accent = BuddyColors.Fitness,
    ) {
        @Composable
        fun pair(a: String, av: String, aset: (String) -> Unit, b: String, bv: String, bset: (String) -> Unit) {
            Row(Modifier.padding(bottom = 10.dp)) {
                Field(av, aset, a, Modifier.weight(1f), keyboard = KeyboardType.Decimal, accent = BuddyColors.Fitness)
                Spacer(Modifier.width(10.dp))
                Field(bv, bset, b, Modifier.weight(1f), keyboard = KeyboardType.Decimal, accent = BuddyColors.Fitness)
            }
        }
        pair("Waist", waist, { waist = it }, "Chest", chest, { chest = it })
        pair("Hips", hips, { hips = it }, "Arm", arm, { arm = it })
        pair("Thigh", thigh, { thigh = it }, "Neck", neck, { neck = it })
        Text("Waist at the navel, neck below the Adam's apple — those two (plus hips for women) give a body-fat estimate.",
            style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
    }
}

/** Compact body summary for the Health tab. */
@Composable
fun BodySummaryCard(hvm: HealthViewModel, onOpen: () -> Unit) {
    val profile by hvm.profile.collectAsStateWithLifecycle()
    val weights by hvm.weights.collectAsStateWithLifecycle()
    val latest = weights.lastOrNull()
    val rate = BodyMath.weeklyRate(weights.mapNotNull { w -> w.date?.let { it to w.data.kg } })
    BuddyCard(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Body", style = MaterialTheme.typography.labelLarge, color = BuddyColors.TextSecondary)
                Text(latest?.let { kg(it.data.kg) } ?: "Log your weight", style = MaterialTheme.typography.titleLarge.numeric())
                val bits = listOfNotNull(
                    profile.heightCm?.let { h -> latest?.let { val b = BodyMath.bmi(it.data.kg, h); "BMI %.1f · %s".format(b, BodyMath.band(b).label) } },
                    rate?.let { "%+.2f kg/wk".format(it) },
                    profile.goalWeightKg?.let { "goal ${kg(it)}" },
                )
                if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = BuddyColors.TextTertiary)
            }
            Box { Text("›", style = MaterialTheme.typography.headlineSmall, color = BuddyColors.TextTertiary) }
        }
    }
}
