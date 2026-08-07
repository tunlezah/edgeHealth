package au.mark.kinetiq.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import au.mark.kinetiq.data.repo.ThemeMode

private val Mint = Color(0xFF38E0A6)
private val MintDim = Color(0xFF1F8F6B)
private val Sky = Color(0xFF5ED4F0)
private val Coral = Color(0xFFFF8A5C)
private val InkDark = Color(0xFF0E1B2C)

private val LightScheme = lightColorScheme(
    primary = MintDim,
    onPrimary = Color.White,
    secondary = Color(0xFF1E7FA3),
    tertiary = Color(0xFFB35430),
    surface = Color(0xFFF7FAF8),
    background = Color(0xFFF7FAF8),
    surfaceVariant = Color(0xFFE2EAE5),
    primaryContainer = Color(0xFFBFF2DF),
    onPrimaryContainer = Color(0xFF0A4B36),
)

private val DarkScheme = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00382A),
    secondary = Sky,
    tertiary = Coral,
    surface = Color(0xFF101A16),
    background = Color(0xFF0C1411),
    surfaceVariant = Color(0xFF1D2B25),
    primaryContainer = Color(0xFF0F5C43),
    onPrimaryContainer = Color(0xFFBFF2DF),
)

/** AMOLED: pure black surfaces for the Edge 60 Fusion's pOLED panel. */
private val AmoledScheme = DarkScheme.copy(
    surface = Color.Black,
    background = Color.Black,
    surfaceVariant = Color(0xFF121212),
    surfaceContainer = Color(0xFF0A0A0A),
    surfaceContainerLow = Color.Black,
    surfaceContainerHigh = Color(0xFF141414),
)

@Composable
fun KinetiqTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val scheme = when (mode) {
        ThemeMode.LIGHT -> LightScheme
        ThemeMode.DARK -> DarkScheme
        ThemeMode.AMOLED -> AmoledScheme
        ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkScheme else LightScheme
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
