package au.mark.kinetiq.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ExerciseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ExerciseEntity>)

    @Query("SELECT * FROM exercises")
    suspend fun all(): List<ExerciseEntity>

    @Query("DELETE FROM exercises")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutines(items: List<RoutineEntity>)

    @Query("SELECT * FROM routines")
    suspend fun allRoutines(): List<RoutineEntity>

    @Query("DELETE FROM routines")
    suspend fun clearRoutines()

    @Query("SELECT value FROM db_meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMeta(meta: DbMetaEntity)
}

@Dao
interface WorkoutDao {
    @Insert
    suspend fun saveWorkout(w: SavedWorkoutEntity): Long

    @Query("SELECT * FROM saved_workouts ORDER BY createdAtEpochMs DESC")
    fun savedWorkouts(): Flow<List<SavedWorkoutEntity>>

    @Query("SELECT * FROM saved_workouts WHERE id = :id")
    suspend fun savedWorkout(id: Long): SavedWorkoutEntity?

    @Query("DELETE FROM saved_workouts WHERE id = :id")
    suspend fun deleteSavedWorkout(id: Long)

    @Query("SELECT * FROM saved_workouts")
    suspend fun savedWorkoutsOnce(): List<SavedWorkoutEntity>

    @Insert
    suspend fun addHistory(h: SessionHistoryEntity): Long

    /** Everything the list, calendar, trends and streak screens need — and no session JSON. */
    @Query(
        "SELECT id, startedAtEpochMs, endedAtEpochMs, name, totalActiveSec, calories, " +
            "blocksJson, healthConnectWritten FROM session_history ORDER BY startedAtEpochMs DESC"
    )
    fun historyRows(): Flow<List<SessionHistoryRow>>

    /** Timestamps only — the widget's streak needs nothing else. */
    @Query("SELECT startedAtEpochMs FROM session_history ORDER BY startedAtEpochMs DESC")
    suspend fun historyStartTimes(): List<Long>

    /** One string — the widget's "Repeat: <name>" line. */
    @Query("SELECT name FROM session_history ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun lastSessionName(): String?

    /** Repeat-last genuinely needs the stored session, so this one still selects it. */
    @Query("SELECT * FROM session_history ORDER BY startedAtEpochMs DESC LIMIT 1")
    suspend fun lastSession(): SessionHistoryEntity?

    /** Export needs every stored session; that is the point of an export. */
    @Query("SELECT * FROM session_history")
    suspend fun historyOnce(): List<SessionHistoryEntity>

    @Query("DELETE FROM session_history WHERE id = :id")
    suspend fun deleteHistory(id: Long)

    @Query("UPDATE session_history SET healthConnectWritten = :written WHERE id = :id")
    suspend fun markHcWritten(id: Long, written: Boolean)
}

@Dao
interface MeasurementDao {
    @Insert
    suspend fun add(m: ManualMeasurementEntity)

    @Query("SELECT * FROM manual_measurements WHERE metric = :metric ORDER BY recordedAtEpochMs DESC LIMIT 1")
    suspend fun latest(metric: String): ManualMeasurementEntity?

    @Query("SELECT * FROM manual_measurements ORDER BY recordedAtEpochMs DESC")
    fun all(): Flow<List<ManualMeasurementEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun cache(c: CachedHealthMetricEntity)

    @Query("SELECT * FROM cached_health_metrics WHERE metric = :metric")
    suspend fun cached(metric: String): CachedHealthMetricEntity?

    @Query("SELECT * FROM cached_health_metrics")
    fun cachedAll(): Flow<List<CachedHealthMetricEntity>>
}
