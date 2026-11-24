package au.mark.kinetiq.domain.resistance

import au.mark.kinetiq.data.model.EffortRating
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.Muscle
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.WorkoutPlan

/**
 * Turns what happened in a session into the two things the app tracks for muscle retention:
 * weekly hard sets per muscle group, and per-exercise outcomes that drive progression.
 *
 * What counts as a hard set (RESEARCH.md §12):
 *  - it was performed — it ran its whole window or was ended with "Set done" (the engine records
 *    this; a skipped set never counts);
 *  - it was not rated EASY. A light-load set that ends four or more reps short of failure is not
 *    the stimulus the evidence describes, so it earns nothing. Unrated sets get the benefit of
 *    the doubt — the rating is optional and its absence says nothing.
 *
 * Credit: a set counts 1 for each primary muscle and ½ for each secondary one, the convention the
 * dose-response meta-regressions use (Pelland 2026).
 */
object ResistanceTracking {

    const val SECONDARY_CREDIT = 0.5

    /** A performed set's planned step, with its index in the plan. */
    private fun performedSets(plan: WorkoutPlan, completed: Set<Int>): List<IndexedValue<SessionStep>> =
        plan.steps.withIndex().filter { (i, step) -> step.isResistanceSet && i in completed }

    private fun counts(index: Int, ratings: Map<Int, EffortRating>): Boolean = ratings[index] != EffortRating.EASY

    /** Number of hard sets in one block. */
    fun hardSets(plan: WorkoutPlan, blockIndex: Int, completed: Set<Int>, ratings: Map<Int, EffortRating>): Int =
        performedSets(plan, completed).count { (i, step) -> step.blockIndex == blockIndex && counts(i, ratings) }

    /** Hard-set credit per muscle (keyed by [Muscle] name) for one block. */
    fun muscleSets(
        plan: WorkoutPlan,
        blockIndex: Int,
        completed: Set<Int>,
        ratings: Map<Int, EffortRating>,
    ): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        for ((i, step) in performedSets(plan, completed)) {
            if (step.blockIndex != blockIndex || !counts(i, ratings)) continue
            for (m in step.primaryMuscles) out[m.name] = (out[m.name] ?: 0.0) + 1.0
            for (m in step.secondaryMuscles) out[m.name] = (out[m.name] ?: 0.0) + SECONDARY_CREDIT
        }
        return out
    }

    /** How one exercise went in a session. */
    data class ExerciseOutcome(
        val exerciseId: String,
        val exerciseName: String,
        val plannedSets: Int,
        val performedSets: Int,
        /** Ratings of the performed sets that were rated, in set order. */
        val ratings: List<EffortRating>,
    )

    fun outcomes(plan: WorkoutPlan, completed: Set<Int>, ratings: Map<Int, EffortRating>): List<ExerciseOutcome> =
        plan.steps.withIndex().filter { it.value.isResistanceSet && it.value.exerciseId != null }
            .groupBy { it.value.blockIndex to it.value.exerciseId!! }
            .map { (key, sets) ->
                val done = sets.filter { it.index in completed }
                ExerciseOutcome(
                    exerciseId = key.second,
                    exerciseName = sets.first().value.exerciseName,
                    plannedSets = sets.size,
                    performedSets = done.size,
                    ratings = done.mapNotNull { ratings[it.index] },
                )
            }

    /** What to change next time, from how the sets were rated. */
    enum class Advice {
        /** Every rated set had four or more reps in hand: this version no longer gets hard. */
        MOVE_UP,
        /** Mostly 2–3 reps in hand: stay, but slow the lowering or add reps. */
        PUSH_HARDER,
        /** Sets are ending where they should, or there is not enough information to say. */
        HOLD,
    }

    /**
     * Deliberately slow to promote: one easy set is noise (self-rated reps in reserve are off by
     * about a rep, worse in long sets — Halperin 2022), so moving up needs at least two rated sets
     * and every one of them EASY.
     */
    fun advise(outcome: ExerciseOutcome): Advice {
        val r = outcome.ratings
        return when {
            r.size >= 2 && r.all { it == EffortRating.EASY } -> Advice.MOVE_UP
            r.size >= 2 && r.count { it != EffortRating.HARD } * 2 > r.size -> Advice.PUSH_HARDER
            else -> Advice.HOLD
        }
    }

    /** Sum per-muscle credit across blocks, as typed keys; unknown names (a newer build's data) are ignored. */
    fun totalByMuscle(blocks: List<Map<String, Double>>): Map<Muscle, Double> {
        val out = linkedMapOf<Muscle, Double>()
        for (block in blocks) for ((name, sets) in block) {
            val muscle = runCatching { Muscle.valueOf(name) }.getOrNull() ?: continue
            out[muscle] = (out[muscle] ?: 0.0) + sets
        }
        return out
    }
}

/**
 * Turns a session's outcomes into concrete next steps: move up a ladder, push harder on the same
 * version, or — when there is no harder version the user's equipment allows — say plainly that
 * the exercise has stopped being a stimulus.
 */
object ProgressionAdvisor {

    data class Suggestion(
        val exerciseId: String,
        val exerciseName: String,
        val advice: ResistanceTracking.Advice,
        /** Set for MOVE_UP when a harder rung is available: the ladder to update and what it becomes. */
        val ladder: String? = null,
        val nextRung: Int? = null,
        val nextExerciseName: String? = null,
    ) {
        val canMoveUp: Boolean get() = ladder != null && nextRung != null
    }

    /**
     * One suggestion per exercise that needs a change; exercises that went as intended produce
     * nothing. [isAllowed] is the equipment-and-constraints gate for a candidate next rung.
     */
    fun suggestions(
        outcomes: List<ResistanceTracking.ExerciseOutcome>,
        exercises: List<Exercise>,
        isAllowed: (Exercise) -> Boolean,
    ): List<Suggestion> {
        val byId = exercises.associateBy { it.id }
        return outcomes.mapNotNull { outcome ->
            val advice = ResistanceTracking.advise(outcome)
            if (advice == ResistanceTracking.Advice.HOLD) return@mapNotNull null
            val base = Suggestion(outcome.exerciseId, outcome.exerciseName, advice)
            if (advice != ResistanceTracking.Advice.MOVE_UP) return@mapNotNull base
            val profile = byId[outcome.exerciseId]?.resistance ?: return@mapNotNull base
            val ladder = profile.ladder ?: return@mapNotNull base
            val harder = exercises
                .filter { it.resistance?.ladder == ladder && it.resistance.rung > profile.rung && isAllowed(it) }
                .minByOrNull { it.resistance!!.rung }
                ?: return@mapNotNull base
            base.copy(ladder = ladder, nextRung = harder.resistance!!.rung, nextExerciseName = harder.name)
        }
    }
}

/**
 * Progression ladders as the user sees them: an ordered run of versions of one movement, easier
 * to harder. Derived from the bundled exercises, so adding a rung to the database adds it here.
 */
object Ladders {

    data class Rung(val rung: Int, val names: List<String>)

    data class Ladder(val id: String, val rungs: List<Rung>) {
        /** The rung in use for a stored level: the nearest one that exists, the easier on a tie. */
        fun current(stored: Int?): Rung {
            val target = stored ?: DEFAULT_RUNG
            return rungs.minWith(compareBy({ kotlin.math.abs(it.rung - target) }, { it.rung }))
        }

        /** The next rung down ([delta] < 0) or up, or null at the end of the ladder. */
        fun step(stored: Int?, delta: Int): Rung? {
            val i = rungs.indexOf(current(stored)) + if (delta < 0) -1 else 1
            return rungs.getOrNull(i)
        }
    }

    /** Same default the block builder uses for a ladder the user has never set. */
    const val DEFAULT_RUNG = 2

    /** Every ladder with more than one rung, in movement-slot order. */
    fun from(exercises: List<Exercise>): List<Ladder> =
        exercises.filter { it.resistance?.ladder != null }
            .groupBy { it.resistance!!.ladder!! }
            .map { (id, members) ->
                val slot = members.first().resistance!!.slot
                slot to Ladder(
                    id = id,
                    rungs = members.groupBy { it.resistance!!.rung }.toSortedMap()
                        .map { (rung, at) -> Rung(rung, at.map { it.name }) },
                )
            }
            .filter { it.second.rungs.size > 1 }
            .sortedWith(compareBy({ it.first.ordinal }, { it.second.id }))
            .map { it.second }
}

