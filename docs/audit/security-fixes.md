# Security remediation design (S-1 … S-10)

Designs for the findings that survived adversarial verification in
[`security-verified.md`](security-verified.md). Dropped findings (K-06, K-13, K-15) and the
reliability-track finding (K-09) are not addressed here.

S-2's *service* half is deferred to reliability finding L-1; only the import-validation half is
designed here, plus a ViewModel-side guard deliberately placed in a different file so the two
patches cannot conflict.

Ordered by implementation priority.

---

## 1. S-2 (import half) + S-3 — Import validation asymmetry

First because it is the only surviving finding that produces a real, user-visible failure (an FGS
watchdog crash), and it is a pure-function change with no UI surface.

### Change — `data/export/ExportImport.kt`

Add a plausibility floor and a future bound on history timestamps, and validate the previously
unchecked `history[].session` and `history[].blocks`:

```kotlin
/** Kinetiq did not exist before 2020 — anything older is a corrupt or hand-edited timestamp. */
internal const val MIN_PLAUSIBLE_EPOCH_MS = 1_577_836_800_000L // 2020-01-01T00:00:00Z

/** Grace for clock skew and timezone confusion when judging "in the future". */
internal const val FUTURE_GRACE_MS = 24L * 60 * 60 * 1000

fun decodeAndValidate(
    raw: String,
    nowEpochMs: Long = System.currentTimeMillis(),
): ImportResult {
    // ... existing parse + formatVersion checks ...

    parsed.savedWorkouts.forEachIndexed { i, w ->
        if (w.name.isBlank()) problems += "Saved workout #${i + 1}: blank name"
        problems += planProblems("Saved workout '${w.name}'", w.session)
        warnings += planWarnings("Saved workout '${w.name}'", w.session)
    }

    parsed.history.forEachIndexed { i, h ->
        val label = "History entry #${i + 1}"
        if (h.endedAtEpochMs < h.startedAtEpochMs) problems += "$label: ends before it starts"
        if (h.totalActiveSec < 0) problems += "$label: negative active time"
        if (h.calories < 0) problems += "$label: negative calories"
        // An entry that sorts to the top of lastSession() drives the widget's one-tap start,
        // so an implausible start time is a correctness problem, not a cosmetic one.
        if (h.startedAtEpochMs < MIN_PLAUSIBLE_EPOCH_MS)
            problems += "$label: start time is before 2020 — not a real Kinetiq session"
        if (h.startedAtEpochMs > nowEpochMs + FUTURE_GRACE_MS)
            problems += "$label: start time is in the future"

        // A history entry may legitimately carry NO session: importHistory() writes an empty
        // sessionJson for such rows and toModel() decodes it back to null. Only validate a
        // plan that is actually present.
        h.session?.let {
            problems += planProblems(label, it)
            warnings += planWarnings(label, it)
        }

        h.blocks.forEachIndexed { b, block ->
            validateBlock("$label, block #${b + 1}", block, problems, warnings)
        }
    }

    return if (problems.isEmpty()) ImportResult.Success(parsed, warnings)
           else ImportResult.Failure(problems)
}

/**
 * Conditions that make a plan unplayable. A stored session with no steps is the concrete cause
 * of the FGS watchdog crash: repeat-last starts the service, startSession() bails on
 * `steps.firstOrNull()` before it ever reaches goForeground().
 */
private fun planProblems(label: String, session: GeneratedSession): List<String> = buildList {
    if (session.plan.steps.isEmpty()) add("$label: plan has no steps")
    if (session.plan.steps.any { it.durationSec <= 0 }) add("$label: step with non-positive duration")
}

/**
 * Odd but harmless: never reject a whole file over these, or the user loses every other entry.
 * Note what is deliberately NOT flagged — blockIndex -1 (warm-up) and -2 (cool-down) are the
 * generator's sentinels (WorkoutGenerator:553-554) and completedBlocks() relies on them being
 * outside plan.blocks.indices.
 */
private fun planWarnings(label: String, session: GeneratedSession): List<String> = buildList {
    val sentinels = setOf(WorkoutGenerator.WARMUP_BLOCK_INDEX, WorkoutGenerator.COOLDOWN_BLOCK_INDEX)
    val valid = session.plan.blocks.indices
    if (session.plan.steps.any { it.blockIndex !in valid && it.blockIndex !in sentinels })
        add("$label: a step points at a block that is not in the plan; its time will not be counted.")
}

private fun validateBlock(
    label: String,
    block: CompletedBlock,
    problems: MutableList<String>,
    warnings: MutableList<String>,
) {
    if (block.activeSec < 0) problems += "$label: negative active time"
    if (block.calories < 0) problems += "$label: negative calories"
    // A device clock change mid-session can genuinely invert these — warn, do not reject.
    if (block.endedAtEpochMs < block.startedAtEpochMs) warnings += "$label: ends before it starts."
    if (Category.entries.none { it.name == block.category })
        warnings += "$label: unknown category '${block.category}'."
}
```

### Complementary guard — `MainActivity.kt`, `MainViewModel.repeatLastWorkout`

```kotlin
viewModelScope.launch {
    val last = workoutRepository.lastSession() ?: return@launch
    val session = last.session ?: return@launch
    // Nothing playable — never hand the service a plan it cannot foreground. This closes the
    // FGS-watchdog path for rows that predate import validation (or arrive by any other route).
    if (session.plan.steps.isEmpty()) return@launch
    WorkoutSessionService.start(...)
    pendingPlayerLaunch.value = true
}
```

Belt to the import validation's braces, and it lives in `MainActivity.kt` — **not** in
`WorkoutSessionService.kt` — so it cannot collide with the L-1 patch to `startSession`.

### Why it's correct

- `ExportedHistoryEntry.session` is declared `GeneratedSession?` and is *legitimately* null:
  `WorkoutRepository.toModel()` (`:131`) does `runCatching{}.getOrNull()`, and `importHistory`
  (`:113`) writes `sessionJson = entry.session?.let{} ?: ""`. Validating unconditionally would
  reject re-exports of previously-imported data. **The `h.session?.let {}` shape is load-bearing.**
- `blocks` is legitimately empty: `completedBlocks()` (`SessionEngine.kt:218-219`) returns `null`
  for any block with `activeSec <= 0`, so a session abandoned during warm-up produces `emptyList()`.
  The existing test at `ExportImportAndMiscTest.kt:42` already exercises this.
- **Warm-up/cool-down sentinels.** `WorkoutGenerator.kt:553-554` defines `WARMUP_BLOCK_INDEX = -1`
  and `COOLDOWN_BLOCK_INDEX = -2`, applied at `:118` and `:146`. A naive `blockIndex >= 0` check
  would reject every export from a workout with a warm-up — i.e. the default
  (`GeneratorConfig.warmup = true`). This design tolerates them explicitly and asserts on them.
- The duration/emptiness rules are not new policy: they are exactly what `savedWorkouts[]` has
  enforced since v1, and history sessions come from the same generator.
- `nowEpochMs` as a defaulted parameter keeps the existing call site in `SettingsScreen.kt:126`
  source-compatible and makes the future-timestamp rule deterministic under test.

### What could break

| Flow | Verdict |
|---|---|
| Export → import round trip (`ExportImportAndMiscTest:37`) | Unaffected — all new rules pass |
| History rows with `session == null` | Explicitly preserved; would have broken under a naive fix |
| History rows with `blocks == emptyList()` | Explicitly preserved; no non-empty requirement |
| Warm-up/cool-down steps (`blockIndex` −1/−2) | Explicitly whitelisted; test asserts the generator emits them |
| Sessions with zero `plan.blocks` | Non-sentinel indices produce a *warning*, never a rejection |
| Import from a newer version with a new `Category` | Warning only, entry still imported |
| Fast clock / different timezone | 24 h future grace absorbs it |
| Health Connect writeback | Unchanged |

**One behaviour change worth stating plainly:** a file containing *any* far-future or zero-step
history entry is now rejected **wholly**, not partially. That matches the existing all-or-nothing
contract of `decodeAndValidate`, so it is not a new pattern — but a single bad row costs the user
the whole import. The alternative (demote to warnings, filter in `applyImport`) is **not
recommended**: partial-import semantics are a new contract and a bigger behavioural change than
the fix itself.

### Test — `ExportImportAndMiscTest.kt`

Four additions: empty-plan history rejected; `session == null` history still accepted; far-future
entry rejected; and the real safety net —

```kotlin
@Test
fun `a freshly generated session survives import validation unchanged`() {
    val db = Json { ignoreUnknownKeys = true }.decodeFromString(
        ExerciseDatabaseFile.serializer(), File("src/main/assets/exercise_db.json").readText())
    val generated = WorkoutGenerator(db.exercises, db.routines, random = Random(42))
        .generate(GeneratorConfig(totalDurationMin = 30, categories = listOf(Category.FLOOR, Category.SPIN)))
        .session
    // The generator really does emit the sentinels this validator must tolerate.
    assertThat(generated.plan.steps.map { it.blockIndex })
        .containsAtLeast(WorkoutGenerator.WARMUP_BLOCK_INDEX, WorkoutGenerator.COOLDOWN_BLOCK_INDEX)

    val now = 1_800_000_000_000L
    val file = ExportFile(now,
        savedWorkouts = listOf(ExportedWorkout("Gen", now, generated)),
        history = listOf(ExportedHistoryEntry(now - 1_800_000, now, "Gen", 1500, 180.0, emptyList(), generated)))
    val result = ExportImportCodec.decodeAndValidate(ExportImportCodec.encode(file), nowEpochMs = now)
    assertThat(result).isInstanceOf(ExportImportCodec.ImportResult.Success::class.java)
    assertThat((result as ExportImportCodec.ImportResult.Success).warnings).isEmpty()
}
```

This proves the new rules never reject what `buildExport()` can actually produce, using the real
bundled `exercise_db.json` the other generator tests already read offline.

### Risk: **Safe**
Pure functions, no Android APIs, covered offline by the existing Robolectric-free test path.

---

## 2. S-1 — ACTION_REPEAT_LAST authorization (keeping the widget working)

### Research that drives the design

Pulled `glance-appwidget-1.1.1-sources.jar` from Google Maven and read the dispatch path.

1. **The widget is not inside a lazy collection**, so `applyAction` (`ApplyAction.kt:59-66`) takes
   the `getPendingIntentForAction` branch. For `StartActivityIntentAction`, `getStartActivityIntent`
   returns **our Intent verbatim** (`:331`), merging only `ActionParameters` (we pass none).
   **Extras we set survive intact**, and the PendingIntent is created by *our* context, `FLAG_IMMUTABLE`.
2. **`getCallingPackage()` / `getCallingActivity()` are unusable.** Verified against API 34
   `Activity.java`: populated only for `startActivityForResult`. The Glance path is a plain
   `PendingIntent.getActivity`, so both are `null`. The finding's suspicion is confirmed — a caller
   check on that basis simply cannot work.
3. **`getReferrer()` is unusable.** It returns `Intent.EXTRA_REFERRER` first, which is attacker-settable.
4. **`getLaunchedFromUid()` is public at API 34 and unspoofable** — but it is **the wrong tool here
   and would break the widget.** It describes the *ActivityRecord's original launch*, not the current
   intent. `MainActivity` is `launchMode="singleTask"`, so a widget tap while the app is already
   running arrives via `onNewIntent` on a record launched by the Launcher — `getLaunchedFromUid()`
   would report the Launcher's uid and **the app's headline one-tap feature would silently die on
   every warm start.** This is the trap the design must avoid.

**Conclusion: the authorization must travel *with the intent*.** A `PendingIntent` created by us,
carried as an extra, is the correct mechanism — `PendingIntent.getCreatorPackage()` is documented as
system-supplied specifically so an app cannot spoof it, and it works identically in `onCreate` and
`onNewIntent`.

### Change — `MainActivity.kt`

```kotlin
/**
 * Pure gate for the widget's repeat-last launch, in the style of shouldNavigateToSummary():
 * the action must match AND the intent must carry a PendingIntent this app created.
 */
internal fun shouldRepeatLast(action: String?, tokenCreatorPackage: String?, selfPackage: String): Boolean =
    action == MainActivity.ACTION_REPEAT_LAST &&
        tokenCreatorPackage != null &&
        tokenCreatorPackage == selfPackage

private fun handleLaunchIntent(intent: Intent?) {
    intent ?: return
    val creator = runCatching {
        intent.getParcelableExtra(EXTRA_ORIGIN_TOKEN, PendingIntent::class.java)?.creatorPackage
    }.getOrNull()
    // Any app may launch MainActivity — it is the LAUNCHER activity. Only our own widget can
    // prove it sent this, so an unauthorised REPEAT_LAST just opens the app on Home.
    if (shouldRepeatLast(intent.action, creator, packageName)) {
        viewModel.repeatLastWorkout(this)
    }
}

companion object {
    const val ACTION_REPEAT_LAST = "au.mark.kinetiq.REPEAT_LAST"

    /**
     * Proof-of-origin for ACTION_REPEAT_LAST. PendingIntent.getCreatorPackage() is supplied by
     * the system precisely so an app cannot spoof its package, and only this app can mint a
     * PendingIntent attributed to this app. Unlike getLaunchedFromUid(), it travels with the
     * intent, so it is just as valid on the singleTask onNewIntent path as on a cold start.
     */
    private const val EXTRA_ORIGIN_TOKEN = "au.mark.kinetiq.extra.ORIGIN_TOKEN"

    /** No receiver is registered for this; the token is an identity stamp, never fired. */
    private const val ACTION_ORIGIN_TOKEN = "au.mark.kinetiq.INTERNAL_ORIGIN_TOKEN"

    /** The one-tap "repeat last workout" launch intent. Only this app can build a valid one. */
    fun repeatLastIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = ACTION_REPEAT_LAST
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(
                EXTRA_ORIGIN_TOKEN,
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(ACTION_ORIGIN_TOKEN).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
}
```

### Change — `widget/KinetiqWidget.kt`

```kotlin
val launchIntent = MainActivity.repeatLastIntent(context)
Column(modifier = GlanceModifier.fillMaxSize() /* ... */ .clickable(actionStartActivity(launchIntent)))
```

Drop the now-unused `import android.content.Intent`, and fix the stale KDoc at `:34` (it references
"EXTRA_REPEAT_LAST", which never existed).

### Why it's correct

- `PendingIntent.getCreatorPackage()` — "The returned string is supplied by the system, so that an
  application can not spoof its package."
- Glance preserves the extras (verified in `getStartActivityIntent`, `ApplyAction.kt:323-341`). Even
  if the widget content later moves inside a `LazyColumn` — switching Glance to
  `setOnClickFillInIntent` — the fill-in intent still carries our extras, so the token survives.
- `Intent.getParcelableExtra(String, Class<T>)` is API 33+; minSdk 34 ✔. `FLAG_IMMUTABLE` is
  mandatory at API 31+ and is set ✔.
- `PendingIntent.getBroadcast` never resolves its target at creation time, so an unregistered action
  is fine; the token is inert if fired (explicit `setPackage`, no receiver).
- The outer Glance PendingIntent's cache key is `(requestCode, Intent.filterEquals)`, which ignores
  extras — so adding the token does not change the key, and `FLAG_UPDATE_CURRENT` refreshes the
  stored extras on every widget update.

### What could break

Grepped every sender: **`KinetiqWidget.kt:59` is the only producer of `ACTION_REPEAT_LAST` in the
repo.** `Reminders.kt:90` builds a bare `Intent(context, MainActivity::class.java)` with no action.

| Flow | Verdict |
|---|---|
| Widget tap, app not running (cold start → `onCreate`) | Token present. **Works** |
| Widget tap, app already running (`singleTask` → `onNewIntent`) | Same PendingIntent, token present. **Works** — the case `getLaunchedFromUid()` would have broken |
| Widget tap, live session running | Existing clobber guard hits first, opens player. Unchanged |
| Launcher icon (`ACTION_MAIN`) | Action doesn't match; untouched |
| Reminder notification tap | No action set; untouched |
| Recents / process-death restore | System re-delivers the original intent with extras. **Works** |
| Third-party `startActivity(action = REPEAT_LAST)` | No token → app opens on Home. Graceful |
| R8 / ProGuard | `MainActivity` is manifest-kept; no reflection |

**One honest regression window.** After an in-place upgrade, the launcher may briefly hold the *old*
RemoteViews whose PendingIntent has no token. A tap in that window opens the app on Home instead of
starting the workout. It self-heals on the next `APPWIDGET_UPDATE` (which the system broadcasts on
package replacement). Window is seconds; failure mode is "app opens", not a crash. Worth a release-note
line, not worth engineering around.

**Alternative considered and rejected:** a non-exported `<activity-alias>` targeted by the widget's
PendingIntent. Structurally stronger (OS-enforced), and it *would* work. Rejected because it depends
on `Intent.getComponent()` reporting the alias rather than the target after framework resolution,
which could not be verified from source with confidence, and `singleTask` task-matching across an
alias adds a second unverified assumption. For a Low-severity nuisance finding, two unverified
framework assumptions is a worse trade than one verified one.

### Test — new `RepeatLastOriginTest.kt`

Pure-function gate tests (shadow-independent) plus a Robolectric check that the widget intent carries
a token whose `creatorPackage` is ours, and that a forged intent without the token is rejected.

### Risk: **Needs care**

**Verify on device:** (a) widget tap with the app fully closed starts the last workout and lands on
the player; (b) widget tap with the app already open in the foreground on Settings does the same
(the `onNewIntent` case, most likely to regress); (c) widget tap during a live session opens the
player without restarting; (d) after installing over the previous version the widget still works
(may need one system widget refresh).

---

## 3. S-6 — Strip Navigation's `deepLinkIds` extras

### Change — `MainActivity.kt`

```kotlin
/**
 * Navigation 2.8.5's NavController.handleDeepLink() reads
 * "android-support-nav:controller:deepLinkIds" from the launch intent with no origin check, and
 * destination ids are just createRoute(route).hashCode() — computable offline. androidx-main has
 * a shouldTrustIntent() guard but NO released version through 2.9.7 does, so upgrading Navigation
 * does not fix this. Kinetiq declares zero deep links, so these extras are never legitimate here.
 */
private val NAV_DEEP_LINK_EXTRAS = listOf(
    "android-support-nav:controller:deepLinkIds",
    "android-support-nav:controller:deepLinkArgs",
    "android-support-nav:controller:deepLinkExtras",
    "android-support-nav:controller:deepLinkHandled",
    "android-support-nav:controller:deepLinkIntent",
)

private fun stripNavigationExtras(intent: Intent?) {
    intent ?: return
    // Extras that cannot even be unparcelled are certainly not ours — drop the lot.
    if (runCatching { NAV_DEEP_LINK_EXTRAS.forEach(intent::removeExtra) }.isFailure) {
        intent.replaceExtras(null as Bundle?)
    }
}
```

Wired in first, before anything else reads extras — in `onCreate` (before `setContent`) and in
`onNewIntent`.

### Why it's correct

Verified against the actual 2.8.5 sources:

- `NavController.kt:2827-2841` defines the five constants verbatim.
- `NavController.kt:1365-1367`: `val deepLinked = !deepLinkHandled && activity != null &&
  handleDeepLink(activity!!.intent)` — invoked from `onGraphCreated`, i.e. during `setGraph`, which
  happens inside composition. Stripping in `onCreate` **before** `setContent` runs strictly earlier.
- `handleDeepLink` (`:1405-1411`) reads `intent.extras.getIntArray(KEY_DEEP_LINK_IDS)` *before* any
  URI matching. Once gone, the URI branch finds nothing (zero declared deep links), `handleDeepLink`
  returns false, and the graph navigates to its start destination — the normal path.
- `Intent.removeExtra` triggers `Bundle.unparcel()` and can throw `BadParcelableException`; the
  `runCatching` + `replaceExtras(null)` fallback fails closed.
- Ordering vs S-1: the strip runs first. In the pathological `replaceExtras(null)` case the origin
  token is also lost, so the widget degrades to "just open the app" — the correct fail-closed
  outcome, and unreachable for a real widget intent.

### What could break

| Flow | Verdict |
|---|---|
| The app's own navigation | Every transition is `navController.navigate(route)`. Nothing in `app/src/main` references any `KEY_DEEP_LINK_*` constant ✔ |
| `navigateUp()` | `NavController.kt:936` reads `KEY_DEEP_LINK_IDS` — but **`grep -rn navigateUp app/src/main` returns zero hits**; the app uses `popBackStack` exclusively ✔ |
| Widget launch | Token key is not a nav key; survives ✔ |
| `consumedSummaryId` rememberSaveable (`KinetiqNavHost.kt:108`) | Backed by `savedInstanceState` via Compose's `SaveableStateRegistry`, separate from the launch Intent ✔ |
| `rememberNavController` back-stack restore | Same mechanism, not the Intent ✔ |
| Process-death restore | System re-delivers the launch intent; `onCreate` strips again ✔ |
| Onboarding gate | Preserved: with no deep link, `startDestination` is `Routes.ONBOARDING` when `!onboardingComplete` ✔ |

### Test — new `NavExtraStrippingTest.kt`
Asserts nav extras are dropped while unrelated extras and the action survive, plus a guard test that
the key list still matches Navigation's own constants (so an upgrade renaming them is caught).

### Risk: **Safe** — the only behavioural change is on intents the app never legitimately receives.

---

## 4. S-5 — `disableIfNoEncryptionCapabilities`

### Change — `res/xml/data_extraction_rules.xml`

```xml
<data-extraction-rules>
    <!-- Only back up to the cloud when the device can encrypt it (i.e. the user has a screen
         lock). Without this, a device with no lock screen uploads under a server-held key.
         Device-to-device transfer is unaffected — D2D never reaches Google's servers. -->
    <cloud-backup disableIfNoEncryptionCapabilities="true">
        <include domain="database" path="." />
        <include domain="sharedpref" path="." />
        <include domain="file" path="datastore/" />
    </cloud-backup>
    <device-transfer>
        <include domain="database" path="." />
        <include domain="sharedpref" path="." />
        <include domain="file" path="datastore/" />
    </device-transfer>
</data-extraction-rules>
```

Plus a README sentence scoping the offline claim against system backup.

### Why it's correct

- The attribute is **unprefixed** (no `android:` namespace) and declared **on `<cloud-backup>`**,
  exactly like the existing unprefixed `domain`/`path` attributes.
- Documented semantics: backups are not sent to the cloud if the device cannot support encryption,
  but D2D transfers continue to operate because they aren't sent to a server.
- minSdk 34 / targetSdk 36 means `android:dataExtractionRules` is operative on every device this app
  runs on; `android:fullBackupContent` is legacy (API ≤ 30) and inert. Leave it — removing it is
  churn with no benefit.
- **It cannot fail manifest merge** — this is an XML *resource*, not a manifest fragment; the merger
  never parses it.

### What could break

| Flow | Verdict |
|---|---|
| User **with** a screen lock (overwhelming default) | Cloud backup proceeds exactly as today ✔ |
| Restore onto a new device with a screen lock | Unchanged ✔ |
| Device-to-device transfer | Attribute is scoped to `<cloud-backup>`; D2D untouched ✔ |
| User with **no** screen lock | Kinetiq is skipped in cloud backup — the intended trade, but a real behaviour change with no user notification. Hence the README line |
| `adb backup` | Deprecated at API 31+ and governed by `allowBackup`, not this attribute ✔ |

### Test
File-read assertion mirroring the existing no-INTERNET manifest test: `<cloud-backup>` carries the
attribute and `<device-transfer>` does not.

### Risk: **Safe**

---

## 5. S-8 — Pin the TTS-settings intent

### Change — `ui/screens/settings/SettingsScreen.kt:198`

```kotlin
OutlinedButton(onClick = {
    // Only implicit intent in the app: pin it to the system Settings package so an installed
    // app declaring the same action cannot intercept the tap. Fall back to the implicit form
    // on the rare device whose Settings app is not com.android.settings.
    fun open(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    runCatching { open(Intent(TTS_SETTINGS_ACTION).setPackage("com.android.settings")) }
        .recoverCatching { open(Intent(TTS_SETTINGS_ACTION)) }
}) { Text("System TTS settings (download offline en-AU voice data)") }
```

### What could break — read this before shipping the one-liner

**This is exactly where a naive `setPackage` fix regresses the app.** The existing code already wraps
the call in `runCatching`, so on a device whose Settings app has a different package name, a bare
`setPackage` would throw `ActivityNotFoundException`, be silently swallowed, and the button would do
**nothing** — a worse outcome than today, on the one screen whose whole purpose is telling the user
how to get offline voice data. The `recoverCatching` fallback is what makes this net-positive.

### Risk: **Safe** (with the fallback). Verify on device that the button still opens system TTS settings.

---

## 6. S-7 — TTS voice: report, don't mute

The constraint — must not leave the user with no voice — is the whole design here, and it pushes
away from the obvious "filter harder" fix.

### Change — `voice/VoiceCoach.kt`

```kotlin
private val _usingNetworkVoice = MutableStateFlow(false)

/**
 * True when the engine settled on a voice that needs a network connection. Kinetiq itself
 * declares no INTERNET permission — any egress would be the user's chosen TTS engine under
 * its own permissions — but the user should be told so they can install offline voice data.
 */
val usingNetworkVoice: StateFlow<Boolean> = _usingNetworkVoice.asStateFlow()

private fun configureVoice() {
    val engine = tts ?: return
    val auLocale = Locale("en", "AU")
    val result = engine.setLanguage(auLocale)
    val selected = if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
        engine.setLanguage(Locale.getDefault())
        Locale.getDefault()
    } else {
        auLocale
    }
    // Prefer the best OFFLINE voice for whichever locale we actually landed on. If none exists
    // we keep the engine's own choice: a network-backed voice still speaks, and going silent
    // would break coaching outright. We only flag it.
    bestOfflineVoice(engine.voices, selected)?.let { engine.voice = it }
    _usingNetworkVoice.value = engine.voice?.isNetworkConnectionRequired == true
    engine.setAudioAttributes(speechAttributes)
}

/** Pure so it is testable: highest-quality voice for [preferred] that needs no network. */
internal fun bestOfflineVoice(voices: Set<Voice>?, preferred: Locale): Voice? =
    voices.orEmpty()
        .filter { it.locale == preferred && !it.isNetworkConnectionRequired }
        .maxByOrNull { it.quality }
```

Surfaced in Settings as a `bodySmall` note directly above the existing "System TTS settings" button,
suppressed when `TtsStatus.FAILED` so the two banners never stack.

### Why it's correct, and what was deliberately *not* done

The finding asks for "drop any selected voice where `isNetworkConnectionRequired` is true".
**That is not done**, because on a device whose only en-AU voice is network-backed, clearing
`engine.voice` hands the engine no voice for that locale and risks the silent-coaching outcome we
must avoid. `TextToSpeech.setVoice` has no "revert to default" contract safe to lean on.

A cross-locale offline fallback (en-AU-network → best offline en-US) was also rejected: it silently
changes the coach's accent for a benefit the user cannot perceive, and verification already conceded
the impact is negligible (no INTERNET permission; any egress is the engine's, as for every Android
app using `TextToSpeech`).

Shipped change: (a) `configureVoice` now applies the offline preference to *whichever* locale it
landed on, not only en-AU — closing the asymmetry the finding names, without changing which locale is
chosen; and (b) the user is told when the result is network-backed.

### What could break

| Flow | Verdict |
|---|---|
| Device with offline en-AU installed | Identical to today ✔ |
| Device with no en-AU at all | Falls back as today; now additionally prefers an offline voice within that locale. Strictly better ✔ |
| Only a network en-AU voice exists | Voice unchanged (still speaks), banner appears. **Never mute** ✔ |
| `TtsStatus.FAILED` path | Banner suppressed; existing error banner wins ✔ |
| Robolectric (`VoiceCoachStatusTest`) | `engine.voices` is null; `voices.orEmpty()` is null-safe → `usingNetworkVoice` stays false. No NPE ✔ |

### Risk: **Safe**

---

## 7. S-4 — Release signing provenance (release engineering, not mitigation)

### Recommended minimum

`.github/workflows/build.yml` — add the free CI hygiene item and stop presenting an
ephemerally-signed artifact as a release build:

```yaml
permissions:
  contents: read
```
```yaml
      - name: Upload release APK (CI-signed with an ephemeral key — NOT upgradable)
        uses: actions/upload-artifact@v4
        with:
          name: kinetiq-release-apk-ci-ephemeral-key
          path: app/build/outputs/apk/release/app-release.apk
```

Keep the `assembleRelease` step: its value is the merged-manifest INTERNET check and the R8 pass,
not the artifact.

### Fuller option (only for installable CI releases)

Env-driven release config that falls back to today's documented D-04 behaviour when unset, so local
and PR builds are byte-for-byte unchanged:

```kotlin
val releaseStore = System.getenv("KINETIQ_KEYSTORE")?.let(::File)?.takeIf { it.exists() }
signingConfigs {
    if (releaseStore != null) {
        create("release") {
            storeFile = releaseStore
            storePassword = System.getenv("KINETIQ_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KINETIQ_KEY_ALIAS")
            keyPassword = System.getenv("KINETIQ_KEY_PASSWORD")
        }
    }
}
buildTypes {
    release {
        // Sideload distribution: debug key unless a release keystore is supplied (README).
        signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
    }
}
```

with a tag-gated decode step (`if: startsWith(github.ref, 'refs/tags/v')`). Fork PRs get no secrets,
so they fall through to the debug path and still build.

**What could break:** the `findByName(...) ?: getByName("debug")` fallback protects the local
developer experience — with no env vars set, `./gradlew assembleRelease` behaves precisely as today.
The tag-gated CI step must be conditional or fork PRs fail on a missing secret. Once a real key
ships, users must uninstall/reinstall once.

### Risk: **Safe** for the minimum; **Needs care** for the fuller option.

---

## 8. S-9 — Health Connect rationale routing

### Recommendation: defer, and document instead

This is a Play policy item, and Kinetiq is a **single-user sideloaded app** (D-04). The compliance
driver does not exist. The permission gating is already correct — `START_VIEW_PERMISSION_USAGE` is
signature-level and system-only, so this is a UX gap, not an attack surface.

**Recommended action:** add a DECISIONS.md entry recording that the alias currently lands on Home,
that this is a Play-listing prerequisite, and that it will be implemented if the app is ever
submitted. Do not spend risk budget on it now.

### If implemented

Use a **boolean**, not a route string. A free-form "navigate to this route from an intent" channel is
the exact shape S-6 exists to remove; adding one back would be self-defeating even behind a signature
permission. And the `onboardingComplete` guard is mandatory — without it, a system-originated intent
could land the user past the disclaimer gate, reopening a narrower version of what S-6 closes.

### Risk: **Needs care** if implemented.

---

## 9. S-10 — one-line recommendations

- **K-10 (no FLAG_SECURE)** — Skip. If ever wanted, put it behind a Settings toggle rather than
  always-on: always-on would also block the *user's own* screenshots of their body data, a worse
  day-to-day cost than the shoulder-surfing risk it defends.
- **K-08 (unbounded import read)** — Optional: pre-check size via `openAssetFileDescriptor(uri, "r")`
  before `readText()` and fail clearly above ~32 MB. `runCatching` already catches `OutOfMemoryError`,
  so current behaviour is an error toast, not a crash.
- **K-11 (stale deps)** — Not a security fix. Bump on the next feature pass; `androidx.media` →
  `media3` is a deprecation migration with no identified CVE. Schedule it, don't rush it.
- **K-12 (CI hygiene)** — Add `permissions: contents: read` (folded into S-4). SHA-pinning actions is
  optional maintenance drag for a single-author repo with a `pull_request` (not `pull_request_target`)
  trigger.
- **K-17 (cleartext export)** — README line only: "Exports are plain JSON — save them somewhere you trust."
- **K-18 (ACTIVITY_RECOGNITION)** — No change. Cross-reference DECISIONS.md D-03 noting that on API
  34+ `FOREGROUND_SERVICE_TYPE_HEALTH` *requires* one of
  `ACTIVITY_RECOGNITION`/`BODY_SENSORS`/`HIGH_SAMPLING_RATE_SENSORS`, so this is a prerequisite rather
  than over-privilege, and the code already degrades to `MEDIA_PLAYBACK` when denied.

---

## Summary

| # | Finding | Files touched | Risk | Worth doing? |
|---|---|---|---|---|
| 1 | S-2/S-3 import validation + ViewModel guard | `ExportImport.kt`, `MainActivity.kt` | Safe | **Yes** — only real crash |
| 2 | S-1 widget origin token | `MainActivity.kt`, `KinetiqWidget.kt` | Needs care | **Yes** — device-test the widget |
| 3 | S-6 nav extras strip | `MainActivity.kt` | Safe | **Yes** |
| 4 | S-5 backup attribute | `data_extraction_rules.xml`, `README.md` | Safe | **Yes** |
| 5 | S-8 TTS intent pinning | `SettingsScreen.kt` | Safe (with fallback) | Marginal, cheap |
| 6 | S-7 TTS voice reporting | `VoiceCoach.kt`, `SettingsScreen.kt` | Safe | Reporting half yes; voice-clearing half **no** |
| 7 | S-4 CI signing | `build.yml` (+ optional `build.gradle.kts`) | Safe / Needs care | Minimum yes |
| 8 | S-9 HC rationale | — | Needs care | **Document instead** |
| 9 | S-10 | various | Safe | Mostly document |

**Four places where the literal fix is the wrong move**, and why:

- S-7's "drop any network voice" would risk muting coaching — report instead.
- S-9 is a Play-listing prerequisite for an app that isn't on Play — document instead.
- S-8's bare `setPackage` would silently disable the button on non-AOSP-named Settings apps — needs
  the implicit fallback to be net-positive.
- S-1's most obvious "modern" answer, `getLaunchedFromUid()`, is a trap that would break the widget
  on every warm start — the token approach exists specifically to avoid it.

**Sources:** Android Auto Backup data-extraction-rules docs; `Activity.getLaunchedFromUid` (API 34
SDK sources); Glance 1.1.1 and Navigation 2.8.5 sources from Google Maven; AOSP
`PendingIntentRecord.java` and `ActivityClientController.java` from `android15-release`.
