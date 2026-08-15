package au.mark.kinetiq

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import au.mark.kinetiq.service.WorkoutSessionService
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Summary screen's "Resume workout" offer must be gated on a full read *and parse*, never on
 * existence + mtime. A file that exists but cannot be parsed used to pass the gate, and tapping the
 * button then deleted the history row before discovering the restore could not happen — losing the
 * workout and crashing the service on the foreground-start watchdog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 supports up to SDK 35; app targets 36.
class StoppedSnapshotGateTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val minimalSession = """
        {"config":{},"plan":{"steps":[
            {"type":"WORK","category":"FLOOR","exerciseId":"floor_plank","exerciseName":"Plank","durationSec":40,"met":3.0,"blockIndex":0}
        ],"blocks":[{"category":"FLOOR"}]}}
    """.trimIndent()

    private fun validSnapshotJson() = """
        {"session":$minimalSession,"sessionName":"Leg day","stepIndex":0,
         "stepRemainingMs":12000,"totalElapsedActiveMs":30000,"caloriesSoFar":12.5,
         "startedAtEpochMs":1700000000000,"weightKg":82.0,"savedAtEpochMs":1700000100000}
    """.trimIndent()

    @After
    fun tearDown() {
        WorkoutSessionService.stoppedSnapshotFile(context).delete()
    }

    @Test
    fun `a valid in-window snapshot is offered`() {
        WorkoutSessionService.stoppedSnapshotFile(context).writeText(validSnapshotJson())
        assertThat(WorkoutSessionService.hasStoppedSnapshot(context)).isTrue()
        val snap = WorkoutSessionService.readStoppedSnapshot(context, json)
        assertThat(snap).isNotNull()
        assertThat(snap!!.session.plan.steps).isNotEmpty()
    }

    @Test
    fun `a truncated snapshot passes the mtime gate but is rejected by the parse gate`() {
        // Exactly what a kill mid-write used to leave behind, before tmp + rename.
        WorkoutSessionService.stoppedSnapshotFile(context)
            .writeText("""{"session":{"config":{},"plan":{"steps":[""")
        assertThat(WorkoutSessionService.hasStoppedSnapshot(context)).isTrue()
        assertThat(WorkoutSessionService.readStoppedSnapshot(context, json)).isNull()
    }

    @Test
    fun `a snapshot older than the advertised window is not offered`() {
        val file = WorkoutSessionService.stoppedSnapshotFile(context)
        file.writeText(validSnapshotJson())
        file.setLastModified(System.currentTimeMillis() - WorkoutSessionService.STOPPED_SNAPSHOT_VALID_MS - 60_000)
        assertThat(WorkoutSessionService.hasStoppedSnapshot(context)).isFalse()
        assertThat(WorkoutSessionService.readStoppedSnapshot(context, json)).isNull()
    }

    @Test
    fun `a missing snapshot is not offered`() {
        WorkoutSessionService.stoppedSnapshotFile(context).delete()
        assertThat(WorkoutSessionService.hasStoppedSnapshot(context)).isFalse()
        assertThat(WorkoutSessionService.readStoppedSnapshot(context, json)).isNull()
    }
}
