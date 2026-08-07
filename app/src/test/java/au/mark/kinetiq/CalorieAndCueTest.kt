package au.mark.kinetiq

import au.mark.kinetiq.data.repo.MachineSettings
import au.mark.kinetiq.data.repo.SpringNotation
import au.mark.kinetiq.domain.CalorieCalculator
import au.mark.kinetiq.domain.MachineCueRenderer
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class CalorieAndCueTest {

    @Test
    fun `kcal follows the compendium formula`() {
        // 8 MET × 80 kg × 0.5 h = 320 kcal
        assertThat(CalorieCalculator.kcal(met = 8f, weightKg = 80.0, seconds = 1800)).isWithin(0.001).of(320.0)
        // 1 hour at 1 MET for 1 kg = 1 kcal
        assertThat(CalorieCalculator.kcal(1f, 1.0, 3600)).isWithin(1e-9).of(1.0)
        assertThat(CalorieCalculator.kcal(5f, 70.0, 0)).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `kcal rejects nonsense inputs`() {
        assertThrows(IllegalArgumentException::class.java) { CalorieCalculator.kcal(0f, 80.0, 60) }
        assertThrows(IllegalArgumentException::class.java) { CalorieCalculator.kcal(5f, 0.0, 60) }
        assertThrows(IllegalArgumentException::class.java) { CalorieCalculator.kcal(5f, 80.0, -1) }
    }

    @Test
    fun `total kcal sums steps`() {
        val total = CalorieCalculator.totalKcal(listOf(8f to 1800, 4f to 1800), 80.0)
        assertThat(total).isWithin(0.001).of(320.0 + 160.0)
    }

    @Test
    fun `spin levels scale to the configured max`() {
        // GR7 default: 11 levels
        assertThat(MachineCueRenderer.levelOf(0.73f, 11)).isEqualTo(8)
        assertThat(MachineCueRenderer.levelOf(0.05f, 11)).isEqualTo(1)  // never below level 1
        assertThat(MachineCueRenderer.levelOf(1.0f, 11)).isEqualTo(11)
        // Same fraction on a 32-level console
        assertThat(MachineCueRenderer.levelOf(0.73f, 32)).isEqualTo(23)
    }

    @Test
    fun `spring notation renders both ways`() {
        assertThat(MachineCueRenderer.renderSprings("MEDIUM_2", SpringNotation.GENERIC)).isEqualTo("two medium springs")
        assertThat(MachineCueRenderer.renderSprings("MEDIUM_2", SpringNotation.COUNT)).isEqualTo("2 springs, medium tension")
        assertThat(MachineCueRenderer.renderSprings("LIGHT", SpringNotation.GENERIC)).isEqualTo("one light spring")
        assertThat(MachineCueRenderer.renderSprings("HEAVY_1", SpringNotation.COUNT)).isEqualTo("1 spring, heavy tension")
    }

    @Test
    fun `spin cue text references level number position and rpm`() {
        val db = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            au.mark.kinetiq.data.model.ExerciseDatabaseFile.serializer(),
            java.io.File("src/main/assets/exercise_db.json").readText(),
        )
        val standingClimb = db.exercises.first { it.id == "spin_standing_climb" }
        val cue = MachineCueRenderer.renderCue(standingClimb, MachineSettings(spinMaxLevel = 11))!!
        assertThat(cue).contains("Standing climb")
        assertThat(cue).contains("resistance 8 to 9")
        assertThat(cue).contains("60 to 75 rpm")
    }

    @Test
    fun `elliptical cue references level direction and arms`() {
        val db = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString(
            au.mark.kinetiq.data.model.ExerciseDatabaseFile.serializer(),
            java.io.File("src/main/assets/exercise_db.json").readText(),
        )
        val reverse = db.exercises.first { it.id == "ell_reverse_stride" }
        val cue = MachineCueRenderer.renderCue(reverse, MachineSettings(ellipticalMaxLevel = 16))!!
        assertThat(cue).contains("Level 7")
        assertThat(cue).contains("reverse stride")
        assertThat(cue).contains("static rail")
    }
}
