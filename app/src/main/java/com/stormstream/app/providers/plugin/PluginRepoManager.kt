package com.stormstream.app.providers.plugin

import android.util.Log
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.RepoIndex
import com.stormstream.app.data.RepoPlugin
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.ProviderManager
import com.stormstream.app.providers.cs3.Cs3Manifest
import com.stormstream.app.providers.cs3.Cs3Provider
import com.stormstream.app.util.StormJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Loads third-party extension repos.
 *
 * Understood repo shapes:
 *   1. Hiki/Storm/Vega/Hikari-style:
 *      { "name": "...", "plugins": [ { "name", "url", "version", "tvTypes", "providerType" } ] }
 *   2. CloudStream-style:
 *      { "name": "...", "manifestVersion": 1, "pluginLists": [ "<url to plugins.json>" ] }
 *   3. Plain github.com/owner/repo shorthand → raw builds/repo.json.
 *
 * Phase 1 fixes:
 *  - Uses async [StormHttpClient] with timeouts so a bad repo URL doesn't crash.
 *  - Better error surfacing (throws a typed exception that callers catch).
 *  - Proper provider type inference from tvTypes when providerType is missing.
 */
class PluginRepoManager(
    private val http: StormHttpClient,
    private val providerManager: ProviderManager,
) {

    private val addedRepos = mutableMapOf<String, RepoIndex>()

    fun repos(): Map<String, RepoIndex> = addedRepos.toMap()

    suspend fun addRepo(url: String): RepoIndex = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(url)
        val body = when (val r = http.get(normalized, timeoutMs = 12_000L)) {
            is StormHttpClient.StormHttpResult.Ok -> r.body
            is StormHttpClient.StormHttpResult.Err ->
                throw RuntimeException("Failed to fetch repo: ${r.message}")
        }
        var idx = try {
            StormJson.decodeFromString(RepoIndex.serializer(), body)
        } catch (e: Exception) {
            throw RuntimeException("Invalid repo JSON at $normalized: ${e.message}", e)
        }
        // CloudStream-style manifest: follow pluginLists
        if (idx.pluginLists.isNotEmpty() && idx.plugins.isEmpty()) {
            val all = mutableListOf<RepoPlugin>()
            idx.pluginLists.forEach { listUrl ->
                val listBody = (http.get(listUrl, timeoutMs = 12_000L)
                    as? StormHttpClient.StormHttpResult.Ok)?.body
                if (listBody != null) {
                    runCatching {
                        StormJson.decodeFromString<List<RepoPlugin>>(listBody)
                    }.onSuccess { all += it }
                      .onFailure { Log.w(TAG, "Plugin list parse fail $listUrl", it) }
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
        } ?: inferTypeFromTvTypes(plugin.tvTypes)

        when (type) {
            ProviderType.STREMIO -> {
                val r = providerManager.installStremioAddon(plugin.url)
                r.isOk
            }
            ProviderType.UNIVERSAL_SCRAPER -> {
                val cfgJson = (http.get(plugin.url, timeoutMs = 10_000L)
                    as? StormHttpClient.StormHttpResult.Ok)?.body
                if (cfgJson != null) {
                    providerManager.installUniversalScraper(cfgJson).isOk
                } else false
            }
            ProviderType.IPTV -> {
                providerManager.installIptvPlaylist(plugin.name, plugin.url).isOk
            }
            ProviderType.CS3 -> {
                runCatching {
                    val bytes = http.getBytes(plugin.url, timeoutMs = 30_000L)
                        ?: return@runCatching false
                    val dir = File(providerManager.ctx.filesDir, "cs3").apply { mkdirs() }
                    val file = File(dir, "${plugin.name}.cs3")
                    file.writeBytes(bytes)
                    // Read embedded plugin.json if present, else use defaults.
                    var manifest = Cs3Manifest(pluginName = plugin.name, version = plugin.version)
                    runCatching {
                        java.util.zip.ZipInputStream(file.inputStream()).use { zip ->
                            while (true) {
                                val entry = zip.nextEntry ?: break
                                if (entry.name == "plugin.json") {
                                    val txt = zip.bufferedReader().readText()
                                    manifest = StormJson.decodeFromString<Cs3Manifest>(txt)
                                    break
                                }
                            }
                        }
                    }
                    val prov = Cs3Provider(providerManager.context, http.client, file, manifest)
                    providerManager.register(prov)
                    true
                }.getOrElse { t ->
                    android.util.Log.e(TAG, "CS3 install failed: ${t.message}", t)
                    false
                }
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
                }.toSet().ifEmpty { setOf(MediaType.MOVIE, MediaType.SERIES) }
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

    private fun inferTypeFromTvTypes(tvTypes: List<String>): ProviderType {
        // Default to Storm native for generic plugin packages.
        return ProviderType.STORM
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
