package au.mark.kinetiq

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The widget's one-tap launch is the app's headline feature, so the gate around it has to admit
 * the widget on both a cold start and a warm one while rejecting anything else.
 */
class LaunchIntentTest {

    private val self = "au.mark.kinetiq"

    @Test
    fun `a token minted by this app on a fresh launch is accepted`() {
        assertThat(shouldRepeatLast(MainActivity.ACTION_REPEAT_LAST, self, self, isRecreation = false)).isTrue()
    }

    @Test
    fun `a token from another app is rejected`() {
        assertThat(shouldRepeatLast(MainActivity.ACTION_REPEAT_LAST, "com.evil.app", self, isRecreation = false))
            .isFalse()
    }

    @Test
    fun `a forged intent carrying no token is rejected`() {
        assertThat(shouldRepeatLast(MainActivity.ACTION_REPEAT_LAST, null, self, isRecreation = false)).isFalse()
    }

    @Test
    fun `a recreation never replays the action`() {
        // Scheduled dark mode, a font-scale change or a restore after process death all re-run
        // onCreate with the task's original intent still attached.
        assertThat(shouldRepeatLast(MainActivity.ACTION_REPEAT_LAST, self, self, isRecreation = true)).isFalse()
    }

    @Test
    fun `an ordinary launcher tap is not a repeat-last`() {
        assertThat(shouldRepeatLast(Intent.ACTION_MAIN, self, self, isRecreation = false)).isFalse()
        assertThat(shouldRepeatLast(null, self, self, isRecreation = false)).isFalse()
    }
}
