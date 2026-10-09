package com.stormstream.app.providers.stremio

import android.util.Log
import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.StreamSource
import com.stormstream.app.data.StreamType
import com.stormstream.app.data.Subtitle
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.StreamProvider
import com.stormstream.app.util.StormJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/**
 * Stremio addon adapter implementing the v3 protocol:
 *  - GET /manifest.json for catalogs/meta resource types
 *  - GET /catalog/{type}/{id}.json for home rows
 *  - GET /catalog/{type}/{id}/search={query}.json for search
 *  - GET /meta/{type}/{id}.json for details
 *  - GET /stream/{type}/{id}[/{season}/{episode}].json for streams
 *
 * Phase 1 fixes:
 *  - Uses StormHttpClient's async result type instead of throwing on HTTP errors.
 *  - Search fallbacks fixed (no more imaginary "top" catalog).
 *  - Better type mapping, supports "anime" type, skips torrent-only streams safely.
 *  - Handles behaviorHints.notWebReady.
 */
class StremioAddonProvider(
    private val http: StormHttpClient,
    private val manifest: StremioManifest,
    private val manifestUrl: String,
) : StreamProvider {

    override val config: ProviderConfig = ProviderConfig(
        id = "stremio:${manifest.id}",
        name = manifest.name ?: "Stremio Addon",
        type = ProviderType.STREMIO,
        icon = manifest.logo ?: manifest.icon,
        baseUrl = baseUrlFrom(manifestUrl),
        sourceUrl = manifestUrl,
        version = manifest.version,
        supportedMediaTypes = manifest.types.mapNotNull { it.toMediaType() }.toSet()
            .ifEmpty { setOf(MediaType.MOVIE, MediaType.SERIES) },
    )

    private val baseUrl: String = baseUrlFrom(manifestUrl)

    override suspend fun initialize() {}

    override suspend fun catalogs(): List<CatalogRef> = manifest.catalogs.map { c ->
        val mt = c.type?.toMediaType() ?: MediaType.MOVIE
        CatalogRef(
            providerId = config.id,
            catalogId = c.id,
            name = c.name ?: c.id,
            mediaType = mt,
            extra = mapOf("stremioType" to (c.type ?: "movie")),
        )
    }

    override fun isAdultExtension(): Boolean = manifest.adult == true

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        val stype = ref.extra["stremioType"] ?: "movie"
        val url = "$baseUrl/catalog/$stype/${ref.catalogId}.json"
        val body = (http.get(url, timeoutMs = 12_000L) as? StormHttpClient.StormHttpResult.Ok)?.body
            ?: return emptyList()
        return runCatching {
            StormJson.decodeFromString<StremioCatalogResponse>(body).metas
                .map { it.toMediaItem(ref.providerId) }
        }.onFailure { Log.w(TAG, "Parse fail for $url", it) }.getOrDefault(emptyList())
    }

    override val searchOnly: Boolean get() = false

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        val out = mutableListOf<MediaItem>()
        val encoded = encodeSearch(query)
        for (c in manifest.catalogs) {
            val stype = c.type ?: continue
            val canSearch = c.extraSupported?.any { it.name == "search" } == true ||
                    c.extraRequired?.any { it.name == "search" } == true
            if (!canSearch) continue
            val url = "$baseUrl/catalog/$stype/${c.id}/search=$encoded.json"
            val resp = (http.get(url, timeoutMs = 8_000L) as? StormHttpClient.StormHttpResult.Ok)?.body
                ?: continue
            val metas = runCatching {
                StormJson.decodeFromString<StremioCatalogResponse>(resp).metas
            }.onFailure { Log.w(TAG, "Search parse fail for $url", it) }.getOrDefault(emptyList())
            out += metas.map { it.toMediaItem(config.id) }
        }
        return out
    }

    override suspend fun getMeta(item: MediaItem): MediaItem {
        val stype = stremioTypeFrom(item.type)
        val url = "$baseUrl/meta/$stype/${item.id}.json"
        val body = (http.get(url, timeoutMs = 8_000L) as? StormHttpClient.StormHttpResult.Ok)?.body
            ?: return item
        val parsed = runCatching {
            StormJson.decodeFromString<StremioMetaResponse>(body).meta
        }.getOrNull() ?: return item
        return item.copy(
            title = parsed.name ?: item.title,
            posterUrl = parsed.poster ?: item.posterUrl,
            backdropUrl = parsed.background ?: parsed.banner ?: item.backdropUrl,
            year = parsed.year?.toIntOrNull()
                ?: parsed.released?.take(4)?.toIntOrNull() ?: item.year,
            rating = parsed.imdbRating,
            description = parsed.description ?: item.description,
            genres = parsed.genres ?: item.genres,
            totalSeasons = parsed.videos?.mapNotNull { it.season }?.maxOrNull() ?: item.totalSeasons,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        if (item.type !in setOf(MediaType.SERIES, MediaType.ANIME)) return null
        val url = "$baseUrl/meta/${stremioTypeFrom(item.type)}/${item.id}.json"
        val body = (http.get(url, timeoutMs = 10_000L) as? StormHttpClient.StormHttpResult.Ok)?.body
            ?: return null
        val meta = runCatching {
            StormJson.decodeFromString<StremioMetaResponse>(body).meta
        }.getOrNull() ?: return null
        val videos = meta.videos ?: return null
        return videos.filter { it.season != null && it.episode != null }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.released ?: "" }))
            .map { v ->
                Episode(
                    id = "${item.id}:${v.season}:${v.episode}",
                    providerId = config.id,
                    showId = item.id,
                    title = v.title ?: "Episode ${v.episode}",
                    season = v.season ?: 1,
                    number = v.episode ?: 1,
                    thumbnailUrl = v.thumbnail,
                    description = v.overview,
                    internalUrl = "${v.id}|${v.season}|${v.episode}",
                )
            }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val stype = stremioTypeFrom(item.type)
        val url = if (episode != null) {
            val parts = episode.internalUrl?.split("|").orEmpty()
            val vidId = parts.getOrNull(0) ?: "${item.id}:${episode.season}:${episode.number}"
            "$baseUrl/stream/$stype/$vidId.json"
        } else {
            "$baseUrl/stream/$stype/${item.id}.json"
        }
        val body = (http.get(url, timeoutMs = 10_000L) as? StormHttpClient.StormHttpResult.Ok)?.body
            ?: return emptyList()
        val resp = runCatching {
            StormJson.decodeFromString<StremioStreamResponse>(body)
        }.getOrNull() ?: return emptyList()
        return resp.streams.mapNotNull { s ->
            val streamUrl = when {
                !s.url.isNullOrBlank() -> s.url
                !s.externalUrl.isNullOrBlank() -> s.externalUrl
                s.ytId != null -> "https://www.youtube.com/watch?v=${s.ytId}"
                // Torrents (infoHash) are not web-ready; skip unless explicitly marked.
                s.infoHash != null && s.behaviorHints?.notWebReady != true ->
                    "magnet:?xt=urn:btih:${s.infoHash}"
                else -> return@mapNotNull null
            }
            // Skip torrents that aren't web-ready (need a debrid service we don't have)
            if (s.infoHash != null && s.behaviorHints?.notWebReady == true)
                return@mapNotNull null
            val name = s.title ?: s.name ?: "Stream"
            val type = inferType(streamUrl)
            StreamSource(
                name = name,
                url = streamUrl,
                type = type,
                quality = s.qualityLabel ?: s.bitrate?.let { "${it / 1000}kbps" },
                headers = s.httpHeaders.orEmpty(),
                subtitles = s.subtitles.mapNotNull { sub ->
                    if (sub.url.isBlank()) null
                    else Subtitle(
                        label = sub.label ?: sub.lang ?: "Subtitle",
                        language = sub.lang ?: "en",
                        url = sub.url,
                    )
                },
                providerId = config.id,
            )
        }
    }

    private fun inferType(url: String): StreamType = when {
        url.endsWith(".m3u8") || url.contains("m3u8") -> StreamType.HLS
        url.endsWith(".mpd") || url.contains(".mpd") -> StreamType.DASH
        url.endsWith(".mp4") || url.contains(".mp4") -> StreamType.MP4
        url.endsWith(".mkv") || url.contains(".mkv") -> StreamType.MKV
        url.startsWith("magnet:") -> StreamType.UNKNOWN // not directly playable
        url.startsWith("https://www.youtube.com") -> StreamType.UNKNOWN
        else -> StreamType.HLS
    }

    private fun stremioTypeFrom(mt: MediaType): String = when (mt) {
        MediaType.MOVIE -> "movie"
        MediaType.SERIES, MediaType.ANIME -> "series"
        MediaType.IPTV, MediaType.LIVE -> "tv"
        MediaType.MANGA -> "movie"
        MediaType.UNKNOWN -> "movie"
    }

    private fun String.toMediaType(): MediaType? = when (this) {
        "movie" -> MediaType.MOVIE
        "series", "tv" -> MediaType.SERIES
        "anime" -> MediaType.ANIME
        "channel" -> MediaType.LIVE
        else -> null
    }

    private fun encodeSearch(q: String): String =
        URLEncoder.encode(q, "UTF-8")

    private fun baseUrlFrom(manifestUrl: String): String =
        manifestUrl.substringBeforeLast("/manifest.json")
            .substringBeforeLast("/manifest")

    companion object {
        private const val TAG = "StormStremio"
        suspend fun fromUrl(http: StormHttpClient, url: String): StremioAddonProvider {
            val body = when (val r = http.get(url, timeoutMs = 10_000L)) {
                is StormHttpClient.StormHttpResult.Ok -> r.body
                is StormHttpClient.StormHttpResult.Err ->
                    throw RuntimeException("Failed to fetch manifest: ${r.message}")
            }
            val manifest = StormJson.decodeFromString<StremioManifest>(body)
            return StremioAddonProvider(http, manifest, url)
        }
    }
}

// ---------- Stremio JSON schema ----------

@Serializable
data class StremioManifest(
    val id: String = "",
    val name: String? = null,
    val description: String? = null,
    val version: String? = null,
    val logo: String? = null,
    val icon: String? = null,
    val background: String? = null,
    val types: List<String> = emptyList(),
    val catalogs: List<StremioCatalogDef> = emptyList(),
    val adult: Boolean? = null,
)

@Serializable
data class StremioCatalogDef(
    val id: String,
    val name: String? = null,
    val type: String? = null,
    @SerialName("extraRequired") val extraRequired: List<StremioExtraDef>? = null,
    @SerialName("extraSupported") val extraSupported: List<StremioExtraDef>? = null,
)

@Serializable
data class StremioExtraDef(
    val name: String,
    val isRequired: Boolean? = null,
    val options: List<String>? = null,
)

@Serializable
data class StremioCatalogResponse(
    val metas: List<StremioMeta> = emptyList(),
)

@Serializable
data class StremioMetaResponse(
    val meta: StremioMeta? = null,
)

@Serializable
data class StremioMeta(
    val id: String = "",
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val year: String? = null,
    val released: String? = null,
    val description: String? = null,
    val genres: List<String>? = null,
    val imdbRating: Double? = null,
    val videos: List<StremioVideo>? = null,
) {
    fun toMediaItem(providerId: String): MediaItem {
        val mt = when (type) {
            "movie" -> MediaType.MOVIE
            "series", "tv" -> MediaType.SERIES
            "anime" -> MediaType.ANIME
            "channel" -> MediaType.LIVE
            else -> MediaType.MOVIE
        }
        return MediaItem(
            id = id,
            providerId = providerId,
            title = name ?: "(untitled)",
            type = mt,
            posterUrl = poster,
            backdropUrl = background ?: banner,
            year = year?.toIntOrNull() ?: released?.take(4)?.toIntOrNull(),
            rating = imdbRating,
            description = description,
            genres = genres.orEmpty(),
        )
    }
}

@Serializable
data class StremioVideo(
    val id: String = "",
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val thumbnail: String? = null,
    val overview: String? = null,
    val released: String? = null,
)

@Serializable
data class StremioStreamResponse(
    val streams: List<StremioStream> = emptyList(),
)

@Serializable
data class StremioStream(
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val externalUrl: String? = null,
    val title: String? = null,
    val name: String? = null,
    val qualityLabel: String? = null,
    val bitrate: Long? = null,
    @SerialName("behaviorHints") val behaviorHints: StremioBehaviorHints? = null,
    @SerialName("httpHeaders") val httpHeaders: Map<String, String>? = null,
    val subtitles: List<StremioSubtitle> = emptyList(),
)

@Serializable
data class StremioBehaviorHints(
    val bingeGroup: String? = null,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
    val notWebReady: Boolean? = null,
)

@Serializable
data class StremioSubtitle(
    val id: String? = null,
    val url: String = "",
    val lang: String? = null,
    val label: String? = null,
)
