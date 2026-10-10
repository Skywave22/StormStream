package com.stormstream.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stormstream.app.util.StormJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.watchProgressDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storm_watch_progress"
)

/**
 * One item's playback progress. Persisted so "Continue watching" survives
 * restarts (the Hikari history / Nuvio watch-progress pattern).
 */
@Serializable
data class WatchProgress(
    val itemKey: String,
    val providerId: String,
    val title: String,
    val posterUrl: String? = null,
    val positionSec: Double = 0.0,
    val durationSec: Double = 0.0,
    val updatedAt: Long = System.currentTimeMillis(),
) {
    /** 0..1 progress fraction, 0 when the duration is unknown. */
    val fraction: Double
        get() = if (durationSec > 0) (positionSec / durationSec).coerceIn(0.0, 1.0) else 0.0

    /** True when the item was started but not (nearly) finished. */
    val resumable: Boolean
        get() = positionSec > 5.0 && fraction < 0.95
}

/**
 * Persisted playback progress for every item the user has started.
 */
class WatchProgressStore(private val context: Context) {

    private val store get() = context.watchProgressDataStore

    private val serializer = MapSerializer(String.serializer(), WatchProgress.serializer())

    val progress: Flow<Map<String, WatchProgress>> =
        store.data.map { prefs ->
            prefs[KEY_PROGRESS]?.let { json ->
                runCatching { StormJson.decodeFromString(serializer, json) }
                    .getOrDefault(emptyMap())
            } ?: emptyMap()
        }

    suspend fun snapshot(): Map<String, WatchProgress> = progress.first()

    suspend fun record(
        item: MediaItem,
        positionSec: Double,
        durationSec: Double,
    ) {
        if (positionSec <= 0.0) return
        val key = keyFor(item.providerId, item.id)
        val current = snapshot()
        val updated = current + (key to WatchProgress(
            itemKey = key,
            providerId = item.providerId,
            title = item.title,
            posterUrl = item.posterUrl,
            positionSec = positionSec,
            durationSec = durationSec,
        ))
        save(updated)
    }

    suspend fun clear(itemKey: String) {
        save(snapshot() - itemKey)
    }

    suspend fun clearAll() = save(emptyMap())

    /** Items worth resuming, most recently watched first. */
    suspend fun resumable(limit: Int = 20): List<WatchProgress> =
        snapshot().values.filter { it.resumable }.sortedByDescending { it.updatedAt }.take(limit)

    private suspend fun save(map: Map<String, WatchProgress>) {
        store.edit { prefs ->
            prefs[KEY_PROGRESS] = StormJson.encodeToString(serializer, map)
        }
    }

    companion object {
        private val KEY_PROGRESS = stringPreferencesKey("watch_progress")

        fun keyFor(providerId: String, itemId: String): String = "$providerId|$itemId"

        @Volatile
        private var instance: WatchProgressStore? = null

        fun get(context: Context): WatchProgressStore =
            instance ?: synchronized(this) {
                instance ?: WatchProgressStore(context.applicationContext).also { instance = it }
            }
    }
}
