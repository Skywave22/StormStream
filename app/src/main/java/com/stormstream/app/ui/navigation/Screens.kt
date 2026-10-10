package com.stormstream.app.ui.navigation

import java.net.URLDecoder
import java.net.URLEncoder

/** Destinations for the navigation graph. */
sealed class StormScreen(val route: String) {
    data object Home : StormScreen("home")
    data object Search : StormScreen("search")
    data object Extensions : StormScreen("extensions")
    data object Settings : StormScreen("settings")
    /** Detail state lives in the ViewModel (survives config changes). */
    data object Detail : StormScreen("detail")
    /** Player state lives in MpvPlayerController. */
    data object Player : StormScreen("player")
    /** Full paged grid for one catalog. */
    data object Browse : StormScreen("browse/{providerId}/{catalogId}/{catalogName}") {
        fun create(providerId: String, catalogId: String, catalogName: String): String =
            "browse/${enc(providerId)}/${enc(catalogId)}/${enc(catalogName)}"

        fun decodeProviderId(encoded: String): String = dec(encoded)
        fun decodeCatalogId(encoded: String): String = dec(encoded)
        fun decodeCatalogName(encoded: String): String = dec(encoded)

        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
        private fun dec(s: String): String = URLDecoder.decode(s, "UTF-8")
    }
}
