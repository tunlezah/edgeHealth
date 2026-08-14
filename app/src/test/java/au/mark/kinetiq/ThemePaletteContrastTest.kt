package au.mark.kinetiq

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import au.mark.kinetiq.ui.theme.KinetiqPalettes
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The gate for every palette: if a hex fails here, fix the palette value — never the test.
 * WCAG 2.x relative-luminance contrast, 4.5:1 for text pairs, 3:1 for the accent-vs-surface
 * component minimum.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35]) // Robolectric 4.14 supports up to SDK 35; app targets 36.
class ThemePaletteContrastTest {

    private fun channel(c: Float): Double {
        val v = c.toDouble()
        return if (v <= 0.03928) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun textPairs(s: ColorScheme): List<Triple<String, Color, Color>> = listOf(
        Triple("onPrimary/primary", s.onPrimary, s.primary),
        Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
        Triple("onSecondary/secondary", s.onSecondary, s.secondary),
        Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
        Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
        Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
        Triple("onSurface/surface", s.onSurface, s.surface),
        Triple("onBackground/background", s.onBackground, s.background),
        Triple("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
    )

    @Test
    fun `all palettes meet 4_5 to 1 on all on-role pairs in light and dark`() {
        KinetiqPalettes.all.forEach { (palette, schemes) ->
            mapOf("light" to schemes.light, "dark" to schemes.dark).forEach { (modeName, scheme) ->
                textPairs(scheme).forEach { (pair, fg, bg) ->
                    assertWithMessage("$palette/$modeName $pair")
                        .that(contrast(fg, bg)).isAtLeast(4.5)
                }
            }
        }
    }

    @Test
    fun `amoled keeps onSurface legible on pure black`() {
        KinetiqPalettes.all.forEach { (palette, schemes) ->
            assertWithMessage("$palette dark onSurface vs black")
                .that(contrast(schemes.dark.onSurface, Color.Black)).isAtLeast(4.5)
        }
    }

    @Test
    fun `primary is distinguishable from surface in both modes`() {
        KinetiqPalettes.all.forEach { (palette, schemes) ->
            assertWithMessage("$palette light primary vs surface")
                .that(contrast(schemes.light.primary, schemes.light.surface)).isAtLeast(3.0)
            assertWithMessage("$palette dark primary vs surface")
                .that(contrast(schemes.dark.primary, schemes.dark.surface)).isAtLeast(3.0)
        }
    }
}
