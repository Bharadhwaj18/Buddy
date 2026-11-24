package com.shrey.buddy.desktop

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** A reminder showing on screen. [base] is the task's remind_at (what acks are keyed on). */
data class Ring(val taskId: String, val title: String, val base: Long, val at: Long, val missed: Boolean, val repeats: Boolean)

/** What the laptop remembers about reminders between runs. */
@Serializable
private data class ReminderMemory(
    /** taskId → the time it last rang here (so it rings once per time). */
    val fired: Map<String, Long> = emptyMap(),
    /** taskId → "base:until" snooze. */
    val snoozes: Map<String, String> = emptyMap(),
    /** Newest ack already applied. */
    val lastAckAt: Long = 0,
    val initialised: Boolean = false,
)

/**
 * The app: sign-in, a copy of open tasks (refreshed every 30 s), and the reminder engine.
 *
 * Laptop first: a reminder pops up here at its time. The phone waits 30 s and checks reminder_acks;
 * anything you press here (Done / Snooze / Tomorrow / Dismiss) is written there, so it stays quiet.
 * Answers given on the phone close the pop-up here the same way.
 */
class BuddyState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _settings = MutableStateFlow(Settings.load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val cloud = Cloud(_settings.value.deviceId)
    private val myDevice get() = "laptop:${cloud.deviceName}"

    val signedIn: StateFlow<Boolean?> = cloud.supabase.auth.sessionStatus.map {
        when (it) {
            is SessionStatus.Authenticated -> true
            is SessionStatus.NotAuthenticated -> false
            else -> null
        }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    private val _ringing = MutableStateFlow<List<Ring>>(emptyList())
    val ringing: StateFlow<List<Ring>> = _ringing.asStateFlow()

    /** Today at a glance (pill, hover panel, main window). Recomputed every minute and on every change. */
    private val _agenda = MutableStateFlow(Agenda.EMPTY)
    val agenda: StateFlow<Agenda> = _agenda.asStateFlow()
    private fun recomputeAgenda() { _agenda.value = Agenda.build(_tasks.value) }

    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val lock = Mutex()
    private var memory = Local.read(MEMORY, ReminderMemory.serializer()) ?: ReminderMemory()
    private var plan: Pair<String?, String?>? = null

    fun updateSettings(f: (Settings) -> Settings) {
        _settings.update(f)
        Settings.save(_settings.value)
    }

    fun start() {
        scope.launch { pollLoop() }
        scope.launch { tickLoop() }
        scope.launch {
            _tasks.collect { recomputeAgenda() }
        }
        scope.launch {
            while (true) { delay(60_000); recomputeAgenda() }
        }
    }

    // ---------------- polling ----------------

    private suspend fun pollLoop() {
        var lastBeat = 0L
        while (true) {
            if (signedIn.first { it != null } == true) {
                val ok = runCatching { refresh() }.onFailure { Log.w("refresh failed", it) }.isSuccess
                _online.value = ok
                if (ok && System.currentTimeMillis() - lastBeat > 55_000) {
                    cloud.heartbeat()
                    lastBeat = System.currentTimeMillis()
                }
            }
            delay(if (_online.value) 30_000 else 60_000)
        }
    }

    suspend fun refresh() {
        cloud.flush()
        val fresh = cloud.openTasks()
        val acks = cloud.recentAcks(Instant.ofEpochMilli(maxOf(memory.lastAckAt, System.currentTimeMillis() - 60 * 60_000L)))
        lock.withLock {
            _tasks.value = fresh
            if (!memory.initialised) {
                // First run: don't pop up every past reminder.
                val now = System.currentTimeMillis()
                memory = memory.copy(
                    fired = fresh.mapNotNull { t -> t.remindAt?.takeIf { it <= now }?.let { t.id to it } }.toMap(),
                    initialised = true,
                )
            }
            acks.filter { it.ackedAt > memory.lastAckAt }.forEach(::applyAck)
            acks.maxOfOrNull { it.ackedAt }?.let { a -> if (a > memory.lastAckAt) memory = memory.copy(lastAckAt = a) }
            // Finished or deleted elsewhere: close its pop-up.
            val open = fresh.filterNot { it.isDone }.associateBy { it.id }
            _ringing.update { list -> list.filter { r -> open[r.taskId]?.remindAt == r.base } }
            save()
        }
    }

    /** An answer from another device (or an earlier one from here). */
    private fun applyAck(a: Ack) {
        if (a.device == myDevice) return
        val task = _tasks.value.firstOrNull { it.id == a.taskId }
        _ringing.update { list -> list.filterNot { it.taskId == a.taskId && it.base == a.remindAt } }
        if (task == null || task.remindAt != a.remindAt) return
        if (a.action == "snoozed" && a.snoozeUntil != null) {
            memory = memory.copy(snoozes = memory.snoozes + (task.id to "${a.remindAt}:${a.snoozeUntil}"))
        } else {
            memory = memory.copy(fired = memory.fired + (task.id to effective(task)))
        }
    }

    private fun effective(t: Task): Long {
        val base = t.remindAt ?: return Long.MAX_VALUE
        val s = memory.snoozes[t.id]?.split(":")
        return s?.takeIf { it.size == 2 && it[0] == base.toString() }?.get(1)?.toLongOrNull() ?: base
    }

    private fun save() = Local.write(MEMORY, ReminderMemory.serializer(), memory)

    // ---------------- ringing ----------------

    private suspend fun tickLoop() {
        while (true) {
            runCatching { tick() }.onFailure { Log.w("tick failed", it) }
            delay(2_000)
        }
    }

    private suspend fun tick() = lock.withLock {
        val now = System.currentTimeMillis()
        var changed = false
        for (t in _tasks.value) {
            if (t.isDone) continue
            val base = t.remindAt ?: continue
            val at = effective(t)
            if (at > now || memory.fired[t.id] == at) continue
            memory = memory.copy(fired = memory.fired + (t.id to at))
            changed = true
            if (at < now - MISSED_WINDOW_MS) continue
            val missed = at < now - RING_GRACE_MS
            _ringing.update { list -> list.filterNot { it.taskId == t.id } + Ring(t.id, t.title, base, at, missed, t.repeat != null) }
            if (!missed && settings.value.sound) Sound.chime()
        }
        if (changed) save()
    }

    // ---------------- answering a reminder ----------------

    private fun task(id: String) = _tasks.value.firstOrNull { it.id == id }

    private fun close(r: Ring) = _ringing.update { list -> list.filterNot { it.taskId == r.taskId } }

    fun done(r: Ring) = act(r) { t ->
        val rows = TaskEdits.complete(t)
        rows.forEach { cloud.write("tasks", it) }
        cloud.ack(r.taskId, r.base, "done")
    }

    fun snooze(r: Ring, minutes: Int) = act(r) { t ->
        val until = System.currentTimeMillis() + minutes * 60_000L
        lock.withLock {
            memory = memory.copy(snoozes = memory.snoozes + (t.id to "${r.base}:$until"))
            save()
        }
        cloud.ack(r.taskId, r.base, "snoozed", until)
    }

    fun tomorrow(r: Ring) = act(r) { t ->
        cloud.write("tasks", TaskEdits.tomorrow(t))
        cloud.ack(r.taskId, r.base, "moved")
    }

    /** Closes it. A repeating task skips to its next time (like the phone's Dismiss). */
    fun dismiss(r: Ring) = act(r) { t ->
        TaskEdits.skip(t)?.let { cloud.write("tasks", it) }
        cloud.ack(r.taskId, r.base, "dismissed")
    }

    private fun act(r: Ring, block: suspend (Task) -> Unit) {
        close(r)
        scope.launch {
            val t = task(r.taskId) ?: return@launch
            runCatching { block(t) }.onFailure { Log.w("reminder action failed", it) }
            runCatching { refresh() }
        }
    }

    // ---------------- adding ----------------

    suspend fun addTask(t: TaskTimes): JsonObject {
        val (planId, bucketId) = plan ?: runCatching { cloud.firstPlanAndBucket() }.getOrNull()?.also { plan = it } ?: (null to null)
        val position = (_tasks.value.filter { it.bucketId == bucketId }.maxOfOrNull { it.position } ?: 0.0) + 1
        val row = TaskEdits.newTask(t.title, planId, bucketId, position, t.dueAt, t.dueAllDay, t.remindAt, t.repeat)
        cloud.write("tasks", row)
        _tasks.update { it + Task(row) }
        return row
    }

    suspend fun deleteRow(row: JsonObject) {
        val current = _tasks.value.firstOrNull { it.id == row.str("id") }?.raw ?: row
        cloud.write("tasks", TaskEdits.deleteRow(current))
        _tasks.update { list -> list.filterNot { it.id == row.str("id") } }
    }

    /** Ticks a task off (or back on, if it was done today). */
    fun toggle(t: Task) = scope.launch {
        if (t.isDone) {
            val back = t.forPush().with("status" to "todo", "completed_at" to null, "updated_at" to maxOf(System.currentTimeMillis(), t.updatedAt + 1).iso())
            _tasks.update { list -> list.map { if (it.id == t.id) Task(back) else it } }
            cloud.write("tasks", back)
        } else {
            val rows = TaskEdits.complete(t)
            _tasks.update { list -> list.filterNot { it.id == t.id } + rows.map(::Task) }
            rows.forEach { cloud.write("tasks", it) }
        }
        runCatching { refresh() }
    }

    companion object {
        private const val MEMORY = "reminders.json"
        /** Later than this (laptop was asleep) it shows as "missed", quietly. */
        const val RING_GRACE_MS = 10 * 60_000L
        const val MISSED_WINDOW_MS = 12 * 60 * 60_000L
    }
}
