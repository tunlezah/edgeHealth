# Kinetiq — offline, science-based workout coach

A fully offline native Android app (Kotlin + Jetpack Compose) for a single user and four training
modalities — **Reformer Pilates, Elliptical (Infiniti VG50BS), Spin cycle (Horizon GR7), and
Floor/bodyweight**, plus a physiotherapy-informed **Back care** category — that generates
evidence-graded workout sessions with **en-AU voice coaching** and **locally rendered procedural
exercise animations**. Primary goal: visceral fat reduction; secondary: general health.

- **No network permission** — the APK declares **no INTERNET permission**, enforced by a Gradle task on
  the merged release manifest, a unit test, and a CI `aapt` check. All exercise data, citations,
  animations and logic ship inside the APK. See [Privacy and the offline
  guarantee](#privacy-and-the-offline-guarantee) for the precise scope of that claim.
- **Optional Health Connect** — reads Weight / Body Fat % / Height (BMI computed in-app), writes
  completed sessions (`ExerciseSessionRecord` per block + `TotalCaloriesBurnedRecord`). Off by default.
- **Evidence-graded content** — every exercise carries an evidence tier and real citations
  (author, year, journal, DOI/PMID), readable offline in the app.

Target device: **Motorola Edge 60 Fusion** (Android 15, 6.67" pOLED). `minSdk 34`, `targetSdk 36`.
Current version: **1.1.0** (`versionCode 2`).

## Table of contents

- [What's in the app](#whats-in-the-app)
- [Bundled content](#bundled-content)
- [Architecture](#architecture)
- [Project layout](#project-layout)
- [Build prerequisites](#build-prerequisites)
- [Building](#building)
- [Testing](#testing)
- [Signing a release for sideloading](#signing-a-release-for-sideloading)
- [Installing on the Motorola Edge 60 Fusion](#installing-on-the-motorola-edge-60-fusion)
- [Privacy and the offline guarantee](#privacy-and-the-offline-guarantee)
- [Known issues](#known-issues)
- [Documentation map](#documentation-map)

## What's in the app

Bottom navigation — **Home · History · Library · Plan · Settings** — is permanent on every screen
except onboarding.

| Area | What it does |
|---|---|
| **Onboarding** | Medical disclaimer (must be acknowledged), body constraints, machine setup, optional Health Connect. Four steps, each with Back and a step indicator. |
| **Home** | Streak, week totals, build/repeat/saved workouts, resume-interrupted-session card. |
| **Builder** | Duration, categories, exercises per category, rest mode, intensity, warm-up/cool-down. Generates a preview you can reorder, swap and remove, with one-tap fixes for warnings. |
| **Player** | 10-second get-ready lead-in, countdown timer, large machine cues, procedural animation, skippable rests, +30 s, pause/skip/stop, explain-again. Runs in a foreground service with notification controls; snapshots every 5 s for process-death restore. |
| **Summary** | Active minutes, MET-estimated kcal, per-block breakdown, Health Connect write status, save-as-named-workout. |
| **History** | Streak, 4-week trends, month calendar, full session list with per-entry delete and Health Connect retry. |
| **Library** | Filter by category / target / evidence tier, live animation thumbnails, full offline reference list per exercise. |
| **Plan** | Rule-based weekly targets (WHO 2020 + visceral-fat dose evidence) with progress bars. |
| **Settings** | Voice and sound (six cue toggles, rate, volume), theme, workout defaults, Health Connect, body constraints, machines, reminders, streak rest days, units and goals, disclaimer, JSON export/import. |
| **Widget** | Glance home-screen widget: one-tap repeat-last-workout plus current streak. |

Voice coaching uses the system TTS engine with an en-AU preference, ducks other audio while speaking,
and adds 3-2-1 countdown beeps via `ToneGenerator`. Themes cover Light / Dark / AMOLED black / System
across seven accent palettes, all contrast-tested by a unit test.

See [`WALKTHROUGH.md`](WALKTHROUGH.md) for the screen-by-screen detail.

## Bundled content

`app/src/main/assets/exercise_db.json` (schema version 2, ~128 KB) ships:

| | Count |
|---|---|
| Exercises | **78** |
| Machine routines | **12** |
| Total entries | **90** |
| Citations across exercises | 93 |
| Procedural animations (`anim/AnimationRegistry.kt`) | **69** |

Exercises by category: Floor 27 · Reformer 17 · Back care 13 · Spin 12 · Elliptical 9.
By evidence tier: Moderate 42 · Strong 32 · Limited 4. Limited-tier entries are hidden until the user
opts in via Settings.

The database is validated at load time by `data/DatabaseValidator.kt` under a hard `check()` — roughly
40 field, range and cross-reference rules, including that every `animationId` resolves against the
animation registry. A malformed asset fails fast rather than degrading silently.

## Architecture

Single-module app, unidirectional data flow, Hilt for DI throughout.

```
Compose screen ──> ViewModel (StateFlow) ──> Repository ──> Room / DataStore / assets
                          │
                          └──> WorkoutSessionService (foreground) ──> SessionEngine (pure logic)
                                       │                                    │
                                       ├──> VoiceCoach (TTS + tones)        └──> tested in isolation
                                       └──> HealthConnectManager (optional)
```

Key decisions, all recorded with rationale in [`DECISIONS.md`](DECISIONS.md):

- **Session logic is a pure function.** `service/SessionEngine.kt` holds the timer, step advance, cue
  and calorie logic with no Android dependencies, driven by `onTick(rawDelta, now)`. This is what makes
  the session behaviour unit-testable; `WorkoutSessionService` is the Android shell around it.
- **Monotonic timing.** Ticks are measured as `SystemClock.elapsedRealtime()` deltas, never wall clock.
  Deltas above 2 s are deliberately *dropped* rather than fast-forwarded, so a stall never skips the user
  through exercises they didn't perform — this behaviour is asserted by `SessionEngineTest`.
- **Animations are typed Kotlin, not data.** A 14-joint rig with keyframe and IK animations
  (`anim/`), rendered to a Compose `Canvas`. Type safety and shared rigs beat a second JSON format.
- **Room stores exercises as JSON columns.** The bundled asset is the source of truth; rows carry the
  serialized model plus indexable columns, avoiding a 40-column entity for read-only reference content.
- **Process-death restore** persists a session snapshot every 5 s to a file (not Room), wiped on clean
  completion.

**Tech stack:** Kotlin 2.0.21 · AGP 8.9.2 · Compose BOM 2024.12.01 · Material 3 · Navigation Compose
2.8.5 · Room 2.6.1 · DataStore 1.1.1 · WorkManager 2.10.0 · Hilt 2.53.1 · Health Connect 1.1.0 ·
Glance 1.1.1 · kotlinx-serialization 1.7.3.

## Project layout

```
app/src/main/java/au/mark/kinetiq/
  anim/       procedural 14-joint rig, keyframe + IK animations, Canvas renderer, registry
  data/       Room DB, models, repositories, bundled-DB validator, export/import codec
  domain/     workout generator (duration solver, blocks, heuristics), MET calories,
              machine cue renderer, streaks, weekly plan engine
  health/     Health Connect manager (connect-client 1.1.0)
  reminders/  WorkManager reminder scheduling
  service/    foreground workout session service + pure SessionEngine
  ui/         Compose screens: onboarding, home, builder, player, summary, history,
              library, plan, settings, health data, hidden animation-QA
  voice/      TTS coach (en-AU preferred, audio-focus ducking, tone-generator beeps)
  widget/     Glance home-screen widget (repeat last workout + streak)
  di/         Hilt modules
app/src/main/assets/exercise_db.json   versioned exercise/routine database (90 entries, all cited)
app/src/test/                          15 unit-test classes, 99 tests
docs/audit/                            security and Android reliability audit reports
docs/plan/                             phased implementation plan and progress tracking
```

## Build prerequisites

| Tool | Version |
|---|---|
| JDK | 17–21 (CI uses Temurin 21) |
| Android SDK | Platform 36, Build-Tools 36.x (`sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools"`) |
| Gradle | Included wrapper (8.14.3) — use `./gradlew`, no local install needed |

Point the build at your SDK with either `ANDROID_HOME` or a `local.properties` containing
`sdk.dir=/path/to/android-sdk`.

## Building

```bash
# Debug build + all unit tests
./gradlew testDebugUnitTest assembleDebug

# Release build — also runs the merged-manifest no-INTERNET check
./gradlew assembleRelease
```

APKs land in `app/build/outputs/apk/{debug,release}/`.

GitHub Actions (`.github/workflows/build.yml`) runs tests, builds both APKs, re-verifies the offline
guarantee against the actual binary with `aapt dump permissions`, and uploads the APKs as workflow
artifacts on every push.

> **Note:** CI runners have no `~/.android/debug.keystore`, so AGP generates a fresh random debug key
> on each run. CI release APKs are therefore signed with a *different key every build* — fine for
> smoke-testing, but you cannot install one over another, and they carry no stable provenance. Build
> locally (or configure a real keystore) for anything you intend to keep installed.

## Testing

```bash
./gradlew testDebugUnitTest
```

99 tests across 15 classes, running offline under Robolectric (the `android-all` runtime jar is
resolved through Gradle rather than Robolectric's own fetcher, so CI needs no network).

| Test class | Covers |
|---|---|
| `SessionEngineTest` | Timer, step advance, tick-delta clamp, skip semantics, calorie accrual |
| `SessionSnapshotCompatTest` | Snapshot schema forward/backward compatibility |
| `WorkoutGeneratorTest` / `…TimeBudgetTest` | Generator correctness and duration solver across the full config matrix (±5%) |
| `DatabaseValidatorTest` | The bundled-DB rule set |
| `AnimGeometryTest` | Rig FK/IK geometry — support contacts actually land |
| `CalorieAndCueTest` | MET calorie math and machine cue rendering |
| `RestModeTest` | Rest-mode-driven session shaping and trailing-rest regression |
| `VoiceCoachStatusTest` | TTS init status, retry and queue behaviour |
| `ThemePaletteContrastTest` | WCAG contrast for all 7 palettes × 4 modes |
| `ExportImportAndMiscTest` | Export/import round-trip and import validation |
| `HealthConnectIdTest` | Client record IDs and per-timestamp zone offsets |
| `SettingsRoundTripTest`, `SummaryNavigationTest`, `DisplayNameTest` | Settings persistence, summary navigation identity, category display names |

## Signing a release for sideloading

The release build falls back to the debug key so `assembleRelease` always produces an installable APK
(adequate for personal sideloading; see `DECISIONS.md` D-04). For a proper signing key:

```bash
# 1. Generate a keystore (once; keep it safe — losing it means you can't update in place)
keytool -genkeypair -v -keystore kinetiq.keystore -alias kinetiq \
        -keyalg RSA -keysize 2048 -validity 10000

# 2. Wire it in: app/build.gradle.kts → android { signingConfigs { create("release") { ... } } }
#    or sign after the fact:
zipalign -f -p 4 app/build/outputs/apk/release/app-release.apk kinetiq-aligned.apk
apksigner sign --ks kinetiq.keystore --ks-key-alias kinetiq --out kinetiq-signed.apk kinetiq-aligned.apk
apksigner verify kinetiq-signed.apk
```

Android will not install an APK over an existing install signed with a different key, so switching
signing keys later means uninstalling first — **which deletes all workout history and body
measurements.** Export your data from Settings before doing that.

## Installing on the Motorola Edge 60 Fusion

**Via adb**

1. On the phone: Settings → About phone → tap **Build number** 7× to enable Developer options,
   then Settings → System → Developer options → enable **USB debugging**.
2. Connect via USB, accept the debugging prompt, then:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

**Via file manager (no PC)**

1. Copy the APK to the phone (USB transfer, or download the CI artifact on the phone).
2. Open it from the Files app; allow **Install unknown apps** for that app when prompted; install.

**First run:** complete onboarding (disclaimer → body constraints → machine setup → optional
Health Connect). Then enable airplane mode and everything still works — that's the point.

**Voice data tip:** for guaranteed offline en-AU speech, open Settings → *System TTS settings*
(linked from Kinetiq's Settings) and download the offline English (Australia) voice.

## Privacy and the offline guarantee

The guarantee is specific and worth stating precisely, because two things sit outside it.

**What is guaranteed.** The APK declares no `android.permission.INTERNET`. Without it the app's process
cannot open a socket — no telemetry, no analytics, no content updates, no crash reporting. This is
verified three ways: a Gradle task on the merged release manifest, a unit test, and a CI step running
`aapt dump permissions` against the built APK. WorkManager's library manifest contributes
`ACCESS_NETWORK_STATE` and `RECEIVE_BOOT_COMPLETED`; neither grants network I/O.

**What sits outside it.**

1. **System TTS.** Spoken cues are handed to whichever text-to-speech engine the user has installed,
   which runs in its own process under its own permissions. If that engine is configured with a
   network-synthesis voice, the *text* of a cue (exercise names, workout titles) is processed there.
   The app prefers a local en-AU voice, and Settings links to the system TTS screen to download offline
   voice data — but no app using `TextToSpeech` can guarantee the engine's behaviour.
2. **Android backup.** `android:allowBackup` is enabled, and `res/xml/data_extraction_rules.xml` includes
   the Room database, shared preferences and DataStore in both cloud backup and device-to-device
   transfer. That means workout history and body measurements can be copied off-device by the *platform*
   backup transport. Google's cloud backup is end-to-end encrypted with a key derived from the device
   lockscreen, so Google cannot read it — but if you want nothing leaving the device at all, set
   `android:allowBackup="false"` in `AndroidManifest.xml`.

**Data stored on device:** workout history, saved workouts, manual body measurements (weight, height,
body fat %, waist, visceral rating), cached Health Connect values, and settings. All in app-private
storage, protected by Android's file-based encryption. Export writes unencrypted JSON to a location you
choose via the system file picker — treat that file as sensitive.

**Health Connect** is off by default and requires explicit permission grants. Reads are limited to
weight, body fat and height; writes are limited to completed exercise sessions and total calories.

## Known issues

The app has been through a two-pass security audit and a two-pass Android reliability audit, both with
independent adversarial verification. Full reports, with verified findings and rejected false positives,
are in [`docs/audit/`](docs/audit/).

Summary of what is outstanding at the time of writing:

- **No high-severity security findings.** The audit confirmed no logging of sensitive data, no SQL
  injection, no WebView or dynamic code loading, correct `FLAG_IMMUTABLE` on all PendingIntents, correct
  SAF usage with no path-traversal surface, and no CI script-injection vector.
- **One critical reliability defect:** resuming a stopped workout from the Summary screen after the
  10-minute recovery window has elapsed deletes the history row before confirming the restore can
  succeed, then crashes the foreground service. See `docs/audit/reliability-verified.md` (L-1).
- **One high-severity data-loss risk:** the Room database uses `fallbackToDestructiveMigration()` at
  schema version 1 with `exportSchema = false`. The next entity change will silently erase all user data
  unless a schema baseline and real migrations land first (L-2). **Do not bump the database version
  before fixing this.**
- **One high-severity performance defect:** history queries deserialize every row's full session JSON on
  the main thread, and the decoded field is unused by all three screens that trigger it. The freeze grows
  linearly with history size (L-3).

Phase 3 of the implementation plan (database and animation expansion) is in progress — see
[`docs/plan/PROGRESS.md`](docs/plan/PROGRESS.md) for the item-level state.

## Documentation map

| Document | Contents |
|---|---|
| [`README.md`](README.md) | This file — build, install, architecture, privacy |
| [`WALKTHROUGH.md`](WALKTHROUGH.md) | Screen-by-screen feature guide |
| [`DECISIONS.md`](DECISIONS.md) | Judgment calls (D-01…D-19) with rationale |
| [`RESEARCH.md`](RESEARCH.md) | Evidence base and citations behind the exercise database |
| [`docs/audit/`](docs/audit/) | Security and reliability audit reports and fix designs |
| [`docs/plan/`](docs/plan/) | Phased implementation plan, progress, deviations |

## Acceptance checks

- `./gradlew testDebugUnitTest` — 99 tests green (DB validator included).
- `./gradlew assembleRelease` — prints `OK: merged release manifest has no INTERNET permission.`
- `aapt dump permissions app-release.apk` — no `android.permission.INTERNET` (CI does this too).
- Hidden animation-QA screen: Settings → long-press the version row.
