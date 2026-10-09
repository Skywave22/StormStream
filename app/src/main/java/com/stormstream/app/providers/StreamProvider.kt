package com.stormstream.app.providers

import com.stormstream.app.core.StormResult
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.StreamSource

/**
 * The single contract every provider backend in StormStream must satisfy.
 *
 * Provider-specific adapters (StremioAddonProvider, UniversalScraperProvider,
 * Cs3Provider, VegaProvider, SkyStreamProvider, SoraProvider, AniyomiProvider,
 * NuvioProvider, IptvProvider, MangaProvider, StormNativeProvider) implement
 * this interface. The UI, database and player talk ONLY to this interface —
 * they never know which backend a MediaItem came from.
 *
 * All methods are suspending and may throw. [ProviderManager] catches and wraps
 * everything into [StormResult] so callers get uniform error handling.
 */
interface StreamProvider {

    val config: ProviderConfig

    // ---------- Lifecycle ----------

    /** Called once after the provider is loaded/instantiated. */
    suspend fun initialize() {}

    /** Free native/JS resources. */
    suspend fun shutdown() {}

    /** Drop any in-memory catalog/search caches so the next fetch is fresh. */
    fun invalidateCache() {}

    // ---------- Capabilities ----------

    /** True if this provider has no catalogs and only answers search queries. */
    val searchOnly: Boolean get() = false

    /** True when this provider supports per-provider settings UI. */
    val hasSettings: Boolean get() = false

    /** Whether this EXTENSION is itself adult/NSFW (asked once at install). */
    fun isAdultExtension(): Boolean? = config.adult

    // ---------- Discovery ----------

    /** All catalogs the provider exposes. */
    suspend fun catalogs(): List<CatalogRef>

    /** Subset of [catalogs] rendered on the Home screen. */
    suspend fun homeCatalogs(): List<CatalogRef> = catalogs()

    /** One page from a catalog. Providers should paginate where possible. */
    suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem>

    /** Search results. */
    suspend fun search(query: String, page: Int): List<MediaItem>

    // ---------- Detail / playback ----------

    /** Warm-up hook: pre-fetch metadata/cache so getMeta is fast. */
    suspend fun warmDetail(item: MediaItem) {}

    /** Full metadata for an item (populates description, rating, genres, etc). */
    suspend fun getMeta(item: MediaItem): MediaItem = item

    /**
     * Episode list for a series/anime. Null return means this item has no
     * episodes (i.e. it is a movie / a single stream).
     */
    suspend fun getEpisodes(item: MediaItem): List<Episode>? = null

    /**
     * Resolve playable streams for an item. Pass [episode] for series/anime;
     * null for movies.
     */
    suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource>
}
