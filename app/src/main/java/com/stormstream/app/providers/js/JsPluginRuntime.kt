package com.stormstream.app.providers.js

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.fleeksoft.ksoup.Ksoup
import com.stormstream.app.core.StormError
import com.stormstream.app.core.StormResult
import com.stormstream.app.net.StormHttpClient
import com.stormstream.app.util.StormJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Runs one JavaScript extension plugin inside an embedded QuickJS engine.
 *
 * The runtime:
 *  1. creates a dedicated single-thread dispatcher (QuickJS contexts are
 *     confined to one thread),
 *  2. installs the native bridge (`storm.http`, `storm.kv`, `storm.html`,
 *     console) implemented with OkHttp, SharedPreferences and ksoup,
 *  3. evaluates the shim (assets/storm-js-shim.js) and registers every plugin
 *     module as a CommonJS module,
 *  4. detects the plugin dialect (StormJS or Vega-compatible),
 *  5. exposes [invoke] which calls a plugin method with a JSON argument and
 *     returns the JSON result (all complex data crosses the boundary as
 *     JSON strings, so no JS↔Kotlin value-conversion edge cases).
 *
 * All plugin code runs sandboxed: no file system, no reflection — only the
 * bridges above.
 */
class JsPluginRuntime(
    private val context: Context,
    private val http: StormHttpClient,
    private val pluginId: String,
) {
    private val dispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "stormjs-$pluginId").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    private var quickJs: QuickJs? = null

    @Volatile
    var info: JsPluginInfo? = null
        private set

    @Volatile
    var dialect: String? = null
        private set

    private val kvPrefs by lazy {
        context.getSharedPreferences("stormjs_$pluginId", Context.MODE_PRIVATE)
    }

    private val timerScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val timerJobs = ConcurrentHashMap<Int, Job>()

    /**
     * Load the shim + plugin code and detect the dialect.
     *
     * @param modules     module name → source (CommonJS mode) or a single
     *                    "main" entry (script mode).
     * @param dialect     force the dialect ("storm"/"vega"/"skystream"/"nuvio")
     *                    instead of auto-detecting.
     * @param scriptMode  evaluate the source as a plain script with a CommonJS
     *                    prelude (SkyStream/Nuvio plugins are plain scripts that
     *                    publish functions on globalThis or module.exports).
     * @param manifestJson SkyStream plugin.json content, assigned to the
     *                    `manifest` global before the source runs.
     * @param scraperId / [scraperSettingsJson] Nuvio scraper identity and its
     *                    saved settings, assigned to SCRAPER_ID / SCRAPER_SETTINGS.
     */
    suspend fun load(
        modules: Map<String, String>,
        dialect: String? = null,
        scriptMode: Boolean = false,
        manifestJson: String? = null,
        scraperId: String? = null,
        scraperSettingsJson: String? = null,
    ): StormResult<JsPluginInfo> = withContext(dispatcher) {
        var qjs: QuickJs? = null
        try {
            qjs = QuickJs.create(dispatcher)
            quickJs = qjs
            qjs.evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS
            installBridge(qjs)

            // Host-injected globals the shim reads at load time (manifest,
            // SCRAPER_ID, SCRAPER_SETTINGS) — set BEFORE the shim runs.
            if (manifestJson != null) {
                qjs.evaluate<Unit>(
                    "globalThis.__stormManifestJson = ${manifestJson.toJsStringLiteral()};",
                    filename = "storm-manifest.js"
                )
            }
            if (scraperId != null) {
                qjs.evaluate<Unit>(
                    "globalThis.__stormScraperId = ${scraperId.toJsStringLiteral()};",
                    filename = "storm-scraper-id.js"
                )
            }
            if (scraperSettingsJson != null) {
                qjs.evaluate<Unit>(
                    "globalThis.__stormScraperSettingsJson = ${scraperSettingsJson.toJsStringLiteral()};",
                    filename = "storm-scraper-settings.js"
                )
            }

            val shim = context.assets.open(SHIM_ASSET).bufferedReader().use { it.readText() }
            qjs.evaluate<Unit>(shim, filename = "storm-js-shim.js")

            for ((name, source) in modules) {
                if (scriptMode) {
                    // Plain script + CommonJS prelude: top-level functions land
                    // on globalThis, `module.exports.x = …` still works.
                    qjs.evaluate<Unit>(
                        "var module = { exports: {} };\nvar exports = module.exports;\n" + source,
                        filename = "$name.js"
                    )
                } else {
                    val wrapped = buildString {
                        append("globalThis.__stormRegisterModule(")
                        append(name.toJsStringLiteral())
                        append(", function (module, exports, require, storm, console, providerContext) {\n")
                        append(source)
                        append("\n});")
                    }
                    qjs.evaluate<Unit>(wrapped, filename = "$name.js")
                }
            }

            val infoJson = qjs.evaluate<String>(
                "globalThis.__stormDetect(${dialect?.toJsStringLiteral() ?: "null"})",
                filename = "storm-detect.js"
            )
            val parsed = StormJson.decodeFromString<JsPluginInfo>(infoJson)
            info = parsed
            dialect = parsed.dialect
            StormResult.Ok(parsed)
        } catch (e: QuickJsException) {
            runCatching { qjs?.close() }
            quickJs = null
            StormResult.Err(StormError.Parse("JS plugin failed to load: ${e.message}", e))
        } catch (e: Throwable) {
            runCatching { qjs?.close() }
            quickJs = null
            StormResult.Err(StormError.ProviderCrashed(pluginId, e))
        }
    }

    /**
     * Invoke a plugin method. [argsJson] is the JSON-encoded argument object.
     * Returns the JSON-encoded result. Throws [JsMethodNotImplemented] when
     * the plugin does not implement [method].
     */
    suspend fun invoke(method: String, argsJson: String): String = withContext(dispatcher) {
        val qjs = quickJs ?: throw IllegalStateException("JS runtime is not loaded")
        val script = "await globalThis.__stormInvoke(" +
            method.toJsStringLiteral() + ", " + argsJson.toJsStringLiteral() + ")"
        try {
            qjs.evaluate<String>(script, filename = "storm-invoke-$method.js")
        } catch (e: QuickJsException) {
            val msg = e.message ?: "JS error"
            if (msg.contains("does not implement")) {
                throw JsMethodNotImplemented(method)
            }
            throw JsPluginException("$method failed: $msg", e)
        }
    }

    /** Invoke, returning null when the plugin does not implement [method]. */
    suspend fun invokeOrNull(method: String, argsJson: String): String? =
        try {
            invoke(method, argsJson)
        } catch (_: JsMethodNotImplemented) {
            null
        }

    /**
     * Invoke a plugin method with POSITIONAL arguments (SkyStream/Nuvio
     * convention: `search(query)`, `load(url)`,
     * `getStreams(tmdbId, mediaType, season, episode)`).
     * [argsArrayJson] is the JSON-encoded argument array.
     */
    suspend fun invokeArray(method: String, argsArrayJson: String): String = withContext(dispatcher) {
        val qjs = quickJs ?: throw IllegalStateException("JS runtime is not loaded")
        val script = "await globalThis.__stormInvokeArray(" +
            method.toJsStringLiteral() + ", " + argsArrayJson.toJsStringLiteral() + ")"
        try {
            qjs.evaluate<String>(script, filename = "storm-invoke-$method.js")
        } catch (e: QuickJsException) {
            val msg = e.message ?: "JS error"
            if (msg.contains("does not implement")) {
                throw JsMethodNotImplemented(method)
            }
            throw JsPluginException("$method failed: $msg", e)
        }
    }

    /** Positional invoke, returning null when the plugin lacks [method]. */
    suspend fun invokeArrayOrNull(method: String, argsArrayJson: String): String? =
        try {
            invokeArray(method, argsArrayJson)
        } catch (_: JsMethodNotImplemented) {
            null
        }

    suspend fun shutdown() {
        timerJobs.values.forEach { it.cancel() }
        timerJobs.clear()
        runCatching { timerScope.cancel() }
        val qjs = quickJs
        quickJs = null
        if (qjs != null) {
            // Close on the engine thread so we never race an in-flight evaluation.
            withContext(dispatcher) { runCatching { qjs.close() } }
        }
        // Also stops the backing single-thread executor.
        runCatching { dispatcher.close() }
    }

    // ---------- native bridge ----------

    private suspend fun installBridge(qjs: QuickJs) {
        qjs.evaluate<Unit>(
            "globalThis.__stormUserAgent = ${StormHttpClient.USER_AGENT.toJsStringLiteral()};",
            filename = "storm-bridge.js"
        )

        qjs.define("console") {
            function("log") { args -> Log.i(TAG, "$pluginId: ${args.joinToString(" ")}") }
            function("warn") { args -> Log.w(TAG, "$pluginId: ${args.joinToString(" ")}") }
            function("error") { args -> Log.e(TAG, "$pluginId: ${args.joinToString(" ")}") }
        }

        qjs.function("__stormLog") { args ->
            val level = args.getOrNull(0) as? String ?: "info"
            val message = args.drop(1).joinToString(" ")
            when (level) {
                "error" -> Log.e(TAG, "$pluginId: $message")
                "warn" -> Log.w(TAG, "$pluginId: $message")
                else -> Log.i(TAG, "$pluginId: $message")
            }
            null
        }

        qjs.asyncFunction("__stormHttpRequest") { args ->
            val method = args.getOrNull(0) as? String ?: "GET"
            val url = args.getOrNull(1) as? String
                ?: throw IllegalArgumentException("__stormHttpRequest: url is required")
            val body = args.getOrNull(2) as? String ?: ""
            val headersJson = args.getOrNull(3) as? String ?: "{}"
            val headers = runCatching {
                StormJson.decodeFromString<Map<String, String>>(headersJson)
            }.getOrDefault(emptyMap())

            val result = runCatching {
                if (method.equals("POST", ignoreCase = true)) {
                    http.postRaw(url, body, headers)
                } else {
                    http.getRaw(url, headers)
                }
            }.getOrElse { e ->
                return@asyncFunction buildJsonObject {
                    put("status", 0)
                    put("body", "")
                    put("json", JsonNull)
                    put("error", e.message ?: "network error")
                }.toString()
            }

            val json = runCatching { StormJson.parseToJsonElement(result.body) }.getOrNull()
            buildJsonObject {
                put("status", result.status)
                put("body", result.body)
                put("json", json ?: JsonNull)
                put("error", JsonNull)
            }.toString()
        }

        qjs.function("__stormKvGet") { args ->
            val key = args.getOrNull(0) as? String ?: return@function null
            kvPrefs.getString(key, null)
        }

        qjs.function("__stormKvSet") { args ->
            val key = args.getOrNull(0) as? String ?: return@function null
            val value = args.getOrNull(1)?.toString() ?: ""
            kvPrefs.edit().putString(key, value).apply()
            null
        }

        qjs.function("__stormSelectText") { args -> selectText(args) }
        qjs.function("__stormSelectTextAll") { args -> selectTextAll(args) }
        qjs.function("__stormSelectAttr") { args -> selectAttr(args) }
        qjs.function("__stormSelectAttrAll") { args -> selectAttrAll(args) }
        qjs.function("__stormSelectHtml") { args -> selectHtml(args) }
        qjs.function("__stormSelectHtmlAll") { args -> selectHtmlAll(args) }

        // Fragment helpers — text/attr/outerHtml of an HTML fragment (ksoup).
        qjs.function("__stormFragmentText") { args ->
            val html = args.getOrNull(0) as? String ?: ""
            runCatching { Ksoup.parse(html).text() }.getOrDefault("")
        }
        qjs.function("__stormFragmentAttr") { args ->
            val html = args.getOrNull(0) as? String ?: ""
            val attr = args.getOrNull(1) as? String ?: ""
            fragmentRoot(html)?.attr(attr)?.takeIf { it.isNotBlank() }
        }
        qjs.function("__stormFragmentHtml") { args ->
            val html = args.getOrNull(0) as? String ?: ""
            fragmentRoot(html)?.outerHtml()
        }

        // Timers — scheduled on the engine thread so they fire between calls too.
        qjs.function("__stormSchedule") { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            val ms = (args.getOrNull(1) as? Number)?.toLong() ?: 0L
            val code = args.getOrNull(2) as? String ?: ""
            val qjsRef = quickJs
            timerJobs[id]?.cancel()
            if (qjsRef != null && code.isNotBlank()) {
                timerJobs[id] = timerScope.launch {
                    delay(ms.coerceAtLeast(0))
                    withContext(dispatcher) {
                        runCatching { qjsRef.evaluate<Unit>(code, filename = "storm-timer.js") }
                    }
                    timerJobs.remove(id)
                }
            }
            null
        }
        qjs.function("__stormCancelSchedule") { args ->
            val id = (args.getOrNull(0) as? Number)?.toInt() ?: 0
            timerJobs.remove(id)?.cancel()
            null
        }

        // Crypto — MD5/SHA digests, HMAC and AES over hex strings.
        qjs.function("__cryptoDigestHex") { args ->
            val hash = (args.getOrNull(0) as? String ?: "SHA-256").uppercase().replace("-", "")
            val hex = args.getOrNull(1) as? String ?: ""
            runCatching {
                val md = MessageDigest.getInstance(hash)
                bytesToHex(md.digest(hexToBytes(hex)))
            }.getOrDefault("")
        }
        qjs.function("__cryptoHmacHex") { args ->
            val hash = (args.getOrNull(0) as? String ?: "SHA-256").uppercase().replace("-", "")
            val keyHex = args.getOrNull(1) as? String ?: ""
            val dataHex = args.getOrNull(2) as? String ?: ""
            runCatching {
                val algo = "Hmac$hash"
                val mac = Mac.getInstance(algo)
                mac.init(SecretKeySpec(hexToBytes(keyHex), algo))
                bytesToHex(mac.doFinal(hexToBytes(dataHex)))
            }.getOrDefault("")
        }
        qjs.function("__cryptoAesHex") { args ->
            val mode = (args.getOrNull(0) as? String ?: "CBC").uppercase()
            val keyHex = args.getOrNull(1) as? String ?: ""
            val ivHex = args.getOrNull(2) as? String ?: ""
            val dataHex = args.getOrNull(3) as? String ?: ""
            val decrypt = (args.getOrNull(4) as? Boolean) ?: true
            runCatching {
                val key = hexToBytes(keyHex)
                val iv = hexToBytes(ivHex)
                val data = hexToBytes(dataHex)
                val op = if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE
                val cipher = when (mode) {
                    "ECB" -> Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
                        init(op, SecretKeySpec(key, "AES"))
                    }
                    "GCM" -> Cipher.getInstance("AES/GCM/NoPadding").apply {
                        init(op, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                    }
                    else -> Cipher.getInstance("AES/CBC/PKCS5Padding").apply {
                        if (iv.isNotEmpty()) init(op, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                        else init(op, SecretKeySpec(key, "AES"))
                    }
                }
                bytesToHex(cipher.doFinal(data))
            }.getOrDefault("")
        }

        // URL parsing (java.net.URI) — powers the URL/URLSearchParams polyfills.
        qjs.function("__stormParseUrl") { args ->
            val raw = args.getOrNull(0) as? String ?: ""
            val base = args.getOrNull(1) as? String ?: ""
            val uri = runCatching {
                if (base.isNotBlank()) java.net.URI(base).resolve(raw) else java.net.URI(raw)
            }.getOrNull() ?: runCatching { java.net.URI(raw.replace(" ", "%20")) }.getOrNull()
            if (uri == null) {
                return@function buildJsonObject {
                    put("href", raw)
                    put("protocol", "")
                    put("host", "")
                    put("hostname", "")
                    put("port", "")
                    put("pathname", raw)
                    put("search", "")
                    put("hash", "")
                    put("origin", "")
                }.toString()
            }
            buildJsonObject {
                put("href", uri.toString())
                put("protocol", uri.scheme?.let { "$it:" } ?: "")
                put("host", uri.host ?: "")
                put("hostname", uri.host ?: "")
                put("port", if (uri.port >= 0) uri.port.toString() else "")
                put("pathname", uri.path ?: "")
                put("search", uri.query?.let { "?$it" } ?: "")
                put("hash", uri.fragment?.let { "#$it" } ?: "")
                put(
                    "origin",
                    if (uri.scheme != null && uri.host != null) {
                        "${uri.scheme}://${uri.host}${if (uri.port >= 0) ":${uri.port}" else ""}"
                    } else ""
                )
            }.toString()
        }
    }

    // ---------- fragment helpers (cheerio subset / parseHtml facade) ----------

    private fun fragmentRoot(html: String): com.fleeksoft.ksoup.nodes.Element? = runCatching {
        val doc = Ksoup.parse(html)
        doc.body().selectFirst("*") ?: doc.selectFirst("*")
    }.getOrNull()

    // ---------- timers (native-scheduled callbacks) ----------

    // ---------- crypto bridges (MessageDigest / Mac / Cipher) ----------

    // ---------- URL parsing bridge ----------

    // ---------- ksoup-backed HTML helpers ----------

    private fun htmlArgs(args: Array<Any?>): Triple<String, String, String?> {
        val html = args.getOrNull(0) as? String ?: ""
        val selector = args.getOrNull(1) as? String ?: ""
        val attr = args.getOrNull(2) as? String
        return Triple(html, selector, attr)
    }

    private fun selectText(args: Array<Any?>): String? {
        val (html, selector, _) = htmlArgs(args)
        if (selector.isBlank()) return null
        return runCatching {
            Ksoup.parse(html).select(selector).firstOrNull()?.text()?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun selectTextAll(args: Array<Any?>): String {
        val (html, selector, _) = htmlArgs(args)
        val out = runCatching {
            if (selector.isBlank()) emptyList()
            else Ksoup.parse(html).select(selector).mapNotNull { it.text().takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
        return StormJson.encodeToString(out)
    }

    private fun selectAttr(args: Array<Any?>): String? {
        val (html, selector, attr) = htmlArgs(args)
        if (selector.isBlank() || attr == null) return null
        return runCatching {
            Ksoup.parse(html).select(selector).firstOrNull()?.attr(attr)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun selectAttrAll(args: Array<Any?>): String {
        val (html, selector, attr) = htmlArgs(args)
        val out = runCatching {
            if (selector.isBlank() || attr == null) emptyList()
            else Ksoup.parse(html).select(selector).mapNotNull { el -> el.attr(attr).takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
        return StormJson.encodeToString(out)
    }

    private fun selectHtml(args: Array<Any?>): String? {
        val (html, selector, _) = htmlArgs(args)
        if (selector.isBlank()) return null
        return runCatching {
            Ksoup.parse(html).select(selector).firstOrNull()?.outerHtml()
        }.getOrNull()
    }

    private fun selectHtmlAll(args: Array<Any?>): String {
        val (html, selector, _) = htmlArgs(args)
        val out = runCatching {
            if (selector.isBlank()) emptyList()
            else Ksoup.parse(html).select(selector).map { it.outerHtml() }
        }.getOrDefault(emptyList())
        return StormJson.encodeToString(out)
    }

    companion object {
        private const val TAG = "StormJs"
        private const val SHIM_ASSET = "storm-js-shim.js"
        private const val EVALUATION_TIMEOUT_MS = 30_000L

        private fun hexToBytes(hex: String): ByteArray {
            val clean = hex.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
            return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        private fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Thrown when a plugin does not implement a requested method. */
class JsMethodNotImplemented(val method: String) : Exception("Method not implemented: $method")

/** Thrown when a plugin method throws or returns a JS error. */
class JsPluginException(message: String, cause: Throwable? = null) : Exception(message, cause)

