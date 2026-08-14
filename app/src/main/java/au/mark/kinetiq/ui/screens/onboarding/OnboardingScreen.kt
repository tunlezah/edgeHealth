package au.mark.kinetiq.ui.screens.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.R
import au.mark.kinetiq.data.model.BodyArea
import au.mark.kinetiq.data.repo.MachineSettings
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.data.repo.SpringNotation
import au.mark.kinetiq.health.HealthConnectManager
import au.mark.kinetiq.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    val healthConnect: HealthConnectManager,
) : ViewModel() {

    val settings = settingsRepository.settings

    fun complete(
        constraints: Set<BodyArea>,
        spinMax: Int,
        ellipticalMax: Int,
        notation: SpringNotation,
        hcEnabled: Boolean,
    ) {
        viewModelScope.launch {
            settingsRepository.setDisclaimerAcknowledged(true)
            settingsRepository.setConstraints(constraints)
            settingsRepository.setMachines(MachineSettings(spinMax, ellipticalMax, notation))
            settingsRepository.setHealthConnectEnabled(hcEnabled)
            settingsRepository.setOnboardingComplete(true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(onDone: () -> Unit, viewModel: OnboardingViewModel = hiltViewModel()) {
    var step by rememberSaveable { mutableStateOf(0) }
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var constraints by rememberSaveable { mutableStateOf(setOf<String>()) }
    var spinMax by rememberSaveable { mutableStateOf("11") }
    var ellipticalMax by rememberSaveable { mutableStateOf("16") }
    var notationGeneric by rememberSaveable { mutableStateOf(true) }
    var hcEnabled by rememberSaveable { mutableStateOf(false) }

    val hcPermissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> hcEnabled = granted.isNotEmpty() }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Step ${step + 1} of 4",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.LinearProgressIndicator(
            progress = { (step + 1) / 4f },
            modifier = Modifier.fillMaxWidth(),
        )
        when (step) {
            0 -> {
                Text("Welcome to Kinetiq", style = MaterialTheme.typography.headlineMedium)
                SectionHeader(stringResource(R.string.disclaimer_title))
                Text(stringResource(R.string.disclaimer_body), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
                    Text("I understand — this is fitness guidance, not medical advice.")
                }
                Button(
                    onClick = {
                        step = 1
                        notifPermissionLauncher.launch(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACTIVITY_RECOGNITION)
                        )
                    },
                    enabled = acknowledged,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Continue") }
            }
            1 -> {
                Text("Your body", style = MaterialTheme.typography.headlineMedium)
                Text("Select any areas with injuries or sensitivities. Exercises that load them are excluded from every generated workout.")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BodyArea.entries.forEach { area ->
                        val label = area.name.lowercase().replace('_', ' ')
                        FilterChip(
                            selected = area.name in constraints,
                            onClick = {
                                constraints = if (area.name in constraints) constraints - area.name else constraints + area.name
                            },
                            label = { Text(label.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Button(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                OutlinedButton(onClick = { step = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
            2 -> {
                Text("Your machines", style = MaterialTheme.typography.headlineMedium)
                SectionHeader("Spin bike — Horizon GR7")
                Text("The GR7 has 11 magnetic resistance levels. Voice cues reference these numbers.")
                OutlinedTextField(
                    value = spinMax, onValueChange = { spinMax = it.filter(Char::isDigit).take(2) },
                    label = { Text("Max resistance level") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionHeader("Elliptical — Infiniti VG50BS")
                Text("Set your console's top resistance level (dealer specs suggest 32 for the BT variant; 16 is a safe default — check the console).")
                OutlinedTextField(
                    value = ellipticalMax, onValueChange = { ellipticalMax = it.filter(Char::isDigit).take(2) },
                    label = { Text("Max resistance level") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                SectionHeader("Reformer springs")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = notationGeneric, onClick = { notationGeneric = true },
                        label = { Text("Light / medium / heavy") })
                    FilterChip(selected = !notationGeneric, onClick = { notationGeneric = false },
                        label = { Text("Spring count") })
                }
                Button(onClick = { step = 3 }, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
                OutlinedButton(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
            3 -> {
                Text("Health Connect (optional)", style = MaterialTheme.typography.headlineMedium)
                Text("Kinetiq can read your weight, height and body fat % to personalise workouts, and write completed sessions back. Everything works without it — you can also enter measurements manually.")
                val available = viewModel.healthConnect.isAvailable()
                if (available) {
                    Button(
                        onClick = { hcPermissionLauncher.launch(viewModel.healthConnect.allPermissions) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (hcEnabled) "Connected ✓" else "Connect Health Connect") }
                } else {
                    Text("Health Connect isn't available on this device.", color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = {
                        viewModel.complete(
                            constraints.map { BodyArea.valueOf(it) }.toSet(),
                            spinMax.toIntOrNull()?.coerceIn(4, 40) ?: 11,
                            ellipticalMax.toIntOrNull()?.coerceIn(4, 40) ?: 16,
                            if (notationGeneric) SpringNotation.GENERIC else SpringNotation.COUNT,
                            hcEnabled,
                        )
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (hcEnabled) "Finish" else "Finish without Health Connect") }
                OutlinedButton(onClick = { step = 2 }, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}
