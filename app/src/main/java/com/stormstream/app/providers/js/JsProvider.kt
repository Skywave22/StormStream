package com.stormstream.app.providers.js

import android.content.Context
import com.stormstream.app.core.StormError
import com.stormstream.app.core.StormResult
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.InstalledExtension
import com.stormstream.app.data.JsPluginManifest
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.StreamSource
import com.stormstream.app.data.Subtitle
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.StreamProvider
import com.stormstream.app.util.StormJson
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * StreamProvider backed by a JavaScript plugin running in the embedded
 * QuickJS runtime ([JsPluginRuntime]).
 *
 * Supports two plugin dialects (auto-detected by the runtime):
 *  - **StormJS** — the native, documented StormStream plugin API.
 *  - **Vega-compatible** — JSON-based Vega-style providers (posts/meta/stream
 *    modules with a `providerContext.axios` bridge).
 *
 * The plugin directory layout (created at install time):
 *   plugins/js-&lt;slug&gt;/manifest.json   — JsPluginManifest
 *   plugins/js-&lt;slug&gt;/&lt;module&gt;.js     — one CommonJS module per file
 */
class JsProvider private constructor(
    private val context: Context,
    private val http: StormHttpClient,
    val pluginDir: File,
    val manifest: JsPluginManifest,
) : StreamProvider {

    override val config: ProviderConfig = ProviderConfig(
        id = "js:" + slugify(manifest.name),
        name = manifest.name,
        type = ProviderType.JS,
        icon = manifest.icon,
        version = manifest.version,
        adult = manifest.adult,
        supportedMediaTypes = manifest.types.mapNotNull { mediaTypeFromString(it) }.toSet()
            .ifEmpty { setOf(MediaType.MOVIE, MediaType.SERIES) },
    )

    private var runtime: JsPluginRuntime? = null

    override suspend fun initialize() {
        val modules = pluginDir.listFiles { f -> f.isFile && f.extension == "js" }
            ?.associate { it.nameWithoutExtension to it.readText() }
            .orEmpty()
        if (modules.isEmpty()) {
            throw StormError.Unsupported("JS plugin '${manifest.name}' has no .js files")
        }
        val rt = JsPluginRuntime(context, http, config.id)
        when (val r = rt.load(modules)) {
            is StormResult.Ok -> {
                when (r.value.dialect) {
                    "storm", "vega" -> Unit
                    else -> throw StormError.Unsupported(
                        "'${manifest.name}' is not a StormStream JS plugin " +
                            "(no StormJS or Vega-style exports were found)"
                    )
                }
            }
            is StormResult.Err -> throw r.error
        }
        runtime = rt
    }

    override suspend fun shutdown() {
        runtime?.shutdown()
        runtime = null
    }

    private fun runtimeOrThrow(): JsPluginRuntime =
        runtime ?: throw StormError.NotInstalled(config.id)

    // ---------- discovery ----------

    override suspend fun catalogs(): List<CatalogRef> {
        val json = runtimeOrThrow().invokeOrNull("getCatalogs", "{}")
            ?.takeUnless { it == "null" } ?: return emptyList()
        val cats = StormJson.decodeFromString<List<JsCatalog>>(json)
        return cats.map { c ->
            CatalogRef(
                providerId = config.id,
                catalogId = c.id,
                name = c.name,
                mediaType = mediaTypeFromString(c.type) ?: MediaType.MOVIE,
            )
        }
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        val args = buildJsonObject {
            put("catalogId", ref.catalogId)
            put("page", page)
        }.toString()
        val json = runtimeOrThrow().invokeOrNull("getCatalog", args)
            ?.takeUnless { it == "null" } ?: return emptyList()
        return StormJson.decodeFromString<List<JsItem>>(json).map { it.toMediaItem(config.id) }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        val args = buildJsonObject {
            put("query", query)
            put("page", page)
        }.toString()
        val json = runtimeOrThrow().invokeOrNull("search", args)
            ?.takeUnless { it == "null" } ?: return emptyList()
        return StormJson.decodeFromString<List<JsItem>>(json).map { it.toMediaItem(config.id) }
    }

    // ---------- detail / playback ----------

    override suspend fun getMeta(item: MediaItem): MediaItem {
        val args = buildJsonObject { put("item", StormJson.encodeToJsonElement(JsItem.serializer(), item.toJsItem())) }.toString()
        val json = runtimeOrThrow().invokeOrNull("getMeta", args)
            ?.takeUnless { it == "null" } ?: return item
        val enriched = StormJson.decodeFromString<JsItem>(json)
        return item.merge(enriched)
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        val args = buildJsonObject { put("item", StormJson.encodeToJsonElement(JsItem.serializer(), item.toJsItem())) }.toString()
        val json = runtimeOrThrow().invokeOrNull("getEpisodes", args) ?: return null
        if (json == "null") return null
        return StormJson.decodeFromString<List<JsEpisode>>(json).map { it.toEpisode(config.id, item.id) }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val args = buildJsonObject {
            put("item", StormJson.encodeToJsonElement(JsItem.serializer(), item.toJsItem()))
            if (episode != null) {
                put("episode", StormJson.encodeToJsonElement(JsEpisode.serializer(), episode.toJsEpisode()))
            }
        }.toString()
        val json = runtimeOrThrow().invoke("getStreams", args).takeUnless { it == "null" }
            ?: return emptyList()
        return StormJson.decodeFromString<List<JsStream>>(json).mapNotNull { it.toStreamSource(config.id) }
    }

    // ---------- mapping ----------

    private fun JsItem.toMediaItem(providerId: String): MediaItem = MediaItem(
        id = id.ifBlank { url ?: title },
        providerId = providerId,
        title = title,
        type = mediaTypeFromString(type) ?: MediaType.MOVIE,
        posterUrl = poster,
        backdropUrl = backdrop,
        year = year,
        rating = rating,
        description = description,
        genres = genres,
        internalUrl = url,
    )

    private fun MediaItem.toJsItem() = JsItem(
        id = id,
        title = title,
        type = when (type) {
            MediaType.MOVIE -> "movie"
            MediaType.SERIES -> "series"
            MediaType.ANIME -> "anime"
            MediaType.MANGA -> "manga"
            MediaType.IPTV -> "tv"
            MediaType.LIVE -> "live"
            MediaType.UNKNOWN -> "movie"
        },
        poster = posterUrl,
        backdrop = backdropUrl,
        year = year,
        rating = rating,
        description = description,
        genres = genres,
        url = internalUrl,
    )

    private fun MediaItem.merge(enriched: JsItem): MediaItem = copy(
        title = enriched.title.ifBlank { title },
        posterUrl = enriched.poster ?: posterUrl,
        backdropUrl = enriched.backdrop ?: backdropUrl,
        year = enriched.year ?: year,
        rating = enriched.rating ?: rating,
        description = enriched.description ?: description,
        genres = enriched.genres.ifEmpty { genres },
        internalUrl = enriched.url ?: internalUrl,
    )

    private fun JsEpisode.toEpisode(providerId: String, showId: String): Episode = Episode(
        id = id.ifBlank { "$showId:$season:$number" },
        providerId = providerId,
        showId = showId,
        title = title,
        season = season,
        number = number,
        thumbnailUrl = thumbnail,
        description = description,
        internalUrl = url,
    )

    private fun Episode.toJsEpisode() = JsEpisode(
        id = id,
        title = title,
        season = season,
        number = number,
        thumbnail = thumbnailUrl,
        description = description,
        url = internalUrl,
    )

    private fun JsStream.toStreamSource(providerId: String): StreamSource? {
        val streamUrl = url?.takeIf { it.isNotBlank() } ?: return null
        return StreamSource(
            name = name.ifBlank { "Stream" },
            url = streamUrl,
            type = streamTypeFromString(type, url),
            quality = quality,
            headers = headers,
            subtitles = subtitles.mapNotNull { sub ->
                val subUrl = sub.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Subtitle(
                    label = sub.label ?: sub.lang ?: "Subtitle",
                    language = sub.lang ?: "und",
                    url = subUrl,
                    format = sub.format,
                )
            },
            providerId = providerId,
        )
    }

    companion object {
        /**
         * Build a provider from an on-disk plugin directory. Reads
         * manifest.json; when absent, synthesizes a manifest for a single
         * main.js plugin.
         */
        fun fromDir(
            context: Context,
            http: StormHttpClient,
            dir: File,
            fallbackName: String? = null,
        ): JsProvider {
            val manifestFile = File(dir, "manifest.json")
            val manifest = runCatching {
                StormJson.decodeFromString<JsPluginManifest>(manifestFile.readText())
            }.getOrNull() ?: JsPluginManifest(
                name = fallbackName?.ifBlank { null } ?: dir.name.removePrefix("js-")
                    .replace('-', ' ').replaceFirstChar { it.uppercase() },
                main = if (File(dir, "main.js").exists()) "main" else null,
            )
            return JsProvider(context, http, dir, manifest)
        }

        /** Extension record for a plugin directory, for persistence. */
        fun extensionRecord(provider: JsProvider): InstalledExtension = InstalledExtension(
            config = provider.config,
            localPath = provider.pluginDir.absolutePath,
        )
    }
}
