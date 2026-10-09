package com.stormstream.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * StormStream visual identity — deep navy + electric blue accent, not a clone
 * of Hikari's Material 3 purple palette. Designed for dark-first TV/browsing.
 */
private val StormDark = darkColorScheme(
    primary = Color(0xFF60A5FA),      // electric-blue
    onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF0B1220),
    background = Color(0xFF070B15),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
)

private val StormLight = lightColorScheme(
    primary = Color(0xFF1E3A8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B1220),
    secondary = Color(0xFF0284C7),
    background = Color(0xFFF8FAFC),
    surface = Color.White,
)

@Composable
fun StormTheme(
    darkTheme: Boolean = true, // streaming UIs default dark
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) StormDark else StormLight
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}
