package com.shrey.buddy.data.health

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shrey.buddy.BuddyApp
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Every ~15 min: sample the phone's step sensor and pull Health Connect. Once a day: 30-day backfill. */
class StepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as BuddyApp).container
        val prefs = applicationContext.getSharedPreferences("steps", Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        runCatching {
            if (prefs.getString("backfilledOn", null) != today) {
                c.steps.backfill()
                prefs.edit().putString("backfilledOn", today).apply()
            } else {
                c.steps.refresh()
            }
        }
        // Watch data (sleep, heart, workouts, weight) about once an hour; 30 days back once a day.
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("healthSyncAt", 0) > 55 * 60_000) {
            runCatching { c.healthSync.sync(days = if (prefs.getString("healthBackfilledOn", null) != today) 30 else 2) }
            prefs.edit().putLong("healthSyncAt", now).putString("healthBackfilledOn", today).apply()
        }
        runCatching { Nudges.check(applicationContext) }
        runCatching { com.shrey.buddy.data.sync.DailyWorker.catchUp(applicationContext) }
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "steps",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<StepWorker>(15, TimeUnit.MINUTES).build(),
            )
        }
    }
}
