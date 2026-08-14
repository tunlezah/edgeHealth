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
