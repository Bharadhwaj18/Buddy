package com.shrey.buddy.capture

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
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
import com.shrey.buddy.data.db.TaskEntity
import com.shrey.buddy.data.tasks.isDone
import com.shrey.buddy.reminders.CheckIns
import com.shrey.buddy.reminders.formatTime
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Home-screen list of today's tasks and reminders, each with a tick. */
class TodayWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Exact
    override suspend fun providePreview(context: Context, widgetCategory: Int) = provideGlance(context, PreviewId)
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val all = (context.applicationContext as BuddyApp).container.db.taskDao().all()
        val rows = rowsFor(all)

        provideContent {
            val lime = ColorProvider(Color(0xFFC6F432))
            val white = ColorProvider(Color(0xFFF2F3F5))
            val grey = ColorProvider(Color(0xFF9AA0AA))
            val openTasks = actionStartActivity(Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "tasks"))
            Column(GlanceModifier.fillMaxSize().background(Color(0xFF17191E)).cornerRadius(24.dp).clickable(openTasks).padding(14.dp)) {
                Row(GlanceModifier.fillMaxWidth().clickable(openTasks), verticalAlignment = Alignment.CenterVertically) {
                    Text("Today", style = TextStyle(color = lime, fontWeight = FontWeight.Bold, fontSize = 16.sp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text(if (rows.isEmpty()) "all clear" else "${rows.size} left", style = TextStyle(color = grey, fontSize = 12.sp))
                    Spacer(GlanceModifier.defaultWeight())
                    Box(
                        GlanceModifier.size(40.dp).background(Color(0xFFC6F432)).cornerRadius(20.dp)
                            .clickable(actionStartActivity(Intent(context, QuickCaptureActivity::class.java))),
                        contentAlignment = Alignment.Center,
                    ) { Text("+", style = TextStyle(color = ColorProvider(Color(0xFF0E0F12)), fontWeight = FontWeight.Bold, fontSize = 18.sp)) }
                }
                Spacer(GlanceModifier.height(8.dp))
                if (rows.isEmpty()) {
                    Text("Nothing due. Enjoy the day ✨", style = TextStyle(color = grey, fontSize = 13.sp))
                } else {
                    LazyColumn {
                        items(rows, itemId = { it.task.id.hashCode().toLong() }) { row ->
                            Row(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                // A 36 dp tick: easy to hit without opening the app by mistake.
                                Box(
                                    GlanceModifier.size(36.dp).cornerRadius(18.dp).background(Color(0xFF272B32))
                                        .clickable(actionRunCallback<CompleteTaskAction>(actionParametersOf(TASK_ID to row.task.id))),
                                    contentAlignment = Alignment.Center,
                                ) { Text("✓", style = TextStyle(color = grey, fontSize = 16.sp)) }
                                Spacer(GlanceModifier.width(10.dp))
                                Column(GlanceModifier.defaultWeight().clickable(openTasks)) {
                                    Text(row.task.title, style = TextStyle(color = white, fontSize = 14.sp), maxLines = 1)
                                    row.detail?.let { Text(it, style = TextStyle(color = if (row.overdue) ColorProvider(Color(0xFFFF6B6B)) else grey, fontSize = 11.sp)) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private data class WidgetRow(val task: TaskEntity, val detail: String?, val overdue: Boolean)

    private fun rowsFor(all: List<TaskEntity>): List<WidgetRow> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        val remindersToday = all.filter {
            !it.isDone && it.remindAt != null && Instant.ofEpochMilli(it.remindAt).atZone(zone).toLocalDate() == today
        }
        return (CheckIns.today(all, today, zone) + remindersToday).distinctBy { it.id }
            .sortedBy { it.remindAt ?: it.dueAt ?: Long.MAX_VALUE }
            .take(12)
            .map { t ->
                val dueDay = t.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
                val overdue = dueDay != null && (dueDay.isBefore(today) || (!t.dueAllDay && t.dueAt!! < now))
                val detail = when {
                    t.remindAt != null && t.remindAt > now -> "⏰ ${formatTime(t.remindAt)}"
                    dueDay != null && dueDay.isBefore(today) -> "Overdue"
                    !t.dueAllDay && t.dueAt != null -> formatTime(t.dueAt)
                    else -> null
                }
                WidgetRow(t, detail, overdue)
            }
    }

    companion object {
        val TASK_ID = ActionParameters.Key<String>("taskId")
        suspend fun refresh(context: Context) = runCatching { TodayWidget().updateAll(context) }
    }
}

/** Tick from the widget: completes the task (repeats spawn their next one, like in the app). */
class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[TodayWidget.TASK_ID] ?: return
        val tasks = (context.applicationContext as BuddyApp).container.tasks
        tasks.get(id)?.takeIf { !it.isDone }?.let { tasks.save(it.copy(status = "done")) }
        TodayWidget.refresh(context)
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget()
}
