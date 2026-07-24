package app.shunt.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// A deliberately plain, high-contrast palette. The alarming colors are
// reserved for the minimum-exposure warning so it can't be mistaken for the
// ordinary clean-route case.
private val Danger = Color(0xFFB3261E)
private val DangerContainer = Color(0xFFF9DEDC)
private val Safe = Color(0xFF1B6B3A)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F3A5F),
    error = Danger,
    errorContainer = DangerContainer,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9BB8E0),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF8C1D18),
)

/** Green used for the clean (camera-free) outcome, resolved per theme. */
@Composable
fun safeColor(): Color = if (isSystemInDarkTheme()) Color(0xFF6FD08C) else Safe

@Composable
fun ShuntTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
