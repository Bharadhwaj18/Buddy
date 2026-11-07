package com.shrey.buddy.ui.health

import com.shrey.buddy.data.items.ExerciseEntry
import com.shrey.buddy.data.items.Routine
import com.shrey.buddy.data.items.RoutineExercise
import com.shrey.buddy.data.items.SetEntry
import com.shrey.buddy.data.items.Workout
import com.shrey.buddy.health.Exercise
import com.shrey.buddy.health.StrengthMath
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A set being logged (text fields stay as typed until the workout is saved). */
@Serializable
data class LiveSet(
    val id: String = UUID.randomUUID().toString(),
    val kg: String = "",
    val reps: String = "",
    val done: Boolean = false,
    val kind: String = "normal",
    /** Seconds, for timed exercises (planks, holds). */
    val secs: String = "",
)

@Serializable
data class LiveExercise(
    val key: String = UUID.randomUUID().toString(),
    val name: String,
    val exerciseId: String? = null,
    val muscles: List<String> = emptyList(),
    val sets: List<LiveSet> = listOf(LiveSet()),
    val restS: Int = 90,
    /** Last time's sets, shown as hints ("60 × 8"). */
    val previous: List<SetEntry> = emptyList(),
    val notes: String = "",
    /** "weight" (kg × reps), "bodyweight" (reps, optional added kg) or "timed" (seconds). */
    val mode: String = "weight",
    /** Your best before this session, for the live trophy on a record set. 0 = never done. */
    val bestKg: Double = 0.0,
    val best1rm: Double = 0.0,
) {
    fun filled(s: LiveSet) = if (mode == "timed") s.secs.isNotBlank() else s.reps.isNotBlank()

    /** Is this ticked set a personal record (heavier than ever, or a better estimated 1RM)? */
    fun isRecord(set: LiveSet): Boolean {
        if (!set.done || set.kind == "warmup" || best1rm <= 0) return false
        val kg = set.kg.replace(',', '.').toDoubleOrNull() ?: return false
        val reps = set.reps.toIntOrNull() ?: return false
        return kg > 0 && reps > 0 && (kg > bestKg || StrengthMath.e1rm(kg, reps) > best1rm + 0.01)
    }
}

@Serializable
data class SessionState(
    val title: String,
    val startedAt: Long = System.currentTimeMillis(),
    val exercises: List<LiveExercise> = emptyList(),
    val routineId: String? = null,
    /** When the current rest ends (epoch millis), or null. */
    val restEndsAt: Long? = null,
    val restTotalS: Int = 0,
) {
    val doneSets get() = exercises.sumOf { e -> e.sets.count { it.done } }
    val totalSets get() = exercises.sumOf { it.sets.size }
}

/**
 * The gym session in progress. [file] is where it's kept, so a workout survives the app being closed
 * or killed half-way (written on every change; it's only a few KB).
 */
class WorkoutSession(private val file: File? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<SessionState?> = _state.asStateFlow()

    private fun set(s: SessionState?) {
        _state.value = s
        runCatching { if (s == null) file?.delete() else file?.writeText(json.encodeToString(SessionState.serializer(), s)) }
    }
    private fun update(f: (SessionState) -> SessionState) { _state.value?.let { set(f(it)) } }

    private fun restore(): SessionState? = runCatching {
        file?.takeIf { it.exists() }?.readText()?.let { json.decodeFromString(SessionState.serializer(), it) }
            // A session left for over a day isn't a workout any more.
            ?.takeIf { System.currentTimeMillis() - it.startedAt < 24 * 3_600_000L }
    }.getOrNull()

    fun start(title: String, history: List<Workout>, routine: Routine? = null, routineId: String? = null) {
        val exercises = routine?.exercises.orEmpty().map { r -> fromRoutine(r, history) }
        set(SessionState(title = title, exercises = exercises, routineId = routineId))
    }

    private fun modeOf(e: Exercise) = when {
        e.isTimed -> "timed"
        e.equipment == "body only" -> "bodyweight"
        else -> "weight"
    }

    private fun bests(name: String, id: String?, history: List<Workout>): Pair<Double, Double> {
        val past = history.flatMap { w -> w.exercises.filter { StrengthMath.same(it, ExerciseEntry(name, exerciseId = id)) } }
            .flatMap { it.sets }.filter { it.done && it.kind != "warmup" && it.reps > 0 && it.weightKg > 0 }
        return (past.maxOfOrNull { it.weightKg } ?: 0.0) to (past.maxOfOrNull { StrengthMath.e1rm(it.weightKg, it.reps) } ?: 0.0)
    }

    /** Pre-filled with last time's numbers, so you just tick sets off. */
    private fun liveSet(p: SetEntry?, fallbackReps: Int? = null) = LiveSet(
        kg = p?.weightKg?.takeIf { it > 0 }?.let(StrengthMath::fmt).orEmpty(),
        reps = (p?.reps?.takeIf { it > 0 } ?: fallbackReps)?.toString().orEmpty(),
        secs = p?.seconds?.takeIf { it > 0 }?.toString().orEmpty(),
    )

    private fun fromRoutine(r: RoutineExercise, history: List<Workout>): LiveExercise {
        val prev = StrengthMath.previous(r.name, r.exerciseId, history)?.sets.orEmpty().filter { it.kind != "warmup" }
        val timed = prev.any { (it.seconds ?: 0) > 0 }
        val sets = (0 until r.sets).map { i -> liveSet(prev.getOrNull(i) ?: prev.lastOrNull(), r.reps.takeIf { !timed }) }
        val (kg, rm) = bests(r.name, r.exerciseId, history)
        return LiveExercise(
            name = r.name, exerciseId = r.exerciseId, muscles = r.muscles, sets = sets, restS = r.restS, previous = prev, notes = r.notes,
            mode = if (timed) "timed" else "weight", bestKg = kg, best1rm = rm,
        )
    }

    private fun fromLibrary(e: Exercise, history: List<Workout>): LiveExercise {
        val prev = StrengthMath.previous(e.name, e.id, history)?.sets.orEmpty().filter { it.kind != "warmup" }
        val sets = if (prev.isNotEmpty()) prev.map { liveSet(it) } else List(3) { LiveSet() }
        val (kg, rm) = bests(e.name, e.id, history)
        return LiveExercise(name = e.name, exerciseId = e.id, muscles = e.primaryMuscles, sets = sets, previous = prev, mode = modeOf(e), bestKg = kg, best1rm = rm)
    }

    fun addExercises(list: List<Exercise>, history: List<Workout>) = update { s -> s.copy(exercises = s.exercises + list.map { fromLibrary(it, history) }) }

    fun addCustomExercise(name: String) = update { it.copy(exercises = it.exercises + LiveExercise(name = name.trim(), sets = List(3) { LiveSet() })) }

    /** Swap an exercise (machine taken?) and keep its place in the workout. */
    fun replaceExercise(key: String, with: Exercise, history: List<Workout>) = update { s ->
        s.copy(exercises = s.exercises.map { if (it.key == key) fromLibrary(with, history).copy(key = key, restS = it.restS) else it })
    }

    fun rename(title: String) = update { it.copy(title = title) }
    fun removeExercise(key: String) = update { s -> s.copy(exercises = s.exercises.filterNot { it.key == key }) }
    fun moveExercise(key: String, delta: Int) = update { s ->
        val i = s.exercises.indexOfFirst { it.key == key }
        val j = (i + delta).coerceIn(0, s.exercises.lastIndex)
        if (i < 0 || i == j) s else s.copy(exercises = s.exercises.toMutableList().apply { add(j, removeAt(i)) })
    }
    /** 0 = no rest timer for this exercise. */
    fun setRest(key: String, seconds: Int) = editExercise(key) { it.copy(restS = seconds.coerceIn(0, 600)) }
    fun setNotes(key: String, notes: String) = editExercise(key) { it.copy(notes = notes.take(200)) }
    fun setMode(key: String, mode: String) = editExercise(key) { it.copy(mode = mode) }

    fun addSet(key: String) = editExercise(key) { e ->
        val last = e.sets.lastOrNull()
        e.copy(sets = e.sets + LiveSet(kg = last?.kg.orEmpty(), reps = last?.reps.orEmpty(), secs = last?.secs.orEmpty()))
    }
    fun removeSet(key: String, setId: String) = editExercise(key) { e -> e.copy(sets = e.sets.filterNot { it.id == setId }) }
    fun editSet(key: String, setId: String, f: (LiveSet) -> LiveSet) = editExercise(key) { e -> e.copy(sets = e.sets.map { if (it.id == setId) f(it) else it }) }
    fun cycleKind(key: String, setId: String) = editSet(key, setId) {
        it.copy(kind = when (it.kind) { "normal" -> "warmup"; "warmup" -> "drop"; "drop" -> "failure"; else -> "normal" })
    }

    /**
     * Typing a new weight / reps / time in one set also updates the sets below it that you haven't ticked
     * and that still held the old number (or nothing) — change 60 → 62.5 once, not four times.
     */
    fun type(key: String, setId: String, kg: String? = null, reps: String? = null, secs: String? = null) = editExercise(key) { e ->
        val i = e.sets.indexOfFirst { it.id == setId }
        if (i < 0) return@editExercise e
        val old = e.sets[i]
        e.copy(sets = e.sets.mapIndexed { j, s ->
            when {
                j == i -> s.copy(kg = kg ?: s.kg, reps = reps ?: s.reps, secs = secs ?: s.secs)
                j > i && !s.done -> s.copy(
                    kg = if (kg != null && (s.kg == old.kg || s.kg.isBlank())) kg else s.kg,
                    reps = if (reps != null && (s.reps == old.reps || s.reps.isBlank())) reps else s.reps,
                    secs = if (secs != null && (s.secs == old.secs || s.secs.isBlank())) secs else s.secs,
                )
                else -> s
            }
        })
    }

    /** Tap "previous" to copy last time's numbers into the set. */
    fun copyPrevious(key: String, setId: String, p: SetEntry) = editSet(key, setId) {
        it.copy(
            kg = p.weightKg.takeIf { w -> w > 0 }?.let(StrengthMath::fmt).orEmpty(),
            reps = p.reps.takeIf { r -> r > 0 }?.toString().orEmpty(),
            secs = p.seconds?.takeIf { s -> s > 0 }?.toString().orEmpty(),
        )
    }

    /** Sets you typed numbers into but forgot to tick. */
    fun filledUnticked(): Int = _state.value?.exercises?.sumOf { e -> e.sets.count { !it.done && e.filled(it) } } ?: 0
    fun tickFilled() = update { s -> s.copy(exercises = s.exercises.map { e -> e.copy(sets = e.sets.map { if (!it.done && e.filled(it)) it.copy(done = true) else it }) }) }

    /** Ticks a set; ticking starts the exercise's rest timer. Returns the rest length (0 if un-ticked or rest is off). */
    fun toggleDone(key: String, setId: String): Int {
        var rest = 0
        update { s ->
            val e = s.exercises.firstOrNull { it.key == key } ?: return@update s
            val set = e.sets.firstOrNull { it.id == setId } ?: return@update s
            val nowDone = !set.done
            rest = if (nowDone) e.restS else 0
            s.copy(
                exercises = s.exercises.map { x -> if (x.key == key) x.copy(sets = x.sets.map { if (it.id == setId) it.copy(done = nowDone) else it }) else x },
                restEndsAt = if (rest > 0) System.currentTimeMillis() + rest * 1000L else s.restEndsAt,
                restTotalS = if (rest > 0) rest else s.restTotalS,
            )
        }
        return rest
    }

    fun adjustRest(deltaS: Int) = update { s ->
        val end = s.restEndsAt ?: return@update s
        s.copy(restEndsAt = (end + deltaS * 1000L).coerceAtLeast(System.currentTimeMillis()), restTotalS = (s.restTotalS + deltaS).coerceAtLeast(1))
    }
    fun skipRest() = update { it.copy(restEndsAt = null) }

    private fun editExercise(key: String, f: (LiveExercise) -> LiveExercise) =
        update { s -> s.copy(exercises = s.exercises.map { if (it.key == key) f(it) else it }) }

    /** The session as a saved workout (only ticked sets count). */
    fun toWorkout(effort: String, kg: Double?): Workout? {
        val s = _state.value ?: return null
        val minutes = ((System.currentTimeMillis() - s.startedAt) / 60_000).toInt().coerceIn(1, 600)
        val exercises = s.exercises.map { e ->
            ExerciseEntry(
                name = e.name,
                exerciseId = e.exerciseId,
                muscles = e.muscles,
                notes = e.notes.trim(),
                sets = e.sets.filter { it.done }.map { ls ->
                    SetEntry(
                        reps = if (e.mode == "timed") 0 else ls.reps.toIntOrNull() ?: 0,
                        weightKg = ls.kg.replace(',', '.').toDoubleOrNull() ?: 0.0,
                        seconds = ls.secs.toIntOrNull()?.takeIf { e.mode == "timed" },
                        kind = ls.kind,
                    )
                },
            )
        }.filter { it.sets.isNotEmpty() }
        return Workout(
            type = "strength",
            title = s.title,
            durationMin = minutes,
            exercises = exercises,
            kcal = StrengthMath.kcal("strength", effort, minutes, kg),
            startedAt = s.startedAt,
            routineId = s.routineId,
            effort = effort,
        )
    }

    fun asRoutine(): Routine? {
        val s = _state.value ?: return null
        return Routine(
            name = s.title,
            exercises = s.exercises.map { e ->
                RoutineExercise(e.name, e.exerciseId, e.muscles, sets = e.sets.size.coerceAtLeast(1), reps = e.sets.firstOrNull()?.reps?.toIntOrNull() ?: 10, restS = e.restS)
            },
        )
    }

    fun clear() { set(null) }
}
