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
 * Angle conventions (side view). Every segment's world direction is written as
 * (sin(a), cos(a)) — a = 0 points straight DOWN, positive a rotates toward +X:
 *  - torso: lower-torso (pelvis -> mid) tilt from vertical-up; 0 = upright, positive = leaning
 *    toward +X, ~90 = lying with the chest end toward +X
 *  - spine: bend of the upper torso (mid -> shoulder line) relative to the lower torso;
 *    0 = straight back, positive = flexing toward +X (same sense as torso)
 *  - neck/head: relative to the upper torso line, positive = tipping toward +X
 *  - upper arm: the arm's world angle is (torso + spine + uArm). With an upright torso,
 *    uArm 0 = hanging straight down, +90 = horizontal toward +X, 180 = overhead.
 *    NOTE: because the arm angle is additive about vertical-down (not mirrored along the
 *    torso line), a leaned torso does NOT carry the "hanging" direction with it — always
 *    compute the world angle you want, then subtract (torso + spine).
 *  - elbow: forearm relative to the upper arm, 0 = straight, positive = flexion toward +X
 *  - thigh: absolute from vertical-down, 0 = straight down, positive = toward +X (hip flexion
 *    for a figure facing +X)
 *  - knee: shank world angle = thigh - knee. Positive knee = heel folding toward -X, which is
 *    anatomical flexion for an upright figure facing +X; supine poses (thigh beyond ±90) need
 *    NEGATIVE knee values for an anatomical fold. The physical flexion magnitude is |knee|.
 *  - foot: relative to the shank, 0 = neutral 90° foot; toe direction = (thigh - knee) + 90 + foot
 */
data class Pose(
    val pelvisX: Float = 0f,
    val pelvisY: Float = 0f,
    val torso: Float = 0f,
    /** Upper-torso bend relative to the lower torso (spinal flexion/extension). */
    val spine: Float = 0f,
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
    const val TORSO = 0.29f         // pelvis -> shoulder line (LOWER + UPPER)
    const val LOWER_TORSO = 0.145f  // pelvis -> mid spine
    const val UPPER_TORSO = 0.145f  // mid spine -> shoulder line
    const val UPPER_ARM = 0.155f
    const val FOREARM = 0.145f      // includes hand
    const val THIGH = 0.235f
    const val SHANK = 0.225f
    const val FOOT = 0.075f
    const val SHOULDER_HALF = 0.095f // half shoulder width (front view)
    const val HIP_HALF = 0.055f      // half hip width (front view)

    // Depth cheat in side view: the far shoulder/hip sit slightly behind and below the near
    // ones so far limbs read as parallax silhouettes instead of being perfectly eclipsed.
    const val FAR_SHOULDER_DX = -0.022f
    const val FAR_SHOULDER_DY = 0.010f
    const val FAR_HIP_DX = -0.016f
    const val FAR_HIP_DY = 0.008f
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
    val midTorso: Joint,  // spine bend point
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
 * which is what front-view exercises (jumping jacks, arm circles, skaters) need; torso/spine
 * read as lateral lean, so a skater can tip side to side.
 */
object Rig {

    private fun rad(deg: Float) = Math.toRadians(deg.toDouble())

    fun solve(pose: Pose, facing: Facing = Facing.SIDE): Skeleton {
        return if (facing == Facing.SIDE) solveSide(pose) else solveFront(pose)
    }

    private fun solveSide(pose: Pose): Skeleton {
        val pelvis = Joint(pose.pelvisX, pose.pelvisY)

        val torsoA = rad(pose.torso)
        val midTorso = Joint(
            pelvis.x + (Proportions.LOWER_TORSO * sin(torsoA)).toFloat(),
            pelvis.y - (Proportions.LOWER_TORSO * cos(torsoA)).toFloat(),
        )
        val upperA = rad(pose.torso + pose.spine)
        val chest = Joint(
            midTorso.x + (Proportions.UPPER_TORSO * sin(upperA)).toFloat(),
            midTorso.y - (Proportions.UPPER_TORSO * cos(upperA)).toFloat(),
        )
        val neckA = rad(pose.torso + pose.spine + pose.neck)
        val neckTop = Joint(
            chest.x + (Proportions.NECK * sin(neckA)).toFloat(),
            chest.y - (Proportions.NECK * cos(neckA)).toFloat(),
        )
        val headCenter = Joint(
            neckTop.x + (Proportions.HEAD_R * sin(neckA)).toFloat(),
            neckTop.y - (Proportions.HEAD_R * cos(neckA)).toFloat(),
        )

        fun arm(uArm: Float, elbow: Float, shoulder: Joint): Pair<Joint, Joint> {
            // world angle of the upper arm = torso + spine + uArm (0 = straight down)
            val ua = rad(pose.torso + pose.spine + uArm)
            val e = Joint(
                shoulder.x + (Proportions.UPPER_ARM * sin(ua)).toFloat(),
                shoulder.y + (Proportions.UPPER_ARM * cos(ua)).toFloat(),
            )
            val fa = rad(pose.torso + pose.spine + uArm + elbow)
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

        val farShoulder = Joint(chest.x + Proportions.FAR_SHOULDER_DX, chest.y + Proportions.FAR_SHOULDER_DY)
        val farHip = Joint(pelvis.x + Proportions.FAR_HIP_DX, pelvis.y + Proportions.FAR_HIP_DY)

        val (elbowNear, wristNear) = arm(pose.uArmR, pose.elbowR, chest)
        val (elbowFar, wristFar) = arm(pose.uArmL, pose.elbowL, farShoulder)
        val (kneeNear, ankleNear, toeNear) = leg(pose.thighR, pose.kneeR, pose.footR, pelvis)
        val (kneeFar, ankleFar, toeFar) = leg(pose.thighL, pose.kneeL, pose.footL, farHip)

        return Skeleton(
            pelvis = pelvis,
            midTorso = midTorso,
            chest = chest,
            neckTop = neckTop,
            headCenter = headCenter,
            near = SkeletonSide(chest, elbowNear, wristNear, pelvis, kneeNear, ankleNear, toeNear),
            far = SkeletonSide(farShoulder, elbowFar, wristFar, farHip, kneeFar, ankleFar, toeFar),
        )
    }

    private fun solveFront(pose: Pose): Skeleton {
        val pelvis = Joint(pose.pelvisX, pose.pelvisY)
        // torso/spine act as lateral lean in front view
        val torsoA = rad(pose.torso)
        val midTorso = Joint(
            pelvis.x + (Proportions.LOWER_TORSO * sin(torsoA)).toFloat(),
            pelvis.y - (Proportions.LOWER_TORSO * cos(torsoA)).toFloat(),
        )
        val upperA = rad(pose.torso + pose.spine)
        val chest = Joint(
            midTorso.x + (Proportions.UPPER_TORSO * sin(upperA)).toFloat(),
            midTorso.y - (Proportions.UPPER_TORSO * cos(upperA)).toFloat(),
        )
        val neckA = rad(pose.torso + pose.spine + pose.neck)
        val neckTop = Joint(
            chest.x + (Proportions.NECK * sin(neckA)).toFloat(),
            chest.y - (Proportions.NECK * cos(neckA)).toFloat(),
        )
        val headCenter = Joint(
            neckTop.x + (Proportions.HEAD_R * sin(neckA)).toFloat(),
            neckTop.y - (Proportions.HEAD_R * cos(neckA)).toFloat(),
        )

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
            midTorso = midTorso,
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
        spine = lerpF(a.spine, b.spine, t),
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
