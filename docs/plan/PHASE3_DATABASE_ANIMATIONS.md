All exploration complete. Below is the full Phase 3 implementation plan.

---

# Kinetiq Phase 3 — Exercise Database & Animation Upgrade: Implementation Plan

## Verified ground truth (read before implementing)

- The "dbVersion" field is **`schemaVersion`** (currently `2`) at the top of `/home/user/edgeHealth/app/src/main/assets/exercise_db.json`. `ExerciseRepository.loadAndSeed()` re-seeds Room whenever `dao.meta("schema_version") != parsed.schemaVersion` (any mismatch, not only newer), after first running `DatabaseValidator.validate(parsed, AnimationRegistry.ids)` and `check()`-crashing on failure. So: **bump `schemaVersion` to `3`** and the re-seed is automatic; no repository code change needed.
- Current DB contents (counted): FLOOR 27, REFORMER 17, SPIN 12 segments, ELLIPTICAL 9 segments, BACK 13 = **78 exercises**; routines: 7 SPIN + 5 ELLIPTICAL = 12. README.md line 101 still says "65 entries" (the only stale count; WALKTHROUGH.md has none).
- `DatabaseValidator` rules every new entry must pass: unique ids; non-blank/no-placeholder text (`todo|tbd|lorem|placeholder|xxx|fixme` as whole words); `voiceHowTo` ≥ 2 sentences; ≥ 1 form cue; STRONG/MODERATE need ≥ 1 reference (all reference fields non-blank, year 1950–2026); LIMITED needs `popularityNote`; `minSec <= defaultWorkSec <= maxSec`, `defaultWorkSec > 0`, `defaultRestSec >= 0`, `0 < met <= 20`; ≥ 1 target; SPIN entries must be `INTERVAL_SEGMENT` with `machine.spin` (resistance fractions 0..1, low ≤ high; cadence low 40–130, high 40–140, low ≤ high, non-blank position); ELLIPTICAL must be `INTERVAL_SEGMENT` with `machine.elliptical` (fractions, direction FORWARD|REVERSE, non-blank arms); REFORMER must be `DISCRETE` with `machine.reformer` (springs matching `^(LIGHT|MEDIUM|HEAVY)(_[1-4])?$`, non-blank bodyPosition); FLOOR/BACK `DISCRETE`; BACK impact must be LOW; `animationId` must resolve in `AnimationRegistry.ids`; routines: SPIN/ELLIPTICAL only, non-empty steps, each step references an existing same-category `INTERVAL_SEGMENT`, `durationSec >= 10`, `totalSec >= 240`. Minimum counts currently FLOOR ≥ 18, REFORMER ≥ 14, SPIN ≥ 10, ELL ≥ 8, BACK ≥ 10, SPIN routines ≥ 6, ELL routines ≥ 4.
- `AnimGeometryTest` derives `keyframed = AnimationRegistry.all.filterIsInstance<KeyframeAnim>()` and every registry-wide test (`no joint ever penetrates the floor`, `figures never float` [with an explicit `airborne` allowlist map], `knee and elbow flexion stay anatomical` [|knee| ≤ 156, |elbow| ≤ 160], `loops are seamless`, `angular velocity stays bounded` [≤ 2200 deg/s], `reformer carriage channel stays in range`) iterates it — **every new animation is auto-covered with zero test changes**. Contact tests exist as targeted patterns (`mountain climber keeps hands planted`, `planted support toes never lift`) to extend.
- `DebugAnimScreen` (hidden QA screen) builds `ids = AnimationRegistry.all.map { it.id }` — **new animations appear automatically**; no change needed.
- Generator facts: `pool()` excludes `isWarmupCooldown` entries and (unless enabled) LIMITED tier; `warmCool()` pools `isWarmupCooldown && (category == cat || category == FLOOR)` sorted in-category first — REFORMER and BACK currently have **zero** warm-up entries so they always fall back to FLOOR content (fix A-7). `machineBlock()` scales a chosen named routine by `blockSec/totalSec`; the Phase 1 spec adds a `[0.5, 2]` fit guard on that ratio, so routine totals must cover 300–2400 s blocks at ratios in [0.5, 2] (verified below).
- Voice model (WorkoutSessionService): every step gets a spoken announcement (name + duration + machine cue + optional how-to) plus a halfway cue on long steps. **This is why sub-10 s or even 20 s micro-steps are unusable for 6 s/9 s and 30/20/10 protocols** — the announcement is longer than the step. Both are modelled as single INTERVAL_SEGMENT blocks whose `voiceHowTo`/`voiceFormCues` teach the internal rhythm (design decisions below).

Implementation sequence: **Step 1 fixes → Step 2 schema/model/validator → Step 3 animations → Step 4 exercises → Step 5 routines → Step 6 insights → Step 7 docs → Step 8 tests.** All JSON edits land in one asset change with `schemaVersion: 3`.

---

## STEP 1 — Consistency fixes to existing data (asset JSON only)

**Files:** `/home/user/edgeHealth/app/src/main/assets/exercise_db.json` (all sub-items), `/home/user/edgeHealth/README.md` (1.6).

### 1.1 Cross-category tier conflicts (six entries, final tiers)

Rule applied (RESEARCH.md §11, Saragiotto 2016): pure **motor-control drills = MODERATE** ("more effective than minimal intervention; **not superior** to other exercise"); **strength/stabilisation patterns = STRONG** (Searle 2015: strength & coordination programs most effective).

| id | current | **final** | change |
|---|---|---|---|
| `floor_dead_bug` | MODERATE | **MODERATE** | none |
| `back_dead_bug` | STRONG | **MODERATE** | `"evidenceTier": "MODERATE"` (keeps Saragiotto ref — MODERATE requires ≥1 ref, satisfied) |
| `floor_bird_dog` | MODERATE | **MODERATE** | none |
| `back_bird_dog` | STRONG | **MODERATE** | `"evidenceTier": "MODERATE"` (keeps McGill + Saragiotto refs) |
| `floor_glute_bridge` | MODERATE | **STRONG** | `"evidenceTier": "STRONG"` AND copy the Searle 2015 reference object verbatim from `back_glute_bridge` into its `references` array (so the STRONG tier is carried by the strength-program evidence, not just ACSM) |
| `back_glute_bridge` | STRONG | **STRONG** | none |

Rationale line for the commit: dead bug / bird dog are motor-control drills (honest MODERATE per Saragiotto); the bridge is a strength/hip-extension pattern (STRONG per Searle 2015 + Selkowitz 2013 EMG).

### 1.2 `floor_hamstring_stretch` vs `back_toe_touch_stretch`

Contradiction: identical movement (both use `fl_hamstretch`), FLOOR entry is MODERATE cool-down staple, BACK entry is LIMITED with a warning note. Resolution — **single tier MODERATE for both, aligned notes, cool-down usage kept**:

- `back_toe_touch_stretch`: `"evidenceTier": "MODERATE"` (its Gordon 2016 reference already satisfies the MODERATE ref rule). **Keep** its `popularityNote` unchanged — `popularityNote` is nullable on any tier and LibraryScreen renders it as an "Evidence note" whenever non-null; the validator only *requires* it for LIMITED.
- `floor_hamstring_stretch`: add an aligned honest note (new field):
  `"popularityNote": "A cool-down staple that reliably improves hamstring flexibility. One honest caveat shared with the back-care library: forward-fold stretching is not a treatment for back pain, and a flared-up back prefers pelvic tilts — keep the stretch in the back of the thigh, never the spine."`
  Keep `warmupCooldown: true`, tier MODERATE, everything else unchanged.

### 1.3 `spin_tabata_sprint` target fix

`"targets": ["VISCERAL_FAT", "CARDIO"]` → `"targets": ["CARDIO"]`. Its own Sultana 2019 citation says very-low-volume HIIT does not meaningfully reduce fat mass; the summary already says so. References, tier, everything else unchanged.

### 1.4 Contraindication fixes

- `floor_burpee`: `"contraindications": ["KNEE", "WRIST", "LOWER_BACK", "ANKLE"]` (adds ANKLE — jump landing).
- Forearm-plank WRIST rule — **forearm-supported holds get no WRIST flag**; align all three side-plank family entries:
  - `floor_side_plank`: `["SHOULDER", "WRIST"]` → `["SHOULDER"]`
  - `back_side_plank_progression` (full side bridge, forearm): `["SHOULDER", "WRIST"]` → `["SHOULDER"]`
  - `back_side_bridge`: already `["SHOULDER"]` — no change. (`floor_plank` already `["SHOULDER"]` — no change.)
- `spin_sprint`: `[]` → `["KNEE"]`; `spin_tabata_sprint`: `[]` → `["KNEE"]` (all-out efforts against heavy gear).
- `ell_hill_grind`: `[]` → `["KNEE"]` (high-resistance grinding).

### 1.5 `spin_recovery_soft`

Chosen resolution: **lower the resistance fraction to match the cue** (the cue "Back the resistance right off" is correct coaching; 0.27 renders as level 3/11 — same as warm-up), and correct the MET:

- `machine.spin.resistanceLow`: `0.27` → `0.18` (renders "resistance 2" on the default 11-level GR7 — matches `spin_cooldown_easy` which already uses 0.18).
- `"met": 4.0` → `"met": 3.5` (below the 4.0 light-cycling code, matching the cool-down segments).
- Cue text unchanged.

### 1.6 Stale counts in docs

`/home/user/edgeHealth/README.md` line 101: `(65 entries, all cited)` → `(105 exercises + 21 named routines, all cited)` — final totals after Step 4/5 (78 + 27 new exercises; 12 + 9 new routines). No other stale counts exist (grepped README/WALKTHROUGH/RESEARCH).

### 1.7 Warm-up/cool-down coverage for REFORMER and BACK

Decision: **flag existing entries, do not clone** (the generator's `warmCool()` only needs `isWarmupCooldown == true` in-category entries; cloning would duplicate content and inflate counts). All four exist already:

- `ref_mermaid`: add `"warmupCooldown": true`
- `ref_lunge_stretch`: add `"warmupCooldown": true`
- `back_cat_cow`: add `"warmupCooldown": true`
- `back_pelvic_tilt`: add `"warmupCooldown": true`

Side effect (accepted): `pool()` excludes warm-up entries from main blocks, so the REFORMER main pool drops 17→15 (then +3 new = 18) and BACK 13→11 (then +1 new, +1 from 1.2's now-MODERATE toe reach = 13). Category minimum counts are unaffected (validator counts all entries).

**Tests (Step 1):** covered by `DatabaseValidatorTest.bundled database passes full validation` plus new assertions in Step 8.4 (tier equality checks for the six 1.1 entries, warm-up coverage per category, contraindication spot-checks).

**Acceptance:** validator passes; `spin_tabata_sprint` no longer carries VISCERAL_FAT; every category returns a non-empty in-category `warmCool()` pool.

**Dependencies:** none (pure data).

---

## STEP 2 — Schema / model / validator adjustments

### 2.1 `schemaVersion` bump + re-seed

**Goal:** force Room re-seed on upgrade.
**Files:** `app/src/main/assets/exercise_db.json` (line 2), no code change.
**Change:** `"schemaVersion": 2` → `3`. `ExerciseRepository` (verified above) clears and re-inserts exercises and routines and rewrites the `schema_version` meta row on next `database()` call. Validation runs before seeding, so a bad asset fails fast in debug and in `DatabaseValidatorTest`.
**Acceptance:** fresh install and upgrade-in-place both show the new entries in Library.

### 2.2 Elliptical incline cue (needed by `ell_incline_climb`)

**Goal:** the VG50BS has an adjustable pedal angle (RESEARCH.md §9); `EllipticalCue` has no incline channel.
**Files:** `app/src/main/java/au/mark/kinetiq/data/model/Models.kt`, `app/src/main/java/au/mark/kinetiq/domain/MachineCueRenderer.kt`, `app/src/main/java/au/mark/kinetiq/data/DatabaseValidator.kt`.
**Exact changes:**
- `Models.kt` — `EllipticalCue` gains: `/** Optional incline/pedal-angle instruction, spoken after the arms cue. */ val incline: String? = null` (default keeps every existing entry parsing unchanged).
- `MachineCueRenderer.kt` — elliptical branch return becomes: `"$res, $direction, ${cue.arms}${cue.incline?.let { ", $it" } ?: ""}."`
- `DatabaseValidator.kt` — in the ELLIPTICAL branch: `cue.incline?.let { validateText(where, "elliptical.incline", it, problems) }`.
**Tests:** unit test in `DatabaseValidatorTest` (blank-incline rejected via synthetic entry) and a `MachineCueRenderer` assertion if a renderer test exists (none found — add the assertion to `DatabaseValidatorTest` or a small new `MachineCueRendererTest`): cue for `ell_incline_climb` ends with the incline phrase.
**Acceptance:** existing 9 elliptical entries render unchanged; `ell_incline_climb` speaks its incline.

### 2.3 Myth-buster mechanism decision: new `insights` JSON section (NOT pseudo-entries)

**Decision & rationale:** LIMITED-tier pseudo-entries would have to fake `met`, `minSec/maxSec`, `animationId`, `voiceHowTo`, would enter the Library's category/target filters and (if the user enables low-evidence) the generator pool — all wrong for content that is not an exercise. A dedicated optional `insights` array is the smaller *honest* change: one data class, one optional field with a default (backward compatible), one validator loop, one Library card section. Insights are served from the in-memory parsed asset (`ExerciseRepository.database()` returns the parsed file, Room is only a mirror) — **no Room/DAO change needed**.
**Files:** `Models.kt`, `DatabaseValidator.kt`, `app/src/main/java/au/mark/kinetiq/ui/screens/library/LibraryScreen.kt`, asset JSON.
**Exact changes:**
- `Models.kt`:
  ```kotlin
  /** A myth-buster card shown in the Library — not an exercise; never enters generation. */
  @Serializable
  data class Insight(
      val id: String,
      val title: String,
      val myth: String,
      val reality: String,
      val references: List<Reference> = emptyList(),
  )
  ```
  and `ExerciseDatabaseFile` gains `val insights: List<Insight> = emptyList()`.
- `DatabaseValidator.kt` — after the routines loop:
  ```kotlin
  db.insights.groupBy { it.id }.filterValues { it.size > 1 }.keys.forEach { problem("Duplicate insight id: $it") }
  for (ins in db.insights) {
      val where = "insight '${ins.id}'"
      validateText(where, "title", ins.title, problems)
      validateText(where, "myth", ins.myth, problems)
      validateText(where, "reality", ins.reality, problems)
      if (ins.references.isEmpty()) problem("$where: needs >= 1 reference")
      // reuse the existing per-reference field checks (extract the loop into a helper or duplicate it)
  }
  ```
- `LibraryScreen.kt` — append a trailing list section: `SectionHeader("Myth busters")` followed by one card per insight (title as `titleMedium`, myth prefixed "The claim: " in `bodyMedium` with `tertiary` colour, reality as `bodyMedium`, then the reference rendered the same way the detail page renders `References`). Wire `insights` through the same state the screen already builds from `ExerciseRepository.database()`.
**Tests:** Step 8.4 — bundled DB has exactly 2 insights, each with ≥1 reference; synthetic negative test (insight without references rejected).
**Acceptance:** two cards visible at the bottom of Library; not present in generator output.

### 2.4 New bench prop for elevated-support animations

**Goal:** Copenhagen plank (top foot on a support) and soleus push-up (seated) need a drawn support surface; `Prop` has none.
**Files:** `app/src/main/java/au/mark/kinetiq/anim/AnimationSpec.kt`, `app/src/main/java/au/mark/kinetiq/anim/AnimationRegistry.kt`, `app/src/main/java/au/mark/kinetiq/anim/ExerciseAnimationView.kt`.
**Exact changes:**
- `AnimationSpec.kt`: `enum class Prop { NONE, MAT, WALL, BENCH, REFORMER, ... }` (add `BENCH`).
- `AnimationRegistry.kt`: `/** Top surface of the drawn bench/box (~45 cm on the normalized figure). */ const val BENCH_Y = 0.22f`.
- `ExerciseAnimationView.kt`: in the prop `when`, `Prop.BENCH -> { drawGround(style); drawBench(style) }` and a `drawBench` helper mirroring `drawWall`: a horizontal top line from x 0.18f..0.50f at `mapY(AnimationRegistry.BENCH_Y)` with two legs down to `GROUND_Y`, stroke `style.prop`, width `scale(0.03f)` (same idiom as `drawWall` at lines 268–274).
**Tests:** compile + the Step 8 contact tests assert joints sit exactly on `BENCH_Y`.

### 2.5 Raise validator minimum counts (lock in Phase 3 content)

**Files:** `DatabaseValidator.kt` minimum-count block.
**Change:** FLOOR ≥ 40, REFORMER ≥ 17, SPIN segments ≥ 14, ELLIPTICAL segments ≥ 11, BACK ≥ 14, SPIN routines ≥ 12, ELLIPTICAL routines ≥ 7. (Actuals after Step 4/5: 43/20/16/12/14 and 13/8.)
**Dependencies:** must land in the same change as Steps 4–5 or the validator fails.

---

## STEP 3 — Animations

All new keyframed poses follow the D-19 discipline: author support contacts exactly on their surfaces (`GY` = 0.46 ankles/wrists on floor, `SUPINE_Y` = 0.45 lying joint line, `BENCH_Y` = 0.22, wall plane x = −0.245), solve contact poses numerically against `Rig.solve` (the geometry tests are the oracle), eccentric slower than concentric with end-range dwell keyframes, `Ease.SMOOTH` default, ACCEL/DECEL only for ballistic segments (none new — no new jumps, so the `airborne` map in `AnimGeometryTest` is untouched). Supine/prone poses need **negative knee values** (thigh beyond ±90°). Keep |knee| ≤ 156, |elbow| ≤ 160, prop channel 0..1, per-sample angular velocity < 2200 deg/s (a 2-keyframe alternating loop of ~1 s with ~140° swings is fine — mountain climber already does this).

**File:** `app/src/main/java/au/mark/kinetiq/anim/AnimationRegistry.kt` — add all instances below AND append every one to the `all` list (this is the only registration point; `byId`/`ids` derive from it).

### 3.1 Reuse of existing families (no new pose families)

| animationId | family reused | keyframe sketch (pose names are existing registry vals) | tempo notes |
|---|---|---|---|
| `fl_squat_thrust` | burpee (crouch/pike/plank), **no flight poses** | `t0 standing → 0.14 burCrouch (LINEAR) → 0.19 burPike (LINEAR) → 0.26 burPlank → 0.40 burPlank (LINEAR) → 0.46 burPike (LINEAR) → 0.54 burCrouch → 0.70 standing` … duration 3400 ms, `prop MAT`, `muscle FULL_BODY`, `pathJoint PELVIS` | stand-up segment (0.54→0.70) slower than the drop; hold at plank; NOT in the airborne map |
| `fl_split_squat` | lunge (`lungeBottom`) | static stance, no alternation: `t0 lungeBottom(front=true, shift=0f).copy(pelvisY = <top height solved with both feet planted, knees ~30°>) → 0.42 lungeBottom(front=true, shift=0f) → 0.54 dwell (dup) → 0.94 top` — derive a "split-stance top" pose from `lungeBottom` by reducing both knee/thigh flexion ~60% and lifting pelvis via `grounded()`-style solve; both feet keep their x through the loop | eccentric 0→0.42, dwell, faster concentric; 3200 ms |
| `fl_bridge_single` | bridge (`brDn`/`brUp`) | `t0 brDn.copy(thighL=-120f, kneeL=-8f, footL=-100f)` (left leg extended, foot clear of mat) `→ 0.32 brUp.copy(thighL=-118f, kneeL=-6f, footL=-100f) → 0.60 dwell → 0.95 down`; near (right) foot stays planted as in brUp | 3600 ms, `muscle GLUTES`, `pathJoint PELVIS`; qualifies for the asymmetric-tempo list |
| `fl_single_leg_rdl` | hinge (`bkHinge` shapes) | `t0 hingeTop-like one-leg stand (thighR 0/kneeR 2 support, thighL -8/kneeL 6 trailing) → 0.42 bottom: torso ≈ 72–80, spine ≈ 6, rear leg thighL ≈ -62, kneeL ≈ 6, footL plantar; arms reach down (uArm solved so wrists hang vertical: uArm ≈ -(torso+spine)+~8) → 0.54 dwell → 0.94 top`; support ankle stays at GY (author with `grounded(0f, 2f)` and keep pelvisX shift ≈ -0.05 at bottom) | 4000 ms, `muscle GLUTES`, `pathJoint HEAD` |
| `fl_side_leg_raise` | clam side-lying (`clamBase`) | legs long instead of folded: base = `clamBase.copy(thighL=-86f, kneeL=2f, footL=-92f, thighR=-86f, kneeR=2f, footR=-92f, pelvisY≈0.40f — solve so the bottom leg lies on the mat)`; `t0 base → 0.40 base.copy(thighR=-52f) (top leg raises) → 0.55 dwell → 0.94 base` | 3200 ms, `muscle GLUTES`, `pathJoint ANKLE` |
| `fl_donkey_kick` | quadruped | `t0 quadruped → 0.38 quadruped.copy(thighR=-58f, kneeR=38f, footR=-95f) (heel drives up/back, knee stays bent) → 0.52 dwell → 0.94 quadruped`; wrists/planted knee stay put (same trick as bird dog) | 3000 ms, `muscle GLUTES` |
| `fl_bicycle` | supine crunch (`crFlat`) + dead-bug alternation | base = `crFlat.copy(spine=-22f, neck=-38f)` (shoulders stay curled all loop); `t0 base.copy(thighR=-118f, kneeR=-102f, footR=-100f, thighL=-98f, kneeL=-6f, footL=-100f, uArmL=52f)` (right knee in, left leg long+hovering, left elbow drives) `→ 0.5 mirrored` | 1900 ms continuous alternation (metronome OK — it's a rhythm move like mountain climber), `muscle CORE` |
| `fl_hollow` | supine (`crFlat`/`supineTabletop`) | `t0 hold: torso 94, spine -16, neck -30, arms overhead along the floor line (uArm ≈ 4f, elbow 2f — world ≈ 98°), legs long and hovering: thigh -101f, knee -2f, foot -100f; pelvisY = SUPINE_Y → 0.5 same ± breathing-scale wiggle (spine -18, thigh -103)` — 2-keyframe isometric like `fl_plank` | 4000 ms, `muscle CORE`; hands and heels must stay ≥ 0.035 above GY (floor test) but a body joint stays near the mat (float test) |
| `fl_flutter` | same hollow base | `t0 hollowBase.copy(thighR=-96f, thighL=-110f) → 0.5 mirrored`, knees ≈ -2 | 1100 ms, `muscle CORE` |
| `bk_bridge_march` | bridge (`brUp`) | `t0 brUp → 0.20 brUp.copy(thighR=-120f, kneeR=-95f, footR=-115f) (right foot lifts to tabletop, pelvis does NOT drop) → 0.34 dwell → 0.48 brUp → 0.68 mirrored left lift → 0.82 dwell → 0.96 brUp` | 4600 ms, `muscle GLUTES`; shoulders stay on mat (test 8.2-h) |
| `rf_footwork_single` | reformer footwork (`supineOnCarriage`) | copy `footworkAnim` timing but only the near (R) leg presses; far (L) leg fixed in tabletop all keyframes: `thighL=-105f, kneeL=-92f, footL=-110f`; near leg runs the existing footwork values (`-156.4/-105 → -106.7/-14.7`), `foot = 15f` | 3200 ms, `prop REFORMER`, `muscle LEGS` |
| `rf_running` | reformer footwork | carriage held long (`prop 0.70` constant): `t0 supineOnCarriage(0.70, …) with near leg long (thighR -106.7, kneeR -14.7, footR -25f relevé) and far knee softened (kneeL -40f, footL 10f heel dropped) → 0.5 mirrored` — alternating heel drop under a long press | 1300 ms continuous, `muscle LEGS` |
| `rf_footwork_ecc` | reformer footwork | same two poses as `footworkAnim(foot=15f)` with eccentric-emphasis timing: `t0 home → 0.22 out (press, ~1.3 s) → 0.30 out dwell → 0.97 home (slow 4 s return)` | duration 6000 ms so the return ≈ 4 s (3–5 s lowering per Roig); qualifies for asymmetric-tempo list |
| *(reuse, no new anim)* `floor_wall_sit_block` | uses existing `fl_wallsit` | — | — |

### 3.2 Genuinely new pose families

| animationId | family | keyframe sketch + contacts | tempo |
|---|---|---|---|
| `fl_shadowbox` | standing boxer stance (new) | base: staggered stance `thighR 14/kneeR 18/footR 4` (lead), `thighL -12/kneeL 22/footL 10` (rear, heel up), pelvis via `grounded(14f,18f)`, torso 8, spine 4, guard arms `uArm ≈ 55/elbow ≈ 120` both sides. Loop: `t0 guard → 0.18 jab (near arm extends: uArmR 96f, elbowR 8f; spine +6 rotation cheat) → 0.30 guard → 0.48 cross (far arm extends: uArmL 99f, elbowL 6f; spine -2, torso 12) → 0.62 guard → 0.80 small weight-shift bounce (pelvisX ±0.02)`. Contacts: both toes at GY all loop | 1600 ms, `muscle ARMS`, `pathJoint WRIST`, `facing SIDE` |
| `fl_calf_raise` | standing heel raise (new) | `t0 standing (foot 0, toes+ankles at GY) → 0.36 top: foot -32f both sides, pelvisY = standing.pelvisY - 0.055f` (solved so TOE stays at GY while the ankle rises) `→ 0.52 dwell → 0.95 down (slow eccentric)`. Wall-hand option omitted — arms stay at sides | 2800 ms, `muscle LEGS`; contact test: toes pinned, ankle rise ≥ 0.03 |
| `fl_pike_pushup` | floor pike press (new; seeded from `burPike`/`iwMid` shapes) | top: high pike, straight arms — start from `burPike` and lengthen legs (`thigh 30f, knee 12f, foot 42f`), wrists at GY, hips high (pelvisY ≈ 0.10–0.14, solve numerically). Bottom: elbows bend to ~95f, head lowers toward the floor between the hands (head.y ≈ GY − 0.06), hips stay high. `t0 top → 0.42 bottom → 0.52 dwell → 0.92 top` | 3200 ms, `muscle SHOULDERS`, `pathJoint HEAD`, `prop MAT` |
| `fl_copenhagen` | side-lying elevated-foot plank (new) — `prop BENCH` | derive from side-plank family (`spDn/spUp` shapes): forearm elbow at GY under shoulder; **far (top) ankle pinned at (x ≈ 0.34, y = BENCH_Y)** all loop; bottom leg short-lever (knee bent ~90, shin hovering). `t0 hips low (pelvisY ≈ 0.36) → 0.40 hips up in line (pelvisY ≈ 0.30, body line shoulder–pelvis–top ankle straight) → 0.58 dwell → 0.95 down`. Solve numerically so the top ankle stays exactly on BENCH_Y at every keyframe | 3800 ms, `muscle LEGS` (adductors→LEGS), `pathJoint PELVIS` |
| `fl_balance` | single-leg stand (new, march-family derived but hold-based) | `t0 standing → 0.10 right knee to hip height (thighR 88f, kneeR 95f, footR -30f), arms out (uArm 65f both) → 0.42 dwell (dup) → 0.50 standing → 0.60 left lift mirrored → 0.92 dwell → then wrap`; support ankle at GY throughout; lifted foot never below GY − 0.05 | 8000 ms slow loop, `muscle LEGS` |
| `fl_soleus` | seated heel raise (new) — `prop BENCH` (bench as the seat) | seated: pelvis at (x ≈ 0.30, y = BENCH_Y − 0.01), torso ≈ 2f upright, thighs horizontal (`thigh ≈ 88f`), knees ~86f so shanks vertical, feet flat, hands resting on thighs (`uArm ≈ 35f, elbow ≈ 55f`). `t0 heels down (foot 0, ankle at GY) → 0.38 heels up (foot ≈ -30f, ankle rises ~0.04, TOE pinned at GY) → 0.50 dwell → 0.94 down` | 2200 ms, `muscle LEGS`, `pathJoint ANKLE` |

### 3.3 Parametric machine instances (exact params must match the voice cues in Step 4)

```kotlin
private val sp4x4          = SpinAnim(id = "sp_4x4", cadenceRpm = 95, standing = 0f, extraLean = 7f, muscle = MuscleGroup.LEGS)
private val spSnap         = SpinAnim(id = "sp_snap", cadenceRpm = 110, standing = 0f, extraLean = 8f, muscle = MuscleGroup.FULL_BODY)
private val sp102030       = SpinAnim(id = "sp_102030", cadenceRpm = 95, standing = 0f, extraLean = 6f, muscle = MuscleGroup.LEGS)
private val spStandingSprint = SpinAnim(id = "sp_standing_sprint", cadenceRpm = 105, standing = 1f, extraLean = 6f, muscle = MuscleGroup.FULL_BODY) // D-11: <= 110
private val el4x4          = EllipticalAnim(id = "el_4x4", strideRpm = 70, armsDrive = true, muscle = MuscleGroup.FULL_BODY)
private val elIncline      = EllipticalAnim(id = "el_incline", strideRpm = 48, armsDrive = true, muscle = MuscleGroup.GLUTES) // glutes track incline (RESEARCH §8)
private val elReverseFast  = EllipticalAnim(id = "el_reverse_fast", strideRpm = 68, reverse = true, armsDrive = false, muscle = MuscleGroup.LEGS)
```

**Registration:** append all 19 keyframed + 7 parametric ids to `AnimationRegistry.all`.

**Tests:** Step 8 (all registry-wide suites auto-cover; targeted contact tests listed there).
**Acceptance:** `AnimGeometryTest` green; hidden QA screen (Settings → long-press version row) cycles all 26 new animations (auto — `DebugAnimScreen` iterates `AnimationRegistry.all`; verify visually once).
**Dependencies:** 2.4 (Prop.BENCH) before `fl_copenhagen`/`fl_soleus`.

---

## STEP 4 — New exercise entries (complete JSON, paste into `exercises`)

All entries below pass every validator rule (checked against the rule list above). Insert SPIN entries after `spin_tabata_sprint`, ELLIPTICAL after `ell_hill_grind`, FLOOR after `floor_child_pose`, REFORMER after `ref_side_splits`, BACK after `back_toe_touch_stretch` (grouping conventions in the file).

### 4.1 SPIN (4 new — includes the 10-20-30 block segment required by the routine design decision in Step 5)

**Design decision (6 s/9 s SIT):** modelled as ONE `INTERVAL_SEGMENT` "sprint-snap block" of 2–4 minutes. Verified against `WorkoutSessionService`: each step triggers a spoken name+duration announcement plus the machine cue and how-to — a 6 s step cannot even finish its own announcement, and the validator floors routine steps at 10 s. The micro-cycle is therefore coached inside one block via `voiceHowTo`/`voiceFormCues` rhythm language, exactly as Tabata's 20/10 is already handled by `spin_tabata_sprint`'s 20 s step + 10 s recovery steps — but at 6/9 s even that decomposition breaks, hence the single block.

```json
{
  "id": "spin_4x4_vo2", "name": "4x4 VO2 block (4 minutes hard)", "category": "SPIN", "kind": "INTERVAL_SEGMENT", "evidenceTier": "STRONG",
  "references": [
    {"title": "Aerobic high-intensity intervals improve VO2max more than moderate training", "authors": "Helgerud J, Hoydal K, Wang E, et al.", "year": 2007, "journal": "Medicine & Science in Sports & Exercise 39(4):665-671", "doiOrPmid": "PMID 17414804", "finding": "4 x 4 minutes at 90-95% HRmax with 3-minute active recoveries improved VO2max significantly more than work-matched moderate training."},
    {"title": "Aerobic interval training versus continuous moderate exercise as a treatment for the metabolic syndrome", "authors": "Tjonna AE, Lee SJ, Rognmo O, et al.", "year": 2008, "journal": "Circulation 118(4):346-354", "doiOrPmid": "PMID 18606913", "finding": "The 4x4 protocol outperformed continuous moderate exercise for reversing metabolic-syndrome risk factors, including greater fitness and insulin-sensitivity gains."},
    {"title": "Effect of exercise intervention dosage on reducing visceral adipose tissue: a systematic review and network meta-analysis", "authors": "Chang YH, Yang HY, Shun SC", "year": 2021, "journal": "International Journal of Obesity 45(5):982-997", "doiOrPmid": "10.1038/s41366-021-00767-9", "finding": "HIIT produced the largest visceral fat reductions of any exercise mode (SMD -0.39) and is effective even in sessions under 30 minutes."}
  ],
  "summary": "The hard four minutes of the Norwegian 4x4: strong but sustainable — hard enough that talking stops, controlled enough that minute four is possible.",
  "voiceName": "Four minute hard block",
  "voiceHowTo": "Settle into a strong seated effort at ninety to one hundred r p m with a gear that makes talking impossible but panic unnecessary. This is a four minute climb to about nine out of ten by the final minute. Pace the first minute deliberately — if minute one feels easy, you have it right.",
  "voiceFormCues": ["Start controlled, finish strong.", "Quiet upper body, big breathing.", "Minute four is the one that counts."],
  "defaultWorkSec": 240, "defaultRestSec": 0, "minSec": 120, "maxSec": 300,
  "met": 10.0, "intensity": "VERY_HIGH", "impact": "LOW",
  "targets": ["VISCERAL_FAT", "CARDIO"], "contraindications": ["KNEE"],
  "machine": {"spin": {"resistanceLow": 0.55, "resistanceHigh": 0.64, "cadenceRpmLow": 90, "cadenceRpmHigh": 100, "position": "seated flat, hard aerobic effort"}},
  "animationId": "sp_4x4"
}
```

```json
{
  "id": "spin_snap_sprint", "name": "Sprint snaps (6 seconds on, 9 off)", "category": "SPIN", "kind": "INTERVAL_SEGMENT", "evidenceTier": "MODERATE",
  "references": [
    {"title": "Comparing Time Efficiency of Sprint vs. High-Intensity Interval Training in Reducing Abdominal Visceral Fat in Obese Young Women: A Randomized, Controlled Trial", "authors": "Zhang H, Tong TK, Kong Z, Shi Q, Liu Y, Nie J", "year": 2018, "journal": "Frontiers in Physiology 9:1048", "doiOrPmid": "10.3389/fphys.2018.01048", "finding": "Twelve weeks of very short all-out sprint intervals reduced abdominal visceral fat comparably to longer HIIT in about half the training time."},
    {"title": "The Effect of Low-Volume High-Intensity Interval Training on Body Composition and Cardiorespiratory Fitness", "authors": "Sultana RN, Sabag A, Keating SE, Johnson NA", "year": 2019, "journal": "Sports Medicine 49(11):1687-1721", "doiOrPmid": "10.1007/s40279-019-01167-w", "finding": "Honest caveat: very-low-volume sprint work improves fitness but is not a shortcut for fat loss without adequate weekly volume."}
  ],
  "summary": "A block of six-second all-out snaps with nine easy seconds between — coached by rhythm inside one continuous block, because the cycle is too quick for step-by-step announcements.",
  "voiceName": "Sprint snaps",
  "voiceHowTo": "Keep a meaningful gear on and find the rhythm: six seconds snapping the pedals as hard as you can, then nine seconds soft. Count it like a wave — snap, two, three, four, five, six, then breathe and float. Hold that wave for the whole block; the beeps will bring you home.",
  "voiceFormCues": ["Six on, nine off — ride the wave.", "Hips stay glued to the saddle.", "Every snap starts hard, never sloppy."],
  "defaultWorkSec": 180, "defaultRestSec": 0, "minSec": 60, "maxSec": 240,
  "met": 8.8, "intensity": "VERY_HIGH", "impact": "LOW",
  "targets": ["VISCERAL_FAT", "CARDIO"], "contraindications": ["KNEE"],
  "machine": {"spin": {"resistanceLow": 0.45, "resistanceHigh": 0.55, "cadenceRpmLow": 80, "cadenceRpmHigh": 110, "position": "seated sprint snaps"}},
  "animationId": "sp_snap"
}
```

```json
{
  "id": "spin_102030_block", "name": "10-20-30 block (5 minutes)", "category": "SPIN", "kind": "INTERVAL_SEGMENT", "evidenceTier": "STRONG",
  "references": [
    {"title": "The 10-20-30 training concept improves performance and health profile in moderately trained runners", "authors": "Gunnarsson TP, Bangsbo J", "year": 2012, "journal": "Journal of Applied Physiology 113(1):16-24", "doiOrPmid": "PMID 22556401", "finding": "Five-minute blocks of repeated 30 s easy / 20 s moderate / 10 s near-maximal work improved VO2max and performance despite reduced total training volume."},
    {"title": "Aerobic high-intensity intervals improve VO2max more than moderate training", "authors": "Helgerud J, Hoydal K, Wang E, et al.", "year": 2007, "journal": "Medicine & Science in Sports & Exercise 39(4):665-671", "doiOrPmid": "PMID 17414804", "finding": "High-intensity interval work produces superior aerobic fitness gains compared with work-matched moderate training."}
  ],
  "summary": "Five one-minute waves: thirty seconds easy, twenty seconds moderate, ten seconds flat out — coached by rhythm inside one block.",
  "voiceName": "Ten twenty thirty block",
  "voiceHowTo": "Each minute is one wave: thirty seconds easy spinning, twenty seconds at a strong working pace, then ten seconds absolutely flying. Restart the wave at the top of every minute — five waves make the block. Keep enough gear on that the ten-second surge has real bite.",
  "voiceFormCues": ["Thirty easy, twenty strong, ten flying.", "New wave every minute.", "The easy thirty is real recovery — use it."],
  "defaultWorkSec": 300, "defaultRestSec": 0, "minSec": 60, "maxSec": 600,
  "met": 8.0, "intensity": "HIGH", "impact": "LOW",
  "targets": ["VISCERAL_FAT", "CARDIO"], "contraindications": ["KNEE"],
  "machine": {"spin": {"resistanceLow": 0.36, "resistanceHigh": 0.55, "cadenceRpmLow": 85, "cadenceRpmHigh": 115, "position": "seated flat, one-minute waves"}},
  "animationId": "sp_102030"
}
```

*(Design decision recorded in the JSON summary/voiceHowTo and in Step 5: the 30/20/10 sub-steps are voice micro-cues inside one 5-minute segment, not 10–30 s routine steps, because per-step TTS announcements + the 10 s validator floor make sub-minute steps unusable, and the service's halfway cue plus interval beeps already mark the block midpoint.)*

```json
{
  "id": "spin_standing_sprint", "name": "Standing sprint (short)", "category": "SPIN", "kind": "INTERVAL_SEGMENT", "evidenceTier": "LIMITED",
  "popularityNote": "A class showstopper, but official Spinning guidance keeps all-out sprints seated: standing at maximal cadence adds sway and knee load for no proven benefit. If you love it, keep it short, keep real resistance on, and cap the cadence — the seated sprint is the evidence-based tool.",
  "references": [
    {"title": "Exercise Intensity During Indoor Cycling (Intensidad de ejercicio en ciclismo indoor)", "authors": "Barbado C, Foster C, Vicente-Campos D, Lopez-Chicharro J", "year": 2017, "journal": "Revista Internacional de Medicina y Ciencias de la Actividad Fisica y del Deporte 17(67)", "doiOrPmid": "10.15366/rimcafd2017.67.004", "finding": "Indoor cycling classes already run above 90% HRmax for long stretches; adding standing maximal sprints pushes intensity beyond what recovery structures support."}
  ],
  "summary": "Out-of-the-saddle all-out effort. Popular, but no direct evidence over the seated sprint — capped hard for control (see the evidence note).",
  "voiceName": "Standing sprint",
  "voiceHowTo": "Load real resistance first, then rise and drive the pedals hard with your weight centred and core braced. Keep the cadence under about one hundred and five — control beats chaos out of the saddle. Twenty seconds is the ceiling, then sit and spin soft.",
  "voiceFormCues": ["Resistance before you rise.", "Core braced, no swinging bars.", "Twenty seconds, then sit."],
  "defaultWorkSec": 20, "defaultRestSec": 0, "minSec": 10, "maxSec": 20,
  "met": 10.3, "intensity": "VERY_HIGH", "impact": "MODERATE",
  "targets": ["CARDIO"], "contraindications": ["KNEE"],
  "machine": {"spin": {"resistanceLow": 0.64, "resistanceHigh": 0.73, "cadenceRpmLow": 95, "cadenceRpmHigh": 105, "position": "standing sprint"}},
  "animationId": "sp_standing_sprint"
}
```
*(D-11 compliance: cadence caps ≤ 110, duration cap 20 s < 30 s sprint ceiling.)*

### 4.2 ELLIPTICAL (3 new)

```json
{
  "id": "ell_4x4_push", "name": "4x4 hard push (4 minutes)", "category": "ELLIPTICAL", "kind": "INTERVAL_SEGMENT", "evidenceTier": "STRONG",
  "references": [
    {"title": "Aerobic high-intensity intervals improve VO2max more than moderate training", "authors": "Helgerud J, Hoydal K, Wang E, et al.", "year": 2007, "journal": "Medicine & Science in Sports & Exercise 39(4):665-671", "doiOrPmid": "PMID 17414804", "finding": "4 x 4 minutes at 90-95% HRmax with 3-minute recoveries is the benchmark protocol for improving VO2max."},
    {"title": "Comparison of energy expenditure on a treadmill vs. an elliptical device at a self-selected exercise intensity", "authors": "Brown GA, Cook CM, Krueger RD, Heelan KA", "year": 2010, "journal": "Journal of Strength and Conditioning Research 24(6):1643-1649", "doiOrPmid": "10.1519/JSC.0b013e3181cb2854", "finding": "The elliptical matches treadmill energy expenditure at matched effort, making it a legitimate low-impact platform for the 4x4 protocol; heart rate reads about 19 bpm high, so go by feel."}
  ],
  "summary": "The Norwegian 4x4's hard four minutes, translated to the elliptical: high resistance, fast purposeful stride, effort climbing to nine out of ten.",
  "voiceName": "Four minute hard push",
  "voiceHowTo": "Take the level high and drive — fast stride, strong pull and push through the handles, building from eight to nine out of ten across the four minutes. Talking should be impossible; collapsing should not. Trust feel over the heart rate readout, which runs high on this machine.",
  "voiceFormCues": ["Build to nine out of ten.", "Tall posture to the final second.", "Go by feel, not the readout."],
  "defaultWorkSec": 240, "defaultRestSec": 0, "minSec": 120, "maxSec": 300,
  "met": 8.0, "intensity": "VERY_HIGH", "impact": "LOW",
  "targets": ["VISCERAL_FAT", "CARDIO"], "contraindications": ["KNEE"],
  "machine": {"elliptical": {"resistanceLow": 0.69, "direction": "FORWARD", "arms": "drive hard through the handles"}},
  "animationId": "el_4x4"
}
```

```json
{
  "id": "ell_incline_climb", "name": "Incline climb", "category": "ELLIPTICAL", "kind": "INTERVAL_SEGMENT", "evidenceTier": "MODERATE",
  "references": [
    {"title": "Comparison of energy expenditure on a treadmill vs. an elliptical device at a self-selected exercise intensity", "authors": "Brown GA, Cook CM, Krueger RD, Heelan KA", "year": 2010, "journal": "Journal of Strength and Conditioning Research 24(6):1643-1649", "doiOrPmid": "10.1519/JSC.0b013e3181cb2854", "finding": "Elliptical work matches treadmill energy cost at matched effort; raising the ramp angle is one of the machine's three intensity levers, and EMG work shows gluteal activity tracks incline."}
  ],
  "summary": "Raise the pedal angle and climb: the VG50BS's ramp shifts the work into the glutes and hamstrings at a steady moderate effort.",
  "voiceName": "Incline climb",
  "voiceHowTo": "Set the pedal angle high so every stride feels like walking up a hill. Keep the effort moderate and rhythmic, driving through the heels and letting the glutes lead. Stand tall — resist the urge to lean on the console as the hill bites.",
  "voiceFormCues": ["Drive through the heels.", "Glutes lead the climb.", "Tall spine, no console leaning."],
  "defaultWorkSec": 180, "defaultRestSec": 0, "minSec": 60, "maxSec": 480,
  "met": 6.5, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["VISCERAL_FAT", "CARDIO", "STRENGTH"], "contraindications": ["KNEE"],
  "machine": {"elliptical": {"resistanceLow": 0.56, "direction": "FORWARD", "arms": "steady drive through the handles", "incline": "pedal angle raised high"}},
  "animationId": "el_incline"
}
```
*(Uses the new `incline` field from Step 2.2.)*

```json
{
  "id": "ell_reverse_sprint", "name": "Reverse sprint", "category": "ELLIPTICAL", "kind": "INTERVAL_SEGMENT", "evidenceTier": "LIMITED",
  "popularityNote": "Fast backwards striding looks great on social media and is sold as a hamstring builder. The only EMG evidence shows backwards striding mainly raises quad activity — the hamstring claim is unverified vendor copy — and speed in reverse adds a real slip-and-stumble risk. If you enjoy it, keep it short with hands on the static rail.",
  "references": [
    {"title": "Comparison of energy expenditure on a treadmill vs. an elliptical device at a self-selected exercise intensity", "authors": "Brown GA, Cook CM, Krueger RD, Heelan KA", "year": 2010, "journal": "Journal of Strength and Conditioning Research 24(6):1643-1649", "doiOrPmid": "10.1519/JSC.0b013e3181cb2854", "finding": "Elliptical work is energetically equivalent to the treadmill; direction changes vary the stimulus, with EMG showing backwards striding raises quad — not hamstring — activity."}
  ],
  "summary": "A short fast burst striding backwards. Quad-dominant in reality, whatever the marketing says (see the evidence note).",
  "voiceName": "Reverse sprint",
  "voiceHowTo": "Slow right down, reverse the stride, and only then build speed with your hands firmly on the static rail. Push the pace for the short interval while sitting slightly back into your hips. If the rhythm ever wobbles, slow down first and reset.",
  "voiceFormCues": ["Hands stay on the static rail.", "Sit back into the hips.", "Wobble means slow down."],
  "defaultWorkSec": 30, "defaultRestSec": 0, "minSec": 15, "maxSec": 60,
  "met": 7.5, "intensity": "HIGH", "impact": "LOW",
  "targets": ["CARDIO"], "contraindications": ["KNEE", "ANKLE"],
  "machine": {"elliptical": {"resistanceLow": 0.44, "direction": "REVERSE", "arms": "hands on the static rail at all times"}},
  "animationId": "el_reverse_fast"
}
```

### 4.3 FLOOR — strong/moderate (10 new)

```json
{
  "id": "floor_squat_thrust", "name": "Squat thrust (no jump)", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "STRONG",
  "references": [
    {"title": "Effect of High-Intensity Interval Training on Total, Abdominal and Visceral Fat Mass: A Meta-Analysis", "authors": "Maillard F, Pereira B, Boisseau N", "year": 2018, "journal": "Sports Medicine 48(2):269-288", "doiOrPmid": "10.1007/s40279-017-0807-y", "finding": "Whole-body weight-bearing intervals were the most effective HIIT mode for visceral fat loss; the squat thrust delivers the burpee pattern without the jump."},
    {"title": "Effect of exercise intervention dosage on reducing visceral adipose tissue: a systematic review and network meta-analysis", "authors": "Chang YH, Yang HY, Shun SC", "year": 2021, "journal": "International Journal of Obesity 45(5):982-997", "doiOrPmid": "10.1038/s41366-021-00767-9", "finding": "HIIT produced the largest visceral fat reductions of any exercise mode (SMD -0.39)."}
  ],
  "summary": "The burpee's smarter sibling: squat, kick back to a plank, return and stand — all the conditioning, none of the jump-landing impact.",
  "voiceName": "Squat thrusts",
  "voiceHowTo": "Squat down and plant your hands, then step or hop your feet back to a strong plank. Bring them back in and simply stand tall — no jump at the top. Keep a rhythm you could hold for the whole interval.",
  "voiceFormCues": ["Strong plank, no sag.", "Stand tall, no jump.", "Rhythm over rush."],
  "defaultWorkSec": 30, "defaultRestSec": 20, "minSec": 15, "maxSec": 60,
  "met": 7.0, "intensity": "HIGH", "impact": "MODERATE",
  "targets": ["VISCERAL_FAT", "CARDIO", "STRENGTH"], "contraindications": ["WRIST", "KNEE"],
  "animationId": "fl_squat_thrust"
}
```

```json
{
  "id": "floor_shadow_boxing", "name": "Shadow boxing", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "2024 Adult Compendium of Physical Activities: A third update of the energy costs of human activities", "authors": "Herrmann SD, Willis EA, Ainsworth BE, et al.", "year": 2024, "journal": "Journal of Sport and Health Science 13(1):6-12", "doiOrPmid": "10.1016/j.jshs.2023.10.010", "finding": "Boxing-style shadow work is catalogued at ~7.8 METs — genuinely vigorous aerobic exercise with no impact on the joints."},
    {"title": "World Health Organization 2020 guidelines on physical activity and sedentary behaviour", "authors": "Bull FC, Al-Ansari SS, Biddle S, et al.", "year": 2020, "journal": "British Journal of Sports Medicine 54(24):1451-1462", "doiOrPmid": "10.1136/bjsports-2020-102955", "finding": "Vigorous-intensity aerobic activity counts double toward the weekly activity target."}
  ],
  "summary": "Vigorous cardio with zero impact: jabs, crosses and footwork shifts thrown at the air with intent.",
  "voiceName": "Shadow boxing",
  "voiceHowTo": "Take a staggered stance with soft knees and hands guarding your chin. Throw alternating punches — jab, cross — rotating through the trunk and snapping each arm back to guard. Keep the feet lively and shift your weight with every combination.",
  "voiceFormCues": ["Punch and snap back to guard.", "Rotate from the trunk.", "Light lively feet."],
  "defaultWorkSec": 40, "defaultRestSec": 20, "minSec": 20, "maxSec": 90,
  "met": 7.8, "intensity": "HIGH", "impact": "LOW",
  "targets": ["VISCERAL_FAT", "CARDIO"], "contraindications": ["SHOULDER"],
  "animationId": "fl_shadowbox"
}
```

```json
{
  "id": "floor_split_squat", "name": "Split squat", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "ACSM Position Stand: Progression models in resistance training for healthy adults", "authors": "American College of Sports Medicine (Ratamess NA et al.)", "year": 2009, "journal": "Medicine & Science in Sports & Exercise 41(3):687-708", "doiOrPmid": "10.1249/MSS.0b013e3181915670", "finding": "Unilateral multi-joint lower-body work builds strength and balance; the static split stance removes the stepping demand of the lunge."}
  ],
  "summary": "A lunge that stays put: feet fixed in a stagger, straight down and up — easier to balance, just as strong.",
  "voiceName": "Split squats",
  "voiceHowTo": "Take a long staggered stance and stay there. Lower straight down until both knees are near ninety degrees, back knee hovering, then drive up through the front heel. Do one side for the first half, then swap legs at the halfway cue.",
  "voiceFormCues": ["Straight down, not forward.", "Front heel heavy.", "Swap legs at halfway."],
  "defaultWorkSec": 40, "defaultRestSec": 20, "minSec": 20, "maxSec": 90,
  "met": 3.8, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["STRENGTH", "BALANCE"], "contraindications": ["KNEE"],
  "animationId": "fl_split_squat"
}
```

```json
{
  "id": "floor_single_leg_bridge", "name": "Single-leg glute bridge", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "Which exercises target the gluteal muscles while minimizing activation of the tensor fascia lata? Electromyographic assessment using fine-wire electrodes", "authors": "Selkowitz DM, Beneck GJ, Powers CM", "year": 2013, "journal": "Journal of Orthopaedic & Sports Physical Therapy 43(2):54-64", "doiOrPmid": "10.2519/jospt.2013.4116", "finding": "Bridging is among the most selective gluteal activators; the single-leg version roughly doubles the load on the working hip."},
    {"title": "ACSM Position Stand: Progression models in resistance training for healthy adults", "authors": "American College of Sports Medicine (Ratamess NA et al.)", "year": 2009, "journal": "Medicine & Science in Sports & Exercise 41(3):687-708", "doiOrPmid": "10.1249/MSS.0b013e3181915670", "finding": "Progressing from bilateral to unilateral loading is the standard bodyweight overload strategy."}
  ],
  "summary": "The glute bridge's progression: one leg reaches long while the other does all the lifting.",
  "voiceName": "Single-leg bridge",
  "voiceHowTo": "Set up for a glute bridge, then extend one leg so the thighs stay level. Drive through the planted heel and lift the hips until your body lines up from shoulders to knee, keeping the hips dead level. Lower with control, and swap legs at the halfway cue.",
  "voiceFormCues": ["Hips stay level — no tipping.", "Thighs in line at the top.", "Swap legs at halfway."],
  "defaultWorkSec": 40, "defaultRestSec": 15, "minSec": 20, "maxSec": 90,
  "met": 3.0, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["STRENGTH", "CORE"], "contraindications": ["HIP"],
  "animationId": "fl_bridge_single"
}
```

```json
{
  "id": "floor_single_leg_rdl", "name": "Single-leg Romanian deadlift", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "ACSM Position Stand: Progression models in resistance training for healthy adults", "authors": "American College of Sports Medicine (Ratamess NA et al.)", "year": 2009, "journal": "Medicine & Science in Sports & Exercise 41(3):687-708", "doiOrPmid": "10.1249/MSS.0b013e3181915670", "finding": "Unilateral hip-hinge patterns build posterior-chain strength and balance with bodyweight loading appropriate for novices."}
  ],
  "summary": "The hip hinge on one leg: hamstrings, glutes and a serious balance challenge in one slow move.",
  "voiceName": "Single-leg deadlifts",
  "voiceHowTo": "Stand on one leg with a soft knee and hinge from the hips, letting the free leg sweep back as your flat chest tips forward. Reach toward the ground until the standing hamstring tightens, then drive the hips forward to stand tall. Slow is the whole point — swap legs at the halfway cue.",
  "voiceFormCues": ["Hips square to the floor.", "Flat back, long line to the heel.", "Swap legs at halfway."],
  "defaultWorkSec": 40, "defaultRestSec": 15, "minSec": 20, "maxSec": 90,
  "met": 3.5, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["STRENGTH", "BALANCE"], "contraindications": ["LOWER_BACK"],
  "animationId": "fl_single_leg_rdl"
}
```

```json
{
  "id": "floor_calf_raise", "name": "Calf raise", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "ACSM Position Stand: Progression models in resistance training for healthy adults", "authors": "American College of Sports Medicine (Ratamess NA et al.)", "year": 2009, "journal": "Medicine & Science in Sports & Exercise 41(3):687-708", "doiOrPmid": "10.1249/MSS.0b013e3181915670", "finding": "Balanced resistance programs include plantar-flexor work; the standing calf raise is the standard bodyweight option and supports ankle stability."}
  ],
  "summary": "Slow heel raises for calf strength and ankle control — rise fast-ish, lower slow.",
  "voiceName": "Calf raises",
  "voiceHowTo": "Stand tall with feet hip width apart, near a wall if balance is shaky. Rise up onto the balls of your feet as high as you can, pause, then lower your heels slowly over three counts. Keep the ankles tracking straight — no rolling out.",
  "voiceFormCues": ["Rise high, pause at the top.", "Three slow counts down.", "Ankles track straight."],
  "defaultWorkSec": 40, "defaultRestSec": 15, "minSec": 20, "maxSec": 90,
  "met": 3.0, "intensity": "LOW", "impact": "LOW",
  "targets": ["STRENGTH", "BALANCE"], "contraindications": ["ANKLE"],
  "animationId": "fl_calf_raise"
}
```

```json
{
  "id": "floor_pike_pushup", "name": "Pike push-up", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "The effects of a calisthenics training intervention on posture, strength and body composition", "authors": "Thomas E, Bianco A, Mancuso EP, Patti A, Tabacchi G, Paoli A, Messina G, Palma A", "year": 2017, "journal": "Isokinetics and Exercise Science 25(3):215-222", "doiOrPmid": "10.3233/IES-170001", "finding": "Progressive calisthenics pressing variations improved strength in untrained men; the pike shifts the push-up's load onto the shoulders."},
    {"title": "ACSM Position Stand: Progression models in resistance training for healthy adults", "authors": "American College of Sports Medicine (Ratamess NA et al.)", "year": 2009, "journal": "Medicine & Science in Sports & Exercise 41(3):687-708", "doiOrPmid": "10.1249/MSS.0b013e3181915670", "finding": "Changing body angle is a recognised progression lever for bodyweight pressing."}
  ],
  "summary": "A push-up folded into a pike: hips high, head lowering between the hands — the bodyweight shoulder press.",
  "voiceName": "Pike push-ups",
  "voiceHowTo": "From a plank, walk your feet in and lift the hips high into an upside-down V. Bend the elbows to lower the top of your head toward the floor between your hands, then press back up until the arms are straight. Keep the hips high the whole time — the legs are just scaffolding.",
  "voiceFormCues": ["Hips stay high.", "Head travels between the hands.", "Press the floor away."],
  "defaultWorkSec": 30, "defaultRestSec": 20, "minSec": 15, "maxSec": 60,
  "met": 4.3, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["STRENGTH"], "contraindications": ["WRIST", "SHOULDER", "NECK"],
  "animationId": "fl_pike_pushup"
}
```

```json
{
  "id": "floor_wall_sit_block", "name": "Wall sit — pressure block", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "STRONG",
  "references": [
    {"title": "Exercise training and resting blood pressure: a large-scale pairwise and network meta-analysis of randomised controlled trials", "authors": "Edwards JJ, Deenmamode AHP, Griffiths M, et al.", "year": 2023, "journal": "British Journal of Sports Medicine 57(20):1317-1326", "doiOrPmid": "PMID 37491419", "finding": "Across 270 RCTs, isometric exercise — wall squats in particular — was the single most effective training mode for lowering resting blood pressure."}
  ],
  "summary": "Long timed wall-sit holds, the isometric protocol with the best blood-pressure evidence of any exercise mode.",
  "voiceName": "Wall sit pressure block",
  "voiceHowTo": "Slide down the wall until your thighs are near parallel and settle in for a long hold — around two minutes is the researched dose. Breathe steadily the whole time and never hold your breath. If the burn peaks early, slide a little higher rather than standing up.",
  "voiceFormCues": ["Breathe — never strain and hold.", "Back flat on the wall.", "Slide higher before you bail."],
  "defaultWorkSec": 120, "defaultRestSec": 60, "minSec": 60, "maxSec": 180,
  "met": 3.3, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["STRENGTH"], "contraindications": ["KNEE"],
  "animationId": "fl_wallsit"
}
```

```json
{
  "id": "floor_copenhagen_plank", "name": "Copenhagen plank (short lever)", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "STRONG",
  "references": [
    {"title": "The Adductor Strengthening Programme prevents groin problems among male football players: a cluster-randomised controlled trial", "authors": "Haroy J, Clarsen B, Wiger EG, et al.", "year": 2019, "journal": "British Journal of Sports Medicine 53(3):150-157", "doiOrPmid": "PMID 29891614", "finding": "A single-exercise programme built on the Copenhagen adduction plank reduced groin problems by 41% across 660 footballers."}
  ],
  "summary": "Side plank with the top leg on a raised support: the inner-thigh strengthener with genuine injury-prevention evidence.",
  "voiceName": "Copenhagen plank",
  "voiceHowTo": "Lie on your side with your elbow under your shoulder and the top knee and shin resting on a low bench or sturdy chair. Lift your hips until your body forms a straight line, letting the top inner thigh do the holding. Keep the holds short and sharp, and swap sides at the halfway cue.",
  "voiceFormCues": ["Inner thigh does the holding.", "Short sharp holds beat long grinds.", "Swap sides at halfway."],
  "defaultWorkSec": 20, "defaultRestSec": 20, "minSec": 10, "maxSec": 45,
  "met": 3.5, "intensity": "HIGH", "impact": "LOW",
  "targets": ["STRENGTH", "CORE"], "contraindications": ["HIP", "SHOULDER"],
  "animationId": "fl_copenhagen"
}
```

```json
{
  "id": "floor_balance_stand", "name": "Single-leg balance block", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "STRONG",
  "references": [
    {"title": "Exercise for preventing falls in older people living in the community", "authors": "Sherrington C, Fairhall NJ, Wallbank GK, et al.", "year": 2019, "journal": "Cochrane Database of Systematic Reviews 2019(1):CD012424", "doiOrPmid": "PMID 30703272", "finding": "High-certainty evidence: exercise programmes emphasising balance and functional training reduce the rate of falls by about a quarter."}
  ],
  "summary": "Deliberate single-leg standing holds — the unglamorous drill with the strongest fall-prevention evidence in the library.",
  "voiceName": "Balance holds",
  "voiceHowTo": "Stand tall and float one knee up to hip height, finding a still point ahead of you with your eyes. Hold steady, arms out if you need them, then change legs at each cue. To progress, hover your hands over your hips or try slow head turns.",
  "voiceFormCues": ["Eyes on one still point.", "Tall through the crown.", "Wobbling is the workout."],
  "defaultWorkSec": 40, "defaultRestSec": 15, "minSec": 20, "maxSec": 90,
  "met": 2.5, "intensity": "LOW", "impact": "LOW",
  "targets": ["BALANCE"], "contraindications": [],
  "animationId": "fl_balance"
}
```

### 4.4 FLOOR — LIMITED tier (6 new; every one has an honest `popularityNote`)

```json
{
  "id": "floor_bicycle_crunch", "name": "Bicycle crunch", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "LIMITED",
  "popularityNote": "Regularly crowned 'the best ab exercise' from an old electromyography test, and the activation is real — but like every ab move, it does not burn belly fat, and sloppy fast reps mostly yank the neck. Slow, rotating quality beats speed.",
  "references": [
    {"title": "The effect of abdominal exercise on abdominal fat", "authors": "Vispute SS, Smith JD, LeCheminant JD, Hurley KS", "year": 2011, "journal": "Journal of Strength and Conditioning Research 25(9):2559-2564", "doiOrPmid": "PMID 21804427", "finding": "Six weeks of daily abdominal exercise produced no reduction in abdominal fat versus control, despite improved endurance."}
  ],
  "summary": "Alternating elbow-to-knee crunches with a pedalling motion. Strong oblique work; zero belly-fat magic (see note).",
  "voiceName": "Bicycle crunches",
  "voiceHowTo": "Lie back with hands lightly behind your head and legs in tabletop. Draw one knee in while the opposite shoulder rotates toward it, as the other leg reaches long and low. Pedal slowly from side to side, exhaling on every twist and never pulling on the neck.",
  "voiceFormCues": ["Rotate the shoulder, not the elbow.", "Slow pedalling, long reach.", "Never yank the neck."],
  "defaultWorkSec": 30, "defaultRestSec": 15, "minSec": 15, "maxSec": 60,
  "met": 3.5, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["CORE"], "contraindications": ["NECK", "LOWER_BACK"],
  "animationId": "fl_bicycle"
}
```

```json
{
  "id": "floor_hollow_hold", "name": "Hollow body hold", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "LIMITED",
  "popularityNote": "A gymnastics staple that fitness media treats as a core rite of passage. Trunk stiffness demands are genuinely high, but there is no direct trial evidence beyond general core work, and the shape presses a tired lower back into overload quickly — bend the knees or raise the legs the moment the back arches.",
  "references": [
    {"title": "The effect of abdominal exercise on abdominal fat", "authors": "Vispute SS, Smith JD, LeCheminant JD, Hurley KS", "year": 2011, "journal": "Journal of Strength and Conditioning Research 25(9):2559-2564", "doiOrPmid": "PMID 21804427", "finding": "Abdominal training improves trunk endurance but does not reduce abdominal fat — the honest frame for every ab hold."}
  ],
  "summary": "Banana-shaped isometric: lower back pressed down, shoulders and legs hovering. Hard, honest core endurance (see note).",
  "voiceName": "Hollow hold",
  "voiceHowTo": "Lie on your back and press the lower back firmly into the mat. Float your shoulders and legs a few centimetres off the floor, arms reaching past your hips or overhead, and hold the shallow banana shape. The instant your lower back lifts, bend the knees or raise the legs higher.",
  "voiceFormCues": ["Lower back stays glued down.", "Shallow banana, long lines.", "Scale up the legs before you arch."],
  "defaultWorkSec": 25, "defaultRestSec": 20, "minSec": 10, "maxSec": 60,
  "met": 3.0, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["CORE"], "contraindications": ["LOWER_BACK", "NECK"],
  "animationId": "fl_hollow"
}
```

```json
{
  "id": "floor_flutter_kicks", "name": "Flutter kicks", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "LIMITED",
  "popularityNote": "A boot-camp favourite sold as a 'lower ab shredder'. There are no lower abs to isolate and no fat to spot-burn; what flutter kicks really train is hip-flexor endurance with the abs bracing. Fine in small doses if your back stays flat on the mat.",
  "references": [
    {"title": "The effect of abdominal exercise on abdominal fat", "authors": "Vispute SS, Smith JD, LeCheminant JD, Hurley KS", "year": 2011, "journal": "Journal of Strength and Conditioning Research 25(9):2559-2564", "doiOrPmid": "PMID 21804427", "finding": "Daily abdominal exercise produced no abdominal fat loss versus control — spot reduction does not happen."}
  ],
  "summary": "Small alternating leg kicks from a hollow-ish base. Hip-flexor endurance, not a fat burner (see note).",
  "voiceName": "Flutter kicks",
  "voiceHowTo": "Lie back with your lower back pressed into the mat and legs long, hovering just off the floor. Kick the legs alternately in small quick beats, keeping the knees long and the trunk braced. Raise the legs higher any time the lower back starts to peel up.",
  "voiceFormCues": ["Small quick beats.", "Back glued down.", "Higher legs if the back lifts."],
  "defaultWorkSec": 25, "defaultRestSec": 15, "minSec": 15, "maxSec": 60,
  "met": 3.0, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["CORE"], "contraindications": ["LOWER_BACK", "NECK"],
  "animationId": "fl_flutter"
}
```

```json
{
  "id": "floor_side_leg_raise", "name": "Side-lying leg raise", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "LIMITED",
  "popularityNote": "Marketed for decades as an 'outer-thigh toner'. Localised training does not strip fat from the trained area — trials show the opposite — but the movement is still a reasonable light hip-abductor drill; the clamshell targets the same muscles with better evidence behind it.",
  "references": [
    {"title": "Regional fat changes induced by localized muscle endurance resistance training", "authors": "Ramirez-Campillo R, Andrade DC, Campos-Jara C, Henriquez-Olguin C, Alvarez-Lepin C, Izquierdo M", "year": 2013, "journal": "Journal of Strength and Conditioning Research 27(8):2219-2224", "doiOrPmid": "10.1519/JSC.0b013e31827e8681", "finding": "Twelve weeks of high-volume localized training reduced fat everywhere except the trained region — direct evidence against spot reduction."}
  ],
  "summary": "Side-lying straight-leg lifts. Light hip work; not an outer-thigh fat burner (see note).",
  "voiceName": "Side leg raises",
  "voiceHowTo": "Lie on your side in one straight line, head resting on your arm. Float the top leg up about forty five degrees with the kneecap facing forward, then lower it slowly without letting the hips roll back. Swap sides at the halfway cue.",
  "voiceFormCues": ["Kneecap faces forward.", "Hips stacked, no rolling.", "Swap sides at halfway."],
  "defaultWorkSec": 40, "defaultRestSec": 15, "minSec": 20, "maxSec": 90,
  "met": 2.5, "intensity": "LOW", "impact": "LOW",
  "targets": ["STRENGTH", "BALANCE"], "contraindications": ["HIP"],
  "animationId": "fl_side_leg_raise"
}
```

```json
{
  "id": "floor_donkey_kick", "name": "Donkey kick", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "LIMITED",
  "popularityNote": "A social-media glute favourite. It does work the glutes lightly, but fine-wire EMG studies rank bridges and clams higher for selective glute activation, and enthusiastic kicking usually turns into lower-back extension. Keep the kick small and the spine quiet.",
  "references": [
    {"title": "Which exercises target the gluteal muscles while minimizing activation of the tensor fascia lata? Electromyographic assessment using fine-wire electrodes", "authors": "Selkowitz DM, Beneck GJ, Powers CM", "year": 2013, "journal": "Journal of Orthopaedic & Sports Physical Therapy 43(2):54-64", "doiOrPmid": "10.2519/jospt.2013.4116", "finding": "Bridging and clam variations activated the gluteals more selectively than kicking patterns in fine-wire EMG comparisons."}
  ],
  "summary": "Quadruped heel drives to the ceiling. Light glute work with better-evidenced alternatives (see note).",
  "voiceName": "Donkey kicks",
  "voiceHowTo": "Start on hands and knees with a level back. Keeping the knee bent at ninety degrees, drive one heel toward the ceiling only as far as the hips stay square and the back stays quiet. Lower with control and keep a steady rhythm, then swap legs at the halfway cue.",
  "voiceFormCues": ["Heel to the ceiling, spine quiet.", "Small kick, square hips.", "Swap legs at halfway."],
  "defaultWorkSec": 40, "defaultRestSec": 15, "minSec": 20, "maxSec": 90,
  "met": 2.8, "intensity": "LOW", "impact": "LOW",
  "targets": ["STRENGTH"], "contraindications": ["WRIST", "KNEE"],
  "animationId": "fl_donkey_kick"
}
```

```json
{
  "id": "floor_soleus_pushup", "name": "Soleus push-up (seated)", "category": "FLOOR", "kind": "DISCRETE", "evidenceTier": "LIMITED",
  "popularityNote": "Went viral as 'the most important exercise you've never heard of'. The single study behind it is real and clever — seated calf raises kept blood sugar noticeably flatter after meals — but it is one lab's metabolic finding, not a fitness or fat-loss tool. Treat it as a desk habit, not a workout.",
  "references": [
    {"title": "A potent physiological method to magnify and sustain soleus oxidative metabolism improves glucose homeostasis", "authors": "Hamilton MT, Hamilton DG, Zderic TW", "year": 2022, "journal": "iScience 25(9):104869", "doiOrPmid": "PMID 36034224", "finding": "Sustained seated soleus contractions elevated local oxidative metabolism and reduced postprandial glucose excursions and insulin — a metabolic effect, not a fitness or fat-loss one."}
  ],
  "summary": "Seated heel raises that keep the soleus ticking over. Interesting metabolism science; not a workout (see note).",
  "voiceName": "Soleus push-ups",
  "voiceHowTo": "Sit tall on a chair or bench with feet flat and knees at ninety degrees. Raise your heels as high as they will go while the toes stay planted, then lower them softly and repeat in a relaxed rhythm. This should feel easy — the point is to keep the calf muscle quietly working, not to burn.",
  "voiceFormCues": ["Toes planted, heels high.", "Relaxed steady rhythm.", "Easy effort is correct."],
  "defaultWorkSec": 60, "defaultRestSec": 10, "minSec": 30, "maxSec": 180,
  "met": 1.8, "intensity": "LOW", "impact": "LOW",
  "targets": ["STRENGTH"], "contraindications": [],
  "animationId": "fl_soleus"
}
```

### 4.5 BACK (1 new)

```json
{
  "id": "back_bridge_march", "name": "Bridge march", "category": "BACK", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "Motor control exercise for chronic non-specific low-back pain", "authors": "Saragiotto BT, Maher CG, Yamato TP, Costa LOP, Menezes Costa LC, Ostelo RWJG, Macedo LG", "year": 2016, "journal": "Cochrane Database of Systematic Reviews 2016(1):CD012004", "doiOrPmid": "10.1002/14651858.CD012004", "finding": "Motor control progressions that challenge pelvic stability under movement are core ingredients of effective low-back programs."},
    {"title": "Exercise interventions for the treatment of chronic low back pain: a systematic review and meta-analysis of randomised controlled trials", "authors": "Searle A, Spink M, Ho A, Chuter V", "year": 2015, "journal": "Clinical Rehabilitation 29(12):1155-1167", "doiOrPmid": "10.1177/0269215515570379", "finding": "Coordination/stabilisation training is among the most effective exercise types for chronic low back pain."}
  ],
  "summary": "Hold the bridge, march the feet: hip extension plus anti-rotation control — the glute bridge's next chapter.",
  "voiceName": "Bridge march",
  "voiceHowTo": "Lift into your glute bridge and stay there. Keeping the hips perfectly level, float one foot a few centimetres up, set it back down, then float the other — a slow quiet march. If the hips dip or twist, pause and rebuild the bridge before the next step.",
  "voiceFormCues": ["Hips level like a table top.", "Slow quiet steps.", "Rebuild the bridge if it wobbles."],
  "defaultWorkSec": 40, "defaultRestSec": 15, "minSec": 20, "maxSec": 90,
  "met": 3.0, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["CORE", "STRENGTH", "BALANCE"], "contraindications": [],
  "animationId": "bk_bridge_march"
}
```

### 4.6 REFORMER (3 new)

```json
{
  "id": "ref_footwork_single_leg", "name": "Single-leg footwork", "category": "REFORMER", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "A systematic review of the effects of Pilates method of exercise in healthy people", "authors": "Cruz-Ferreira A, Fernandes J, Laranjo L, Bernardo LM, Silva A", "year": 2011, "journal": "Archives of Physical Medicine and Rehabilitation 92(12):2071-2081", "doiOrPmid": "PMID 22030232", "finding": "Pilates builds muscular endurance; single-leg footwork doubles the leg's share of the spring load and exposes side-to-side control differences."},
    {"title": "ACSM Position Stand: Progression models in resistance training for healthy adults", "authors": "American College of Sports Medicine (Ratamess NA et al.)", "year": 2009, "journal": "Medicine & Science in Sports & Exercise 41(3):687-708", "doiOrPmid": "10.1249/MSS.0b013e3181915670", "finding": "Bilateral-to-unilateral progression is the standard overload strategy when external load is fixed."}
  ],
  "summary": "Footwork one leg at a time: lighter springs, double the honesty about left-right differences.",
  "voiceName": "Single-leg footwork",
  "voiceHowTo": "Drop to a lighter spring, centre one heel on the bar and float the other leg to tabletop. Press the carriage out long without locking the knee, then resist it home, keeping the pelvis carved-in-stone still. Swap legs at the halfway cue and notice which side wobbles.",
  "voiceFormCues": ["Pelvis stays carved in stone.", "Knee tracks straight down the rail.", "Swap legs at halfway."],
  "defaultWorkSec": 50, "defaultRestSec": 15, "minSec": 30, "maxSec": 120,
  "met": 3.0, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["STRENGTH", "BALANCE", "CORE"], "contraindications": ["KNEE"],
  "machine": {"reformer": {"springs": "MEDIUM_1", "bodyPosition": "lying supine on the carriage, one heel centred on the bar, other leg in tabletop"}},
  "animationId": "rf_footwork_single"
}
```

```json
{
  "id": "ref_running", "name": "Running (prancing)", "category": "REFORMER", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "A systematic review of the effects of Pilates method of exercise in healthy people", "authors": "Cruz-Ferreira A, Fernandes J, Laranjo L, Bernardo LM, Silva A", "year": 2011, "journal": "Archives of Physical Medicine and Rehabilitation 92(12):2071-2081", "doiOrPmid": "PMID 22030232", "finding": "Rhythmic Pilates endurance sequences build muscular endurance; running on the bar adds calf and ankle articulation to the classic footwork press."}
  ],
  "summary": "Legs long, heels alternately dropping and lifting under the bar — the reformer's gentle jog.",
  "voiceName": "Reformer running",
  "voiceHowTo": "Press the carriage out with the balls of both feet on the bar and legs long. Keeping the carriage still, drop one heel under the bar while the other rises, then swap in a smooth prancing rhythm. Let the knees soften alternately while the pelvis stays perfectly quiet.",
  "voiceFormCues": ["Carriage stays still — only the heels move.", "Smooth prancing rhythm.", "Quiet pelvis, soft knees."],
  "defaultWorkSec": 50, "defaultRestSec": 15, "minSec": 30, "maxSec": 120,
  "met": 3.3, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["STRENGTH", "MOBILITY", "BALANCE"], "contraindications": ["ANKLE"],
  "machine": {"reformer": {"springs": "MEDIUM_2", "bodyPosition": "lying supine, balls of both feet on the bar, legs pressed long"}},
  "animationId": "rf_running"
}
```

```json
{
  "id": "ref_footwork_eccentric", "name": "Footwork — slow lowering", "category": "REFORMER", "kind": "DISCRETE", "evidenceTier": "MODERATE",
  "references": [
    {"title": "The effects of eccentric versus concentric resistance training on muscle strength and mass in healthy adults: a systematic review with meta-analysis", "authors": "Roig M, O'Brien K, Kirk G, Murray R, McKinnon P, Shadgan B, Reid WD", "year": 2009, "journal": "British Journal of Sports Medicine 43(8):556-568", "doiOrPmid": "PMID 18981046", "finding": "Eccentric-emphasis training produced greater gains in strength and muscle mass than concentric training — the rationale for slowing the return phase."},
    {"title": "A systematic review of the effects of Pilates method of exercise in healthy people", "authors": "Cruz-Ferreira A, Fernandes J, Laranjo L, Bernardo LM, Silva A", "year": 2011, "journal": "Archives of Physical Medicine and Rehabilitation 92(12):2071-2081", "doiOrPmid": "PMID 22030232", "finding": "Footwork is the method's foundational leg-endurance series; spring resistance makes the slow return easy to control."}
  ],
  "summary": "Footwork with a four-count return: press out in one, resist home in four — eccentric emphasis on springs.",
  "voiceName": "Slow-lowering footwork",
  "voiceHowTo": "Set up as for heels footwork. Press the carriage out in one smooth count, then take four slow counts to let it home, feeling the legs resist the springs every centimetre. The lowering is the exercise — never let the springs win the race.",
  "voiceFormCues": ["Out in one, home in four.", "Resist every centimetre.", "Springs never win the race."],
  "defaultWorkSec": 50, "defaultRestSec": 15, "minSec": 30, "maxSec": 120,
  "met": 3.0, "intensity": "MODERATE", "impact": "LOW",
  "targets": ["STRENGTH"], "contraindications": ["KNEE"],
  "machine": {"reformer": {"springs": "MEDIUM_2", "bodyPosition": "lying supine on the carriage, heels on the foot bar"}},
  "animationId": "rf_footwork_ecc"
}
```

**Tests / Acceptance / Dependencies (Step 4):** validator passes with Step 2.5 minimums; every `animationId` resolves against the Step 3 registry (validator + `DatabaseValidatorTest.every animation id resolves` both enforce this — confirmed both exist); LIMITED entries excluded from generation by default (existing `pool()` behaviour, no change). Depends on Steps 2–3.

---

## STEP 5 — New named routines (complete JSON, paste into `routines`)

All satisfy the validator (SPIN/ELLIPTICAL category, same-category INTERVAL_SEGMENT steps, each ≥ 10 s, total ≥ 240 s) and the generator's scaling logic. **Fit-guard coverage check** (Phase 1 `[0.5, 2]` ratio guard): after these additions SPIN routine totals span 1020–2700 s → covers blocks 510–5400 s; ELLIPTICAL totals span 1110–2340 s → covers 555–4680 s. Every 10–40 min machine block has at least one in-guard routine per category. ✔

**30/20/10 design decision (recorded):** each 5-minute block is ONE step using `spin_102030_block` with in-cue rhythm coaching — not 60 s steps of three sub-steps — because the service speaks a full announcement per step and the validator floors steps at 10 s; three sub-steps per minute would generate ~45 steps of overlapping speech. Same reasoning applies to the 6 s/9 s SIT block (see 4.1).

```json
{
  "id": "rt_spin_norwegian44", "name": "Norwegian 4x4", "category": "SPIN", "intensity": "HIGH",
  "summary": "The Helgerud protocol: 10-minute ramped warm-up, four times four minutes hard with three-minute easy spins, then a proper cool-down.",
  "steps": [
    {"exerciseId": "spin_warmup_flat", "durationSec": 300},
    {"exerciseId": "spin_seated_flat_steady", "durationSec": 180},
    {"exerciseId": "spin_fast_flat", "durationSec": 60},
    {"exerciseId": "spin_recovery_soft", "durationSec": 60},
    {"exerciseId": "spin_4x4_vo2", "durationSec": 240},
    {"exerciseId": "spin_recovery_soft", "durationSec": 180},
    {"exerciseId": "spin_4x4_vo2", "durationSec": 240},
    {"exerciseId": "spin_recovery_soft", "durationSec": 180},
    {"exerciseId": "spin_4x4_vo2", "durationSec": 240},
    {"exerciseId": "spin_recovery_soft", "durationSec": 180},
    {"exerciseId": "spin_4x4_vo2", "durationSec": 240},
    {"exerciseId": "spin_cooldown_easy", "durationSec": 240}
  ]
}
```
*(Total 2340 s = 39:00.)*

```json
{
  "id": "rt_spin_norwegian44_compact", "name": "Norwegian 4x4 — Compact", "category": "SPIN", "intensity": "HIGH",
  "summary": "Two-rep 4x4 for shorter blocks: ramped warm-up, 2 x 4 minutes hard, cool-down.",
  "steps": [
    {"exerciseId": "spin_warmup_flat", "durationSec": 300},
    {"exerciseId": "spin_seated_flat_steady", "durationSec": 180},
    {"exerciseId": "spin_4x4_vo2", "durationSec": 240},
    {"exerciseId": "spin_recovery_soft", "durationSec": 180},
    {"exerciseId": "spin_4x4_vo2", "durationSec": 240},
    {"exerciseId": "spin_recovery_soft", "durationSec": 60},
    {"exerciseId": "spin_cooldown_easy", "durationSec": 240}
  ]
}
```
*(Total 1440 s = 24:00.)*

```json
{
  "id": "rt_ell_norwegian44", "name": "Norwegian 4x4 Stride", "category": "ELLIPTICAL", "intensity": "HIGH",
  "summary": "The 4x4 protocol on the elliptical: ramped warm-up, four hard four-minute pushes with easy three-minute recoveries.",
  "steps": [
    {"exerciseId": "ell_warmup_easy", "durationSec": 300},
    {"exerciseId": "ell_steady_forward", "durationSec": 240},
    {"exerciseId": "ell_recovery_easy", "durationSec": 60},
    {"exerciseId": "ell_4x4_push", "durationSec": 240},
    {"exerciseId": "ell_recovery_easy", "durationSec": 180},
    {"exerciseId": "ell_4x4_push", "durationSec": 240},
    {"exerciseId": "ell_recovery_easy", "durationSec": 180},
    {"exerciseId": "ell_4x4_push", "durationSec": 240},
    {"exerciseId": "ell_recovery_easy", "durationSec": 180},
    {"exerciseId": "ell_4x4_push", "durationSec": 240},
    {"exerciseId": "ell_cooldown_easy", "durationSec": 240}
  ]
}
```
*(Total 2340 s = 39:00.)*

```json
{
  "id": "rt_ell_norwegian44_compact", "name": "Norwegian 4x4 Stride — Compact", "category": "ELLIPTICAL", "intensity": "HIGH",
  "summary": "Two-rep elliptical 4x4 for shorter blocks.",
  "steps": [
    {"exerciseId": "ell_warmup_easy", "durationSec": 300},
    {"exerciseId": "ell_steady_forward", "durationSec": 180},
    {"exerciseId": "ell_4x4_push", "durationSec": 240},
    {"exerciseId": "ell_recovery_easy", "durationSec": 180},
    {"exerciseId": "ell_4x4_push", "durationSec": 240},
    {"exerciseId": "ell_recovery_easy", "durationSec": 60},
    {"exerciseId": "ell_cooldown_easy", "durationSec": 240}
  ]
}
```
*(Total 1440 s = 24:00.)*

```json
{
  "id": "rt_spin_102030", "name": "10-20-30 Ride", "category": "SPIN", "intensity": "VERY_HIGH",
  "summary": "Three five-minute 10-20-30 blocks — one-minute waves of easy, strong and flying — with two-minute recoveries between blocks.",
  "steps": [
    {"exerciseId": "spin_warmup_flat", "durationSec": 300},
    {"exerciseId": "spin_seated_flat_steady", "durationSec": 120},
    {"exerciseId": "spin_102030_block", "durationSec": 300},
    {"exerciseId": "spin_recovery_soft", "durationSec": 120},
    {"exerciseId": "spin_102030_block", "durationSec": 300},
    {"exerciseId": "spin_recovery_soft", "durationSec": 120},
    {"exerciseId": "spin_102030_block", "durationSec": 300},
    {"exerciseId": "spin_cooldown_easy", "durationSec": 240}
  ]
}
```
*(Total 1800 s = 30:00; 3 blocks within the specified 2–4 with 120 s between-block recoveries.)*

```json
{
  "id": "rt_spin_sit_snaps", "name": "SIT Snap Session", "category": "SPIN", "intensity": "VERY_HIGH",
  "summary": "Three sprint-snap blocks of 6-on/9-off waves with real recoveries — maximum stimulus, minimum minutes.",
  "steps": [
    {"exerciseId": "spin_warmup_flat", "durationSec": 300},
    {"exerciseId": "spin_snap_sprint", "durationSec": 180},
    {"exerciseId": "spin_recovery_soft", "durationSec": 120},
    {"exerciseId": "spin_snap_sprint", "durationSec": 180},
    {"exerciseId": "spin_recovery_soft", "durationSec": 120},
    {"exerciseId": "spin_snap_sprint", "durationSec": 180},
    {"exerciseId": "spin_cooldown_easy", "durationSec": 240}
  ]
}
```
*(Total 1320 s = 22:00.)*

```json
{
  "id": "rt_spin_recovery_ride", "name": "Recovery Ride", "category": "SPIN", "intensity": "LOW",
  "summary": "Twenty easy minutes for tired legs: light gears only, breathing that never leaves conversation.",
  "steps": [
    {"exerciseId": "spin_warmup_flat", "durationSec": 300},
    {"exerciseId": "spin_recovery_soft", "durationSec": 240},
    {"exerciseId": "spin_warmup_flat", "durationSec": 240},
    {"exerciseId": "spin_recovery_soft", "durationSec": 240},
    {"exerciseId": "spin_cooldown_easy", "durationSec": 180}
  ]
}
```
*(Total 1200 s = 20:00; all-LOW segments.)*

```json
{
  "id": "rt_ell_recovery_glide", "name": "Recovery Glide", "category": "ELLIPTICAL", "intensity": "LOW",
  "summary": "Twenty gentle minutes of easy striding — active recovery that stays honestly easy.",
  "steps": [
    {"exerciseId": "ell_warmup_easy", "durationSec": 300},
    {"exerciseId": "ell_recovery_easy", "durationSec": 240},
    {"exerciseId": "ell_warmup_easy", "durationSec": 240},
    {"exerciseId": "ell_recovery_easy", "durationSec": 240},
    {"exerciseId": "ell_cooldown_easy", "durationSec": 180}
  ]
}
```
*(Total 1200 s = 20:00.)*

```json
{
  "id": "rt_spin_mict45", "name": "45-Minute Steady Ride", "category": "SPIN", "intensity": "MODERATE",
  "summary": "The full MICT dose in one ride: long steady blocks, two gentle climbs and one leg-speed surge.",
  "steps": [
    {"exerciseId": "spin_warmup_flat", "durationSec": 300},
    {"exerciseId": "spin_seated_flat_steady", "durationSec": 600},
    {"exerciseId": "spin_seated_climb", "durationSec": 300},
    {"exerciseId": "spin_seated_flat_steady", "durationSec": 480},
    {"exerciseId": "spin_fast_flat", "durationSec": 60},
    {"exerciseId": "spin_recovery_soft", "durationSec": 120},
    {"exerciseId": "spin_seated_flat_steady", "durationSec": 480},
    {"exerciseId": "spin_seated_climb", "durationSec": 180},
    {"exerciseId": "spin_cooldown_easy", "durationSec": 180}
  ]
}
```
*(Total 2700 s = 45:00.)*

**Acceptance:** validator green; each routine's steps sum to the stated total; generator picks the Norwegian routines for HIGH/VERY_HIGH machine blocks (routine intensity HIGH passes the `rank <= pref+1` filter for MODERATE+ users). **Dependencies:** Step 4 (segment ids).

---

## STEP 6 — Myth-buster insights (JSON for the new `insights` array)

```json
"insights": [
  {
    "id": "insight_spot_reduction",
    "title": "Myth: you can burn fat off one spot",
    "myth": "Crunches melt belly fat, side bends shrink love handles — train an area to slim that area.",
    "reality": "Controlled trials say no. Twelve weeks of high-volume single-leg training reduced fat everywhere except the trained leg, and six weeks of daily ab work changed abdominal fat not at all. Muscles grow where you train; fat leaves from wherever your body decides, driven by your overall energy balance and by whole-body cardio — which is exactly why this app's visceral-fat plan is built on bike, elliptical and interval work rather than ab circuits.",
    "references": [
      {"title": "Regional fat changes induced by localized muscle endurance resistance training", "authors": "Ramirez-Campillo R, Andrade DC, Campos-Jara C, Henriquez-Olguin C, Alvarez-Lepin C, Izquierdo M", "year": 2013, "journal": "Journal of Strength and Conditioning Research 27(8):2219-2224", "doiOrPmid": "10.1519/JSC.0b013e31827e8681", "finding": "Twelve weeks of localized training reduced fat in the trunk and arms — but not in the trained leg. Spot reduction did not happen."}
    ]
  },
  {
    "id": "insight_fat_burning_zone",
    "title": "Myth: the 'fat-burning zone'",
    "myth": "Slow, easy cardio burns more fat, so staying in the low-heart-rate 'fat-burning zone' is the smart way to lose it.",
    "reality": "Easy cardio burns a higher percentage of fat per minute, but far fewer total calories — and total energy use is what changes body fat. Meta-analysis of interval studies shows high-intensity work reduces total, abdominal and visceral fat at least as well as easy cardio, in less time. Both easy and hard sessions have a place in the weekly plan; there is no magic slow zone.",
    "references": [
      {"title": "Effect of High-Intensity Interval Training on Total, Abdominal and Visceral Fat Mass: A Meta-Analysis", "authors": "Maillard F, Pereira B, Boisseau N", "year": 2018, "journal": "Sports Medicine 48(2):269-288", "doiOrPmid": "10.1007/s40279-017-0807-y", "finding": "HIIT significantly reduced total, abdominal and visceral fat despite spending little or no time in the so-called fat-burning zone."}
    ]
  }
]
```

**Dependencies:** Step 2.3. **Acceptance:** two "Myth busters" cards render in Library; validator enforces their references.

---

## STEP 7 — Docs

- `README.md` line 101 (Step 1.6 text).
- `DECISIONS.md`: append `D-20` recording: schemaVersion 3 re-seed; tier-conflict resolutions (honest-MODERATE rule applied to motor-control drills, bridges STRONG); the "one block, voice-rhythm cues" model for 6/9 s and 30/20/10 protocols; insights section vs pseudo-entries; Prop.BENCH; `EllipticalCue.incline`; standing-sprint LIMITED capping per D-11.
- `RESEARCH.md`: add §12 with the new citations (Tjønna 2008, Zhang/Tong 2018, Gunnarsson & Bangsbo 2012, Edwards 2023, Harøy 2019, Sherrington 2019, Roig 2009, Hamilton 2022) in the file's existing citation style (authors, year, journal, DOI/PMID, one-line finding).

---

## STEP 8 — Tests

### 8.1 Auto-coverage (verified, no change needed)
`AnimGeometryTest` iterates `AnimationRegistry.all` for floor penetration, float detection, joint limits, loop continuity, velocity caps, and prop-channel range — all 26 new animations enter automatically once registered. `DebugAnimScreen` cycles `AnimationRegistry.all` — all new animations appear on the hidden QA screen automatically; do one manual eyeball pass. `DatabaseValidator` already rejects unresolvable `animationId`s and `DatabaseValidatorTest.every animation id resolves` re-asserts it against the real asset — confirmed present, no change needed.

### 8.2 New targeted assertions in `/home/user/edgeHealth/app/src/test/java/au/mark/kinetiq/AnimGeometryTest.kt`

Follow the exact structure of `mountain climber keeps hands planted and trunk still` (solve `poseAt(i/60f)` through `Rig.solve`, compare joints to a phase-0 reference or a constant):

- `fun squat thrust keeps hands planted through the floor phase()` — for `fl_squat_thrust`, over phases 0.20–0.44: `near.wrist` within 0.015 of its phase-0.26 position (x and y). Also assert `"fl_squat_thrust" !in` the `airborne` map (no flight).
- `fun wall sit back stays on the wall()` — for `fl_wallsit`: at all 60 phases, `|sk.pelvis.x − ref.pelvis.x| < 0.02` and `|sk.chest.x − ref.chest.x| < 0.02`, and both `< −0.15` (wall plane drawn at x = −0.245).
- `fun copenhagen top foot stays on the bench()` — for `fl_copenhagen`: at all phases, `|sk.far.ankle.y − AnimationRegistry.BENCH_Y| < 0.02`, and the support elbow near the floor: `sk.near.elbow.y > AnimationRegistry.GY − 0.04`.
- `fun heel raises keep toes grounded while ankles rise()` — for `fl_calf_raise` and `fl_soleus`: at all phases `maxOf(near.toe.y, far.toe.y) > GY − 0.02` (toes stay down); AND `max(ankle.y) − min(ankle.y)` across the loop `>= 0.03` (heels genuinely rise).
- `fun pike pushup keeps hands and toes planted while the head dips()` — for `fl_pike_pushup`: wrists and toes within 0.02 of phase-0 x/y at all phases; `max(head.y) − min(head.y) >= 0.05`.
- `fun balance hold keeps the support foot planted and the lifted foot clear()` — for `fl_balance`: at every phase at least one ankle within `GY ± 0.02`; during the dwell windows (phases 0.20–0.40 and 0.70–0.90) the other ankle `y < GY − 0.05`.
- `fun bridge march keeps shoulders down and hips up()` — for `bk_bridge_march`: `sk.chest.y > 0.35` at all phases (shoulders stay near the mat) and `pose.pelvisY < 0.36` (bridge never collapses); for `fl_bridge_single`: extended (far) ankle `y < GY − 0.04` at all phases.
- `fun shadow boxing keeps both feet planted while the fists travel()` — for `fl_shadowbox`: both toes within `GY ± 0.025` at all phases; `max(near.wrist.x) − min(near.wrist.x) >= 0.12`.
- `fun eccentric footwork lowers slower than it presses()` — for `rf_footwork_ecc` keyframes directly: the home→out span (`t` of the out keyframe) `<= 0.30` and the return span (`1 − t(last dwell)`) `>= 0.55`.
- `fun single leg footwork moves one leg only()` — for `rf_footwork_single`: `thighL/kneeL/footL` identical across all keyframes; `thighR` varies (mirror of the existing `footwork variants differ only in ankle articulation` style).
- Extend `machine cadences match coached ranges`: `spin["sp_snap"]!!.cadenceRpm == 110`; `spin["sp_standing_sprint"]!!.cadenceRpm <= 110` (D-11); `spin["sp_4x4"]!!.cadenceRpm in 90..100`; `el["el_4x4"]!!.strideRpm > el["el_forward"]!!.strideRpm`; `el["el_incline"]!!.strideRpm < el["el_forward"]!!.strideRpm`; `el["el_reverse_fast"]!!.reverse` is true and `armsDrive` false.
- Extend `strength moves have asymmetric tempo…` id list with: `"fl_bridge_single", "fl_pike_pushup", "fl_calf_raise", "fl_single_leg_rdl", "rf_footwork_ecc"`.

### 8.3 Existing-test impact check
`no joint ever penetrates the floor` uses floor = GY + 0.035 — bench-supported poses are above it, fine. `figures never float` needs no new airborne entries (no new jumps) but every new anim must keep a joint below y = 0.30 — all standing/lying/kneeling poses do (ankles at GY); verify `fl_copenhagen` (support elbow at GY) and `fl_pike_pushup` (wrists/toes at GY) pass.

### 8.4 `/home/user/edgeHealth/app/src/test/java/au/mark/kinetiq/DatabaseValidatorTest.kt` updates

- Update `bundled database meets minimum content counts` to the new floors: FLOOR ≥ 40, REFORMER ≥ 17, SPIN segments ≥ 14, ELLIPTICAL segments ≥ 11, BACK ≥ 14, SPIN routines ≥ 12, ELLIPTICAL routines ≥ 7.
- New `fun phase 3 entry counts are exact()`: FLOOR == 43, REFORMER == 20, SPIN == 16, ELLIPTICAL == 12, BACK == 14 (105 total), routines SPIN == 13 / ELLIPTICAL == 8 (21 total), insights == 2.
- New `fun tier decisions hold()`: `floor_glute_bridge`/`back_glute_bridge` are STRONG; `floor_bird_dog`/`back_bird_dog`/`floor_dead_bug`/`back_dead_bug` are MODERATE; `back_toe_touch_stretch` is MODERATE with non-null popularityNote; `spin_tabata_sprint.targets == [CARDIO]`; LIMITED total == 11 (crunch, russian twist, side splits + 8 new) and every one has a note (existing test covers the note).
- New `fun every category has in-category warmup content()`: for each Category, `exercises.count { it.category == cat && it.isWarmupCooldown } >= 2` — locks fix 1.7 (FLOOR 6, SPIN 2, ELL 2, REFORMER 2, BACK 2).
- New `fun contraindication fixes hold()`: burpee contains ANKLE; `floor_side_plank` and `back_side_plank_progression` do NOT contain WRIST; `spin_sprint`, `spin_tabata_sprint`, `ell_hill_grind` contain KNEE.
- New `fun recovery spin matches its cue()`: `spin_recovery_soft.machine!!.spin!!.resistanceLow <= 0.20f` and `met == 3.5f`.
- New `fun named routines cover 10 to 40 minute blocks within the fit guard()`: for each of SPIN/ELLIPTICAL and each `min` in 10..40, assert some routine has `blockSec/totalSec` in `0.5..2.0`.
- New negative tests: insight without references rejected; blank `elliptical.incline` rejected (synthetic).
- New `fun sprint caps respect D11()`: `spin_standing_sprint.maxSec <= 30` and its cadence high ≤ 110; `spin_sprint.maxSec <= 30`.

### 8.5 Acceptance for the whole phase
`./gradlew test` green; `DatabaseValidatorTest.bundled database passes full validation` green (this is the same validation the runtime loader crashes on, so it is the release gate); manual QA-screen pass over the 26 new animations; a generated 25-min HIGH spin session selects a 4x4 routine; a 15-min REFORMER session warms up with mermaid/reformer lunge instead of floor marching.

---

### Critical Files for Implementation
- /home/user/edgeHealth/app/src/main/assets/exercise_db.json
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/anim/AnimationRegistry.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/data/DatabaseValidator.kt
- /home/user/edgeHealth/app/src/test/java/au/mark/kinetiq/AnimGeometryTest.kt
- /home/user/edgeHealth/app/src/main/java/au/mark/kinetiq/data/model/Models.kt