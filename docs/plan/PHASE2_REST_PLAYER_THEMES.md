I have completed a full read of the codebase. Below is the Phase 2 implementation plan.

---

# Phase 2 Implementation Plan — Kinetiq (edgeHealth)

## 0. Verified current state (facts the plan is built on)

- **Settings storage** (`/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/data/repo/SettingsRepository.kt`): Preferences DataStore named `"kinetiq_settings"`. Pattern: `private object Keys { val x = stringPreferencesKey("x") }`, a single `settings: Flow<AppSettings>` mapping with `?: default` per key, enum stored via `.name` and decoded with `runCatching { Enum.valueOf(it) }.getOrNull() ?: DEFAULT`, one `suspend fun setX(...)` per setting. `ThemeMode { LIGHT, DARK, AMOLED, SYSTEM }` **already exists** (key `"theme"`), confirmed selectable in `SettingsScreen.kt:162-171` via `FilterChip` row and applied in `MainActivity.kt:68` (`KinetiqTheme(mode = settings.theme)`).
- **Theme** (`ui/theme/Theme.kt`): hand-built `LightScheme`/`DarkScheme` overriding only 9 roles (primary, onPrimary, secondary, tertiary, surface, background, surfaceVariant, primaryContainer, onPrimaryContainer). **Confirmed gap (review L1)**: `secondaryContainer`, `tertiaryContainer`, `onSecondary`, `onSurfaceVariant`, `surfaceContainer*`, `outline` all fall through to Material baseline purple/neutral — visible in the bottom bar pill (`KinetiqNavHost.kt:223` uses `secondaryContainer`) and warning cards (`BuilderScreen.kt:267` uses `tertiaryContainer`). AMOLED = `DarkScheme.copy(...)` with pure black surface overrides (`Theme.kt:42-49`). **No dynamic color anywhere** (grep confirmed no `dynamicColorScheme` usage). Widget uses `GlanceTheme.colors` (system dynamic), **not** the app theme — no interplay to maintain.
- **Serialization**: `GeneratorConfig` is `@Serializable`, embedded in `GeneratedSession` which is persisted in: saved-workout rows (`SavedWorkoutEntity.json`), history rows (`SessionHistoryEntity.sessionJson`), the disk snapshot (`SessionSnapshot`), the last-config DataStore key (`"last_config"`), and export files. Both the DI `Json` (`AppModule.kt:23-26`) and `ExportImportCodec.json` use `ignoreUnknownKeys = true; encodeDefaults = true` → **adding a field with a default is fully backward compatible** (old JSON → default value; new JSON read by old code → key ignored).
- **Generator**: `discreteBlock` (`WorkoutGenerator.kt:184-256`) sizes rests as `(w / ratio).roundToInt().coerceIn(10, 90)`; **confirmed** `if (i < picked.size - 1)` at line 244 means no trailing rest inside a discrete block. Machine blocks emit no REST steps at all. Between-block `TRANSITION` steps are inserted *before* each block with `index > 0` (line 110-118), so nothing trails the final block; cooldown steps follow the last block directly. **No trailing rest exists anywhere** — item A3 is a verification-only task.
- **Player**: `PlayerScreen.kt` — keep-screen-on is plain `remember` (line 80), overall progress is step-count-weighted (line 125), machine cue is `bodyMedium`/`onSurfaceVariant` (134-136), exercise name `headlineMedium` (129-133), +30s is an `Add` icon (192-195). Service handles `ACTION_SKIP` etc.; commands sent via `WorkoutSessionService.command(context, action)`.
- **Voice/audio**: `VoiceCoach` requests transient-may-duck focus only while speaking; **nothing today listens for focus loss or `ACTION_AUDIO_BECOMING_NOISY`**. `minSdk = 34`, so `AudioManager.addOnModeChangedListener` (API 31+) is available for call detection without `READ_PHONE_STATE`.
- **Exercise data for "setup change"** (`Models.kt`): there is **no** "animation family" field; `animationId` prefixes (`fl_`, `bk_`, `el_`, …) just mirror category. The only setup signals within a discrete block are `Exercise.machine?.reformer?.springs` (values in DB: `LIGHT_1`, `MEDIUM_1`, `MEDIUM_2`) and `machine?.reformer?.bodyPosition`, plus presence/absence of `machine` itself. FLOOR/BACK exercises have `machine == null`, so floor position changes are **not detectable from data** — the rule below is defined accordingly.
- **Tests**: JUnit4 + Truth + Robolectric available (`app/build.gradle.kts:115-118`). `WorkoutGeneratorTest` loads the real `src/main/assets/exercise_db.json` with a seeded `Random(42)` — new generator tests must follow this pattern. Backtick test names are the house style.

---

## PART A — REST MODEL RESTRUCTURE

### A1. Rest modes: STANDARD / RECOVERY / CONTINUOUS

**Goal**: Replace the work:rest ratio slider with three evidence-based rest modes; keep every previously-serialized `GeneratorConfig` loadable and behaving sensibly (STANDARD).

**Files**
- `/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/data/model/Session.kt`
- `/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/data/model/Models.kt` (no change needed — rule uses existing fields)
- `/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/data/repo/SettingsRepository.kt`
- `/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/ui/screens/builder/BuilderScreen.kt`
- `/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/ui/screens/settings/SettingsScreen.kt`
- `/home/user/edgeHealth/app/src/main/res/values/strings.xml`

**Exact changes**

1. New enum in `Session.kt` (next to `StepType`, same file so it serializes with the config):
```kotlin
/** How rests between discrete exercises are computed (Phase 2, RESEARCH-backed). */
@Serializable
enum class RestMode { STANDARD, RECOVERY, CONTINUOUS }
```
2. `GeneratorConfig` gains one field and deprecates one:
```kotlin
@Serializable
data class GeneratorConfig(
    ...
    /** Rest model between discrete exercises. Old configs (pre-1.2) deserialize to STANDARD. */
    val restMode: RestMode = RestMode.STANDARD,
    @Deprecated("Superseded by restMode in v1.2; retained only for serialization compatibility with saved workouts/history/exports. No longer read by the generator or UI.")
    val workRestRatio: Float = 2.0f,
    ...
)
```
   - **Do not remove or rename `workRestRatio`** — it stays serialized (with `encodeDefaults = true` it will keep appearing in new JSON, which is intended: old exports remain re-importable both ways). Suppress the deprecation warning at the one remaining copy site if any (`applyFix` copies whole configs, no explicit reference needed).
   - `transitionSec` (between-category-block pause, default 60) is **unchanged and out of scope** — rest modes govern intra-block rests only.
3. Rest duration rules (implemented in the generator, A4, but defined here):
   - **STANDARD**: 15 s between consecutive discrete exercises; **20 s when setup changes**. Concrete rule (new `internal fun` in `WorkoutGenerator`):
     ```kotlin
     /** True when moving between [a] and [b] requires re-setup (springs, machine presence, body position). */
     internal fun setupChange(a: Exercise, b: Exercise): Boolean =
         a.category != b.category ||
         (a.machine == null) != (b.machine == null) ||
         a.machine?.reformer?.springs != b.machine?.reformer?.springs ||
         a.machine?.reformer?.bodyPosition != b.machine?.reformer?.bodyPosition
     ```
     Rationale recorded in KDoc: reformer spring/position changes need 15–20 s (reformer evidence); FLOOR/BACK have no position field, so consecutive mat moves get the 15 s floor — matching the 10–30 s circuit-transition meta-analysis. There is no animation-family field; do not invent one.
   - **RECOVERY**: rest scales with configured intensity, staying inside the 30–45 s evidence window:
     ```kotlin
     internal fun recoveryRestSec(intensity: Intensity): Int = when (intensity) {
         Intensity.LOW -> 45; Intensity.MODERATE -> 40; Intensity.HIGH -> 35; Intensity.VERY_HIGH -> 30
     }
     ```
     (Harder work at lower fitness intent → longer rest for easier settings is deliberate: RECOVERY mode is chosen *by* deconditioned users; document this in KDoc.)
   - **CONTINUOUS**: no REST steps, except a **forced 10 s REST step** (`exerciseName = "Change setup"`) inserted whenever `setupChange(prev, next)` is true. Spoken "next up" overlap is a player/service behavior (see below).
4. Settings default: in `SettingsRepository.kt`
   - `AppSettings` gains `val defaultRestMode: RestMode = RestMode.STANDARD` and `val continuousNoticeSeen: Boolean = false` (import `au.mark.kinetiq.data.model.RestMode`).
   - `Keys`: `val restMode = stringPreferencesKey("rest_mode")`, `val continuousNoticeSeen = booleanPreferencesKey("continuous_notice_seen")`.
   - Mapping: `defaultRestMode = p[Keys.restMode]?.let { runCatching { RestMode.valueOf(it) }.getOrNull() } ?: RestMode.STANDARD`, `continuousNoticeSeen = p[Keys.continuousNoticeSeen] ?: false`.
   - Setters: `suspend fun setDefaultRestMode(v: RestMode) = edit { it[Keys.restMode] = v.name }` and `suspend fun setContinuousNoticeSeen(v: Boolean) = edit { it[Keys.continuousNoticeSeen] = v }`.
5. Builder UI (`BuilderScreen.kt`): delete the ratio slider item (lines 228-235). Replace with:
   ```kotlin
   item {
       SectionHeader("Rest between exercises")
       FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
           RestMode.entries.forEach { mode ->
               FilterChip(
                   selected = config.restMode == mode,
                   onClick = { viewModel.selectRestMode(mode) },
                   label = { Text(restModeLabel(mode)) },   // "Standard", "Recovery", "Continuous"
               )
           }
       }
       Text(restModeSubtitle(config.restMode), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
       // subtitles: STANDARD "15–20 s transitions", RECOVERY "30–45 s rests, scaled to intensity",
       // CONTINUOUS "No rests — next exercise is announced over the last 5 s"
   }
   ```
   `BuilderViewModel` additions:
   ```kotlin
   data class BuilderUiState(..., val showContinuousNotice: Boolean = false)
   fun selectRestMode(mode: RestMode) { ... }          // sets config; if CONTINUOUS && !settings.continuousNoticeSeen → showContinuousNotice = true
   fun dismissContinuousNotice() { ... }               // setContinuousNoticeSeen(true); showContinuousNotice = false
   ```
   One-time `AlertDialog` in `BuilderScreen` when `state.showContinuousNotice` (match the disclaimer dialog pattern in `SettingsScreen.kt:314-321`), using new string resources in `strings.xml`:
   ```xml
   <string name="rest_continuous_notice_title">Continuous mode</string>
   <string name="rest_continuous_notice_body">Continuous mode removes rests entirely. The next exercise is announced during the last 5 seconds of the current one. A short 10-second pause is still inserted when you need to change equipment or springs. Skip any step from the player if you need a breather.</string>
   ```
   (These are the only new `strings.xml` entries for A1; all other Builder text is hardcoded, matching the existing convention.)
   Builder default: in `BuilderViewModel.init`, when `lastConfigJson` is null, seed `config = GeneratorConfig(restMode = settingsRepository.current().defaultRestMode)`.
6. Settings UI: add under the existing "Units & goals" section (or a new `SectionHeader("Workout defaults")` placed after "Machines") the same three-chip `FilterChip` row bound to `settings.defaultRestMode` / `viewModel.set { setDefaultRestMode(mode) }`.
7. **Backward compatibility (explicit)**:
   - Old saved workouts / history / snapshot / `last_config` JSON have no `restMode` key → kotlinx.serialization uses the default `STANDARD`. Their `plan.steps` are already fully materialized (rests baked in), so playback is unchanged regardless of mode.
   - `workRestRatio` remains in the schema; old exports import cleanly; new exports remain importable by v1.1 (extra key ignored via `ignoreUnknownKeys`).
   - CONTINUOUS-specific player behavior keys off `state.session.config.restMode`, which is `STANDARD` for all legacy sessions — no behavior change for old data.

**Tests** (`app/src/test/java/au/mark/kinetiq/RestModeTest.kt`, new; plain JUnit + Truth, seeded like `WorkoutGeneratorTest`)
- `` `old GeneratorConfig json without restMode decodes to STANDARD and keeps workRestRatio` `` — decode the literal string `{"totalDurationMin":15,"categories":["FLOOR"],"workRestRatio":3.0}` with `Json { ignoreUnknownKeys = true }`; assert `restMode == RestMode.STANDARD`, `workRestRatio == 3.0f`.
- `` `new GeneratorConfig json round-trips restMode` `` — encode/decode each of the three modes.
- Generator behavior tests are in A4.

**Acceptance criteria**
- Builder shows three chips, no ratio slider; selecting CONTINUOUS the first time shows the notice dialog exactly once per install (persisted).
- A v1.1 export file imports with zero warnings about config.
- "Repeat last" on a pre-upgrade history entry starts and plays identically to before.

**Dependencies**: none (first item to land; A4, B items build on it).

---

### A2. Skippable rests in the player

**Goal**: One-tap rest skipping — a visible skip button plus tap-anywhere on the rest content area — without stealing touches from existing controls.

**Files**: `/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/ui/screens/player/PlayerScreen.kt`

**Exact changes**
- Reuse the existing `ACTION_SKIP` service command; no service change needed (`skipStep()` already stops speech and advances).
- Define `val isRestStep = step.type == StepType.REST || step.type == StepType.TRANSITION` (the same predicate already used at line 159).
- **Tap-anywhere region**: apply a clickable modifier to the *content* region only — the big timer `Text` (line 139) and the animation `Box` (line 148) — never the whole screen. Concretely, wrap lines 139–174 (timer, animation, next-up card) in:
  ```kotlin
  Column(
      Modifier
          .weight(1f)
          .fillMaxWidth()
          .then(
              if (isRestStep) Modifier.clickable(
                  interactionSource = remember { MutableInteractionSource() },
                  indication = null,
                  onClickLabel = "Skip rest",
              ) { WorkoutSessionService.command(context, WorkoutSessionService.ACTION_SKIP) }
              else Modifier
          ),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) { ...timer, animation box, next-up card... }
  ```
  The animation `Box` keeps its own `weight(1f)` inside this column. Because the controls `Row` (lines 177–204) and the keep-screen-on `SettingSwitchRow` are **outside** this column, the pause/skip/stop/+30 buttons and the switch keep their own touch handling — no gesture conflict, no nested-scroll issues (Column is not scrollable). `clickable` (not raw `pointerInput`) keeps TalkBack actionability with the "Skip rest" label.
- **Visible button**: inside the rest branch (after the next-up card, before `Spacer`):
  ```kotlin
  TextButton(onClick = { WorkoutSessionService.command(context, WorkoutSessionService.ACTION_SKIP) }) {
      Text("Skip rest — tap anywhere")
  }
  ```

**Tests**: manual/QA (no Compose UI test infra exists in `test/`; do not introduce one for this). Add to the item's QA checklist: tapping the timer during WORK does nothing; during REST advances immediately; pause button still works during rest.

**Acceptance criteria**: During REST/TRANSITION, tapping timer/animation/next-up area or the "Skip rest" button advances to the next step immediately and cancels rest speech. During WORK/WARMUP/COOLDOWN steps taps on the same area are inert. All five control buttons behave exactly as before.

**Dependencies**: none.

---

### A3. No trailing rest after the final exercise (verification item)

**Goal**: Confirm and document — no code change expected.

**Findings (verified)**
- `WorkoutGenerator.kt:244` `if (i < picked.size - 1)` — the last discrete exercise of a block never gets a trailing REST. ✔
- Machine blocks (`machineBlock`, lines 300-376) emit only WORK steps. ✔
- Between-block TRANSITIONs are emitted *before* blocks with `index > 0` (line 110), never after the last block; cooldown steps append directly after the final block, warm-down itself is WORK-typed steps of `StepType.COOLDOWN` — nothing trails it. ✔

**Exact changes**: none to production code. Add one regression test.

**Tests** (append to `WorkoutGeneratorTest.kt`)
- `` `no rest or transition trails the final work step` ``: generate `GeneratorConfig(totalDurationMin = 40, categories = listOf(Category.FLOOR, Category.REFORMER, Category.SPIN), warmup = true, cooldown = true)` across `repeat(10) { seed -> ... }`; assert `plan.steps.last().type` is `COOLDOWN` (or `WORK` with `cooldown = false`), and that for every block the last step whose `blockIndex == i` and category matches is not `REST`/`TRANSITION` — i.e. `steps.zipWithNext().none { (a, b) -> a.type == StepType.REST && (b.type == StepType.TRANSITION || b.type == StepType.COOLDOWN) }` and `steps.last().type != StepType.REST && steps.last().type != StepType.TRANSITION`.

**Acceptance criteria**: test passes against current code (it should — if it doesn't, the failure pinpoints the real trailing case to fix).

**Dependencies**: none.

---

### A4. Generator: mode-driven rests + updated solver

**Goal**: `discreteBlock` computes rests from `config.restMode` (per A1 rules) instead of the ratio; work seconds solved from the actual rest total.

**Files**: `/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/domain/generator/WorkoutGenerator.kt`

**Exact changes**
1. Add the two internal helpers from A1 (`setupChange`, `recoveryRestSec`) plus:
   ```kotlin
   /** Rest seconds between consecutive picks under [mode]; 0 = no rest step. */
   internal fun restBetween(mode: RestMode, intensity: Intensity, prev: Exercise, next: Exercise): Int = when (mode) {
       RestMode.STANDARD -> if (setupChange(prev, next)) 20 else 15
       RestMode.RECOVERY -> maxOf(recoveryRestSec(intensity), if (setupChange(prev, next)) 20 else 0) // always ≥30 anyway
       RestMode.CONTINUOUS -> if (setupChange(prev, next)) 10 else 0
   }
   ```
2. Replace the solver (keep the old signature deleted, update its one test):
   ```kotlin
   /** work = (blockSec - totalRestSec) / count, with no rest after the final exercise. */
   internal fun solveWorkSec(blockSec: Int, count: Int, totalRestSec: Int): Int {
       if (count <= 0) return 0
       return ((blockSec - totalRestSec).coerceAtLeast(0)) / count
   }
   ```
3. Rework `discreteBlock` (lines 184-256):
   - Delete `val ratio = config.workRestRatio...` (line 204).
   - **Ordering change**: pick exercises *first*, then compute rests, then solve work:
     ```kotlin
     val nominalRest = when (config.restMode) {           // for count estimation only
         RestMode.STANDARD -> 15; RestMode.RECOVERY -> recoveryRestSec(config.intensity); RestMode.CONTINUOUS -> 0
     }
     val count = requested ?: countFor(blockSec, defaultWork = 40, nominalRest).coerceIn(3, 12)
     // countFor becomes: max(1, blockSec / (defaultWork + nominalRest))
     val picked = pickBalanced(candidates, count, highAdiposity)
     val rests = picked.zipWithNext().map { (a, b) -> restBetween(config.restMode, config.intensity, a, b) }
     var workSec = solveWorkSec(blockSec, picked.size, rests.sum())
     ```
   - The "too short" warning (lines 211-219) keeps its 20 s floor logic, using the new solver/`countFor`.
   - Emission loop: after each WORK step `i < picked.size - 1`, emit REST only when `rests[i] > 0`, with `durationSec = rests[i]` (drop the old `coerceIn(10, 90)`), and `exerciseName = if (config.restMode == RestMode.CONTINUOUS) "Change setup" else "Rest"`.
   - **Note**: the high-adiposity substitution (lines 229-231) currently swaps `effective` after picking; move that substitution to *before* the `rests` computation so `setupChange` sees the exercises actually emitted.
4. CONTINUOUS "next up" spoken overlap — `WorkoutSessionService.kt`:
   - New cue flag `private var nextUpSpoken = false`, reset in `resetStepCues()`.
   - In `onTick`, after the halfway block:
     ```kotlin
     // CONTINUOUS mode: announce the next exercise over the last 5 s of the current one.
     if (!nextUpSpoken && state.session.config.restMode == RestMode.CONTINUOUS &&
         step.type == StepType.WORK && next?.type == StepType.WORK && remaining <= 5_000
     ) {
         nextUpSpoken = true
         voice.speak("Next up: ${next.exerciseName}.")
     }
     ```
   - Existing REST/TRANSITION cues are untouched (they simply won't fire when there are no rest steps; the forced 10 s "Change setup" REST still gets the normal rest announcement, whose text at line 298 already reads naturally).

**Tests** (`RestModeTest.kt`, continuing from A1; seeded `Random(42)`, real DB; and update `WorkoutGeneratorTest`)
- Update `` `duration solver computes work seconds within ratio` `` → rename `` `duration solver divides block minus rests across exercises` ``: `solveWorkSec(600, 8, 7 * 15)` == `(600-105)/8` == `61`; `solveWorkSec(600, 0, 0) == 0`; `solveWorkSec(100, 4, 200) == 0`.
- `` `STANDARD mode emits 15s rests and 20s on reformer setup change` ``: generate `GeneratorConfig(totalDurationMin = 20, categories = listOf(Category.REFORMER), restMode = RestMode.STANDARD, warmup = false, cooldown = false, useHealthData = false)`; assert every REST step duration is 15 or 20, and for each 20 s rest the surrounding exercises satisfy `setupChange`; for each 15 s rest they don't. Also a FLOOR run asserts all rests are exactly 15.
- `` `RECOVERY mode rest scales with intensity` ``: for each `Intensity`, generate a FLOOR block and assert all REST durations equal `recoveryRestSec(intensity)` (i.e. 45/40/35/30) — all within 30–45.
- `` `CONTINUOUS mode has no rests except forced 10s setup changes` ``: FLOOR block → zero REST steps; REFORMER block (contains `LIGHT_1`/`MEDIUM_1`/`MEDIUM_2` spring variety) → every REST step has `durationSec == 10` and `exerciseName == "Change setup"`.
- `` `block time sums to budget for each rest mode` ``: for each mode, single-category no-warmup/cooldown 20-min generation; assert `plan.steps.sumOf { it.durationSec }` is within `count` seconds of `20 * 60` (integer-division slack), unless min/max clamping fired (filter: assert within 10%).
- `` `setupChange detects spring and position changes` ``: direct unit test on two hand-built `Exercise` fixtures (reformer LIGHT_1 vs MEDIUM_2 → true; two identical-cue reformer moves → false; floor vs floor → false; floor vs reformer → true).

**Acceptance criteria**
- No generated plan contains a REST outside {10, 15, 20, 30, 35, 40, 45} seconds.
- CONTINUOUS 20-min FLOOR session has strictly more WORK seconds than the same-seed STANDARD session.
- All pre-existing `WorkoutGeneratorTest` tests still pass (only the solver test text changes).

**Dependencies**: A1 (enum/field), lands together with A1 in one PR ideally.

---

## PART B — PLAYER POLISH

### B5. Machine cue prominence

**Goal**: Machine setup line readable from across the room.

**Files**: `PlayerScreen.kt`

**Exact changes** (lines 129-136):
```kotlin
Text(step.exerciseName, style = MaterialTheme.typography.headlineLarge,
     color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
step.machineCueText?.let {
    Text(it, style = MaterialTheme.typography.titleLarge,
         color = MaterialTheme.colorScheme.onSurface,           // high contrast, was onSurfaceVariant
         textAlign = TextAlign.Center,
         modifier = Modifier.padding(horizontal = 8.dp))
}
```
**Tests**: none (visual). **Acceptance**: cue renders `titleLarge`/`onSurface`; long cues wrap without pushing the timer off-screen (verify with the longest cue in the DB, an elliptical one). **Dependencies**: none.

### B6. Time-weighted overall progress + minutes-left caption

**Goal**: Progress bar reflects seconds, not step count.

**Files**: `PlayerScreen.kt`

**Exact changes** (replace lines 119-127):
```kotlin
val plan = s.session.plan
val elapsedInStep = step.durationSec - remainingSec
val elapsedSec = remember(s.stepIndex) { plan.steps.take(s.stepIndex).sumOf { it.durationSec } } + elapsedInStep
val totalSec = plan.totalSec.coerceAtLeast(1)
val minutesLeft = ((totalSec - elapsedSec).coerceAtLeast(0) + 59) / 60
Text("${s.sessionName} · step ${s.stepIndex + 1} of ${s.totalSteps}", ...)
LinearProgressIndicator(
    progress = { (elapsedSec.toFloat() / totalSec).coerceIn(0f, 1f) },
    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
)
Text("~$minutesLeft min left", style = MaterialTheme.typography.labelMedium,
     color = MaterialTheme.colorScheme.onSurfaceVariant)
```
Note in code: `+30s` extensions and skips make this an estimate against the *planned* `totalSec`; clamping handles overrun. **Tests**: none (pure UI arithmetic; the sums are trivially derived from tested plan data). **Acceptance**: at the midpoint of a plan whose first half is long exercises, the bar shows time-share, not step-share; caption counts down and never goes negative. **Dependencies**: none.

### B7. Auto-pause on call / headphone disconnect

**Goal**: Pause the session when a call starts (audio mode change) or wired/BT audio route is torn away; resume stays manual.

**Files**: `/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/service/WorkoutSessionService.kt` (this is the right home — it owns pause state; `VoiceCoach` only holds transient focus while speaking, so its focus callbacks cannot see call interruptions during silence).

**Exact changes**
```kotlin
// fields
private var noisyReceiver: BroadcastReceiver? = null
private var audioModeListener: AudioManager.OnModeChangedListener? = null

// in goForeground() (so they're active only while a session runs), guarded against double-registration:
private fun registerAutoPause() {
    if (noisyReceiver != null) return
    noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { autoPause("Audio disconnected") }
    }.also { registerReceiver(it, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
    val am = getSystemService(AudioManager::class.java)
    audioModeListener = AudioManager.OnModeChangedListener { mode ->
        if (mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL ||
            mode == AudioManager.MODE_IN_COMMUNICATION) autoPause("Call")
    }.also { am.addOnModeChangedListener(mainExecutor, it) }
}

private fun autoPause(reason: String) {
    val state = stateHolder.state.value ?: return
    if (state.paused || state.finished) return
    setPaused(true)   // existing method: updates state, speaks "Paused.", refreshes notification
}
```
Unregister both in `onDestroy()` (and in `finishSession` after `stopSelf()` is queued). No new permissions (`ACTION_AUDIO_BECOMING_NOISY` and mode listener are permission-free on API 34).
- **Resume with 3-2-1**: **dependency on Phase 1** — Phase 1's resume-countdown (its change to `setPaused(false)`) is what plays 3-2-1 before the timer restarts. This item adds nothing to resume; if Phase 1 hasn't merged yet, resume behaves as today (immediate) and picks up the countdown when Phase 1 lands. Flag in the PR description.

**Tests**: Robolectric test is possible (`Shadows.shadowOf(audioManager).setMode(...)` + broadcast) but service lifecycle under Robolectric+Hilt is heavy; specify **manual QA**: start session → phone call in → paused + notification shows Resume; unplug/disconnect headphones → paused. **Acceptance**: both triggers pause exactly once, never resume by themselves, and don't fire when already paused/finished. **Dependencies**: Phase 1 resume countdown (soft).

### B8. Keep-screen-on: saveable, persisted, cleared while paused

**Goal**: Player toggle survives rotation, writes back to settings, and the flag drops during pause.

**Files**: `PlayerScreen.kt`

**Exact changes**
- `PlayerViewModel` gains `private val settingsRepository: SettingsRepository` and
  ```kotlin
  fun persistKeepScreenOn(v: Boolean) { viewModelScope.launch { settingsRepository.setKeepScreenOn(v) } }
  ```
  (add `viewModelScope` import; setter `setKeepScreenOn` already exists in `SettingsRepository.kt:163`.)
- Line 80: `var keepScreenOn by rememberSaveable { mutableStateOf(keepScreenOnDefault) }`.
- Switch callback (line 209): `onCheckedChange = { keepScreenOn = it; viewModel.persistKeepScreenOn(it) }`.
- Pause-aware flag: hoist paused before the effect (`val isPaused = state?.paused == true`) and change line 84 to `DisposableEffect(keepScreenOn, isPaused) { if (keepScreenOn && !isPaused) window?.addFlags(...) else window?.clearFlags(...); onDispose { window?.clearFlags(...) } }`.

**Tests**: none (window-flag behavior). **Acceptance**: toggle state survives rotation; next session opens with the last-chosen value; screen may sleep while paused even with the toggle on. **Dependencies**: none.

### B9. "+30" control legibility

**Files**: `PlayerScreen.kt` lines 17, 192-195.
**Exact change**: replace `Icons.Filled.Add` with `Icons.Filled.MoreTime` (available — `material-icons-extended` is a dependency), keep `contentDescription = "Add 30 seconds"`, same 56 dp `FilledIconButton`. **Acceptance**: control unambiguous; TalkBack unchanged. **Dependencies**: none.

### B10. Quick-wins bundle

All are small, independent, mechanical edits. Group into one PR, ordered by file.

| # | Change | File / location | Exact change |
|---|--------|-----------------|--------------|
| 10a | Numeric keyboards | `OnboardingScreen.kt:143-152` (both fields), `SettingsScreen.kt:205-213, 215-223, 282-290`, `HealthDataScreen.kt:191-196` | Add `keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)` (Onboarding/Settings int fields) or `KeyboardType.Decimal` (`ManualEntryField`). Imports: `androidx.compose.foundation.text.KeyboardOptions`, `androidx.compose.ui.text.input.KeyboardType`. |
| 10b | isError + supporting text | Same Settings fields + `ManualEntryField` | Settings spin/elliptical: `isError = spinMax.toIntOrNull()?.let { it in 4..40 } != true`, `supportingText = { Text("4–40") }` (analogous for elliptical, weight `30–250 kg`). `ManualEntryField`: `isError = text.isNotEmpty() && text.toDoubleOrNull()?.let { it in range } != true`, `supportingText = { Text("${range.start.toInt()}–${range.endInclusive.toInt()}") }`. |
| 10c | Delete confirmations | `HistoryScreen.kt:145-147`, `HomeScreen.kt:212-214` | Local `var pendingDelete by remember { mutableStateOf<Long?>(null) }` (History) / `<SavedWorkout?>` (Home); delete `IconButton` sets it; `AlertDialog(title = Text("Delete this session?" / "Delete \"${w.name}\"?"), text = Text("This can't be undone."), confirmButton = TextButton("Delete") { viewModel.delete(...) }, dismissButton = TextButton("Cancel"))`. Match the disclaimer-dialog pattern. |
| 10d | Empty states | `LibraryScreen.kt` (after filters, when `filtered.isEmpty()`), `HistoryScreen.kt` (when `state.entries.isEmpty()`), `HomeScreen.kt` (when `state.saved.isEmpty() && state.lastWorkoutName == null`) | `item { Text("No exercises match these filters.", style = bodyMedium, color = onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }`; History: `"No sessions yet — your finished workouts appear here."`; Home: `"Build your first workout to get started."`. |
| 10e | `Category.displayName()` | New extension in `Models.kt` (bottom of file); call sites `BuilderScreen.kt:213`, `LibraryScreen.kt:107` (+ row subtitle 157), `HomeScreen.kt:204`, `SummaryScreen.kt:78`, `BuilderViewModel.start` name building (`BuilderScreen.kt:172-173`) | ```fun Category.displayName(): String = when (this) { Category.FLOOR -> "Floor"; Category.REFORMER -> "Reformer"; Category.SPIN -> "Spin bike"; Category.ELLIPTICAL -> "Elliptical"; Category.BACK -> "Back care" }``` Summary stores `block.category` as a raw `String`: use `runCatching { Category.valueOf(block.category) }.getOrNull()?.displayName() ?: block.category`. |
| 10f | Metric-units switch | `SettingsScreen.kt:279` | **Remove the row** (the app is metric-only; a do-nothing toggle is worse than none). Keep `AppSettings.metricUnits`, key `metric_units`, and `setMetricUnits` for serialization/back-compat; add `// retained for settings back-compat; UI removed v1.2` comment. |
| 10g | EvidenceBadge non-interactive | `Common.kt:56-63` | Replace `AssistChip(onClick = {}, ...)` with a plain `Surface(shape = MaterialTheme.shapes.small, color = Color.Transparent, border = BorderStroke(1.dp, color)) { Text(label, style = labelMedium, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }` — removes button semantics/ripple. Call sites unchanged. |
| 10h | Onboarding back + step indicator | `OnboardingScreen.kt:101-188` | Above the `when`: `Text("Step ${step + 1} of 4", style = labelMedium, color = onSurfaceVariant)` + `LinearProgressIndicator(progress = { (step + 1) / 4f }, Modifier.fillMaxWidth())`. In steps 1–3 add `TextButton(onClick = { step-- }) { Text("Back") }` above the Continue button. (`step` is already `rememberSaveable`.) |
| 10i | Tab highlight on nested routes | `KinetiqNavHost.kt:107-110, 204-205` | Add `private val parentTab = mapOf(Routes.BUILDER to Routes.HOME, Routes.PLAYER to Routes.HOME, Routes.SUMMARY to Routes.HOME, Routes.LIBRARY_DETAIL to Routes.LIBRARY, Routes.HEALTH to Routes.SETTINGS, Routes.DEBUG_ANIM to Routes.SETTINGS)`; compute `val selectedTab = parentTab[currentRoute] ?: currentRoute` and pass to `KinetiqBottomBar`; `selected = selectedTab == tab.route`. |
| 10j | Calendar semantics + day headers | `HistoryScreen.kt:174, 190-209` | Headers: `listOf("Mon","Tue","Wed","Thu","Fri","Sat","Sun")`. Day cell: `Modifier.semantics { contentDescription = "${date.format(DateTimeFormatter.ofPattern("d MMMM"))}, ${if (done) "workout completed" else "no workout"}" }` on the inner `Box`. |
| 10k | Slider semantics + volume % | `SettingsScreen.kt:153-156` | `Text("Voice volume: ${(voice.volume * 100).roundToInt()}%")`; both volume and speech-rate sliders get `modifier = Modifier.semantics { contentDescription = "Voice volume" /* or "Speech rate" */ }` (value semantics come from Slider itself). |
| 10l | Builder preview edit protection | `BuilderScreen.kt:88-90` (updateConfig), `62` (state), `125-160` (edits), preview header | `BuilderUiState` gains `val configChanged: Boolean = false, val edited: Boolean = false`. `updateConfig` **no longer nulls the preview**: `copy(config = transform(...), configChanged = uiState.value.preview != null)`. `removeStep`/`moveStep`/`swapStep` set `edited = true`. `generate()` resets both to false. UI: when `state.configChanged && state.preview != null`, show a `Card(tertiaryContainer)` banner above the preview: `Text("Settings changed — this preview no longer matches.")` + `OutlinedButton("Regenerate")` → if `state.edited`, first an `AlertDialog("Regenerating discards your manual edits. Continue?")`. Start button remains enabled (stale preview is still valid to run). |
| 10m | rememberSaveable state | `SummaryScreen.kt:58` (`name`), `HistoryScreen.kt:100` (`month`), `HealthDataScreen.kt:189` (`text` in `ManualEntryField`) | Swap `remember` → `rememberSaveable`. `YearMonth` isn't saveable: `var monthEpoch by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }` with `val month = YearMonth.parse(monthEpoch)` (or a custom `Saver` — pick the string form, simplest). Summary: `rememberSaveable(s.historyId) { mutableStateOf(s.name) }`. |
| 10n | PlanScreen copy | `PlanScreen.kt:42, 56-61` | Add `settingsRepository.settings` into the ViewModel state (expose `visceralFatGoal`). Replace the paragraph: goal ON → `"Grounded in WHO 2020 activity guidelines and visceral-fat dose–response evidence: at least 3 cardio sessions a week of 30–60 minutes, plus strength work twice a week."`; goal OFF → `"Grounded in WHO 2020 activity guidelines: 150–300 minutes of moderate activity a week, plus strength work twice a week."` — **drop "See RESEARCH.md"**. The default/placeholder `progressForWeek(emptyList(), visceralFatGoal = true)` at line 42 becomes goal-aware once settings joins the combine (initial `stateIn` value keeps `true` until first emission — acceptable). |
| 10o | Summary re-save on rename | `SummaryScreen.kt:59, 109-113` | Replace `var saved` with `var savedAs by rememberSaveable { mutableStateOf<String?>(null) }`. `enabled = savedAs != name.ifBlank { s.name }`; on save set `savedAs = usedName`. Button text: `if (savedAs != null && savedAs == name.ifBlank { s.name }) "Saved ✓ — reusable from Home" else if (savedAs != null) "Save as new name" else "Save workout"`. Each save calls the existing `saveWorkout` (creates a new row — matches repo API). |
| 10p | Library detail loading/error | `LibraryScreen.kt:170-172` | `val loaded = produceState<Result<Exercise?>?>(null, exerciseId) { value = runCatching { viewModel.exercise(exerciseId) } }`; render: `null` → centered `CircularProgressIndicator()`; success-with-null or failure → `Column { back IconButton; Text("Exercise not found.", color = error) }`; else detail as today. |
| 10q | Home resume-snapshot in ViewModel | `HomeScreen.kt:56-61, 108-111, 134, 155-162`; `WorkoutSessionService.kt` companion | Add to companion: `fun snapshotSummary(context: Context, json: Json): SessionSnapshot? = readSnapshot(context, json)` (readSnapshot already exists/public — just use it). `HomeViewModel`: inject `@ApplicationContext context`; `val snapshot = MutableStateFlow<SessionSnapshot?>(null)` refreshed in `init` and exposed via `HomeUiState(hasSnapshot/snapshotName/snapshotProgress)`: name = `snap.sessionName`, progress = `"step ${snap.stepIndex + 1} of ${snap.session.plan.steps.size}"`. Screen: delete line 134 (`WorkoutSessionService.hasSnapshot(context)` in composition); button text `"Resume: ${name} — ${progress}"`. Refresh on resume: call `viewModel.refreshSnapshot()` from a `LaunchedEffect(Unit)`. |

**Tests for B10**: one new unit test file `app/src/test/java/au/mark/kinetiq/DisplayNameTest.kt` — `` `every category has a human display name distinct from enum name` `` (asserts non-blank, no underscores, covers all `Category.entries`). Everything else is UI-only; cover via QA checklist in the PR.

**Acceptance criteria (bundle)**: every numeric field opens a numeric keypad; invalid entries show red outline + range hint and are not persisted; deletes always confirm; no raw enum spellings (`FLOOR`, `VERY_HIGH`-style) visible anywhere in the five listed screens; changing builder config keeps the preview visible with the banner; Home resume button names the interrupted session.

**Dependencies**: none among themselves; 10e touches the same lines as A1's Builder edit (coordinate: land A1 first, rebase).

---

## PART C — THEMING

### C11. Accent palettes orthogonal to mode

**Report of what exists** (per instruction): mode selection Light/Dark/AMOLED/System **exists** (`ThemeMode` enum, `"theme"` DataStore key, chips in Settings, `KinetiqTheme(mode)` in MainActivity). There is **no** palette concept, **no** dynamic color, and only 9 color roles are overridden (gap confirmed). AMOLED is `DarkScheme.copy` with pure-black `surface`/`background` + tuned `surfaceVariant`/`surfaceContainer`/`surfaceContainerLow`/`surfaceContainerHigh`. The Glance widget uses `GlanceTheme.colors` (system Material You), independent of the app theme — **decision: leave the widget on GlanceTheme; no interplay work**.

**Goal**: 7 palettes × existing 4 modes, full Material3 role coverage so no baseline purple leaks.

**Files**
- `SettingsRepository.kt` — enum, key, field, setter
- `ui/theme/Theme.kt` — palette definitions + resolution (bulk of the work)
- `SettingsScreen.kt` — swatch row
- `MainActivity.kt` — pass palette

**Exact changes**

1. `SettingsRepository.kt` (co-locate with `ThemeMode`, matching existing pattern):
```kotlin
enum class ThemePalette { MINT, OCEAN, EMBER, VIOLET, CITRUS, ROSE, SLATE }
```
`AppSettings` gains `val palette: ThemePalette = ThemePalette.MINT`. Key: `val palette = stringPreferencesKey("theme_palette")`. Mapping: `palette = p[Keys.palette]?.let { runCatching { ThemePalette.valueOf(it) }.getOrNull() } ?: ThemePalette.MINT`. Setter: `suspend fun setPalette(v: ThemePalette) = edit { it[Keys.palette] = v.name }`.

2. `Theme.kt` restructure:
```kotlin
/** Full light+dark scheme pair for one accent palette. */
data class PaletteSchemes(val displayName: String, val light: ColorScheme, val dark: ColorScheme)

object KinetiqPalettes {
    fun schemes(palette: ThemePalette): PaletteSchemes = when (palette) { ... }
    val all: List<Pair<ThemePalette, PaletteSchemes>>   // for the swatch row and the contrast test
}

@Composable
fun KinetiqTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    palette: ThemePalette = ThemePalette.MINT,
    content: @Composable () -> Unit,
) {
    val p = KinetiqPalettes.schemes(palette)
    val scheme = when (mode) {
        ThemeMode.LIGHT -> p.light
        ThemeMode.DARK -> p.dark
        ThemeMode.AMOLED -> p.dark.copy(                       // same pattern as today's AmoledScheme
            surface = Color.Black, background = Color.Black,
            surfaceVariant = Color(0xFF121212), surfaceContainer = Color(0xFF0A0A0A),
            surfaceContainerLow = Color.Black, surfaceContainerHigh = Color(0xFF141414),
        )
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) p.dark else p.light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
```
Every palette's `light`/`dark` is built with `lightColorScheme(...)`/`darkColorScheme(...)` overriding this **full role set** (21 roles — closes review L1): `primary, onPrimary, primaryContainer, onPrimaryContainer, secondary, onSecondary, secondaryContainer, onSecondaryContainer, tertiary, onTertiary, tertiaryContainer, onTertiaryContainer, background, onBackground, surface, onSurface, surfaceVariant, onSurfaceVariant, surfaceContainer, surfaceContainerLow, surfaceContainerHigh, outline`. (`error` roles stay Material defaults — they already pass contrast.)

3. **The 7 palettes** (M3 tonal conventions: light accents ≈ tone 40 with white text, containers tone 90/10; dark accents ≈ tone 80 with tone-20 text, containers tone 30/90; surfaces hue-tinted neutrals). Values below are the spec; **the C12 contrast test is the gate — if any pair fails, the implementer darkens/lightens that value until it passes, test is authoritative.**

**1. Kinetiq Mint (default — preserves current colors)**
- Light: P `#1F8F6B` onP `#FFFFFF` PC `#BFF2DF` onPC `#0A4B36` · S `#1E7FA3` onS `#FFFFFF` SC `#C4E7F5` onSC `#003547` · T `#B35430` onT `#FFFFFF` TC `#FFDBCC` onTC `#380D00` · bg/surface `#F7FAF8` onBg/onSurface `#171D1A` · SV `#E2EAE5` onSV `#404944` · sCont `#EBF1EC` sContLow `#F1F5F1` sContHigh `#E5ECE7` · outline `#6F7973`
- Dark: P `#38E0A6` onP `#00382A` PC `#0F5C43` onPC `#BFF2DF` · S `#5ED4F0` onS `#003544` SC `#004D5F` onSC `#B8EAFF` · T `#FF8A5C` onT `#4A1500` TC `#6E2F12` onTC `#FFDBCC` · bg `#0C1411` surface `#101A16` on* `#DEE4DF` · SV `#1D2B25` onSV `#BFC9C2` · sCont `#16211C` sContLow `#101A16` sContHigh `#1C2823` · outline `#89938C`

**2. Ocean**
- Light: P `#00639B` onP `#FFFFFF` PC `#CDE5FF` onPC `#001D31` · S `#51606F` onS `#FFFFFF` SC `#D5E4F7` onSC `#0E1D2A` · T `#67587A` onT `#FFFFFF` TC `#EDDCFF` onTC `#221533` · bg/surface `#F7F9FF` on* `#181C20` · SV `#DEE3EB` onSV `#42474E` · sCont `#EBEEF3` sContLow `#F1F4F9` sContHigh `#E5E8ED` · outline `#72777F`
- Dark: P `#98CBFF` onP `#003354` PC `#004A77` onPC `#CDE5FF` · S `#B9C8DA` onS `#233240` SC `#394857` onSC `#D5E4F7` · T `#D2BFE7` onT `#372A4A` TC `#4E4062` onTC `#EDDCFF` · bg `#0E1116` surface `#111418` on* `#E0E2E8` · SV `#1E242B` onSV `#C2C7CF` · sCont `#171B21` sContLow `#111418` sContHigh `#1D222A` · outline `#8C9199`

**3. Ember**
- Light: P `#9A4500` onP `#FFFFFF` PC `#FFDBC9` onPC `#331200` · S `#77574A` onS `#FFFFFF` SC `#F5DED3` onSC `#2C160B` · T `#6C5D2F` onT `#FFFFFF` TC `#F6E1A6` onTC `#221B00` · bg/surface `#FFF8F5` on* `#221A15` · SV `#F0DFD6` onSV `#52443C` · sCont `#F8ECE4` sContLow `#FCF1EA` sContHigh `#F2E6DE` · outline `#84746B`
- Dark: P `#FFB68F` onP `#532200` PC `#763300` onPC `#FFDBC9` · S `#E7BEAD` onS `#442A1E` SC `#5D4033` onSC `#F5DED3` · T `#D9C58D` onT `#3B2F05` TC `#534619` onTC `#F6E1A6` · bg `#170F0B` surface `#1A120D` on* `#F0DFD7` · SV `#2B211B` onSV `#D7C2B8` · sCont `#211812` sContLow `#1A120D` sContHigh `#281E18` · outline `#A08D84`

**4. Violet** (baseline M3 palette — known-good values)
- Light: P `#6750A4` onP `#FFFFFF` PC `#EADDFF` onPC `#21005D` · S `#625B71` onS `#FFFFFF` SC `#E8DEF8` onSC `#1D192B` · T `#7D5260` onT `#FFFFFF` TC `#FFD8E4` onTC `#31111D` · bg/surface `#FDF7FF` on* `#1C1B20` · SV `#E7E0EC` onSV `#49454F` · sCont `#F1EBF4` sContLow `#F7F1FA` sContHigh `#EBE5EE` · outline `#79747E`
- Dark: P `#D0BCFF` onP `#381E72` PC `#4F378B` onPC `#EADDFF` · S `#CCC2DC` onS `#332D41` SC `#4A4458` onSC `#E8DEF8` · T `#EFB8C8` onT `#492532` TC `#633B48` onTC `#FFD8E4` · bg `#101014` surface `#141218` on* `#E6E0E9` · SV `#26232C` onSV `#CAC4D0` · sCont `#1B1920` sContLow `#141218` sContHigh `#211F26` · outline `#938F99`

**5. Citrus**
- Light: P `#4C6700` onP `#FFFFFF` PC `#CDEF83` onPC `#151F00` · S `#5B6147` onS `#FFFFFF` SC `#DFE6C4` onSC `#181E09` · T `#396661` onT `#FFFFFF` TC `#BCECE6` onTC `#00201D` · bg/surface `#FAFAF0` on* `#1B1C16` · SV `#E3E4D3` onSV `#46483B` · sCont `#EEEEE2` sContLow `#F4F4E8` sContHigh `#E8E9DC` · outline `#77786A`
- Dark: P `#B1D264` onP `#263500` PC `#394E00` onPC `#CDEF83` · S `#C3CAA9` onS `#2D331B` SC `#434930` onSC `#DFE6C4` · T `#A0D0CA` onT `#013733` TC `#1F4E49` onTC `#BCECE6` · bg `#10110B` surface `#13140D` on* `#E4E3D6` · SV `#22231A` onSV `#C6C8B5` · sCont `#191A12` sContLow `#13140D` sContHigh `#1F2017` · outline `#909283`

**6. Rose**
- Light: P `#984061` onP `#FFFFFF` PC `#FFD9E2` onPC `#3E001D` · S `#74565F` onS `#FFFFFF` SC `#F7DAE1` onSC `#2B151C` · T `#7C5635` onT `#FFFFFF` TC `#FFDCC1` onTC `#2E1500` · bg/surface `#FFF8F8` on* `#22191C` · SV `#F2DDE1` onSV `#514347` · sCont `#FAEBEE` sContLow `#FEF1F3` sContHigh `#F4E5E8` · outline `#837377`
- Dark: P `#FFB1C8` onP `#5E1133` PC `#7B2949` onPC `#FFD9E2` · S `#E3BDC6` onS `#422931` SC `#5A3F47` onSC `#F7DAE1` · T `#EFBD94` onT `#48290B` TC `#62401F` onTC `#FFDCC1` · bg `#160E10` surface `#191113` on* `#EFDFE1` · SV `#2A2124` onSV `#D5C2C6` · sCont `#201618` sContLow `#191113` sContHigh `#261C1F` · outline `#9E8C90`

**7. Slate (mono/low-chroma)**
- Light: P `#435E6E` onP `#FFFFFF` PC `#C6E1F2` onPC `#0B2430` · S `#5B6670` onS `#FFFFFF` SC `#DFE5EB` onSC `#171E24` · T `#6B5F66` onT `#FFFFFF` TC `#F4DEE8` onTC `#251A20` · bg/surface `#F9FAFB` on* `#1A1C1E` · SV `#E0E4E8` onSV `#44474B` · sCont `#EDEFF2` sContLow `#F3F4F6` sContHigh `#E7EAED` · outline `#74777C`
- Dark: P `#A6C8DC` onP `#0B3244` PC `#274A5C` onPC `#C6E1F2` · S `#C2CCD6` onS `#2C3640` SC `#434D57` onSC `#DFE5EB` · T `#D7C1CD` onT `#3A2B34` TC `#52424B` onTC `#F4DEE8` · bg `#0F1113` surface `#121416` on* `#E2E4E6` · SV `#22262A` onSV `#C4C8CC` · sCont `#181B1D` sContLow `#121416` sContHigh `#1E2124` · outline `#8E9297`

4. `MainActivity.kt:68`: `KinetiqTheme(mode = settings.theme, palette = settings.palette) { ... }`.
5. Settings UI (`SettingsScreen.kt`, inside the existing "Theme" section after the mode chips):
```kotlin
Text("Accent", style = MaterialTheme.typography.bodyMedium)
FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    KinetiqPalettes.all.forEach { (palette, schemes) ->
        val swatch = if (isSystemInDarkTheme()) schemes.dark.primary else schemes.light.primary
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(swatch)
                .then(if (settings.palette == palette)
                    Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                .clickable(onClickLabel = schemes.displayName) { viewModel.set { setPalette(palette) } }
                .semantics { contentDescription = "${schemes.displayName} theme" +
                    if (settings.palette == palette) ", selected" else "" },
        )
    }
}
Text(KinetiqPalettes.schemes(settings.palette).displayName, style = MaterialTheme.typography.bodySmall,
     color = MaterialTheme.colorScheme.onSurfaceVariant)
```
Display names: `"Kinetiq Mint"`, `"Ocean"`, `"Ember"`, `"Violet"`, `"Citrus"`, `"Rose"`, `"Slate"`.
6. Dynamic color: none exists, none added — record as a code comment in `Theme.kt` (`// Deliberately no dynamicColorScheme: palettes are hand-tuned and contrast-tested.`). Widget: no change (GlanceTheme, documented above).

**Acceptance criteria**
- Selecting each swatch recolors the whole app immediately (bottom-bar pill, warning cards, chips — the previously-leaking `secondaryContainer`/`tertiaryContainer`/`surfaceContainer` roles included; **no Material baseline purple visible in any palette × mode**).
- AMOLED mode under every palette has pure `#000000` background/surface with the palette's accents intact.
- Existing users see Kinetiq Mint unchanged (no stored key → MINT; current hexes preserved).

**Dependencies**: none; C12 must land in the same PR.

### C12. Theme tests

**Files**: new `app/src/test/java/au/mark/kinetiq/ThemePaletteContrastTest.kt`, new `app/src/test/java/au/mark/kinetiq/SettingsRoundTripTest.kt`

**`ThemePaletteContrastTest`** — pure math, no Android runtime (`androidx.compose.ui.graphics.Color` and `lightColorScheme`/`darkColorScheme` are plain class constructions; if class-loading of material3 on the JVM complains, annotate `@RunWith(RobolectricTestRunner::class)` — Robolectric is already a test dependency):
```kotlin
private fun luminance(c: Color): Double  // WCAG relative luminance from sRGB channels
private fun contrast(a: Color, b: Color): Double = (maxOf(l1,l2) + 0.05) / (minOf(l1,l2) + 0.05)
```
- `` `all palettes meet 4_5 to 1 on all on-role pairs in light and dark` ``: iterate `KinetiqPalettes.all` × {light, dark} × pairs `[onPrimary/primary, onPrimaryContainer/primaryContainer, onSecondary/secondary, onSecondaryContainer/secondaryContainer, onTertiary/tertiary, onTertiaryContainer/tertiaryContainer, onSurface/surface, onBackground/background, onSurfaceVariant/surfaceVariant]`; assert `contrast >= 4.5` with a failure message naming palette/mode/pair.
- `` `amoled overrides keep onSurface legible on pure black` ``: for each palette, `contrast(dark.onSurface, Color.Black) >= 4.5`.
- `` `primary is distinguishable from surface in both modes` ``: `contrast(primary, surface) >= 3.0` (non-text UI-component minimum) — catches a swatch that vanishes.

**`SettingsRoundTripTest`** — `@RunWith(RobolectricTestRunner::class)`, `runTest`, real `SettingsRepository(ApplicationProvider.getApplicationContext())`:
- `` `palette and rest mode default round-trip through datastore` ``: `setPalette(ThemePalette.EMBER); setDefaultRestMode(RestMode.CONTINUOUS)`; assert `current().palette == EMBER && current().defaultRestMode == CONTINUOUS`.
- `` `unknown stored palette string falls back to mint` ``: write `"NOT_A_PALETTE"` directly to the `theme_palette` key via the test's own DataStore handle (or assert the decode helper behavior by writing through `context.dataStore` — mirror the `runCatching { valueOf }` fallback); assert `current().palette == ThemePalette.MINT`.
- `` `continuous notice flag persists` ``.

**Acceptance criteria**: both test classes pass in `./gradlew testDebugUnitTest`; contrast test fails loudly (named pair) if any hex above needs adjusting.

**Dependencies**: C11.

---

## Implementation sequence

1. **PR-1 (models + generator)**: A1 (enum, config field, settings keys) + A4 (generator + service next-up cue) + A3 regression test + `RestModeTest`. Highest risk, no UI churn yet.
2. **PR-2 (builder + settings UI)**: A1's Builder chips/notice dialog/Settings default row + B10l (preview protection — same ViewModel) + B10e (`displayName`, touches the same Builder lines).
3. **PR-3 (player)**: A2, B5, B6, B8, B9 (single file, `PlayerScreen.kt`) + B7 (service) — flag Phase 1 resume-countdown dependency in the PR body.
4. **PR-4 (theming)**: C11 + C12 together.
5. **PR-5 (quick wins)**: remaining B10 items (a–d, f–k, m–q) + `DisplayNameTest`.

## Back-compat risk register (explicit)

| Risk | Verdict |
|---|---|
| `GeneratorConfig.restMode` added — saved workouts, history `sessionJson`, `last_config`, disk snapshot, export files | **Safe**: all decoders use `ignoreUnknownKeys = true`; new field has a default (`STANDARD`); `encodeDefaults = true` writes it explicitly going forward. Covered by the explicit decode test in `RestModeTest`. |
| Removing `workRestRatio` | **Forbidden** — kept, `@Deprecated`, still serialized. Removing it would break nothing on *read* (unknown key ignored) but would silently change old exports' semantics documentation; keep for auditability. |
| Old plans replayed under CONTINUOUS-aware service | Safe: behavior keys off `session.config.restMode == CONTINUOUS`; legacy sessions decode as STANDARD. |
| New DataStore keys (`rest_mode`, `theme_palette`, `continuous_notice_seen`) | Safe: missing → defaults; corrupt strings → `runCatching` fallback (tested). |
| Removing the ratio slider | Old `last_config` still decodes; its stored ratio is simply unused. No migration needed. |
| ExportFile `FORMAT_VERSION` | **Do not bump** — schema is additive-with-defaults only; v1.1 ⇄ v1.2 files interchange cleanly (the codec already warns on newer versions). |
| Room | Untouched — no entity changes anywhere in Phase 2. |

### Critical Files for Implementation
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/data/model/Session.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/domain/generator/WorkoutGenerator.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/ui/screens/player/PlayerScreen.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/data/repo/SettingsRepository.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/ui/theme/Theme.kt