package com.stormstream.app.net

import okhttp3.Cache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit

/** Raw HTTP response: status code + decoded body. */
data class HttpResult(val status: Int, val body: String) {
    val isSuccessful: Boolean get() = status in 200..299
}

/**
 * Shared HTTP client used by every provider and the JS plugin runtime.
 * Centralized so we can tune timeouts, logging, caching and per-request
 * headers in one place.
 */
class StormHttpClient(cacheDir: File) {

    private val cache = Cache(File(cacheDir, "http"), 50L * 1024 * 1024) // 50 MB

    val client: OkHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .addInterceptor(
            HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
        )
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .build()
            chain.proceed(req)
        }
        .build()

    /** GET that throws on non-2xx. Convenience for providers. */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val r = getRaw(url, headers)
        require(r.isSuccessful) { "HTTP ${r.status} for $url" }
        return r.body
    }

    /** GET returning status + body without throwing. */
    fun getRaw(url: String, headers: Map<String, String> = emptyMap()): HttpResult {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        return client.newCall(request).execute().use { resp ->
            HttpResult(resp.code, resp.body?.string().orEmpty())
        }
    }

    fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        return client.newCall(request).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    fun post(
        url: String,
        body: String,
        mediaType: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): String {
        val r = postRaw(url, body, headers, mediaType)
        require(r.isSuccessful) { "HTTP ${r.status} POST $url" }
        return r.body
    }

    fun postRaw(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap(),
        mediaType: String = "application/json"
    ): HttpResult {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            post(body.toRequestBody(mediaType.toMediaType()))
        }.build()
        return client.newCall(request).execute().use { resp ->
            HttpResult(resp.code, resp.body?.string().orEmpty())
        }
    }

    /** Download a URL to a local file (used for plugin files). Returns the file. */
    fun download(url: String, dest: File, headers: Map<String, String> = emptyMap()): File {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.get().build()
        client.newCall(request).execute().use { resp ->
            require(resp.isSuccessful) { "HTTP ${resp.code} for $url" }
            dest.parentFile?.mkdirs()
            resp.body?.byteStream()?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return dest
    }

    /** Evict the HTTP cache (Settings → Clear cache). */
    fun evictCache() {
        runCatching { cache.evictAll() }
    }

    /** Resolve a relative URL against a base. */
    fun resolve(base: String, relative: String): String {
        val b = base.toHttpUrlOrNull() ?: return relative
        return b.newBuilder(relative)?.toString() ?: relative
    }

    companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; StormStream) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}
