package au.mark.kinetiq.data.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import au.mark.kinetiq.data.model.BodyArea
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "kinetiq_settings")

enum class ThemeMode { LIGHT, DARK, AMOLED, SYSTEM }

/** Accent color palette, orthogonal to [ThemeMode]. MINT is the original Kinetiq look. */
enum class ThemePalette { MINT, OCEAN, EMBER, VIOLET, CITRUS, ROSE, SLATE }

enum class SpringNotation { GENERIC, COUNT }

data class VoiceSettings(
    val countdownBeeps: Boolean = true,
    val nameAnnouncement: Boolean = true,
    val howToDescription: Boolean = true,
    val halfwayCue: Boolean = true,
    val restNextUpCue: Boolean = true,
    val machineSettingCues: Boolean = true,
    val volume: Float = 1.0f,
    val speechRate: Float = 1.0f,
)

data class MachineSettings(
    val spinMaxLevel: Int = 11,      // Horizon GR7: 11 magnetic levels (RESEARCH.md)
    val ellipticalMaxLevel: Int = 16, // Infiniti VG50BS default; see DECISIONS.md
    val springNotation: SpringNotation = SpringNotation.GENERIC,
)

/**
 * Kit the user owns beyond the machines. Hand weights are owned here but only *used* when the
 * Builder's per-session chip is on; the other three gate exercises directly.
 */
data class EquipmentSettings(
    /** Heaviest single hand weight owned, kg. Exercises that need more are never generated. */
    val handWeightMaxKg: Float = 2f,
    val chairOrStep: Boolean = false,
    val reformerBox: Boolean = false,
    val ankleAnchor: Boolean = false,
) {
    /** Everything owned except hand weights, which the session config switches on. */
    val owned: Set<au.mark.kinetiq.data.model.Equipment>
        get() = buildSet {
            if (chairOrStep) add(au.mark.kinetiq.data.model.Equipment.CHAIR_OR_STEP)
            if (reformerBox) add(au.mark.kinetiq.data.model.Equipment.REFORMER_BOX)
            if (ankleAnchor) add(au.mark.kinetiq.data.model.Equipment.ANKLE_ANCHOR)
        }
}

data class AppSettings(
    val onboardingComplete: Boolean = false,
    val disclaimerAcknowledged: Boolean = false,
    val disclaimerLineInWorkout: Boolean = true,
    val constraints: Set<BodyArea> = emptySet(),
    val voice: VoiceSettings = VoiceSettings(),
    val machines: MachineSettings = MachineSettings(),
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val palette: ThemePalette = ThemePalette.MINT,
    val includeLowEvidence: Boolean = false,
    val healthConnectEnabled: Boolean = false,
    val healthConnectWriteback: Boolean = true,
    val useHealthDataInGenerator: Boolean = true,
    val keepScreenOn: Boolean = true,
    val metricUnits: Boolean = true,
    val fallbackWeightKg: Float = 80f,
    /** Days of week (1=Mon..7=Sun) that never break a streak. */
    val restDays: Set<Int> = setOf(7),
    val reminderDays: Set<Int> = emptySet(),
    val reminderHour: Int = 7,
    val reminderMinute: Int = 0,
    val visceralFatGoal: Boolean = true,
    /** Default rest model seeded into the Builder for new configs. */
    val defaultRestMode: au.mark.kinetiq.data.model.RestMode = au.mark.kinetiq.data.model.RestMode.STANDARD,
    /** One-time "continuous mode removes rests" notice acknowledged. */
    val continuousNoticeSeen: Boolean = false,
    val equipment: EquipmentSettings = EquipmentSettings(),
    /** Current rung per resistance progression ladder; a missing ladder uses the generator's default. */
    val ladderRungs: Map<String, Int> = emptyMap(),
)

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val disclaimerAck = booleanPreferencesKey("disclaimer_ack")
        val disclaimerLine = booleanPreferencesKey("disclaimer_line")
        val constraints = stringPreferencesKey("constraints")
        val beeps = booleanPreferencesKey("v_beeps")
        val names = booleanPreferencesKey("v_names")
        val howto = booleanPreferencesKey("v_howto")
        val halfway = booleanPreferencesKey("v_halfway")
        val restNext = booleanPreferencesKey("v_restnext")
        val machineCues = booleanPreferencesKey("v_machine")
        val volume = floatPreferencesKey("v_volume")
        val rate = floatPreferencesKey("v_rate")
        val spinMax = intPreferencesKey("spin_max")
        val ellipticalMax = intPreferencesKey("elliptical_max")
        val springNotation = stringPreferencesKey("spring_notation")
        val theme = stringPreferencesKey("theme")
        val palette = stringPreferencesKey("theme_palette")
        val lowEvidence = booleanPreferencesKey("low_evidence")
        val hcEnabled = booleanPreferencesKey("hc_enabled")
        val hcWriteback = booleanPreferencesKey("hc_writeback")
        val useHealthData = booleanPreferencesKey("use_health_data")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val metric = booleanPreferencesKey("metric_units")
        val fallbackWeight = floatPreferencesKey("fallback_weight")
        val restDays = stringPreferencesKey("rest_days")
        val reminderDays = stringPreferencesKey("reminder_days")
        val reminderHour = intPreferencesKey("reminder_hour")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val visceralGoal = booleanPreferencesKey("visceral_goal")
        val lastConfig = stringPreferencesKey("last_config")
        val restMode = stringPreferencesKey("rest_mode")
        val continuousNoticeSeen = booleanPreferencesKey("continuous_notice_seen")
        val handWeightMaxKg = floatPreferencesKey("eq_hand_weight_max_kg")
        val chairOrStep = booleanPreferencesKey("eq_chair_or_step")
        val reformerBox = booleanPreferencesKey("eq_reformer_box")
        val ankleAnchor = booleanPreferencesKey("eq_ankle_anchor")
        val ladderRungs = stringPreferencesKey("ladder_rungs")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingComplete = p[Keys.onboardingComplete] ?: false,
            disclaimerAcknowledged = p[Keys.disclaimerAck] ?: false,
            disclaimerLineInWorkout = p[Keys.disclaimerLine] ?: true,
            constraints = decodeEnumSet(p[Keys.constraints]),
            voice = VoiceSettings(
                countdownBeeps = p[Keys.beeps] ?: true,
                nameAnnouncement = p[Keys.names] ?: true,
                howToDescription = p[Keys.howto] ?: true,
                halfwayCue = p[Keys.halfway] ?: true,
                restNextUpCue = p[Keys.restNext] ?: true,
                machineSettingCues = p[Keys.machineCues] ?: true,
                volume = p[Keys.volume] ?: 1.0f,
                speechRate = p[Keys.rate] ?: 1.0f,
            ),
            machines = MachineSettings(
                spinMaxLevel = p[Keys.spinMax] ?: 11,
                ellipticalMaxLevel = p[Keys.ellipticalMax] ?: 16,
                springNotation = p[Keys.springNotation]?.let { runCatching { SpringNotation.valueOf(it) }.getOrNull() }
                    ?: SpringNotation.GENERIC,
            ),
            theme = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            palette = p[Keys.palette]?.let { runCatching { ThemePalette.valueOf(it) }.getOrNull() } ?: ThemePalette.MINT,
            includeLowEvidence = p[Keys.lowEvidence] ?: false,
            healthConnectEnabled = p[Keys.hcEnabled] ?: false,
            healthConnectWriteback = p[Keys.hcWriteback] ?: true,
            useHealthDataInGenerator = p[Keys.useHealthData] ?: true,
            keepScreenOn = p[Keys.keepScreenOn] ?: true,
            metricUnits = p[Keys.metric] ?: true,
            fallbackWeightKg = p[Keys.fallbackWeight] ?: 80f,
            restDays = decodeIntSet(p[Keys.restDays], default = setOf(7)),
            reminderDays = decodeIntSet(p[Keys.reminderDays], default = emptySet()),
            reminderHour = p[Keys.reminderHour] ?: 7,
            reminderMinute = p[Keys.reminderMinute] ?: 0,
            visceralFatGoal = p[Keys.visceralGoal] ?: true,
            defaultRestMode = p[Keys.restMode]?.let {
                runCatching { au.mark.kinetiq.data.model.RestMode.valueOf(it) }.getOrNull()
            } ?: au.mark.kinetiq.data.model.RestMode.STANDARD,
            continuousNoticeSeen = p[Keys.continuousNoticeSeen] ?: false,
            equipment = EquipmentSettings(
                handWeightMaxKg = p[Keys.handWeightMaxKg] ?: 2f,
                chairOrStep = p[Keys.chairOrStep] ?: false,
                reformerBox = p[Keys.reformerBox] ?: false,
                ankleAnchor = p[Keys.ankleAnchor] ?: false,
            ),
            ladderRungs = decodeLadderRungs(p[Keys.ladderRungs]),
        )
    }

    suspend fun current(): AppSettings = settings.first()

    val lastConfigJson: Flow<String?> = context.dataStore.data.map { it[Keys.lastConfig] }

    suspend fun setLastConfigJson(json: String) = edit { it[Keys.lastConfig] = json }
    suspend fun setOnboardingComplete(v: Boolean) = edit { it[Keys.onboardingComplete] = v }
    suspend fun setDisclaimerAcknowledged(v: Boolean) = edit { it[Keys.disclaimerAck] = v }
    suspend fun setDisclaimerLine(v: Boolean) = edit { it[Keys.disclaimerLine] = v }
    suspend fun setConstraints(v: Set<BodyArea>) = edit { it[Keys.constraints] = v.joinToString(",") { c -> c.name } }
    suspend fun setVoice(v: VoiceSettings) = edit {
        it[Keys.beeps] = v.countdownBeeps; it[Keys.names] = v.nameAnnouncement
        it[Keys.howto] = v.howToDescription; it[Keys.halfway] = v.halfwayCue
        it[Keys.restNext] = v.restNextUpCue; it[Keys.machineCues] = v.machineSettingCues
        it[Keys.volume] = v.volume; it[Keys.rate] = v.speechRate
    }
    suspend fun setMachines(m: MachineSettings) = edit {
        it[Keys.spinMax] = m.spinMaxLevel
        it[Keys.ellipticalMax] = m.ellipticalMaxLevel
        it[Keys.springNotation] = m.springNotation.name
    }
    suspend fun setTheme(v: ThemeMode) = edit { it[Keys.theme] = v.name }
    suspend fun setPalette(v: ThemePalette) = edit { it[Keys.palette] = v.name }
    suspend fun setIncludeLowEvidence(v: Boolean) = edit { it[Keys.lowEvidence] = v }
    suspend fun setHealthConnectEnabled(v: Boolean) = edit { it[Keys.hcEnabled] = v }
    suspend fun setHealthConnectWriteback(v: Boolean) = edit { it[Keys.hcWriteback] = v }
    suspend fun setUseHealthData(v: Boolean) = edit { it[Keys.useHealthData] = v }
    suspend fun setKeepScreenOn(v: Boolean) = edit { it[Keys.keepScreenOn] = v }
    suspend fun setMetricUnits(v: Boolean) = edit { it[Keys.metric] = v }
    suspend fun setFallbackWeight(v: Float) = edit { it[Keys.fallbackWeight] = v }
    suspend fun setRestDays(v: Set<Int>) = edit { it[Keys.restDays] = v.joinToString(",") }
    suspend fun setReminder(days: Set<Int>, hour: Int, minute: Int) = edit {
        it[Keys.reminderDays] = days.joinToString(",")
        it[Keys.reminderHour] = hour
        it[Keys.reminderMinute] = minute
    }
    suspend fun setVisceralGoal(v: Boolean) = edit { it[Keys.visceralGoal] = v }
    suspend fun setDefaultRestMode(v: au.mark.kinetiq.data.model.RestMode) = edit { it[Keys.restMode] = v.name }
    suspend fun setContinuousNoticeSeen(v: Boolean) = edit { it[Keys.continuousNoticeSeen] = v }
    suspend fun setEquipment(e: EquipmentSettings) = edit {
        it[Keys.handWeightMaxKg] = e.handWeightMaxKg
        it[Keys.chairOrStep] = e.chairOrStep
        it[Keys.reformerBox] = e.reformerBox
        it[Keys.ankleAnchor] = e.ankleAnchor
    }

    /** Read-modify-write inside one DataStore transaction, so two quick taps cannot lose one. */
    suspend fun setLadderRung(ladder: String, rung: Int) = edit {
        val next = decodeLadderRungs(it[Keys.ladderRungs]) + (ladder to rung.coerceAtLeast(1))
        it[Keys.ladderRungs] = encodeLadderRungs(next)
    }

    suspend fun resetLadderRungs() = edit { it.remove(Keys.ladderRungs) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private fun decodeEnumSet(raw: String?): Set<BodyArea> =
        raw?.split(',')?.filter { it.isNotBlank() }
            ?.mapNotNull { runCatching { BodyArea.valueOf(it) }.getOrNull() }?.toSet() ?: emptySet()

    private fun decodeIntSet(raw: String?, default: Set<Int>): Set<Int> =
        raw?.let { s -> s.split(',').filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull() }.toSet() } ?: default

    companion object {
        /** "pushup=3,leg_push=2" — ladder ids are lower_snake_case (validator-enforced), so no escaping is needed. */
        internal fun encodeLadderRungs(rungs: Map<String, Int>): String =
            rungs.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

        internal fun decodeLadderRungs(raw: String?): Map<String, Int> =
            raw?.split(',')?.mapNotNull { pair ->
                val (k, v) = pair.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
                val rung = v.toIntOrNull() ?: return@mapNotNull null
                if (k.isBlank() || rung < 1) null else k to rung
            }?.toMap() ?: emptyMap()
    }
}
