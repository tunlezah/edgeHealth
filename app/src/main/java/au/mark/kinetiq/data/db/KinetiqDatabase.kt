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
    exportSchema = false,
)
abstract class KinetiqDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun measurementDao(): MeasurementDao
}
