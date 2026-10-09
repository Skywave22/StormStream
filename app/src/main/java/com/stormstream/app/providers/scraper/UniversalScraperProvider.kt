package com.stormstream.app.providers.scraper

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
import com.stormstream.app.util.StormJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

/**
 * Universal no-code scraper provider.
 *
 * Two modes:
 *   - HTML mode: CSS selectors against Jsoup-parsed pages.
 *   - JSON mode: JSONPath-like dotted paths against fetched JSON.
 *
 * The provider is fully described by a [UniversalScraperConfig] JSON that the
 * user pastes in Settings → Add scraper (same idea as Hikari's scrapers).
 */
class UniversalScraperProvider(
    private val http: StormHttpClient,
    private val cfg: UniversalScraperConfig,
) : StreamProvider {

    override val config: ProviderConfig = ProviderConfig(
        id = "scraper:${cfg.id ?: cfg.name}",
        name = cfg.name,
        type = ProviderType.UNIVERSAL_SCRAPER,
        icon = cfg.icon,
        baseUrl = cfg.baseUrl,
        supportedMediaTypes = cfg.catalogs.map { it.mediaType }.toSet(),
    )

    private val baseUrl: String get() = cfg.baseUrl.trimEnd('/')

    override suspend fun catalogs(): List<CatalogRef> = cfg.catalogs.map { c ->
        CatalogRef(
            providerId = config.id,
            catalogId = c.id,
            name = c.name,
            mediaType = c.mediaType,
        )
    }

    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> {
        val cat = cfg.catalogs.first { it.id == ref.catalogId }
        val url = buildUrl(cat.path.orEmpty(), page)
        return scrapeList(url, cat.itemSelectors, ref.mediaType)
    }

    override suspend fun search(query: String, page: Int): List<MediaItem> {
        val s = cfg.search ?: return emptyList()
        val url = buildUrl(s.path, page)
            .replace("{query}", URLEncoder.encode(query, "UTF-8"))
        return scrapeList(url, s.itemSelectors, MediaType.MOVIE)
    }

    override suspend fun getMeta(item: MediaItem): MediaItem {
        val d = cfg.detail ?: return item
        val url = baseUrl + (item.internalUrl ?: return item)
        val doc = fetchDoc(url)
        return item.copy(
            description = selectText(doc, d.description),
            rating = selectText(doc, d.rating)?.toDoubleOrNull(),
            genres = selectList(doc, d.genres),
            year = selectText(doc, d.year)?.take(4)?.toIntOrNull(),
            backdropUrl = selectAttr(doc, d.backdrop, "src")?.let { http.resolve(url, it) },
        )
    }

    override suspend fun getEpisodes(item: MediaItem): List<Episode>? {
        val e = cfg.episodes ?: return null
        val url = baseUrl + (item.internalUrl ?: return null)
        val doc = fetchDoc(url)
        val items = doc.select(e.rowSelector)
        var n = 0
        return items.mapNotNull { el ->
            n++
            val epNum = selectText(el, e.number)?.toIntOrNull() ?: n
            val season = selectText(el, e.season)?.toIntOrNull() ?: 1
            val link = selectAttr(el, e.link, "href") ?: return@mapNotNull null
            val title = selectText(el, e.title) ?: "Episode $epNum"
            Episode(
                id = "$url#ep$season-$epNum",
                providerId = config.id,
                showId = item.id,
                title = title,
                season = season,
                number = epNum,
                thumbnailUrl = selectAttr(el, e.image, "src")?.let { http.resolve(url, it) },
                internalUrl = link,
            )
        }
    }

    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> {
        val s = cfg.streams ?: return emptyList()
        val link = episode?.internalUrl ?: item.internalUrl ?: return emptyList()
        val url = if (link.startsWith("http")) link else baseUrl + link
        val doc = fetchDoc(url)
        val items = doc.select(s.rowSelector)
        return items.mapNotNull { el ->
            val rawUrl = selectAttr(el, s.link, "href")
                ?: selectAttr(el, s.link, "src")
                ?: return@mapNotNull null
            val resolved = if (rawUrl.startsWith("http")) rawUrl else http.resolve(url, rawUrl)
            val name = selectText(el, s.name) ?: "Server"
            val type = when {
                resolved.contains(".m3u8") -> StreamType.HLS
                resolved.contains(".mpd") -> StreamType.DASH
                resolved.contains(".mp4") -> StreamType.MP4
                resolved.contains(".mkv") -> StreamType.MKV
                else -> StreamType.HLS
            }
            StreamSource(
                name = name,
                url = resolved,
                type = type,
                quality = selectText(el, s.quality),
                headers = s.headers.orEmpty(),
                subtitles = emptyList(),
                providerId = config.id,
            )
        }
    }

    // ---------- helpers ----------

    private fun buildUrl(path: String, page: Int): String {
        val p = path.ifEmpty { "/" }
        val url = when {
            p.startsWith("http") -> p
            else -> baseUrl + if (p.startsWith("/")) p else "/$p"
        }
        return url
            .replace("{page}", page.toString())
            .replace("{pagePlus1}", (page + 1).toString())
    }

    private fun fetchDoc(url: String): Document =
        Jsoup.connect(url)
            .userAgent(StormHttpClient.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()

    private fun scrapeList(url: String, sel: ItemSelectors, defaultType: MediaType): List<MediaItem> {
        val doc = runCatching {
            if (cfg.mode == ScraperMode.JSON) return scrapeJsonList(url, sel, defaultType)
            Jsoup.connect(url)
                .userAgent(StormHttpClient.USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                .get()
        }.getOrNull() ?: return emptyList()
        val rows = doc.select(sel.row)
        return rows.mapNotNull { el ->
            val title = selectText(el, sel.title) ?: return@mapNotNull null
            val link = selectAttr(el, sel.link, "href") ?: return@mapNotNull null
            val poster = selectAttr(el, sel.image, "src")
                ?: selectAttr(el, sel.image, "data-src")
            MediaItem(
                id = "$url#${link.hashCode()}",
                providerId = config.id,
                title = clean(title) ?: title,
                type = defaultType,
                posterUrl = poster?.let { http.resolve(url, it) },
                internalUrl = link,
                year = selectText(el, sel.year)?.take(4)?.toIntOrNull(),
                rating = selectText(el, sel.rating)?.toDoubleOrNull(),
            )
        }
    }

    /** JSON-API mode: expects responses shaped as arrays of objects with simple keys. */
    private fun scrapeJsonList(url: String, sel: ItemSelectors, defaultType: MediaType): List<MediaItem> {
        val body = http.get(url)
        val arr = runCatching {
            val el = StormJson.parseToJsonElement(body)
            when {
                sel.jsonRoot != null -> el.jsonObject[sel.jsonRoot]?.let {
                    StormJson.decodeFromJsonElement<List<JsonObject>>(it)
                } ?: emptyList()
                el is JsonArray ->
                    StormJson.decodeFromJsonElement<List<JsonObject>>(el)
                else -> emptyList()
            }
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { obj ->
            val title = obj[sel.title.jsonKey]?.jsonPrimitive?.content ?: return@mapNotNull null
            val link = obj[sel.link.jsonKey]?.jsonPrimitive?.content ?: return@mapNotNull null
            val poster = sel.image.jsonKey?.let { obj[it]?.jsonPrimitive?.content }
            MediaItem(
                id = "$url#${link.hashCode()}",
                providerId = config.id,
                title = title,
                type = defaultType,
                posterUrl = poster,
                internalUrl = link,
                year = sel.year?.jsonKey?.let { obj[it]?.jsonPrimitive?.content?.take(4)?.toIntOrNull() },
            )
        }
    }

    private fun selectText(el: Element, s: Selector?): String? {
        if (s == null || s.selector.isNullOrBlank()) return null
        return clean(el.selectFirst(s.selector)?.text())
    }
    private fun selectAttr(el: Element, s: Selector?, attr: String): String? {
        if (s == null || s.selector.isNullOrBlank()) return null
        return el.selectFirst(s.selector)?.attr(attr)?.takeIf { it.isNotBlank() }
    }
    private fun selectList(el: Element, s: Selector?): List<String> {
        if (s == null || s.selector.isNullOrBlank()) return emptyList()
        return el.select(s.selector).mapNotNull { clean(it.text()) }
    }
    private fun clean(s: String?): String? = s?.trim()?.takeIf { it.isNotBlank() }
}

// ---------- Config schema ----------

@Serializable
enum class ScraperMode { HTML, JSON }

@Serializable
data class UniversalScraperConfig(
    val id: String? = null,
    val name: String,
    val baseUrl: String,
    val homeUrl: String? = null,
    val icon: String? = null,
    val mode: ScraperMode = ScraperMode.HTML,
    val catalogs: List<ScraperCatalog> = emptyList(),
    val search: ScraperSearch? = null,
    val detail: ScraperDetail? = null,
    val episodes: ScraperEpisodes? = null,
    val streams: ScraperStreams? = null,
) {
    companion object {
        fun parse(json: String): UniversalScraperConfig =
            StormJson.decodeFromString(serializer(), json)
    }
}

@Serializable
data class ScraperCatalog(
    val id: String,
    val name: String,
    val type: String = "movie",
    val path: String? = null,
    val itemSelectors: ItemSelectors = ItemSelectors(),
) {
    val mediaType: MediaType get() = when (type) {
        "tv", "series" -> MediaType.SERIES
        "anime" -> MediaType.ANIME
        "manga" -> MediaType.MANGA
        else -> MediaType.MOVIE
    }
}

@Serializable
data class ScraperSearch(
    val path: String,
    val itemSelectors: ItemSelectors = ItemSelectors(),
)

@Serializable
data class ItemSelectors(
    val row: String = "article, .item, .card, li",
    val title: Selector = Selector("h2, h3, .title, .name"),
    val link: Selector = Selector("a", "href"),
    val image: Selector = Selector("img", "src"),
    val year: Selector? = null,
    val rating: Selector? = null,
    /** For JSON mode: top-level key holding the array. */
    val jsonRoot: String? = null,
)

@Serializable
data class Selector(
    val selector: String? = null,
    /** Attribute name; empty for "text()" on HTML, field name on JSON. */
    val attr: String? = null,
) {
    val jsonKey: String? get() = selector
}

@Serializable
data class ScraperDetail(
    val description: Selector? = null,
    val rating: Selector? = null,
    val genres: Selector? = null,
    val year: Selector? = null,
    val backdrop: Selector? = null,
)

@Serializable
data class ScraperEpisodes(
    val rowSelector: String = ".episode, .ep-item, li",
    val title: Selector? = Selector(".ep-title, .name, a"),
    val link: Selector = Selector("a", "href"),
    val number: Selector? = null,
    val season: Selector? = null,
    val image: Selector? = Selector("img", "src"),
)

@Serializable
data class ScraperStreams(
    val rowSelector: String = ".server, .link-item, a",
    val name: Selector? = Selector(null),
    val link: Selector = Selector("a", "href"),
    val quality: Selector? = null,
    val headers: Map<String, String>? = null,
)
