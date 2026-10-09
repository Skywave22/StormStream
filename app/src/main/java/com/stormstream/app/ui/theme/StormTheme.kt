package com.stormstream.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF3FA9FF),
    onPrimary = Color(0xFF001E31),
    primaryContainer = Color(0xFF004C75),
    onPrimaryContainer = Color(0xFFCAE6FF),
    secondary = Color(0xFFB4C8DA),
    onSecondary = Color(0xFF1F323F),
    secondaryContainer = Color(0xFF354957),
    onSecondaryContainer = Color(0xFFD0E4F7),
    tertiary = Color(0xFFFFB3AC),
    onTertiary = Color(0xFF5F1315),
    tertiaryContainer = Color(0xFF7E2A29),
    onTertiaryContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A0F14),
    onBackground = Color(0xFFE1E2E5),
    surface = Color(0xFF11161C),
    onSurface = Color(0xFFE1E2E5),
    surfaceVariant = Color(0xFF1B232C),
    onSurfaceVariant = Color(0xFFBFC8D2),
    surfaceTint = Color(0xFF3FA9FF),
    outline = Color(0xFF3A4550),
    outlineVariant = Color(0xFF2A3540),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    inverseOnSurface = Color(0xFF11161C),
    inverseSurface = Color(0xFFE1E2E5),
    inversePrimary = Color(0xFF006599),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006599),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCAE6FF),
    onPrimaryContainer = Color(0xFF001E31),
    secondary = Color(0xFF50606E),
    background = Color(0xFFFAFCFF),
    surface = Color(0xFFF5F9FF),
)

@Composable
fun StormTheme(
    darkTheme: Boolean = true, // dark-by-default cinematic UI
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = StormShapes,
        content = content
    )
}

private val StormShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
