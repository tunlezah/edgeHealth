package au.mark.kinetiq

import au.mark.kinetiq.ResistanceTestSupport.matLibrary
import au.mark.kinetiq.ResistanceTestSupport.reformerLibrary
import au.mark.kinetiq.ResistanceTestSupport.set
import au.mark.kinetiq.data.model.BodyArea
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.Equipment
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.MovementSlot
import au.mark.kinetiq.data.model.RestMode
import au.mark.kinetiq.data.model.SessionFocus
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.domain.generator.GeneratorResult
import au.mark.kinetiq.domain.generator.ResistanceBlockBuilder
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The set-based block builder behind muscle-retention sessions, against a synthetic library so
 * the behaviour is pinned independently of bundled content.
 */
class ResistanceBlockBuilderTest {

    private val mat = matLibrary()
    private val both = matLibrary() + reformerLibrary()

    private fun retention(
        minutes: Int = 30,
        categories: List<Category> = listOf(Category.FLOOR),
        restMode: RestMode = RestMode.STANDARD,
        handWeights: Boolean = false,
        gapFill: Boolean = false,
        warm: Boolean = false,
        perCategory: Int? = null,
    ) = GeneratorConfig(
        totalDurationMin = minutes, categories = categories, restMode = restMode,
        warmup = warm, cooldown = warm, useHealthData = false,
        focus = SessionFocus.MUSCLE_RETENTION, handWeights = handWeights, allowLimitedGapFill = gapFill,
        exercisesPerCategory = perCategory,
    )

    private fun generate(
        config: GeneratorConfig,
        library: List<Exercise> = mat,
        seed: Int = 7,
        profile: WorkoutGenerator.Profile = WorkoutGenerator.Profile(),
    ): GeneratorResult = WorkoutGenerator(library, emptyList(), random = Random(seed)).generate(config, profile)

    private fun GeneratorResult.sets(): List<SessionStep> = session.plan.steps.filter { it.isResistanceSet }
    private fun GeneratorResult.ids(): Set<String> = sets().mapNotNull { it.exerciseId }.toSet()
    private fun GeneratorResult.slots(library: List<Exercise>): Set<MovementSlot> {
        val byId = library.associateBy { it.id }
        return ids().mapNotNull { byId[it]?.resistance?.slot }.toSet()
    }

    // ------------------------------------------------------------------ structure

    @Test
    fun `a retention block is made of numbered sets, never single bouts`() {
        repeat(20) { seed ->
            val result = generate(retention(), seed = seed)
            val work = result.session.plan.steps.filter { it.type == StepType.WORK }
            assertThat(work).isNotEmpty()
            for (step in work) {
                assertThat(step.setIndex).isNotNull()
                assertThat(step.setCount).isNotNull()
                assertThat(step.effortCue).isNotEmpty()
                assertThat(step.primaryMuscles).isNotEmpty()
            }
            // Every exercise runs 2–4 sets, numbered 1..n in order.
            for ((_, steps) in work.groupBy { it.exerciseId }) {
                assertThat(steps.size).isIn(2..ResistanceBlockBuilder.LAST_RESORT_SETS)
                assertThat(steps.map { it.setIndex }).isEqualTo((1..steps.size).toList())
                assertThat(steps.map { it.setCount }.distinct()).containsExactly(steps.size)
            }
            assertThat(result.session.plan.blocks.single().isResistance).isTrue()
        }
    }

    @Test
    fun `set windows follow the load and side rules`() {
        val byId = mat.associateBy { it.id }
        repeat(20) { seed ->
            for (step in generate(retention(minutes = 45), seed = seed).sets()) {
                val profile = byId.getValue(step.exerciseId!!).resistance!!
                val window = ResistanceBlockBuilder.windowFor(profile)
                assertWithMessage("${step.exerciseId} ${step.durationSec}s").that(step.durationSec).isIn(window.min..window.max)
                assertThat(step.switchSides).isEqualTo(profile.unilateral)
            }
        }
        // Light bilateral work: 45–75 s. One side at a time: that window per side. Holds: 20–45 s.
        assertThat(ResistanceBlockBuilder.windowFor(byId.getValue("pushup"))).isEqualTo(ResistanceBlockBuilder.Window(45, 60, 75))
        assertThat(ResistanceBlockBuilder.windowFor(byId.getValue("split_squat"))).isEqualTo(ResistanceBlockBuilder.Window(90, 120, 150))
        assertThat(ResistanceBlockBuilder.windowFor(byId.getValue("wall_sit"))).isEqualTo(ResistanceBlockBuilder.Window(20, 35, 45))
    }

    @Test
    fun `no rest trails the final set and every other set is followed by one`() {
        val steps = generate(retention()).session.plan.steps
        assertThat(steps.last().type).isEqualTo(StepType.WORK)
        steps.zipWithNext().forEach { (a, b) ->
            if (a.type == StepType.WORK) assertThat(b.type).isEqualTo(StepType.REST)
            if (a.type == StepType.REST) assertThat(b.type).isEqualTo(StepType.WORK)
        }
    }

    @Test
    fun `STANDARD alternates pairs with short transitions and rests a full minute before repeating`() {
        val steps = generate(retention(restMode = RestMode.STANDARD)).session.plan.steps
        steps.windowed(3).filter { it[1].type == StepType.REST }.forEach { (prev, rest, next) ->
            val expected = if (prev.exerciseId == next.exerciseId) 60 else 30
            assertThat(rest.durationSec).isEqualTo(expected)
        }
        // At least one alternating pair exists: A1 B1 A2.
        val order = steps.filter { it.type == StepType.WORK }.map { it.exerciseId }
        assertThat(order.windowed(3).any { (a, b, c) -> a == c && a != b }).isTrue()
    }

    @Test
    fun `RECOVERY runs straight sets with the longest rests`() {
        val steps = generate(retention(restMode = RestMode.RECOVERY)).session.plan.steps
        val order = steps.filter { it.type == StepType.WORK }.map { it.exerciseId }
        // Straight sets: once an exercise is left it never comes back.
        assertThat(order.zipWithNext().count { (a, b) -> a != b }).isEqualTo(order.distinct().size - 1)
        steps.windowed(3).filter { it[1].type == StepType.REST }.forEach { (prev, rest, next) ->
            assertThat(rest.durationSec).isEqualTo(if (prev.exerciseId == next.exerciseId) 75 else 45)
        }
    }

    @Test
    fun `reformer exercises on different springs are not alternated`() {
        val library = listOf(
            set("a", MovementSlot.BICEPS, cat = Category.REFORMER, springs = "HEAVY_2"),
            set("b", MovementSlot.TRICEPS, cat = Category.REFORMER, springs = "LIGHT"),
        )
        val order = generate(retention(categories = listOf(Category.REFORMER)), library)
            .session.plan.steps.filter { it.type == StepType.WORK }.map { it.exerciseId }
        assertThat(order.zipWithNext().count { (x, y) -> x != y }).isEqualTo(1)
        assertThat(ResistanceBlockBuilder.restBetweenSets(RestMode.CONTINUOUS, library[0], library[1])).isEqualTo(30)
    }

    // ------------------------------------------------------------------ selection

    @Test
    fun `the big four are covered before accessories when the library allows`() {
        repeat(20) { seed ->
            val slots = generate(retention(minutes = 45, handWeights = true), seed = seed).slots(mat)
            assertThat(slots).containsAtLeast(
                MovementSlot.LOWER_PUSH, MovementSlot.UPPER_PUSH, MovementSlot.LOWER_PULL, MovementSlot.UPPER_PULL,
            )
        }
    }

    @Test
    fun `a short session still rotates through the big four across runs`() {
        // 10 minutes fits about two exercises; the random rotation must not always pick the same two.
        val seen = mutableSetOf<MovementSlot>()
        repeat(40) { seed -> seen += generate(retention(minutes = 10, handWeights = true), seed = seed).slots(mat) }
        assertThat(seen).containsAtLeast(
            MovementSlot.LOWER_PUSH, MovementSlot.UPPER_PUSH, MovementSlot.LOWER_PULL, MovementSlot.UPPER_PULL,
        )
    }

    @Test
    fun `one exercise per ladder, at the rung nearest the user's level`() {
        repeat(20) { seed ->
            val ids = generate(retention(minutes = 60), seed = seed).ids()
            assertThat(ids.intersect(setOf("knee_pushup", "pushup")).size).isAtMost(1)
            assertThat(ids.intersect(setOf("squat", "split_squat")).size).isAtMost(1)
            // Default rung is 2.
            assertThat(ids).doesNotContain("pushup")
            assertThat(ids).doesNotContain("squat")
        }
        val advanced = WorkoutGenerator.Profile(ladderRungs = mapOf("pushup" to 3, "leg_push" to 1))
        repeat(20) { seed ->
            val ids = generate(retention(minutes = 60), seed = seed, profile = advanced).ids()
            assertThat(ids).doesNotContain("knee_pushup")
            assertThat(ids).doesNotContain("split_squat")
        }
    }

    @Test
    fun `a ladder falls back to the nearest rung the equipment allows`() {
        // Rung 2 needs weights; without them the only rung-1 option is LIMITED, so nothing is picked
        // unless gap filling is on.
        val off = generate(retention(minutes = 45)).ids()
        assertThat(off).containsNoneOf("prone_ytw", "prone_ytw_weighted")
        val filled = generate(retention(minutes = 45, gapFill = true)).ids()
        assertThat(filled).contains("prone_ytw")
        val weighted = generate(retention(minutes = 45, handWeights = true, gapFill = true)).ids()
        assertThat(weighted).contains("prone_ytw_weighted")
        assertThat(weighted).doesNotContain("prone_ytw")
    }

    @Test
    fun `limited-tier exercises never appear by default and never displace a better option`() {
        val limited = both.filter { it.resistance?.tier == EvidenceTier.LIMITED }.map { it.id }.toSet()
        val cats = listOf(Category.FLOOR, Category.REFORMER)
        repeat(20) { seed ->
            assertThat(generate(retention(60, cats), both, seed).ids().intersect(limited)).isEmpty()
        }
        // With the box, the reformer has a MODERATE pull — the LIMITED row must stay out even with gap fill on.
        val withBox = WorkoutGenerator.Profile(ownedEquipment = setOf(Equipment.REFORMER_BOX))
        repeat(20) { seed ->
            val ids = generate(retention(60, listOf(Category.REFORMER), gapFill = true), both, seed, withBox).ids()
            assertThat(ids).contains("ref_box_pull")
            assertThat(ids).doesNotContain("ref_row")
        }
        // Without it, gap fill brings the row in.
        assertThat(generate(retention(60, listOf(Category.REFORMER), gapFill = true), both).ids()).contains("ref_row")
    }

    @Test
    fun `the global low-evidence setting also only fills gaps`() {
        val profile = WorkoutGenerator.Profile(includeLowEvidence = true)
        repeat(20) { seed ->
            val ids = generate(retention(60, handWeights = true), seed = seed, profile = profile).ids()
            assertThat(ids).doesNotContain("prone_ytw") // the weighted MODERATE version exists
        }
    }

    @Test
    fun `hand-weight exercises need the session chip and a heavy enough weight`() {
        val weighted = mat.filter { Equipment.HAND_WEIGHTS in it.equipment }.map { it.id }.toSet()
        repeat(20) { seed ->
            assertThat(generate(retention(60), seed = seed).ids().intersect(weighted)).isEmpty()
        }
        assertThat(generate(retention(60, handWeights = true)).ids()).containsAtLeast("lateral_raise", "prone_ytw_weighted")
        // 1.5 kg reaches the prone raise's 1 kg minimum but not the lateral raise's 2 kg.
        val light = WorkoutGenerator.Profile(handWeightMaxKg = 1.5f)
        val ids = generate(retention(60, handWeights = true), profile = light).ids()
        assertThat(ids).contains("prone_ytw_weighted")
        assertThat(ids).doesNotContain("lateral_raise")
    }

    @Test
    fun `body constraints hard-exclude resistance exercises too`() {
        val library = mat.map { if (it.id == "split_squat") it.copy(contraindications = listOf(BodyArea.KNEE)) else it }
        val profile = WorkoutGenerator.Profile(constraints = setOf(BodyArea.KNEE), ladderRungs = mapOf("leg_push" to 2))
        repeat(20) { seed ->
            assertThat(generate(retention(60), library, seed, profile).ids()).doesNotContain("split_squat")
        }
    }

    @Test
    fun `a later block trains what the earlier one could not`() {
        // A mat with no pulling option at all (the unweighted prone raise removed).
        val library = both.filter { it.id != "prone_ytw" }
        val cats = listOf(Category.FLOOR, Category.REFORMER)
        repeat(20) { seed ->
            val result = generate(retention(40, cats, gapFill = true), library, seed)
            val reformerIds = result.sets().filter { it.category == Category.REFORMER }.mapNotNull { it.exerciseId }
            // The mat could not pull, so the reformer's first job is the row — ahead of arm work.
            assertThat(reformerIds.first()).isEqualTo("ref_row")
        }
    }

    @Test
    fun `exercises per category caps the number of distinct exercises`() {
        repeat(10) { seed ->
            assertThat(generate(retention(45, perCategory = 2), seed = seed).ids().size).isAtMost(2)
        }
    }

    // ------------------------------------------------------------------ warnings

    @Test
    fun `an uncoverable region is reported with the fix that would cover it`() {
        // Mat only, no weights: pulling exists only as a LIMITED option.
        val result = generate(retention(45))
        val pull = result.warnings.single { it.message.contains("upper-body pull") }
        assertThat(pull.fixLabel).isEqualTo("Add limited-evidence fillers")
        assertThat(pull.fixedConfig!!.allowLimitedGapFill).isTrue()
        assertThat(generate(pull.fixedConfig!!).warnings.none { it.message.contains("upper-body pull") }).isTrue()
    }

    @Test
    fun `hand weights are offered when they would fill a region nothing else can`() {
        val library = mat.filter { it.id != "prone_ytw" }
        val warning = generate(retention(45), library).warnings.single { it.message.contains("upper-body pull") }
        assertThat(warning.fixLabel).isEqualTo("Use hand weights")
        assertThat(generate(warning.fixedConfig!!, library).slots(library)).contains(MovementSlot.UPPER_PULL)
    }

    @Test
    fun `another station is offered when it holds the missing region`() {
        val library = (mat.filter { it.resistance?.slot != MovementSlot.UPPER_PULL } + reformerLibrary())
        val profile = WorkoutGenerator.Profile(ownedEquipment = setOf(Equipment.REFORMER_BOX))
        val warning = generate(retention(45), library, profile = profile).warnings
            .single { it.message.contains("upper-body pull") }
        assertThat(warning.fixLabel).isEqualTo("Add Reformer")
        assertThat(warning.fixedConfig!!.categories).containsExactly(Category.FLOOR, Category.REFORMER).inOrder()
    }

    @Test
    fun `regions squeezed out by a short session are not reported as gaps`() {
        val result = generate(retention(10, handWeights = true))
        assertThat(result.warnings.none { it.message.contains("muscles") }).isTrue()
    }

    @Test
    fun `resistance after cardio is flagged with a reorder fix`() {
        val result = generate(retention(40, listOf(Category.SPIN, Category.FLOOR)))
        val warning = result.warnings.single { it.fixLabel == "Resistance first" }
        assertThat(warning.fixedConfig!!.categories).containsExactly(Category.FLOOR, Category.SPIN).inOrder()
        assertThat(generate(warning.fixedConfig!!).warnings.none { it.fixLabel == "Resistance first" }).isTrue()
    }

    @Test
    fun `a retention session with no resistance station says so`() {
        val result = generate(retention(20, listOf(Category.BACK)))
        assertThat(result.warnings.single { it.fixLabel == "Add Floor" }.fixedConfig!!.categories)
            .containsExactly(Category.FLOOR, Category.BACK).inOrder()
    }

    @Test
    fun `the visceral-fat cardio nag is not raised for retention sessions`() {
        val config = retention(30).copy(useHealthData = true)
        assertThat(generate(config).warnings.none { it.message.contains("visceral-fat") }).isTrue()
        // …but still is for the same session in GENERAL focus.
        val general = generate(config.copy(focus = SessionFocus.GENERAL))
        assertThat(general.warnings.any { it.message.contains("visceral-fat") }).isTrue()
    }

    // ------------------------------------------------------------------ budget

    @Test
    fun `retention plans hit the requested duration or explain the deviation`() {
        val categorySets = listOf(
            listOf(Category.FLOOR), listOf(Category.REFORMER), listOf(Category.FLOOR, Category.REFORMER),
        )
        for (min in listOf(5, 10, 15, 20, 30, 45, 60)) for (cats in categorySets)
            for (mode in RestMode.entries) for (weights in listOf(false, true)) for (warm in listOf(false, true)) {
                val config = retention(min, cats, mode, handWeights = weights, gapFill = true, warm = warm)
                repeat(5) { seed ->
                    val result = generate(config, both, seed)
                    val total = result.session.plan.totalSec
                    val requested = min * 60
                    val within = abs(total - requested) <= requested / 20
                    val explained = result.warnings.any { w ->
                        w.plannedTotalSec?.let { abs(total - it) <= maxOf(1, total / 20) } == true
                    }
                    assertWithMessage("min=$min cats=$cats mode=$mode weights=$weights warm=$warm seed=$seed total=$total")
                        .that(within || explained).isTrue()
                    assertThat(result.session.plan.steps.all { it.durationSec > 0 }).isTrue()
                }
            }
    }

    @Test
    fun `a block the library cannot fill ends early rather than piling on sets`() {
        val library = listOf(set("only", MovementSlot.UPPER_PUSH))
        val result = generate(retention(45), library)
        val sets = result.sets()
        assertThat(sets.size).isEqualTo(ResistanceBlockBuilder.LAST_RESORT_SETS)
        assertThat(result.warnings.any { it.message.contains("Only 1 resistance exercise") }).isTrue()
        assertThat(result.warnings.any { it.plannedTotalSec != null }).isTrue()
    }

    // ------------------------------------------------------------------ GENERAL is untouched

    @Test
    fun `general sessions never contain an exercise that needs equipment the user lacks`() {
        val needsKit = both.filter { it.equipment.isNotEmpty() }.map { it.id }.toSet()
        repeat(30) { seed ->
            val config = GeneratorConfig(
                totalDurationMin = 45, categories = listOf(Category.FLOOR, Category.REFORMER), useHealthData = false,
            )
            val ids = generate(config, both, seed).session.plan.steps.mapNotNull { it.exerciseId }
            assertThat(ids.intersect(needsKit)).isEmpty()
            assertThat(generate(config, both, seed).session.plan.steps.none { it.isResistanceSet }).isTrue()
        }
    }

    @Test
    fun `adding content the user has no equipment for leaves general sessions byte-identical`() {
        val withoutKit = both.filter { it.equipment.isEmpty() }
        for (mode in RestMode.entries) repeat(15) { seed ->
            val config = GeneratorConfig(
                totalDurationMin = 30, categories = listOf(Category.FLOOR, Category.REFORMER),
                restMode = mode, useHealthData = false,
            )
            assertThat(generate(config, both, seed).session.plan)
                .isEqualTo(generate(config, withoutKit, seed).session.plan)
        }
    }

    @Test
    fun `renumbering keeps set labels truthful after a hand edit`() {
        val steps = generate(retention(30, restMode = RestMode.RECOVERY)).session.plan.steps
        val firstId = steps.first { it.isResistanceSet }.exerciseId
        val before = steps.count { it.exerciseId == firstId }
        val edited = ResistanceBlockBuilder.renumberSets(steps.drop(1)) // remove set 1 of the first exercise
        val remaining = edited.filter { it.exerciseId == firstId }
        assertThat(remaining.map { it.setIndex }).isEqualTo((1 until before).toList())
        assertThat(remaining.map { it.setCount }.distinct()).containsExactly(before - 1)
    }
}
