# Kinetiq Upgrade — Master Implementation Plan (Phases 1–4)

This is the authoritative execution plan for the Kinetiq upgrade. It was produced from a
full-codebase review plus external research (visceral-fat exercise evidence 2020–2026,
workout-player UX conventions, adaptive-learning literature). Every work item is specified in
one of four phase documents in this directory; this file defines **how to execute them**:
ordering, cross-phase reconciliations, global conventions, and the definition of done.

| Phase | Document | Scope |
|---|---|---|
| 1 | [PHASE1_TRUST_SAFETY.md](PHASE1_TRUST_SAFETY.md) | Get-ready lead-in, stop confirmation, summary reachability, widget/start guards, generator time-math, service correctness (`SessionEngine` extraction), voice reliability, Health Connect hardening, validator bounds |
| 2 | [PHASE2_REST_PLAYER_THEMES.md](PHASE2_REST_PLAYER_THEMES.md) | Three-mode rest model (STANDARD/RECOVERY/CONTINUOUS), skippable rests, player polish, ~17 UX quick wins, 7 accent palettes × Light/Dark/AMOLED/System with contrast tests |
| 3 | [PHASE3_DATABASE_ANIMATIONS.md](PHASE3_DATABASE_ANIMATIONS.md) | Exercise DB consistency fixes, 27 new exercises (complete JSON provided), 9 new routines (4×4, 10-20-30, SIT, recovery, MICT), 26 new stick-figure animations with geometry/contact tests, myth-buster insights |
| 4 | [PHASE4_LEARNING_LOOP.md](PHASE4_LEARNING_LOOP.md) | Room migration (step events, preference table, sRPE columns), step-event logging, session-RPE capture, preference model, progression/deload engine, weekly MET-min dose meter, time-of-day nudge |

## Rules of engagement (read first)

1. **The phase documents are the spec.** Every item carries Goal / Files / Exact changes /
   Tests / Acceptance criteria / Dependencies. Implement items exactly as written. If reality
   contradicts the spec (an API doesn't exist, a line number moved, a test can't be expressed
   as written), implement the item's *intent*, note the deviation in the commit message, and
   record it in `docs/plan/DEVIATIONS.md` (create it on first use: item ID, what the spec said,
   what was done instead, why). Never silently skip an item.
2. **Work on branch `claude/health-app-review-6cpona`.** Commit per work item or small item
   group, with messages referencing item IDs (e.g. `P1-R: extract SessionEngine`,
   `P2-C11: accent palettes`). Push with `git push -u origin claude/health-app-review-6cpona`.
3. **Gate every commit on `./gradlew testDebugUnitTest`.** Gate every phase on
   `./gradlew assembleRelease` (which enforces the no-INTERNET manifest check). Nothing in this
   plan may add the INTERNET permission or any network I/O — the offline guarantee is
   inviolable.
4. **Never delete or rename serialized fields.** `GeneratorConfig`, `SessionStep`, `StepType`,
   `SessionSnapshot`, export formats: additive-with-defaults only. Phase 1 deliberately chose a
   service-level prepare phase over a new `StepType`; Phase 2 deliberately keeps
   `workRestRatio` as a deprecated serialized field. Do not "clean these up".
5. **Track progress in `docs/plan/PROGRESS.md`**: a checkbox per item ID (the full item list is
   in each phase doc's sequence table). Update it in the same commit as the work. This file is
   how anyone — including a fresh session — verifies nothing was dropped.

## Execution order

Phases execute **1 → 2 → 3 → 4**. Within each phase, follow the phase doc's own
implementation-sequence table. Phase 3 (pure data + animations) has no code dependency on
Phases 1–2 and may be done in parallel by a second agent if desired, **except** validator items
(P1 item 9 must merge before P3's validator edits to avoid conflicts in `DatabaseValidator.kt`).

Suggested PR/commit grouping (from the phase docs):

- **Phase 1**: item 9 → item 5 (+`WorkoutGeneratorTimeBudgetTest`) → item 7 → item R
  (SessionEngine) → item 6 (a–h) → item 1 (get-ready) → item 3 → item 2 → item 4 → item 8.
- **Phase 2**: PR-1 models+generator (A1+A4+A3+`RestModeTest`) → PR-2 builder+settings UI
  (A1 UI + B10l + B10e) → PR-3 player (A2, B5–B9) → PR-4 theming (C11+C12) → PR-5 quick wins.
- **Phase 3**: Step 1 fixes → Step 2 schema/model/validator → Step 3 animations → Step 4
  exercises → Step 5 routines → Step 6 insights → Step 7 docs → Step 8 tests. All JSON edits
  land in one asset change with `schemaVersion: 3`.
- **Phase 4**: Step A schema/migration → Step F (metMinutes write only) → Step B step events →
  Step C sRPE → Step D preference model → Step E progression engine → Step F (UI + engine
  fixes) → Step G time-of-day nudge.

## Cross-phase reconciliations (binding)

The four phase documents were authored against the current codebase. Where later phases touch
the same code as earlier ones, these rulings apply:

1. **Rest solver: Phase 1 item 5(b)(c) vs Phase 2 Part A4.** Phase 1 fixes the *ratio-based*
   solver (fixpoint on clamped rest) and introduces `redistribute()`. Phase 2 then **replaces**
   the ratio mechanism with rest modes. Final state after Phase 2:
   `discreteBlock` = pick exercises → compute per-gap rests via `restBetween(mode, intensity,
   prev, next)` → `workBudget = blockSec − Σrests` → `redistribute(List(count){workBudget/count},
   minSec, maxSec, workBudget)`. Phase 1's `redistribute()`, capped-block warning,
   `plannedTotalSec` warning field, `minViableDurationMin()` and the mainSec-floor warning all
   **survive**; Phase 1's ratio fixpoint loop is **superseded** and may be deleted in Phase 2
   PR-1. Implement Phase 1 item 5 fully anyway (it is the tested foundation and ships alone);
   Phase 2 then swaps the rest computation.
2. **Time-budget test matrix.** Phase 1's `WorkoutGeneratorTimeBudgetTest` matrix includes
   `ratios = [0.5, 2, 6]`. When Phase 2 PR-1 lands, update the matrix dimension to
   `restModes = [STANDARD, RECOVERY, CONTINUOUS]` (same assertions: `plan.totalSec` within ±5%
   of request, or an explicit `plannedTotalSec` warning). The invariant — never silently off
   budget — is permanent.
3. **Room versioning.** Phase 4 is the **only** Room schema change in this plan:
   `MIGRATION_1_2` exactly as specified in Phase 4 Step A, and the swap of
   `fallbackToDestructiveMigration()` for `addMigrations(...)` — this swap is mandatory; a
   destructive fallback would wipe user history on upgrade. Phases 1–3 must not bump the Room
   version (Phase 3's `schemaVersion: 3` is the *asset* schema, not Room). If any earlier work
   accidentally bumps Room, renumber Phase 4's migration accordingly.
4. **`SessionEngine` and Phase 4's `StepEventRecorder`.** Phase 1 extracts `SessionEngine`;
   Phase 4 Step B is written to work with or without it. Since Phase 1 lands first, wire the
   recorder as Phase 4's contingency prescribes: owned by the engine, hooks in `onTick`
   advance / `skip` / `extend` / session end, snapshot fields carried in `SessionSnapshot`
   alongside Phase 1's additions.
5. **`GeneratorConfig` field merges.** Phase 2 adds `restMode`; Phase 4's `ProgressionEngine`
   copies configs (`config.copy(totalDurationMin = …, intensity = …)`) — copies preserve
   `restMode` automatically; no action beyond awareness. The persisted-defaults mechanism for
   accepted progression suggestions is the existing `lastConfig` DataStore key (Phase 4 Step E
   ruling) — do not invent a second store.
6. **Builder edits collide.** Phase 2 PR-2 (rest-mode chips, preview protection, displayName)
   and Phase 4 Steps D/E (preference chips, progression card) all touch
   `BuilderScreen.kt`/`BuilderViewModel`. Phase order handles this; rebase Phase 4 work on the
   Phase 2 result and keep the Phase 2 UI structure (section headers, FilterChip rows) as the
   pattern.
7. **`finishSession` accretes calls.** Final order inside the Phase 1 item 6(f) try/finally
   structure, after `addHistory` → `historyId`:
   ① `addStepEvents(historyId, events)` (P4-B) → ② `applySessionEvents(...)` if
   `learnPreferences` (P4-D) → ③ `stateHolder.completed(...)` → ④ `KinetiqWidget().updateAll`
   (P1-4). `CompletedBlock.metMinutes` (P4-F) is computed where block MET is already
   duration-weighted by P1 item 6(h).
8. **Voice status API.** Phase 1 item 7 defines `TtsStatus`/`retryInit()`. Phase 2's player
   changes must keep the failure banner (P1) rendered above the Phase 2 layout changes; no
   conflict, just don't drop it while restructuring `PlayerScreen.kt`.
9. **Validator minimum counts.** Phase 3 Step 2.5 raises minimums to match its content and its
   Step 8.4 pins exact counts. If you add or drop any entry relative to the Phase 3 lists,
   update both together — the exact-count test is the completeness gate for Phase 3; changing
   it requires a DEVIATIONS.md entry.

## Global conventions (verified from the codebase — all phases must follow)

- **Settings**: Preferences DataStore, `Keys` object + `AppSettings` field + `?: default`
  mapping + one `suspend fun setX`. Enums stored via `.name`, decoded with
  `runCatching { valueOf }.getOrNull() ?: DEFAULT`.
- **JSON**: single Hilt `Json { ignoreUnknownKeys = true; encodeDefaults = true }`. New fields
  always get defaults.
- **Tests**: JUnit4 + Truth, backtick sentence names, real `exercise_db.json` loaded from
  `src/main/assets`, seeded `Random`. Robolectric available; prefer pure JVM. All new domain
  logic takes explicit `nowEpochMs`/`zone`/`Random` parameters — no wall-clock reads inside
  logic.
- **UI text**: inline strings in composables; `strings.xml` only for notification/framework
  strings. en-AU voice style for anything spoken.
- **Animations**: D-19 discipline — contacts solved against the rig FK, eccentric slower than
  concentric, `Ease.SMOOTH` default, |knee| ≤ 156, |elbow| ≤ 160, angular velocity < 2200°/s.
  Every new animation registers in `AnimationRegistry.all` (auto-covered by `AnimGeometryTest`
  and the hidden QA screen) **plus** the targeted contact assertions in Phase 3 Step 8.2.

## Definition of done (the shipping gate)

The work is complete when ALL of the following hold:

1. `docs/plan/PROGRESS.md` shows every item in all four phase docs checked, or listed in
   `DEVIATIONS.md` with a reason.
2. `./gradlew testDebugUnitTest` green, including every NEW test named in the phase docs — as a
   completeness check, grep the test sources for these test classes and fail the review if any
   is missing: `SessionEngineTest`, `SessionSnapshotCompatTest`, `VoiceCoachStatusTest`,
   `WorkoutGeneratorTimeBudgetTest`, `RestModeTest`, `DisplayNameTest`,
   `ThemePaletteContrastTest`, `SettingsRoundTripTest`, `MigrationTest`, `DaoTest`,
   `StepEventLoggingTest`, `PreferenceModelTest`, `ProgressionEngineTest`,
   `WeeklyPlanEngineTest`, `TimeOfDayNudgeTest` — plus the Phase 3 additions inside
   `AnimGeometryTest` and `DatabaseValidatorTest`.
3. `./gradlew assembleRelease` prints the no-INTERNET confirmation.
4. Phase 3's exact-count test passes: 105 exercises, 21 routines, 2 insights; all 26 new
   animations render on the hidden QA screen (Settings → long-press version row) — one manual
   eyeball pass, noting any pose that looks wrong even if geometry tests pass.
5. Manual QA script (device or emulator):
   - Start a workout → 10 s "Get ready" with first-exercise animation + spoken how-to → 3-2-1
     beeps → exercise 1 timer starts only then. Tap during get-ready → jumps to 3 s.
   - Stop button → dialog; notification Stop → "Tap again to stop". Confirmed stop → Summary
     shows "Resume workout" for 10 min and resuming works.
   - Finish a session while on the History tab → app navigates to Summary; leave via bottom
     bar → Home shows "View last session summary".
   - Builder: three rest-mode chips; CONTINUOUS shows the one-time notice; a REFORMER
     continuous session still inserts 10 s "Change setup" steps.
   - Player: tap anywhere during a rest skips it; machine cue text is large/high-contrast.
   - Settings: 7 accent swatches; each recolors the whole app in all four modes; AMOLED stays
     pure black; no Material default purple anywhere.
   - Summary: sRPE + difficulty chips + enjoyment stars persist to History.
   - After ~2 simulated "too easy" weeks (seed via DB if needed), Builder shows the progression
     card; accepting updates the defaults.
   - Plan screen: MET-min dose meter row renders with the 730/400 target logic.
   - Kill the app mid-session → resume restores the correct step, block accounting intact.
6. Docs updated: README count line, DECISIONS.md D-20 (Phase 3 Step 7), RESEARCH.md §12,
   and WALKTHROUGH.md amended for: get-ready phase, rest modes, accent palettes, sRPE capture,
   preference learning + reset, progression card, dose meter, myth-buster cards.

## What is explicitly OUT of scope

Do not implement (deferred by decision): live BLE heart-rate zones, nutrition tracking,
imperial units, cloud/social anything, Design 3 contextual bandit (Phase 4 research verdict:
do not build), new StepType enum values, Peloton-style class content. If an item seems to
require one of these, it's a misreading — check the phase doc.
