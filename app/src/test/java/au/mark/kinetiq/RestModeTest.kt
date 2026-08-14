package au.mark.kinetiq

import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.ExerciseDatabaseFile
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.Intensity
import au.mark.kinetiq.data.model.MachineCue
import au.mark.kinetiq.data.model.ReformerCue
import au.mark.kinetiq.data.model.RestMode
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.random.Random

class RestModeTest {

    private lateinit var db: ExerciseDatabaseFile
    private lateinit var generator: WorkoutGenerator
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setup() {
        db = json.decodeFromString(
            ExerciseDatabaseFile.serializer(),
            File("src/main/assets/exercise_db.json").readText(),
        )
        generator = WorkoutGenerator(db.exercises, db.routines, random = Random(42))
    }

    // ---- serialization back-compat (P2-A1) ----

    @Test
    fun `old GeneratorConfig json without restMode decodes to STANDARD and keeps workRestRatio`() {
        val legacy = """{"totalDurationMin":15,"categories":["FLOOR"],"workRestRatio":3.0}"""
        val config = json.decodeFromString(GeneratorConfig.serializer(), legacy)
        assertThat(config.restMode).isEqualTo(RestMode.STANDARD)
        @Suppress("DEPRECATION")
        assertThat(config.workRestRatio).isEqualTo(3.0f)
    }

    @Test
    fun `new GeneratorConfig json round-trips restMode`() {
        RestMode.entries.forEach { mode ->
            val config = GeneratorConfig(restMode = mode)
            val decoded = json.decodeFromString(
                GeneratorConfig.serializer(),
                json.encodeToString(GeneratorConfig.serializer(), config),
            )
            assertThat(decoded.restMode).isEqualTo(mode)
        }
    }

    // ---- generator behavior (P2-A4) ----

    private fun floorConfig(mode: RestMode, intensity: Intensity = Intensity.MODERATE) = GeneratorConfig(
        totalDurationMin = 20, categories = listOf(Category.FLOOR),
        restMode = mode, intensity = intensity,
        warmup = false, cooldown = false, useHealthData = false,
    )

    @Test
    fun `STANDARD mode emits 15s rests on floor and 20s on setup changes`() {
        val floorRests = generator.generate(floorConfig(RestMode.STANDARD))
            .session.plan.steps.filter { it.type == StepType.REST }
        assertThat(floorRests).isNotEmpty()
        // FLOOR exercises have no machine, so no setup changes: every rest is exactly 15 s.
        floorRests.forEach { assertThat(it.durationSec).isEqualTo(15) }

        val reformerSteps = generator.generate(
            GeneratorConfig(
                totalDurationMin = 20, categories = listOf(Category.REFORMER),
                restMode = RestMode.STANDARD, warmup = false, cooldown = false, useHealthData = false,
            ),
        ).session.plan.steps
        val byId = db.exercises.associateBy { it.id }
        reformerSteps.forEachIndexed { i, step ->
            if (step.type != StepType.REST) return@forEachIndexed
            assertThat(step.durationSec).isAnyOf(15, 20)
            val prev = byId.getValue(reformerSteps[i - 1].exerciseId!!)
            val next = byId.getValue(reformerSteps[i + 1].exerciseId!!)
            val expected = if (generator.setupChange(prev, next)) 20 else 15
            assertThat(step.durationSec).isEqualTo(expected)
        }
    }

    @Test
    fun `RECOVERY mode rest scales with intensity`() {
        val expected = mapOf(
            Intensity.LOW to 45, Intensity.MODERATE to 40,
            Intensity.HIGH to 35, Intensity.VERY_HIGH to 30,
        )
        for ((intensity, restSec) in expected) {
            val rests = generator.generate(floorConfig(RestMode.RECOVERY, intensity))
                .session.plan.steps.filter { it.type == StepType.REST }
            assertThat(rests).isNotEmpty()
            rests.forEach { assertThat(it.durationSec).isEqualTo(restSec) }
        }
    }

    @Test
    fun `CONTINUOUS mode has no rests except forced 10s setup changes`() {
        val floorRests = generator.generate(floorConfig(RestMode.CONTINUOUS))
            .session.plan.steps.filter { it.type == StepType.REST }
        assertThat(floorRests).isEmpty()

        // Reformer spring/position changes still force a 10 s setup pause across many seeds.
        var sawSetupPause = false
        repeat(10) { seed ->
            val g = WorkoutGenerator(db.exercises, db.routines, random = Random(seed))
            val rests = g.generate(
                GeneratorConfig(
                    totalDurationMin = 20, categories = listOf(Category.REFORMER),
                    restMode = RestMode.CONTINUOUS, warmup = false, cooldown = false, useHealthData = false,
                ),
            ).session.plan.steps.filter { it.type == StepType.REST }
            rests.forEach {
                assertThat(it.durationSec).isEqualTo(10)
                assertThat(it.exerciseName).isEqualTo("Change setup")
            }
            if (rests.isNotEmpty()) sawSetupPause = true
        }
        assertThat(sawSetupPause).isTrue()
    }

    @Test
    fun `CONTINUOUS floor session has strictly more work than STANDARD`() {
        // 8 exercises in 10 min keeps per-exercise work inside min/max bounds, so the seconds
        // STANDARD spends resting go entirely into work under CONTINUOUS.
        fun workSec(mode: RestMode) = WorkoutGenerator(db.exercises, db.routines, random = Random(42))
            .generate(
                GeneratorConfig(
                    totalDurationMin = 10, categories = listOf(Category.FLOOR),
                    restMode = mode, exercisesPerCategory = 8,
                    warmup = false, cooldown = false, useHealthData = false,
                ),
            ).session.plan.steps.filter { it.type == StepType.WORK }.sumOf { it.durationSec }
        assertThat(workSec(RestMode.CONTINUOUS)).isGreaterThan(workSec(RestMode.STANDARD))
    }

    @Test
    fun `block time sums to budget for each rest mode or the deviation is explained`() {
        for (mode in RestMode.entries) {
            val result = generator.generate(floorConfig(mode))
            val total = result.session.plan.totalSec
            val onBudget = total in (20 * 60 * 9 / 10)..(20 * 60 * 11 / 10)
            val explained = result.warnings.any { w ->
                w.plannedTotalSec?.let { kotlin.math.abs(total - it) <= maxOf(1, total / 20) } == true
            }
            assertThat(onBudget || explained).isTrue()
        }
    }

    @Test
    fun `setupChange detects spring and position changes`() {
        fun reformer(springs: String, position: String) = db.exercises.first { it.category == Category.REFORMER }
            .copy(machine = MachineCue(reformer = ReformerCue(springs = springs, bodyPosition = position)))
        val floor = db.exercises.first { it.category == Category.FLOOR }

        assertThat(generator.setupChange(reformer("LIGHT_1", "supine"), reformer("MEDIUM_2", "supine"))).isTrue()
        assertThat(generator.setupChange(reformer("LIGHT_1", "supine"), reformer("LIGHT_1", "kneeling"))).isTrue()
        assertThat(generator.setupChange(reformer("LIGHT_1", "supine"), reformer("LIGHT_1", "supine"))).isFalse()
        assertThat(generator.setupChange(floor, floor)).isFalse()
        assertThat(generator.setupChange(floor, reformer("LIGHT_1", "supine"))).isTrue()
    }

    // ---- trailing rest regression (P2-A3, verified no production change needed) ----

    @Test
    fun `no rest or transition trails the final work step`() {
        repeat(10) { seed ->
            val g = WorkoutGenerator(db.exercises, db.routines, random = Random(seed))
            val result = g.generate(
                GeneratorConfig(
                    totalDurationMin = 40,
                    categories = listOf(Category.FLOOR, Category.REFORMER, Category.SPIN),
                    warmup = true, cooldown = true, useHealthData = false,
                ),
            )
            val steps = result.session.plan.steps
            assertThat(steps.last().type).isAnyOf(StepType.COOLDOWN, StepType.WORK)
            // No rest ever directly precedes a transition or cooldown — rests only sit between works.
            steps.zipWithNext().forEach { (a, b) ->
                if (a.type == StepType.REST) assertThat(b.type).isEqualTo(StepType.WORK)
            }
        }
    }
}
