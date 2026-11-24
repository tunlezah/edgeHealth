# WALKTHROUGH.md — every screen, briefly

Use this to verify the feature list. Bottom navigation: **Home · History · Library · Plan · Settings** — a permanent bar on every screen except onboarding, with the entire cell (icon, label, and surrounding space out to the screen edges) as the touch target.

## 1. Onboarding (first run only)
1. **Disclaimer** — "general fitness guidance, not medical advice"; must be acknowledged to continue.
   Re-viewable later (Settings → Disclaimer). Also asks for notification + activity-recognition
   permissions (the latter lets the workout service run as a `health` foreground service; denying it
   is fine — the service falls back to media-playback type).
2. **Body constraints** — multi-select chips: knees, wrists, shoulders, lower back, neck, hips,
   ankles. Matching exercises are hard-excluded from generation.
3. **Machines** — GR7 max resistance level (default 11), VG50BS max level (default 16 — check your
   console; the BT variant's dealer spec says 32), reformer spring notation (generic words vs count).
   Every step shows a "Step N of 4" indicator and has a Back button.
4. **Health Connect (optional)** — connect and grant read/write permissions, or skip entirely.

## 2. Home
Streak (🔥), sessions/minutes/kcal this week, **Build a workout**, **Repeat last: <name>**,
saved workouts (play/delete), link to body measurements, and — if the app was killed mid-session —
**Resume interrupted workout**.

## 3. Workout builder
All generator inputs: total duration (5–60 min, default 15), categories in tap order (block order) — including the physiotherapy-informed **Back care** category (McGill big three, motor-control drills, hip work; all low impact with pain-safe cues), exercises
per category (or auto), **rest mode** (Standard 15–20 s transitions / Recovery 30–45 s / Continuous
back-to-back with a one-time notice), intensity, warm-up/cool-down toggles, "use my health data".
Changing a setting keeps the generated preview visible under a "settings changed" banner, and
regenerating over hand edits asks first.
**Generate** shows warnings with one-tap fixes (e.g. "20 exercises in 8 min → Use 10 exercises",
"light on cardio → Add the bike") and an editable preview: reorder (↑↓), swap (⇄ picks a compatible
replacement honouring constraints/evidence/equipment), remove (✕). **Start workout** launches the player.

**Session focus** sits at the top: *General fitness* (everything above) or *Muscle retention*.
Muscle retention rebuilds the Floor and Reformer blocks as **repeated sets taken close to your
limit** — one exercise per region (leg push, upper push, hamstring/glute, upper pull, then
shoulders, arms, calves, trunk), two to three sets each — to hold on to muscle while losing fat.
Bike and elliptical blocks stay as cardio and count as zero resistance sets. Three optional chips:
**Hand weights** (uses the heaviest weight set in Settings → Equipment; exercises that would need
more are left out rather than watered down), **Easing in** (first weeks: stop each set with 3–4
reps in hand) and **Limited-evidence fillers** (lets a clearly labelled low-evidence exercise cover
a region nothing better can — it never displaces a better-graded one). The rest chips become
*Paired sets* (two exercises alternate, 30 s between, 60 s before repeating), *Straight sets*
(75 s between sets) and *Short rests*. Warnings name a region nothing can cover and offer the fix
that would cover it ("Use hand weights", "Add Reformer", "Add limited-evidence fillers"), and flag
resistance placed after cardio. Swapping one set swaps every set of that exercise for another that
trains the same region.

## 4. Player
Every session opens with a 10-second **Get ready** countdown (first exercise's animation + spoken
how-to; tap to jump to the last 3 s) so exercise 1's clock never runs while you're walking to the
mat. Big countdown timer, large machine-cue text readable from across the room, time-weighted
overall progress with "~N min left", large animation, next-up preview card during
rests/transitions — **rests are skippable** (tap anywhere or the Skip-rest button). Controls:
pause/resume, skip, **+30 s**, stop (confirmed — never one tap; the summary offers a 10-minute
undo), 🗣 explain-again. Auto-pauses on phone calls and headphone disconnects; every resume gets a
3-2-1 countdown. Keep-screen-on toggle persists and lets the screen sleep while paused. Runs in a
foreground service with media-style notification controls (pause/skip/two-tap stop) — survives
screen-off and app switching; a 5-second disk snapshot enables restore after process death with
per-block accounting intact.

**Resistance sets** (muscle-retention sessions) show *Set 2 of 3* and the effort target under the
name, and add a full-width **Set done** button: reaching your last good rep before the clock runs
out is the point of the set, so — unlike Skip — it keeps the rest that follows and counts the set.
One-side-at-a-time sets get double the window and a spoken "Switch sides" at the midpoint. During
the rest after a set you did, one tap answers **"Good reps left in that set?"** (0–1 / 2–3 /
4 or more); the rating survives a process death with the rest of the session.

Voice: en-AU TTS announces names, how-tos (during the preceding rest), halfway, rest/next-up,
machine settings ("Standing climb — resistance 8 to 9, 60 to 75 rpm."), plus 3-2-1 countdown beeps
(ToneGenerator). Cues duck music (transient-may-duck focus) and never overlap. For resistance
sets the springs/weights cue, the effort target and the how-to are spoken before the first set of
an exercise only; later sets are announced as "Push-up, set 2 of 3".

## 5. Summary
Active minutes, MET-estimated kcal, per-block breakdown (category, HIIT flag, minutes, kcal),
Health Connect write status, **Save this workout** with a name (reusable from Home + widget).
Resistance blocks also show their hard sets and the per-muscle credit, and a **Next time** card
turns the set ratings into advice: move up a progression ladder (one tap, when a harder version
your equipment allows exists), keep the version but push closer to your limit, or — when there is
no harder version — a plain statement that those sets have stopped counting.

## 6. History
Streak + 4-week trends (sessions/week, minutes/week, kcal/week), month calendar with workout days
highlighted, full session list (with HC ✓ marker) and per-entry delete.

## 7. Exercise library
Filter by category / target / evidence tier. Each row shows a live animation thumbnail and evidence
badge; LIMITED-tier entries are greyed out until "Include low-evidence exercises" is on. Detail page:
large animation, summary, how-to, form cues, machine setup, honest popularity note (LIMITED), and
tappable-to-read full references (authors, year, journal, DOI/PMID, finding) — all offline text.
Exercises usable as resistance sets carry a second, separate **muscle-retention** grade with its
own note and references (an exercise can be well supported for general health and still weak for
holding on to muscle — reformer footwork is the standing example), the muscles trained, and any
equipment needed. A *Muscle retention* filter row narrows the list to them, optionally by that grade.

## 8. Weekly plan
Rule-based targets (WHO 2020 + VAT dose evidence): with the visceral-fat goal on, 4 cardio
sessions / ~180 min + 2 strength sessions; otherwise the WHO baseline. Shows progress bars and a
plain-language suggestion for the rest of the week.

A **Muscle retention** section tracks the week's resistance dose: sessions with four or more hard
sets (target 2) and hard sets per muscle group (target 6, floor 4). A set counts when it was
performed and not rated "4 or more reps left"; it earns one set for each primary muscle and half
for each secondary. The copy states what is not known — no trial has tested a minimum dose while
losing fat — and that the aim is to minimise muscle loss, not eliminate it. The next
muscle-retention session starts with whichever region is furthest behind.

## 9. Settings
- **Voice & sound**: six individual cue switches (beeps, names, how-to, halfway, rest/next-up,
  machine cues), volume slider, speech rate, test-voice button, link to system TTS settings.
- **Theme**: Light / Dark / AMOLED black / System, plus seven accent palettes (Kinetiq Mint,
  Ocean, Ember, Violet, Citrus, Rose, Slate) — all WCAG-contrast-tested in every mode.
- **Workout defaults**: default rest mode for new workouts.
- **Health Connect**: manage connection + write-back toggle; body-measurements screen shows HC
  values with source app + timestamp, computed BMI, WHO waist-circumference risk thresholds, and
  manual entry (weight, height, body fat %, waist, smart-scale visceral rating — freshest wins).
- **Body constraints** editing; **Include low-evidence exercises** toggle.
- **Machines**: GR7/VG50BS max levels, spring notation.
- **Equipment**: heaviest single hand weight (kg, default 2), sturdy chair or step, reformer long
  box, ankle anchor. Muscle-retention sessions only use what is switched on here.
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
