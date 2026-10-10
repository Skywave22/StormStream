package com.stormstream.app.providers

import android.content.Context
import android.util.Log
import com.stormstream.app.core.StormError
import com.stormstream.app.core.StormResult
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.ExtensionStore
import com.stormstream.app.data.InstalledExtension
import com.stormstream.app.data.JsPluginManifest
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.StreamSource
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.iptv.IptvProvider
import com.stormstream.app.providers.js.JsProvider
import com.stormstream.app.providers.js.slugify
import com.stormstream.app.providers.scraper.UniversalScraperConfig
import com.stormstream.app.providers.scraper.UniversalScraperProvider
import com.stormstream.app.providers.stremio.StremioAddonProvider
import com.stormstream.app.util.StormJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Central registry for every [StreamProvider] in the app.
 *
 * Responsibilities:
 *  1. Owns the shared [StormHttpClient].
 *  2. Tracks installed providers and exposes them as a [StateFlow].
 *  3. Routes UI calls (home/search/detail/streams) to providers and merges results.
 *  4. Installs / uninstalls / enables / disables extensions and **persists**
 *     every change to [ExtensionStore] so installs survive restarts.
 *  5. Restores providers from the store on app start and bootstraps two
 *     curated sources on first run.
 */
class ProviderManager private constructor(
    private val context: Context,
    val http: StormHttpClient,
    private val extensionStore: ExtensionStore,
) {
    private val _providers = MutableStateFlow<Map<String, StreamProvider>>(emptyMap())
    val providers: StateFlow<Map<String, StreamProvider>> = _providers.asStateFlow()

    /** providerId → initialization error message (shown in the Extensions UI). */
    private val _providerErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val providerErrors: StateFlow<Map<String, String>> = _providerErrors.asStateFlow()

    /** providerIds the user has switched off (persisted in the extension record). */
    private val _disabledIds = MutableStateFlow<Set<String>>(emptySet())
    val disabledIds: StateFlow<Set<String>> = _disabledIds.asStateFlow()

    private val lock = Any()

    // ---- access ----

    fun enabledProviders(): List<StreamProvider> =
        _providers.value.values.filter { it.config.enabled && it.config.id !in _disabledIds.value }

    fun get(providerId: String): StreamProvider? = _providers.value[providerId]

    // ---- registration ----

    private fun register(provider: StreamProvider) {
        synchronized(lock) {
            _providers.value = _providers.value + (provider.config.id to provider)
        }
        _providerErrors.value = _providerErrors.value - provider.config.id
        Log.i(TAG, "Registered provider ${provider.config.id} (${provider.config.type})")
    }

    private suspend fun unregister(providerId: String) {
        val p = synchronized(lock) {
            val removed = _providers.value[providerId]
            _providers.value = _providers.value - providerId
            removed
        }
        runCatching { p?.shutdown() }
    }

    private fun setError(providerId: String, message: String) {
        _providerErrors.value = _providerErrors.value + (providerId to message)
    }

    // ---- install (per type) ----

    suspend fun installStremioAddon(manifestUrl: String): StormResult<ProviderConfig> =
        withContext(Dispatchers.IO) {
            try {
                val addon = StremioAddonProvider.fromUrl(http, manifestUrl.trim())
                addon.initialize()
                register(addon)
                extensionStore.upsertExtension(InstalledExtension(config = addon.config))
                StormResult.Ok(addon.config)
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed("stremio:$manifestUrl", e))
            }
        }

    suspend fun installScraperConfig(configJson: String): StormResult<ProviderConfig> =
        withContext(Dispatchers.IO) {
            try {
                val cfg = UniversalScraperConfig.parse(configJson)
                val provider = UniversalScraperProvider(http, cfg)
                provider.initialize()
                register(provider)
                // Persist the config to disk so it survives restarts.
                val file = File(pluginDir(), "scraper-${slugify(cfg.name)}.json")
                file.parentFile?.mkdirs()
                file.writeText(configJson)
                extensionStore.upsertExtension(
                    InstalledExtension(
                        config = provider.config,
                        localPath = file.absolutePath,
                    )
                )
                StormResult.Ok(provider.config)
            } catch (e: Throwable) {
                StormResult.Err(StormError.Parse("Invalid scraper config: ${e.message}", e))
            }
        }

    suspend fun installIptvPlaylist(name: String, url: String): StormResult<ProviderConfig> =
        withContext(Dispatchers.IO) {
            try {
                val provider = IptvProvider(http, name, url.trim())
                provider.initialize()
                register(provider)
                extensionStore.upsertExtension(InstalledExtension(config = provider.config))
                StormResult.Ok(provider.config)
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed("iptv:$name", e))
            }
        }

    /**
     * Install a JavaScript plugin from a URL. The URL may point to:
     *  - a single `.js` file (StormJS or Vega-style), or
     *  - a plugin manifest JSON (`storm.plugin.json`) describing one or more
     *    module files (relative URLs are resolved against the manifest URL).
     */
    suspend fun installJsPlugin(
        url: String,
        fallbackName: String? = null,
    ): StormResult<ProviderConfig> = withContext(Dispatchers.IO) {
        try {
            val trimmed = url.trim()
            val text = http.get(trimmed)
            val looksLikeManifest = trimmed.endsWith(".json", ignoreCase = true) ||
                text.trimStart().startsWith("{")

            if (looksLikeManifest) {
                val parsed = StormJson.decodeFromString<JsPluginManifest>(text)
                installJsPluginFiles(
                    name = fallbackName ?: parsed.name,
                    version = parsed.version,
                    description = parsed.description,
                    icon = parsed.icon,
                    adult = parsed.adult,
                    files = parsed.files.ifEmpty {
                        if (parsed.main.isNullOrBlank()) emptyMap() else mapOf("main" to parsed.main)
                    },
                    baseUrl = trimmed,
                )
            } else {
                installJsPluginFiles(
                    name = fallbackName ?: "JS Plugin",
                    files = mapOf("main" to trimmed),
                    baseUrl = trimmed,
                    inlineSource = text,
                )
            }
        } catch (e: StormError) {
            StormResult.Err(e)
        } catch (e: Throwable) {
            StormResult.Err(StormError.ProviderCrashed("js:$url", e))
        }
    }

    /**
     * Install a JavaScript plugin from a set of module files.
     *
     * @param files    module name → file URL (absolute, or relative to [baseUrl]).
     * @param baseUrl  base URL used to resolve relative file URLs.
     * @param inlineSource when set, this source is written as `main.js` without
     *                     downloading (used when the caller already has the file).
     */
    suspend fun installJsPluginFiles(
        name: String,
        version: String? = null,
        description: String? = null,
        icon: String? = null,
        adult: Boolean = false,
        files: Map<String, String>,
        baseUrl: String,
        inlineSource: String? = null,
    ): StormResult<ProviderConfig> = withContext(Dispatchers.IO) {
        try {
            val dir = File(pluginDir(), "js-" + slugify(name))
            dir.mkdirs()

            if (inlineSource != null) {
                File(dir, "main.js").writeText(inlineSource)
            } else {
                for ((module, fileUrl) in files) {
                    val target = File(dir, "$module.js")
                    http.download(http.resolve(baseUrl, fileUrl), target)
                }
            }

            val manifest = JsPluginManifest(
                name = name,
                version = version,
                description = description,
                icon = icon,
                adult = adult,
                main = "main",
            )
            File(dir, "manifest.json").writeText(StormJson.encodeToString(manifest))

            val provider = JsProvider.fromDir(context, http, dir, name)
            provider.initialize()
            register(provider)
            extensionStore.upsertExtension(JsProvider.extensionRecord(provider))
            StormResult.Ok(provider.config)
        } catch (e: StormError) {
            StormResult.Err(e)
        } catch (e: Throwable) {
            StormResult.Err(StormError.ProviderCrashed("js:$name", e))
        }
    }

    // ---- uninstall / enable ----

    suspend fun uninstall(providerId: String) {
        val ext = extensionStore.extensionsSnapshot().firstOrNull { it.config.id == providerId }
        unregister(providerId)
        extensionStore.removeExtension(providerId)
        if (ext?.localPath != null) {
            runCatching {
                val f = File(ext.localPath)
                if (f.isDirectory) f.deleteRecursively() else f.delete()
            }
        }
    }

    suspend fun setEnabled(providerId: String, enabled: Boolean) {
        _disabledIds.value =
            if (enabled) _disabledIds.value - providerId
            else _disabledIds.value + providerId
        // Persist the toggle in the extension record.
        val ext = extensionStore.extensionsSnapshot().firstOrNull { it.config.id == providerId }
        if (ext != null) {
            extensionStore.upsertExtension(ext.copy(config = ext.config.copy(enabled = enabled)))
        }
    }

    // ---- restore / bootstrap ----

    /** Re-register every persisted extension. Called once on app start. */
    suspend fun restore() = coroutineScope {
        val extensions = extensionStore.extensionsSnapshot()
        val disabled = extensions.filter { !it.config.enabled }.map { it.config.id }.toSet()
        _disabledIds.value = disabled
        // Restore concurrently: several extensions do network I/O on init.
        extensions.map { ext ->
            async {
                val r = restoreOne(ext)
                if (r is StormResult.Err) {
                    setError(ext.config.id, r.error.message)
                    Log.w(TAG, "Failed to restore ${ext.config.id}: ${r.error.message}")
                }
            }
        }.awaitAll()
    }

    private suspend fun restoreOne(ext: InstalledExtension): StormResult<ProviderConfig> =
        withContext(Dispatchers.IO) {
            try {
                when (ext.config.type) {
                    ProviderType.STREMIO -> {
                        val url = ext.config.sourceUrl
                            ?: return@withContext StormResult.Err(StormError.Unsupported("Missing addon URL"))
                        installStremioAddon(url)
                    }
                    ProviderType.SCRAPER -> {
                        val json = ext.localPath?.let {
                            runCatching { File(it).readText() }.getOrNull()
                        } ?: return@withContext StormResult.Err(
                            StormError.Unsupported("Missing scraper config file")
                        )
                        installScraperConfig(json)
                    }
                    ProviderType.IPTV -> {
                        val url = ext.config.sourceUrl
                            ?: return@withContext StormResult.Err(StormError.Unsupported("Missing playlist URL"))
                        installIptvPlaylist(ext.config.name, url)
                    }
                    ProviderType.JS -> {
                        val dir = ext.localPath?.let(::File)
                            ?: return@withContext StormResult.Err(
                                StormError.Unsupported("Missing plugin directory")
                            )
                        if (!dir.exists()) {
                            return@withContext StormResult.Err(
                                StormError.Unsupported("Plugin files were deleted")
                            )
                        }
                        val provider = JsProvider.fromDir(context, http, dir, ext.config.name)
                        provider.initialize()
                        register(provider)
                        StormResult.Ok(provider.config)
                    }
                }
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed(ext.config.id, e))
            }
        }

    /**
     * Install two curated sources on first run so Home is never empty.
     * Guarded by a persisted flag so the defaults do NOT come back after the
     * user deliberately uninstalls everything.
     */
    suspend fun bootstrapDefaultsIfNeeded() {
        if (extensionStore.defaultsInstalled.first()) return
        extensionStore.markDefaultsInstalled()
        installStremioAddon("https://v3-cinemeta.strem.io/manifest.json")
        installIptvPlaylist("IPTV Demo", "https://iptv-org.github.io/iptv/index.m3u")
    }

    // ---- aggregate queries (fan-out to all enabled providers) ----

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

    suspend fun getStreams(item: MediaItem, episode: Episode?): StormResult<List<StreamSource>> =
        withContext(Dispatchers.IO) {
            val p = get(item.providerId)
                ?: return@withContext StormResult.Err(StormError.NotInstalled(item.providerId))
            try {
                StormResult.Ok(p.getStreams(item, episode))
            } catch (e: Throwable) {
                StormResult.Err(StormError.ProviderCrashed(item.providerId, e))
            }
        }

    // ---- helpers ----

    private fun pluginDir(): File = File(context.filesDir, "plugins")

    companion object {
        private const val TAG = "StormProviderMgr"

        @Volatile
        private var instance: ProviderManager? = null

        fun get(context: Context): ProviderManager =
            instance ?: synchronized(this) {
                instance ?: ProviderManager(
                    context.applicationContext,
                    StormHttpClient(File(context.applicationContext.cacheDir, "storm")),
                    ExtensionStore.get(context.applicationContext),
                ).also { instance = it }
            }

        fun http(context: Context): StormHttpClient = get(context).http
    }
}
