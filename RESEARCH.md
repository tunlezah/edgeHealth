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

## 12. Muscle retention during fat loss — resistance training with body weight, hand weights and the reformer

Added November 2026 with schema v3. This section backs the `resistance` profile on exercises and the
muscle-retention session builder. The profile's *retention tier* grades one claim only — "an effective
resistance exercise for maintaining its target muscles" — and is kept separate from the general
`evidenceTier` of §1–§11. Equipment in scope: body weight on a mat, a Pilates reformer, and hand weights
of 1, 1.5 and 2 kg. Cardio machines appear only to explain why they do not count (§12.7).

Verification: two literature reviews were run for this section (programming principles; exercise-level
evidence). Every paper cited below was retrieved from PubMed and its abstract read, and its DOI (where
PubMed lists one) resolved through Crossref; the single exception is marked "Crossref only". Findings
are abstract-level unless marked "full text". Anything marked *(derived)* is this app's translation of
the evidence into a number, not a number a study reported. STRONG / MODERATE / LIMITED follow the tier
rule in §12.9.

### 12.1 Resistance training in an energy deficit

- **Adding resistance training (RT) to a diet protects lean mass; cardio alone does not do it as well
  (STRONG).** **Villareal DT, Aguirre L, Gurney AB, et al. 2017** (N Engl J Med 376(20):1943–1955, DOI
  10.1056/NEJMoa1616338, PMID 28514618): RCT, 160 older adults with obesity, 6 months, about 9% weight
  loss in every exercise arm — lean mass fell 5% with diet plus aerobic training, 2% with diet plus RT
  and 3% with diet plus both. **Beavers KM, Ambrosius WT, Rejeski WJ, et al. 2017** (Obesity (Silver
  Spring) 25(11):1823–1829, DOI 10.1002/oby.21977, PMID 29086504): 18-month RCT, 249 older adults with
  obesity — lean-mass loss was −1.0 kg with diet alone, −1.6 kg with diet plus aerobic training and −0.8
  kg with diet plus RT.
- **Meta-analyses agree.** **Sardeli AV, Komatsu TR, Mori MA, et al. 2018** (Nutrients 10(4):423, DOI
  10.3390/nu10040423, PMID 29596307): 6 RCTs in obese older adults — RT cut lean-mass loss by 0.82 kg
  (95% CI 0.36–1.27; reported as "93.5%") with similar fat and weight loss. **Binmahfoz A, Dighriri A,
  Gray C, Gray SR 2025** (BMJ Open Sport Exerc Med 11(3):e002363, DOI 10.1136/bmjsem-2024-002363, PMID
  40909191): 25 RCTs in adults aged 18–65 with overweight or obesity — RT protected fat-free mass (SMD
  0.40) with no effect on total weight loss. **Lopez P, Taaffe DR, Galvão DA, et al. 2022** (Obes Rev
  23(5):e13428, DOI 10.1111/obr.13428, PMID 35191588): 114 trials — lean mass was maintained (about −0.3
  kg, not significant) when RT was combined with caloric restriction.
- **Retention, not growth.** **Murphy C, Koehler K 2022** (Scand J Med Sci Sports 32(1):125–137, DOI
  10.1111/sms.14075, PMID 34623696): meta-regression — a deficit of about 500 kcal/day was estimated to
  cancel lean-mass gain from RT, while strength gains were not impaired. The app promises retention and
  never growth.
- **Limit on generalising — the reason this section exists.** The deficit trials whose protocols could
  be read (full text) all used conventional machine-based RT: Villareal 2017 (3 days/week, nine
  machines, building to 2–3 sets at about 85% 1RM, supervised by trainers), Beavers 2017 (4 days/week, 3
  sets of 10–12 reps at about 75% 1RM) and the six RCTs pooled in Sardeli 2018 (2–3 sets of 8–15 reps at
  ≥65% 1RM, 3 days/week). None used body weight, spring resistance or light hand weights. That the same
  protection can be had from this app's equipment is **MODERATE at best**: it is inferred by chaining
  these trials to the low-load evidence in §12.3, which comes almost entirely from people who were not
  dieting.
- **Cardio does not cancel RT.** In Villareal 2017 the combined arm lost 3% of lean mass against 2% with
  RT alone. Outside a diet, **Schumann M, Feuerbacher JF, Sünkeler M, et al. 2022** (Sports Med
  52(3):601–612, DOI 10.1007/s40279-021-01587-7, PMID 34757594) (43 studies) found that adding aerobic
  training did not compromise muscle hypertrophy (SMD −0.01) or maximal strength, only explosive
  strength, and mainly when both were done in the same session. A lot of cardio on top of a deficit has
  not been studied as an interference question.

### 12.2 Maintenance dose and the weekly-sets target

- **Keeping muscle takes less work than building it — shown outside a diet, with the load kept up
  (MODERATE).** **Bickel CS, Cross JM, Bamman MM 2011** (Med Sci Sports Exerc 43(7):1177–1187, DOI
  10.1249/MSS.0b013e318207c15d, PMID 21131862): after 16 weeks of training, 32 weeks at one-third or
  one-ninth of the weekly dose preserved hypertrophy in adults aged 20–35 but not in adults aged 60–75.
  **Tavares LD, de Souza EO, Ugrinowitsch C, et al. 2017** (Eur J Sport Sci 17(6):665–672, DOI
  10.1080/17461391.2017.1298673, PMID 28316261): 1 or 2 reduced sessions a week held quadriceps
  cross-sectional area for 8 weeks in 33 young men, while stopping lost 5.4%. **Spiering BA, Mujika I,
  Sharp MA, Foulis SA 2021** (J Strength Cond Res 35(5):1449–1458, DOI 10.1519/JSC.0000000000003964,
  PMID 33629972) (narrative review): older adults may need up to 2 sessions a week and 2–3 sets per
  exercise, and relative load is the variable to keep. All of this used moderate-to-heavy loads, outside
  any diet, for at most 32 weeks.
- **More weekly sets build more muscle, with diminishing returns (STRONG); how the sets are spread over
  the week does not matter for size.** **Pelland JC, Remmert JF, Robinson ZP, et al. 2026** (Sports Med
  56(2):481–505, DOI 10.1007/s40279-025-02344-w, PMID 41343037): meta-regressions of 67 studies (mean
  age 25) — hypertrophy rises with weekly sets with diminishing returns, frequency had a negligible
  effect on hypertrophy, and counting a set as half a set for muscles trained indirectly fitted best.
  **Currier BS, D'Souza AC, Singh MAF, et al. 2026** (Med Sci Sports Exerc 58(4):851–872, DOI
  10.1249/MSS.0000000000003897, PMID 41843416) (ACSM position stand; overview of 137 systematic
  reviews): hypertrophy is enhanced by ≥10 sets per muscle group per week, while load, training to
  failure, time under tension and frequency did not consistently affect it. **Schoenfeld BJ, Grgic J,
  Krieger J 2019** (J Sports Sci 37(11):1286–1295, DOI 10.1080/02640414.2018.1555906, PMID 30558493): 25
  studies — no meaningful effect of frequency on hypertrophy when volume is equal.
- **Floor.** **Iversen VM, Norum M, Schoenfeld BJ, Fimland MS 2021** (Sports Med 51(10):2079–2095, DOI
  10.1007/s40279-021-01490-1, PMID 34125411) (narrative review, full text) advises at least 4 weekly
  sets per muscle group. **Roth C, Schoenfeld BJ, Behringer M 2022** (Eur J Appl Physiol
  122(5):1129–1151, DOI 10.1007/s00421-022-04896-5, PMID 35146569): across 15 studies of
  resistance-trained athletes in caloric restriction, programs with ≥10 weekly sets per muscle group
  showed low-to-no lean-mass loss; the authors judged the evidence insufficient for a firm conclusion.
- **Weekly-sets target the app uses *(derived)*: 6–10 hard sets per muscle group per week, never fewer
  than 4.** This is a judgement between the ≥4-set floor (Iversen 2021) and the growth-oriented ≥10 sets
  (Currier 2026), leaning upward because a deficit blunts the response (Murphy & Koehler 2022) and older
  adults need more than the bare minimum (Bickel 2011). **No study has tested a minimum dose during a
  deficit, or with low loads.**
- **What counts as a set.** Only a set that ends close to failure (§12.5). It counts in full for the
  exercise's primary muscles and as half a set for its secondary muscles (the "fractional" method that
  fitted best in Pelland 2026).
- **Coverage.** The session builder fills leg push, upper push, hamstring/glute and upper pull before
  accessories — the four regions the ACSM stand asks a program to cover (Currier 2026, full text; an
  expert view inside the stand, MODERATE).

### 12.3 Low loads taken close to failure, and the load floor

- **Muscle growth is similar across a wide range of loads when sets end at or near failure (STRONG in
  young adults who were not dieting; MODERATE in older adults; untested in a deficit).** **Schoenfeld
  BJ, Grgic J, Ogborn D, Krieger JW 2017** (J Strength Cond Res 31(12):3508–3523, DOI
  10.1519/JSC.0000000000002200, PMID 28834797): 21 studies with all sets to failure — hypertrophy was
  similar between loads of ≤60% 1RM and heavier loads, while 1RM strength gains were greater with heavy
  loads. **Lopez P, Radaelli R, Taaffe DR, et al. 2021** (Med Sci Sports Exerc 53(6):1206–1216, DOI
  10.1249/MSS.0000000000002585, PMID 33433148): network meta-analysis of 28 studies — no hypertrophy
  difference between low (>15 RM), moderate and high loads. **Mitchell CJ, Churchward-Venne TA, West DW,
  et al. 2012** (J Appl Physiol (1985) 113(1):71–77, DOI 10.1152/japplphysiol.00307.2012, PMID
  22518835): 18 untrained young men — 30% 1RM to failure grew the quadriceps as much as 80% 1RM (6.8% vs
  7.2%). **Morton RW, Oikawa SY, Wavell CG, et al. 2016** (J Appl Physiol (1985) 121(1):129–138, DOI
  10.1152/japplphysiol.00154.2016, PMID 27174923): 49 trained men — 20–25 reps per set at 30–50% 1RM
  matched 8–12 reps at 75–90% 1RM for lean mass and fibre area.
- **The price is maximal strength.** Heavier loads give larger 1RM gains (Schoenfeld 2017; Lopez 2021),
  so a low-load program holds size better than it holds maximal strength.
- **There is a floor.** **Lasevicius T, Ugrinowitsch C, Schoenfeld BJ, et al. 2018** (Eur J Sport Sci
  18(6):772–780, DOI 10.1080/17461391.2018.1450898, PMID 29564973): with volume matched, 20% 1RM
  produced about half the growth of 40–80% 1RM (elbow flexors 11.4% vs about 25%). **Van Roie E,
  Delecluse C, Coudyzer W, et al. 2013** (Exp Gerontol 48(11):1351–1361, DOI
  10.1016/j.exger.2013.08.010, PMID 23999311): older adults doing 80–100 reps to failure at 20% 1RM did
  gain muscle volume — effective, but impractical in a timed set.
- **Effort seems to matter more as the load gets lighter (MODERATE, contested).** **Lasevicius T,
  Schoenfeld BJ, Silva-Batista C, et al. 2022** (J Strength Cond Res 36(2):346–351, DOI
  10.1519/JSC.0000000000003454, PMID 31895290): at 30% 1RM, sets to failure (about 34 reps) grew the
  quadriceps 7.8% while sets stopped at about 20 reps gave a non-significant 2.8%; at 80% 1RM failure
  made no difference. **Nóbrega SR, Ugrinowitsch C, Pintanel L, et al. 2018** (J Strength Cond Res
  32(1):162–169, DOI 10.1519/JSC.0000000000001787, PMID 29189407) found no such penalty.
- **Load floor the app uses *(derived)*: a set must get hard within about 30 reps.** That corresponds to
  roughly 30% 1RM or more (Mitchell 2012; Lasevicius 2022); Iversen 2021 gives 15–40 reps for sets taken
  to failure. If more than about 30 clean reps are possible, the exercise is progressed or flagged as
  too light — the set is not made longer. This is a pragmatic line, not a physiological threshold.
- **Cost.** Low-load sets to failure take longer and are more uncomfortable than heavy ones (**Fisher
  JP, Steele J 2017** (Muscle Nerve 56(4):797–803, DOI 10.1002/mus.25537, PMID 28006852), n=7).

### 12.4 Translating sets into a timer

- **The clock is a container, not the stimulus.** Time under tension did not consistently affect
  hypertrophy (Currier 2026), and no trial comparing time-prescribed with repetition-prescribed sets was
  found. Every duration below is *(derived)* from rep counts × tempo in studies that prescribed reps.
- **Tempo is forgiving.** **Schoenfeld BJ, Ogborn DI, Krieger JW 2015** (Sports Med 45(4):577–585, DOI
  10.1007/s40279-015-0304-0, PMID 25601394): 8 studies, sets to failure — repetition durations from 0.5
  to 8 s gave similar hypertrophy; very slow reps (>10 s) looked worse. **Martins-Costa HC, Lacerda LT,
  Diniz RCR, et al. 2022** (J Strength Cond Res 36(7):1770–1780, DOI 10.1519/JSC.0000000000004004, PMID
  34932279): 12 reps × 3 s and 6 reps × 6 s (both 36 s under tension per set) grew muscle equally.
- **Slow, continuous-tension reps make a light load hard inside a fixed window (MODERATE).** **Tanimoto
  M, Ishii N 2006** (J Appl Physiol (1985) 100(4):1150–1157, DOI 10.1152/japplphysiol.00741.2005, PMID
  16339347) and **Tanimoto M, Sanada K, Yamamoto K, et al. 2008** (J Strength Cond Res 22(6):1926–1938,
  DOI 10.1519/JSC.0b013e318185f2b0, PMID 18978616): about 3 s down and 3 s up with no relaxation at
  roughly 50–60% 1RM gave size and strength gains comparable with heavy normal-tempo training in young
  men. **Watanabe Y, Tanimoto M, Ohgane A, et al. 2013** (J Aging Phys Act 21(1):71–84, DOI
  10.1123/japa.21.1.71, PMID 22832536) and **Watanabe Y, Madarame H, Ogasawara R, et al. 2014** (Clin
  Physiol Funct Imaging 34(6):463–470, DOI 10.1111/cpf.12117, PMID 24304680): the same method worked at
  50% and at 30% 1RM in adults aged 59–77, where normal-tempo training at the same load did little.
  **Tsuzuku S, Kajioka T, Sakakibara H, Shimaoka K 2018** (Scand J Med Sci Sports 28(4):1339–1344, DOI
  10.1111/sms.13039, PMID 29247985): bodyweight squat, table-top push-up and sit-up at 4 s down / 4 s
  up, 2 sets of 10–14 reps, increased thigh muscle thickness in 88 adults aged ≥70. That is five small
  trials, largely from one research group and none in a deficit; the slow tempo is a way to reach high
  effort with a light load, not something intrinsically superior.
- **Set windows *(all derived)*.** Light loads (body weight, springs, light hand weights): **45–75 s**,
  at about 3 s down and 2–3 s up with no resting at the top. Moderate loads (a load failed at in about
  8–15 reps): **30–45 s**. Isometric holds: **20–45 s** in a lengthened position — **Oranchuk DJ, Storey
  AG, Nelson AR, Cronin JB 2019** (Scand J Med Sci Sports 29(4):484–503, DOI 10.1111/sms.13375, PMID
  30580468) (26 studies) found isometric training builds muscle at any intensity and more at long muscle
  lengths, but gives no hold durations. One-side-at-a-time work gets the window per side.
- **Reading the window.** Reaching failure before the window ends is success, not a fault; if the window
  ends with more than about five good reps left, the set was too easy *(derived)*.
- **Rests.** Between sets of the same muscle, **60–90 s**: **Singer A, Wolf M, Generoso L, et al. 2024**
  (Front Sports Act Living 6:1429789, DOI 10.3389/fspor.2024.1429789, PMID 39205815) (Bayesian
  meta-analysis, 9 studies) found a small hypertrophy benefit of resting more than 60 s and no
  appreciable difference beyond 90 s, with credible intervals spanning zero; **Schoenfeld BJ, Pope ZK,
  Benik FM, et al. 2016** (J Strength Cond Res 30(7):1805–1812, DOI 10.1519/JSC.0000000000001272, PMID
  26605807) found 3-minute rests beat 1-minute rests in 21 trained men; Currier 2026 rates the data
  insufficient. Reasonable, not proven. **Or alternating pairs:** two non-competing exercises alternated
  with short transitions, so each muscle still gets about 90 s before it works again *(derived)* —
  **Zhang X, Weakley J, Li H, et al. 2025** (Sports Med 55(4):953–975, DOI 10.1007/s40279-025-02176-8,
  PMID 39903375) (19 studies): supersets shorten sessions with similar long-term hypertrophy (SMD −0.05)
  at a higher perceived exertion.

### 12.5 Effort targets

- **Failure itself is not required, but ending closer to it helps a little (MODERATE).** **Refalo MC,
  Helms ER, Trexler ET, et al. 2023** (Sports Med 53(3):649–665, DOI 10.1007/s40279-022-01784-y, PMID
  36334240): 15 studies — a trivial hypertrophy advantage for training to set failure (ES 0.19, 95% CI
  0.00–0.37). **Grgic J, Schoenfeld BJ, Orazem J, Sabol F 2022** (J Sport Health Sci 11(2):202–211, DOI
  10.1016/j.jshs.2021.01.007, PMID 33497853): 15 studies — no significant difference between failure and
  non-failure training. **Robinson ZP, Pelland JC, Remmert JF, et al. 2024** (Sports Med
  54(9):2209–2231, DOI 10.1007/s40279-024-02069-2, PMID 38970765): hypertrophy increased as sets ended
  closer to failure, with reps in reserve *estimated* from study descriptions.
- **Targets the app cues.** Light loads: keep going to the last good rep (0–1 reps in reserve), because
  low-load sets stopped short did not grow muscle in Lasevicius 2022 (contested by Nóbrega 2018).
  Moderate loads: stop with 1–3 reps in reserve. Holds: stop when form goes. The ease-in target for a
  newcomer's first weeks (3–4 reps in reserve) is a convention *(derived)*, not a trial finding.
- **Self-rated effort is noisy.** **Halperin I, Malleron T, Har-Nir I, et al. 2022** (Sports Med
  52(2):377–390, DOI 10.1007/s40279-021-01559-x, PMID 34542869): 12 studies, n=414 — people
  under-predict reps to failure by about one rep on average, and accuracy is worse in sets longer than
  12 reps, which are exactly the sets this equipment produces. A timer cannot verify effort at all,
  hence the post-set effort rating.
- **Safety versus efficacy.** Low loads appear to need near-failure effort, yet Currier 2026 (full text)
  notes that lifting to fatigue may be inadvisable for some people (older individuals, for example) and
  proposes near-failure or 2–3 reps in reserve as sufficient. Intra-arterial pressure averaged 320/250
  mmHg in five bodybuilders leg-pressing to failure, partly because of breath-holding (**MacDougall JD,
  Tuxen D, Sale DG, et al. 1985** (J Appl Physiol (1985) 58(3):785–790, DOI 10.1152/jappl.1985.58.3.785,
  PMID 3980383)). No outcome trial resolves this; the app defaults to technical (form) failure, cues
  steady breathing and defers to medical advice.

### 12.6 Progression: reps first, then a harder variation

- **Adding reps at a fixed load works as well as adding load (MODERATE; 8–10 weeks, not dieting).**
  **Plotkin D, Coleman M, Van Every D, et al. 2022** (PeerJ 10:e14142, DOI 10.7717/peerj.14142, PMID
  36199287): 43 trained adults — rep progression and load progression gave similar muscle-thickness
  gains. **Chaves TS, Scarpelli MC, Bergamasco JGA, et al. 2024** (Int J Sports Med 45(7):504–510, DOI
  10.1055/a-2256-5857, PMID 38286426): within-subject, 39 untrained adults — the same vastus lateralis
  growth and 1RM gain either way.
- **Then a harder variation.** Training one limb at a time gives the same hypertrophy as two (**Kassiano
  W, Nunes JP, Costa B, et al. 2025** (Sports Med 55(4):923–936, DOI 10.1007/s40279-024-02169-z, PMID
  39794667); 9 studies, wide interval). Leverage is measurable for push-ups: feet-elevated variants put
  the most load on the hands and knee or hands-elevated variants the least (**Ebben WP, Wurm B,
  VanderZanden TL, et al. 2011** (J Strength Cond Res 25(10):2891–2894, DOI
  10.1519/JSC.0b013e31820c8587, PMID 21873902)), and the hands carry about 66% of body weight at the
  start of a standard push-up against about 53% on the knees (**Gouvali MK, Boudolos K 2005** (J
  Strength Cond Res 19(1):146–151, DOI 10.1519/14733.1, PMID 15705025)). Slower tempo and pauses as
  progression levers are **LIMITED** — supported only indirectly, by §12.4.
- **Why ladders are needed.** Strength gains from bodyweight exercise were inversely related to starting
  strength, and most of the stronger women did not improve (**Yoshitake Y, Takai Y, Kitamura T, et al.
  2011** (Int J Sports Med 32(12):924–928, DOI 10.1055/s-0031-1283177, PMID 22116634)); a 10-month
  uncontrolled program in older adults showed the same dependence (**Yamauchi J, Nakayama S, Ishii N
  2009** (Geriatr Gerontol Int 9(3):262–269, DOI 10.1111/j.1447-0594.2009.00530.x, PMID 19702936)). A
  fixed bodyweight exercise stops being an overload as the user gets stronger, so the database groups
  alternatives into ladders (`leg_push`, `pushup`, `hip_bridge`, `calf`, `prone_raise`, `strap_row`,
  `ref_leg_press`) whose `rung` rises with difficulty.

### 12.7 Why cardio machines count as zero resistance sets

- **RT beats aerobic training for muscle (STRONG).** **Grgic J, Mcllvenna LC, Fyfe JJ, et al. 2019**
  (Sports Med 49(2):233–254, DOI 10.1007/s40279-018-1008-z, PMID 30341595) (21 studies): Hedges' g 0.66
  for whole-muscle knee-extensor hypertrophy, 0.99 for type I and 1.44 for type II fibres, in favour of
  RT. **Willis LH, Slentz CA, Bateman LA, et al. 2012** (J Appl Physiol (1985) 113(12):1831–1837, DOI
  10.1152/japplphysiol.01370.2011, PMID 23019316): 119 adults, 8 months, no prescribed diet — RT and
  combined training increased lean mass more than aerobic training. During weight loss the aerobic-only
  arms lost the most lean mass (§12.1).
- **Cycling can add a little quadriceps in previously untrained people (LIMITED–MODERATE).** **Harber
  MP, Konopka AR, Undem MK, et al. 2012** (J Appl Physiol (1985) 113(9):1495–1504, DOI
  10.1152/japplphysiol.00786.2012, PMID 22984247) and **Harber MP, Konopka AR, Douglass MD, et al.
  2009** (Am J Physiol Regul Integr Comp Physiol 297(5):R1452–R1459, DOI 10.1152/ajpregu.00354.2009,
  PMID 19692660): 12 weeks of cycle training increased MRI quadriceps volume by 5–6% in men and 12% in
  older women — with 6–7 participants per group, no control group, growth in type I fibres only and, in
  the older women, lower force per unit of fibre area.
- **Elliptical (LIMITED).** The muscle data come from one research group, mostly in older adults with
  obesity: **Marcangeli V, Youssef L, Dulac M, et al. 2022** (J Cachexia Sarcopenia Muscle
  13(3):1526–1540, DOI 10.1002/jcsm.12955, PMID 35257499) (total lean mass +1.1–1.3% after 12 weeks of
  elliptical HIIT, no non-exercise control) and **Youssef L, Granet J, Marcangeli V, et al. 2022**
  (Healthcare (Basel) 10(7):1346, DOI 10.3390/healthcare10071346, PMID 35885872) (lean mass +1.58%, but
  lower-limb strength +4.3% against +23.2% with treadmill walking).
- **Handles.** No study of handle forces or upper-body muscle outcomes on a standard elliptical was
  found; **Mier CM, Feito Y 2006** (Res Q Exerc Sport 77(4):507–513, DOI 10.1080/02701367.2006.10599385,
  PMID 17243225) shows only that using the arm poles raises oxygen uptake and perceived exertion. This
  matches §8.
- **Rule.** Spin-bike and elliptical work count as zero resistance sets in the weekly meter, and the app
  makes no muscle-retention claim for them. None of these studies involves a person already doing a lot
  of cardio, for whom the stimulus is no longer new.

### 12.8 The reformer — the honest position

- **No trial showing limb-muscle hypertrophy from reformer or any other Pilates training was found** (an
  absence in the search, not proof that none exists). The only imaging studies: **Dorado C, Calbet JA,
  Lopez-Gordillo A, et al. 2012** (Med Sci Sports Exerc 44(8):1589–1594, DOI
  10.1249/MSS.0b013e31824fb6ae, PMID 22357302) — 9 inactive women, 36 weeks, twice weekly, rectus
  abdominis volume +21% on MRI, before-and-after with no control group, apparatus not stated in the
  abstract; and **Dorado C, López-Gordillo A, Serrano-Sánchez JA, et al. 2020** (Sports Health
  12(6):547–551, DOI 10.1177/1941738120918381, PMID 32396038) — 12 inactive women on the same kind of
  36-week program, no significant change in gluteal, iliopsoas, piriformis or quadratus lumborum volume.
- **Lean mass: null on pooling.** **Cavina APS, Pizzo Junior E, Machado AF, et al. 2020** (J Phys Act
  Health 17(6):673–681, DOI 10.1123/jpah.2019-0171, PMID 32396869) (10 studies, mat Pilates): no better
  than control for lean mass. **Wang Y, Chen Z, Wu Z, et al. 2021** (Front Physiol 12:643455, DOI
  10.3389/fphys.2021.643455, PMID 33776797) (11 RCTs, n=393; also §4): lean body mass unchanged (MD
  0.00, 95% CI −1.40 to 1.40). **Bajčetić Č, Rudović V, Novaković M, et al. 2026** (Sports (Basel)
  14(9):407, DOI 10.3390/sports14090407, PMID 42784403) (12 studies, about 540 adult women): no
  significant pooled effect on lean body mass or handgrip strength.
- **Strength: positive in sedentary older adults, null in the most specific meta-analysis.** **Oliveira
  LS, de Oliveira RG, da Silva TQ, et al. 2024** (J Bodyw Mov Ther 39:615–634, DOI
  10.1016/j.jbmt.2024.02.021, PMID 38876695) (24 RCTs, 1,190 adults over 60): no significant strength
  improvement against control (SMD 1.18, 95% CI −0.71 to 3.08, low-quality evidence). Positive apparatus
  trials: **Campos de Oliveira L, Gonçalves de Oliveira R, Pires-Oliveira DA 2015** (J Phys Ther Sci
  27(3):871–876, DOI 10.1589/jpts.27.871, PMID 25931749) — 32 older adults, 12 weeks, knee extensor and
  flexor torque improved against stretching (full text: one set of ten reps per exercise at moderate
  effort, spring load progressed, reformer arm curls and triceps work included); **Oliveira LC,
  Pires-Oliveira DA, Abucarub AC, et al. 2017** (J Bodyw Mov Ther 21(1):2–10, DOI
  10.1016/j.jbmt.2016.03.002, PMID 28167180) — 30 older women, elbow flexor and extensor strength
  improved against control (the abstract does not list the exercises). Strength gain in sedentary people
  in their sixties is not evidence of muscle retention in a healthy adult who is dieting.
- **Spring loads are low and hard to pin down.** Self BP, Bagley AM, Triplett TL, Paulos LE 1996 (J Appl
  Biomech 12(3):326–337, DOI 10.1123/jab.12.3.326; **Crossref only — not indexed in PubMed**): in
  dancers, forces relative to body weight were highest in the standing movement, not on the reformer
  with two or four springs, and reformer force depended entirely on knee angle. **Cantergi D, Loss JF,
  Jinha A, et al. 2015** (J Electromyogr Kinesiol 25(2):260–264, DOI 10.1016/j.jelekin.2014.08.016, PMID
  25262161): 15 people doing reformer leg presses used two different hip strategies, depending on the
  direction they pushed on the bar. **Brodt GA, Cantergi D, Gertz LC, Loss JF 2014** (J Appl Biomech
  30(3):483–490, DOI 10.1123/jab.2013-0167, PMID 24603558): force estimated from spring stretch differed
  significantly from measured force. **Kim HJ, Sung JH, Ryu JK, et al. 2024** (Healthcare (Basel)
  12(23):2447, DOI 10.3390/healthcare12232447, PMID 39685069): in 18 women, lightening the springs
  raised trunk-muscle activity in three reformer core exercises (full text: rectus abdominis at most
  about 25% of maximum in the elephant; biceps femoris 7–16% in the hip roll).
- **Strap work is geared down *(derived from pulley geometry, not measured)*.** Each hand works against
  about a quarter of the spring force with two straps and about half with one. Two-arm strap work on
  light springs is therefore a very light load; heavy springs and one arm at a time are what make strap
  work a resistance stimulus. No kilogram figures are shown in the app.
- **What this means in the database.** No reformer exercise is graded STRONG for retention. Strap biceps
  curl and strap triceps press are MODERATE (strength evidence in older women only; one arm, heavy
  springs). Footwork (two-leg and single-leg), the two-arm row and the one-arm strap row are LIMITED;
  the one-arm strap row is kept because it is the best pulling option available without weights, and is
  labelled as not a substitute for a loaded row. The hundred and knee stretches are LIMITED as
  resistance work: the abdominal result above is for a whole Pilates program, and **Queiroz BC, Cagliari
  MF, Amorim CF, Sacco IC 2010** (Arch Phys Med Rehabil 91(1):86–92, DOI 10.1016/j.apmr.2009.09.016,
  PMID 20103401) found comparatively low rectus abdominis activation across knee-stretch variations.
  Feet in straps, frog, leg circles, elephant, pelvic curl, mermaid and side splits carry no retention
  profile and stay mobility and control work. §4's general tiers are unchanged.

### 12.9 Exercise-level evidence behind the retention tiers

Tier rule. **STRONG:** longitudinal training studies or meta-analyses that used the exercise, or a
biomechanically near-identical one. **MODERATE:** muscle-activity (EMG) or biomechanics support plus
training evidence for the pattern, or a single small or uncontrolled trial. **LIMITED:** plausible but
essentially unstudied, null or weak results, or an available load too low to make the claim. A tier is
not a load guarantee. No exercise-level trial below was run in a calorie deficit, and EMG is not
hypertrophy — in the hip-thrust trial cited below, higher gluteal EMG did not consistently predict
growth.

- **Push-ups (STRONG: knee and full).** **Kikuchi N, Nakazato K 2017** (J Exerc Sci Fit 15(1):37–42, DOI
  10.1016/j.jesf.2017.06.003, PMID 29541130): 18 young men, 8 weeks — push-ups adjusted (by kneeling,
  for example) to a 40% 1RM bench-press load increased triceps and chest thickness and bench 1RM as
  bench pressing did. Nine per group; the chest-thickness figures in the abstract are identical for both
  groups, a probable reporting error, so they are not quoted anywhere in the app. **Calatayud J,
  Borreani S, Colado JC, et al. 2015** (J Strength Cond Res 29(1):246–253, DOI
  10.1519/JSC.0000000000000589, PMID 24983847): band-loaded push-ups at 6RM matched the bench press for
  strength over 5 weeks in trained students. **Kotarsky CJ, Christensen BK, Miller JS, Hackney KJ 2018**
  (J Strength Cond Res 32(3):651–659, DOI 10.1519/JSC.0000000000002345, PMID 29466268): 4 weeks of
  progressive push-up variations improved bench 1RM as bench training did; muscle thickness did not
  change in either group. **Close-grip (MODERATE):** a narrow hand position gave higher triceps and
  chest EMG (**Cogley RM, Archambault TA, Fibeger JF, et al. 2005** (J Strength Cond Res 19(3):628–633,
  DOI 10.1519/15094.1, PMID 16095413), n=40; **Marcolin G, Petrone N, Moro T, et al. 2015** (J Athl
  Train 50(11):1126–1132, DOI 10.4085/1062-6050-50.9.09, PMID 26488636), n=8) but has never been trained
  on its own. **Pike push-up (LIMITED):** no EMG or training study found.
- **Squat and lunge patterns (MODERATE with body weight).** Tsuzuku 2018 (§12.4; adults ≥70). **Takai Y,
  Fukunaga Y, Fujita E, et al. 2013** (J Sports Sci Med 12(1):60–65, PMID 24149726; no DOI in PubMed):
  100 bodyweight squats a day for 8 weeks increased thigh muscle thickness 3.2% in adolescent boys.
  **Wei W, Zhu J, Ren S, et al. 2023** (Sci Rep 13(1):13505, DOI 10.1038/s41598-023-40319-x, PMID
  37598268): a bodyweight progression from two-leg to one-leg squats matched barbell squats for thigh,
  gluteal and calf thickness in 13 sedentary young women. **Ogawa M, Hashimoto Y, Mochizuki Y, et al.
  2023** (Exp Physiol 108(7):975–985, DOI 10.1113/EP090655, PMID 37133323): 8 weeks of bodyweight
  training to maximum reps (squats, lunges, push-ups and others) increased MRI quadriceps area in adults
  aged 30–64. **Jönhagen S, Ackermann P, Saartok T 2009** (J Strength Cond Res 23(3):972–978, DOI
  10.1519/JSC.0b013e3181a00d98, PMID 19387378): 6 weeks of walking lunges improved hamstring strength in
  young soccer players. **Boudreau SN, Dwyer MK, Mattacola CG, et al. 2009** (J Sport Rehabil
  18(1):91–103, DOI 10.1123/jsr.18.1.91, PMID 19321909): quadriceps and gluteal EMG rose from step-up to
  lunge to single-leg squat. **Split squat:** the training evidence is loaded and rear-foot-elevated —
  **Speirs DE, Bennett MA, Finn CV, Turner AP 2016** (J Strength Cond Res 30(2):386–392, DOI
  10.1519/JSC.0000000000001096, PMID 26200193) (5 weeks, strength gains equal to the back squat in
  academy rugby players) and **Wang Y, Zhao X, Zhang L, et al. 2026** (Front Physiol 17:1786733, DOI
  10.3389/fphys.2026.1786733, PMID 41983008) (6 weeks at 80% 1RM, vastus lateralis thickness +9.3% in
  trained men); the bodyweight flat-floor version is untrialled. **Wall sit:** MODERATE only at 90° of
  knee bend or deeper, by analogy with long-length isometric training (Oranchuk 2019).
- **Glutes and hamstrings.** Single-leg bridge (MODERATE): **Lehecka BJ, Edwards M, Haverkamp R, et al.
  2017** (Int J Sports Phys Ther 12(4):543–549, PMID 28900560; no DOI in PubMed) — about 51% of maximum
  gluteus maximus and 58% of maximum gluteus medius EMG; loaded hip-extension training grows the glutes
  (**Plotkin DL, Rodas MA, Vigotsky AD, et al. 2023** (Front Physiol 14:1279170, DOI
  10.3389/fphys.2023.1279170, PMID 37877099): 9 weeks, hip thrust and back squat gave similar gluteal
  growth on MRI; a 2026 correction, Front Physiol 17:1987823, DOI 10.3389/fphys.2026.1987823, PMID
  43017308, discloses retrospective registration and donated equipment, results unchanged; **Krause Neto
  W, Krause TLV, Gama EF 2025** (Front Physiol 16:1542334, DOI 10.3389/fphys.2025.1542334, PMID
  40276368): meta-analysis of 11 studies, SMD 0.71). Two-leg bridge: LIMITED (too light). Sliding leg
  curl (MODERATE): **Orishimo KF, McHugh MP 2015** (J Strength Cond Res 29(3):772–778, DOI
  10.1519/JSC.0000000000000666, PMID 25226327) — highest hamstring EMG of four home exercises, and a
  4-week program of the four raised knee-flexor strength 9% (n=12, no control group); **Tsaklis P,
  Malliaropoulos N, Mendiguchia J, et al. 2015** (Open Access J Sports Med 6:209–217, DOI
  10.2147/OAJSM.S79189, PMID 26170726) — classed as high intensity alongside the Nordic in 20 elite
  female track athletes. Unloaded single-leg Romanian deadlift (LIMITED): among the lowest for hamstring
  EMG in Orishimo & McHugh 2015 — without load it is a balance drill.
- **Calves.** **Kinoshita M, Maeo S, Kobayashi Y, et al. 2023** (Front Physiol 14:1272106, DOI
  10.3389/fphys.2023.1272106, PMID 38156065): 12 weeks of machine-loaded standing calf raises increased
  gastrocnemius volume 9–12%, against 0.6–1.7% for seated raises. **Schoenfeld BJ, Vigotsky AD, Grgic J,
  et al. 2020** (Physiol Rep 8(9):e14427, DOI 10.14814/phy2.14427, PMID 32342648): 20–30RM and 6–10RM
  loads gave similar calf hypertrophy. **Kassiano W, Costa B, Kunevaliki G, et al. 2023** (J Strength
  Cond Res 37(9):1746–1753, DOI 10.1519/JSC.0000000000004460, PMID 37015016): raises limited to the
  stretched half of the range grew the medial gastrocnemius more than full range (15.2% vs 6.7%).
  **Fujiwara K, Toyama H, Asai H, et al. 2010** (J Physiol Anthropol 29(1):23–28, DOI
  10.2114/jpa2.29.23, PMID 20453430): 100 two-leg heel raises a day for two months increased soleus
  thickness 12.7% in 49 women aged 60–79 (no control group described). Hence: flat-floor single-leg
  raise MODERATE (no stretch below level); two-leg raise LIMITED for a healthy adult; the off-a-step
  version (STRONG) waits for step equipment.
- **Upper back (prone Y raise; rows).** **Ekstrom RA, Donatelli RA, Soderberg GL 2003** (J Orthop Sports
  Phys Ther 33(5):247–258, DOI 10.2519/jospt.2003.33.5.247, PMID 12774999) and **Cools AM, Dewitte V,
  Lanszweert F, et al. 2007** (Am J Sports Med 35(10):1744–1751, DOI 10.1177/0363546507303560, PMID
  17606671): prone overhead arm raises and prone horizontal abduction with external rotation are among
  the highest-activation exercises for the middle and lower trapezius — tested as high-intensity
  exercises, whereas the arm's own weight is a small load. Unweighted: LIMITED; with light hand weights:
  MODERATE. **Not a latissimus exercise.** Rows: **Lehman GJ, Buchan DD, Lundy A, et al. 2004** (Dyn Med
  3(1):4, DOI 10.1186/1476-5918-3-4, PMID 15228624) — seated cable rows gave the highest middle
  trapezius and rhomboid activity of four pulling exercises; **Fenwick CM, Brown SH, McGill SM 2009** (J
  Strength Cond Res 23(2):350–358, DOI 10.1519/JSC.0b013e3181942019, PMID 19197209) — in seven men,
  inverted and bent-over rows gave high latissimus and upper-back activity. Neither study was done on a
  reformer.
- **Hand weights (tiers assume an adequate load, §12.11).** Lateral raise (STRONG): **Larsen S, Wolf M,
  Schoenfeld BJ, et al. 2025** (Front Physiol 16:1611468, DOI 10.3389/fphys.2025.1611468, PMID 40692697)
  — 8 weeks, five sets to failure twice weekly, lateral deltoid thickness +3.3–4.6% in trained lifters,
  the same as cable raises; **Campos YAC, Vianna JM, Guimarães MP, et al. 2020** (J Hum Kinet 75:5–14,
  DOI 10.2478/hukin-2020-0033, PMID 33312291) — lateral raise and shoulder press activated the middle
  deltoid (about 30% and 28% of maximum) where bench press and fly barely did (5% and 3%); **Coratella
  G, Tornatore G, Longo S, et al. 2020** (Int J Environ Res Public Health 17(17):6015, DOI
  10.3390/ijerph17176015, PMID 32824894) — middle deltoid activity was highest with the arm in neutral
  rotation. Biceps curl (STRONG): **Farup J, de Paoli F, Bjerg K, et al. 2015** (Scand J Med Sci Sports
  25(6):754–763, DOI 10.1111/sms.12396, PMID 25603897) — dumbbell curls at 40% 1RM to failure for 6
  weeks, elbow-flexor volume up about 12% (n=10); **Pedrosa GF, Simões MG, Figueiredo MOC, et al. 2023**
  (Sports (Basel) 11(2):39, DOI 10.3390/sports11020039, PMID 36828324) — bottom-half preacher curls grew
  the lower biceps more than top-half curls; **Coratella G, Tornatore G, Longo S, et al. 2023** (Sports
  (Basel) 11(3):64, DOI 10.3390/sports11030064, PMID 36976950) — a palms-up grip gave the greatest
  biceps activation. Overhead triceps extension (STRONG): **Maeo S, Wu Y, Huang M, et al. 2023** (Eur J
  Sport Sci 23(7):1240–1250, DOI 10.1080/17461391.2022.2100279, PMID 35819335) — 12 weeks of *cable*
  elbow extensions overhead grew the triceps 19.9% against 13.9% with the arm at the side; a hand weight
  loads the range differently.

### 12.10 Coverage gaps without adequate hand weights

- **Covered with a mat alone at MODERATE or better:** leg push (squat, lunges, split squat), upper push
  (knee and full push-up, with close-grip for triceps), hamstring/glute (single-leg bridge, sliding leg
  curl) and calves (single-leg raise). The first three are pinned by the database validator.
- **Lats and upper back — the weakest area.** No mat exercise trains the lats. The only bodyweight
  option with any support, the inverted row, needs a safe anchor most homes lack and is not shipped. The
  reformer one-arm strap row is LIMITED, and the prone Y raise trains the middle and lower trapezius and
  rear shoulder, not the lats. Even an adequately loaded dumbbell row would only be MODERATE (no
  hypertrophy trial isolates it); nothing here reaches STRONG without a pull-up bar or cable machine.
- **Biceps.** There is no bodyweight exercise without a bar or anchor. The reformer strap curl
  (MODERATE; strength data in older women only; low, unmeasured load) is the only route without weights.
- **Side of the shoulder (middle deltoid).** Nothing direct without weights — the pike push-up is
  LIMITED and pressing barely activates the middle deltoid (Campos 2020).
- **What 1–2 kg hand weights do and do not fix.** They can turn the prone Y raise into a MODERATE
  upper-back and rear-shoulder exercise and, for some users, bring the lateral raise into a useful
  range. They do **not** provide a row — a one-arm row needs something like 6–12 kg (a planning
  estimate) — so the lat gap stays open. At 2 kg, curls and overhead triceps extensions sit at the very
  bottom of the useful range and will be too light for many adults; the 30-rep check (§12.3) decides.
  Lower-body dumbbell work is not shipped at these weights.
- The generator warns about the pulling gap instead of papering over it.

### 12.11 `minUsefulKg` values are planning estimates

- `minUsefulKg` (lateral raise 2 kg, biceps curl 2 kg, overhead triceps extension 2 kg, weighted prone Y
  raise 1 kg) is the lightest per-hand weight at which an exercise is plausibly a useful load. **No
  study supports these exact thresholds** *(derived)*; they shift with sex, age and training history.
  The exercise review done for this section put lateral raises, prone raises, overhead triceps
  extensions and (for lighter users) curls in a "light pair, about 2–6 kg each" band — its author's
  estimate, not a study result — and the shipped values are the bottom edge of that band.
- The working test is the one in §12.3: the set must get hard within about 30 reps. Each hand-weight
  entry's note says so, and its how-to asks for slow lowering with no rest at the top to get the most
  out of a light weight.

### 12.12 Expectation-setting: "minimise", not "prevent"

- Even with supervised, conventional RT, older adults losing about 9% of body weight still lost about 2%
  of lean mass (Villareal 2017), and 0.8 kg over 18 months in Beavers 2017. The app says "minimise
  muscle loss", never "prevent".
- DXA "lean mass" is not muscle. **Neeland IJ, Linge J, Birkenfeld AL 2024** (Diabetes Obes Metab 26
  Suppl 4:16–27, DOI 10.1111/dom.15728, PMID 38937282) (review): lean mass ranged from ≤15% to 40–60% of
  the weight lost across GLP-1 trials, and lean mass is not the same thing as muscle. **Heymsfield SB,
  Gonzalez MC, Shen W, et al. 2014** (Obes Rev 15(4):310–321, DOI 10.1111/obr.12143, PMID 24447775): the
  "a quarter of weight lost is fat-free mass" rule varies with time, ageing, inactivity and exercise.
- Low-load training protects muscle size better than it protects maximal strength (§12.3).

### 12.13 Where the evidence is thin

1. **This exact use case is untested.** No trial of bodyweight, reformer-spring or light-hand-weight RT
   for lean-mass retention during an energy deficit was found. The recommendation chains "conventional
   RT preserves lean mass in a deficit" (older, obese, supervised, machines at ≥65% 1RM) to "low loads
   match high loads near failure" (young, not dieting, 6–12 weeks). Each link is well supported; the
   combination is an extrapolation.
2. **Timed sets are a translation, not a tested prescription.** No RCT compares time-prescribed with
   repetition-prescribed sets; every window in §12.4 is derived.
3. **Self-rated effort is noisy** (Halperin 2022), most of all in the long sets this equipment produces,
   and a timer cannot verify it.
4. **Reformer hypertrophy is unsupported.** No limb-muscle imaging data, null pooled effects on lean
   mass, measured spring forces below those of the standing movement for the legs, and strap-load
   figures that are derivations.
5. **The maintenance dose in a deficit is unknown;** 6–10 weekly sets is a judgement call (§12.2).
6. **The load floor and the need for failure at low loads are unsettled** (Lasevicius 2018 vs Van Roie
   2013; Lasevicius 2022 vs Nóbrega 2018).
7. **Small samples and favourable populations.** Bodyweight evidence is strongest where users are
   weakest (older, sedentary, adolescent). Push-up hypertrophy rests on 9 men per group; bodyweight
   versus barbell squats on 13 women; the sliding leg curl on 12 people with no control group; Pilates
   abdominal growth on 9 women with no control group.
8. **Abstract-level reading.** Apart from the items marked "full text", findings are taken from
   abstracts; a full text should be checked before any specific effect size is shown in the app.
