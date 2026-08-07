package au.mark.kinetiq.domain.generator

import au.mark.kinetiq.data.model.BodyArea
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.ExerciseKind
import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.Intensity
import au.mark.kinetiq.data.model.NamedRoutine
import au.mark.kinetiq.data.model.SessionBlock
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.model.Target
import au.mark.kinetiq.data.model.WorkoutPlan
import au.mark.kinetiq.data.repo.BodyMetrics
import au.mark.kinetiq.data.repo.MachineSettings
import au.mark.kinetiq.domain.MachineCueRenderer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/** A generator warning with an optional one-tap fix. */
data class GeneratorWarning(
    val message: String,
    val fixLabel: String? = null,
    val fixedConfig: GeneratorConfig? = null,
)

data class GeneratorResult(
    val session: GeneratedSession,
    val warnings: List<GeneratorWarning>,
)

/**
 * Rule-based session generator.
 *
 * Structure: [warm-up] + one block per selected category (in the user's order, never
 * interleaved) + [cool-down], with a spoken transition pause between blocks.
 *
 * FLOOR / REFORMER blocks are sequences of discrete timed exercises with rests sized by the
 * work:rest ratio. SPIN / ELLIPTICAL blocks are continuous coached intervals — either a bundled
 * named routine scaled to the block length, or a sequence assembled from interval segments.
 *
 * ## Personalization heuristics (applied only when health data is available AND the toggle is on)
 * - BMI >= 30 **or** body fat above 32% (typical high-adiposity threshold) biases selection
 *   toward `impact = LOW` and removes VERY_HIGH intensity work from the first half of each block,
 *   per the graded-progression guidance in RESEARCH.md §1.
 * - Visceral-fat goal (default on): at least 50% of session work time targets MODERATE+
 *   cardio ([Target.VISCERAL_FAT]/[Target.CARDIO]); when the user's intensity preference is
 *   HIGH/VERY_HIGH, HIIT-patterned segments are preferred on machine blocks, matching the
 *   meta-analytic evidence that both MICT and HIIT reduce VAT with HIIT more time-efficient.
 * - Body constraints hard-exclude any exercise listing a matching contraindication.
 *
 * ## Evidence gating
 * LIMITED-tier entries never enter auto-generation unless the user has enabled
 * "Include low-evidence exercises" in Settings.
 */
class WorkoutGenerator(
    private val exercises: List<Exercise>,
    private val routines: List<NamedRoutine>,
    private val machines: MachineSettings = MachineSettings(),
    private val random: Random = Random.Default,
) {

    data class Profile(
        val constraints: Set<BodyArea> = emptySet(),
        val includeLowEvidence: Boolean = false,
        val visceralFatGoal: Boolean = true,
        val metrics: BodyMetrics? = null,
    )

    fun generate(config: GeneratorConfig, profile: Profile = Profile()): GeneratorResult {
        val warnings = mutableListOf<GeneratorWarning>()
        val categories = config.categories.ifEmpty { listOf(Category.FLOOR) }

        val highAdiposity = config.useHealthData && profile.metrics?.let { m ->
            (m.bmi ?: 0.0) >= 30.0 || (m.bodyFatPct ?: 0.0) >= 32.0
        } == true

        // --- Time budget ---
        val totalSec = config.totalDurationMin * 60
        val transitionsSec = if (categories.size > 1) (categories.size - 1) * config.transitionSec else 0
        val warmupSec = if (config.warmup) warmCoolSlice(totalSec) else 0
        val cooldownSec = if (config.cooldown) warmCoolSlice(totalSec) else 0
        var mainSec = totalSec - transitionsSec - warmupSec - cooldownSec
        if (mainSec < 5 * 60 * categories.size) {
            warnings += GeneratorWarning(
                message = "That leaves under 5 minutes of work per category. Consider a longer session or fewer categories.",
                fixLabel = "Set ${(5 * categories.size) + ((warmupSec + cooldownSec + transitionsSec) / 60) + 1} min",
                fixedConfig = config.copy(totalDurationMin = (5 * categories.size) + ((warmupSec + cooldownSec + transitionsSec) / 60) + 1),
            )
            mainSec = max(mainSec, 4 * 60 * categories.size)
        }

        // --- Split main time across categories by weight ---
        val weightSum = categories.sumOf { (config.categoryWeights[it] ?: 1f).toDouble() }
        val blockSec = categories.associateWith { cat ->
            ((config.categoryWeights[cat] ?: 1f) / weightSum * mainSec).roundToInt()
        }

        val steps = mutableListOf<SessionStep>()
        val blocks = mutableListOf<SessionBlock>()

        if (warmupSec > 0) steps += warmupSteps(categories.first(), warmupSec, profile, blockIndex = 0)

        categories.forEachIndexed { index, cat ->
            if (index > 0) {
                steps += SessionStep(
                    type = StepType.TRANSITION,
                    category = cat,
                    exerciseName = "Move to ${stationName(cat)}",
                    durationSec = config.transitionSec,
                    blockIndex = index,
                )
            }
            val secs = blockSec.getValue(cat)
            when (cat) {
                Category.FLOOR, Category.REFORMER -> {
                    val (blockSteps, blockWarnings) = discreteBlock(cat, secs, config, profile, highAdiposity, index)
                    steps += blockSteps
                    warnings += blockWarnings
                    blocks += SessionBlock(category = cat, isHiit = false)
                }
                Category.SPIN, Category.ELLIPTICAL -> {
                    val (blockSteps, block) = machineBlock(cat, secs, config, profile, highAdiposity, index)
                    steps += blockSteps
                    blocks += block
                }
            }
        }

        if (cooldownSec > 0) steps += cooldownSteps(categories.last(), cooldownSec, profile, blockIndex = categories.size - 1)

        // Visceral-fat goal check: >= 50% of WORK time should be MODERATE+ cardio-targeted.
        if (profile.visceralFatGoal && config.useHealthData) {
            val byId = exercises.associateBy { it.id }
            val workSteps = steps.filter { it.type == StepType.WORK }
            val workSec = workSteps.sumOf { it.durationSec }
            val cardioSec = workSteps.filter { s ->
                val ex = s.exerciseId?.let { byId[it] }
                ex != null && ex.intensity != Intensity.LOW &&
                    (Target.VISCERAL_FAT in ex.targets || Target.CARDIO in ex.targets)
            }.sumOf { it.durationSec }
            if (workSec > 0 && cardioSec.toDouble() / workSec < 0.5) {
                val cardioCats = listOf(Category.SPIN, Category.ELLIPTICAL).filter { it !in categories }
                warnings += GeneratorWarning(
                    message = "Under half of this session is moderate+ cardio — light on your visceral-fat goal." +
                        if (cardioCats.isNotEmpty()) " Adding ${stationName(cardioCats.first())} time would help." else "",
                    fixLabel = cardioCats.firstOrNull()?.let { "Add ${stationName(it)}" },
                    fixedConfig = cardioCats.firstOrNull()?.let { config.copy(categories = categories + it) },
                )
            }
        }

        val plan = WorkoutPlan(steps = steps, blocks = blocks)
        return GeneratorResult(GeneratedSession(config, plan), warnings)
    }

    // ---------------------------------------------------------------------------------------
    // Selection pool

    private fun pool(cat: Category, profile: Profile, kind: ExerciseKind? = null): List<Exercise> =
        exercises.filter { ex ->
            ex.category == cat &&
                (kind == null || ex.kind == kind) &&
                !ex.isWarmupCooldown &&
                (profile.includeLowEvidence || ex.evidenceTier != EvidenceTier.LIMITED) &&
                ex.contraindications.none { it in profile.constraints }
        }

    private fun intensityRank(i: Intensity) = when (i) {
        Intensity.LOW -> 0; Intensity.MODERATE -> 1; Intensity.HIGH -> 2; Intensity.VERY_HIGH -> 3
    }

    private fun allowedByPreference(ex: Exercise, pref: Intensity): Boolean =
        intensityRank(ex.intensity) <= intensityRank(pref) + 1

    // ---------------------------------------------------------------------------------------
    // Discrete blocks (FLOOR / REFORMER)

    private fun discreteBlock(
        cat: Category,
        blockSec: Int,
        config: GeneratorConfig,
        profile: Profile,
        highAdiposity: Boolean,
        blockIndex: Int,
    ): Pair<List<SessionStep>, List<GeneratorWarning>> {
        val warnings = mutableListOf<GeneratorWarning>()
        var candidates = pool(cat, profile, ExerciseKind.DISCRETE)
            .filter { allowedByPreference(it, config.intensity) }
        if (highAdiposity) {
            val lowImpact = candidates.filter { it.impact == au.mark.kinetiq.data.model.Impact.LOW }
            if (lowImpact.size >= 4) candidates = lowImpact
        }
        if (candidates.isEmpty()) return emptyList<SessionStep>() to listOf(
            GeneratorWarning("No ${stationName(cat)} exercises match your constraints — block skipped.")
        )

        // Duration/count solver: how many exercises fit with sensible work+rest times?
        val ratio = config.workRestRatio.coerceIn(0.5f, 6f)
        val requested = config.exercisesPerCategory
        val count: Int
        var workSec: Int
        if (requested != null && requested > 0) {
            count = requested
            workSec = solveWorkSec(blockSec, count, ratio)
            if (workSec < 20) {
                val fixedCount = max(1, countFor(blockSec, 30, ratio))
                warnings += GeneratorWarning(
                    message = "$requested exercises in ${blockSec / 60} min leaves only ${workSec}s each — too short to be useful.",
                    fixLabel = "Use $fixedCount exercises",
                    fixedConfig = config.copy(exercisesPerCategory = fixedCount),
                )
                workSec = max(workSec, 15)
            }
        } else {
            count = countFor(blockSec, defaultWork = 40, ratio = ratio).coerceIn(3, 12)
            workSec = solveWorkSec(blockSec, count, ratio)
        }

        val picked = pickBalanced(candidates, count, highAdiposity)
        val steps = mutableListOf<SessionStep>()
        picked.forEachIndexed { i, ex ->
            // Cap VERY_HIGH work early in the block for high-adiposity users.
            val effective = if (highAdiposity && i < picked.size / 2 && ex.intensity == Intensity.VERY_HIGH) {
                picked.firstOrNull { it.intensity != Intensity.VERY_HIGH && it != ex } ?: ex
            } else ex
            val w = workSec.coerceIn(effective.minSec, effective.maxSec)
            steps += SessionStep(
                type = StepType.WORK,
                category = cat,
                exerciseId = effective.id,
                exerciseName = effective.name,
                durationSec = w,
                machineCueText = MachineCueRenderer.renderCue(effective, machines),
                met = effective.met,
                animationId = effective.animationId,
                blockIndex = blockIndex,
            )
            if (i < picked.size - 1) {
                val rest = (w / ratio).roundToInt().coerceIn(10, 90)
                steps += SessionStep(
                    type = StepType.REST,
                    category = cat,
                    exerciseName = "Rest",
                    durationSec = rest,
                    blockIndex = blockIndex,
                )
            }
        }
        return steps to warnings
    }

    /** work = blockSec / (count + (count-1)/ratio) with the last exercise having no rest after it. */
    internal fun solveWorkSec(blockSec: Int, count: Int, ratio: Float): Int {
        if (count <= 0) return 0
        val denom = count + (count - 1) / ratio
        return (blockSec / denom).toInt()
    }

    private fun countFor(blockSec: Int, defaultWork: Int, ratio: Float): Int {
        val slot = defaultWork + defaultWork / ratio
        return max(1, (blockSec / slot).toInt())
    }

    /** Prefer covering distinct targets; shuffle for variety; bias low impact when asked. */
    private fun pickBalanced(candidates: List<Exercise>, count: Int, lowImpactBias: Boolean): List<Exercise> {
        val shuffled = candidates.shuffled(random)
            .sortedByDescending { ex ->
                var score = ex.targets.size + random.nextInt(3)
                if (lowImpactBias && ex.impact == au.mark.kinetiq.data.model.Impact.LOW) score += 2
                if (ex.evidenceTier == EvidenceTier.STRONG) score += 1
                score
            }
        val picked = mutableListOf<Exercise>()
        val covered = mutableSetOf<Target>()
        for (ex in shuffled) {
            if (picked.size >= count) break
            if (ex.targets.any { it !in covered } || picked.size < count / 2) {
                picked += ex
                covered += ex.targets
            }
        }
        for (ex in shuffled) {
            if (picked.size >= count) break
            if (ex !in picked) picked += ex
        }
        // Repeat the pool if the user asked for more sets than there are distinct exercises.
        while (picked.size < count && candidates.isNotEmpty()) picked += picked[picked.size % candidates.size]
        return picked.take(count)
    }

    // ---------------------------------------------------------------------------------------
    // Machine blocks (SPIN / ELLIPTICAL)

    private fun machineBlock(
        cat: Category,
        blockSec: Int,
        config: GeneratorConfig,
        profile: Profile,
        highAdiposity: Boolean,
        blockIndex: Int,
    ): Pair<List<SessionStep>, SessionBlock> {
        val byId = exercises.associateBy { it.id }
        val wantHiit = !highAdiposity &&
            intensityRank(config.intensity) >= intensityRank(Intensity.HIGH) &&
            profile.visceralFatGoal

        // Prefer a named routine that fits the block, scaled proportionally.
        val fitting = routines.filter { r ->
            r.category == cat &&
                r.steps.all { s -> byId[s.exerciseId]?.contraindications?.none { it in profile.constraints } != false } &&
                (profile.includeLowEvidence || r.steps.all { s -> byId[s.exerciseId]?.evidenceTier != EvidenceTier.LIMITED }) &&
                intensityRank(r.intensity) <= intensityRank(config.intensity) + 1 &&
                (!highAdiposity || intensityRank(r.intensity) <= intensityRank(Intensity.HIGH))
        }
        val preferred = if (wantHiit) fitting.filter { intensityRank(it.intensity) >= intensityRank(Intensity.HIGH) } else fitting
        val routine = (preferred.ifEmpty { fitting }).minByOrNull {
            kotlin.math.abs(it.totalSec - blockSec) + random.nextInt(60)
        }

        if (routine != null) {
            val scale = blockSec.toDouble() / routine.totalSec
            val isHiit = intensityRank(routine.intensity) >= intensityRank(Intensity.HIGH)
            val steps = routine.steps.mapNotNull { s ->
                val ex = byId[s.exerciseId] ?: return@mapNotNull null
                val dur = max(ex.minSec, min(ex.maxSec * 3, (s.durationSec * scale).roundToInt()))
                SessionStep(
                    type = StepType.WORK,
                    category = cat,
                    exerciseId = ex.id,
                    exerciseName = ex.name,
                    durationSec = dur,
                    machineCueText = MachineCueRenderer.renderCue(ex, machines),
                    met = ex.met,
                    animationId = ex.animationId,
                    blockIndex = blockIndex,
                )
            }
            return steps to SessionBlock(category = cat, isHiit = isHiit, routineName = routine.name)
        }

        // Fallback: assemble segments to fill the block.
        var candidates = pool(cat, profile, ExerciseKind.INTERVAL_SEGMENT)
            .filter { allowedByPreference(it, config.intensity) }
        if (highAdiposity) candidates = candidates.filter { it.intensity != Intensity.VERY_HIGH }.ifEmpty { candidates }
        if (candidates.isEmpty()) return emptyList<SessionStep>() to SessionBlock(category = cat)

        val steps = mutableListOf<SessionStep>()
        var remaining = blockSec
        var i = 0
        val ordered = candidates.shuffled(random)
        while (remaining >= 30 && i < 60) {
            val ex = ordered[i % ordered.size]
            val dur = min(remaining, ex.defaultWorkSec)
            steps += SessionStep(
                type = StepType.WORK,
                category = cat,
                exerciseId = ex.id,
                exerciseName = ex.name,
                durationSec = dur,
                machineCueText = MachineCueRenderer.renderCue(ex, machines),
                met = ex.met,
                animationId = ex.animationId,
                blockIndex = blockIndex,
            )
            remaining -= dur
            i++
        }
        val isHiit = steps.count { s -> byId[s.exerciseId]?.intensity?.let(::intensityRank) == 3 } >= steps.size / 3
        return steps to SessionBlock(category = cat, isHiit = isHiit)
    }

    // ---------------------------------------------------------------------------------------
    // Warm-up / cool-down

    /** 3–5 minutes scaled to session length (10% of total, clamped). */
    internal fun warmCoolSlice(totalSec: Int): Int = (totalSec / 10).coerceIn(3 * 60, 5 * 60)

    private fun warmupSteps(cat: Category, seconds: Int, profile: Profile, blockIndex: Int): List<SessionStep> =
        warmCool(cat, seconds, StepType.WARMUP, profile, blockIndex)

    private fun cooldownSteps(cat: Category, seconds: Int, profile: Profile, blockIndex: Int): List<SessionStep> =
        warmCool(cat, seconds, StepType.COOLDOWN, profile, blockIndex)

    private fun warmCool(cat: Category, seconds: Int, type: StepType, profile: Profile, blockIndex: Int): List<SessionStep> {
        val pool = exercises.filter {
            it.isWarmupCooldown && it.contraindications.none { c -> c in profile.constraints } &&
                (it.category == cat || it.category == Category.FLOOR)
        }.sortedByDescending { it.category == cat }
        if (pool.isEmpty()) return emptyList()
        val chosen = pool.take(max(1, seconds / 60))
        val per = seconds / chosen.size
        return chosen.map { ex ->
            SessionStep(
                type = type,
                category = ex.category,
                exerciseId = ex.id,
                exerciseName = ex.name,
                durationSec = per.coerceAtLeast(30),
                machineCueText = MachineCueRenderer.renderCue(ex, machines),
                met = ex.met,
                animationId = ex.animationId,
                blockIndex = blockIndex,
            )
        }
    }

    companion object {
        fun stationName(cat: Category): String = when (cat) {
            Category.FLOOR -> "the mat"
            Category.REFORMER -> "the reformer"
            Category.SPIN -> "the bike"
            Category.ELLIPTICAL -> "the elliptical"
        }
    }
}
