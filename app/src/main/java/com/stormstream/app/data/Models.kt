package com.stormstream.app.data

import kotlinx.serialization.Serializable

/**
 * Shared data models for StormStream.
 *
 * Every provider — Stremio, universal scrapers, CloudStream .cs3, Vega,
 * SkyStream, Sora, Aniyomi, Nuvio, IPTV, Manga, Storm native — is adapted into
 * these shapes so the rest of the UI/player only sees one vocabulary.
 */

enum class MediaType {
    MOVIE, SERIES, ANIME, MANGA, IPTV, LIVE, UNKNOWN
}

enum class StreamType {
    HLS, DASH, MP4, MKV, SUBTITLE_ONLY, UNKNOWN
}

@Serializable
data class ProviderConfig(
    /** Stable identifier, unique across all installed providers. */
    val id: String,
    /** Human-readable name shown in UI. */
    val name: String,
    /** What kind of backend this provider uses (stremio, cs3, vega, ...). */
    val type: ProviderType,
    /** Optional icon URL. */
    val icon: String? = null,
    /** Base URL for remote addons/plugins; null for local-on-device plugins. */
    val baseUrl: String? = null,
    /** The source URL from which the extension was installed (repo JSON, addon URL, .cs3 file, etc). */
    val sourceUrl: String? = null,
    /** Installed version string, if the extension reports one. */
    val version: String? = null,
    /** Media types this provider can serve. */
    val supportedMediaTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.SERIES),
    /** Whether the user has enabled this provider. */
    val enabled: Boolean = true,
    /** Whether this provider's content is NSFW. */
    val adult: Boolean = false,
)

@Serializable
enum class ProviderType(val key: String) {
    STREMIO("stremio"),
    UNIVERSAL_SCRAPER("scraper"),
    CS3("cs3"),           // CloudStream .cs3
    VEGA("vega"),         // vega-providers CommonJS
    SKYSTREAM("skystream"),
    SORA("sora"),
    ANIYOMI("aniyomi"),
    NUVIO("nuvio"),       // JS rule scrapers (QuickJS)
    IPTV("iptv"),         // M3U/M3U8 playlists + Xtream Codes
    MANGA("manga"),
    STORM("storm");       // Native Kotlin .storm extensions
}

@Serializable
data class CatalogRef(
    val providerId: String,
    val catalogId: String,
    val name: String,
    val mediaType: MediaType,
    /** Extra JSON-encoded metadata used by the provider (e.g. Stremio's catalog types). */
    val extra: Map<String, String> = emptyMap(),
)

@Serializable
data class MediaItem(
    /** Opaque ID that the originating provider can re-resolve. */
    val id: String,
    val providerId: String,
    val title: String,
    val type: MediaType,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    /** Optional internal URL/link the provider needs to fetch meta/streams. */
    val internalUrl: String? = null,
    /** For series/anime: season/episode count known ahead of time, if any. */
    val totalSeasons: Int? = null,
    /** For IPTV only: channel group. */
    val group: String? = null,
)

@Serializable
data class Episode(
    val id: String,
    val providerId: String,
    val showId: String,
    val title: String?,
    val season: Int,
    val number: Int,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    /** Provider's internal handle (URL/ID) for resolving streams of this episode. */
    val internalUrl: String? = null,
)

@Serializable
data class Subtitle(
    val label: String,
    val language: String,
    val url: String,
    val format: String = "vtt",
)

@Serializable
data class StreamSource(
    val name: String,
    val url: String,
    val type: StreamType,
    val quality: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<Subtitle> = emptyList(),
    /** Which provider resolved this stream, for UI attribution. */
    val providerId: String,
)

@Serializable
data class RepoIndex(
    val name: String,
    val description: String? = null,
    val plugins: List<RepoPlugin> = emptyList(),
    /** For CloudStream compat repos. */
    val pluginLists: List<String> = emptyList(),
)

@Serializable
data class RepoPlugin(
    val name: String,
    val url: String,
    val version: Int = 1,
    val tvTypes: List<String> = emptyList(),
    val icon: String? = null,
    val providerType: String? = null,
)

/** Extension installed on the device: a record in DataStore. */
@Serializable
data class InstalledExtension(
    val config: ProviderConfig,
    /** Local path to the file (plugin JAR/JS/JSON), null for remote-only addons like Stremio. */
    val localPath: String? = null,
    /** For Stremio addons, the manifest URL; for IPTV, the playlist URL; for scrapers, the inline JSON is in localPath's file */
    val sourceUrl: String? = config.sourceUrl,
    /** Inline config (e.g. scraper JSON) so we don't need filesystem writes for small configs. */
    val inlineConfig: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
)

/** Identifies what the player should currently be playing. Held inside AppViewModel. */
data class PlaybackTarget(
    val item: MediaItem,
    val episode: Episode?,
    val streams: List<StreamSource>,
    val selectedIndex: Int = 0,
) {
    val selectedStream: StreamSource?
        get() = streams.getOrNull(selectedIndex)
}

/**
 * A single watch-history record. Stored in DataStore as a JSON list.
 *
 * [positionMs] is the last playback position so we can resume. [updatedAt]
 * lets us order the "Continue watching" row newest-first.
 */
@Serializable
data class HistoryEntry(
    /** Stable key: providerId + itemId for movies, +episode id for series. */
    val key: String,
    val item: MediaItem,
    val episode: Episode?,
    val lastStreamUrl: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float get() = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val completed: Boolean get() = durationMs > 0L && positionMs >= durationMs - 30_000L
}

/** A bookmarked ("Watch later") item. */
@Serializable
data class BookmarkEntry(
    val key: String,
    val item: MediaItem,
    val addedAt: Long = System.currentTimeMillis(),
)
