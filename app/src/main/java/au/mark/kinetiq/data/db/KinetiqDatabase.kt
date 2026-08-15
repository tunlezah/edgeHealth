package au.mark.kinetiq.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        ExerciseEntity::class,
        RoutineEntity::class,
        DbMetaEntity::class,
        SavedWorkoutEntity::class,
        SessionHistoryEntity::class,
        ManualMeasurementEntity::class,
        CachedHealthMetricEntity::class,
    ],
    version = 1,
    // Schemas are exported to app/schemas and checked in. Room only validates the identity hash
    // stored in room_master_table at runtime — it never reads these JSON files — so the export is
    // purely a build artifact. It is what makes a real Migration authorable when an entity changes.
    exportSchema = true,
)
abstract class KinetiqDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun measurementDao(): MeasurementDao
}
