package com.stormstream.app.providers.plugin

import android.util.Log
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.RepoIndex
import com.stormstream.app.data.RepoPlugin
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.ProviderManager
import com.stormstream.app.util.StormJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads third-party extension repos (the same idea as Hikari's "Add repo" flow).
 *
 * A repo URL points at a repo.json of one of three shapes:
 *   1. Hiki/Storm/Vega style:
 *      { "name": "...", "plugins": [ { "name", "url", "version", "tvTypes", "providerType" } ] }
 *   2. CloudStream style:
 *      { "name": "...", "manifestVersion": 1, "pluginLists": [ "<url to plugins.json>" ] }
 *      where plugins.json is itself a list of plugin objects.
 *   3. Plain github.com/owner/repo shorthand — we rewrite it to the raw URL.
 *
 * Once fetched, plugins are shown to the user and installed on demand; the
 * per-type runtime downloads the file and loads it (JS, dex, json config, etc).
 */
class PluginRepoManager(
    private val http: StormHttpClient,
    private val providerManager: ProviderManager,
) {

    private val addedRepos = mutableMapOf<String, RepoIndex>()

    fun repos(): Map<String, RepoIndex> = addedRepos.toMap()

    suspend fun addRepo(url: String): RepoIndex = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url)
        val body = http.get(normalized)
        var idx = StormJson.decodeFromString(RepoIndex.serializer(), body)
        // If it's a CloudStream-style manifest, follow pluginLists.
        if (idx.pluginLists.isNotEmpty() && idx.plugins.isEmpty()) {
            val all = mutableListOf<RepoPlugin>()
            idx.pluginLists.forEach { listUrl ->
                val listBody = runCatching { http.get(listUrl) }.getOrNull()
                if (listBody != null) {
                    val arr = StormJson.decodeFromString<List<RepoPlugin>>(listBody)
                    all += arr
                }
            }
            idx = idx.copy(plugins = all)
        }
        addedRepos[normalized] = idx
        idx
    }

    suspend fun installPlugin(plugin: RepoPlugin): Boolean = withContext(Dispatchers.IO) {
        val type = plugin.providerType?.let {
            runCatching { ProviderType.valueOf(it.uppercase()) }.getOrNull()
        } ?: ProviderType.STORM
        when (type) {
            // For demo, we register scaffold entries. Real code would download
            // the plugin file to cache dir and hand off to the per-type runtime.
            ProviderType.STREMIO -> {
                providerManager.installStremioAddon(plugin.url)
                true
            }
            ProviderType.UNIVERSAL_SCRAPER -> {
                val cfgJson = runCatching { http.get(plugin.url) }.getOrNull()
                if (cfgJson != null) { providerManager.installUniversalScraper(cfgJson); true }
                else false
            }
            ProviderType.IPTV -> {
                providerManager.installIptvPlaylist(plugin.name, plugin.url)
                true
            }
            else -> {
                val types = plugin.tvTypes.mapNotNull { t ->
                    when (t) {
                        "movie" -> MediaType.MOVIE
                        "tv", "series" -> MediaType.SERIES
                        "anime" -> MediaType.ANIME
                        "manga" -> MediaType.MANGA
                        else -> null
                    }
                }.toSet().ifEmpty { setOf(MediaType.MOVIE) }
                val cfg = ProviderConfig(
                    id = "${type.key}:${plugin.name.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                    name = plugin.name,
                    type = type,
                    icon = plugin.icon,
                    baseUrl = plugin.url,
                    version = plugin.version.toString(),
                    supportedMediaTypes = types,
                )
                providerManager.installScaffold(type, cfg)
                Log.i(TAG, "Installed scaffold for ${plugin.name} (type=$type)")
                true
            }
        }
    }

    private fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.startsWith("http")) return t
        // github.com/owner/repo short form → raw build repo.json.
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
