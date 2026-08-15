package au.mark.kinetiq

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import java.io.File

/**
 * Guard rail for the Room schema baseline. Migration tests proper need a real SQLite and the
 * schemas in androidTest assets, so they are instrumented; this reads the sources the same way
 * [ExportImportAndMiscTest] reads the manifest, and fails the build if anyone bumps the database
 * version without checking in the schema that a Migration would have to be diffed against.
 */
class RoomSchemaBaselineTest {

    private val databaseSource = File("src/main/java/au/mark/kinetiq/data/db/KinetiqDatabase.kt").readText()
    private val schemaDir = File("schemas/au.mark.kinetiq.data.db.KinetiqDatabase")

    private val declaredVersion: Int =
        Regex("""version\s*=\s*(\d+)""").find(databaseSource)!!.groupValues[1].toInt()

    @Test
    fun `schema export is enabled`() {
        assertThat(databaseSource).contains("exportSchema = true")
    }

    @Test
    fun `every database version from 1 has a checked-in schema json`() {
        for (version in 1..declaredVersion) {
            val file = File(schemaDir, "$version.json")
            assertThat(file.exists()).isTrue()
            val database = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
            assertThat(database["version"]!!.jsonPrimitive.int).isEqualTo(version)
            assertThat(database["identityHash"]!!.jsonPrimitive.content).isNotEmpty()
        }
    }

    @Test
    fun `the baseline schema covers every entity the database declares`() {
        val baseline = Json.parseToJsonElement(File(schemaDir, "1.json").readText())
            .jsonObject["database"]!!.jsonObject
        val tables = baseline["entities"]!!.jsonArray.map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
        assertThat(tables).containsExactly(
            "exercises", "routines", "db_meta", "saved_workouts",
            "session_history", "manual_measurements", "cached_health_metrics",
        )
    }

    @Test
    fun `the database builder has no destructive fallback`() {
        // Destructive migration silently drops every table on a version bump. The app is
        // offline-only, so there is no server copy to restore the user's history from.
        val appModule = File("src/main/java/au/mark/kinetiq/di/AppModule.kt").readText()
        assertThat(appModule).doesNotContain("fallbackToDestructiveMigration")
        assertThat(appModule).doesNotContain("fallbackToDestructiveMigrationOnDowngrade")
    }
}
