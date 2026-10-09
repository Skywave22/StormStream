package com.stormstream.app.util

import kotlinx.serialization.json.Json

/**
 * App-wide JSON configuration. Lenient so we can talk to sloppy addon
 * servers and CloudStream/Vega repos without crashing on unknown keys.
 */
val StormJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = false
}
