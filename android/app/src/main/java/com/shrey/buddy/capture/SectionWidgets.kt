package com.shrey.buddy.capture

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.MainActivity
import com.shrey.buddy.data.health.HealthData
import com.shrey.buddy.data.health.Nudges
import com.shrey.buddy.data.items.Habit
import com.shrey.buddy.data.items.HabitLog
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.TrainingPlan
import com.shrey.buddy.data.health.Coach
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// ---- Shared look ----

private val Bg = Color(0xFF17191E)
private val Tile = Color(0xFF272B32)
private val Lime = ColorProvider(Color(0xFFC6F432))
private val White = ColorProvider(Color(0xFFF2F3F5))
private val Grey = ColorProvider(Color(0xFF9AA0AA))
private val Orange = ColorProvider(Color(0xFFFF8A4C))
private val Blue = ColorProvider(Color(0xFF5AC8FA))

private fun open(context: Context, where: String) =
    actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, where))

/** Tapping anywhere that isn't a button opens the section. */
@Composable
private fun Frame(title: String, accent: ColorProvider, onTitle: Action, content: @Composable ColumnScope.() -> Unit) {
    Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(24.dp).clickable(onTitle).padding(14.dp)) {
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TextStyle(color = accent, fontWeight = FontWeight.Bold, fontSize = 15.sp), modifier = GlanceModifier.defaultWeight())
            Text("›", style = TextStyle(color = Grey, fontSize = 16.sp))
        }
        Spacer(GlanceModifier.height(8.dp))
        content()
    }
}

/** Small = roughly a 2-row or 2-column widget: show the headline and one action only. */
@Composable
private fun isShort() = androidx.glance.LocalSize.current.height < 150.dp
@Composable
private fun isNarrow() = androidx.glance.LocalSize.current.width < 210.dp

@Composable
private fun Pill(label: String, action: Action, fill: Color = Tile, text: ColorProvider = White) {
    Box(
        // 44 dp tall: comfortable to hit with a thumb.
        GlanceModifier.height(44.dp).background(fill).cornerRadius(16.dp).padding(horizontal = 14.dp).clickable(action),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = TextStyle(color = text, fontWeight = FontWeight.Bold, fontSize = 13.sp)) }
}

/** Refreshes every section widget (called when data changes). */
object SectionWidgets {
    suspend fun refreshAll(context: Context) {
        runCatching { HealthWidget().updateAll(context) }
        runCatching { MoneyWidget().updateAll(context) }
        runCatching { FitnessWidget().updateAll(context) }
        runCatching { HabitsWidget().updateAll(context) }
        runCatching { TodayWidget().updateAll(context) }
        runCatching { BuddyWidget().updateAll(context) }
    }
}

// ---- Health ----

class HealthWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact
    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideGlance(context, PreviewId)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as BuddyApp).container
        val h = HealthData.load(c.items)
        val today = LocalDate.now()
        val goal = context.getSharedPreferences("steps", Context.MODE_PRIVATE).getInt("goal", 10_000)
        val steps = h.steps[today]?.steps ?: 0
        val sleep = h.sleep[today] ?: h.sleep[today.minusDays(1)]
        val water = h.water[today] ?: 0
        val waterGoal = Nudges.waterGoalMl(context, h.latestKg)
        val rhr = h.heart.entries.maxByOrNull { it.key }?.value?.restingBpm
        provideContent {
            Frame("Health", Orange, open(context, "health")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%,d".format(steps), style = TextStyle(color = White, fontWeight = FontWeight.Bold, fontSize = 24.sp))
                    Text("  / %,d steps".format(goal), style = TextStyle(color = Grey, fontSize = 12.sp))
                }
                LinearProgressIndicator(
                    progress = (steps.toFloat() / goal).coerceIn(0f, 1f),
                    color = Orange, backgroundColor = ColorProvider(Color(0xFF272B32)),
                    modifier = GlanceModifier.fillMaxWidth().height(6.dp),
                )
                if (!isShort()) {
                Spacer(GlanceModifier.height(10.dp))
                Row(GlanceModifier.fillMaxWidth()) {
                    Stat("Sleep", sleep?.let { "${it.minutes / 60}h ${it.minutes % 60}m" } ?: "–", GlanceModifier.defaultWeight())
                    Stat("Water", "%.1f/%.1f L".format(water / 1000.0, waterGoal / 1000.0), GlanceModifier.defaultWeight())
                    if (!isNarrow()) Stat("Rest ♥", rhr?.let { "$it" } ?: "–", GlanceModifier.defaultWeight())
                }
                }
                Spacer(GlanceModifier.defaultWeight())
                Row {
                    Pill("💧 +250", actionRunCallback<AddWaterAction>(actionParametersOf(WATER_ML to 250)), fill = Color(0xFF1E3A4A), text = Blue)
                    if (!isNarrow()) {
                        Spacer(GlanceModifier.width(8.dp))
                        Pill("+500 ml", actionRunCallback<AddWaterAction>(actionParametersOf(WATER_ML to 500)), fill = Color(0xFF1E3A4A), text = Blue)
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: GlanceModifier) {
    Column(modifier) {
        Text(value, style = TextStyle(color = White, fontWeight = FontWeight.Bold, fontSize = 14.sp))
        Text(label, style = TextStyle(color = Grey, fontSize = 11.sp))
    }
}

val WATER_ML = ActionParameters.Key<Int>("ml")

/** Stand-in id for drawing a widget's picker preview (our widgets don't use the id). */
internal object PreviewId : GlanceId

class AddWaterAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        Nudges.addWater(context, parameters[WATER_ML] ?: 250)
        HealthWidget().updateAll(context)
    }
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = HealthWidget() }

// ---- Money ----

class MoneyWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact
    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideGlance(context, PreviewId)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as BuddyApp).container
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val month = YearMonth.now()
        val excluded = c.db.accountDao().all().filter { !it.includeInTotals }.map { it.id }.toSet()
        val debits = c.db.transactionDao().all().filter { it.direction == "debit" && !it.isTransfer && it.accountId !in excluded }
        fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val spentMonth = debits.filter { YearMonth.from(day(it.occurredAt)) == month }.sumOf { it.amountMinor }
        val spentToday = debits.filter { day(it.occurredAt) == today }.sumOf { it.amountMinor }
        val recent = c.db.transactionDao().all().filter { !it.isTransfer }.sortedByDescending { it.occurredAt }.take(3)
        val inr = NumberFormat.getCurrencyInstance(Locale.Builder().setLanguage("en").setRegion("IN").build()).apply { maximumFractionDigits = 0 }
        provideContent {
            Frame("Money", Lime, open(context, "money")) {
                Text(inr.format(spentMonth / 100.0), style = TextStyle(color = White, fontWeight = FontWeight.Bold, fontSize = 24.sp))
                Text("spent in ${month.month.name.lowercase().replaceFirstChar(Char::uppercase)} · ${inr.format(spentToday / 100.0)} today", style = TextStyle(color = Grey, fontSize = 12.sp))
                Spacer(GlanceModifier.height(8.dp))
                if (!isShort()) recent.forEach { t ->
                    Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text((t.payee ?: t.category ?: "Transaction").take(22), style = TextStyle(color = White, fontSize = 12.sp), modifier = GlanceModifier.defaultWeight())
                        Text(
                            (if (t.direction == "credit") "+" else "−") + inr.format(t.amountMinor / 100.0),
                            style = TextStyle(color = if (t.direction == "credit") Lime else White, fontSize = 12.sp),
                        )
                    }
                }
                Spacer(GlanceModifier.defaultWeight())
                Pill("＋ Add expense", actionStartActivity(Intent(context, QuickCaptureActivity::class.java)), fill = Color(0xFFC6F432), text = ColorProvider(Color(0xFF0E0F12)))
            }
        }
    }
}

class MoneyWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = MoneyWidget() }

// ---- Fitness ----

class FitnessWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact
    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideGlance(context, PreviewId)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val c = (context.applicationContext as BuddyApp).container
        val h = HealthData.load(c.items)
        val today = LocalDate.now()
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val week = h.workouts.filter { it.date != null && !it.date!!.isBefore(monday) }
        val km = week.mapNotNull { it.data.run?.distanceM }.sum() / 1000
        val plan = c.items.all(Kinds.TRAINING_PLAN, TrainingPlan.serializer()).firstOrNull { it.data.active }?.data
        val todays = plan?.let { p ->
            val start = runCatching { LocalDate.parse(p.startDate) }.getOrNull() ?: return@let null
            val wk = (ChronoUnit.WEEKS.between(start, today) + 1).toInt()
            p.weeks.firstOrNull { it.week == wk }?.sessions?.firstOrNull { Coach.dayIndex(it.day) == today.dayOfWeek.value - 1 && it.type != "rest" }
        }
        provideContent {
            Frame("Fitness", Orange, open(context, "health")) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.1f km".format(km), style = TextStyle(color = White, fontWeight = FontWeight.Bold, fontSize = 22.sp))
                    Text("  · ${week.size} session${if (week.size == 1) "" else "s"} this week", style = TextStyle(color = Grey, fontSize = 12.sp))
                }
                Spacer(GlanceModifier.height(6.dp))
                Text(
                    todays?.let { "Today: ${Coach.sessionTitle(it)}" } ?: if (plan != null) "Rest day in your plan" else "No plan — ask the Coach for one",
                    style = TextStyle(color = if (todays != null) Lime else Grey, fontSize = 12.sp), maxLines = 2,
                    modifier = GlanceModifier.clickable(open(context, "coach")),
                )
                Spacer(GlanceModifier.defaultWeight())
                Row {
                    Pill("🏃 Run", open(context, "run"), fill = Color(0xFFFF8A4C), text = ColorProvider(Color(0xFF0E0F12)))
                    Spacer(GlanceModifier.width(8.dp))
                    Pill("🏋️ Gym", open(context, "workout"))
                }
            }
        }
    }
}

class FitnessWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = FitnessWidget() }

// ---- Habits ----

class HabitsWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact
    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideGlance(context, PreviewId)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val items = (context.applicationContext as BuddyApp).container.items
        val today = LocalDate.now()
        val habits = items.all(Kinds.HABIT, Habit.serializer()).filter { !it.data.archived }.sortedBy { it.entity.createdAt }
        val logs = items.all(Kinds.HABIT_LOG, HabitLog.serializer()).filter { it.date == today }.mapNotNull { it.refId }.toSet()
        provideContent {
            Frame("Habits today", Lime, open(context, "health")) {
                if (habits.isEmpty()) Text("Add habits in Buddy → Health", style = TextStyle(color = Grey, fontSize = 12.sp))
                habits.take(6).forEach { hb ->
                    val done = hb.id in logs
                    val auto = hb.data.auto != null
                    Row(
                        GlanceModifier.fillMaxWidth().height(40.dp)
                            .let { if (auto) it else it.clickable(actionRunCallback<ToggleHabitAction>(actionParametersOf(HABIT_ID to hb.id))) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            GlanceModifier.size(28.dp).cornerRadius(14.dp).background(if (done) Color(0xFFC6F432) else Tile),
                            contentAlignment = Alignment.Center,
                        ) { Text(if (done) "✓" else "", style = TextStyle(color = ColorProvider(Color(0xFF0E0F12)), fontSize = 12.sp)) }
                        Spacer(GlanceModifier.width(10.dp))
                        Text("${hb.data.emoji} ${hb.data.name}" + if (auto) " (auto)" else "", style = TextStyle(color = White, fontSize = 14.sp), maxLines = 1)
                    }
                }
            }
        }
    }

    companion object {
        val HABIT_ID = ActionParameters.Key<String>("habitId")
    }
}

/** Same deterministic id as the app, so ticking on the widget and in the app can't double up. */
class ToggleHabitAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val habitId = parameters[HabitsWidget.HABIT_ID] ?: return
        val items = (context.applicationContext as BuddyApp).container.items
        val day = LocalDate.now()
        val id = ItemStore.stableId("habitlog:$habitId:$day")
        if (items.get(id, HabitLog.serializer()) != null) items.delete(id)
        else items.put(Kinds.HABIT_LOG, HabitLog(1), HabitLog.serializer(), id = id, occurredOn = day, refId = habitId)
        HabitsWidget().updateAll(context)
    }
}

class HabitsWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget = HabitsWidget() }
