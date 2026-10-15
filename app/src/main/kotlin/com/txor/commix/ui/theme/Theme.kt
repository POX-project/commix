package com.txor.commix.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AppleYellow,
    secondary = CinemaGreen,
    tertiary = RecordingRed,
    background = CinemaBlack,
    surface = CinemaCardDark,
    onPrimary = CinemaBlack,
    onSecondary = CinemaBlack,
    onTertiary = CinemaTextLight,
    onBackground = CinemaTextLight,
    onSurface = CinemaTextLight
)

@Composable
fun CommixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
