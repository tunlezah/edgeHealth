package au.mark.kinetiq.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import au.mark.kinetiq.data.repo.CompletedBlock
import au.mark.kinetiq.data.repo.MeasurementRepository
import au.mark.kinetiq.data.repo.Metric
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton

/** What a body-metrics refresh actually did, so the UI can explain the outcome precisely. */
data class RefreshSummary(
    val imported: Int,
    val readPermissionGranted: Boolean,
    val historyPermissionGranted: Boolean,
    val failures: List<String> = emptyList(),
)

/**
 * Optional Health Connect integration (client 1.1.0).
 *
 * Reads Weight / Body Fat % / Height (BMI is computed in-app — Health Connect has neither a
 * BMI nor a visceral-fat data type). Writes an ExerciseSessionRecord per completed block plus a
 * TotalCaloriesBurnedRecord from the MET estimate. Every feature degrades gracefully when
 * Health Connect is unavailable or permissions are revoked.
 */
@Singleton
class HealthConnectManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val measurements: MeasurementRepository,
) {

    val readPermissions = setOf(
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(BodyFatRecord::class),
        HealthPermission.getReadPermission(HeightRecord::class),
    )
    val writePermissions = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
    )

    /**
     * Health Connect only serves the 30 days before permission was first granted unless this is held.
     * Body metrics come from other apps (scale/Fit) and are almost always older, so without it the
     * reads error out and nothing is ever imported. Requested alongside the per-type read permissions;
     * kept out of [readPermissions] because it is not itself a per-type read grant.
     */
    val historyPermission: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    val allPermissions = readPermissions + writePermissions + historyPermission

    fun availability(): Int = HealthConnectClient.getSdkStatus(context)
    fun isAvailable(): Boolean = availability() == HealthConnectClient.SDK_AVAILABLE

    private fun client(): HealthConnectClient? =
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null

    suspend fun grantedPermissions(): Set<String> =
        client()?.permissionController?.getGrantedPermissions() ?: emptySet()

    suspend fun hasAnyReadPermission(): Boolean = grantedPermissions().any { it in readPermissions }
    suspend fun hasWritePermissions(): Boolean = grantedPermissions().containsAll(writePermissions)

    /**
     * Pulls the freshest Weight/BodyFat/Height records into the local cache with source app +
     * timestamp. Each metric is read independently so a missing type or a per-type failure can't
     * abort the others, and the read window is capped to the last 30 days unless the history
     * permission is granted — Health Connect errors on any read that reaches past 30 days without it.
     */
    suspend fun refreshBodyMetrics(): Result<RefreshSummary> = runCatching {
        val client = client() ?: error("Health Connect is not available on this device")
        val granted = client.permissionController.getGrantedPermissions()
        val historyGranted = historyPermission in granted
        val timeFilter =
            if (historyGranted) TimeRangeFilter.before(Instant.now())
            else TimeRangeFilter.after(Instant.now().minus(Duration.ofDays(HISTORY_WINDOW_DAYS)))

        suspend fun <T : androidx.health.connect.client.records.Record> latest(type: kotlin.reflect.KClass<T>): T? =
            client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = timeFilter,
                    ascendingOrder = false,
                    pageSize = 1,
                )
            ).records.firstOrNull()

        var imported = 0
        val failures = mutableListOf<String>()
        // Read one metric in isolation: skip when its permission is missing, count a cached value,
        // and record (never rethrow) a per-type failure so the remaining metrics still run.
        suspend fun pull(label: String, permission: String, read: suspend () -> Boolean) {
            if (permission !in granted) return
            runCatching { read() }
                .onSuccess { if (it) imported++ }
                .onFailure { failures += "$label: ${it.message ?: it.javaClass.simpleName}" }
        }

        pull("Weight", HealthPermission.getReadPermission(WeightRecord::class)) {
            latest(WeightRecord::class)?.also {
                measurements.cacheHealthConnectValue(
                    Metric.WEIGHT_KG, it.weight.inKilograms, it.time.toEpochMilli(),
                    it.metadata.dataOrigin.packageName,
                )
            } != null
        }
        pull("Body fat", HealthPermission.getReadPermission(BodyFatRecord::class)) {
            latest(BodyFatRecord::class)?.also {
                measurements.cacheHealthConnectValue(
                    Metric.BODY_FAT_PCT, it.percentage.value, it.time.toEpochMilli(),
                    it.metadata.dataOrigin.packageName,
                )
            } != null
        }
        pull("Height", HealthPermission.getReadPermission(HeightRecord::class)) {
            latest(HeightRecord::class)?.also {
                measurements.cacheHealthConnectValue(
                    Metric.HEIGHT_CM, it.height.inMeters * 100.0, it.time.toEpochMilli(),
                    it.metadata.dataOrigin.packageName,
                )
            } != null
        }

        RefreshSummary(
            imported = imported,
            readPermissionGranted = granted.any { it in readPermissions },
            historyPermissionGranted = historyGranted,
            failures = failures,
        )
    }


    /**
     * Writes one ExerciseSessionRecord per completed block (correct type per category, HIIT when
     * the block was HIIT-structured) plus a single TotalCaloriesBurnedRecord for the session.
     * Records are app-recorded (not manual entry) and carry stable clientRecordIds so a retry
     * after a partial failure upserts instead of duplicating.
     */
    suspend fun writeSession(
        sessionName: String,
        blocks: List<CompletedBlock>,
        totalCalories: Double,
        startEpochMs: Long,
        endEpochMs: Long,
    ): Result<Unit> = runCatching {
        val client = client() ?: error("Health Connect is not available on this device")
        require(blocks.isNotEmpty()) { "No completed blocks to write" }
        if (!hasWritePermissions()) {
            error("Health Connect write permission not granted — grant it from the Health screen")
        }

        val device = Device(type = Device.TYPE_PHONE)
        val records = buildList {
            blocks.filter { it.endedAtEpochMs > it.startedAtEpochMs }.forEachIndexed { i, block ->
                add(
                    ExerciseSessionRecord(
                        startTime = Instant.ofEpochMilli(block.startedAtEpochMs),
                        startZoneOffset = offsetAt(block.startedAtEpochMs),
                        endTime = Instant.ofEpochMilli(block.endedAtEpochMs),
                        endZoneOffset = offsetAt(block.endedAtEpochMs),
                        metadata = Metadata.autoRecorded(
                            device = device,
                            clientRecordId = clientRecordIdFor(startEpochMs, "block", i),
                        ),
                        exerciseType = exerciseTypeFor(block),
                        title = "$sessionName — ${block.category.lowercase().replaceFirstChar { it.uppercase() }}",
                    )
                )
            }
            if (totalCalories > 0 && endEpochMs > startEpochMs) {
                add(
                    TotalCaloriesBurnedRecord(
                        startTime = Instant.ofEpochMilli(startEpochMs),
                        startZoneOffset = offsetAt(startEpochMs),
                        endTime = Instant.ofEpochMilli(endEpochMs),
                        endZoneOffset = offsetAt(endEpochMs),
                        energy = Energy.kilocalories(totalCalories),
                        metadata = Metadata.autoRecorded(
                            device = device,
                            clientRecordId = clientRecordIdFor(startEpochMs, "kcal"),
                        ),
                    )
                )
            }
        }
        client.insertRecords(records)
    }

    private fun exerciseTypeFor(block: CompletedBlock): Int = when {
        block.isHiit -> ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        block.category == "SPIN" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
        block.category == "ELLIPTICAL" -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        block.category == "REFORMER" -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
        else -> ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS
    }

    companion object {
        /** Health Connect's default read window (days) when the history permission is not granted. */
        private const val HISTORY_WINDOW_DAYS = 30L

        /** Deterministic per session+record so Health Connect retries upsert, never duplicate. */
        internal fun clientRecordIdFor(startEpochMs: Long, kind: String, index: Int = -1): String =
            if (index >= 0) "kinetiq-$startEpochMs-$kind-$index" else "kinetiq-$startEpochMs-$kind"

        /** Offset at a specific instant — correct across a DST transition mid-session. */
        internal fun offsetAt(epochMs: Long): ZoneOffset =
            ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(epochMs))
    }
}
