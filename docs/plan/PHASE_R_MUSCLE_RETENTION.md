# Phase R — Muscle-retention sessions

Built out of sequence, ahead of Phases 3 and 4, at the user's request: resistance training with body
weight, the reformer and light hand weights, to minimise muscle loss while losing fat. This document
records what was built, the rules it follows, and what it changes for the phases still to come. The
evidence is in `RESEARCH.md` §12; the judgment calls are `DECISIONS.md` D-20–D-29.

## What it is

A session **focus** (`GeneratorConfig.focus`, default `GENERAL`). With `MUSCLE_RETENTION`, FLOOR and
REFORMER blocks are built by `ResistanceBlockBuilder` as repeated sets — one exercise per movement
slot, two to three sets each, in a window sized for light loads, taken close to failure. BACK, SPIN
and ELLIPTICAL blocks are generated exactly as before; cardio machines count as zero resistance sets.

## Rules that later work must keep

1. **GENERAL is untouched.** The circuit generator's code path does not change, and it consumes the
   same random numbers it always has. `ResistanceBlockBuilderTest` asserts general plans are
   byte-identical when content the user has no equipment for is added.
2. **One eligibility rule set.** Anything that can put an exercise into a plan goes through
   `ExerciseEligibility` — generator pools, warm-up/cool-down, the retention builder, the Builder's
   swap button. Do not add a second copy of the rules.
3. **The retention tier is its own claim.** `Exercise.resistance.tier` never borrows from
   `evidenceTier`. STRONG/MODERATE need a reference inside `resistance.references`; LIMITED needs a
   candid `resistance.note`. Reformer work is never STRONG.
4. **Limited evidence fills gaps only.** A LIMITED exercise is used for a slot nothing better can
   cover, only when asked for, never instead of a better-graded option — except a ladder level the
   user chose for themselves.
5. **Equipment is a hard gate.** `HAND_WEIGHTS` comes from the per-session chip and counts only in a
   retention session; `CHAIR`, `STEP`, `REFORMER_BOX` and `ANKLE_ANCHOR` come from Settings. A
   hand-weight exercise states `minUsefulKg` and is not generated below it.
6. **Additive storage only; Room stays at version 1.** New fields on `GeneratorConfig`, `SessionStep`,
   `SessionBlock`, `CompletedBlock` and `SessionSnapshot` all default; ladder levels and equipment
   live in DataStore.
7. **Numbers that are derived say so.** Set windows, rests, the weekly target and `minUsefulKg` are
   translations of the evidence, not findings, and are marked as such wherever they appear.

## Items

| ID | Item | Where |
|---|---|---|
| R-0 | Additive model (`Equipment`, `Muscle`, `MovementSlot`, `LoadClass`, `ResistanceProfile`, `SessionFocus`, set fields), `ExerciseEligibility`, validator rules, equipment settings | `data/model`, `domain/generator/ExerciseEligibility.kt`, `data/DatabaseValidator.kt` |
| R-1a | Evidence-graded content, asset `schemaVersion: 3`, `RESEARCH.md` §12 | `assets/exercise_db.json` |
| R-1b | Animations for the new exercises; `KeyframeAnim.handWeights` and the dumbbell glyph | `anim/` |
| R-1c | `ResistanceBlockBuilder`, retention warnings and fixes, Builder focus and chips, player set counter / effort target / "Set done" / "Switch sides", set-aware voice cues | `domain/generator`, `service`, `ui/screens/builder`, `ui/screens/player` |
| R-2a | Which sets count; hard sets per muscle on `CompletedBlock`; weekly meter on the Plan screen; deficit-first slot order | `domain/resistance`, `domain/plan`, `ui/screens/plan` |
| R-2b | Post-set effort rating; progression advice on the Summary; ladder levels in Settings | `service`, `ui/screens/summary`, `ui/screens/settings` |
| R-2c | Health Connect: set-based mat blocks written as strength training | `health/HealthConnectManager.kt` |
| R-3 | Equipment-dependent exercises, all off until switched on in Settings → Equipment: incline and feet-elevated push-ups, rear-foot-elevated split squat, single-leg squat to a chair, single-leg hip thrust (chair); single-leg calf raise off a step (step); Nordic hamstring curl (ankle anchor); long-box pulling straps (reformer long box); plus the reverse Nordic, which needs nothing. Props `BENCH_FRONT`, `BENCH_BACK`, `STEP`, `ANCHOR`, `REFORMER_BOX_ARM_STRAPS`; one-arm strap animations draw a single strap | `assets`, `anim/` |

Tests: `ResistanceFoundationTest`, `ResistanceBlockBuilderTest`, `ResistanceContentTest`,
`ResistanceTrackingTest`, `HealthConnectTypeTest`, plus the additions to `AnimGeometryTest`.

## What this changes for Phase 3

Phase R built six of Phase 3's exercises and their animations to the Phase 3 spec, and took asset
`schemaVersion: 3`. Phase 3 must be read with these adjustments (recorded in `DEVIATIONS.md`):

- **Already built — do not add again:** `floor_split_squat`, `floor_single_leg_bridge`,
  `floor_single_leg_rdl`, `floor_calf_raise`, `floor_pike_pushup`, `ref_footwork_single_leg`, and the
  animations `fl_split_squat`, `fl_bridge_single`, `fl_single_leg_rdl`, `fl_calf_raise`,
  `fl_pike_pushup`, `rf_footwork_single`. That is 5 of the 10 entries in Phase 3 §4.3, 1 of the 3 in
  §4.6, 4 of the 13 animations in §3.1 and 2 of the 6 in §3.2.
- **Schema version:** Phase 3's asset change lands as `schemaVersion: 4`, not 3.
- **Exact-count gates:** Phase 3 adds its *remaining* 21 exercises and 20 animations to whatever the
  database then holds. Its "105 exercises" gate, the validator minimums in §2.5 and the counts in
  §8.4 and in `IMPLEMENTATION_PLAN.md` must be recomputed from the database at that time, not taken
  from the Phase 3 text.
- **Re-verify Phase 3's citations before use.** The papers Phase 3 cites for the six entries are real,
  but several of its `finding` sentences claimed things the papers do not say ("the single-leg version
  roughly doubles the load on the working hip", "single-leg footwork doubles the leg's share of the
  spring load", a pike push-up claim attributed to a paper whose abstract lists no exercises). They
  were rewritten here. The remaining Phase 3 entries have not been checked and should be, against
  PubMed, before they ship.
- **New entries need a decision about retention.** Any Phase 3 exercise that is plausibly a resistance
  set (the Copenhagen plank, for one) should get a `resistance` profile graded on its own evidence, or
  deliberately none.
- **`Prop.BENCH`:** Phase 3 §2.4 specifies a single `Prop.BENCH`. Phase R needed the bench both in
  front of and behind the figure and shipped `Prop.BENCH_FRONT` / `Prop.BENCH_BACK` with
  `BENCH_Y = 0.22f` (the Phase 3 value) and their x-extents as constants on `AnimationRegistry`, drawn
  by `drawBench`. Phase 3's Copenhagen plank and soleus push-up should use those rather than add a
  third bench. Surfaces are authored as contact heights like `GY`; the renderer draws their top edge
  0.026 lower.

## What this changes for Phase 4

Nothing structural: Room is still at version 1, so `MIGRATION_1_2` is unaffected. Two overlaps to
reconcile when Phase 4 is built:

- **Set outcomes already exist.** `EngineState.completedSets`, the post-set `EffortRating` and
  `ResistanceTracking` cover resistance sets. Phase 4's `StepEventRecorder` should record resistance
  sets through the same engine hooks rather than a parallel mechanism, and its session sRPE is in
  addition to — not instead of — the per-set rating.
- **Progression.** Phase 4's `ProgressionEngine` adjusts duration and intensity for general sessions.
  Resistance progression is ladder-based (`ProgressionAdvisor`, `Ladders`) and should stay separate;
  the two must not both nudge the same session.
