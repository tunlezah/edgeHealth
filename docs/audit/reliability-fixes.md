# Reliability remediation design (L-1 … L-22)

Designs for the findings that survived adversarial verification in
[`reliability-verified.md`](reliability-verified.md). Rejected findings (R-04, R-16, R-21, R-25,
R-26) and the reclassified-as-deliberate items (R-19 tick clamp, R-20 mtime) are **not** addressed —
R-19 in particular is asserted by a passing test, so "fixing" it is a regression.

Framework facts (A) `lifecycleScope = Main.immediate` and (B) Room Flow emits in the collector's
context are assumed throughout.

**Standing recommendation:** add StrictMode disk-read/write logging to debug builds in
`KinetiqApp.onCreate`. It is the only cheap way to *verify* L-3, L-5, L-16 and L-17 on-device, and it
catches the next one for free.

---

## L-1 (CRITICAL) — FGS watchdog crash + history destruction on the Summary Resume button

Two coupled fixes, with L-17 folded in.

### 1a. Every `onStartCommand` path reaches `startForeground` or `stopSelf`

**The safe initial notification already exists.** `buildNotification()` handles `state == null`
(`:621-632`) with title `Kinetiq` / text `"Workout session"`. That is the placeholder — no new
builder, no new layout, no new channel. It gains two optional hints:

```kotlin
private fun buildNotification(titleHint: String? = null, textHint: String? = null): Notification {
    val state = stateHolder.state.value
    val title = when {
        state == null && !titleHint.isNullOrBlank() -> titleHint
        inPrepare -> "Get ready"
        step != null -> step.exerciseName
        else -> getString(R.string.app_name)
    }
    val text = when {
        state == null -> textHint ?: "Workout session"
        // ... unchanged
    }
    // everything from `fun action(...)` down is UNCHANGED
}
```

**Why there is no flicker.** Placeholder and refined notification come from the *same builder*, so
same channel, same `NOTIFICATION_ID`, same three actions in the same order, same `MediaStyle` with
`setShowActionsInCompactView(0, 1, 2)`, same `setOngoing(true)`. Refinement is
`NotificationManager.notify(NOTIFICATION_ID, …)`, which replaces content **in place** — no cancel, no
re-post. `setOnlyAlertOnce(true)` + `setSilent(true)` suppress re-alerting. Only two text lines change.

Hints are chosen to minimise even that:

| entry | `titleHint` | `textHint` | first refinement |
|---|---|---|---|
| `ACTION_START` | `"Get ready"` | `"Starting <name>…"` | title **identical** (state enters prepare); text → `"Starting in 10s — <exercise>"` |
| `ACTION_RESUME_SNAPSHOT` / `_STOPPED` | *(none → "Kinetiq")* | `"Restoring your workout…"` | title → exercise name, text → clock. One change, ~30 ms |

The `ACTION_START` case is picked deliberately so the *title* — the line the eye tracks — never changes.

**Why the early `goForeground()` still picks the right FGS type.**
`checkSelfPermission(ACTIVITY_RECOGNITION)` is a process-local check against the permission set cached
in `ActivityThread`. It does not block, does not touch session state, and returns the identical answer
at t=0 ms and t=40 ms. To *guarantee* the type never changes mid-session (a revoke between the two
calls would flip HEALTH→MEDIA_PLAYBACK, which Android 14+ treats as a type transition),
`goForeground` becomes idempotent:

```kotlin
private var isForeground = false

private fun goForeground(titleHint: String? = null, textHint: String? = null) {
    registerAutoPause()
    val notification = buildNotification(titleHint, textHint)
    if (isForeground) {
        // Already claimed. Refine in place — re-calling startForeground would re-evaluate the
        // FGS type, which must stay fixed for the life of the session.
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
        return
    }
    val hasActivityRecognition = checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
        PackageManager.PERMISSION_GRANTED
    val type = if (hasActivityRecognition) ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
               else ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK   // DECISIONS.md D-03
    ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    isForeground = true
    acquireWakeLock()
}
```

**The dispatcher** gains a synchronous **run generation** (also the L-4 fix) and a synchronous
foreground claim:

```kotlin
/** Bumped synchronously whenever a NEW run is accepted. Lets a parked finish coroutine tell
 *  whether it still owns the service (see shouldTearDown). */
private var runGeneration = 0
private var latestStartId = 0

/** Actions the companion delivers via startForegroundService — each arms the ~5 s FGS watchdog. */
internal fun isForegroundEntry(action: String?): Boolean =
    action == ACTION_START || action == ACTION_RESUME_SNAPSHOT || action == ACTION_RESUME_STOPPED

override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    super.onStartCommand(intent, flags, startId)
    latestStartId = startId
    when (val action = intent?.action) {
        ACTION_START -> {
            if (shouldIgnoreStart(stateHolder.state.value?.finished)) {
                // Live session: this delivery is a refresh only. Already foreground, so AOSP's
                // sendServiceArgsLocked cleared fgRequired without arming a new timeout.
                goForeground()                       // idempotent -> notify()
            } else {
                val session = intent.getStringExtra(EXTRA_SESSION_JSON)?.let { payload ->
                    runCatching { json.decodeFromString(GeneratedSession.serializer(), payload) }.getOrNull()
                }
                if (session == null || session.plan.steps.isEmpty()) {
                    // Nothing to run. Stopping satisfies the FGS start timeout without ever
                    // showing a notification; safe only because no live session exists here.
                    stopSelfResult(startId)
                } else {
                    val name = intent.getStringExtra(EXTRA_SESSION_NAME) ?: "Workout"
                    val gen = claimForeground("Get ready", "Starting $name…")
                    startSession(session, name, gen, startId)
                }
            }
        }
        ACTION_RESUME_SNAPSHOT, ACTION_RESUME_STOPPED -> {
            // Whether a restorable snapshot exists needs disk I/O, so the foreground must be
            // claimed FIRST and released again if the read comes back empty.
            val gen = claimForeground(textHint = "Restoring your workout…")
            restoreFromSnapshot(gen, startId, fromStopped = action == ACTION_RESUME_STOPPED)
        }
        // ... control actions unchanged ...
        // Defensive: START_NOT_STICKY means the platform never redelivers a null intent, but
        // if one ever arrives it must not leave the service running with nothing to do.
        null -> abandonRun(runGeneration, startId)
        else -> Unit
    }
    return START_NOT_STICKY
}

private fun claimForeground(titleHint: String? = null, textHint: String? = null): Int {
    val gen = ++runGeneration
    goForeground(titleHint, textHint)
    return gen
}

/**
 * A foreground entry that turned out to have nothing to run. Drops the placeholder and stops,
 * satisfying the watchdog and leaving no ghost notification. Never fires if a newer run has
 * already claimed the service or if a live session exists.
 */
private fun abandonRun(gen: Int, startId: Int) {
    if (runGeneration != gen) return
    if (stateHolder.state.value != null) { updateNotification(); return }
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    isForeground = false
    stopSelfResult(startId)   // declines to stop if a newer start is already queued
}
```

Bail-outs inside the coroutines become explicit — `restoreFromSnapshot` gets
`?: return@launch abandonRun(gen, startId)` on the snapshot read (the L-1(a) crash), and `startSession`
the same on `steps.firstOrNull()` (L-1(e)). Both also re-check `runGeneration != gen` after each
suspension. In `restoreFromSnapshot`, the stopped-snapshot file is deleted **after** publishing state,
not before — a crash mid-restore then leaves it recoverable instead of consuming it for nothing.

**Why it's correct.** Android 14+ arms `ActiveServices.serviceForegroundTimeoutLocked` on every
`startForegroundService` delivery; the two documented ways to disarm it are `startForeground()` and
destroying the service. Every branch now reaches one **before the first suspension point**. Per fact
(A), `readSnapshot(...) ?: return@launch` is the first statement of the launch and runs *inline*, so
`abandonRun` executes synchronously inside `onStartCommand` — deterministically, not racily.

**What could break**
- *ACTION_START while a session is live* (finding (h)): unchanged — `goForeground()` degenerates to
  `updateNotification()`. The generation is **not** bumped, so a live run's finish coroutine is not orphaned.
- *ACTION_START after `finished = true`* (the L-4 window): a new run is accepted, generation bumps, old
  teardown suppressed. Correct.
- *Notification actions tapped during the ~10 ms placeholder window*: `setPaused`/`skipStep` return
  early on `state == null`; `ACTION_STOP` merely arms — and `stopArmedUntil` is now reset when a run
  starts, so a stale arm can't finish a fresh session.
- *Degenerate ACTION_START*: no notification is ever posted, so the user sees nothing rather than a
  10 ms flash. Same root cause as security finding **S-2/N-01**; this is defence in depth and the
  ingestion-side validation is theirs. The `runCatching` around `decodeFromString` also turns a
  synchronous throw inside `onStartCommand` — which would crash *with the watchdog still armed* — into
  a clean stop.

### 1b/1c + L-17. `resumeStopped` must not destroy history before the restore is confirmed

```kotlin
sealed interface ResumeOutcome {
    data object Started : ResumeOutcome
    data object Expired : ResumeOutcome    // gone, expired, or unparseable
    data object TimedOut : ResumeOutcome   // service never published a live session
}

/** Remaining resume window in ms; null = no offer. Disk I/O is NEVER done during composition
 *  (HomeScreen.kt:76 states the rule; this screen used to break it). */
private val _resumeWindowMs = MutableStateFlow<Long?>(null)
val resumeWindowMs: StateFlow<Long?> = _resumeWindowMs.asStateFlow()

/**
 * Gate the Resume offer on a FULL read + parse (like HomeScreen.kt:81 does), not on existence +
 * mtime — a torn write passes the mtime gate. Then tick the remaining window once a second so
 * the button disappears exactly when the window closes.
 */
fun watchResumeWindow() {
    windowJob?.cancel()
    windowJob = viewModelScope.launch {
        val expiresAt = withContext(Dispatchers.IO) {
            val parsed = WorkoutSessionService.readStoppedSnapshot(appContext, json)
            if (parsed == null || parsed.session.plan.steps.isEmpty()) null
            else WorkoutSessionService.stoppedSnapshotFile(appContext).lastModified() +
                WorkoutSessionService.STOPPED_SNAPSHOT_VALID_MS
        } ?: run { _resumeWindowMs.value = null; return@launch }

        while (isActive) {
            val left = expiresAt - System.currentTimeMillis()
            _resumeWindowMs.value = left.takeIf { it > 0 }
            if (left <= 0) return@launch
            delay(1_000)
        }
    }
}

/**
 * Undo an accidental stop. The ORDER is load-bearing: the history row is the user's only record
 * of the workout and there is no server copy, so it is deleted strictly AFTER the service has
 * published a live PlayerState for the restored run. A failed restore therefore leaves history
 * intact; a successful one leaves no duplicate, because the resumed run writes its own row when
 * it finishes.
 */
fun resumeStopped(context: Context, onResult: (ResumeOutcome) -> Unit) {
    val summary = stateHolder.lastCompleted.value ?: return
    if (resumeInFlight) return
    resumeInFlight = true
    viewModelScope.launch {
        // 1. Prove the snapshot is restorable before touching anything destructive.
        val snap = withContext(Dispatchers.IO) {
            WorkoutSessionService.readStoppedSnapshot(context, json)
        }
        if (snap == null || snap.session.plan.steps.isEmpty()) {
            resumeInFlight = false; _resumeWindowMs.value = null
            message.value = "That workout can no longer be resumed — it stays in your history."
            onResult(ResumeOutcome.Expired); return@launch
        }
        // 2. Ask the service to restore.
        WorkoutSessionService.resumeStopped(context)
        // 3. Wait for proof. startedAtEpochMs is copied verbatim from the snapshot into the
        //    restored PlayerState, so it identifies THIS run and excludes any leftover.
        val live = withTimeoutOrNull(RESUME_CONFIRM_TIMEOUT_MS) {
            stateHolder.state.first {
                it != null && !it.finished && it.startedAtEpochMs == snap.startedAtEpochMs
            }
        }
        if (live == null) {
            resumeInFlight = false
            message.value = "Couldn't restart that workout — it's still in your history."
            onResult(ResumeOutcome.TimedOut); return@launch      // history AND summary untouched
        }
        // 4. Confirmed. Retire the stopped run's row and its summary.
        if (summary.historyId > 0) runCatching { workoutRepository.deleteHistory(summary.historyId) }
        stateHolder.clearCompleted()
        resumeInFlight = false
        onResult(ResumeOutcome.Started)
    }
}
```

The composable renders `"Stopped by accident? Resume workout · $minsLeft min left"`.

**Yes to the countdown.** It is the honest fix for "the button stays drawn forever": the user is told
the offer is time-limited — which `PlayerScreen.kt:99` already promises them in words — and the button
self-removes at expiry because `_resumeWindowMs` goes null. It costs one coroutine and one
`delay(1000)` on a screen with no other animation.

**What could break**
- *Happy path*: `resuming = true` is set on click, so the existing `LaunchedEffect(summary) { if
  (summary == null && !resuming) onDone() }` at `:95` does not double-navigate. Preserved exactly.
- *Expired / torn file*: the button was never drawn (step 1 parses), so this is only reachable by a tap
  racing expiry by <1 s. History intact, `resuming` reset, Done still works.
- *Service fails to start*: 8 s timeout, history intact, summary intact, message shown. **The old code
  would have deleted the row.**
- *Duplicate rows*: impossible. Deletion happens only on confirmation; the resumed run gets a fresh
  `sessionId` (`:211`) and writes a new row on finish. Never zero, never two.
- *Interaction with L-4*: if the previous `finishSession` is still parked in the Health Connect IPC when
  the resume fires, L-4's generation guard suppresses its teardown, so it cannot wipe the restored run.
  The two fixes reinforce each other.

### Tests
New `ServiceCommandTest.kt` (plain JUnit + Truth, testing extracted top-level predicates in
`SessionEngineTest`'s style) and `StoppedSnapshotGateTest.kt` (Robolectric) — the latter proves the
L-1(b) button can never be offered for a bad file:

```kotlin
@Test fun `a truncated stopped snapshot passes the mtime gate but is rejected by the parse gate`() {
    WorkoutSessionService.stoppedSnapshotFile(ctx).writeText("""{"session":{"config":{},"plan":{"steps":[""")
    assertThat(WorkoutSessionService.hasStoppedSnapshot(ctx)).isTrue()
    assertThat(WorkoutSessionService.readStoppedSnapshot(ctx, json)).isNull()
}
```

**Must still pass unchanged:** `SessionEngineTest` (nothing in the engine is touched; the R-19 clamp
test in particular), `SessionSnapshotCompatTest`, `SummaryNavigationTest`.

### Risk: **Invasive**
On-device: (1) stop a workout, wait 11 min on Summary, confirm the button counts down and disappears;
(2) stop, delete the stopped snapshot via adb, tap Resume in-window — no crash, no lost history row,
a message; (3) `adb shell dumpsys activity services au.mark.kinetiq` during the first 200 ms of a
start confirms `isForeground=true`; (4) watch the notification for a title flicker.

---

## L-2 (HIGH) — destructive migration with no schema baseline

### The fact that makes this safe

**The checked-in schema JSON is a build-time artifact. Room never reads it at runtime.** At runtime
Room validates only the identity hash it wrote into `room_master_table` when it created the database.
So *adding* a schema baseline cannot break a single existing install — it is a pure build-system
change. The only runtime behaviour change in the whole fix is dropping
`fallbackToDestructiveMigration()`, and while `version = 1` on both sides that is a strict no-op.

### Can the v1 schema be captured retroactively? Yes — with one precondition

Room's schema JSON is a deterministic function of the annotated entity classes and the version number.
The classes on disk are exactly the ones that produced the shipped v1 database, so turning export on
and rebuilding emits a `1.json` describing the identical `CREATE TABLE` statements with the identical
identity hash. **Precondition: not one entity field, type, nullability, index or primary key may change
in the same commit.**

### The sequence — step 0 must land alone

**1.** `app/build.gradle.kts` (the `androidx.room` Gradle plugin is *not* applied, so the KSP argument
is the correct form for KSP `2.0.21-1.0.28`):

```kotlin
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

android {
    // Room's MigrationTestHelper reads schemas from androidTest assets.
    sourceSets.getByName("androidTest") { assets.srcDir("$projectDir/schemas") }
}
```

**2.** `KinetiqDatabase.kt`: `exportSchema = true`. **`version` stays 1.**

**3.** `./gradlew :app:kspDebugKotlin`, commit `app/schemas/au.mark.kinetiq.data.db.KinetiqDatabase/1.json`.

**4. Verify against a real device — the one step that cannot be done from source.**

```bash
adb exec-out run-as au.mark.kinetiq cat databases/kinetiq.db > /tmp/kinetiq.db
sqlite3 /tmp/kinetiq.db "SELECT identity_hash FROM room_master_table;"
```

Compare with `database.identityHash` in `1.json`. Match ⇒ the baseline is *proven*. Mismatch ⇒ an
entity drifted since the shipped build: reconstruct from the shipped tag
(`git checkout <tag> -- .../Entities.kt`, generate, restore HEAD) and treat the difference as an
undeclared schema change that already needs a migration.

**5.** `AppModule.kt` — drop the destructive fallback:

```kotlin
Room.databaseBuilder(context, KinetiqDatabase::class.java, "kinetiq.db")
    // NO destructive fallback. This database is the user's entire workout history; the app is
    // offline-only so there is no server copy, and backup_rules/data_extraction_rules only
    // restore at device setup, not across an in-place update — which is exactly when destructive
    // migration fires. A missing migration must fail loudly, not silently drop every table.
    // Downgrade is likewise NOT handled: export first, then sideload the older APK (see README).
    .addMigrations(*KinetiqMigrations.ALL)
    .build()
```

New `data/db/KinetiqMigrations.kt` with `val ALL: Array<Migration> = arrayOf()` — empty at version 1,
the baseline.

### What must happen BEFORE the next entity change

`1.json` must be **committed and hash-verified**. After that every entity change is: bump `version = 2`
→ build (emits `2.json`) → diff → write `Migration(1, 2)` → add to `ALL` → add a `MigrationTestHelper`
test. Until `1.json` exists there is nothing to diff against and a migration cannot be authored correctly.

### What could break
- Turning on `exportSchema` / the KSP arg: nothing. Build-time only.
- Dropping the fallback: while versions match, identical behaviour. When they diverge without a
  migration, Room throws on first DB access — a launch crash. For a single-user sideloaded app that is
  the **right** failure: recoverable (reinstall the previous APK, export, then update), whereas silent
  destruction is not. State it in the README.
- **Do not add `fallbackToDestructiveMigrationOnDowngrade()`** — same data loss under a friendlier name.

### Test
Migration tests are *instrumented* (`MigrationTestHelper` needs real SQLite and schemas in assets), so
they cannot live in `app/src/test`. That is future work at version 2. What *can* live there is a guard
rail — `RoomSchemaBaselineTest` reading the source the way the existing manifest test does: asserts
`exportSchema = true`, that every version from 1 to the declared version has a checked-in JSON with a
non-empty identity hash, and that `AppModule.kt` contains no `fallbackToDestructiveMigration`.

### Risk: **Safe** as a build-system change, *provided step 4's hash verification is performed.*

---

## L-3 / L-8 (HIGH / MEDIUM) — history JSON on the main thread; widget deserialises the whole table

### Who actually needs `HistoryEntry.session` — verified by grep

| caller | needs session? |
|---|---|
| `HistoryScreen` (list, calendar, trends, `retryHcWrite`) | **no** |
| `HomeScreen`/`HomeViewModel.uiState` (streak, weekly stats) | **no** |
| `PlanScreen` → `WeeklyPlanEngine.progressForWeek` | **no** |
| `HomeViewModel.repeatLast` / `MainActivity.repeatLastWorkout` (`lastSession()`) | **yes**, one row |
| `KinetiqWidget` (`lastSession()`) | **no** — only `.name` |
| `KinetiqWidget` (`historyOnce()`) | **no** — only `startedAtEpochMs` |
| `ExportImportManager.buildExport` | **yes**, every row — that is the point of an export |
| `WorkoutRepository.lastSessionFlow()` | **dead — zero callers** |

`HomeScreen`'s `workout.session.plan.totalSec` at `:269` is a **`SavedWorkout`**, not a `HistoryEntry` —
that one genuinely needs its session and cannot be projected away.

### Design

A Room projection POJO `SessionHistoryRow` (no `sessionJson`), new DAO queries:

```kotlin
@Query("SELECT id, startedAtEpochMs, endedAtEpochMs, name, totalActiveSec, calories, " +
       "blocksJson, healthConnectWritten FROM session_history ORDER BY startedAtEpochMs DESC")
fun historyRows(): Flow<List<SessionHistoryRow>>

/** Timestamps only — the widget's streak needs nothing else. 365 Longs, zero JSON. */
@Query("SELECT startedAtEpochMs FROM session_history ORDER BY startedAtEpochMs DESC")
suspend fun historyStartTimes(): List<Long>

/** One String — the widget's "Repeat: <name>" line. */
@Query("SELECT name FROM session_history ORDER BY startedAtEpochMs DESC LIMIT 1")
suspend fun lastSessionName(): String?

// lastSession() and historyOnce() KEPT — repeat-last and export need the full session.
// history() and lastSessionFlow() DELETED — no remaining callers.
```

The model splits into a slim `HistoryEntry` (no `session`) and `HistoryEntryWithSession`, and the
repository moves the decode off-main:

```kotlin
/**
 * Room emits downstream in the COLLECTOR's context (CoroutinesRoom.createFlow's emitAll runs in
 * the flow builder's collector context), and all three collectors are stateIn(viewModelScope) =
 * Main.immediate. flowOn moves this map — and Room's own query — off the main thread; only the
 * final emission lands back on Main.
 */
fun history(): Flow<List<HistoryEntry>> =
    dao.historyRows().map { rows -> rows.map { it.toModel() } }.flowOn(Dispatchers.Default)

/** Saved workouts genuinely need their full session (HomeScreen renders plan.totalSec and
 *  config.categories), so the decode can only be moved, not removed. */
fun savedWorkouts(): Flow<List<SavedWorkout>> =
    dao.savedWorkouts().map { list -> list.mapNotNull { it.toModel() } }.flowOn(Dispatchers.Default)
```

**Call-site updates** (four files, mechanical): `MainActivity.repeatLastWorkout` and
`HomeViewModel.repeatLast` use `lastSessionForRepeat()`; `KinetiqWidget` uses `lastSessionName()` +
`historyStartTimes()` — **L-8 disappears entirely**, the widget now reads one `String` and N `Long`s;
`ExportImportManager` maps `it.entry.*` + `it.session`.

### Why it's correct
Fact (B) says the `.map` after a Room flow runs wherever the terminal collector runs. `flowOn(Default)`
changes the *upstream* context of everything above it, so both `CoroutinesRoom`'s `emitAll` and our
`.map` execute on `Default`; `stateIn(viewModelScope, …)` still collects on Main and emits to Compose on
Main. Room's `InvalidationTracker` registration happens inside the flow builder's `coroutineScope` (now
on `Default`) and the tracker is thread-safe. No Compose state is touched off-main — only the
already-built immutable list crosses back.

### What could break
- **`HistoryScreen.retryHcWrite(entry)`** reads `name, blocks, calories, startedAtEpochMs,
  endedAtEpochMs, id`. **All present in the slim `HistoryEntry`.** Preserved verbatim, no signature change.
- **`WeeklyPlanEngine.progressForWeek(history: List<HistoryEntry>)`** — signature and body unchanged;
  reads only `startedAtEpochMs` and `blocks`.
- **Repeat-last** still decodes the session; if corrupt, `session` is null and both callers already
  bail. A *corrupt* row now still appears in the History list (we never touch its `sessionJson`) instead
  of being silently listable-but-unrepeatable — a small improvement.
- **Export** still emits `session` for every entry, so exported files are byte-identical in content.
- **Import dedupe** still calls `historyOnce()` and decodes everything. Rare, user-triggered,
  background — leaving it is the conservative choice.

### L-18 folded in — recommendation is **not to fix**
L-18 is not an independent defect; it is why L-3/L-8 grew without bound. With the projection, 365 rows
cost ~110 KB of `blocksJson` parsing **off-main**. Adding retention or a `LIMIT` to a single-user app
whose headline feature is a multi-year streak and a full-history calendar would be user-hostile and
would break the streak and calendar computations. **Document in DECISIONS.md; do not add retention.**
If it ever matters, push streak/calendar aggregation into SQL, don't delete the user's data.

### Test
New `WorkoutRepositoryProjectionTest.kt` (Robolectric, in-memory Room, fully offline): history is
newest-first with per-block breakdown; a corrupt-`sessionJson` row still appears in the list;
repeat-last still resolves the full session; repeat-last yields null for a corrupt row so callers bail;
widget projections return name and start times without decoding; export still carries every session.

**Must still pass unchanged:** `ExportImportAndMiscTest` (it exercises `ExportImportCodec` directly,
never the repository — verified by reading it), `SessionEngineTest`, `SessionSnapshotCompatTest`.

### Risk: **Needs care** (7 files)

---

## L-4 (MEDIUM) — `finishSession`'s `finally` can destroy a newly started session

### Identity to capture: the **run generation**, not the sessionId

`sessionId` would work for "new session already published" but not for "new `ACTION_START` accepted,
its coroutine still parked in `settingsRepo.current()`" — at that moment `stateHolder.state.value` is
still null and a sessionId check would wave the teardown through. The generation is bumped
**synchronously inside `onStartCommand`**, before any suspension, so it covers both.

```kotlin
/** A finish coroutine may tear the service down only if its run is still the current one. */
internal fun shouldTearDown(finishingGen: Int, currentGen: Int): Boolean = finishingGen == currentGen

private fun finishSession(userStopped: Boolean, startId: Int = latestStartId) {
    val gen = runGeneration            // the run THIS coroutine owns
    // ...
    lifecycleScope.launch {
        try { /* body, with L-12's runCatching guards */ }
        finally {
            if (userStopped) runCatching { writeStoppedSnapshot(state, es) }   // L-16: now suspend
            // Nothing here may touch a session that isn't ours. A new ACTION_START may have been
            // accepted while we were parked in the Health Connect IPC (which can take seconds),
            // and shouldIgnoreStart accepts it the moment finished == true.
            if (shouldTearDown(gen, runGeneration)) {
                deleteSnapshot(this@WorkoutSessionService)
                stateHolder.update(null)
                if (!stopSelfResult(startId)) stopSelf()
            }
        }
    }
}
```

**Threading `startId`:** a `latestStartId` field set at the top of every `onStartCommand`; the default
argument binds **at invocation**, before the `launch`, so the stop paths capture their own delivery id
and the natural-completion path captures the last command received. Where `startId` really earns its
keep is `abandonRun`, where `stopSelfResult` correctly declines to stop when a newer start is queued.

**Supporting one-liners** in `startSession`/`restoreFromSnapshot` before `stateHolder.update(...)`:
`lastSnapshotMs = 0L` (so the on-disk snapshot stops describing the previous run within 200 ms) and
`stopArmedUntil = 0L; stopArmJob?.cancel()` (so a Stop armed on the previous run can't instantly finish
the new one).

### What could break
- *The L-4 story* (stop → "Repeat last" → finish resumes): the new start bumped the generation, so the
  old finally skips `stateHolder.update(null)`, `deleteSnapshot` and `stopSelf`. New session survives.
- *Service destroyed mid-finish*: the `finally` still runs and calls `stopSelf()` on a dying service —
  harmless no-op, same as today.
- *User pauses during the ~1 s finish window*: `latestStartId` advances, `stopSelfResult(oldId)` returns
  false, the `stopSelf()` fallback stops the service anyway. **Without the fallback this would be a new
  zombie-FGS bug.**

### Risk: **Needs care**
On-device: enable Health Connect write-back, start a workout, stop from the notification while on Home,
immediately tap "Repeat last", confirm the new session does *not* vanish.

---

## L-5 (MEDIUM / HIGH first run) — 130 KB asset parsed and seeded on the main thread

```kotlin
/** 130 KB read + parse + validation, and on a schema bump 90 encodeToString calls and two Room
 *  inserts. Every caller is Main-dispatched — including the service's speakHowToAt, which runs
 *  during the GET-READY countdown of every session. */
private suspend fun loadAndSeed(): ExerciseDatabaseFile = withContext(Dispatchers.IO) {
    /* body byte-for-byte unchanged */
}
```

The `mutex.withLock` stays outside, so the lock is held across the dispatcher switch — exactly the
intent (one loader, one seed). This single change fixes all four call sites at once.

For the Builder, `withContext(Dispatchers.Default)` around the generator call plus a `generateSeq`
guard so rapid Regenerate taps drop stale results.

### Preserving the `generating` flag and preview-edit protection
`generating = true` is still set on Main **before** the switch and `false` **after**, so the flag's
window is strictly *longer*, never shorter — `BuilderScreen.kt:350`'s `enabled` check keeps working.

**The real regression risk**, correctly anticipated: with generation now off-main, the user can
hand-edit the preview *during* generation and the write-back would clobber it with `edited = false`.
That is impossible today because generation blocks the UI thread. Closed in the ViewModel, one line each:

```kotlin
private fun editSteps(transform: (List<SessionStep>) -> List<SessionStep>) {
    if (uiState.value.generating) return   // an in-flight generation would overwrite this edit
    /* unchanged */
}
```

The documented contract at `:98` is preserved exactly: edits made *before* a generate are still only
discarded through the explicit confirm dialog; edits *attempted during* a generate are ignored rather
than silently lost.

### What could break
`WorkoutGeneratorTest`, `WorkoutGeneratorTimeBudgetTest` and `RestModeTest` construct `WorkoutGenerator`
directly and never touch the ViewModel — unchanged. `WorkoutGenerator` is pure over immutable inputs;
`Random.Default` is thread-safe; nothing it touches is Compose state.

### Risk: **Needs care.** On-device: fresh install → first Builder generate (no ANR, button
disables/re-enables); tap Regenerate rapidly ×5 → one coherent preview; hand-edit then regenerate →
dialog still appears.

---

## L-6 (MEDIUM) — sticky widget intent restarts a workout

Two independent bugs, one fix:

```kotlin
/** A launch action must be handled exactly once: not again on a configuration-change or
 *  process-death recreation, and not again from a stale getIntent(). */
internal fun shouldHandleLaunchIntent(action: String?, isRecreation: Boolean): Boolean =
    action == MainActivity.ACTION_REPEAT_LAST && !isRecreation

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    // savedInstanceState != null means this is a recreation — dark mode at sunset (Motorola's
    // scheduled auto dark mode toggles uiMode), a font-scale, locale or density change, or a
    // restore after process death. The task's original widget intent must not fire again.
    handleLaunchIntent(intent, isRecreation = savedInstanceState != null)
    setContent { /* unchanged */ }
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)                              // getIntent() must reflect the newest delivery
    handleLaunchIntent(intent, isRecreation = false)
}

private fun handleLaunchIntent(intent: Intent?, isRecreation: Boolean) {
    if (!shouldHandleLaunchIntent(intent?.action, isRecreation)) return
    intent!!.action = null                         // consume it; getIntent() can never replay it
    viewModel.repeatLastWorkout(this)
}
```

**Why it's correct:** `onSaveInstanceState` always runs before a configuration-change recreation and
before a process-death kill, so `savedInstanceState != null` is exactly the "not a fresh launch"
predicate. A genuine cold launch from the widget passes null. Nulling the action mutates the very
`Intent` object `getIntent()` returns, so no future path can re-derive it.

**Do not add `android:configChanges`.** The manifest is not the bug. `screenOrientation="portrait"`
already suppresses rotation recreation, and Compose handles `uiMode`/`fontScale`/`locale`/`density`
correctly *through* recreation — that is the supported path. Adding `configChanges` would suppress
recreation and risk stale theme/resource state for zero benefit once the intent is consumed correctly.
**Leave the manifest alone**, and record why in DECISIONS.md.

### Risk: **Safe.** On-device: launch from widget, complete a workout, wait, toggle dark mode in Quick
Settings, confirm no notification and no TTS announcement.

---

## L-7 (MEDIUM) — wake lock + 5 Hz ticker held through indefinite pauses

### What actually needs to stay awake while paused: **nothing**

The wake lock exists so the step clock stays accurate with the screen off *while the workout is
running*. Paused, there is no step clock. Everything a pause must survive is handled without the CPU:

| requirement | survives without a wake lock? |
|---|---|
| ongoing notification | yes — system-owned, survives doze |
| auto-pause receiver + `OnModeChangedListener` | yes — registered receivers are delivered on wake |
| foreground service status | yes — orthogonal to the wake lock; `health`/`mediaPlayback` are exempt from Android 15's FGS timeout regime |
| the 5 s snapshot | irrelevant — paused means no ticks, so nothing has changed to snapshot |
| Resume delivery | yes — a user interaction wakes the process to deliver to a foreground service |

The ticker is **suspended, not spun**:

```kotlin
// A paused workout has no clock to keep accurate, so let the CPU sleep. startTicker()
// re-baselines lastTick to elapsedRealtime() at resume, so the paused interval is never billed
// and the SessionEngine tick clamp (deliberate, tested — R-19) is never even exercised on resume.
if (paused) { suspendTicker(); releaseWakeLock() }
else { acquireWakeLock(); startTicker() }

/** True exactly while a step clock is running. */
internal fun shouldHoldWakeLock(paused: Boolean, finished: Boolean): Boolean = !paused && !finished
```

`startTicker()` already begins with `tickerJob?.cancel()` and `var lastTick = SystemClock.elapsedRealtime()`
— the re-baseline is free and already correct.

### What could break
- *Pause → screen off 30 min → Resume*: `elapsedRealtime()` advanced 30 min, but `startTicker`
  re-baselines, so the step resumes at exactly the second it was paused, with the existing
  `RESUME_PREPARE_MS = 3000` countdown. Previously the ticker spun at 5 Hz for 30 minutes doing nothing.
- *Pause → process killed*: the snapshot's mtime freezes at pause time. **No change** — `maybeSnapshot`
  is only called from `onTick`, which the old ticker already skipped while paused.
- The `acquire(4h)` timeout is retained and restarts on each resume, which is correct.

### The unbounded foreground service: **do not add an auto-finish watchdog**
Real, but after the wake-lock fix an indefinitely-paused session costs one ongoing notification and an
idle process — not a battery problem. An auto-finish timer would risk silently ending a workout the
user is 20 minutes into a phone call about, and a coroutine `delay` would not fire reliably under doze
anyway (an `AlarmManager.setAndAllowWhileIdle` would be needed — real machinery for no user benefit).
**Don't build it.** Instead make the paused notification honest: `paused -> "Paused — tap Resume to continue"`.

### Risk: **Needs care.** On-device: pause → screen off 10 min → `adb shell dumpsys power | grep -i
kinetiq` shows no held wake lock → resume from the notification → the step timer continues at the same
second and the 3-2-1 plays. Repeat resuming from the in-app button.

---

## L-9 (MEDIUM) — Home re-reads the snapshot 5×/s

```kotlin
// The resume card only renders when there is NO live session (the else branch at :199), so a
// running session's 5 Hz PlayerState updates must not re-read the snapshot five times a second.
// Key on presence, not on the state object.
val hasLiveSession = playerState != null
LaunchedEffect(hasLiveSession) {
    if (hasLiveSession) viewModel.clearSnapshot() else viewModel.refreshSnapshot()
}
```

plus a `snapshotJob` in the ViewModel so a restart cancels the in-flight read.

**What could break:** *Session finishes while Home is open* — `playerState` → null re-fires the effect
once, `readSnapshot` returns null (the finish deleted it), no card. Previously the constant re-reads
happened to cover this; now the null transition does it once, deliberately.

**Test: recommend none.** Pure efficiency fix with no extractable predicate; `HomeViewModel` needs a
Context plus two repositories to construct. Covered by StrictMode logging and the manual check.

### Risk: **Safe.**

---

## L-10 (MEDIUM) — the reminder chain self-terminates

Two fixes plus one documented non-fix.

**1. The worker must not end the chain** — an extracted `reminderOutcome(settings, runAttemptCount)`
returning `NOTIFY_AND_RESCHEDULE` / `DISABLED` / `RETRY` / `GIVE_UP`, with
`settingsRepository.current()` wrapped in `runCatching` and `Result.retry()` bounded by
`MAX_ATTEMPTS = 4`.

**2. Re-arm from a place that does not depend on the worker** — a second `ensureScheduled` entry point
using **`KEEP`**, called from `KinetiqApp.onCreate`:

```kotlin
/**
 * Self-heal: enqueue the next occurrence ONLY if nothing is already scheduled. KEEP (never
 * REPLACE) is load-bearing — Application.onCreate also runs when WorkManager starts the process
 * to execute ReminderWorker itself, and REPLACE would cancel the very worker that is about to
 * post the notification.
 */
fun ensureScheduled(context: Context, days: Set<Int>, hour: Int, minute: Int) { ... }
```

Re-arming on every process start covers reboot, app update, force-stop and a dead chain — no
`BOOT_COMPLETED` or `MY_PACKAGE_REPLACED` receiver needed. `schedule(...)` (REPLACE) is untouched and
stays the user-edit path.

**3. DST — document, don't fix.** `delayToNext` computes a wall-clock `Duration` that WorkManager
applies as elapsed time, so a 07:00 reminder arrives at 06:00 or 08:00 on the two changeover days. But
**each firing reschedules from a fresh `LocalDateTime.now()`, so the error self-corrects on the very
next occurrence.** Blast radius: one reminder, twice a year. Fixing it properly needs `AlarmManager`
exact alarms (a permission the app deliberately avoids — see the KDoc at `Reminders.kt:26-29`).

### Test
New `ReminderChainTest.kt` (plain JUnit + Truth) over `reminderOutcome`.
**Must still pass unchanged:** `ExportImportAndMiscTest`'s `reminder delay lands on the next configured
slot` — `delayToNext` is untouched.

### Risk: **Safe.**

---

## L-11 + L-13 + L-14 — per-frame motion-path solve (merged: one file, one review pass)

The motion-path loop reads nothing from `timeMs`. It is a pure function of `(anim, width, height)`.
Split into a **pure, Android-free** point solver (JVM-unit-testable) and a cached `Path`:

```kotlin
/**
 * The motion-path arc in canvas pixels. Pure in (anim, width, height) — never in time. 29 pose
 * solves and 29 rig solves; 25 of the 50 registry animations declare a non-NONE pathJoint.
 * Offset is pure Kotlin (compose-ui-geometry), so this is testable without Robolectric.
 */
internal fun motionPathPoints(anim: KeyframeAnim, width: Float, height: Float): List<Offset>
```

```kotlin
// Reset the clock when the animation changes so a recycled LazyColumn row does not inherit the
// previous exercise's phase. NOT keyed on `paused` — unpausing must not restart.
var timeMs by remember(anim.id) { mutableFloatStateOf(0f) }
var canvasSize by remember { mutableStateOf(IntSize.Zero) }

val motionPath: Path? = remember(anim.id, canvasSize) { /* build from motionPathPoints */ }
```

with `Modifier.onSizeChanged { canvasSize = it }` and the draw block collapsing to a single
`motionPath?.let { drawPath(...) }`.

**KDoc correction (`:39`)** — replace *"One pose solve + ~20 filled paths per frame — trivially cheap
at 60fps"* with an accurate description noting the 29 extra solves for the 25 animations that declare a
path joint, now solved once in `remember`.

**L-14 — one word:** `PlayerScreen.kt:251`, the next-up preview, gets `paused = s.paused`.

### The hazards, addressed
- **Canvas size in the key**: yes, `remember(anim.id, canvasSize)`. The points are in pixel space and
  scale linearly with the canvas, which the test below proves.
- **Responds to `anim.id` on LazyColumn recycle**: `remember(anim.id, canvasSize)` re-keys, and the
  existing `LaunchedEffect(anim.id, paused)` already restarts the clock. Adding `remember(anim.id)` to
  `timeMs` additionally makes each new exercise start at its authored keyframe 0 instead of inheriting a
  random phase. *This is a visible behaviour change* — judged an improvement, but optional and droppable
  for zero visual delta.
- **DebugAnimScreen auto-cycling**: the id changes every 4 s; the view sits in
  `fillMaxWidth().aspectRatio(1f)` so `canvasSize` is stable and `onSizeChanged` fires once. Each id
  change recomputes 29 solves and restarts the clock — exactly what the QA screen wants. One caveat: on
  the very first composition `canvasSize == IntSize.Zero`, so the arc is absent for one frame per view.
  Acceptable; `BoxWithConstraints` would avoid it but the codebase uses `onSizeChanged` nowhere else.

### L-13 — the rest of the allocations: **do not fix**
`capsule`, `spineBlob` and `drawShadow`'s per-frame `Brush.radialGradient` all have inputs that
**genuinely change every frame** — the shadow's centre, radius and alpha track foot spread and jump
height. They are not hoistable. Making them allocation-free requires a mutable Path pool with manual
`reset()` and a quantised brush cache: exactly the kind of change that breaks a renderer covered by 13
geometric contract tests, for ~26 µs against an 8.3 ms budget. **Correct the KDoc, hoist the motion
path (the 95 % win), leave the rest.**

### Test
New `MotionPathTest.kt` — plain JUnit + Truth, no Robolectric (the point of returning `List<Offset>`),
in the spirit of `AnimGeometryTest`: half the registry declares a path; the path is deterministic
(which is what makes `remember` legal); **the path scales with the canvas, so canvas size MUST key the
cache**; every point lands inside the canvas; a `NONE` joint yields no points.

**Must still pass unchanged:** all 13 tests in `AnimGeometryTest` — `Rig`, `poseAt` and `groundContact`
are not touched; only *where and how often* they are called changes.

### Risk: **Needs care** (renderer). On-device: scroll the Library fast and confirm arcs draw on the
right joint; let DebugAnimScreen cycle all 50; pause during a rest and confirm both the main animation
*and* the next-up preview freeze.

---

## L-12 (LOW-MEDIUM) — `finishSession` has a `finally` but no `catch`

**Do not add a broad `catch`.** Match the file's own style (`runCatching` at `:510`, `:519`, `:533`) and
make the block **exception-total by construction**, which is stronger than catching:

```kotlin
// A DataStore read can throw IOException/CorruptionException (no ReplaceFileCorruptionHandler is
// configured). It is only consulted for two booleans, and defaults mean healthConnectEnabled =
// false — the safe fallback.
val settings = runCatching { settingsRepo.current() }.getOrDefault(AppSettings())
val blocks = runCatching { completedBlocks(...) }.getOrDefault(emptyList())
```

**Rationale for keeping `try { } finally { }` with no `catch`:** the `finally` must still run on
**cancellation** (`onDestroy` cancels `lifecycleScope`), and a `catch (Throwable)` would swallow
`CancellationException` and break structured concurrency.

**Rationale for `getOrDefault(AppSettings())`:** its defaults are `healthConnectEnabled = false`, so a
settings failure degrades to "don't write to Health Connect" — the safe direction. And crucially, with
`settings` no longer able to abort, `addHistory` still runs, so **the workout is written to history
even when the settings read fails** — the finding's actual harm.

### Test
New `SessionFinishTest.kt` (plain JUnit; **not** appended to `SessionEngineTest`, so that file stays
untouched). **Must still pass unchanged:** `SessionEngineTest`'s `completed block met is duration
weighted` and `block met aggregation excludes sentinel indices`, which pin `completedBlocks`' behaviour.

### Risk: **Safe.**

---

## L-15 (LOW) — audio-focus race

`@Volatile` alone would fix visibility but not the check-then-act at `:223`. Use a lock:

```kotlin
private val focusLock = Any()
private var focusRequest: AudioFocusRequest? = null   // guarded by focusLock

/** Three writers — speak()/stopSpeaking() on main, countdownBeeps() on the Main scope, and
 *  onUtteranceFinished() on a BINDER thread. A lock, not @Volatile: the guard is a
 *  check-then-act, not just a read. Both bodies are short non-blocking AudioManager IPCs. */
private fun requestFocus() = synchronized(focusLock) { ... }
private fun abandonFocus() = synchronized(focusLock) { ... }
```

Chosen over `AtomicReference` + CAS because with a CAS you must decide whether to claim the slot before
or after the IPC: claiming first can leave a slot marked held while an ungranted request is abandoned;
claiming after can leak a granted focus if the CAS loses. The lock has neither ambiguity, and is
uncontended >99.99 % of the time. Nothing inside the lock calls back into `VoiceCoach`, so deadlock is
impossible.

### Test
Widen to `internal` and add `hasAudioFocusForTest()` — the class already exposes `pendingCountForTest`
and `utteranceCountForTest()` in exactly this style. A 4000-iteration concurrent hammer test is
flaky-failing on the current code and deterministic after.

### Risk: **Safe.**

---

## L-16 (LOW) — `writeStoppedSnapshot` is a main-thread non-atomic write

```kotlin
/**
 * Written on a user stop so an accidental stop stays recoverable. It must COMPLETE before the
 * finally returns (the service may be destroyed moments later) — but synchronous does not mean
 * main-thread: NonCancellable + IO keeps the completion guarantee while moving a ~12 KB write off
 * the UI thread, and it works even when the finally is running because of cancellation.
 * tmp + rename (like maybeSnapshot at :607-610) means a kill mid-write leaves the PREVIOUS file
 * or none, never a truncated one — a truncated file passes hasStoppedSnapshot()'s existence +
 * mtime gate and was an independent second trigger for L-1's crash-plus-data-loss path.
 */
private suspend fun writeStoppedSnapshot(state: PlayerState, es: SessionEngine.EngineState?) =
    withContext(NonCancellable + Dispatchers.IO) { /* tmp write + rename */ }
```

`NonCancellable` overrides the `Job` in the child context, so the block is not itself cancelled even
when the enclosing coroutine already is — the documented idiom for suspending cleanup.

**No fsync.** `rename(2)` within the same directory is atomic for the directory entry; L-20 established
fsync isn't worth it, and L-1b now *parses* the file before offering the button, so a non-durable
residue is caught.

### Risk: **Safe.**

---

## L-17 (LOW) — disk I/O inside composition on the Summary screen

**Fixed as part of L-1b** — `hasStoppedSnapshot(context)` at `SummaryScreen.kt:175` is replaced by
`viewModel.resumeWindowMs`, a `StateFlow` fed by a `Dispatchers.IO` read+parse, honouring the
codebase's own rule at `HomeScreen.kt:76`. Same bug surface, same edit.

---

## L-18 — unbounded history: **document, don't fix**
See L-3. After the projection, 365 rows cost ~110 KB of `blocksJson` parsing off the main thread.
Retention or a `LIMIT` would break the streak calculation, the full-history calendar and the 4-week
trends, and would delete a single-user app's most valuable data to solve a problem the projection
already solved.

## L-19 — `mediaPlayback` FGS type: **no code change**
The platform does not runtime-verify a `mediaPlayback` prerequisite on Android 14/15, and it is exempt
from Android 15's FGS timeout regime. The type selection is correct and legal; D-03 already records the
reasoning; the app is sideloaded so the Play-policy expectation does not apply. The residual is
cosmetic: `MediaSessionCompat` is never made active, so `MediaStyle` degrades to the standard layout.
Option (b) — activate it and publish a `PlaybackStateCompat` — is only worth it if the app ever goes to
Play, and would be **Needs care** because it touches the notification path, the highest-blast-radius
surface in the app.

*Side benefit of L-1a:* `goForeground`'s idempotence now **guarantees** the FGS type is fixed for the
life of a session, so a mid-session `ACTIVITY_RECOGNITION` revoke can no longer produce a
HEALTH→MEDIA_PLAYBACK transition.

## L-20 — 5 s snapshot volume: **no action**
Every impact claim was falsified (~6 MB/session not 16 MB; ~2 GB/year against petabyte-class UFS
endurance; 540 small buffered writes on `Dispatchers.IO` over 45 min). The one thing worth doing —
tmp + rename — is already done at `:607-610`. Update D-16 with the corrected figures so the next
auditor doesn't re-raise it.

## L-21 — `retryInit` orphans TTS: one line
```kotlin
// Symmetric with warmUp's RETRY/FAIL branches: never drop a TextToSpeech binding without
// shutting it down. A no-op today — both call sites are gated on FAILED, where tts was already
// nulled at :100 — but the next caller would leak an engine.
tts?.shutdown()
tts = null
```
**Risk: Safe.**

## L-22 — `shutdown()` never called: **document, don't wire**
Calling `voice.shutdown()` from `onDestroy` would be **wrong**: `VoiceCoach` is a process-scoped
`@Singleton` also used by `PlayerScreen` and `SettingsScreen`, and tearing it down would cost 2–3 s of
silence at the start of the next session. Its retention — one binding, one scope — is bounded and
constant, which verification correctly established is not a leak. **The current state is right**; the
finding's real content is "dead code with no explanation". Add the KDoc that says so.

---

# Implementation order

**Land these together — they are one bug surface**

1. **L-1a + L-4** — both live in `onStartCommand`/`finishSession` and share the `runGeneration`
   mechanism and `startId` threading. Splitting them means writing the generation counter twice. *(Invasive)*
2. **L-1b/c + L-17 + L-16** — the Summary resume path. L-16's atomic write is what makes L-1b's parse
   gate the second line of defence rather than the only one. *(Invasive)*
3. **L-3 + L-8 + L-18** — one repository/DAO/model change; L-8 is a call-site of it and L-18 is a
   documented non-fix conclusion drawn from it. *(Needs care)*
4. **L-11 + L-13 + L-14** — one file, one renderer review pass; L-13's conclusion is "don't", but it
   must be *decided* in the same session. *(Needs care)*

**Fully independent**

- **L-2** — must land **alone**, with **no entity change in the same commit**, and step 4's on-device
  identity-hash verification must be performed before the next release. *(Safe)*
- **L-5** *(Needs care)* · **L-6** *(Safe)* · **L-7** *(Needs care)* · **L-9** *(Safe)* ·
  **L-10** *(Safe)* · **L-12** *(Safe — but sequence with group 1 to avoid a conflict in the same
  function)* · **L-15 + L-21** *(Safe, land together)* · **L-19, L-20, L-22** *(docs only)*

**Recommended sequence:** L-2 first (alone, unblocks all future schema work, zero risk) → L-6, L-9,
L-10, L-15+L-21, L-12 (all Safe, build confidence and a test baseline) → group 1 → group 2 → L-7 →
group 3 → L-5 → group 4 → the documentation-only items.

# Not worth fixing — document instead

- **L-18** retention: the projection removes the cost; deleting a streak app's history would be user-hostile.
- **L-19** FGS type / MediaSession: correct and legal today; sideloaded, so Play policy doesn't bind.
- **L-20** 5 s snapshot: every impact claim was falsified.
- **L-22** `shutdown()`: the current non-wiring is the *right* design.
- **L-7's** auto-finish watchdog: the wake-lock fix removes the battery cost; a timer that silently ends
  a workout is worse than the notification it removes.
- **L-13's** per-frame `Path`/`Brush` allocations: inputs genuinely change per frame; the pooling
  required would risk a renderer with 13 geometric contract tests for ~26 µs.
- **L-10's** DST drift: self-corrects on the next occurrence; one reminder, twice a year.
- **L-6's** missing `android:configChanges`: the manifest is not the bug; consuming the intent is the
  correct, targeted fix.

# Tests that must still pass, unchanged

`SessionEngineTest` (all, including the R-19 tick clamp and `stopArmDecision`), `SessionSnapshotCompatTest`,
`AnimGeometryTest` (all 13), `ExportImportAndMiscTest`, `WorkoutGeneratorTest`,
`WorkoutGeneratorTimeBudgetTest`, `RestModeTest`, `DatabaseValidatorTest`, `SummaryNavigationTest`,
`VoiceCoachStatusTest` (the four existing cases), `CalorieAndCueTest`, `DisplayNameTest`,
`HealthConnectIdTest`, `SettingsRoundTripTest`, `ThemePaletteContrastTest`.

**New test files:** `ServiceCommandTest`, `StoppedSnapshotGateTest`, `RoomSchemaBaselineTest`,
`WorkoutRepositoryProjectionTest`, `ExerciseRepositoryTest`, `LaunchIntentTest`, `ReminderChainTest`,
`MotionPathTest`, `SessionFinishTest` — nine files, all JUnit4 + Truth, Robolectric only where a
`Context` or `filesDir` is genuinely required, all offline.
