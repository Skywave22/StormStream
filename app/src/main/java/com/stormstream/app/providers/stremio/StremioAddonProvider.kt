package com.stormstream.app.providers.stremio

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
 * Stremio addon adapter.
 *
 * Implements the v3 Stremio addon protocol:
 *  - GET /manifest.json for catalogs/meta resource types
 *  - GET /catalog/{type}/{id}.json for home rows
 *  - GET /catalog/{type}/{id}/search={query}.json for search
 *  - GET /meta/{type}/{id}.json for details
 *  - GET /stream/{type}/{id}[/{season}/{episode}].json for streams
 *
 * Reference: https://github.com/Stremio/stremio-addon-sdk
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

    override suspend fun initialize() {
        // Warm manifest — nothing else needed.
    }

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
        val root = "$baseUrl/catalog/$stype/${ref.catalogId}"
        // Stremio paginates with a "skip" extra: /catalog/{type}/{id}/skip=N.json
        val url = if (page > 1) "$root/skip=${(page - 1) * SKIP_PAGE_SIZE}.json" else "$root.json"
        val body = runCatching { http.get(url) }.getOrNull() ?: return emptyList()
        val parsed = StormJson.decodeFromString<StremioCatalogResponse>(body)
        return parsed.metas.map { it.toMediaItem(ref.providerId) }
    }

    override val searchOnly: Boolean get() = false

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        // Stremio search is exposed as an "extra" named "search" on catalogs.
        val out = mutableListOf<MediaItem>()
        val typesWeSupport = setOf("movie", "series") intersect manifest.types.toSet()
        for (c in manifest.catalogs) {
            val stype = c.type ?: if (typesWeSupport.contains("movie")) "movie" else continue
            if (stype !in typesWeSupport) continue
            val searchable = c.extraRequired?.any { it.name == "search" } == true ||
                    c.extraSupported?.any { it.name == "search" } == true
            if (!searchable && c.extraRequired != null && c.extraSupported != null) continue
            val url = "$baseUrl/catalog/$stype/${c.id}/search=${encodeSearch(query)}.json"
            val resp = runCatching {
                val body = http.get(url)
                StormJson.decodeFromString<StremioCatalogResponse>(body).metas
            }.getOrDefault(emptyList())
            out += resp.map { it.toMediaItem(config.id) }
        }
        // Some addons support search across all catalogs generically; fall back to
        // a generic movie-catalog search for minimal addons.
        if (out.isEmpty()) {
            runCatching {
                val body = http.get("$baseUrl/catalog/movie/top/search=${encodeSearch(query)}.json")
                out += StormJson.decodeFromString<StremioCatalogResponse>(body).metas
                    .map { it.toMediaItem(config.id) }
            }
        }
        return out
    }

    override suspend fun getMeta(item: MediaItem): MediaItem {
        val stype = stremioTypeFrom(item.type)
        val url = "$baseUrl/meta/$stype/${item.id}.json"
        val body = runCatching { http.get(url) }.getOrNull() ?: return item
        val parsed = StormJson.decodeFromString<StremioMetaResponse>(body)
        val meta = parsed.meta ?: return item
        return item.copy(
            title = meta.name ?: item.title,
            posterUrl = meta.poster ?: item.posterUrl,
            backdropUrl = meta.background ?: meta.banner ?: item.backdropUrl,
            year = meta.year?.toIntOrNull() ?: meta.released?.take(4)?.toIntOrNull() ?: item.year,
            rating = meta.imdbRating?.toString()?.toDoubleOrNull(),
            description = meta.description ?: item.description,
            genres = meta.genres ?: item.genres,
            totalSeasons = meta.videos?.let { vs -> vs.mapNotNull { it.season }.maxOrNull() }
                ?: item.totalSeasons,
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        if (item.type !in setOf(MediaType.SERIES, MediaType.ANIME)) return null
        // Episodes come back attached to the meta response as "videos".
        val meta = runCatching {
            val url = "$baseUrl/meta/${stremioTypeFrom(item.type)}/${item.id}.json"
            val body = http.get(url)
            StormJson.decodeFromString<StremioMetaResponse>(body).meta
        }.getOrNull()
        val videos = meta?.videos ?: return null
        return videos.filter { it.season != null && it.episode != null }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
            .map { v ->
                Episode(
                    id = "${item.id}:${v.season}:${v.episode}",
                    providerId = config.id,
                    showId = item.id,
                    title = v.title,
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
        val body = runCatching { http.get(url) }.getOrNull() ?: return emptyList()
        val resp = StormJson.decodeFromString<StremioStreamResponse>(body)
        return resp.streams.mapNotNull { s ->
            // libmpv plays direct media URLs only — skip magnets, YouTube ids and
            // watch pages (externalUrl), which a bare libmpv cannot decode.
            val streamUrl = s.url ?: return@mapNotNull null
            StreamSource(
                name = s.title ?: s.name ?: "Stream",
                url = streamUrl,
                type = when {
                    streamUrl.endsWith(".m3u8") || streamUrl.contains("m3u8") -> StreamType.HLS
                    streamUrl.endsWith(".mpd") || streamUrl.contains("mpd") -> StreamType.DASH
                    streamUrl.endsWith(".mp4") -> StreamType.MP4
                    streamUrl.endsWith(".mkv") -> StreamType.MKV
                    else -> StreamType.UNKNOWN
                },
                quality = s.qualityLabel ?: s.bitrate?.let { "${it / 1000}kbps" },
                headers = s.httpHeaders ?: s.behaviorHints?.proxyHeaders.orEmpty(),
                subtitles = s.subtitles.map { sub ->
                    Subtitle(
                        label = sub.label ?: sub.lang ?: "Subtitle",
                        language = sub.lang ?: "en",
                        url = sub.url,
                    )
                },
                providerId = config.id,
            )
        }
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
        "series" -> MediaType.SERIES
        "anime" -> MediaType.ANIME
        "tv" -> MediaType.IPTV
        "channel" -> MediaType.LIVE
        else -> null
    }

    private fun encodeSearch(q: String): String =
        URLEncoder.encode(q, "UTF-8")

    private fun baseUrlFrom(manifestUrl: String): String {
        // Strip the trailing /manifest.json to get the addon root.
        return manifestUrl.substringBeforeLast("/manifest.json")
            .substringBeforeLast("/manifest")
    }

    companion object {
        /** Items skipped per page when a catalog is paged with the skip extra. */
        private const val SKIP_PAGE_SIZE = 24

        fun fromUrl(http: StormHttpClient, url: String): StremioAddonProvider {
            val body = http.get(url)
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
            "series" -> MediaType.SERIES
            "anime" -> MediaType.ANIME
            "tv", "channel" -> MediaType.IPTV
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
    @SerialName("proxyHeaders") val proxyHeaders: Map<String, String>? = null,
)

@Serializable
data class StremioSubtitle(
    val id: String? = null,
    val url: String = "",
    val lang: String? = null,
    val label: String? = null,
)
