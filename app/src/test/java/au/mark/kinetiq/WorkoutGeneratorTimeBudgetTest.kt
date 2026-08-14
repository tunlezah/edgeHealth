package au.mark.kinetiq

import au.mark.kinetiq.data.model.Category
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
import kotlin.math.abs
import kotlin.random.Random

/**
 * The single most important generator invariant: a plan either lands within 5% of the requested
 * duration, or carries a warning whose [plannedTotalSec] says exactly what it runs instead.
 * Never silently off budget.
 */
class WorkoutGeneratorTimeBudgetTest {

    private lateinit var db: ExerciseDatabaseFile

    @Before
    fun setup() {
        val json = Json { ignoreUnknownKeys = true }
        db = json.decodeFromString(
            ExerciseDatabaseFile.serializer(),
            File("src/main/assets/exercise_db.json").readText(),
        )
    }

    private fun generator(seed: Int = 7) = WorkoutGenerator(db.exercises, db.routines, random = Random(seed))

    /** Asserts the invariant for one result; returns the plan total for extra checks. */
    private fun assertOnBudgetOrExplained(config: GeneratorConfig, label: String): Int {
        val result = generator().generate(config)
        val total = result.session.plan.totalSec
        val requested = config.totalDurationMin * 60
        val withinRequest = abs(total - requested) <= requested / 20
        val explained = result.warnings.any { w ->
            w.plannedTotalSec?.let { abs(total - it) <= maxOf(1, total / 20) } == true
        }
        com.google.common.truth.Truth.assertWithMessage(
            "$label: total=${total}s requested=${requested}s warnings=${result.warnings.map { it.message }}"
        ).that(withinRequest || explained).isTrue()
        return total
    }

    @Test
    fun `generated plans hit the requested duration or explain the deviation across the config matrix`() {
        val durations = listOf(5, 10, 15, 30, 60)
        val categorySets = listOf(
            listOf(Category.FLOOR),
            listOf(Category.SPIN),
            listOf(Category.FLOOR, Category.SPIN),
            listOf(Category.FLOOR, Category.REFORMER, Category.SPIN),
        )
        val ratios = listOf(0.5f, 2f, 6f)
        val perCategory = listOf(null, 3, 10)
        for (min in durations) for (cats in categorySets) for (ratio in ratios) for (perCat in perCategory) {
            assertOnBudgetOrExplained(
                GeneratorConfig(
                    totalDurationMin = min,
                    categories = cats,
                    workRestRatio = ratio,
                    exercisesPerCategory = perCat,
                    useHealthData = false,
                ),
                label = "min=$min cats=$cats ratio=$ratio perCat=$perCat",
            )
        }
    }

    @Test
    fun `over-budget plans carry an explicit duration warning with the planned total`() {
        val result = generator().generate(
            GeneratorConfig(
                totalDurationMin = 5,
                categories = listOf(Category.FLOOR, Category.REFORMER, Category.SPIN),
                useHealthData = false,
            ),
        )
        val warning = result.warnings.first { it.plannedTotalSec != null }
        assertThat(warning.message).contains("instead of the 5 min requested")
        val total = result.session.plan.totalSec
        assertThat(abs(total - warning.plannedTotalSec!!)).isAtMost(maxOf(1, total / 20))
    }

    @Test
    fun `machine routine scaling renormalizes after per-step clamping`() {
        for (min in listOf(8, 45)) {
            assertOnBudgetOrExplained(
                GeneratorConfig(
                    totalDurationMin = min, categories = listOf(Category.SPIN),
                    warmup = false, cooldown = false, useHealthData = false,
                ),
                label = "spin $min min",
            )
        }
    }

    @Test
    fun `routines that cannot fit the block are rejected in favor of segment assembly`() {
        // A 6-minute spin block is far below every bundled routine's [0.5, 2] scale window.
        val result = generator().generate(
            GeneratorConfig(
                totalDurationMin = 6, categories = listOf(Category.SPIN),
                warmup = false, cooldown = false, useHealthData = false,
            ),
        )
        assertThat(result.session.plan.blocks.single().routineName).isNull()
        val total = result.session.plan.totalSec
        assertThat(abs(total - 6 * 60)).isAtMost(6 * 60 / 20)
    }

    @Test
    fun `discrete block redistributes clamped work time to stay on budget or warns`() {
        val result = generator().generate(
            GeneratorConfig(
                totalDurationMin = 30, categories = listOf(Category.REFORMER),
                exercisesPerCategory = 3, warmup = false, cooldown = false, useHealthData = false,
            ),
        )
        val total = result.session.plan.totalSec
        val onBudget = abs(total - 30 * 60) <= 30 * 60 / 20
        val warned = result.warnings.any { it.message.contains("time limits") }
        assertThat(onBudget || warned).isTrue()
        // Either way, the plan must not silently claim 30 minutes while delivering 7.
        if (!onBudget) {
            assertThat(result.warnings.any { w ->
                w.plannedTotalSec?.let { abs(total - it) <= maxOf(1, total / 20) } == true
            }).isTrue()
        }
    }

    @Test
    fun `rest clamp keeps block time on budget at extreme ratios`() {
        for (ratio in listOf(0.5f, 6f)) {
            assertOnBudgetOrExplained(
                GeneratorConfig(
                    totalDurationMin = 15, categories = listOf(Category.FLOOR),
                    workRestRatio = ratio, warmup = false, cooldown = false, useHealthData = false,
                ),
                label = "floor ratio=$ratio",
            )
        }
    }

    @Test
    fun `high adiposity reordering never duplicates exercises`() {
        val metrics = BodyMetrics(weightKg = 120.0, heightCm = 170.0)
        repeat(10) { seed ->
            val g = WorkoutGenerator(db.exercises, db.routines, random = Random(seed))
            val result = g.generate(
                GeneratorConfig(
                    totalDurationMin = 30, categories = listOf(Category.FLOOR),
                    intensity = Intensity.VERY_HIGH, warmup = false, cooldown = false, useHealthData = true,
                ),
                WorkoutGenerator.Profile(metrics = metrics),
            )
            val ids = result.session.plan.steps.filter { it.type == StepType.WORK }.mapNotNull { it.exerciseId }
            assertThat(ids).containsNoDuplicates()
        }
    }

    @Test
    fun `one tap duration fix converges`() {
        val first = generator().generate(
            GeneratorConfig(
                totalDurationMin = 10,
                categories = listOf(Category.FLOOR, Category.REFORMER, Category.SPIN),
                useHealthData = false,
            ),
        )
        val fix = first.warnings.first { it.fixedConfig?.totalDurationMin != null && it.plannedTotalSec != null }
        val second = generator().generate(fix.fixedConfig!!)
        assertThat(second.warnings.none { it.message.contains("under 5 minutes") }).isTrue()
    }

    @Test
    fun `redistribute respects bounds and target`() {
        val g = generator()
        val out = g.redistribute(
            initial = listOf(40, 40, 40),
            minBound = { 20 },
            maxBound = { i -> if (i == 0) 45 else 200 },
            targetSec = 150,
        )
        assertThat(out.sum()).isEqualTo(150)
        assertThat(out[0]).isAtMost(45)
        out.forEach { assertThat(it).isAtLeast(20) }
    }
}
