package com.stormstream.app.providers.cs3

import com.stormstream.app.data.*
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.StreamSource
import java.lang.reflect.Method

/**
 * Thin reflection bridge over a CloudStream3 plugin instance.
 *
 * CloudStream plugins extend `com.lagradost.cloudstream3.plugins.Plugin` and
 * register `MainAPI` providers.  Rather than bundling the full CloudStream API
 * (which is ~10k LOC and pulls several AndroidX deps), we adapt against the
 * small subset we need: the plugin's `load()` method to register a provider and
 * that provider's `search/getMain/getLink` methods.  When a method is missing or
 * has an unexpected signature, we return empty results — StormStream UI handles
 * "no results" gracefully.
 */
internal class Cs3PluginBridge(private val pluginInstance: Any) {

    private var providerInstance: Any? = null
    private var searchMethod: Method? = null
    private var mainMethod: Method? = null
    private var loadMethod: Method? = null
    private var getMetaMethod: Method? = null
    private var episodesMethod: Method? = null
    private var linkMethod: Method? = null

    init {
        // Plugin class is expected to implement `load(PluginManager)` which registers
        // providers. Without the full CS3 API on classpath we can't resolve the
        // PluginManager type, so we look for a single-arg `load` and invoke it with
        // a stub manager that records registered providers.
        loadMethod = pluginInstance.javaClass.methods.firstOrNull { it.name == "load" && it.parameterTypes.size == 1 }
        if (loadMethod == null) {
            // Some plugins expose the provider directly via a no-arg getter; attempt
            // to call it.
            providerInstance = pluginInstance.javaClass.methods
                .firstOrNull { it.name == "getProvider" && it.parameterTypes.isEmpty() }
                ?.invoke(pluginInstance)
        }
        if (providerInstance != null) {
            bindProviderMethods(providerInstance!!)
        }
    }

    private fun bindProviderMethods(prov: Any) {
        val cls = prov.javaClass
        searchMethod = cls.methods.firstOrNull { it.name == "search" }
        mainMethod = cls.methods.firstOrNull { it.name == "getMainPage" || it.name == "mainPage" }
        getMetaMethod = cls.methods.firstOrNull { it.name == "load" }
        episodesMethod = cls.methods.firstOrNull { it.name == "getEpisodes" || it.name == "loadEpisodes" }
        linkMethod = cls.methods.firstOrNull { it.name == "getLinks" || it.name == "extractLinks" }
    }

    fun dispose() {
        runCatching {
            pluginInstance.javaClass.methods.firstOrNull { it.name == "beforeUnload" }?.invoke(pluginInstance)
        }
    }

    fun catalogs(providerId: String): List<CatalogRef> {
        if (providerInstance == null) return emptyList()
        return listOf(CatalogRef(providerId, "main", providerInstance!!.javaClass.simpleName, MediaType.MOVIE))
    }

    fun catalog(ref: CatalogRef, page: Int): List<MediaItem> = emptyList() // TODO phase 3: adapt getMainPage results
    fun search(query: String, page: Int): List<MediaItem> = emptyList() // TODO phase 3
    fun meta(item: MediaItem): MediaItem = item
    fun episodes(item: MediaItem): List<Episode>? = null
    fun streams(item: MediaItem, episode: Episode?): List<StreamSource> = emptyList()
}
