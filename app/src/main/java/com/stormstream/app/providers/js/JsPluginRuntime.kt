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
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.Executors

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

    /** Load the shim + plugin modules and detect the dialect. */
    suspend fun load(modules: Map<String, String>): StormResult<JsPluginInfo> = withContext(dispatcher) {
        var qjs: QuickJs? = null
        try {
            qjs = QuickJs.create(dispatcher)
            quickJs = qjs
            qjs.evaluationTimeoutMillis = EVALUATION_TIMEOUT_MS
            installBridge(qjs)

            val shim = context.assets.open(SHIM_ASSET).bufferedReader().use { it.readText() }
            qjs.evaluate<Unit>(shim, filename = "storm-js-shim.js")

            for ((name, source) in modules) {
                val wrapped = buildString {
                    append("globalThis.__stormRegisterModule(")
                    append(name.toJsStringLiteral())
                    append(", function (module, exports, require, storm, console, providerContext) {\n")
                    append(source)
                    append("\n});")
                }
                qjs.evaluate<Unit>(wrapped, filename = "$name.js")
            }

            val infoJson = qjs.evaluate<String>(
                "globalThis.__stormDetect()",
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

    fun shutdown() {
        try {
            quickJs?.close()
        } catch (_: Throwable) {
        }
        quickJs = null
        // Also stops the backing single-thread executor.
        runCatching { dispatcher.close() }
    }

    // ---------- native bridge ----------

    private fun installBridge(qjs: QuickJs) {
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
    }

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
    }
}

/** Thrown when a plugin does not implement a requested method. */
class JsMethodNotImplemented(val method: String) : Exception("Method not implemented: $method")

/** Thrown when a plugin method throws or returns a JS error. */
class JsPluginException(message: String, cause: Throwable? = null) : Exception(message, cause)

