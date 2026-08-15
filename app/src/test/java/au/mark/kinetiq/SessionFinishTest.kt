package au.mark.kinetiq

import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.SessionBlock
import au.mark.kinetiq.data.model.WorkoutPlan
import au.mark.kinetiq.data.repo.AppSettings
import au.mark.kinetiq.service.completedBlocks
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * finishSession's body has to be exception-total: a throw there would skip the history write and
 * escape into lifecycleScope with no handler, losing a completed workout *and* crashing the
 * process. These pin the fallbacks it now relies on.
 */
class SessionFinishTest {

    @Test
    fun `completedBlocks tolerates an empty plan`() {
        val blocks = completedBlocks(
            plan = WorkoutPlan(steps = emptyList(), blocks = emptyList()),
            blockActiveMs = emptyMap(),
            blockBounds = emptyMap(),
            weightKg = 80.0,
            fallbackBounds = 0L to 1L,
        )
        assertThat(blocks).isEmpty()
    }

    @Test
    fun `completedBlocks tolerates blocks with no accrued time`() {
        val blocks = completedBlocks(
            plan = WorkoutPlan(steps = emptyList(), blocks = listOf(SessionBlock(Category.FLOOR))),
            blockActiveMs = emptyMap(),
            blockBounds = emptyMap(),
            weightKg = 80.0,
            fallbackBounds = 100L to 200L,
        )
        assertThat(blocks).isEmpty()
    }

    @Test
    fun `default settings keep Health Connect off so a settings failure never writes`() {
        // finishSession falls back to AppSettings() when the DataStore read throws; that fallback
        // must fail closed rather than writing records the user never consented to.
        assertThat(AppSettings().healthConnectEnabled).isFalse()
    }
}
