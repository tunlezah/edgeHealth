package au.mark.kinetiq.domain.plan

import au.mark.kinetiq.data.repo.CompletedBlock
import au.mark.kinetiq.data.repo.HistoryEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

/**
 * Local rule-based weekly plan, grounded in the Phase 0 evidence (RESEARCH.md):
 *  - WHO 2020 (Bull et al. 2020): 150–300 min/week moderate aerobic (or 75–150 vigorous)
 *    plus muscle-strengthening on >= 2 days/week.
 *  - VAT dose findings (Chang 2021): >= 3 aerobic sessions/week, 30–60 min each; consistency
 *    beats raw weekly minutes, and HIIT is effective in < 30 min sessions.
 */
object WeeklyPlanEngine {

    data class WeeklyTargets(
        val moderateCardioMinutes: Int = 150,
        val cardioSessions: Int = 3,
        val strengthSessions: Int = 2,
    )

    data class WeeklyProgress(
        val targets: WeeklyTargets,
        val cardioMinutesDone: Int,
        val cardioSessionsDone: Int,
        val strengthSessionsDone: Int,
        val suggestion: String,
    )

    private val CARDIO_CATEGORIES = setOf("SPIN", "ELLIPTICAL")
    private val STRENGTH_CATEGORIES = setOf("FLOOR", "REFORMER", "BACK")

    fun progressForWeek(
        history: List<HistoryEntry>,
        visceralFatGoal: Boolean,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): WeeklyProgress {
        val targets = if (visceralFatGoal) WeeklyTargets(moderateCardioMinutes = 180, cardioSessions = 4, strengthSessions = 2)
        else WeeklyTargets()

        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisWeek = history.filter {
            val d = Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate()
            !d.isBefore(weekStart) && !d.isAfter(today)
        }

        var cardioMin = 0
        var cardioSessions = 0
        var strengthSessions = 0
        for (entry in thisWeek) {
            val cardioSec = entry.blocks.filter { it.category in CARDIO_CATEGORIES || it.isHiit }.sumOf(CompletedBlock::activeSec)
            val strengthSec = entry.blocks.filter { it.category in STRENGTH_CATEGORIES }.sumOf(CompletedBlock::activeSec)
            cardioMin += cardioSec / 60
            if (cardioSec >= 10 * 60) cardioSessions++
            if (strengthSec >= 10 * 60) strengthSessions++
        }

        val cardioLeft = (targets.cardioSessions - cardioSessions).coerceAtLeast(0)
        val strengthLeft = (targets.strengthSessions - strengthSessions).coerceAtLeast(0)
        val minutesLeft = (targets.moderateCardioMinutes - cardioMin).coerceAtLeast(0)

        val suggestion = when {
            cardioLeft == 0 && strengthLeft == 0 ->
                "Weekly targets met — nice work. Extra movement is a bonus, recovery matters too."
            cardioLeft > 0 && strengthLeft > 0 ->
                "Aim for $cardioLeft more cardio session${plural(cardioLeft)} (~$minutesLeft min) and $strengthLeft strength session${plural(strengthLeft)} this week."
            cardioLeft > 0 ->
                "Aim for $cardioLeft more moderate cardio session${plural(cardioLeft)} (~$minutesLeft min) this week — bike or elliptical both count."
            else ->
                "Cardio target met. Add $strengthLeft strength session${plural(strengthLeft)} (floor or reformer) this week."
        }

        return WeeklyProgress(targets, cardioMin, cardioSessions, strengthSessions, suggestion)
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
