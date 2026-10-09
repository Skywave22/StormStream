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
 * Persistent storage for user settings and the list of installed extensions.
 *
 * Phase 1: DataStore-backed. Provider configs are serialized as a JSON list
 * under a single preference key so we can restore the full provider registry on
 * cold start without Room. Watch history, bookmarks, etc. move to Room in
 * Phase 2.
 */
class StormStore(private val context: Context) {

    companion object {
        private val Context.dataStore: DataStore<Preferences>
                by preferencesDataStore(name = "stormstream")

        // Keys
        private val KEY_ADULT = booleanPreferencesKey("adult_content")
        private val KEY_INCOGNITO = booleanPreferencesKey("incognito")
        private val KEY_EXTENSIONS = stringPreferencesKey("extensions_json")
        private val KEY_BOOTSTRAPPED = booleanPreferencesKey("bootstrapped_v1")
        private val KEY_PLAYER_MPV_HINT = booleanPreferencesKey("prefer_external")
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

    // --- bootstrap flag (we only seed default sources once) ---

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
}
