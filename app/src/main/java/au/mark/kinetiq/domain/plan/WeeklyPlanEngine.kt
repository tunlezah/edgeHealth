package au.mark.kinetiq.domain.plan

import au.mark.kinetiq.data.model.Muscle
import au.mark.kinetiq.data.model.displayName
import au.mark.kinetiq.data.repo.CompletedBlock
import au.mark.kinetiq.data.repo.HistoryEntry
import au.mark.kinetiq.domain.resistance.ResistanceTracking
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
        val resistance: ResistanceProgress = ResistanceProgress(),
    )

    /**
     * Muscle-retention dose for the week (RESEARCH.md §12).
     *
     * Targets are per muscle group: [SETS_TARGET] hard sets a week, never below [SETS_FLOOR].
     * The floor is Iversen 2021's "at least four weekly sets"; the target is a judgment call
     * between that and the ~10 sets where growth-oriented dose-response curves flatten — no
     * study has tested a minimum dose during an energy deficit, and the copy says so.
     */
    data class ResistanceProgress(
        /** Sessions this week with at least [SETS_PER_SESSION] hard sets. */
        val sessionsDone: Int = 0,
        val sessionsTarget: Int = SESSIONS_TARGET,
        /** Hard-set credit per muscle this week; every muscle present, 0.0 when untrained. */
        val setsByMuscle: Map<Muscle, Double> = Muscle.entries.associateWith { 0.0 },
        val suggestion: String = NO_SETS_YET,
    ) {
        val anySets: Boolean get() = setsByMuscle.values.any { it > 0.0 }
        val belowFloor: List<Muscle> get() = setsByMuscle.filterValues { it < SETS_FLOOR }.keys.toList()
    }

    const val SESSIONS_TARGET = 2
    const val SETS_TARGET = 6
    const val SETS_FLOOR = 4
    /** Fewer hard sets than this and a session is a top-up, not a resistance session. */
    const val SETS_PER_SESSION = 4

    internal const val NO_SETS_YET =
        "No resistance sets yet this week. Two or three short muscle-retention sessions are what help you hold " +
            "on to muscle while losing fat — cardio alone does not do that job."

    /** Hard-set credit per muscle for the current week, for the generator's "what is behind" ordering. */
    fun weeklyMuscleSets(
        history: List<HistoryEntry>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Map<Muscle, Double> = ResistanceTracking.totalByMuscle(
        thisWeek(history, today, zone).flatMap { e -> e.blocks.filter { it.isResistance }.map { it.muscleSets } },
    )

    private fun thisWeek(history: List<HistoryEntry>, today: LocalDate, zone: ZoneId): List<HistoryEntry> {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return history.filter {
            val d = Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate()
            !d.isBefore(weekStart) && !d.isAfter(today)
        }
    }

    internal fun resistanceProgress(week: List<HistoryEntry>): ResistanceProgress {
        val sessions = week.count { e -> e.blocks.filter { it.isResistance }.sumOf { it.hardSets } >= SETS_PER_SESSION }
        val totals = ResistanceTracking.totalByMuscle(week.flatMap { e -> e.blocks.filter { it.isResistance }.map { it.muscleSets } })
        val byMuscle = Muscle.entries.associateWith { totals[it] ?: 0.0 }
        val trained = byMuscle.values.any { it > 0.0 }
        // Trunk endurance work is not what this meter is for; do not nag about it.
        val tracked = byMuscle.filterKeys { it != Muscle.TRUNK }
        val below = tracked.filterValues { it < SETS_FLOOR }.keys
        val short = tracked.filterValues { it >= SETS_FLOOR && it < SETS_TARGET }.keys
        fun names(ms: Collection<Muscle>) = ms.joinToString(", ") { it.displayName().lowercase() }
        val suggestion = when {
            !trained -> NO_SETS_YET
            below.isNotEmpty() ->
                "Under the weekly minimum of $SETS_FLOOR hard sets: ${names(below)}. " +
                    "A muscle-retention session starts with whatever is furthest behind."
            short.isNotEmpty() ->
                "Every muscle group has its minimum. Still short of $SETS_TARGET sets: ${names(short)}."
            else -> "Every muscle group has had $SETS_TARGET or more hard sets this week. More adds little — recover."
        }
        return ResistanceProgress(sessionsDone = sessions, setsByMuscle = byMuscle, suggestion = suggestion)
    }

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

        val thisWeek = thisWeek(history, today, zone)

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

        return WeeklyProgress(
            targets, cardioMin, cardioSessions, strengthSessions, suggestion,
            resistance = resistanceProgress(thisWeek),
        )
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
