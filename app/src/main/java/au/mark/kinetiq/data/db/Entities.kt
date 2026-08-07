package au.mark.kinetiq.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Exercise rows are loaded from the bundled JSON on first run (and on schema-version bumps). */
@Entity(tableName = "exercises")
data class ExerciseEntity(
    @PrimaryKey val id: String,
    val category: String,
    /** Full Exercise model serialized as JSON — the JSON asset is the source of truth. */
    val json: String,
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey val id: String,
    val category: String,
    val json: String,
)

@Entity(tableName = "db_meta")
data class DbMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "saved_workouts")
data class SavedWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtEpochMs: Long,
    /** GeneratedSession JSON (config + resolved plan). */
    val json: String,
)

@Entity(tableName = "session_history")
data class SessionHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val name: String,
    val totalActiveSec: Int,
    val calories: Double,
    /** Per-block breakdown JSON: List<CompletedBlock>. */
    val blocksJson: String,
    val healthConnectWritten: Boolean,
    /** GeneratedSession JSON so the session can be repeated. */
    val sessionJson: String,
)

/** Manually entered body metrics; freshest value per metric wins in heuristics. */
@Entity(tableName = "manual_measurements")
data class ManualMeasurementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** WEIGHT_KG, HEIGHT_CM, BODY_FAT_PCT, WAIST_CM, VISCERAL_RATING */
    val metric: String,
    val value: Double,
    val recordedAtEpochMs: Long,
)

/** Cached Health Connect values with source app + timestamp for display. */
@Entity(tableName = "cached_health_metrics")
data class CachedHealthMetricEntity(
    @PrimaryKey val metric: String,
    val value: Double,
    val recordedAtEpochMs: Long,
    val sourceApp: String,
)
