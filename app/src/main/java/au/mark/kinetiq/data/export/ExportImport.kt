package au.mark.kinetiq.data.export

import au.mark.kinetiq.data.model.GeneratedSession
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

    fun decodeAndValidate(raw: String): ImportResult {
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
            if (w.session.plan.steps.isEmpty()) problems += "Saved workout '${w.name}': plan has no steps"
            if (w.session.plan.steps.any { it.durationSec <= 0 })
                problems += "Saved workout '${w.name}': step with non-positive duration"
        }
        parsed.history.forEachIndexed { i, h ->
            if (h.endedAtEpochMs < h.startedAtEpochMs) problems += "History entry #${i + 1}: ends before it starts"
            if (h.totalActiveSec < 0) problems += "History entry #${i + 1}: negative active time"
            if (h.calories < 0) problems += "History entry #${i + 1}: negative calories"
        }

        return if (problems.isEmpty()) ImportResult.Success(parsed, warnings) else ImportResult.Failure(problems)
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
