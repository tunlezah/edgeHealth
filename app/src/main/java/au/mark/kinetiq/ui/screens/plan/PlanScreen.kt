package au.mark.kinetiq.ui.screens.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.data.repo.WorkoutRepository
import au.mark.kinetiq.domain.plan.WeeklyPlanEngine
import au.mark.kinetiq.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class PlanUiState(
    val progress: WeeklyPlanEngine.WeeklyProgress,
    val visceralFatGoal: Boolean,
)

@HiltViewModel
class PlanViewModel @Inject constructor(
    workoutRepository: WorkoutRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val uiState = combine(workoutRepository.history(), settingsRepository.settings) { history, settings ->
        PlanUiState(
            progress = WeeklyPlanEngine.progressForWeek(history, settings.visceralFatGoal),
            visceralFatGoal = settings.visceralFatGoal,
        )
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000),
        PlanUiState(
            progress = WeeklyPlanEngine.progressForWeek(emptyList(), visceralFatGoal = true),
            visceralFatGoal = true,
        ),
    )
}

@Composable
fun PlanScreen(viewModel: PlanViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val progress = state.progress
    val targets = progress.targets

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Weekly plan", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (state.visceralFatGoal) {
                "Grounded in WHO 2020 activity guidelines and visceral-fat dose–response evidence: " +
                    "at least 3 cardio sessions a week of 30–60 minutes, plus strength work twice a week. " +
                    "Full citations live in each exercise's library page."
            } else {
                "Grounded in WHO 2020 activity guidelines: 150–300 minutes of moderate activity a week, " +
                    "plus strength work twice a week. Full citations live in each exercise's library page."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("This week's suggestion", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Text(progress.suggestion, style = MaterialTheme.typography.bodyLarge)
            }
        }

        SectionHeader("Progress")
        PlanProgressRow(
            "Moderate+ cardio minutes",
            progress.cardioMinutesDone, targets.moderateCardioMinutes, "min",
        )
        PlanProgressRow(
            "Cardio sessions (bike / elliptical / HIIT)",
            progress.cardioSessionsDone, targets.cardioSessions, "sessions",
        )
        PlanProgressRow(
            "Strength sessions (floor / reformer)",
            progress.strengthSessionsDone, targets.strengthSessions, "sessions",
        )
    }
}

@Composable
private fun PlanProgressRow(label: String, done: Int, target: Int, unit: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row {
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "$done / $target $unit",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done >= target) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            LinearProgressIndicator(
                progress = { (done.toFloat() / target).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
