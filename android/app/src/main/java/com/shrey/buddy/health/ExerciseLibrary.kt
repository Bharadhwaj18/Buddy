package com.shrey.buddy.health

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The bundled exercise library: free-exercise-db (github.com/yuhonas/free-exercise-db), public domain
 * (The Unlicense). 870+ exercises with muscles, equipment, instructions and photos.
 */
@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val force: String? = null,
    val level: String? = null,
    val mechanic: String? = null,
    val equipment: String? = null,
    val primaryMuscles: List<String> = emptyList(),
    val secondaryMuscles: List<String> = emptyList(),
    val instructions: List<String> = emptyList(),
    val category: String? = null,
    val images: List<String> = emptyList(),
) {
    val imageUrls get() = images.map { IMAGE_BASE + it }
    val isTimed get() = category == "stretching" || name.contains("plank", true) || name.contains("hold", true)

    companion object {
        const val IMAGE_BASE = "https://raw.githubusercontent.com/yuhonas/free-exercise-db/main/exercises/"
    }
}

object ExerciseLibrary {
    @Volatile private var cache: List<Exercise>? = null
    private val json = Json { ignoreUnknownKeys = true }

    fun all(context: Context): List<Exercise> = cache ?: synchronized(this) {
        cache ?: context.assets.open("exercises.json").bufferedReader().use { r ->
            json.decodeFromString(ListSerializer(Exercise.serializer()), r.readText())
        }.sortedBy { it.name }.also { cache = it }
    }

    fun byId(context: Context, id: String?) = id?.let { i -> all(context).firstOrNull { it.id == i } }

    /** The muscle groups used for filtering, in a gym-friendly order. */
    val muscleGroups = listOf(
        "chest", "shoulders", "triceps", "biceps", "forearms", "lats", "middle back", "lower back", "traps",
        "abdominals", "quadriceps", "hamstrings", "glutes", "calves", "adductors", "abductors", "neck",
    )

    val equipment = listOf("body only", "dumbbell", "barbell", "cable", "machine", "kettlebells", "bands", "e-z curl bar", "medicine ball", "exercise ball", "foam roll", "other")

    /** The gym staples, so "bench" finds the barbell bench press first. Ids from free-exercise-db. */
    private val staples = listOf(
        "Barbell_Bench_Press_-_Medium_Grip", "Barbell_Full_Squat", "Barbell_Deadlift", "Standing_Military_Press",
        "Pullups", "Bent_Over_Barbell_Row", "Dumbbell_Bench_Press", "Incline_Dumbbell_Press", "Romanian_Deadlift",
        "Leg_Press", "Wide-Grip_Lat_Pulldown", "Seated_Cable_Rows", "Dumbbell_Shoulder_Press", "Barbell_Curl",
        "Triceps_Pushdown", "Dips_-_Triceps_Version", "Pushups", "Plank", "Lying_Leg_Curls", "Leg_Extensions",
        "Standing_Calf_Raises", "Side_Lateral_Raise", "Face_Pull", "Hammer_Curls", "Chin-Up",
        "Barbell_Hip_Thrust", "Dumbbell_Lunges", "Goblet_Squat", "Cable_Crossover",
    ).withIndex().associate { (i, id) -> id to i }

    /** Search by name words (all must match), optionally filtered by muscle and equipment. Staples and name-starts-with first. */
    fun search(list: List<Exercise>, query: String, muscle: String?, equipment: String?): List<Exercise> {
        val q = query.trim().lowercase()
        val words = q.split(' ').filter { it.isNotBlank() }
        return list.filter { e ->
            (muscle == null || muscle in e.primaryMuscles) &&
                (equipment == null || e.equipment == equipment) &&
                words.all { w -> e.name.lowercase().contains(w) }
        }.sortedWith(
            compareBy<Exercise> { staples[it.id] ?: Int.MAX_VALUE }
                .thenBy { if (q.isNotEmpty() && it.name.lowercase().startsWith(q)) 0 else 1 }
                .thenBy { if (it.level == "beginner") 0 else 1 }
                .thenBy { if (it.mechanic == "compound") 0 else 1 }
                .thenBy { it.name.length },
        )
    }
}
