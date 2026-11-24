# DECISIONS.md — judgment calls

- **D-01 Animations as Kotlin data, not JSON.** Keyframed joint poses live in typed Kotlin
  (`anim/AnimationRegistry.kt`) beside the exercise DB rather than in the JSON asset — type safety, easy
  reuse of shared rigs (spin positions), and the DB validator still checks every `animationId` resolves.
- **D-02 Room stores exercises as JSON columns.** The bundled JSON asset is the source of truth; Room rows
  carry the serialized model plus indexable columns (id, category). Avoids a 40-column entity for data that
  is read-only reference content.
- **D-03 Foreground service type `health|mediaPlayback`.** API 34+ requires a typed FGS. `health` needs
  ACTIVITY_RECOGNITION at runtime; the app requests it in onboarding, and falls back to starting the service
  as `mediaPlayback` (it genuinely plays TTS coaching audio) if the user denies it. Both types declared.
- **D-04 Release build signs with the debug key by default.** Distribution is sideload-only; README documents
  generating a proper keystore and wiring it in for a real signed release.
- **D-05 Rest days default to Sunday** for streak fairness; configurable in Settings.
- **D-06 Elliptical reverse-pedal cues say "quad emphasis".** The only EMG evidence (Willamette 2005) shows
  rectus femoris up when striding backward; glutes track incline, not direction. Vendor claims about
  hamstrings were not reproducible and are not spoken.
- **D-07 No GR7 Bluetooth integration.** Research (RESEARCH.md §9): the GR7's BLE cadence sensor has no
  documented protocol, community-reported data bugs, and no FTMS resistance control (manual lever). The app
  also must not hold network permissions and targets 100% offline operation. Cue text references level
  numbers instead; the max level is a settings field (default 11).
- **D-08 VG50BS resistance default = 16 levels.** The Infiniti AU site was unreachable (HTTP 503) during
  research so the AU console's level count is unconfirmed; the spec mandates a 16 default in that case. The
  EU dealer spec for the VG50BS-BT variant lists 32 levels — noted in Settings help text so the user can set
  the field to match their console (cues scale automatically from fractions of max).
- **D-09 Tabata MET 11.0 while spin segments use compendium cycling codes.** HIIT-style floor/machine blocks
  use the closest 2024 Compendium code (02214 HIIT vigorous, 01270 spin class 9.0, 01305 bicycling HIIT 8.8);
  where a segment is clearly lighter (recovery spin) the wattage-based cycling codes are used.
- **D-10 Work:rest ratio applies to FLOOR/REFORMER blocks only.** Machine blocks are continuous coached
  intervals whose recovery structure comes from the named routines themselves (evidence-based class formats),
  so the global ratio doesn't reshape them.
- **D-11 Sprint cadence capped at 110 rpm** per Mad Dogg guidance (sprints ≤30 s, meaningful resistance,
  ceiling ~110 rpm) even though some gym programs allow 120.
- **D-12 Import de-duplicates** on (name + created-at) for saved workouts and (start-time + name) for history
  rather than wiping existing data — import is additive and repeat-safe.
- **D-13 Waist-circumference thresholds use WHO cut-offs** (94/102 cm men, 80/88 cm women) as the visceral-fat
  proxy guidance in manual measurements.
- **D-14 Weekly plan targets with visceral-fat goal on: 4 cardio sessions / ~180 min + 2 strength sessions**,
  slightly above WHO minimums, matching the dose–response evidence (Chang 2021: ≥3 sessions/week; Recchia
  2023: dose-dependent VAT loss). With the goal off, WHO baseline (150 min / 3 cardio / 2 strength).
- **D-15 WorkManager's library manifest adds ACCESS_NETWORK_STATE and RECEIVE_BOOT_COMPLETED.** Neither
  grants network I/O; RECEIVE_BOOT_COMPLETED is what lets reminder work survive reboots. The offline
  guarantee (no `android.permission.INTERNET`) is asserted on the merged release manifest by a Gradle
  task, a unit test, and a CI `aapt dump permissions` step.
- **D-16 Process-death restoration** persists the running session snapshot every 5 s to a file in app storage
  (not Room) — cheap, atomic, and wiped on clean completion.
- **D-17 Back care category (v1.1).** New BACK category of 13 physiotherapy-informed mat exercises
  (McGill big three, motor-control drills, hip strength, McKenzie extension). The validator enforces
  LOW impact for every BACK entry; flexion stretching ships LIMITED with an honest note. Counts toward
  the weekly strength target. Citations verified on PubMed (RESEARCH.md §11).
- **D-18 Custom bottom bar (v1.1).** Replaced M3 NavigationBar with a visually identical custom bar
  whose full cells (76 dp tall, one-fifth screen wide, edge to edge) are the touch targets, fixing
  hard-to-hit edge tabs on the Edge 60 Fusion's curved panel. The bar is permanent on every screen
  except onboarding; during a workout, leaving via a tab is safe because the session runs in the
  foreground service and Home shows a "return to player" button.
- **D-19 Animation accuracy overhaul (v1.2).** An audit found ~two-thirds of the hand-authored stick-figure
  keyframes were geometrically wrong, traced to two root causes: the arm-angle doc in `Rig.kt` contradicted
  the FK (arms mirrored on any leaned torso), and positive knee values fold the shank anatomically backwards
  once the thigh rotates past ±90° (every supine pose). All affected poses were re-solved numerically against
  the rig FK so support contacts actually land (feet on the foot bar, hands on the bar, elbows on the mat).
  Structural upgrades: a two-segment spine channel (cat-cow, crunches and McKenzie press-ups now articulate),
  per-segment easing with a minimum-jerk quintic default plus ACCEL/DECEL ballistic segments, asymmetric
  rep tempo with end-range dwells (eccentric slower than concentric), a stance-foot ground constraint applied
  after interpolation, and per-exercise variants that were previously shared (footwork heels vs toes, forward
  vs reverse lunge, spin/elliptical cadences matching the voice coaching). Rendering moved from uniform
  strokes to tapered capsule limbs with a weight hierarchy, a curved spine, near/far depth via blended color
  and parallax shoulder/hip offsets, feet and hands, a radial contact shadow and a breathing overlay so holds
  never freeze. A geometry test suite (floor penetration, joint limits, loop continuity, velocity caps) runs
  over every registry animation in CI.
- **D-20 Muscle retention is a session focus, not a sixth category.** Categories say *where* you train
  (mat, reformer, bike, elliptical); the focus says *how the block is built*. `GeneratorConfig.focus`
  defaults to GENERAL, so every stored config, saved workout, history row, snapshot and export means
  exactly what it meant before. With the focus on, FLOOR and REFORMER blocks are built by
  `ResistanceBlockBuilder` as repeated sets; the circuit generator's code path is untouched, and a test
  asserts general sessions are byte-identical when content the user has no equipment for is added.
- **D-21 A separate retention tier.** `Exercise.resistance.tier` grades one claim — "an effective
  resistance exercise for maintaining its target muscles" — apart from `evidenceTier`. Reusing the
  general tier would have shipped reformer footwork, the pelvic curl and feet-in-straps as
  MODERATE/STRONG muscle-retention work on the strength of flexibility and back-pain evidence. Same
  honesty rule as the general tier: STRONG/MODERATE need a reference of their own, LIMITED a candid note.
- **D-22 Sets, not bouts; the clock is only a container.** A 40 s bout with a 15 s rest is conditioning.
  Set windows are derived from rep-based studies, not found in any: 45–75 s for light loads (body
  weight, springs, light hand weights) at a slow continuous tempo, 30–45 s for a load failed at in 8–15
  reps, 20–45 s for holds, the window per side for unilateral work; 2–3 sets (a fourth only when nothing
  else can fill the block). Rests: 60 s before repeating an exercise (75 s in straight-set mode), 30 s
  when alternating a non-competing pair. What makes a set count is how it ends, so the effort target is
  always shown and spoken, and "Set done" — unlike Skip — keeps the rest and records the set.
- **D-23 Cardio machines contribute zero resistance sets, and get no resistance content** (user
  decision after the evidence review). Cycling shows small quadriceps gains in previously inactive
  people in small uncontrolled studies and is clearly inferior to resistance training; elliptical
  data are three DXA studies from one lab; nothing measures upper-body loading from the handles. In a
  muscle-retention session SPIN/ELLIPTICAL blocks are generated exactly as before, the generator
  advises doing resistance first, and the visceral-fat "under half cardio" warning is not raised.
- **D-24 Hand weights are a per-session choice gated by a useful minimum.** The Builder chip switches
  them on for one session; Settings stores the heaviest weight owned (default 2 kg — the user has 1,
  1.5 and 2 kg). Each hand-weight exercise states `minUsefulKg`; below it the exercise is not
  generated at all, because a set that cannot get hard is worse than no set. Those minimums are
  planning estimates, not study findings, and say so. At 2 kg only shoulder raises, curls, triceps
  extensions and prone raises qualify; rows, presses and loaded leg work need heavier weights and are
  deliberately not shipped yet. Hand weights never count outside a muscle-retention session.
- **D-25 Limited-evidence exercises are gap fillers only.** In a muscle-retention session a LIMITED
  exercise is used only for a region nothing better can cover with the available equipment, only when
  the global low-evidence setting or the session's "Limited-evidence fillers" chip is on, and never in
  place of a better-graded one. When it is off the generator names the uncovered region and offers the
  fix. This matters most for upper-body pulling: with no adequate hand weights the one-arm reformer
  strap row (LIMITED) is the best option there is, and the app says exactly that.
- **D-26 What counts as a hard set.** Performed (ran its window, or ended with "Set done" after at
  least 15 s) and not rated "4 or more reps left". Unrated sets count — the rating is optional. One set
  per primary muscle, half per secondary (the convention of the dose-response meta-regressions).
  Weekly target 6 hard sets per muscle group with a floor of 4: the floor is Iversen 2021's minimum,
  the target a judgment call below the ~10 where growth-oriented curves flatten. No trial has tested
  a minimum dose in an energy deficit; the Plan screen says so and promises "minimise", not "prevent".
- **D-27 Progression is advice, not automation, and lives in DataStore.** After a session the Summary
  suggests moving up a ladder only when at least two sets were rated and every one was "4 or more left"
  (self-rated reps in reserve are noisy), and the user applies it with one tap. Ladder levels are a
  small DataStore string; set outcomes ride on `CompletedBlock` as additive JSON. **No Room change** —
  Room stays at version 1, so Phase 4's planned `MIGRATION_1_2` is unaffected.
- **D-28 Phase 3 overlap built here.** Six exercises Phase 3 specifies (split squat, single-leg glute
  bridge, single-leg Romanian deadlift, calf raise, pike push-up, single-leg footwork) and their
  animations were built in this work to that spec, with retention profiles added, and the asset moved
  to `schemaVersion: 3`. Phase 3's remaining content therefore lands at `schemaVersion: 4` with its
  exact-count gates renumbered — see `docs/plan/DEVIATIONS.md` and `PHASE_R_MUSCLE_RETENTION.md`.
- **D-29 Two fixes made in passing.** (a) The "Halfway cue" and "Spoken how-to descriptions" toggles
  were never consulted for the mid-step and in-rest cues — both were always spoken; they now honour
  the settings. (b) CI had been failing since the `android-actions/setup-android` default package
  list began requesting the removed `tools` SDK package; the workflow now requests `platform-tools` only.

