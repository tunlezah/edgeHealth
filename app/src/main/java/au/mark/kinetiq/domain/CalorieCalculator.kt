package au.mark.kinetiq.domain

/**
 * MET-based energy estimate per the Compendium of Physical Activities convention:
 * kcal = MET × body mass (kg) × duration (hours).
 * See RESEARCH.md §6 for the MET values assigned per exercise.
 */
object CalorieCalculator {

    fun kcal(met: Float, weightKg: Double, seconds: Int): Double {
        require(met > 0f) { "MET must be positive" }
        require(weightKg > 0.0) { "Weight must be positive" }
        require(seconds >= 0) { "Duration cannot be negative" }
        return met * weightKg * (seconds / 3600.0)
    }

    /** Sums kcal across (met, seconds) pairs, e.g. all steps of a session. */
    fun totalKcal(steps: List<Pair<Float, Int>>, weightKg: Double): Double =
        steps.sumOf { (met, sec) -> kcal(met, weightKg, sec) }
}
