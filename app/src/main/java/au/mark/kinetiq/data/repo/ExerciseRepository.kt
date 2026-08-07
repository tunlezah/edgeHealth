package au.mark.kinetiq.data.repo

import android.content.Context
import au.mark.kinetiq.anim.AnimationRegistry
import au.mark.kinetiq.data.DatabaseValidator
import au.mark.kinetiq.data.db.DbMetaEntity
import au.mark.kinetiq.data.db.ExerciseDao
import au.mark.kinetiq.data.db.ExerciseEntity
import au.mark.kinetiq.data.db.RoutineEntity
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.ExerciseDatabaseFile
import au.mark.kinetiq.data.model.NamedRoutine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bundled exercise database (assets/exercise_db.json) into Room on first run,
 * re-seeding whenever the asset's schemaVersion is newer than the stored one.
 * Exposes an in-memory snapshot for the generator and library screens.
 */
@Singleton
class ExerciseRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: ExerciseDao,
    private val json: Json,
) {
    private val mutex = Mutex()

    @Volatile
    private var cache: ExerciseDatabaseFile? = null

    suspend fun database(): ExerciseDatabaseFile = cache ?: mutex.withLock {
        cache ?: loadAndSeed().also { cache = it }
    }

    suspend fun exercises(): List<Exercise> = database().exercises
    suspend fun routines(): List<NamedRoutine> = database().routines
    suspend fun exercise(id: String): Exercise? = database().exercises.find { it.id == id }
    suspend fun byCategory(cat: Category): List<Exercise> = database().exercises.filter { it.category == cat }

    private suspend fun loadAndSeed(): ExerciseDatabaseFile {
        val asset = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        val parsed = json.decodeFromString<ExerciseDatabaseFile>(asset)

        val validation = DatabaseValidator.validate(parsed, AnimationRegistry.ids)
        check(validation.isValid) {
            "Bundled exercise database failed validation:\n" + validation.problems.joinToString("\n")
        }

        val storedVersion = dao.meta(META_SCHEMA_VERSION)?.toIntOrNull() ?: -1
        if (storedVersion != parsed.schemaVersion) {
            dao.clear()
            dao.clearRoutines()
            dao.insertAll(parsed.exercises.map {
                ExerciseEntity(id = it.id, category = it.category.name, json = json.encodeToString(Exercise.serializer(), it))
            })
            dao.insertRoutines(parsed.routines.map {
                RoutineEntity(id = it.id, category = it.category.name, json = json.encodeToString(NamedRoutine.serializer(), it))
            })
            dao.putMeta(DbMetaEntity(META_SCHEMA_VERSION, parsed.schemaVersion.toString()))
        }
        return parsed
    }

    companion object {
        const val ASSET_NAME = "exercise_db.json"
        private const val META_SCHEMA_VERSION = "schema_version"
    }
}
