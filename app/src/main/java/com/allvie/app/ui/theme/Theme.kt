package com.allvie.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
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
    primary = Color(0xFF7D83FF),
    onPrimary = Color(0xFF080A66),
    secondary = Color(0xFF9CB8FF),
    tertiary = Color(0xFF7AE0D7),
    background = Color(0xFF101022),
    surface = Color(0xFF18192D),
    surfaceVariant = Color(0xFF23253C),
    outline = Color(0xFF6E7291)
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
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isDarkTheme -> dynamicDarkColorScheme(context)
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
                    Color(0xFF0F1020),
                    Color(0xFF16173A),
                    Color(0xFF1F2158)
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
