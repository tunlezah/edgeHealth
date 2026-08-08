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
