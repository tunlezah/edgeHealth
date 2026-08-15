# Kinetiq security audit — VERIFIED findings after adversarial review

Two-pass result. Pass 1 raised 18; pass 2 independently verified each against source (and against the
real navigation-runtime 2.8.5 sources pulled from Google Maven). Outcome: 3 dropped outright, most
reclassified to Info/hygiene, 2 new findings added. The app is in genuinely good security shape —
no logging, no WebView/reflection/dynamic loading, no SQL injection, all PendingIntents FLAG_IMMUTABLE,
no secrets committed, bundled asset fail-closed, no CI script injection.

## DROPPED — do not implement fixes for these
- **K-06 Navigation implicit deep links — FALSE POSITIVE.** `NavDestination.route`'s setter assigns a
  *private* `routeDeepLink` field, explicitly segregated from the public `deepLinks` list
  (see the verbatim doc comment at NavDestination.kt:246-251). `matchDeepLink` iterates only the
  public list and short-circuits on `deepLinks.isEmpty()`. Kinetiq declares zero deep links, so no
  URI can reach /health or /debug_anim.
- **K-13 over-broad ProGuard keeps** — obfuscation is not a security boundary; rules are functionally
  correct. APK-size commentary, not a finding.
- **K-15 registerReceiver without RECEIVER_NOT_EXPORTED** — ACTION_AUDIO_BECOMING_NOISY is a protected
  broadcast; receivers registered solely for protected broadcasts are exempt from the API 34 requirement.

## MOVED TO THE RELIABILITY TRACK
- **K-09 destructive migration** — confirmed real and severity RAISED, but it is a data-loss/reliability
  defect, not a security one. `AppModule.kt:31` `.fallbackToDestructiveMigration()` +
  `KinetiqDatabase.kt:16-17` `version = 1, exportSchema = false`. Any future version bump silently drops
  every table. The confidentiality half (no SQLCipher) is a non-finding on minSdk 34 with FBE.
  → Hand to the reliability fix designer, not this one.

---

# SURVIVING SECURITY FINDINGS TO FIX (ranked)

## S-1 — K-05: exported MainActivity accepts ACTION_REPEAT_LAST from any app · Low
`AndroidManifest.xml:35-45` MainActivity exported=true. `MainActivity.kt:84-88` `handleLaunchIntent`
checks only `intent?.action` — no caller/referrer/signature check. Fires from onCreate (`:70`) and
onNewIntent (`:79-82`). `MainViewModel.repeatLastWorkout` (`:43-59`) has only a clobber guard
("don't overwrite a live session", "no history → bail") — the PROGRESS.md "P1-4 widget/start guards"
item is a clobber guard, NOT an authorization guard.

Verified impact is a NUISANCE, not data fabrication. Pass 2 corrected four inflated claims:
  - The wake lock is NOT held 4 hours: `acquire(4h)` at `:287` is a TIMEOUT ceiling; released in
    onDestroy (`:680`), service stopSelf()s on finish (`:558`). Actual hold ≈ session length.
  - NO bogus history row or Health Connect record without the session actually elapsing in real time.
    `finishSession` is reached only via `Effect.Finished` (`:355`) or a user stop. HC write also
    requires `healthConnectEnabled`, which defaults to FALSE (`SettingsRepository.kt:56`).
  - Audio focus is AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK (`VoiceCoach.kt:224`) — ducks, does not seize.
  - Background activity-launch restrictions (Android 10+) mean the attacker must already be foreground.
FIX: add a real authorization/origin check for ACTION_REPEAT_LAST.

## S-2 — N-01 (NEW): imported history with an empty step list crashes via FGS watchdog · Low
The concrete failure the K-07 validation gap actually produces.
`ExportImport.kt:81-85` never checks `history[].session.plan.steps`, and import applies no bound on
`startedAtEpochMs`. `WorkoutDao.lastSession()` (`Daos.kt:59-60`) is `ORDER BY startedAtEpochMs DESC
LIMIT 1`, so a crafted far-future entry becomes "the last session". A widget tap
(`KinetiqWidget.kt:58-61`) or ACTION_REPEAT_LAST → `repeatLastWorkout` → `WorkoutSessionService.start`
→ `startForegroundService` (`:740`). In `startSession`,
`val first = session.plan.steps.firstOrNull() ?: return@launch` (`:137`) returns BEFORE `goForeground()`
at `:161` → startForeground never called, service never stopSelf()s →
ForegroundServiceDidNotStartInTimeException after ~5s.
NOTE: the same shape is latent in the normal path — `settingsRepo.current()` and
`measurementRepo.resolved()` (`:133-135`) are suspending reads that precede `goForeground()`.
FIX: validate steps on import AND call startForeground before any suspending work. **Coordinate the
service half with the reliability track — this is the same root cause as their R-01.**

## S-3 — K-07: import validation asymmetry · Low
`ExportImport.kt:75-85`. `savedWorkouts[]` gets three checks (blank name, empty steps, non-positive
durationSec). `history[]` gets only three weak ones (endedAt>=startedAt, totalActiveSec>=0, calories>=0).
`history[].blocks` (`:38`) and `history[].session` (`:39`) are entirely unvalidated. Picker accepts */*
(`SettingsScreen.kt:395`).
Health Connect chain is WEAKER than pass 1 claimed: `HistoryScreen.kt:191` gates the sync button on
`healthConnectEnabled && healthConnectWriteback`, and healthConnectEnabled defaults FALSE;
`HealthConnectManager.writeSession` (`:122-124`) re-checks `hasWritePermissions()`. Needs four
deliberate user actions and yields only garbage timestamps in the user's own HC store.
FIX: apply the same validation depth to history[].session and history[].blocks.

## S-4 — K-01/K-02: release signing provenance · Low
`app/build.gradle.kts:33-35` release signs with the debug config. Pass 2 correction: the debug
password/alias are public constants but the KEY MATERIAL is not — AGP generates a per-machine random
key. The trojan-update scenario requires already owning the dev machine's keystore file, and Android
refuses a differently-signed in-place install anyway. Documented decision (DECISIONS.md D-04) for a
single-user sideload app, so "High" was inflated.
The REAL defect: GitHub runners have no ~/.android/debug.keystore, so AGP mints a FRESH RANDOM KEY every
CI run (setup-gradle does not cache ~/.android). Every CI "release" APK has a different unauthenticated
key → in-place upgrade impossible, provenance nil. `.github/workflows/build.yml:49-57` uploads it, and
`on: pull_request` (`:5`) means PR trees build too. Public repo.
FIX: release-engineering, not attack mitigation. Document/handle properly.

## S-5 — K-03: cloud-backup encryption capability · Info
Pass 2 REVERSED pass 1's framing: `android:allowBackup` defaults to true and with NO rules file Android
backs up the ENTIRE data dir. These include-only rules therefore NARROW the backup set (they drop
filesDir root, where session_snapshot.json lives). Calling it "explicit opt-in, not the default" was
wrong — it is more restrictive than the default. Google cloud backup is E2E-encrypted with a
lockscreen-derived key since Android 9.
THE ONE ACTIONABLE ITEM: `<cloud-backup>` lacks `disableIfNoEncryptionCapabilities="true"`, so on a
device with NO SCREEN LOCK the data is backed up under a server-held key instead of being skipped.
minSdk 34 → data_extraction_rules.xml is operative; backup_rules.xml is legacy.
FIX: add that one attribute. Plus reconcile README "100% offline" wording with system backup.

## S-6 — N-02 (NEW): KEY_DEEP_LINK_IDS extras channel unauthenticated in Navigation 2.8.5 · Info
The narrow, CORRECT version of what K-06 was reaching for.
`NavController.handleDeepLink` (`:1405-1411`) reads
`intent.extras.getIntArray("android-support-nav:controller:deepLinkIds")` BEFORE any URI matching, with
no origin trust check. `findInvalidDestinationDisplayNameInDeepLink` (`:1575-1596`) only verifies the IDs
exist in the graph. Destination IDs are `createRoute(route).hashCode()` (`NavDestination.kt:823-824`) —
computable offline — and since `KinetiqNavHost.kt:162-166` passes no `route` to NavHost, the graph's own
ID is 0. So an app that can launch an activity may send explicit intent extras
`intArrayOf(0, "android-app://androidx.navigation/health".hashCode())` and land on /health or
/debug_anim, skipping the start-destination onboarding selection (`KinetiqNavHost.kt:164`) and the
disclaimer gate (`OnboardingScreen.kt:110-129`).
Bounded: cold start only (deepLinkHandled + singleTask mean onNewIntent does nothing); attacker cannot
read what is rendered; background-launch restrictions apply.
IMPORTANT: `androidx-main` adds a `shouldTrustIntent()` guard but NO RELEASED VERSION THROUGH 2.9.7 HAS
IT — verified against 2.8.9, 2.9.0, 2.9.3, 2.9.6, 2.9.7 sources. **Upgrading Navigation does NOT fix
this.** Must be fixed in app code.

## S-7 — K-04: TTS voice may be network-backed on the fallback path · Info
`VoiceCoach.kt:128-140`. The `!it.isNetworkConnectionRequired` filter at `:136` applies ONLY to the
en-AU upgrade pass; neither `setLanguage(auLocale)` (`:131`) nor the `setLanguage(Locale.getDefault())`
fallback (`:133`) is followed by a check on `engine.voice?.isNetworkConnectionRequired`.
Pass 2 pushback (fair, accept it): README:9 scopes the guarantee precisely to "the APK declares no
INTERNET permission", enforced three ways. Any egress happens in the USER'S CHOSEN TTS ENGINE's process
under ITS permissions — true of every Android app using TextToSpeech. Spoken content is exercise names
+ workout titles, not sensitive. Real code asymmetry, negligible impact.
FIX: cheap symmetry fix — drop any selected voice where isNetworkConnectionRequired is true.

## S-8 — K-14: implicit intent to TTS settings · Info
`SettingsScreen.kt:198` `Intent("com.android.settings.TTS_SETTINGS")` with no setPackage. Only implicit
intent in the app. No extras, no data, no result read, wrapped in runCatching. One-line hardening nit.
FIX: `setPackage("com.android.settings")`.

## S-9 — K-16: Health Connect rationale alias lands on Home · Info (Play policy, not security)
`AndroidManifest.xml:48-57` — permission gating IS correct (START_VIEW_PERMISSION_USAGE is
signature-level, system-only, not a reachable attack surface). But `MainActivity.handleLaunchIntent`
(`:84-88`) ignores VIEW_PERMISSION_USAGE so the launch lands on Home with no rationale.
FIX: route it to an actual rationale screen. Play compliance item.

## S-10 — Info-only, optional hardening; none is a defect in this app
- K-10 no FLAG_SECURE on HealthDataScreen (`:137-152`). Recents thumbnails live in system-owned
  FBE-encrypted storage, not readable by other apps; third-party screenshots need user-consented
  MediaProjection. Defends against shoulder-surfing/screen recorders only. A preference, not a defect.
- K-08 unbounded import read (`SettingsScreen.kt:124`). Pass 1's mechanism was FACTUALLY WRONG: Kotlin's
  `runCatching` catches Throwable, which INCLUDES OutOfMemoryError. User-operated SAF picker, no attacker.
  Size cap is hygiene.
- K-11 stale deps. No CVE identified by either pass. `androidx.media` → media3 is a deprecation.
- K-12 CI hygiene: no top-level `permissions:` block, actions at floating tags. Trigger is
  `pull_request` NOT `pull_request_target`, so fork PRs get a read-only token and no secrets.
- K-17 cleartext SAF export. SAF usage verified textbook-correct, path traversal structurally impossible.
  Fully user-initiated. At most a README line.
- K-18 ACTIVITY_RECOGNITION. Pass 2 REVERSED the over-privilege framing: on API 34+,
  FOREGROUND_SERVICE_TYPE_HEALTH REQUIRES one of ACTIVITY_RECOGNITION/BODY_SENSORS/
  HIGH_SAMPLING_RATE_SENSORS. Requesting it is the documented prerequisite, exactly as DECISIONS.md D-03
  states, and the code already degrades to MEDIA_PLAYBACK when denied. Not a defect.
