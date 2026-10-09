package com.stormstream.app.ui.navigation

/**
 * Destinations for the bottom nav / navigation graph.
 *
 * Phase 1 fix: Detail no longer requires an itemId path param — the ViewModel
 * holds the selected item. The Player screen similarly reads the PlaybackTarget
 * from the ViewModel instead of relying on broken path args.
 */
sealed class StormScreen(val route: String) {
    data object Home : StormScreen("home")
    data object Search : StormScreen("search")
    data object Extensions : StormScreen("extensions")
    data object Settings : StormScreen("settings")
    data object Detail : StormScreen("detail")
    data object Player : StormScreen("player")
}
