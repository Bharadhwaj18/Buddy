package com.shrey.buddy.data.items

import kotlinx.serialization.Serializable

/** `items.kind` values. Payload classes below; all fields have defaults so old rows always decode. */
object Kinds {
    const val HABIT = "habit"
    /** One per day (stable id "steps:<date>"): merged phone-sensor + Health Connect steps. */
    const val STEPS_DAY = "steps_day"
    /** Single row (stable id "body:profile"): height, sex, birth year, goals. */
    const val BODY_PROFILE = "body_profile"
    const val WEIGHT = "weight"
    const val BODY_MEASURE = "body_measure"
    const val ROUTINE = "routine"
    /** One per night (stable id "sleep:<wake date>"). */
    const val SLEEP = "sleep"
    /** One per day (stable id "heart:<date>"). */
    const val HEART_DAY = "heart_day"
    /** One per day (stable id "water:<date>"). */
    const val WATER = "water"
    /** AI-built running / strength / weight plans. */
    const val TRAINING_PLAN = "training_plan"
    /** One per week (stable id "health_report:<monday>"). */
    const val HEALTH_REPORT = "health_report"
    const val HABIT_LOG = "habit_log"
    const val WORKOUT = "workout"
    const val MEAL = "meal"
    const val JOURNAL = "journal"
    const val NOTE = "note"
    const val GOAL = "goal"
    const val PERSON = "person"
    const val READING = "reading"
    const val VEHICLE = "vehicle"
    const val VEHICLE_LOG = "vehicle_log"
    const val WISH = "wish"
    const val DOCUMENT = "document"
    const val HOLDING = "holding"
    const val INSIGHT = "insight"
    const val RECURRING = "recurring"
    const val BUDGET = "budget"
    const val SUBCATEGORY = "subcategory"
    const val MONEY_BACK = "money_back"
    const val LIGHT = "light"
    const val LIGHT_ROUTINE = "light_routine"
    /** A hummed / sung tune in the Music idea vault. */
    const val HUM = "hum"
    /** Someone owes you (or you owe them) for a split. */
    const val IOU = "iou"
    /** A delivery tracked from shop / courier SMS. */
    const val PARCEL = "parcel"
}

// ---- Health ----

/** [auto]: null = tick manually; "steps" = met when Health Connect steps ≥ [target]; "no_spend" = no debits that day. */
@Serializable
data class Habit(
    val name: String = "",
    val emoji: String = "✅",
    val target: Int = 1,
    val auto: String? = null,
    val archived: Boolean = false,
)

@Serializable
data class HabitLog(val value: Int = 1)

@Serializable
data class StepsDay(
    val steps: Long = 0,
    /** Steps in each hour 0..23. */
    val hourly: List<Int> = List(24) { 0 },
    /** "phone" (step sensor), "health_connect", or the watch app's package. */
    val source: String = "phone",
    /** From Health Connect when a watch reports it; otherwise estimated from steps. */
    val distanceM: Double? = null,
)

@Serializable
data class SleepNight(
    val startMs: Long = 0,
    val endMs: Long = 0,
    val minutes: Int = 0,
    val deepMin: Int = 0,
    val lightMin: Int = 0,
    val remMin: Int = 0,
    val awakeMin: Int = 0,
    /** "phone" (estimated from screen-off time) or the watch app's package. */
    val source: String = "phone",
)

@Serializable
data class HeartDay(
    val minBpm: Int? = null,
    val avgBpm: Int? = null,
    val maxBpm: Int? = null,
    val restingBpm: Int? = null,
    val spo2Pct: Double? = null,
    val hrvMs: Double? = null,
    val source: String = "",
)

@Serializable
data class WaterDay(val ml: Int = 0)

@Serializable
data class PlanExercise(
    val name: String = "",
    val sets: Int = 3,
    val reps: String = "10",
    @kotlinx.serialization.SerialName("rest_s") val restS: Int? = null,
    /** Starting weight or effort ("60 kg", "RPE 7"). */
    val load: String = "",
    val notes: String = "",
)

@Serializable
data class PlanSession(
    /** Mon..Sun */
    val day: String = "Mon",
    /** run | strength | cross | rest | mobility */
    val type: String = "run",
    val title: String = "",
    val details: String = "",
    @kotlinx.serialization.SerialName("duration_min") val durationMin: Int? = null,
    @kotlinx.serialization.SerialName("distance_km") val distanceKm: Double? = null,
    val exercises: List<PlanExercise> = emptyList(),
    val warmup: String = "",
    val cooldown: String = "",
    val intensity: String = "",
)

@Serializable
data class PlanWeek(val week: Int = 1, val focus: String = "", val sessions: List<PlanSession> = emptyList())

@Serializable
data class TrainingPlan(
    val title: String = "",
    val summary: String = "",
    val weeks: List<PlanWeek> = emptyList(),
    val nutrition: String? = null,
    val tips: List<String> = emptyList(),
    /** One line per week of the whole plan (keeps later blocks consistent while it's being built). */
    val outline: List<String> = emptyList(),
    // ---- set by the app ----
    /** Routines made from this plan's strength days (removed again when the plan is stopped). */
    val routineIds: List<String> = emptyList(),
    /** run_5k | run_10k | run_half | muscle | strength | weight_loss | fitness */
    val goal: String = "",
    /** ISO date of week 1's Monday. */
    val startDate: String = "",
    val active: Boolean = true,
    /** Tasks plan the sessions were added to. */
    val taskPlanId: String? = null,
    val error: String? = null,
)

@Serializable
data class HealthReport(
    val headline: String = "",
    val highlights: List<String> = emptyList(),
    val watch: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("next_week") val nextWeek: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class BodyProfile(
    val heightCm: Double? = null,
    /** "male" | "female" — for BMR / calorie maths. */
    val sex: String? = null,
    val birthYear: Int? = null,
    val goalWeightKg: Double? = null,
    /** sedentary | light | moderate | active | very_active */
    val activity: String = "light",
)

@Serializable
data class WeightEntry(
    val kg: Double = 0.0,
    val bodyFatPct: Double? = null,
    val note: String = "",
    /** "manual" or the app that wrote it to Health Connect (smart scale / watch). */
    val source: String = "manual",
)

@Serializable
data class BodyMeasure(
    val waistCm: Double? = null,
    val chestCm: Double? = null,
    val hipsCm: Double? = null,
    val armCm: Double? = null,
    val thighCm: Double? = null,
    val neckCm: Double? = null,
)

@Serializable
data class SetEntry(
    val reps: Int = 0,
    val weightKg: Double = 0.0,
    /** Timed sets (planks, carries). */
    val seconds: Int? = null,
    val done: Boolean = true,
    /** "normal" | "warmup" | "drop" | "failure" — warm-ups don't count towards volume or records. */
    val kind: String = "normal",
)

@Serializable
data class ExerciseEntry(
    val name: String = "",
    val sets: List<SetEntry> = emptyList(),
    /** id in the bundled exercise library (free-exercise-db), when picked from it. */
    val exerciseId: String? = null,
    val muscles: List<String> = emptyList(),
    val notes: String = "",
)

/** A saved workout template ("Push day"). */
@Serializable
data class Routine(
    val name: String = "",
    val exercises: List<RoutineExercise> = emptyList(),
    val notes: String = "",
)

@Serializable
data class RoutineExercise(
    val name: String = "",
    val exerciseId: String? = null,
    val muscles: List<String> = emptyList(),
    val sets: Int = 3,
    val reps: Int = 10,
    val restS: Int = 90,
    /** Shown as the exercise note when you start it ("Start ~60 kg · elbows tucked"). */
    val notes: String = "",
)

@Serializable
data class RunSplit(val km: Int, val s: Int, val m: Double, val climb: Double = 0.0)

@Serializable
data class RunSummary(
    val distanceM: Double = 0.0,
    /** Active (unpaused) time. */
    val durationS: Long = 0,
    /** Encoded polyline of the route (Google polyline format, 1e5 precision). Older runs: thinned. */
    val polyline: String = "",
    val elevationGainM: Double = 0.0,
    // ---- Full detail (runs saved from 0.19 on) ----
    /** Every GPS point, polyline-encoded; same order as [tSec] and [altM]. */
    val track: String = "",
    /** Seconds since start (paused time removed) for each track point. */
    val tSec: List<Int> = emptyList(),
    /** Altitude (m) for each track point. */
    val altM: List<Int> = emptyList(),
    val movingS: Int = 0,
    val splits: List<RunSplit> = emptyList(),
    val best1kS: Int? = null,
    val best5kS: Int? = null,
    val best10kS: Int? = null,
    val kcal: Int = 0,
    val startedAt: Long = 0,
)

/** type: strength | run | walk | cycle | yoga | sport | other */
@Serializable
data class Workout(
    val type: String = "strength",
    val title: String = "",
    val durationMin: Int = 0,
    val notes: String = "",
    val exercises: List<ExerciseEntry> = emptyList(),
    val run: RunSummary? = null,
    /** Estimated (or watch-measured) calories. */
    val kcal: Int = 0,
    val startedAt: Long = 0,
    val routineId: String? = null,
    /** "easy" | "moderate" | "hard" — how it felt; scales the calorie estimate. */
    val effort: String = "moderate",
    /** Health Connect session id, for activities imported from a watch (Garmin etc.). */
    val hcId: String = "",
)

@Serializable
data class Meal(
    val description: String = "",
    val kcal: Int = 0,
    val proteinG: Int = 0,
    val slot: String = "meal",
)

// ---- Life ----

@Serializable
data class Journal(
    /** 1 (awful) … 5 (great) */
    val mood: Int = 3,
    val energy: Int = 3,
    val text: String = "",
    val gratitude: String = "",
)

@Serializable
data class Note(
    val title: String = "",
    val body: String = "",
    val pinned: Boolean = false,
    val tags: List<String> = emptyList(),
)

/** area: money | fitness | tasks | learning | life */
@Serializable
data class Goal(
    val title: String = "",
    val area: String = "life",
    val target: Double = 0.0,
    val current: Double = 0.0,
    val unit: String = "",
    val due: String? = null,
    val done: Boolean = false,
)

@Serializable
data class Person(
    val name: String = "",
    val relation: String = "",
    /** "MM-dd" */
    val birthday: String? = null,
    val lastContact: String? = null,
    val contactEveryDays: Int = 0,
    val notes: String = "",
)

/** type: book | course | article | podcast; status: want | active | done */
@Serializable
data class ReadingItem(
    val title: String = "",
    val author: String = "",
    val type: String = "book",
    val status: String = "want",
    val progress: Int = 0,
    val rating: Int = 0,
    val notes: String = "",
)

@Serializable
data class Vehicle(
    val name: String = "",
    val regNo: String = "",
    val insuranceExpiry: String? = null,
    val pucExpiry: String? = null,
    val serviceDue: String? = null,
    val odometerKm: Int = 0,
)

/** type: fuel | service | repair | other */
@Serializable
data class VehicleLog(
    val type: String = "fuel",
    val amountMinor: Long = 0,
    val odometerKm: Int = 0,
    val litres: Double = 0.0,
    val note: String = "",
    /** The Money transaction this fuel fill was logged from (so they stay in step). */
    val txnId: String? = null,
)

/** status: waiting | bought | dropped. Cooling-off: think until [coolOffUntil] before buying. */
@Serializable
data class Wish(
    val title: String = "",
    val priceMinor: Long = 0,
    val url: String = "",
    val coolOffUntil: String? = null,
    val status: String = "waiting",
)

/** category: id | insurance | warranty | bill | medical | other. File lives in Storage bucket "vault". */
@Serializable
data class Document(
    val title: String = "",
    val category: String = "other",
    val expiry: String? = null,
    val storagePath: String = "",
    val mime: String = "",
    val sizeBytes: Long = 0,
    val notes: String = "",
)

/**
 * kind: mutual_fund | stock | gold | fd | epf | ppf | crypto | property | other.
 * Live holdings ([source] "mf" = AMFI scheme code, "stock" = Yahoo ticker like INFY.NS) are valued as
 * units × latest price; "manual" ones use [currentMinor] as typed.
 */
@Serializable
data class Holding(
    val name: String = "",
    val kind: String = "mutual_fund",
    val investedMinor: Long = 0,
    val currentMinor: Long = 0,
    val updatedOn: String? = null,
    val source: String = "manual",
    val symbol: String = "",
    val units: Double = 0.0,
    val price: Double = 0.0,
    val previousPrice: Double? = null,
    val priceDate: String? = null,
)

@Serializable
data class Recurring(
    val name: String = "",
    val amountMinor: Long = 0,
    /** debit | credit */
    val direction: String = "debit",
    val category: String = "bills",
    val accountId: String? = null,
    /** monthly | weekly | quarterly | yearly */
    val frequency: String = "monthly",
    /** Day of month (monthly/quarterly/yearly) or ISO day of week 1–7 (weekly). */
    val day: Int = 1,
    /** For yearly: month 1–12. */
    val month: Int = 1,
    val startDate: String? = null,
    /** Text to look for in the payee to recognise the bank's record of it (e.g. "ICCL", "landlord"). */
    val match: String = "",
    /** Log a transaction automatically on the due date if no matching bank SMS appears (cash rent etc.). */
    val autoLog: Boolean = false,
    val active: Boolean = true,
    /** ISO dates already handled (paid via SMS match, logged, or skipped). */
    val settled: List<String> = emptyList(),
)

/** Monthly limit. category = a Category slug, or "total" for the overall budget. */
@Serializable
data class Budget(val category: String = "total", val limitMinor: Long = 0, val name: String = "")

/** A sub-group you made under a spending category, e.g. Food → "Coffee". Stored on transactions as "food/Coffee". */
@Serializable
data class Subcategory(val parent: String = "other", val name: String = "")

/** Links money you got back (an incoming transaction, this item's id key) to the payment it refunds. */
@Serializable
data class MoneyBackLink(val creditId: String = "", val debitId: String = "")

/** Generated by Buddy AI. period: daily | weekly | insight */
@Serializable
data class Insight(
    val title: String = "",
    val body: String = "",
    val period: String = "insight",
)

// ---- Smart home (WiZ lights) ----

/** A WiZ bulb on the home Wi-Fi. [ip] is the last address it answered from (it can change). */
@Serializable
data class Light(
    val mac: String = "",
    val name: String = "",
    val room: String = "",
    val ip: String = "",
)

/** What a routine (or a voice command) does to the lights. Null = leave as is. */
@Serializable
data class LightAction(
    val on: Boolean = true,
    /** 10–100 %. */
    val brightness: Int? = null,
    /** 2200 (warm) – 6500 K (cool). */
    val warmth: Int? = null,
    /** "#RRGGBB". */
    val color: String? = null,
    /** A WiZ scene (see Wiz.SCENES). */
    val sceneId: Int? = null,
    /** "flash" blinks the lights a few times, then puts them back. */
    val effect: String? = null,
    /** Room names; empty = every light. */
    val rooms: List<String> = emptyList(),
)

/**
 * trigger:
 *  - "time"     at [time] on [days] (1 = Monday … 7 = Sunday; empty = every day)
 *  - "manual"   only when you run it (tap, or say its name: "Hey Buddy, movie mode")
 *  - "reminder" when a reminder calls
 *  - "wake"     when a wake-up / alarm reminder calls
 *  - "arrive"   when you get home (the phone joins the Wi-Fi the lights are on), only after [after]
 */
@Serializable
data class LightRoutine(
    val name: String = "",
    val trigger: String = "time",
    val time: String = "19:00",
    val days: List<Int> = emptyList(),
    val after: String? = null,
    val action: LightAction = LightAction(),
    val enabled: Boolean = true,
)

// ---- Music ----

/** A note of a hum: starts [at] ms in, lasts [len] ms. */
@Serializable
data class HumNote(val at: Int = 0, val len: Int = 0, val midi: Int = 60)

/**
 * A tune in the idea vault. Notes, key, tempo and chords sync; the recording itself ([audio], a file name in
 * the app's files/hums folder) stays on the phone that recorded it.
 */
@Serializable
data class Hum(
    val title: String = "",
    val notes: List<HumNote> = emptyList(),
    val bpm: Int = 100,
    /** "A minor"; blank when too short to tell. */
    val key: String = "",
    /** One chord symbol per bar of 4 beats. */
    val chords: List<String> = emptyList(),
    val lengthMs: Int = 0,
    val audio: String? = null,
    /** Where in the recording the first note starts (notes are stored from 0). */
    val audioOffsetMs: Int = 0,
    val createdAt: Long = 0,
)

// ---- Splits ----

/**
 * One person's share. [theyOwe] = they owe you (you paid); false = you owe them. [txnId] is the payment it came
 * from, if any; [settledTxnId] the money that paid it back (found automatically in bank SMS, or picked).
 */
@Serializable
data class Iou(
    val person: String = "",
    val amountMinor: Long = 0,
    val theyOwe: Boolean = true,
    val note: String = "",
    val txnId: String? = null,
    val createdAt: Long = 0,
    val settledAt: Long? = null,
    val settledTxnId: String? = null,
    val lastNudgeAt: Long? = null,
)

// ---- Parcels ----

@Serializable
data class Parcel(
    val merchant: String = "",
    val title: String? = null,
    val orderId: String? = null,
    /** ParcelStatus name. */
    val status: String = "ORDERED",
    /** ISO date it should arrive. */
    val eta: String? = null,
    /** Code to give the delivery person. */
    val deliveryCode: String? = null,
    val firstSeen: Long = 0,
    val updatedAt: Long = 0,
    /** Hidden from the lists (you said you got it, or it's old). */
    val hidden: Boolean = false,
)
