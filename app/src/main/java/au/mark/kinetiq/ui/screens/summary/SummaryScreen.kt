package au.mark.kinetiq.ui.screens.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.data.model.displayName
import au.mark.kinetiq.data.repo.WorkoutRepository
import au.mark.kinetiq.service.SessionStateHolder
import au.mark.kinetiq.ui.components.SectionHeader
import au.mark.kinetiq.ui.components.StatCard
import au.mark.kinetiq.service.WorkoutSessionService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject

/** Outcome of tapping "Resume workout" on the summary. */
sealed interface ResumeOutcome {
    data object Started : ResumeOutcome
    /** Gone, expired, or unparseable — the history row stays put. */
    data object Expired : ResumeOutcome
    /** The service never published a live session — the history row stays put. */
    data object TimedOut : ResumeOutcome
}

@HiltViewModel
class SummaryViewModel @Inject constructor(
    val stateHolder: SessionStateHolder,
    private val workoutRepository: WorkoutRepository,
    private val healthConnect: au.mark.kinetiq.health.HealthConnectManager,
    private val json: kotlinx.serialization.json.Json,
    private val exerciseRepository: au.mark.kinetiq.data.repo.ExerciseRepository,
    private val settingsRepository: au.mark.kinetiq.data.repo.SettingsRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    /** Progression advice from this session's set ratings; empty for sessions without resistance sets. */
    val suggestions = MutableStateFlow<List<au.mark.kinetiq.domain.resistance.ProgressionAdvisor.Suggestion>>(emptyList())

    /** Ladders already moved up from this summary, so the button cannot be applied twice. */
    val movedUp = MutableStateFlow<Set<String>>(emptySet())

    fun loadSuggestions() {
        val s = stateHolder.lastCompleted.value ?: return
        viewModelScope.launch {
            val outcomes = au.mark.kinetiq.domain.resistance.ResistanceTracking.outcomes(
                s.session.plan, s.completedSets, s.setRatings,
            )
            movedUp.value = emptySet()
            if (outcomes.isEmpty()) {
                suggestions.value = emptyList()
                return@launch
            }
            // Advice is a nicety; a failed lookup must never take the summary down with it.
            suggestions.value = runCatching {
                val settings = settingsRepository.current()
                val kit = au.mark.kinetiq.domain.generator.ExerciseEligibility.kitFor(
                    settings.equipment.owned, settings.equipment.handWeightMaxKg, s.session.config.usesHandWeights,
                )
                au.mark.kinetiq.domain.resistance.ProgressionAdvisor.suggestions(
                    outcomes, exerciseRepository.exercises(),
                ) { au.mark.kinetiq.domain.generator.ExerciseEligibility.allowed(it, settings.constraints, kit) }
            }.getOrDefault(emptyList())
        }
    }

    fun moveUp(suggestion: au.mark.kinetiq.domain.resistance.ProgressionAdvisor.Suggestion) {
        val ladder = suggestion.ladder ?: return
        val rung = suggestion.nextRung ?: return
        viewModelScope.launch {
            settingsRepository.setLadderRung(ladder, rung)
            movedUp.value = movedUp.value + ladder
        }
    }

    /** Re-attempt a failed Health Connect write; clientRecordIds make this upsert-safe. */
    fun retryHealthConnect() {
        val s = stateHolder.lastCompleted.value ?: return
        viewModelScope.launch {
            val result = healthConnect.writeSession(
                s.name, s.blocks, s.calories, s.startedAtEpochMs, s.endedAtEpochMs,
            )
            if (result.isSuccess && s.historyId > 0) workoutRepository.markHcWritten(s.historyId)
            stateHolder.completed(
                s.copy(
                    healthConnectWritten = result.isSuccess,
                    healthConnectError = result.exceptionOrNull()?.message,
                )
            )
        }
    }
    fun saveWorkout(name: String, onSaved: () -> Unit) {
        val summary = stateHolder.lastCompleted.value ?: return
        viewModelScope.launch {
            workoutRepository.saveWorkout(name, summary.session)
            onSaved()
        }
    }

    fun done() {
        stateHolder.clearCompleted()
    }

    /**
     * Remaining resume window in ms; null means no offer. Disk I/O is never done during
     * composition — the rule this screen used to break, which is also what made the offer stick
     * around forever: a value computed in the composable body has nothing to invalidate it.
     */
    private val _resumeWindowMs = MutableStateFlow<Long?>(null)
    val resumeWindowMs: StateFlow<Long?> = _resumeWindowMs.asStateFlow()

    /** Explains a resume that could not be honoured, rather than failing silently. */
    val message = MutableStateFlow<String?>(null)

    private var windowJob: Job? = null
    private var resumeInFlight = false

    /**
     * Gate the offer on a full read *and parse*, not on existence + mtime — a torn write passes an
     * mtime check. Then tick the remaining window once a second so the button disappears exactly
     * when the window the app advertised ("resume for the next 10 minutes") actually closes.
     */
    fun watchResumeWindow() {
        windowJob?.cancel()
        windowJob = viewModelScope.launch {
            val expiresAt = withContext(Dispatchers.IO) {
                val parsed = WorkoutSessionService.readStoppedSnapshot(appContext, json)
                if (parsed == null || parsed.session.plan.steps.isEmpty()) null
                else WorkoutSessionService.stoppedSnapshotFile(appContext).lastModified() +
                    WorkoutSessionService.STOPPED_SNAPSHOT_VALID_MS
            } ?: run { _resumeWindowMs.value = null; return@launch }

            while (isActive) {
                val left = expiresAt - System.currentTimeMillis()
                _resumeWindowMs.value = left.takeIf { it > 0 }
                if (left <= 0) return@launch
                delay(1_000)
            }
        }
    }

    /**
     * Undo an accidental stop.
     *
     * The order is load-bearing. The history row is the user's only record of the workout and there
     * is no server copy, so it is deleted strictly *after* the service has published a live session
     * for the restored run. A failed restore therefore leaves history intact; a successful one
     * leaves no duplicate, because the resumed run writes its own row when it finishes.
     */
    fun resumeStopped(context: android.content.Context, onResult: (ResumeOutcome) -> Unit) {
        val summary = stateHolder.lastCompleted.value ?: return
        if (resumeInFlight) return
        resumeInFlight = true
        viewModelScope.launch {
            // 1. Prove the snapshot is restorable before touching anything destructive.
            val snap = withContext(Dispatchers.IO) {
                WorkoutSessionService.readStoppedSnapshot(context, json)
            }
            if (snap == null || snap.session.plan.steps.isEmpty()) {
                resumeInFlight = false
                _resumeWindowMs.value = null
                message.value = "That workout can no longer be resumed — it stays in your history."
                onResult(ResumeOutcome.Expired)
                return@launch
            }
            // 2. Ask the service to restore.
            WorkoutSessionService.resumeStopped(context)
            // 3. Wait for proof. startedAtEpochMs is copied verbatim from the snapshot into the
            //    restored state, so it identifies this run and excludes any leftover.
            val live = withTimeoutOrNull(RESUME_CONFIRM_TIMEOUT_MS) {
                stateHolder.state.first {
                    it != null && !it.finished && it.startedAtEpochMs == snap.startedAtEpochMs
                }
            }
            if (live == null) {
                resumeInFlight = false
                message.value = "Couldn't restart that workout — it's still in your history."
                onResult(ResumeOutcome.TimedOut)
                return@launch
            }
            // 4. Confirmed. Only now retire the stopped run's row and its summary.
            if (summary.historyId > 0) runCatching { workoutRepository.deleteHistory(summary.historyId) }
            stateHolder.clearCompleted()
            resumeInFlight = false
            onResult(ResumeOutcome.Started)
        }
    }

    companion object {
        private const val RESUME_CONFIRM_TIMEOUT_MS = 8_000L
    }
}

@Composable
fun SummaryScreen(
    onDone: () -> Unit,
    onResume: () -> Unit = {},
    viewModel: SummaryViewModel = hiltViewModel(),
) {
    val summary by viewModel.stateHolder.lastCompleted.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    // Navigation is a side effect — never run it during composition, and only once. A resume
    // clears the summary too, but owns its own navigation — don't double-navigate.
    var resuming by remember { mutableStateOf(false) }
    val resumeMessage by viewModel.message.collectAsState()
    androidx.compose.runtime.LaunchedEffect(summary) {
        if (summary == null && !resuming) onDone()
    }
    val s = summary ?: return
    androidx.compose.runtime.LaunchedEffect(s.sessionId) {
        viewModel.watchResumeWindow()
        viewModel.loadSuggestions()
    }
    val suggestions by viewModel.suggestions.collectAsState()
    val movedUp by viewModel.movedUp.collectAsState()
    var name by androidx.compose.runtime.saveable.rememberSaveable(s.sessionId) { mutableStateOf(s.name) }
    // Tracks the name the workout was last saved under; editing re-enables "Save as new name".
    var savedAs by androidx.compose.runtime.saveable.rememberSaveable(s.sessionId) { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Session complete 🎉", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("active minutes", "${s.totalActiveSec / 60}", Modifier.weight(1f))
            StatCard("est. kcal (MET-based)", "${s.calories.toInt()}", Modifier.weight(1f))
        }

        SectionHeader("Per-block breakdown")
        s.blocks.forEach { block ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        val categoryLabel = runCatching {
                            au.mark.kinetiq.data.model.Category.valueOf(block.category)
                        }.getOrNull()?.displayName()
                            ?: block.category.lowercase().replaceFirstChar { it.uppercase() }
                        Text(
                            categoryLabel + when {
                                block.isHiit -> " · HIIT"
                                block.isResistance -> " · resistance sets"
                                else -> ""
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${block.activeSec / 60} min ${block.activeSec % 60}s · ${block.calories.toInt()} kcal" +
                                if (block.isResistance) " · ${block.hardSets} hard set${if (block.hardSets == 1) "" else "s"}" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (block.isResistance && block.muscleSets.isNotEmpty()) {
                            Text(
                                au.mark.kinetiq.domain.resistance.ResistanceTracking.totalByMuscle(listOf(block.muscleSets))
                                    .entries.joinToString(" · ") { (m, n) -> "${m.displayName().lowercase()} ${formatSets(n)}" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            SectionHeader("Next time")
            suggestions.forEach { sg ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(sg.exerciseName, style = MaterialTheme.typography.titleMedium)
                        val moved = sg.ladder != null && sg.ladder in movedUp
                        Text(
                            when {
                                moved -> "Next session uses ${sg.nextExerciseName}."
                                sg.advice == au.mark.kinetiq.domain.resistance.ResistanceTracking.Advice.MOVE_UP && sg.canMoveUp ->
                                    "Four or more reps in hand on every set — it has stopped being hard enough to count. " +
                                        "${sg.nextExerciseName} is the next step up."
                                sg.advice == au.mark.kinetiq.domain.resistance.ResistanceTracking.Advice.MOVE_UP ->
                                    "Four or more reps in hand on every set, so those sets did not count. There is no harder " +
                                        "version your equipment allows: slow the lowering, pause where it is hardest, or it " +
                                        "needs a heavier load."
                                else ->
                                    "You finished with reps in hand. Same version next time — keep going until you couldn't " +
                                        "do another good rep."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!moved && sg.canMoveUp) {
                            OutlinedButton(onClick = { viewModel.moveUp(sg) }) { Text("Move up to ${sg.nextExerciseName}") }
                        }
                    }
                }
            }
        }

        SectionHeader("Health Connect")
        Text(
            when {
                s.healthConnectWritten -> "✓ Session written to Health Connect (one record per block + calories)."
                s.healthConnectError != null -> "Could not write to Health Connect: ${s.healthConnectError}"
                else -> "Health Connect write-back is off or not connected."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!s.healthConnectWritten && s.healthConnectError != null) {
            OutlinedButton(
                onClick = viewModel::retryHealthConnect,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Retry Health Connect write") }
        }

        SectionHeader("Save this workout")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Workout name") },
            modifier = Modifier.fillMaxWidth(),
        )
        val effectiveName = name.ifBlank { s.name }
        Button(
            onClick = { viewModel.saveWorkout(effectiveName) { savedAs = effectiveName } },
            enabled = savedAs != effectiveName,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    savedAs == effectiveName -> "Saved ✓ — reusable from Home"
                    savedAs != null -> "Save as new name"
                    else -> "Save workout"
                }
            )
        }

        // The window is a live value from the ViewModel, not a disk check evaluated once during
        // composition — so the offer disappears exactly when it expires instead of lingering and
        // then failing (which used to delete the history row before discovering it had).
        val resumeLeftMs by viewModel.resumeWindowMs.collectAsState()
        resumeLeftMs?.takeIf { s.stoppedEarly }?.let { left ->
            val minsLeft = ((left + 59_999) / 60_000).toInt()
            OutlinedButton(
                onClick = {
                    resuming = true
                    viewModel.resumeStopped(context) { outcome ->
                        if (outcome == ResumeOutcome.Started) onResume() else resuming = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stopped by accident? Resume workout · $minsLeft min left") }
        }
        resumeMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        OutlinedButton(
            // Clearing the summary triggers the LaunchedEffect above exactly once — calling
            // onDone here as well would double-pop the back stack.
            onClick = { viewModel.done() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done") }
    }
}

/** "3" for 3.0, "2.5" for 2.5 — secondary muscles earn half sets. */
private fun formatSets(n: Double): String = if (n % 1.0 == 0.0) n.toInt().toString() else "%.1f".format(n)
