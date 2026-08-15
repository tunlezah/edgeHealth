package au.mark.kinetiq.data.repo

import au.mark.kinetiq.data.db.SavedWorkoutEntity
import au.mark.kinetiq.data.db.SessionHistoryEntity
import au.mark.kinetiq.data.db.SessionHistoryRow
import au.mark.kinetiq.data.db.WorkoutDao
import au.mark.kinetiq.data.model.GeneratedSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
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

/**
 * History as the list, calendar, trends and streak screens need it. Deliberately carries no
 * [GeneratedSession]: no UI consumer reads it, and decoding it per row put a full plan parse on
 * the collector's thread for every row in the table.
 */
data class HistoryEntry(
    val id: Long,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val name: String,
    val totalActiveSec: Int,
    val calories: Double,
    val blocks: List<CompletedBlock>,
    val healthConnectWritten: Boolean,
)

/** A history row with its stored session — only repeat-last and export/import need this. */
data class HistoryEntryWithSession(val entry: HistoryEntry, val session: GeneratedSession?)

@Singleton
class WorkoutRepository @Inject constructor(
    private val dao: WorkoutDao,
    private val json: Json,
) {
    /**
     * Saved workouts genuinely need their session — Home renders plan totals and categories — so
     * the decode can only be moved off the main thread, not removed.
     */
    fun savedWorkouts(): Flow<List<SavedWorkout>> = dao.savedWorkouts()
        .map { list -> list.mapNotNull { it.toModel() } }
        .flowOn(Dispatchers.Default)

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

    /**
     * Room emits downstream in the *collector's* context — CoroutinesRoom.createFlow runs only the
     * query on the query executor, while emitAll runs in the flow builder's collector context — and
     * every collector here is `stateIn(viewModelScope, …)`, i.e. Main.immediate. Without flowOn the
     * per-row block decode ran on the main thread on every screen entry and every session finish.
     */
    fun history(): Flow<List<HistoryEntry>> = dao.historyRows()
        .map { rows -> rows.map { it.toModel() } }
        .flowOn(Dispatchers.Default)

    /** The widget's streak needs timestamps only — no JSON decoded at all. */
    suspend fun historyStartTimes(): List<Long> = dao.historyStartTimes()

    /** The widget's "Repeat: <name>" line. */
    suspend fun lastSessionName(): String? = dao.lastSessionName()

    suspend fun lastSessionForRepeat(): HistoryEntryWithSession? = dao.lastSession()?.toModelWithSession()

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

    /** Export/import: the only caller that legitimately wants every stored session. */
    suspend fun historyOnce(): List<HistoryEntryWithSession> = dao.historyOnce().map { it.toModelWithSession() }

    suspend fun importSaved(name: String, createdAtEpochMs: Long, session: GeneratedSession) {
        dao.saveWorkout(
            SavedWorkoutEntity(
                name = name,
                createdAtEpochMs = createdAtEpochMs,
                json = json.encodeToString(GeneratedSession.serializer(), session),
            )
        )
    }

    suspend fun importHistory(entry: HistoryEntry, session: GeneratedSession?) {
        dao.addHistory(
            SessionHistoryEntity(
                startedAtEpochMs = entry.startedAtEpochMs,
                endedAtEpochMs = entry.endedAtEpochMs,
                name = entry.name,
                totalActiveSec = entry.totalActiveSec,
                calories = entry.calories,
                blocksJson = json.encodeToString(ListSerializer(CompletedBlock.serializer()), entry.blocks),
                healthConnectWritten = entry.healthConnectWritten,
                sessionJson = session?.let { json.encodeToString(GeneratedSession.serializer(), it) } ?: "",
            )
        )
    }

    private fun SavedWorkoutEntity.toModel(): SavedWorkout? = runCatching {
        SavedWorkout(id, name, createdAtEpochMs, this@WorkoutRepository.json.decodeFromString(GeneratedSession.serializer(), this.json))
    }.getOrNull()

    private fun SessionHistoryRow.toModel(): HistoryEntry = HistoryEntry(
        id = id,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        name = name,
        totalActiveSec = totalActiveSec,
        calories = calories,
        blocks = runCatching {
            json.decodeFromString(ListSerializer(CompletedBlock.serializer()), blocksJson)
        }.getOrDefault(emptyList()),
        healthConnectWritten = healthConnectWritten,
    )

    private fun SessionHistoryEntity.toModelWithSession(): HistoryEntryWithSession = HistoryEntryWithSession(
        entry = HistoryEntry(
            id = id,
            startedAtEpochMs = startedAtEpochMs,
            endedAtEpochMs = endedAtEpochMs,
            name = name,
            totalActiveSec = totalActiveSec,
            calories = calories,
            blocks = runCatching {
                json.decodeFromString(ListSerializer(CompletedBlock.serializer()), blocksJson)
            }.getOrDefault(emptyList()),
            healthConnectWritten = healthConnectWritten,
        ),
        session = runCatching { json.decodeFromString(GeneratedSession.serializer(), sessionJson) }.getOrNull(),
    )
}
