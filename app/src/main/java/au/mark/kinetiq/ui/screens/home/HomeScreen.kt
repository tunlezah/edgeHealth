package au.mark.kinetiq.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.data.model.displayName
import au.mark.kinetiq.data.repo.SavedWorkout
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.data.repo.WorkoutRepository
import au.mark.kinetiq.domain.plan.StreakCalculator
import au.mark.kinetiq.service.SessionStateHolder
import au.mark.kinetiq.service.WorkoutSessionService
import au.mark.kinetiq.ui.components.SectionHeader
import au.mark.kinetiq.ui.components.StatCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class HomeUiState(
    val streak: Int = 0,
    val weekSessions: Int = 0,
    val weekMinutes: Int = 0,
    val weekCalories: Int = 0,
    val lastWorkoutName: String? = null,
    val saved: List<SavedWorkout> = emptyList(),
)

/** Interrupted-session details for the resume card, read off the main thread. */
data class SnapshotInfo(val name: String, val stepIndex: Int, val totalSteps: Int)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    settingsRepository: SettingsRepository,
    val sessionStateHolder: SessionStateHolder,
    private val json: Json,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
) : ViewModel() {

    /** Snapshot check is disk I/O — never during composition. Refreshed on screen entry. */
    val snapshot = kotlinx.coroutines.flow.MutableStateFlow<SnapshotInfo?>(null)

    private var snapshotJob: kotlinx.coroutines.Job? = null

    fun refreshSnapshot() {
        // Cancel-and-replace: launching into viewModelScope means a restarted effect would
        // otherwise leave the previous read in flight, and results could land out of order.
        snapshotJob?.cancel()
        snapshotJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            snapshot.value = WorkoutSessionService.readSnapshot(appContext, json)?.let {
                SnapshotInfo(name = it.sessionName, stepIndex = it.stepIndex, totalSteps = it.session.plan.steps.size)
            }
        }
    }

    /**
     * A live session means the on-disk snapshot describes *that* session, and the resume card only
     * renders when there is no live session — so the parsed value could never be shown anyway.
     */
    fun clearSnapshot() {
        snapshotJob?.cancel()
        snapshot.value = null
    }

    init {
        refreshSnapshot()
    }

    val uiState = combine(
        workoutRepository.history(),
        workoutRepository.savedWorkouts(),
        settingsRepository.settings,
    ) { history, saved, settings ->
        val zone = ZoneId.systemDefault()
        val weekStart = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val thisWeek = history.filter {
            !Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate().isBefore(weekStart)
        }
        HomeUiState(
            streak = StreakCalculator.currentStreak(
                history.map { it.startedAtEpochMs },
                settings.restDays.mapNotNull { runCatching { DayOfWeek.of(it) }.getOrNull() }.toSet(),
            ),
            weekSessions = thisWeek.size,
            weekMinutes = thisWeek.sumOf { it.totalActiveSec } / 60,
            weekCalories = thisWeek.sumOf { it.calories }.toInt(),
            lastWorkoutName = history.firstOrNull()?.name,
            saved = saved,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    fun repeatLast(context: android.content.Context, onLaunched: () -> Unit) {
        viewModelScope.launch {
            val last = workoutRepository.lastSessionForRepeat() ?: return@launch
            val session = last.session ?: return@launch
            if (session.plan.steps.isEmpty()) return@launch
            start(context, session, last.entry.name)
            onLaunched()
        }
    }

    fun startSaved(context: android.content.Context, workout: SavedWorkout, onLaunched: () -> Unit) {
        start(context, workout.session, workout.name)
        onLaunched()
    }

    fun resumeSnapshot(context: android.content.Context, onLaunched: () -> Unit) {
        WorkoutSessionService.resumeSnapshot(context)
        onLaunched()
    }

    fun deleteSaved(id: Long) {
        viewModelScope.launch { workoutRepository.deleteSavedWorkout(id) }
    }

    private fun start(context: android.content.Context, session: GeneratedSession, name: String) {
        WorkoutSessionService.start(
            context, json.encodeToString(GeneratedSession.serializer(), session), name.ifBlank { "Workout" },
        )
    }
}

@Composable
fun HomeScreen(
    onStartBuilder: () -> Unit,
    onOpenPlayer: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenSummary: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val playerState by viewModel.sessionStateHolder.state.collectAsState()
    val lastCompleted by viewModel.sessionStateHolder.lastCompleted.collectAsState()
    val snapshot by viewModel.snapshot.collectAsState()
    val context = LocalContext.current
    var pendingDelete by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<SavedWorkout?>(null)
    }

    // Key on presence, not on the state object: playerState is rebuilt every 200 ms by the
    // service's ticker, which would otherwise re-read and re-parse the snapshot five times a
    // second for the whole workout while Home is open behind the player.
    val hasLiveSession = playerState != null
    androidx.compose.runtime.LaunchedEffect(hasLiveSession) {
        if (hasLiveSession) viewModel.clearSnapshot() else viewModel.refreshSnapshot()
    }

    pendingDelete?.let { workout ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete \"${workout.name}\"?") },
            text = { Text("This saved workout can't be recovered.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.deleteSaved(workout.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "Kinetiq",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        if (playerState != null) {
            item {
                Button(onClick = onOpenPlayer, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text("  Workout in progress — return to player")
                }
            }
        } else {
            val snap = snapshot
            if (snap != null) {
                item {
                    Button(
                        onClick = { viewModel.resumeSnapshot(context, onOpenPlayer) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Resume: ${snap.name} — step ${snap.stepIndex + 1} of ${snap.totalSteps}") }
                }
            }
        }
        // Parking spot for a summary the user navigated away from without tapping Done.
        val unviewedSummary = lastCompleted
        if (unviewedSummary != null && playerState == null) {
            item {
                OutlinedButton(onClick = onOpenSummary, modifier = Modifier.fillMaxWidth()) {
                    Text("View last session summary: ${unviewedSummary.name}")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("day streak", "${state.streak} 🔥", Modifier.weight(1f))
                StatCard("sessions this week", "${state.weekSessions}", Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("minutes this week", "${state.weekMinutes}", Modifier.weight(1f))
                StatCard("kcal this week", "${state.weekCalories}", Modifier.weight(1f))
            }
        }
        item {
            Button(onClick = onStartBuilder, modifier = Modifier.fillMaxWidth()) {
                Text("Build a workout")
            }
        }
        if (state.lastWorkoutName == null && state.saved.isEmpty()) {
            item {
                Text(
                    "Build your first workout to get started — your streak, saved workouts and weekly stats appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.lastWorkoutName != null) {
            item {
                OutlinedButton(
                    onClick = { viewModel.repeatLast(context, onOpenPlayer) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Repeat last: ${state.lastWorkoutName}") }
            }
        }
        item {
            OutlinedButton(onClick = onOpenHealth, modifier = Modifier.fillMaxWidth()) {
                Text("Body measurements & Health Connect")
            }
        }
        if (state.saved.isNotEmpty()) {
            item { SectionHeader("Saved workouts") }
            items(state.saved, key = { it.id }) { workout ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(workout.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${workout.session.plan.totalSec / 60} min · ${workout.session.config.categories.joinToString { it.displayName() }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { viewModel.startSaved(context, workout, onOpenPlayer) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Start ${workout.name}")
                        }
                        IconButton(onClick = { pendingDelete = workout }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete ${workout.name}")
                        }
                    }
                }
            }
        }
    }
}
