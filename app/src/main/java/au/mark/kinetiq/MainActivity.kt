package au.mark.kinetiq

import android.app.PendingIntent
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
            val last = workoutRepository.lastSessionForRepeat() ?: return@launch
            val session = last.session ?: return@launch
            // Never hand the service a plan it cannot foreground: startSession() bails on
            // steps.firstOrNull() before it reaches startForeground, which trips the ~5 s FGS
            // watchdog. Import validation is the first line of defence; this is the second.
            if (session.plan.steps.isEmpty()) return@launch
            WorkoutSessionService.start(
                context,
                json.encodeToString(au.mark.kinetiq.data.model.GeneratedSession.serializer(), session),
                last.entry.name.ifBlank { "Workout" },
            )
            pendingPlayerLaunch.value = true
        }
    }
}

/**
 * Gate for the widget's repeat-last launch. MainActivity is the exported LAUNCHER activity, so any
 * app can send it an explicit intent; only our own widget can mint a PendingIntent whose creator
 * package is ours. Also requires a fresh launch: a configuration-change or process-death
 * recreation must not replay the action.
 */
internal fun shouldRepeatLast(
    action: String?,
    tokenCreatorPackage: String?,
    selfPackage: String,
    isRecreation: Boolean,
): Boolean = action == MainActivity.ACTION_REPEAT_LAST &&
    !isRecreation &&
    tokenCreatorPackage != null &&
    tokenCreatorPackage == selfPackage

/**
 * Navigation 2.8.5's NavController.handleDeepLink() reads these extras from the hosting activity's
 * intent with no origin check, and destination ids are just createRoute(route).hashCode() — which
 * anyone can compute offline. androidx-main added a shouldTrustIntent() guard but no released
 * version through 2.9.7 has it, so upgrading Navigation does not close this. Kinetiq declares zero
 * deep links, so these extras are never legitimate here.
 */
internal val NAV_DEEP_LINK_EXTRAS = listOf(
    "android-support-nav:controller:deepLinkIds",
    "android-support-nav:controller:deepLinkArgs",
    "android-support-nav:controller:deepLinkExtras",
    "android-support-nav:controller:deepLinkHandled",
    "android-support-nav:controller:deepLinkIntent",
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Must run before setContent: NavController.onGraphCreated() feeds getIntent() through
        // handleDeepLink() during composition.
        stripNavigationExtras(intent)
        // A non-null savedInstanceState means this is a recreation — scheduled dark mode, a
        // font-scale/locale/density change, or a restore after process death — not a fresh launch.
        handleLaunchIntent(intent, isRecreation = savedInstanceState != null)
        setContent {
            val settings by viewModel.settings.collectAsState()
            KinetiqTheme(mode = settings.theme, palette = settings.palette) {
                KinetiqApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask means getIntent() otherwise keeps returning whatever created the task, so a
        // later recreation would replay a months-old widget tap.
        setIntent(intent)
        stripNavigationExtras(intent)
        handleLaunchIntent(intent, isRecreation = false)
    }

    private fun stripNavigationExtras(intent: Intent?) {
        intent ?: return
        // Extras that cannot even be unparcelled are certainly not ours — drop the lot.
        if (runCatching { NAV_DEEP_LINK_EXTRAS.forEach(intent::removeExtra) }.isFailure) {
            intent.replaceExtras(null as Bundle?)
        }
    }

    private fun handleLaunchIntent(intent: Intent?, isRecreation: Boolean) {
        intent ?: return
        val creator = runCatching {
            intent.getParcelableExtra(EXTRA_ORIGIN_TOKEN, PendingIntent::class.java)?.creatorPackage
        }.getOrNull()
        if (!shouldRepeatLast(intent.action, creator, packageName, isRecreation)) return
        // Consume it: getIntent() is sticky for the life of the task, so without this the action
        // fires again on every subsequent recreation.
        intent.action = null
        viewModel.repeatLastWorkout(this)
    }

    companion object {
        const val ACTION_REPEAT_LAST = "au.mark.kinetiq.REPEAT_LAST"

        /**
         * Proof-of-origin for [ACTION_REPEAT_LAST]. PendingIntent.getCreatorPackage() is supplied
         * by the system so an app cannot spoof its package, and only this app can mint one
         * attributed to this app. Unlike getLaunchedFromUid(), it travels with the intent, so it
         * is equally valid on the singleTask onNewIntent path as on a cold start — which matters,
         * because a warm widget tap arrives on a record the Launcher created.
         */
        internal const val EXTRA_ORIGIN_TOKEN = "au.mark.kinetiq.extra.ORIGIN_TOKEN"

        /** No receiver is registered for this; the token is an identity stamp, never fired. */
        private const val ACTION_ORIGIN_TOKEN = "au.mark.kinetiq.INTERNAL_ORIGIN_TOKEN"

        /** The one-tap "repeat last workout" launch intent. Only this app can build a valid one. */
        fun repeatLastIntent(context: android.content.Context): Intent =
            Intent(context, MainActivity::class.java).apply {
                action = ACTION_REPEAT_LAST
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(
                    EXTRA_ORIGIN_TOKEN,
                    PendingIntent.getBroadcast(
                        context, 0,
                        Intent(ACTION_ORIGIN_TOKEN).setPackage(context.packageName),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
    }
}
