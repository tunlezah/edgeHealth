package au.mark.kinetiq.service

import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.model.WorkoutPlan
import au.mark.kinetiq.data.repo.CompletedBlock
import au.mark.kinetiq.domain.CalorieCalculator

/**
 * Pure workout state machine: tick accrual, cue points, step advance, prepare countdowns,
 * per-block accounting. No Android dependencies — everything the service used to compute
 * inline lives here so it is unit-testable on the JVM. The service executes the returned
 * [Effect]s (speech, beeps, finish) and mirrors [EngineState] into the UI-facing PlayerState.
 */
class SessionEngine(
    private val steps: List<SessionStep>,
    private val weightKg: Double,
) {

    data class CueFlags(
        val halfwaySpoken: Boolean = false,
        val countdownSpoken: Boolean = false,
        val howToSpoken: Boolean = false,
        val prepareBeepsPlayed: Boolean = false,
    )

    data class EngineState(
        val stepIndex: Int = 0,
        val stepRemainingMs: Long = 0L,
        /** > 0 == in a GET-READY countdown; no time or calories accrue while it runs. */
        val prepareRemainingMs: Long = 0L,
        /** Announce the step when the prepare countdown ends (session start / restore, not plain unpause). */
        val announceAfterPrepare: Boolean = true,
        val totalElapsedActiveMs: Long = 0L,
        val caloriesSoFar: Double = 0.0,
        val blockActiveMs: Map<Int, Long> = emptyMap(),
        val blockBounds: Map<Int, Pair<Long, Long>> = emptyMap(),
        val cues: CueFlags = CueFlags(),
        val finished: Boolean = false,
    )

    sealed interface Effect {
        data object PlayCountdownBeeps : Effect
        data object SpeakHalfway : Effect
        data class SpeakNextHowTo(val nextIndex: Int) : Effect
        data class AnnounceStep(val index: Int, val fresh: Boolean) : Effect
        data object PrepareEnded : Effect
        data object Finished : Effect
    }

    data class TickResult(val state: EngineState, val effects: List<Effect>)

    fun stepAt(index: Int): SessionStep? = steps.getOrNull(index)

    fun initialState(prepareMs: Long = PREPARE_DURATION_MS): EngineState = EngineState(
        stepIndex = 0,
        stepRemainingMs = (steps.firstOrNull()?.durationSec ?: 0) * 1000L,
        prepareRemainingMs = prepareMs,
        announceAfterPrepare = true,
    )

    fun onTick(state: EngineState, rawDeltaMs: Long, nowEpochMs: Long): TickResult {
        if (state.finished) return TickResult(state, emptyList())
        // Clamp: after doze or a stalled ticker one tick can carry minutes of wall clock.
        // The workout must not fast-forward steps or bill the gap as exercise at this
        // step's MET — the gap is simply dropped.
        val delta = rawDeltaMs.coerceIn(0L, MAX_TICK_DELTA_MS)
        val effects = mutableListOf<Effect>()

        // GET-READY pre-phase.
        if (state.prepareRemainingMs > 0) {
            var cues = state.cues
            val remaining = state.prepareRemainingMs - delta
            if (!cues.prepareBeepsPlayed && remaining <= COUNTDOWN_LEAD_MS) {
                cues = cues.copy(prepareBeepsPlayed = true)
                effects += Effect.PlayCountdownBeeps
            }
            return if (remaining <= 0) {
                effects += Effect.PrepareEnded
                if (state.announceAfterPrepare) effects += Effect.AnnounceStep(state.stepIndex, fresh = false)
                TickResult(
                    // The prepare intro already spoke the how-to; don't repeat it.
                    state.copy(prepareRemainingMs = 0, cues = state.cues.copy(howToSpoken = true)),
                    effects,
                )
            } else {
                TickResult(state.copy(prepareRemainingMs = remaining, cues = cues), effects)
            }
        }

        val step = steps.getOrNull(state.stepIndex) ?: return finish(state, effects)
        val stepDurationMs = step.durationSec * 1000L
        val remaining = state.stepRemainingMs - delta
        val isActiveStep = isActive(step.type)

        // Final-tick accounting: only what was actually left of the step accrues.
        val accrualMs = if (isActiveStep) minOf(delta, state.stepRemainingMs).coerceAtLeast(0L) else 0L
        val addedCalories = if (accrualMs > 0)
            CalorieCalculator.kcal(step.met, weightKg, 1) * (accrualMs / 1000.0) else 0.0

        var blockActive = state.blockActiveMs
        var blockBounds = state.blockBounds
        if (accrualMs > 0) {
            blockActive = blockActive + (step.blockIndex to (blockActive[step.blockIndex] ?: 0L) + accrualMs)
            val prev = blockBounds[step.blockIndex]
            blockBounds = blockBounds + (step.blockIndex to ((prev?.first ?: nowEpochMs) to nowEpochMs))
        }

        var cues = state.cues
        if (!cues.halfwaySpoken && isActiveStep && stepDurationMs >= 40_000 && remaining <= stepDurationMs / 2) {
            cues = cues.copy(halfwaySpoken = true)
            effects += Effect.SpeakHalfway
        }
        val next = steps.getOrNull(state.stepIndex + 1)
        if (!cues.countdownSpoken && remaining <= COUNTDOWN_LEAD_MS &&
            next != null && next.type == StepType.WORK && step.type != StepType.WORK
        ) {
            cues = cues.copy(countdownSpoken = true)
            effects += Effect.PlayCountdownBeeps
        }
        if (!cues.howToSpoken && (step.type == StepType.REST || step.type == StepType.TRANSITION) &&
            remaining <= stepDurationMs - 1_500
        ) {
            cues = cues.copy(howToSpoken = true)
            if (next != null && next.type == StepType.WORK) effects += Effect.SpeakNextHowTo(state.stepIndex + 1)
        }

        val accrued = state.copy(
            totalElapsedActiveMs = state.totalElapsedActiveMs + accrualMs,
            caloriesSoFar = state.caloriesSoFar + addedCalories,
            blockActiveMs = blockActive,
            blockBounds = blockBounds,
            cues = cues,
        )
        return if (remaining <= 0) advance(accrued, by = 1, effects = effects)
        else TickResult(accrued.copy(stepRemainingMs = remaining), effects)
    }

    /** Skipping a WORK step also skips the rest that followed it — the user wants the next exercise. */
    fun skip(state: EngineState): TickResult {
        if (state.finished) return TickResult(state, emptyList())
        if (state.prepareRemainingMs > 0) return TickResult(skipPrepare(state), emptyList())
        val step = steps.getOrNull(state.stepIndex) ?: return finish(state, mutableListOf())
        val by = if (step.type == StepType.WORK && steps.getOrNull(state.stepIndex + 1)?.type == StepType.REST) 2 else 1
        return advance(state, by, mutableListOf())
    }

    fun extend(state: EngineState, extraMs: Long = 30_000L): EngineState =
        if (state.finished || state.prepareRemainingMs > 0) state
        else state.copy(stepRemainingMs = state.stepRemainingMs + extraMs)

    /** Tap-to-skip during GET-READY: jump to the final three seconds, never straight to zero. */
    fun skipPrepare(state: EngineState): EngineState =
        if (state.prepareRemainingMs > RESUME_PREPARE_MS) state.copy(prepareRemainingMs = RESUME_PREPARE_MS)
        else state

    private fun advance(state: EngineState, by: Int, effects: MutableList<Effect>): TickResult {
        val nextIndex = state.stepIndex + by
        val next = steps.getOrNull(nextIndex) ?: return finish(state, effects)
        effects += Effect.AnnounceStep(nextIndex, fresh = false)
        return TickResult(
            state.copy(stepIndex = nextIndex, stepRemainingMs = next.durationSec * 1000L, cues = CueFlags()),
            effects,
        )
    }

    private fun finish(state: EngineState, effects: MutableList<Effect>): TickResult {
        effects += Effect.Finished
        return TickResult(state.copy(finished = true), effects)
    }

    companion object {
        const val MAX_TICK_DELTA_MS = 2_000L
        const val PREPARE_DURATION_MS = 10_000L
        const val RESUME_PREPARE_MS = 3_000L
        const val COUNTDOWN_LEAD_MS = 3_300L

        fun isActive(type: StepType): Boolean =
            type != StepType.REST && type != StepType.TRANSITION

        /** Derive already-passed cue points after a snapshot restore so none replay out of context. */
        fun cueFlagsForRestore(step: SessionStep, remainingMs: Long): CueFlags {
            val durMs = step.durationSec * 1000L
            return CueFlags(
                halfwaySpoken = remainingMs <= durMs / 2,
                countdownSpoken = remainingMs <= COUNTDOWN_LEAD_MS,
                howToSpoken = if (step.type == StepType.REST || step.type == StepType.TRANSITION)
                    remainingMs <= durMs - 1_500 else true,
            )
        }
    }
}

/**
 * Per-block session records for history and Health Connect. Pure so it is testable:
 * duration-weighted MET (a 30 s sprint no longer averages equally with a 5-min recovery),
 * and warm-up/cool-down sentinel indices (< 0) are naturally excluded because only
 * plan-block indices 0..n-1 are consulted.
 */
fun completedBlocks(
    plan: WorkoutPlan,
    blockActiveMs: Map<Int, Long>,
    blockBounds: Map<Int, Pair<Long, Long>>,
    weightKg: Double,
    fallbackBounds: Pair<Long, Long>,
): List<CompletedBlock> = plan.blocks.mapIndexedNotNull { index, block ->
    val activeSec = ((blockActiveMs[index] ?: 0L) / 1000).toInt()
    if (activeSec <= 0) return@mapIndexedNotNull null
    val bounds = blockBounds[index] ?: fallbackBounds
    val metSteps = plan.steps.filter {
        it.blockIndex == index && it.type != StepType.REST && it.type != StepType.TRANSITION
    }
    val met = if (metSteps.isEmpty()) 3.0
    else metSteps.sumOf { it.met.toDouble() * it.durationSec } /
        metSteps.sumOf { it.durationSec }.coerceAtLeast(1)
    CompletedBlock(
        category = block.category.name,
        activeSec = activeSec,
        calories = CalorieCalculator.kcal(met.toFloat(), weightKg, activeSec),
        isHiit = block.isHiit,
        startedAtEpochMs = bounds.first,
        endedAtEpochMs = bounds.second,
    )
}
