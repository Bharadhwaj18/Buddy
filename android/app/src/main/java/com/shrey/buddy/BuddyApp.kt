package com.shrey.buddy

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.shrey.buddy.capture.CaptureNotifier
import com.shrey.buddy.data.capture.CaptureRepository
import com.shrey.buddy.data.db.BuddyDatabase
import com.shrey.buddy.data.health.HealthRepository
import com.shrey.buddy.data.sync.SyncEngine
import com.shrey.buddy.data.sync.SyncScheduler
import com.shrey.buddy.data.sync.DailyWorker
import com.shrey.buddy.data.tasks.TaskRepository
import com.shrey.buddy.finance.FinanceRepository
import com.shrey.buddy.data.items.ItemStore
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.storage.Storage
import com.shrey.buddy.update.Updater
import kotlin.time.Duration.Companion.seconds
import com.shrey.buddy.ai.BuddyAi
import com.shrey.buddy.ai.Briefings
import com.shrey.buddy.reminders.ReminderNotifications
import com.shrey.buddy.reminders.ReminderScheduler
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

class BuddyApp : Application() {
    lateinit var container: AppContainer
        private set

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        SyncScheduler.schedulePeriodic(this)
        CaptureNotifier.ensureChannel(this)
        DailyWorker.schedule(this, "daily", replace = false)
        DailyWorker.schedule(this, "weekly", replace = false)
        DailyWorker.schedule(this, DailyWorker.MORNING_TASKS, replace = false)
        DailyWorker.schedule(this, DailyWorker.EVENING, replace = false)
        ReminderNotifications.ensureChannels(this)
        com.shrey.buddy.data.health.StepWorker.schedule(this)
        // Section widgets follow data changes (items or transactions), at most every few seconds.
        ReminderScheduler.scope.launch {
            kotlinx.coroutines.flow.combine(
                container.db.itemDao().observeLastChange(),
                container.finance.observeTransactions(),
            ) { a, b -> a to b.size }
                .debounce(3_000)
                .collect { com.shrey.buddy.capture.SectionWidgets.refreshAll(this@BuddyApp) }
        }
        // Warm the exercise library in the background so the picker opens instantly.
        ReminderScheduler.scope.launch { runCatching { com.shrey.buddy.health.ExerciseLibrary.all(this@BuddyApp) } }
        ReminderScheduler.scope.launch { runCatching { com.shrey.buddy.capture.WidgetGallery.publishPreviews(this@BuddyApp) } }
        // Any task change (edit here, or a sync pull) re-arms the next reminder alarm.
        ReminderScheduler.scope.launch {
            container.db.taskDao().observeActive().debounce(300).collect {
                ReminderScheduler.refresh(this@BuddyApp)
                com.shrey.buddy.capture.TodayWidget.refresh(this@BuddyApp)
            }
        }
    }
}

/** Manual DI for M0. Swap for Hilt once the graph grows. */
class AppContainer(val context: Context) {
    val supabase = createSupabaseClient(
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    ) {
        // AI answers that look things up take longer than the 10 s default.
        requestTimeout = 60.seconds
        install(Auth)
        install(Postgrest)
        install(Functions)
        install(Storage)
    }

    val updater = Updater(context, supabase)

    val ai = BuddyAi(supabase)

    val db: BuddyDatabase = Room.databaseBuilder(context, BuddyDatabase::class.java, "personal.db").build()

    val syncEngine = SyncEngine(db, supabase)

    val tasks = TaskRepository(
        db,
        onLocalChange = { SyncScheduler.syncSoon(context) },
        // "Keep calling until I answer" is stored on the phone; copies of a task keep it.
        onSpawned = { original, copy ->
            if (com.shrey.buddy.reminders.ReminderSettings.isNag(context, original.id)) {
                com.shrey.buddy.reminders.ReminderSettings.setNag(context, copy.id, true)
            }
        },
    )

    val health = HealthRepository(context)

    val steps by lazy { com.shrey.buddy.data.health.StepLog(context, items, health) }

    val healthSync by lazy { com.shrey.buddy.data.health.HealthSync(context, items, health) }

    val coach by lazy { com.shrey.buddy.data.health.Coach(this) }

    val finance = FinanceRepository(
        db,
        context.getSharedPreferences("finance", Context.MODE_PRIVATE),
    ) { SyncScheduler.syncSoon(context) }

    val captures = CaptureRepository(db, context, finance)

    val items = ItemStore(db) { SyncScheduler.syncSoon(context) }

    /** Fuel transactions ↔ vehicle fuel logs. */
    val fuel = com.shrey.buddy.finance.FuelLink(items, context).also { link -> finance.onMerged = { old, new -> link.relink(old, new) } }

    val briefings by lazy { Briefings(context, this) }

    val planning by lazy { com.shrey.buddy.finance.PlanningService(context, this) }
}
