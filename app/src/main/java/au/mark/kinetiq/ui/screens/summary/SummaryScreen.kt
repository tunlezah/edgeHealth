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
) : ViewModel() {
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
}

@Composable
fun SummaryScreen(onDone: () -> Unit, viewModel: SummaryViewModel = hiltViewModel()) {
    val summary by viewModel.stateHolder.lastCompleted.collectAsState()
    val s = summary ?: run { onDone(); return }
    var name by remember { mutableStateOf(s.name) }
    var saved by remember { mutableStateOf(false) }

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
                        Text(
                            block.category.lowercase().replaceFirstChar { it.uppercase() } +
                                if (block.isHiit) " · HIIT" else "",
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

        SectionHeader("Save this workout")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Workout name") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.saveWorkout(name.ifBlank { s.name }) { saved = true } },
            enabled = !saved,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (saved) "Saved ✓ — reusable from Home" else "Save workout") }

        OutlinedButton(
            onClick = { viewModel.done(); onDone() },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Done") }
    }
}
