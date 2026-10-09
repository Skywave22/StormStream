package com.stormstream.app.ui.navigation

/** Destinations for the bottom nav / navigation graph. */
sealed class StormScreen(val route: String) {
    data object Home : StormScreen("home")
    data object Search : StormScreen("search")
    data object Extensions : StormScreen("extensions")
    data object Settings : StormScreen("settings")
    data object Detail : StormScreen("detail/{itemId}") {
        fun create(itemId: String) = "detail/$itemId"
        const val ARG_ITEM_ID = "itemId"
    }
    data object Player : StormScreen("player/{itemId}/{episodeId}") {
        fun create(itemId: String, episodeId: String? = "null") = "player/$itemId/$episodeId"
    }
}
