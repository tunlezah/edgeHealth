package au.mark.kinetiq.ui.screens.player

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.anim.ExerciseAnimationView
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.repo.ExerciseRepository
import au.mark.kinetiq.service.SessionStateHolder
import au.mark.kinetiq.service.WorkoutSessionService
import au.mark.kinetiq.ui.components.SettingSwitchRow
import au.mark.kinetiq.voice.TtsStatus
import au.mark.kinetiq.voice.VoiceCoach
import kotlinx.coroutines.launch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    val stateHolder: SessionStateHolder,
    private val voice: VoiceCoach,
    private val exerciseRepository: ExerciseRepository,
    private val settingsRepository: au.mark.kinetiq.data.repo.SettingsRepository,
) : ViewModel() {
    val voiceStatus = voice.status

    fun retryVoice() = voice.retryInit()

    fun persistKeepScreenOn(v: Boolean) {
        viewModelScope.launch { settingsRepository.setKeepScreenOn(v) }
    }

    suspend fun explainAgain() {
        val state = stateHolder.state.value ?: return
        val id = state.currentStep?.exerciseId ?: return
        exerciseRepository.exercise(id)?.let { voice.speak(it.voiceHowTo) }
    }
}

@Composable
fun PlayerScreen(
    keepScreenOnDefault: Boolean,
    onExit: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.stateHolder.state.collectAsState()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var keepScreenOn by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(keepScreenOnDefault) }
    var explainRequested by remember { mutableStateOf(0) }
    var showStopConfirm by remember { mutableStateOf(false) }

    if (showStopConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop workout?") },
            text = { Text("Your progress so far will be saved to history. You can resume from the summary for the next 10 minutes.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showStopConfirm = false
                    WorkoutSessionService.command(context, WorkoutSessionService.ACTION_STOP_CONFIRMED)
                }) { Text("Stop") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showStopConfirm = false }) { Text("Keep going") }
            },
        )
    }

    // FLAG_KEEP_SCREEN_ON while the player is visible and running; the screen may sleep on pause.
    val isPaused = state?.paused == true
    DisposableEffect(keepScreenOn, isPaused) {
        val window = activity?.window
        if (keepScreenOn && !isPaused) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(explainRequested) {
        if (explainRequested > 0) viewModel.explainAgain()
    }

    // Summary navigation is owned globally (KinetiqApp observes lastCompleted); this screen
    // only backs out if the service never publishes a session.
    LaunchedEffect(state) {
        if (state == null) {
            // Give a just-started service a moment to publish before bailing out.
            kotlinx.coroutines.delay(1500)
            if (viewModel.stateHolder.state.value == null && viewModel.stateHolder.lastCompleted.value == null) onExit()
        }
    }

    val s = state ?: return
    val step = s.currentStep ?: return
    if (s.inPrepare && !s.paused) {
        PrepareView(s)
        return
    }
    val remainingSec = (s.stepRemainingMs / 1000).toInt()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Time-weighted overall progress: a 5-minute segment and a 15-second rest no longer
        // advance the bar equally. Estimated against the planned totals — +30s extensions and
        // skips make it approximate; clamping absorbs any overrun.
        val plan = s.session.plan
        val elapsedInStep = (step.durationSec - remainingSec).coerceAtLeast(0)
        val elapsedSec = remember(s.stepIndex, plan) {
            plan.steps.take(s.stepIndex).sumOf { it.durationSec }
        } + elapsedInStep
        val planTotalSec = plan.totalSec.coerceAtLeast(1)
        val minutesLeft = ((planTotalSec - elapsedSec).coerceAtLeast(0) + 59) / 60
        Text(
            "${s.sessionName} · step ${s.stepIndex + 1} of ${s.totalSteps}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { (elapsedSec.toFloat() / planTotalSec).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Text(
            "~$minutesLeft min left",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val voiceStatus by viewModel.voiceStatus.collectAsState()
        if (voiceStatus == TtsStatus.FAILED) {
            Card(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Voice coach unavailable — cues are muted. Timers still run.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    androidx.compose.material3.TextButton(onClick = { viewModel.retryVoice() }) { Text("Retry") }
                }
            }
        }

        Text(
            step.exerciseName,
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        step.machineCueText?.let {
            // The machine cue is what the user reads from across the room — keep it big and
            // high-contrast, never a muted caption.
            Text(
                it,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }

        val isRestStep = step.type == StepType.REST || step.type == StepType.TRANSITION
        // Tap-anywhere-to-skip applies to the content region only (timer, animation, next-up) —
        // the control buttons below keep their own touch handling.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    if (isRestStep) Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Skip rest",
                    ) { WorkoutSessionService.command(context, WorkoutSessionService.ACTION_SKIP) }
                    else Modifier
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Big timer
            Text(
                "%d:%02d".format(remainingSec / 60, remainingSec % 60),
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .semantics { contentDescription = "$remainingSec seconds remaining" },
            )

            // Animation — large
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ExerciseAnimationView(
                    animationId = step.animationId,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1.05f),
                    contentDesc = "Animation of ${step.exerciseName}",
                    paused = s.paused,
                )
            }

            // Next up preview during rests/transitions
            val next = s.nextStep
            if (next != null && isRestStep) {
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ExerciseAnimationView(
                            animationId = next.animationId,
                            modifier = Modifier.size(84.dp),
                            contentDesc = "Next: ${next.exerciseName}",
                            // This card stays on screen during a paused rest, so without this it
                            // kept animating beside a frozen main animation.
                            paused = s.paused,
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("Next up", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(next.exerciseName, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                androidx.compose.material3.TextButton(
                    onClick = { WorkoutSessionService.command(context, WorkoutSessionService.ACTION_SKIP) },
                ) { Text("Skip rest — tap anywhere") }
                Spacer(Modifier.height(4.dp))
            }
        }

        // Controls — min 56dp touch targets, TalkBack labels
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledIconButton(
                onClick = { WorkoutSessionService.command(context, if (s.paused) WorkoutSessionService.ACTION_RESUME else WorkoutSessionService.ACTION_PAUSE) },
                modifier = Modifier.size(72.dp),
            ) {
                Icon(
                    if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (s.paused) "Resume workout" else "Pause workout",
                    modifier = Modifier.size(36.dp),
                )
            }
            FilledIconButton(
                onClick = { WorkoutSessionService.command(context, WorkoutSessionService.ACTION_SKIP) },
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Filled.SkipNext, contentDescription = "Skip step") }
            FilledIconButton(
                onClick = { WorkoutSessionService.command(context, WorkoutSessionService.ACTION_EXTEND) },
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Filled.MoreTime, contentDescription = "Add 30 seconds") }
            FilledIconButton(
                onClick = { showStopConfirm = true },
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Filled.Stop, contentDescription = "Stop workout") }
            IconButton(
                onClick = { explainRequested++ },
                modifier = Modifier.size(56.dp),
            ) { Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Explain this exercise again") }
        }

        SettingSwitchRow(
            title = "Keep screen on",
            checked = keepScreenOn,
            onCheckedChange = { keepScreenOn = it; viewModel.persistKeepScreenOn(it) },
        )
    }
}

/** GET-READY countdown before the current step's clock starts. Tap to jump to the last 3 s. */
@Composable
private fun PrepareView(s: au.mark.kinetiq.service.PlayerState) {
    val context = LocalContext.current
    val step = s.currentStep ?: return
    val prepareSec = ((s.prepareRemainingMs + 999) / 1000).toInt()
    Column(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClickLabel = "Start now",
            ) { WorkoutSessionService.command(context, WorkoutSessionService.ACTION_SKIP_PREPARE) }
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Get ready",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            "$prepareSec",
            style = MaterialTheme.typography.displayLarge,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .semantics { contentDescription = "Starting in $prepareSec seconds" },
        )
        Text("First up", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(step.exerciseName, style = MaterialTheme.typography.headlineSmall)
        step.machineCueText?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            ExerciseAnimationView(
                animationId = step.animationId,
                modifier = Modifier.fillMaxWidth().aspectRatio(1.05f),
                contentDesc = "Animation of ${step.exerciseName}",
            )
        }
        Text(
            "Tap anywhere to start now",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp),
        )
    }
}
