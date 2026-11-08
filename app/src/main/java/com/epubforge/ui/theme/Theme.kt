package com.epubforge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Default palette in the spirit of Mihon's "Default" theme (deep blue accents).
private val Light = lightColorScheme(
    primary = Color(0xFF0058CA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E2FF),
    onPrimaryContainer = Color(0xFF001A41),
    secondary = Color(0xFF0058CA),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8E2FF),
    onSecondaryContainer = Color(0xFF001A41),
    tertiary = Color(0xFF006E1B),
    background = Color(0xFFFEFBFF),
    surface = Color(0xFFFEFBFF),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44474F),
    surfaceContainer = Color(0xFFF1F0F7),
    surfaceContainerHigh = Color(0xFFEBEAF1),
    surfaceContainerHighest = Color(0xFFE5E5EC),
    outline = Color(0xFF757780),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAEC6FF),
    onPrimary = Color(0xFF002E69),
    primaryContainer = Color(0xFF004494),
    onPrimaryContainer = Color(0xFFD8E2FF),
    secondary = Color(0xFFAEC6FF),
    onSecondary = Color(0xFF002E69),
    secondaryContainer = Color(0xFF004494),
    onSecondaryContainer = Color(0xFFD8E2FF),
    tertiary = Color(0xFF7ADC77),
    background = Color(0xFF1B1B1F),
    surface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFF44474F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    surfaceContainer = Color(0xFF1F1F23),
    surfaceContainerHigh = Color(0xFF2A2A2E),
    surfaceContainerHighest = Color(0xFF353539),
    outline = Color(0xFF8E9099),
)

@Composable
fun EpubForgeTheme(themeMode: Int, dynamicColor: Boolean, pureBlack: Boolean, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    var scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    if (dark && pureBlack) {
        scheme = scheme.copy(
            background = Color.Black, surface = Color.Black,
            surfaceContainer = Color(0xFF0C0C0C), surfaceContainerLow = Color(0xFF080808),
            surfaceContainerHigh = Color(0xFF131313),
        )
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
