package au.mark.kinetiq

import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.Equipment
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.ExerciseDatabaseFile
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.MovementSlot
import au.mark.kinetiq.data.model.RestMode
import au.mark.kinetiq.data.model.SessionFocus
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.domain.generator.GeneratorResult
import au.mark.kinetiq.domain.generator.ResistanceBlockBuilder
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import au.mark.kinetiq.domain.resistance.Ladders
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

/**
 * Muscle-retention sessions against the bundled database: what the shipped content actually
 * delivers with the kit the app assumes by default (a mat, a reformer, hand weights up to 2 kg).
 * These pin the claims made in the Builder copy, DECISIONS D-20–D-28 and RESEARCH §12.
 */
class ResistanceContentTest {

    private lateinit var db: ExerciseDatabaseFile

    @Before
    fun setup() {
        db = Json { ignoreUnknownKeys = true }.decodeFromString(
            ExerciseDatabaseFile.serializer(), File("src/main/assets/exercise_db.json").readText(),
        )
    }

    private val bigFour = ResistanceBlockBuilder.BIG_FOUR

    private fun retention(
        minutes: Int,
        categories: List<Category>,
        handWeights: Boolean = false,
        gapFill: Boolean = false,
        restMode: RestMode = RestMode.STANDARD,
        warm: Boolean = false,
    ) = GeneratorConfig(
        totalDurationMin = minutes, categories = categories, restMode = restMode, warmup = warm, cooldown = warm,
        useHealthData = false, focus = SessionFocus.MUSCLE_RETENTION, handWeights = handWeights,
        allowLimitedGapFill = gapFill,
    )

    private fun generate(config: GeneratorConfig, seed: Int, profile: WorkoutGenerator.Profile = WorkoutGenerator.Profile()) =
        WorkoutGenerator(db.exercises, db.routines, random = Random(seed)).generate(config, profile)

    private fun GeneratorResult.setIds(): Set<String> =
        session.plan.steps.filter { it.isResistanceSet }.mapNotNull { it.exerciseId }.toSet()

    private fun GeneratorResult.slots(): Set<MovementSlot> {
        val byId = db.exercises.associateBy { it.id }
        return setIds().mapNotNull { byId[it]?.resistance?.slot }.toSet()
    }

    // ------------------------------------------------------------------ what the content delivers

    @Test
    fun `a mat alone covers leg push, upper push and hamstring-glute at moderate or better`() {
        repeat(25) { seed ->
            val result = generate(retention(40, listOf(Category.FLOOR)), seed)
            assertThat(result.slots()).containsAtLeast(
                MovementSlot.LOWER_PUSH, MovementSlot.UPPER_PUSH, MovementSlot.LOWER_PULL,
            )
            val byId = db.exercises.associateBy { it.id }
            for (id in result.setIds()) {
                assertWithMessage(id).that(byId.getValue(id).resistance!!.tier).isNotEqualTo(EvidenceTier.LIMITED)
            }
        }
    }

    @Test
    fun `a mat alone cannot cover upper-body pulling, and the app says so with a fix`() {
        val result = generate(retention(40, listOf(Category.FLOOR)), seed = 1)
        assertThat(result.slots()).doesNotContain(MovementSlot.UPPER_PULL)
        val warning = result.warnings.single { it.message.contains("upper-body pull") }
        assertThat(warning.fixedConfig).isNotNull()
    }

    @Test
    fun `two kilo hand weights add the upper-back raise, shoulders, biceps and triceps`() {
        val seen = mutableSetOf<MovementSlot>()
        repeat(25) { seed ->
            val result = generate(retention(60, listOf(Category.FLOOR), handWeights = true), seed)
            seen += result.slots()
            // With weights the mat reaches all four regions and no gap is reported.
            assertThat(result.slots()).containsAtLeastElementsIn(bigFour)
            assertThat(result.warnings.none { it.message.contains("muscles") }).isTrue()
        }
        assertThat(seen).containsAtLeast(MovementSlot.SHOULDERS, MovementSlot.BICEPS, MovementSlot.TRICEPS)
    }

    @Test
    fun `every shipped hand-weight exercise is usable with a two kilo weight`() {
        // D-24: the content is written for the weights the user owns. An entry that needs more must
        // not ship until heavier weights are supported end to end.
        val weighted = db.exercises.filter { Equipment.HAND_WEIGHTS in it.equipment }
        assertThat(weighted).isNotEmpty()
        for (ex in weighted) {
            assertWithMessage(ex.id).that(ex.resistance!!.minUsefulKg!!).isAtMost(2f)
            assertWithMessage(ex.id).that(ex.category).isEqualTo(Category.FLOOR)
        }
    }

    @Test
    fun `mat and reformer together cover all four regions once fillers are allowed`() {
        val cats = listOf(Category.FLOOR, Category.REFORMER)
        repeat(25) { seed ->
            val result = generate(retention(45, cats, gapFill = true), seed)
            assertThat(result.slots()).containsAtLeastElementsIn(bigFour)
            assertThat(result.warnings.none { it.message.contains("muscles") }).isTrue()
        }
        // Without fillers the reformer's only pull is limited evidence: left out, and reported.
        val strict = generate(retention(45, cats), seed = 1)
        assertThat(strict.slots()).doesNotContain(MovementSlot.UPPER_PULL)
        assertThat(strict.warnings.single { it.fixLabel == "Add limited-evidence fillers" }.message)
            .contains("upper-body pull")
    }

    @Test
    fun `limited-evidence exercises stay out of retention sessions unless asked for`() {
        val byId = db.exercises.associateBy { it.id }
        val cats = listOf(Category.FLOOR, Category.REFORMER)
        repeat(25) { seed ->
            for (weights in listOf(false, true)) {
                for (id in generate(retention(60, cats, handWeights = weights), seed).setIds()) {
                    assertWithMessage(id).that(byId.getValue(id).resistance!!.tier).isNotEqualTo(EvidenceTier.LIMITED)
                }
            }
        }
    }

    @Test
    fun `reformer work is never graded strong for muscle retention`() {
        // RESEARCH §12.8: no trial shows limb-muscle growth from reformer training.
        for (ex in db.exercises.filter { it.category == Category.REFORMER && it.resistance != null }) {
            assertWithMessage(ex.id).that(ex.resistance!!.tier).isNotEqualTo(EvidenceTier.STRONG)
        }
    }

    @Test
    fun `cardio machines carry no retention profile and no equipment`() {
        for (ex in db.exercises.filter { it.category == Category.SPIN || it.category == Category.ELLIPTICAL }) {
            assertWithMessage(ex.id).that(ex.resistance).isNull()
            assertWithMessage(ex.id).that(ex.equipment).isEmpty()
        }
    }

    // ------------------------------------------------------------------ equipment

    private val needsKit get() = db.exercises.filter { it.equipment.any { e -> e != Equipment.HAND_WEIGHTS } }

    @Test
    fun `nothing that needs a chair, step, anchor or box is generated until it is switched on`() {
        val gated = needsKit.map { it.id }.toSet()
        assertThat(gated).isNotEmpty()
        val cats = listOf(Category.FLOOR, Category.REFORMER)
        repeat(25) { seed ->
            // Retention sessions, with every chip on and limited evidence allowed…
            val retention = generate(
                retention(60, cats, handWeights = true, gapFill = true), seed,
                WorkoutGenerator.Profile(includeLowEvidence = true),
            )
            assertThat(retention.setIds().intersect(gated)).isEmpty()
            // …and general sessions.
            val general = generate(
                GeneratorConfig(totalDurationMin = 60, categories = cats, useHealthData = false), seed,
                WorkoutGenerator.Profile(includeLowEvidence = true),
            )
            assertThat(general.session.plan.steps.mapNotNull { it.exerciseId }.toSet().intersect(gated)).isEmpty()
        }
    }

    @Test
    fun `each piece of equipment unlocks what the app says it does`() {
        fun unlockedBy(equipment: Equipment) = db.exercises.filter { it.equipment == listOf(equipment) }.map { it.id }
        assertThat(unlockedBy(Equipment.CHAIR)).containsExactly(
            "floor_incline_pushup", "floor_feet_elevated_pushup", "floor_rfe_split_squat",
            "floor_single_leg_squat_to_chair", "floor_single_leg_hip_thrust",
        )
        assertThat(unlockedBy(Equipment.STEP)).containsExactly("floor_step_calf_raise")
        assertThat(unlockedBy(Equipment.ANKLE_ANCHOR)).containsExactly("floor_nordic_curl")
        assertThat(unlockedBy(Equipment.REFORMER_BOX)).containsExactly("ref_long_box_pulling_straps")
        // No entry needs two pieces of kit at once.
        assertThat(db.exercises.none { it.equipment.size > 1 }).isTrue()
    }

    @Test
    fun `the long box gives the reformer a moderate-evidence pull, so the limited row is no longer needed`() {
        val withBox = WorkoutGenerator.Profile(ownedEquipment = setOf(Equipment.REFORMER_BOX))
        repeat(25) { seed ->
            val result = generate(retention(45, listOf(Category.FLOOR, Category.REFORMER), gapFill = true), seed, withBox)
            assertThat(result.setIds()).contains("ref_long_box_pulling_straps")
            assertThat(result.setIds()).containsNoneOf("ref_strap_row_single", "ref_rowing_back")
            assertThat(result.warnings.none { it.message.contains("muscles") }).isTrue()
        }
    }

    @Test
    fun `an ankle anchor brings in the strong-evidence hamstring exercise as a short, stop-short set`() {
        val withAnchor = WorkoutGenerator.Profile(ownedEquipment = setOf(Equipment.ANKLE_ANCHOR))
        var seen = false
        repeat(25) { seed ->
            val sets = generate(retention(45, listOf(Category.FLOOR)), seed, withAnchor).session.plan.steps
                .filter { it.exerciseId == "floor_nordic_curl" }
            if (sets.isNotEmpty()) {
                seen = true
                for (set in sets) {
                    assertThat(set.durationSec).isIn(30..45)
                    assertThat(set.effortCue).isEqualTo(ResistanceBlockBuilder.CUE_MODERATE_LOAD)
                }
            }
        }
        assertThat(seen).isTrue()
    }

    @Test
    fun `a chair extends the push-up and leg ladders to four rungs`() {
        val ladders = Ladders.from(db.exercises).associateBy { it.id }
        assertThat(ladders.getValue("pushup").rungs.map { it.rung }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(ladders.getValue("leg_push").rungs.map { it.rung }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(ladders.getValue("hip_bridge").rungs.map { it.rung }).containsExactly(1, 2, 3).inOrder()
        assertThat(ladders.getValue("calf").rungs.map { it.rung }).containsExactly(1, 2, 3).inOrder()
        // At the top of the push-up ladder with a chair, the feet-elevated version is used; without
        // one the generator falls back to the hardest rung the kit allows.
        val top = mapOf("pushup" to 4)
        val chair = WorkoutGenerator.Profile(ownedEquipment = setOf(Equipment.CHAIR), ladderRungs = top)
        val none = WorkoutGenerator.Profile(ladderRungs = top)
        repeat(15) { seed ->
            assertThat(generate(retention(40, listOf(Category.FLOOR)), seed, chair).setIds())
                .contains("floor_feet_elevated_pushup")
            val fallback = generate(retention(40, listOf(Category.FLOOR)), seed, none).setIds()
            assertThat(fallback).contains("floor_pushup")
            assertThat(fallback).doesNotContain("floor_feet_elevated_pushup")
        }
    }

    @Test
    fun `ladders run in consecutive rungs and include the default level`() {
        val ladders = Ladders.from(db.exercises)
        assertThat(ladders.map { it.id }).containsAtLeast("leg_push", "pushup", "hip_bridge", "calf", "prone_raise")
        for (ladder in ladders) {
            val rungs = ladder.rungs.map { it.rung }
            assertWithMessage(ladder.id).that(rungs).isEqualTo((rungs.first()..rungs.last()).toList())
            assertWithMessage(ladder.id).that(rungs).contains(Ladders.DEFAULT_RUNG)
        }
    }

    // ------------------------------------------------------------------ general sessions are untouched

    @Test
    fun `general sessions never use hand weights, whatever the stored flag says`() {
        val weighted = db.exercises.filter { Equipment.HAND_WEIGHTS in it.equipment }.map { it.id }.toSet()
        repeat(40) { seed ->
            val config = GeneratorConfig(
                totalDurationMin = 45, categories = listOf(Category.FLOOR, Category.REFORMER),
                useHealthData = false, handWeights = true, // stale flag on a general config
            )
            val steps = generate(config, seed, WorkoutGenerator.Profile(includeLowEvidence = true)).session.plan.steps
            assertThat(steps.mapNotNull { it.exerciseId }.toSet().intersect(weighted)).isEmpty()
            assertThat(steps.none { it.isResistanceSet }).isTrue()
            assertThat(steps.none { it.setIndex != null || it.effortCue != null }).isTrue()
        }
    }

    @Test
    fun `back care and cardio blocks are built as before inside a retention session`() {
        val result = generate(retention(45, listOf(Category.FLOOR, Category.BACK, Category.SPIN)), seed = 4)
        val plan = result.session.plan
        assertThat(plan.blocks.map { it.isResistance }).containsExactly(true, false, false).inOrder()
        assertThat(plan.steps.filter { it.category == Category.BACK && it.type == StepType.WORK }.none { it.isResistanceSet }).isTrue()
        assertThat(plan.steps.filter { it.category == Category.SPIN && it.type == StepType.WORK }).isNotEmpty()
        assertThat(plan.blocks[2].routineName).isNotNull()
    }

    // ------------------------------------------------------------------ budget

    @Test
    fun `retention plans on the bundled database hit the requested duration or explain the deviation`() {
        val categorySets = listOf(
            listOf(Category.FLOOR), listOf(Category.REFORMER), listOf(Category.FLOOR, Category.REFORMER),
            listOf(Category.FLOOR, Category.SPIN), listOf(Category.FLOOR, Category.REFORMER, Category.ELLIPTICAL),
        )
        for (min in listOf(5, 10, 15, 30, 45, 60)) for (cats in categorySets) for (mode in RestMode.entries)
            for (weights in listOf(false, true)) for (fill in listOf(false, true)) {
                val config = retention(min, cats, handWeights = weights, gapFill = fill, restMode = mode, warm = true)
                repeat(3) { seed ->
                    val result = generate(config, seed)
                    val total = result.session.plan.totalSec
                    val requested = min * 60
                    val within = abs(total - requested) <= requested / 20
                    val explained = result.warnings.any { w ->
                        w.plannedTotalSec?.let { abs(total - it) <= maxOf(1, total / 20) } == true
                    }
                    assertWithMessage("min=$min cats=$cats mode=$mode weights=$weights fill=$fill seed=$seed total=$total")
                        .that(within || explained).isTrue()
                }
            }
    }
}
