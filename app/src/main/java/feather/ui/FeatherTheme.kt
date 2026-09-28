package feather.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/*
 * Palette: a fresh teal for actions, a soft indigo for selections, a warm orange accent to catch the eye,
 * on near-white cool surfaces. Saturated enough to look alive on a Full HD screen, light enough to stay
 * comfortable for long sessions. Every text/background pair below keeps at least 4.5:1 contrast.
 * Change the colours here and the whole app follows.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFF00838F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDF3F7),
    onPrimaryContainer = Color(0xFF00363B),
    secondary = Color(0xFF4F5FC4),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1E5FF),
    onSecondaryContainer = Color(0xFF16205E),
    tertiary = Color(0xFFD9480F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE2D2),
    onTertiaryContainer = Color(0xFF4A1500),
    background = Color(0xFFF5FBFC),
    onBackground = Color(0xFF15282B),
    surface = Color(0xFFF5FBFC),
    onSurface = Color(0xFF15282B),
    surfaceVariant = Color(0xFFE4F2F4),
    onSurfaceVariant = Color(0xFF3B5559),
    surfaceTint = Color(0xFF00838F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF8F9),
    surfaceContainer = Color(0xFFE8F4F6),
    surfaceContainerHigh = Color(0xFFE1F0F2),
    surfaceContainerHighest = Color(0xFFDAEBEE),
    outline = Color(0xFF6F9095),
    outlineVariant = Color(0xFFBFD8DC),
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1),
    onPrimary = Color(0xFF00363B),
    primaryContainer = Color(0xFF004F57),
    onPrimaryContainer = Color(0xFFCDF3F7),
    secondary = Color(0xFFB4BEFF),
    onSecondary = Color(0xFF1B2678),
    secondaryContainer = Color(0xFF343F94),
    onSecondaryContainer = Color(0xFFE1E5FF),
    tertiary = Color(0xFFFFB68F),
    onTertiary = Color(0xFF552100),
    tertiaryContainer = Color(0xFF7A3100),
    onTertiaryContainer = Color(0xFFFFE2D2),
    background = Color(0xFF0E1B1D),
    onBackground = Color(0xFFDDEDEF),
    surface = Color(0xFF0E1B1D),
    onSurface = Color(0xFFDDEDEF),
    surfaceVariant = Color(0xFF1C3134),
    onSurfaceVariant = Color(0xFFB7D0D4),
    surfaceTint = Color(0xFF4DD0E1),
    surfaceContainerLowest = Color(0xFF091416),
    surfaceContainerLow = Color(0xFF122326),
    surfaceContainer = Color(0xFF162A2D),
    surfaceContainerHigh = Color(0xFF1C3134),
    surfaceContainerHighest = Color(0xFF233B3F),
    outline = Color(0xFF7FA0A5),
    outlineVariant = Color(0xFF2F4A4E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Friendlier, rounder corners than the Material default. */
private val FeatherShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun FeatherTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        shapes = FeatherShapes,
        content = content,
    )
}
