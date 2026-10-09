package com.stormstream.app.providers.vega

import com.stormstream.app.core.StormException
import com.stormstream.app.data.*
import com.stormstream.app.providers.StreamProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * QuickJS-backed Vega/Nuvio-style JavaScript provider.
 *
 * Vega and Nuvio plugins are CommonJS/ES modules that expose a small standard
 * API (search, mainPage, load, getStreams).  We eval them on a bundled QuickJS
 * instance via reflection (so the native `.so` can be dropped later without
 * changing Kotlin code); if QuickJS is not available on the classpath, the
 * engine reports an error at init time and [ProviderManager] skips installation.
 *
 * The VM exposes:
 *   - `http.get(url, headers?)` -> string
 *   - `http.post(url, body, headers?)` -> string
 *   - `log(...)` -> Logcat
 *   - `jsoup(html)` -> a DOM bridge (lightweight)
 *
 * Providers return plain JSON structures that map 1:1 to [MediaItem],
 * [Episode], and [StreamSource].
 */
class VegaProvider(
    private val sourceFile: File,
    private val http: OkHttpClient,
    private val manifest: VegaManifest,
) : StreamProvider {

    override val config: ProviderConfig = ProviderConfig(
        id = "vega:${manifest.id ?: sourceFile.name}",
        name = manifest.name ?: sourceFile.nameWithoutExtension,
        type = ProviderType.VEGA,
        version = manifest.version,
        sourceUrl = sourceFile.absolutePath,
        supportedMediaTypes = manifest.mediaTypes.map { m ->
            runCatching { MediaType.valueOf(m.uppercase()) }.getOrDefault(MediaType.MOVIE)
        }.toSet().ifEmpty { setOf(MediaType.MOVIE, MediaType.SERIES) },
    )

    private var vm: Any? = null  // quickjs QuickJSContext instance (reflection)

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        // TODO(phase3): init QuickJS via `cz.voff:quickjs-android` and load script;
        // for now throw a clear error so install feedback is honest.
        throw StormException("Vega JS runtime will be enabled when the QuickJS native libs are bundled")
    }

    override suspend fun catalogs(): List<CatalogRef> = emptyList()
    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = emptyList()
    override suspend fun search(query: String, page: Int): List<MediaItem> = emptyList()
    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> = emptyList()
}

data class VegaManifest(
    val id: String? = null,
    val name: String? = null,
    val version: String? = null,
    val mediaTypes: List<String> = listOf("movie", "series"),
    val pluginUrl: String? = null,
)
