package au.mark.kinetiq

import androidx.health.connect.client.records.ExerciseSessionRecord
import au.mark.kinetiq.data.repo.CompletedBlock
import au.mark.kinetiq.health.HealthConnectManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Which Health Connect exercise type each kind of completed block is written as. */
class HealthConnectTypeTest {

    private fun block(category: String, isHiit: Boolean = false, isResistance: Boolean = false) = CompletedBlock(
        category = category, activeSec = 600, calories = 50.0, isHiit = isHiit,
        startedAtEpochMs = 1, endedAtEpochMs = 2, isResistance = isResistance,
    )

    @Test
    fun `set-based mat work is written as strength training`() {
        assertThat(HealthConnectManager.exerciseTypeFor(block("FLOOR", isResistance = true)))
            .isEqualTo(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING)
    }

    @Test
    fun `reformer sets stay Pilates`() {
        assertThat(HealthConnectManager.exerciseTypeFor(block("REFORMER", isResistance = true)))
            .isEqualTo(ExerciseSessionRecord.EXERCISE_TYPE_PILATES)
    }

    @Test
    fun `every type written before the feature is unchanged`() {
        assertThat(HealthConnectManager.exerciseTypeFor(block("FLOOR")))
            .isEqualTo(ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS)
        assertThat(HealthConnectManager.exerciseTypeFor(block("BACK")))
            .isEqualTo(ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS)
        assertThat(HealthConnectManager.exerciseTypeFor(block("REFORMER")))
            .isEqualTo(ExerciseSessionRecord.EXERCISE_TYPE_PILATES)
        assertThat(HealthConnectManager.exerciseTypeFor(block("SPIN")))
            .isEqualTo(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY)
        assertThat(HealthConnectManager.exerciseTypeFor(block("ELLIPTICAL")))
            .isEqualTo(ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL)
        assertThat(HealthConnectManager.exerciseTypeFor(block("SPIN", isHiit = true)))
            .isEqualTo(ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING)
    }
}
