I have explored everything needed. Here is the Phase 4 implementation plan.

---

# Phase 4 — Learning/Progression Loop: Implementation Plan

## 0. Verified current state (facts the plan is built on)

- **DB**: `KinetiqDatabase` is at **version 1**, `exportSchema = false`, 7 entities, **no migrations exist**, and `AppModule.provideDatabase` uses `.fallbackToDestructiveMigration()` (`/home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/di/AppModule.kt:32`). Phase 4 introduces the first real migration and MUST remove the destructive fallback or user history will be wiped.
- **`SessionHistoryEntity`** (`data/db/Entities.kt:37-50`): `id, startedAtEpochMs, endedAtEpochMs, name, totalActiveSec, calories, blocksJson, healthConnectWritten, sessionJson`. No RPE fields.
- **`CompletedBlock`** (`data/repo/WorkoutRepository.kt:15-23`): `category: String, activeSec: Int, calories: Double, isHiit: Boolean, startedAtEpochMs, endedAtEpochMs`. **No MET stored**; per-session weight is NOT persisted, so MET-min is *not* exactly back-derivable from `calories` for old rows (only approximately, using the *current* weight).
- **Service**: no `SessionEngine` extraction exists yet in this repo (Phase 1 dependency). `skipStep` (`WorkoutSessionService.kt:357`) calls `advanceStep(state, 0.0)`; `extendStep` (`:363`) adds 30 000 ms to `stepRemainingMs`; natural advance happens in `onTick` (`:232` → `advanceStep` at `:247`); `finishSession` (`:370`) writes the history row via `workoutRepo.addHistory` and publishes `CompletedSummary(historyId=…)` which the Summary screen reads — so sRPE is an **UPDATE** by `historyId`.
- **Generator**: constructor `WorkoutGenerator(exercises, routines, machines = MachineSettings(), random = Random.Default)` (`WorkoutGenerator.kt:61-66`); `pool()` at `:165`; `pickBalanced` at `:271` (Int score = `targets.size + random.nextInt(3)` + biases); `machineBlock` at `:300-376` (routine cost = `abs(totalSec-blockSec) + random.nextInt(60)`, fallback = `candidates.shuffled(random)` round-robin). Builder constructs it at `BuilderScreen.kt:97-101`.
- **Settings**: DataStore prefs; `lastConfig` key already persists the builder's `GeneratorConfig` JSON and seeds the Builder on open (`BuilderViewModel.init`, `BuilderScreen.kt:76-86`). This is the natural home for accepted progression defaults.
- **`WeeklyPlanEngine`** (`domain/plan/WeeklyPlanEngine.kt`): per-entry truncation bug at `:58` (`cardioMin += cardioSec / 60` truncates per entry); no vigorous double-count; **zero tests** (confirmed: no `WeeklyPlanEngineTest` in `app/src/test`).
- **Tests**: all pure JVM (JUnit4 + Truth); Robolectric 4.14.1 is already a `testImplementation` dependency but **no test currently uses it**; no `androidTest` sources beyond the default deps; `room-testing` is NOT in the version catalog. `WorkoutGeneratorTest` injects `Random(42)` and reads `src/main/assets/exercise_db.json`.
- Reminders: `ReminderScheduler.schedule(context, days, hour, minute)` + `SettingsRepository.setReminder(days, hour, minute)` is the existing one-tap path (`reminders/Reminders.kt:34`, `SettingsRepository.kt:167`).
- Export: `ExportedHistoryEntry` (`data/export/ExportImport.kt:31-40`) must gain the new nullable feedback fields (optional, defaulted — format stays backward compatible under `ignoreUnknownKeys`).

**Version note**: this plan writes the migration as `MIGRATION_1_2`. If Phases 1–3 have already bumped the DB version by implementation time, renumber to `MIGRATION_N_N+1` — the DDL is unchanged. All Phase 4 schema changes go into **one** migration.

---

## Implementation sequence (dependency order)

1. **Step A — Schema + migration** (items 1+2+3 schema): new entities, ALTERs, DAOs, `MIGRATION_1_2`, AppModule wiring, gradle test deps. Everything else depends on this.
2. **Step B — Step-event logging** (item 1): pure `StepEventRecorder` + service hooks + snapshot extension + flush in `finishSession`.
3. **Step C — sRPE capture** (item 2): Summary UI + update DAO/repo path + export fields.
4. **Step D — Preference model** (item 3): `PreferenceMath`/`PreferenceRepository`/`PreferenceUpdater`, generator hooks, Builder chips, Settings toggle/reset. Depends on B (events) and A.
5. **Step E — Progression engine** (item 5): pure engine + Builder card + persistence. Depends on C (sRPE) and the MET-minutes derivation from F — implement `CompletedBlock.metMinutes` (part of F) before or together with E.
6. **Step F — Weekly dose meter + WeeklyPlanEngine fixes** (item 6): `metMinutes` on `CompletedBlock`, engine fixes, PlanScreen row. Independent of B–D except `metMinutes` write in `finishSession`.
7. **Step G — Time-of-day nudge** (item 4): pure evaluator + Plan card + DataStore state. Independent; needs only history.
8. **Step H — Tests** (item 7): written alongside each step; migration/DAO tests last in Step A.

Practical ordering for one agent: **A → F(metMinutes write only) → B → C → D → E → F(UI+engine) → G**, tests with each step.

---## Step A — Room schema, migration 1→2, DAOs, gradle

### Goal
Add `step_events` and `exercise_prefs` tables, three nullable feedback columns on `session_history`, the first real migration, and stop destructive fallback.

### Files
- Touched: `app/src/main/java/au/mark/kinetiq/data/db/Entities.kt`, `Daos.kt`, `KinetiqDatabase.kt`, `di/AppModule.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`
- New: `app/src/test/java/au/mark/kinetiq/db/MigrationTest.kt`, `app/src/test/java/au/mark/kinetiq/db/DaoTest.kt`

### Exact changes

**Entities.kt — append:**

```kotlin
import androidx.room.ForeignKey
import androidx.room.Index

/** One terminal event per WORK step of a played session (COMPLETED/EXTENDED/SKIPPED/ABANDONED). */
@Entity(
    tableName = "step_events",
    foreignKeys = [ForeignKey(
        entity = SessionHistoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionHistoryId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionHistoryId"), Index("exerciseId"), Index("epochMs")],
)
data class StepEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Set when flushed at finishSession; CASCADE-deleted with the history row. */
    val sessionHistoryId: Long?,
    val exerciseId: String,
    val category: String,
    /** COMPLETED, EXTENDED, SKIPPED, ABANDONED — see StepEventType. */
    val eventType: String,
    val plannedSec: Int,
    val actualSec: Int,
    /** workIndex / totalWorkSteps, workIndex 0-based over StepType.WORK steps only. */
    val positionFrac: Float,
    /** Minutes since local midnight at the moment the event was recorded. */
    val minuteOfDay: Int,
    /** ISO day-of-week, 1=Mon..7=Sun. */
    val dayOfWeek: Int,
    val epochMs: Long,
)

/** Learned per-exercise preference score with exponential decay (half-life 21 days). */
@Entity(tableName = "exercise_prefs")
data class ExercisePrefEntity(
    @PrimaryKey val exerciseId: String,
    val score: Double,
    /** Terminal WORK-step events ever recorded for this exercise. */
    val exposures: Int,
    /** Events with positive value (COMPLETED or EXTENDED). */
    val completions: Int,
    /** Number of DISTINCT SESSIONS containing >=1 SKIPPED event for this exercise. */
    val skips: Int,
    val lastUpdatedEpochMs: Long,
)
```

**SessionHistoryEntity — add three trailing nullable columns:**

```kotlin
    /** Session RPE 0–10, null = not answered. */
    val srpe: Int? = null,
    /** TOO_EASY | ABOUT_RIGHT | TOO_HARD, null = not answered. */
    val perceivedDifficulty: String? = null,
    /** Enjoyment 1–5, null = not answered. */
    val enjoyment: Int? = null,
```

**Daos.kt — new DAOs + one addition to WorkoutDao:**

```kotlin
@Dao
interface StepEventDao {
    @Insert suspend fun insertAll(events: List<StepEventEntity>)
    @Query("SELECT * FROM step_events WHERE sessionHistoryId = :historyId") 
    suspend fun forSession(historyId: Long): List<StepEventEntity>
    @Query("SELECT * FROM step_events WHERE exerciseId = :exerciseId ORDER BY epochMs DESC")
    suspend fun forExercise(exerciseId: String): List<StepEventEntity>
    @Query("SELECT * FROM step_events WHERE epochMs >= :sinceEpochMs")
    suspend fun since(sinceEpochMs: Long): List<StepEventEntity>
    @Query("DELETE FROM step_events") suspend fun clear()
}

@Dao
interface ExercisePrefDao {
    @Query("SELECT * FROM exercise_prefs") suspend fun all(): List<ExercisePrefEntity>
    @Query("SELECT * FROM exercise_prefs WHERE exerciseId = :id") suspend fun forExercise(id: String): ExercisePrefEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<ExercisePrefEntity>)
    @Query("DELETE FROM exercise_prefs") suspend fun clear()
}
```

Add to `WorkoutDao`:

```kotlin
@Query("UPDATE session_history SET srpe = :srpe, perceivedDifficulty = :difficulty, enjoyment = :enjoyment WHERE id = :id")
suspend fun setSessionFeedback(id: Long, srpe: Int?, difficulty: String?, enjoyment: Int?)
```

**KinetiqDatabase.kt:**

```kotlin
@Database(
    entities = [ExerciseEntity::class, RoutineEntity::class, DbMetaEntity::class,
        SavedWorkoutEntity::class, SessionHistoryEntity::class, ManualMeasurementEntity::class,
        CachedHealthMetricEntity::class, StepEventEntity::class, ExercisePrefEntity::class],
    version = 2,
    exportSchema = true,   // enable schema export from here on
)
abstract class KinetiqDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao
    abstract fun workoutDao(): WorkoutDao
    abstract fun measurementDao(): MeasurementDao
    abstract fun stepEventDao(): StepEventDao
    abstract fun exercisePrefDao(): ExercisePrefDao

    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session_history ADD COLUMN srpe INTEGER")
                db.execSQL("ALTER TABLE session_history ADD COLUMN perceivedDifficulty TEXT")
                db.execSQL("ALTER TABLE session_history ADD COLUMN enjoyment INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `step_events` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionHistoryId` INTEGER, `exerciseId` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`eventType` TEXT NOT NULL, `plannedSec` INTEGER NOT NULL, `actualSec` INTEGER NOT NULL, " +
                        "`positionFrac` REAL NOT NULL, `minuteOfDay` INTEGER NOT NULL, `dayOfWeek` INTEGER NOT NULL, " +
                        "`epochMs` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`sessionHistoryId`) REFERENCES `session_history`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_step_events_sessionHistoryId` ON `step_events` (`sessionHistoryId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_step_events_exerciseId` ON `step_events` (`exerciseId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_step_events_epochMs` ON `step_events` (`epochMs`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `exercise_prefs` (" +
                        "`exerciseId` TEXT NOT NULL, `score` REAL NOT NULL, `exposures` INTEGER NOT NULL, " +
                        "`completions` INTEGER NOT NULL, `skips` INTEGER NOT NULL, " +
                        "`lastUpdatedEpochMs` INTEGER NOT NULL, PRIMARY KEY(`exerciseId`))"
                )
            }
        }
    }
}
```

**AppModule.kt** — replace `.fallbackToDestructiveMigration()` with `.addMigrations(KinetiqDatabase.MIGRATION_1_2)` (keep `.fallbackToDestructiveMigrationOnDowngrade()` as a safety valve). Add `@Provides fun provideStepEventDao(db) = db.stepEventDao()` and `@Provides fun provideExercisePrefDao(db) = db.exercisePrefDao()`.

**Gradle**: in `gradle/libs.versions.toml` add `androidx-room-testing = { group = "androidx.room", name = "room-testing", version.ref = "room" }` and `androidx-test-core = { group = "androidx.test", name = "core", version = "1.6.1" }`. In `app/build.gradle.kts` add `testImplementation(libs.androidx.room.testing)`, `testImplementation(libs.androidx.test.core)`, and `ksp { arg("room.schemaLocation", "$projectDir/schemas") }`.

### Tests

`app/src/test/java/au/mark/kinetiq/db/MigrationTest.kt` — `@RunWith(RobolectricTestRunner::class)`. Because no v1 schema JSON was ever exported (exportSchema was false), do NOT use `MigrationTestHelper`; use the pragmatic pure-JVM approach:

- `fun migrate 1 to 2 preserves history and creates new tables()`:
  1. Create a file DB via `SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("mig.db"), null)`; execute the exact v1 DDL for **all seven** v1 tables (Room-style DDL; booleans as `INTEGER NOT NULL`), e.g. session_history: `CREATE TABLE IF NOT EXISTS \`session_history\` (\`id\` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, \`startedAtEpochMs\` INTEGER NOT NULL, \`endedAtEpochMs\` INTEGER NOT NULL, \`name\` TEXT NOT NULL, \`totalActiveSec\` INTEGER NOT NULL, \`calories\` REAL NOT NULL, \`blocksJson\` TEXT NOT NULL, \`healthConnectWritten\` INTEGER NOT NULL, \`sessionJson\` TEXT NOT NULL)`; insert one session_history row; `PRAGMA user_version = 1`; close.
  2. Open with `Room.databaseBuilder(context, KinetiqDatabase::class.java, "mig.db").addMigrations(KinetiqDatabase.MIGRATION_1_2).build()` and run a query. Room's open-helper validates the post-migration schema against the generated v2 expectations — a wrong migration throws `IllegalStateException("Migration didn't properly handle…")`, failing the test.
  3. Assert: `workoutDao().historyOnce()` has size 1 with `srpe == null`; `dao.setSessionFeedback(id, 7, "ABOUT_RIGHT", 4)` then re-read asserts values; `stepEventDao().insertAll(...)` + `forSession(id)` round-trips; `exercisePrefDao().upsertAll` + `all()` round-trips.
- `fun deleting a history row cascades its step events()` — insert history + 2 events with that `sessionHistoryId`, `deleteHistory(id)`, assert `forSession(id)` empty. (Note: enable FK enforcement is automatic in Room.)

`DaoTest.kt` (Robolectric, `Room.inMemoryDatabaseBuilder`): 
- `fun step events query by exercise orders by epoch descending()`
- `fun exercise pref upsert replaces by primary key()`
- `fun exercise pref clear removes all rows()` (this is the "reset" DAO test for item 3).

### Acceptance criteria
- App upgrade from a real v1 DB keeps all history/saved workouts (no destructive wipe).
- `./gradlew testDebugUnitTest` green; `schemas/au.mark.kinetiq.data.db.KinetiqDatabase/2.json` generated and committed.

### Dependencies
None (first step). If Phase 1–3 changed the DB version, renumber.

---

## Step B — Step-event logging (item 1)

### Goal
Record exactly **one terminal event per WORK step** of a played session, buffered in memory (and in the crash snapshot), flushed to Room with the `historyId` at `finishSession`.

### Files
- New: `app/src/main/java/au/mark/kinetiq/domain/session/StepEventRecorder.kt`
- Touched: `service/WorkoutSessionService.kt`, `service/SessionState.kt` (snapshot), `data/repo/WorkoutRepository.kt`

### Exact semantics (remove all ambiguity)

Only `StepType.WORK` steps produce events (warm-up/cool-down/rest/transition never do). Per WORK step, terminal classification:

| Situation | eventType | actualSec |
|---|---|---|
| Ran to natural end, no extend pressed | `COMPLETED` | `plannedSec` |
| Extend pressed ≥1× during the step, then ran to (extended) natural end | `EXTENDED` | `plannedSec + 30 * extendCount` |
| Skip pressed with `elapsedInStep / plannedSec >= 0.8` | `COMPLETED` (shortened — value +0.5 comes from `actualSec < plannedSec`) | elapsed whole seconds |
| Skip pressed with `elapsedInStep / plannedSec < 0.8` | `SKIPPED` | elapsed whole seconds |
| Session stopped (`finishSession(userStopped = true)`): current WORK step | `ABANDONED` | elapsed whole seconds |
| Session stopped: later WORK steps never reached | `ABANDONED` | `0` |
| Skip after extend | skip rules win (elapsed compared against original `plannedSec`) |

**Reclassification rule (exact)**: at `onSessionEnd(userStopped = true)`, let `stopWorkIndex` = work-index of the current step (or `totalWorkSteps` if the session had advanced past the last step). Any already-buffered `SKIPPED` event whose `workIndex >= stopWorkIndex - 2` is rewritten to `ABANDONED` (value −0.1 instead of −1.0/−0.3) before flush. Buffered in memory, so this is a list rewrite, not a DB update.

`positionFrac = workIndex.toFloat() / totalWorkSteps` (0-based index among WORK steps; `totalWorkSteps = steps.count { it.type == WORK }`; guard divide-by-zero → 0f). `minuteOfDay`/`dayOfWeek` from `Instant.ofEpochMilli(nowEpochMs).atZone(zone)` → `hour*60+minute` and `dayOfWeek.value`.

### Class design

```kotlin
package au.mark.kinetiq.domain.session

/** Buffered event; @Serializable so it survives in the session snapshot. */
@kotlinx.serialization.Serializable
data class PendingStepEvent(
    val workIndex: Int,
    val exerciseId: String,
    val category: String,
    val eventType: String,       // StepEventType.name
    val plannedSec: Int,
    val actualSec: Int,
    val positionFrac: Float,
    val minuteOfDay: Int,
    val dayOfWeek: Int,
    val epochMs: Long,
)

enum class StepEventType { COMPLETED, EXTENDED, SKIPPED, ABANDONED }

/** Pure step-event classifier. No Android/Room deps — unit-testable on the JVM.
 *  If Phase 1's SessionEngine extraction has landed, instantiate this inside SessionEngine
 *  and forward the same three calls; the service hooks below then move with it. */
class StepEventRecorder(
    private val steps: List<SessionStep>,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    enum class EndCause { NATURAL, SKIPPED }

    private val workIndexByStep: Map<Int, Int>   // stepIndex -> 0-based work index
    private val totalWork: Int
    private val extendCounts = mutableMapOf<Int, Int>()          // stepIndex -> extend presses
    private val pending = mutableListOf<PendingStepEvent>()

    fun onExtend(stepIndex: Int)                                  // extendCounts[stepIndex]++
    fun onStepEnded(stepIndex: Int, elapsedSec: Int, cause: EndCause, nowEpochMs: Long)
    /** Terminal call. Classifies the current step + un-reached WORK steps when userStopped,
     *  applies the skip->abandon reclassification, returns the final buffer. */
    fun onSessionEnd(currentStepIndex: Int, elapsedInCurrentSec: Int, userStopped: Boolean, nowEpochMs: Long): List<PendingStepEvent>
    /** For snapshot save/restore. */
    fun snapshotEvents(): List<PendingStepEvent>
    fun restore(events: List<PendingStepEvent>)
}
```

`onStepEnded` classification uses the table above; `plannedSec = steps[stepIndex].durationSec`; NATURAL with `extendCounts > 0` → EXTENDED. Non-WORK steps: no-op. Note `onSessionEnd(userStopped = false)` (natural finish) only flushes — the last step already got its `onStepEnded(NATURAL)` from the tick loop.

### Service hooks (exact)

- Field: `private var recorder: StepEventRecorder? = null`. Create in `startSession` and `restoreFromSnapshot` (`StepEventRecorder(session.plan.steps)`; on restore also `recorder.restore(snap.events)`).
- `onTick` natural advance (`WorkoutSessionService.kt:232`): immediately before `advanceStep(state, carryCalories=...)` call `recorder?.onStepEnded(state.stepIndex, elapsedSec = step.durationSec, NATURAL, System.currentTimeMillis())`. **Important**: `extendStep` increases `stepRemainingMs` but not `step.durationSec`, so for NATURAL ends pass `elapsedSec = step.durationSec + 30 * extendCount` — the recorder owns extendCounts, so simply pass `step.durationSec` and let the recorder add `30 * extendCounts[stepIndex]` internally. Specify: recorder adds the extension seconds itself for NATURAL ends.
- `skipStep` (`:357`): before `advanceStep`, compute `elapsedSec = (step.durationSec + 30 * extendCount(stepIndex)) - (state.stepRemainingMs / 1000).toInt()` — implement as `recorder?.onStepEnded(state.stepIndex, elapsedSec, SKIPPED, now)` where the service computes elapsed from `stepRemainingMs`; the recorder compares elapsed against **original** `plannedSec` for the 0.8 rule.
- `extendStep` (`:363`): `recorder?.onExtend(state.stepIndex)` alongside the existing `stepRemainingMs` bump.
- `finishSession` (`:370`), inside the coroutine after `addHistory` returns `historyId`:

```kotlin
val events = recorder?.onSessionEnd(state.stepIndex,
    elapsedInCurrentSec = elapsedOfCurrentStep(state), userStopped, endedAt).orEmpty()
workoutRepo.addStepEvents(historyId, events)
```

where `elapsedOfCurrentStep(state) = currentStep.durationSec + 30*extends - (state.stepRemainingMs/1000)`, coerced `>= 0`.
- **Snapshot**: `SessionSnapshot` gains `val events: List<PendingStepEvent> = emptyList()` and `val extendCounts: Map<Int, Int> = emptyMap()` (defaults keep old snapshots decodable; `Json` has `ignoreUnknownKeys=true` + defaults). `maybeSnapshot` includes `recorder.snapshotEvents()`/counts; `restoreFromSnapshot` rebuilds.

### Repository addition

```kotlin
suspend fun addStepEvents(historyId: Long, events: List<PendingStepEvent>) =
    stepEventDao.insertAll(events.map {
        StepEventEntity(sessionHistoryId = historyId, exerciseId = it.exerciseId, category = it.category,
            eventType = it.eventType, plannedSec = it.plannedSec, actualSec = it.actualSec,
            positionFrac = it.positionFrac, minuteOfDay = it.minuteOfDay, dayOfWeek = it.dayOfWeek, epochMs = it.epochMs)
    })
suspend fun stepEventsSince(sinceEpochMs: Long): List<StepEventEntity> = stepEventDao.since(sinceEpochMs)
suspend fun stepEventsFor(historyId: Long): List<StepEventEntity> = stepEventDao.forSession(historyId)
```

(`WorkoutRepository` constructor gains `private val stepEventDao: StepEventDao`.)

### Tests — `app/src/test/java/au/mark/kinetiq/StepEventLoggingTest.kt` (pure JVM, tests `StepEventRecorder` with a hand-built 5-WORK-step plan, fixed `nowEpochMs`, fixed `ZoneId.of("Australia/Sydney")`)
- `fun natural completion records COMPLETED with actual equal to planned()` — assert single event, `eventType == "COMPLETED"`, `actualSec == plannedSec`, `positionFrac == 0f` for first work step.
- `fun extend then natural completion records EXTENDED with extension added()` — two extends then natural end → `eventType == "EXTENDED"`, `actualSec == plannedSec + 60`.
- `fun skip before 80 percent records SKIPPED, skip at or after 80 percent records shortened COMPLETED()` — skip at elapsed = 0.5×planned → SKIPPED; at 0.85×planned → COMPLETED with `actualSec < plannedSec`.
- `fun user stop marks current and remaining work steps ABANDONED()` — complete step 0, stop during step 2 (work index 1) → events for work indices 1..4 all ABANDONED, actualSec 0 for unreached ones.
- `fun skip within two steps of an early stop is reclassified to ABANDONED()` — skip at work index 2, complete 3, stop during 4 → the index-2 SKIPPED becomes ABANDONED (2 ≥ 4−2); a skip at index 0 stays SKIPPED.
- `fun rest and transition steps never produce events()`.
- `fun minuteOfDay and dayOfWeek derive from epoch in zone()` — known epoch → asserted values.

### Acceptance criteria
- Playing a session to the end produces exactly `totalWorkSteps` rows in `step_events`, all with the session's `historyId`; deleting the history entry deletes them.
- Kill-and-restore mid-session (snapshot path) loses no already-recorded events.

### Dependencies
Step A. **Phase 1 contingency**: if `SessionEngine` exists, the recorder is owned/driven by it instead of the service; the classification class and tests are unchanged.

---

## Step C — Session-RPE capture (item 2)

### Goal
One optional, no-nag feedback block on the existing Summary screen; writes are UPDATEs against the already-inserted history row.

### Files
- Touched: `ui/screens/summary/SummaryScreen.kt`, `data/repo/WorkoutRepository.kt`, `data/export/ExportImport.kt` (schema columns/DAO already done in Step A)

### Exact changes

**Repository:**

```kotlin
enum class PerceivedDifficulty { TOO_EASY, ABOUT_RIGHT, TOO_HARD }

suspend fun setSessionFeedback(historyId: Long, srpe: Int?, difficulty: PerceivedDifficulty?, enjoyment: Int?) =
    dao.setSessionFeedback(historyId, srpe?.coerceIn(0, 10), difficulty?.name, enjoyment?.coerceIn(1, 5))
```

`HistoryEntry` gains `val srpe: Int? = null, val perceivedDifficulty: String? = null, val enjoyment: Int? = null`, mapped in `SessionHistoryEntity.toModel()`; `addHistory`/`importHistory` pass nulls (or imported values).

**SummaryViewModel** — add:

```kotlin
fun setFeedback(srpe: Int?, difficulty: PerceivedDifficulty?, enjoyment: Int?) {
    val id = stateHolder.lastCompleted.value?.historyId ?: return
    viewModelScope.launch { workoutRepository.setSessionFeedback(id, srpe, difficulty, enjoyment) }
}
```

**SummaryScreen UI placement**: insert a new section between the "Per-block breakdown" cards and the "Health Connect" section (i.e. after line 90, before line 92 of `SummaryScreen.kt`):

```
SectionHeader("How did that feel? (optional)")
- FlowRow of 3 FilterChips: "too easy" / "about right" / "too hard"  (single-select, deselectable)
- Text("Effort 0–10" + current value); Slider(value 0..10, steps = 9); value only committed
  once the user touches it (start slider "unset": render a leading "skip" state via a
  `var srpeTouched by remember { mutableStateOf(false) }`)
- Row of 5 IconButton stars for enjoyment (1–5), single-select, deselectable
```

Local `remember` state for the three inputs; every change calls `viewModel.setFeedback(...)` with the current trio (idempotent UPDATE — no save button, no dialog, no blocking; "Done" works regardless). No reminders/notifications ever reference it (no nag).

**Export**: `ExportedHistoryEntry` gains `val srpe: Int? = null, val perceivedDifficulty: String? = null, val enjoyment: Int? = null`; `buildExport` copies them; `applyImport` passes them through `importHistory`. `FORMAT_VERSION` stays 1 (additive optional fields; old files decode via defaults, old apps ignore unknown keys).

### Tests (in `app/src/test/java/au/mark/kinetiq/db/DaoTest.kt` + `ExportImportAndMiscTest.kt`)
- `fun setSessionFeedback updates only the target row and clamps ranges()` — insert two history rows, `setSessionFeedback(id1, 12, TOO_HARD, 9)` → row1 `srpe == 10`, `enjoyment == 5`; row2 untouched (nulls).
- `fun feedback is nullable and defaults to null on insert()`.
- In `ExportImportAndMiscTest`: `fun export round-trips session feedback fields()` and `fun import of pre-phase4 export file without feedback fields succeeds()` (decode a JSON literal lacking the new keys → nulls).

### Acceptance criteria
- Finishing a workout and tapping "too hard" + sRPE 8 persists to the history row; skipping straight to Done leaves all three NULL; the section renders once, on Summary only.

### Dependencies
Step A (columns + DAO). Feeds Step E.

---

## Step D — Preference model (item 3)

### Goal
Explainable per-exercise preference weights learned from step events; multiplicative, guard-railed nudges inside the generator; never touches pool/evidence/contraindication/coverage logic.

### Files
- New: `app/src/main/java/au/mark/kinetiq/domain/prefs/PreferenceMath.kt`, `domain/prefs/PreferenceWeights.kt` (or same file), `data/repo/PreferenceRepository.kt`
- Touched: `domain/generator/WorkoutGenerator.kt`, `ui/screens/builder/BuilderScreen.kt`, `ui/screens/settings/SettingsScreen.kt`, `data/repo/SettingsRepository.kt`, `service/WorkoutSessionService.kt` (one call)
- New test: `app/src/test/java/au/mark/kinetiq/PreferenceModelTest.kt`

### Exact math (single source of truth: `PreferenceMath`)

```kotlin
object PreferenceMath {
    const val HALF_LIFE_DAYS = 21.0
    val LAMBDA = ln(2.0) / HALF_LIFE_DAYS                      // per day
    const val MS_PER_DAY = 86_400_000.0

    /** score * exp(-λ * Δt_days); Δt < 0 treated as 0. */
    fun decayed(score: Double, lastUpdatedEpochMs: Long, nowEpochMs: Long): Double =
        score * exp(-LAMBDA * ((nowEpochMs - lastUpdatedEpochMs).coerceAtLeast(0) / MS_PER_DAY))

    /** Event value v. */
    fun eventValue(eventType: String, plannedSec: Int, actualSec: Int, positionFrac: Float): Double = when (eventType) {
        "EXTENDED" -> 1.5
        "COMPLETED" -> if (actualSec >= plannedSec) 1.0 else 0.5      // shortened completion
        "SKIPPED" -> if (positionFrac < 0.6f) -1.0 else -0.3          // late-session skip discounted
        "ABANDONED" -> -0.1
        else -> 0.0
    }

    /** Guard-railed multiplicative weight. */
    fun weight(decayedScore: Double, exposures: Int, distinctSkipSessions: Int): Double {
        if (exposures < 3) return 1.1                                  // novelty bonus, fixed
        var w = (1.0 + 0.3 * tanh(decayedScore / 4.0)).coerceIn(0.7, 1.3)
        if (distinctSkipSessions < 2) w = max(w, 1.0)                  // a single skip never binds
        return w
    }

    const val EPSILON = 0.15                                           // per-slot ignore probability
}
```

**Update rule (write path, `PreferenceRepository.applySessionEvents`)** — for each exerciseId with events in the finished session:
`score' = decayed(score, lastUpdated, now) + Σ eventValue(e)` over that exercise's events this session; `exposures += events.size`; `completions += events.count { type in {COMPLETED, EXTENDED} }`; `skips += if (events.any { type == SKIPPED }) 1 else 0` (**at most +1 per session — this per-distinct-session counter is the "single skip never binds" mechanism**: weight is floored at 1.0 until `skips >= 2`); `lastUpdatedEpochMs = now`. New exercises start from `score = 0.0, exposures = 0, completions = 0, skips = 0`. Decay is applied **on read too** (`weight()` is always fed `decayed(...)`), so an unused exercise's score relaxes toward 0 (weight → 1.0) even without writes.

### PreferenceWeights interface (generator-facing, keeps tests deterministic)

```kotlin
package au.mark.kinetiq.domain.generator

fun interface PreferenceWeights {
    /** Multiplicative selection weight in [0.7, 1.3]; 1.0 = neutral. */
    fun weight(exerciseId: String): Double
    companion object { val NEUTRAL: PreferenceWeights = PreferenceWeights { 1.0 } }
}
```

`WorkoutGenerator` constructor becomes:

```kotlin
class WorkoutGenerator(
    private val exercises: List<Exercise>,
    private val routines: List<NamedRoutine>,
    private val machines: MachineSettings = MachineSettings(),
    private val random: Random = Random.Default,
    private val prefs: PreferenceWeights = PreferenceWeights.NEUTRAL,
)
```

Default `NEUTRAL` keeps every existing `WorkoutGeneratorTest` call site compiling and byte-identical in behavior (weight 1.0 multiplications). Tests inject fixed weights via `WorkoutGenerator(..., prefs = PreferenceWeights { id -> mapOf("plank" to 0.7).getOrDefault(id, 1.0) })`.

Add a private helper (ε-greedy per draw, uses the injected `random` so seeded tests are deterministic):

```kotlin
private fun effectiveWeight(id: String): Double =
    if (random.nextDouble() < PreferenceMath.EPSILON) 1.0 else prefs.weight(id)
```

(To avoid a domain->domain dependency cycle concern: `EPSILON` may be duplicated as a private const in the generator; both are fine, pick the const.)

### Hook points (exact)

1. **`pickBalanced` (`:271`)** — scoring lambda becomes Double and multiplies:

```kotlin
val shuffled = candidates.shuffled(random)
    .sortedByDescending { ex ->
        var score = (ex.targets.size + random.nextInt(3)).toDouble()
        if (lowImpactBias && ex.impact == Impact.LOW) score += 2
        if (ex.evidenceTier == EvidenceTier.STRONG) score += 1
        score * effectiveWeight(ex.id)          // ONLY change: multiplicative pref weight
    }
```

Coverage pass (`targets !in covered` loops), repeats, and `pool()` remain untouched.

2. **`machineBlock` routine cost (`:322`)** — one ε draw per routine, mean over its distinct exercise ids:

```kotlin
val routine = (preferred.ifEmpty { fitting }).minByOrNull { r ->
    val ids = r.steps.map { it.exerciseId }.distinct()
    val meanPref = if (random.nextDouble() < PreferenceMath.EPSILON) 1.0
        else ids.map { prefs.weight(it) }.average()
    (kotlin.math.abs(r.totalSec - blockSec) + random.nextInt(60)) * (2.0 - meanPref)
}
```

(mean 1.0 → cost ×1.0; liked routine (1.3) → ×0.7; disliked (0.7) → ×1.3.)

3. **Fallback segment ordering (`:356`)** — `val ordered = candidates.shuffled(random).sortedByDescending { effectiveWeight(it.id) }` (stable sort keeps shuffle order among equal weights).

Guardrails already structural: warm-up/cool-down path (`warmCool`) never consults `prefs`; `pool()`, evidence gating, contraindications, visceral-fat check untouched.

### PreferenceRepository + updater

```kotlin
@Singleton
class PreferenceRepository @Inject constructor(private val dao: ExercisePrefDao) {
    suspend fun weights(nowEpochMs: Long = System.currentTimeMillis()): PreferenceWeights {
        val byId = dao.all().associateBy { it.exerciseId }
        return PreferenceWeights { id ->
            byId[id]?.let {
                PreferenceMath.weight(PreferenceMath.decayed(it.score, it.lastUpdatedEpochMs, nowEpochMs), it.exposures, it.skips)
            } ?: 1.1   // never-seen exercise = novelty weight
        }
    }
    suspend fun stats(): Map<String, ExercisePrefEntity> = dao.all().associateBy { it.exerciseId }
    suspend fun reset() = dao.clear()

    /** The "PreferenceUpdater": invoked from finishSession after events flush. */
    suspend fun applySessionEvents(events: List<StepEventEntity>, nowEpochMs: Long = System.currentTimeMillis()) {
        val byExercise = events.groupBy { it.exerciseId }
        val updated = byExercise.map { (id, evs) ->
            val prior = dao.forExercise(id)
            val base = prior?.let { PreferenceMath.decayed(it.score, it.lastUpdatedEpochMs, nowEpochMs) } ?: 0.0
            ExercisePrefEntity(
                exerciseId = id,
                score = base + evs.sumOf { PreferenceMath.eventValue(it.eventType, it.plannedSec, it.actualSec, it.positionFrac) },
                exposures = (prior?.exposures ?: 0) + evs.size,
                completions = (prior?.completions ?: 0) + evs.count { it.eventType == "COMPLETED" || it.eventType == "EXTENDED" },
                skips = (prior?.skips ?: 0) + if (evs.any { it.eventType == "SKIPPED" }) 1 else 0,
                lastUpdatedEpochMs = nowEpochMs,
            )
        }
        dao.upsertAll(updated)
    }
}
```

**Where updates run**: in `WorkoutSessionService.finishSession`, immediately after `workoutRepo.addStepEvents(historyId, events)`:

```kotlin
if (settings.learnPreferences) preferenceRepository.applySessionEvents(insertedEntities /* or re-map events */, endedAt)
```

(Inject `PreferenceRepository` into the service; pass the mapped `StepEventEntity` list built in `addStepEvents` — simplest: have `addStepEvents` return the entity list.)

### Settings (Phase 2 plumbing pattern)
- `AppSettings` gains `val learnPreferences: Boolean = true`; key `booleanPreferencesKey("learn_prefs")`; setter `suspend fun setLearnPreferences(v: Boolean)`.
- `SettingsScreen`: new `SectionHeader("Personalisation")` (place after the existing generator-related toggles, near "Include low-evidence"): `SettingSwitchRow("Learn my preferences", "Nudges future workouts toward exercises you finish and away from ones you skip. Never overrides safety filters.", settings.learnPreferences) { viewModel.set { setLearnPreferences(it) } }` plus an `OutlinedButton("Reset learned preferences")` → confirm `AlertDialog` ("This clears what Kinetiq has learned about your exercise likes. Your history is kept.") → `preferenceRepository.reset()` (inject into `SettingsViewModel`).

### Builder wiring + explanation chips
- `BuilderViewModel`: inject `PreferenceRepository`. In `generate()`:

```kotlin
val learn = settings.learnPreferences
val prefWeights = if (learn) preferenceRepository.weights() else PreferenceWeights.NEUTRAL
val prefStats = if (learn) preferenceRepository.stats() else emptyMap()
val generator = WorkoutGenerator(exercises, routines, machines = settings.machines, prefs = prefWeights)
```

- `BuilderUiState` gains `val prefNotes: Map<String, String> = emptyMap()` (exerciseId → chip text). After generation, build notes for exercise ids appearing in the preview: only when `exposures >= 3`; text rules (exact): if `completions.toDouble()/exposures >= 0.8` → `"you've finished this ${completions} of ${exposures} times"`; else if `skips >= 2` → `"often skipped — kept for variety"`; else no note.
- `PreviewStepRow` gains `prefNote: String? = null` parameter; rendered as a third line: `Text(prefNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)` under the duration line, only when non-null and `step.type == StepType.WORK`. Call site: `prefNote = step.exerciseId?.let { state.prefNotes[it] }`.

### Tests — `PreferenceModelTest.kt` (pure JVM)
- `fun decay halves the score after exactly 21 days()` — `decayed(4.0, t0, t0 + 21*86_400_000L)` within `1e-9` of `2.0`; also `Δt = 42 days → 1.0`; `Δt = 0 → 4.0`; negative Δt → 4.0.
- `fun event values match the spec table()` — EXTENDED→1.5; COMPLETED full→1.0; COMPLETED shortened (actual<planned)→0.5; SKIPPED at frac 0.59→−1.0; at 0.6→−0.3; ABANDONED→−0.1.
- `fun weight is bounded by tanh and clamp()` — `weight(1e9, 10, 5) == 1.3`; `weight(-1e9, 10, 5) == 0.7`; `weight(0.0, 10, 5) == 1.0`; `weight(4.0, 10, 5) == 1.0 + 0.3*tanh(1.0)` (≈1.2285).
- `fun novelty floor applies under three exposures()` — `weight(-10.0, 2, 5) == 1.1` and `weight(10.0, 0, 0) == 1.1`.
- `fun a single skip session never drops the weight below neutral()` — `weight(-3.0, 5, 1) == 1.0`; `weight(-3.0, 5, 2) < 1.0`.
- `fun updater increments skip counter at most once per session()` — two SKIPPED events for the same exercise in one applySessionEvents call → `skips == 1` (test via a fake in-memory `ExercisePrefDao` or move the aggregation into a pure `PreferenceMath.fold(prior, events, now)` function and test that — **specify: extract the fold as a pure function** `fun fold(prior: ExercisePrefEntity?, exerciseId: String, events: List<StepEventEntity>, now: Long): ExercisePrefEntity` so this is JVM-testable without Room; `applySessionEvents` calls it).
- `fun epsilon exploration still surfaces a disliked exercise()` — build generator over the real `exercise_db.json` (same pattern as `WorkoutGeneratorTest`) with `prefs` returning 0.7 for one common FLOOR exercise id and 1.0 otherwise; across `Random(seed)` for seeds 0..39, 30-min FLOOR sessions: assert the disliked id appears in at least one plan (ε keeps it alive) and in fewer plans than with `NEUTRAL` weights (bias works).
- `fun neutral weights reproduce legacy generator output()` — with `Random(42)`, plan from `WorkoutGenerator(..., prefs = NEUTRAL)` — assert a session generates with the same WORK-step count as before the change (guards against accidental ordering drift; if exact step-list equality holds, assert equality of exerciseId lists).
- Reset: covered by `DaoTest.fun exercise pref clear removes all rows()`.

### Acceptance criteria
- With the toggle off, generator receives `NEUTRAL` and no pref rows are written on finish.
- All existing `WorkoutGeneratorTest` cases pass unmodified.
- Skipping an exercise once (one session) does not reduce its selection weight; skipping it in two sessions does.
- Reset button empties `exercise_prefs` and Builder chips disappear.

### Dependencies
Steps A, B; Settings plumbing follows the existing Phase 2 pattern (`SettingSwitchRow`, `viewModel.set {}`).

---

## Step E — Progression/deload engine (item 5)

### Goal
Weekly, explainable, never-silent adjustment suggestions to the persisted default `GeneratorConfig`.

### Files
- New: `app/src/main/java/au/mark/kinetiq/domain/plan/ProgressionEngine.kt`
- Touched: `ui/screens/builder/BuilderScreen.kt` (card + accept/dismiss), `data/repo/SettingsRepository.kt` (progression state keys)
- New test: `app/src/test/java/au/mark/kinetiq/ProgressionEngineTest.kt`

### Exact design

```kotlin
object ProgressionEngine {

    /** Aggregates for one COMPLETED Monday-start week (most recent last). */
    data class WeekStats(
        val metMinutes: Double,     // Σ CompletedBlock MET-minutes that week (see Step F derivation)
        val sessions: Int,
        val tooEasy: Int,           // sessions with perceivedDifficulty == TOO_EASY
        val tooHard: Int,           // sessions with perceivedDifficulty == TOO_HARD
        val meanSrpe: Double?,      // mean of non-null srpe, null if none
    )

    enum class Kind { PROGRESS_DURATION, PROGRESS_INTENSITY, BACK_OFF, DELOAD }

    data class ProgressionSuggestion(val newDefaults: GeneratorConfig, val reason: String, val kind: Kind)

    /**
     * @param weeks up to the last 4 COMPLETED weeks, oldest first; current partial week excluded.
     * @param lastKind kind of the last ACCEPTED suggestion (null if none) — drives duration/intensity alternation.
     * @param weeksSinceBackOff completed weeks since a BACK_OFF was accepted (Int.MAX_VALUE if never).
     */
    fun evaluate(weeks: List<WeekStats>, current: GeneratorConfig, lastKind: Kind?, weeksSinceBackOff: Int): ProgressionSuggestion?
}
```

**Definitions (exact):**
- `weekEasy(w) = (w.tooEasy >= 2 && w.tooHard == 0) || (w.meanSrpe != null && w.meanSrpe <= 3.0)`
- `weekHard(w) = w.tooHard >= 2 || (w.meanSrpe != null && w.meanSrpe >= 9.0)`
- `trainingWeek(w) = w.sessions >= 2`
- `stableVolume(a, b) = b.metMinutes in (a.metMinutes * 0.85)..(a.metMinutes * 1.15)` (±15%)
- `downWeek(w, priorMax) = w.metMinutes <= 0.6 * priorMax` (a ≥40% reduction vs the max of the preceding weeks in the window)

**Rule evaluation order (first match wins), with `w1` = most recent completed week, `w2` = the one before:**

1. **BACK_OFF**: `weeks.size >= 2 && weekHard(w1) && weekHard(w2)` → `newDefaults = current.copy(totalDurationMin = (current.totalDurationMin * 0.8).roundToInt().coerceAtLeast(5))`, intensity unchanged ("hold"), `reason = "You rated the last two weeks hard — easing back about 20% and holding intensity."`. **Hold mechanism**: after a BACK_OFF is accepted, `evaluate` must be called with `weeksSinceBackOff`; while `weeksSinceBackOff < 2`, rules 2–3 are suppressed (return null unless BACK_OFF fires again).
2. **DELOAD**: `weeks.size == 4 && weeks.all { trainingWeek(it) }` and no `weeks[i]` is a `downWeek` vs `max(metMinutes of weeks[0..i-1])` (i ≥ 1) → duration ×0.65 (−35%, inside the −30–40% band), intensity −1 notch: `Intensity.entries[max(0, ordinal-1)]`. `reason = "Four solid training weeks in a row — this week, take a lighter deload week to consolidate."`
3. **PROGRESS**: `weeks.size >= 2 && weekEasy(w1) && weekEasy(w2) && stableVolume(w2, w1)` →
   - if `lastKind == PROGRESS_DURATION && current.intensity != Intensity.VERY_HIGH` → **intensity notch**: `intensity = Intensity.entries[ordinal+1]`, kind PROGRESS_INTENSITY, `reason = "You rated the last two weeks easy — nudging intensity up one notch."`
   - else → **duration first**: `newDuration = min((current.totalDurationMin * 1.08).roundToInt(), (current.totalDurationMin * 1.10).toInt())` and at least `current + 2` capped by the 10% rule: final formula `newDuration = (current.totalDurationMin * 1.08).roundToInt().coerceAtMost((current.totalDurationMin * 1.10).toInt()).coerceAtLeast(current.totalDurationMin + 1)`; kind PROGRESS_DURATION, `reason = "This week: $newDuration min suggested — you rated last week easy."` **Ramp cap ≤10%/week is structural** (the coerceAtMost).
4. Otherwise → `null`.

### Where it runs + persistence

- **Evaluation point**: `BuilderViewModel.init` (on Builder open) — after `lastConfigJson` seeds the config, launch: `workoutRepository.historyOnce()` → bucket entries into Monday-start completed weeks (`ZoneId.systemDefault()`, same `TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)` idiom as `WeeklyPlanEngine`; a week is *completed* if its Sunday is before today) → last 4 → `WeekStats` (MET-minutes via the Step F helper `WeeklyPlanEngine.metMinutes(entry, fallbackWeightKg)`), plus `tooEasy/tooHard/meanSrpe` from the new `HistoryEntry` fields → `ProgressionEngine.evaluate(...)`.
- **State in SettingsRepository** (new keys, exact):
  - `stringPreferencesKey("progression_last_kind")` — name of last **accepted** Kind.
  - `longPreferencesKey("progression_backoff_epochday")` — `LocalDate.toEpochDay()` of the Monday of the week a BACK_OFF was accepted (used to compute `weeksSinceBackOff`).
  - `stringPreferencesKey("progression_dismissed_key")` — dedupe: `"${kind.name}:${weekStart.toEpochDay()}"`; if the computed suggestion's key equals the stored one, don't show it again this week.
  - Setters: `setProgressionAccepted(kind: String, weekStartEpochDay: Long)`, `setProgressionDismissed(key: String)`; flows exposed alongside `lastConfigJson`.
- **Accepted defaults persist via the existing `lastConfig` mechanism** (it already seeds the Builder — it *is* the persisted default `GeneratorConfig`): accept → `uiState` config replaced with `newDefaults`, `settingsRepository.setLastConfigJson(json.encodeToString(GeneratorConfig.serializer(), newDefaults))`, record accepted kind/epochday, then `generate()`.

### UI (BuilderScreen)

`BuilderUiState` gains `val progression: ProgressionSuggestion? = null`. Insert a card as the item directly under the "Workout builder" title (before the Duration slider):

```
Card(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
    Text("Suggested for this week", style = titleMedium)
    Text(suggestion.reason, style = bodyMedium)
    Row { Button("Use ${suggestion.newDefaults.totalDurationMin} min") { viewModel.acceptSuggestion() }
          TextButton("Ignore") { viewModel.dismissSuggestion() } }
}
```

Never auto-applied; dismiss stores the dedupe key so it stays gone for the rest of the week.

### Tests — `ProgressionEngineTest.kt` (pure JVM, synthetic `WeekStats`; helper `week(met=300.0, sessions=3, easy=0, hard=0, srpe=null)`)
- `fun two consecutive easy weeks at stable volume propose duration increase first()` — `lastKind = null`, weeks `[easy(300), easy(310)]`, current 30 min → kind PROGRESS_DURATION, `newDefaults.totalDurationMin == 32` (30×1.08=32.4→32; ≤ 33 cap), intensity unchanged.
- `fun progression alternates to an intensity notch after a duration increase()` — same weeks, `lastKind = PROGRESS_DURATION`, intensity MODERATE → kind PROGRESS_INTENSITY, `intensity == HIGH`, duration unchanged.
- `fun intensity notch caps at VERY_HIGH and falls back to duration()`.
- `fun mean srpe at or below three counts as easy()` — chips absent, `meanSrpe = 3.0` both weeks → suggestion non-null.
- `fun unstable volume blocks progression()` — easy weeks with 300 → 400 MET-min (>15%) → null.
- `fun ramp cap never exceeds ten percent()` — for durations 5..60, assert `suggested <= (d * 1.10).toInt()`.
- `fun two hard weeks propose twenty percent back off and hold intensity()` — hard×2, 40 min → BACK_OFF, `totalDurationMin == 32`, `intensity == current.intensity`.
- `fun back off suppresses progression for two weeks()` — easy weeks but `weeksSinceBackOff = 1` → null; `= 2` → suggestion returns.
- `fun four training weeks without a down week propose deload()` — `[300,310,320,315]` all ≥2 sessions → DELOAD, duration `== (d*0.65).roundToInt()`, intensity one notch down.
- `fun a forty percent down week resets the deload clock()` — `[300,310,170,315]` (170 ≤ 0.6×310) → no DELOAD.
- `fun fewer than two completed weeks yields no suggestion()`.

### Acceptance criteria
- Opening Builder after two "too easy" weeks shows exactly one card with the duration copy; accepting rewrites the sliders and persists so the next Builder open starts from the new defaults; ignoring hides it for the week; nothing ever changes config without a tap.

### Dependencies
Steps A, C (sRPE fields), F (`metMinutes` derivation helper). Uses existing `lastConfig` persistence (no new config store needed — this is the answer to "if config isn't persisted": it already is, via `Keys.lastConfig`).

---

## Step F — Weekly dose meter + WeeklyPlanEngine fixes (item 6)

### Goal
MET-min/week meter on PlanScreen with HIIT-aware target; fix minute truncation; WHO vigorous double-counting.

### Files
- Touched: `data/repo/WorkoutRepository.kt` (`CompletedBlock`), `service/WorkoutSessionService.kt` (write `metMinutes`), `domain/plan/WeeklyPlanEngine.kt`, `ui/screens/plan/PlanScreen.kt`
- New test: `app/src/test/java/au/mark/kinetiq/WeeklyPlanEngineTest.kt`

### MET-minutes: what is and isn't derivable (exact)

`CompletedBlock` stores `calories` and `activeSec` but **not** MET and **not** the session weight, and `kcal = MET × kg × h`, so MET-min = `calories / weightKg × 60` requires a weight the row doesn't carry. Therefore:

1. **Add a field to the blocks JSON** (additive, backward compatible — `Json` has `ignoreUnknownKeys` + `encodeDefaults`):

```kotlin
@Serializable
data class CompletedBlock(
    ..., 
    /** MET × minutes for this block, computed at finish time. 0.0 on legacy rows. */
    val metMinutes: Double = 0.0,
)
```

2. **Write path** — in `finishSession` (`WorkoutSessionService.kt:386-400`), where `met` (block mean) is already computed: `metMinutes = met * (activeSec / 60.0)`.
3. **Legacy fallback (read path)** — for rows with `metMinutes == 0.0 && activeSec > 0`, back-derive approximately with the **current** weight: `metMinutes ≈ calories / currentWeightKg * 60.0`. This is exact when weight hasn't changed and clearly documented as approximate otherwise. Helper (used by PlanScreen and Step E):

```kotlin
// In WeeklyPlanEngine
fun blockMetMinutes(b: CompletedBlock, fallbackWeightKg: Double?): Double =
    if (b.metMinutes > 0.0) b.metMinutes
    else fallbackWeightKg?.takeIf { it > 0 }?.let { b.calories / it * 60.0 } ?: 0.0
```

### WeeklyPlanEngine changes (exact)

- Signature: `fun progressForWeek(history, visceralFatGoal, currentWeightKg: Double? = null, today = LocalDate.now(), zone = ZoneId.systemDefault())`.
- **Truncation fix**: accumulate `cardioSecTotal` across entries and compute `cardioMin = cardioSecTotal / 60` once at the end (replaces per-entry `cardioMin += cardioSec / 60` at `:58`).
- **Vigorous double-count (WHO)**: per entry, `whoSec = Σ non-HIIT cardio-block activeSec + 2 × Σ HIIT-block activeSec` (a block is counted once: HIIT branch takes precedence over the category test). `cardioMinutesDone` becomes WHO-equivalent moderate minutes.
- **Dose meter**: `metMinutes = Σ over week's entries Σ blocks blockMetMinutes(b, currentWeightKg)` (rounded to Int for display); `hiitSessions = count of entries with any block where isHiit && activeSec >= 10*60`; `metMinuteTarget = if (hiitSessions >= 2) 400 else 730`.
- `WeeklyProgress` gains `val metMinutes: Int, val metMinuteTarget: Int, val hiitSessions: Int`.

### PlanScreen

- `PlanViewModel`: also inject `MeasurementRepository`; combine stays `combine(history, settings)` but map through a suspend block that first fetches `measurementRepository.resolved(Metric.WEIGHT_KG)?.value ?: settings.fallbackWeightKg.toDouble()` (use `combine(...).mapLatest { ... }` or fetch weight once in `init` into a `MutableStateFlow<Double>` and combine three flows — specify the three-flow combine with a `weightFlow = flow { emit(resolved ?: fallback) }`).
- UI: add a fourth `PlanProgressRow("Weekly dose (MET-minutes)", progress.metMinutes, progress.metMinuteTarget, "MET-min")` after the strength row, followed by a caption `Text`: `"Target 730 MET-min/week, or 400 when ≥2 sessions are HIIT — HIIT minutes count double toward WHO cardio minutes."` (`bodySmall`, `onSurfaceVariant`).

### Tests — `WeeklyPlanEngineTest.kt` (pure JVM; helper builders for `HistoryEntry`/`CompletedBlock`; fixed `today = LocalDate.of(2026, 8, 13)` (a Thursday) and `zone = ZoneId.of("Australia/Sydney")`)
- `fun week starts on monday and excludes the previous sunday()` — entry Sunday 2026-08-09 23:00 excluded; Monday 2026-08-10 00:30 included (assert via `cardioSessionsDone`).
- `fun cardio minutes accumulate seconds before dividing()` — two entries with 90-sec SPIN blocks → `cardioMinutesDone == 3` (old code gave 2).
- `fun hiit minutes count double toward who cardio minutes()` — one 10-min HIIT FLOOR-category block → `cardioMinutesDone == 20`.
- `fun hiit block is not double counted when also a cardio category()` — 10-min SPIN isHiit block → 20, not 30.
- `fun dose target drops to 400 with two hiit sessions()` — 2 entries each with a ≥10-min HIIT block → `metMinuteTarget == 400`; with 1 → 730.
- `fun met minutes prefer stored value and fall back to calories over weight()` — block(metMinutes=120.0) → 120; legacy block(metMinutes=0.0, calories=160.0, weight 80) → 120 (`160/80*60`); weight null → 0.
- `fun met minutes sum across blocks and entries()`.

### Acceptance criteria
- New sessions store `metMinutes` per block; Plan screen shows `X / 730 MET-min` (or `/400` in a 2-HIIT week); no truncation drift (3× 50s cardio ≥ 2 min, not 0).

### Dependencies
Step A not required (JSON field, not a column). `finishSession` write should land before users accumulate more legacy rows — schedule early (see sequence).

---

## Step G — Time-of-day consistency nudge (item 4)

### Goal
One-time, dismissible suggestion card that offers to align the reminder time with observed workout times. Never auto-moves anything.

### Files
- New: `app/src/main/java/au/mark/kinetiq/domain/plan/TimeOfDayNudge.kt`
- Touched: `ui/screens/plan/PlanScreen.kt` (card + viewmodel actions), `data/repo/SettingsRepository.kt` (2 keys)
- New tests: added to `WeeklyPlanEngineTest.kt` or its own `TimeOfDayNudgeTest.kt` (own file)

### Exact design

```kotlin
object TimeOfDayNudge {
    enum class TimeBucket { MORNING, MIDDAY, EVENING }

    /** MORNING: start < 11:00; MIDDAY: 11:00 <= start < 17:00; EVENING: start >= 17:00 (local). */
    fun bucketOf(minuteOfDay: Int): TimeBucket =
        if (minuteOfDay < 11 * 60) TimeBucket.MORNING
        else if (minuteOfDay < 17 * 60) TimeBucket.MIDDAY else TimeBucket.EVENING

    data class Suggestion(
        val bucket: TimeBucket,
        val hitCount: Int,          // sessions in dominant bucket
        val total: Int,             // sessions considered (<= 10)
        val windowStartMin: Int,    // earliest start minuteOfDay in bucket
        val windowEndMin: Int,      // latest start minuteOfDay in bucket
        val suggestedHour: Int,     // median start, rounded to nearest 15 min
        val suggestedMinute: Int,
    )

    /**
     * @param recentStartsEpochMs up to the 10 most recent session starts (any order).
     * Fires when: total >= 5, dominant bucket share >= 0.7, and |suggested - current reminder| > 60 min
     * (or no reminder is configured at all: reminderDays empty counts as "differs").
     */
    fun evaluate(
        recentStartsEpochMs: List<Long>,
        reminderDays: Set<Int>, reminderHour: Int, reminderMinute: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Suggestion?
}
```

Median: sort the dominant bucket's `minuteOfDay` values, take middle (lower of two for even counts), round to nearest 15: `((m + 7) / 15) * 15`, clamp so it stays inside the bucket's hour bounds is NOT required (median of members is inside). Difference test: `abs(suggestedMin − (reminderHour*60+reminderMinute)) > 60` OR `reminderDays.isEmpty()`.

**State storage** (SettingsRepository, exact keys):
- `booleanPreferencesKey("nudge_never")` — "don't ask again", permanent.
- `stringPreferencesKey("nudge_handled_bucket")` — bucket name that was applied or "not now"-dismissed; the card re-appears only if `evaluate` returns a **different** bucket and `nudge_never` is false.
- Setters: `setNudgeNever(v: Boolean)`, `setNudgeHandledBucket(name: String)`; both surfaced in `AppSettings` (`nudgeNever: Boolean = false`, `nudgeHandledBucket: String? = null`).

**Surface — PlanScreen** (chosen over Home: it is the coaching surface and already combines history+settings). `PlanViewModel` computes `nudge: Suggestion?` in the existing `combine` (inputs: last 10 `history` starts, `settings.reminder*`, `settings.nudgeNever`, `settings.nudgeHandledBucket`) — null when `nudgeNever` or `handledBucket == suggestion.bucket.name`. Card inserted between "This week's suggestion" card and the "Progress" header:

```
Card {
  Text("Consistency boost", titleMedium)
  Text("You've done ${hitCount} of ${total} workouts between ${fmt(windowStartMin)}–${fmt(windowEndMin)}. " +
       "Set your reminder for ${fmt(suggestedHour*60+suggestedMinute)}?", bodyMedium)
  Row { Button("Set reminder") { viewModel.applyNudge(context, suggestion) }
        TextButton("Not now") { viewModel.dismissNudge(suggestion) }
        TextButton("Don't ask again") { viewModel.neverNudge() } }
}
```

`fmt(min)` = `"%d:%02d %s"` 12-hour format. Actions (PlanViewModel gains `SettingsRepository` write access + `ReminderScheduler`):
- `applyNudge`: `days = settings.reminderDays.ifEmpty { setOf(1,2,3,4,5) }`; `settingsRepository.setReminder(days, h, m)`; `reminderScheduler.schedule(context, days, h, m)`; `setNudgeHandledBucket(bucket.name)`.
- `dismissNudge`: `setNudgeHandledBucket(bucket.name)`.
- `neverNudge`: `setNudgeNever(true)`.

### Tests — `TimeOfDayNudgeTest.kt` (pure JVM, fixed zone)
- `fun buckets split at 11 and 17 local()` — 10:59→MORNING, 11:00→MIDDAY, 16:59→MIDDAY, 17:00→EVENING.
- `fun fires at seventy percent dominance over at least five sessions()` — 7 morning + 3 evening of 10, reminder 19:00 → non-null, bucket MORNING, `hitCount == 7, total == 10`.
- `fun does not fire under five sessions or under seventy percent()` — 3/4 morning → null; 6/10 morning → null.
- `fun does not fire when reminder already within an hour()` — dominant-median 07:00, reminder 07:45 → null; reminder 08:15 → non-null.
- `fun fires when no reminder is configured()` — `reminderDays = emptySet()` → non-null regardless of stored hour.
- `fun suggested time is the median rounded to fifteen minutes()` — starts 06:20/06:40/07:10 → median 06:40 → 6:45.

### Acceptance criteria
- Card appears at most once per dominant bucket; "Set reminder" updates Settings' reminder row and schedules via WorkManager; "Don't ask again" is permanent; reminder time never changes without the tap.

### Dependencies
None beyond existing reminders/settings plumbing.

---

## Cross-cutting notes for the implementing agent

- **String resources**: this codebase intentionally uses inline strings in composables and `strings.xml` only for notification/framework text (`reminder_title` etc.). Follow that: the copy specified above goes inline in the composables; no new `strings.xml` entries are required.
- **Offline guarantee**: everything here is local (Room/DataStore) — the release-manifest INTERNET check is unaffected.
- **Determinism**: all generator changes route randomness exclusively through the injected `random`; all new domain objects (`StepEventRecorder`, `PreferenceMath`, `ProgressionEngine`, `TimeOfDayNudge`, `WeeklyPlanEngine`) take explicit `nowEpochMs`/`today`/`zone` parameters so every test above is pure-JVM and clock-independent.
- **Phase 1 dependency (explicit)**: no `SessionEngine` exists in the repo today. Step B is written so the only Phase-1-sensitive part is *where the three recorder hooks live* (service vs engine); the recorder, semantics, DAO, and tests are identical either way. Phase 2 dependency: the settings toggle/reset reuse the existing `SettingSwitchRow` + `SettingsViewModel.set {}` pattern verbatim.
- **Migration chain (explicit)**: exactly one new Room version this phase — `1 → 2` (`MIGRATION_1_2` in `KinetiqDatabase.companion`), containing: 3 × `ALTER TABLE session_history ADD COLUMN`, `CREATE TABLE step_events` (+3 indices, FK CASCADE), `CREATE TABLE exercise_prefs`. `AppModule` swaps `fallbackToDestructiveMigration()` for `addMigrations(MIGRATION_1_2)`. `CompletedBlock.metMinutes` is a JSON-field change, not a schema change.

### Critical Files for Implementation
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/data/db/KinetiqDatabase.kt (with Entities.kt/Daos.kt beside it — the migration chain anchor)
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/service/WorkoutSessionService.kt (all event write points + metMinutes + pref update at finishSession)
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/domain/generator/WorkoutGenerator.kt (prefs param + three hook points)
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/ui/screens/builder/BuilderScreen.kt (generator construction, progression card, pref chips)
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/domain/plan/WeeklyPlanEngine.kt (truncation fix, dose meter, WeekStats source for ProgressionEngine)