# RESEARCH.md — Phase 0 evidence base for Kinetiq

Research performed at build time (August 2026) via literature and vendor-documentation search.
This file is the source of truth for the bundled exercise database (`app/src/main/assets/exercise_db.json`):
every evidence tier, MET value, cadence range, and machine cue format in the app traces back to a
finding below. Nothing is fetched at app runtime.

---

## 1. Exercise and visceral adipose tissue (VAT)

### 1.1 Aerobic / moderate-intensity continuous training (MICT)

- **Vissers et al. 2013** (PLoS One 8(2):e56415, DOI 10.1371/journal.pone.0056415, PMID 23409182).
  15 RCTs, n=852, exercise *without* caloric restriction. Pooled VAT change **Hedges' g = −0.497**
  (95% CI −0.655 to −0.340). Aerobic training of moderate or high intensity had the highest potential
  to reduce VAT; programs without dieting reduced VAT by >30 cm² (women) / >40 cm² (men) in ~12 weeks.
- **Ismail et al. 2012** (Obesity Reviews 13(1):68–91, DOI 10.1111/j.1467-789X.2011.00931.x, PMID 21951360).
  Aerobic vs control **ES = −0.33**; resistance training vs control **ES = 0.09 (null)**. Aerobic exercise
  is central for VAT reduction; RT alone is not effective for VAT.
- **Verheggen et al. 2016** (Obesity Reviews 17(8):664–690, DOI 10.1111/obr.12406, PMID 27213481).
  117 studies, n=4,815. Diet gives larger weight loss, but exercise trends toward larger VAT loss —
  **VAT can improve without body-weight change** (key honest-messaging point used on the Summary screen).
- **Recchia et al. 2023** (Br J Sports Med 57(16):1035–1041, DOI 10.1136/bjsports-2022-106304, PMID 36669870).
  Exercise reduces VAT dose-dependently (~−0.15 SMD per 1,000 kcal/week); caloric-restriction VAT effect
  is not dose-dependent.

### 1.2 HIIT / SIT

- **Maillard, Pereira & Boisseau 2018** (Sports Medicine 48(2), DOI 10.1007/s40279-017-0807-y). 39 studies,
  n=617. HIIT significantly reduces total, abdominal, and visceral fat mass; running/weight-bearing HIIT
  outperformed cycling HIIT.
- **Wewege et al. 2017** (Obesity Reviews 18(6):635–646, DOI 10.1111/obr.12532). HIIT matches MICT for fat
  mass and waist-circumference reduction with **~40% less time commitment**.
- **Keating et al. 2017** (Obesity Reviews 18(8):943–964, DOI 10.1111/obr.12536, PMID 28513103). Head-to-head,
  interval training is a time-efficient *equivalent* to MICT for adiposity, not a superior fat-burner.
- **Chang, Yang & Shun 2021** (Int J Obesity 45(5):982–997, DOI 10.1038/s41366-021-00767-9, PMID 33558643).
  Network meta-analysis (32 RCTs, n=1,905): **HIIT SMD = −0.39**, moderate+ aerobic **SMD = −0.26** on VAT;
  RT/SIT/combined not significant. **Effective dose: 3 sessions/week for 12–16 weeks, aerobic 30–60 min/session;
  HIIT effective even <30 min**; consistency mattered more than weekly minutes.
- **Sultana et al. 2019** (Sports Medicine, DOI 10.1007/s40279-019-01167-w, PMID 31401727). Low-volume HIIT
  (≤500 MET-min/wk) improves fitness but does **not** meaningfully reduce fat mass — honest caveat attached
  to Tabata-style content in the app.
- **Chen et al. 2024** (Obesity Reviews, DOI 10.1111/obr.13666). 84 RCTs, n=4,836: aerobic (≥moderate), RT,
  combined and HIIT all reduce VAT; RT effective in males/body-fat <40% but not females/body-fat ≥40%.

### 1.3 Resistance / combined training

RT alone is weak for VAT (Ismail 2012 ES 0.09; Chang 2021 NS) though Chen 2024 finds subgroup benefits.
RT keeps its place for strength, lean mass and the WHO 2x/week muscle-strengthening requirement.
Combined aerobic+RT is effective for VAT (Chen 2024) without clear superiority over aerobic alone (Chang 2021).

### 1.4 Protocol parameters the generator uses

- MICT: 30–60 min/session, ≥3×/week, ~64–76% HRmax (RPE 12–13). (Chang 2021; WHO 2020.)
- HIIT 4×4 "Norwegian": 4 × 4 min @ 90–95% HRmax, 3 min active recovery — **Helgerud et al. 2007**
  (Med Sci Sports Exerc 39(4):665–671, PMID 17414804).
- Tabata: 7–8 × 20 s @ ~170% VO2max, 10 s rest — **Tabata et al. 1996** (Med Sci Sports Exerc
  28(10):1327–1330). Fitness benefit robust; fat-loss claims tempered by Sultana 2019.
- Visceral-fat heuristic: ≥50% of session time at MODERATE+ intensity cardio, per §1.1–1.2.

## 2. Indoor cycling and elliptical evidence

- **Chavarrias et al. 2019** (Medicina 55(8):452, DOI 10.3390/medicina55080452). Indoor cycling systematic
  review: VO2max +8–10.5%; SBP −4.1 to −17 mmHg; meaningful body-composition change mostly when combined
  with diet. Sessions 30–100 min, 2–6×/week, 8–24 weeks.
- **Barbado et al. 2017** (Rev Int Med Cienc Act Fís Deporte 17(67)). Typical indoor cycling class intensity
  runs very high (large fractions above 90% HRmax) → the app builds deliberate recovery blocks into routines.
- **Brown et al. 2010** (J Strength Cond Res 24(6):1643–1649, DOI 10.1519/JSC.0b013e3181cb2854, PMID 20453685).
  At matched RPE, elliptical energy expenditure equals treadmill, but **HR runs ~19 bpm higher on the
  elliptical** → the app anchors elliptical intensity to RPE-style cues, not heart rate.

## 3. Bodyweight / calisthenics

- **Thomas et al. 2017** (Isokinetics and Exercise Science 25(3):215–222, DOI 10.3233/IES-170001). 8-week
  equipment-free calisthenics RCT improved posture, strength, and body composition in untrained men.
- **ACSM 2009 position stand** (Med Sci Sports Exerc 41(3):687–708, DOI 10.1249/MSS.0b013e3181915670,
  PMID 19204579). Progressive overload across load/volume/complexity — the basis for the harder floor
  progressions in the database.
- **Bull et al. 2020** (WHO guidelines, Br J Sports Med 54(24):1451–1462, DOI 10.1136/bjsports-2020-102955,
  PMID 33239350). 150–300 min/week moderate (or 75–150 vigorous) aerobic + muscle strengthening ≥2 days/week.
  Drives the Weekly Plan targets.

## 4. Pilates (reformer and mat) — honest tiering

- **STRONG (chronic low-back pain, flexibility, dynamic balance):**
  - Yamato et al. 2015, Cochrane CD010265 (DOI 10.1002/14651858.CD010265.pub2) — Pilates probably better
    than minimal intervention for chronic LBP pain/disability.
  - Wells et al. 2014 (PLoS One 9(7):e100402, DOI 10.1371/journal.pone.0100402, PMID 24984069) — significant
    short-term pain reduction; typical dose 1–3×/week, 30–60 min.
  - Cruz-Ferreira et al. 2011 (Arch Phys Med Rehabil 92(12):2071–2081, PMID 22030232) — strong evidence for
    flexibility and dynamic balance, moderate for muscular endurance.
- **MODERATE (blood pressure, muscular endurance):**
  - González-Devesa et al. 2024 (J Hum Hypertens 38(3):200–211, DOI 10.1038/s41371-024-00899-1, PMID 38361026)
    — SBP −4.76 / DBP −3.43 mmHg in hypertensive patients.
- **LIMITED (fat loss / body composition):**
  - Wang et al. 2021 (Front Physiol 12:643455, DOI 10.3389/fphys.2021.643455, PMID 33776797) — small RCTs
    (n=393) show weight/BMI/body-fat% changes but **no waist-circumference effect**; authors call for better
    trials. Pilates entries therefore score for CORE/MOBILITY/general-health targets, mostly MODERATE tier,
    and are **not** presented as visceral-fat tools. MET ~1.8–3.0 supports this (light energy cost).

## 5. Spot reduction is a myth (LIMITED-tier popularity notes)

- **Vispute et al. 2011** (J Strength Cond Res 25(9):2559–2564, PMID 21804427). 6 weeks of daily ab work:
  zero abdominal fat loss vs control despite endurance gains.
- **Ramírez-Campillo et al. 2013** (J Strength Cond Res 27(8):2219–2224, DOI 10.1519/JSC.0b013e31827e8681).
  12 weeks of single-leg training: fat loss occurred in trunk/arms, not the trained leg.
- Consequence: crunch-style "belly-fat burner" content ships at LIMITED tier with an honest popularityNote.

## 6. MET values (2024 Adult Compendium)

Sources: **Ainsworth et al. 2011** (Med Sci Sports Exerc 43(8):1575–1581, DOI 10.1249/MSS.0b013e31821ece12)
and **Herrmann et al. 2024** (J Sport Health Sci 13(1):6–12, DOI 10.1016/j.jshs.2023.10.010, PMID 38242596;
hosted at pacompendium.com). Codes below are 2024-numbering.

| Activity | Code | MET |
|---|---|---|
| Stationary cycling, general | 01200 | 6.8 |
| Stationary cycling 50 W (light) | 01214 | 4.0 |
| Stationary cycling 90–100 W | 01220 | 6.0 |
| Stationary cycling 126–150 W | 01228 | 8.0 |
| Stationary cycling 151–199 W | 01232 | 10.3 |
| RPM/spin class | 01270 | 9.0 |
| Bicycling HIIT | 01305 | 8.8 |
| Calisthenics vigorous (burpees, jumping jacks) | 02020 | 7.5 |
| Calisthenics moderate (push-ups, lunges) | 02022 | 3.8 |
| Calisthenics light (crunches, plank) | 02024 | 2.8 |
| Elliptical, moderate | 02048 | 5.0 |
| Elliptical, high intensity | 02049 | 9.0 |
| Pilates mat/traditional | 02103 | 1.8 |
| Pilates, general | 02105 | 2.8 |
| Stretching, mild | 02101 | 2.3 |
| Interval exercise, moderate | 02210 | 7.0 |
| HIIT vigorous (Tabata-style) | 02214 | 11.0 |

Calorie math: kcal = MET × kg × hours (Compendium convention), implemented in `CalorieCalculator`.

## 7. Spin programming (positions, cadence, safety)

Primary sources: Mad Dogg Athletics (Spinning®) official training PDFs ("Five Core Spinning Movements and
Hand Positions"; "Sprinting in the Spinning Program"), Indoor Cycling Institute professional standards,
spinning.com cadence guidance.

| Position | Cadence (rpm) | Resistance | Notes |
|---|---|---|---|
| Seated flat | 80–110 | light–moderate | base position, recovery to intervals |
| Standing flat / run | 80–110 (ICI: ≤85 standing) | moderate | "enough resistance to support you standing" |
| Jumps | 80–100 | moderate | seated↔standing transitions, 4–8 s per position |
| Seated climb | 60–80 | moderately heavy | gradual resistance build |
| Standing climb | 60–80 (app cues ~60–75) | heavy | most powerful position; hands wide |
| Sprint | 100–110 | meaningful gear | ≤30 s all-out; **never** low resistance at high cadence |

Safety rules baked into the voice cues: add resistance **before** standing; no sprint without meaningful
resistance; sprints capped at 30 s; recovery 1.5–3 min after all-out efforts; classes routinely run too hot
(Barbado 2017), so routines include explicit recovery segments.

Named routine structures (standard class formats): Pyramid (interval/resistance ladder up then down),
Tabata sprints (8 × 20 s/10 s), Rolling hills (alternating climbs/flats), Endurance ride (long steady
blocks), 30/30 sprint intervals, Climb ladder (step resistance up per stage). Warm-up 5–10 min at 80–90 rpm
light; cool-down drops resistance and cadence progressively.

## 8. Elliptical programming

- Three intensity levers: stride rate, resistance, incline/ramp (ACE).
- HIIT protocol shapes from ACE: warm-up ~5–8 min; hard interval RPE 7–10 / recovery RPE 3–4; recovery =
  drop resistance + ramp + speed until you can catch your breath.
- **Reverse pedaling**: 2005 Willamette University EMG study — the only significant direction effect is
  **higher rectus femoris (quad) activity striding backward**; glute activity tracks incline, not direction.
  Marketing claims of "+12% hamstrings backward" are unverified vendor copy and are not used. Reverse
  segments therefore cue quad emphasis honestly.
- Arms vs legs: no peer-reviewed EMG found for handle use; coaching convention (arms-drive raises total
  demand; legs-only shifts load to legs/core) is used with MODERATE/LIMITED tiering accordingly.
- Elliptical EE equals treadmill at matched RPE (Brown 2010) — supports elliptical as the app's low-impact
  running analogue; HR-based intensity reads ~19 bpm hot, so cues use effort language.

## 9. The user's machines

### Horizon GR7 spin bike — cue format "resistance N (1–11) + position + rpm"
- Johnson Fitness Australia product page: "**11 Magnetic Resistance Levels**", handlebar resistance lever,
  induction magnetic brake, belt drive, 6 kg rear flywheel. → default max level **11** (settings field,
  scalable).
- **Bluetooth**: the current GR7 ships a BLE **cadence sensor** (fitDisplay/Zwift pairable; community reports
  of buggy data) but **no FTMS resistance control and no verifiable data protocol** — resistance is a manual
  lever, so nothing can be read or set programmatically with any reliability. With the app's hard offline/no-
  network-permission constraint and no documented protocol, **no Bluetooth integration is built** (see
  DECISIONS.md D-07).
- Caveat: a ©2018 GR7 generation (friction cantilever brake, continuously-variable lever, no levels) exists;
  cues scale off the settings max-level field either way.
- Safety note from the GR7 manual: **fixed flywheel** — pedals keep turning; slow down gradually; lever
  pressed fully is the emergency brake. This is included in the spin warm-up voice guidance.

### Infiniti VG50BS elliptical — cue format "Level N + direction + arm use"
- Manufacturer/dealer specs: drum magnetic resistance, electronic tension adjustment, resistance buttons in
  both moving handles, 12 preset + 4 HRC + 1 user + 1 watt programs, adjustable pedal angle.
- **Exact AU resistance level count could not be confirmed** — infiniti.com.au returned 503 throughout
  research. The EU official-dealer spec of the VG50BS-BT lists **32 levels**, unconfirmed for the AU model.
  Per spec instruction, the settings field **defaults to 16** and is user-adjustable; see DECISIONS.md D-08.

## 10. Health Connect (verified against official docs, August 2026)

- Current stable client: **`androidx.health.connect:connect-client:1.1.0`** (first stable, Oct 2025).
  The old `androidx.health:health-connect-client` coordinate is discontinued.
- Permissions: `android.permission.health.READ_WEIGHT / READ_BODY_FAT / READ_HEIGHT /
  READ_HEALTH_DATA_HISTORY / WRITE_EXERCISE / WRITE_TOTAL_CALORIES_BURNED`; request via
  `PermissionController.createRequestPermissionResultContract()`; availability via
  `HealthConnectClient.getSdkStatus()`.
- **30-day read window (why reads returned nothing).** By default an app can only read data written in
  the 30 days before permission was first granted; on API 34+ the "no limit" carve-out applies *only to
  an app's own written data*. The app never writes Weight/Height/Body-fat (only ExerciseSession +
  TotalCaloriesBurned), so those metrics always count as another app's data and a full-history read
  **errors** without `PERMISSION_READ_HEALTH_DATA_HISTORY`. The app now requests it; when it isn't
  granted, `refreshBodyMetrics` caps the read to the last 30 days so recent readings still import.
- API 34+ rationale entry point: `ViewPermissionUsageActivity` activity-alias with
  `android.intent.action.VIEW_PERMISSION_USAGE` + category `HEALTH_PERMISSIONS`.
- 1.1.0 breaking change: `Metadata` must come from factory methods — the app uses `Metadata.manualEntry()`.
- Exercise type constants used: `EXERCISE_TYPE_BIKING_STATIONARY` (9), `EXERCISE_TYPE_CALISTHENICS` (13),
  `EXERCISE_TYPE_ELLIPTICAL` (25), `EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING` (36),
  `EXERCISE_TYPE_PILATES` (48).
- **Confirmed: no visceral-fat data type and no BMI data type** in Health Connect. BMI is computed in-app
  from Weight + Height; waist circumference and smart-scale visceral rating are manual-entry only.
  Waist-circumference risk thresholds surfaced in the app (WHO): elevated risk ≥94 cm men / ≥80 cm women;
  substantially increased ≥102 cm / ≥88 cm (WHO waist circumference report, 2008/2011).

## 11. Back care category (added v1.1) — physiotherapy-informed core & lower-back strength

All citations verified against PubMed at build time.

- **Hayden JA, Ellis J, Ogilvie R, Malmivaara A, van Tulder MW (2021).** Exercise therapy for chronic
  low back pain. *Cochrane Database Syst Rev* 2021(9):CD009790. DOI 10.1002/14651858.CD009790.pub2,
  PMID 34580864. — Across 249 trials, exercise probably reduces pain and improves function in chronic
  LBP (moderate-certainty evidence).
- **Saragiotto BT, Maher CG, Yamato TP, et al. (2016).** Motor control exercise for chronic
  non-specific low-back pain. *Cochrane Database Syst Rev* 2016(1):CD012004. DOI
  10.1002/14651858.CD012004, PMID 26742533. — Motor control exercise beats minimal intervention;
  not superior to other exercise (honest MODERATE tiering for pure motor-control drills).
- **Searle A, Spink M, Ho A, Chuter V (2015).** Exercise interventions for the treatment of chronic
  low back pain: systematic review and meta-analysis. *Clin Rehabil* 29(12):1155–1167. DOI
  10.1177/0269215515570379, PMID 25681408. — Strength/resistance and coordination/stabilisation
  programs are the most effective exercise types for chronic LBP.
- **Gordon R, Bloxham S (2016).** A systematic review of the effects of exercise and physical activity
  on non-specific chronic low back pain. *Healthcare (Basel)* 4(2):22. DOI 10.3390/healthcare4020022,
  PMID 27417610. — Combined strengthening + flexibility + aerobic programs are effective.
- **McGill SM (2001).** Low back stability: from formal description to issues for performance and
  rehabilitation. *Exerc Sport Sci Rev* 29(1):26–31. DOI 10.1097/00003677-200101000-00006, PMID
  11210443. — Conceptual basis of the "big three" (curl-up, side bridge, bird dog): challenge the
  trunk while minimizing spine load.
- **Smith BE, Littlewood C, May S (2014).** An update of stabilisation exercises for low back pain:
  systematic review with meta-analysis. *BMC Musculoskelet Disord* 15:416. DOI 10.1186/1471-2474-15-416,
  PMID 25488399. — Stabilisation exercise helps, comparably to other active exercise.
- **Machado LA, de Souza MvS, Ferreira PH, Ferreira ML (2006).** The McKenzie method for low back
  pain. *Spine* 31(9):E254–E262. DOI 10.1097/01.brs.0000214884.18502.93, PMID 16641766. — Some
  benefit over passive therapy for acute LBP; modest effects → press-ups tiered MODERATE.
- **Selkowitz DM, Beneck GJ, Powers CM (2013).** Which exercises target the gluteal muscles while
  minimizing activation of the tensor fascia lata? *J Orthop Sports Phys Ther* 43(2):54–64. DOI
  10.2519/jospt.2013.4116, PMID 23160432. — Clam and bridge best activate gluteals selectively.
- **Steele J, Bruce-Low S, Smith D (2015).** A review of the specificity of exercises designed for
  conditioning the lumbar extensors. *Br J Sports Med* 49(5):291–297. DOI 10.1136/bjsports-2013-092197,
  PMID 24092889. — Free-body extensions poorly isolate lumbar extensors (honest caveat on the
  extension hold).
- **Fernández-Rodríguez R, et al. (2022).** Best exercise options for reducing pain and disability in
  adults with chronic low back pain: network meta-analysis. *J Orthop Sports Phys Ther* 52(8):505–521.
  DOI 10.2519/jospt.2022.10671, PMID 35722759. — 118 RCTs: Pilates, strength and core-based exercise
  are the most effective options for chronic LBP.

Design rules applied: every BACK entry is LOW impact (enforced by the database validator), spoken
cues stay in pain-free ranges ("stop if anything runs down a leg"), flexion-stretch content ships at
LIMITED tier with an honest note, and the category counts toward the weekly strength target.
