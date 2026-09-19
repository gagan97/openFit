package dev.openfit.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Design tokens lifted from Dreeve's own stylesheet so the app chrome matches the pages it embeds
 * (primary #f26722, dark surface #202830, dark text #f0f6fc).
 */
object DreeveColors {
    val Primary = Color(0xFFF26722)
    val PrimarySoftLight = Color(0xFFFFF1E9)
    val PrimarySoftDark = Color(0x33F26722)
    val Dark = Color(0xFF202830)
    val DarkElevated = Color(0xFF2A323B)
    val Light = Color(0xFFFFFFFF)
    val LightElevated = Color(0xFFF6F7F9)
    val TextOnDark = Color(0xFFF0F6FC)
    val TextOnLight = Color(0xFF111827)
    val Muted = Color(0xFF9AA0A6)
    val Grey = Color(0xFFCCCCCC)
    val Green = Color(0xFF9BE15D)
    val Red = Color(0xFFFF6E6E)
}

private val DarkScheme = darkColorScheme(
    primary = DreeveColors.Primary,
    onPrimary = Color.White,
    primaryContainer = DreeveColors.PrimarySoftDark,
    onPrimaryContainer = DreeveColors.TextOnDark,
    background = DreeveColors.Dark,
    onBackground = DreeveColors.TextOnDark,
    surface = DreeveColors.Dark,
    onSurface = DreeveColors.TextOnDark,
    surfaceVariant = DreeveColors.DarkElevated,
    onSurfaceVariant = DreeveColors.Grey,
    outline = DreeveColors.Grey,
    error = DreeveColors.Red,
)

private val LightScheme = lightColorScheme(
    primary = DreeveColors.Primary,
    onPrimary = Color.White,
    primaryContainer = DreeveColors.PrimarySoftLight,
    onPrimaryContainer = DreeveColors.TextOnLight,
    background = DreeveColors.Light,
    onBackground = DreeveColors.TextOnLight,
    surface = DreeveColors.Light,
    onSurface = DreeveColors.TextOnLight,
    surfaceVariant = DreeveColors.LightElevated,
    onSurfaceVariant = Color(0xFF4B5563),
    outline = DreeveColors.Grey,
    error = DreeveColors.Red,
)

@Composable
fun OpenFitTheme(themeOverride: String, content: @Composable () -> Unit) {
    val dark = when (themeOverride) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
}
