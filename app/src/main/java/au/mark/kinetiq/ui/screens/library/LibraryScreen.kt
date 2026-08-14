package au.mark.kinetiq.ui.screens.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.anim.ExerciseAnimationView
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.displayName
import au.mark.kinetiq.data.model.EvidenceTier
import au.mark.kinetiq.data.model.Exercise
import au.mark.kinetiq.data.model.Target
import au.mark.kinetiq.data.repo.ExerciseRepository
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.ui.components.EvidenceBadge
import au.mark.kinetiq.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibraryUiState(
    val exercises: List<Exercise> = emptyList(),
    val category: Category? = null,
    val target: Target? = null,
    val tier: EvidenceTier? = null,
    val includeLowEvidence: Boolean = false,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val uiState = MutableStateFlow(LibraryUiState())

    init {
        viewModelScope.launch {
            val all = exerciseRepository.exercises()
            uiState.value = uiState.value.copy(exercises = all)
        }
        viewModelScope.launch {
            settingsRepository.settings.collect {
                uiState.value = uiState.value.copy(includeLowEvidence = it.includeLowEvidence)
            }
        }
    }

    fun setCategory(c: Category?) { uiState.value = uiState.value.copy(category = c) }
    fun setTarget(t: Target?) { uiState.value = uiState.value.copy(target = t) }
    fun setTier(t: EvidenceTier?) { uiState.value = uiState.value.copy(tier = t) }

    suspend fun exercise(id: String): Exercise? = exerciseRepository.exercise(id)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(onOpen: (String) -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()

    val filtered = state.exercises.filter { ex ->
        (state.category == null || ex.category == state.category) &&
            (state.target == null || state.target in ex.targets) &&
            (state.tier == null || ex.evidenceTier == state.tier)
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Exercise library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            SectionHeader("Category")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { c ->
                    FilterChip(
                        selected = state.category == c,
                        onClick = { viewModel.setCategory(if (state.category == c) null else c) },
                        label = { Text(c.displayName()) },
                    )
                }
            }
        }
        item {
            SectionHeader("Target")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Target.entries.forEach { t ->
                    FilterChip(
                        selected = state.target == t,
                        onClick = { viewModel.setTarget(if (state.target == t) null else t) },
                        label = { Text(t.name.lowercase().replace('_', ' ')) },
                    )
                }
            }
        }
        item {
            SectionHeader("Evidence tier")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EvidenceTier.entries.forEach { t ->
                    FilterChip(
                        selected = state.tier == t,
                        onClick = { viewModel.setTier(if (state.tier == t) null else t) },
                        label = { Text(t.name.lowercase()) },
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    "No exercises match these filters.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(filtered, key = { it.id }) { ex ->
            val greyedOut = ex.evidenceTier == EvidenceTier.LIMITED && !state.includeLowEvidence
            Card(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(ex.id) },
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ExerciseAnimationView(
                        animationId = ex.animationId,
                        modifier = Modifier.size(72.dp),
                        contentDesc = "Preview of ${ex.name}",
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            ex.name + if (greyedOut) "  ·  limited evidence" else "",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (greyedOut) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${ex.category.displayName()} · ${ex.met} MET · ${ex.intensity.name.lowercase().replace('_', ' ')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    EvidenceBadge(ex.evidenceTier)
                }
            }
        }
    }
}

@Composable
fun ExerciseDetailScreen(exerciseId: String, onBack: () -> Unit, viewModel: LibraryViewModel = hiltViewModel()) {
    // null = still loading; Optional-style wrapper distinguishes "loading" from "not found".
    val loaded by produceState<Result<Exercise?>?>(null, exerciseId) {
        value = runCatching { viewModel.exercise(exerciseId) }
    }
    when {
        loaded == null -> {
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.CircularProgressIndicator() }
            return
        }
        loaded?.getOrNull() == null -> {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                Text("Exercise not found.", color = MaterialTheme.colorScheme.error)
            }
            return
        }
    }
    val ex = loaded?.getOrNull() ?: return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(ex.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            EvidenceBadge(ex.evidenceTier)
        }

        ExerciseAnimationView(
            animationId = ex.animationId,
            modifier = Modifier.fillMaxWidth().aspectRatio(1.1f),
            contentDesc = "Animation of ${ex.name}",
        )

        Text(ex.summary, style = MaterialTheme.typography.bodyLarge)

        SectionHeader("How to")
        Text(ex.voiceHowTo, style = MaterialTheme.typography.bodyMedium)

        SectionHeader("Form cues")
        ex.voiceFormCues.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }

        ex.machine?.let { machine ->
            SectionHeader("Machine setup")
            machine.reformer?.let { Text("Springs: ${it.springs.lowercase().replace('_', ' ')} — ${it.bodyPosition}") }
            machine.spin?.let { Text("Position: ${it.position} · ${it.cadenceRpmLow}–${it.cadenceRpmHigh} rpm") }
            machine.elliptical?.let { Text("${it.direction.lowercase()} stride · ${it.arms}") }
        }

        if (ex.popularityNote != null) {
            SectionHeader("Evidence note")
            Text(ex.popularityNote!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        }

        if (ex.references.isNotEmpty()) {
            SectionHeader("References")
            ex.references.forEach { ref ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${ref.authors} (${ref.year})", style = MaterialTheme.typography.labelLarge)
                        Text(ref.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "${ref.journal} · ${ref.doiOrPmid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(ref.finding, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Text(
            "Suggested time: ${ex.defaultWorkSec}s work / ${ex.defaultRestSec}s rest · ${ex.met} MET",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
