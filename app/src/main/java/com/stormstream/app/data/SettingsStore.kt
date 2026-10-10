package com.stormstream.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storm_settings"
)

/** Theme preference values. */
const val THEME_DARK = "dark"
const val THEME_LIGHT = "light"
const val THEME_SYSTEM = "system"

/**
 * Persistent app settings (theme, player defaults, content filters).
 */
class SettingsStore(private val context: Context) {

    private val store get() = context.settingsDataStore

    val theme: Flow<String> = store.data.map { it[KEY_THEME] ?: THEME_DARK }
    val hwdec: Flow<Boolean> = store.data.map { it[KEY_HWDEC] ?: true }
    val defaultSpeed: Flow<Double> = store.data.map { it[KEY_SPEED] ?: 1.0 }
    val autoplayNext: Flow<Boolean> = store.data.map { it[KEY_AUTOPLAY] ?: true }
    val showAdult: Flow<Boolean> = store.data.map { it[KEY_ADULT] ?: false }
    val subtitleScale: Flow<Double> = store.data.map { it[KEY_SUBSCALE] ?: 1.0 }
    /** User's own TMDB v3 API key (wins over the bundled public keys). */
    val tmdbApiKey: Flow<String> = store.data.map { it[KEY_TMDB_API_KEY] ?: "" }

    suspend fun setTheme(value: String) { store.edit { it[KEY_THEME] = value } }
    suspend fun setHwdec(value: Boolean) { store.edit { it[KEY_HWDEC] = value } }
    suspend fun setDefaultSpeed(value: Double) { store.edit { it[KEY_SPEED] = value } }
    suspend fun setAutoplayNext(value: Boolean) { store.edit { it[KEY_AUTOPLAY] = value } }
    suspend fun setShowAdult(value: Boolean) { store.edit { it[KEY_ADULT] = value } }
    suspend fun setSubtitleScale(value: Double) { store.edit { it[KEY_SUBSCALE] = value } }
    suspend fun setTmdbApiKey(value: String) { store.edit { it[KEY_TMDB_API_KEY] = value.trim() } }

    companion object {
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_HWDEC = booleanPreferencesKey("hwdec")
        private val KEY_SPEED = doublePreferencesKey("default_speed")
        private val KEY_AUTOPLAY = booleanPreferencesKey("autoplay_next")
        private val KEY_ADULT = booleanPreferencesKey("show_adult")
        private val KEY_SUBSCALE = doublePreferencesKey("subtitle_scale")
        private val KEY_TMDB_API_KEY = stringPreferencesKey("tmdb_api_key")

        @Volatile
        private var instance: SettingsStore? = null

        fun get(context: Context): SettingsStore =
            instance ?: synchronized(this) {
                instance ?: SettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
