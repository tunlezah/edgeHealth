package au.mark.kinetiq.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import au.mark.kinetiq.data.repo.VoiceSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers

/** Lifecycle of the TTS engine; FAILED means init failed after a retry and speech is muted. */
enum class TtsStatus { IDLE, INITIALIZING, READY, FAILED }

/** What to do after a TTS init callback given the engine status and prior attempt count. */
enum class TtsInitAction { READY, RETRY, FAIL }

internal fun nextInitAction(status: Int, attempts: Int): TtsInitAction = when {
    status == TextToSpeech.SUCCESS -> TtsInitAction.READY
    attempts < VoiceCoach.MAX_INIT_ATTEMPTS -> TtsInitAction.RETRY
    else -> TtsInitAction.FAIL
}

/**
 * On-device TTS coach.
 *
 *  - Prefers an en-AU voice, falling back to the device default locale.
 *  - Cues are queued through [TextToSpeech.QUEUE_ADD]; they never overlap.
 *  - Requests transient-may-duck audio focus so cues duck the user's music
 *    instead of stopping it, releasing focus as soon as the queue drains.
 *  - Countdown beeps are synthesized with [ToneGenerator] — no bundled audio files.
 *  - Engine init failure retries once, then surfaces [TtsStatus.FAILED] so the UI
 *    can say the voice is unavailable; sessions keep running silently.
 *
 * Pre-warm by calling [warmUp] before a session starts.
 */
@Singleton
class VoiceCoach @Inject constructor(@ApplicationContext private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pendingOnReady = mutableListOf<() -> Unit>()
    private var initAttempts = 0

    private val _status = MutableStateFlow(TtsStatus.IDLE)
    val status: StateFlow<TtsStatus> = _status.asStateFlow()

    @Volatile var settings: VoiceSettings = VoiceSettings()

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val utteranceCount = AtomicInteger(0)
    private var focusRequest: AudioFocusRequest? = null
    private var utteranceSeq = 0

    internal val pendingCountForTest: Int get() = pendingOnReady.size
    internal fun utteranceCountForTest(): Int = utteranceCount.get()

    fun warmUp(onReady: (() -> Unit)? = null) {
        if (ready) { onReady?.invoke(); return }
        onReady?.let { enqueuePending(it) }
        if (_status.value == TtsStatus.FAILED) {
            // Engine is known-dead: run callbacks so callers (session start) proceed silently.
            drainPending()
            return
        }
        if (tts != null) return
        _status.value = TtsStatus.INITIALIZING
        tts = TextToSpeech(context) { status ->
            when (nextInitAction(status, ++initAttempts)) {
                TtsInitAction.READY -> {
                    configureVoice()
                    installListener()
                    ready = true
                    initAttempts = 0
                    _status.value = TtsStatus.READY
                    drainPending()
                }
                TtsInitAction.RETRY -> {
                    tts?.shutdown()
                    tts = null
                    scope.launch { delay(2_000); warmUp() }
                }
                TtsInitAction.FAIL -> {
                    tts?.shutdown()
                    tts = null
                    _status.value = TtsStatus.FAILED
                    drainPending()
                }
            }
        }
    }

    /** User-triggered retry after a FAILED init (banner button / Settings test). */
    fun retryInit() {
        if (ready) return
        initAttempts = 0
        _status.value = TtsStatus.IDLE
        tts = null
        warmUp()
    }

    private fun enqueuePending(block: () -> Unit) {
        if (pendingOnReady.size >= MAX_PENDING_UTTERANCES) pendingOnReady.removeAt(0)
        pendingOnReady.add(block)
    }

    private fun drainPending() {
        val toRun = pendingOnReady.toList()
        pendingOnReady.clear()
        toRun.forEach { it() }
    }

    private fun configureVoice() {
        val engine = tts ?: return
        val auLocale = Locale("en", "AU")
        val result = engine.setLanguage(auLocale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        // Prefer a higher-quality en-AU voice when one is installed.
        engine.voices?.filter { it.locale == auLocale && !it.isNetworkConnectionRequired }
            ?.maxByOrNull { it.quality }
            ?.let { engine.voice = it }
        engine.setAudioAttributes(speechAttributes)
    }

    private val speechAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun installListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = onUtteranceFinished()

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) = onUtteranceFinished()

            override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceFinished()
        })
    }

    /** Clamped at zero: a stale onDone racing stopSpeaking must never drive the count negative. */
    internal fun onUtteranceFinished() {
        if (utteranceCount.updateAndGet { (it - 1).coerceAtLeast(0) } == 0) abandonFocus()
    }

    /** Queues a spoken cue. Cues never overlap; focus ducks other audio while speaking. */
    fun speak(text: String, flush: Boolean = false) {
        if (text.isBlank()) return
        if (_status.value == TtsStatus.FAILED) return
        val engine = tts ?: run { warmUp { speak(text, flush) }; return }
        if (!ready) { enqueuePending { speak(text, flush) }; return }

        engine.setSpeechRate(settings.speechRate.coerceIn(0.5f, 2.0f))
        requestFocus()
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, settings.volume.coerceIn(0f, 1f))
        }
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (flush) utteranceCount.set(0)
        utteranceCount.incrementAndGet()
        engine.speak(text, mode, params, "kinetiq-${utteranceSeq++}")
    }

    fun stopSpeaking() {
        tts?.stop()
        utteranceCount.set(0)
        abandonFocus()
    }

    /** 3-2-1 countdown: two short low beeps then one long high beep, generated on device. */
    fun countdownBeeps() {
        if (!settings.countdownBeeps) return
        scope.launch {
            requestFocus()
            val volume = (settings.volume.coerceIn(0f, 1f) * 100).toInt().coerceIn(10, 100)
            val gen = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            try {
                repeat(2) {
                    gen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                    delay(1000)
                }
                gen.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
                delay(450)
            } finally {
                gen.release()
                if (utteranceCount.get() <= 0) abandonFocus()
            }
        }
    }

    fun singleBeep() {
        scope.launch {
            val volume = (settings.volume.coerceIn(0f, 1f) * 100).toInt().coerceIn(10, 100)
            val gen = ToneGenerator(AudioManager.STREAM_MUSIC, volume)
            try {
                gen.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                delay(300)
            } finally {
                gen.release()
            }
        }
    }

    private fun requestFocus() {
        if (focusRequest != null) return
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttributes)
            .build()
        audioManager.requestAudioFocus(request)
        focusRequest = request
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    fun shutdown() {
        stopSpeaking()
        tts?.shutdown()
        tts = null
        ready = false
        if (_status.value != TtsStatus.FAILED) _status.value = TtsStatus.IDLE
    }

    companion object {
        /** Init retries before giving up and surfacing FAILED (first attempt + one retry). */
        const val MAX_INIT_ATTEMPTS = 2

        /** Cap on cues queued while the engine is still initializing. */
        const val MAX_PENDING_UTTERANCES = 16
    }
}
