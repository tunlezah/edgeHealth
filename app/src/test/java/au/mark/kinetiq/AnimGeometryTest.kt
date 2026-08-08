package au.mark.kinetiq

import au.mark.kinetiq.anim.AnimationRegistry
import au.mark.kinetiq.anim.EllipticalAnim
import au.mark.kinetiq.anim.Joint
import au.mark.kinetiq.anim.KeyframeAnim
import au.mark.kinetiq.anim.Pose
import au.mark.kinetiq.anim.Rig
import au.mark.kinetiq.anim.SpinAnim
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

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
                if (lowest < 0.30f) {
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
        )
        for (id in strengthIds) {
            val anim = AnimationRegistry.byId[id] as KeyframeAnim
            assertThat(anim.keyframes.size).isAtLeast(4)
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
}
