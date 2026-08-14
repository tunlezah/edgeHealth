package au.mark.kinetiq.service

import au.mark.kinetiq.data.model.GeneratedSession
import au.mark.kinetiq.data.repo.CompletedBlock
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Live state of the running workout, observed by the player UI and the widget. */
data class PlayerState(
    val session: GeneratedSession,
    val sessionName: String,
    /** Unique per started session; ties a CompletedSummary to the run that produced it. */
    val sessionId: String = "",
    val stepIndex: Int = 0,
    val stepRemainingMs: Long = 0,
    /** > 0 == GET-READY countdown before the current step's clock starts. */
    val prepareRemainingMs: Long = 0,
    val totalElapsedActiveMs: Long = 0,
    val paused: Boolean = false,
    val finished: Boolean = false,
    val caloriesSoFar: Double = 0.0,
    val startedAtEpochMs: Long = 0,
    val weightKg: Double = 80.0,
) {
    val currentStep get() = session.plan.steps.getOrNull(stepIndex)
    val nextStep get() = session.plan.steps.getOrNull(stepIndex + 1)
    val totalSteps get() = session.plan.steps.size
    val inPrepare get() = prepareRemainingMs > 0
}

/** Result of a finished session, displayed by the Summary screen. */
data class CompletedSummary(
    val sessionId: String,
    val historyId: Long,
    val name: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val totalActiveSec: Int,
    val calories: Double,
    val blocks: List<CompletedBlock>,
    val healthConnectWritten: Boolean,
    val healthConnectError: String? = null,
    val session: GeneratedSession,
    /** True when the user stopped early — the summary offers a short-lived resume. */
    val stoppedEarly: Boolean = false,
)

@Singleton
class SessionStateHolder @Inject constructor() {
    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state.asStateFlow()

    private val _lastCompleted = MutableStateFlow<CompletedSummary?>(null)
    val lastCompleted: StateFlow<CompletedSummary?> = _lastCompleted.asStateFlow()

    fun update(state: PlayerState?) { _state.value = state }
    fun completed(summary: CompletedSummary) { _lastCompleted.value = summary }
    fun clearCompleted() { _lastCompleted.value = null }
}

/**
 * Snapshot persisted to disk every few seconds so a killed process can restore the session.
 * New fields are additive with defaults: pre-1.2 snapshots decode (defaults fill in) and new
 * snapshots decode on old builds (unknown keys ignored).
 */
@Serializable
data class SessionSnapshot(
    val session: GeneratedSession,
    val sessionName: String,
    val stepIndex: Int,
    val stepRemainingMs: Long,
    val totalElapsedActiveMs: Long,
    val caloriesSoFar: Double,
    val startedAtEpochMs: Long,
    val weightKg: Double,
    val savedAtEpochMs: Long,
    /** Per-block accrual so a restore doesn't drop earlier blocks from history/Health Connect. */
    val blockActiveMs: Map<Int, Long> = emptyMap(),
    /** Per-block wall-clock bounds as [startEpochMs, endEpochMs] pairs. */
    val blockBounds: Map<Int, List<Long>> = emptyMap(),
    val prepareRemainingMs: Long = 0,
    val sessionId: String = "",
)
