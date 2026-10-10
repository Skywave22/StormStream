package com.stormstream.app.providers.plugin

import android.util.Log
import com.stormstream.app.core.StormError
import com.stormstream.app.core.StormResult
import com.stormstream.app.data.ExtensionStore
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.RepoIndex
import com.stormstream.app.data.RepoPlugin
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.ProviderManager
import com.stormstream.app.util.StormJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Loads third-party extension repositories and installs plugins from them.
 *
 * A repo URL points at a repo.json of one of these shapes:
 *   1. Hiki/Vega/SkyStream style:
 *      { "name": "...", "plugins": [ { "name", "url", "version", "tvTypes",
 *        "providerType", "files": { "<module>": "<url>" } } ] }
 *   2. CloudStream style:
 *      { "name": "...", "pluginLists": [ "<url to plugins.json>" ] }
 *   3. A plain JSON array of plugin objects.
 *   4. A `github.com/owner/repo` shorthand — rewritten to the repo's
 *      `builds/repo.json` on raw.githubusercontent.com.
 *
 * Loaded repos are kept in memory (with per-repo loading/error state) and the
 * list of added repo URLs is persisted in [ExtensionStore].
 */
class PluginRepoManager(
    private val http: StormHttpClient,
    private val providerManager: ProviderManager,
    private val extensionStore: ExtensionStore,
) {

    sealed interface RepoState {
        data object Loading : RepoState
        data class Ok(val index: RepoIndex) : RepoState
        data class Err(val message: String) : RepoState
    }

    private val _repoStates = MutableStateFlow<Map<String, RepoState>>(emptyMap())
    val repoStates: StateFlow<Map<String, RepoState>> = _repoStates.asStateFlow()

    /** Persisted list of added repo URLs. */
    val repoEntries = extensionStore.repos

    /** Load (or reload) every persisted repo. Called on app start. */
    suspend fun loadAll() {
        extensionStore.reposSnapshot().forEach { entry ->
            refreshRepo(entry.url)
        }
    }

    /** Add a repo URL, persist it, and load it. */
    suspend fun addRepo(rawUrl: String): RepoIndex = withContext(Dispatchers.IO) {
        val url = normalizeUrl(rawUrl)
        extensionStore.addRepo(url)
        loadRepo(url)
    }

    suspend fun refreshRepo(url: String) = withContext(Dispatchers.IO) {
        loadRepo(url)
    }

    suspend fun removeRepo(url: String) {
        extensionStore.removeRepo(url)
        _repoStates.value = _repoStates.value - url
    }

    private suspend fun loadRepo(url: String): RepoIndex {
        _repoStates.value = _repoStates.value + (url to RepoState.Loading)
        return try {
            val index = fetchIndex(url)
            _repoStates.value = _repoStates.value + (url to RepoState.Ok(index))
            index
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to load repo $url", e)
            _repoStates.value = _repoStates.value + (url to RepoState.Err(e.message ?: "Failed to load"))
            throw e
        }
    }

    private suspend fun fetchIndex(url: String): RepoIndex = withContext(Dispatchers.IO) {
        val body = http.get(url)
        // Plain array of plugins?
        if (body.trimStart().startsWith("[")) {
            val plugins = StormJson.decodeFromString<List<RepoPlugin>>(body)
            return@withContext RepoIndex(name = url, plugins = plugins)
        }
        // Nuvio manifest shape: {"name": …, "scrapers": […]} — one plugin entry
        // per scraper, all pointing at the manifest URL.
        runCatching {
            val obj = StormJson.parseToJsonElement(body).jsonObject
            val scrapers = obj["scrapers"]?.jsonArray
            if (scrapers != null && scrapers.isNotEmpty()) {
                val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: url
                return@withContext RepoIndex(
                    name = name,
                    plugins = scrapers.mapNotNull { el ->
                        val o = el as? JsonObject ?: return@mapNotNull null
                        RepoPlugin(
                            name = o["name"]?.jsonPrimitive?.contentOrNull
                                ?: o["id"]?.jsonPrimitive?.contentOrNull
                                ?: "scraper",
                            url = url,
                            providerType = "nuvio",
                            description = o["description"]?.jsonPrimitive?.contentOrNull,
                            icon = o["logo"]?.jsonPrimitive?.contentOrNull,
                        )
                    },
                )
            }
        }
        var idx = StormJson.decodeFromString<RepoIndex>(body)
        // CloudStream-style manifest: follow pluginLists; SkyStream-style:
        // follow nested "repos" URLs as well.
        val extraLists = idx.pluginLists + idx.repos
        if (extraLists.isNotEmpty()) {
            val all = idx.plugins.toMutableList()
            extraLists.forEach { listUrl ->
                runCatching {
                    val listBody = http.get(http.resolve(url, listUrl))
                    val nested = StormJson.decodeFromString<RepoIndex>(listBody)
                    all += nested.plugins
                }.onFailure { Log.w(TAG, "Failed to load plugin list $listUrl", it) }
            }
            idx = idx.copy(plugins = all.distinctBy { it.name + it.url })
        }
        idx
    }

    /**
     * Install a plugin from a repo. Dispatches on the plugin's providerType:
     * Stremio addons, scraper configs, IPTV playlists and JS plugins are all
     * really installed (downloaded, initialized, persisted).
     */
    suspend fun installPlugin(plugin: RepoPlugin): StormResult<Unit> = withContext(Dispatchers.IO) {
        // Untyped plugins are assumed to be JS (StormJS / Vega style) — except
        // CloudStream .cs3/.dex plugins, which this app cannot run.
        val type = ProviderType.fromRepoType(plugin.providerType)
            ?: if (plugin.url.endsWith(".cs3") || plugin.url.endsWith(".dex")) null
               else ProviderType.JS
        when (type) {
            ProviderType.STREMIO ->
                providerManager.installStremioAddon(plugin.url).map { }
            ProviderType.SCRAPER -> {
                val json = runCatching { http.get(plugin.url) }.getOrElse {
                    return@withContext StormResult.Err(
                        StormError.Network("Failed to download scraper config: ${it.message}", it)
                    )
                }
                providerManager.installScraperConfig(json).map { }
            }
            ProviderType.IPTV ->
                providerManager.installIptvPlaylist(plugin.name, plugin.url).map { }
            ProviderType.NUVIO -> {
                when (val r = providerManager.installNuvioManifest(plugin.url)) {
                    is StormResult.Ok -> StormResult.Ok(Unit)
                    is StormResult.Err -> StormResult.Err(r.error)
                }
            }
            ProviderType.SKYSTREAM ->
                if (plugin.url.endsWith(".sky")) {
                    providerManager.installSkyStreamPackage(plugin.url).map { }
                } else {
                    providerManager.installSkyStreamPlugin(
                        name = plugin.packageName ?: plugin.name,
                        jsUrl = plugin.url,
                        manifestUrl = plugin.manifest?.let { http.resolve(url, it) },
                    ).map { }
                }
            ProviderType.JS ->
                if (plugin.files.isNotEmpty()) {
                    // Multi-file JS plugin: relative file URLs resolve against
                    // the plugin's own URL.
                    val base = plugin.url.substringBeforeLast('/')
                    providerManager.installJsPluginFiles(
                        name = plugin.name,
                        version = plugin.version.toString(),
                        description = plugin.description,
                        icon = plugin.icon,
                        files = plugin.files,
                        baseUrl = base,
                    ).map { }
                } else {
                    providerManager.installJsPlugin(plugin.url, fallbackName = plugin.name).map { }
                }
            null -> StormResult.Err(
                StormError.Unsupported(
                    "CloudStream .cs3 plugins are not supported. StormStream runs " +
                        "JavaScript plugins (StormJS / Vega-dialect), not CloudStream dex plugins."
                )
            )
        }
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http")) return t
        val gh = Regex("""github\.com/([^/]+)/([^/]+)/?""").find(t)
        if (gh != null) {
            val (owner, repo) = gh.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/builds/repo.json"
        }
        return t
    }

    companion object {
        private const val TAG = "StormRepoMgr"
    }
}
