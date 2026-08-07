package au.mark.kinetiq.domain

import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.repo.MachineSettings
import au.mark.kinetiq.data.repo.SpringNotation
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders machine cues as spoken text, scaled to the user's configured machines:
 *  - Spin: Horizon GR7-style numbered magnetic levels (default max 11).
 *  - Elliptical: Infiniti VG50BS-style console levels (default max 16).
 *  - Reformer: generic spring words or count-based notation per settings.
 */
object MachineCueRenderer {

    fun renderCue(exercise: Exercise, machines: MachineSettings): String? {
        val m = exercise.machine ?: return null
        m.spin?.let { cue ->
            val lo = levelOf(cue.resistanceLow, machines.spinMaxLevel)
            val hi = levelOf(cue.resistanceHigh, machines.spinMaxLevel)
            val res = if (lo == hi) "resistance $lo" else "resistance $lo to $hi"
            val cadence = if (cue.cadenceRpmLow == cue.cadenceRpmHigh) "around ${cue.cadenceRpmLow} rpm"
            else "${cue.cadenceRpmLow} to ${cue.cadenceRpmHigh} rpm"
            return "${cue.position.replaceFirstChar { it.uppercase() }} — $res, $cadence."
        }
        m.elliptical?.let { cue ->
            val lo = levelOf(cue.resistanceLow, machines.ellipticalMaxLevel)
            val hi = levelOf(cue.resistanceHigh, machines.ellipticalMaxLevel)
            val res = if (lo == hi) "Level $lo" else "Level $lo to $hi"
            val direction = if (cue.direction == "REVERSE") "reverse stride" else "forward stride"
            return "$res, $direction, ${cue.arms}."
        }
        m.reformer?.let { cue ->
            return "${renderSprings(cue.springs, machines.springNotation)}, ${cue.bodyPosition}."
        }
        return null
    }

    /** Maps a 0..1 resistance fraction onto the machine's numbered levels (min level 1). */
    fun levelOf(fraction: Float, maxLevel: Int): Int =
        max(1, (fraction * maxLevel).roundToInt()).coerceAtMost(maxLevel)

    /**
     * Springs are stored generically as LIGHT|MEDIUM|HEAVY with an optional count suffix,
     * e.g. MEDIUM_2 = "two medium springs" (GENERIC) or "2 springs" (COUNT).
     */
    fun renderSprings(springs: String, notation: SpringNotation): String {
        val parts = springs.split('_')
        val word = parts[0].lowercase()
        val count = parts.getOrNull(1)?.toIntOrNull() ?: 1
        return when (notation) {
            SpringNotation.GENERIC -> {
                val countWord = when (count) {
                    1 -> "one"; 2 -> "two"; 3 -> "three"; else -> "$count"
                }
                if (count == 1) "one $word spring" else "$countWord $word springs"
            }
            SpringNotation.COUNT -> if (count == 1) "1 spring, $word tension" else "$count springs, $word tension"
        }
    }
}
