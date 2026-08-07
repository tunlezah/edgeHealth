package au.mark.kinetiq.data.repo

import au.mark.kinetiq.data.db.CachedHealthMetricEntity
import au.mark.kinetiq.data.db.ManualMeasurementEntity
import au.mark.kinetiq.data.db.MeasurementDao
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

object Metric {
    const val WEIGHT_KG = "WEIGHT_KG"
    const val HEIGHT_CM = "HEIGHT_CM"
    const val BODY_FAT_PCT = "BODY_FAT_PCT"
    const val WAIST_CM = "WAIST_CM"
    const val VISCERAL_RATING = "VISCERAL_RATING"
}

/** A resolved body metric: freshest of Health Connect cache vs manual entry. */
data class ResolvedMetric(val value: Double, val recordedAtEpochMs: Long, val source: String)

/**
 * Body metrics snapshot used by the generator heuristics.
 * BMI is computed in-app (Health Connect has no BMI type).
 */
data class BodyMetrics(
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val bodyFatPct: Double? = null,
    val waistCm: Double? = null,
    val visceralRating: Double? = null,
) {
    val bmi: Double?
        get() {
            val w = weightKg ?: return null
            val h = (heightCm ?: return null) / 100.0
            if (h <= 0) return null
            return w / (h * h)
        }
}

@Singleton
class MeasurementRepository @Inject constructor(private val dao: MeasurementDao) {

    suspend fun addManual(metric: String, value: Double) {
        dao.add(ManualMeasurementEntity(metric = metric, value = value, recordedAtEpochMs = System.currentTimeMillis()))
    }

    fun manualAll(): Flow<List<ManualMeasurementEntity>> = dao.all()
    fun cachedAll(): Flow<List<CachedHealthMetricEntity>> = dao.cachedAll()

    suspend fun cacheHealthConnectValue(metric: String, value: Double, recordedAtEpochMs: Long, sourceApp: String) {
        dao.cache(CachedHealthMetricEntity(metric, value, recordedAtEpochMs, sourceApp))
    }

    /** Freshest value wins per metric, regardless of source. */
    suspend fun resolved(metric: String): ResolvedMetric? {
        val manual = dao.latest(metric)
        val cached = dao.cached(metric)
        return when {
            manual == null && cached == null -> null
            manual == null -> ResolvedMetric(cached!!.value, cached.recordedAtEpochMs, cached.sourceApp)
            cached == null -> ResolvedMetric(manual.value, manual.recordedAtEpochMs, "Manual entry")
            manual.recordedAtEpochMs >= cached.recordedAtEpochMs ->
                ResolvedMetric(manual.value, manual.recordedAtEpochMs, "Manual entry")
            else -> ResolvedMetric(cached.value, cached.recordedAtEpochMs, cached.sourceApp)
        }
    }

    suspend fun bodyMetrics(): BodyMetrics = BodyMetrics(
        weightKg = resolved(Metric.WEIGHT_KG)?.value,
        heightCm = resolved(Metric.HEIGHT_CM)?.value,
        bodyFatPct = resolved(Metric.BODY_FAT_PCT)?.value,
        waistCm = resolved(Metric.WAIST_CM)?.value,
        visceralRating = resolved(Metric.VISCERAL_RATING)?.value,
    )
}
