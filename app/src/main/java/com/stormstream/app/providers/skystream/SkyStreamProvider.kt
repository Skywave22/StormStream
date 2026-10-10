package com.stormstream.app.providers.skystream

import android.content.Context
import com.stormstream.app.core.StormError
import com.stormstream.app.core.StormResult
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.InstalledExtension
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.PluginSettingField
import com.stormstream.app.data.PluginSettingOption
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.SkyStreamManifest
import com.stormstream.app.data.StreamSource
import com.stormstream.app.data.StreamType
import com.stormstream.app.data.Subtitle
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.StreamProvider
import com.stormstream.app.providers.js.JsPluginRuntime
import com.stormstream.app.util.StormJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * SkyStream extension provider.
 *
 * Runs SkyStream plugins (`.sky` packages: `plugin.json` + `plugin.js`) in the
 * embedded QuickJS runtime, script mode, `skystream` dialect. The plugin API
 * (both the callback flavor `getHome(cb)` and the promise flavor) is bridged
 * by the shim; this class maps the JSON shapes onto StormStream's models.
 *
 * Learned from the SkyStream ecosystem (plugin.json manifest with declarative
 * settings, getHome/search/load/loadStreams, MultimediaItem/StreamResult
 * shapes) — reimplemented against StormStream's own runtime and models.
 */
class SkyStreamProvider(
    private val context: Context,
    private val http: StormHttpClient,
    val packageName: String,
    val manifest: SkyStreamManifest,
    val pluginDir: File,
) : StreamProvider {

    private val runtime = JsPluginRuntime(context, http, pluginId(packageName))

    override val config: ProviderConfig = ProviderConfig(
        id = pluginId(packageName),
        name = manifest.name.ifBlank { packageName },
        type = ProviderType.SKYSTREAM,
        icon = manifest.icon,
        baseUrl = manifest.baseUrl ?: manifest.mainUrl,
        sourceUrl = manifest.mainUrl,
        version = manifest.version.ifBlank { null },
        supportedMediaTypes = manifest.supportedTypes
            .mapNotNull(::skyTypeToMediaType)
            .toSet()
            .ifEmpty { setOf(MediaType.MOVIE, MediaType.SERIES) },
        extra = mapOf("packageName" to packageName),
    )

    override val hasSettings: Boolean get() = manifest.settings.isNotEmpty()

    private val prefsName get() = "stormjs_${pluginId(packageName)}"
    private val prefs get() = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    /** Cached getHome sections (section name → items), guarded by a mutex. */
    private val homeMutex = Mutex()
    private var homeCache: Map<String, List<JsonObject>>? = null

    /** Cached load() results by item url (for getEpisodes). */
    private val detailCache = ConcurrentHashMap<String, JsonObject>()

    override suspend fun initialize() {
        val manifestJson = File(pluginDir, "plugin.json").takeIf { it.exists() }?.readText()
        val source = File(pluginDir, "plugin.js").takeIf { it.exists() }?.readText()
            ?: throw StormError.Unsupported("SkyStream plugin has no plugin.js")
        val result = runtime.load(
            modules = mapOf("main" to source),
            forcedDialect = "skystream",
            scriptMode = true,
            manifestJson = manifestJson,
        )
        if (result is StormResult.Err) {
            throw RuntimeException("SkyStream plugin failed to load: ${result.error.message}")
        }
    }

    override suspend fun shutdown() {
        runtime.shutdown()
    }

    // ---------- settings (manifest-declared, SkyStream convention) ----------

    override suspend fun settingsFields(): List<PluginSettingField> = manifest.settings.map { f ->
        PluginSettingField(
            key = f.key,
            title = f.title,
            type = when (f.type.lowercase()) {
                "bool", "boolean", "switch", "toggle" -> "bool"
                "select" -> "select"
                "url" -> "url"
                else -> "text"
            },
            defaultValue = f.defaultValue,
            options = f.options.map { PluginSettingOption(it.label, it.value) },
            description = f.description,
        )
    }

    override suspend fun getSettings(): Map<String, String> =
        manifest.settings.associate { f ->
            f.key to (prefs.getString(f.key, f.defaultValue) ?: f.defaultValue)
        }

    override suspend fun setSetting(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    // ---------- discovery ----------

    override suspend fun catalogs(): List<CatalogRef> = withContext(Dispatchers.IO) {
        val home = homeSections()
        home.keys.map { section ->
            CatalogRef(
                providerId = config.id,
                catalogId = "home:${section.hashCode().toUInt().toString(16)}",
                name = section,
                mediaType = MediaType.UNKNOWN,
                extra = mapOf("section" to section, "source" to "home"),
            )
        }
    }

    override suspend fun homeCatalogs(): List<CatalogRef> = catalogs()

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val section = ref.extra["section"] ?: return@withContext emptyList()
            val items = homeSections()[section] ?: emptyList()
            val pageSize = 24
            val start = (page - 1).coerceAtLeast(0) * pageSize
            items.drop(start).take(pageSize).mapNotNull { it.toMediaItem() }
        }

    override suspend fun search(query: String, page: Int): List<MediaItem> =
        withContext(Dispatchers.IO) {
            val json = runtime.invokeArrayOrNull("search", StormJson.encodeToString(listOf(query)))
                ?: return@withContext emptyList()
            parseItemArray(json).mapNotNull { it.toMediaItem() }
        }

    // ---------- detail / playback ----------

    override suspend fun getMeta(item: MediaItem): MediaItem = withContext(Dispatchers.IO) {
        val url = item.internalUrl ?: item.id
        val detail = loadDetail(url) ?: return@withContext item
        val enriched = detail.toMediaItem() ?: return@withContext item
        item.copy(
            title = enriched.title.ifBlank { item.title },
            posterUrl = enriched.posterUrl ?: item.posterUrl,
            backdropUrl = enriched.backdropUrl ?: item.backdropUrl,
            year = enriched.year ?: item.year,
            rating = enriched.rating ?: item.rating,
            description = enriched.description ?: item.description,
            genres = enriched.genres.ifEmpty { item.genres },
            tmdbId = enriched.tmdbId ?: item.tmdbId,
            imdbId = enriched.imdbId ?: item.imdbId,
            totalSeasons = enriched.totalSeasons ?: item.totalSeasons,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = withContext(Dispatchers.IO) {
        val url = item.internalUrl ?: item.id
        val detail = loadDetail(url) ?: return@withContext null
        val eps = detail["episodes"]?.jsonArray?.mapNotNull { (it as? JsonObject)?.toEpisode(item.id) }
        if (eps.isNullOrEmpty()) null else eps
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> =
        withContext(Dispatchers.IO) {
            val url = episode?.internalUrl ?: item.internalUrl ?: item.id
            val json = runtime.invokeArrayOrNull("loadStreams", StormJson.encodeToString(listOf(url)))
                ?: return@withContext emptyList()
            parseStreamArray(json)
        }

    // ---------- plugin calls ----------

    /** getHome() → { section: [items] } (or a bare array). Cached. */
    private suspend fun homeSections(): Map<String, List<JsonObject>> = homeMutex.withLock {
        homeCache?.let { return@withLock it }
        val json = runtime.invokeArrayOrNull("getHome", "[]")
            ?: return@withLock emptyMap<String, List<JsonObject>>().also { homeCache = it }
        val parsed = runCatching { StormJson.parseToJsonElement(json) }.getOrNull()
        val sections = when {
            parsed is JsonObject -> parsed.entries.associate { (k, v) ->
                k to (v as? JsonArray)?.toItemObjects().orEmpty()
            }
            parsed is JsonArray -> mapOf("Home" to parsed.toItemObjects())
            else -> emptyMap()
        }
        homeCache = sections
        sections
    }

    /** load(url) → detailed item (cached per url). */
    private suspend fun loadDetail(url: String): JsonObject? {
        detailCache[url]?.let { return it }
        val json = runtime.invokeArrayOrNull("load", StormJson.encodeToString(listOf(url)))
            ?: return null
        val parsed = runCatching { StormJson.parseToJsonElement(json) }.getOrNull()
        val obj = (parsed as? JsonObject)?.let { if (it.isEmpty()) null else it } ?: return null
        detailCache[url] = obj
        return obj
    }

    // ---------- JSON mapping ----------

    private fun JsonArray.toItemObjects(): List<JsonObject> =
        mapNotNull { (it as? JsonObject) }

    private fun JsonElement?.str(name: String): String? =
        (this as? JsonObject)?.get(name)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.toMediaItem(): MediaItem? {
        val title = str("title") ?: return null
        val url = str("url") ?: str("link") ?: ""
        return MediaItem(
            id = url.ifBlank { title },
            providerId = config.id,
            title = title,
            type = skyTypeToMediaType(str("type") ?: str("contentType")) ?: MediaType.MOVIE,
            posterUrl = str("posterUrl") ?: str("poster") ?: str("image"),
            backdropUrl = str("backgroundPosterUrl") ?: str("bannerUrl") ?: str("backdrop"),
            year = (str("year") ?: str("releaseDate")?.take(4))?.toIntOrNull(),
            rating = this["score"]?.jsonPrimitive?.doubleOrNull
                ?: this["rating"]?.jsonPrimitive?.doubleOrNull,
            description = str("description") ?: str("synopsis"),
            genres = (this["tags"] ?: this["genres"])?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: emptyList(),
            internalUrl = url.ifBlank { null },
            tmdbId = str("tmdbId") ?: str("tmdb_id"),
            imdbId = str("imdbId") ?: str("imdb_id"),
            totalSeasons = this["episodes"]?.jsonArray
                ?.mapNotNull { it.jsonObject["season"]?.jsonPrimitive?.intOrNull }
                ?.maxOrNull(),
        )
    }

    private fun JsonObject.toEpisode(showId: String): Episode? {
        val url = str("url") ?: str("link") ?: ""
        val season = this["season"]?.jsonPrimitive?.intOrNull ?: 1
        val number = this["episode"]?.jsonPrimitive?.intOrNull
            ?: this["number"]?.jsonPrimitive?.intOrNull ?: 1
        return Episode(
            id = url.ifBlank { "$showId:$season:$number" },
            providerId = config.id,
            showId = showId,
            title = str("title") ?: str("name"),
            season = season,
            number = number,
            thumbnailUrl = str("thumbnail") ?: str("image") ?: str("poster"),
            description = str("description") ?: str("overview"),
            internalUrl = url.ifBlank { null },
        )
    }

    private fun parseItemArray(json: String): List<JsonObject> {
        val parsed = runCatching { StormJson.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        return when {
            parsed is JsonArray -> parsed.toItemObjects()
            parsed is JsonObject -> listOf(parsed)
            else -> emptyList()
        }
    }

    private fun parseStreamArray(json: String): List<StreamSource> {
        val parsed = runCatching { StormJson.parseToJsonElement(json) }.getOrNull() ?: return emptyList()
        val arr = when {
            parsed is JsonArray -> parsed
            parsed is JsonObject -> parsed["streams"]?.jsonArray ?: return emptyList()
            else -> return emptyList()
        }
        return arr.mapNotNull { (it as? JsonObject)?.toStreamSource() }
    }

    private fun JsonObject.toStreamSource(): StreamSource? {
        val url = str("url") ?: str("link") ?: return null
        val subs = (this["subtitles"] as? JsonArray)?.mapNotNull { sub ->
            (sub as? JsonObject)?.let { o ->
                val subUrl = o.str("url") ?: o.str("link") ?: return@let null
                Subtitle(
                    label = o.str("label") ?: o.str("name") ?: o.str("lang") ?: "Subtitle",
                    language = o.str("lang") ?: o.str("language") ?: "und",
                    url = subUrl,
                    format = o.str("format") ?: "vtt",
                )
            }
        }.orEmpty()
        val typeStr = str("type")?.lowercase()
        return StreamSource(
            name = str("source") ?: str("name") ?: str("providerName") ?: "Stream",
            url = url,
            type = when {
                typeStr == "hls" || typeStr == "m3u8" || url.contains(".m3u8") -> StreamType.HLS
                typeStr == "dash" || typeStr == "mpd" || url.contains(".mpd") -> StreamType.DASH
                typeStr == "mp4" || url.contains(".mp4") -> StreamType.MP4
                typeStr == "mkv" || typeStr == "webm" || url.contains(".mkv") -> StreamType.MKV
                else -> StreamType.UNKNOWN
            },
            quality = str("quality") ?: str("resolution"),
            headers = (this["headers"] as? JsonObject)?.entries?.associate { (k, v) ->
                k to (v.jsonPrimitive.contentOrNull ?: "")
            } ?: emptyMap(),
            subtitles = subs,
            providerId = config.id,
        )
    }

    companion object {
        fun pluginId(packageName: String): String = "skystream:$packageName"

        fun pluginsDir(context: Context): File =
            File(context.filesDir, "skystream/plugins").apply { mkdirs() }

        fun safeDirName(name: String): String =
            name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "plugin" }

        /** Build a provider from an installed plugin directory. */
        fun fromDir(
            context: Context,
            http: StormHttpClient,
            dir: File,
        ): SkyStreamProvider {
            val manifest = runCatching {
                StormJson.decodeFromString<SkyStreamManifest>(
                    File(dir, "plugin.json").readText()
                )
            }.getOrNull() ?: SkyStreamManifest(
                packageName = dir.name,
                name = dir.name.removePrefix("sky-").replace('-', ' ').replaceFirstChar { it.uppercase() },
            )
            val packageName = manifest.packageName.ifBlank { safeDirName(manifest.name.ifBlank { dir.name }) }
            return SkyStreamProvider(context, http, packageName, manifest, dir)
        }

        /** Extension record for persistence. */
        fun extensionRecord(provider: SkyStreamProvider): InstalledExtension = InstalledExtension(
            config = provider.config,
            localPath = provider.pluginDir.absolutePath,
        )
    }
}

// ---------- helpers ----------

private fun skyTypeToMediaType(raw: String?): MediaType? = when (raw?.lowercase()?.trim()) {
    "movie", "film" -> MediaType.MOVIE
    "series", "tv", "tvshow", "show" -> MediaType.SERIES
    "anime" -> MediaType.ANIME
    "livestream", "live", "channel", "iptv" -> MediaType.IPTV
    else -> null
}
