# Kinetiq — offline, science-based workout coach

A fully offline native Android app (Kotlin + Jetpack Compose) for a single user and four training
modalities — **Reformer Pilates, Elliptical (Infiniti VG50BS), Spin cycle (Horizon GR7), and
Floor/bodyweight** — that generates evidence-graded workout sessions with **en-AU voice coaching**
and **locally rendered procedural exercise animations**. Primary goal: visceral fat reduction;
secondary: general health.

- **100% offline** — the APK declares **no INTERNET permission** (enforced by a Gradle task on the
  merged release manifest, a unit test, and a CI `aapt` check). All exercise data, citations,
  animations and logic ship in the APK.
- **Optional Health Connect** — reads Weight / Body Fat % / Height (BMI computed in-app), writes
  completed sessions (`ExerciseSessionRecord` per block + `TotalCaloriesBurnedRecord`).
- Evidence base documented in [`RESEARCH.md`](RESEARCH.md); judgment calls in
  [`DECISIONS.md`](DECISIONS.md); screen-by-screen guide in [`WALKTHROUGH.md`](WALKTHROUGH.md).

Target device: **Motorola Edge 60 Fusion** (Android 15, 6.67" pOLED). `minSdk 34`, `targetSdk 36`.

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
# Debug build + all unit tests (generator, calorie math, DB validator, import validation)
./gradlew testDebugUnitTest assembleDebug

# Release build — also runs the merged-manifest no-INTERNET check
./gradlew assembleRelease
```

APKs land in `app/build/outputs/apk/{debug,release}/`.

GitHub Actions (`.github/workflows/build.yml`) runs tests, builds both APKs, re-verifies the
offline guarantee against the actual binary, and uploads the APKs as workflow artifacts on every
push.

## Signing a release for sideloading

The release build is configured to fall back to the debug key so `assembleRelease` always produces
an installable APK (fine for personal sideloading). For a proper signing key:

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

## Project layout

```
app/src/main/java/au/mark/kinetiq/
  anim/       procedural 14-joint rig, keyframe + IK animations, Canvas renderer, registry
  data/       Room DB, models, repositories, bundled-DB validator, export/import codec
  domain/     workout generator (duration solver, blocks, heuristics), MET calories,
              machine cue renderer, streaks, weekly plan engine
  health/     Health Connect manager (connect-client 1.1.0)
  reminders/  WorkManager reminder scheduling
  service/    foreground workout session service (timer, cues, notification, snapshot restore)
  ui/         Compose screens: onboarding, home, builder, player, summary, history,
              library, plan, settings, health data, hidden animation-QA
  voice/      TTS coach (en-AU preferred, audio-focus ducking, tone-generator beeps)
  widget/     Glance home-screen widget (repeat last workout + streak)
app/src/main/assets/exercise_db.json   versioned exercise/routine database (65 entries, all cited)
app/src/test/                          unit tests
```

## Acceptance checks

- `./gradlew testDebugUnitTest` — all green (DB validator included).
- `./gradlew assembleRelease` — prints `OK: merged release manifest has no INTERNET permission.`
- `aapt dump permissions app-release.apk` — no `android.permission.INTERNET` (CI does this too).
- Hidden animation-QA screen: Settings → long-press the version row.
