package com.shrey.buddy.data.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.MainActivity
import com.shrey.buddy.R
import com.shrey.buddy.data.items.Document
import com.shrey.buddy.data.items.Goal
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.Person
import com.shrey.buddy.data.items.Vehicle
import com.shrey.buddy.data.items.Wish
import com.shrey.buddy.ui.life.buildReminders
import com.shrey.buddy.reminders.CheckIns
import com.shrey.buddy.reminders.ReminderSettings
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * Morning briefing (07:30 daily, with today's reminders) and weekly review (Sunday 19:00).
 * Each run schedules the next one, so the time stays fixed regardless of when it last ran.
 */
class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val kind = inputData.getString(KEY_KIND) ?: "daily"
        val c = (applicationContext as BuddyApp).container
        // Morning plan / evening wrap-up: purely local, quick.
        if (kind == MORNING_TASKS || kind == EVENING) {
            try {
                val settings = ReminderSettings.get(applicationContext)
                val tasks = c.db.taskDao().all()
                if (kind == MORNING_TASKS && settings.morningPlan) CheckIns.morning(applicationContext, tasks)
                if (kind == EVENING && settings.eveningWrap) CheckIns.evening(applicationContext, tasks)
            } finally {
                schedule(applicationContext, kind)
            }
            return Result.success()
        }
        try {
            c.supabase.auth.sessionStatus.first { it !is SessionStatus.Initializing }
            if (c.supabase.auth.currentSessionOrNull() != null) {
                // A flaky morning sync mustn't cost you the briefing.
                runCatching { c.syncEngine.sync() }
                runCatching { c.planning.refreshHoldings(force = true) }
                runCatching { c.planning.runAutoLog() }
                runCatching { c.planning.checkBudgets() }
                val reminders = if (kind == "daily") {
                    buildReminders(
                        c.items.all(Kinds.PERSON, Person.serializer()), c.items.all(Kinds.DOCUMENT, Document.serializer()),
                        c.items.all(Kinds.VEHICLE, Vehicle.serializer()), c.items.all(Kinds.WISH, Wish.serializer()),
                        c.items.all(Kinds.GOAL, Goal.serializer()),
                    ).filter { !it.date.isAfter(LocalDate.now().plusDays(3)) }
                } else emptyList()
                // Sunday: the health report too (stored for the Coach screen).
                val health = if (kind == "weekly") runCatching { c.coach.weeklyReport() }.getOrNull() else null
                health?.let { r ->
                    notify("Your health week", (listOf(r.headline) + r.nextWeek.take(3).map { "• $it" }).joinToString("\n"), open = "coach", id = 49)
                }
                // The free AI tier is sometimes busy first thing: three goes, then a briefing written on the phone.
                var body: String? = null
                for (attempt in 1..3) {
                    body = runCatching { c.briefings.briefing(kind).body }.getOrNull()?.takeIf { it.isNotBlank() }
                    if (body != null) break
                    if (attempt < 3) kotlinx.coroutines.delay(20_000L * attempt)
                }
                if (body == null && kind == "daily") body = runCatching { c.briefings.localDaily() }.getOrNull()
                val reminderText = reminders.joinToString("\n") { "• ${it.title}" }
                val text = listOfNotNull(body, reminderText.ifBlank { null }).joinToString("\n\n")
                if (text.isNotBlank()) notify(if (kind == "weekly") "Your week" else "Good morning", text)
                if (kind == "daily") markDelivered(applicationContext)
            }
        } finally {
            schedule(applicationContext, kind)
        }
        return Result.success()
    }

    private fun notify(title: String, text: String, open: String = "insights", id: Int = if (title == "Your week") 46 else 45) {
        val ctx = applicationContext
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Briefings & reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            ctx, id, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, open), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(id, n)
    }

    companion object {
        private fun prefs(context: Context) = context.getSharedPreferences("briefings", Context.MODE_PRIVATE)
        private fun markDelivered(context: Context) = prefs(context).edit().putString("dailyOn", LocalDate.now().toString()).apply()

        /**
         * Safety net: Android sometimes delays or drops the 07:30 job (battery saver, a reboot, an app update).
         * Called every 15 minutes and whenever Buddy opens — if it's past 07:30 and today's briefing hasn't
         * arrived, run it now.
         */
        fun catchUp(context: Context) {
            val now = LocalDateTime.now()
            if (now.toLocalTime().isBefore(LocalTime.of(7, 35)) || now.hour >= 21) return
            if (prefs(context).getString("dailyOn", null) == now.toLocalDate().toString()) return
            val request = OneTimeWorkRequestBuilder<DailyWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_KIND to "daily"))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("briefing-daily-catchup", ExistingWorkPolicy.KEEP, request)
        }

        private const val CHANNEL = "briefings"
        private const val KEY_KIND = "kind"

        const val MORNING_TASKS = "morning_tasks"
        const val EVENING = "evening"

        /** Schedules the next run of [kind]: "daily" 07:30, "weekly" Sunday 19:00, morning plan 08:00, evening wrap-up 21:00. */
        fun schedule(context: Context, kind: String, replace: Boolean = true) {
            val now = LocalDateTime.now()
            val next = if (kind == "weekly") {
                var t = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(LocalTime.of(19, 0))
                if (!t.isAfter(now)) t = t.plusWeeks(1)
                t
            } else {
                val at = when (kind) {
                    MORNING_TASKS -> LocalTime.of(8, 0)
                    EVENING -> LocalTime.of(21, 0)
                    else -> LocalTime.of(7, 30)
                }
                var t = now.toLocalDate().atTime(at)
                if (!t.isAfter(now)) t = t.plusDays(1)
                t
            }
            val local = kind == MORNING_TASKS || kind == EVENING
            val request = OneTimeWorkRequestBuilder<DailyWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(if (local) NetworkType.NOT_REQUIRED else NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_KIND to kind))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "briefing-$kind",
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
