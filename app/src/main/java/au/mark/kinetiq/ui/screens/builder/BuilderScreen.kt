package au.mark.kinetiq.ui.screens.builder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.displayName
import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.data.model.GeneratorConfig
import au.mark.kinetiq.data.model.Intensity
import au.mark.kinetiq.data.model.SessionStep
import au.mark.kinetiq.data.model.StepType
import au.mark.kinetiq.data.repo.ExerciseRepository
import au.mark.kinetiq.data.repo.MeasurementRepository
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.domain.generator.GeneratorWarning
import au.mark.kinetiq.domain.generator.WorkoutGenerator
import au.mark.kinetiq.domain.MachineCueRenderer
import au.mark.kinetiq.service.WorkoutSessionService
import au.mark.kinetiq.ui.components.SectionHeader
import au.mark.kinetiq.ui.components.formatSec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

data class BuilderUiState(
    val config: GeneratorConfig = GeneratorConfig(),
    val preview: GeneratedSession? = null,
    val warnings: List<GeneratorWarning> = emptyList(),
    val generating: Boolean = false,
    /** Config changed since the preview was generated — preview kept, banner shown. */
    val configChanged: Boolean = false,
    /** The user hand-edited the preview (reorder/swap/remove). */
    val edited: Boolean = false,
    val showContinuousNotice: Boolean = false,
)

@HiltViewModel
class BuilderViewModel @Inject constructor(
    private val exerciseRepository: ExerciseRepository,
    private val settingsRepository: SettingsRepository,
    private val measurementRepository: MeasurementRepository,
    private val json: Json,
) : ViewModel() {

    val uiState = MutableStateFlow(BuilderUiState())

    init {
        // Start from the last used config when one exists; otherwise seed the settings default.
        viewModelScope.launch {
            val raw = settingsRepository.lastConfigJson.first()
            val restored = raw?.let {
                runCatching { json.decodeFromString(GeneratorConfig.serializer(), it) }.getOrNull()
            }
            uiState.value = uiState.value.copy(
                config = restored
                    ?: uiState.value.config.copy(restMode = settingsRepository.current().defaultRestMode),
            )
        }
    }

    /** Keeps a stale preview visible (banner) instead of silently discarding hand edits. */
    fun updateConfig(transform: (GeneratorConfig) -> GeneratorConfig) {
        val hadPreview = uiState.value.preview != null
        uiState.value = uiState.value.copy(
            config = transform(uiState.value.config),
            configChanged = hadPreview,
        )
    }

    fun selectRestMode(mode: au.mark.kinetiq.data.model.RestMode) {
        updateConfig { it.copy(restMode = mode) }
        if (mode == au.mark.kinetiq.data.model.RestMode.CONTINUOUS) {
            viewModelScope.launch {
                if (!settingsRepository.current().continuousNoticeSeen) {
                    uiState.value = uiState.value.copy(showContinuousNotice = true)
                }
            }
        }
    }

    fun dismissContinuousNotice() {
        viewModelScope.launch { settingsRepository.setContinuousNoticeSeen(true) }
        uiState.value = uiState.value.copy(showContinuousNotice = false)
    }

    private var generateSeq = 0

    fun generate() {
        val seq = ++generateSeq
        viewModelScope.launch {
            // Set on Main before the switch and cleared on Main after, so the flag's window is
            // strictly longer than before — the Generate button's enabled state is unaffected.
            uiState.value = uiState.value.copy(generating = true)
            val settings = settingsRepository.current()
            val metrics = if (uiState.value.config.useHealthData) measurementRepository.bodyMetrics() else null
            val exercises = exerciseRepository.exercises()
            val routines = exerciseRepository.routines()
            // Snapshot what we are generating for, so a config edit mid-generation cannot make the
            // result describe a different configuration than the one that was saved.
            val config = uiState.value.config
            val result = withContext(Dispatchers.Default) {
                WorkoutGenerator(
                    exercises = exercises,
                    routines = routines,
                    machines = settings.machines,
                ).generate(
                    config,
                    WorkoutGenerator.Profile(
                        constraints = settings.constraints,
                        includeLowEvidence = settings.includeLowEvidence,
                        visceralFatGoal = settings.visceralFatGoal,
                        metrics = metrics,
                    ),
                )
            }
            settingsRepository.setLastConfigJson(json.encodeToString(GeneratorConfig.serializer(), config))
            // A newer generate() superseded this one (rapid Regenerate taps) — drop the stale result.
            if (seq != generateSeq) return@launch
            uiState.value = uiState.value.copy(
                preview = result.session, warnings = result.warnings, generating = false,
                configChanged = false, edited = false,
            )
        }
    }

    fun applyFix(warning: GeneratorWarning) {
        warning.fixedConfig?.let { fixed ->
            uiState.value = uiState.value.copy(config = fixed)
            generate()
        }
    }

    fun removeStep(index: Int) = editSteps { steps -> steps.filterIndexed { i, _ -> i != index } }

    fun moveStep(index: Int, delta: Int) = editSteps { steps ->
        val target = index + delta
        if (target < 0 || target >= steps.size) steps
        else steps.toMutableList().apply { add(target, removeAt(index)) }
    }

    fun swapStep(index: Int) {
        if (uiState.value.generating) return
        viewModelScope.launch {
            val session = uiState.value.preview ?: return@launch
            val step = session.plan.steps.getOrNull(index) ?: return@launch
            if (step.type != StepType.WORK) return@launch
            val settings = settingsRepository.current()
            val usedIds = session.plan.steps.mapNotNull { it.exerciseId }.toSet()
            val discreteCategories = setOf(Category.FLOOR, Category.REFORMER, Category.BACK)
            val candidates = exerciseRepository.byCategory(step.category)
                .filter { it.kind.name == (if (step.category in discreteCategories) "DISCRETE" else "INTERVAL_SEGMENT") }
                .filter { !it.isWarmupCooldown && it.id !in usedIds }
                .filter { settings.includeLowEvidence || it.evidenceTier != au.mark.kinetiq.data.model.EvidenceTier.LIMITED }
                .filter { it.contraindications.none { c -> c in settings.constraints } }
            val replacement = candidates.randomOrNull() ?: return@launch
            editSteps { steps ->
                steps.mapIndexed { i, s ->
                    if (i == index) s.copy(
                        exerciseId = replacement.id,
                        exerciseName = replacement.name,
                        machineCueText = MachineCueRenderer.renderCue(replacement, settings.machines),
                        met = replacement.met,
                        animationId = replacement.animationId,
                        durationSec = s.durationSec.coerceIn(replacement.minSec, replacement.maxSec),
                    ) else s
                }
            }
        }
    }

    private fun editSteps(transform: (List<SessionStep>) -> List<SessionStep>) {
        // Generation now runs off the main thread, so a preview edit made while one is in flight
        // would be silently overwritten by its write-back. Ignoring the edit is honest; losing it
        // after the user saw it applied is not.
        if (uiState.value.generating) return
        val session = uiState.value.preview ?: return
        val newSteps = transform(session.plan.steps)
        uiState.value = uiState.value.copy(
            preview = session.copy(plan = session.plan.copy(steps = newSteps, totalSec = newSteps.sumOf { it.durationSec })),
            edited = true,
        )
    }

    fun start(context: android.content.Context) {
        val session = uiState.value.preview ?: return
        val name = "${session.config.totalDurationMin} min " +
            session.config.categories.joinToString("+") { it.displayName() }
        WorkoutSessionService.start(context, json.encodeToString(GeneratedSession.serializer(), session), name)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuilderScreen(onStarted: () -> Unit, onBack: () -> Unit, viewModel: BuilderViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val config = state.config
    val context = LocalContext.current
    var confirmRegenerate by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    if (state.showContinuousNotice) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissContinuousNotice,
            title = { Text("Continuous mode") },
            text = {
                Text(
                    "Continuous mode removes rests entirely. The next exercise is announced during " +
                        "the last 5 seconds of the current one. A short 10-second pause is still inserted " +
                        "when you need to change equipment or springs. Skip any step from the player if " +
                        "you need a breather."
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = viewModel::dismissContinuousNotice) { Text("Got it") }
            },
        )
    }
    if (confirmRegenerate) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRegenerate = false },
            title = { Text("Regenerate preview?") },
            text = { Text("Regenerating discards your manual edits to the preview.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmRegenerate = false
                    viewModel.generate()
                }) { Text("Regenerate") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRegenerate = false }) { Text("Keep edits") }
            },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Workout builder", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            SectionHeader("Duration: ${config.totalDurationMin} min")
            Slider(
                value = config.totalDurationMin.toFloat(),
                onValueChange = { v -> viewModel.updateConfig { it.copy(totalDurationMin = v.toInt()) } },
                valueRange = 5f..60f, steps = 10,
            )
        }
        item {
            SectionHeader("Categories (in workout order)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Category.entries.forEach { cat ->
                    val selected = cat in config.categories
                    val order = config.categories.indexOf(cat) + 1
                    FilterChip(
                        selected = selected,
                        onClick = {
                            viewModel.updateConfig {
                                it.copy(categories = if (selected) it.categories - cat else it.categories + cat)
                            }
                        },
                        label = { Text(if (selected) "$order. ${cat.displayName()}" else cat.displayName()) },
                    )
                }
            }
        }
        item {
            SectionHeader("Exercises per category: ${config.exercisesPerCategory?.toString() ?: "auto"}")
            Slider(
                value = (config.exercisesPerCategory ?: 0).toFloat(),
                onValueChange = { v ->
                    viewModel.updateConfig { it.copy(exercisesPerCategory = v.toInt().takeIf { n -> n > 0 }) }
                },
                valueRange = 0f..15f, steps = 14,
            )
        }
        item {
            SectionHeader("Rest between exercises")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                au.mark.kinetiq.data.model.RestMode.entries.forEach { mode ->
                    FilterChip(
                        selected = config.restMode == mode,
                        onClick = { viewModel.selectRestMode(mode) },
                        label = {
                            Text(
                                when (mode) {
                                    au.mark.kinetiq.data.model.RestMode.STANDARD -> "Standard"
                                    au.mark.kinetiq.data.model.RestMode.RECOVERY -> "Recovery"
                                    au.mark.kinetiq.data.model.RestMode.CONTINUOUS -> "Continuous"
                                }
                            )
                        },
                    )
                }
            }
            Text(
                when (config.restMode) {
                    au.mark.kinetiq.data.model.RestMode.STANDARD -> "15–20 s transitions between exercises"
                    au.mark.kinetiq.data.model.RestMode.RECOVERY -> "30–45 s rests, scaled to intensity"
                    au.mark.kinetiq.data.model.RestMode.CONTINUOUS -> "No rests — the next exercise is announced over the last 5 s"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SectionHeader("Intensity")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Intensity.entries.forEach { level ->
                    FilterChip(
                        selected = config.intensity == level,
                        onClick = { viewModel.updateConfig { it.copy(intensity = level) } },
                        label = { Text(level.name.lowercase().replace('_', ' ')) },
                    )
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = config.warmup, onClick = { viewModel.updateConfig { it.copy(warmup = !it.warmup) } },
                    label = { Text("Warm-up") })
                FilterChip(selected = config.cooldown, onClick = { viewModel.updateConfig { it.copy(cooldown = !it.cooldown) } },
                    label = { Text("Cool-down") })
                FilterChip(selected = config.useHealthData, onClick = { viewModel.updateConfig { it.copy(useHealthData = !it.useHealthData) } },
                    label = { Text("Use my health data") })
            }
        }
        item {
            Button(
                onClick = { if (state.edited) confirmRegenerate = true else viewModel.generate() },
                enabled = config.categories.isNotEmpty() && !state.generating,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.preview == null) "Generate session" else "Regenerate") }
        }
        if (state.configChanged && state.preview != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Settings changed — this preview no longer matches.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        OutlinedButton(onClick = {
                            if (state.edited) confirmRegenerate = true else viewModel.generate()
                        }) { Text("Regenerate") }
                    }
                }
            }
        }
        items(state.warnings.size) { i ->
            val warning = state.warnings[i]
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Column(Modifier.padding(12.dp)) {
                    Text(warning.message, style = MaterialTheme.typography.bodyMedium)
                    if (warning.fixLabel != null) {
                        OutlinedButton(onClick = { viewModel.applyFix(warning) }) { Text(warning.fixLabel) }
                    }
                }
            }
        }
        state.preview?.let { session ->
            item {
                SectionHeader("Preview — ${session.plan.totalSec / 60} min, ${session.plan.steps.count { it.type == StepType.WORK }} exercises")
            }
            itemsIndexed(session.plan.steps) { index, step ->
                PreviewStepRow(
                    step = step,
                    onRemove = { viewModel.removeStep(index) },
                    onUp = { viewModel.moveStep(index, -1) },
                    onDown = { viewModel.moveStep(index, +1) },
                    onSwap = { viewModel.swapStep(index) },
                )
            }
            item {
                Button(
                    onClick = { viewModel.start(context); onStarted() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                ) { Text("Start workout") }
            }
        }
    }
}

@Composable
private fun PreviewStepRow(
    step: SessionStep,
    onRemove: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onSwap: () -> Unit,
) {
    val muted = step.type == StepType.REST || step.type == StepType.TRANSITION
    Card(
        colors = if (muted) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${stepLabel(step.type)}${step.exerciseName}",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    "${formatSec(step.durationSec)}${step.machineCueText?.let { " · $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (step.type == StepType.WORK) {
                IconButton(onClick = onSwap) { Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap exercise") }
                IconButton(onClick = onUp) { Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up") }
                IconButton(onClick = onDown) { Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down") }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Remove") }
            }
        }
    }
}

private fun stepLabel(type: StepType): String = when (type) {
    StepType.WARMUP -> "Warm-up · "
    StepType.COOLDOWN -> "Cool-down · "
    StepType.REST -> ""
    StepType.TRANSITION -> ""
    StepType.WORK -> ""
}
