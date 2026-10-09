package com.stormstream.app.providers

import android.content.Context
import android.util.Log
import com.stormstream.app.core.StormError
import com.stormstream.app.core.StormResult
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.InstalledExtension
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.StreamSource
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.aniyomi.AniyomiProvider
import com.stormstream.app.providers.cs3.Cs3Provider
import com.stormstream.app.providers.iptv.IptvProvider
import com.stormstream.app.providers.manga.MangaProvider
import com.stormstream.app.providers.nuvio.NuvioProvider
import com.stormstream.app.providers.scraper.UniversalScraperConfig
import com.stormstream.app.providers.scraper.UniversalScraperProvider
import com.stormstream.app.providers.skystream.SkyStreamProvider
import com.stormstream.app.providers.sora.SoraProvider
import com.stormstream.app.providers.storm.StormNativeProvider
import com.stormstream.app.providers.stremio.StremioAddonProvider
import com.stormstream.app.providers.vega.VegaProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Central registry for every StreamProvider in the app.
 *
 * Responsibilities:
 *  1. Owns the OkHttp [StormHttpClient] shared by all providers.
 *  2. Tracks installed providers and exposes them as a StateFlow.
 *  3. Routes UI calls (home/search/detail/streams) to every enabled provider
 *     concurrently and merges results.
 *  4. Provides install() / uninstall() hooks for each provider type, delegating
 *     to the per-type adapter/manager (StremioAddonProvider.fromUrl etc.).
 */
class ProviderManager private constructor(
    private val context: Context,
    val http: StormHttpClient,
) {
    private val _providers = MutableStateFlow<Map<String, StreamProvider>>(emptyMap())
    val providers: StateFlow<Map<String, StreamProvider>> = _providers.asStateFlow()

    private val lock = Any()

    // ---- Access ----

    fun enabledProviders(): List<StreamProvider> =
        _providers.value.values.filter { it.config.enabled }

    fun get(providerId: String): StreamProvider? = _providers.value[providerId]

    // ---- Registration ----

    fun register(provider: StreamProvider) {
        synchronized(lock) {
            _providers.value = _providers.value + (provider.config.id to provider)
        }
        Log.i(TAG, "Registered provider ${provider.config.id} (${provider.config.type})")
    }

    fun unregister(providerId: String) {
        synchronized(lock) {
            _providers.value = _providers.value - providerId
        }
    }

    // ---- Aggregate queries (fan-out to all enabled providers) ----

    suspend fun allHomeCatalogs(): List<CatalogRef> = coroutineScope {
        enabledProviders().map { p ->
            async { runCatching { p.homeCatalogs() }.getOrDefault(emptyList()) }
        }.awaitAll().flatten()
    }

    suspend fun catalogPage(ref: CatalogRef, page: Int): StormResult<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            val p = get(ref.providerId)
                ?: return@withContext StormResult.Err(StormError.NotInstalled(ref.providerId))
            try {
                StormResult.Ok(p.getCatalog(ref, page))
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed(ref.providerId, e))
            }
        }

    suspend fun searchAll(query: String, page: Int): StormResult<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val results = enabledProviders().map { p ->
                    async {
                        runCatching { p.search(query, page) }
                            .onFailure { Log.w(TAG, "Search failed on ${p.config.id}", it) }
                            .getOrDefault(emptyList())
                    }
                }.awaitAll().flatten()
                StormResult.Ok(results)
            }
        }

    suspend fun getMeta(item: MediaItem): StormResult<MediaItem> =
        withContext(Dispatchers.IO) {
            val p = get(item.providerId)
                ?: return@withContext StormResult.Err(StormError.NotInstalled(item.providerId))
            try {
                StormResult.Ok(p.getMeta(item))
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed(item.providerId, e))
            }
        }

    suspend fun getEpisodes(item: MediaItem): StormResult<List<Episode>?> =
        withContext(Dispatchers.IO) {
            val p = get(item.providerId)
                ?: return@withContext StormResult.Err(StormError.NotInstalled(item.providerId))
            try {
                StormResult.Ok(p.getEpisodes(item))
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed(item.providerId, e))
            }
        }

    suspend fun getStreams(
        item: MediaItem,
        episode: Episode?
    ): StormResult<List<StreamSource>> =
        withContext(Dispatchers.IO) {
            val p = get(item.providerId)
                ?: return@withContext StormResult.Err(StormError.NotInstalled(item.providerId))
            try {
                StormResult.Ok(p.getStreams(item, episode))
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed(item.providerId, e))
            }
        }

    // ---- Install helpers (per provider type) ----

    suspend fun installStremioAddon(manifestUrl: String): StormResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val addon = StremioAddonProvider.fromUrl(http, manifestUrl)
                addon.initialize()
                register(addon)
                StormResult.Ok(Unit)
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed("stremio:$manifestUrl", e))
            }
        }

    suspend fun installUniversalScraper(configJson: String): StormResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val cfg = UniversalScraperConfig.parse(configJson)
                val p = UniversalScraperProvider(http, cfg)
                p.initialize()
                register(p)
                StormResult.Ok(Unit)
            } catch (e: Throwable) {
                StormResult.Err(StormError.Parse("Invalid scraper JSON: ${e.message}", e))
            }
        }

    suspend fun installIptvPlaylist(name: String, url: String): StormResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val p = IptvProvider(http, name, url)
                p.initialize()
                register(p)
                StormResult.Ok(Unit)
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed("iptv:$name", e))
            }
        }

    /** Register an adapter scaffold for a provider type we don't run natively
     *  in the demo build (cs3, vega, skystream, sora, aniyomi, nuvio, manga,
     *  storm native). Each scaffold exposes install() that stashes the config
     *  and returns a descriptive "not yet available in this build" stream list
     *  — the UI can still show the provider as installed and list its items
     *  after catalog refresh; play just shows a friendly message.
     */
    fun installScaffold(type: ProviderType, cfg: ProviderConfig) {
        val p: StreamProvider = when (type) {
            ProviderType.CS3 -> Cs3Provider(cfg)
            ProviderType.VEGA -> VegaProvider(cfg)
            ProviderType.SKYSTREAM -> SkyStreamProvider(cfg)
            ProviderType.SORA -> SoraProvider(cfg)
            ProviderType.ANIYOMI -> AniyomiProvider(cfg)
            ProviderType.NUVIO -> NuvioProvider(cfg)
            ProviderType.MANGA -> MangaProvider(cfg)
            ProviderType.STORM -> StormNativeProvider(cfg)
            // These three are handled by their real install methods above.
            ProviderType.STREMIO,
            ProviderType.UNIVERSAL_SCRAPER,
            ProviderType.IPTV -> return
        }
        register(p)
    }

    /** Bootstrap a few demo/curated sources on first launch so Home is not empty. */
    suspend fun bootstrapDefaults() {
        if (_providers.value.isNotEmpty()) return
        // Stremio's public "Cinemeta" catalog addon is always-on as a seed.
        installStremioAddon("https://v3-cinemeta.strem.io/manifest.json")
        // A demo IPTV playlist (public, free, legal demo streams).
        installIptvPlaylist(
            "IPTV Demo",
            "https://iptv-org.github.io/iptv/index.m3u"
        )
    }

    companion object {
        private const val TAG = "StormProviderMgr"

        @Volatile
        private var instance: ProviderManager? = null

        fun get(context: Context): ProviderManager =
            instance ?: synchronized(this) {
                instance ?: ProviderManager(
                    context.applicationContext,
                    StormHttpClient(context.applicationContext.cacheDir)
                ).also { instance = it }
            }

        /** Access to the shared HTTP client for plugin managers/repo loaders. */
        fun http(context: Context): StormHttpClient = get(context).http
    }

    /** Restore from persisted extension records (called on app start). */
    suspend fun restore(extensions: List<InstalledExtension>) {
        extensions.forEach { ext ->
            when (ext.config.type) {
                ProviderType.STREMIO -> installStremioAddon(ext.config.sourceUrl ?: return@forEach)
                ProviderType.UNIVERSAL_SCRAPER -> {
                    val json = ext.localPath?.let { runCatching { java.io.File(it).readText() }.getOrNull() }
                    if (json != null) installUniversalScraper(json)
                }
                ProviderType.IPTV -> installIptvPlaylist(ext.config.name, ext.config.sourceUrl ?: return@forEach)
                else -> installScaffold(ext.config.type, ext.config)
            }
        }
    }
}
