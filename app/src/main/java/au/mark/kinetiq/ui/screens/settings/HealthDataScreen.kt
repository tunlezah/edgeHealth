package au.mark.kinetiq.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.data.db.CachedHealthMetricEntity
import au.mark.kinetiq.data.repo.MeasurementRepository
import au.mark.kinetiq.data.repo.Metric
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.health.HealthConnectManager
import au.mark.kinetiq.ui.components.SectionHeader
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class HealthDataViewModel @Inject constructor(
    val healthConnect: HealthConnectManager,
    private val measurements: MeasurementRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val cached = measurements.cachedAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val status = MutableStateFlow<String?>(null)
    val body = MutableStateFlow(au.mark.kinetiq.data.repo.BodyMetrics())

    init { reload() }

    fun reload() {
        viewModelScope.launch { body.value = measurements.bodyMetrics() }
    }

    fun refreshFromHealthConnect() {
        viewModelScope.launch {
            status.value = "Reading Health Connect…"
            status.value = healthConnect.refreshBodyMetrics().fold(
                onSuccess = { s ->
                    when {
                        s.imported > 0 ->
                            "Imported ${s.imported} metric${if (s.imported == 1) "" else "s"} from Health Connect."
                        !s.readPermissionGranted -> "Connected, but no read permissions were granted."
                        s.failures.isNotEmpty() -> "Read failed — ${s.failures.joinToString("; ")}"
                        s.attempted.isEmpty() -> "Read access on, but Weight/Body fat/Height are all toggled off."
                        !s.historyPermissionGranted ->
                            "0 records for ${s.emptyReads.joinToString()} in the last 30 days — grant 'access past data' for older entries."
                        else ->
                            "0 records for ${s.emptyReads.joinToString()} (history access on). The source app may not be sharing that type to Health Connect."
                    }
                },
                onFailure = { "Could not read: ${it.javaClass.simpleName} — ${it.message}" },
            )
            reload()
        }
    }

    fun connected(granted: Set<String>) {
        viewModelScope.launch {
            settingsRepository.setHealthConnectEnabled(granted.isNotEmpty())
            if (granted.isNotEmpty()) refreshFromHealthConnect()
            else status.value = "No permissions granted."
        }
    }

    fun addManual(metric: String, value: Double) {
        viewModelScope.launch {
            measurements.addManual(metric, value)
            reload()
            status.value = "Saved."
        }
    }
}

@Composable
fun HealthDataScreen(onBack: () -> Unit, viewModel: HealthDataViewModel = hiltViewModel()) {
    val cached by viewModel.cached.collectAsState()
    val status by viewModel.status.collectAsState()
    val body by viewModel.body.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted -> viewModel.connected(granted) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("Body data", style = MaterialTheme.typography.headlineSmall)
        }

        SectionHeader("Health Connect")
        if (viewModel.healthConnect.isAvailable()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { permissionLauncher.launch(viewModel.healthConnect.allPermissions) }, modifier = Modifier.weight(1f)) {
                    Text("Connect / permissions")
                }
                OutlinedButton(onClick = viewModel::refreshFromHealthConnect, modifier = Modifier.weight(1f)) {
                    Text("Refresh data")
                }
            }
        } else {
            Text("Health Connect is not available on this device.", color = MaterialTheme.colorScheme.error)
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }

        if (cached.isNotEmpty()) {
            SectionHeader("From Health Connect")
            cached.forEach { CachedMetricRow(it) }
        }

        SectionHeader("Current values (freshest wins)")
        val bmi = body.bmi
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Weight: ${body.weightKg?.let { "%.1f kg".format(it) } ?: "—"}")
                Text("Height: ${body.heightCm?.let { "%.0f cm".format(it) } ?: "—"}")
                Text("Body fat: ${body.bodyFatPct?.let { "%.1f %%".format(it) } ?: "—"}")
                Text("BMI (computed): ${bmi?.let { "%.1f".format(it) } ?: "— (needs weight + height)"}")
                Text("Waist: ${body.waistCm?.let { "%.1f cm".format(it) } ?: "—"}")
                Text("Smart-scale visceral rating: ${body.visceralRating?.let { "%.0f".format(it) } ?: "—"}")
            }
        }
        Text(
            "Waist circumference is the preferred visceral-fat proxy. WHO thresholds — men: ≥94 cm elevated, " +
                "≥102 cm substantially increased risk; women: ≥80 cm elevated, ≥88 cm substantially increased.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SectionHeader("Manual entry")
        ManualEntryField("Weight (kg)", 20.0..300.0) { viewModel.addManual(Metric.WEIGHT_KG, it) }
        ManualEntryField("Height (cm)", 100.0..230.0) { viewModel.addManual(Metric.HEIGHT_CM, it) }
        ManualEntryField("Body fat (%)", 3.0..70.0) { viewModel.addManual(Metric.BODY_FAT_PCT, it) }
        ManualEntryField("Waist circumference (cm)", 40.0..200.0) { viewModel.addManual(Metric.WAIST_CM, it) }
        ManualEntryField("Smart-scale visceral rating", 1.0..60.0) { viewModel.addManual(Metric.VISCERAL_RATING, it) }
    }
}

@Composable
private fun CachedMetricRow(entity: CachedHealthMetricEntity) {
    val formatter = remember { DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm") }
    val label = when (entity.metric) {
        Metric.WEIGHT_KG -> "Weight %.1f kg".format(entity.value)
        Metric.HEIGHT_CM -> "Height %.0f cm".format(entity.value)
        Metric.BODY_FAT_PCT -> "Body fat %.1f %%".format(entity.value)
        else -> "${entity.metric}: ${entity.value}"
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "from ${entity.sourceApp} · " +
                    Instant.ofEpochMilli(entity.recordedAtEpochMs).atZone(ZoneId.systemDefault()).format(formatter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ManualEntryField(label: String, range: ClosedFloatingPointRange<Double>, onSave: (Double) -> Unit) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
            label = { Text(label) },
            isError = text.isNotEmpty() && text.toDoubleOrNull()?.let { it in range } != true,
            supportingText = { Text("${range.start.toInt()}–${range.endInclusive.toInt()}") },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
            ),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                text.toDoubleOrNull()?.let { v -> if (v in range) { onSave(v); text = "" } }
            },
            enabled = text.toDoubleOrNull()?.let { it in range } == true,
        ) { Text("Save") }
    }
}
