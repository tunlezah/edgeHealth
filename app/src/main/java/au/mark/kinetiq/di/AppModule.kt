package au.mark.kinetiq.di

import android.content.Context
import androidx.room.Room
import au.mark.kinetiq.data.db.ExerciseDao
import au.mark.kinetiq.data.db.KinetiqDatabase
import au.mark.kinetiq.data.db.MeasurementDao
import au.mark.kinetiq.data.db.WorkoutDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KinetiqDatabase =
        Room.databaseBuilder(context, KinetiqDatabase::class.java, "kinetiq.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideExerciseDao(db: KinetiqDatabase): ExerciseDao = db.exerciseDao()
    @Provides fun provideWorkoutDao(db: KinetiqDatabase): WorkoutDao = db.workoutDao()
    @Provides fun provideMeasurementDao(db: KinetiqDatabase): MeasurementDao = db.measurementDao()
}
