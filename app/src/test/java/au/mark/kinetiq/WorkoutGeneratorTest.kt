package au.mark.kinetiq

import au.mark.kinetiq.data.model.BodyArea
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.ExerciseDatabaseFile
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.Intensity
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.repo.BodyMetrics
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

class WorkoutGeneratorTest {

    private lateinit var db: ExerciseDatabaseFile
    private lateinit var generator: WorkoutGenerator

    @Before
    fun setup() {
        val json = Json { ignoreUnknownKeys = true }
        db = json.decodeFromString(
            ExerciseDatabaseFile.serializer(),
            File("src/main/assets/exercise_db.json").readText(),
        )
        generator = WorkoutGenerator(db.exercises, db.routines, random = Random(42))
    }

    @Test
    fun `duration solver computes work seconds within ratio`() {
        // 10 minutes, 8 exercises, 2:1 work:rest → work = 600 / (8 + 7/2) = ~52s
        val work = generator.solveWorkSec(600, 8, 2f)
        assertThat(work).isEqualTo(52)
        // Degenerate input
        assertThat(generator.solveWorkSec(600, 0, 2f)).isEqualTo(0)
    }

    @Test
    fun `warmup and cooldown slice is 3 to 5 minutes scaled`() {
        assertThat(generator.warmCoolSlice(20 * 60)).isEqualTo(3 * 60)   // 10% clamped up to 3 min
        assertThat(generator.warmCoolSlice(40 * 60)).isEqualTo(4 * 60)   // 10%
        assertThat(generator.warmCoolSlice(90 * 60)).isEqualTo(5 * 60)   // clamped to 5 min
    }

    @Test
    fun `categories run as complete blocks in user order and never interleave`() {
        val result = generator.generate(
            GeneratorConfig(
                totalDurationMin = 40,
                categories = listOf(Category.FLOOR, Category.SPIN),
                warmup = false, cooldown = false,
            ),
        )
        val workSteps = result.session.plan.steps.filter { it.type == StepType.WORK }
        val categorySequence = workSteps.map { it.category }
        // Once SPIN starts, FLOOR never reappears.
        val firstSpin = categorySequence.indexOfFirst { it == Category.SPIN }
        assertThat(firstSpin).isGreaterThan(0)
        assertThat(categorySequence.subList(firstSpin, categorySequence.size).toSet()).containsExactly(Category.SPIN)
        // A transition step separates the blocks.
        assertThat(result.session.plan.steps.count { it.type == StepType.TRANSITION }).isEqualTo(1)
    }

    @Test
    fun `constraints hard-exclude contraindicated exercises`() {
        val result = generator.generate(
            GeneratorConfig(totalDurationMin = 30, categories = listOf(Category.FLOOR), warmup = false, cooldown = false),
            WorkoutGenerator.Profile(constraints = setOf(BodyArea.KNEE, BodyArea.WRIST)),
        )
        val byId = db.exercises.associateBy { it.id }
        result.session.plan.steps.filter { it.type == StepType.WORK }.forEach { step ->
            val ex = byId.getValue(step.exerciseId!!)
            assertThat(ex.contraindications).containsNoneOf(BodyArea.KNEE, BodyArea.WRIST)
        }
    }

    @Test
    fun `LIMITED tier exercises never enter auto-generation by default`() {
        repeat(10) { seed ->
            val g = WorkoutGenerator(db.exercises, db.routines, random = Random(seed))
            val result = g.generate(
                GeneratorConfig(totalDurationMin = 45, categories = listOf(Category.FLOOR, Category.REFORMER)),
            )
            val byId = db.exercises.associateBy { it.id }
            result.session.plan.steps.filter { it.type == StepType.WORK }.forEach { step ->
                assertThat(byId.getValue(step.exerciseId!!).evidenceTier).isNotEqualTo(EvidenceTier.LIMITED)
            }
        }
    }

    @Test
    fun `LIMITED tier exercises may appear when the toggle is on`() {
        val limitedIds = db.exercises.filter { it.evidenceTier == EvidenceTier.LIMITED }.map { it.id }.toSet()
        var seen = false
        repeat(40) { seed ->
            val g = WorkoutGenerator(db.exercises, db.routines, random = Random(seed))
            val result = g.generate(
                GeneratorConfig(totalDurationMin = 45, categories = listOf(Category.FLOOR), warmup = false, cooldown = false),
                WorkoutGenerator.Profile(includeLowEvidence = true),
            )
            if (result.session.plan.steps.any { it.exerciseId in limitedIds }) seen = true
        }
        assertThat(seen).isTrue()
    }

    @Test
    fun `unreasonable exercise count produces a warning with a fix`() {
        // 20 exercises in 8 minutes → ~16 s per exercise, below the 20 s usefulness floor.
        val result = generator.generate(
            GeneratorConfig(
                totalDurationMin = 8,
                categories = listOf(Category.FLOOR),
                exercisesPerCategory = 20,
                warmup = false, cooldown = false,
                useHealthData = false,
            ),
        )
        val warning = result.warnings.firstOrNull { it.fixedConfig?.exercisesPerCategory != null }
        assertThat(warning).isNotNull()
        assertThat(warning!!.fixedConfig!!.exercisesPerCategory).isLessThan(20)
    }

    @Test
    fun `machine blocks use named routines scaled to the block`() {
        val result = generator.generate(
            GeneratorConfig(totalDurationMin = 30, categories = listOf(Category.SPIN), warmup = false, cooldown = false),
        )
        val block = result.session.plan.blocks.single()
        assertThat(block.routineName).isNotNull()
        val total = result.session.plan.steps.sumOf { it.durationSec }
        // Scaled routine should land within 20% of the requested time.
        assertThat(total).isAtLeast((30 * 60 * 0.8).toInt())
        assertThat(total).isAtMost((30 * 60 * 1.25).toInt())
    }

    @Test
    fun `high adiposity biases away from very high intensity early`() {
        val metrics = BodyMetrics(weightKg = 120.0, heightCm = 170.0) // BMI ~41.5
        val byId = db.exercises.associateBy { it.id }
        repeat(10) { seed ->
            val g = WorkoutGenerator(db.exercises, db.routines, random = Random(seed))
            val result = g.generate(
                GeneratorConfig(
                    totalDurationMin = 30, categories = listOf(Category.FLOOR),
                    intensity = Intensity.VERY_HIGH, warmup = false, cooldown = false, useHealthData = true,
                ),
                WorkoutGenerator.Profile(metrics = metrics),
            )
            val work = result.session.plan.steps.filter { it.type == StepType.WORK }
            val firstHalf = work.take(work.size / 2)
            firstHalf.forEach { step ->
                assertThat(byId.getValue(step.exerciseId!!).intensity).isNotEqualTo(Intensity.VERY_HIGH)
            }
        }
    }

    @Test
    fun `BACK category generates a discrete block of physio exercises`() {
        val result = generator.generate(
            GeneratorConfig(totalDurationMin = 15, categories = listOf(Category.BACK), warmup = false, cooldown = false),
        )
        val byId = db.exercises.associateBy { it.id }
        val work = result.session.plan.steps.filter { it.type == StepType.WORK }
        assertThat(work).isNotEmpty()
        work.forEach { step ->
            val ex = byId.getValue(step.exerciseId!!)
            assertThat(ex.category).isEqualTo(Category.BACK)
            assertThat(ex.impact).isEqualTo(au.mark.kinetiq.data.model.Impact.LOW)
        }
    }

    @Test
    fun `short sessions get a proportional warmup slice`() {
        // 5-minute session: warm-up must not swallow the workout.
        assertThat(generator.warmCoolSlice(5 * 60)).isAtMost(60)
    }

    @Test
    fun `warmup and cooldown appear when enabled`() {
        val result = generator.generate(
            GeneratorConfig(totalDurationMin = 30, categories = listOf(Category.FLOOR), warmup = true, cooldown = true),
        )
        assertThat(result.session.plan.steps.first().type).isEqualTo(StepType.WARMUP)
        assertThat(result.session.plan.steps.last().type).isEqualTo(StepType.COOLDOWN)
    }
}
