package au.mark.kinetiq.data.model

import kotlinx.serialization.Serializable

/** What a single step of a generated session is. */
@Serializable
enum class StepType { WARMUP, WORK, REST, TRANSITION, COOLDOWN }

/**
 * One fully-resolved timed step of a playable session.
 * For machine categories, WORK steps are interval segments spoken over a continuous block.
 */
@Serializable
data class SessionStep(
    val type: StepType,
    val category: Category,
    /** null for TRANSITION steps */
    val exerciseId: String? = null,
    val exerciseName: String = "",
    val durationSec: Int,
    /** Fully-rendered machine cue sentence, e.g. "Standing climb — resistance 8, around 65 rpm." */
    val machineCueText: String? = null,
    val met: Float = 1.5f,
    val animationId: String? = null,
    /** Index of the block this step belongs to (block = one category run). */
    val blockIndex: Int = 0,
)

/** One category block of a session. */
@Serializable
data class SessionBlock(
    val category: Category,
    /** True when this block is HIIT-structured (drives the Health Connect exercise type). */
    val isHiit: Boolean = false,
    /** Name of the named routine used, if any. */
    val routineName: String? = null,
)

/** A complete generated (or saved) workout session plan. */
@Serializable
data class WorkoutPlan(
    val name: String = "",
    val steps: List<SessionStep>,
    val blocks: List<SessionBlock>,
    val totalSec: Int = steps.sumOf { it.durationSec },
)

/** Generator inputs (Section 5 of the spec). */
@Serializable
data class GeneratorConfig(
    val totalDurationMin: Int = 30,
    /** null = auto */
    val exercisesPerCategory: Int? = null,
    val categories: List<Category> = listOf(Category.FLOOR),
    /** Per-category time weights; missing category = 1.0. */
    val categoryWeights: Map<Category, Float> = emptyMap(),
    /** Work:rest ratio expressed as rest = work / ratio, e.g. 2.0 → rest is half of work. */
    val workRestRatio: Float = 2.0f,
    val warmup: Boolean = true,
    val cooldown: Boolean = true,
    val intensity: Intensity = Intensity.MODERATE,
    val useHealthData: Boolean = true,
    val transitionSec: Int = 60,
)

/** A concrete result the player runs, kept alongside the config that produced it. */
@Serializable
data class GeneratedSession(
    val config: GeneratorConfig,
    val plan: WorkoutPlan,
)
