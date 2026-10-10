package com.stormstream.app.providers.nuvio

import android.content.Context
import com.stormstream.app.core.StormError
import com.stormstream.app.core.StormResult
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.InstalledExtension
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.NuvioManifest
import com.stormstream.app.data.NuvioScraperDef
import com.stormstream.app.data.PluginSettingField
import com.stormstream.app.data.PluginSettingOption
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.StreamSource
import com.stormstream.app.data.StreamType
import com.stormstream.app.data.Subtitle
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.net.TmdbClient
import com.stormstream.app.providers.StreamProvider
import com.stormstream.app.providers.js.JsPluginRuntime
import com.stormstream.app.util.StormJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Nuvio plugin scraper provider.
 *
 * Nuvio plugins are pure stream sources keyed by TMDB id — they expose no
 * catalogs/search of their own. Following the integration pattern used by the
 * Hikari reference app, each scraper is browsed THROUGH TMDB: its catalog rows
 * are TMDB popular/trending shelves and its search is TMDB multi-search, and
 * streams are resolved per item via the plugin's
 * `getStreams(tmdbId, mediaType, season, episode)`.
 *
 * Learned from the NuvioMobile plugin conventions (manifest scrapers[],
 * getStreams/onSettings, SCRAPER_ID/SCRAPER_SETTINGS, fetch+cheerio+crypto
 * polyfills) — reimplemented against StormStream's own runtime and models.
 */
class NuvioProvider(
    private val context: Context,
    private val http: StormHttpClient,
    private val tmdb: TmdbClient,
    val manifestName: String,
    val scraper: NuvioScraperDef,
    val pluginDir: File,
) : StreamProvider {

    private val runtime = JsPluginRuntime(context, http, pluginId(manifestName, scraper.id))

    override val config: ProviderConfig = ProviderConfig(
        id = pluginId(manifestName, scraper.id),
        name = scraper.name.ifBlank { manifestName },
        type = ProviderType.NUVIO,
        icon = scraper.logo,
        sourceUrl = manifestName,
        version = scraper.version.ifBlank { null },
        supportedMediaTypes = scraper.supportedTypes
            .mapNotNull(::nuvioTypeToMediaType)
            .toSet()
            .ifEmpty { setOf(MediaType.MOVIE, MediaType.SERIES) },
        extra = mapOf(
            "scraperId" to scraper.id,
            "manifestName" to manifestName,
        ),
    )

    override val hasSettings: Boolean get() = scraper.hasSettings

    private val prefsName get() = "nuvio_${manifestName}_${scraper.id}"
    private val prefs get() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Cached onSettings() layout (null = not fetched yet, empty = none). */
    @Volatile
    private var settingsLayout: List<PluginSettingField>? = null

    override suspend fun initialize() {
        val sourceFile = File(pluginDir, scraper.filename.ifBlank { "${scraper.id}.js" })
        val source = sourceFile.takeIf { it.exists() }?.readText()
            ?: throw StormError.Unsupported("Nuvio scraper file missing: ${sourceFile.name}")
        val settingsJson = StormJson.encodeToString(
            (settingsLayout ?: emptyList()).associate { it.key to (prefs.getString(it.key, it.defaultValue) ?: it.defaultValue) }
                .ifEmpty { prefs.all.mapValues { it.value.toString() } },
        )
        val result = runtime.load(
            modules = mapOf("main" to source),
            dialect = "nuvio",
            scriptMode = true,
            scraperId = scraper.id,
            scraperSettingsJson = settingsJson,
        )
        if (result is StormResult.Err) {
            throw RuntimeException("Nuvio scraper failed to load: ${result.error.message}")
        }
    }

    override suspend fun shutdown() {
        runtime.shutdown()
    }

    // ---------- settings (Nuvio onSettings() layout) ----------

    override suspend fun settingsFields(): List<PluginSettingField>? = withContext(Dispatchers.IO) {
        settingsLayout?.let { return@withContext it }
        val json = runtime.invokeArrayOrNull("onSettings", "[]") ?: return@withContext null
        val parsed = runCatching { StormJson.parseToJsonElement(json) }.getOrNull()
        val arr = (parsed as? JsonArray) ?: return@withContext null
        val fields = arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            PluginSettingField(
                key = key,
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: key,
                type = when (o["type"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                    "bool", "boolean", "switch", "toggle" -> "bool"
                    "select", "dropdown" -> "select"
                    "url" -> "url"
                    "info", "header", "label" -> "info"
                    else -> "text"
                },
                defaultValue = o["defaultValue"]?.jsonPrimitive?.contentOrNull
                    ?: o["default"]?.jsonPrimitive?.contentOrNull ?: "",
                options = (o["options"] as? JsonArray)?.mapNotNull { opt ->
                    (opt as? JsonObject)?.let { oo ->
                        val value = oo["value"]?.jsonPrimitive?.contentOrNull
                            ?: oo["id"]?.jsonPrimitive?.contentOrNull
                            ?: return@let null
                        PluginSettingOption(
                            label = oo["label"]?.jsonPrimitive?.contentOrNull
                                ?: oo["name"]?.jsonPrimitive?.contentOrNull
                                ?: value,
                            value = value,
                        )
                    }
                } ?: emptyList(),
                description = o["description"]?.jsonPrimitive?.contentOrNull,
            )
        }
        settingsLayout = fields
        fields
    }

    override suspend fun getSettings(): Map<String, String> {
        val fields = settingsFields() ?: return prefs.all.mapValues { it.value.toString() }
        return fields.associate { f ->
            f.key to (prefs.getString(f.key, f.defaultValue) ?: f.defaultValue)
        }
    }

    override suspend fun setSetting(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    // ---------- discovery (browsed through TMDB) ----------

    override suspend fun catalogs(): List<CatalogRef> {
        val types = config.supportedMediaTypes
        return types.map { mt ->
            CatalogRef(
                providerId = config.id,
                catalogId = "tmdb:${if (mt == MediaType.SERIES || mt == MediaType.ANIME) "tv" else "movie"}",
                name = if (mt == MediaType.SERIES || mt == MediaType.ANIME) {
                    "${config.name} · Series"
                } else {
                    "${config.name} · Movies"
                },
                mediaType = mt,
            )
        }
    }

    override suspend fun homeCatalogs(): List<CatalogRef> = catalogs().take(2)

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val items = tmdb.popular(ref.mediaType, page)
            items.map { it.copy(providerId = config.id) }
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val supported = config.supportedMediaTypes
            tmdb.searchMulti(query, page)
                .filter { it.type in supported }
                .map { it.copy(providerId = config.id) }
        }

    // ---------- detail / playback ----------

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val tmdbId = item.tmdbId ?: item.imdbId?.let { tmdb.findByImdb(it, item.type)?.tmdbId }
        if (tmdbId == null) return@withContext item
        val detailed = when (item.type) {
            MediaType.SERIES, MediaType.ANIME -> tmdb.tvDetails(tmdbId)?.first
            else -> tmdb.movieDetails(tmdbId)
        } ?: return@withContext item
        item.copy(
            title = detailed.title.ifBlank { item.title },
            posterUrl = detailed.posterUrl ?: item.posterUrl,
            backdropUrl = detailed.backdropUrl ?: item.backdropUrl,
            year = detailed.year ?: item.year,
            rating = detailed.rating ?: item.rating,
            description = detailed.description ?: item.description,
            genres = detailed.genres.ifEmpty { item.genres },
            tmdbId = tmdbId,
            imdbId = detailed.imdbId ?: item.imdbId,
            totalSeasons = detailed.totalSeasons ?: item.totalSeasons,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        if (item.type != MediaType.SERIES && item.type != MediaType.ANIME) return@withContext null
        val tmdbId = item.tmdbId ?: item.imdbId?.let { tmdb.findByImdb(it, item.type)?.tmdbId }
            ?: return@withContext null
        val eps = tmdb.tvDetails(tmdbId)?.second
            ?: return@withContext null
        eps.map { it.copy(providerId = config.id) }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val tmdbId = item.tmdbId
                ?: item.imdbId?.let { tmdb.findByImdb(it, item.type)?.tmdbId }
                ?: return@withContext emptyList()
            val mediaType = if (item.type == MediaType.SERIES || item.type == MediaType.ANIME) "tv" else "movie"
            val args = buildJsonArray {
                add(tmdbId)
                add(mediaType)
                if (episode != null) {
                    add(episode.season)
                    add(episode.number)
                } else {
                    add(JsonNull)
                    add(JsonNull)
                }
            }
            val json = runtime.invokeArrayOrNull("getStreams", args.toString())
                ?: return@withContext emptyList()
            parseStreamResults(json)
        }

    private fun parseStreamResults(json: String): List<StreamSource> {
        val parsed = runCatching { StormJson.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        val arr = (parsed as? JsonArray) ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            // libmpv plays direct media URLs only — torrent/infoHash-only
            // results are skipped.
            val url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val subs = (o["subtitles"] as? JsonArray)?.mapNotNull { sub ->
                val so = sub as? JsonObject ?: return@mapNotNull null
                val subUrl = so["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Subtitle(
                    label = so["name"]?.jsonPrimitive?.contentOrNull
                        ?: so["label"]?.jsonPrimitive?.contentOrNull
                        ?: so["language"]?.jsonPrimitive?.contentOrNull
                        ?: "Subtitle",
                    language = so["language"]?.jsonPrimitive?.contentOrNull ?: "und",
                    url = subUrl,
                )
            }.orEmpty()
            val quality = o["quality"]?.jsonPrimitive?.contentOrNull
                ?: o["size"]?.jsonPrimitive?.contentOrNull
            StreamSource(
                name = listOfNotNull(
                    o["title"]?.jsonPrimitive?.contentOrNull,
                    o["name"]?.jsonPrimitive?.contentOrNull,
                    o["provider"]?.jsonPrimitive?.contentOrNull,
                ).firstOrNull() ?: "Stream",
                url = url,
                type = when {
                    url.contains(".m3u8") -> StreamType.HLS
                    url.contains(".mpd") -> StreamType.DASH
                    url.contains(".mp4") -> StreamType.MP4
                    url.contains(".mkv") || url.contains(".webm") -> StreamType.MKV
                    else -> StreamType.UNKNOWN
                },
                quality = quality,
                headers = (o["headers"] as? JsonObject)?.entries?.associate { (k, v) ->
                    k to (v.jsonPrimitive.contentOrNull ?: "")
                } ?: emptyMap(),
                subtitles = subs,
                providerId = config.id,
            )
        }
    }

    companion object {
        fun pluginId(manifestName: String, scraperId: String): String =
            "nuvio:${safe(manifestName)}:$scraperId"

        fun safe(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "plugin" }

        fun pluginsDir(context: Context): File =
            File(context.filesDir, "nuvio").apply { mkdirs() }

        /**
         * Build a provider for one scraper of an installed manifest directory.
         * The directory holds the downloaded manifest.json plus one .js per
         * scraper.
         */
        fun fromDir(
            context: Context,
            http: StormHttpClient,
            tmdb: TmdbClient,
            dir: File,
            scraperId: String,
        ): NuvioProvider {
            val manifest = runCatching {
                StormJson.decodeFromString<NuvioManifest>(File(dir, "manifest.json").readText())
            }.getOrNull() ?: NuvioManifest(name = dir.name)
            val scraper = manifest.scrapers.firstOrNull { it.id == scraperId }
                ?: NuvioScraperDef(id = scraperId, name = scraperId, filename = "$scraperId.js")
            return NuvioProvider(context, http, tmdb, manifest.name.ifBlank { dir.name }, scraper, dir)
        }

        fun extensionRecord(provider: NuvioProvider): InstalledExtension = InstalledExtension(
            config = provider.config,
            localPath = provider.pluginDir.absolutePath,
        )
    }
}

private fun nuvioTypeToMediaType(raw: String?): MediaType? = when (raw?.lowercase()?.trim()) {
    "movie", "film" -> MediaType.MOVIE
    "tv", "series", "tvshow", "show" -> MediaType.SERIES
    "anime" -> MediaType.ANIME
    else -> null
}
