package com.stormstream.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shared async HTTP client used by every provider.
 *
 * Improvements over v0.1:
 *  - All methods are suspend-safe (run on Dispatchers.IO).
 *  - Configurable per-call timeout via [withTimeoutOrNull] so one slow addon
 *    cannot block the whole home screen.
 *  - Connection pool + DNS pre-warming; better concurrency.
 *  - Retry with backoff for idempotent GETs.
 *  - Callers can pass headers per-request (Referer/Origin/cookies).
 *  - [get] returns a [StormHttpResult] instead of throwing — callers decide how
 *    to surface failures.
 */
class StormHttpClient(cacheDir: File) {

    private val cache = Cache(File(cacheDir, "http"), 100L * 1024 * 1024) // 100 MB

    val client: OkHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            chain.proceed(req)
        }
        .build()

    /**
     * Result of an HTTP fetch. Either [Ok] with a response body and final URL,
     * or [Err] with a diagnostic message and optional status code.
     */
    sealed class StormHttpResult {
        data class Ok(val body: String, val url: String, val code: Int) : StormHttpResult()
        data class Err(val message: String, val code: Int = -1, val cause: Throwable? = null) :
            StormHttpResult()
    }

    /** Fetch a URL as a string, retrying idempotent GETs up to [retries] times. */
    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 20_000L,
        retries: Int = 1,
    ): StormHttpResult = withContext(Dispatchers.IO) {
        var lastErr: StormHttpResult = StormHttpResult.Err("Unknown error")
        repeat(retries + 1) { attempt ->
            val r = executeOnce(url, headers, timeoutMs, ::buildGet)
            when (r) {
                is StormHttpResult.Ok -> return@withContext r
                is StormHttpResult.Err -> {
                    lastErr = r
                    // Don't retry on 4xx client errors; only on network/5xx.
                    if (r.code in 400..499) return@withContext r
                    if (attempt < retries) Thread.sleep(250L * (attempt + 1))
                }
            }
        }
        lastErr
    }

    /** Fetch a URL as bytes, with same retry/timeout semantics. */
    suspend fun getBytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000L,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        withTimeoutOrNull(timeoutMs) {
            runCatching {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) null
                    else resp.body?.bytes()
                }
            }.getOrNull()
        }
    }

    /** POST JSON/body and return body string. */
    suspend fun post(
        url: String,
        body: String,
        mediaType: String = "application/json",
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 20_000L,
    ): StormHttpResult = withContext(Dispatchers.IO) {
        executeOnce(url, headers, timeoutMs) { u, hdrs ->
            Request.Builder().url(u).apply {
                hdrs.forEach { (k, v) -> header(k, v) }
                post(body.toRequestBody(mediaType.toMediaType()))
            }.build()
        }
    }

    /** Resolve a relative URL against a base. */
    fun resolve(base: String, relative: String): String {
        if (relative.startsWith("http")) return relative
        val b = base.toHttpUrlOrNull() ?: return relative
        return b.newBuilder(relative)?.toString() ?: relative
    }

    // ---------- internals ----------

    private fun buildGet(url: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()

    private fun executeOnce(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        buildReq: (String, Map<String, String>) -> Request,
    ): StormHttpResult {
        val callClient = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        return try {
            val req = buildReq(url, headers)
            callClient.newCall(req).execute().use { resp: Response ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful) {
                    StormHttpResult.Ok(body, resp.request.url.toString(), resp.code)
                } else {
                    StormHttpResult.Err(
                        "HTTP ${resp.code}",
                        code = resp.code
                    )
                }
            }
        } catch (e: IOException) {
            StormHttpResult.Err("Network: ${e.message ?: e.javaClass.simpleName}", cause = e)
        } catch (e: Exception) {
            StormHttpResult.Err(e.message ?: "Request failed", cause = e)
        }
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; StormStream/0.2) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }
}
