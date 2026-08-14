package au.mark.kinetiq

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.mark.kinetiq.data.repo.AppSettings
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.data.repo.WorkoutRepository
import au.mark.kinetiq.service.SessionStateHolder
import au.mark.kinetiq.service.WorkoutSessionService
import au.mark.kinetiq.ui.nav.KinetiqApp
import au.mark.kinetiq.ui.theme.KinetiqTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    private val workoutRepository: WorkoutRepository,
    val sessionStateHolder: SessionStateHolder,
    private val json: Json,
) : ViewModel() {

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Set when the widget asks to repeat the last workout; consumed by the nav host. */
    val pendingPlayerLaunch = MutableStateFlow(false)

    fun repeatLastWorkout(context: android.content.Context) {
        // A live session must never be clobbered by a widget tap — just open the player.
        if (sessionStateHolder.state.value != null) {
            pendingPlayerLaunch.value = true
            return
        }
        viewModelScope.launch {
            val last = workoutRepository.lastSession() ?: return@launch
            val session = last.session ?: return@launch
            WorkoutSessionService.start(
                context,
                json.encodeToString(au.mark.kinetiq.data.model.GeneratedSession.serializer(), session),
                last.name.ifBlank { "Workout" },
            )
            pendingPlayerLaunch.value = true
        }
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLaunchIntent(intent)
        setContent {
            val settings by viewModel.settings.collectAsState()
            KinetiqTheme(mode = settings.theme, palette = settings.palette) {
                KinetiqApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent?.action == ACTION_REPEAT_LAST) {
            viewModel.repeatLastWorkout(this)
        }
    }

    companion object {
        const val ACTION_REPEAT_LAST = "au.mark.kinetiq.REPEAT_LAST"
    }
}
