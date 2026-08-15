# Kinetiq reliability audit — VERIFIED findings after adversarial review

Pass 1 raised 26; pass 2 independently verified each against source. 5 rejected outright, 2 reclassified
as deliberate design, several severities corrected, 3 new findings added.

## TWO FRAMEWORK FACTS pass 2 established with evidence — rely on these
**(A) `lifecycleScope` on a LifecycleService is `Dispatchers.Main.immediate`.** lifecycle 2.8.7 constructs
`LifecycleCoroutineScopeImpl(this, SupervisorJob() + Dispatchers.Main.immediate)`. `Main.immediate`'s
`isDispatchNeeded()` returns false when already on main, so `launch { }` from `onStartCommand` executes its
body INLINE, SYNCHRONOUSLY, up to the first real suspension. Some bail-outs are therefore synchronous
(deterministic), others post-suspension (racy).
**(B) Room 2.6.1 Flow queries emit downstream in the COLLECTOR's context.** `CoroutinesRoom.createFlow` is
`flow { coroutineScope { launch(queryContext) { ... resultChannel.send(...) }; emitAll(resultChannel) } }`.
Only `Callable.call()` runs on the query dispatcher; `emitAll` runs in the flow builder's collector context.
So any operator chained AFTER the DAO flow runs wherever the terminal collector runs.
→ The "Room emits off-main so the map is off-main" hypothesis is WRONG. Pass 1 was right about R-06.

## REJECTED — do not implement fixes for these
- **R-04 LaunchedEffect(state) deep-compare.** Mechanism false. `syncState` uses `current.copy(...)` which
  PRESERVES the `session` reference; data-class `equals` opens with `if (this === other) return true`, so
  it short-circuits. No deep comparison exists. Residual cost is a few hundred bytes/sec. `PlayerScreen.kt:152`
  is in fact correctly memoising.
- **R-16 unguarded decodeFromString.** The payload is always a re-encode of a GeneratedSession this same
  process just decoded. `SavedWorkoutEntity.toModel()` is `runCatching{}.getOrNull()` + `mapNotNull`
  (`WorkoutRepository.kt:44,118-120`) so a poisoned saved workout is DROPPED before it can be started;
  `HistoryEntry.session` likewise (`:131`) with both callers bailing on null. No foreign JSON reaches the line.
- **R-21 startService zombie.** All 7 call sites are in PlayerScreen, which returns before rendering them
  unless a live session exists (`val s = state ?: return` at `:135`). SessionStateHolder is an in-process
  @Singleton, so state != null implies the service is alive. Background-start exception unreachable (all
  call sites are click handlers in a visible Activity, and the service is already running).
- **R-25 registerReceiver flag.** ContextImpl waives the requirement when EVERY action in the filter is a
  protected broadcast; there is exactly one and it qualifies. Adding a flag would be noise.
- **R-26 library filter.** Premise false — animations invalidate DRAW only, never composition (pass 1
  established this itself). The filter runs on a chip tap: 3 predicates over 90 exercises. Microseconds.

## RECLASSIFIED as deliberate design — DO NOT "FIX"
- **R-19 tick clamp.** This is intentional, documented AND TESTED. Comment at `SessionEngine.kt:68-70`:
  "The workout must not fast-forward steps or bill the gap as exercise at this step's MET — the gap is
  simply dropped." `SessionEngineTest.kt:30-36` ASSERTS it: a 600_000ms raw delta must consume exactly 2s
  of step and 2s of calories. Changing this breaks a passing test encoding the intent. Carrying the excess
  would fast-forward users through exercises they never performed. Pass 1's supporting argument was also
  weak — it blamed main-thread contention from R-02/R-03, costed at 30-60µs/frame, 3 orders of magnitude
  short of stalling a 200ms ticker.
- **R-20 mtime freshness.** No better alternative exists. The snapshot must survive process death AND
  reboot; elapsedRealtime resets at boot so it cannot be a cross-reboot baseline. The only monotonic design
  is elapsedRealtime + Settings.Global.BOOT_COUNT with a wall-clock fallback — substantial machinery for a
  staleness heuristic on an NITZ/NTP-disciplined phone. Once R-01 is fixed this finding has NO remediation
  value left. Drop it.

---

# SURVIVING FINDINGS TO FIX (ranked)

## L-1 — R-01: FGS watchdog crash · **CRITICAL**
Conclusion and severity right; pass 1 aimed it at the WRONG code path. Sub-claim breakdown:
- (a) TRUE and fully synchronous: `WorkoutSessionService.kt:179-181` `readSnapshot(...) ?: return@launch`
  is the FIRST statement in the launch, so per fact (A) it runs inline inside onStartCommand. goForeground()
  (the only ServiceCompat.startForeground, `:278`) is at `:222`. Null return → startForeground never called,
  nothing calls stopSelf() → ~5s watchdog → ForegroundServiceDidNotStartInTimeException. Entries are
  `startForegroundService` (`:717`, `:749`).
- (b) **THE REAL TRIGGER — the Summary screen's stopped-snapshot Resume button.** `SummaryScreen.kt:175`
  gates on `hasStoppedSnapshot(context)` (`:707-709`) = EXISTENCE + MTIME ONLY, no read, no parse. Contrast
  the Home path which gates on a full `readSnapshot()` (`HomeScreen.kt:81`) and so can never surface a
  button for an unparseable file. Worse, it is evaluated ONCE DURING COMPOSITION and `summary` is a
  StateFlow that does not change while the screen sits there — so once drawn the Resume button stays drawn
  FOREVER, long after the 10-minute window (`STOPPED_SNAPSHOT_VALID_MS`, `:701`) expires. And the app
  explicitly teaches this affordance: `PlayerScreen.kt:99` says "You can resume from the summary for the
  next 10 minutes."
- (c) **THE CRASH ALSO DESTROYS THE WORKOUT RECORD — pass 1 missed this.** `SummaryScreen.kt:73-80`
  `resumeStopped` deletes the history row (`workoutRepository.deleteHistory(summary.historyId)`) and calls
  `stateHolder.clearCompleted()` BEFORE anything confirms the restore can succeed. Full sequence needing no
  exotic timing: stop early → summary → distracted 11 min → tap Resume → history row deleted → service
  finds no snapshot and bails → 5s later app killed → on relaunch the workout is gone from history, from
  the summary, and from the resume offer. **Crash PLUS permanent data loss on an advertised button.**
- (d) `startSession`'s suspend-before-foreground (`:133-135` before `goForeground()` at `:161`) — TRUE but
  DOWNGRADE to a hardening item. Normally tens of ms; no evidence it fires in practice.
- (e) `steps.firstOrNull() ?: return@launch` (`:137`) — TRUE, marginally reachable via imported history
  with an empty plan. **This is the same root cause as the security track's S-2/N-01 — coordinate.**
- (f) `payload == null` at `:92` — REJECTED. The only producer `start()` (`:739-746`) always sets the extra.
- (g) The 6h hasSnapshot race — real but negligible (Home gates on a full readSnapshot).
- (h) NOT a finding: ACTION_START while live (`:87-88`) calls updateNotification, not startForeground.
  AOSP's `sendServiceArgsLocked` clears fgRequired without arming the timeout when already foreground.
FIX: every path out of onStartCommand must reach startForeground or stopSelf unconditionally; and
resumeStopped must not destroy the history row before the restore is confirmed.

## L-2 — R-08: destructive migration · **HIGH**
`AppModule.kt:30-33` `.fallbackToDestructiveMigration()`, `KinetiqDatabase.kt:16-17` `version = 1,
exportSchema = false`. Next entity change + version bump silently drops every table. No checked-in schema
JSON to author a Migration against or write a migration test with.
Pass 2 checked the mitigation pass 1 didn't: backup_rules.xml and data_extraction_rules.xml DO include
`domain="database"`, but that does NOT help — those restore at device setup, not across an in-place app
update, which is exactly when destructive migration fires. Offline-only app, no server copy → total
unrecoverable loss. (This is also the security track's K-09, routed here.)

## L-3 — R-06: history JSON parsed on the MAIN THREAD · **HIGH**
Chain verified end to end: `Daos.kt:56-57` (no LIMIT) → `WorkoutRepository.kt:59` `.map` chained AFTER
Room's flow → per fact (B) runs in the collector's context → `toModel()` (`:122-132`) decodes
GeneratedSession per row → all three collectors are `stateIn(viewModelScope, ...)` =
Dispatchers.Main.immediate (`HistoryScreen.kt:96`, `HomeScreen.kt:112`, `PlanScreen.kt:48-55`).
WORSE than pass 1 said, both verified by grep:
- **No UI consumer reads `entry.session` ANYWHERE.** Grepped across ui/screens/history/, ui/screens/plan/
  and domain/plan/ — only hit is an unrelated `state.sessionsPerWeek`. The most expensive field decoded is
  100% dead for all three screens.
- `HomeViewModel.uiState` also combines `savedWorkouts()`, whose `toModel()` (`:118-120`) decodes each
  saved workout's full session on the same main-thread collection.
Volume: ~10-15KB per session (60-80 SessionSteps × ~150B). 100 sessions ≈ 1MB parsed on main on every
Home/History/Plan entry after the 5s WhileSubscribed gap and on every session finish; 365 ≈ 4MB. Linear,
unbounded. New users see nothing.
FIX: projection query that never selects sessionJson.

## L-4 — NEW-1 (NEW): finishSession's `finally` can destroy a NEWLY STARTED session · **MEDIUM**
`finishSession` (`:491-560`) does substantial suspend work — `settingsRepo.current()` (`:493`),
`healthConnect.writeSession(...)` (`:511`, cross-process IPC that can take seconds), `addHistory` (`:520`),
`KinetiqWidget().updateAll(...)` (`:533`) — then a `finally` that unconditionally does `deleteSnapshot(...)`,
`stateHolder.update(null)`, `stopSelf()`. Nothing ties that block to the session it was finishing.
Meanwhile `shouldIgnoreStart` (`:45`) is `currentFinished == false`, so once finishSession sets
`finished = true` at `:483`, a NEW ACTION_START is accepted and startSession runs to completion.
Reachable: stop from the notification while on Home with Health Connect enabled (finish coroutine parked in
the HC IPC) → tap "Repeat last" → new session starts, Player opens → finish coroutine resumes →
`stateHolder.update(null)` wipes the NEW session and `stopSelf()` stops the service. User sees the workout
they just started vanish and the app bounce back to Home.
Note `stopSelf()` not `stopSelfResult(startId)` — the classic form of this bug.

## L-5 — R-17: 130KB asset parsed + seeded on the main thread · **MEDIUM (HIGH on first run)**
`ExerciseRepository.loadAndSeed()` (`:46-68`) is suspend but never switches dispatcher. Asset is
**130,509 bytes, 90 exercises** (verified). Callers all main-dispatched:
- `WorkoutSessionService.howToFor` (`:414-419`) ← `speakHowToAt` (`:396-402`) = Main.immediate, triggered
  from `voice.warmUp { ... speakHowToAt(0) }` at `:172` — i.e. **during the GET-READY countdown at the
  start of every session, on the shared main thread.**
- `PlayerViewModel.explainAgain()` (`PlayerScreen.kt:75-79`), `LibraryViewModel.init` (`LibraryScreen.kt:68-71`)
- `BuilderViewModel.generate()` (`BuilderScreen.kt:123-148`) — pass 1's extra claim CONFIRMED: the entire
  564-line WorkoutGenerator also runs inline on viewModelScope with no withContext. The `generating = true`
  flag at `:125` shows the author expected this to be slow.
Cached via `@Volatile cache` (`:34-39`) so once per process — but the first-run/schema-bump path
additionally does 90 `json.encodeToString` calls plus Room inserts (`:59-66`) on main = the 200-500ms case.

## L-6 — R-14: sticky widget intent restarts a workout · **MEDIUM**
Verified empirically from the manifest: `AndroidManifest.xml:35-45` has **NO `android:configChanges`
attribute at all**. `screenOrientation="portrait"` suppresses rotation recreation, but `uiMode` (dark-mode
toggle, incl. Motorola's scheduled auto dark mode), `fontScale`, `locale`, `density` and process death ALL
recreate this Activity, and onCreate calls `handleLaunchIntent(intent)` unconditionally (`MainActivity.kt:70`).
Sharper than pass 1 stated: `onNewIntent` (`:79-82`) calls handleLaunchIntent but **never calls
`setIntent(intent)`**, so `getIntent()` permanently returns whatever intent originally created the task.
`repeatLastWorkout` (`:43-59`) is guarded against clobbering a LIVE session but not against starting a NEW
one once `state.value == null`. User story: launch from widget, do a workout, finish; hours later, same
task, phone switches to dark mode at sunset → Activity recreated → foreground service starts, wake lock
taken, TTS announces "Starting Full Body. 14 steps, about 30 minutes." unprompted.

## L-7 — R-12: wake lock + 5Hz ticker through indefinite pauses · **MEDIUM**
`acquireWakeLock` (`:282-289`) called from `goForeground()` (`:279`); PARTIAL_WAKE_LOCK,
setReferenceCounted(false), acquire(4h); released only in onDestroy (`:680`). Ticker (`:293-307`) keeps
running while paused. `autoPause()` (`:261-265`) fires on ACTION_AUDIO_BECOMING_NOISY and on
MODE_RINGTONE/MODE_IN_CALL (`:236-249`); resume is manual by design.
The acquire(4h) timeout DOES bound the wake lock, but nothing bounds the foreground service or the ticker —
and on Android 15 the `health` and `mediaPlayback` FGS types are EXEMPT from the timeout regime, so it runs
until the user notices.

## L-8 — R-07: widget deserialises the whole history table · **MEDIUM**
`KinetiqWidget.kt:49` `historyOnce()` → `Daos.kt:65-66` `SELECT * FROM session_history` (no order, no
limit) → same `toModel()` decoding sessionJson + blocksJson per row — all to produce
`history.map { it.startedAtEpochMs }` for StreakCalculator (`:52-54`). At 365 rows ≈ 4MB of JSON to extract
365 Longs.
Pass 1 OVERSTATED: `updatePeriodMillis="1800000"` is the PLATFORM MINIMUM (the system clamps below 30 min),
not an aggressive choice. And periodic widget updates **do not wake the device** — they are delivered only
when it is already awake. So "wakes every 30 min" is wrong. Cost is CPU/allocation piggybacking on existing
wakeups, bounded by the 10s broadcast budget which ~300ms comfortably fits inside. Same fix as L-3.

## L-9 — R-05: Home re-reads the snapshot 5×/s during a session · **MEDIUM**
`HomeScreen.kt:161` `LaunchedEffect(playerState) { viewModel.refreshSnapshot() }`; `refreshSnapshot`
(`:79-85`) launches into viewModelScope not the effect's scope, so a restart does not cancel the in-flight
read. playerState changes every 200ms while a session runs, and Home is reachable during a session
(the "Workout in progress — return to player" button at `:194` proves it).
And the result is ENTIRELY DISCARDED: the resume card is inside the `else` branch of
`if (playerState != null)` (`:192-209`), so while a session is live the parsed value can never be rendered.
Downgraded from High because it is off-main and bounded per-read — straightforward waste, not a battery
catastrophe.

## L-10 — R-18: reminder chain self-terminates · **MEDIUM**
`Reminders.kt:77-84`. `settingsRepository.current()` unguarded; CoroutineWorker catches the throw and
returns `Result.failure()`; no `Result.retry()`. `scheduler.schedule(...)` at `:81` is the ONLY thing
enqueuing the next occurrence — grepped, the only other caller is `SettingsViewModel.updateReminder`
(`SettingsScreen.kt:87-91`), i.e. the user re-editing the setting. Nothing re-arms on app start,
BOOT_COMPLETED, or MY_PACKAGE_REPLACED. One transient failure silently ends reminders permanently and the
user's only recovery is to toggle the setting without knowing they need to.
DST sub-claim correct but minor: `delayToNext` (`:55-65`) computes a wall-clock Duration via
LocalDateTime.now() which WorkManager applies as an elapsed-time delay → one reminder shifts an hour, twice
a year.

## L-11 — R-02: per-frame motion-path solve · **MEDIUM** (was High)
Mechanism verified exactly. `ExerciseAnimationView.kt:352-369` loop `for (i in 0..28)` reads nothing from
`timeMs` — pure function of anim.id and canvas size, recomputed every frame. `poseAt`
(`AnimationSpec.kt:66-90`) ends in `groundContact` whose `clearKnee` (`:115-132`) runs a 2-iteration
numeric solve with acos/toDegrees; `Rig.solveSide` (`Rig.kt:116-190`) does ~20 toRadians + ~20 sin/cos and
allocates ~22 objects. So ~29 × (~50 transcendental ops + ~30 allocations).
HONEST COST (pass 1 overreached): ~1,450 transcendental ops ≈ 22µs + ~870 TLAB allocations ≈ 7µs =
**~30-60µs per frame per view** on a Dimensity-class 2025 midrange. Against the Edge 60 Fusion's 8.3ms
budget at 120Hz that is 0.7% for the single Player view — **NOT perceptible; calling it "jank" is wrong.**
On Library with ~8 visible cards it is ~0.5ms/frame (6%) plus ~**30 MB/s of garbage** during scroll — real
sustained allocation pressure and battery cost, not a dropped frame.
Two things pass 1 got right: the loop guards on `pathJoint != NONE`, and **25 of the 50 KeyframeAnims set a
non-NONE path joint** (11 PELVIS, 8 WRIST, 3 ANKLE, 3 HEAD) so it fires for half the library. The KDoc at
`ExerciseAnimationView.kt:39` ("One pose solve + ~20 filled paths per frame — trivially cheap at 60fps") is
FACTUALLY FALSE for those 25: it is 30 pose solves and 30 rig solves.
FIX: hoist into `remember(anim.id)` keyed on canvas size. Worth doing because it is free, not because users
are suffering. Correct the KDoc.

## L-12 — R-15: finishSession has finally but no catch · **LOW-MEDIUM** (was Medium)
Structure verified at `:491-560`. `settingsRepo.current()` (`:493`) and `completedBlocks(...)` (`:497`) sit
outside any runCatching; everything after is guarded (`:510`, `:519`, `:533`). No catch. The `finally`
(`:551-559`) deletes the snapshot, nulls the state and stopSelf()s regardless, and the exception escapes
lifecycleScope.launch with no CoroutineExceptionHandler → process crash. A throw before `:519` means the
session is never written to history and `stateHolder.completed(...)` at `:535` never runs.
DOWNGRADED because pass 2 could not find a plausible thrower: `completedBlocks` (`SessionEngine.kt:212-236`)
has its divisor guarded by `.coerceAtLeast(1)` and is otherwise total; `settingsRepo.current()` can throw
IOException/CorruptionException from DataStore (no ReplaceFileCorruptionHandler configured) but rarely.
Structural point stands; `settings` is only needed for two booleans at `:509` so wrapping costs nothing.

## L-13 — R-03: per-frame Path/Shader allocation · **LOW** (was High) — MERGE WITH L-11
Counts verified: `capsule` allocates 1 Path + 2 Rect (`:144-149`), called 11× per figure (drawSide ×5 ×2
sides at `:194-199`, plus the neck capsule at `:242`); `spineBlob` allocates 1 Path + ArrayList + 9 Offset +
FloatArray (`:160-170`); motion path is a 13th. `drawShadow` builds a fresh Brush.radialGradient every
frame (`:219-222`) — since ShaderBrush caches createShader PER INSTANCE, a new instance per frame does mean
a new native RadialGradient every frame.
All true, and ~26µs/frame/view. Real waste, invisible for a single view, meaningful only as part of the
Library-scroll allocation total already counted in L-11. **Not a separate finding — same file, same fix
session.**

## L-14 — R-24 sub-claim: next-up preview animates while paused · **LOW**
CONFIRMED at `PlayerScreen.kt:251` — the next-up preview passes animationId, modifier, contentDesc and
OMITS `paused`, defaulting to false (`ExerciseAnimationView.kt:46`). This card renders during
rest/transition steps, and pausing during a rest does not remove it, so the 84dp preview keeps animating at
full frame rate while the main animation beside it is frozen. One-word fix.
REJECTED half: `PlayerScreen.kt:343` PrepareView also omits `paused` but is only rendered under
`if (s.inPrepare && !s.paused)` (`:137`) — paused-by-construction, moot.
The Float-precision main claim is RECLASSIFIED as a nit: frame deltas ~16.7ms stay vastly larger than the
ULP (0.25ms past ~35min), so accumulation never stalls; effect is ≤0.125ms of phase jitter, ~1.5% of a
frame, on an animation whose phase is `timeMs % durationMs`. Invisible.

## L-15 — R-11: audio-focus race · **LOW** (was Medium); claimed symptom is WRONG
Race is real: `focusRequest` (`VoiceCoach.kt:67`) is plain non-@Volatile, written by `requestFocus()`
(`:222-229`) and `abandonFocus()` (`:231-234`), reached from speak()/stopSpeaking() on main, from
countdownBeeps() on the Main scope, and from `onUtteranceFinished()` (`:160-162`) invoked by the
UtteranceProgressListener (`:148-156`) on a BINDER thread. Three writers, no synchronisation.
BUT the claimed "music stays ducked forever" is the LESS likely half: `onUtteranceFinished` does
`utteranceCount.updateAndGet {...}` (atomic RMW) immediately BEFORE `abandonFocus()`, and `speak()` does
`incrementAndGet()` AFTER `requestFocus()`. That incidental happens-before edge means the binder thread
reliably SEES main's write and does abandon it. The unpaired direction is the reverse: the binder thread's
`focusRequest = null` has no subsequent atomic write, so main's next `requestFocus()` may read a stale
non-null and take the `if (focusRequest != null) return` early exit (`:223`).
REAL SYMPTOM: one cue fails to duck the user's music, self-correcting on the next abandon.

## L-16 — NEW-2 (NEW): writeStoppedSnapshot is a main-thread non-atomic write · **LOW**
`:566-582` ends with `stoppedSnapshotFile(this).writeText(json.encodeToString(...))`. Two problems:
- Runs in finishSession's `finally` inside `lifecycleScope.launch` = Main.immediate, after suspend points
  that resume on main → a synchronous ~12KB DISK WRITE ON THE MAIN THREAD on every user stop. The comment
  at `:565` justifies SYNCHRONOUS, which is fine, but synchronous does not require main-thread;
  `withContext(NonCancellable + IO)` preserves the guarantee.
- Unlike `maybeSnapshot` (`:607-610`), it writes DIRECTLY to the target with no tmp + rename. A kill
  mid-write leaves a truncated file that still passes `hasStoppedSnapshot()` (existence + mtime only) —
  precisely the gate `SummaryScreen.kt:175` uses. **So a torn write is an independent SECOND trigger for
  the L-1 crash-plus-data-loss path, reachable without any window expiry.**

## L-17 — NEW-3 (NEW): disk I/O inside composition on the Summary screen · **LOW**
`SummaryScreen.kt:175` calls `hasStoppedSnapshot(context)` — File.exists() + File.lastModified(), two
syscalls — DIRECTLY IN THE COMPOSABLE BODY, on every recomposition. This contradicts the codebase's own
documented rule at `HomeScreen.kt:76` ("Snapshot check is disk I/O — never during composition"), and it is
structurally what makes the L-1 stale-button bug possible: because the value is computed during composition
and nothing invalidates it, the check can never re-run.

## L-18 — R-23: unbounded history · **LOW** (was Medium)
No deleteHistoryOlderThan, no retention setting, no LIMIT/OFFSET in `Daos.kt:56-66`; `HistoryScreen.kt:177`
`items(state.entries, ...)` retains every entry with its fully deserialised session in a StateFlow.
**O(n²) sub-claim REJECTED**: `ExportImport.kt:116`/`:124` do `existing.any {...}` per imported entry —
365 × 365 ≈ 133k comparisons of a Long and a String. Microseconds. Not a finding.
`buildExport` building the whole payload as one String and `SettingsScreen.kt:113-114` doubling it via
toByteArray() is real but only matters at multi-year scale on a low-memory device.
HONEST FRAMING: not an independent defect — it is the REASON L-3 and L-8 get worse without bound. Fix as
part of them.

## L-19 — R-22: mediaPlayback FGS type · **LOW** (policy risk only)
Pass 2 checked what Android 14/15 ACTUALLY enforce: **the platform does NOT runtime-verify a mediaPlayback
prerequisite.** Android 14's per-type prerequisites are enforced at runtime only for microphone/camera
(while-in-use), location, mediaProjection (needs an active projection), and health (needs one of
BODY_SENSORS/HIGH_SAMPLING_RATE_SENSORS/ACTIVITY_RECOGNITION, else SecurityException). mediaPlayback
carries a PLAY STORE POLICY expectation of actual playback, not a platform check. Android 15 added FGS
timeouts for dataSync/mediaProcessing; mediaPlayback is exempt and gained no MediaSession-active requirement.
So `:267-280` is CORRECT AND LEGAL today. The inactive MediaSessionCompat (`:80`, attached at `:663-667`)
is a cosmetic inconsistency — the real consequence is only that MediaStyle won't render the compact media
UI as intended.
**POST_NOTIFICATIONS sub-claim REJECTED**: the app DOES request it at `OnboardingScreen.kt:124`. If denied,
an FGS still runs and the notification is suppressed — graceful degradation, no crash.
Remaining risk is Play-policy only, and this app is sideloaded.

## L-20 — R-13: 5s snapshot volume · **INFORMATIONAL** (was Medium); impact claims FALSE
Mechanism real (`:584-613` re-serialises the entire immutable GeneratedSession every 5s). Consequences not:
- Size: ~10-15KB not 30KB. 540 writes ≈ 6MB/session, not 16MB.
- Flash wear: ~2GB/year against UFS endurance measured in PETABYTES. Not a consideration.
- Battery: 540 small buffered writes over 45 min on Dispatchers.IO (`:590`). Negligible.
- No-fsync (`:610`): failure mode is a truncated snapshot after abrupt power loss, and `readSnapshot`'s
  `runCatching{}.getOrNull()` (`:729-733`) plus Home's full-read gate handle it cleanly.
Genuine efficiency nit; no user suffers.

## L-21 — R-09: retryInit orphans TTS · **INFORMATIONAL** (latent, currently UNREACHABLE)
Asymmetry real: `VoiceCoach.kt:109-115` sets `tts = null` with no shutdown(), unlike `:94`/`:99`.
BUT pass 2 could not reach it: `retryInit` opens with `if (ready) return` so it only proceeds when !ready,
and BOTH call sites are gated on FAILED (`PlayerScreen.kt:187` renders only inside
`if (voiceStatus == TtsStatus.FAILED)` at `:173`; `SettingsScreen.kt:98` checks
`if (voiceCoach.status.value == TtsStatus.FAILED)`). In FAILED, `tts` was ALREADY nulled at `:100`. So
`tts = null` at `:113` is always a no-op today and no orphan is ever created. Double-tap also checked:
`testVoice` runs inline on Main.immediate through retryInit + warmUp before its first suspension, so a
second tap always observes INITIALIZING, not FAILED.
A one-line defect waiting for a fourth caller. Add `tts?.shutdown()` for symmetry.

## L-22 — R-10: shutdown() never called · **INFORMATIONAL** (dead code, NOT a leak)
Only `.shutdown()` call sites in app/src/main/ are `VoiceCoach.kt:94`, `:99` and `:238` (the last inside
shutdown() itself). Nothing calls the public shutdown().
But calling it a leak is WRONG: VoiceCoach is @Singleton with @ApplicationContext (`:52`); holding one
TextToSpeech binding and one CoroutineScope for the process lifetime is BOUNDED, CONSTANT retention — the
intended design for a coach used from the service, the Player and Settings. Nothing grows.
Real observation: shutdown() is unreachable code. Either wire it up or delete it.
