package au.mark.kinetiq.anim

/** Prop drawn behind/with the figure. */
enum class Prop { NONE, MAT, WALL, REFORMER, REFORMER_LEG_STRAPS, REFORMER_ARM_STRAPS, SPIN_BIKE, ELLIPTICAL }

/** Primary working muscle group — used for the optional highlight tint. */
enum class MuscleGroup { LEGS, GLUTES, CORE, CHEST, BACK, SHOULDERS, ARMS, FULL_BODY }

/** Which joint traces the subtle motion-path arc. */
enum class PathJoint { NONE, WRIST, ANKLE, PELVIS, HEAD }

/**
 * Easing applied to the segment that STARTS at a keyframe.
 *
 * SMOOTH is a minimum-jerk quintic (zero velocity AND zero acceleration at both ends) — the
 * default for muscle-driven motion; the old cosine ease had maximum acceleration exactly at
 * the endpoints, which made every keyframe boundary pop. ACCEL/DECEL are the two halves of
 * the quintic for ballistic phases (a fall accelerates, a rise decelerates). HOLD freezes the
 * pose until the next keyframe (use for contact instants, not long pauses — for a visible
 * pause, duplicate the keyframe so breathing overlays still run).
 */
enum class Ease {
    SMOOTH, LINEAR, ACCEL, DECEL, HOLD;

    fun apply(t: Float): Float = when (this) {
        SMOOTH -> t * t * t * (t * (t * 6f - 15f) + 10f)
        LINEAR -> t
        // halves of the quintic, renormalized to 0..1
        ACCEL -> { val h = t / 2f; 2f * (h * h * h * (h * (h * 6f - 15f) + 10f)) }
        DECEL -> { val h = 0.5f + t / 2f; 2f * (h * h * h * (h * (h * 6f - 15f) + 10f)) - 1f }
        HOLD -> 0f
    }
}

/** One keyframe: a pose at a fraction [t] in 0..1 of the loop; [ease] shapes the segment to the next keyframe. */
data class Keyframe(val t: Float, val pose: Pose, val ease: Ease = Ease.SMOOTH)

sealed interface ExerciseAnim {
    val id: String
    val durationMs: Int
    val muscle: MuscleGroup
}

/**
 * Keyframed animation for FLOOR / REFORMER exercises.
 * Keyframes are interpolated cyclically (last wraps to first) with per-segment easing,
 * so every loop is seamless by construction.
 */
data class KeyframeAnim(
    override val id: String,
    override val durationMs: Int,
    val facing: Facing = Facing.SIDE,
    val prop: Prop = Prop.NONE,
    override val muscle: MuscleGroup = MuscleGroup.FULL_BODY,
    val pathJoint: PathJoint = PathJoint.NONE,
    val keyframes: List<Keyframe>,
) : ExerciseAnim {

    init {
        require(keyframes.size >= 2) { "$id needs >= 2 keyframes" }
        require(keyframes.zipWithNext().all { (a, b) -> a.t < b.t }) { "$id keyframes must be sorted" }
        require(keyframes.first().t >= 0f && keyframes.last().t <= 1f) { "$id keyframe times must be in 0..1" }
    }

    /** Cyclic interpolation with per-segment easing between neighbouring keyframes. */
    fun poseAt(phase: Float): Pose {
        val t = ((phase % 1f) + 1f) % 1f
        val idx = keyframes.indexOfLast { it.t <= t }
        val a: Keyframe
        val b: Keyframe
        var span: Float
        var local: Float
        if (idx == -1) {
            // before first keyframe: wrap from last
            a = keyframes.last(); b = keyframes.first()
            span = (1f - a.t) + b.t
            local = (t + 1f - a.t)
        } else if (idx == keyframes.lastIndex) {
            a = keyframes.last(); b = keyframes.first()
            span = (1f - a.t) + b.t
            local = t - a.t
        } else {
            a = keyframes[idx]; b = keyframes[idx + 1]
            span = b.t - a.t
            local = t - a.t
        }
        if (span <= 0f) span = 1e-4f
        val raw = (local / span).coerceIn(0f, 1f)
        return groundContact(Rig.lerp(a.pose, b.pose, a.ease.apply(raw)))
    }

    /**
     * Stance-foot constraint: keyframe poses are authored with support feet exactly on the
     * ground, but interpolating pelvis height and leg angles independently lets feet dip
     * below the floor mid-transition. Correct by lifting the pelvis just enough that the
     * lowest foot point lands on the ground line — never pushing down, so airborne phases
     * (jumps) are unaffected.
     */
    private fun groundContact(p: Pose): Pose {
        fun footLow(thigh: Float, knee: Float, foot: Float, hipY: Float): Float {
            val ankleY = hipY +
                Proportions.THIGH * cosd(thigh) +
                Proportions.SHANK * cosd(thigh - knee)
            val toeY = ankleY + Proportions.FOOT * cosd(thigh - knee + 90f + foot)
            return maxOf(ankleY, toeY)
        }
        val near = footLow(p.thighR, p.kneeR, p.footR, p.pelvisY)
        val far = footLow(p.thighL, p.kneeL, p.footL, p.pelvisY + Proportions.FAR_HIP_DY)
        val penetration = maxOf(near, far) - AnimationRegistry.GY
        return if (penetration > 0f) p.copy(pelvisY = p.pelvisY - penetration) else p
    }

    private fun cosd(deg: Float) = kotlin.math.cos(Math.toRadians(deg.toDouble())).toFloat()
}

/** Procedural spin-bike rider: legs solved by IK onto rotating pedals. */
data class SpinAnim(
    override val id: String,
    val cadenceRpm: Int,
    /** 0 = seated, 1 = standing */
    val standing: Float,
    /** forward lean beyond the base riding posture, degrees */
    val extraLean: Float = 0f,
    /** if > 0, the rider alternates seated/standing with this period (jumps) */
    val jumpPeriodMs: Int = 0,
    override val muscle: MuscleGroup = MuscleGroup.LEGS,
) : ExerciseAnim {
    override val durationMs: Int get() = (60_000f / cadenceRpm).toInt().coerceAtLeast(300)
}

/** Procedural elliptical rider: feet on elliptical stride path, hands on moving or static handles. */
data class EllipticalAnim(
    override val id: String,
    val strideRpm: Int,
    val reverse: Boolean = false,
    /** true = hands drive the moving handles; false = hands on the static rail */
    val armsDrive: Boolean = true,
    override val muscle: MuscleGroup = MuscleGroup.FULL_BODY,
) : ExerciseAnim {
    override val durationMs: Int get() = (60_000f / strideRpm).toInt().coerceAtLeast(300)
}
