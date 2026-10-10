package com.stormstream.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The app's accent palettes (Settings → Appearance → Accent).
 *
 * One accent drives the whole UI: it becomes the Material 3 primary (and its
 * container/tinted variants), so every screen — and the player's accent
 * controls — restyle together from a single choice.
 *
 * Design learned from the Hikari reference app's accent system; the palettes
 * themselves are StormStream's own.
 */
enum class StormAccent(
    val key: String,
    val label: String,
    val start: Color,
    val end: Color,
) {
    BLUE("blue", "Electric Blue", Color(0xFF60A5FA), Color(0xFF2563EB)),
    AMBER("amber", "Amber", Color(0xFFF5C569), Color(0xFFE0A93B)),
    VIOLET("violet", "Violet", Color(0xFF00B4DB), Color(0xFF9D4EDD)),
    CYAN("cyan", "Cyan", Color(0xFF22E1E8), Color(0xFF00A3C4)),
    TEAL("teal", "Teal", Color(0xFF2FE0B0), Color(0xFF0E9E88)),
    GREEN("green", "Green", Color(0xFF7BE86B), Color(0xFF16A34A)),
    RED("red", "Red", Color(0xFFFF6B6B), Color(0xFFD90429)),
    ORANGE("orange", "Orange", Color(0xFFFFB056), Color(0xFFF0590A)),
    PINK("pink", "Pink", Color(0xFFFF7BC5), Color(0xFFE0248A)),
    PURPLE("purple", "Purple", Color(0xFFB98CFF), Color(0xFF7B2CBF)),
    MONO("mono", "Monochrome", Color(0xFFE6E8F0), Color(0xFF8A90A8));

    /** The solid accent — buttons, selected tabs, icons, focus rings. */
    val mid: Color get() = lerp(start, end, 0.5f)

    /** A darker shade of the accent, for pressed/inset surfaces. */
    val soft: Color get() = lerp(end, Color.Black, 0.45f)

    companion object {
        val DEFAULT = BLUE

        fun fromKey(key: String?): StormAccent =
            entries.firstOrNull { it.key == key?.lowercase()?.trim() } ?: DEFAULT
    }
}
