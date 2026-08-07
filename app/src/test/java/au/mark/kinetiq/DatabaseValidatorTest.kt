package au.mark.kinetiq

import au.mark.kinetiq.anim.AnimationRegistry
import au.mark.kinetiq.data.DatabaseValidator
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.ExerciseDatabaseFile
import au.mark.kinetiq.data.model.ExerciseKind
import au.mark.kinetiq.data.model.Impact
import au.mark.kinetiq.data.model.Intensity
import au.mark.kinetiq.data.model.Target
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File

class DatabaseValidatorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadRealDatabase(): ExerciseDatabaseFile {
        val file = File("src/main/assets/exercise_db.json")
        assertThat(file.exists()).isTrue()
        return json.decodeFromString(ExerciseDatabaseFile.serializer(), file.readText())
    }

    @Test
    fun `bundled database passes full validation`() {
        val db = loadRealDatabase()
        val result = DatabaseValidator.validate(db, AnimationRegistry.ids)
        assertThat(result.problems).isEmpty()
        assertThat(result.isValid).isTrue()
    }

    @Test
    fun `bundled database meets minimum content counts`() {
        val db = loadRealDatabase()
        val byCat = db.exercises.groupBy { it.category }
        assertThat(byCat[Category.FLOOR]!!.size).isAtLeast(18)
        assertThat(byCat[Category.REFORMER]!!.size).isAtLeast(14)
        assertThat(byCat[Category.SPIN]!!.count { it.kind == ExerciseKind.INTERVAL_SEGMENT }).isAtLeast(10)
        assertThat(byCat[Category.ELLIPTICAL]!!.count { it.kind == ExerciseKind.INTERVAL_SEGMENT }).isAtLeast(8)
        assertThat(db.routines.count { it.category == Category.SPIN }).isAtLeast(6)
        assertThat(db.routines.count { it.category == Category.ELLIPTICAL }).isAtLeast(4)
    }

    @Test
    fun `every STRONG and MODERATE exercise carries at least one reference`() {
        val db = loadRealDatabase()
        db.exercises.filter { it.evidenceTier != EvidenceTier.LIMITED }.forEach { ex ->
            assertThat(ex.references).isNotEmpty()
        }
    }

    @Test
    fun `every LIMITED exercise carries an honest popularity note`() {
        val db = loadRealDatabase()
        val limited = db.exercises.filter { it.evidenceTier == EvidenceTier.LIMITED }
        assertThat(limited).isNotEmpty()
        limited.forEach { ex -> assertThat(ex.popularityNote).isNotEmpty() }
    }

    @Test
    fun `every animation id resolves`() {
        val db = loadRealDatabase()
        db.exercises.forEach { ex ->
            assertThat(AnimationRegistry.ids).contains(ex.animationId)
        }
    }

    // ---- negative cases against a synthetic db ----

    private fun syntheticExercise(
        id: String = "x",
        tier: EvidenceTier = EvidenceTier.STRONG,
        refs: Int = 1,
        note: String? = null,
    ) = Exercise(
        id = id, name = "Test move", category = Category.FLOOR, kind = ExerciseKind.DISCRETE,
        evidenceTier = tier,
        references = List(refs) {
            au.mark.kinetiq.data.model.Reference(
                "A real title", "Someone A", 2020, "A Journal", "10.1000/x", "A finding.",
            )
        },
        popularityNote = note,
        summary = "A summary.", voiceName = "Test move",
        voiceHowTo = "Do the thing. Then do it again.",
        voiceFormCues = listOf("Stay strong."),
        defaultWorkSec = 30, defaultRestSec = 15, minSec = 15, maxSec = 60,
        met = 4f, intensity = Intensity.MODERATE, impact = Impact.LOW,
        targets = listOf(Target.STRENGTH), animationId = "fl_squat",
    )

    @Test
    fun `STRONG without references is rejected`() {
        val db = ExerciseDatabaseFile(1, listOf(syntheticExercise(refs = 0)), emptyList())
        val result = DatabaseValidator.validate(db, AnimationRegistry.ids)
        assertThat(result.problems.any { it.contains("requires >= 1 reference") }).isTrue()
    }

    @Test
    fun `LIMITED without popularityNote is rejected`() {
        val db = ExerciseDatabaseFile(1, listOf(syntheticExercise(tier = EvidenceTier.LIMITED, refs = 0, note = null)), emptyList())
        val result = DatabaseValidator.validate(db, AnimationRegistry.ids)
        assertThat(result.problems.any { it.contains("requires popularityNote") }).isTrue()
    }

    @Test
    fun `placeholder text is rejected`() {
        val db = ExerciseDatabaseFile(
            1, listOf(syntheticExercise().copy(summary = "TODO write this later")), emptyList(),
        )
        val result = DatabaseValidator.validate(db, AnimationRegistry.ids)
        assertThat(result.problems.any { it.contains("placeholder") }).isTrue()
    }

    @Test
    fun `unresolvable animation id is rejected`() {
        val db = ExerciseDatabaseFile(1, listOf(syntheticExercise().copy(animationId = "nope_missing")), emptyList())
        val result = DatabaseValidator.validate(db, AnimationRegistry.ids)
        assertThat(result.problems.any { it.contains("does not resolve") }).isTrue()
    }
}
