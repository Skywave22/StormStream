package com.stormstream.app.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling in-memory app log (the Hikari "Logs & diagnostics" pattern).
 *
 * Keeps the last [CAPACITY] lines so Settings → Logs can show (and share) the
 * real error text behind a bug report instead of a screenshot. Nothing is
 * uploaded automatically — sharing is an explicit user action.
 */
object AppLog {

    private const val CAPACITY = 300

    private val lines = ArrayDeque<String>(CAPACITY)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(tag: String, message: String) {
        val line = "${timeFormat.format(Date())} ${tag.padEnd(14)} $message"
        if (lines.size >= CAPACITY) lines.removeFirst()
        lines.addLast(line)
    }

    fun log(tag: String, message: String, error: Throwable?) {
        log(tag, message + (error?.let { ": ${it.message ?: it.javaClass.simpleName}" } ?: ""))
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    fun render(): String = snapshot().joinToString("\n")
}
