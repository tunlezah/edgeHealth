package au.mark.kinetiq

import android.speech.tts.TextToSpeech
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
}
