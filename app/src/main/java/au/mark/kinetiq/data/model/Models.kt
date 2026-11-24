package au.mark.kinetiq.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Exercise category — the four equipment modalities plus BACK, a physiotherapy-informed
 * core/lower-back strength category of mat exercises (see RESEARCH.md §11).
 */
@Serializable
enum class Category { FLOOR, REFORMER, SPIN, ELLIPTICAL, BACK }

/** Human-readable category name for chips and labels — never show raw enum spellings. */
fun Category.displayName(): String = when (this) {
    Category.FLOOR -> "Floor"
    Category.REFORMER -> "Reformer"
    Category.SPIN -> "Spin bike"
    Category.ELLIPTICAL -> "Elliptical"
    Category.BACK -> "Back care"
}

/** DISCRETE = timed exercise with rests; INTERVAL_SEGMENT = coached machine interval. */
@Serializable
enum class ExerciseKind { DISCRETE, INTERVAL_SEGMENT }

/**
 * Evidence tier per the Phase 0 research (see RESEARCH.md):
 * STRONG/MODERATE require ≥1 real citation; LIMITED requires an honest popularityNote
 * and is excluded from auto-generation unless enabled in Settings.
 */
@Serializable
enum class EvidenceTier { STRONG, MODERATE, LIMITED }

@Serializable
enum class Intensity { LOW, MODERATE, HIGH, VERY_HIGH }

@Serializable
enum class Impact { LOW, MODERATE, HIGH }

@Serializable
enum class Target { VISCERAL_FAT, CARDIO, STRENGTH, CORE, MOBILITY, BALANCE }

@Serializable
enum class BodyArea { KNEE, WRIST, SHOULDER, LOWER_BACK, NECK, HIP, ANKLE }

/**
 * Kit an exercise needs beyond its category's own station (mat, reformer, bike, elliptical).
 * An exercise is only ever generated when every item it lists is available: HAND_WEIGHTS comes
 * from the per-session Builder chip, the rest from Settings → Equipment.
 *
 * CHAIR and STEP are separate on purpose: a chair raises hands, feet or shoulders, but nobody
 * should stand on the edge of one — a calf raise off an edge needs a step or stair.
 */
@Serializable
enum class Equipment { HAND_WEIGHTS, CHAIR, STEP, REFORMER_BOX, ANKLE_ANCHOR }

fun Equipment.displayName(): String = when (this) {
    Equipment.HAND_WEIGHTS -> "Hand weights"
    Equipment.CHAIR -> "Sturdy chair or bench"
    Equipment.STEP -> "Step or bottom stair"
    Equipment.REFORMER_BOX -> "Reformer long box"
    Equipment.ANKLE_ANCHOR -> "Ankle anchor"
}

/** Muscle groups tracked by the weekly resistance-set meter (RESEARCH.md §12). */
@Serializable
enum class Muscle { QUADS, GLUTES, HAMSTRINGS, CALVES, CHEST, BACK, SHOULDERS, BICEPS, TRICEPS, TRUNK }

fun Muscle.displayName(): String = when (this) {
    Muscle.QUADS -> "Quads"
    Muscle.GLUTES -> "Glutes"
    Muscle.HAMSTRINGS -> "Hamstrings"
    Muscle.CALVES -> "Calves"
    Muscle.CHEST -> "Chest"
    Muscle.BACK -> "Upper back"
    Muscle.SHOULDERS -> "Shoulders"
    Muscle.BICEPS -> "Biceps"
    Muscle.TRICEPS -> "Triceps"
    Muscle.TRUNK -> "Trunk"
}

/**
 * Movement slots a muscle-retention session is balanced across. The first four are the regions
 * the ACSM 2026 position stand asks every program to cover; the rest are accessories filled
 * when time allows. Declaration order is the fill priority.
 */
@Serializable
enum class MovementSlot { LOWER_PUSH, UPPER_PUSH, LOWER_PULL, UPPER_PULL, SHOULDERS, BICEPS, TRICEPS, CALVES, TRUNK }

fun MovementSlot.displayName(): String = when (this) {
    MovementSlot.LOWER_PUSH -> "leg push (quads and glutes)"
    MovementSlot.UPPER_PUSH -> "upper-body push (chest and triceps)"
    MovementSlot.LOWER_PULL -> "hamstring and glute"
    MovementSlot.UPPER_PULL -> "upper-body pull (upper back)"
    MovementSlot.SHOULDERS -> "shoulder"
    MovementSlot.BICEPS -> "biceps"
    MovementSlot.TRICEPS -> "triceps"
    MovementSlot.CALVES -> "calf"
    MovementSlot.TRUNK -> "trunk"
}

/**
 * How heavy an exercise is relative to the user's strength, which sets the set window and the
 * effort target: LOW (body weight, springs, light hand weights) needs long, slow sets taken to
 * the last good rep; MODERATE (a load failed at in ~8–15 reps) can stop 1–3 reps short.
 */
@Serializable
enum class LoadClass { LOW, MODERATE }

/**
 * Makes an exercise eligible for muscle-retention sessions and grades it for that claim alone.
 *
 * [tier] is deliberately separate from [Exercise.evidenceTier]: an exercise can be well supported
 * for general health or back care and still be weak as a muscle-retention stimulus (reformer
 * footwork is the standing example). STRONG/MODERATE require >= 1 reference here; LIMITED
 * requires a candid [note].
 */
@Serializable
data class ResistanceProfile(
    val slot: MovementSlot,
    val primary: List<Muscle>,
    /** Counted as half a set each in the weekly meter (Pelland 2026). */
    val secondary: List<Muscle> = emptyList(),
    val tier: EvidenceTier,
    val note: String? = null,
    val references: List<Reference> = emptyList(),
    /** One side at a time: the set window is doubled and split by a "switch sides" cue. */
    val unilateral: Boolean = false,
    /** A held position rather than reps: short window, no tempo cue. */
    val isometric: Boolean = false,
    /** Progression ladder id; exercises sharing one are alternatives, harder as [rung] rises. */
    val ladder: String? = null,
    val rung: Int = 1,
    val loadClass: LoadClass = LoadClass.LOW,
    /**
     * HAND_WEIGHTS exercises only: the lightest per-hand weight (kg) at which this is plausibly
     * a useful load. A planning estimate, not a study finding — see RESEARCH.md §12.
     */
    val minUsefulKg: Float? = null,
)

/** One literature reference backing an exercise. All fields are real — validated by unit test. */
@Serializable
data class Reference(
    val title: String,
    val authors: String,
    val year: Int,
    val journal: String,
    val doiOrPmid: String,
    val finding: String,
)

/** Spin bike cue data. Resistance is expressed on the user's configured level scale (default 1–11 GR7). */
@Serializable
data class SpinCue(
    /** Fraction of the bike's max resistance level, 0.0–1.0; rendered as a level number at runtime. */
    val resistanceLow: Float,
    val resistanceHigh: Float = resistanceLow,
    val cadenceRpmLow: Int,
    val cadenceRpmHigh: Int,
    /** e.g. "seated flat", "standing climb" */
    val position: String,
)

@Serializable
data class EllipticalCue(
    val resistanceLow: Float,
    val resistanceHigh: Float = resistanceLow,
    /** FORWARD or REVERSE stride */
    val direction: String,
    /** e.g. "drive through the handles", "legs only — hands on the static rail" */
    val arms: String,
)

@Serializable
data class ReformerCue(
    /** Generic spring load: LIGHT, MEDIUM, HEAVY, or e.g. MEDIUM_2 for two medium springs. */
    val springs: String,
    val bodyPosition: String,
)

@Serializable
data class MachineCue(
    val spin: SpinCue? = null,
    val elliptical: EllipticalCue? = null,
    val reformer: ReformerCue? = null,
)

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val category: Category,
    val kind: ExerciseKind,
    val evidenceTier: EvidenceTier,
    val references: List<Reference> = emptyList(),
    val popularityNote: String? = null,
    val summary: String,
    val voiceName: String,
    val voiceHowTo: String,
    val voiceFormCues: List<String> = emptyList(),
    val defaultWorkSec: Int,
    val defaultRestSec: Int,
    val minSec: Int,
    val maxSec: Int,
    val met: Float,
    val intensity: Intensity,
    val impact: Impact,
    val targets: List<Target>,
    val contraindications: List<BodyArea> = emptyList(),
    val machine: MachineCue? = null,
    val animationId: String,
    /** True for warm-up/cool-down-appropriate content. */
    @SerialName("warmupCooldown") val isWarmupCooldown: Boolean = false,
    /** Kit this exercise cannot be done without. Empty = just the category's own station. */
    val equipment: List<Equipment> = emptyList(),
    /** Non-null = usable in muscle-retention sessions, graded separately for that purpose. */
    val resistance: ResistanceProfile? = null,
)

/** A named machine routine (SPIN/ELLIPTICAL): ordered interval segments selectable as a unit. */
@Serializable
data class RoutineStep(
    /** id of an INTERVAL_SEGMENT exercise in the same category */
    val exerciseId: String,
    val durationSec: Int,
)

@Serializable
data class NamedRoutine(
    val id: String,
    val name: String,
    val category: Category,
    val summary: String,
    val intensity: Intensity,
    val steps: List<RoutineStep>,
) {
    val totalSec: Int get() = steps.sumOf { it.durationSec }
}

/** Top-level bundled database file: assets/exercise_db.json */
@Serializable
data class ExerciseDatabaseFile(
    val schemaVersion: Int,
    val exercises: List<Exercise>,
    val routines: List<NamedRoutine>,
)
