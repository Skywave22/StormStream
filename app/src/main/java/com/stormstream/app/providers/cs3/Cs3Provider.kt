package com.stormstream.app.providers.cs3

import android.content.Context
import com.stormstream.app.data.*
import com.stormstream.app.providers.StreamProvider
import dalvik.system.DexClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.zip.ZipInputStream

/**
 * CloudStream-compatible (.cs3) plugin loader.
 *
 * A .cs3 file is a ZIP containing at minimum:
 *   - classes.jar (a DEX-converted jar of the plugin classes implementing the
 *     CloudStream3 plugin API)
 *   - plugin.json (name, version, etc)
 *
 * This loader extracts classes.jar into the app's cache dir and instantiates
 * the plugin via [Dalvik/dex/DexClassLoader], reflecting against a thin bridge
 * interface ([Cs3PluginBridge]) that mirrors the subset of the CloudStream API
 * StormStream supports (search, catalog, meta, streams, episodes).
 *
 * When a .cs3 file is installed locally or downloaded from a plugin repo, the
 * [com.stormstream.app.providers.PluginRepoManager] feeds the file path here;
 * remote URLs are first downloaded via OkHttp and cached.
 *
 * If a class fails to load (e.g. because the plugin uses a different CS API
 * version than our bridge), installation returns [StormResult.Err] with a
 * human-readable message and the UI surfaces the error as a snackbar.
 */
class Cs3Provider(
    private val context: Context,
    private val http: OkHttpClient,
    private val pluginFile: File,
    private val manifest: Cs3Manifest,
) : StreamProvider {

    override val config: ProviderConfig = ProviderConfig(
        id = "cs3:${manifest.internalName ?: pluginFile.name}",
        name = manifest.pluginName ?: pluginFile.nameWithoutExtension,
        type = ProviderType.CS3,
        version = manifest.version.toString(),
        sourceUrl = pluginFile.absolutePath,
        supportedMediaTypes = setOf(MediaType.MOVIE, MediaType.SERIES, MediaType.ANIME),
    )

    private var classLoader: DexClassLoader? = null
    private var bridge: Cs3PluginBridge? = null

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        // Extract classes.jar from the .cs3 zip into a temp file and construct a DexClassLoader.
        val outDex = File(context.cacheDir, "cs3_${System.nanoTime()}.jar")
        outDex.outputStream().use { os ->
            ZipInputStream(pluginFile.inputStream()).use { zip ->
                var found = false
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name.endsWith("classes.jar") || entry.name == "classes.dex") {
                        zip.copyTo(os)
                        found = true
                        break
                    }
                }
                if (!found) throw RuntimeException("Invalid .cs3: no classes.jar/dex found")
            }
        }
        val optDir = File(context.cacheDir, "cs3_opt").apply { mkdirs() }
        val cl = DexClassLoader(outDex.absolutePath, optDir.absolutePath, null, javaClass.classLoader)
        // Try common plugin class names (CloudStream convention: the manifest lists "className").
        val candidates = listOf(manifest.className, "com.lagradost.cloudstream3.plugins.Plugin")
            .filterNotNull()
        var loaded: Cs3PluginBridge? = null
        var lastErr: Throwable? = null
        for (cname in candidates) {
            try {
                val cls = cl.loadClass(cname)
                val instance = cls.getDeclaredConstructor().newInstance()
                loaded = Cs3PluginBridge(instance)
                break
            } catch (t: Throwable) { lastErr = t }
        }
        classLoader = cl
        bridge = loaded ?: throw RuntimeException(
            "Could not load CS3 plugin: ${lastErr?.message ?: "unknown class"}"
        )
    }

    override suspend fun shutdown() {
        bridge?.dispose()
        bridge = null
        classLoader = null
    }

    override suspend fun catalogs(): List<CatalogRef> = bridge?.catalogs(config.id) ?: emptyList()
    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        bridge?.catalog(ref, page) ?: emptyList()
    override suspend fun search(query: String, page: Int): List<MediaItem> =
        bridge?.search(query, page) ?: emptyList()
    override suspend fun getMeta(item: MediaItem): MediaItem =
        bridge?.meta(item) ?: item
    override suspend fun getEpisodes(item: MediaItem): List<Episode>? =
        bridge?.episodes(item)
    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        bridge?.streams(item, episode) ?: emptyList()
}

@kotlinx.serialization.Serializable
data class Cs3Manifest(
    val pluginName: String? = null,
    val internalName: String? = null,
    val version: Int = 1,
    val className: String? = null,
    val requiresResources: Boolean = false,
    val description: String? = null,
    val authors: List<String> = emptyList(),
)
