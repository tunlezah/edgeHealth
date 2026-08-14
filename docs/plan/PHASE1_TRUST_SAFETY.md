All exploration is done — I read every file involved (service, state, models, generator, player, summary, nav, widget, voice, HC manager, repos, settings, DAOs/entities, validator, and all four test files). The plan below is the deliverable.

---

# Kinetiq Phase 1 Implementation Plan — Trust & Safety

## Global conventions verified from the codebase (implementation agent must follow these)

- Settings flow: `SettingsRepository.settings: Flow<AppSettings>` (DataStore), `current()` one-shot; ViewModels use `viewModel.set { setX(...) }` pattern (SettingsScreen.kt:70-72).
- Service state: `SessionStateHolder` (@Singleton) holds `state: StateFlow<PlayerState?>` and `lastCompleted: StateFlow<CompletedSummary?>`; the service is the only writer; UI observes via `hiltViewModel()`.
- JSON: single Hilt `Json { ignoreUnknownKeys = true; encodeDefaults = true }` (AppModule.kt:23-26). Adding fields **with defaults** to `@Serializable` classes is forward- and backward-compatible. Adding a **new enum value** to a serialized enum is NOT downgrade-safe — this drives the PREPARE decision below.
- Tests: plain JUnit4 + Truth in `app/src/test/java/au/mark/kinetiq/`, backtick sentence names, real `exercise_db.json` loaded via `File("src/main/assets/exercise_db.json")` with `Json { ignoreUnknownKeys = true }`, fixed `Random(seed)` (WorkoutGeneratorTest.kt:24-32). Robolectric is available (`testImplementation(libs.robolectric)`) but no existing test uses it; keep new tests pure-JVM wherever possible.
- Screen text is written inline in composables (existing pattern); only notification/widget/disclaimer strings live in `res/values/strings.xml`. Follow that split.
- No Room schema changes anywhere in this phase (`fallbackToDestructiveMigration()` is set, but nothing below touches an `@Entity`).

## Implementation sequence (dependency-safe)

| Order | Item | Why here |
|---|---|---|
| 1 | Item 9 — validator bounds | Zero deps, trivial |
| 2 | Item 5 — generator time-math + invariant tests | No service deps; PREPARE never touches generator |
| 3 | Item 7 — VoiceCoach status/clamp | Item 6d/6e and Item 1 speak through the new API |
| 4 | Item R — SessionEngine extraction | Foundation for 6a-6h and 1 |
| 5 | Item 6 — service fixes (a-h), incl. 6f containment | Uses R and 7 |
| 6 | Item 1 — GET-READY pre-phase | Uses R (engine prepare state), 6b (snapshot fields) |
| 7 | Item 3 — summary identity + global nav | Introduces `sessionId` used by Item 2 |
| 8 | Item 2 — stop confirmation + recovery snapshot | Uses 3 (`sessionId`, summary flow), 6b/6f |
| 9 | Item 4 — widget/start guards + updateAll | Uses 6f (finishSession is stable) |
| 10 | Item 8 — Health Connect | Uses 6f/6h (block records), 3 (Summary retry UI) |

---

## Item R — Extract a pure `SessionEngine` (prerequisite refactor)

**Goal.** Move all tick/advance/cue/accrual arithmetic out of `WorkoutSessionService` into a pure Kotlin class with no Android imports so items 6a-6h and 1 get real JVM tests. The service keeps: coroutine ticker, wake lock, notification, MediaSession, TTS calls, snapshot I/O, DB/HC writes.

**Files.**
- NEW `app/src/main/java/au/mark/kinetiq/service/SessionEngine.kt`
- `app/src/main/java/au/mark/kinetiq/service/WorkoutSessionService.kt` (delegate)
- `app/src/main/java/au/mark/kinetiq/service/SessionState.kt` (PlayerState gains fields, see Items 1/3/6b)
- NEW `app/src/test/java/au/mark/kinetiq/SessionEngineTest.kt`

**Exact changes.**

```kotlin
package au.mark.kinetiq.service
// imports: only au.mark.kinetiq.data.model.*, au.mark.kinetiq.domain.CalorieCalculator, kotlin stdlib

class SessionEngine(
    private val steps: List<SessionStep>,
    private val weightKg: Double,
) {
    data class CueFlags(
        val halfwaySpoken: Boolean = false,
        val countdownSpoken: Boolean = false,
        val howToSpoken: Boolean = false,
        val prepareBeepsPlayed: Boolean = false,
    )

    data class EngineState(
        val stepIndex: Int = 0,
        val stepRemainingMs: Long = 0L,
        val prepareRemainingMs: Long = 0L,          // > 0 == in GET-READY pre-phase (Item 1)
        val totalElapsedActiveMs: Long = 0L,
        val caloriesSoFar: Double = 0.0,
        val blockActiveMs: Map<Int, Long> = emptyMap(),
        val blockBounds: Map<Int, Pair<Long, Long>> = emptyMap(),
        val cues: CueFlags = CueFlags(),
        val finished: Boolean = false,
    ) {
        val currentStep: SessionStep? get() = null // implemented against the engine's steps via helper below
    }

    sealed interface Effect {
        data object PlayCountdownBeeps : Effect          // 3-2-1 beeps
        data object SpeakHalfway : Effect
        data class SpeakNextHowTo(val nextIndex: Int) : Effect
        data class AnnounceStep(val index: Int, val fresh: Boolean) : Effect
        data object PrepareEnded : Effect                // Item 1: leave GET-READY
        data object Finished : Effect
    }

    data class TickResult(val state: EngineState, val effects: List<Effect>)

    fun stepAt(index: Int): SessionStep? = steps.getOrNull(index)

    /** Clamps rawDeltaMs to [0, MAX_TICK_DELTA_MS] (Item 6a), handles prepare phase (Item 1),
     *  accrual (capped at previous stepRemainingMs on the final tick — Item 6g),
     *  cue-point crossings, and step advance. */
    fun onTick(state: EngineState, rawDeltaMs: Long, nowEpochMs: Long): TickResult

    /** Item 6c: skipping a WORK step also skips an immediately following REST. */
    fun skip(state: EngineState, nowEpochMs: Long): TickResult

    fun extend(state: EngineState, extraMs: Long = 30_000L): EngineState

    /** Item 1: tap-to-skip — jump the prepare phase to its last 3 s. */
    fun skipPrepare(state: EngineState): EngineState =
        if (state.prepareRemainingMs > RESUME_PREPARE_MS)
            state.copy(prepareRemainingMs = RESUME_PREPARE_MS) else state

    companion object {
        const val MAX_TICK_DELTA_MS = 2_000L
        const val PREPARE_DURATION_MS = 10_000L
        const val RESUME_PREPARE_MS = 3_000L
        const val COUNTDOWN_LEAD_MS = 3_300L

        fun isActive(type: StepType): Boolean =
            type != StepType.REST && type != StepType.TRANSITION

        /** Item 6e: derive cue flags from remaining time after a snapshot restore. */
        fun cueFlagsForRestore(step: SessionStep, remainingMs: Long): CueFlags {
            val durMs = step.durationSec * 1000L
            return CueFlags(
                halfwaySpoken = remainingMs <= durMs / 2,
                countdownSpoken = remainingMs <= COUNTDOWN_LEAD_MS,
                howToSpoken = if (step.type == StepType.REST || step.type == StepType.TRANSITION)
                    remainingMs <= durMs - 1_500 else true,
            )
        }
    }
}
```

Behavioral contract of `onTick` (this replaces WorkoutSessionService.kt:192-265 logic verbatim, minus TTS/notification which become effects):
1. `delta = rawDeltaMs.coerceIn(0, MAX_TICK_DELTA_MS)`.
2. If `prepareRemainingMs > 0`: decrement it; no calories/active time; if it crossed `COUNTDOWN_LEAD_MS` and `!cues.prepareBeepsPlayed` → emit `PlayCountdownBeeps`, set flag; if it reached 0 → emit `PrepareEnded` + `AnnounceStep(stepIndex, fresh = false)` (the service decides what to say; see Item 1), reset `cues` for step 0 except `howToSpoken = true`. Return.
3. Otherwise the current WORK/REST logic, with: accrual delta capped as `accrual = min(delta, previous stepRemainingMs)` so the final tick never over-accrues (6g); halfway / countdown / how-to threshold checks emit effects instead of speaking; on `remaining <= 0` advance and emit `AnnounceStep(newIndex, fresh = false)`; when there is no next step, set `finished = true` and emit `Finished`.
4. `blockActiveMs` / `blockBounds` are maintained inside `EngineState` (immutably via `merge`-style copies) using `nowEpochMs` for bounds — this is what makes 6b snapshot-able.

Service delegation: `WorkoutSessionService` keeps a `private var engine: SessionEngine?` created in `startSession`/`restoreFromSnapshot`, stores `EngineState` inside `PlayerState` (see field additions in 6b/Item 1 — `PlayerState` becomes the projection: `stepIndex`, `stepRemainingMs`, `prepareRemainingMs`, `totalElapsedActiveMs`, `caloriesSoFar` copied from `EngineState`; the two block maps and cue flags live in a `private var engineState: EngineState` field mirrored on every tick). The fields `halfwaySpoken/countdownSpoken/howToSpoken` and the two `mutableMapOf` at lines 61-67 are **deleted** from the service. Effects are executed in `executeEffects(effects: List<Effect>)`: `PlayCountdownBeeps → voice.countdownBeeps()`, `SpeakHalfway → voice.speak("Halfway.")`, `SpeakNextHowTo → speakNextHowTo(...)`, `AnnounceStep → announceStep(...)`, `Finished → finishSession(userStopped = false)`.

**Tests** (`SessionEngineTest.kt`; build steps with the same `SessionStep(...)` literals used in ExportImportAndMiscTest.kt:25-34; no Robolectric):
- `tick delta is clamped to the max tick` — 60 s WORK step, `onTick(rawDeltaMs = 600_000)`: `stepRemainingMs == 58_000`, `caloriesSoFar` equals kcal for exactly 2 s, not 600 s.
- `final tick carries partial calories and active time into the next step` — step with 500 ms remaining, tick 2 000 ms: advances, `totalElapsedActiveMs` grew by exactly 500, calories by 0.5 s worth.
- `active time and calories never accrue on rest or transition steps` — tick through a REST step; both totals unchanged; `blockActiveMs` unchanged.
- `skipping a work step also skips the following rest` — [WORK, REST, WORK]: `skip` from index 0 lands on index 2 with `stepRemainingMs == steps[2].durationSec * 1000`.
- `skipping the last step finishes the session` — `skip` on last index → `finished == true`, effects contain `Finished`.
- `halfway cue fires once for long work steps` — 60 s WORK: tick past 30 s → one `SpeakHalfway`; further ticks → none.
- `countdown beeps fire before a work step` — REST followed by WORK, tick to ≤3 300 ms remaining → one `PlayCountdownBeeps`.
- `block active time and bounds are tracked per block index` — two WORK steps with blockIndex 0 and 1; assert map keys/values and that bounds first/last equal the `nowEpochMs` passed.
- `extend adds thirty seconds to the current step` — assert `+30_000`.
- `cue flags for restore are derived from remaining time` — three cases matching the thresholds above (Item 6e).
- `prepare phase accrues no calories or active time` — state with `prepareRemainingMs = 10_000`, tick 2 000×5 → totals 0, then `PrepareEnded` emitted.
- `prepare phase fires countdown beeps once in the last three seconds` — assert single `PlayCountdownBeeps` when crossing 3 300 ms.
- `skip during prepare jumps to the final three seconds` — `skipPrepare` from 9 000 → 3 000; from 2 000 → unchanged.

**Acceptance criteria.** All service timer behavior identical to today for a plain session (manual QA: run a 5-min FLOOR session; cues, notification seconds, calories match pre-refactor); `WorkoutSessionService` contains no arithmetic on `deltaMs` other than computing it; `./gradlew testDebugUnitTest` green.

**Dependencies.** None (do after Items 9/5/7 only for sequencing convenience).

---

## Item 1 — GET-READY lead-in (PREPARE pre-phase)

**Decision — service-level pre-phase, NOT a new `StepType.PREPARE`.** Verified reasons:
- `StepType` (Session.kt:7) is serialized into saved workouts (`SavedWorkoutEntity.json`), history (`sessionJson`), exports (ExportImport round-trip test), and snapshots. A new enum value would poison all of those for any older reader (kotlinx rejects unknown enum values even with `ignoreUnknownKeys`), and the generator/builder preview, `plan.totalSec = steps.sumOf { durationSec }` (Session.kt:45), step counters ("step 1 of N", PlayerScreen.kt:120), and `finishSession`'s block aggregation would all need PREPARE special-casing.
- A pre-phase field is one `Long` with a default: zero-calorie by construction (engine rule R.2), invisible to history/generator/export, and snapshot-compatible both directions.

**Files.**
- `service/SessionEngine.kt` (already carries `prepareRemainingMs` — Item R)
- `service/SessionState.kt` — `PlayerState` + `SessionSnapshot`
- `service/WorkoutSessionService.kt`
- `ui/screens/player/PlayerScreen.kt`

**Exact changes.**

`PlayerState` (SessionState.kt:13-24) — add:
```kotlin
val prepareRemainingMs: Long = 0,
// derived:
val inPrepare: Boolean get() = prepareRemainingMs > 0
```

`SessionSnapshot` — add (with Item 6b's fields): `val prepareRemainingMs: Long = 0`.

`WorkoutSessionService`:
- New action: `const val ACTION_SKIP_PREPARE = "au.mark.kinetiq.SKIP_PREPARE"`; `onStartCommand` branch calls `skipPrepare()` which applies `engine.skipPrepare(engineState)` and republishes state.
- `startSession(...)` (95-123): initialize `PlayerState(..., prepareRemainingMs = SessionEngine.PREPARE_DURATION_MS)`. Replace the `voice.warmUp { ... }` body with:
  ```kotlin
  voice.warmUp {
      if (settings.disclaimerAcknowledged && settings.disclaimerLineInWorkout)
          voice.speak(getString(R.string.disclaimer_workout_reminder))
      voice.speak("Starting $name. ${session.plan.steps.size} steps, about ${session.plan.totalSec / 60} minutes. Get ready — first up: ${first.exerciseName}.")
      if (voice.settings.howToDescription) speakCurrentHowTo(stateHolder.state.value ?: return@warmUp)
  }
  startTicker()   // ticker runs immediately; prepare countdown absorbs TTS warm-up latency
  ```
  The engine emits `PlayCountdownBeeps` at prepare-3 s and `PrepareEnded` at 0; on `PrepareEnded` the service calls `announceStep(fresh = false)` (name + duration + machine cue; how-to already spoken, `howToSpoken` pre-set true by the engine).
- `setPaused(false)` (350-355): before publishing, set `prepareRemainingMs = SessionEngine.RESUME_PREPARE_MS` on the engine state (bare 3-s countdown; `prepareBeepsPlayed = false` so beeps fire immediately; **no** how-to/intro speech — the only utterance stays `"Resuming."`).
- `restoreFromSnapshot` (126-149): restore `prepareRemainingMs` from snapshot but since restore lands paused, the 3-s countdown comes from the resume path above.

`PlayerScreen.kt`:
- After `val s = state ?: return` add:
  ```kotlin
  if (s.inPrepare) { PrepareView(s, context); return }
  ```
- New private composable in the same file:
  ```kotlin
  @Composable
  private fun PrepareView(s: PlayerState, context: android.content.Context)
  ```
  Content: "Get ready" (`headlineMedium`, primary), big countdown `"%d".format((s.prepareRemainingMs + 999) / 1000)` (`displayLarge`, `semantics { contentDescription = "..." }` per PlayerScreen.kt:144 pattern), first-step preview reusing the exact "Next up" card structure (PlayerScreen.kt:159-174: `ExerciseAnimationView(animationId = s.currentStep?.animationId, ...)` full-size, name, `machineCueText`), whole column wrapped in `Modifier.clickable { WorkoutSessionService.command(context, WorkoutSessionService.ACTION_SKIP_PREPARE) }` with a caption "Tap to start now" (`bodyMedium`, `onSurfaceVariant`).
- Notification (`buildNotification`, 474-517): when `state.inPrepare`, title = "Get ready", text = countdown seconds — one-line change to the `title`/`remaining` derivation.

**Tests.** Engine-level tests already listed under Item R (`prepare phase accrues no calories or active time`, `prepare phase fires countdown beeps once in the last three seconds`, `skip during prepare jumps to the final three seconds`). Add to `SessionSnapshotCompatTest.kt` (Item 6b): `snapshot round-trips prepare state`.

**Acceptance criteria.** Starting any session shows GET-READY for 10 s with first-exercise animation and spoken how-to; timer for step 1 does not run during it; calories stay 0.0 during it; 3-2-1 beeps at 3 s; tapping the screen jumps to 3 s; resume-from-pause and resume-from-snapshot give exactly a 3-s beep countdown with no speech beyond "Resuming."; a session's recorded `totalActiveSec` for a fixed plan is unchanged vs. pre-Phase-1.

**Dependencies.** Item R (engine), Item 6b (snapshot field ships in the same `SessionSnapshot` edit), Item 7 (speak API unchanged but banner interacts in Player).

---

## Item 2 — Stop confirmation + brief recovery snapshot

**Goal.** No single tap (UI or notification) can destroy a session; an accidental stop is recoverable for 10 minutes.

**Files.** `ui/screens/player/PlayerScreen.kt`, `service/WorkoutSessionService.kt`, `service/SessionState.kt`, `ui/screens/summary/SummaryScreen.kt`, `res/values/strings.xml`.

**Exact changes.**

UI confirm dialog (PlayerScreen.kt:196-199):
```kotlin
var showStopConfirm by remember { mutableStateOf(false) }
// stop button onClick = { showStopConfirm = true }
if (showStopConfirm) {
    AlertDialog(
        onDismissRequest = { showStopConfirm = false },
        title = { Text("Stop workout?") },
        text = { Text("Your progress so far will be saved to history. You can resume from the summary for the next 10 minutes.") },
        confirmButton = { TextButton(onClick = {
            showStopConfirm = false
            WorkoutSessionService.command(context, WorkoutSessionService.ACTION_STOP_CONFIRMED)
        }) { Text("Stop") } },
        dismissButton = { TextButton(onClick = { showStopConfirm = false }) { Text("Keep going") } },
    )
}
```
(`AlertDialog`/`TextButton` imports per SettingsScreen.kt:18,26 pattern.)

Two-stage notification stop (WorkoutSessionService):
- New constants: `ACTION_STOP_CONFIRMED = "au.mark.kinetiq.STOP_CONFIRMED"`, `private const STOP_ARM_WINDOW_MS = 3_000L` (companion + private).
- New fields: `private var stopArmedUntil = 0L; private var stopArmJob: Job? = null`.
- `onStartCommand`: `ACTION_STOP ->` becomes:
  ```kotlin
  ACTION_STOP -> {
      val now = SystemClock.elapsedRealtime()
      if (now < stopArmedUntil) finishSession(userStopped = true)
      else {
          stopArmedUntil = now + STOP_ARM_WINDOW_MS
          updateNotification()
          stopArmJob?.cancel()
          stopArmJob = lifecycleScope.launch { delay(STOP_ARM_WINDOW_MS); stopArmedUntil = 0L; updateNotification() }
      }
  }
  ACTION_STOP_CONFIRMED -> finishSession(userStopped = true)
  ```
- `buildNotification` line 509: label = `if (SystemClock.elapsedRealtime() < stopArmedUntil) getString(R.string.notif_action_confirm_stop) else getString(R.string.notif_action_stop)`.
- strings.xml: `<string name="notif_action_confirm_stop">Tap again to stop</string>`.

Recovery snapshot:
- Companion additions:
  ```kotlin
  fun stoppedSnapshotFile(context: Context): File = File(context.filesDir, "session_snapshot.stopped.json")
  fun hasStoppedSnapshot(context: Context): Boolean =
      stoppedSnapshotFile(context).let { it.exists() && System.currentTimeMillis() - it.lastModified() < 10 * 60 * 1000 }
  const val ACTION_RESUME_STOPPED = "au.mark.kinetiq.RESUME_STOPPED"
  fun resumeStopped(context: Context) { /* startForegroundService with ACTION_RESUME_STOPPED */ }
  ```
- In `finishSession(userStopped = true)` path (inside the Item-6f try/finally, before `deleteSnapshot`): write the **current** state synchronously to `stoppedSnapshotFile` using the same `SessionSnapshot` encoding as `maybeSnapshot` (446-470). Then delete the live snapshot as today.
- `restoreFromSnapshot(fromStopped: Boolean = false)`: reads the stopped file when `fromStopped`, deletes it after a successful restore; `ACTION_RESUME_SNAPSHOT` keeps `false`, `ACTION_RESUME_STOPPED` passes `true`.
- `CompletedSummary` (SessionState.kt:31-42): add `val stoppedEarly: Boolean = false` (set from `userStopped` in `finishSession`).

SummaryScreen: when `s.stoppedEarly && WorkoutSessionService.hasStoppedSnapshot(context)`, show above "Done":
```kotlin
OutlinedButton(onClick = { viewModel.resumeStopped(context); onResume() }, modifier = Modifier.fillMaxWidth()) {
    Text("Stopped by accident? Resume workout")
}
```
`SummaryViewModel.resumeStopped(context: Context)`: `viewModelScope.launch { workoutRepository.deleteHistory(summary.historyId); stateHolder.clearCompleted(); WorkoutSessionService.resumeStopped(context) }` — deleting the history row prevents a duplicate entry when the session finishes a second time. `SummaryScreen` gains parameter `onResume: () -> Unit` wired in `KinetiqNavHost` to `navController.navigate(Routes.PLAYER) { popUpTo(Routes.HOME) }`.

**Tests.**
- `SessionSnapshotCompatTest.kt`: `stopped snapshot serializes the same schema as the live snapshot` — encode a `SessionSnapshot`, decode, field-by-field equality (guards the recovery path against schema drift).
- Two-stage arming logic: extract `internal fun stopArmDecision(nowMs: Long, armedUntil: Long): StopDecision` (enum `ARM`/`FINISH`) into the service file (top-level, no Android deps) and test in `SessionEngineTest.kt`: `first stop arms and second stop within the window finishes` (asserts ARM at t=0, FINISH at t=2 999, ARM again at t=3 001).

**Acceptance criteria.** Player stop always shows a dialog; notification Stop never ends a session on first tap and the action label visibly changes to "Tap again to stop" for 3 s; after a confirmed stop the Summary shows a resume button for ≤10 min which restores the session paused at the stopped step with block accounting intact (needs 6b) and leaves exactly one history row after final completion.

**Dependencies.** Item 3 (`CompletedSummary` edits land together), Item 6b (snapshot carries block maps so the resumed session's HC blocks are right), Item 6f (finishSession structure).

---

## Item 3 — Summary reachability + stale-summary race

**Goal.** The summary is always reachable after a session ends regardless of which screen is open; a stale `lastCompleted` can never hijack a newly starting session.

**Root causes (verified).** `SummaryScreen` clears `lastCompleted` only via the "Done" button (SummaryScreen.kt:49-51,116-118) — leaving via the permanent bottom bar (KinetiqNavHost.kt:110) leaks it. `PlayerScreen`'s effect (96-106) then routes `state == null && completed != null` → `onFinished()`, so opening the player for a *new* session before the service publishes state jumps to the *old* summary.

**Files.** `service/SessionState.kt`, `service/WorkoutSessionService.kt`, `ui/nav/KinetiqNavHost.kt`, `ui/screens/player/PlayerScreen.kt`, `ui/screens/home/HomeScreen.kt`, `ui/screens/summary/SummaryScreen.kt`.

**Exact changes.**

Session identity:
- `PlayerState`: add `val sessionId: String = ""`.
- `CompletedSummary`: add `val sessionId: String` (no default — every producer must stamp it).
- `SessionSnapshot`: add `val sessionId: String = ""` (restore reuses it; empty → generate fresh).
- `WorkoutSessionService.startSession`: `val sessionId = java.util.UUID.randomUUID().toString()`; also call `stateHolder.clearCompleted()` as the first statement — a starting session invalidates any unviewed old summary. `finishSession` copies `state.sessionId` into the summary.

Global navigation (KinetiqApp, KinetiqNavHost.kt:85-97 — mirror the existing `pendingLaunch` effect):
```kotlin
val lastCompleted by mainViewModel.sessionStateHolder.lastCompleted.collectAsState()
var consumedSummaryId by rememberSaveable { mutableStateOf<String?>(null) }
LaunchedEffect(lastCompleted) {
    val c = lastCompleted ?: return@LaunchedEffect
    if (c.sessionId != consumedSummaryId) {
        consumedSummaryId = c.sessionId
        navController.navigate(Routes.SUMMARY) { launchSingleTop = true; popUpTo(Routes.HOME) }
    }
}
```
Navigate-once-per-sessionId means the user can leave Summary via the bottom bar without being yanked back, and a re-published summary (HC retry, Item 8) does not re-navigate.

PlayerScreen simplification: **delete** the `onFinished` parameter and the `completed` collection (lines 77, 96-106 collapse to):
```kotlin
LaunchedEffect(state) {
    if (state == null) {
        kotlinx.coroutines.delay(1500)
        if (viewModel.stateHolder.state.value == null) onExit()
    }
}
```
(The global effect owns summary navigation; the hijack path is structurally gone.) Update `KinetiqNavHost` composable(Routes.PLAYER) accordingly.

Home card: `HomeScreen` gains `onOpenSummary: () -> Unit` (NavHost: `navController.navigate(Routes.SUMMARY)`). In the LazyColumn after the "in progress / resume" items:
```kotlin
val lastCompleted by viewModel.sessionStateHolder.lastCompleted.collectAsState()
if (lastCompleted != null && playerState == null) {
    item {
        OutlinedButton(onClick = onOpenSummary, modifier = Modifier.fillMaxWidth()) {
            Text("View last session summary: ${lastCompleted!!.name}")
        }
    }
}
```
Clearing rules (final): cleared by "Done" (existing), by `startSession` (new). NOT cleared on dispose — the Home card is the parking spot.

**Tests.** `SessionEngineTest.kt` addition (pure logic extracted as `internal fun shouldNavigateToSummary(summaryId: String?, consumedId: String?): Boolean` in KinetiqNavHost.kt): `summary navigation fires once per session id` — asserts true for new id, false for same id, true again for a different id, false for null.

**Acceptance criteria.** Finish a session while sitting on History → app navigates to Summary. Leave Summary via bottom bar → Home shows "View last session summary" and tapping it reopens the same summary. Start a new workout while an unviewed summary exists → Player shows GET-READY (never the old summary), and Home card disappears. "Done" still pops to Home with `lastCompleted == null`.

**Dependencies.** None hard; ships before Item 2 (which extends `CompletedSummary` again) — do both edits to `CompletedSummary` in one commit if convenient.

---

## Item 4 — Widget/start guards + widget staleness

**Files.** `MainActivity.kt`, `service/WorkoutSessionService.kt`, `widget/KinetiqWidget.kt` (import only), `ui/screens/home/HomeScreen.kt` (no change needed — covered by service guard).

**Exact changes.**
1. `MainViewModel.repeatLastWorkout` (MainActivity.kt:43-54) — first lines:
   ```kotlin
   fun repeatLastWorkout(context: android.content.Context) {
       if (sessionStateHolder.state.value != null) {   // session live: just navigate
           pendingPlayerLaunch.value = true
           return
       }
       viewModelScope.launch { ... existing body ... }
   }
   ```
   (The existing `pendingLaunch && playerState != null` effect at KinetiqNavHost.kt:92-97 then routes to the player.)
2. Service `ACTION_START` guard (WorkoutSessionService.kt:78-82):
   ```kotlin
   ACTION_START -> {
       if (stateHolder.state.value?.finished == false) {
           updateNotification()   // refresh; do NOT clobber the live session
       } else {
           val payload = ...; if (payload != null) startSession(...)
       }
   }
   ```
3. Widget staleness: in `finishSession`, inside the coroutine after `stateHolder.completed(...)` (and inside the Item-6f `finally`-protected happy path):
   ```kotlin
   runCatching { au.mark.kinetiq.widget.KinetiqWidget().updateAll(this@WorkoutSessionService) }
   ```
   using `androidx.glance.appwidget.updateAll` (suspend extension; already on classpath via `libs.androidx.glance.appwidget`). This refreshes "Repeat: {name}" and the streak right after every session.

**Tests.** Guard logic is two `if`s around Android components; cover via extraction: `internal fun shouldIgnoreStart(currentFinished: Boolean?): Boolean = currentFinished == false` in WorkoutSessionService.kt, asserted in `SessionEngineTest.kt` as `a start command is ignored while a session is live` (true for `false`, false for `null` and `true`). Manual QA item below.

**Acceptance criteria.** With a session running: widget tap opens the app on the Player with the running session untouched (same stepIndex); sending a raw `ACTION_START` intent does not reset state. After finishing a first-ever session, the widget shows "Repeat: {name}" and streak ≥ 1 without waiting for a system-scheduled update.

**Dependencies.** Item 6f (finishSession restructure) merged first so the `updateAll` call has a stable home.

---

## Item 5 — Generator time-math

**Files.** `domain/generator/WorkoutGenerator.kt`, NEW `app/src/test/java/au/mark/kinetiq/WorkoutGeneratorTimeBudgetTest.kt`, `WorkoutGeneratorTest.kt` (one assertion update if `machine blocks use named routines scaled to the block` tightens — keep, it will now pass at ±5%).

**Exact changes.**

New shared helper (internal for tests):
```kotlin
/** Iterative proportional redistribution: scale `initial` toward targetSec, clamping each
 *  element into [minOf(i), maxOf(i)]; re-spread the residual over unclamped elements;
 *  ≤ 6 passes or until |residual| ≤ 2 s. Returns durations summing as close to targetSec
 *  as the bounds allow. */
internal fun redistribute(
    initial: List<Int>, minBound: (Int) -> Int, maxBound: (Int) -> Int, targetSec: Int,
): List<Int>
```

New field on the warning type (WorkoutGenerator.kt:26-30):
```kotlin
data class GeneratorWarning(
    val message: String,
    val fixLabel: String? = null,
    val fixedConfig: GeneratorConfig? = null,
    val plannedTotalSec: Int? = null,   // set whenever the plan intentionally deviates from request
)
```
(Additive with default — BuilderScreen warning cards need no change.)

**(a) Machine routine renormalization + routine-fit guard** (326-344):
- Add to the `fitting` filter (314-320): `&& blockSec.toDouble() / r.totalSec in 0.5..2.0` — grossly mismatched routines fall through to the existing segment-assembly fallback (348-375).
- Replace the per-step `max(ex.minSec, min(ex.maxSec * 3, (s.durationSec * scale).roundToInt()))` with: compute `scaled = routine.steps.map { (it.durationSec * scale).roundToInt() }` then `redistribute(scaled, min = { i -> byId[...].minSec }, max = { i -> byId[...].maxSec * 3 }, targetSec = blockSec)`.

**(b)+(c) Discrete block budget** (204-254): replace the solver with a fixpoint + redistribution:
```kotlin
// 1. fixpoint on (work, rest) honoring the rest clamp:
var w = solveWorkSec(blockSec, count, ratio)
repeat(4) {
    val r = (w / ratio).roundToInt().coerceIn(10, 90)
    val w2 = if (count > 1) (blockSec - (count - 1) * r) / count else blockSec
    if (w2 == w) return@repeat else w = max(15, w2)
}
val rest = (w / ratio).roundToInt().coerceIn(10, 90)
// 2. per-exercise clamp then redistribute the residual work budget:
val workBudget = blockSec - (count - 1) * rest
val works = redistribute(List(count) { w }, min = { picked[it].minSec }, max = { picked[it].maxSec }, targetSec = workBudget)
```
Emit rests of `rest` seconds between steps (unchanged structurally). If `abs(works.sum() + (count-1)*rest - blockSec) > blockSec / 20` (all steps clamped), add:
```kotlin
GeneratorWarning(
    message = "Exercise time limits cap this block at ${actual/60} min ${actual%60}s of the ${blockSec/60} min planned.",
    fixLabel = "Use $betterCount exercises",
    fixedConfig = config.copy(exercisesPerCategory = betterCount),   // betterCount = countFor(blockSec, 40, ratio)
    plannedTotalSec = null,
)
```
The existing `workSec < 20` warning (211-218) stays, but `workSec = max(workSec, 15)` is removed (redistribution owns the budget now).

**(d) Explicit mainSec-floor warning** (89-96): compute the inflated plan total and say it:
```kotlin
val flooredMain = max(mainSec, 4 * 60 * categories.size)
val plannedTotal = flooredMain + warmupSec + cooldownSec + transitionsSec
warnings += GeneratorWarning(
    message = "That leaves under 5 minutes of work per category, so this plan will run about ${(plannedTotal + 30) / 60} min instead of the ${config.totalDurationMin} min requested. Consider a longer session or fewer categories.",
    fixLabel = "Set $fixMin min",
    fixedConfig = config.copy(totalDurationMin = fixMin),
    plannedTotalSec = plannedTotal,
)
mainSec = flooredMain
```

**(e) Converging one-tap fix**: new internal function used for `fixMin` above:
```kotlin
/** Smallest whole-minute duration whose derived warm-up/cool-down/transition slices still
 *  leave >= 5 min of main time per category. Iterates because warmCoolSlice depends on total. */
internal fun minViableDurationMin(config: GeneratorConfig, categoryCount: Int): Int {
    var m = config.totalDurationMin
    repeat(30) {
        val total = m * 60
        val wc = (if (config.warmup) warmCoolSlice(total) else 0) + (if (config.cooldown) warmCoolSlice(total) else 0)
        val trans = if (categoryCount > 1) (categoryCount - 1) * config.transitionSec else 0
        if (total - wc - trans >= 5 * 60 * categoryCount) return m
        m += 1
    }
    return m
}
```

**(f) High-adiposity duplication fix** (227-231): delete the per-index substitution. After `pickBalanced`, reorder instead:
```kotlin
val ordered = if (highAdiposity) {
    val (veryHigh, rest) = picked.partition { it.intensity == Intensity.VERY_HIGH }
    rest + veryHigh          // VERY_HIGH pushed to the back half; list is a permutation — no duplicates
} else picked
ordered.forEachIndexed { i, ex -> ... }   // `effective` variable removed
```

**Tests** (`WorkoutGeneratorTimeBudgetTest.kt`, same `@Before` setup as WorkoutGeneratorTest.kt:24-32):
- `generated plans hit the requested duration within 5 percent across the config matrix` — for `durations = [5,10,15,30,60]` × `categorySets = [[FLOOR],[SPIN],[FLOOR,SPIN],[FLOOR,REFORMER,SPIN]]` × `ratios = [0.5f,2f,6f]` × `perCat = [null,3,10]`, seeded `Random(7)`: assert `plan.totalSec` within ±5% of `min*60` **or** a warning exists with `plannedTotalSec != null` and `plan.totalSec` within ±5% of that `plannedTotalSec`.
- `over-budget plans always carry an explicit duration warning with the planned total` — 5 min × 3 categories: warning present, `plannedTotalSec == plan.totalSec ± 5%`, message contains "instead of the 5 min requested".
- `machine routine scaling renormalizes after per-step clamping` — SPIN-only, 8 min and 45 min, warmup/cooldown off: `plan.totalSec` within ±5% of request.
- `routines that cannot fit the block are rejected in favor of segment assembly` — SPIN-only 6 min (blockSec far below every routine ≥4 min×2 guard): `blocks.single().routineName` may be null; total still within ±5%.
- `discrete block redistributes clamped work time to stay on budget` — FLOOR, `exercisesPerCategory = 3`, 30 min, no warmup/cooldown: total within ±5% or capped-block warning present.
- `rest clamp keeps block time on budget at extreme ratios` — FLOOR at ratio 0.5 and 6, 15 min: within ±5%.
- `high adiposity reordering never duplicates exercises` — the Item-(f) scenario from WorkoutGeneratorTest.kt:142-160 plus `assertThat(work.mapNotNull { it.exerciseId }).containsNoDuplicates()` across 10 seeds.
- `one tap duration fix converges` — take the 5-min/3-category warning's `fixedConfig`, regenerate, assert the under-5-minutes warning is absent and total within ±5%.
- `redistribute respects bounds and target` — direct unit test of the helper: initial [40,40,40], bounds [20..45,20..200,20..200], target 150 → sums to 150, each in bounds.

**Acceptance criteria.** All existing WorkoutGeneratorTest tests still pass unmodified (the high-adiposity first-half assertion at :156-158 must still hold with the partition approach against the real DB); the full matrix test passes; `machine blocks use named routines scaled to the block` (existing ±20/25% tolerance) passes trivially.

**Dependencies.** None. Must land before Item 6h (both edit generator/finishSession territory) to avoid merge churn.

---

## Item 6 — Service correctness fixes

**Files.** `service/SessionEngine.kt` (a, c, e, g via engine — see Item R), `service/WorkoutSessionService.kt` (b, d, e, f, h), `service/SessionState.kt` (b), `domain/generator/WorkoutGenerator.kt` (h), `voice/VoiceCoach.kt` (d — one call-order note), tests in `SessionEngineTest.kt` + NEW `SessionSnapshotCompatTest.kt`.

**(a) Clamp tick delta.** In engine: `delta = rawDeltaMs.coerceIn(0L, MAX_TICK_DELTA_MS)` (2 000 ms = 10× nominal tick). Rationale documented in KDoc: after doze/suspension the workout must not silently fast-forward steps nor bill minutes of calories at the current step's MET; the wall-clock gap is dropped. Test: `tick delta is clamped to the max tick` (Item R).

**(b) Snapshot completeness + compat.** `SessionSnapshot` (SessionState.kt:58-69) gains, all defaulted:
```kotlin
val blockActiveMs: Map<Int, Long> = emptyMap(),
val blockBounds: Map<Int, List<Long>> = emptyMap(),   // [startEpochMs, endEpochMs]; List not Pair for stable JSON shape
val prepareRemainingMs: Long = 0,                     // Item 1
val sessionId: String = "",                           // Item 3
```
`maybeSnapshot` writes them from the engine state (`blockBounds.mapValues { listOf(it.value.first, it.value.second) }`); `restoreFromSnapshot` rebuilds `EngineState.blockActiveMs/blockBounds` from them. Backward compat: kotlinx defaults cover old→new; `ignoreUnknownKeys = true` covers new→old (no enum additions anywhere — deliberate, see Item 1). Tests in `SessionSnapshotCompatTest.kt`:
- `legacy snapshot json without new fields still decodes` — hardcoded JSON string containing exactly the nine pre-Phase-1 fields; decode with `Json { ignoreUnknownKeys = true; encodeDefaults = true }`; assert `blockActiveMs` empty, `sessionId` empty, other fields intact.
- `snapshot round-trips block accounting and prepare state` — encode/decode with populated maps; equality.

**(c) Skip WORK skips trailing REST.** Engine `skip`: if `steps[stepIndex].type == StepType.WORK && steps[stepIndex+1]?.type == StepType.REST` advance by 2 else 1; if past the end → finished. Service `skipStep` (357-361) becomes `engine.skip(...)` + `voice.stopSpeaking()` + effects. Test: `skipping a work step also skips the following rest`, `skipping the last step finishes the session` (Item R).

**(d) TTS staleness on natural advance.** `advanceStep`-equivalent path in the service: before executing `AnnounceStep(fresh = false)` effects, call `voice.stopSpeaking()` (exactly what `skipStep` does today at :359) so a long how-to never delays the next exercise's name. Utterance order inside `announceStep` (275-308) is already name → machine cue → how-to and how-to is `fresh`-gated — keep. Also fix the flush race at VoiceCoach.kt:113-114: replace `if (flush) utteranceCount.set(1)` with `if (flush) utteranceCount.set(0)` placed **before** `engine.speak(...)`, followed by the existing `incrementAndGet()` (move the increment after the mode computation); combined with the clamp in Item 7 this prevents a stale `onDone` from abandoning focus mid-utterance. Acceptance: starting step N+1 while the how-to of step N is still speaking cuts it off within ~200 ms and speaks the new name first (manual QA + engine effect-ordering test `step advance emits announce effect after accrual`).

**(e) Re-announce + cue flags on restore.** `restoreFromSnapshot`: replace `resetStepCues()` with `cues = SessionEngine.cueFlagsForRestore(step, snap.stepRemainingMs)`; set `private var pendingRestoreAnnounce = true`. `setPaused(false)`: after `voice.speak("Resuming.", flush = true)`, if `pendingRestoreAnnounce` → `pendingRestoreAnnounce = false; announceStep(fresh = false)` (user hears which exercise they're resuming into, after the Item-1 3-s countdown). Test: `cue flags for restore are derived from remaining time` (Item R).

**(f) finishSession containment** (370-441). Restructure the coroutine body:
```kotlin
lifecycleScope.launch {
    var summaryPublished = false
    try {
        val settings = settingsRepo.current()
        ...build blocks...
        val hcResult = if (shouldWrite) runCatching { healthConnect.writeSession(...) }.getOrElse { Result.failure(it) } else null
        val historyId = runCatching { workoutRepo.addHistory(...) }.getOrElse { -1L }
        stateHolder.completed(CompletedSummary(...))          // always publish — even if HC/DB failed
        summaryPublished = true
        runCatching { au.mark.kinetiq.widget.KinetiqWidget().updateAll(this@WorkoutSessionService) }  // Item 4
    } finally {
        if (userStopped) runCatching { writeStoppedSnapshot() }   // Item 2
        deleteSnapshot(this@WorkoutSessionService)
        stateHolder.update(null)
        stopSelf()
    }
}
```
Guarantees: the service always stops, live state always clears, the snapshot is always removed (so Home never offers to "resume" a finished session), and a HC/Room exception surfaces as `healthConnectError`/`historyId == -1` instead of a zombie foreground service. `voice.speak(...)` and `tickerJob?.cancel()` stay before the launch as today.

**(g) Final-tick accounting.** Engine rule 3 (Item R): the last partial delta of a step contributes `min(delta, previousRemaining)` to `totalElapsedActiveMs`, `caloriesSoFar`, and `blockActiveMs` — today the calories are carried (`carryCalories`, :233) but active milliseconds are dropped and `blockActiveMs` over-accrues by the overshoot. Test: `final tick carries partial calories and active time into the next step` (Item R).

**(h) Block MET weighting + warm-up/cool-down isolation.**
- `WorkoutGenerator`: `warmupSteps(..., blockIndex = 0)` (:107) → `blockIndex = WARMUP_BLOCK_INDEX`; `cooldownSteps(..., blockIndex = categories.size - 1)` (:135) → `COOLDOWN_BLOCK_INDEX`; companion constants `const val WARMUP_BLOCK_INDEX = -1; const val COOLDOWN_BLOCK_INDEX = -2`.
- `finishSession` MET (:390-391): duration-weighted —
  ```kotlin
  val metSteps = plan.steps.filter { it.blockIndex == index && it.type != StepType.REST && it.type != StepType.TRANSITION }
  val met = metSteps.sumOf { it.met.toDouble() * it.durationSec } / metSteps.sumOf { it.durationSec }.coerceAtLeast(1)
  ```
  (fallback 3.0 when `metSteps` empty, as today).
- Because `plan.blocks.mapIndexedNotNull` looks up `blockActiveMs[index]` for index ∈ 0..n-1, entries under −1/−2 are automatically excluded from block records — no other change needed.
- **Health Connect implication (must go in code comment + this spec):** per-block `ExerciseSessionRecord`s no longer include warm-up/cool-down minutes, so their summed duration is less than `totalActiveSec`; the session-spanning `TotalCaloriesBurnedRecord` still covers everything (warm-up/cool-down calories continue to accrue into `caloriesSoFar` per-tick). Old saved workouts serialized with warm-up `blockIndex = 0` keep the old (polluted) behavior — acceptable, documented.
- Tests: in `WorkoutGeneratorTimeBudgetTest.kt` add `warmup and cooldown steps carry sentinel block indices` (generate 30-min FLOOR with warmup+cooldown; assert every WARMUP step has `blockIndex == -1`, COOLDOWN `== -2`, and no WORK/REST step has a negative index). In `SessionEngineTest.kt`: `block met aggregation excludes sentinel indices` if the aggregation helper is extracted as `internal fun completedBlocks(plan: WorkoutPlan, blockActiveMs: Map<Int,Long>, blockBounds: Map<Int, Pair<Long,Long>>, weightKg: Double, fallbackBounds: Pair<Long,Long>): List<CompletedBlock>` — **do extract it** (pure function, top-level in SessionEngine.kt) so 6h and 6g are testable; `finishSession` calls it. Additional test: `completed block met is duration weighted` — two WORK steps in block 0 with (met 8, 60 s) and (met 2, 540 s): expected MET 2.6, not 5.0.

**Acceptance criteria (Item 6 overall).** Kill the app mid-session (adb `am kill`), reopen, resume: correct step announced, halfway cue not repeated if already past halfway, per-block HC record durations match pre-kill accrual within one tick. Skip on a WORK step lands on the next WORK step. Leaving the phone in doze for 10 min mid-workout advances the timer by ≤2 s per received tick (no step teleporting). Simulated HC failure (permission revoked mid-session) still yields a Summary with the error string and a stopped service.

**Dependencies.** Item R (engine), Item 7 (voice call sites), Item 5 (generator file collision for 6h).

---

## Item 7 — VoiceCoach reliability

**Files.** `voice/VoiceCoach.kt`, `ui/screens/player/PlayerScreen.kt`, `ui/screens/settings/SettingsScreen.kt`, NEW `app/src/test/java/au/mark/kinetiq/VoiceCoachStatusTest.kt`.

**Exact changes** (VoiceCoach.kt:35-62, 83-122):
```kotlin
enum class TtsStatus { IDLE, INITIALIZING, READY, FAILED }   // top-level in VoiceCoach.kt

// fields
private val _status = MutableStateFlow(TtsStatus.IDLE)
val status: StateFlow<TtsStatus> = _status.asStateFlow()
private var initAttempts = 0
// companion: private const val MAX_PENDING_UTTERANCES = 16, private const val MAX_INIT_ATTEMPTS = 2
```
- `warmUp(onReady)`: set `_status.value = TtsStatus.INITIALIZING` before constructing TTS; bound the queue — `if (pendingOnReady.size >= MAX_PENDING_UTTERANCES) pendingOnReady.removeAt(0)` before adding. Init callback failure branch (currently missing — status != SUCCESS silently leaves `ready=false` forever, VoiceCoach.kt:53-61):
  ```kotlin
  else {
      tts?.shutdown(); tts = null
      if (++initAttempts < MAX_INIT_ATTEMPTS) {
          scope.launch { delay(2_000); warmUp() }      // retry once
      } else {
          _status.value = TtsStatus.FAILED
          pendingOnReady.forEach { it() }               // run callbacks so session flow (ticker already started) proceeds silently
          pendingOnReady.clear()
      }
  }
  ```
  Success branch additionally sets `_status.value = TtsStatus.READY; initAttempts = 0`.
- `speak(...)`: first line `if (_status.value == TtsStatus.FAILED) return` (drop silently; no re-warm loop). The `!ready` enqueue path uses the same bounded add.
- New `fun retryInit() { initAttempts = 0; _status.value = TtsStatus.IDLE; shutdown-lite (tts=null, ready=false); warmUp() }` for the banner button.
- Utterance clamp (:87-97): replace all three `decrementAndGet()` with a shared `private fun onUtteranceFinished() { if (utteranceCount.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) abandonFocus() }`; `stopSpeaking` keeps `set(0)`; flush fix per Item 6d.

UI banner (shared shape, inline text per codebase convention):
- `PlayerViewModel`: add `val voiceStatus = voice.status` (make `voice` accessible: it is already injected; expose the flow). PlayerScreen, directly under the progress bar: when `voiceStatus == TtsStatus.FAILED`, a `Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer))` with `Text("Voice coach unavailable — cues are muted. Timers still run.")` and a `TextButton("Retry") { viewModel.retryVoice() }` (`PlayerViewModel.retryVoice() = voice.retryInit()`).
- `SettingsViewModel`: `val voiceStatus = voiceCoach.status`; `testVoice()` becomes:
  ```kotlin
  fun testVoice() {
      voiceCoach.settings = settings.value.voice
      voiceCoach.warmUp { voiceCoach.speak("G'day! ...") }
      viewModelScope.launch {
          delay(3_000)
          ioMessage.value = if (voiceCoach.status.value == TtsStatus.FAILED)
              "Voice engine failed to start. Cues will be silent — open System TTS settings below."
          else "Voice test played. If you heard nothing, check media volume and the offline voice data."
      }
  }
  ```
  (reuses the existing `ioMessage` display slot at SettingsScreen.kt:301). Under the "Test voice" button show the same FAILED banner text when `voiceStatus == FAILED`.

**Tests** (`VoiceCoachStatusTest.kt`, `@RunWith(RobolectricTestRunner::class)` — Robolectric's `ShadowTextToSpeech` delivers the init callback):
- `status reaches ready after successful init` — construct `VoiceCoach(ApplicationProvider.getApplicationContext())`, `warmUp()`, drive the shadow init success, assert `status.value == TtsStatus.READY`.
- `pending utterance queue is bounded` — before init completes, call `speak("x$i")` 40 times; use reflection-free observable: after init success the shadow's spoken-text log size is ≤ 16 (assert via `ShadowTextToSpeech` last-spoken tracking, or expose `internal val pendingCount: Int get() = pendingOnReady.size` and assert `<= 16` pre-init).
- `failed init after retry surfaces FAILED and drops queued speech` — drive init failure twice; assert `status.value == FAILED` and a subsequent `speak` does not throw and leaves status FAILED.
- `utterance counter never goes negative` — expose `internal fun utteranceCountForTest(): Int`; call `onUtteranceFinished` (via listener `onDone("id")`) more times than increments; assert `utteranceCountForTest() == 0`.
(If `ShadowTextToSpeech` in the pinned Robolectric version lacks init-callback control, fall back to extracting the status/attempt decision into `internal fun nextInitAction(status: Int, attempts: Int): InitAction` (enum RETRY/FAIL/READY) and unit-test that pure function — the implementer must pick whichever compiles, keeping all four behaviors asserted.)

**Acceptance criteria.** With TTS engine disabled on-device: session still runs (timer, beeps unaffected — ToneGenerator path is independent), Player shows the muted banner, Settings "Test voice" prints a visible failure message, and exactly one retry attempt happened. With TTS healthy: zero behavior change.

**Dependencies.** None; land before Items R/6 so their speak paths compile against the final API.

---

## Item 8 — Health Connect write-back hardening

**Files.** `health/HealthConnectManager.kt`, `data/repo/WorkoutRepository.kt` (no change — `markHcWritten` at :88 finally gets callers), `ui/screens/summary/SummaryScreen.kt`, `ui/screens/history/HistoryScreen.kt`, `service/WorkoutSessionService.kt` (pass-through only).

**Exact changes.**

`HealthConnectManager.writeSession` (:109-148):
- Signature unchanged. Body changes:
  1. Pre-check: after obtaining the client, `if (!hasWritePermissions()) error("Health Connect write permission not granted — grant it from the Health screen")`.
  2. Per-record zone offsets: add `private fun offsetAt(epochMs: Long): ZoneOffset = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(epochMs))`; use `offsetAt(block.startedAtEpochMs)` / `offsetAt(block.endedAtEpochMs)` for each ExerciseSessionRecord and `offsetAt(startEpochMs)` / `offsetAt(endEpochMs)` for the calories record (replaces the single `zone` at :119 — correct across DST transitions mid-session).
  3. Metadata + upsert-safe retries: replace both `Metadata.manualEntry()` (:128, :143) with recording-method-correct, id-stamped metadata:
     ```kotlin
     import androidx.health.connect.client.records.metadata.Device
     val device = Device(type = Device.TYPE_PHONE)
     // per block (i = index in the filtered block list):
     metadata = Metadata.autoRecordedWithId(clientRecordId = "kinetiq-$startEpochMs-block-$i", device = device)
     // calories:
     metadata = Metadata.autoRecordedWithId(clientRecordId = "kinetiq-$startEpochMs-kcal", device = device)
     ```
     `autoRecorded` is correct (the app records the session live, the user does not type values), and a stable `clientRecordId` makes `insertRecords` an upsert in HC client 1.1.0 — a retry after a partially-failed write cannot duplicate records. (Implementer: the 1.1.0 factory set is `manualEntry()/manualEntryWithId()/autoRecorded(Device)/autoRecordedWithId(id, Device)/unknownRecordingMethod()` — verify names compile; the intent is autoRecorded + clientRecordId.)

Wire up `markHcWritten` (dead at WorkoutRepository.kt:88):
- `SummaryViewModel` add:
  ```kotlin
  fun retryHealthConnect() {
      val s = stateHolder.lastCompleted.value ?: return
      viewModelScope.launch {
          val result = healthConnect.writeSession(s.name, s.blocks, s.calories, s.startedAtEpochMs, s.endedAtEpochMs)
          if (result.isSuccess && s.historyId > 0) workoutRepository.markHcWritten(s.historyId)
          stateHolder.completed(s.copy(
              healthConnectWritten = result.isSuccess,
              healthConnectError = result.exceptionOrNull()?.message,
          ))
      }
  }
  ```
  (inject `healthConnect: HealthConnectManager`; the `stateHolder.completed` republish updates the screen — the Item-3 navigate-once guard keys on `sessionId`, so no spurious navigation.) SummaryScreen Health Connect section (:92-100): when `!s.healthConnectWritten && s.healthConnectError != null`, add `OutlinedButton(onClick = viewModel::retryHealthConnect) { Text("Retry Health Connect write") }`.
- `HistoryViewModel` add (inject `HealthConnectManager` + `SettingsRepository` already present):
  ```kotlin
  fun retryHcWrite(entry: HistoryEntry) {
      viewModelScope.launch {
          val result = healthConnect.writeSession(entry.name, entry.blocks, entry.calories, entry.startedAtEpochMs, entry.endedAtEpochMs)
          if (result.isSuccess) workoutRepository.markHcWritten(entry.id)
      }
  }
  ```
  HistoryScreen row (:131-149): next to the delete IconButton, when `!entry.healthConnectWritten && entry.blocks.isNotEmpty()` and settings.healthConnectEnabled (collect settings in the ViewModel state — add `hcEnabled: Boolean` to `HistoryUiState`), show `IconButton(onClick = { viewModel.retryHcWrite(entry) }) { Icon(Icons.Filled.Sync, contentDescription = "Write ${entry.name} to Health Connect") }` (`Icons.Filled.Sync` from material-icons-extended, already a dependency). The row refreshes automatically because `history()` is a Flow and `markHcWritten` updates the row.

**Tests.** HC client calls are device-bound; test the pure parts:
- In `SessionEngineTest.kt` (or a small `HealthConnectIdTest.kt`): `client record ids are deterministic per session and record` — extract `internal fun clientRecordIdFor(startEpochMs: Long, kind: String, index: Int = -1): String` in HealthConnectManager.kt; assert `clientRecordIdFor(123, "block", 0) == "kinetiq-123-block-0"`, `clientRecordIdFor(123, "kcal") == "kinetiq-123-kcal"`, and same inputs → same output.
- `zone offset is computed per timestamp` — `offsetAt` made `internal`; with `TimeZone` fixed to a DST zone (e.g. `Australia/Sydney`) via `java.util.TimeZone.setDefault` in the test, assert offsets differ for an epoch pair straddling the October DST switch.

**Acceptance criteria.** Revoking HC write permission then finishing a session yields Summary text "Could not write… write permission not granted…" plus a Retry button; granting permission and tapping Retry flips the section to "✓ Session written…" and History gains "HC ✓" on that row without duplicated HC records (verify in the Health Connect app: one session record per block, one calories record). History rows written before this change with `HC ✗` show the sync icon and can be back-filled.

**Dependencies.** Item 6f/6h (finishSession/block shape final), Item 3 (summary republish semantics).

---

## Item 9 — DatabaseValidator bounds

**Files.** `data/DatabaseValidator.kt`, `app/src/test/java/au/mark/kinetiq/DatabaseValidatorTest.kt`.

**Exact changes.**
- :85 `if (ref.year !in 1950..2026)` → `if (ref.year !in 1950..(java.time.Year.now().value + 1))` (allow in-press citations one year ahead; no more annual code edits).
- :102 cadence asymmetry `cue.cadenceRpmLow !in 40..130 || cue.cadenceRpmHigh !in 40..140` → symmetric `cue.cadenceRpmLow !in 40..140 || cue.cadenceRpmHigh !in 40..140` (the `low > high` check on the same line already enforces ordering).

**Tests** (append to DatabaseValidatorTest.kt, using its existing synthetic-exercise builder style):
- `reference year bound tracks the current year` — an exercise with a reference dated `Year.now().value + 1` passes; `Year.now().value + 2` produces a problem containing "implausible year".
- `cadence bounds are symmetric` — a spin cue with `cadenceRpmLow = 135, cadenceRpmHigh = 138` passes; `low = 30` fails.

**Acceptance criteria.** `bundled database passes full validation` still green; the two new tests green; no content changes to `exercise_db.json`.

**Dependencies.** None.

---

## Migration / compatibility summary

- **Room:** zero schema changes; no migration needed.
- **Snapshot JSON (`session_snapshot.json`):** additive fields with defaults (`blockActiveMs`, `blockBounds`, `prepareRemainingMs`, `sessionId`) — old snapshots decode (kotlinx defaults), new snapshots decode on old builds (`ignoreUnknownKeys`). New file `session_snapshot.stopped.json` is self-contained with a 10-min validity window.
- **`GeneratedSession`/`SessionStep`/export format:** untouched except `blockIndex` sentinel values (−1/−2), which are plain Ints — fully compatible; old saved plans keep old aggregation behavior (documented in 6h).
- **No new `StepType` enum value** — deliberate, keeps saved workouts/history/exports/snapshots downgrade-safe.
- **`GeneratorWarning.plannedTotalSec`:** in-memory only, never serialized.
- **DataStore:** no new keys.

### Critical Files for Implementation
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/service/WorkoutSessionService.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/service/SessionState.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/domain/generator/WorkoutGenerator.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/ui/screens/player/PlayerScreen.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/voice/VoiceCoach.kt