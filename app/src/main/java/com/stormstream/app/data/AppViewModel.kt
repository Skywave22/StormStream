package com.stormstream.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stormstream.app.core.StormResult
import com.stormstream.app.providers.ProviderManager
import com.stormstream.app.providers.plugin.PluginRepoManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Single shared ViewModel for the app. In a production build this would be
 * split into per-screen ViewModels; for a seed project one shared VM keeps
 * the wiring simple and readable.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val providerManager = ProviderManager.get(app)
    val repoManager = PluginRepoManager(ProviderManager.http(app), providerManager)

    val providers = providerManager.providers

    private val _homeState = MutableStateFlow<Map<CatalogRef, List<MediaItem>>>(emptyMap())
    val homeState: StateFlow<Map<CatalogRef, List<MediaItem>>> = _homeState.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _selectedItem = MutableStateFlow<MediaItem?>(null)
    val selectedItem: StateFlow<MediaItem?> = _selectedItem.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _streams = MutableStateFlow<List<StreamSource>>(emptyList())
    val streams: StateFlow<List<StreamSource>> = _streams.asStateFlow()

    private val _repos = MutableStateFlow<Map<String, com.stormstream.app.data.RepoIndex>>(emptyMap())
    val repos: StateFlow<Map<String, com.stormstream.app.data.RepoIndex>> = _repos.asStateFlow()

    init {
        viewModelScope.launch {
            providerManager.bootstrapDefaults()
            refreshHome()
            _loading.value = false
        }
    }

    fun refreshHome() {
        viewModelScope.launch {
            val catalogs = providerManager.allHomeCatalogs()
            val map = mutableMapOf<CatalogRef, List<MediaItem>>()
            catalogs.forEach { ref ->
                val r = providerManager.catalogPage(ref, 1)
                if (r is StormResult.Ok) map[ref] = r.value
            }
            _homeState.value = map
        }
    }

    fun search(query: String) {
        if (query.isBlank()) { _searchResults.value = emptyList(); return }
        viewModelScope.launch {
            val r = providerManager.searchAll(query, 1)
            _searchResults.value = (r as? StormResult.Ok)?.value ?: emptyList()
        }
    }

    fun openItem(item: MediaItem) {
        viewModelScope.launch {
            _selectedItem.value = item
            _streams.value = emptyList()
            _episodes.value = emptyList()
            val meta = providerManager.getMeta(item)
            val resolved = (meta as? StormResult.Ok)?.value ?: item
            _selectedItem.value = resolved
            val eps = providerManager.getEpisodes(resolved)
            _episodes.value = (eps as? StormResult.Ok)?.value ?: emptyList()
            if (eps is StormResult.Ok && eps.value == null) {
                // Movie — resolve streams immediately.
                loadStreams(resolved, null)
            }
        }
    }

    fun loadStreams(item: MediaItem, episode: Episode?) {
        viewModelScope.launch {
            val r = providerManager.getStreams(item, episode)
            _streams.value = (r as? StormResult.Ok)?.value ?: emptyList()
        }
    }

    fun installStremio(url: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val r = providerManager.installStremioAddon(url)
            onDone(r is StormResult.Ok)
            refreshHome()
        }
    }

    fun installScraper(json: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val r = providerManager.installUniversalScraper(json)
            onDone(r is StormResult.Ok)
            refreshHome()
        }
    }

    fun installIptv(name: String, url: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val r = providerManager.installIptvPlaylist(name, url)
            onDone(r is StormResult.Ok)
            refreshHome()
        }
    }

    fun addRepo(url: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repoManager.addRepo(url) }
                .onSuccess { _repos.value = repoManager.repos(); onDone(true) }
                .onFailure { onDone(false) }
        }
    }

    fun installPlugin(plugin: com.stormstream.app.data.RepoPlugin) {
        viewModelScope.launch {
            repoManager.installPlugin(plugin)
            refreshHome()
        }
    }
}
