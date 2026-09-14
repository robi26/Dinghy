package ch.steigis.dinghy.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Dinghy's palette, keyed to the launcher icon: indigo hull water, a sky-blue
 * secondary and the mainsail's amber as the accent.
 *
 * Deliberately not `dynamicColorScheme`. Wallpaper-derived colour is the more
 * fashionable choice, but it would leave the app looking unrelated to its own
 * icon and, on a muted wallpaper, produces exactly the washed-out grey-lavender
 * this replaces. `MaterialTheme` with no scheme at all — which is what this used
 * to be — is worse still: that is Material 3's baseline purple, and it is light
 * only, so the app ignored the system's dark mode entirely.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBF1FE),
    onSecondaryContainer = Color(0xFF082F49),
    tertiary = Color(0xFFB45309),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFEF3C7),
    onTertiaryContainer = Color(0xFF451A03),
    background = Color(0xFFFBFBFE),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFBFBFE),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE7E8EE),
    onSurfaceVariant = Color(0xFF44464B),
    outline = Color(0xFF75777F),
    outlineVariant = Color(0xFFC5C6D0),
    // Material 3 draws Card, Surface and friends from the surfaceContainer
    // ramp, not from `surface`. Leaving these unset is what kept the cards a
    // baseline lavender while everything around them had changed.
    surfaceTint = Color(0xFF4F46E5),
    surfaceDim = Color(0xFFDBDBE4),
    surfaceBright = Color(0xFFFBFBFE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5FA),
    surfaceContainer = Color(0xFFEFEFF6),
    surfaceContainerHigh = Color(0xFFE9E9F1),
    surfaceContainerHighest = Color(0xFFE3E3EC),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF1F0F4),
    inversePrimary = Color(0xFFBFC4FF),
    scrim = Color(0xFF000000),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF3730A3),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF075985),
    onSecondaryContainer = Color(0xFFDBF1FE),
    tertiary = Color(0xFFFCD34D),
    onTertiary = Color(0xFF451A03),
    tertiaryContainer = Color(0xFF92400E),
    onTertiaryContainer = Color(0xFFFEF3C7),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44464B),
    onSurfaceVariant = Color(0xFFC5C6CD),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    surfaceTint = Color(0xFFA5B4FC),
    surfaceDim = Color(0xFF121316),
    surfaceBright = Color(0xFF38393E),
    surfaceContainerLowest = Color(0xFF0D0E11),
    surfaceContainerLow = Color(0xFF1A1B1F),
    surfaceContainer = Color(0xFF1E1F24),
    surfaceContainerHigh = Color(0xFF292A2F),
    surfaceContainerHighest = Color(0xFF34353A),
    inverseSurface = Color(0xFFE3E2E6),
    inverseOnSurface = Color(0xFF2F3033),
    inversePrimary = Color(0xFF4F46E5),
    scrim = Color(0xFF000000),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Material 3, stable. Expressive was tried and backed out: `MaterialExpressiveTheme`
 * ships in material3 1.4.0 but is `internal` there, so it is only reachable from the
 * 1.5.0 alphas, which drag in alpha Compose and compileSdk 37. Not a trade worth
 * making for an app that is already on people's phones.
 */
@Composable
fun DinghyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
