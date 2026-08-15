package au.mark.kinetiq.data.export

import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import au.mark.kinetiq.data.repo.CompletedBlock
import au.mark.kinetiq.data.repo.HistoryEntry
import au.mark.kinetiq.data.repo.SavedWorkout
import au.mark.kinetiq.data.repo.WorkoutRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Export file model: saved workouts + history, versioned for forward compatibility. */
@Serializable
data class ExportFile(
    val formatVersion: Int = FORMAT_VERSION,
    val exportedAtEpochMs: Long,
    val savedWorkouts: List<ExportedWorkout>,
    val history: List<ExportedHistoryEntry>,
) {
    companion object { const val FORMAT_VERSION = 1 }
}

@Serializable
data class ExportedWorkout(
    val name: String,
    val createdAtEpochMs: Long,
    val session: GeneratedSession,
)

@Serializable
data class ExportedHistoryEntry(
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val name: String,
    val totalActiveSec: Int,
    val calories: Double,
    val blocks: List<CompletedBlock>,
    val session: GeneratedSession?,
)

/**
 * Pure validation/serialization for export/import — the SAF file plumbing lives in the UI layer.
 * Import validates against the schema and reports every problem clearly.
 */
object ExportImportCodec {

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(file: ExportFile): String = json.encodeToString(ExportFile.serializer(), file)

    sealed interface ImportResult {
        data class Success(val file: ExportFile, val warnings: List<String>) : ImportResult
        data class Failure(val problems: List<String>) : ImportResult
    }

    /** Kinetiq did not exist before 2020 — anything older is a corrupt or hand-edited timestamp. */
    internal const val MIN_PLAUSIBLE_EPOCH_MS = 1_577_836_800_000L // 2020-01-01T00:00:00Z

    /** Grace for clock skew and timezone confusion when judging "in the future". */
    internal const val FUTURE_GRACE_MS = 24L * 60 * 60 * 1000

    fun decodeAndValidate(raw: String, nowEpochMs: Long = System.currentTimeMillis()): ImportResult {
        val parsed = try {
            json.decodeFromString(ExportFile.serializer(), raw)
        } catch (e: Exception) {
            return ImportResult.Failure(listOf("Not a valid Kinetiq export file: ${e.message?.take(200)}"))
        }

        val problems = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (parsed.formatVersion > ExportFile.FORMAT_VERSION)
            warnings += "File was exported by a newer app version (format ${parsed.formatVersion}); unknown fields are ignored."
        if (parsed.formatVersion < 1) problems += "Invalid formatVersion ${parsed.formatVersion}"

        parsed.savedWorkouts.forEachIndexed { i, w ->
            if (w.name.isBlank()) problems += "Saved workout #${i + 1}: blank name"
            problems += planProblems("Saved workout '${w.name}'", w.session)
            warnings += planWarnings("Saved workout '${w.name}'", w.session)
        }
        parsed.history.forEachIndexed { i, h ->
            val label = "History entry #${i + 1}"
            if (h.endedAtEpochMs < h.startedAtEpochMs) problems += "$label: ends before it starts"
            if (h.totalActiveSec < 0) problems += "$label: negative active time"
            if (h.calories < 0) problems += "$label: negative calories"
            // The newest history row drives repeat-last from Home and from the widget, so an
            // implausible start time is a correctness problem, not a cosmetic one.
            if (h.startedAtEpochMs < MIN_PLAUSIBLE_EPOCH_MS)
                problems += "$label: start time is before 2020 — not a real Kinetiq session"
            if (h.startedAtEpochMs > nowEpochMs + FUTURE_GRACE_MS)
                problems += "$label: start time is in the future"

            // A history entry may legitimately carry NO session: importHistory writes an empty
            // sessionJson for such rows and it decodes back to null. Only validate one that exists,
            // or re-importing previously imported data would fail.
            h.session?.let {
                problems += planProblems(label, it)
                warnings += planWarnings(label, it)
            }
            h.blocks.forEachIndexed { b, block ->
                validateBlock("$label, block #${b + 1}", block, problems, warnings)
            }
        }

        return if (problems.isEmpty()) ImportResult.Success(parsed, warnings) else ImportResult.Failure(problems)
    }

    /**
     * Conditions that make a plan unplayable. A stored session with no steps is the concrete cause
     * of a foreground-service watchdog crash: repeat-last starts the service, which then bails on
     * `steps.firstOrNull()` before it can call startForeground.
     */
    private fun planProblems(label: String, session: GeneratedSession): List<String> = buildList {
        if (session.plan.steps.isEmpty()) add("$label: plan has no steps")
        if (session.plan.steps.any { it.durationSec <= 0 }) add("$label: step with non-positive duration")
    }

    /**
     * Odd but harmless — never reject a whole file over these, or one bad row costs the user every
     * other entry. Note what is deliberately NOT flagged: blockIndex -1 (warm-up) and -2
     * (cool-down) are the generator's sentinels, and block accounting relies on them sitting
     * outside plan.blocks.indices. A naive `blockIndex >= 0` rule would reject every export from a
     * workout with a warm-up, which is the default.
     */
    private fun planWarnings(label: String, session: GeneratedSession): List<String> = buildList {
        val sentinels = setOf(WorkoutGenerator.WARMUP_BLOCK_INDEX, WorkoutGenerator.COOLDOWN_BLOCK_INDEX)
        val valid = session.plan.blocks.indices
        if (session.plan.steps.any { it.blockIndex !in valid && it.blockIndex !in sentinels })
            add("$label: a step points at a block that is not in the plan; its time will not be counted.")
    }

    private fun validateBlock(
        label: String,
        block: CompletedBlock,
        problems: MutableList<String>,
        warnings: MutableList<String>,
    ) {
        if (block.activeSec < 0) problems += "$label: negative active time"
        if (block.calories < 0) problems += "$label: negative calories"
        // A device clock change mid-session can genuinely invert these — warn, do not reject.
        if (block.endedAtEpochMs < block.startedAtEpochMs) warnings += "$label: ends before it starts."
        if (Category.entries.none { it.name == block.category })
            warnings += "$label: unknown category '${block.category}'."
    }
}

@Singleton
class ExportImportManager @Inject constructor(
    private val workoutRepository: WorkoutRepository,
) {
    suspend fun buildExport(): ExportFile {
        val saved = workoutRepository.savedWorkoutsOnce()
        val history = workoutRepository.historyOnce()
        return ExportFile(
            exportedAtEpochMs = System.currentTimeMillis(),
            savedWorkouts = saved.map { ExportedWorkout(it.name, it.createdAtEpochMs, it.session) },
            history = history.map {
                ExportedHistoryEntry(
                    it.entry.startedAtEpochMs, it.entry.endedAtEpochMs, it.entry.name,
                    it.entry.totalActiveSec, it.entry.calories, it.entry.blocks, it.session,
                )
            },
        )
    }

    /** Imports a validated file; returns (workouts, history) counts. */
    suspend fun applyImport(file: ExportFile): Pair<Int, Int> {
        var w = 0
        var h = 0
        val existing = workoutRepository.savedWorkoutsOnce()
        for (workout in file.savedWorkouts) {
            val dup = existing.any { it.name == workout.name && it.createdAtEpochMs == workout.createdAtEpochMs }
            if (!dup) {
                workoutRepository.importSaved(workout.name, workout.createdAtEpochMs, workout.session)
                w++
            }
        }
        val existingHistory = workoutRepository.historyOnce()
        for (entry in file.history) {
            val dup = existingHistory.any {
                it.entry.startedAtEpochMs == entry.startedAtEpochMs && it.entry.name == entry.name
            }
            if (!dup) {
                workoutRepository.importHistory(
                    HistoryEntry(
                        id = 0,
                        startedAtEpochMs = entry.startedAtEpochMs,
                        endedAtEpochMs = entry.endedAtEpochMs,
                        name = entry.name,
                        totalActiveSec = entry.totalActiveSec,
                        calories = entry.calories,
                        blocks = entry.blocks,
                        healthConnectWritten = false,
                    ),
                    session = entry.session,
                )
                h++
            }
        }
        return w to h
    }
}
