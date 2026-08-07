package au.mark.kinetiq.data.repo

import au.mark.kinetiq.data.db.SavedWorkoutEntity
import au.mark.kinetiq.data.db.SessionHistoryEntity
import au.mark.kinetiq.data.db.WorkoutDao
import au.mark.kinetiq.data.model.GeneratedSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class CompletedBlock(
    val category: String,
    val activeSec: Int,
    val calories: Double,
    val isHiit: Boolean,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
)

data class SavedWorkout(val id: Long, val name: String, val createdAtEpochMs: Long, val session: GeneratedSession)

data class HistoryEntry(
    val id: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val name: String,
    val totalActiveSec: Int,
    val calories: Double,
    val blocks: List<CompletedBlock>,
    val healthConnectWritten: Boolean,
    val session: GeneratedSession?,
)

@Singleton
class WorkoutRepository @Inject constructor(
    private val dao: WorkoutDao,
    private val json: Json,
) {
    fun savedWorkouts(): Flow<List<SavedWorkout>> = dao.savedWorkouts().map { list -> list.mapNotNull { it.toModel() } }

    suspend fun savedWorkout(id: Long): SavedWorkout? = dao.savedWorkout(id)?.toModel()

    suspend fun saveWorkout(name: String, session: GeneratedSession): Long =
        dao.saveWorkout(
            SavedWorkoutEntity(
                name = name,
                createdAtEpochMs = System.currentTimeMillis(),
                json = json.encodeToString(GeneratedSession.serializer(), session),
            )
        )

    suspend fun deleteSavedWorkout(id: Long) = dao.deleteSavedWorkout(id)

    fun history(): Flow<List<HistoryEntry>> = dao.history().map { list -> list.map { it.toModel() } }

    suspend fun lastSession(): HistoryEntry? = dao.lastSession()?.toModel()

    fun lastSessionFlow(): Flow<HistoryEntry?> = dao.lastSessionFlow().map { it?.toModel() }

    suspend fun addHistory(
        startedAtEpochMs: Long,
        endedAtEpochMs: Long,
        name: String,
        totalActiveSec: Int,
        calories: Double,
        blocks: List<CompletedBlock>,
        healthConnectWritten: Boolean,
        session: GeneratedSession,
    ): Long = dao.addHistory(
        SessionHistoryEntity(
            startedAtEpochMs = startedAtEpochMs,
            endedAtEpochMs = endedAtEpochMs,
            name = name,
            totalActiveSec = totalActiveSec,
            calories = calories,
            blocksJson = json.encodeToString(ListSerializer(CompletedBlock.serializer()), blocks),
            healthConnectWritten = healthConnectWritten,
            sessionJson = json.encodeToString(GeneratedSession.serializer(), session),
        )
    )

    suspend fun deleteHistory(id: Long) = dao.deleteHistory(id)
    suspend fun markHcWritten(id: Long) = dao.markHcWritten(id, true)

    suspend fun savedWorkoutsOnce(): List<SavedWorkout> = dao.savedWorkoutsOnce().mapNotNull { it.toModel() }
    suspend fun historyOnce(): List<HistoryEntry> = dao.historyOnce().map { it.toModel() }

    suspend fun importSaved(name: String, createdAtEpochMs: Long, session: GeneratedSession) {
        dao.saveWorkout(
            SavedWorkoutEntity(
                name = name,
                createdAtEpochMs = createdAtEpochMs,
                json = json.encodeToString(GeneratedSession.serializer(), session),
            )
        )
    }

    suspend fun importHistory(entry: HistoryEntry) {
        dao.addHistory(
            SessionHistoryEntity(
                startedAtEpochMs = entry.startedAtEpochMs,
                endedAtEpochMs = entry.endedAtEpochMs,
                name = entry.name,
                totalActiveSec = entry.totalActiveSec,
                calories = entry.calories,
                blocksJson = json.encodeToString(ListSerializer(CompletedBlock.serializer()), entry.blocks),
                healthConnectWritten = entry.healthConnectWritten,
                sessionJson = entry.session?.let { json.encodeToString(GeneratedSession.serializer(), it) } ?: "",
            )
        )
    }

    private fun SavedWorkoutEntity.toModel(): SavedWorkout? = runCatching {
        SavedWorkout(id, name, createdAtEpochMs, this@WorkoutRepository.json.decodeFromString(GeneratedSession.serializer(), this.json))
    }.getOrNull()

    private fun SessionHistoryEntity.toModel(): HistoryEntry = HistoryEntry(
        id = id,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        name = name,
        totalActiveSec = totalActiveSec,
        calories = calories,
        blocks = runCatching { json.decodeFromString(ListSerializer(CompletedBlock.serializer()), blocksJson) }.getOrDefault(emptyList()),
        healthConnectWritten = healthConnectWritten,
        session = runCatching { json.decodeFromString(GeneratedSession.serializer(), sessionJson) }.getOrNull(),
    )
}
