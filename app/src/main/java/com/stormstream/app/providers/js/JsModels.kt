package com.stormstream.app.providers.js

import com.stormstream.app.data.MediaType
import com.stormstream.app.data.StreamType
import kotlinx.serialization.Serializable

/**
 * Data shapes exchanged with JavaScript plugins.
 *
 * Plugins always speak JSON: the runtime calls plugin functions with a JSON
 * argument string and expects a JSON result string back. These DTOs describe
 * the "StormJS" contract (see assets/storm-js-shim.js and the README).
 */

@Serializable
data class JsCatalog(
    val id: String,
    val name: String = id,
    val type: String = "movie",
)

@Serializable
data class JsItem(
    val id: String = "",
    val title: String = "",
    val type: String = "movie",
    val poster: String? = null,
    val backdrop: String? = null,
    val year: Int? = null,
    val rating: Double? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    /** Internal link the provider needs for meta/stream resolution. */
    val url: String? = null,
)

@Serializable
data class JsEpisode(
    val id: String = "",
    val title: String? = null,
    val season: Int = 1,
    val number: Int = 1,
    val thumbnail: String? = null,
    val description: String? = null,
    val url: String? = null,
)

@Serializable
data class JsStream(
    val name: String = "Stream",
    val url: String? = null,
    /** "hls" | "dash" | "mp4" | "mkv" | "auto" — inferred from the URL when "auto". */
    val type: String = "auto",
    val quality: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<JsSubtitle> = emptyList(),
)

@Serializable
data class JsSubtitle(
    val label: String? = null,
    val lang: String? = null,
    val url: String? = null,
    val format: String = "vtt",
)

/** Result of loading a plugin: dialect + self-reported metadata. */
@Serializable
data class JsPluginInfo(
    val dialect: String? = null,
    val name: String? = null,
    val version: String? = null,
    val description: String? = null,
    val icon: String? = null,
    val adult: Boolean = false,
)

fun mediaTypeFromString(s: String?): MediaType? = when (s?.lowercase()?.trim()) {
    "movie" -> MediaType.MOVIE
    "series", "tv" -> MediaType.SERIES
    "anime" -> MediaType.ANIME
    "manga" -> MediaType.MANGA
    "iptv", "channel" -> MediaType.IPTV
    "live" -> MediaType.LIVE
    else -> null
}

fun streamTypeFromString(s: String?, url: String): StreamType = when (s?.lowercase()?.trim()) {
    "hls", "m3u8" -> StreamType.HLS
    "dash", "mpd" -> StreamType.DASH
    "mp4" -> StreamType.MP4
    "mkv", "webm" -> StreamType.MKV
    "subtitle", "sub" -> StreamType.SUBTITLE_ONLY
    else -> when {
        url.contains(".m3u8") -> StreamType.HLS
        url.contains(".mpd") -> StreamType.DASH
        url.contains(".mp4") -> StreamType.MP4
        url.contains(".mkv") || url.contains(".webm") -> StreamType.MKV
        else -> StreamType.UNKNOWN
    }
}

/** Escape a Kotlin string as a JavaScript string literal (single-quoted). */
fun String.toJsStringLiteral(): String = buildString {
    append('\'')
    for (c in this@toJsStringLiteral) {
        when (c) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\u2028' -> append("\\u2028")
            '\u2029' -> append("\\u2029")
            else -> append(c)
        }
    }
    append('\'')
}

fun slugify(name: String): String =
    name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "plugin" }
