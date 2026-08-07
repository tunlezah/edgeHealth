package au.mark.kinetiq.anim

import kotlin.math.cos
import kotlin.math.sin

/**
 * 2D procedural skeletal rig.
 *
 * The humanoid is authored in a normalized space where the standing figure is ~1.0 unit tall
 * (7.5 head-heights, per classic figure proportion), pelvis at the origin-ish. X grows to the
 * viewer's right, Y grows DOWN (screen space). All angles are degrees.
 *
 * Angle conventions (side view):
 *  - torso: 0 = upright, positive = leaning forward (to +X)
 *  - upper arm: relative to the torso line, 0 = hanging straight down along the torso
 *  - elbow: bend of the forearm relative to the upper arm, 0 = straight, positive = flexion (forward)
 *  - thigh: absolute from vertical, 0 = straight down, positive = forward (hip flexion)
 *  - knee: bend relative to the thigh, 0 = straight, positive = flexion (heel toward glutes)
 *  - foot: relative to the shank, 0 = neutral 90° foot
 *  - neck/head: relative to the torso, positive = looking down/forward-flexed
 */
data class Pose(
    val pelvisX: Float = 0f,
    val pelvisY: Float = 0f,
    val torso: Float = 0f,
    val neck: Float = 0f,
    val uArmL: Float = 0f,
    val elbowL: Float = 0f,
    val uArmR: Float = 0f,
    val elbowR: Float = 0f,
    val thighL: Float = 0f,
    val kneeL: Float = 0f,
    val thighR: Float = 0f,
    val kneeR: Float = 0f,
    val footL: Float = 0f,
    val footR: Float = 0f,
    /** Free channel for prop animation (reformer carriage offset, wheel phase, …), 0..1. */
    val prop: Float = 0f,
)

/** Segment lengths for a ~7.5-head-heights figure normalized to total height 1.0. */
object Proportions {
    const val HEAD_R = 0.062f       // head circle radius
    const val NECK = 0.045f
    const val TORSO = 0.29f         // pelvis -> shoulder line
    const val UPPER_ARM = 0.155f
    const val FOREARM = 0.145f      // includes hand
    const val THIGH = 0.235f
    const val SHANK = 0.225f
    const val FOOT = 0.075f
    const val SHOULDER_HALF = 0.095f // half shoulder width (front view)
    const val HIP_HALF = 0.055f      // half hip width (front view)
}

data class Joint(val x: Float, val y: Float)

/** Computed world-space joints for one drawn "layer" (near or far side of the body). */
data class SkeletonSide(
    val shoulder: Joint,
    val elbow: Joint,
    val wrist: Joint,
    val hip: Joint,
    val knee: Joint,
    val ankle: Joint,
    val toe: Joint,
)

data class Skeleton(
    val pelvis: Joint,
    val chest: Joint,     // shoulder line center
    val neckTop: Joint,
    val headCenter: Joint,
    val near: SkeletonSide,
    val far: SkeletonSide,
)

enum class Facing { SIDE, FRONT }

/**
 * Forward kinematics: converts a [Pose] into world-space joints.
 * In FRONT facing, "forward" angles are drawn as abduction (limbs swing out to the sides),
 * which is what front-view exercises (jumping jacks, arm circles, skaters) need.
 */
object Rig {

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble())

    fun solve(pose: Pose, facing: Facing = Facing.SIDE): Skeleton {
        return if (facing == Facing.SIDE) solveSide(pose) else solveFront(pose)
    }

    private fun solveSide(pose: Pose): Skeleton {
        val pelvis = Joint(pose.pelvisX, pose.pelvisY)

        val torsoA = rad(pose.torso)
        val chest = Joint(
            pelvis.x + (Proportions.TORSO * sin(torsoA)).toFloat(),
            pelvis.y - (Proportions.TORSO * cos(torsoA)).toFloat(),
        )
        val neckA = rad(pose.torso + pose.neck)
        val neckTop = Joint(
            chest.x + (Proportions.NECK * sin(neckA)).toFloat(),
            chest.y - (Proportions.NECK * cos(neckA)).toFloat(),
        )
        val headCenter = Joint(
            neckTop.x + (Proportions.HEAD_R * sin(neckA)).toFloat(),
            neckTop.y - (Proportions.HEAD_R * cos(neckA)).toFloat(),
        )

        fun arm(uArm: Float, elbow: Float, shoulder: Joint): Pair<Joint, Joint> {
            // upper arm angle is relative to torso direction (down along torso = 0)
            val ua = rad(pose.torso + uArm)
            val e = Joint(
                shoulder.x + (Proportions.UPPER_ARM * sin(ua)).toFloat(),
                shoulder.y + (Proportions.UPPER_ARM * cos(ua)).toFloat(),
            )
            val fa = rad(pose.torso + uArm + elbow)
            val w = Joint(
                e.x + (Proportions.FOREARM * sin(fa)).toFloat(),
                e.y + (Proportions.FOREARM * cos(fa)).toFloat(),
            )
            return e to w
        }

        fun leg(thigh: Float, knee: Float, foot: Float, hip: Joint): Triple<Joint, Joint, Joint> {
            val ta = rad(thigh)
            val k = Joint(
                hip.x + (Proportions.THIGH * sin(ta)).toFloat(),
                hip.y + (Proportions.THIGH * cos(ta)).toFloat(),
            )
            val sa = rad(thigh - knee) // knee flexion folds the shank backwards
            val a = Joint(
                k.x + (Proportions.SHANK * sin(sa)).toFloat(),
                k.y + (Proportions.SHANK * cos(sa)).toFloat(),
            )
            val fa2 = rad(thigh - knee + 90f + foot)
            val t = Joint(
                a.x + (Proportions.FOOT * sin(fa2)).toFloat(),
                a.y + (Proportions.FOOT * cos(fa2)).toFloat(),
            )
            return Triple(k, a, t)
        }

        val (elbowNear, wristNear) = arm(pose.uArmR, pose.elbowR, chest)
        val (elbowFar, wristFar) = arm(pose.uArmL, pose.elbowL, chest)
        val (kneeNear, ankleNear, toeNear) = leg(pose.thighR, pose.kneeR, pose.footR, pelvis)
        val (kneeFar, ankleFar, toeFar) = leg(pose.thighL, pose.kneeL, pose.footL, pelvis)

        return Skeleton(
            pelvis = pelvis,
            chest = chest,
            neckTop = neckTop,
            headCenter = headCenter,
            near = SkeletonSide(chest, elbowNear, wristNear, pelvis, kneeNear, ankleNear, toeNear),
            far = SkeletonSide(chest, elbowFar, wristFar, pelvis, kneeFar, ankleFar, toeFar),
        )
    }

    private fun solveFront(pose: Pose): Skeleton {
        val pelvis = Joint(pose.pelvisX, pose.pelvisY)
        val chest = Joint(pelvis.x, pelvis.y - Proportions.TORSO)
        val neckTop = Joint(chest.x, chest.y - Proportions.NECK)
        val headCenter = Joint(neckTop.x, neckTop.y - Proportions.HEAD_R)

        fun arm(uArm: Float, elbow: Float, dir: Int): Triple<Joint, Joint, Joint> {
            val shoulder = Joint(chest.x + dir * Proportions.SHOULDER_HALF, chest.y)
            // abduction: 0 = hanging down, 180 = straight overhead
            val ua = rad(uArm)
            val e = Joint(
                shoulder.x + dir * (Proportions.UPPER_ARM * sin(ua)).toFloat(),
                shoulder.y + (Proportions.UPPER_ARM * cos(ua)).toFloat(),
            )
            val fa = rad(uArm + elbow)
            val w = Joint(
                e.x + dir * (Proportions.FOREARM * sin(fa)).toFloat(),
                e.y + (Proportions.FOREARM * cos(fa)).toFloat(),
            )
            return Triple(shoulder, e, w)
        }

        fun leg(thigh: Float, knee: Float, dir: Int): Triple<Joint, Joint, Joint> {
            val hip = Joint(pelvis.x + dir * Proportions.HIP_HALF, pelvis.y)
            val ta = rad(thigh)
            val k = Joint(
                hip.x + dir * (Proportions.THIGH * sin(ta)).toFloat(),
                hip.y + (Proportions.THIGH * cos(ta)).toFloat(),
            )
            val sa = rad(thigh - knee)
            val a = Joint(
                k.x + dir * (Proportions.SHANK * sin(sa)).toFloat(),
                k.y + (Proportions.SHANK * cos(sa)).toFloat(),
            )
            return Triple(hip, k, a)
        }

        val (shoulderR, elbowR2, wristR2) = arm(pose.uArmR, pose.elbowR, +1)
        val (shoulderL, elbowL2, wristL2) = arm(pose.uArmL, pose.elbowL, -1)
        val (hipR, kneeR2, ankleR2) = leg(pose.thighR, pose.kneeR, +1)
        val (hipL, kneeL2, ankleL2) = leg(pose.thighL, pose.kneeL, -1)

        return Skeleton(
            pelvis = pelvis,
            chest = chest,
            neckTop = neckTop,
            headCenter = headCenter,
            near = SkeletonSide(shoulderR, elbowR2, wristR2, hipR, kneeR2, ankleR2, Joint(ankleR2.x + 0.03f, ankleR2.y)),
            far = SkeletonSide(shoulderL, elbowL2, wristL2, hipL, kneeL2, ankleL2, Joint(ankleL2.x - 0.03f, ankleL2.y)),
        )
    }

    /** Interpolates between two poses. */
    fun lerp(a: Pose, b: Pose, t: Float): Pose = Pose(
        pelvisX = lerpF(a.pelvisX, b.pelvisX, t),
        pelvisY = lerpF(a.pelvisY, b.pelvisY, t),
        torso = lerpF(a.torso, b.torso, t),
        neck = lerpF(a.neck, b.neck, t),
        uArmL = lerpF(a.uArmL, b.uArmL, t),
        elbowL = lerpF(a.elbowL, b.elbowL, t),
        uArmR = lerpF(a.uArmR, b.uArmR, t),
        elbowR = lerpF(a.elbowR, b.elbowR, t),
        thighL = lerpF(a.thighL, b.thighL, t),
        kneeL = lerpF(a.kneeL, b.kneeL, t),
        thighR = lerpF(a.thighR, b.thighR, t),
        kneeR = lerpF(a.kneeR, b.kneeR, t),
        footL = lerpF(a.footL, b.footL, t),
        footR = lerpF(a.footR, b.footR, t),
        prop = lerpF(a.prop, b.prop, t),
    )

    private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Two-bone IK: returns the bend joint for a limb from [root] reaching toward [target]. */
    fun ik(root: Joint, target: Joint, l1: Float, l2: Float, bendSign: Int): Joint {
        val dx = target.x - root.x
        val dy = target.y - root.y
        var d = kotlin.math.sqrt(dx * dx + dy * dy)
        d = d.coerceIn(0.0001f, l1 + l2 - 0.0001f)
        // law of cosines for the root angle
        val a = ((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d)).coerceIn(-1f, 1f)
        val rootAngle = kotlin.math.acos(a)
        val base = kotlin.math.atan2(dy, dx)
        val angle = base + bendSign * rootAngle
        return Joint(
            root.x + l1 * cos(angle),
            root.y + l1 * sin(angle),
        )
    }
}
