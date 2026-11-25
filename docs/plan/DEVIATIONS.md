# Deviations from the phase specs

Record format: item / what the spec said / what was done / why.

- **P2-C11** / "Existing users see Kinetiq Mint unchanged (current hexes preserved)" /
  Mint light `primary` nudged `#1F8F6B` → `#1B8060` (all other hexes preserved) /
  The original color fails the spec's own WCAG gate (4.04:1 under white text vs the required
  4.5:1), and the spec rules the contrast test authoritative over individual hex values.
- **P1-5 → P2-A4** / Phase 1 specified a work:rest-ratio fixpoint solver / implemented, then
  replaced by the rest-mode solver in Phase 2 PR-1 / exactly as prescribed by
  IMPLEMENTATION_PLAN.md reconciliation #1 — noted here for traceability.
- **Phase R (whole phase)** / the plan runs Phases 1 → 2 → 3 → 4 and lists no resistance work / a
  muscle-retention phase was built ahead of Phases 3 and 4 / requested by the user; specified in
  `PHASE_R_MUSCLE_RETENTION.md`, which also records what Phases 3 and 4 must now account for.
- **P3-2.1** / "schemaVersion → 3" / Phase R shipped asset `schemaVersion: 3`; Phase 3 lands as 4 /
  the asset changed first in Phase R, and the loader re-seeds on any version change.
- **P3-3.1, P3-3.2, P3-4.3, P3-4.6 (six entries)** / Phase 3 adds split squat, single-leg glute
  bridge, single-leg RDL, calf raise, pike push-up and single-leg footwork with their animations /
  built in Phase R to the Phase 3 spec, with a `resistance` profile added to each / agreed with the
  user rather than specifying the same exercises twice. Phase 3's exact-count gates (105 exercises;
  §2.5 minimums; §8.4; `IMPLEMENTATION_PLAN.md` Definition of done #4) must be recomputed from the
  database when Phase 3 is built.
- **P3-4.3 / P3-4.6 citations** / the Phase 3 JSON is to be pasted as written / the `finding` text
  of every citation on the six entries was rewritten after checking the papers on PubMed, and one
  citation (Thomas 2017 on the pike push-up) was replaced / the papers are real but several findings
  claimed things they do not say. The remaining Phase 3 entries are unverified.
- **P3-4.6 `ref_footwork_single_leg`** / "Drop to a lighter spring", `MEDIUM_1` / `MEDIUM_2`, the
  same springs as two-leg footwork / as rung 2 of the reformer leg-press ladder it has to be harder
  than rung 1, not lighter; on one medium spring each leg worked against no more than in two-leg
  footwork.
- **P3-3.1 / P3-3.2 animation sketches** / keyframe values as sketched in the Phase 3 tables / solved
  against the rig, with these departures: waypoint keyframes with ACCEL/DECEL/LINEAR easing on seven
  animations (a single blend let planted hands and feet wander); `fl_pike_pushup` solved as an
  inverted V whose hips come forward at the bottom (the sketched leg angles put the feet in front of
  the hips); `rf_footwork_single` uses the current reformer foot and tabletop values (the sketched
  ones predate the reformer foot fix); `fl_single_leg_rdl` keeps a flat back; `fl_calf_raise` uses
  38° of plantarflexion; `fl_bridge_single` uses its own solved top pose / the Phase 3 document itself
  says its numbers are sketches to be solved numerically, and the geometry tests are the oracle.
- **P3-2.4** / one `Prop.BENCH` with `BENCH_Y = 0.22f` and a `drawBench` helper / Phase R shipped
  `Prop.BENCH_FRONT` and `Prop.BENCH_BACK` (same `BENCH_Y`), plus `STEP`, `ANCHOR` and
  `REFORMER_BOX_ARM_STRAPS` / its chair exercises need the bench on either side of the figure. Phase 3
  should reuse these.

