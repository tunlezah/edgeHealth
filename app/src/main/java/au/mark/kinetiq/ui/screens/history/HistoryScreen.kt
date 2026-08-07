package au.mark.kinetiq.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.data.repo.HistoryEntry
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.data.repo.WorkoutRepository
import au.mark.kinetiq.domain.plan.StreakCalculator
import au.mark.kinetiq.ui.components.SectionHeader
import au.mark.kinetiq.ui.components.StatCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class HistoryUiState(
    val entries: List<HistoryEntry> = emptyList(),
    val workoutDays: Set<LocalDate> = emptySet(),
    val streak: Int = 0,
    val sessionsPerWeek: Float = 0f,
    val minutesPerWeek: Int = 0,
    val caloriesPerWeek: Int = 0,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState = combine(workoutRepository.history(), settingsRepository.settings) { history, settings ->
        val zone = ZoneId.systemDefault()
        val days = history.map { Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate() }.toSet()
        // Simple trends over the trailing 4 weeks.
        val fourWeeksAgo = LocalDate.now().minusWeeks(4)
        val recent = history.filter {
            !Instant.ofEpochMilli(it.startedAtEpochMs).atZone(zone).toLocalDate().isBefore(fourWeeksAgo)
        }
        HistoryUiState(
            entries = history,
            workoutDays = days,
            streak = StreakCalculator.currentStreak(
                history.map { it.startedAtEpochMs },
                settings.restDays.mapNotNull { runCatching { DayOfWeek.of(it) }.getOrNull() }.toSet(),
            ),
            sessionsPerWeek = recent.size / 4f,
            minutesPerWeek = recent.sumOf { it.totalActiveSec } / 60 / 4,
            caloriesPerWeek = recent.sumOf { it.calories }.toInt() / 4,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryUiState())

    fun delete(id: Long) {
        viewModelScope.launch { workoutRepository.deleteHistory(id) }
    }
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var month by remember { mutableStateOf(YearMonth.now()) }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text("History", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("day streak", "${state.streak} 🔥", Modifier.weight(1f))
                StatCard("sessions / week (4-wk avg)", "%.1f".format(state.sessionsPerWeek), Modifier.weight(1f))
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("minutes / week", "${state.minutesPerWeek}", Modifier.weight(1f))
                StatCard("kcal / week", "${state.caloriesPerWeek}", Modifier.weight(1f))
            }
        }
        item {
            SectionHeader("Calendar")
            CalendarMonth(
                month = month,
                workoutDays = state.workoutDays,
                onPrev = { month = month.minusMonths(1) },
                onNext = { month = month.plusMonths(1) },
            )
        }
        item { SectionHeader("Sessions") }
        items(state.entries, key = { it.id }) { entry ->
            val formatter = remember { DateTimeFormatter.ofPattern("EEE d MMM, HH:mm") }
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            Instant.ofEpochMilli(entry.startedAtEpochMs).atZone(ZoneId.systemDefault()).format(formatter) +
                                " · ${entry.totalActiveSec / 60} min · ${entry.calories.toInt()} kcal" +
                                if (entry.healthConnectWritten) " · HC ✓" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { viewModel.delete(entry.id) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete session ${entry.name}")
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarMonth(
    month: YearMonth,
    workoutDays: Set<LocalDate>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous month") }
                Text(
                    month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = onNext) { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next month") }
            }
            Row(Modifier.fillMaxWidth()) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Text(
                        it, modifier = Modifier.weight(1f), textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val firstDay = month.atDay(1)
            val leadingBlanks = firstDay.dayOfWeek.value - 1
            val totalCells = leadingBlanks + month.lengthOfMonth()
            val rows = (totalCells + 6) / 7
            repeat(rows) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { col ->
                        val cell = row * 7 + col
                        val dayNum = cell - leadingBlanks + 1
                        Box(Modifier.weight(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                            if (dayNum in 1..month.lengthOfMonth()) {
                                val date = month.atDay(dayNum)
                                val done = date in workoutDays
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .background(
                                            if (done) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                                            CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "$dayNum",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
