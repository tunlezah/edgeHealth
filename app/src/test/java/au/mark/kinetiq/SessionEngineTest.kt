package au.mark.kinetiq

import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.SessionBlock
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.model.WorkoutPlan
import au.mark.kinetiq.domain.CalorieCalculator
import au.mark.kinetiq.service.SessionEngine
import au.mark.kinetiq.service.completedBlocks
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionEngineTest {

    private fun work(sec: Int, blockIndex: Int = 0, met: Float = 6f, id: String = "ex") =
        SessionStep(type = StepType.WORK, category = Category.FLOOR, exerciseId = id, exerciseName = id, durationSec = sec, met = met, blockIndex = blockIndex)

    private fun rest(sec: Int, blockIndex: Int = 0) =
        SessionStep(type = StepType.REST, category = Category.FLOOR, exerciseName = "Rest", durationSec = sec, blockIndex = blockIndex)

    private val weight = 80.0

    private fun engine(vararg steps: SessionStep) = SessionEngine(steps.toList(), weight)

    /** A running state with the prepare phase already over. */
    private fun runningState(e: SessionEngine) = e.initialState(prepareMs = 0)

    @Test
    fun `tick delta is clamped to the max tick`() {
        val e = engine(work(60))
        val result = e.onTick(runningState(e), rawDeltaMs = 600_000, nowEpochMs = 1_000L)
        assertThat(result.state.stepRemainingMs).isEqualTo(58_000)
        val expected = CalorieCalculator.kcal(6f, weight, 1) * 2.0
        assertThat(result.state.caloriesSoFar).isWithin(1e-9).of(expected)
    }

    @Test
    fun `final tick carries partial calories and active time into the next step`() {
        val e = engine(work(10), work(30))
        var s = runningState(e).copy(stepRemainingMs = 500)
        val before = s.totalElapsedActiveMs
        val result = e.onTick(s, rawDeltaMs = 2_000, nowEpochMs = 1_000L)
        assertThat(result.state.stepIndex).isEqualTo(1)
        assertThat(result.state.totalElapsedActiveMs - before).isEqualTo(500)
        assertThat(result.state.caloriesSoFar).isWithin(1e-9).of(CalorieCalculator.kcal(6f, weight, 1) * 0.5)
    }

    @Test
    fun `active time and calories never accrue on rest or transition steps`() {
        val e = engine(rest(30), work(30))
        val result = e.onTick(runningState(e), rawDeltaMs = 1_000, nowEpochMs = 1_000L)
        assertThat(result.state.totalElapsedActiveMs).isEqualTo(0)
        assertThat(result.state.caloriesSoFar).isEqualTo(0.0)
        assertThat(result.state.blockActiveMs).isEmpty()
    }

    @Test
    fun `skipping a work step also skips the following rest`() {
        val e = engine(work(30), rest(15), work(40))
        val result = e.skip(runningState(e))
        assertThat(result.state.stepIndex).isEqualTo(2)
        assertThat(result.state.stepRemainingMs).isEqualTo(40_000)
    }

    @Test
    fun `skipping a rest advances a single step`() {
        val e = engine(work(30), rest(15), work(40))
        val atRest = runningState(e).copy(stepIndex = 1, stepRemainingMs = 15_000)
        val result = e.skip(atRest)
        assertThat(result.state.stepIndex).isEqualTo(2)
    }

    @Test
    fun `skipping the last step finishes the session`() {
        val e = engine(work(30))
        val result = e.skip(runningState(e))
        assertThat(result.state.finished).isTrue()
        assertThat(result.effects).contains(SessionEngine.Effect.Finished)
    }

    @Test
    fun `halfway cue fires once for long work steps`() {
        val e = engine(work(60))
        var s = runningState(e)
        var halfways = 0
        repeat(40) {
            val r = e.onTick(s, 1_000, 1_000L)
            s = r.state
            halfways += r.effects.count { it == SessionEngine.Effect.SpeakHalfway }
        }
        assertThat(halfways).isEqualTo(1)
    }

    @Test
    fun `countdown beeps fire before a work step`() {
        val e = engine(rest(10), work(30))
        var s = runningState(e)
        var beeps = 0
        repeat(9) {
            val r = e.onTick(s, 1_000, 1_000L)
            s = r.state
            beeps += r.effects.count { it == SessionEngine.Effect.PlayCountdownBeeps }
        }
        assertThat(beeps).isEqualTo(1)
    }

    @Test
    fun `block active time and bounds are tracked per block index`() {
        val e = engine(work(2, blockIndex = 0), work(2, blockIndex = 1))
        var s = runningState(e)
        var now = 100_000L
        repeat(5) {
            val r = e.onTick(s, 1_000, now)
            s = r.state
            now += 1_000
        }
        assertThat(s.blockActiveMs.keys).containsExactly(0, 1)
        assertThat(s.blockActiveMs[0]).isEqualTo(2_000)
        assertThat(s.blockBounds[0]!!.first).isEqualTo(100_000L)
    }

    @Test
    fun `extend adds thirty seconds to the current step`() {
        val e = engine(work(30))
        val s = e.extend(runningState(e))
        assertThat(s.stepRemainingMs).isEqualTo(60_000)
    }

    @Test
    fun `cue flags for restore are derived from remaining time`() {
        val w = work(60)
        assertThat(SessionEngine.cueFlagsForRestore(w, 50_000).halfwaySpoken).isFalse()
        assertThat(SessionEngine.cueFlagsForRestore(w, 20_000).halfwaySpoken).isTrue()
        assertThat(SessionEngine.cueFlagsForRestore(w, 2_000).countdownSpoken).isTrue()
        // WORK steps never re-speak the how-to on restore.
        assertThat(SessionEngine.cueFlagsForRestore(w, 50_000).howToSpoken).isTrue()
        val r = rest(30)
        assertThat(SessionEngine.cueFlagsForRestore(r, 29_500).howToSpoken).isFalse()
        assertThat(SessionEngine.cueFlagsForRestore(r, 10_000).howToSpoken).isTrue()
    }

    @Test
    fun `prepare phase accrues no calories or active time`() {
        val e = engine(work(40))
        var s = e.initialState() // 10 s prepare
        var prepareEnded = false
        while (!prepareEnded) {
            val r = e.onTick(s, 2_000, 1_000L)
            s = r.state
            if (SessionEngine.Effect.PrepareEnded in r.effects) prepareEnded = true
        }
        assertThat(prepareEnded).isTrue()
        assertThat(s.totalElapsedActiveMs).isEqualTo(0)
        assertThat(s.caloriesSoFar).isEqualTo(0.0)
        assertThat(s.stepRemainingMs).isEqualTo(40_000) // step clock untouched during prepare
    }

    @Test
    fun `prepare phase fires countdown beeps once in the last three seconds`() {
        val e = engine(work(40))
        var s = e.initialState()
        var beeps = 0
        repeat(6) {
            val r = e.onTick(s, 2_000, 1_000L)
            s = r.state
            beeps += r.effects.count { it == SessionEngine.Effect.PlayCountdownBeeps }
        }
        assertThat(beeps).isEqualTo(1)
    }

    @Test
    fun `prepare end announces the step only when asked`() {
        val e = engine(work(40))
        var announced = 0
        var s = e.initialState().copy(prepareRemainingMs = 1_000, announceAfterPrepare = false)
        announced += e.onTick(s, 1_500, 1_000L).effects.count { it is SessionEngine.Effect.AnnounceStep }
        assertThat(announced).isEqualTo(0)
        s = e.initialState().copy(prepareRemainingMs = 1_000, announceAfterPrepare = true)
        announced += e.onTick(s, 1_500, 1_000L).effects.count { it is SessionEngine.Effect.AnnounceStep }
        assertThat(announced).isEqualTo(1)
    }

    @Test
    fun `skip during prepare jumps to the final three seconds`() {
        val e = engine(work(40))
        val s = e.initialState().copy(prepareRemainingMs = 9_000)
        assertThat(e.skipPrepare(s).prepareRemainingMs).isEqualTo(SessionEngine.RESUME_PREPARE_MS)
        val late = e.initialState().copy(prepareRemainingMs = 2_000)
        assertThat(e.skipPrepare(late).prepareRemainingMs).isEqualTo(2_000)
    }

    @Test
    fun `completed block met is duration weighted`() {
        val plan = WorkoutPlan(
            steps = listOf(work(60, blockIndex = 0, met = 8f), work(540, blockIndex = 0, met = 2f)),
            blocks = listOf(SessionBlock(category = Category.FLOOR)),
        )
        val blocks = completedBlocks(
            plan = plan,
            blockActiveMs = mapOf(0 to 600_000L),
            blockBounds = mapOf(0 to (0L to 600_000L)),
            weightKg = weight,
            fallbackBounds = 0L to 600_000L,
        )
        // Duration-weighted MET = (8*60 + 2*540) / 600 = 2.6, not the plain average 5.0.
        val expected = CalorieCalculator.kcal(2.6f, weight, 600)
        assertThat(blocks.single().calories).isWithin(0.01).of(expected)
    }

    @Test
    fun `first stop arms and second stop within the window finishes`() {
        // t=0: nothing armed → ARM. Armed until t=3000.
        assertThat(au.mark.kinetiq.service.stopArmDecision(nowMs = 0, armedUntilMs = 0))
            .isEqualTo(au.mark.kinetiq.service.StopDecision.ARM)
        assertThat(au.mark.kinetiq.service.stopArmDecision(nowMs = 2_999, armedUntilMs = 3_000))
            .isEqualTo(au.mark.kinetiq.service.StopDecision.FINISH)
        assertThat(au.mark.kinetiq.service.stopArmDecision(nowMs = 3_001, armedUntilMs = 3_000))
            .isEqualTo(au.mark.kinetiq.service.StopDecision.ARM)
    }

    @Test
    fun `block met aggregation excludes sentinel indices`() {
        val plan = WorkoutPlan(
            steps = listOf(
                work(120, blockIndex = -1, met = 3f, id = "warmup"),
                work(300, blockIndex = 0, met = 8f),
                work(120, blockIndex = -2, met = 2f, id = "cooldown"),
            ),
            blocks = listOf(SessionBlock(category = Category.SPIN)),
        )
        val blocks = completedBlocks(
            plan = plan,
            blockActiveMs = mapOf(-1 to 120_000L, 0 to 300_000L, -2 to 120_000L),
            blockBounds = mapOf(0 to (10L to 20L)),
            weightKg = weight,
            fallbackBounds = 0L to 1L,
        )
        assertThat(blocks).hasSize(1)
        assertThat(blocks.single().activeSec).isEqualTo(300)
        assertThat(blocks.single().category).isEqualTo("SPIN")
    }
}
