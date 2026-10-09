package com.stormstream.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.stormstream.app.core.StormResult
import com.stormstream.app.providers.ProviderManager
import com.stormstream.app.providers.plugin.PluginRepoManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Single shared ViewModel.
 *
 * Phase 1 fixes:
 *  - Calls providerManager.initializeOnStart() to restore persisted extensions
 *    (previously providers were thrown away on every process death).
 *  - Debounced search (300 ms) so we don't fire a network request per keystroke.
 *  - Exposes a one-shot [errors] SharedFlow for the UI to show Snackbars.
 *  - Holds a [PlaybackTarget] so the Player screen can actually read which
 *    stream/episode the user picked.
 *  - Adult-content filtering respected via StormStore.
 *  - Proper loading/error states per screen.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val providerManager = ProviderManager.get(app)
    val repoManager = PluginRepoManager(ProviderManager.http(app), providerManager)
    private val store = StormStore(app)

    val providers = providerManager.providers

    private val _homeState = MutableStateFlow<Map<CatalogRef, List<MediaItem>>>(emptyMap())
    val homeState: StateFlow<Map<CatalogRef, List<MediaItem>>> = _homeState.asStateFlow()

    private val _homeLoading = MutableStateFlow(true)
    val homeLoading: StateFlow<Boolean> = _homeLoading.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults.asStateFlow()

    private val _searchLoading = MutableStateFlow(false)
    val searchLoading: StateFlow<Boolean> = _searchLoading.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _streamsLoading = MutableStateFlow(false)
    val streamsLoading: StateFlow<Boolean> = _streamsLoading.asStateFlow()

    private val _selectedItem = MutableStateFlow<MediaItem?>(null)
    val selectedItem: StateFlow<MediaItem?> = _selectedItem.asStateFlow()

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _streams = MutableStateFlow<List<StreamSource>>(emptyList())
    val streams: StateFlow<List<StreamSource>> = _streams.asStateFlow()

    private val _repos = MutableStateFlow<Map<String, RepoIndex>>(emptyMap())
    val repos: StateFlow<Map<String, RepoIndex>> = _repos.asStateFlow()

    /** Currently held playback target (set when user hits Play). */
    private val _playback = MutableStateFlow<PlaybackTarget?>(null)
    val playback: StateFlow<PlaybackTarget?> = _playback.asStateFlow()

    /** One-shot error/snackbar events. */
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var searchJob: Job? = null

    private val _adultEnabled = MutableStateFlow(false)
    val adultEnabled: StateFlow<Boolean> = _adultEnabled.asStateFlow()

    init {
        viewModelScope.launch { store.adultContent.collect { _adultEnabled.value = it } }
        viewModelScope.launch {
            _homeLoading.value = true
            providerManager.initializeOnStart()
            refreshHome()
            _homeLoading.value = false
        }
    }

    // ---------- Home ----------

    fun refreshHome() {
        viewModelScope.launch {
            _homeLoading.value = true
            _homeState.value = emptyMap()
            val catalogs = providerManager.allHomeCatalogs()
            // Load each catalog concurrently, populating state as each returns so
            // the UI fills in progressively instead of waiting for the slowest.
            val map = mutableMapOf<CatalogRef, List<MediaItem>>()
            val jobs = catalogs.map { ref ->
                launch {
                    val r = providerManager.catalogPage(ref, 1)
                    when (r) {
                        is StormResult.Ok -> {
                            val items = if (adultEnabled.value) r.value
                                else r.value.filter { !isAdult(it) }
                            synchronized(map) { map[ref] = items }
                            _homeState.update { map.toMap() }
                        }
                        is StormResult.Err -> {
                            Log_w("Catalog ${ref.name} failed: ${r.error.message}")
                        }
                    }
                }
            }
            jobs.joinAll()
            _homeLoading.value = false
        }
    }

    // ---------- Search (debounced) ----------

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _searchLoading.value = false
            return
        }
        _searchLoading.value = true
        searchJob = viewModelScope.launch {
            delay(300L)
            val r = providerManager.searchAll(query, 1)
            when (r) {
                is StormResult.Ok -> {
                    val items = if (adultEnabled.value) r.value
                        else r.value.filter { !isAdult(it) }
                    _searchResults.value = items
                }
                is StormResult.Err -> {
                    _errors.tryEmit("Search failed: ${r.error.message}")
                    _searchResults.value = emptyList()
                }
            }
            _searchLoading.value = false
        }
    }

    // ---------- Detail / streams ----------

    fun openItem(item: MediaItem) {
        viewModelScope.launch {
            _selectedItem.value = item
            _streams.value = emptyList()
            _episodes.value = emptyList()
            _detailLoading.value = true
            val meta = providerManager.getMeta(item)
            val resolved = (meta as? StormResult.Ok)?.value ?: item
            _selectedItem.value = resolved
            val eps = providerManager.getEpisodes(resolved)
            _episodes.value = (eps as? StormResult.Ok)?.value ?: emptyList()
            if (eps is StormResult.Err) {
                _errors.tryEmit("Episode list failed: ${eps.error.message}")
            }
            _detailLoading.value = false
            if ((eps is StormResult.Ok) && eps.value == null) {
                // Movie — resolve streams immediately.
                loadStreams(resolved, null)
            }
        }
    }

    fun loadStreams(item: MediaItem, episode: Episode?) {
        viewModelScope.launch {
            _streamsLoading.value = true
            val r = providerManager.getStreams(item, episode)
            when (r) {
                is StormResult.Ok -> _streams.value = r.value
                is StormResult.Err -> {
                    _streams.value = emptyList()
                    _errors.tryEmit("Streams failed: ${r.error.message}")
                }
            }
            _streamsLoading.value = false
        }
    }

    /** Set the currently selected stream (user taps a source chip in detail/player). */
    fun selectStream(index: Int) {
        val current = _playback.value ?: return
        if (index in current.streams.indices) {
            _playback.value = current.copy(selectedIndex = index)
        }
    }

    /** Start playback: stash the PlaybackTarget so the Player screen consumes it. */
    fun startPlayback(item: MediaItem, episode: Episode?, index: Int = 0) {
        val list = _streams.value
        if (list.isEmpty()) {
            _errors.tryEmit("No playable streams available")
            return
        }
        _playback.value = PlaybackTarget(
            item = item,
            episode = episode,
            streams = list,
            selectedIndex = index.coerceIn(0, list.lastIndex),
        )
    }

    fun clearPlayback() { _playback.value = null }

    // ---------- Install actions ----------

    fun installStremio(url: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val r = providerManager.installStremioAddon(url)
            when (r) {
                is StormResult.Ok -> { _errors.tryEmit("Stremio addon installed"); onDone(true) }
                is StormResult.Err -> {
                    _errors.tryEmit("Install failed: ${r.error.message}"); onDone(false)
                }
            }
            refreshHome()
        }
    }

    fun installScraper(json: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val r = providerManager.installUniversalScraper(json)
            when (r) {
                is StormResult.Ok -> { _errors.tryEmit("Scraper installed"); onDone(true) }
                is StormResult.Err -> {
                    _errors.tryEmit("Install failed: ${r.error.message}"); onDone(false)
                }
            }
            refreshHome()
        }
    }

    fun installIptv(name: String, url: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val r = providerManager.installIptvPlaylist(name, url)
            when (r) {
                is StormResult.Ok -> { _errors.tryEmit("Playlist added"); onDone(true) }
                is StormResult.Err -> {
                    _errors.tryEmit("Install failed: ${r.error.message}"); onDone(false)
                }
            }
            refreshHome()
        }
    }

    fun uninstallProvider(id: String) {
        providerManager.unregister(id)
        _errors.tryEmit("Extension removed")
        refreshHome()
    }

    fun toggleProviderEnabled(id: String, enabled: Boolean) {
        providerManager.setEnabled(id, enabled)
        refreshHome()
    }

    fun addRepo(url: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            runCatching { repoManager.addRepo(url) }
                .onSuccess {
                    _repos.value = repoManager.repos()
                    _errors.tryEmit("Repo added: ${it.name}")
                    onDone(true)
                }
                .onFailure {
                    _errors.tryEmit("Add repo failed: ${it.message}")
                    onDone(false)
                }
        }
    }

    fun installPlugin(plugin: RepoPlugin) {
        viewModelScope.launch {
            val ok = repoManager.installPlugin(plugin)
            if (ok) _errors.tryEmit("Installed ${plugin.name}")
            else _errors.tryEmit("Failed to install ${plugin.name}")
            refreshHome()
        }
    }

    fun setAdultEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setAdultContent(enabled) }
    }

    // ---------- helpers ----------

    private fun isAdult(item: MediaItem): Boolean {
        // Very coarse heuristic — real catalogs flag adult content via config.
        val pid = item.providerId
        val p = providerManager.get(pid) ?: return false
        return p.config.adult
    }

    private fun Log_w(msg: String) {
        android.util.Log.w("StormVM", msg)
    }
}
