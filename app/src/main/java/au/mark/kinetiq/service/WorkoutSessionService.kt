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
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import au.mark.kinetiq.KinetiqApp
import au.mark.kinetiq.MainActivity
import au.mark.kinetiq.R
import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.repo.CompletedBlock
import au.mark.kinetiq.data.repo.MeasurementRepository
import au.mark.kinetiq.data.repo.Metric
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.data.repo.WorkoutRepository
import au.mark.kinetiq.domain.CalorieCalculator
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import au.mark.kinetiq.health.HealthConnectManager
import au.mark.kinetiq.voice.VoiceCoach
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

/**
 * Foreground service that runs the workout: an elapsed-realtime-based timer (accurate across
 * doze and screen-off, guarded by a partial wake lock), spoken coaching via [VoiceCoach],
 * media-style notification controls, and a 5-second disk snapshot for process-death restore.
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

    /** Wall-clock step boundaries for the per-block Health Connect records. */
    private val blockActiveMs = mutableMapOf<Int, Long>()
    private val blockBounds = mutableMapOf<Int, Pair<Long, Long>>()

    // Cue bookkeeping for the current step.
    private var halfwaySpoken = false
    private var countdownSpoken = false
    private var howToSpoken = false
    private var lastSnapshotMs = 0L

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSessionCompat(this, "KinetiqSession")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                val payload = intent.getStringExtra(EXTRA_SESSION_JSON)
                val name = intent.getStringExtra(EXTRA_SESSION_NAME) ?: "Workout"
                if (payload != null) startSession(json.decodeFromString(GeneratedSession.serializer(), payload), name)
            }
            ACTION_RESUME_SNAPSHOT -> restoreFromSnapshot()
            ACTION_PAUSE -> setPaused(true)
            ACTION_RESUME -> setPaused(false)
            ACTION_SKIP -> skipStep()
            ACTION_EXTEND -> extendStep()
            ACTION_STOP -> finishSession(userStopped = true)
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startSession(session: GeneratedSession, name: String) {
        lifecycleScope.launch {
            val settings = settingsRepo.current()
            voice.settings = settings.voice
            val weight = measurementRepo.resolved(Metric.WEIGHT_KG)?.value ?: settings.fallbackWeightKg.toDouble()

            val first = session.plan.steps.firstOrNull() ?: return@launch
            stateHolder.update(
                PlayerState(
                    session = session,
                    sessionName = name,
                    stepIndex = 0,
                    stepRemainingMs = first.durationSec * 1000L,
                    startedAtEpochMs = System.currentTimeMillis(),
                    weightKg = weight,
                )
            )
            blockActiveMs.clear(); blockBounds.clear()
            resetStepCues()
            goForeground()
            voice.warmUp {
                if (settings.disclaimerAcknowledged && settings.disclaimerLineInWorkout) {
                    voice.speak(getString(R.string.disclaimer_workout_reminder))
                }
                voice.speak("Starting ${name}. ${session.plan.steps.size} steps, about ${session.plan.totalSec / 60} minutes.")
                announceStep(fresh = true)
            }
            startTicker()
        }
    }

    private fun restoreFromSnapshot() {
        lifecycleScope.launch {
            val snap = readSnapshot(this@WorkoutSessionService, json) ?: return@launch
            val settings = settingsRepo.current()
            voice.settings = settings.voice
            stateHolder.update(
                PlayerState(
                    session = snap.session,
                    sessionName = snap.sessionName,
                    stepIndex = snap.stepIndex,
                    stepRemainingMs = snap.stepRemainingMs,
                    totalElapsedActiveMs = snap.totalElapsedActiveMs,
                    caloriesSoFar = snap.caloriesSoFar,
                    startedAtEpochMs = snap.startedAtEpochMs,
                    weightKg = snap.weightKg,
                    paused = true,
                )
            )
            resetStepCues()
            goForeground()
            voice.warmUp { voice.speak("Workout restored — paused. Resume when you are ready.") }
            startTicker()
        }
    }

    private fun goForeground() {
        val notification = buildNotification()
        val hasActivityRecognition = checkSelfPermission(android.Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED
        val type = if (hasActivityRecognition) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        } else {
            // TTS coaching audio makes this a legitimate media-playback service (DECISIONS.md D-03).
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
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

    private suspend fun onTick(state: PlayerState, deltaMs: Long) {
        val step = state.currentStep ?: run { finishSession(userStopped = false); return }
        var remaining = state.stepRemainingMs - deltaMs
        val stepDurationMs = step.durationSec * 1000L

        // Accrue active time + calories for WORK-type steps.
        val isActiveStep = step.type != StepType.TRANSITION && step.type != StepType.REST
        val addedCalories = if (isActiveStep)
            CalorieCalculator.kcal(step.met, state.weightKg, 1) * (deltaMs / 1000.0) else 0.0
        if (isActiveStep) {
            blockActiveMs.merge(step.blockIndex, deltaMs, Long::plus)
            val now = System.currentTimeMillis()
            blockBounds.merge(step.blockIndex, now to now) { old, new -> old.first to new.second }
        }

        // Halfway cue for long work steps.
        if (!halfwaySpoken && voice.settings.halfwayCue && isActiveStep &&
            stepDurationMs >= 40_000 && remaining <= stepDurationMs / 2
        ) {
            halfwaySpoken = true
            voice.speak("Halfway.")
        }

        // Countdown beeps in the final 3 seconds before a WORK step starts.
        val next = state.nextStep
        if (!countdownSpoken && remaining <= 3300 && next != null && next.type == StepType.WORK &&
            step.type != StepType.WORK
        ) {
            countdownSpoken = true
            voice.countdownBeeps()
        }

        // Speak the next exercise's how-to during rests/transitions.
        if (!howToSpoken && (step.type == StepType.REST || step.type == StepType.TRANSITION) &&
            remaining <= stepDurationMs - 1500 && voice.settings.howToDescription
        ) {
            howToSpoken = true
            speakNextHowTo(state)
        }

        if (remaining <= 0) {
            advanceStep(state, carryCalories = addedCalories)
        } else {
            stateHolder.update(
                state.copy(
                    stepRemainingMs = remaining,
                    totalElapsedActiveMs = state.totalElapsedActiveMs + (if (isActiveStep) deltaMs else 0),
                    caloriesSoFar = state.caloriesSoFar + addedCalories,
                )
            )
            maybeSnapshot()
            if ((state.stepRemainingMs / 1000) != (remaining / 1000)) updateNotification()
        }
    }

    private fun advanceStep(state: PlayerState, carryCalories: Double) {
        val nextIndex = state.stepIndex + 1
        val next = state.session.plan.steps.getOrNull(nextIndex)
        if (next == null) {
            stateHolder.update(state.copy(caloriesSoFar = state.caloriesSoFar + carryCalories))
            finishSession(userStopped = false)
            return
        }
        stateHolder.update(
            state.copy(
                stepIndex = nextIndex,
                stepRemainingMs = next.durationSec * 1000L,
                caloriesSoFar = state.caloriesSoFar + carryCalories,
            )
        )
        resetStepCues()
        announceStep(fresh = false)
        updateNotification()
    }

    private fun resetStepCues() {
        halfwaySpoken = false
        countdownSpoken = false
        howToSpoken = false
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
                if (fresh && voice.settings.howToDescription) speakCurrentHowTo(state)
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

    private fun speakCurrentHowTo(state: PlayerState) {
        lifecycleScope.launch {
            val step = state.currentStep ?: return@launch
            val id = step.exerciseId ?: return@launch
            howToFor(id)?.let { voice.speak(it) }
        }
    }

    private fun speakNextHowTo(state: PlayerState) {
        lifecycleScope.launch {
            val next = state.nextStep ?: return@launch
            if (next.type != StepType.WORK) return@launch
            val id = next.exerciseId ?: return@launch
            howToFor(id)?.let { voice.speak(it) }
        }
    }

    /** "Explain again" from the UI. */
    fun explainAgain() {
        val state = stateHolder.state.value ?: return
        speakCurrentHowTo(state)
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
        stateHolder.update(state.copy(paused = paused))
        voice.speak(if (paused) "Paused." else "Resuming.", flush = true)
        updateNotification()
    }

    private fun skipStep() {
        val state = stateHolder.state.value ?: return
        voice.stopSpeaking()
        advanceStep(state, carryCalories = 0.0)
    }

    private fun extendStep() {
        val state = stateHolder.state.value ?: return
        stateHolder.update(state.copy(stepRemainingMs = state.stepRemainingMs + 30_000))
        voice.speak("Thirty seconds added.")
        updateNotification()
    }

    private fun finishSession(userStopped: Boolean) {
        val state = stateHolder.state.value ?: return
        if (state.finished) return
        stateHolder.update(state.copy(finished = true, paused = true))
        tickerJob?.cancel()
        voice.speak(
            if (userStopped) "Workout stopped. Well done for showing up."
            else "Workout complete. Great work — remember to drink some water.",
            flush = true,
        )

        lifecycleScope.launch {
            val settings = settingsRepo.current()
            val endedAt = System.currentTimeMillis()
            val plan = state.session.plan

            val blocks = plan.blocks.mapIndexedNotNull { index, block ->
                val activeSec = ((blockActiveMs[index] ?: 0L) / 1000).toInt()
                if (activeSec <= 0) return@mapIndexedNotNull null
                val bounds = blockBounds[index] ?: (state.startedAtEpochMs to endedAt)
                val met = plan.steps.filter { it.blockIndex == index && it.type != StepType.REST && it.type != StepType.TRANSITION }
                    .map { it.met }.average().takeIf { !it.isNaN() } ?: 3.0
                CompletedBlock(
                    category = block.category.name,
                    activeSec = activeSec,
                    calories = CalorieCalculator.kcal(met.toFloat(), state.weightKg, activeSec),
                    isHiit = block.isHiit,
                    startedAtEpochMs = bounds.first,
                    endedAtEpochMs = bounds.second,
                )
            }

            val totalActiveSec = (state.totalElapsedActiveMs / 1000).toInt()
            var hcWritten = false
            var hcError: String? = null
            if (settings.healthConnectEnabled && settings.healthConnectWriteback && blocks.isNotEmpty()) {
                val result = healthConnect.writeSession(
                    state.sessionName, blocks, state.caloriesSoFar, state.startedAtEpochMs, endedAt,
                )
                hcWritten = result.isSuccess
                hcError = result.exceptionOrNull()?.message
            }

            val historyId = workoutRepo.addHistory(
                startedAtEpochMs = state.startedAtEpochMs,
                endedAtEpochMs = endedAt,
                name = state.sessionName,
                totalActiveSec = totalActiveSec,
                calories = state.caloriesSoFar,
                blocks = blocks,
                healthConnectWritten = hcWritten,
                session = state.session,
            )

            stateHolder.completed(
                CompletedSummary(
                    historyId = historyId,
                    name = state.sessionName,
                    startedAtEpochMs = state.startedAtEpochMs,
                    endedAtEpochMs = endedAt,
                    totalActiveSec = totalActiveSec,
                    calories = state.caloriesSoFar,
                    blocks = blocks,
                    healthConnectWritten = hcWritten,
                    healthConnectError = hcError,
                    session = state.session,
                )
            )
            deleteSnapshot(this@WorkoutSessionService)
            stateHolder.update(null)
            stopSelf()
        }
    }

    // ------------------------------------------------------------------ snapshot

    private fun maybeSnapshot() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotMs < 5_000) return
        lastSnapshotMs = now
        val state = stateHolder.state.value ?: return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val snap = SessionSnapshot(
                    session = state.session,
                    sessionName = state.sessionName,
                    stepIndex = state.stepIndex,
                    stepRemainingMs = state.stepRemainingMs,
                    totalElapsedActiveMs = state.totalElapsedActiveMs,
                    caloriesSoFar = state.caloriesSoFar,
                    startedAtEpochMs = state.startedAtEpochMs,
                    weightKg = state.weightKg,
                    savedAtEpochMs = System.currentTimeMillis(),
                )
                val file = snapshotFile(this@WorkoutSessionService)
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(SessionSnapshot.serializer(), snap))
                tmp.renameTo(file)
            }
        }
    }

    // ------------------------------------------------------------------ notification

    private fun buildNotification(): Notification {
        val state = stateHolder.state.value
        val step = state?.currentStep
        val title = step?.exerciseName ?: getString(R.string.app_name)
        val remaining = ((state?.stepRemainingMs ?: 0) / 1000).toInt()
        val text = if (state != null)
            "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')} — step ${state.stepIndex + 1}/${state.totalSteps}"
        else "Workout session"

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
            .addAction(action(ACTION_STOP, R.drawable.ic_notification, getString(R.string.notif_action_stop)))
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
        wakeLock?.let { if (it.isHeld) it.release() }
        mediaSession?.release()
        voice.stopSpeaking()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 42
        const val ACTION_START = "au.mark.kinetiq.START"
        const val ACTION_RESUME_SNAPSHOT = "au.mark.kinetiq.RESUME_SNAPSHOT"
        const val ACTION_PAUSE = "au.mark.kinetiq.PAUSE"
        const val ACTION_RESUME = "au.mark.kinetiq.RESUME"
        const val ACTION_SKIP = "au.mark.kinetiq.SKIP"
        const val ACTION_EXTEND = "au.mark.kinetiq.EXTEND"
        const val ACTION_STOP = "au.mark.kinetiq.STOP"
        const val EXTRA_SESSION_JSON = "session_json"
        const val EXTRA_SESSION_NAME = "session_name"

        fun snapshotFile(context: Context): File = File(context.filesDir, "session_snapshot.json")

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
