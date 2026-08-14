# Implementation progress

Check items in the same commit as the work. An unchecked item with no DEVIATIONS.md entry means
the work is not done. See IMPLEMENTATION_PLAN.md for rules.

## Phase 1 — Trust & safety (PHASE1_TRUST_SAFETY.md)

- [x] P1-9 DatabaseValidator bounds (year dynamic, cadence symmetric)
- [x] P1-5 Generator time-math: redistribute(), routine-fit guard, budget warnings, plannedTotalSec, minViableDurationMin, high-adiposity reorder
- [x] P1-5T WorkoutGeneratorTimeBudgetTest (full config matrix ±5%)
- [x] P1-7 VoiceCoach: TtsStatus flow, retry, bounded queue, counter clamp, Player/Settings banners + VoiceCoachStatusTest
- [x] P1-R SessionEngine extraction + SessionEngineTest (13 tests)
- [x] P1-6a Tick delta clamp
- [x] P1-6b Snapshot carries blockActiveMs/blockBounds (+ SessionSnapshotCompatTest)
- [x] P1-6c Skip WORK skips trailing REST
- [x] P1-6d TTS flush on natural advance + VoiceCoach flush-race fix
- [x] P1-6e Restore: cue flags from remaining time + re-announce on resume
- [x] P1-6f finishSession try/finally containment
- [x] P1-6g Final-tick accounting
- [x] P1-6h Block MET duration-weighted; warm-up/cool-down sentinel block indices (−1/−2)
- [x] P1-1 Get-ready prepare phase (10 s; tap-to-skip to 3 s; 3 s resume countdown; PrepareView; notification state)
- [ ] P1-3 Summary: sessionId identity, global navigate-once, Home summary card, PlayerScreen simplification
- [ ] P1-2 Stop confirmation (dialog + two-stage notification) + 10-min stopped-snapshot recovery
- [ ] P1-4 Widget/start guards + widget updateAll on finish
- [ ] P1-8 Health Connect: permission pre-check, per-timestamp zone offsets, autoRecorded metadata + clientRecordId, retry actions (Summary + History), markHcWritten wired

## Phase 2 — Rest model, player, themes (PHASE2_REST_PLAYER_THEMES.md)

- [ ] P2-A1 RestMode enum + GeneratorConfig field (workRestRatio deprecated, kept) + settings keys + Builder chips + continuous notice + Settings default row
- [ ] P2-A4 Mode-driven rests in generator (setupChange, recoveryRestSec, restBetween), new solver, continuous next-up cue + RestModeTest
- [ ] P2-A2 Skippable rests (tap-anywhere + button)
- [ ] P2-A3 Trailing-rest regression test
- [ ] P2-B5 Machine cue prominence (titleLarge/onSurface; name headlineLarge)
- [ ] P2-B6 Time-weighted progress + minutes-left caption
- [ ] P2-B7 Auto-pause on call / audio-becoming-noisy
- [ ] P2-B8 Keep-screen-on saveable + persisted + cleared while paused
- [ ] P2-B9 “+30” control legibility
- [ ] P2-B10a Numeric keyboards (all number fields)
- [ ] P2-B10b isError + supporting text on range-validated fields
- [ ] P2-B10c Delete confirmations (History, Home saved workouts)
- [ ] P2-B10d Empty states (Library, History, Home)
- [ ] P2-B10e Category.displayName() everywhere + DisplayNameTest
- [ ] P2-B10f Remove do-nothing Metric-units switch (keep stored key)
- [ ] P2-B10g EvidenceBadge non-interactive
- [ ] P2-B10h Onboarding back button + step indicator
- [ ] P2-B10i Tab highlight on nested routes
- [ ] P2-B10j Calendar day semantics + Mon/Tue headers
- [ ] P2-B10k Slider semantics + volume % readout
- [ ] P2-B10l Builder preview edit protection (banner + regenerate confirm)
- [ ] P2-B10m rememberSaveable (Summary name, History month, manual entry)
- [ ] P2-B10n PlanScreen goal-aware copy; drop “See RESEARCH.md”
- [ ] P2-B10o Summary re-save on rename
- [ ] P2-B10p Library detail loading/error states
- [ ] P2-B10q Home resume-snapshot via ViewModel with name/progress
- [ ] P2-C11 7 accent palettes × Light/Dark/AMOLED/System, full 21-role coverage, swatch row, MainActivity wiring
- [ ] P2-C12 ThemePaletteContrastTest + SettingsRoundTripTest

## Phase 3 — Database & animations (PHASE3_DATABASE_ANIMATIONS.md)

- [ ] P3-1.1 Tier conflicts resolved (dead bug/bird dog MODERATE; bridges STRONG + Searle ref copy)
- [ ] P3-1.2 Hamstring-stretch contradiction resolved (both MODERATE, aligned notes)
- [ ] P3-1.3 spin_tabata_sprint loses VISCERAL_FAT target
- [ ] P3-1.4 Contraindication fixes (burpee ANKLE; side-plank WRIST alignment; spin sprints + ell_hill_grind KNEE)
- [ ] P3-1.5 spin_recovery_soft resistance 0.18 + MET 3.5
- [ ] P3-1.6 README count updated
- [ ] P3-1.7 REFORMER/BACK warm-up flags (mermaid, lunge stretch, cat-cow, pelvic tilt)
- [ ] P3-2.1 schemaVersion → 3
- [ ] P3-2.2 EllipticalCue.incline + renderer + validator
- [ ] P3-2.3 Insights model + validator + Library myth-buster cards
- [ ] P3-2.4 Prop.BENCH + drawBench + BENCH_Y
- [ ] P3-2.5 Validator minimum counts raised
- [ ] P3-3.1 Reused-family animations (13: squat thrust, split squat, single-leg bridge, single-leg RDL, side leg raise, donkey kick, bicycle, hollow, flutter, bridge march, single-leg footwork, reformer running, eccentric footwork)
- [ ] P3-3.2 New pose families (6: shadow boxing, calf raise, pike push-up, Copenhagen, balance, soleus)
- [ ] P3-3.3 Parametric machine instances (7) + all registered in AnimationRegistry.all
- [ ] P3-4.1 SPIN entries (4: 4x4, snap sprint, 10-20-30 block, standing sprint)
- [ ] P3-4.2 ELLIPTICAL entries (3: 4x4 push, incline climb, reverse sprint)
- [ ] P3-4.3 FLOOR strong/moderate entries (10)
- [ ] P3-4.4 FLOOR LIMITED entries (6)
- [ ] P3-4.5 BACK entry (bridge march)
- [ ] P3-4.6 REFORMER entries (3)
- [ ] P3-5 Routines (9: Norwegian 4x4 ×2 + compacts, 10-20-30, SIT snaps, recovery ×2, 45-min MICT)
- [ ] P3-6 Insights JSON (2 myth-busters)
- [ ] P3-7 Docs (README, DECISIONS D-20, RESEARCH §12)
- [ ] P3-8.2 Targeted animation contact tests (11 new assertions + cadence/tempo list extensions)
- [ ] P3-8.4 DatabaseValidatorTest updates (exact counts, tier decisions, warm-up coverage, contraindications, fit-guard coverage, D-11 caps, negative tests)

## Phase 4 — Learning loop (PHASE4_LEARNING_LOOP.md)

- [ ] P4-A Room v2: step_events + exercise_prefs + sRPE columns, MIGRATION_1_2, destructive fallback removed, DAOs, schema export, MigrationTest + DaoTest
- [ ] P4-F1 CompletedBlock.metMinutes written at finishSession
- [ ] P4-B StepEventRecorder + service hooks + snapshot events + flush with historyId + StepEventLoggingTest (7 tests)
- [ ] P4-C sRPE/difficulty/enjoyment on Summary + setSessionFeedback + export fields + tests
- [ ] P4-D PreferenceMath/Repository/Weights + generator hooks (pickBalanced, routine cost, fallback ordering) + ε-exploration + settings toggle/reset + Builder chips + PreferenceModelTest (9 tests)
- [ ] P4-E ProgressionEngine + Builder suggestion card + lastConfig persistence + ProgressionEngineTest (11 tests)
- [ ] P4-F2 WeeklyPlanEngine fixes (truncation, HIIT double-count) + dose meter on PlanScreen + WeeklyPlanEngineTest (7 tests)
- [ ] P4-G TimeOfDayNudge + Plan card + apply/dismiss/never + TimeOfDayNudgeTest (6 tests)

## Final gates

- [ ] All tests green (`./gradlew testDebugUnitTest`)
- [ ] Release build green with no-INTERNET confirmation (`./gradlew assembleRelease`)
- [ ] Hidden QA screen eyeball pass over 26 new animations
- [ ] Manual QA script from IMPLEMENTATION_PLAN.md § Definition of done
- [ ] WALKTHROUGH.md updated for all new user-facing behavior
