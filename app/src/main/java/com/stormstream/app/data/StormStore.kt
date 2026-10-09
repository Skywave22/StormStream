package com.stormstream.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stormstream.app.util.StormJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

/**
 * Persistent storage for user settings, installed extensions, watch history,
 * and bookmarks.
 *
 * DataStore-backed; watch history and bookmarks are small JSON lists so we
 * avoid a Room dependency in Phase 2.
 */
class StormStore(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences>
                by preferencesDataStore(name = "stormstream")

        private val KEY_ADULT = booleanPreferencesKey("adult_content")
        private val KEY_INCOGNITO = booleanPreferencesKey("incognito")
        private val KEY_PLAYER_ENGINE = stringPreferencesKey("player_engine")
        private val KEY_EXTENSIONS = stringPreferencesKey("extensions_json")
        private val KEY_BOOTSTRAPPED = booleanPreferencesKey("bootstrapped_v1")
        private val KEY_HISTORY = stringPreferencesKey("history_json")
        private val KEY_BOOKMARKS = stringPreferencesKey("bookmarks_json")
        private const val MAX_HISTORY = 60
        private const val MAX_BOOKMARKS = 100

        fun historyKey(item: MediaItem, episode: Episode?): String =
            if (episode != null) "${item.providerId}:${item.id}:${episode.id}"
            else "${item.providerId}:${item.id}"
    }

    // --- settings ---

    val adultContent: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_ADULT] ?: false
    }

    suspend fun setAdultContent(enabled: Boolean) {
        context.dataStore.edit { it[KEY_ADULT] = enabled }
    }

    val incognito: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_INCOGNITO] ?: false
    }

    suspend fun setIncognito(enabled: Boolean) {
        context.dataStore.edit { it[KEY_INCOGNITO] = enabled }
    }

    val playerEngine: Flow<String> = context.dataStore.data.map {
        it[KEY_PLAYER_ENGINE] ?: "mpv"
    }

    suspend fun setPlayerEngine(type: String) {
        context.dataStore.edit { it[KEY_PLAYER_ENGINE] = type }
    }

    // --- bootstrap flag ---

    suspend fun isBootstrapped(): Boolean =
        context.dataStore.data.first()[KEY_BOOTSTRAPPED] == true

    suspend fun markBootstrapped() {
        context.dataStore.edit { it[KEY_BOOTSTRAPPED] = true }
    }

    // --- installed extensions ---

    suspend fun saveExtensions(extensions: List<InstalledExtension>) {
        val json = StormJson.encodeToString(
            ListSerializer(InstalledExtension.serializer()),
            extensions
        )
        context.dataStore.edit { it[KEY_EXTENSIONS] = json }
    }

    val extensionsFlow: Flow<List<InstalledExtension>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_EXTENSIONS] ?: return@map emptyList()
        runCatching {
            StormJson.decodeFromString(
                ListSerializer(InstalledExtension.serializer()),
                json
            )
        }.getOrDefault(emptyList())
    }

    suspend fun loadExtensionsOnce(): List<InstalledExtension> =
        extensionsFlow.first()

    // --- watch history ---

    val historyFlow: Flow<List<HistoryEntry>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_HISTORY] ?: return@map emptyList()
        runCatching {
            StormJson.decodeFromString(ListSerializer(HistoryEntry.serializer()), json)
                .sortedByDescending { it.updatedAt }
        }.getOrDefault(emptyList())
    }

    suspend fun updateHistory(entry: HistoryEntry, incognito: Boolean) {
        if (incognito) return
        val current = historyFlow.first().toMutableList()
        current.removeAll { it.key == entry.key }
        current.add(0, entry)
        val trimmed = current.take(MAX_HISTORY)
        val json = StormJson.encodeToString(
            ListSerializer(HistoryEntry.serializer()), trimmed
        )
        context.dataStore.edit { it[KEY_HISTORY] = json }
    }

    suspend fun removeFromHistory(key: String) {
        val current = historyFlow.first().toMutableList()
        current.removeAll { it.key == key }
        val json = StormJson.encodeToString(
            ListSerializer(HistoryEntry.serializer()), current
        )
        context.dataStore.edit { it[KEY_HISTORY] = json }
    }

    suspend fun clearHistory() {
        context.dataStore.edit { it.remove(KEY_HISTORY) }
    }

    // --- bookmarks ---

    val bookmarksFlow: Flow<List<BookmarkEntry>> = context.dataStore.data.map { prefs ->
        val json = prefs[KEY_BOOKMARKS] ?: return@map emptyList()
        runCatching {
            StormJson.decodeFromString(ListSerializer(BookmarkEntry.serializer()), json)
                .sortedByDescending { it.addedAt }
        }.getOrDefault(emptyList())
    }

    suspend fun toggleBookmark(item: MediaItem): Boolean {
        val key = historyKey(item, null)
        val current = bookmarksFlow.first().toMutableList()
        return if (current.any { it.key == key }) {
            current.removeAll { it.key == key }
            persistBookmarks(current)
            false
        } else {
            current.add(0, BookmarkEntry(key = key, item = item))
            persistBookmarks(current.take(MAX_BOOKMARKS))
            true
        }
    }

    suspend fun isBookmarked(item: MediaItem): Boolean {
        val key = historyKey(item, null)
        return bookmarksFlow.first().any { it.key == key }
    }

    suspend fun removeBookmark(key: String) {
        val current = bookmarksFlow.first().toMutableList()
        current.removeAll { it.key == key }
        persistBookmarks(current)
    }

    private suspend fun persistBookmarks(list: List<BookmarkEntry>) {
        val json = StormJson.encodeToString(
            ListSerializer(BookmarkEntry.serializer()), list
        )
        context.dataStore.edit { it[KEY_BOOKMARKS] = json }
    }

    suspend fun clearBookmarks() {
        context.dataStore.edit { it.remove(KEY_BOOKMARKS) }
    }
}
