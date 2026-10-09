package com.stormstream.app.providers.iptv

import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.ProviderType
import com.stormstream.app.data.StreamSource
import com.stormstream.app.data.StreamType
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.providers.StreamProvider

/**
 * IPTV/M3U provider.
 *
 * Parses Extended M3U playlists (#EXTM3U, #EXTINF with tvg-logo, group-title,
 * tvg-id attributes). Supports #EXTGRP and plain non-attributed #EXTINF lines.
 * Streams are assumed to be HLS unless the URL suffix indicates otherwise.
 *
 * M3U8 playlists that do TV/Radio are very common for public IPTV lists; this
 * provider lets StormStream treat them as a first-class content source, like
 * Hikari's IPTV tab.
 */
class IptvProvider(
    private val http: StormHttpClient,
    private val playlistName: String,
    private val playlistUrl: String,
) : StreamProvider {

    override val config: ProviderConfig = ProviderConfig(
        id = "iptv:" + playlistName.lowercase().replace(Regex("[^a-z0-9]+"), "-"),
        name = playlistName,
        type = ProviderType.IPTV,
        baseUrl = playlistUrl,
        supportedMediaTypes = setOf(MediaType.IPTV, MediaType.LIVE),
    )

    @Volatile
    private var channels: List<IptvChannel> = emptyList()

    override suspend fun initialize() {
        channels = parsePlaylist(http.get(playlistUrl))
    }

    override suspend fun catalogs(): List<CatalogRef> {
        val groups = channels.map { it.group ?: "Other" }.distinct()
        val out = mutableListOf(
            CatalogRef(config.id, "all", "All Channels", MediaType.IPTV)
        )
        groups.forEach { g ->
            out += CatalogRef(
                providerId = config.id,
                catalogId = "group:${g}",
                name = g,
                mediaType = MediaType.IPTV,
                extra = mapOf("group" to g),
            )
        }
        return out
    }

    override suspend fun homeCatalogs(): List<CatalogRef> =
        // Only put "All Channels" and top few groups on Home to avoid spam.
        catalogs().take(5)

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        val filtered = when {
            ref.catalogId == "all" -> channels
            ref.catalogId.startsWith("group:") ->
                channels.filter { it.group == ref.extra["group"] }
            else -> channels
        }
        val pageSize = 50
        val start = (page - 1).coerceAtLeast(0) * pageSize
        return filtered.drop(start).take(pageSize).map { it.toMediaItem(config.id) }
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        val q = query.lowercase()
        return channels.filter { it.name.lowercase().contains(q) }
            .map { it.toMediaItem(config.id) }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val ch = channels.firstOrNull { it.id == item.id } ?: return emptyList()
        return listOf(
            StreamSource(
                name = ch.name,
                url = ch.url,
                type = when {
                    ch.url.endsWith(".m3u8") || ch.url.contains("m3u8") -> StreamType.HLS
                    ch.url.endsWith(".mpd") -> StreamType.DASH
                    ch.url.endsWith(".mp4") -> StreamType.MP4
                    ch.url.endsWith(".ts") -> StreamType.MP4
                    else -> StreamType.HLS
                },
                headers = ch.headers,
                providerId = config.id,
            )
        )
    }

    // ---------- parsing ----------

    private data class IptvChannel(
        val id: String,
        val name: String,
        val logo: String?,
        val group: String?,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
    ) {
        fun toMediaItem(pid: String): MediaItem = MediaItem(
            id = id,
            providerId = pid,
            title = name,
            type = MediaType.IPTV,
            posterUrl = logo,
            group = group,
            internalUrl = url,
        )
    }

    private fun parsePlaylist(body: String): List<IptvChannel> {
        val out = mutableListOf<IptvChannel>()
        val lines = body.lines()
        var i = 0
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingTvgId: String? = null
        while (i < lines.size) {
            val line = lines[i].trim()
            when {
                line.startsWith("#EXTM3U") -> {
                    pendingGroup = extractAttr(line, "group-title") ?: pendingGroup
                }
                line.startsWith("#EXTINF") -> {
                    val comma = line.indexOf(',')
                    pendingName = if (comma >= 0) line.substring(comma + 1).trim() else null
                    pendingLogo = extractAttr(line, "tvg-logo")
                    pendingGroup = extractAttr(line, "group-title") ?: pendingGroup
                    pendingTvgId = extractAttr(line, "tvg-id")
                }
                line.startsWith("#EXTGRP:") -> {
                    pendingGroup = line.removePrefix("#EXTGRP:").trim()
                }
                line.isBlank() || line.startsWith("#") -> { /* skip */ }
                else -> {
                    // This line is a URL.
                    val url = line
                    val name = pendingName ?: url.substringAfterLast('/').substringBefore('?')
                    out += IptvChannel(
                        id = "ch:" + (pendingTvgId ?: url.hashCode().toString()),
                        name = name,
                        logo = pendingLogo,
                        group = pendingGroup,
                        url = url,
                    )
                    pendingName = null
                    pendingLogo = null
                }
            }
            i++
        }
        return out
    }

    private fun extractAttr(line: String, key: String): String? {
        val pattern = Regex("""$key="([^"]*)"""")
        return pattern.find(line)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }
}
