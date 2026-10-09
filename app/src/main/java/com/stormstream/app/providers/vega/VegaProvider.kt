package com.stormstream.app.providers.vega

import com.stormstream.app.data.*
import com.stormstream.app.providers.StreamProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File

/**
 * QuickJS-backed Vega/Nuvio-style JavaScript provider.
 *
 * Phase 3 note: the QuickJS native library is not yet bundled; initialize()
 * throws a descriptive error so the UI can tell the user why the plugin
 * didn't load. When libquickjs.so is added to jniLibs, replace the init
 * body with a real VM bootstrap that evaluates the script and binds the
 * http/jsoup helpers.
 */
class VegaProvider(
    private val sourceFile: File? = null,
    private val http: OkHttpClient? = null,
    private val manifest: VegaManifest = VegaManifest(),
) : StreamProvider {

    override val config: ProviderConfig = ProviderConfig(
        id = "vega:${manifest.id ?: sourceFile?.name ?: "builtin"}",
        name = manifest.name ?: sourceFile?.nameWithoutExtension ?: "Vega",
        type = ProviderType.VEGA,
        version = manifest.version,
        sourceUrl = sourceFile?.absolutePath,
        supportedMediaTypes = manifest.mediaTypes.map { m ->
            runCatching { MediaType.valueOf(m.uppercase()) }.getOrDefault(MediaType.MOVIE)
        }.toSet().ifEmpty { setOf(MediaType.MOVIE, MediaType.SERIES) },
    )

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        throw RuntimeException("Vega JS runtime not yet bundled (Phase 3)")
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
