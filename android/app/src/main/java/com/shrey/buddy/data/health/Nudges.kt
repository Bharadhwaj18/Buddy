package com.shrey.buddy.data.health

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.shrey.buddy.BuddyApp
import com.shrey.buddy.MainActivity
import com.shrey.buddy.R
import com.shrey.buddy.data.items.ItemStore
import com.shrey.buddy.data.items.Kinds
import com.shrey.buddy.data.items.WaterDay
import com.shrey.buddy.data.items.WeightEntry
import com.shrey.buddy.reminders.ReminderScheduler
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Gentle daytime nudges: drink water (when behind on the goal) and move (after an hour without steps). */
object Nudges {
    private const val CHANNEL = "nudges"
    private const val WATER_ID = 71
    private const val MOVE_ID = 72
    const val ACTION_ADD_WATER = "com.shrey.buddy.ADD_WATER"

    private fun prefs(context: Context) = context.getSharedPreferences("nudges", Context.MODE_PRIVATE)

    fun waterOn(context: Context) = prefs(context).getBoolean("water", true)
    fun moveOn(context: Context) = prefs(context).getBoolean("move", true)
    fun setWater(context: Context, on: Boolean) = prefs(context).edit().putBoolean("water", on).apply()
    fun setMove(context: Context, on: Boolean) = prefs(context).edit().putBoolean("move", on).apply()

    /** Daily water goal: set by you, else ~35 ml per kg of body weight (rounded to a glass), else 2.5 L. */
    fun waterGoalMl(context: Context, kg: Double?): Int {
        val set = prefs(context).getInt("waterGoal", 0)
        if (set > 0) return set
        return kg?.let { ((it * 35) / 250).toInt().coerceIn(6, 16) * 250 } ?: 2500
    }
    fun setWaterGoal(context: Context, ml: Int) {
        prefs(context).edit().putInt("waterGoal", ml).apply()
        _goalVersion.value++
    }
    private val _goalVersion = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumped when the goal changes so every water view updates at once. */
    val goalVersion: kotlinx.coroutines.flow.StateFlow<Int> = _goalVersion

    /** Called by the step sampler whenever you've clearly moved. */
    fun movedNow(context: Context) = prefs(context).edit().putLong("lastMoveAt", System.currentTimeMillis()).apply()

    suspend fun addWater(context: Context, ml: Int, day: LocalDate = LocalDate.now()) {
        val items = (context.applicationContext as BuddyApp).container.items
        val id = ItemStore.stableId("water:$day")
        val now = items.get(id, WaterDay.serializer())?.data?.ml ?: 0
        items.put(Kinds.WATER, WaterDay((now + ml).coerceAtLeast(0)), WaterDay.serializer(), id = id, occurredOn = day)
    }

    suspend fun check(context: Context) {
        val now = LocalTime.now()
        if (now.hour !in 9..21) return
        val p = prefs(context)
        val t = System.currentTimeMillis()

        if (moveOn(context) && (context.applicationContext as BuddyApp).container.steps.hasSensorPermission()) {
            val lastMove = p.getLong("lastMoveAt", t).also { if (!p.contains("lastMoveAt")) movedNow(context) }
            if (t - lastMove > 60 * 60_000 && t - p.getLong("moveNudgeAt", 0) > 90 * 60_000) {
                notify(context, MOVE_ID, "Time to move 🚶", "You've been still for an hour — a 5-minute walk helps.", null)
                p.edit().putLong("moveNudgeAt", t).apply()
            }
        }

        if (waterOn(context)) {
            val items = (context.applicationContext as BuddyApp).container.items
            val kg = items.all(Kinds.WEIGHT, WeightEntry.serializer()).maxByOrNull { it.date ?: LocalDate.MIN }?.data?.kg
            val goal = waterGoalMl(context, kg)
            val had = items.get(ItemStore.stableId("water:${LocalDate.now()}"), WaterDay.serializer())?.data?.ml ?: 0
            // On pace = an even share of the goal across 9 am – 9 pm.
            val expected = goal * (now.hour - 8) / 13
            if (had < expected - 250 && t - p.getLong("waterNudgeAt", 0) > 110 * 60_000) {
                val add = PendingIntent.getBroadcast(
                    context, 73, Intent(context, NudgeReceiver::class.java).setAction(ACTION_ADD_WATER),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                notify(context, WATER_ID, "Drink some water 💧", "%.1f L of %.1f L so far today".format(had / 1000.0, goal / 1000.0), "+250 ml" to add)
                p.edit().putLong("waterNudgeAt", t).apply()
            }
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String, action: Pair<String, PendingIntent>?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Water & move nudges", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN, "health"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_buddy)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(45 * 60_000)
            .apply { action?.let { (label, intent) -> addAction(0, label, intent) } }
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }

    internal fun clearWater(context: Context) = NotificationManagerCompat.from(context).cancel(WATER_ID)
}

class NudgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Nudges.ACTION_ADD_WATER) return
        val pending = goAsync()
        ReminderScheduler.scope.launch {
            runCatching { Nudges.addWater(context, 250) }
            Nudges.clearWater(context)
            pending.finish()
        }
    }
}
