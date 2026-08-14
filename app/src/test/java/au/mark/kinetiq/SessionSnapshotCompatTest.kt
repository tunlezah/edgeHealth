package au.mark.kinetiq

import au.mark.kinetiq.service.SessionSnapshot
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

/** The disk snapshot must decode across app versions in both directions. */
class SessionSnapshotCompatTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val minimalSession = """
        {"config":{},"plan":{"steps":[
            {"type":"WORK","category":"FLOOR","exerciseId":"floor_plank","exerciseName":"Plank","durationSec":40,"met":3.0,"blockIndex":0}
        ],"blocks":[{"category":"FLOOR"}]}}
    """.trimIndent()

    @Test
    fun `legacy snapshot json without new fields still decodes`() {
        // Exactly the nine pre-Phase-1 fields.
        val legacy = """
            {"session":$minimalSession,"sessionName":"Old workout","stepIndex":0,
             "stepRemainingMs":12000,"totalElapsedActiveMs":30000,"caloriesSoFar":12.5,
             "startedAtEpochMs":1700000000000,"weightKg":82.0,"savedAtEpochMs":1700000100000}
        """.trimIndent()
        val snap = json.decodeFromString(SessionSnapshot.serializer(), legacy)
        assertThat(snap.blockActiveMs).isEmpty()
        assertThat(snap.blockBounds).isEmpty()
        assertThat(snap.prepareRemainingMs).isEqualTo(0)
        assertThat(snap.stepRemainingMs).isEqualTo(12000)
        assertThat(snap.sessionName).isEqualTo("Old workout")
    }

    @Test
    fun `snapshot round-trips block accounting and prepare state`() {
        val original = json.decodeFromString(
            SessionSnapshot.serializer(),
            """
            {"session":$minimalSession,"sessionName":"W","stepIndex":0,
             "stepRemainingMs":1000,"totalElapsedActiveMs":2000,"caloriesSoFar":1.0,
             "startedAtEpochMs":1,"weightKg":80.0,"savedAtEpochMs":2}
            """.trimIndent(),
        ).copy(
            blockActiveMs = mapOf(0 to 90_000L, 1 to 45_000L),
            blockBounds = mapOf(0 to listOf(100L, 200L), 1 to listOf(300L, 400L)),
            prepareRemainingMs = 3_000,
        )
        val decoded = json.decodeFromString(
            SessionSnapshot.serializer(),
            json.encodeToString(SessionSnapshot.serializer(), original),
        )
        assertThat(decoded).isEqualTo(original)
    }
}
