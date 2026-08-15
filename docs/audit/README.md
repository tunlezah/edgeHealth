# Audit reports

Kinetiq has been through two independent audits, each run as a three-stage process:

1. **Find** — an auditor reads the source and casts a wide net, marking confidence honestly.
2. **Verify** — a second, deliberately skeptical auditor independently re-checks every finding against
   the source (and, where the claim rests on framework behaviour, against the framework's own sources).
   Its job is to kill false positives, not to agree.
3. **Design** — a third engineer designs remediations that don't break the application.

Only stage-2 output is treated as fact. Stage-1 reports are not kept, because several of their
headline claims did not survive verification.

## Reports

| File | Contents |
|---|---|
| [`security-verified.md`](security-verified.md) | Verified security and privacy findings |
| [`security-fixes.md`](security-fixes.md) | Remediation designs for the surviving security findings |
| [`reliability-verified.md`](reliability-verified.md) | Verified Android reliability, memory and performance findings |
| [`reliability-fixes.md`](reliability-fixes.md) | Remediation designs for the surviving reliability findings |

## Reading these

Each verified report is split into three parts, and **all three matter**:

- **Rejected** — findings disproved during verification. Do not "fix" these; the reports record *why*
  each was rejected so the same false positive doesn't get re-raised later.
- **Reclassified** — real code observations that are deliberate design, a different category of
  problem, or not worth acting on. Notably, `SessionEngine`'s tick-delta clamp is asserted by a passing
  test and must not be "corrected".
- **Surviving findings** — ranked, with corrected severities and independently checked file:line
  evidence.

## Headline results

**Security:** no high-severity findings. Three pass-1 findings were rejected outright, including the
claim that Jetpack Navigation's implicit deep links let another app bypass the medical-disclaimer gate
— disproved by reading the navigation-runtime 2.8.5 sources, where route-derived deep links are held in
a private field explicitly segregated from the matchable list. Two findings the first pass missed were
added. Several severities were corrected downward after the exploit chains were traced end to end.

**Reliability:** one Critical, three High. The Critical was confirmed but the first pass had aimed it at
the wrong code path — the reachable trigger is the Summary screen's stopped-workout Resume button, which
is gated on a composition-time check that never re-runs and which deletes the history row before
confirming the restore can succeed. Five findings were rejected and two reclassified as deliberate,
tested design.

## Scope note

These audits cover the application source, build configuration, manifest, and CI workflow. They were
performed on the codebase by its owner for defensive purposes. Findings are recorded with enough
detail to be reproducible and to be re-checked as the code changes.
