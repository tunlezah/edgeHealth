package au.mark.kinetiq

import au.mark.kinetiq.data.export.ExportFile
import au.mark.kinetiq.data.export.ExportImportCodec
import au.mark.kinetiq.data.export.ExportedHistoryEntry
import au.mark.kinetiq.data.export.ExportedWorkout
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.model.ExerciseDatabaseFile
import au.mark.kinetiq.data.model.WorkoutPlan
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import au.mark.kinetiq.domain.plan.StreakCalculator
import au.mark.kinetiq.reminders.ReminderScheduler
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class ExportImportAndMiscTest {

    private fun sampleSession() = GeneratedSession(
        config = GeneratorConfig(totalDurationMin = 10, categories = listOf(Category.FLOOR)),
        plan = WorkoutPlan(
            steps = listOf(
                SessionStep(StepType.WORK, Category.FLOOR, "floor_squat", "Bodyweight squat", 40, met = 3.8f),
                SessionStep(StepType.REST, Category.FLOOR, null, "Rest", 20),
            ),
            blocks = listOf(au.mark.kinetiq.data.model.SessionBlock(Category.FLOOR)),
        ),
    )

    @Test
    fun `export round-trips through import validation`() {
        val file = ExportFile(
            exportedAtEpochMs = 1700000000000,
            savedWorkouts = listOf(ExportedWorkout("Morning", 1700000000000, sampleSession())),
            history = listOf(
                ExportedHistoryEntry(1700000000000, 1700001800000, "Morning", 1500, 180.0, emptyList(), sampleSession()),
            ),
        )
        val encoded = ExportImportCodec.encode(file)
        val result = ExportImportCodec.decodeAndValidate(encoded)
        assertThat(result).isInstanceOf(ExportImportCodec.ImportResult.Success::class.java)
        val decoded = (result as ExportImportCodec.ImportResult.Success).file
        assertThat(decoded.savedWorkouts).hasSize(1)
        assertThat(decoded.history).hasSize(1)
    }

    @Test
    fun `garbage input fails with a clear message`() {
        val result = ExportImportCodec.decodeAndValidate("this is not json at all")
        assertThat(result).isInstanceOf(ExportImportCodec.ImportResult.Failure::class.java)
        assertThat((result as ExportImportCodec.ImportResult.Failure).problems.first())
            .contains("Not a valid Kinetiq export file")
    }

    @Test
    fun `structurally valid but broken entries are reported`() {
        val broken = ExportFile(
            exportedAtEpochMs = 1,
            savedWorkouts = listOf(ExportedWorkout("", 1, sampleSession())),
            history = listOf(ExportedHistoryEntry(200, 100, "Backwards", -5, -1.0, emptyList(), null)),
        )
        val result = ExportImportCodec.decodeAndValidate(ExportImportCodec.encode(broken))
        assertThat(result).isInstanceOf(ExportImportCodec.ImportResult.Failure::class.java)
        val problems = (result as ExportImportCodec.ImportResult.Failure).problems
        assertThat(problems.any { it.contains("blank name") }).isTrue()
        assertThat(problems.any { it.contains("ends before it starts") }).isTrue()
        assertThat(problems.any { it.contains("negative active time") }).isTrue()
        assertThat(problems.any { it.contains("negative calories") }).isTrue()
    }

    @Test
    fun `streak counts consecutive days and respects rest days`() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.of(2026, 8, 7) // a Friday
        fun at(date: LocalDate) = date.atStartOfDay(zone).plusHours(7).toInstant().toEpochMilli()

        // Worked out Mon, Tue, Wed, Thu; Sunday is a rest day.
        val sessions = listOf(
            at(today.minusDays(1)), at(today.minusDays(2)), at(today.minusDays(3)), at(today.minusDays(4)),
        )
        val streak = StreakCalculator.currentStreak(sessions, setOf(DayOfWeek.SUNDAY), today, zone)
        assertThat(streak).isEqualTo(4)

        // A gap on a non-rest day breaks the streak.
        val gappy = listOf(at(today.minusDays(1)), at(today.minusDays(3)))
        assertThat(StreakCalculator.currentStreak(gappy, setOf(DayOfWeek.SUNDAY), today, zone)).isEqualTo(1)

        // Rest day bridges the gap: workout Sat + Mon, Sunday rest, checked on Monday.
        val monday = LocalDate.of(2026, 8, 3)
        val bridged = listOf(at(monday), at(monday.minusDays(2)))
        assertThat(StreakCalculator.currentStreak(bridged, setOf(DayOfWeek.SUNDAY), monday, zone)).isEqualTo(3)

        assertThat(StreakCalculator.currentStreak(emptyList(), setOf(DayOfWeek.SUNDAY), today, zone)).isEqualTo(0)
    }

    @Test
    fun `reminder delay lands on the next configured slot`() {
        // Wednesday 18:00; reminder Mon/Fri at 07:00 → next is Friday 07:00 (37 h away).
        val now = LocalDateTime.of(2026, 8, 5, 18, 0)
        val delay = ReminderScheduler.delayToNext(now, days = setOf(1, 5), hour = 7, minute = 0)
        assertThat(delay.toHours()).isEqualTo(37)

        // Same-day later time is used when still ahead.
        val morning = LocalDateTime.of(2026, 8, 5, 6, 0)
        val sameDay = ReminderScheduler.delayToNext(morning, days = setOf(3), hour = 7, minute = 0)
        assertThat(sameDay.toMinutes()).isEqualTo(60)
    }

    @Test
    fun `no INTERNET permission anywhere in the manifest`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertThat(manifest).doesNotContain("android.permission.INTERNET")
    }

    @Test
    fun `cloud backup is skipped on devices with no encryption capabilities`() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertThat(rules).contains("<cloud-backup disableIfNoEncryptionCapabilities=\"true\">")
        // device-transfer must NOT carry it — D2D never reaches a server.
        assertThat(rules.substringAfter("<device-transfer")).doesNotContain("disableIfNoEncryptionCapabilities")
    }

    private val now = 1_800_000_000_000L

    @Test
    fun `history entry with an empty plan is rejected`() {
        // The concrete cause of the foreground-service watchdog crash: repeat-last hands the
        // service a plan it cannot start, and it bails before calling startForeground.
        val empty = sampleSession().copy(plan = WorkoutPlan(steps = emptyList(), blocks = emptyList()))
        val file = ExportFile(
            exportedAtEpochMs = now,
            savedWorkouts = emptyList(),
            history = listOf(ExportedHistoryEntry(now - 1_800_000, now, "Crafted", 10, 1.0, emptyList(), empty)),
        )
        val result = ExportImportCodec.decodeAndValidate(ExportImportCodec.encode(file), nowEpochMs = now)
        assertThat(result).isInstanceOf(ExportImportCodec.ImportResult.Failure::class.java)
        assertThat((result as ExportImportCodec.ImportResult.Failure).problems.any { it.contains("no steps") })
            .isTrue()
    }

    @Test
    fun `history entry with no stored session is still accepted`() {
        // importHistory writes an empty sessionJson for these, and it decodes back to null —
        // rejecting them would break re-importing previously imported data.
        val file = ExportFile(
            exportedAtEpochMs = now,
            savedWorkouts = emptyList(),
            history = listOf(ExportedHistoryEntry(now - 1_800_000, now, "Legacy", 1500, 180.0, emptyList(), null)),
        )
        assertThat(ExportImportCodec.decodeAndValidate(ExportImportCodec.encode(file), nowEpochMs = now))
            .isInstanceOf(ExportImportCodec.ImportResult.Success::class.java)
    }

    @Test
    fun `implausible history timestamps are rejected`() {
        val future = ExportFile(
            exportedAtEpochMs = now,
            savedWorkouts = emptyList(),
            history = listOf(
                ExportedHistoryEntry(
                    now + 400L * 24 * 3600 * 1000, now + 401L * 24 * 3600 * 1000,
                    "Future", 10, 1.0, emptyList(), sampleSession(),
                ),
            ),
        )
        val futureResult = ExportImportCodec.decodeAndValidate(ExportImportCodec.encode(future), nowEpochMs = now)
        assertThat((futureResult as ExportImportCodec.ImportResult.Failure).problems.any { it.contains("in the future") })
            .isTrue()

        val ancient = ExportFile(
            exportedAtEpochMs = now,
            savedWorkouts = emptyList(),
            history = listOf(ExportedHistoryEntry(1_000L, 2_000L, "Ancient", 10, 1.0, emptyList(), sampleSession())),
        )
        val ancientResult = ExportImportCodec.decodeAndValidate(ExportImportCodec.encode(ancient), nowEpochMs = now)
        assertThat((ancientResult as ExportImportCodec.ImportResult.Failure).problems.any { it.contains("before 2020") })
            .isTrue()
    }

    @Test
    fun `a freshly generated session survives import validation unchanged`() {
        // The real safety net: the new rules must never reject what buildExport() can actually
        // produce, sentinel warm-up/cool-down block indices included.
        val db = Json { ignoreUnknownKeys = true }.decodeFromString(
            ExerciseDatabaseFile.serializer(),
            File("src/main/assets/exercise_db.json").readText(),
        )
        val generated = WorkoutGenerator(db.exercises, db.routines)
            .generate(
                GeneratorConfig(totalDurationMin = 30, categories = listOf(Category.FLOOR, Category.SPIN)),
            ).session

        assertThat(generated.plan.steps.map { it.blockIndex })
            .containsAtLeast(WorkoutGenerator.WARMUP_BLOCK_INDEX, WorkoutGenerator.COOLDOWN_BLOCK_INDEX)

        val file = ExportFile(
            exportedAtEpochMs = now,
            savedWorkouts = listOf(ExportedWorkout("Gen", now, generated)),
            history = listOf(
                ExportedHistoryEntry(now - 1_800_000, now, "Gen", 1500, 180.0, emptyList(), generated),
            ),
        )
        val result = ExportImportCodec.decodeAndValidate(ExportImportCodec.encode(file), nowEpochMs = now)
        assertThat(result).isInstanceOf(ExportImportCodec.ImportResult.Success::class.java)
        assertThat((result as ExportImportCodec.ImportResult.Success).warnings).isEmpty()
    }
}
