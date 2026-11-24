package au.mark.kinetiq

import au.mark.kinetiq.ResistanceTestSupport.set
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.EffortRating
import au.mark.kinetiq.data.model.Equipment
import au.mark.kinetiq.data.model.MovementSlot
import au.mark.kinetiq.data.model.Muscle
import au.mark.kinetiq.data.model.SessionBlock
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.model.WorkoutPlan
import au.mark.kinetiq.data.repo.CompletedBlock
import au.mark.kinetiq.data.repo.HistoryEntry
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.domain.generator.ResistanceBlockBuilder
import au.mark.kinetiq.domain.plan.WeeklyPlanEngine
import au.mark.kinetiq.domain.resistance.ProgressionAdvisor
import au.mark.kinetiq.domain.resistance.ResistanceTracking
import au.mark.kinetiq.domain.resistance.ResistanceTracking.Advice
import au.mark.kinetiq.service.PlayerState
import au.mark.kinetiq.service.SessionEngine
import au.mark.kinetiq.service.SessionSnapshot
import au.mark.kinetiq.service.completedBlocks
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/**
 * From "Set done" to the weekly meter: which sets the engine records as performed, how they turn
 * into hard-set credit per muscle, and what the app advises afterwards.
 */
class ResistanceTrackingTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun setStep(
        id: String, n: Int, of: Int, sec: Int = 60, block: Int = 0, switchSides: Boolean = false,
        primary: List<Muscle> = listOf(Muscle.CHEST), secondary: List<Muscle> = listOf(Muscle.TRICEPS),
    ) = SessionStep(
        type = StepType.WORK, category = Category.FLOOR, exerciseId = id, exerciseName = id, durationSec = sec,
        met = 3.8f, blockIndex = block, setIndex = n, setCount = of, switchSides = switchSides,
        effortCue = "Slow reps.", primaryMuscles = primary, secondaryMuscles = secondary,
    )

    private fun rest(sec: Int = 30, block: Int = 0) =
        SessionStep(type = StepType.REST, category = Category.FLOOR, exerciseName = "Rest", durationSec = sec, blockIndex = block)

    private fun plain(sec: Int = 40) =
        SessionStep(type = StepType.WORK, category = Category.FLOOR, exerciseId = "plain", exerciseName = "plain", durationSec = sec)

    private fun engine(vararg steps: SessionStep) = SessionEngine(steps.toList(), 80.0)
    private fun running(e: SessionEngine) = e.initialState(prepareMs = 0)

    /** Advance by whole seconds in engine-sized ticks, collecting effects. */
    private fun SessionEngine.run(from: SessionEngine.EngineState, seconds: Int): Pair<SessionEngine.EngineState, List<SessionEngine.Effect>> {
        var s = from
        val effects = mutableListOf<SessionEngine.Effect>()
        repeat(seconds) {
            val r = onTick(s, 1_000, nowEpochMs = 1_000L)
            s = r.state
            effects += r.effects
        }
        return s to effects
    }

    // ------------------------------------------------------------------ engine

    @Test
    fun `Set done goes to the rest, where skip jumps past it`() {
        val e = engine(setStep("a", 1, 2), rest(), setStep("a", 2, 2))
        val (worked, _) = e.run(running(e), 20)
        val done = e.completeSet(worked)
        assertThat(done.state.stepIndex).isEqualTo(1)
        assertThat(done.state.stepRemainingMs).isEqualTo(30_000)
        assertThat(done.effects).contains(SessionEngine.Effect.AnnounceStep(1, fresh = false))
        assertThat(e.skip(worked).state.stepIndex).isEqualTo(2)
    }

    @Test
    fun `a set that runs its window or is ended with Set done is recorded, a skipped one is not`() {
        val e = engine(setStep("a", 1, 3), rest(), setStep("a", 2, 3), rest(), setStep("a", 3, 3))
        // Set 1: full window.
        var (s, _) = e.run(running(e), 60)
        assertThat(s.stepIndex).isEqualTo(1)
        assertThat(s.completedSets).containsExactly(0)
        // Set 2: skipped.
        s = e.run(s, 30).first
        s = e.skip(e.run(s, 20).first).state
        assertThat(s.stepIndex).isEqualTo(4)
        assertThat(s.completedSets).containsExactly(0)
        // Set 3: Set done after 25 s.
        val finished = e.completeSet(e.run(s, 25).first)
        assertThat(finished.state.finished).isTrue()
        assertThat(finished.state.completedSets).containsExactly(0, 4)
        assertThat(finished.effects).contains(SessionEngine.Effect.Finished)
    }

    @Test
    fun `Set done inside the first fifteen seconds ends the set without counting it`() {
        val e = engine(setStep("a", 1, 2), rest(), setStep("a", 2, 2))
        val early = e.completeSet(e.run(running(e), 10).first)
        assertThat(early.state.stepIndex).isEqualTo(1)
        assertThat(early.state.completedSets).isEmpty()
        val onTime = e.completeSet(e.run(running(e), 15).first)
        assertThat(onTime.state.completedSets).containsExactly(0)
    }

    @Test
    fun `extending a set does not stop Set done from counting it`() {
        val e = engine(setStep("a", 1, 1, sec = 45), rest())
        val (s, _) = e.run(e.extend(e.run(running(e), 10).first), 30)
        // 40 s done of a window stretched to 75 s.
        assertThat(e.completeSet(s).state.completedSets).containsExactly(0)
    }

    @Test
    fun `Set done is a no-op on ordinary steps, rests and during get-ready`() {
        val circuit = engine(plain(), rest(), plain())
        val s = circuit.run(running(circuit), 20).first
        assertThat(circuit.completeSet(s).state).isSameInstanceAs(s)
        val e = engine(setStep("a", 1, 1), rest(), setStep("b", 1, 1))
        val atRest = e.run(running(e), 61).first
        assertThat(atRest.stepIndex).isEqualTo(1)
        assertThat(e.completeSet(atRest).state).isSameInstanceAs(atRest)
        val preparing = e.initialState()
        assertThat(e.completeSet(preparing).state).isSameInstanceAs(preparing)
    }

    @Test
    fun `circuit steps are never recorded as sets`() {
        val e = engine(plain(40), rest(15), plain(40))
        val s = e.run(running(e), 95).first
        assertThat(s.finished).isTrue()
        assertThat(s.completedSets).isEmpty()
    }

    @Test
    fun `a one-side-at-a-time set calls the switch at its midpoint instead of halfway`() {
        val e = engine(setStep("split", 1, 1, sec = 120, switchSides = true))
        val (_, effects) = e.run(running(e), 61)
        assertThat(effects.count { it == SessionEngine.Effect.SpeakSwitchSides }).isEqualTo(1)
        assertThat(effects).doesNotContain(SessionEngine.Effect.SpeakHalfway)
        // Even a short hold per side gets the cue; the ordinary halfway cue needs 40 s.
        val short = engine(setStep("hold", 1, 1, sec = 30, switchSides = true))
        assertThat(short.run(running(short), 16).second).contains(SessionEngine.Effect.SpeakSwitchSides)
        val bilateral = engine(setStep("push", 1, 1, sec = 60))
        val both = bilateral.run(running(bilateral), 31).second
        assertThat(both).contains(SessionEngine.Effect.SpeakHalfway)
        assertThat(both).doesNotContain(SessionEngine.Effect.SpeakSwitchSides)
    }

    // ------------------------------------------------------------------ credit

    private val plan = WorkoutPlan(
        steps = listOf(
            setStep("push", 1, 2), rest(), setStep("split", 1, 2, primary = listOf(Muscle.QUADS, Muscle.GLUTES), secondary = emptyList()),
            rest(), setStep("push", 2, 2), rest(), setStep("split", 2, 2, primary = listOf(Muscle.QUADS, Muscle.GLUTES), secondary = emptyList()),
            // A second, reformer block.
            setStep("curl", 1, 1, block = 1, primary = listOf(Muscle.BICEPS), secondary = emptyList()),
        ),
        blocks = listOf(SessionBlock(Category.FLOOR, isResistance = true), SessionBlock(Category.REFORMER, isResistance = true)),
    )

    @Test
    fun `a performed set earns one set per primary muscle and half per secondary`() {
        val all = setOf(0, 2, 4, 6, 7)
        val floor = ResistanceTracking.muscleSets(plan, 0, all, emptyMap())
        assertThat(floor).containsExactly("CHEST", 2.0, "TRICEPS", 1.0, "QUADS", 2.0, "GLUTES", 2.0)
        assertThat(ResistanceTracking.hardSets(plan, 0, all, emptyMap())).isEqualTo(4)
        assertThat(ResistanceTracking.muscleSets(plan, 1, all, emptyMap())).containsExactly("BICEPS", 1.0)
    }

    @Test
    fun `skipped sets and sets rated too easy earn nothing, unrated sets count`() {
        val performed = setOf(0, 2, 4) // split set 2 (index 6) skipped
        val ratings = mapOf(0 to EffortRating.HARD, 2 to EffortRating.EASY) // push set 2 (index 4) unrated
        val sets = ResistanceTracking.muscleSets(plan, 0, performed, ratings)
        assertThat(sets).containsExactly("CHEST", 2.0, "TRICEPS", 1.0)
        assertThat(ResistanceTracking.hardSets(plan, 0, performed, ratings)).isEqualTo(2)
        // A rating on a step that was never performed changes nothing.
        assertThat(ResistanceTracking.hardSets(plan, 0, performed, ratings + (6 to EffortRating.HARD))).isEqualTo(2)
    }

    @Test
    fun `completed blocks carry set credit for resistance blocks only`() {
        val active = mapOf(0 to 240_000L, 1 to 60_000L)
        val blocks = completedBlocks(
            plan, active, emptyMap(), 80.0, fallbackBounds = 1L to 2L,
            completedSets = setOf(0, 2, 4, 6, 7), setRatings = mapOf(7 to EffortRating.EASY),
        )
        assertThat(blocks[0].isResistance).isTrue()
        assertThat(blocks[0].hardSets).isEqualTo(4)
        assertThat(blocks[0].muscleSets["QUADS"]).isEqualTo(2.0)
        assertThat(blocks[1].hardSets).isEqualTo(0) // its only set was rated too easy
        assertThat(blocks[1].muscleSets).isEmpty()

        val circuit = WorkoutPlan(steps = listOf(plain()), blocks = listOf(SessionBlock(Category.FLOOR)))
        val plainBlock = completedBlocks(circuit, mapOf(0 to 40_000L), emptyMap(), 80.0, 1L to 2L).single()
        assertThat(plainBlock.isResistance).isFalse()
        assertThat(plainBlock.hardSets).isEqualTo(0)
        assertThat(plainBlock.muscleSets).isEmpty()
    }

    @Test
    fun `history written before the feature decodes with no set credit`() {
        val legacy = """[{"category":"FLOOR","activeSec":600,"calories":50.0,"isHiit":false,"startedAtEpochMs":1,"endedAtEpochMs":2}]"""
        val block = json.decodeFromString(ListSerializer(CompletedBlock.serializer()), legacy).single()
        assertThat(block.isResistance).isFalse()
        assertThat(block.hardSets).isEqualTo(0)
        assertThat(block.muscleSets).isEmpty()
    }

    @Test
    fun `a snapshot from before the feature restores with nothing recorded, and new ones round-trip`() {
        val session = """{"config":{},"plan":{"steps":[{"type":"WORK","category":"FLOOR","exerciseName":"A","durationSec":40}],"blocks":[{"category":"FLOOR"}]}}"""
        val legacy = """{"session":$session,"sessionName":"Old","stepIndex":0,"stepRemainingMs":1000,"totalElapsedActiveMs":0,
            "caloriesSoFar":0.0,"startedAtEpochMs":1,"weightKg":80.0,"savedAtEpochMs":2}"""
        val snap = json.decodeFromString(SessionSnapshot.serializer(), legacy)
        assertThat(snap.completedSets).isEmpty()
        assertThat(snap.setRatings).isEmpty()
        val updated = snap.copy(completedSets = listOf(0, 4), setRatings = mapOf(0 to "HARD", 4 to "EASY"))
        assertThat(json.decodeFromString(SessionSnapshot.serializer(), json.encodeToString(SessionSnapshot.serializer(), updated)))
            .isEqualTo(updated)
    }

    @Test
    fun `only the set just performed can be rated, and only during its rest`() {
        val session = au.mark.kinetiq.data.model.GeneratedSession(au.mark.kinetiq.data.model.GeneratorConfig(), plan)
        val base = PlayerState(session = session, sessionName = "S")
        assertThat(base.copy(stepIndex = 1, completedSets = setOf(0)).rateableSetIndex).isEqualTo(0)
        assertThat(base.copy(stepIndex = 1, completedSets = emptySet()).rateableSetIndex).isNull() // skipped
        assertThat(base.copy(stepIndex = 2, completedSets = setOf(0)).rateableSetIndex).isNull()  // working
        assertThat(base.copy(stepIndex = 0).rateableSetIndex).isNull()
    }

    // ------------------------------------------------------------------ advice

    private fun outcome(vararg ratings: EffortRating, id: String = "knee_pushup") =
        ResistanceTracking.ExerciseOutcome(id, "Set $id", plannedSets = 3, performedSets = ratings.size, ratings = ratings.toList())

    @Test
    fun `moving up needs at least two rated sets, all of them easy`() {
        assertThat(ResistanceTracking.advise(outcome(EffortRating.EASY, EffortRating.EASY))).isEqualTo(Advice.MOVE_UP)
        assertThat(ResistanceTracking.advise(outcome(EffortRating.EASY))).isEqualTo(Advice.HOLD)
        assertThat(ResistanceTracking.advise(outcome(EffortRating.EASY, EffortRating.EASY, EffortRating.MEDIUM)))
            .isEqualTo(Advice.PUSH_HARDER)
        assertThat(ResistanceTracking.advise(outcome(EffortRating.MEDIUM, EffortRating.MEDIUM))).isEqualTo(Advice.PUSH_HARDER)
        assertThat(ResistanceTracking.advise(outcome(EffortRating.HARD, EffortRating.MEDIUM))).isEqualTo(Advice.HOLD)
        assertThat(ResistanceTracking.advise(outcome(EffortRating.HARD, EffortRating.HARD, EffortRating.HARD))).isEqualTo(Advice.HOLD)
        assertThat(ResistanceTracking.advise(outcome())).isEqualTo(Advice.HOLD)
    }

    @Test
    fun `outcomes group sets by exercise with the ratings of the sets performed`() {
        val outcomes = ResistanceTracking.outcomes(plan, setOf(0, 2, 4), mapOf(0 to EffortRating.EASY, 4 to EffortRating.EASY))
        val push = outcomes.single { it.exerciseId == "push" }
        assertThat(push.plannedSets).isEqualTo(2)
        assertThat(push.performedSets).isEqualTo(2)
        assertThat(push.ratings).containsExactly(EffortRating.EASY, EffortRating.EASY)
        val split = outcomes.single { it.exerciseId == "split" }
        assertThat(split.performedSets).isEqualTo(1)
        assertThat(split.ratings).isEmpty()
        assertThat(outcomes.single { it.exerciseId == "curl" }.performedSets).isEqualTo(0)
    }

    @Test
    fun `a move up names the next rung the user's equipment allows`() {
        val library = listOf(
            set("knee_pushup", MovementSlot.UPPER_PUSH, ladder = "pushup", rung = 2),
            set("pushup", MovementSlot.UPPER_PUSH, ladder = "pushup", rung = 3),
            set("elevated_pushup", MovementSlot.UPPER_PUSH, ladder = "pushup", rung = 4, equipment = listOf(Equipment.CHAIR_OR_STEP)),
            set("lateral_raise", MovementSlot.SHOULDERS),
        )
        val easy = arrayOf(EffortRating.EASY, EffortRating.EASY)
        val noChair: (au.mark.kinetiq.data.model.Exercise) -> Boolean = { it.equipment.isEmpty() }

        val fromKnees = ProgressionAdvisor.suggestions(listOf(outcome(*easy)), library, noChair).single()
        assertThat(fromKnees.canMoveUp).isTrue()
        assertThat(fromKnees.ladder).isEqualTo("pushup")
        assertThat(fromKnees.nextRung).isEqualTo(3)
        assertThat(fromKnees.nextExerciseName).isEqualTo("Set pushup")

        // The top rung the equipment allows: say so rather than invent a step.
        val fromFull = ProgressionAdvisor.suggestions(listOf(outcome(*easy, id = "pushup")), library, noChair).single()
        assertThat(fromFull.advice).isEqualTo(Advice.MOVE_UP)
        assertThat(fromFull.canMoveUp).isFalse()
        val withChair = ProgressionAdvisor.suggestions(listOf(outcome(*easy, id = "pushup")), library) { true }.single()
        assertThat(withChair.nextRung).isEqualTo(4)

        // No ladder at all.
        val solo = ProgressionAdvisor.suggestions(listOf(outcome(*easy, id = "lateral_raise")), library) { true }.single()
        assertThat(solo.canMoveUp).isFalse()

        // Sets that went as intended produce no suggestion.
        assertThat(ProgressionAdvisor.suggestions(listOf(outcome(EffortRating.HARD, EffortRating.HARD)), library) { true }).isEmpty()
    }

    @Test
    fun `ladders list their rungs in order and step within their ends`() {
        val library = listOf(
            set("pushup", MovementSlot.UPPER_PUSH, ladder = "pushup", rung = 3),
            set("knee_pushup", MovementSlot.UPPER_PUSH, ladder = "pushup", rung = 2),
            set("lunge", MovementSlot.LOWER_PUSH, ladder = "leg_push", rung = 2),
            set("split_squat", MovementSlot.LOWER_PUSH, ladder = "leg_push", rung = 2),
            set("squat", MovementSlot.LOWER_PUSH, ladder = "leg_push", rung = 1),
            set("lonely", MovementSlot.CALVES, ladder = "calf", rung = 1), // one rung: not a ladder
            set("solo", MovementSlot.SHOULDERS),
        )
        val ladders = au.mark.kinetiq.domain.resistance.Ladders.from(library)
        // Movement-slot order: leg push before upper push.
        assertThat(ladders.map { it.id }).containsExactly("leg_push", "pushup").inOrder()
        val legs = ladders[0]
        assertThat(legs.rungs.map { it.rung }).containsExactly(1, 2).inOrder()
        assertThat(legs.rungs[1].names).containsExactly("Set lunge", "Set split_squat")
        // Unset: the default level. Out of range: the nearest rung that exists.
        assertThat(legs.current(null).rung).isEqualTo(2)
        assertThat(legs.current(7).rung).isEqualTo(2)
        assertThat(legs.step(null, -1)!!.rung).isEqualTo(1)
        assertThat(legs.step(null, +1)).isNull()
        assertThat(legs.step(1, -1)).isNull()
        val push = ladders[1]
        assertThat(push.current(null).rung).isEqualTo(2)
        assertThat(push.step(2, +1)!!.names).containsExactly("Set pushup")
        // The Settings default and the generator's default are one number.
        assertThat(au.mark.kinetiq.domain.resistance.Ladders.DEFAULT_RUNG).isEqualTo(ResistanceBlockBuilder.DEFAULT_RUNG)
    }

    @Test
    fun `ladder levels survive storage`() {
        val rungs = mapOf("pushup" to 3, "leg_push" to 2)
        val encoded = SettingsRepository.encodeLadderRungs(rungs)
        assertThat(encoded).isEqualTo("leg_push=2,pushup=3")
        assertThat(SettingsRepository.decodeLadderRungs(encoded)).isEqualTo(rungs)
        assertThat(SettingsRepository.decodeLadderRungs(null)).isEmpty()
        assertThat(SettingsRepository.decodeLadderRungs("pushup=x,,=2,calf=0,ok=1")).containsExactly("ok", 1)
    }

    // ------------------------------------------------------------------ the week

    private val zone = ZoneId.of("Australia/Sydney")
    private val wednesday = LocalDate.of(2026, 11, 25)

    private fun entry(date: LocalDate, vararg blocks: CompletedBlock) = HistoryEntry(
        id = 0, startedAtEpochMs = date.atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
        endedAtEpochMs = date.atTime(7, 30).atZone(zone).toInstant().toEpochMilli(),
        name = "S", totalActiveSec = 1200, calories = 100.0, blocks = blocks.toList(), healthConnectWritten = false,
    )

    private fun resistanceBlock(hardSets: Int, vararg sets: Pair<Muscle, Double>) = CompletedBlock(
        category = "FLOOR", activeSec = 900, calories = 60.0, isHiit = false, startedAtEpochMs = 1, endedAtEpochMs = 2,
        isResistance = true, hardSets = hardSets, muscleSets = sets.associate { it.first.name to it.second },
    )

    @Test
    fun `the weekly meter sums this week's sets per muscle and counts real sessions`() {
        val history = listOf(
            entry(wednesday.minusDays(2), resistanceBlock(6, Muscle.QUADS to 3.0, Muscle.CHEST to 3.0, Muscle.TRICEPS to 1.5)),
            entry(wednesday, resistanceBlock(5, Muscle.QUADS to 3.0, Muscle.BICEPS to 2.0)),
            entry(wednesday, resistanceBlock(2, Muscle.CALVES to 2.0)),               // a top-up, not a session
            entry(wednesday.minusDays(7), resistanceBlock(9, Muscle.BACK to 9.0)),    // last week
            // Cardio and circuits never count, whatever they claim.
            entry(wednesday, CompletedBlock("SPIN", 1800, 300.0, true, 1, 2, muscleSets = mapOf("QUADS" to 99.0))),
        )
        val week = WeeklyPlanEngine.progressForWeek(history, visceralFatGoal = true, today = wednesday, zone = zone).resistance
        assertThat(week.sessionsDone).isEqualTo(2)
        assertThat(week.setsByMuscle[Muscle.QUADS]).isEqualTo(6.0)
        assertThat(week.setsByMuscle[Muscle.TRICEPS]).isEqualTo(1.5)
        assertThat(week.setsByMuscle[Muscle.BACK]).isEqualTo(0.0)
        assertThat(week.setsByMuscle.keys).containsExactlyElementsIn(Muscle.entries)
        assertThat(week.suggestion).contains("Under the weekly minimum")
        assertThat(week.suggestion).contains("upper back")
        assertThat(week.suggestion).doesNotContain("quads")
        assertThat(WeeklyPlanEngine.weeklyMuscleSets(history, wednesday, zone)[Muscle.QUADS]).isEqualTo(6.0)
    }

    @Test
    fun `the weekly suggestion is honest at each stage`() {
        val none = WeeklyPlanEngine.progressForWeek(emptyList(), true, wednesday, zone).resistance
        assertThat(none.anySets).isFalse()
        assertThat(none.suggestion).contains("No resistance sets yet")
        // Trunk is not chased: nine muscles at target with trunk at zero is "done".
        val all = Muscle.entries.filter { it != Muscle.TRUNK }.map { it to 6.0 }.toTypedArray()
        val done = WeeklyPlanEngine.progressForWeek(listOf(entry(wednesday, resistanceBlock(20, *all))), true, wednesday, zone).resistance
        assertThat(done.suggestion).contains("More adds little")
        val floor = Muscle.entries.filter { it != Muscle.TRUNK }.map { it to 4.0 }.toTypedArray()
        val nearly = WeeklyPlanEngine.progressForWeek(listOf(entry(wednesday, resistanceBlock(20, *floor))), true, wednesday, zone).resistance
        assertThat(nearly.suggestion).contains("Still short of 6 sets")
    }

    @Test
    fun `existing weekly targets are unchanged by resistance history`() {
        val history = listOf(entry(wednesday, resistanceBlock(6, Muscle.QUADS to 6.0)))
        val progress = WeeklyPlanEngine.progressForWeek(history, visceralFatGoal = true, today = wednesday, zone = zone)
        // A 15-minute floor block still counts as a strength session exactly as before.
        assertThat(progress.strengthSessionsDone).isEqualTo(1)
        assertThat(progress.cardioSessionsDone).isEqualTo(0)
        assertThat(progress.targets.strengthSessions).isEqualTo(2)
    }

    @Test
    fun `a retention session starts with the region furthest behind this week`() {
        val builder = { seed: Int -> ResistanceBlockBuilder(emptyList(), au.mark.kinetiq.data.repo.MachineSettings(), Random(seed)) }
        val week = mapOf(Muscle.QUADS to 6.0, Muscle.CHEST to 6.0, Muscle.HAMSTRINGS to 3.0, Muscle.BACK to 0.0)
        repeat(20) { seed ->
            val order = builder(seed).slotOrder(week)
            assertThat(order.first()).isEqualTo(MovementSlot.UPPER_PULL)
            // Rotation, not sorting: lower and upper still alternate through the big four.
            assertThat(order.take(4)).containsExactly(
                MovementSlot.UPPER_PULL, MovementSlot.LOWER_PUSH, MovementSlot.UPPER_PUSH, MovementSlot.LOWER_PULL,
            ).inOrder()
            assertThat(order.drop(4)).isEqualTo(ResistanceBlockBuilder.ACCESSORIES)
        }
        // Level pegging: any of the four may lead.
        val starts = (0 until 40).map { builder(it).slotOrder().first() }.toSet()
        assertThat(starts).containsExactlyElementsIn(ResistanceBlockBuilder.BIG_FOUR)
    }
}
