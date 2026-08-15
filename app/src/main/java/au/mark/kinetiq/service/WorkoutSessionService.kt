package au.mark.kinetiq.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import au.mark.kinetiq.KinetiqApp
import au.mark.kinetiq.MainActivity
import au.mark.kinetiq.R
import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.repo.AppSettings
import au.mark.kinetiq.data.repo.MeasurementRepository
import au.mark.kinetiq.data.repo.Metric
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.data.repo.WorkoutRepository
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import au.mark.kinetiq.health.HealthConnectManager
import au.mark.kinetiq.voice.VoiceCoach
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

/** Outcome of a notification Stop tap given the arming window. */
enum class StopDecision { ARM, FINISH }

internal fun stopArmDecision(nowMs: Long, armedUntilMs: Long): StopDecision =
    if (nowMs < armedUntilMs) StopDecision.FINISH else StopDecision.ARM

/** A start command must never clobber a live (unfinished) session. */
internal fun shouldIgnoreStart(currentFinished: Boolean?): Boolean = currentFinished == false

/**
 * Actions the companion delivers via `startForegroundService`. Each one arms the platform's ~5 s
 * foreground-start watchdog, so every path handling one must reach `startForeground` or stop.
 */
internal fun isForegroundEntry(action: String?): Boolean =
    action == WorkoutSessionService.ACTION_START ||
        action == WorkoutSessionService.ACTION_RESUME_SNAPSHOT ||
        action == WorkoutSessionService.ACTION_RESUME_STOPPED

/**
 * A finish coroutine may tear the service down only if its run is still the current one. It does
 * seconds of suspending work (Health Connect IPC, DB write), during which a new session can be
 * accepted — `shouldIgnoreStart` lets one in the moment `finished` is set.
 */
internal fun shouldTearDown(finishingGen: Int, currentGen: Int): Boolean = finishingGen == currentGen

/** The wake lock exists to keep the step clock accurate — it is only needed while one is running. */
internal fun shouldHoldWakeLock(paused: Boolean, finished: Boolean): Boolean = !paused && !finished

/**
 * Foreground service that runs the workout: an elapsed-realtime-based timer (accurate across
 * doze and screen-off, guarded by a partial wake lock), spoken coaching via [VoiceCoach],
 * media-style notification controls, and a 5-second disk snapshot for process-death restore.
 *
 * All timing/cue/accrual arithmetic lives in the pure [SessionEngine]; this class owns the
 * Android pieces (ticker coroutine, wake lock, notification, TTS calls, snapshot I/O, DB and
 * Health Connect writes) and executes the engine's effects.
 */
@AndroidEntryPoint
class WorkoutSessionService : LifecycleService() {

    @Inject lateinit var stateHolder: SessionStateHolder
    @Inject lateinit var voice: VoiceCoach
    @Inject lateinit var settingsRepo: SettingsRepository
    @Inject lateinit var workoutRepo: WorkoutRepository
    @Inject lateinit var measurementRepo: MeasurementRepository
    @Inject lateinit var healthConnect: HealthConnectManager
    @Inject lateinit var json: Json

    private var tickerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null

    private var engine: SessionEngine? = null
    private var engineState: SessionEngine.EngineState? = null

    /** Restore lands paused; the first resume should re-announce the step after its countdown. */
    private var announceOnNextResume = false
    private var lastSnapshotMs = 0L

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "KinetiqSession")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        latestStartId = startId
        when (val action = intent?.action) {
            ACTION_START -> {
                if (shouldIgnoreStart(stateHolder.state.value?.finished)) {
                    // Live session: this delivery is a refresh. Already foreground, so the
                    // platform cleared fgRequired without arming a new timeout.
                    goForeground() // idempotent — degenerates to notify()
                } else {
                    // A throw here would escape onStartCommand with the watchdog still armed.
                    val session = intent.getStringExtra(EXTRA_SESSION_JSON)?.let { payload ->
                        runCatching { json.decodeFromString(GeneratedSession.serializer(), payload) }.getOrNull()
                    }
                    if (session == null || session.plan.steps.isEmpty()) {
                        // Nothing to run. Stopping satisfies the foreground-start timeout without
                        // ever showing a notification — safe because no live session exists here.
                        stopSelfResult(startId)
                    } else {
                        val name = intent.getStringExtra(EXTRA_SESSION_NAME) ?: "Workout"
                        val gen = claimForeground("Get ready", "Starting $name…")
                        startSession(session, name, gen, startId)
                    }
                }
            }
            // Whether a restorable snapshot exists needs disk I/O, so the foreground has to be
            // claimed first and released again if the read comes back empty.
            ACTION_RESUME_SNAPSHOT, ACTION_RESUME_STOPPED -> {
                val gen = claimForeground(textHint = "Restoring your workout…")
                restoreFromSnapshot(gen, startId, fromStopped = action == ACTION_RESUME_STOPPED)
            }
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_SKIP -> skipStep()
            ACTION_SKIP_PREPARE -> skipPrepare()
            ACTION_EXTEND -> extendStep()
            // The notification's Stop is two-stage: the first tap arms it for 3 s (label flips
            // to "Tap again to stop"), only a second tap inside the window ends the session.
            ACTION_STOP -> {
                val now = SystemClock.elapsedRealtime()
                when (stopArmDecision(now, stopArmedUntil)) {
                    StopDecision.FINISH -> finishSession(userStopped = true, startId = startId)
                    StopDecision.ARM -> {
                        stopArmedUntil = now + STOP_ARM_WINDOW_MS
                        updateNotification()
                        stopArmJob?.cancel()
                        stopArmJob = lifecycleScope.launch {
                            delay(STOP_ARM_WINDOW_MS)
                            stopArmedUntil = 0L
                            updateNotification()
                        }
                    }
                }
            }
            // The player UI confirms with its own dialog, then sends this directly.
            ACTION_STOP_CONFIRMED -> finishSession(userStopped = true, startId = startId)
            // START_NOT_STICKY means the platform never redelivers a null intent, but if one ever
            // arrives it must not leave the service running with nothing to do.
            null -> abandonRun(runGeneration, startId)
        }
        return START_NOT_STICKY
    }

    private var stopArmedUntil = 0L
    private var stopArmJob: Job? = null

    /**
     * Bumped synchronously — before any suspension — whenever a new run is accepted, so a finish
     * coroutine parked in a suspending call can tell whether it still owns the service.
     */
    private var runGeneration = 0

    /** Most recent onStartCommand id, for the paths where no id is in scope. */
    private var latestStartId = 0

    private var isForeground = false

    /** Claim the foreground synchronously and open a new run generation. */
    private fun claimForeground(titleHint: String? = null, textHint: String? = null): Int {
        val gen = ++runGeneration
        goForeground(titleHint, textHint)
        return gen
    }

    /**
     * A foreground entry that turned out to have nothing to run: drop the placeholder and stop.
     * This is what satisfies the watchdog on the bail-out paths that previously just returned,
     * leaving the service alive but never foreground until the platform killed the process.
     */
    private fun abandonRun(gen: Int, startId: Int) {
        if (runGeneration != gen) return
        if (stateHolder.state.value != null) { updateNotification(); return }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelfResult(startId)
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startSession(session: GeneratedSession, name: String, gen: Int, startId: Int) {
        lifecycleScope.launch {
            val settings = settingsRepo.current()
            if (runGeneration != gen) return@launch
            voice.settings = settings.voice
            val weight = measurementRepo.resolved(Metric.WEIGHT_KG)?.value ?: settings.fallbackWeightKg.toDouble()
            if (runGeneration != gen) return@launch

            val first = session.plan.steps.firstOrNull() ?: return@launch abandonRun(gen, startId)
            // A starting session invalidates any unviewed old summary — it can never hijack
            // navigation into the previous session's results.
            stateHolder.clearCompleted()
            val sessionId = java.util.UUID.randomUUID().toString()
            val newEngine = SessionEngine(
                session.plan.steps, weight,
                announceNextDuringWork = session.config.restMode == au.mark.kinetiq.data.model.RestMode.CONTINUOUS,
            )
            engine = newEngine
            engineState = newEngine.initialState()
            announceOnNextResume = false
            // The on-disk snapshot still describes the previous run until the first tick; forcing
            // one immediately stops a stale file outliving this start.
            lastSnapshotMs = 0L
            // A Stop armed on the previous run must not instantly finish this one.
            stopArmedUntil = 0L
            stopArmJob?.cancel()
            stateHolder.update(
                PlayerState(
                    session = session,
                    sessionName = name,
                    sessionId = sessionId,
                    stepIndex = 0,
                    stepRemainingMs = first.durationSec * 1000L,
                    prepareRemainingMs = SessionEngine.PREPARE_DURATION_MS,
                    startedAtEpochMs = System.currentTimeMillis(),
                    weightKg = weight,
                )
            )
            goForeground()
            // The GET-READY countdown absorbs TTS warm-up latency and the intro speech, so the
            // first exercise's clock only starts once the user has been told what to do.
            voice.warmUp {
                if (settings.disclaimerAcknowledged && settings.disclaimerLineInWorkout) {
                    voice.speak(getString(R.string.disclaimer_workout_reminder))
                }
                voice.speak(
                    "Starting $name. ${session.plan.steps.size} steps, about ${session.plan.totalSec / 60} minutes. " +
                        "Get ready — first up: ${first.exerciseName}."
                )
                if (voice.settings.howToDescription) speakHowToAt(0)
            }
            startTicker()
        }
    }

    private fun restoreFromSnapshot(gen: Int, startId: Int, fromStopped: Boolean = false) {
        lifecycleScope.launch {
            // This read is the first statement in the launch and is non-suspending, so on
            // Main.immediate it runs inline inside onStartCommand — the bail-out is deterministic,
            // not racy, and abandonRun disarms the watchdog before it can fire.
            val snap = (
                if (fromStopped) readStoppedSnapshot(this@WorkoutSessionService, json)
                else readSnapshot(this@WorkoutSessionService, json)
                ) ?: return@launch abandonRun(gen, startId)
            if (runGeneration != gen) return@launch
            val settings = settingsRepo.current()
            if (runGeneration != gen) return@launch
            voice.settings = settings.voice
            val newEngine = SessionEngine(
                snap.session.plan.steps, snap.weightKg,
                announceNextDuringWork = snap.session.config.restMode == au.mark.kinetiq.data.model.RestMode.CONTINUOUS,
            )
            val step = snap.session.plan.steps.getOrNull(snap.stepIndex)
            engine = newEngine
            engineState = SessionEngine.EngineState(
                stepIndex = snap.stepIndex,
                stepRemainingMs = snap.stepRemainingMs,
                prepareRemainingMs = 0,
                totalElapsedActiveMs = snap.totalElapsedActiveMs,
                caloriesSoFar = snap.caloriesSoFar,
                blockActiveMs = snap.blockActiveMs,
                blockBounds = snap.blockBounds.mapValues { (_, v) ->
                    (v.getOrElse(0) { 0L }) to (v.getOrElse(1) { 0L })
                },
                cues = step?.let { SessionEngine.cueFlagsForRestore(it, snap.stepRemainingMs) }
                    ?: SessionEngine.CueFlags(),
            )
            announceOnNextResume = true
            lastSnapshotMs = 0L
            stopArmedUntil = 0L
            stopArmJob?.cancel()
            stateHolder.update(
                PlayerState(
                    session = snap.session,
                    sessionName = snap.sessionName,
                    // A stop-resumed run needs a fresh id: the old one was already consumed by
                    // the navigate-once summary guard, which would swallow the final summary.
                    sessionId = if (fromStopped) java.util.UUID.randomUUID().toString()
                    else snap.sessionId.ifBlank { java.util.UUID.randomUUID().toString() },
                    stepIndex = snap.stepIndex,
                    stepRemainingMs = snap.stepRemainingMs,
                    totalElapsedActiveMs = snap.totalElapsedActiveMs,
                    caloriesSoFar = snap.caloriesSoFar,
                    startedAtEpochMs = snap.startedAtEpochMs,
                    weightKg = snap.weightKg,
                    paused = true,
                )
            )
            // Delete only after the restored state is published: a crash mid-restore then leaves
            // the snapshot recoverable instead of having consumed it for nothing.
            if (fromStopped) stoppedSnapshotFile(this@WorkoutSessionService).delete()
            goForeground() // refines the placeholder posted in onStartCommand
            voice.warmUp { voice.speak("Workout restored — paused. Resume when you are ready.") }
            startTicker()
        }
    }

    private var noisyReceiver: android.content.BroadcastReceiver? = null
    private var audioModeListener: android.media.AudioManager.OnModeChangedListener? = null

    /**
     * A timed workout that keeps counting through a phone call effectively destroys the session —
     * auto-pause on incoming/active calls and on headphone disconnects. Resume stays manual
     * (and gets the 3-2-1 countdown).
     */
    private fun registerAutoPause() {
        if (noisyReceiver != null) return
        noisyReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = autoPause()
        }.also {
            registerReceiver(it, android.content.IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }
        val am = getSystemService(android.media.AudioManager::class.java)
        audioModeListener = android.media.AudioManager.OnModeChangedListener { mode ->
            if (mode == android.media.AudioManager.MODE_RINGTONE ||
                mode == android.media.AudioManager.MODE_IN_CALL ||
                mode == android.media.AudioManager.MODE_IN_COMMUNICATION
            ) autoPause()
        }.also { am.addOnModeChangedListener(mainExecutor, it) }
    }

    private fun unregisterAutoPause() {
        noisyReceiver?.let { runCatching { unregisterReceiver(it) } }
        noisyReceiver = null
        audioModeListener?.let {
            runCatching { getSystemService(android.media.AudioManager::class.java).removeOnModeChangedListener(it) }
        }
        audioModeListener = null
    }

    private fun autoPause() {
        val state = stateHolder.state.value ?: return
        if (state.paused || state.finished) return
        setPaused(true)
    }

    private fun goForeground(titleHint: String? = null, textHint: String? = null) {
        registerAutoPause()
        val notification = buildNotification(titleHint, textHint)
        if (isForeground) {
            // Already claimed. Refine in place — calling startForeground again would re-evaluate
            // the FGS type, and a permission revoke mid-session would then look like a type
            // transition (HEALTH -> MEDIA_PLAYBACK), which Android 14+ treats as a change.
            getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            return
        }
        val hasActivityRecognition = checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        val type = if (hasActivityRecognition) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            // TTS coaching audio makes this a legitimate media-playback service (DECISIONS.md D-03).
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        isForeground = true
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kinetiq:session").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    // ------------------------------------------------------------------ timer

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            var lastTick = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(200)
                val now = SystemClock.elapsedRealtime()
                val delta = now - lastTick
                lastTick = now
                val state = stateHolder.state.value ?: continue
                if (state.paused || state.finished) continue
                onTick(state, delta)
            }
        }
    }

    private fun onTick(state: PlayerState, deltaMs: Long) {
        val eng = engine ?: return
        val es = engineState ?: return
        val result = eng.onTick(es, deltaMs, System.currentTimeMillis())
        engineState = result.state
        if (!result.state.finished) {
            syncState(state)
            maybeSnapshot()
            if ((es.stepRemainingMs / 1000) != (result.state.stepRemainingMs / 1000) ||
                (es.prepareRemainingMs / 1000) != (result.state.prepareRemainingMs / 1000) ||
                es.stepIndex != result.state.stepIndex
            ) updateNotification()
        }
        executeEffects(result.effects)
    }

    /** Mirror the engine's numbers into the UI-facing state. */
    private fun syncState(base: PlayerState? = null) {
        val es = engineState ?: return
        val current = base ?: stateHolder.state.value ?: return
        stateHolder.update(
            current.copy(
                stepIndex = es.stepIndex,
                stepRemainingMs = es.stepRemainingMs,
                prepareRemainingMs = es.prepareRemainingMs,
                totalElapsedActiveMs = es.totalElapsedActiveMs,
                caloriesSoFar = es.caloriesSoFar,
            )
        )
    }

    private fun executeEffects(effects: List<SessionEngine.Effect>) {
        for (effect in effects) when (effect) {
            SessionEngine.Effect.PlayCountdownBeeps -> voice.countdownBeeps()
            SessionEngine.Effect.SpeakHalfway -> voice.speak("Halfway.")
            is SessionEngine.Effect.SpeakNextHowTo -> speakHowToAt(effect.nextIndex)
            is SessionEngine.Effect.SpeakNextUp -> {
                val name = stateHolder.state.value?.session?.plan?.steps?.getOrNull(effect.nextIndex)?.exerciseName
                if (name != null) voice.speak("Next up: $name.")
            }
            is SessionEngine.Effect.AnnounceStep -> {
                // Flush any still-running how-to so the new step's name is never delayed.
                voice.stopSpeaking()
                announceStep(fresh = effect.fresh)
            }
            SessionEngine.Effect.PrepareEnded -> updateNotification()
            SessionEngine.Effect.Finished -> finishSession(userStopped = false)
        }
    }

    // ------------------------------------------------------------------ voice cues

    private fun announceStep(fresh: Boolean) {
        val state = stateHolder.state.value ?: return
        val step = state.currentStep ?: return
        when (step.type) {
            StepType.WORK, StepType.WARMUP, StepType.COOLDOWN -> {
                if (voice.settings.nameAnnouncement) {
                    val prefix = when (step.type) {
                        StepType.WARMUP -> "Warm up: "
                        StepType.COOLDOWN -> "Cool down: "
                        else -> ""
                    }
                    voice.speak("$prefix${step.exerciseName}. ${formatDuration(step.durationSec)}.")
                }
                if (voice.settings.machineSettingCues && step.machineCueText != null) {
                    voice.speak(step.machineCueText)
                }
                if (fresh && voice.settings.howToDescription) speakHowToAt(state.stepIndex)
            }
            StepType.REST -> {
                if (voice.settings.restNextUpCue) {
                    val next = state.nextStep
                    val nextName = next?.exerciseName
                    voice.speak(
                        if (nextName != null) "Rest ${step.durationSec} seconds. Next up: $nextName."
                        else "Rest ${step.durationSec} seconds."
                    )
                }
            }
            StepType.TRANSITION -> {
                val station = WorkoutGenerator.stationName(step.category)
                voice.speak("Category complete — move to $station, next block starts in ${step.durationSec} seconds.")
            }
        }
    }

    private fun speakHowToAt(index: Int) {
        lifecycleScope.launch {
            val step = stateHolder.state.value?.session?.plan?.steps?.getOrNull(index) ?: return@launch
            val id = step.exerciseId ?: return@launch
            howToFor(id)?.let { voice.speak(it) }
        }
    }

    /** "Explain again" from the UI. */
    fun explainAgain() {
        val state = stateHolder.state.value ?: return
        speakHowToAt(state.stepIndex)
    }

    private var exerciseHowTo: Map<String, String>? = null

    @Inject lateinit var exerciseRepo: au.mark.kinetiq.data.repo.ExerciseRepository

    private suspend fun howToFor(id: String): String? {
        val cache = exerciseHowTo ?: exerciseRepo.exercises()
            .associate { it.id to it.voiceHowTo }
            .also { exerciseHowTo = it }
        return cache[id]
    }

    private fun formatDuration(sec: Int): String =
        if (sec >= 90) "${sec / 60} minutes ${if (sec % 60 > 0) "${sec % 60} seconds" else ""}".trim()
        else "$sec seconds"

    // ------------------------------------------------------------------ controls

    private fun setPaused(paused: Boolean) {
        val state = stateHolder.state.value ?: return
        if (!paused && !state.finished) {
            // Every resume gets a bare 3-2-1 so the user can get back into position.
            // Only a snapshot restore re-announces the exercise; a plain unpause stays quiet.
            engineState = engineState?.let { es ->
                es.copy(
                    prepareRemainingMs = SessionEngine.RESUME_PREPARE_MS,
                    announceAfterPrepare = announceOnNextResume,
                    cues = es.cues.copy(prepareBeepsPlayed = false),
                )
            }
            announceOnNextResume = false
        }
        stateHolder.update(
            state.copy(paused = paused, prepareRemainingMs = engineState?.prepareRemainingMs ?: 0)
        )
        // A paused workout has no step clock to keep accurate, so let the CPU sleep rather than
        // holding a partial wake lock and spinning a 5 Hz ticker through what may be an
        // indefinite pause (auto-pause on a call, then the user walks away). startTicker()
        // re-baselines lastTick to elapsedRealtime() on resume, so the paused interval is never
        // billed and the engine's deliberate tick clamp is not even exercised.
        if (paused) {
            suspendTicker()
            releaseWakeLock()
        } else {
            acquireWakeLock()
            startTicker()
        }
        voice.speak(if (paused) "Paused." else "Resuming.", flush = true)
        updateNotification()
    }

    private fun suspendTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun skipStep() {
        val state = stateHolder.state.value ?: return
        val eng = engine ?: return
        val es = engineState ?: return
        voice.stopSpeaking()
        val result = eng.skip(es)
        engineState = result.state
        if (!result.state.finished) {
            syncState(state)
            updateNotification()
        }
        executeEffects(result.effects)
    }

    private fun skipPrepare() {
        val eng = engine ?: return
        val es = engineState ?: return
        engineState = eng.skipPrepare(es)
        syncState()
        updateNotification()
    }

    private fun extendStep() {
        val eng = engine ?: return
        val es = engineState ?: return
        engineState = eng.extend(es)
        syncState()
        voice.speak("Thirty seconds added.")
        updateNotification()
    }

    private fun finishSession(userStopped: Boolean, startId: Int = latestStartId) {
        val state = stateHolder.state.value ?: return
        if (state.finished) return
        // The run this coroutine owns. Captured before the launch, so it cannot drift.
        val gen = runGeneration
        val es = engineState
        stateHolder.update(state.copy(finished = true, paused = true))
        tickerJob?.cancel()
        voice.speak(
            if (userStopped) "Workout stopped. Well done for showing up."
            else "Workout complete. Great work — remember to drink some water.",
            flush = true,
        )

        lifecycleScope.launch {
            try {
                // Both of these are consulted only for a couple of values, but an unguarded throw
                // here would skip the history write entirely AND escape into lifecycleScope with no
                // CoroutineExceptionHandler — losing the workout and crashing the process. Defaults
                // mean healthConnectEnabled = false, so a settings failure degrades to "don't write
                // to Health Connect", which is the safe direction. No broad catch: the `finally`
                // must still run on cancellation, and catching Throwable would swallow
                // CancellationException and break structured concurrency.
                val settings = runCatching { settingsRepo.current() }.getOrDefault(AppSettings())
                val endedAt = System.currentTimeMillis()
                val plan = state.session.plan

                val blocks = runCatching {
                    completedBlocks(
                        plan = plan,
                        blockActiveMs = es?.blockActiveMs ?: emptyMap(),
                        blockBounds = es?.blockBounds ?: emptyMap(),
                        weightKg = state.weightKg,
                        fallbackBounds = state.startedAtEpochMs to endedAt,
                    )
                }.getOrDefault(emptyList())

                val totalActiveSec = ((es?.totalElapsedActiveMs ?: 0L) / 1000).toInt()
                val calories = es?.caloriesSoFar ?: 0.0
                var hcWritten = false
                var hcError: String? = null
                if (settings.healthConnectEnabled && settings.healthConnectWriteback && blocks.isNotEmpty()) {
                    val result = runCatching {
                        healthConnect.writeSession(
                            state.sessionName, blocks, calories, state.startedAtEpochMs, endedAt,
                        )
                    }.getOrElse { Result.failure(it) }
                    hcWritten = result.isSuccess
                    hcError = result.exceptionOrNull()?.message
                }

                val historyId = runCatching {
                    workoutRepo.addHistory(
                        startedAtEpochMs = state.startedAtEpochMs,
                        endedAtEpochMs = endedAt,
                        name = state.sessionName,
                        totalActiveSec = totalActiveSec,
                        calories = calories,
                        blocks = blocks,
                        healthConnectWritten = hcWritten,
                        session = state.session,
                    )
                }.getOrElse { -1L }

                // Keep the home-screen widget's "Repeat: <name>" and streak current.
                runCatching { au.mark.kinetiq.widget.KinetiqWidget().updateAll(this@WorkoutSessionService) }

                stateHolder.completed(
                    CompletedSummary(
                        sessionId = state.sessionId,
                        historyId = historyId,
                        name = state.sessionName,
                        startedAtEpochMs = state.startedAtEpochMs,
                        endedAtEpochMs = endedAt,
                        totalActiveSec = totalActiveSec,
                        calories = calories,
                        blocks = blocks,
                        healthConnectWritten = hcWritten,
                        healthConnectError = hcError,
                        session = state.session,
                        stoppedEarly = userStopped,
                    )
                )
            } finally {
                // A user-initiated stop stays recoverable for 10 minutes (accidental taps).
                if (userStopped) runCatching { writeStoppedSnapshot(state, es) }
                // Nothing here may touch a session that isn't ours. The Health Connect write above
                // is a cross-process IPC that can take seconds, and shouldIgnoreStart accepts a new
                // ACTION_START the moment `finished` is set — so by now the user may already have
                // started another workout, which this block would otherwise wipe out from under them.
                if (shouldTearDown(gen, runGeneration)) {
                    // Stop cleanly even if history or HC writes threw: no zombie foreground
                    // service, no stale "resume" offer for a finished session.
                    deleteSnapshot(this@WorkoutSessionService)
                    stateHolder.update(null)
                    // stopSelfResult declines if a newer command is queued; the fallback makes sure
                    // a control command racing the finish can never leave the service running.
                    if (!stopSelfResult(startId)) stopSelf()
                }
            }
        }
    }

    // ------------------------------------------------------------------ snapshot

    /**
     * Written on a user stop so an accidental stop stays recoverable. It must *complete* before the
     * finally returns — the service may be destroyed moments later — but synchronous does not mean
     * main-thread: NonCancellable + IO keeps the completion guarantee (and works even when the
     * finally is running because of cancellation) while moving a ~12 KB write off the UI thread.
     *
     * tmp + rename, matching [maybeSnapshot]: a kill mid-write must leave the previous file or none,
     * never a truncated one. A truncated file passes an existence + mtime check, which is exactly
     * how the Summary screen used to end up offering a Resume button that could not be honoured.
     */
    private suspend fun writeStoppedSnapshot(state: PlayerState, es: SessionEngine.EngineState?) =
        withContext(NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
        val snap = SessionSnapshot(
            session = state.session,
            sessionName = state.sessionName,
            stepIndex = es?.stepIndex ?: state.stepIndex,
            stepRemainingMs = es?.stepRemainingMs ?: state.stepRemainingMs,
            totalElapsedActiveMs = es?.totalElapsedActiveMs ?: state.totalElapsedActiveMs,
            caloriesSoFar = es?.caloriesSoFar ?: state.caloriesSoFar,
            startedAtEpochMs = state.startedAtEpochMs,
            weightKg = state.weightKg,
            savedAtEpochMs = System.currentTimeMillis(),
            blockActiveMs = es?.blockActiveMs ?: emptyMap(),
            blockBounds = es?.blockBounds?.mapValues { (_, v) -> listOf(v.first, v.second) } ?: emptyMap(),
            sessionId = state.sessionId,
        )
        val file = stoppedSnapshotFile(this@WorkoutSessionService)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(SessionSnapshot.serializer(), snap))
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("stopped snapshot rename failed")
        }
    }

    private fun maybeSnapshot() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotMs < 5_000) return
        lastSnapshotMs = now
        val state = stateHolder.state.value ?: return
        val es = engineState ?: return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val snap = SessionSnapshot(
                    session = state.session,
                    sessionName = state.sessionName,
                    stepIndex = es.stepIndex,
                    stepRemainingMs = es.stepRemainingMs,
                    totalElapsedActiveMs = es.totalElapsedActiveMs,
                    caloriesSoFar = es.caloriesSoFar,
                    startedAtEpochMs = state.startedAtEpochMs,
                    weightKg = state.weightKg,
                    savedAtEpochMs = System.currentTimeMillis(),
                    blockActiveMs = es.blockActiveMs,
                    blockBounds = es.blockBounds.mapValues { (_, v) -> listOf(v.first, v.second) },
                    prepareRemainingMs = es.prepareRemainingMs,
                    sessionId = state.sessionId,
                )
                val file = snapshotFile(this@WorkoutSessionService)
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(SessionSnapshot.serializer(), snap))
                tmp.renameTo(file)
            }
        }
    }

    // ------------------------------------------------------------------ notification

    /**
     * The `state == null` branches double as the placeholder posted synchronously from
     * onStartCommand, before the session has been loaded. Same builder, same channel, same
     * NOTIFICATION_ID, same actions — so refining it later is an in-place content replace with no
     * flicker, and the hints are chosen so ACTION_START's title does not change at all.
     */
    private fun buildNotification(titleHint: String? = null, textHint: String? = null): Notification {
        val state = stateHolder.state.value
        val step = state?.currentStep
        val inPrepare = state?.inPrepare == true
        val title = when {
            state == null && !titleHint.isNullOrBlank() -> titleHint
            inPrepare -> "Get ready"
            step != null -> step.exerciseName
            else -> getString(R.string.app_name)
        }
        val remaining = if (inPrepare) ((state?.prepareRemainingMs ?: 0) / 1000).toInt()
        else ((state?.stepRemainingMs ?: 0) / 1000).toInt()
        val text = when {
            state == null -> textHint ?: "Workout session"
            state.paused && !inPrepare -> "Paused — tap Resume to continue"
            inPrepare -> "Starting in ${remaining}s — ${step?.exerciseName ?: ""}"
            else -> "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')} — step ${state.stepIndex + 1}/${state.totalSteps}"
        }

        fun action(action: String, icon: Int, label: String): NotificationCompat.Action {
            val intent = Intent(this, WorkoutSessionService::class.java).setAction(action)
            val pi = PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action(icon, label, pi)
        }

        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val paused = state?.paused == true
        val stopLabel = if (SystemClock.elapsedRealtime() < stopArmedUntil)
            getString(R.string.notif_action_confirm_stop) else getString(R.string.notif_action_stop)
        return NotificationCompat.Builder(this, KinetiqApp.CHANNEL_SESSION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(
                if (paused) action(ACTION_RESUME, R.drawable.ic_notification, getString(R.string.notif_action_resume))
                else action(ACTION_PAUSE, R.drawable.ic_notification, getString(R.string.notif_action_pause))
            )
            .addAction(action(ACTION_SKIP, R.drawable.ic_notification, getString(R.string.notif_action_skip)))
            .addAction(action(ACTION_STOP, R.drawable.ic_notification, stopLabel))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onDestroy() {
        tickerJob?.cancel()
        isForeground = false
        unregisterAutoPause()
        releaseWakeLock()
        mediaSession?.release()
        voice.stopSpeaking()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 42
        const val ACTION_START = "au.mark.kinetiq.START"
        const val ACTION_RESUME_SNAPSHOT = "au.mark.kinetiq.RESUME_SNAPSHOT"
        const val ACTION_RESUME_STOPPED = "au.mark.kinetiq.RESUME_STOPPED"
        const val ACTION_PAUSE = "au.mark.kinetiq.PAUSE"
        const val ACTION_RESUME = "au.mark.kinetiq.RESUME"
        const val ACTION_SKIP = "au.mark.kinetiq.SKIP"
        const val ACTION_SKIP_PREPARE = "au.mark.kinetiq.SKIP_PREPARE"
        const val ACTION_EXTEND = "au.mark.kinetiq.EXTEND"
        const val ACTION_STOP = "au.mark.kinetiq.STOP"
        const val ACTION_STOP_CONFIRMED = "au.mark.kinetiq.STOP_CONFIRMED"
        const val EXTRA_SESSION_JSON = "session_json"
        const val EXTRA_SESSION_NAME = "session_name"
        private const val STOP_ARM_WINDOW_MS = 3_000L

        /** How long after a user stop the summary still offers to resume. */
        internal const val STOPPED_SNAPSHOT_VALID_MS = 10 * 60 * 1000L

        fun snapshotFile(context: Context): File = File(context.filesDir, "session_snapshot.json")

        fun stoppedSnapshotFile(context: Context): File = File(context.filesDir, "session_snapshot.stopped.json")

        fun hasStoppedSnapshot(context: Context): Boolean = stoppedSnapshotFile(context).let {
            it.exists() && System.currentTimeMillis() - it.lastModified() < STOPPED_SNAPSHOT_VALID_MS
        }

        fun readStoppedSnapshot(context: Context, json: Json): SessionSnapshot? = runCatching {
            if (!hasStoppedSnapshot(context)) return null
            json.decodeFromString(SessionSnapshot.serializer(), stoppedSnapshotFile(context).readText())
        }.getOrNull()

        fun resumeStopped(context: Context) {
            context.startForegroundService(
                Intent(context, WorkoutSessionService::class.java).setAction(ACTION_RESUME_STOPPED)
            )
        }

        fun hasSnapshot(context: Context): Boolean {
            val f = snapshotFile(context)
            if (!f.exists()) return false
            // Stale snapshots (> 6 h) are discarded.
            return System.currentTimeMillis() - f.lastModified() < 6 * 60 * 60 * 1000
        }

        fun readSnapshot(context: Context, json: Json): SessionSnapshot? = runCatching {
            val f = snapshotFile(context)
            if (!hasSnapshot(context)) return null
            json.decodeFromString(SessionSnapshot.serializer(), f.readText())
        }.getOrNull()

        fun deleteSnapshot(context: Context) {
            snapshotFile(context).delete()
        }

        fun start(context: Context, sessionJson: String, name: String) {
            context.startForegroundService(
                Intent(context, WorkoutSessionService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_SESSION_JSON, sessionJson)
                    .putExtra(EXTRA_SESSION_NAME, name)
            )
        }

        fun resumeSnapshot(context: Context) {
            context.startForegroundService(
                Intent(context, WorkoutSessionService::class.java).setAction(ACTION_RESUME_SNAPSHOT)
            )
        }

        fun command(context: Context, action: String) {
            context.startService(Intent(context, WorkoutSessionService::class.java).setAction(action))
        }
    }
}
