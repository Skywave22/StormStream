package com.stormstream.app.core

/**
 * Discriminated result type used across provider calls so the UI can render
 * granular errors (network, missing plugin, adult-gated, provider crashed).
 */
sealed class StormResult<out T> {
    data class Ok<T>(val value: T) : StormResult<T>()
    data class Err(val error: StormError) : StormResult<Nothing>()

    inline fun <R> map(f: (T) -> R): StormResult<R> = when (this) {
        is Ok -> Ok(f(value))
        is Err -> this
    }

    inline fun onError(f: (StormError) -> Unit): StormResult<T> {
        if (this is Err) f(error)
        return this
    }

    fun getOrNull(): T? = (this as? Ok)?.value
}

sealed class StormError(val message: String, val cause: Throwable? = null) {
    class Network(message: String, cause: Throwable? = null) : StormError(message, cause)
    class Parse(message: String, cause: Throwable? = null) : StormError(message, cause)
    class NotInstalled(providerId: String) : StormError("Provider $providerId is not installed")
    class AdultBlocked(title: String) : StormError("\"$title\" is adult content (disabled in settings)")
    class ProviderCrashed(providerId: String, cause: Throwable) :
        StormError("Provider $providerId crashed: ${cause.message}", cause)
    class Unsupported(message: String) : StormError(message)
    class Cancelled : StormError("Cancelled")
}
