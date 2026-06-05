package com.allvie.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import com.allvie.app.domain.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF1313EC),
    onPrimary = Color.White,
    secondary = Color(0xFF2C58A3),
    tertiary = Color(0xFF00867A),
    background = Color(0xFFF6F6F8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE9EAF2),
    outline = Color(0xFF8B90A7)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF1D28A),
    onPrimary = Color(0xFF2C210A),
    primaryContainer = Color(0xFF6F5A90),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFE5D5BD),
    onSecondary = Color(0xFF2E2618),
    tertiary = Color(0xFFB7D8C9),
    onTertiary = Color(0xFF1F352C),
    background = Color(0xFF0E0D0A),
    onBackground = Color(0xFFFFF4E4),
    surface = Color(0xFF211E19),
    onSurface = Color(0xFFFFF5E7),
    surfaceVariant = Color(0xFF3A332A),
    onSurfaceVariant = Color(0xFFE7D7C0),
    outline = Color(0xFFB09D80)
)

@Composable
fun AllVieTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit
) {
    val isDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isDarkTheme -> dynamicLightColorScheme(context)
        isDarkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

val AppBackgroundBrush: Brush
    @Composable
    get() {
        val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDarkBackground) {
        Brush.linearGradient(
            colors = listOf(
                Color(0xFF090806),
                Color(0xFF11100D),
                Color(0xFF17130E)
            )
        )
        } else {
            Brush.linearGradient(
                colors = listOf(
                    Color(0xFFF8F9FF),
                    Color(0xFFF1F2FF),
                    Color(0xFFF4F6FC)
                )
            )
        }
    }

@Composable
fun allViePanelColor(
    alphaLight: Float = 0.74f,
    alphaDark: Float = 0.92f
): Color {
    val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return if (isDarkBackground) {
        Color(0xFF211E19).copy(alpha = alphaDark.coerceAtLeast(0.96f))
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = alphaLight)
    }
}

@Composable
fun allVieOutlineColor(
    alphaLight: Float = 0.16f,
    alphaDark: Float = 0.28f
): Color {
    val isDarkBackground = MaterialTheme.colorScheme.background.luminance() < 0.5f
    return MaterialTheme.colorScheme.outline.copy(alpha = if (isDarkBackground) alphaDark.coerceAtLeast(0.42f) else alphaLight)
}
