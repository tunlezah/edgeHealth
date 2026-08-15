package au.mark.kinetiq

import au.mark.kinetiq.anim.AnimationRegistry
import au.mark.kinetiq.anim.KeyframeAnim
import au.mark.kinetiq.anim.PathJoint
import au.mark.kinetiq.anim.motionPathPoints
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The motion-path arc is cached in `remember(anim.id, canvasSize)`, which is only legal because it
 * is a pure function of exactly those two things. These pin the properties that make the cache
 * correct — determinism, and the canvas-size dependence that must therefore be part of the key.
 */
class MotionPathTest {

    private val withPath = AnimationRegistry.all
        .filterIsInstance<KeyframeAnim>()
        .filter { it.pathJoint != PathJoint.NONE }

    @Test
    fun `a large share of the registry declares a motion path`() {
        // Guards the reason the cache is worth having: this is not a rare code path.
        assertThat(withPath.size).isAtLeast(20)
    }

    @Test
    fun `the path is deterministic, which is what makes remember legal`() {
        for (anim in withPath) {
            assertThat(motionPathPoints(anim, 300f, 300f)).isEqualTo(motionPathPoints(anim, 300f, 300f))
        }
    }

    @Test
    fun `the path scales with the canvas, so canvas size must key the cache`() {
        for (anim in withPath) {
            val small = motionPathPoints(anim, 100f, 100f)
            val big = motionPathPoints(anim, 200f, 200f)
            assertThat(big).hasSize(small.size)
            small.zip(big).forEach { (s, b) ->
                assertThat(b.x).isWithin(1e-3f).of(s.x * 2f)
                assertThat(b.y).isWithin(1e-3f).of(s.y * 2f)
            }
        }
    }

    @Test
    fun `every path point lands within the canvas bounds`() {
        for (anim in withPath) {
            motionPathPoints(anim, 400f, 400f).forEach { point ->
                assertThat(point.x).isGreaterThan(-16f)
                assertThat(point.x).isLessThan(416f)
                assertThat(point.y).isGreaterThan(-16f)
                assertThat(point.y).isLessThan(416f)
            }
        }
    }

    @Test
    fun `a NONE path joint or a zero-sized canvas yields no points`() {
        val none = AnimationRegistry.all.filterIsInstance<KeyframeAnim>()
            .first { it.pathJoint == PathJoint.NONE }
        assertThat(motionPathPoints(none, 300f, 300f)).isEmpty()
        // Zero size is the first composition, before onSizeChanged has reported.
        assertThat(motionPathPoints(withPath.first(), 0f, 0f)).isEmpty()
    }
}
