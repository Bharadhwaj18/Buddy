package com.shrey.buddy.data.tasks

import androidx.room.withTransaction
import com.shrey.buddy.data.db.BucketEntity
import com.shrey.buddy.data.db.BuddyDatabase
import com.shrey.buddy.data.db.OutboxEntry
import com.shrey.buddy.data.db.PlanEntity
import com.shrey.buddy.data.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.UUID

@Serializable
data class ChecklistItem(val id: String, val text: String, val done: Boolean = false)

object Checklists {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ChecklistItem.serializer())
    fun decode(raw: String?): List<ChecklistItem> =
        raw?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    fun encode(items: List<ChecklistItem>): String = json.encodeToString(serializer, items)
}

val TaskEntity.checklist: List<ChecklistItem> get() = Checklists.decode(checklistJson)
val TaskEntity.isDone: Boolean get() = status == "done"
fun TaskEntity.inMyDay(today: LocalDate = LocalDate.now()): Boolean = myDay == today.toString()

/**
 * Planner-style tasks (DESIGN.md §4.3): plans → buckets → tasks, plus My Day.
 * Every write goes through the outbox so it syncs.
 */
class TaskRepository(
    private val db: BuddyDatabase,
    private val onLocalChange: () -> Unit,
    /** A task was copied (next repeat, or duplicate) — lets device-local per-task settings follow it. */
    private val onSpawned: (original: TaskEntity, copy: TaskEntity) -> Unit = { _, _ -> },
) {
    fun observeTasks(): Flow<List<TaskEntity>> = db.taskDao().observeActive()
    fun observePlans(): Flow<List<PlanEntity>> = db.planDao().observeAll()
    fun observeBuckets(): Flow<List<BucketEntity>> = db.bucketDao().observeAll()

    /** First run: a "Personal" plan with three buckets; older tasks without a plan move into it. */
    suspend fun ensureDefaults() {
        val plans = db.planDao().all()
        val now = System.currentTimeMillis()
        db.withTransaction {
            val plan = plans.firstOrNull() ?: PlanEntity(
                id = uuid("plan:personal"), name = "Personal", color = "lime", position = 0.0,
                archived = false, createdAt = now, updatedAt = now, deletedAt = null,
            ).also { p ->
                write(p)
                listOf("To do", "This week", "Later").forEachIndexed { i, name ->
                    write(BucketEntity(uuid("bucket:personal:$i"), p.id, name, i.toDouble(), now, now, null))
                }
            }
            val firstBucket = db.bucketDao().forPlan(plan.id).firstOrNull()
            db.taskDao().all().filter { it.planId == null }.forEach { t ->
                write(t.copy(planId = plan.id, bucketId = firstBucket?.id, updatedAt = next(t)))
            }
        }
        onLocalChange()
    }

    // ---- tasks ----

    suspend fun add(
        title: String,
        planId: String? = null,
        bucketId: String? = null,
        myDay: Boolean = false,
        dueAt: Long? = null,
        priority: String = "normal",
        dueAllDay: Boolean = true,
        remindAt: Long? = null,
        repeat: String? = null,
    ): TaskEntity? {
        val clean = title.trim()
        if (clean.isEmpty()) return null
        val plan = planId ?: db.planDao().all().firstOrNull()?.id
        val bucket = bucketId ?: plan?.let { db.bucketDao().forPlan(it).firstOrNull()?.id }
        val position = (db.taskDao().all().filter { it.bucketId == bucket }.maxOfOrNull { it.position } ?: 0.0) + 1
        val now = System.currentTimeMillis()
        val task = TaskEntity(
            id = UUID.randomUUID().toString(), title = clean, notes = null, status = "todo",
            createdAt = now, updatedAt = now, deletedAt = null, planId = plan, bucketId = bucket,
            priority = priority, dueAt = dueAt, myDay = if (myDay) LocalDate.now().toString() else null,
            position = position, dueAllDay = dueAllDay || dueAt == null, remindAt = remindAt, repeat = repeat,
        )
        db.withTransaction { write(task) }
        onLocalChange()
        return task
    }

    suspend fun get(id: String): TaskEntity? = db.taskDao().get(id)

    /**
     * Saves any edit; keeps completedAt consistent with status. Completing a repeating task creates
     * its next occurrence (fresh checklist, dates moved forward) — like Microsoft To Do.
     */
    suspend fun save(task: TaskEntity): TaskEntity? {
        val prev = db.taskDao().get(task.id)
        val justCompleted = task.status == "done" && prev?.status != "done"
        val completedAt = when {
            justCompleted -> System.currentTimeMillis()
            task.status != "done" -> null
            else -> task.completedAt
        }
        val rule = RepeatRule.parse(task.repeat)
        val spawnNext = justCompleted && rule != null && (task.dueAt != null || task.remindAt != null)
        var spawned: TaskEntity? = null
        db.withTransaction {
            // The finished copy stops repeating, so un-ticking it later can't spawn a duplicate.
            write(task.copy(completedAt = completedAt, updatedAt = next(prev ?: task), repeat = if (spawnNext) null else task.repeat))
            if (spawnNext) {
                val (due, remind) = nextOccurrenceTimes(rule!!, task.dueAt, task.remindAt)
                val now = System.currentTimeMillis()
                spawned = task.copy(
                    id = UUID.randomUUID().toString(), status = "todo", createdAt = now, updatedAt = now,
                    completedAt = null, myDay = null, dueAt = due, remindAt = remind,
                    checklistJson = Checklists.encode(task.checklist.map { it.copy(done = false) }),
                ).also { write(it) }
            }
        }
        onLocalChange()
        spawned?.let { onSpawned(task, it) }
        return spawned
    }

    /** Reverses a completion: the task comes back as it was, and any next occurrence it created goes away. */
    suspend fun undoComplete(original: TaskEntity, spawned: TaskEntity?) {
        val current = db.taskDao().get(original.id) ?: return
        db.withTransaction {
            write(original.copy(updatedAt = next(current)))
            spawned?.let { s -> db.taskDao().get(s.id)?.let { write(it.copy(deletedAt = next(it), updatedAt = next(it))) } }
        }
        onLocalChange()
    }

    /**
     * Moves a task to [day], keeping its time of day and the gap to its reminder. Moving to today puts it
     * in My Day; moving anywhere else takes it out.
     */
    suspend fun moveTo(task: TaskEntity, day: LocalDate) {
        val zone = java.time.ZoneId.systemDefault()
        if (task.dueAt == null && task.remindAt != null) {
            // Reminder-only: the reminder moves to that day, same time.
            val r = java.time.Instant.ofEpochMilli(task.remindAt).atZone(zone).with(day)
            save(task.copy(remindAt = r.toInstant().toEpochMilli(), myDay = if (day == LocalDate.now()) day.toString() else null))
            return
        }
        val due = task.dueAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone) }
        val newDue = due?.with(day) ?: day.atStartOfDay(zone)
        val shift = due?.let { java.time.Duration.between(it, newDue).toMillis() }
        save(
            task.copy(
                dueAt = newDue.toInstant().toEpochMilli(),
                dueAllDay = if (due == null) true else task.dueAllDay,
                remindAt = task.remindAt?.let { r -> if (shift != null) r + shift else r },
                myDay = if (day == LocalDate.now()) day.toString() else null,
            ),
        )
    }

    /** Every unfinished task for today (or overdue, or in My Day) → tomorrow. Returns how many moved. */
    suspend fun rollOverToTomorrow(): Int {
        val today = LocalDate.now()
        val zone = java.time.ZoneId.systemDefault()
        val left = db.taskDao().all().filter { t ->
            t.status != "done" && (t.inMyDay(today) ||
                t.dueAt?.let { !java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().isAfter(today) } == true)
        }
        left.forEach { moveTo(it, today.plusDays(1)) }
        return left.size
    }

    suspend fun duplicate(task: TaskEntity): TaskEntity {
        val now = System.currentTimeMillis()
        val copy = task.copy(
            id = UUID.randomUUID().toString(), status = "todo", createdAt = now, updatedAt = now, completedAt = null,
            position = task.position + 0.5,
            checklistJson = Checklists.encode(task.checklist.map { it.copy(done = false) }),
        )
        db.withTransaction { write(copy) }
        onLocalChange()
        onSpawned(task, copy)
        return copy
    }

    /** Skips a repeating task to its next occurrence without completing it. */
    suspend fun skip(task: TaskEntity) {
        val rule = RepeatRule.parse(task.repeat) ?: return
        val (due, remind) = nextOccurrenceTimes(rule, task.dueAt, task.remindAt)
        save(task.copy(dueAt = due, remindAt = remind))
    }

    suspend fun toggleDone(task: TaskEntity) = save(task.copy(status = if (task.isDone) "todo" else "done"))

    suspend fun toggleMyDay(task: TaskEntity) =
        save(task.copy(myDay = if (task.inMyDay()) null else LocalDate.now().toString()))

    suspend fun delete(task: TaskEntity) {
        val ts = next(task)
        db.withTransaction { write(task.copy(deletedAt = ts, updatedAt = ts)) }
        onLocalChange()
    }

    // ---- plans ----

    suspend fun addPlan(name: String, color: String): PlanEntity? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        val now = System.currentTimeMillis()
        val position = (db.planDao().all().maxOfOrNull { it.position } ?: 0.0) + 1
        val plan = PlanEntity(UUID.randomUUID().toString(), clean, color, position, false, now, now, null)
        db.withTransaction {
            write(plan)
            write(BucketEntity(UUID.randomUUID().toString(), plan.id, "To do", 0.0, now, now, null))
        }
        onLocalChange()
        return plan
    }

    suspend fun savePlan(plan: PlanEntity) {
        val prev = db.planDao().get(plan.id)
        db.withTransaction { write(plan.copy(updatedAt = maxOf(System.currentTimeMillis(), (prev?.updatedAt ?: 0) + 1))) }
        onLocalChange()
    }

    /** Deletes the plan, its buckets and its tasks (soft delete, synced). */
    suspend fun deletePlan(planId: String) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.planDao().get(planId)?.let { write(it.copy(deletedAt = now, updatedAt = maxOf(now, it.updatedAt + 1))) }
            db.bucketDao().forPlan(planId).forEach { write(it.copy(deletedAt = now, updatedAt = maxOf(now, it.updatedAt + 1))) }
            db.taskDao().all().filter { it.planId == planId }.forEach { write(it.copy(deletedAt = now, updatedAt = next(it))) }
        }
        onLocalChange()
    }

    // ---- buckets ----

    suspend fun addBucket(planId: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val now = System.currentTimeMillis()
        val position = (db.bucketDao().forPlan(planId).maxOfOrNull { it.position } ?: 0.0) + 1
        db.withTransaction { write(BucketEntity(UUID.randomUUID().toString(), planId, clean, position, now, now, null)) }
        onLocalChange()
    }

    suspend fun renameBucket(bucket: BucketEntity, name: String) {
        if (name.isBlank()) return
        db.withTransaction { write(bucket.copy(name = name.trim(), updatedAt = maxOf(System.currentTimeMillis(), bucket.updatedAt + 1))) }
        onLocalChange()
    }

    /** Deletes a bucket; its tasks move to the plan's first remaining bucket. */
    suspend fun deleteBucket(bucket: BucketEntity) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            val target = db.bucketDao().forPlan(bucket.planId).firstOrNull { it.id != bucket.id }
            db.taskDao().all().filter { it.bucketId == bucket.id }.forEach { write(it.copy(bucketId = target?.id, updatedAt = next(it))) }
            write(bucket.copy(deletedAt = now, updatedAt = maxOf(now, bucket.updatedAt + 1)))
        }
        onLocalChange()
    }

    // ---- outbox writes ----

    private suspend fun write(t: TaskEntity) {
        db.taskDao().upsert(t)
        db.outboxDao().enqueue(OutboxEntry(tableName = TABLE, rowId = t.id, enqueuedAt = System.currentTimeMillis()))
    }

    private suspend fun write(p: PlanEntity) {
        db.planDao().upsert(p)
        db.outboxDao().enqueue(OutboxEntry(tableName = "plans", rowId = p.id, enqueuedAt = System.currentTimeMillis()))
    }

    private suspend fun write(b: BucketEntity) {
        db.bucketDao().upsert(b)
        db.outboxDao().enqueue(OutboxEntry(tableName = "buckets", rowId = b.id, enqueuedAt = System.currentTimeMillis()))
    }

    // Last-write-wins compares updated_at, so it must strictly increase per row even if the clock goes backwards.
    private fun next(task: TaskEntity) = maxOf(System.currentTimeMillis(), task.updatedAt + 1)

    private fun uuid(key: String) = UUID.nameUUIDFromBytes(key.toByteArray()).toString()

    companion object {
        const val TABLE = "tasks"
    }
}
