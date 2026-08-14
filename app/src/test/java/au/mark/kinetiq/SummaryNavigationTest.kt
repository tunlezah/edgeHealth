package au.mark.kinetiq

import au.mark.kinetiq.ui.nav.shouldNavigateToSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SummaryNavigationTest {

    @Test
    fun `summary navigation fires once per session id`() {
        assertThat(shouldNavigateToSummary("abc", null)).isTrue()
        assertThat(shouldNavigateToSummary("abc", "abc")).isFalse()
        assertThat(shouldNavigateToSummary("def", "abc")).isTrue()
        assertThat(shouldNavigateToSummary(null, "abc")).isFalse()
        assertThat(shouldNavigateToSummary(null, null)).isFalse()
    }
}
