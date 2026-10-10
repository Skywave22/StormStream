package com.stormstream.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stormstream.app.util.StormJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.extensionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storm_extensions"
)

/**
 * Persistent store for installed extensions and added repositories.
 *
 * This is what makes installs survive restarts: every install/uninstall/
 * enable-toggle is written here and replayed by [com.stormstream.app.providers.ProviderManager]
 * on app start.
 */
class ExtensionStore(private val context: Context) {

    private val store get() = context.extensionDataStore

    val extensions: Flow<List<InstalledExtension>> =
        store.data.map { prefs ->
            prefs[KEY_EXTENSIONS]?.let { json ->
                runCatching {
                    StormJson.decodeFromString<List<InstalledExtension>>(json)
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

    val repos: Flow<List<RepoEntry>> =
        store.data.map { prefs ->
            prefs[KEY_REPOS]?.let { json ->
                runCatching {
                    StormJson.decodeFromString<List<RepoEntry>>(json)
                }.getOrDefault(emptyList())
            } ?: emptyList()
        }

    suspend fun extensionsSnapshot(): List<InstalledExtension> = extensions.first()
    suspend fun reposSnapshot(): List<RepoEntry> = repos.first()

    suspend fun upsertExtension(ext: InstalledExtension) {
        val current = extensionsSnapshot().toMutableList()
        current.removeAll { it.config.id == ext.config.id }
        current += ext
        saveExtensions(current)
    }

    suspend fun removeExtension(providerId: String) {
        saveExtensions(extensionsSnapshot().filterNot { it.config.id == providerId })
    }

    suspend fun setExtensions(list: List<InstalledExtension>) = saveExtensions(list)

    private suspend fun saveExtensions(list: List<InstalledExtension>) {
        store.edit { prefs ->
            prefs[KEY_EXTENSIONS] = StormJson.encodeToString(list)
        }
    }

    suspend fun addRepo(url: String) {
        val current = reposSnapshot().toMutableList()
        if (current.none { it.url == url }) {
            current += RepoEntry(url)
        }
        store.edit { prefs ->
            prefs[KEY_REPOS] = StormJson.encodeToString(current)
        }
    }

    suspend fun removeRepo(url: String) {
        store.edit { prefs ->
            prefs[KEY_REPOS] = StormJson.encodeToString(
                reposSnapshot().filterNot { it.url == url }
            )
        }
    }

    companion object {
        private val KEY_EXTENSIONS = stringPreferencesKey("installed_extensions")
        private val KEY_REPOS = stringPreferencesKey("repos")

        @Volatile
        private var instance: ExtensionStore? = null

        fun get(context: Context): ExtensionStore =
            instance ?: synchronized(this) {
                instance ?: ExtensionStore(context.applicationContext).also { instance = it }
            }
    }
}
