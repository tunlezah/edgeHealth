package au.mark.kinetiq

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import au.mark.kinetiq.data.db.KinetiqDatabase
import au.mark.kinetiq.data.db.SessionHistoryEntity
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.SessionBlock
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.model.WorkoutPlan
import au.mark.kinetiq.data.repo.CompletedBlock
import au.mark.kinetiq.data.repo.WorkoutRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The history list, calendar, trends and streak read no stored session, so the flow query projects
 * it away. These pin the split: the list screens keep everything they render, and the two callers
 * that genuinely need a session still get one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkoutRepositoryProjectionTest {

    private lateinit var db: KinetiqDatabase
    private lateinit var repo: WorkoutRepository
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun sampleSession() = GeneratedSession(
        config = GeneratorConfig(totalDurationMin = 10, categories = listOf(Category.FLOOR)),
        plan = WorkoutPlan(
            steps = listOf(
                SessionStep(StepType.WORK, Category.FLOOR, "floor_squat", "Bodyweight squat", 40, met = 3.8f),
            ),
            blocks = listOf(SessionBlock(Category.FLOOR)),
        ),
    )

    private fun entity(
        startedAt: Long,
        name: String,
        sessionJson: String = json.encodeToString(GeneratedSession.serializer(), sampleSession()),
    ) = SessionHistoryEntity(
        startedAtEpochMs = startedAt,
        endedAtEpochMs = startedAt + 1_800_000,
        name = name,
        totalActiveSec = 1500,
        calories = 180.0,
        blocksJson = json.encodeToString(
            ListSerializer(CompletedBlock.serializer()),
            listOf(CompletedBlock("FLOOR", 1500, 180.0, false, startedAt, startedAt + 1_800_000)),
        ),
        healthConnectWritten = false,
        sessionJson = sessionJson,
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KinetiqDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = WorkoutRepository(db.workoutDao(), json)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `history is newest-first and keeps the per-block breakdown the screens render`() = runTest {
        db.workoutDao().addHistory(entity(1_700_000_000_000, "Older"))
        db.workoutDao().addHistory(entity(1_700_100_000_000, "Newer"))

        val history = repo.history().first()
        assertThat(history.map { it.name }).containsExactly("Newer", "Older").inOrder()
        // retryHcWrite needs blocks, calories and both timestamps — all still present.
        assertThat(history.first().blocks).hasSize(1)
        assertThat(history.first().calories).isEqualTo(180.0)
        assertThat(history.first().healthConnectWritten).isFalse()
    }

    @Test
    fun `a row with corrupt session json still appears in the history list`() = runTest {
        // The list query never touches sessionJson, so a row that cannot be repeated is still
        // visible and deletable rather than silently missing.
        db.workoutDao().addHistory(entity(1_700_000_000_000, "Corrupt", sessionJson = "{not json"))
        assertThat(repo.history().first().map { it.name }).containsExactly("Corrupt")
    }

    @Test
    fun `repeat-last still resolves the full session`() = runTest {
        db.workoutDao().addHistory(entity(1_700_000_000_000, "Leg day"))
        val last = repo.lastSessionForRepeat()
        assertThat(last).isNotNull()
        assertThat(last!!.entry.name).isEqualTo("Leg day")
        assertThat(last.session?.plan?.steps).hasSize(1)
    }

    @Test
    fun `repeat-last yields a null session for a corrupt row so callers bail`() = runTest {
        db.workoutDao().addHistory(entity(1_700_000_000_000, "Corrupt", sessionJson = "{not json"))
        assertThat(repo.lastSessionForRepeat()!!.session).isNull()
    }

    @Test
    fun `widget projections return the name and every start time without decoding a session`() = runTest {
        db.workoutDao().addHistory(entity(1_700_000_000_000, "Older", sessionJson = "{not json"))
        db.workoutDao().addHistory(entity(1_700_100_000_000, "Newer", sessionJson = "{not json"))

        assertThat(repo.lastSessionName()).isEqualTo("Newer")
        assertThat(repo.historyStartTimes()).containsExactly(1_700_100_000_000, 1_700_000_000_000).inOrder()
    }

    @Test
    fun `export still carries the stored session for every entry`() = runTest {
        db.workoutDao().addHistory(entity(1_700_000_000_000, "Leg day"))
        val exported = repo.historyOnce()
        assertThat(exported).hasSize(1)
        assertThat(exported.first().entry.name).isEqualTo("Leg day")
        assertThat(exported.first().session).isNotNull()
    }
}
