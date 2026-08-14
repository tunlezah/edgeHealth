package au.mark.kinetiq.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import au.mark.kinetiq.data.repo.ThemeMode
import au.mark.kinetiq.data.repo.ThemePalette

/** Full light+dark scheme pair for one accent palette. */
data class PaletteSchemes(val displayName: String, val light: ColorScheme, val dark: ColorScheme)

/**
 * Seven hand-tuned accent palettes, each defining the FULL Material3 role set for light and
 * dark so no baseline purple ever leaks through container/surface roles (the pre-1.2 bug).
 * Deliberately no dynamicColorScheme: palettes are hand-tuned and contrast-tested
 * (ThemePaletteContrastTest gates every on-role pair at WCAG 4.5:1).
 */
object KinetiqPalettes {

    /**
     * The original Kinetiq look. Light primary nudged 0xFF1F8F6B → 0xFF1B8060: the original
     * failed WCAG 4.5:1 under white text (4.04:1) and the contrast test is the gate.
     */
    private val mint = PaletteSchemes(
        displayName = "Kinetiq Mint",
        light = lightColorScheme(
            primary = Color(0xFF1B8060), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFBFF2DF), onPrimaryContainer = Color(0xFF0A4B36),
            secondary = Color(0xFF1E7FA3), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFC4E7F5), onSecondaryContainer = Color(0xFF003547),
            tertiary = Color(0xFFB35430), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFDBCC), onTertiaryContainer = Color(0xFF380D00),
            background = Color(0xFFF7FAF8), onBackground = Color(0xFF171D1A),
            surface = Color(0xFFF7FAF8), onSurface = Color(0xFF171D1A),
            surfaceVariant = Color(0xFFE2EAE5), onSurfaceVariant = Color(0xFF404944),
            surfaceContainer = Color(0xFFEBF1EC), surfaceContainerLow = Color(0xFFF1F5F1),
            surfaceContainerHigh = Color(0xFFE5ECE7), outline = Color(0xFF6F7973),
        ),
        dark = darkColorScheme(
            primary = Color(0xFF38E0A6), onPrimary = Color(0xFF00382A),
            primaryContainer = Color(0xFF0F5C43), onPrimaryContainer = Color(0xFFBFF2DF),
            secondary = Color(0xFF5ED4F0), onSecondary = Color(0xFF003544),
            secondaryContainer = Color(0xFF004D5F), onSecondaryContainer = Color(0xFFB8EAFF),
            tertiary = Color(0xFFFF8A5C), onTertiary = Color(0xFF4A1500),
            tertiaryContainer = Color(0xFF6E2F12), onTertiaryContainer = Color(0xFFFFDBCC),
            background = Color(0xFF0C1411), onBackground = Color(0xFFDEE4DF),
            surface = Color(0xFF101A16), onSurface = Color(0xFFDEE4DF),
            surfaceVariant = Color(0xFF1D2B25), onSurfaceVariant = Color(0xFFBFC9C2),
            surfaceContainer = Color(0xFF16211C), surfaceContainerLow = Color(0xFF101A16),
            surfaceContainerHigh = Color(0xFF1C2823), outline = Color(0xFF89938C),
        ),
    )

    private val ocean = PaletteSchemes(
        displayName = "Ocean",
        light = lightColorScheme(
            primary = Color(0xFF00639B), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFCDE5FF), onPrimaryContainer = Color(0xFF001D31),
            secondary = Color(0xFF51606F), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFD5E4F7), onSecondaryContainer = Color(0xFF0E1D2A),
            tertiary = Color(0xFF67587A), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFEDDCFF), onTertiaryContainer = Color(0xFF221533),
            background = Color(0xFFF7F9FF), onBackground = Color(0xFF181C20),
            surface = Color(0xFFF7F9FF), onSurface = Color(0xFF181C20),
            surfaceVariant = Color(0xFFDEE3EB), onSurfaceVariant = Color(0xFF42474E),
            surfaceContainer = Color(0xFFEBEEF3), surfaceContainerLow = Color(0xFFF1F4F9),
            surfaceContainerHigh = Color(0xFFE5E8ED), outline = Color(0xFF72777F),
        ),
        dark = darkColorScheme(
            primary = Color(0xFF98CBFF), onPrimary = Color(0xFF003354),
            primaryContainer = Color(0xFF004A77), onPrimaryContainer = Color(0xFFCDE5FF),
            secondary = Color(0xFFB9C8DA), onSecondary = Color(0xFF233240),
            secondaryContainer = Color(0xFF394857), onSecondaryContainer = Color(0xFFD5E4F7),
            tertiary = Color(0xFFD2BFE7), onTertiary = Color(0xFF372A4A),
            tertiaryContainer = Color(0xFF4E4062), onTertiaryContainer = Color(0xFFEDDCFF),
            background = Color(0xFF0E1116), onBackground = Color(0xFFE0E2E8),
            surface = Color(0xFF111418), onSurface = Color(0xFFE0E2E8),
            surfaceVariant = Color(0xFF1E242B), onSurfaceVariant = Color(0xFFC2C7CF),
            surfaceContainer = Color(0xFF171B21), surfaceContainerLow = Color(0xFF111418),
            surfaceContainerHigh = Color(0xFF1D222A), outline = Color(0xFF8C9199),
        ),
    )

    private val ember = PaletteSchemes(
        displayName = "Ember",
        light = lightColorScheme(
            primary = Color(0xFF9A4500), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFDBC9), onPrimaryContainer = Color(0xFF331200),
            secondary = Color(0xFF77574A), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFF5DED3), onSecondaryContainer = Color(0xFF2C160B),
            tertiary = Color(0xFF6C5D2F), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFF6E1A6), onTertiaryContainer = Color(0xFF221B00),
            background = Color(0xFFFFF8F5), onBackground = Color(0xFF221A15),
            surface = Color(0xFFFFF8F5), onSurface = Color(0xFF221A15),
            surfaceVariant = Color(0xFFF0DFD6), onSurfaceVariant = Color(0xFF52443C),
            surfaceContainer = Color(0xFFF8ECE4), surfaceContainerLow = Color(0xFFFCF1EA),
            surfaceContainerHigh = Color(0xFFF2E6DE), outline = Color(0xFF84746B),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFFFB68F), onPrimary = Color(0xFF532200),
            primaryContainer = Color(0xFF763300), onPrimaryContainer = Color(0xFFFFDBC9),
            secondary = Color(0xFFE7BEAD), onSecondary = Color(0xFF442A1E),
            secondaryContainer = Color(0xFF5D4033), onSecondaryContainer = Color(0xFFF5DED3),
            tertiary = Color(0xFFD9C58D), onTertiary = Color(0xFF3B2F05),
            tertiaryContainer = Color(0xFF534619), onTertiaryContainer = Color(0xFFF6E1A6),
            background = Color(0xFF170F0B), onBackground = Color(0xFFF0DFD7),
            surface = Color(0xFF1A120D), onSurface = Color(0xFFF0DFD7),
            surfaceVariant = Color(0xFF2B211B), onSurfaceVariant = Color(0xFFD7C2B8),
            surfaceContainer = Color(0xFF211812), surfaceContainerLow = Color(0xFF1A120D),
            surfaceContainerHigh = Color(0xFF281E18), outline = Color(0xFFA08D84),
        ),
    )

    private val violet = PaletteSchemes(
        displayName = "Violet",
        light = lightColorScheme(
            primary = Color(0xFF6750A4), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
            secondary = Color(0xFF625B71), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFE8DEF8), onSecondaryContainer = Color(0xFF1D192B),
            tertiary = Color(0xFF7D5260), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFD8E4), onTertiaryContainer = Color(0xFF31111D),
            background = Color(0xFFFDF7FF), onBackground = Color(0xFF1C1B20),
            surface = Color(0xFFFDF7FF), onSurface = Color(0xFF1C1B20),
            surfaceVariant = Color(0xFFE7E0EC), onSurfaceVariant = Color(0xFF49454F),
            surfaceContainer = Color(0xFFF1EBF4), surfaceContainerLow = Color(0xFFF7F1FA),
            surfaceContainerHigh = Color(0xFFEBE5EE), outline = Color(0xFF79747E),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFD0BCFF), onPrimary = Color(0xFF381E72),
            primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
            secondary = Color(0xFFCCC2DC), onSecondary = Color(0xFF332D41),
            secondaryContainer = Color(0xFF4A4458), onSecondaryContainer = Color(0xFFE8DEF8),
            tertiary = Color(0xFFEFB8C8), onTertiary = Color(0xFF492532),
            tertiaryContainer = Color(0xFF633B48), onTertiaryContainer = Color(0xFFFFD8E4),
            background = Color(0xFF101014), onBackground = Color(0xFFE6E0E9),
            surface = Color(0xFF141218), onSurface = Color(0xFFE6E0E9),
            surfaceVariant = Color(0xFF26232C), onSurfaceVariant = Color(0xFFCAC4D0),
            surfaceContainer = Color(0xFF1B1920), surfaceContainerLow = Color(0xFF141218),
            surfaceContainerHigh = Color(0xFF211F26), outline = Color(0xFF938F99),
        ),
    )

    private val citrus = PaletteSchemes(
        displayName = "Citrus",
        light = lightColorScheme(
            primary = Color(0xFF4C6700), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFCDEF83), onPrimaryContainer = Color(0xFF151F00),
            secondary = Color(0xFF5B6147), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFDFE6C4), onSecondaryContainer = Color(0xFF181E09),
            tertiary = Color(0xFF396661), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFBCECE6), onTertiaryContainer = Color(0xFF00201D),
            background = Color(0xFFFAFAF0), onBackground = Color(0xFF1B1C16),
            surface = Color(0xFFFAFAF0), onSurface = Color(0xFF1B1C16),
            surfaceVariant = Color(0xFFE3E4D3), onSurfaceVariant = Color(0xFF46483B),
            surfaceContainer = Color(0xFFEEEEE2), surfaceContainerLow = Color(0xFFF4F4E8),
            surfaceContainerHigh = Color(0xFFE8E9DC), outline = Color(0xFF77786A),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFB1D264), onPrimary = Color(0xFF263500),
            primaryContainer = Color(0xFF394E00), onPrimaryContainer = Color(0xFFCDEF83),
            secondary = Color(0xFFC3CAA9), onSecondary = Color(0xFF2D331B),
            secondaryContainer = Color(0xFF434930), onSecondaryContainer = Color(0xFFDFE6C4),
            tertiary = Color(0xFFA0D0CA), onTertiary = Color(0xFF013733),
            tertiaryContainer = Color(0xFF1F4E49), onTertiaryContainer = Color(0xFFBCECE6),
            background = Color(0xFF10110B), onBackground = Color(0xFFE4E3D6),
            surface = Color(0xFF13140D), onSurface = Color(0xFFE4E3D6),
            surfaceVariant = Color(0xFF22231A), onSurfaceVariant = Color(0xFFC6C8B5),
            surfaceContainer = Color(0xFF191A12), surfaceContainerLow = Color(0xFF13140D),
            surfaceContainerHigh = Color(0xFF1F2017), outline = Color(0xFF909283),
        ),
    )

    private val rose = PaletteSchemes(
        displayName = "Rose",
        light = lightColorScheme(
            primary = Color(0xFF984061), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFFFD9E2), onPrimaryContainer = Color(0xFF3E001D),
            secondary = Color(0xFF74565F), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFF7DAE1), onSecondaryContainer = Color(0xFF2B151C),
            tertiary = Color(0xFF7C5635), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFFFDCC1), onTertiaryContainer = Color(0xFF2E1500),
            background = Color(0xFFFFF8F8), onBackground = Color(0xFF22191C),
            surface = Color(0xFFFFF8F8), onSurface = Color(0xFF22191C),
            surfaceVariant = Color(0xFFF2DDE1), onSurfaceVariant = Color(0xFF514347),
            surfaceContainer = Color(0xFFFAEBEE), surfaceContainerLow = Color(0xFFFEF1F3),
            surfaceContainerHigh = Color(0xFFF4E5E8), outline = Color(0xFF837377),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFFFB1C8), onPrimary = Color(0xFF5E1133),
            primaryContainer = Color(0xFF7B2949), onPrimaryContainer = Color(0xFFFFD9E2),
            secondary = Color(0xFFE3BDC6), onSecondary = Color(0xFF422931),
            secondaryContainer = Color(0xFF5A3F47), onSecondaryContainer = Color(0xFFF7DAE1),
            tertiary = Color(0xFFEFBD94), onTertiary = Color(0xFF48290B),
            tertiaryContainer = Color(0xFF62401F), onTertiaryContainer = Color(0xFFFFDCC1),
            background = Color(0xFF160E10), onBackground = Color(0xFFEFDFE1),
            surface = Color(0xFF191113), onSurface = Color(0xFFEFDFE1),
            surfaceVariant = Color(0xFF2A2124), onSurfaceVariant = Color(0xFFD5C2C6),
            surfaceContainer = Color(0xFF201618), surfaceContainerLow = Color(0xFF191113),
            surfaceContainerHigh = Color(0xFF261C1F), outline = Color(0xFF9E8C90),
        ),
    )

    private val slate = PaletteSchemes(
        displayName = "Slate",
        light = lightColorScheme(
            primary = Color(0xFF435E6E), onPrimary = Color(0xFFFFFFFF),
            primaryContainer = Color(0xFFC6E1F2), onPrimaryContainer = Color(0xFF0B2430),
            secondary = Color(0xFF5B6670), onSecondary = Color(0xFFFFFFFF),
            secondaryContainer = Color(0xFFDFE5EB), onSecondaryContainer = Color(0xFF171E24),
            tertiary = Color(0xFF6B5F66), onTertiary = Color(0xFFFFFFFF),
            tertiaryContainer = Color(0xFFF4DEE8), onTertiaryContainer = Color(0xFF251A20),
            background = Color(0xFFF9FAFB), onBackground = Color(0xFF1A1C1E),
            surface = Color(0xFFF9FAFB), onSurface = Color(0xFF1A1C1E),
            surfaceVariant = Color(0xFFE0E4E8), onSurfaceVariant = Color(0xFF44474B),
            surfaceContainer = Color(0xFFEDEFF2), surfaceContainerLow = Color(0xFFF3F4F6),
            surfaceContainerHigh = Color(0xFFE7EAED), outline = Color(0xFF74777C),
        ),
        dark = darkColorScheme(
            primary = Color(0xFFA6C8DC), onPrimary = Color(0xFF0B3244),
            primaryContainer = Color(0xFF274A5C), onPrimaryContainer = Color(0xFFC6E1F2),
            secondary = Color(0xFFC2CCD6), onSecondary = Color(0xFF2C3640),
            secondaryContainer = Color(0xFF434D57), onSecondaryContainer = Color(0xFFDFE5EB),
            tertiary = Color(0xFFD7C1CD), onTertiary = Color(0xFF3A2B34),
            tertiaryContainer = Color(0xFF52424B), onTertiaryContainer = Color(0xFFF4DEE8),
            background = Color(0xFF0F1113), onBackground = Color(0xFFE2E4E6),
            surface = Color(0xFF121416), onSurface = Color(0xFFE2E4E6),
            surfaceVariant = Color(0xFF22262A), onSurfaceVariant = Color(0xFFC4C8CC),
            surfaceContainer = Color(0xFF181B1D), surfaceContainerLow = Color(0xFF121416),
            surfaceContainerHigh = Color(0xFF1E2124), outline = Color(0xFF8E9297),
        ),
    )

    fun schemes(palette: ThemePalette): PaletteSchemes = when (palette) {
        ThemePalette.MINT -> mint
        ThemePalette.OCEAN -> ocean
        ThemePalette.EMBER -> ember
        ThemePalette.VIOLET -> violet
        ThemePalette.CITRUS -> citrus
        ThemePalette.ROSE -> rose
        ThemePalette.SLATE -> slate
    }

    val all: List<Pair<ThemePalette, PaletteSchemes>> = ThemePalette.entries.map { it to schemes(it) }
}

@Composable
fun KinetiqTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    palette: ThemePalette = ThemePalette.MINT,
    content: @Composable () -> Unit,
) {
    val p = KinetiqPalettes.schemes(palette)
    val scheme = when (mode) {
        ThemeMode.LIGHT -> p.light
        ThemeMode.DARK -> p.dark
        // AMOLED: pure black surfaces for the Edge 60 Fusion's pOLED panel, palette accents intact.
        ThemeMode.AMOLED -> p.dark.copy(
            surface = Color.Black,
            background = Color.Black,
            surfaceVariant = Color(0xFF121212),
            surfaceContainer = Color(0xFF0A0A0A),
            surfaceContainerLow = Color.Black,
            surfaceContainerHigh = Color(0xFF141414),
        )
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) p.dark else p.light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
