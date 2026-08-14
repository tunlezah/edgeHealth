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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SummaryViewModel @Inject constructor(
    val stateHolder: SessionStateHolder,
    private val workoutRepository: WorkoutRepository,
    private val healthConnect: au.mark.kinetiq.health.HealthConnectManager,
) : ViewModel() {

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

    /** Undo an accidental stop: drop the just-written history row and restore the session. */
    fun resumeStopped(context: android.content.Context, onResumed: () -> Unit) {
        val summary = stateHolder.lastCompleted.value ?: return
        viewModelScope.launch {
            if (summary.historyId > 0) workoutRepository.deleteHistory(summary.historyId)
            stateHolder.clearCompleted()
            au.mark.kinetiq.service.WorkoutSessionService.resumeStopped(context)
            onResumed()
        }
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
    androidx.compose.runtime.LaunchedEffect(summary) {
        if (summary == null && !resuming) onDone()
    }
    val s = summary ?: return
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
                            categoryLabel + if (block.isHiit) " · HIIT" else "",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "${block.activeSec / 60} min ${block.activeSec % 60}s · ${block.calories.toInt()} kcal",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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

        if (s.stoppedEarly && au.mark.kinetiq.service.WorkoutSessionService.hasStoppedSnapshot(context)) {
            OutlinedButton(
                onClick = {
                    resuming = true
                    viewModel.resumeStopped(context, onResume)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Stopped by accident? Resume workout") }
        }

        OutlinedButton(
            // Clearing the summary triggers the LaunchedEffect above exactly once — calling
            // onDone here as well would double-pop the back stack.
            onClick = { viewModel.done() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done") }
    }
}
