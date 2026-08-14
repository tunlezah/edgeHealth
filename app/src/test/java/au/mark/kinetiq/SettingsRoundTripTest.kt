package au.mark.kinetiq

import androidx.test.core.app.ApplicationProvider
import au.mark.kinetiq.data.model.RestMode
import au.mark.kinetiq.data.repo.SettingsRepository
import au.mark.kinetiq.data.repo.ThemePalette
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 supports up to SDK 35; app targets 36.
class SettingsRoundTripTest {

    private val repo = SettingsRepository(ApplicationProvider.getApplicationContext())

    /**
     * One ordered flow: defaults are asserted before any write because the DataStore singleton
     * outlives individual test methods under Robolectric.
     */
    @Test
    fun `defaults apply then palette rest mode and notice flag round-trip`() = runTest {
        val initial = repo.current()
        assertThat(initial.palette).isEqualTo(ThemePalette.MINT)
        assertThat(initial.defaultRestMode).isEqualTo(RestMode.STANDARD)
        assertThat(initial.continuousNoticeSeen).isFalse()

        repo.setPalette(ThemePalette.EMBER)
        repo.setDefaultRestMode(RestMode.CONTINUOUS)
        repo.setContinuousNoticeSeen(true)

        val updated = repo.current()
        assertThat(updated.palette).isEqualTo(ThemePalette.EMBER)
        assertThat(updated.defaultRestMode).isEqualTo(RestMode.CONTINUOUS)
        assertThat(updated.continuousNoticeSeen).isTrue()
    }
}
