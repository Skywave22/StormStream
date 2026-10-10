package com.stormstream.app.data

import kotlinx.serialization.Serializable

/**
 * Shared data models for StormStream.
 *
 * Every provider — Stremio addons, universal scrapers, IPTV playlists and
 * JavaScript plugins — is adapted into these shapes so the rest of the app
 * (UI, persistence, player) only ever sees one vocabulary.
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
    /** What kind of backend this provider uses. */
    val type: ProviderType,
    /** Optional icon URL. */
    val icon: String? = null,
    /** Base URL for remote addons/plugins; null for local-only plugins. */
    val baseUrl: String? = null,
    /** The source URL from which the extension was installed. */
    val sourceUrl: String? = null,
    /** Installed version string, if the extension reports one. */
    val version: String? = null,
    /** Media types this provider can serve. */
    val supportedMediaTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.SERIES),
    /** Whether the user has enabled this provider. */
    val enabled: Boolean = true,
    /** Whether this provider's content is NSFW. */
    val adult: Boolean = false,
    /** Opaque per-type metadata (SkyStream packageName, Nuvio scraperId, …). */
    val extra: Map<String, String> = emptyMap(),
)

/**
 * The extension systems StormStream can actually install and run:
 *  - [STREMIO]  — any Stremio v3 addon (manifest.json URL)
 *  - [SCRAPER]  — universal no-code HTML/JSON scraper configs
 *  - [IPTV]     — M3U/M3U8 playlists
 *  - [JS]       — JavaScript plugins running in the built-in QuickJS runtime
 *                 (StormJS API, with a compatibility bridge for JSON-based
 *                 Vega-style providers)
 */
@Serializable
enum class ProviderType(val key: String) {
    STREMIO("stremio"),
    SCRAPER("scraper"),
    IPTV("iptv"),
    JS("js"),
    SKYSTREAM("skystream"),
    NUVIO("nuvio");

    companion object {
        fun fromKey(key: String?): ProviderType? =
            key?.lowercase()?.let { k -> entries.firstOrNull { it.key == k } }

        /**
         * Map an extension-repo `providerType` string (which may name any
         * ecosystem: vega, nuvio, sora, skystream, cloudstream, ...) onto a
         * type StormStream can install. Ecosystems we run through the JS
         * runtime map to [JS]; genuinely unknown values also map to [JS] and
         * fail with a clear error at load time if the file is not JavaScript.
         */
        fun fromRepoType(raw: String?): ProviderType? = when (raw?.lowercase()?.trim()) {
            "stremio", "stremio-addon", "stremio_addon", "addon" -> STREMIO
            "scraper", "universal", "universal_scraper", "html", "json" -> SCRAPER
            "iptv", "m3u", "m3u8", "playlist" -> IPTV
            "js", "storm", "stormjs", "javascript" -> JS
            "skystream", "sky", "skystream-extension" -> SKYSTREAM
            "nuvio", "nuvio-plugin", "nuvio-scraper" -> NUVIO
            else -> null
        }
    }
}

@Serializable
data class CatalogRef(
    val providerId: String,
    val catalogId: String,
    val name: String,
    val mediaType: MediaType,
    /** Extra metadata used by the provider (opaque to the UI). */
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
    /** For series/anime: season count known ahead of time, if any. */
    val totalSeasons: Int? = null,
    /** For IPTV only: channel group. */
    val group: String? = null,
    /** TMDB id when known (Nuvio plugins resolve streams by TMDB id). */
    val tmdbId: String? = null,
    /** IMDb id when known (TMDB find-by-imdb fallback). */
    val imdbId: String? = null,
    /** Cast names, when the provider reports them. */
    val cast: List<String> = emptyList(),
    /** Runtime label ("1h 42m"), when the provider reports it. */
    val runtime: String? = null,
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
    val name: String = "",
    val description: String? = null,
    val plugins: List<RepoPlugin> = emptyList(),
    /** For CloudStream-compat repos: URLs of plugin list documents. */
    val pluginLists: List<String> = emptyList(),
    /** SkyStream-style: nested repository URLs to merge in. */
    val repos: List<String> = emptyList(),
)

@Serializable
data class RepoPlugin(
    val name: String,
    val url: String,
    val version: Int = 1,
    val tvTypes: List<String> = emptyList(),
    val icon: String? = null,
    val providerType: String? = null,
    val description: String? = null,
    /**
     * For multi-file JavaScript plugins (e.g. Vega-style providers split into
     * catalog/posts/meta/stream modules): module name → file URL.
     */
    val files: Map<String, String> = emptyMap(),
    /** SkyStream entries: the plugin's packageName (identity for .sky installs). */
    val packageName: String? = null,
    /** SkyStream entries: inline plugin.json manifest content. */
    val manifest: String? = null,
)

/** An extension installed on the device — persisted in DataStore. */
@Serializable
data class InstalledExtension(
    val config: ProviderConfig,
    /** Local directory/file holding downloaded plugin data, null for remote-only addons. */
    val localPath: String? = null,
    val installedAt: Long = System.currentTimeMillis(),
)

/** A repository the user added, persisted in DataStore. */
@Serializable
data class RepoEntry(
    val url: String,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * Manifest for a JavaScript plugin (`storm.plugin.json`). A plugin may also be
 * a single `.js` file, in which case the manifest is synthesized from the
 * plugin's own exports.
 */
@Serializable
data class JsPluginManifest(
    val name: String,
    val version: String? = null,
    val description: String? = null,
    val icon: String? = null,
    /** "storm" (native StormJS API) or "vega" (compat bridge). Auto-detected when null. */
    val dialect: String? = null,
    val adult: Boolean = false,
    /** Entry file name when the plugin has several modules. */
    val main: String? = null,
    /** module name → file URL, for multi-file plugins. */
    val files: Map<String, String> = emptyMap(),
    val types: List<String> = emptyList(),
)

/**
 * A declarative settings field a plugin can declare. SkyStream manifests
 * declare `settings[]` statically; Nuvio scrapers return an equivalent layout
 * from `onSettings()`. The app renders these as the extension's settings
 * dialog and persists the values per extension.
 */
@Serializable
data class PluginSettingField(
    val key: String,
    val title: String,
    /** "bool" | "select" | "text" | "url" | "info" */
    val type: String = "text",
    val defaultValue: String = "",
    val options: List<PluginSettingOption> = emptyList(),
    val description: String? = null,
)

@Serializable
data class PluginSettingOption(
    val label: String,
    val value: String,
)

/**
 * SkyStream extension manifest (`plugin.json` inside a `.sky` package).
 */
@Serializable
data class SkyStreamManifest(
    val packageName: String = "",
    val name: String = "",
    val version: String = "",
    val baseUrl: String? = null,
    val mainUrl: String? = null,
    val languages: List<String> = emptyList(),
    val supportedTypes: List<String> = emptyList(),
    val settings: List<PluginSettingField> = emptyList(),
    val icon: String? = null,
    val description: String? = null,
)

/**
 * Nuvio extension manifest (`manifest.json` listing one or more scrapers).
 */
@Serializable
data class NuvioManifest(
    val name: String = "",
    val version: String = "",
    val description: String? = null,
    val author: String? = null,
    val scrapers: List<NuvioScraperDef> = emptyList(),
)

@Serializable
data class NuvioScraperDef(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val version: String = "",
    val filename: String = "",
    val supportedTypes: List<String> = listOf("movie", "tv"),
    val enabled: Boolean = true,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    val contentLanguage: List<String>? = null,
)
