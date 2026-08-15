package au.mark.kinetiq

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import au.mark.kinetiq.voice.TtsInitAction
import au.mark.kinetiq.voice.TtsStatus
import au.mark.kinetiq.voice.VoiceCoach
import au.mark.kinetiq.voice.nextInitAction
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 supports up to SDK 35; app targets 36.
class VoiceCoachStatusTest {

    @Test
    fun `init action is ready on success regardless of attempts`() {
        assertThat(nextInitAction(TextToSpeech.SUCCESS, 1)).isEqualTo(TtsInitAction.READY)
        assertThat(nextInitAction(TextToSpeech.SUCCESS, VoiceCoach.MAX_INIT_ATTEMPTS)).isEqualTo(TtsInitAction.READY)
    }

    @Test
    fun `init failure retries once then fails`() {
        assertThat(nextInitAction(TextToSpeech.ERROR, 1)).isEqualTo(TtsInitAction.RETRY)
        assertThat(nextInitAction(TextToSpeech.ERROR, VoiceCoach.MAX_INIT_ATTEMPTS)).isEqualTo(TtsInitAction.FAIL)
        assertThat(nextInitAction(TextToSpeech.ERROR, VoiceCoach.MAX_INIT_ATTEMPTS + 1)).isEqualTo(TtsInitAction.FAIL)
    }

    @Test
    fun `pending utterance queue is bounded before the engine is ready`() {
        val coach = VoiceCoach(ApplicationProvider.getApplicationContext())
        // Robolectric's TextToSpeech never fires the init callback here, so the coach stays
        // un-ready and every speak() call lands in the pending queue — perfect for the bound.
        repeat(40) { i -> coach.speak("cue $i") }
        assertThat(coach.pendingCountForTest).isAtMost(VoiceCoach.MAX_PENDING_UTTERANCES)
        assertThat(coach.status.value).isAnyOf(TtsStatus.INITIALIZING, TtsStatus.IDLE)
    }

    @Test
    fun `utterance counter never goes negative`() {
        val coach = VoiceCoach(ApplicationProvider.getApplicationContext())
        repeat(5) { coach.onUtteranceFinished() }
        assertThat(coach.utteranceCountForTest()).isEqualTo(0)
    }

    private fun voice(name: String, locale: Locale, quality: Int, network: Boolean) =
        Voice(name, locale, quality, Voice.LATENCY_NORMAL, network, emptySet())

    @Test
    fun `best offline voice prefers quality and never returns a network voice`() {
        val coach = VoiceCoach(ApplicationProvider.getApplicationContext())
        val au = Locale("en", "AU")
        val voices = setOf(
            voice("au-net-high", au, Voice.QUALITY_VERY_HIGH, network = true),
            voice("au-off-low", au, Voice.QUALITY_LOW, network = false),
            voice("au-off-high", au, Voice.QUALITY_HIGH, network = false),
            voice("us-off-high", Locale.US, Voice.QUALITY_HIGH, network = false),
        )
        assertThat(coach.bestOfflineVoice(voices, au)?.name).isEqualTo("au-off-high")
    }

    @Test
    fun `no offline voice means the engine keeps its own choice rather than going mute`() {
        // The caller must NOT clear engine.voice when this returns null: on a device whose only
        // en-AU voice is network-backed, doing so would silence coaching altogether.
        val coach = VoiceCoach(ApplicationProvider.getApplicationContext())
        val au = Locale("en", "AU")
        assertThat(coach.bestOfflineVoice(setOf(voice("au-net", au, Voice.QUALITY_HIGH, true)), au)).isNull()
        assertThat(coach.bestOfflineVoice(null, au)).isNull()
    }

    @Test
    fun `audio focus request and abandon stay consistent under concurrency`() {
        // Three writers race on focusRequest: main, the beep scope, and a binder thread delivering
        // UtteranceProgressListener callbacks. Without the lock this check-then-act loses updates.
        val coach = VoiceCoach(ApplicationProvider.getApplicationContext())
        val pool = Executors.newFixedThreadPool(4)
        repeat(4_000) { i ->
            pool.submit { if (i % 2 == 0) coach.requestFocus() else coach.abandonFocus() }
        }
        pool.shutdown()
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue()
        coach.abandonFocus()
        assertThat(coach.hasAudioFocusForTest()).isFalse()
    }
}
