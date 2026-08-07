# WALKTHROUGH.md — every screen, briefly

Use this to verify the feature list. Bottom navigation: **Home · History · Library · Plan · Settings**.

## 1. Onboarding (first run only)
1. **Disclaimer** — "general fitness guidance, not medical advice"; must be acknowledged to continue.
   Re-viewable later (Settings → Disclaimer). Also asks for notification + activity-recognition
   permissions (the latter lets the workout service run as a `health` foreground service; denying it
   is fine — the service falls back to media-playback type).
2. **Body constraints** — multi-select chips: knees, wrists, shoulders, lower back, neck, hips,
   ankles. Matching exercises are hard-excluded from generation.
3. **Machines** — GR7 max resistance level (default 11), VG50BS max level (default 16 — check your
   console; the BT variant's dealer spec says 32), reformer spring notation (generic words vs count).
4. **Health Connect (optional)** — connect and grant read/write permissions, or skip entirely.

## 2. Home
Streak (🔥), sessions/minutes/kcal this week, **Build a workout**, **Repeat last: <name>**,
saved workouts (play/delete), link to body measurements, and — if the app was killed mid-session —
**Resume interrupted workout**.

## 3. Workout builder
All generator inputs: total duration (10–90 min), categories in tap order (block order), exercises
per category (or auto), work:rest ratio, intensity, warm-up/cool-down toggles, "use my health data".
**Generate** shows warnings with one-tap fixes (e.g. "20 exercises in 8 min → Use 10 exercises",
"light on cardio → Add the bike") and an editable preview: reorder (↑↓), swap (⇄ picks a compatible
replacement honouring constraints/evidence), remove (✕). **Start workout** launches the player.

## 4. Player
Big countdown timer, large animation with working-muscle tint and motion arc, step progress, machine
cue text, next-up preview card during rests/transitions. Controls: pause/resume, skip, **+30 s**,
stop, 🗣 explain-again (re-speaks the how-to). Keep-screen-on toggle (FLAG_KEEP_SCREEN_ON). Runs in a
foreground service with media-style notification controls (pause/skip/stop) — survives screen-off
and app switching; a 5-second disk snapshot enables restore after process death.

Voice: en-AU TTS announces names, how-tos (during the preceding rest), halfway, rest/next-up,
machine settings ("Standing climb — resistance 8 to 9, 60 to 75 rpm."), plus 3-2-1 countdown beeps
(ToneGenerator). Cues duck music (transient-may-duck focus) and never overlap.

## 5. Summary
Active minutes, MET-estimated kcal, per-block breakdown (category, HIIT flag, minutes, kcal),
Health Connect write status, **Save this workout** with a name (reusable from Home + widget).

## 6. History
Streak + 4-week trends (sessions/week, minutes/week, kcal/week), month calendar with workout days
highlighted, full session list (with HC ✓ marker) and per-entry delete.

## 7. Exercise library
Filter by category / target / evidence tier. Each row shows a live animation thumbnail and evidence
badge; LIMITED-tier entries are greyed out until "Include low-evidence exercises" is on. Detail page:
large animation, summary, how-to, form cues, machine setup, honest popularity note (LIMITED), and
tappable-to-read full references (authors, year, journal, DOI/PMID, finding) — all offline text.

## 8. Weekly plan
Rule-based targets (WHO 2020 + VAT dose evidence): with the visceral-fat goal on, 4 cardio
sessions / ~180 min + 2 strength sessions; otherwise the WHO baseline. Shows progress bars and a
plain-language suggestion for the rest of the week.

## 9. Settings
- **Voice & sound**: six individual cue switches (beeps, names, how-to, halfway, rest/next-up,
  machine cues), volume slider, speech rate, test-voice button, link to system TTS settings.
- **Theme**: Light / Dark / AMOLED black / System.
- **Health Connect**: manage connection + write-back toggle; body-measurements screen shows HC
  values with source app + timestamp, computed BMI, WHO waist-circumference risk thresholds, and
  manual entry (weight, height, body fat %, waist, smart-scale visceral rating — freshest wins).
- **Body constraints** editing; **Include low-evidence exercises** toggle.
- **Machines**: GR7/VG50BS max levels, spring notation.
- **Reminders**: day chips + time picker (WorkManager; reschedules itself, survives reboot).
- **Streak rest days**: days that never break a streak.
- **Units & goals**: metric units, visceral-fat goal, fallback weight for calories.
- **Disclaimer**: view dialog + toggle for the spoken pre-workout reminder line.
- **Data**: Export / Import JSON via the system file picker (SAF, no storage permissions); import
  validates and lists every problem, or reports exactly what was added (duplicates skipped).
- **Hidden QA**: long-press the version row → animation QA screen cycling all 54 rig animations.

## 10. Home-screen widget
Glance widget: one-tap **repeat last workout** (jumps straight into the player) + current streak.

## Definition-of-done trace
Install APK → onboarding → build a 30-min Floor + Spin session → hear en-AU cues incl. GR7 levels →
smooth animations → summary → save → HC write ✓ → tomorrow: one tap on the widget — all in airplane
mode.
