# Deviations from the phase specs

Record format: item / what the spec said / what was done / why.

- **P2-C11** / "Existing users see Kinetiq Mint unchanged (current hexes preserved)" /
  Mint light `primary` nudged `#1F8F6B` → `#1B8060` (all other hexes preserved) /
  The original color fails the spec's own WCAG gate (4.04:1 under white text vs the required
  4.5:1), and the spec rules the contrast test authoritative over individual hex values.
- **P1-5 → P2-A4** / Phase 1 specified a work:rest-ratio fixpoint solver / implemented, then
  replaced by the rest-mode solver in Phase 2 PR-1 / exactly as prescribed by
  IMPLEMENTATION_PLAN.md reconciliation #1 — noted here for traceability.
