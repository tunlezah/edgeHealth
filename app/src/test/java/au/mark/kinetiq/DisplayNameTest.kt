package au.mark.kinetiq

import au.mark.kinetiq.data.model.Category
import au.mark.kinetiq.data.model.displayName
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DisplayNameTest {

    @Test
    fun `every category has a human display name distinct from the enum spelling`() {
        Category.entries.forEach { cat ->
            val name = cat.displayName()
            assertThat(name).isNotEmpty()
            assertThat(name).doesNotContain("_")
            assertThat(name).isNotEqualTo(cat.name) // never the raw ALL-CAPS enum spelling
        }
        assertThat(Category.BACK.displayName()).isEqualTo("Back care")
    }
}
