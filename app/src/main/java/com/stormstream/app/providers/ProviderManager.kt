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
import com.stormstream.app.data.StormStore
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.iptv.IptvProvider
import com.stormstream.app.providers.scraper.UniversalScraperConfig
import com.stormstream.app.providers.scraper.UniversalScraperProvider
import com.stormstream.app.providers.stremio.StremioAddonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Central registry for every StreamProvider in the app.
 *
 * Phase 1 improvements:
 *  - Concurrency: fan-out to all providers in parallel with per-call timeouts,
 *    so a slow addon cannot freeze the home screen.
 *  - Persistence: installed extensions are written to DataStore on every
 *    install/uninstall and restored on cold start.
 *  - One-time bootstrap: default sources are installed only on first launch
 *    and use lighter-weight playlists (not the 20,000-channel iptv-org index).
 *  - Enable/disable/uninstall flows with state updates.
 */
class ProviderManager private constructor(
    internal val ctx: Context,
    val http: StormHttpClient,
    private val store: StormStore,
) {
    val context: android.content.Context get() = ctx
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _providers = MutableStateFlow<Map<String, StreamProvider>>(emptyMap())
    val providers: StateFlow<Map<String, StreamProvider>> = _providers.asStateFlow()

    private val _extensions = mutableListOf<InstalledExtension>()

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
            _extensions.removeAll { it.config.id == providerId }
        }
        appScope.launch { persist() }
    }

    fun setEnabled(providerId: String, enabled: Boolean) {
        val p = _providers.value[providerId] ?: return
        val newCfg = p.config.copy(enabled = enabled)
        // Rebuild provider map with updated config if possible via re-registration.
        synchronized(lock) {
            // Provider implementations keep their own config val — for simple enable flag
            // we store a wrapper view in the map via a ConfigOverrideProvider below.
            val wrapped = ConfigOverrideProvider(p, newCfg)
            _providers.value = _providers.value + (providerId to wrapped)
            val extIndex = _extensions.indexOfFirst { it.config.id == providerId }
            if (extIndex >= 0) {
                _extensions[extIndex] = _extensions[extIndex].copy(config = newCfg)
            }
        }
        appScope.launch { persist() }
    }

    // ---- Aggregate queries (fan-out to all enabled providers) ----

    suspend fun allHomeCatalogs(): List<CatalogRef> = coroutineScope {
        val results = enabledProviders().map { p ->
            async {
                withTimeoutOrNull(CATALOG_TIMEOUT_MS) {
                    runCatching { p.homeCatalogs() }.getOrDefault(emptyList())
                } ?: emptyList<CatalogRef>().also {
                    Log.w(TAG, "homeCatalogs timed out for ${p.config.id}")
                }
            }
        }.awaitAll().flatten()
        results
    }

    suspend fun catalogPage(ref: CatalogRef, page: Int): StormResult<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            val p = get(ref.providerId)
                ?: return@withContext StormResult.Err(StormError.NotInstalled(ref.providerId))
            val r = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                runCatching { p.getCatalog(ref, page) }
            }
            when {
                r == null -> StormResult.Err(StormError.Timeout("${p.config.id} took too long"))
                r.isFailure -> StormResult.Err(
                    StormError.ProviderCrashed(ref.providerId, r.exceptionOrNull()
                        ?: RuntimeException("Unknown"))
                )
                else -> StormResult.Ok(r.getOrThrow())
            }
        }

    suspend fun searchAll(query: String, page: Int): StormResult<List<MediaItem>> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val results = enabledProviders().map { p ->
                    async {
                        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                            runCatching { p.search(query, page) }
                                .onFailure { Log.w(TAG, "Search failed on ${p.config.id}", it) }
                                .getOrDefault(emptyList())
                        } ?: emptyList<MediaItem>().also {
                            Log.w(TAG, "search timed out for ${p.config.id}")
                        }
                    }
                }.awaitAll().flatten()
                StormResult.Ok(results)
            }
        }

    suspend fun getMeta(item: MediaItem): StormResult<MediaItem> =
        withContext(Dispatchers.IO) {
            val p = get(item.providerId)
                ?: return@withContext StormResult.Err(StormError.NotInstalled(item.providerId))
            val r = withTimeoutOrNull(META_TIMEOUT_MS) {
                runCatching { p.getMeta(item) }
            }
            when {
                r == null -> StormResult.Ok(item) // meta is best-effort; fall back to item
                r.isFailure -> StormResult.Err(
                    StormError.ProviderCrashed(item.providerId, r.exceptionOrNull()
                        ?: RuntimeException("Unknown"))
                )
                else -> StormResult.Ok(r.getOrThrow())
            }
        }

    suspend fun getEpisodes(item: MediaItem): StormResult<List<Episode>?> =
        withContext(Dispatchers.IO) {
            val p = get(item.providerId)
                ?: return@withContext StormResult.Err(StormError.NotInstalled(item.providerId))
            val r = withTimeoutOrNull(META_TIMEOUT_MS) {
                runCatching { p.getEpisodes(item) }
            }
            when {
                r == null -> StormResult.Ok(null)
                r.isFailure -> StormResult.Err(
                    StormError.ProviderCrashed(item.providerId, r.exceptionOrNull()
                        ?: RuntimeException("Unknown"))
                )
                else -> StormResult.Ok(r.getOrThrow())
            }
        }

    suspend fun getStreams(
        item: MediaItem,
        episode: Episode?
    ): StormResult<List<StreamSource>> =
        withContext(Dispatchers.IO) {
            val p = get(item.providerId)
                ?: return@withContext StormResult.Err(StormError.NotInstalled(item.providerId))
            val r = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                runCatching { p.getStreams(item, episode) }
            }
            when {
                r == null -> StormResult.Err(StormError.Timeout("Stream resolution timed out"))
                r.isFailure -> StormResult.Err(
                    StormError.ProviderCrashed(item.providerId, r.exceptionOrNull()
                        ?: RuntimeException("Unknown"))
                )
                else -> StormResult.Ok(r.getOrThrow())
            }
        }

    // ---- Install helpers (per provider type) ----

    suspend fun installStremioAddon(manifestUrl: String): StormResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val addon = StremioAddonProvider.fromUrl(http, manifestUrl)
                addon.initialize()
                register(addon)
                recordExtension(InstalledExtension(
                    config = addon.config,
                    sourceUrl = manifestUrl,
                ))
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
                recordExtension(InstalledExtension(
                    config = p.config,
                    inlineConfig = configJson,
                ))
                StormResult.Ok(Unit)
            } catch (e: Throwable) {
                StormResult.Err(StormError.Parse("Invalid scraper JSON: ${e.message}", e))
            }
        }

    suspend fun installIptvPlaylist(name: String, url: String): StormResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val p = IptvProvider(http, name, url)
                // initialize() parses the M3U; guard with timeout so a huge playlist
                // doesn't ANR. IptvProvider itself caps its parse work lazily.
                withTimeoutOrNull(IPT_INIT_TIMEOUT_MS) { p.initialize() }
                    ?: return@withContext StormResult.Err(
                        StormError.Timeout("Playlist too large — timed out loading $name"))
                register(p)
                recordExtension(InstalledExtension(
                    config = p.config,
                    sourceUrl = url,
                ))
                StormResult.Ok(Unit)
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed("iptv:$name", e))
            }
        }

    /** Register a scaffold adapter for a provider type that doesn't have a
     *  native runtime yet (cs3, vega, skystream, sora, aniyomi, nuvio, manga, storm).
     *  These providers will register in the UI but return empty streams.
     */
    fun installScaffold(type: ProviderType, cfg: ProviderConfig) {
        // For scaffold types that don't yet have real loaders (skystream/sora/aniyomi/
        // nuvio/manga/storm), register a no-op stub so the user sees the extension
        // in the Installed list. CS3 and VEGA have real loaders reachable via the
        // repo manager's download/install flow; scaffold here still creates a stub
        // if invoked (e.g. from a manually-added source).
        val p: StreamProvider = ScaffoldProvider(cfg)
        register(p)
        recordExtension(InstalledExtension(config = cfg, sourceUrl = cfg.baseUrl))
    }

    // ---- Bootstrap & restore ----

    /** Called once at app startup. Restores persisted extensions, then seeds
     *  lightweight defaults on first ever launch. */
    suspend fun initializeOnStart() {
        val saved = store.loadExtensionsOnce()
        if (saved.isNotEmpty()) {
            restore(saved)
        }
        if (!store.isBootstrapped()) {
            bootstrapDefaults()
            store.markBootstrapped()
        }
    }

    /** Lightweight bootstrap — uses smaller playlists so first launch is fast. */
    suspend fun bootstrapDefaults() {
        if (_providers.value.isNotEmpty()) return
        // Stremio Cinemeta (movie+series catalog) — small, fast, always useful.
        installStremioAddon("https://v3-cinemeta.strem.io/manifest.json")
        // A small categorized IPTV list instead of the 20,000-channel iptv-org index.
        // This list is under 100 channels and includes news + entertainment.
        installIptvPlaylist(
            "IPTV Starter",
            "https://iptv-org.github.io/iptv/categories/news.m3u"
        )
    }

    // ---- Persistence helpers ----

    private fun recordExtension(ext: InstalledExtension) {
        synchronized(lock) {
            _extensions.removeAll { it.config.id == ext.config.id }
            _extensions.add(ext)
        }
        appScope.launch { persist() }
    }

    private suspend fun persist() {
        val snapshot = synchronized(lock) { _extensions.toList() }
        store.saveExtensions(snapshot)
    }

    suspend fun restore(extensions: List<InstalledExtension>) {
        extensions.forEach { ext ->
            if (_providers.value.containsKey(ext.config.id)) return@forEach
            when (ext.config.type) {
                ProviderType.STREMIO -> {
                    val url = ext.sourceUrl ?: return@forEach
                    runCatching { installStremioAddon(url) }
                        .onFailure { Log.w(TAG, "Failed to restore stremio addon $url", it) }
                }
                ProviderType.UNIVERSAL_SCRAPER -> {
                    val json = ext.inlineConfig
                        ?: ext.localPath?.let { runCatching { File(it).readText() }.getOrNull() }
                    if (json != null) {
                        runCatching { installUniversalScraper(json) }
                            .onFailure { Log.w(TAG, "Failed to restore scraper", it) }
                    }
                }
                ProviderType.IPTV -> {
                    val url = ext.sourceUrl ?: return@forEach
                    runCatching { installIptvPlaylist(ext.config.name, url) }
                        .onFailure { Log.w(TAG, "Failed to restore IPTV ${ext.config.name}", it) }
                }
                else -> installScaffold(ext.config.type, ext.config)
            }
        }
    }

    companion object {
        private const val TAG = "StormProviderMgr"
        private const val CATALOG_TIMEOUT_MS = 8_000L
        private const val PAGE_TIMEOUT_MS = 12_000L
        private const val SEARCH_TIMEOUT_MS = 10_000L
        private const val META_TIMEOUT_MS = 10_000L
        private const val STREAM_TIMEOUT_MS = 15_000L
        private const val IPT_INIT_TIMEOUT_MS = 20_000L

        @Volatile
        private var instance: ProviderManager? = null

        fun get(context: Context): ProviderManager =
            instance ?: synchronized(this) {
                instance ?: ProviderManager(
                    context.applicationContext,
                    StormHttpClient(context.applicationContext.cacheDir),
                    StormStore(context.applicationContext)
                ).also { instance = it }
            }

        fun http(context: Context): StormHttpClient = get(context).http
    }
}

/**
 * Wraps an existing StreamProvider with a new ProviderConfig (e.g. to change
 * enabled state). Calls are delegated directly.
 */
private class ConfigOverrideProvider(
    private val inner: StreamProvider,
    override val config: ProviderConfig,
) : StreamProvider by inner
