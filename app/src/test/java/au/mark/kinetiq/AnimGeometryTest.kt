package au.mark.kinetiq

import au.mark.kinetiq.anim.AnimationRegistry
import au.mark.kinetiq.anim.EllipticalAnim
import au.mark.kinetiq.anim.Facing
import au.mark.kinetiq.anim.Joint
import au.mark.kinetiq.anim.KeyframeAnim
import au.mark.kinetiq.anim.Pose
import au.mark.kinetiq.anim.Prop
import au.mark.kinetiq.anim.Rig
import au.mark.kinetiq.anim.Skeleton
import au.mark.kinetiq.anim.SpinAnim
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Geometric contracts for every keyframed animation in the registry, evaluated through the
 * same FK the renderer uses. These are the regressions the original hand-authored set shipped
 * with (feet floating half a metre above the floor, knees folded backwards, hands hanging in
 * mid-air) — encoded as tests so they can never come back.
 */
class AnimGeometryTest {

    private val keyframed = AnimationRegistry.all.filterIsInstance<KeyframeAnim>()

    private fun jointsOf(pose: Pose, anim: KeyframeAnim): List<Pair<String, Joint>> {
        val sk = Rig.solve(pose, anim.facing)
        return listOf(
            "pelvis" to sk.pelvis, "midTorso" to sk.midTorso, "chest" to sk.chest,
            "neckTop" to sk.neckTop, "head" to sk.headCenter,
            "near.elbow" to sk.near.elbow, "near.wrist" to sk.near.wrist,
            "near.knee" to sk.near.knee, "near.ankle" to sk.near.ankle, "near.toe" to sk.near.toe,
            "far.elbow" to sk.far.elbow, "far.wrist" to sk.far.wrist,
            "far.knee" to sk.far.knee, "far.ankle" to sk.far.ankle, "far.toe" to sk.far.toe,
        )
    }

    @Test
    fun `registry has no duplicate ids`() {
        val ids = AnimationRegistry.all.map { it.id }
        assertThat(ids).containsNoDuplicates()
        assertThat(AnimationRegistry.byId.size).isEqualTo(ids.size)
    }

    @Test
    fun `no joint ever penetrates the floor`() {
        val floor = AnimationRegistry.GY + 0.035f
        val problems = mutableListOf<String>()
        for (anim in keyframed) {
            for (i in 0 until 80) {
                val pose = anim.poseAt(i / 80f)
                for ((name, j) in jointsOf(pose, anim)) {
                    if (j.y > floor) {
                        problems.add("${anim.id} phase ${i / 80f}: $name at y=${"%.3f".format(j.y)}")
                        break
                    }
                }
            }
        }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `figures never float — something stays near a support surface`() {
        // Every non-airborne phase must have at least one joint within reach of the floor,
        // the reformer carriage plane, or the foot bar (catches whole-figure levitation).
        val airborne = mapOf("fl_burpee" to (0.50f..0.72f), "fl_squatjump" to (0.14f..0.42f))
        val problems = mutableListOf<String>()
        for (anim in keyframed) {
            val window = airborne[anim.id]
            for (i in 0 until 60) {
                val phase = i / 60f
                if (window != null && phase in window) continue
                val pose = anim.poseAt(phase)
                val lowest = jointsOf(pose, anim).maxOf { it.second.y }
                // a body on the long box is carried BOX_H above the carriage plane
                val support = if (anim.prop == Prop.REFORMER_BOX_ARM_STRAPS) 0.30f - AnimationRegistry.BOX_H else 0.30f
                if (lowest < support) {
                    problems.add("${anim.id} phase $phase: lowest joint at y=${"%.3f".format(lowest)}")
                    break
                }
            }
        }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `knee and elbow flexion stay anatomical`() {
        val problems = mutableListOf<String>()
        for (anim in keyframed) {
            for (kf in anim.keyframes) {
                val p = kf.pose
                for ((name, v) in listOf("kneeL" to p.kneeL, "kneeR" to p.kneeR)) {
                    if (abs(v) > 156f) problems.add("${anim.id} t=${kf.t}: $name=$v")
                }
                for ((name, v) in listOf("elbowL" to p.elbowL, "elbowR" to p.elbowR)) {
                    if (abs(v) > 160f) problems.add("${anim.id} t=${kf.t}: $name=$v")
                }
            }
        }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `loops are seamless — pose at phase 1 returns to phase 0`() {
        for (anim in keyframed) {
            val a = anim.poseAt(0f)
            val b = anim.poseAt(0.99999f)
            // with cyclic wrap, approaching 1.0 must land back on the phase-0 pose
            assertThat(abs(b.torso - a.torso)).isLessThan(1.5f)
            assertThat(abs(b.pelvisY - a.pelvisY)).isLessThan(0.02f)
            assertThat(abs(b.thighR - a.thighR)).isLessThan(1.5f)
            assertThat(abs(b.uArmR - a.uArmR)).isLessThan(1.5f)
        }
    }

    @Test
    fun `angular velocity stays bounded — no teleporting limbs`() {
        val problems = mutableListOf<String>()
        val samples = 120
        for (anim in keyframed) {
            var prev = anim.poseAt(0f)
            val dtMs = anim.durationMs.toFloat() / samples
            for (i in 1..samples) {
                val cur = anim.poseAt(i / samples.toFloat())
                val maxDelta = listOf(
                    cur.torso - prev.torso, cur.spine - prev.spine,
                    cur.uArmL - prev.uArmL, cur.uArmR - prev.uArmR,
                    cur.elbowL - prev.elbowL, cur.elbowR - prev.elbowR,
                    cur.thighL - prev.thighL, cur.thighR - prev.thighR,
                    cur.kneeL - prev.kneeL, cur.kneeR - prev.kneeR,
                ).maxOf { abs(it) }
                val degPerSec = maxDelta / dtMs * 1000f
                if (degPerSec > 2200f) {
                    problems.add("${anim.id} phase ${i / samples.toFloat()}: $degPerSec deg/s")
                    break
                }
                prev = cur
            }
        }
        assertThat(problems).isEmpty()
    }

    @Test
    fun `strength moves have asymmetric tempo with an end-range dwell`() {
        // The signature timing fix: these moves must not be 2-keyframe metronomes.
        val strengthIds = listOf(
            "fl_squat", "fl_pushup", "fl_bridge", "fl_crunch", "fl_side_plank",
            "bk_curlup", "bk_pressup", "bk_sidebridge", "rf_pelviccurl",
            "fl_split_squat", "fl_bridge_single", "fl_single_leg_rdl", "fl_calf_raise",
            "fl_calf_raise_single", "fl_pike_pushup", "fl_knee_pushup", "fl_slide_curl",
            "fl_prone_raise", "fl_prone_raise_w", "fl_lateral_raise", "fl_biceps_curl",
            "fl_triceps_overhead", "rf_footwork_single", "rf_biceps_curl", "rf_triceps_press",
            "fl_incline_pushup", "fl_decline_pushup", "fl_rfe_split_squat", "fl_chair_squat_single",
            "fl_hip_thrust_single", "fl_calf_raise_step", "fl_nordic", "fl_reverse_nordic",
            "rf_box_pull", "rf_row_single",
        )
        for (id in strengthIds) {
            val anim = AnimationRegistry.byId[id] as KeyframeAnim
            assertThat(anim.keyframes.size).isAtLeast(4)
        }
    }

    @Test
    fun `mountain climber keeps hands planted and trunk still`() {
        // Regression: the old ground constraint lifted the whole pelvis (hands included) when
        // the swinging leg would dip; the knee-clearance constraint must keep the trunk fixed.
        val anim = AnimationRegistry.byId["fl_mountain"] as KeyframeAnim
        val ref = Rig.solve(anim.poseAt(0f), anim.facing)
        for (i in 0 until 60) {
            val pose = anim.poseAt(i / 60f)
            val sk = Rig.solve(pose, anim.facing)
            assertThat(abs(sk.near.wrist.x - ref.near.wrist.x)).isLessThan(0.012f)
            assertThat(abs(sk.near.wrist.y - ref.near.wrist.y)).isLessThan(0.012f)
            assertThat(abs(pose.pelvisY - ref.pelvis.y)).isLessThan(0.01f)
        }
    }

    @Test
    fun `planted support toes never lift during plank-family loops`() {
        // The planted foot of a plank/climber must stay in touch with the floor at all phases.
        for (id in listOf("fl_mountain", "fl_plank", "fl_pushup", "fl_pike_pushup")) {
            val anim = AnimationRegistry.byId[id] as KeyframeAnim
            for (i in 0 until 60) {
                val sk = Rig.solve(anim.poseAt(i / 60f), anim.facing)
                val lowestToe = maxOf(sk.near.toe.y, sk.far.toe.y)
                assertThat(lowestToe).isGreaterThan(AnimationRegistry.GY - 0.035f)
            }
        }
    }

    @Test
    fun `reformer carriage channel stays in range`() {
        for (anim in keyframed) {
            for (kf in anim.keyframes) {
                assertThat(kf.pose.prop).isAtLeast(0f)
                assertThat(kf.pose.prop).isAtMost(1f)
            }
        }
    }

    @Test
    fun `machine cadences match coached ranges`() {
        val spin = AnimationRegistry.all.filterIsInstance<SpinAnim>().associateBy { it.id }
        assertThat(spin["sp_sprint"]!!.cadenceRpm).isAtLeast(110)
        assertThat(spin["sp_recovery"]!!.cadenceRpm).isLessThan(spin["sp_seated_flat"]!!.cadenceRpm)
        assertThat(spin["sp_fast_flat"]!!.cadenceRpm).isGreaterThan(spin["sp_seated_flat"]!!.cadenceRpm)
        assertThat(spin["sp_seated_climb_heavy"]!!.cadenceRpm).isLessThan(spin["sp_seated_climb"]!!.cadenceRpm)
        val el = AnimationRegistry.all.filterIsInstance<EllipticalAnim>().associateBy { it.id }
        assertThat(el["el_easy"]!!.strideRpm).isLessThan(el["el_forward"]!!.strideRpm)
        assertThat(el["el_forward_fast"]!!.strideRpm).isGreaterThan(el["el_forward"]!!.strideRpm)
    }

    @Test
    fun `footwork variants differ only in ankle articulation`() {
        val heels = AnimationRegistry.byId["rf_footwork_heels"] as KeyframeAnim
        val toes = AnimationRegistry.byId["rf_footwork_toes"] as KeyframeAnim
        val h = heels.keyframes.first().pose
        val t = toes.keyframes.first().pose
        assertThat(h.thighL).isEqualTo(t.thighL)
        assertThat(h.kneeL).isEqualTo(t.kneeL)
        assertThat(h.footL).isNotEqualTo(t.footL)
    }

    // ------------------------------------------------------------------ strength additions
    // Contact contracts for the muscle-retention set. Every check runs over the whole loop through
    // poseAt (ground constraint included), not just at the keyframes: a planted hand or foot that
    // is correct at both ends of a move can still skate in between.

    private val gy = AnimationRegistry.GY

    private fun anim(id: String) = AnimationRegistry.byId[id] as KeyframeAnim

    /** One loop of solved skeletons, through the same poseAt + FK the renderer uses. */
    private fun loopOf(anim: KeyframeAnim, samples: Int = 120): List<Skeleton> =
        (0 until samples).map { Rig.solve(anim.poseAt(it / samples.toFloat()), anim.facing) }

    /** World angle of the segment a -> b in the rig's convention: 0 = straight down, positive toward +X. */
    private fun fromDownDeg(a: Joint, b: Joint): Float =
        Math.toDegrees(atan2((b.x - a.x).toDouble(), (b.y - a.y).toDouble())).toFloat()

    /** Bend at b along a -> b -> c in degrees (0 = one straight line). */
    private fun bendDeg(a: Joint, b: Joint, c: Joint): Float {
        val d = fromDownDeg(b, c) - fromDownDeg(a, b)
        return abs(((d + 540f) % 360f) - 180f)
    }

    @Test
    fun `split squat keeps both feet planted while the hips drop`() {
        val loop = loopOf(anim("fl_split_squat"))
        val ref = loop.first()
        for (sk in loop) {
            // front foot flat: ankle and toe on the floor, never sliding
            assertThat(sk.near.ankle.y).isWithin(0.005f).of(gy)
            assertThat(sk.near.toe.y).isWithin(0.006f).of(gy)
            assertThat(sk.near.ankle.x).isWithin(0.005f).of(ref.near.ankle.x)
            // rear foot on its ball: toe on the floor, heel up
            assertThat(sk.far.toe.y).isWithin(0.005f).of(gy)
            assertThat(sk.far.toe.x).isWithin(0.005f).of(ref.far.toe.x)
            assertThat(sk.far.ankle.y).isLessThan(gy - 0.02f)
            // static stance: the pelvis only travels vertically
            assertThat(sk.pelvis.x).isWithin(0.005f).of(ref.pelvis.x)
        }
        assertThat(loop.maxOf { it.pelvis.y } - loop.minOf { it.pelvis.y }).isAtLeast(0.10f)
    }

    @Test
    fun `single-leg RDL hinges over a planted support foot`() {
        val a = anim("fl_single_leg_rdl")
        val loop = loopOf(a)
        val ref = loop.first()
        for (sk in loop) {
            assertThat(sk.near.ankle.y).isWithin(0.004f).of(gy)
            assertThat(sk.near.toe.y).isWithin(0.006f).of(gy)
            assertThat(sk.near.ankle.x).isWithin(0.004f).of(ref.near.ankle.x)
            // the trailing foot never bears weight
            assertThat(maxOf(sk.far.ankle.y, sk.far.toe.y)).isLessThan(gy)
        }
        val bottom = Rig.solve(a.poseAt(0.48f), a.facing)
        assertThat(a.poseAt(0.48f).torso).isAtLeast(65f)                          // near-horizontal trunk
        assertThat(bottom.far.ankle.y).isLessThan(gy - 0.15f)                     // rear leg lifted…
        assertThat(bottom.far.ankle.x).isLessThan(bottom.pelvis.x - 0.3f)         // …and long behind
        assertThat(abs(bottom.near.wrist.x - bottom.near.shoulder.x)).isLessThan(0.06f)  // arms hang plumb
    }

    @Test
    fun `single-leg bridge keeps shoulders on the mat and the planted foot down`() {
        val loop = loopOf(anim("fl_bridge_single"))
        val ref = loop.first()
        for (sk in loop) {
            assertThat(sk.chest.y).isWithin(0.006f).of(gy)                        // shoulders stay down…
            assertThat(sk.chest.x).isWithin(0.006f).of(ref.chest.x)               // …and do not slide
            assertThat(sk.near.ankle.y).isWithin(0.005f).of(gy)
            assertThat(sk.near.toe.y).isWithin(0.006f).of(gy)
            assertThat(sk.near.ankle.x).isWithin(0.006f).of(ref.near.ankle.x)
            assertThat(sk.far.ankle.y).isLessThan(gy - 0.04f)                     // free leg clear of the mat
        }
        assertThat(loop.maxOf { it.pelvis.y } - loop.minOf { it.pelvis.y }).isAtLeast(0.08f)
    }

    @Test
    fun `heel raises keep the support toe on the floor while the ankle rises`() {
        for (id in listOf("fl_calf_raise", "fl_calf_raise_single")) {
            val loop = loopOf(anim(id))
            val ref = loop.first()
            for (sk in loop) {
                // the lowest toe is on the floor, and the near toe is a fixed pivot
                assertThat(maxOf(sk.near.toe.y, sk.far.toe.y)).isWithin(0.003f).of(gy)
                assertThat(sk.near.toe.y).isWithin(0.002f).of(ref.near.toe.y)
                assertThat(sk.near.toe.x).isWithin(0.006f).of(ref.near.toe.x)
                assertThat(sk.near.toe.y).isWithin(0.012f).of(gy)
            }
            assertThat(loop.maxOf { it.near.ankle.y } - loop.minOf { it.near.ankle.y }).isAtLeast(0.04f)
        }
        // single-leg: the near toe is the only contact; the far foot stays clear all loop
        for (sk in loopOf(anim("fl_calf_raise_single"))) {
            assertThat(sk.near.toe.y).isWithin(0.003f).of(gy)
            assertThat(maxOf(sk.far.ankle.y, sk.far.toe.y)).isLessThan(gy - 0.04f)
        }
    }

    @Test
    fun `pike push-up keeps hands and toes planted while the head dips`() {
        val loop = loopOf(anim("fl_pike_pushup"))
        val ref = loop.first()
        for (sk in loop) {
            for ((s, r) in listOf(sk.near to ref.near, sk.far to ref.far)) {
                assertThat(s.wrist.y).isWithin(0.009f).of(gy)
                assertThat(s.wrist.x).isWithin(0.006f).of(r.wrist.x)
                assertThat(s.toe.y).isWithin(0.004f).of(gy)
                assertThat(s.toe.x).isWithin(0.004f).of(r.toe.x)
            }
            assertThat(sk.pelvis.y).isLessThan(sk.chest.y - 0.15f)   // hips stay the apex of the pike
            assertThat(sk.headCenter.y).isLessThan(gy - 0.05f)       // head reaches for the floor, never through it
        }
        assertThat(loop.maxOf { it.headCenter.y } - loop.minOf { it.headCenter.y }).isAtLeast(0.08f)
    }

    @Test
    fun `knee push-up pivots on planted knees with the hands down`() {
        val loop = loopOf(anim("fl_knee_pushup"))
        val ref = loop.first()
        for (sk in loop) {
            for ((s, r) in listOf(sk.near to ref.near, sk.far to ref.far)) {
                assertThat(s.knee.y).isWithin(0.004f).of(gy)
                assertThat(s.knee.x).isWithin(0.006f).of(r.knee.x)
                assertThat(s.wrist.y).isWithin(0.008f).of(gy)
                assertThat(s.wrist.x).isWithin(0.006f).of(r.wrist.x)
                assertThat(s.ankle.y).isLessThan(s.knee.y - 0.06f)   // shins angled up…
                assertThat(s.ankle.x).isLessThan(s.knee.x - 0.1f)    // …behind the knees
            }
            // knee, hip and shoulder stay one straight line, top to bottom
            assertThat(bendDeg(sk.near.knee, sk.pelvis, sk.chest)).isLessThan(1.5f)
        }
        assertThat(loop.maxOf { it.chest.y } - loop.minOf { it.chest.y }).isAtLeast(0.12f)
    }

    @Test
    fun `sliding leg curl keeps both heels on the floor and the shoulders pinned`() {
        val a = anim("fl_slide_curl")
        val loop = loopOf(a)
        val ref = loop.first()
        for ((i, sk) in loop.withIndex()) {
            assertThat(sk.near.ankle.y).isWithin(0.005f).of(gy)
            assertThat(sk.far.ankle.y).isWithin(0.005f).of(gy)
            assertThat(sk.near.toe.y).isLessThan(sk.near.ankle.y)        // toes up: weight on the heels
            assertThat(sk.chest.y).isWithin(0.006f).of(gy)
            assertThat(sk.chest.x).isWithin(0.006f).of(ref.chest.x)
            // hips stay off the mat even at the long end
            assertThat(a.poseAt(i / loop.size.toFloat()).pelvisY).isLessThan(AnimationRegistry.SUPINE_Y - 0.03f)
        }
        assertThat(loop.maxOf { it.near.ankle.x } - loop.minOf { it.near.ankle.x }).isAtLeast(0.2f)
        assertThat(a.keyframes.minOf { abs(it.pose.kneeR) }).isLessThan(25f)   // legs nearly straight at the long end
    }

    @Test
    fun `prone raise lifts arms and chest off the floor and sets them back down`() {
        val a = anim("fl_prone_raise")
        val rest = Rig.solve(a.poseAt(0f), a.facing)
        val hold = Rig.solve(a.poseAt(0.41f), a.facing)
        assertThat(rest.near.wrist.y).isWithin(0.004f).of(gy)
        assertThat(rest.far.wrist.y).isWithin(0.004f).of(gy)
        assertThat(hold.near.wrist.y).isLessThan(gy - 0.08f)
        assertThat(hold.chest.y).isLessThan(rest.chest.y - 0.02f)   // real extension: the chest rises
        assertThat(hold.headCenter.y).isLessThan(rest.headCenter.y)
        for (sk in loopOf(a)) {
            // hips and legs stay heavy on the mat; arms stay long overhead
            assertThat(sk.pelvis.y).isWithin(0.002f).of(AnimationRegistry.SUPINE_Y)
            assertThat(sk.near.ankle.y).isWithin(0.002f).of(rest.near.ankle.y)
            assertThat(sk.near.wrist.x).isLessThan(sk.chest.x - 0.25f)
        }
    }

    @Test
    fun `lateral raise lifts both arms to shoulder height in step`() {
        val a = anim("fl_lateral_raise")
        assertThat(a.facing).isEqualTo(Facing.FRONT)
        for (sk in loopOf(a)) {
            // mirror-symmetric about the trunk, hands never above the shoulder line
            assertThat(sk.near.wrist.x - sk.pelvis.x).isWithin(1e-3f).of(sk.pelvis.x - sk.far.wrist.x)
            assertThat(sk.near.wrist.y).isWithin(1e-3f).of(sk.far.wrist.y)
            assertThat(sk.near.wrist.y).isGreaterThan(sk.near.shoulder.y - 0.02f)
        }
        val rest = Rig.solve(a.poseAt(0f), a.facing)
        val top = Rig.solve(a.poseAt(0.40f), a.facing)
        assertThat(rest.near.wrist.y).isGreaterThan(rest.pelvis.y - 0.05f)       // hands by the hips
        assertThat(top.near.wrist.y).isWithin(0.05f).of(top.near.shoulder.y)     // about shoulder height
        assertThat(top.near.wrist.x - top.near.shoulder.x).isAtLeast(0.27f)      // arms out to the side
    }

    @Test
    fun `biceps curl keeps the upper arm plumb while the elbow does the work`() {
        val a = anim("fl_biceps_curl")
        for (sk in loopOf(a)) {
            for (s in listOf(sk.near, sk.far)) {
                assertThat(abs(fromDownDeg(s.shoulder, s.elbow))).isLessThan(5f)
            }
        }
        val flex = a.keyframes.map { it.pose.elbowR }
        assertThat(flex.min()).isLessThan(20f)
        assertThat(flex.max()).isAtLeast(130f)
    }

    @Test
    fun `overhead triceps extension lowers the hands behind the head`() {
        val a = anim("fl_triceps_overhead")
        for (sk in loopOf(a)) {
            // upper arms stay overhead, close to vertical
            assertThat(sk.near.elbow.y).isLessThan(sk.near.shoulder.y - 0.14f)
            assertThat(abs(sk.near.elbow.x - sk.near.shoulder.x)).isLessThan(0.04f)
        }
        val top = Rig.solve(a.poseAt(0f), a.facing)
        val low = Rig.solve(a.poseAt(0.49f), a.facing)
        assertThat(top.near.wrist.y).isLessThan(top.headCenter.y - 0.1f)         // locked out above the head
        assertThat(low.near.wrist.x).isLessThan(low.headCenter.x - 0.06f)        // behind a figure facing +X
        assertThat(low.near.wrist.y).isGreaterThan(top.near.wrist.y + 0.15f)
        assertThat(low.near.wrist.y).isLessThan(low.near.shoulder.y)             // still above the shoulders
    }

    @Test
    fun `hand weights are flagged on exactly the hand-weight animations`() {
        val weighted = keyframed.filter { it.handWeights }.map { it.id }
        assertThat(weighted).containsExactly(
            "fl_prone_raise_w", "fl_lateral_raise", "fl_biceps_curl", "fl_triceps_overhead",
        )
        // the weighted prone raise is the bodyweight one, frame for frame
        val plain = anim("fl_prone_raise")
        val loaded = anim("fl_prone_raise_w")
        assertThat(loaded.keyframes).isEqualTo(plain.keyframes)
        assertThat(loaded.durationMs).isEqualTo(plain.durationMs)
        assertThat(loaded.prop).isEqualTo(plain.prop)
    }

    @Test
    fun `single leg footwork moves one leg only and keeps its heel on the bar`() {
        val a = anim("rf_footwork_single")
        val first = a.keyframes.first().pose
        for (kf in a.keyframes) {
            assertThat(kf.pose.thighL).isEqualTo(first.thighL)
            assertThat(kf.pose.kneeL).isEqualTo(first.kneeL)
            assertThat(kf.pose.footL).isEqualTo(first.footL)
        }
        assertThat(a.keyframes.map { it.pose.thighR }.distinct().size).isGreaterThan(1)
        // the pressing leg starts where two-leg footwork starts
        val both = anim("rf_footwork_heels").keyframes.first().pose
        assertThat(first.thighR).isEqualTo(both.thighR)
        assertThat(first.kneeR).isEqualTo(both.kneeR)
        val ref = Rig.solve(a.poseAt(0f), a.facing)
        for (sk in loopOf(a)) {
            assertThat(sk.near.ankle.x).isWithin(0.008f).of(ref.near.ankle.x)
            assertThat(sk.near.ankle.y).isWithin(0.008f).of(ref.near.ankle.y)
            assertThat(sk.far.ankle.y).isLessThan(sk.pelvis.y - 0.15f)   // free leg held up in tabletop
        }
    }

    @Test
    fun `reformer strap arm work rides the carriage with a still trunk`() {
        // The renderer draws the carriage at -0.16 + 0.24 * prop, so a figure seated on it must
        // travel 0.24 per unit of prop or the seat slides out from under it.
        for (id in listOf("rf_biceps_curl", "rf_triceps_press", "rf_row_single")) {
            val a = anim(id)
            val p0 = a.poseAt(0f)
            var travel = 0f
            for (i in 0 until 60) {
                val p = a.poseAt(i / 60f)
                assertThat(p.pelvisX - p0.pelvisX).isWithin(0.002f).of(0.24f * (p.prop - p0.prop))
                assertThat(p.pelvisY).isWithin(1e-4f).of(p0.pelvisY)
                assertThat(p.torso).isWithin(0.5f).of(p0.torso)
                travel = maxOf(travel, p.prop - p0.prop)
            }
            assertThat(travel).isAtLeast(0.2f)   // the carriage really moves as the arms work
        }
        // curl: upper arms hold their line while the elbows fold, carriage furthest out at full flexion
        val curl = anim("rf_biceps_curl")
        val upper = loopOf(curl).map { fromDownDeg(it.near.shoulder, it.near.elbow) }
        assertThat(upper.max() - upper.min()).isLessThan(5f)
        val bent = curl.keyframes.maxByOrNull { it.pose.elbowR }!!.pose
        val long = curl.keyframes.minByOrNull { it.pose.elbowR }!!.pose
        assertThat(bent.elbowR - long.elbowR).isAtLeast(110f)
        assertThat(bent.prop).isGreaterThan(long.prop)
        // press: seated with the BACK to the risers (feet toward the foot bar, -X); the hand starts
        // beside the ear with the elbow folded and presses forward and up until the arm is straight
        val press = anim("rf_triceps_press")
        val start = Rig.solve(press.poseAt(0f), press.facing)
        val lock = Rig.solve(press.poseAt(0.46f), press.facing)
        assertThat(start.near.ankle.x).isLessThan(start.pelvis.x - 0.1f)
        assertThat(abs(press.poseAt(0f).elbowR)).isAtLeast(110f)
        assertThat(abs(press.poseAt(0.46f).elbowR)).isLessThan(12f)
        assertThat(hypot(start.near.wrist.x - start.headCenter.x, start.near.wrist.y - start.headCenter.y)).isLessThan(0.08f)
        assertThat(lock.near.wrist.x - lock.pelvis.x).isLessThan(start.near.wrist.x - start.pelvis.x - 0.15f)   // forward
        assertThat(lock.near.wrist.y).isLessThan(lock.near.shoulder.y - 0.12f)                                   // and up
        assertThat(press.poseAt(0.46f).prop).isGreaterThan(press.poseAt(0f).prop)   // the rope draws the carriage toward the risers
        for (sk in loopOf(press)) {
            // knees bent, soles on the carriage deck the whole time
            assertThat(sk.near.ankle.y).isWithin(0.004f).of(AnimationRegistry.CARRIAGE_LIE)
            assertThat(sk.far.ankle.y).isWithin(0.004f).of(AnimationRegistry.CARRIAGE_LIE)
        }
        // row: the near elbow drives back past the ribs and the hand finishes by the waist
        val row = anim("rf_row_single")
        val reach = Rig.solve(row.poseAt(0f), row.facing)
        val pulled = Rig.solve(row.poseAt(0.46f), row.facing)
        assertThat(reach.near.elbow.x).isGreaterThan(reach.near.shoulder.x + 0.08f)
        assertThat(pulled.near.elbow.x).isLessThan(pulled.near.shoulder.x - 0.04f)
        assertThat(pulled.near.wrist.y).isGreaterThan(pulled.near.shoulder.y + 0.12f)
        assertThat(row.poseAt(0.46f).prop).isGreaterThan(row.poseAt(0f).prop)
    }

    @Test
    fun `new strength moves lower slower than they lift`() {
        // Each tracker grows during the eccentric (lowering / lengthening) phase.
        val trackers: Map<String, (Skeleton) -> Float> = mapOf(
            "fl_split_squat" to { it.pelvis.y },
            "fl_bridge_single" to { it.pelvis.y },
            "fl_single_leg_rdl" to { it.headCenter.y },
            "fl_calf_raise" to { it.near.ankle.y },
            "fl_calf_raise_single" to { it.near.ankle.y },
            "fl_pike_pushup" to { it.headCenter.y },
            "fl_knee_pushup" to { it.chest.y },
            "fl_slide_curl" to { -it.near.ankle.x },   // heels sliding away from the hips
            "fl_prone_raise" to { it.near.wrist.y },
            "fl_prone_raise_w" to { it.near.wrist.y },
            "fl_lateral_raise" to { it.near.wrist.y },
            "fl_biceps_curl" to { it.near.wrist.y },
            "fl_triceps_overhead" to { it.near.wrist.y },
            "fl_incline_pushup" to { it.chest.y },
            "fl_decline_pushup" to { it.chest.y },
            "fl_rfe_split_squat" to { it.pelvis.y },
            "fl_chair_squat_single" to { it.pelvis.y },
            "fl_hip_thrust_single" to { it.pelvis.y },
            "fl_calf_raise_step" to { it.near.ankle.y },
            "fl_nordic" to { it.headCenter.y },
            "fl_reverse_nordic" to { it.headCenter.y },
        )
        for ((id, track) in trackers) {
            val s = loopOf(anim(id), samples = 400).map(track)
            var lowering = 0
            var lifting = 0
            var still = 0
            for (i in s.indices) {
                val d = s[(i + 1) % s.size] - s[i]
                when {
                    d > 1e-5f -> lowering++
                    d < -1e-5f -> lifting++
                    else -> still++
                }
            }
            assertWithMessage("$id eccentric vs concentric samples").that(lowering).isGreaterThan(lifting)
            assertWithMessage("$id end-range dwell samples").that(still).isAtLeast(20)
        }
    }

    // ------------------------------------------------------------------ bench, step, anchor, long box
    // The props are drawn from the public constants on AnimationRegistry, so these pin the poses
    // to those numbers: change a constant and the contact tests say which animation left its prop.

    private val benchY = AnimationRegistry.BENCH_Y

    /** Distance from p to the segment a-b. */
    private fun distToSegment(p: Joint, a: Joint, b: Joint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val u = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
        return hypot(p.x - (a.x + u * dx), p.y - (a.y + u * dy))
    }

    @Test
    fun `prop geometry is self-consistent and each animation names the prop it was solved on`() {
        assertThat(AnimationRegistry.BENCH_FRONT_X0).isGreaterThan(0f)                                // in front: +X
        assertThat(AnimationRegistry.BENCH_FRONT_X1).isGreaterThan(AnimationRegistry.BENCH_FRONT_X0)
        assertThat(AnimationRegistry.BENCH_BACK_X1).isLessThan(0f)                                    // behind: -X
        assertThat(AnimationRegistry.BENCH_BACK_X0).isLessThan(AnimationRegistry.BENCH_BACK_X1)
        assertThat(benchY).isLessThan(AnimationRegistry.STEP_Y)                                       // the bench is taller than the step
        assertThat(AnimationRegistry.STEP_Y).isLessThan(gy)
        assertThat(AnimationRegistry.STEP_X1).isGreaterThan(AnimationRegistry.STEP_X0)
        assertThat(AnimationRegistry.ANCHOR_Y).isLessThan(gy)
        assertThat(AnimationRegistry.BOX_H).isGreaterThan(0.1f)
        val props = mapOf(
            "fl_incline_pushup" to Prop.BENCH_FRONT, "fl_hip_thrust_single" to Prop.BENCH_FRONT,
            "fl_decline_pushup" to Prop.BENCH_BACK, "fl_rfe_split_squat" to Prop.BENCH_BACK,
            "fl_chair_squat_single" to Prop.BENCH_BACK, "fl_calf_raise_step" to Prop.STEP,
            "fl_nordic" to Prop.ANCHOR, "fl_reverse_nordic" to Prop.MAT,
            "rf_box_pull" to Prop.REFORMER_BOX_ARM_STRAPS, "rf_row_single" to Prop.REFORMER_ARM_STRAPS,
            "rf_biceps_curl" to Prop.REFORMER_ARM_STRAPS, "rf_triceps_press" to Prop.REFORMER_ARM_STRAPS,
        )
        for ((id, prop) in props) assertWithMessage(id).that(anim(id).prop).isEqualTo(prop)
    }

    @Test
    fun `incline push-up keeps the hands on the bench and the toes on the floor`() {
        val loop = loopOf(anim("fl_incline_pushup"))
        val ref = loop.first()
        for (sk in loop) {
            for ((s, r) in listOf(sk.near to ref.near, sk.far to ref.far)) {
                assertThat(s.wrist.y).isWithin(0.008f).of(benchY)
                assertThat(s.wrist.x).isWithin(0.005f).of(r.wrist.x)
                assertThat(s.wrist.x).isGreaterThan(AnimationRegistry.BENCH_FRONT_X0)   // hands on the seat, not in front of it
                assertThat(s.wrist.x).isLessThan(AnimationRegistry.BENCH_FRONT_X1)
                assertThat(s.toe.y).isWithin(0.004f).of(gy)
                assertThat(s.toe.x).isWithin(0.004f).of(r.toe.x)
            }
            // ankle, knee, hip and shoulder stay one straight line
            assertThat(bendDeg(sk.near.ankle, sk.near.knee, sk.pelvis)).isLessThan(1.5f)
            assertThat(bendDeg(sk.near.knee, sk.pelvis, sk.chest)).isLessThan(1.5f)
            assertThat(sk.chest.y).isLessThan(benchY - 0.08f)                           // chest lowers toward the edge, never onto it
        }
        assertThat(loop.maxOf { it.chest.y } - loop.minOf { it.chest.y }).isAtLeast(0.12f)
    }

    @Test
    fun `decline push-up keeps the toes on the bench and the hands on the floor`() {
        val a = anim("fl_decline_pushup")
        val loop = loopOf(a)
        val ref = loop.first()
        for (sk in loop) {
            for ((s, r) in listOf(sk.near to ref.near, sk.far to ref.far)) {
                assertThat(s.toe.y).isWithin(0.004f).of(benchY)
                assertThat(s.toe.x).isWithin(0.004f).of(r.toe.x)
                assertThat(s.toe.x).isGreaterThan(AnimationRegistry.BENCH_BACK_X0)
                assertThat(s.toe.x).isLessThan(AnimationRegistry.BENCH_BACK_X1)
                assertThat(s.wrist.y).isWithin(0.008f).of(gy)
                assertThat(s.wrist.x).isWithin(0.005f).of(r.wrist.x)
            }
            assertThat(bendDeg(sk.near.ankle, sk.near.knee, sk.pelvis)).isLessThan(1.5f)
            assertThat(bendDeg(sk.near.knee, sk.pelvis, sk.chest)).isLessThan(1.5f)
        }
        val bottom = Rig.solve(a.poseAt(0.47f), a.facing)
        assertThat(bottom.chest.y).isGreaterThan(bottom.pelvis.y + 0.03f)               // head-down at the bottom
        assertThat(loop.maxOf { it.chest.y } - loop.minOf { it.chest.y }).isAtLeast(0.12f)
    }

    @Test
    fun `rear-foot-elevated split squat keeps the rear foot on the bench and the front foot down`() {
        val a = anim("fl_rfe_split_squat")
        val loop = loopOf(a)
        val ref = loop.first()
        for ((i, sk) in loop.withIndex()) {
            // rear (far) foot: instep on the seat edge, toes along the seat
            assertThat(sk.far.ankle.y).isWithin(0.004f).of(benchY)
            assertThat(sk.far.toe.y).isWithin(0.004f).of(benchY)
            assertThat(sk.far.ankle.x).isWithin(0.005f).of(AnimationRegistry.BENCH_BACK_X1)
            assertThat(sk.far.toe.x).isGreaterThan(AnimationRegistry.BENCH_BACK_X0)
            // front (near) foot flat and still
            assertThat(sk.near.ankle.y).isWithin(0.004f).of(gy)
            assertThat(sk.near.toe.y).isWithin(0.005f).of(gy)
            assertThat(sk.near.ankle.x).isWithin(0.005f).of(ref.near.ankle.x)
            assertThat(sk.far.knee.y).isLessThan(gy - 0.03f)                            // rear knee never rests on the floor
            assertThat(a.poseAt(i / loop.size.toFloat()).torso).isLessThan(15f)          // trunk fairly upright
        }
        assertThat(loop.maxOf { it.pelvis.y } - loop.minOf { it.pelvis.y }).isAtLeast(0.18f)
        val bottom = Rig.solve(a.poseAt(0.50f), a.facing)
        assertThat(bottom.near.knee.y).isWithin(0.02f).of(bottom.pelvis.y)              // front thigh about parallel
    }

    @Test
    fun `single-leg chair squat reaches the seat over a planted support foot`() {
        val a = anim("fl_chair_squat_single")
        val loop = loopOf(a)
        val ref = loop.first()
        for (sk in loop) {
            assertThat(sk.near.ankle.y).isWithin(0.004f).of(gy)
            assertThat(sk.near.toe.y).isWithin(0.005f).of(gy)
            assertThat(sk.near.ankle.x).isWithin(0.006f).of(ref.near.ankle.x)
            assertThat(sk.pelvis.y).isLessThan(benchY + 0.002f)                         // touches the seat, never sinks into it
            assertThat(maxOf(sk.far.ankle.y, sk.far.toe.y)).isLessThan(gy - 0.015f)     // held leg stays off the floor
            assertThat(sk.far.ankle.x).isGreaterThan(sk.pelvis.x)                       // …and in front
        }
        val seat = Rig.solve(a.poseAt(0.50f), a.facing)
        assertThat(seat.pelvis.y).isWithin(0.004f).of(benchY)                           // hips reach the seat…
        assertThat(seat.pelvis.x).isLessThan(AnimationRegistry.BENCH_BACK_X1)           // …on the bench, not in front of it
        assertThat(seat.pelvis.x).isGreaterThan(AnimationRegistry.BENCH_BACK_X0)
        assertThat(ref.pelvis.y).isLessThan(0.02f)                                      // and the figure stands tall again
        assertThat(ref.near.ankle.x).isGreaterThan(AnimationRegistry.BENCH_BACK_X1 + 0.05f)   // standing in front of the bench
    }

    @Test
    fun `single-leg hip thrust keeps the shoulders on the bench and the planted foot down`() {
        val a = anim("fl_hip_thrust_single")
        val loop = loopOf(a)
        val ref = loop.first()
        for (sk in loop) {
            assertThat(sk.chest.y).isWithin(0.005f).of(benchY)
            assertThat(sk.chest.x).isWithin(0.005f).of(ref.chest.x)
            assertThat(sk.chest.x).isGreaterThan(AnimationRegistry.BENCH_FRONT_X0)
            assertThat(sk.near.ankle.y).isWithin(0.004f).of(gy)
            assertThat(sk.near.toe.y).isWithin(0.005f).of(gy)
            assertThat(sk.near.ankle.x).isWithin(0.005f).of(ref.near.ankle.x)
            assertThat(sk.far.ankle.y).isLessThan(gy - 0.15f)                           // free leg lifted
            assertThat(sk.pelvis.y).isLessThan(gy - 0.04f)                              // hips never rest on the floor
        }
        val top = Rig.solve(a.poseAt(0.44f), a.facing)
        assertThat(top.pelvis.y).isWithin(0.01f).of(top.chest.y)                        // trunk level…
        assertThat(top.near.knee.y).isWithin(0.03f).of(top.pelvis.y)                    // …and so is the working thigh
        assertThat(ref.pelvis.y).isGreaterThan(benchY + 0.12f)                          // from well below the bench
    }

    @Test
    fun `step calf raise keeps the forefoot on the step while the heel drops below it and rises above`() {
        val a = anim("fl_calf_raise_step")
        val stepY = AnimationRegistry.STEP_Y
        val loop = loopOf(a)
        val ref = loop.first()
        for (sk in loop) {
            assertThat(sk.near.toe.y).isWithin(0.004f).of(stepY)
            assertThat(sk.near.toe.x).isWithin(0.004f).of(ref.near.toe.x)
            assertThat(sk.near.toe.x).isGreaterThan(AnimationRegistry.STEP_X0)          // forefoot on the step…
            assertThat(sk.near.toe.x).isLessThan(AnimationRegistry.STEP_X1)
            assertThat(sk.near.ankle.x).isLessThan(AnimationRegistry.STEP_X0 - 0.026f)  // …heel hanging clear of its edge
            assertThat(sk.far.toe.x).isLessThan(AnimationRegistry.STEP_X0)              // other foot hooked behind, off everything
            assertThat(maxOf(sk.far.ankle.y, sk.far.toe.y)).isLessThan(gy - 0.05f)
        }
        val top = Rig.solve(a.poseAt(0f), a.facing)
        val stretch = Rig.solve(a.poseAt(0.53f), a.facing)
        assertThat(top.near.ankle.y).isLessThan(stepY - 0.035f)        // heel well above the step top
        assertThat(stretch.near.ankle.y).isGreaterThan(stepY + 0.02f)  // heel below it in the stretch
        assertThat(stretch.near.ankle.y).isLessThan(gy - 0.03f)        // …without reaching the floor
    }

    @Test
    fun `Nordics pivot on planted knees and ankles with a straight knee-to-head line`() {
        for (id in listOf("fl_nordic", "fl_reverse_nordic")) {
            val loop = loopOf(anim(id))
            val ref = loop.first()
            for (sk in loop) {
                assertThat(sk.near.knee.y).isWithin(0.004f).of(gy)
                assertThat(sk.far.knee.y).isWithin(0.012f).of(gy)       // the far knee rides the rig's depth offset
                assertThat(sk.near.ankle.y).isWithin(0.004f).of(gy)
                assertThat(sk.far.ankle.y).isWithin(0.004f).of(gy)
                for ((s, r) in listOf(sk.near to ref.near, sk.far to ref.far)) {
                    assertThat(s.knee.x).isWithin(0.004f).of(r.knee.x)
                    assertThat(s.ankle.x).isWithin(0.004f).of(r.ankle.x)
                }
                // hips never hinge and the head stays on the line
                assertThat(bendDeg(sk.near.knee, sk.pelvis, sk.chest)).isLessThan(1f)
                assertThat(bendDeg(sk.pelvis, sk.chest, sk.headCenter)).isLessThan(1f)
            }
        }
        // Nordic: ankles under the anchor bar; a long slow forward lean; hands only meet the floor to catch
        val nordic = anim("fl_nordic")
        for (i in 0 until 120) {
            val phase = i / 120f
            val sk = Rig.solve(nordic.poseAt(phase), nordic.facing)
            assertThat(sk.near.ankle.x).isWithin(0.004f).of(AnimationRegistry.ANCHOR_X)
            assertThat(sk.near.ankle.y).isGreaterThan(AnimationRegistry.ANCHOR_Y)       // the bar sits on top of the ankle
            if (phase < 0.44f) assertThat(sk.near.wrist.y).isLessThan(gy - 0.08f)        // hands-free through 50 degrees of lean
            if (phase in 0.52f..0.70f) {
                assertThat(sk.near.wrist.y).isWithin(0.006f).of(gy)                      // caught on the floor
                assertThat(sk.far.wrist.y).isWithin(0.006f).of(gy)
            }
        }
        assertThat(nordic.poseAt(0.435f).torso).isAtLeast(45f)                           // still hands-free at 45 degrees
        assertThat(nordic.keyframes.maxOf { it.pose.torso }).isAtMost(70f)
        // reverse Nordic leans back over the shins
        val reverse = anim("fl_reverse_nordic")
        val back = Rig.solve(reverse.poseAt(0.50f), reverse.facing)
        assertThat(reverse.poseAt(0.50f).torso).isAtMost(-30f)
        assertThat(back.headCenter.x).isLessThan(back.near.knee.x - 0.3f)
        assertThat(back.pelvis.x).isLessThan(back.near.knee.x - 0.1f)
    }

    @Test
    fun `long-box pull keeps the trunk on the box while the near arm sweeps to the hip`() {
        val a = anim("rf_box_pull")
        val lie = AnimationRegistry.CARRIAGE_LIE - AnimationRegistry.BOX_H
        val p0 = a.poseAt(0f)
        for (i in 0 until 120) {
            val p = a.poseAt(i / 120f)
            val sk = Rig.solve(p, a.facing)
            assertThat(sk.pelvis.y).isWithin(0.002f).of(lie)                            // hips and waist stay on the box…
            assertThat(sk.midTorso.y).isWithin(0.002f).of(lie)
            assertThat(p.pelvisX - p0.pelvisX).isWithin(0.002f).of(0.24f * (p.prop - p0.prop))   // …which rides the carriage
            val boxFront = -0.16f + 0.24f * p.prop + AnimationRegistry.BOX_HALF_LENGTH
            assertThat(sk.chest.x).isGreaterThan(boxFront)                              // shoulders past the front edge, arms free
            assertThat(sk.pelvis.x).isLessThan(boxFront)
            assertThat(sk.near.ankle.y).isWithin(0.02f).of(lie)                         // legs long at box height
            assertThat(sk.chest.y).isGreaterThan(lie - 0.05f)                           // the chest lifts only slightly
            // far arm rests along the side
            assertThat(sk.far.wrist.x - sk.pelvis.x).isWithin(0.05f).of(0f)
            assertThat(sk.far.wrist.y).isWithin(0.04f).of(lie)
        }
        val long = Rig.solve(a.poseAt(0f), a.facing)
        val hip = Rig.solve(a.poseAt(0.46f), a.facing)
        assertThat(long.near.wrist.x).isGreaterThan(long.near.shoulder.x + 0.15f)       // long and forward…
        assertThat(long.near.wrist.y).isGreaterThan(long.near.shoulder.y + 0.1f)        // …and down toward the pulley
        assertThat(hip.near.wrist.x - hip.pelvis.x).isWithin(0.06f).of(0f)              // finishes at the hip
        assertThat(hip.near.wrist.y).isWithin(0.04f).of(lie)
        assertThat(hip.chest.y).isLessThan(long.chest.y - 0.01f)
        assertThat(a.poseAt(0.46f).prop).isGreaterThan(p0.prop + 0.2f)                  // the carriage travels with the pull
    }

    @Test
    fun `one-arm strap work leaves the far hand resting on the thigh while the near hand travels`() {
        for (id in listOf("rf_row_single", "rf_biceps_curl", "rf_triceps_press")) {
            val loop = loopOf(anim(id))
            val ref = loop.first()
            var travel = 0f
            for (sk in loop) {
                // far wrist stays put on the body…
                assertThat(sk.far.wrist.x - sk.pelvis.x).isWithin(0.004f).of(ref.far.wrist.x - ref.pelvis.x)
                assertThat(sk.far.wrist.y - sk.pelvis.y).isWithin(0.004f).of(ref.far.wrist.y - ref.pelvis.y)
                // …resting on the far thigh
                assertWithMessage(id).that(distToSegment(sk.far.wrist, sk.far.hip, sk.far.knee)).isLessThan(0.04f)
                travel = maxOf(
                    travel,
                    hypot(
                        (sk.near.wrist.x - sk.pelvis.x) - (ref.near.wrist.x - ref.pelvis.x),
                        (sk.near.wrist.y - sk.pelvis.y) - (ref.near.wrist.y - ref.pelvis.y),
                    ),
                )
            }
            assertWithMessage(id).that(travel).isAtLeast(0.15f)
        }
    }

    @Test
    fun `one-arm strap work draws a single strap and nothing else does`() {
        val keyframed = AnimationRegistry.all.filterIsInstance<KeyframeAnim>()
        val single = keyframed.filter { it.singleStrap }
        assertThat(single.map { it.id }).containsExactly("rf_biceps_curl", "rf_row_single", "rf_triceps_press", "rf_box_pull")
        // The flag only means something on a prop that draws arm straps.
        for (anim in single) {
            assertWithMessage(anim.id).that(anim.prop)
                .isAnyOf(au.mark.kinetiq.anim.Prop.REFORMER_ARM_STRAPS, au.mark.kinetiq.anim.Prop.REFORMER_BOX_ARM_STRAPS)
        }
    }
}
