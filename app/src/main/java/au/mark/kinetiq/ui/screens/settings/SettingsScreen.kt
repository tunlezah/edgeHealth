package au.mark.kinetiq.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import au.mark.kinetiq.ui.theme.KinetiqPalettes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.R
import au.mark.kinetiq.data.export.ExportImportCodec
import au.mark.kinetiq.data.export.ExportImportManager
import au.mark.kinetiq.data.model.BodyArea
import au.mark.kinetiq.data.repo.AppSettings
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.data.repo.SpringNotation
import au.mark.kinetiq.data.repo.ThemeMode
import au.mark.kinetiq.reminders.ReminderScheduler
import au.mark.kinetiq.ui.components.SectionHeader
import au.mark.kinetiq.ui.components.SettingSwitchRow
import au.mark.kinetiq.voice.VoiceCoach
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The only implicit intent in the app; pinned to the system Settings package where possible. */
private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val exportImport: ExportImportManager,
    private val reminderScheduler: ReminderScheduler,
    private val voiceCoach: VoiceCoach,
    private val exerciseRepository: au.mark.kinetiq.data.repo.ExerciseRepository,
) : ViewModel() {

    val settings = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val ioMessage = MutableStateFlow<String?>(null)

    /** Progression ladders from the bundled exercises, for the "Resistance levels" section. */
    val ladders = MutableStateFlow<List<au.mark.kinetiq.domain.resistance.Ladders.Ladder>>(emptyList())

    init {
        viewModelScope.launch {
            ladders.value = runCatching {
                au.mark.kinetiq.domain.resistance.Ladders.from(exerciseRepository.exercises())
            }.getOrDefault(emptyList())
        }
    }

    fun set(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }

    fun updateReminder(context: android.content.Context, days: Set<Int>, hour: Int, minute: Int) {
        viewModelScope.launch {
            repo.setReminder(days, hour, minute)
            reminderScheduler.schedule(context, days, hour, minute)
        }
    }

    val voiceStatus = voiceCoach.status
    val usingNetworkVoice = voiceCoach.usingNetworkVoice

    fun testVoice() {
        viewModelScope.launch {
            voiceCoach.settings = settings.value.voice
            if (voiceCoach.status.value == au.mark.kinetiq.voice.TtsStatus.FAILED) voiceCoach.retryInit()
            voiceCoach.warmUp { voiceCoach.speak("G'day! This is your Kinetiq coach. Standing climb — resistance 8, around 65 r p m.") }
            kotlinx.coroutines.delay(3_000)
            ioMessage.value = if (voiceCoach.status.value == au.mark.kinetiq.voice.TtsStatus.FAILED) {
                "Voice engine failed to start. Cues will be silent — open System TTS settings below."
            } else {
                "Voice test played. If you heard nothing, check media volume and the offline voice data."
            }
        }
    }

    fun export(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                val payload = ExportImportCodec.encode(exportImport.buildExport())
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload.toByteArray()) }
                    ?: error("Could not open the selected file for writing")
            }.onSuccess { ioMessage.value = "Export complete." }
                .onFailure { ioMessage.value = "Export failed: ${it.message}" }
        }
    }

    fun import(context: android.content.Context, uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?: error("Could not read the selected file")
                when (val result = ExportImportCodec.decodeAndValidate(raw)) {
                    is ExportImportCodec.ImportResult.Failure ->
                        ioMessage.value = "Import rejected:\n" + result.problems.joinToString("\n• ", prefix = "• ")
                    is ExportImportCodec.ImportResult.Success -> {
                        val (w, h) = exportImport.applyImport(result.file)
                        ioMessage.value = buildString {
                            append("Imported $w workout(s) and $h history entr${if (h == 1) "y" else "ies"}.")
                            result.warnings.forEach { append("\n").append(it) }
                        }
                    }
                }
            }.onFailure { ioMessage.value = "Import failed: ${it.message}" }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    onOpenHealth: () -> Unit,
    onOpenDebugAnim: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val ioMessage by viewModel.ioMessage.collectAsState()
    val context = LocalContext.current
    var showDisclaimer by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let { viewModel.export(context, it) } }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.import(context, it) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))

        SectionHeader("Voice & sound")
        val voice = settings.voice
        SettingSwitchRow("Countdown beeps", voice.countdownBeeps) { v -> viewModel.set { setVoice(voice.copy(countdownBeeps = v)) } }
        SettingSwitchRow("Exercise name announcements", voice.nameAnnouncement) { v -> viewModel.set { setVoice(voice.copy(nameAnnouncement = v)) } }
        SettingSwitchRow("Spoken how-to descriptions", voice.howToDescription, subtitle = "Spoken during the rest before each exercise") { v -> viewModel.set { setVoice(voice.copy(howToDescription = v)) } }
        SettingSwitchRow("Halfway cue", voice.halfwayCue) { v -> viewModel.set { setVoice(voice.copy(halfwayCue = v)) } }
        SettingSwitchRow("Rest / next-up cues", voice.restNextUpCue) { v -> viewModel.set { setVoice(voice.copy(restNextUpCue = v)) } }
        SettingSwitchRow("Machine setting cues", voice.machineSettingCues, subtitle = "Spring, resistance, cadence and position calls") { v -> viewModel.set { setVoice(voice.copy(machineSettingCues = v)) } }
        Text("Voice volume: ${(voice.volume * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = voice.volume,
            onValueChange = { v -> viewModel.set { setVoice(voice.copy(volume = v)) } },
            modifier = Modifier.semantics { contentDescription = "Voice volume" },
        )
        Text("Speech rate: ${"%.1f".format(voice.speechRate)}×", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = voice.speechRate,
            valueRange = 0.5f..1.8f,
            onValueChange = { v -> viewModel.set { setVoice(voice.copy(speechRate = v)) } },
            modifier = Modifier.semantics { contentDescription = "Speech rate" },
        )
        OutlinedButton(onClick = viewModel::testVoice) { Text("Test voice") }
        val voiceStatus by viewModel.voiceStatus.collectAsState()
        if (voiceStatus == au.mark.kinetiq.voice.TtsStatus.FAILED) {
            Text(
                "Voice engine unavailable — cues will be silent. Try the system TTS settings below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        val usingNetworkVoice by viewModel.usingNetworkVoice.collectAsState()
        if (voiceStatus != au.mark.kinetiq.voice.TtsStatus.FAILED && usingNetworkVoice) {
            Text(
                "Your speech engine picked a voice that needs a network connection. Kinetiq never " +
                    "goes online, but the engine might — install the offline English (Australia) " +
                    "voice below to keep coaching fully offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = {
            // Pin to the system Settings package so an installed app declaring the same action
            // cannot intercept the tap — but fall back to the implicit form, because on a device
            // whose Settings app is not com.android.settings a bare setPackage would throw, be
            // swallowed by runCatching, and silently leave this button doing nothing.
            fun open(intent: Intent) = context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runCatching { open(Intent(TTS_SETTINGS_ACTION).setPackage("com.android.settings")) }
                .recoverCatching { open(Intent(TTS_SETTINGS_ACTION)) }
        }) { Text("System TTS settings (download offline en-AU voice data)") }

        SectionHeader("Theme")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.theme == mode,
                    onClick = { viewModel.set { setTheme(mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }.replace("Amoled", "AMOLED black")) },
                )
            }
        }
        Text("Accent", style = MaterialTheme.typography.bodyMedium)
        val darkSwatches = isSystemInDarkTheme()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KinetiqPalettes.all.forEach { (palette, schemes) ->
                val swatch = if (darkSwatches) schemes.dark.primary else schemes.light.primary
                val selected = settings.palette == palette
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .then(
                            if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                            else Modifier
                        )
                        .clickable(onClickLabel = schemes.displayName) { viewModel.set { setPalette(palette) } }
                        .semantics {
                            contentDescription = "${schemes.displayName} theme" + if (selected) ", selected" else ""
                        },
                )
            }
        }
        Text(
            KinetiqPalettes.schemes(settings.palette).displayName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("Health Connect")
        OutlinedButton(onClick = onOpenHealth, modifier = Modifier.fillMaxWidth()) {
            Text("Manage Health Connect & body measurements")
        }
        SettingSwitchRow("Write completed sessions to Health Connect", settings.healthConnectWriteback) { v -> viewModel.set { setHealthConnectWriteback(v) } }

        SectionHeader("Body constraints")
        Text("Exercises loading these areas are excluded from generated workouts.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BodyArea.entries.forEach { area ->
                val selected = area in settings.constraints
                FilterChip(
                    selected = selected,
                    onClick = {
                        viewModel.set {
                            setConstraints(if (selected) settings.constraints - area else settings.constraints + area)
                        }
                    },
                    label = { Text(area.name.lowercase().replace('_', ' ')) },
                )
            }
        }

        SectionHeader("Evidence")
        SettingSwitchRow(
            "Include low-evidence exercises",
            settings.includeLowEvidence,
            subtitle = "LIMITED-tier moves join auto-generation and lose their grey-out",
        ) { v -> viewModel.set { setIncludeLowEvidence(v) } }

        SectionHeader("Machines")
        var spinMax by remember(settings.machines.spinMaxLevel) { mutableStateOf(settings.machines.spinMaxLevel.toString()) }
        OutlinedTextField(
            value = spinMax,
            onValueChange = {
                spinMax = it.filter(Char::isDigit).take(2)
                spinMax.toIntOrNull()?.let { n -> if (n in 4..40) viewModel.set { setMachines(settings.machines.copy(spinMaxLevel = n)) } }
            },
            label = { Text("GR7 spin bike — max resistance level (default 11)") },
            isError = spinMax.toIntOrNull()?.let { it in 4..40 } != true,
            supportingText = { Text("4–40") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        var ellMax by remember(settings.machines.ellipticalMaxLevel) { mutableStateOf(settings.machines.ellipticalMaxLevel.toString()) }
        OutlinedTextField(
            value = ellMax,
            onValueChange = {
                ellMax = it.filter(Char::isDigit).take(2)
                ellMax.toIntOrNull()?.let { n -> if (n in 4..40) viewModel.set { setMachines(settings.machines.copy(ellipticalMaxLevel = n)) } }
            },
            label = { Text("VG50BS elliptical — max level (check console; BT variant lists 32)") },
            isError = ellMax.toIntOrNull()?.let { it in 4..40 } != true,
            supportingText = { Text("4–40") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Reformer spring notation", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.machines.springNotation == SpringNotation.GENERIC,
                onClick = { viewModel.set { setMachines(settings.machines.copy(springNotation = SpringNotation.GENERIC)) } },
                label = { Text("Light / medium / heavy") },
            )
            FilterChip(
                selected = settings.machines.springNotation == SpringNotation.COUNT,
                onClick = { viewModel.set { setMachines(settings.machines.copy(springNotation = SpringNotation.COUNT)) } },
                label = { Text("Spring count") },
            )
        }

        SectionHeader("Equipment")
        Text(
            "What you have beyond the machines. Muscle-retention sessions only ever use what is switched on " +
                "here; hand weights are also chosen per session in the Builder.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val equipment = settings.equipment
        var handWeightMax by remember(equipment.handWeightMaxKg) {
            mutableStateOf(equipment.handWeightMaxKg.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() })
        }
        OutlinedTextField(
            value = handWeightMax,
            onValueChange = { raw ->
                handWeightMax = raw.filter { it.isDigit() || it == '.' }.take(4)
                handWeightMax.toFloatOrNull()?.let { kg ->
                    if (kg in 0.5f..40f) viewModel.set { setEquipment(equipment.copy(handWeightMaxKg = kg)) }
                }
            },
            label = { Text("Heaviest single hand weight (kg)") },
            isError = handWeightMax.toFloatOrNull()?.let { it in 0.5f..40f } != true,
            supportingText = {
                Text("0.5–40. Exercises that need a heavier weight to be worth doing are left out, not watered down.")
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        SettingSwitchRow(
            "Sturdy chair or step", equipment.chairOrStep,
            subtitle = "Unlocks elevated and step-based exercises as they are added",
        ) { v -> viewModel.set { setEquipment(equipment.copy(chairOrStep = v)) } }
        SettingSwitchRow(
            "Reformer long box", equipment.reformerBox,
            subtitle = "Unlocks prone pulling work on the reformer as it is added",
        ) { v -> viewModel.set { setEquipment(equipment.copy(reformerBox = v)) } }
        SettingSwitchRow(
            "Ankle anchor", equipment.ankleAnchor,
            subtitle = "Something immovable to hook your heels under, for Nordic hamstring curls",
        ) { v -> viewModel.set { setEquipment(equipment.copy(ankleAnchor = v)) } }

        val ladders by viewModel.ladders.collectAsState()
        if (ladders.isNotEmpty()) {
            SectionHeader("Resistance levels")
            Text(
                "Each ladder runs from an easier version of a movement to a harder one. Muscle-retention " +
                    "sessions use the level shown. Move down if a version is beyond you, up when it stops " +
                    "getting hard — the ratings you give after your sets will suggest moves too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ladders.forEach { ladder ->
                val stored = settings.ladderRungs[ladder.id]
                val current = ladder.current(stored)
                val easier = ladder.step(stored, -1)
                val harder = ladder.step(stored, +1)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(current.names.joinToString(" / "), style = MaterialTheme.typography.titleMedium)
                        Text(
                            ladder.rungs.joinToString("  →  ") { it.names.joinToString(" / ") },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { easier?.let { r -> viewModel.set { setLadderRung(ladder.id, r.rung) } } },
                                enabled = easier != null,
                            ) { Text("Easier") }
                            OutlinedButton(
                                onClick = { harder?.let { r -> viewModel.set { setLadderRung(ladder.id, r.rung) } } },
                                enabled = harder != null,
                            ) { Text("Harder") }
                        }
                    }
                }
            }
        }

        SectionHeader("Workout defaults")
        Text("Rest between exercises (new workouts)", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            au.mark.kinetiq.data.model.RestMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.defaultRestMode == mode,
                    onClick = { viewModel.set { setDefaultRestMode(mode) } },
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

        SectionHeader("Workout reminders")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Mon" to 1, "Tue" to 2, "Wed" to 3, "Thu" to 4, "Fri" to 5, "Sat" to 6, "Sun" to 7).forEach { (label, day) ->
                FilterChip(
                    selected = day in settings.reminderDays,
                    onClick = {
                        val days = if (day in settings.reminderDays) settings.reminderDays - day else settings.reminderDays + day
                        viewModel.updateReminder(context, days, settings.reminderHour, settings.reminderMinute)
                    },
                    label = { Text(label) },
                )
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Time: %02d:%02d".format(settings.reminderHour, settings.reminderMinute))
            OutlinedButton(onClick = {
                android.app.TimePickerDialog(
                    context,
                    { _, h, m -> viewModel.updateReminder(context, settings.reminderDays, h, m) },
                    settings.reminderHour, settings.reminderMinute, true,
                ).show()
            }) { Text("Change") }
        }

        SectionHeader("Streak rest days")
        Text("These days never break your streak.", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Mon" to 1, "Tue" to 2, "Wed" to 3, "Thu" to 4, "Fri" to 5, "Sat" to 6, "Sun" to 7).forEach { (label, day) ->
                FilterChip(
                    selected = day in settings.restDays,
                    onClick = {
                        viewModel.set {
                            setRestDays(if (day in settings.restDays) settings.restDays - day else settings.restDays + day)
                        }
                    },
                    label = { Text(label) },
                )
            }
        }

        SectionHeader("Goals")
        // The app is metric-only; the old do-nothing "Metric units" switch is gone
        // (the stored key is retained for settings back-compat).
        SettingSwitchRow("Visceral-fat goal", settings.visceralFatGoal, subtitle = "Biases generation toward moderate+ cardio time") { v -> viewModel.set { setVisceralGoal(v) } }
        var fallbackWeight by remember(settings.fallbackWeightKg) { mutableStateOf(settings.fallbackWeightKg.toInt().toString()) }
        OutlinedTextField(
            value = fallbackWeight,
            onValueChange = {
                fallbackWeight = it.filter(Char::isDigit).take(3)
                fallbackWeight.toIntOrNull()?.let { n -> if (n in 30..250) viewModel.set { setFallbackWeight(n.toFloat()) } }
            },
            label = { Text("Fallback weight for calories (kg)") },
            isError = fallbackWeight.toIntOrNull()?.let { it in 30..250 } != true,
            supportingText = { Text("30–250 kg") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionHeader("Disclaimer")
        SettingSwitchRow("Spoken disclaimer line before each workout", settings.disclaimerLineInWorkout) { v -> viewModel.set { setDisclaimerLine(v) } }
        OutlinedButton(onClick = { showDisclaimer = true }) { Text("View disclaimer") }

        SectionHeader("Data")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { exportLauncher.launch("kinetiq-export.json") }, modifier = Modifier.weight(1f)) { Text("Export JSON") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, modifier = Modifier.weight(1f)) { Text("Import JSON") }
        }
        ioMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        // Hidden QA entry: long-press the version row to open the animation debug screen.
        Text(
            "Kinetiq 1.1.0 — offline build (long-press for animation QA)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(vertical = 24.dp)
                .combinedClickable(onClick = {}, onLongClick = onOpenDebugAnim),
        )
    }

    if (showDisclaimer) {
        AlertDialog(
            onDismissRequest = { showDisclaimer = false },
            title = { Text(stringResource(R.string.disclaimer_title)) },
            text = { Text(stringResource(R.string.disclaimer_body)) },
            confirmButton = { TextButton(onClick = { showDisclaimer = false }) { Text("Close") } },
        )
    }
}
