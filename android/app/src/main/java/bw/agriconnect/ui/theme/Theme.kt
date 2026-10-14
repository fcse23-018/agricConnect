package bw.agriconnect.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = AgriColors.ForestGreen,
    onPrimary = AgriColors.PureWhite,
    primaryContainer = AgriColors.GreenContainer,
    onPrimaryContainer = AgriColors.DeepGreen,

    secondary = AgriColors.SoilBrown,
    onSecondary = AgriColors.PureWhite,
    secondaryContainer = AgriColors.BrownContainer,
    onSecondaryContainer = AgriColors.DarkBrown,

    tertiary = AgriColors.AgriGreen,
    onTertiary = AgriColors.PureWhite,

    background = AgriColors.WarmWhite,
    onBackground = AgriColors.TextMain,
    surface = AgriColors.PureWhite,
    onSurface = AgriColors.TextMain,
    surfaceVariant = AgriColors.WarmWhite,
    onSurfaceVariant = AgriColors.TextSecondary,

    outline = AgriColors.Border,
    outlineVariant = AgriColors.Border,

    error = AgriColors.ErrorRed,
    onError = AgriColors.PureWhite,
    errorContainer = AgriColors.ErrorContainer,
    onErrorContainer = AgriColors.ErrorRed,
)

private val DarkColors = darkColorScheme(
    primary = AgriColors.AgriGreen,
    onPrimary = AgriColors.PureWhite,
    primaryContainer = AgriColors.DeepGreen,
    onPrimaryContainer = AgriColors.GreenContainer,

    secondary = AgriColors.SoilBrown,
    onSecondary = AgriColors.PureWhite,
    secondaryContainer = AgriColors.DarkBrown,
    onSecondaryContainer = AgriColors.BrownContainer,

    tertiary = AgriColors.AgriGreen,
    onTertiary = AgriColors.PureWhite,

    background = AgriColors.DarkBackground,
    onBackground = AgriColors.DarkTextMain,
    surface = AgriColors.DarkSurface,
    onSurface = AgriColors.DarkTextMain,
    surfaceVariant = AgriColors.DarkSurfaceVariant,
    onSurfaceVariant = AgriColors.DarkTextSecondary,

    outline = AgriColors.DarkBorder,
    outlineVariant = AgriColors.DarkBorder,

    error = AgriColors.ErrorRed,
    onError = AgriColors.PureWhite,
    errorContainer = AgriColors.ErrorRed,
    onErrorContainer = AgriColors.ErrorContainer,
)

/**
 * Root theme for AgriConnect. Applies the design tokens, keeps the status bar
 * legible in both modes, and honours the system dark-mode setting.
 */
@Composable
fun AgriConnectTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AgriTypography,
        shapes = AgriShapes,
        content = content,
    )
}
