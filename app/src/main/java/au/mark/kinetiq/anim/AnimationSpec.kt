package au.mark.kinetiq.anim

/** Prop drawn behind/with the figure. */
enum class Prop { NONE, MAT, WALL, REFORMER, SPIN_BIKE, ELLIPTICAL }

/** Primary working muscle group — used for the optional highlight tint. */
enum class MuscleGroup { LEGS, GLUTES, CORE, CHEST, BACK, SHOULDERS, ARMS, FULL_BODY }

/** Which joint traces the subtle motion-path arc. */
enum class PathJoint { NONE, WRIST, ANKLE, PELVIS, HEAD }

/** One keyframe: a pose at a fraction [t] in 0..1 of the loop. */
data class Keyframe(val t: Float, val pose: Pose)

sealed interface ExerciseAnim {
    val id: String
    val durationMs: Int
    val muscle: MuscleGroup
}

/**
 * Keyframed animation for FLOOR / REFORMER exercises.
 * Keyframes are interpolated cyclically (last wraps to first) with ease-in/out,
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

    /** Cyclic interpolation with cosine easing between neighbouring keyframes. */
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
        val eased = (1f - kotlin.math.cos(raw * Math.PI.toFloat())) / 2f
        return Rig.lerp(a.pose, b.pose, eased)
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
