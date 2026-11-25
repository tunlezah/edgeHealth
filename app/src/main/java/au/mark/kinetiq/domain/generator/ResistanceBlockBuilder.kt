package au.mark.kinetiq.domain.generator

import au.mark.kinetiq.data.model.BodyArea
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.Equipment
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.ExerciseKind
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.LoadClass
import au.mark.kinetiq.data.model.MovementSlot
import au.mark.kinetiq.data.model.Muscle
import au.mark.kinetiq.data.model.ResistanceProfile
import au.mark.kinetiq.data.model.RestMode
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.repo.MachineSettings
import au.mark.kinetiq.domain.MachineCueRenderer
import au.mark.kinetiq.domain.resistance.Ladders
import kotlin.math.abs
import kotlin.random.Random

/**
 * Builds one FLOOR or REFORMER block of a muscle-retention session (RESEARCH.md §12).
 *
 * The circuit generator gives each exercise a single ~40 s bout with 15 s rests. That is
 * conditioning. What protects muscle in an energy deficit is repeated sets that end close to
 * failure, so this builder works in **sets**:
 *
 *  - one exercise per movement slot, covering leg push / upper push / hamstring-glute / upper
 *    pull first (the four regions the ACSM 2026 position stand asks for), accessories after;
 *  - 2–3 sets of each, in a window long enough for a light load to get hard (45–75 s for body
 *    weight, springs and light hand weights; doubled and split for one-side-at-a-time work);
 *  - same-muscle rests of 60–90 s, or two non-competing exercises alternated with short
 *    transitions so each muscle still gets about 100 s before it works again.
 *
 * The clock is only a container. Whether a set counts depends on how it ends, which is what the
 * spoken effort cue and the post-set effort rating are for.
 *
 * Exercises are graded on their *retention* tier. LIMITED entries never displace a better-graded
 * option; they can only fill a slot nothing else covers, and only when the user has asked for
 * that (the global low-evidence setting or the per-session gap-fill flag).
 */
internal class ResistanceBlockBuilder(
    private val exercises: List<Exercise>,
    private val machines: MachineSettings,
    private val random: Random,
) {

    /** Why a slot got no exercise from this block. Ordered: the more actionable reason sorts first. */
    enum class Unfilled { LIMITED_ONLY, NOTHING }

    data class Result(
        val steps: List<SessionStep>,
        val warnings: List<GeneratorWarning>,
        /** Slots this block actually trains. */
        val covered: Set<MovementSlot>,
        /** Slots this block could have trained (had at least one usable exercise). */
        val available: Set<MovementSlot>,
        val unfilled: Map<MovementSlot, Unfilled>,
    )

    /** Set-window bounds in seconds. */
    data class Window(val min: Int, val default: Int, val max: Int)

    /** One planned set of one exercise. */
    data class SetSlot(val exercise: Exercise, val setIndex: Int, val setCount: Int)

    /** One chosen exercise and how many sets of it the block runs. */
    data class Pick(val exercise: Exercise, val sets: Int)

    /**
     * The big four rotated so the region with the fewest hard sets this week comes first (a random
     * one when they are level, e.g. on a Monday), accessories after. Rotating — rather than
     * sorting — keeps lower and upper alternating so pairs never compete, while making sure a
     * short session picks up what is behind instead of training the same two regions every time.
     */
    fun slotOrder(weeklyMuscleSets: Map<Muscle, Double> = emptyMap()): List<MovementSlot> {
        fun done(slot: MovementSlot) = weeklyMuscleSets[LEAD_MUSCLE.getValue(slot)] ?: 0.0
        val least = BIG_FOUR.minOf(::done)
        val behind = BIG_FOUR.filter { done(it) == least }
        val start = BIG_FOUR.indexOf(behind[random.nextInt(behind.size)])
        return BIG_FOUR.drop(start) + BIG_FOUR.take(start) + ACCESSORIES
    }

    fun build(
        cat: Category,
        blockSec: Int,
        config: GeneratorConfig,
        constraints: Set<BodyArea>,
        includeLowEvidence: Boolean,
        kit: ExerciseEligibility.Kit,
        ladderRungs: Map<String, Int>,
        blockIndex: Int,
        slotOrder: List<MovementSlot>,
        alreadyCovered: Set<MovementSlot>,
    ): Result {
        val station = WorkoutGenerator.stationName(cat)
        val includeLimited = includeLowEvidence || config.allowLimitedGapFill
        val eligible = exercises.filter { ex ->
            ex.category == cat && ex.kind == ExerciseKind.DISCRETE &&
                ExerciseEligibility.inResistancePool(ex, constraints, includeLimited = true, kit = kit)
        }

        val unfilled = mutableMapOf<MovementSlot, Unfilled>()
        val options: Map<MovementSlot, List<Exercise>> = MovementSlot.entries.associateWith { slot ->
            // One candidate per ladder first — the rung is the user's level, whatever its tier —
            // and only then the evidence gate. A level the user chose for themselves is honoured
            // even when it is a limited-evidence regression: it is their call that the harder rung
            // is not yet doable. Anything else LIMITED is a gap filler at most.
            val candidates = representatives(eligible.filter { it.resistance?.slot == slot }, ladderRungs)
            val better = candidates.filter { ex ->
                ex.resistance?.tier != EvidenceTier.LIMITED || ex.resistance.ladder in ladderRungs
            }
            val usable = when {
                better.isNotEmpty() -> better
                includeLimited -> candidates
                else -> emptyList()
            }
            if (usable.isEmpty()) {
                unfilled[slot] = if (candidates.isNotEmpty()) Unfilled.LIMITED_ONLY else Unfilled.NOTHING
            }
            ranked(usable)
        }
        val available = options.filterValues { it.isNotEmpty() }.keys

        // Slots an earlier block already trained go to the back of the queue (stable sort).
        val orderedSlots = slotOrder.sortedBy { it in alreadyCovered }
        val maxDistinct = config.exercisesPerCategory?.takeIf { it > 0 } ?: Int.MAX_VALUE
        val mode = config.restMode

        fun fits(trial: List<Pick>): Boolean {
            val seq = setOrder(trial, mode)
            val rests = restsOf(seq, mode).sum()
            val minTotal = seq.sumOf { windowFor(it.exercise).min } + rests
            val defaultTotal = seq.sumOf { windowFor(it.exercise).default } + rests
            // Room to squeeze toward the short end of the window, but not so far that every set
            // in the block is pinned at its minimum.
            return minTotal <= blockSec && defaultTotal <= blockSec * 11 / 10
        }

        var picks = listOf<Pick>()
        var depth = 0
        while (picks.size < maxDistinct) {
            // Pass 0 takes the best option of each slot; later passes go one option deeper.
            val pass = orderedSlots.mapNotNull { options.getValue(it).getOrNull(depth) }
            if (pass.isEmpty()) break
            val addedIds = mutableListOf<String>()
            for (ex in pass) {
                if (picks.size >= maxDistinct) break
                val candidate = Pick(ex, BASE_SETS)
                if (fits(picks + candidate)) {
                    picks = picks + candidate
                    addedIds += ex.id
                }
            }
            // The block always gets at least one exercise: if not even two sets of anything fit
            // (a very short block and a long one-side-at-a-time window), run one set of the first.
            if (picks.isEmpty()) {
                picks = listOf(Pick(pass.first(), 1))
                addedIds += pass.first().id
            }
            // Deepen before widening: a third set of what is already here beats a new exercise.
            picks = topUp(picks, addedIds, MAX_SETS, ::fits)
            depth++
        }
        // Nothing left to add and still short: allow a fourth set rather than end early.
        if (picks.isNotEmpty() && underBudget(picks, mode, blockSec)) {
            picks = topUp(picks, picks.map { it.exercise.id }, LAST_RESORT_SETS, ::fits)
        }

        val warnings = mutableListOf<GeneratorWarning>()
        val limitedFix = if (!includeLimited && unfilled.values.any { it == Unfilled.LIMITED_ONLY }) {
            "Add limited-evidence fillers" to config.copy(allowLimitedGapFill = true)
        } else null

        if (picks.isEmpty()) {
            warnings += GeneratorWarning(
                message = "No resistance exercises on $station match your equipment and body constraints — block skipped.",
                fixLabel = limitedFix?.first,
                fixedConfig = limitedFix?.second,
            )
            return Result(emptyList(), warnings, emptySet(), available, unfilled)
        }

        val seq = setOrder(picks, mode)
        val rests = restsOf(seq, mode)
        val works = redistributeSeconds(
            initial = seq.map { windowFor(it.exercise).default },
            minBound = { i -> windowFor(seq[i].exercise).min },
            maxBound = { i -> windowFor(seq[i].exercise).max },
            targetSec = (blockSec - rests.sum()).coerceAtLeast(0),
        )

        val actual = works.sum() + rests.sum()
        if (blockSec - actual > blockSec / 20) {
            val n = picks.size
            val runs = "that block runs ${actual / 60} min ${actual % 60}s instead of the ${blockSec / 60} min planned"
            val capped = picks.size >= maxDistinct
            warnings += if (capped) GeneratorWarning(
                message = "With $n exercise${if (n == 1) "" else "s"} per category, $runs. " +
                    "More sets of the same few exercises would add fatigue, not benefit.",
                fixLabel = "Choose exercises automatically",
                fixedConfig = config.copy(exercisesPerCategory = null),
            ) else GeneratorWarning(
                message = "Only $n resistance exercise${if (n == 1) "" else "s"} on $station " +
                    "match${if (n == 1) "es" else ""} your equipment, so $runs. " +
                    "More sets than this would add fatigue, not benefit.",
                fixLabel = limitedFix?.first,
                fixedConfig = limitedFix?.second,
            )
        }

        val steps = mutableListOf<SessionStep>()
        seq.forEachIndexed { i, slot ->
            steps += setStep(
                ex = slot.exercise, machines = machines, easeIn = config.easeIn, blockIndex = blockIndex,
                durationSec = works[i], setIndex = slot.setIndex, setCount = slot.setCount,
            )
            if (i < seq.lastIndex) {
                steps += SessionStep(
                    type = StepType.REST,
                    category = cat,
                    exerciseName = "Rest",
                    durationSec = rests[i],
                    blockIndex = blockIndex,
                )
            }
        }
        val covered = picks.mapNotNull { it.exercise.resistance?.slot }.toSet()
        return Result(steps, warnings, covered, available, unfilled)
    }

    /**
     * One exercise per ladder: the rung nearest the user's level (the easier one on a tie), a
     * random pick among same-rung alternatives so sessions vary. Exercises on no ladder stand alone.
     */
    private fun representatives(inSlot: List<Exercise>, ladderRungs: Map<String, Int>): List<Exercise> =
        inSlot.groupBy { it.resistance?.ladder ?: "solo:${it.id}" }.map { (ladder, members) ->
            if (members.size == 1) members.first()
            else {
                val target = ladderRungs[ladder] ?: DEFAULT_RUNG
                val rung = members.map { it.resistance!!.rung }.distinct()
                    .minWith(compareBy({ abs(it - target) }, { it }))
                val atRung = members.filter { it.resistance!!.rung == rung }
                atRung[random.nextInt(atRung.size)]
            }
        }

    /**
     * Best retention tier first. Within a tier a multi-muscle movement comes before a
     * single-muscle one and a hold comes last, so a region's first exercise is its progressive
     * compound lift and accessories (a wall sit, a reverse Nordic) only arrive when there is time
     * for a second. Random beyond that.
     */
    private fun ranked(usable: List<Exercise>): List<Exercise> =
        usable.shuffled(random).sortedWith(
            compareBy<Exercise>({ tierRank(it.resistance!!.tier) })
                .thenByDescending { it.resistance!!.primary.size }
                .thenBy { it.resistance!!.isometric },
        )

    /** Add one set at a time, in order, to the named picks while the block still fits. */
    private fun topUp(picks: List<Pick>, ids: List<String>, cap: Int, fits: (List<Pick>) -> Boolean): List<Pick> {
        var out = picks
        var grew = true
        while (grew) {
            grew = false
            for (id in ids) {
                val i = out.indexOfFirst { it.exercise.id == id }
                if (i < 0 || out[i].sets >= cap) continue
                val trial = out.toMutableList().also { it[i] = it[i].copy(sets = it[i].sets + 1) }
                if (fits(trial)) {
                    out = trial
                    grew = true
                }
            }
        }
        return out
    }

    private fun underBudget(picks: List<Pick>, mode: RestMode, blockSec: Int): Boolean {
        val seq = setOrder(picks, mode)
        val maxTotal = seq.sumOf { windowFor(it.exercise).max } + restsOf(seq, mode).sum()
        return maxTotal < blockSec - blockSec / 20
    }

    /**
     * Set order. RECOVERY runs straight sets. The other modes alternate consecutive pairs
     * (A1 B1 A2 B2 …) so one muscle rests while another works — unless moving between the two
     * means re-rigging the reformer, in which case alternating would cost more than it saves.
     */
    fun setOrder(picks: List<Pick>, mode: RestMode): List<SetSlot> {
        val out = mutableListOf<SetSlot>()
        fun straight(p: Pick) {
            for (s in 1..p.sets) out += SetSlot(p.exercise, s, p.sets)
        }
        if (mode == RestMode.RECOVERY) {
            picks.forEach(::straight)
            return out
        }
        var i = 0
        while (i < picks.size) {
            val a = picks[i]
            val b = picks.getOrNull(i + 1)
            if (b != null && !requiresSetupChange(a.exercise, b.exercise)) {
                for (s in 1..maxOf(a.sets, b.sets)) {
                    if (s <= a.sets) out += SetSlot(a.exercise, s, a.sets)
                    if (s <= b.sets) out += SetSlot(b.exercise, s, b.sets)
                }
                i += 2
            } else {
                straight(a)
                i += 1
            }
        }
        return out
    }

    private fun restsOf(seq: List<SetSlot>, mode: RestMode): List<Int> =
        seq.zipWithNext().map { (a, b) -> restBetweenSets(mode, a.exercise, b.exercise) }

    companion object {
        val BIG_FOUR = listOf(
            MovementSlot.LOWER_PUSH, MovementSlot.UPPER_PUSH, MovementSlot.LOWER_PULL, MovementSlot.UPPER_PULL,
        )
        val ACCESSORIES = MovementSlot.entries.filter { it !in BIG_FOUR }

        /** The muscle whose weekly sets stand for each big-four region when deciding what is behind. */
        private val LEAD_MUSCLE = mapOf(
            MovementSlot.LOWER_PUSH to Muscle.QUADS,
            MovementSlot.UPPER_PUSH to Muscle.CHEST,
            MovementSlot.LOWER_PULL to Muscle.HAMSTRINGS,
            MovementSlot.UPPER_PULL to Muscle.BACK,
        )

        /** Ladder rung used until the user's own level is known: the middle of most ladders. */
        const val DEFAULT_RUNG = Ladders.DEFAULT_RUNG
        const val BASE_SETS = 2
        const val MAX_SETS = 3
        const val LAST_RESORT_SETS = 4

        private fun tierRank(t: EvidenceTier) = when (t) {
            EvidenceTier.STRONG -> 0; EvidenceTier.MODERATE -> 1; EvidenceTier.LIMITED -> 2
        }

        /**
         * Set windows (RESEARCH.md §12, all derived from rep-based studies):
         *  - light loads need 45–75 s of slow, continuous reps to get hard;
         *  - a load failed at in 8–15 reps needs 30–45 s;
         *  - long-length holds are useful at 20–45 s;
         *  - one-side-at-a-time work gets the window per side.
         */
        fun windowFor(profile: ResistanceProfile): Window {
            val base = when {
                profile.isometric -> Window(20, 35, 45)
                profile.loadClass == LoadClass.MODERATE -> Window(30, 40, 45)
                else -> Window(45, 60, 75)
            }
            return if (profile.unilateral) Window(base.min * 2, base.default * 2, base.max * 2) else base
        }

        fun windowFor(ex: Exercise): Window = windowFor(ex.resistance!!)

        /** Springs for reformer work; a plain reminder for hand-weight work; nothing otherwise. */
        fun setupCue(ex: Exercise, machines: MachineSettings): String? =
            MachineCueRenderer.renderCue(ex, machines)
                ?: if (Equipment.HAND_WEIGHTS in ex.equipment) "Use your heaviest hand weights." else null

        /**
         * One set of [ex] as a playable step. The only place a resistance set is assembled, so a
         * generated set and one produced by the Builder's swap button cannot drift apart.
         */
        fun setStep(
            ex: Exercise,
            machines: MachineSettings,
            easeIn: Boolean,
            blockIndex: Int,
            durationSec: Int,
            setIndex: Int,
            setCount: Int,
        ): SessionStep {
            val profile = ex.resistance!!
            return SessionStep(
                type = StepType.WORK,
                category = ex.category,
                exerciseId = ex.id,
                exerciseName = ex.name,
                durationSec = durationSec,
                machineCueText = setupCue(ex, machines),
                met = ex.met,
                animationId = ex.animationId,
                blockIndex = blockIndex,
                setIndex = setIndex,
                setCount = setCount,
                switchSides = profile.unilateral,
                effortCue = effortCue(profile, easeIn),
                primaryMuscles = profile.primary,
                secondaryMuscles = profile.secondary,
            )
        }

        /**
         * Re-derive "set n of m" after a hand edit (remove, reorder, swap) so the player never
         * announces a set number that no longer matches what is in the plan.
         */
        fun renumberSets(steps: List<SessionStep>): List<SessionStep> {
            val totals = steps.filter { it.isResistanceSet }
                .groupingBy { it.blockIndex to it.exerciseId }.eachCount()
            val seen = mutableMapOf<Pair<Int, String?>, Int>()
            return steps.map { s ->
                if (!s.isResistanceSet) s
                else {
                    val key = s.blockIndex to s.exerciseId
                    val n = (seen[key] ?: 0) + 1
                    seen[key] = n
                    s.copy(setIndex = n, setCount = totals.getValue(key))
                }
            }
        }

        /**
         * Rest before the next set. Repeating the same exercise needs a real rest (a small benefit
         * above 60 s, none detectable past 90 s — Singer 2024). Moving to a different muscle only
         * needs a transition, with at least 30 s when the reformer has to be re-rigged.
         */
        fun restBetweenSets(mode: RestMode, prev: Exercise, next: Exercise): Int {
            val same = prev.id == next.id
            val base = when (mode) {
                RestMode.STANDARD -> if (same) 60 else 30
                RestMode.RECOVERY -> if (same) 75 else 45
                RestMode.CONTINUOUS -> if (same) 45 else 20
            }
            return if (!same && requiresSetupChange(prev, next)) maxOf(base, 30) else base
        }

        const val CUE_EASE_IN =
            "Smooth reps: about three seconds down, two up. Stop with three or four good reps still in hand."
        const val CUE_LOW_LOAD =
            "Slow reps: three seconds down, two up, no resting at the top. Keep going until you couldn't do " +
                "another good rep, then tap Set done."
        const val CUE_MODERATE_LOAD =
            "Controlled reps. Stop with one to three good reps left, then tap Set done."
        const val CUE_HOLD =
            "Hold the hard position and keep breathing. Stop when your form goes."

        /**
         * The effort target, which is the part that actually protects muscle. Light loads only
         * work when sets end at the last good rep (contested but the safer reading — Lasevicius
         * 2022); heavier loads can stop 1–3 short (Refalo 2023). The ease-in target is a
         * convention for the first weeks, not a trial finding.
         */
        fun effortCue(profile: ResistanceProfile, easeIn: Boolean): String = when {
            profile.isometric -> CUE_HOLD
            easeIn -> CUE_EASE_IN
            profile.loadClass == LoadClass.MODERATE -> CUE_MODERATE_LOAD
            else -> CUE_LOW_LOAD
        }
    }
}
