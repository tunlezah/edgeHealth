package au.mark.kinetiq.domain.generator

import au.mark.kinetiq.data.model.BodyArea
import au.mark.kinetiq.data.model.Equipment
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.Exercise

/**
 * The single place that decides whether an exercise may be put into a plan.
 *
 * Every path that selects exercises — the generator's pools, warm-up/cool-down, the
 * muscle-retention block builder and the Builder's "swap exercise" button — goes through here.
 * The swap button used to carry its own copy of these rules; a copy is how an exercise that
 * needs hand weights would end up in a session built without them.
 */
object ExerciseEligibility {

    /** What the user has to hand for one generation. */
    data class Kit(
        val equipment: Set<Equipment> = emptySet(),
        /** Heaviest single hand weight owned, kg. Only consulted when HAND_WEIGHTS is in [equipment]. */
        val handWeightMaxKg: Float = 0f,
    )

    /** Owned kit from Settings plus the per-session hand-weights choice. */
    fun kitFor(owned: Set<Equipment>, handWeightMaxKg: Float, handWeightsThisSession: Boolean): Kit {
        val withoutWeights = owned - Equipment.HAND_WEIGHTS
        return Kit(
            equipment = if (handWeightsThisSession) withoutWeights + Equipment.HAND_WEIGHTS else withoutWeights,
            handWeightMaxKg = handWeightMaxKg,
        )
    }

    /**
     * Every listed item is available, and — for hand-weight exercises — the user's heaviest
     * weight reaches the exercise's useful minimum. Below that minimum the exercise would be
     * generated as a set that cannot get hard, which is worse than not offering it.
     */
    fun equipmentAvailable(ex: Exercise, kit: Kit): Boolean {
        if (!ex.equipment.all { it in kit.equipment }) return false
        if (Equipment.HAND_WEIGHTS in ex.equipment) {
            val needs = ex.resistance?.minUsefulKg ?: 0f
            if (needs > kit.handWeightMaxKg) return false
        }
        return true
    }

    /** Hard gates: body constraints and equipment. Never relaxed by any toggle. */
    fun allowed(ex: Exercise, constraints: Set<BodyArea>, kit: Kit): Boolean =
        ex.contraindications.none { it in constraints } && equipmentAvailable(ex, kit)

    /** Membership of the GENERAL selection pool (circuits and interval segments). */
    fun inGeneralPool(
        ex: Exercise,
        constraints: Set<BodyArea>,
        includeLowEvidence: Boolean,
        kit: Kit,
    ): Boolean =
        !ex.isWarmupCooldown &&
            (includeLowEvidence || ex.evidenceTier != EvidenceTier.LIMITED) &&
            allowed(ex, constraints, kit)

    /**
     * Membership of the muscle-retention pool. Graded on the exercise's *retention* tier, not its
     * general one. LIMITED entries are returned only when [includeLimited] is set; the block
     * builder still uses them solely to fill a slot nothing better can cover.
     */
    fun inResistancePool(
        ex: Exercise,
        constraints: Set<BodyArea>,
        includeLimited: Boolean,
        kit: Kit,
    ): Boolean {
        val profile = ex.resistance ?: return false
        return !ex.isWarmupCooldown &&
            (includeLimited || profile.tier != EvidenceTier.LIMITED) &&
            allowed(ex, constraints, kit)
    }
}
