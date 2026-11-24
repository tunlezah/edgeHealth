package au.mark.kinetiq

import au.mark.kinetiq.ResistanceTestSupport.general
import au.mark.kinetiq.ResistanceTestSupport.ref
import au.mark.kinetiq.ResistanceTestSupport.set
import au.mark.kinetiq.anim.AnimationRegistry
import au.mark.kinetiq.data.DatabaseValidator
import au.mark.kinetiq.data.export.ExportFile
import au.mark.kinetiq.data.export.ExportImportCodec
import au.mark.kinetiq.data.export.ExportedWorkout
import au.mark.kinetiq.data.model.BodyArea
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.Equipment
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.ExerciseDatabaseFile
import au.mark.kinetiq.data.model.ExerciseKind
import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.MovementSlot
import au.mark.kinetiq.data.model.Muscle
import au.mark.kinetiq.data.model.SessionBlock
import au.mark.kinetiq.data.model.SessionFocus
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.domain.generator.ExerciseEligibility
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.random.Random

/**
 * The additive foundations of muscle-retention sessions: stored data from before the feature
 * still decodes to exactly what it meant, the one eligibility rule set holds, and the database
 * validator refuses dishonest or incomplete retention content.
 */
class ResistanceFoundationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ------------------------------------------------------------------ stored-data compatibility

    @Test
    fun `a config stored before the feature decodes to a general session without hand weights`() {
        val legacy = """{"totalDurationMin":20,"categories":["FLOOR","SPIN"],"restMode":"RECOVERY","intensity":"HIGH"}"""
        val config = json.decodeFromString(GeneratorConfig.serializer(), legacy)
        assertThat(config.focus).isEqualTo(SessionFocus.GENERAL)
        assertThat(config.handWeights).isFalse()
        assertThat(config.allowLimitedGapFill).isFalse()
        assertThat(config.easeIn).isFalse()
        assertThat(config.totalDurationMin).isEqualTo(20)
    }

    @Test
    fun `a step and block stored before the feature decode as plain circuit work`() {
        val step = json.decodeFromString(
            SessionStep.serializer(),
            """{"type":"WORK","category":"FLOOR","exerciseId":"floor_plank","exerciseName":"Plank","durationSec":40,"met":3.0,"blockIndex":0}""",
        )
        assertThat(step.setIndex).isNull()
        assertThat(step.setCount).isNull()
        assertThat(step.switchSides).isFalse()
        assertThat(step.effortCue).isNull()
        assertThat(step.primaryMuscles).isEmpty()
        assertThat(step.isResistanceSet).isFalse()
        val block = json.decodeFromString(SessionBlock.serializer(), """{"category":"FLOOR","isHiit":false}""")
        assertThat(block.isResistance).isFalse()
    }

    @Test
    fun `an exercise stored before the feature needs no equipment and has no retention profile`() {
        val legacy = json.encodeToString(Exercise.serializer(), general("x"))
            .replace(""","equipment":[]""", "").replace(""","resistance":null""", "")
        assertThat(legacy).doesNotContain("equipment")
        val ex = json.decodeFromString(Exercise.serializer(), legacy)
        assertThat(ex.equipment).isEmpty()
        assertThat(ex.resistance).isNull()
    }

    @Test
    fun `a retention session round-trips through storage and export`() {
        val library = ResistanceTestSupport.matLibrary()
        val session = WorkoutGenerator(library, emptyList(), random = Random(3)).generate(
            GeneratorConfig(
                totalDurationMin = 20, warmup = false, cooldown = false, useHealthData = false,
                focus = SessionFocus.MUSCLE_RETENTION, handWeights = true, easeIn = true,
            ),
        ).session
        assertThat(session.plan.steps.any { it.isResistanceSet && it.switchSides }).isTrue()

        val decoded = json.decodeFromString(
            GeneratedSession.serializer(), json.encodeToString(GeneratedSession.serializer(), session),
        )
        assertThat(decoded).isEqualTo(session)

        val export = ExportFile(
            exportedAtEpochMs = 1_800_000_000_000L,
            savedWorkouts = listOf(ExportedWorkout("Retention", 1_800_000_000_000L, session)),
            history = emptyList(),
        )
        val result = ExportImportCodec.decodeAndValidate(ExportImportCodec.encode(export), nowEpochMs = 1_800_000_100_000L)
        assertThat(result).isInstanceOf(ExportImportCodec.ImportResult.Success::class.java)
        val imported = (result as ExportImportCodec.ImportResult.Success).file.savedWorkouts.single().session
        assertThat(imported).isEqualTo(session)
    }

    @Test
    fun `a build without the feature can still read a retention session`() {
        // Forward compatibility: an older build ignores unknown keys and sees ordinary WORK steps.
        val step = SessionStep(
            type = StepType.WORK, category = Category.FLOOR, exerciseId = "a", exerciseName = "A",
            durationSec = 60, setIndex = 2, setCount = 3, switchSides = true, effortCue = "Slow reps.",
            primaryMuscles = listOf(Muscle.QUADS),
        )
        val encoded = json.encodeToString(SessionStep.serializer(), step)
        assertThat(encoded).contains("\"type\":\"WORK\"")
        assertThat(encoded).contains("\"setIndex\":2")
    }

    // ------------------------------------------------------------------ eligibility

    @Test
    fun `equipment is a hard gate`() {
        val weighted = set("w", MovementSlot.SHOULDERS, equipment = listOf(Equipment.HAND_WEIGHTS), minUsefulKg = 2f)
        val boxed = set("b", MovementSlot.UPPER_PULL, cat = Category.REFORMER, equipment = listOf(Equipment.REFORMER_BOX))
        val none = ExerciseEligibility.kitFor(emptySet(), 2f, handWeightsThisSession = false)
        val weights = ExerciseEligibility.kitFor(emptySet(), 2f, handWeightsThisSession = true)
        assertThat(ExerciseEligibility.equipmentAvailable(weighted, none)).isFalse()
        assertThat(ExerciseEligibility.equipmentAvailable(weighted, weights)).isTrue()
        assertThat(ExerciseEligibility.equipmentAvailable(boxed, weights)).isFalse()
        val box = ExerciseEligibility.kitFor(setOf(Equipment.REFORMER_BOX), 2f, false)
        assertThat(ExerciseEligibility.equipmentAvailable(boxed, box)).isTrue()
    }

    @Test
    fun `owning hand weights is not the same as using them this session`() {
        // Even if HAND_WEIGHTS somehow sits in the owned set, only the session choice switches it on.
        val kit = ExerciseEligibility.kitFor(setOf(Equipment.HAND_WEIGHTS), 2f, handWeightsThisSession = false)
        assertThat(kit.equipment).doesNotContain(Equipment.HAND_WEIGHTS)
    }

    @Test
    fun `a hand weight below the useful minimum excludes the exercise`() {
        val row = set("row", MovementSlot.UPPER_PULL, equipment = listOf(Equipment.HAND_WEIGHTS), minUsefulKg = 6f)
        assertThat(ExerciseEligibility.equipmentAvailable(row, ExerciseEligibility.kitFor(emptySet(), 2f, true))).isFalse()
        assertThat(ExerciseEligibility.equipmentAvailable(row, ExerciseEligibility.kitFor(emptySet(), 6f, true))).isTrue()
    }

    @Test
    fun `the general pool grades on the general tier and the retention pool on the retention tier`() {
        val kit = ExerciseEligibility.Kit()
        // Well supported in general, weak for muscle retention — reformer footwork is the real case.
        val footwork = set("fw", MovementSlot.LOWER_PUSH, cat = Category.REFORMER, tier = EvidenceTier.LIMITED)
        assertThat(footwork.evidenceTier).isEqualTo(EvidenceTier.STRONG)
        assertThat(ExerciseEligibility.inGeneralPool(footwork, emptySet(), includeLowEvidence = false, kit = kit)).isTrue()
        assertThat(ExerciseEligibility.inResistancePool(footwork, emptySet(), includeLimited = false, kit = kit)).isFalse()
        assertThat(ExerciseEligibility.inResistancePool(footwork, emptySet(), includeLimited = true, kit = kit)).isTrue()
        // No profile, no retention pool — whatever the toggles say.
        assertThat(ExerciseEligibility.inResistancePool(general("g"), emptySet(), includeLimited = true, kit = kit)).isFalse()
    }

    @Test
    fun `body constraints exclude from both pools`() {
        val kit = ExerciseEligibility.Kit()
        val ex = set("s", MovementSlot.LOWER_PUSH, contraindications = listOf(BodyArea.KNEE))
        assertThat(ExerciseEligibility.inGeneralPool(ex, setOf(BodyArea.KNEE), true, kit)).isFalse()
        assertThat(ExerciseEligibility.inResistancePool(ex, setOf(BodyArea.KNEE), true, kit)).isFalse()
        assertThat(ExerciseEligibility.inResistancePool(ex, setOf(BodyArea.WRIST), true, kit)).isTrue()
    }

    // ------------------------------------------------------------------ validator

    private fun problemsFor(vararg exercises: Exercise): List<String> =
        DatabaseValidator.validate(ExerciseDatabaseFile(1, exercises.toList(), emptyList()), AnimationRegistry.ids).problems

    private fun retentionProblems(ex: Exercise) = problemsFor(ex).filter { it.contains("'${ex.id}'") }

    @Test
    fun `a moderate or strong retention tier needs its own reference`() {
        val ex = set("a", MovementSlot.LOWER_PUSH)
        val stripped = ex.copy(resistance = ex.resistance!!.copy(references = emptyList()))
        // The exercise's general references do not carry the retention claim.
        assertThat(stripped.references).isNotEmpty()
        assertThat(retentionProblems(stripped).any { it.contains("retention tier requires >= 1 reference") }).isTrue()
        assertThat(retentionProblems(ex)).isEmpty()
    }

    @Test
    fun `a limited retention tier needs a candid note`() {
        val ex = set("a", MovementSlot.LOWER_PUSH, tier = EvidenceTier.LIMITED)
        val silent = ex.copy(resistance = ex.resistance!!.copy(note = null))
        assertThat(retentionProblems(silent).any { it.contains("requires a candid note") }).isTrue()
        assertThat(retentionProblems(ex)).isEmpty()
    }

    @Test
    fun `hand-weight exercises must be graded and state their useful minimum`() {
        val ungraded = general("a", equipment = listOf(Equipment.HAND_WEIGHTS))
        assertThat(retentionProblems(ungraded).any { it.contains("must carry a resistance profile") }).isTrue()
        val noMinimum = set("b", MovementSlot.SHOULDERS, equipment = listOf(Equipment.HAND_WEIGHTS), minUsefulKg = null)
        assertThat(retentionProblems(noMinimum).any { it.contains("must state minUsefulKg") }).isTrue()
        val strayMinimum = set("c", MovementSlot.SHOULDERS, minUsefulKg = 2f)
        assertThat(retentionProblems(strayMinimum).any { it.contains("only for HAND_WEIGHTS") }).isTrue()
    }

    @Test
    fun `retention profiles are rejected where they make no sense`() {
        val back = set("a", MovementSlot.TRUNK).copy(category = Category.BACK)
        assertThat(retentionProblems(back).any { it.contains("only for FLOOR and REFORMER") }).isTrue()
        val warmup = set("b", MovementSlot.TRUNK).copy(isWarmupCooldown = true)
        assertThat(retentionProblems(warmup).any { it.contains("cannot be a resistance set") }).isTrue()
        val noMuscle = set("c", MovementSlot.TRUNK, primary = emptyList())
        assertThat(retentionProblems(noMuscle).any { it.contains("at least one primary muscle") }).isTrue()
        val both = set("d", MovementSlot.LOWER_PUSH, primary = listOf(Muscle.QUADS), secondary = listOf(Muscle.QUADS))
        assertThat(retentionProblems(both).any { it.contains("both primary and secondary") }).isTrue()
        val boxOnMat = set("e", MovementSlot.UPPER_PULL, equipment = listOf(Equipment.REFORMER_BOX))
        assertThat(retentionProblems(boxOnMat).any { it.contains("REFORMER_BOX is only meaningful") }).isTrue()
        val badLadder = set("f", MovementSlot.UPPER_PUSH, ladder = "Push Up")
        assertThat(retentionProblems(badLadder).any { it.contains("lower_snake_case") }).isTrue()
    }

    @Test
    fun `a ladder cannot span movement slots`() {
        val problems = problemsFor(
            set("a", MovementSlot.UPPER_PUSH, ladder = "mixed", rung = 1),
            set("b", MovementSlot.LOWER_PUSH, ladder = "mixed", rung = 2),
        )
        assertThat(problems.any { it.contains("ladder 'mixed'") }).isTrue()
    }

    @Test
    fun `the mat-only coverage the app promises is enforced once retention content exists`() {
        // A lone profiled exercise switches the coverage rules on and fails them.
        val problems = problemsFor(set("a", MovementSlot.UPPER_PUSH))
        assertThat(problems.any { it.contains("no MODERATE+ equipment-free FLOOR exercise for LOWER_PUSH") }).isTrue()
        assertThat(problems.any { it.contains("no exercise at all for UPPER_PULL") }).isTrue()
        // No profiles at all: the feature is simply absent, which is valid.
        assertThat(problemsFor(general("g")).none { it.contains("resistance coverage") }).isTrue()
    }

    @Test
    fun `a limited or equipment-bound exercise does not satisfy the mat-only promise`() {
        val problems = problemsFor(
            set("a", MovementSlot.LOWER_PUSH, tier = EvidenceTier.LIMITED),
            set("b", MovementSlot.UPPER_PUSH, equipment = listOf(Equipment.CHAIR_OR_STEP)),
            set("c", MovementSlot.LOWER_PULL, cat = Category.REFORMER),
        )
        for (slot in listOf("LOWER_PUSH", "UPPER_PUSH", "LOWER_PULL")) {
            assertThat(problems.any { it.contains("equipment-free FLOOR exercise for $slot") }).isTrue()
        }
    }

    @Test
    fun `references on a retention profile are held to the same standard as general ones`() {
        val ex = set("a", MovementSlot.LOWER_PUSH)
        val bad = ex.copy(resistance = ex.resistance!!.copy(references = listOf(ref.copy(finding = "TODO"))))
        assertThat(retentionProblems(bad).any { it.contains("placeholder") }).isTrue()
        assertThat(ex.kind).isEqualTo(ExerciseKind.DISCRETE)
    }
}
