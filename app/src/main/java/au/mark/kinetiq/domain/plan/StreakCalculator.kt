package au.mark.kinetiq.domain.plan

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Streak = consecutive calendar days ending today (or yesterday) where each day either
 * has a completed session or is one of the user's configured rest days.
 * Rest days never break a streak, but they only count inside a streak that contains
 * at least one real workout.
 */
object StreakCalculator {

    fun currentStreak(
        sessionStartTimesEpochMs: List<Long>,
        restDays: Set<DayOfWeek>,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        if (sessionStartTimesEpochMs.isEmpty()) return 0
        val workoutDays = sessionStartTimesEpochMs
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
            .toSet()

        // A streak may still be alive if today has no workout yet (today pending, not broken).
        var day = today
        if (day !in workoutDays && day.dayOfWeek !in restDays) {
            day = day.minusDays(1)
        }

        var streak = 0
        var sawWorkout = false
        while (true) {
            when {
                day in workoutDays -> { streak++; sawWorkout = true }
                // A rest day only counts when it bridges to an earlier workout — trailing rest
                // days before the streak's first workout don't inflate the count.
                day.dayOfWeek in restDays -> if (streakAliveBefore(day, workoutDays, restDays)) streak++ else break
                else -> break
            }
            day = day.minusDays(1)
        }
        return if (sawWorkout) streak else 0
    }

    private fun streakAliveBefore(day: LocalDate, workoutDays: Set<LocalDate>, restDays: Set<DayOfWeek>): Boolean {
        var d = day.minusDays(1)
        repeat(7) {
            if (d in workoutDays) return true
            if (d.dayOfWeek !in restDays) return false
            d = d.minusDays(1)
        }
        return false
    }
}
