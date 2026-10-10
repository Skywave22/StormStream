package com.stormstream.app.net

import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.MediaType
import com.stormstream.app.util.StormJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal TMDB v3 API client.
 *
 * Nuvio plugins resolve streams by TMDB id, so StormStream browses TMDB for
 * them (the same integration pattern Hikari uses): each Nuvio scraper is
 * browsed through TMDB catalogs/search, and its streams are resolved per item.
 *
 * API keys: TMDB v3 keys are public-by-design in open-source apps. A key set
 * in Settings always wins; otherwise the bundled public keys are tried in
 * order (a revoked key just falls through to the next).
 */
class TmdbClient(
    private val http: StormHttpClient,
    private val apiKeyProvider: suspend () -> String?,
) {
    private suspend fun get(path: String, params: Map<String, String>): JsonObject? {
        val key = apiKeyProvider() ?: return null
        val qs = (params + ("api_key" to key)).entries.joinToString("&") { (k, v) ->
            "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}"
        }
        val body = runCatching { http.get("$API_BASE$path?$qs") }.getOrNull() ?: return null
        return runCatching { StormJson.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    /** search/multi — movies + tv in one call. */
    suspend fun searchMulti(query: String, page: Int = 1): List<MediaItem> {
        val obj = get(
            "/search/multi",
            mapOf("query" to query, "page" to page.coerceAtLeast(1).toString(), "include_adult" to "false"),
        ) ?: return emptyList()
        return obj["results"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject.toMediaItem() }
    }

    /** Popular movies or TV (for scraper-browsed catalog rows). */
    suspend fun popular(mediaType: MediaType, page: Int = 1): List<MediaItem> {
        val path = when (mediaType) {
            MediaType.SERIES, MediaType.ANIME -> "/tv/popular"
            else -> "/movie/popular"
        }
        val obj = get(path, mapOf("page" to page.coerceAtLeast(1).toString())) ?: return emptyList()
        return obj["results"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject.toMediaItem() }
    }

    /** Trending (day) — a second shelf flavor for scraper catalogs. */
    suspend fun trending(mediaType: MediaType, page: Int = 1): List<MediaItem> {
        val path = when (mediaType) {
            MediaType.SERIES, MediaType.ANIME -> "/trending/tv/day"
            else -> "/trending/movie/day"
        }
        val obj = get(path, mapOf("page" to page.coerceAtLeast(1).toString())) ?: return emptyList()
        return obj["results"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject.toMediaItem() }
    }

    /** Movie details (with imdb id via append_to_response). */
    suspend fun movieDetails(tmdbId: String): MediaItem? {
        val obj = get("/movie/$tmdbId", mapOf("append_to_response" to "external_ids"))
            ?: return null
        return obj.toMediaItem()?.copy(
            imdbId = obj["external_ids"]?.jsonObject?.get("imdb_id")?.jsonPrimitive?.contentOrNull
                ?: obj.toMediaItem()?.imdbId,
        )
    }

    /** TV details: seasons list (for episode generation) + imdb id. */
    suspend fun tvDetails(tmdbId: String): Pair<MediaItem, List<Episode>>? {
        val obj = get("/tv/$tmdbId", mapOf("append_to_response" to "external_ids"))
            ?: return null
        val item = obj.toMediaItem() ?: return null
        val imdb = obj["external_ids"]?.jsonObject?.get("imdb_id")?.jsonPrimitive?.contentOrNull
        val seasons = obj["seasons"]?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject }
            .filter { (it["season_number"]?.jsonPrimitive?.intOrNull ?: 0) > 0 }
            .map { seasonObj ->
                val seasonNumber = seasonObj["season_number"]?.jsonPrimitive?.intOrNull ?: 1
                val count = seasonObj["episode_count"]?.jsonPrimitive?.intOrNull ?: 0
                val name = seasonObj["name"]?.jsonPrimitive?.contentOrNull
                (1..count).map { ep ->
                    Episode(
                        id = "$tmdbId:$seasonNumber:$ep",
                        providerId = "",
                        showId = tmdbId,
                        title = name?.let { "$it · E$ep" } ?: "S${seasonNumber}E$ep",
                        season = seasonNumber,
                        number = ep,
                        thumbnailUrl = seasonObj["poster_path"]?.jsonPrimitive?.contentOrNull
                            ?.let { IMG_BASE + it },
                        internalUrl = null,
                    )
                }
            }
            .flatten()
        return item.copy(imdbId = imdb ?: item.imdbId) to seasons
    }

    /** find by IMDb id (Stremio metas carry imdb ids, not TMDB ids). */
    suspend fun findByImdb(imdbId: String, mediaType: MediaType): MediaItem? {
        val obj = get(
            "/find/$imdbId",
            mapOf("external_source" to "imdb_id"),
        ) ?: return null
        val results = when (mediaType) {
            MediaType.SERIES, MediaType.ANIME ->
                (obj["tv_results"]?.jsonArray ?: obj["movie_results"]?.jsonArray).orEmpty()
            else ->
                (obj["movie_results"]?.jsonArray ?: obj["tv_results"]?.jsonArray).orEmpty()
        }
        return results.firstOrNull()?.jsonObject?.toMediaItem()
    }

    private fun JsonObject.toMediaItem(): MediaItem? {
        val mediaTypeRaw = get("media_type")?.jsonPrimitive?.contentOrNull
        val title = get("title")?.jsonPrimitive?.contentOrNull
            ?: get("name")?.jsonPrimitive?.contentOrNull
            ?: return null
        val id = get("id")?.jsonPrimitive?.intOrNull?.toString()
            ?: get("imdb_id")?.jsonPrimitive?.contentOrNull
            ?: return null
        val type = when (mediaTypeRaw?.lowercase()) {
            "tv" -> MediaType.SERIES
            "movie" -> MediaType.MOVIE
            else -> MediaType.MOVIE
        }
        val date = get("release_date")?.jsonPrimitive?.contentOrNull
            ?: get("first_air_date")?.jsonPrimitive?.contentOrNull
        return MediaItem(
            id = id,
            providerId = "", // filled in by the caller (the browsing provider)
            title = title,
            type = type,
            posterUrl = get("poster_path")?.jsonPrimitive?.contentOrNull?.let { IMG_BASE + it },
            backdropUrl = get("backdrop_path")?.jsonPrimitive?.contentOrNull?.let { IMG_BACKDROP + it },
            year = date?.take(4)?.toIntOrNull(),
            rating = get("vote_average")?.jsonPrimitive?.doubleOrNull,
            description = get("overview")?.jsonPrimitive?.contentOrNull,
            tmdbId = id,
            imdbId = get("imdb_id")?.jsonPrimitive?.contentOrNull,
        )
    }

    companion object {
        private const val API_BASE = "https://api.themoviedb.org/3"
        const val IMG_BASE = "https://image.tmdb.org/t/p/w500"
        const val IMG_BACKDROP = "https://image.tmdb.org/t/p/w1280"

        /**
         * Bundled public TMDB v3 keys (public-by-design in open-source apps;
         * the same keys shipped in the public Hikari reference app). A key
         * configured in Settings always takes precedence.
         */
        val BUNDLED_KEYS = listOf(
            "68e094699525b18a70bab2f86b1fa706",
            "439c478a771f35c05022f9feabcca01c",
        )
    }
}
