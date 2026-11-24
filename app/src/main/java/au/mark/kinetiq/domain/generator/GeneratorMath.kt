package au.mark.kinetiq.domain.generator

import au.mark.kinetiq.data.model.Exercise
import kotlin.math.abs
import kotlin.math.roundToInt

/** True when moving between [a] and [b] requires re-setup (springs, machine presence, body position). */
internal fun requiresSetupChange(a: Exercise, b: Exercise): Boolean =
    a.category != b.category ||
        (a.machine == null) != (b.machine == null) ||
        a.machine?.reformer?.springs != b.machine?.reformer?.springs ||
        a.machine?.reformer?.bodyPosition != b.machine?.reformer?.bodyPosition

/**
 * Iterative proportional redistribution: clamp each element into its bounds, then spread the
 * residual against [targetSec] over the elements not pinned at a bound in the residual's
 * direction. Converges in <= 6 passes or returns the closest achievable split.
 *
 * Shared by the circuit generator and the muscle-retention block builder, so both hold the same
 * "on budget or explained" invariant.
 */
internal fun redistributeSeconds(
    initial: List<Int>,
    minBound: (Int) -> Int,
    maxBound: (Int) -> Int,
    targetSec: Int,
): List<Int> {
    if (initial.isEmpty()) return initial
    val out = initial.mapIndexed { i, v -> v.coerceIn(minBound(i), maxBound(i)) }.toMutableList()
    repeat(6) {
        val residual = targetSec - out.sum()
        if (abs(residual) <= 2) return@repeat
        val adjustable = out.indices.filter { i ->
            if (residual > 0) out[i] < maxBound(i) else out[i] > minBound(i)
        }
        if (adjustable.isEmpty()) return@repeat
        val share = residual.toDouble() / adjustable.size
        for (i in adjustable) {
            out[i] = (out[i] + share).roundToInt().coerceIn(minBound(i), maxBound(i))
        }
    }
    // Exact settling: hand out the last few seconds one at a time while any element has room.
    var residual = targetSec - out.sum()
    while (residual != 0) {
        val i = out.indices.firstOrNull { i ->
            if (residual > 0) out[i] < maxBound(i) else out[i] > minBound(i)
        } ?: break
        val step = if (residual > 0) 1 else -1
        out[i] += step
        residual -= step
    }
    return out
}
