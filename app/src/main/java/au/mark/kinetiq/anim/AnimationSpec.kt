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
     * Stance-limb constraint: keyframe poses are authored with support feet and planted hands
     * exactly on the ground, but interpolating pelvis height and limb angles independently
     * lets extremities dip below the floor mid-transition.
     *
     * Correction order matters: first each offending LEG bends its own knee just enough to
     * clear the floor — that is what a real swinging leg does (a mountain-climber foot skims
     * the ground while the trunk and planted hands stay put). Knee corrections stay on the
     * shank's current side of vertical (flipping IK branches makes the knee visibly pop) and
     * fade out near that boundary; the pelvis-lift residual covers whatever the fade leaves.
     * Planted wrists get the analogous elbow correction. Nothing ever pushes the figure down,
     * so airborne phases (jumps) are unaffected.
     */
    private fun groundContact(p: Pose): Pose {
        val gy = AnimationRegistry.GY

        fun ankleY(thigh: Float, knee: Float, hipY: Float): Float =
            hipY + Proportions.THIGH * cosd(thigh) + Proportions.SHANK * cosd(thigh - knee)

        fun toeY(thigh: Float, knee: Float, foot: Float, aY: Float): Float =
            aY + Proportions.FOOT * cosd(thigh - knee + 90f + foot)

        /** Bend this leg's knee along its current fold branch so its foot clears the floor. */
        fun clearKnee(thigh: Float, knee: Float, foot: Float, hipY: Float): Float {
            val sa0 = thigh - knee
            val scale = (kotlin.math.abs(sa0) / 15f).coerceAtMost(1f)
            if (scale <= 0f) return knee
            var k = knee
            for (i in 0 until 2) {
                val aY = ankleY(thigh, k, hipY)
                val tY = toeY(thigh, k, foot, aY)
                if (maxOf(aY, tY) <= gy + 0.002f) break
                val toeDrop = maxOf(0f, tY - aY)
                val cosArg = (gy - toeDrop - hipY - Proportions.THIGH * cosd(thigh)) / Proportions.SHANK
                if (cosArg < -1f || cosArg > 1f) break  // knee alone can't clear it
                val sa = Math.toDegrees(kotlin.math.acos(cosArg.toDouble())).toFloat()
                val solved = if (sa0 >= 0f) thigh - sa else thigh + sa
                k = knee + (solved - knee) * scale
            }
            return k
        }

        /** Bend this arm's elbow along its current fold branch so its wrist clears the floor. */
        fun clearElbow(uArm: Float, elbow: Float, shoulderY: Float, base: Float): Float {
            val a1 = base + uArm
            val f0 = normDeg(a1 + elbow)
            val scale = (kotlin.math.abs(f0) / 15f).coerceAtMost(1f)
            if (scale <= 0f) return elbow
            val eY = shoulderY + Proportions.UPPER_ARM * cosd(a1)
            val wY = eY + Proportions.FOREARM * cosd(f0)
            if (wY <= gy + 0.012f) return elbow
            val cosArg = (gy + 0.010f - eY) / Proportions.FOREARM
            if (cosArg < -1f || cosArg > 1f) return elbow
            val fw = Math.toDegrees(kotlin.math.acos(cosArg.toDouble())).toFloat()
            val solvedWorld = if (f0 >= 0f) fw else -fw
            return elbow + normDeg(solvedWorld - f0) * scale
        }

        val kneeR = clearKnee(p.thighR, p.kneeR, p.footR, p.pelvisY)
        val kneeL = clearKnee(p.thighL, p.kneeL, p.footL, p.pelvisY + Proportions.FAR_HIP_DY)
        var out = if (kneeR != p.kneeR || kneeL != p.kneeL) p.copy(kneeR = kneeR, kneeL = kneeL) else p

        val nearA = ankleY(out.thighR, out.kneeR, out.pelvisY)
        val farA = ankleY(out.thighL, out.kneeL, out.pelvisY + Proportions.FAR_HIP_DY)
        val low = maxOf(nearA, toeY(out.thighR, out.kneeR, out.footR, nearA),
            farA, toeY(out.thighL, out.kneeL, out.footL, farA))
        val penetration = low - gy
        if (penetration > 0f) out = out.copy(pelvisY = out.pelvisY - penetration)

        if (facing == Facing.SIDE) {
            val chestY = out.pelvisY -
                Proportions.LOWER_TORSO * cosd(out.torso) -
                Proportions.UPPER_TORSO * cosd(out.torso + out.spine)
            val base = out.torso + out.spine
            val elbowR = clearElbow(out.uArmR, out.elbowR, chestY, base)
            val elbowL = clearElbow(out.uArmL, out.elbowL, chestY + Proportions.FAR_SHOULDER_DY, base)
            if (elbowR != out.elbowR || elbowL != out.elbowL) out = out.copy(elbowR = elbowR, elbowL = elbowL)
        }
        return out
    }

    private fun cosd(deg: Float) = kotlin.math.cos(Math.toRadians(deg.toDouble())).toFloat()

    /** Normalize an angle to (-180, 180]. */
    private fun normDeg(a: Float): Float {
        var x = a % 360f
        if (x > 180f) x -= 360f
        if (x <= -180f) x += 360f
        return x
    }
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
