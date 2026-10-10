package com.stormstream.app.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coil.imageLoader
import com.stormstream.app.core.StormResult
import com.stormstream.app.data.PluginSettingField
import com.stormstream.app.player.MpvPlayerController
import com.stormstream.app.data.WatchProgressStore
import com.stormstream.app.providers.ProviderManager
import com.stormstream.app.providers.plugin.PluginRepoManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row on the Home screen: a catalog plus its first page of items. */
data class HomeRow(
    val catalog: CatalogRef,
    val items: List<MediaItem>,
    val isLoading: Boolean = true,
    val error: String? = null,
)

/** Paged state for the Browse screen. */
data class BrowseState(
    val catalog: CatalogRef,
    val items: List<MediaItem>,
    val page: Int,
    val isLoading: Boolean,
    val endReached: Boolean,
    val error: String?,
)

private const val HOME_ROW_LIMIT = 20
private const val BROWSE_PAGE_SIZE = 24

/**
 * Single shared ViewModel: home rows, search, detail/streams, browse,
 * extension management and settings. The player itself is the app-scoped
 * [MpvPlayerController] (internal libmpv).
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val providerManager = ProviderManager.get(app)
    private val extensionStore = ExtensionStore.get(app)
    private val watchProgress = WatchProgressStore.get(app)
    val repoManager = PluginRepoManager(ProviderManager.http(app), providerManager, extensionStore)
    val settings = SettingsStore.get(app)

    /** The one player: internal libmpv. */
    val player: MpvPlayerController = MpvPlayerController.get(app)

    // ---------- messages (snackbar) ----------

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private fun message(text: String) { _messages.tryEmit(text) }

    // ---------- busy ----------

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    // ---------- home ----------

    private val _homeRows = MutableStateFlow<List<HomeRow>>(emptyList())
    val homeRows: StateFlow<List<HomeRow>> = _homeRows.asStateFlow()

    private val _homeRefreshing = MutableStateFlow(false)
    val homeRefreshing: StateFlow<Boolean> = _homeRefreshing.asStateFlow()

    // ---------- search ----------

    private val _searchResults = MutableStateFlow<List<MediaItem>>(emptyList())
    val searchResults: StateFlow<List<MediaItem>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    // ---------- detail ----------

    private val _selectedItem = MutableStateFlow<MediaItem?>(null)
    val selectedItem: StateFlow<MediaItem?> = _selectedItem.asStateFlow()

    /** Items worth resuming (Continue watching), most recent first. */
    val continueWatching: StateFlow<List<com.stormstream.app.data.WatchProgress>> =
        watchProgress.progress
            .map { progressMap -> progressMap.values.filter { it.resumable }.sortedByDescending { it.updatedAt }.take(20) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Position to seek to right after the next play() starts (resume). */
    @Volatile
    private var pendingResumeSec: Double? = null

    /** Progress for the currently open item, if any. */
    val currentProgress: StateFlow<com.stormstream.app.data.WatchProgress?> =
        _selectedItem.map { item ->
            item?.let { watchProgress.snapshot()[WatchProgressStore.keyFor(it.providerId, it.id)] }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _episodes = MutableStateFlow<List<Episode>>(emptyList())
    val episodes: StateFlow<List<Episode>> = _episodes.asStateFlow()

    private val _streams = MutableStateFlow<List<StreamSource>>(emptyList())
    val streams: StateFlow<List<StreamSource>> = _streams.asStateFlow()

    private val _detailLoading = MutableStateFlow(false)
    val detailLoading: StateFlow<Boolean> = _detailLoading.asStateFlow()

    private val _streamsLoading = MutableStateFlow(false)
    val streamsLoading: StateFlow<Boolean> = _streamsLoading.asStateFlow()

    private val _selectedEpisode = MutableStateFlow<Episode?>(null)
    val selectedEpisode: StateFlow<Episode?> = _selectedEpisode.asStateFlow()

    val seasons: StateFlow<List<Int>> = MutableStateFlow<List<Int>>(emptyList()).also { flow ->
        viewModelScope.launch {
            _episodes.collect { eps ->
                flow.value = eps.map { it.season }.distinct().sorted()
            }
        }
    }

    // ---------- browse ----------

    private val _browse = MutableStateFlow<BrowseState?>(null)
    val browse: StateFlow<BrowseState?> = _browse.asStateFlow()

    // ---------- extensions ----------

    val installedExtensions: StateFlow<List<InstalledExtension>> =
        extensionStore.extensions.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    val providerErrors: StateFlow<Map<String, String>> = providerManager.providerErrors
    val repoStates = repoManager.repoStates
    val repoEntries = repoManager.repoEntries

    init {
        // Auto-advance lives here (not in the player screen) so it also works
        // when the app is backgrounded while an episode plays.
        viewModelScope.launch {
            player.playbackEnded.collect { playNextEpisode() }
        }
        viewModelScope.launch {
            providerManager.restore()
            providerManager.bootstrapDefaultsIfNeeded()
            repoManager.loadAll()
            refreshHome()
        }
    }

    // ---------- home ----------

    /** Home shelf keys the user hid ("providerId@catalogId"). */
    val hiddenCatalogs: StateFlow<Set<String>> =
        settings.hiddenCatalogs.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet(),
        )

    fun hideCatalog(catalog: CatalogRef) {
        viewModelScope.launch {
            val key = "${catalog.providerId}@${catalog.catalogId}"
            settings.setHiddenCatalogs(hiddenCatalogs.value + key)
        }
    }

    fun unhideCatalog(key: String) {
        viewModelScope.launch {
            settings.setHiddenCatalogs(hiddenCatalogs.value - key)
        }
    }

    // ---------- per-extension settings (SkyStream manifests / Nuvio onSettings) ----------

    suspend fun settingsFieldsFor(providerId: String): List<PluginSettingField>? =
        providerManager.get(providerId)?.settingsFields()

    suspend fun settingsValuesFor(providerId: String): Map<String, String> =
        providerManager.get(providerId)?.getSettings() ?: emptyMap()

    fun setSettingFor(providerId: String, key: String, value: String) {
        viewModelScope.launch {
            providerManager.get(providerId)?.setSetting(key, value)
        }
    }

    fun setTmdbApiKey(value: String) {
        viewModelScope.launch { settings.setTmdbApiKey(value) }
    }

    fun refreshHome() {
        viewModelScope.launch {
            _homeRefreshing.value = true
            val hidden = hiddenCatalogs.value
            val catalogs = providerManager.allHomeCatalogs()
                .filter { "${it.providerId}@${it.catalogId}" !in hidden }
            _homeRows.value = catalogs.map { HomeRow(catalog = it, items = emptyList()) }
            coroutineScope {
                catalogs.forEach { ref ->
                    launch {
                        val r = providerManager.catalogPage(ref, 1)
                        _homeRows.value = _homeRows.value.map { row ->
                            if (row.catalog != ref) return@map row
                            when (r) {
                                is StormResult.Ok -> row.copy(
                                    items = r.value.filterAdult().take(HOME_ROW_LIMIT),
                                    isLoading = false,
                                )
                                is StormResult.Err -> row.copy(isLoading = false, error = r.error.message)
                            }
                        }
                    }
                }
            }
            _homeRefreshing.value = false
        }
    }

    // ---------- search ----------

    fun search(query: String) {
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _searching.value = true
            val r = providerManager.searchAll(query, 1)
            _searchResults.value = (r as? StormResult.Ok)?.value?.filterAdult() ?: emptyList()
            _searching.value = false
        }
    }

    // ---------- detail ----------

    fun openItem(item: MediaItem) {
        viewModelScope.launch {
            _selectedItem.value = item
            // Remember where to resume from (Continue watching).
            val progress = watchProgress.snapshot()[WatchProgressStore.keyFor(item.providerId, item.id)]
            pendingResumeSec = progress?.takeIf { it.resumable }?.positionSec
            _episodes.value = emptyList()
            _streams.value = emptyList()
            _selectedEpisode.value = null
            _detailLoading.value = true
            val meta = providerManager.getMeta(item)
            val resolved = (meta as? StormResult.Ok)?.value ?: item
            _selectedItem.value = resolved
            val eps = providerManager.getEpisodes(resolved)
            val epsList = (eps as? StormResult.Ok)?.value
            _episodes.value = epsList ?: emptyList()
            _detailLoading.value = false
            if (epsList == null) {
                // Movie / single-stream item — resolve streams right away.
                loadStreams(resolved, null)
            }
        }
    }

    /** Select an episode and resolve its streams. */
    fun selectEpisode(episode: Episode, autoPlay: Boolean = false) {
        val item = _selectedItem.value ?: return
        _selectedEpisode.value = episode
        viewModelScope.launch {
            loadStreams(item, episode)
            if (autoPlay) {
                playFirstLoadedStream(item, episode)
            }
        }
    }

    fun loadStreams(item: MediaItem, episode: Episode?) {
        viewModelScope.launch {
            _streamsLoading.value = true
            _streams.value = emptyList()
            val r = providerManager.getStreams(item, episode)
            _streams.value = (r as? StormResult.Ok)?.value ?: emptyList()
            _streamsLoading.value = false
        }
    }

    /**
     * "Play" button behavior: ensure streams are loaded for the current
     * selection, then either open the picker (multiple streams) or play the
     * first one directly.
     */
    fun playSelected(openPicker: () -> Unit) {
        val item = _selectedItem.value ?: return
        val eps = _episodes.value
        if (eps.isEmpty()) {
            // Movie / single item.
            if (_streams.value.isNotEmpty()) {
                dispatchPlay(item, null, openPicker)
            } else {
                viewModelScope.launch {
                    loadStreamsSuspend(item, null)
                    dispatchPlay(item, null, openPicker)
                }
            }
            return
        }
        val target = _selectedEpisode.value ?: eps.first()
        if (_selectedEpisode.value?.id == target.id && _streams.value.isNotEmpty()) {
            dispatchPlay(item, target, openPicker)
        } else {
            viewModelScope.launch {
                _selectedEpisode.value = target
                loadStreamsSuspend(item, target)
                dispatchPlay(item, target, openPicker)
            }
        }
    }

    private suspend fun loadStreamsSuspend(item: MediaItem, episode: Episode?) {
        _streamsLoading.value = true
        _streams.value = emptyList()
        val r = providerManager.getStreams(item, episode)
        _streams.value = (r as? StormResult.Ok)?.value ?: emptyList()
        _streamsLoading.value = false
    }

    private fun dispatchPlay(item: MediaItem, episode: Episode?, openPicker: () -> Unit) {
        val source = _streams.value.firstOrNull()
        if (source == null) {
            message("No streams found for this item")
            return
        }
        if (_streams.value.size > 1) {
            openPicker()
        } else {
            playSource(item, episode, source)
        }
    }

    private fun playFirstLoadedStream(item: MediaItem, episode: Episode) {
        val source = _streams.value.firstOrNull() ?: return
        playSource(item, episode, source)
    }

    fun playSource(item: MediaItem, episode: Episode?, source: StreamSource) {
        player.play(item, episode, source)
        // Resume from the saved position (Continue watching).
        val resume = pendingResumeSec
        pendingResumeSec = null
        if (resume != null && resume > 5.0) {
            player.seekTo(resume)
        }
    }

    /** Play the episode before/after the current one (player prev/next buttons). */
    fun playAdjacentEpisode(delta: Int) {
        val item = _selectedItem.value ?: return
        val eps = _episodes.value
        if (eps.isEmpty()) return
        val currentId = player.currentEpisode?.id ?: _selectedEpisode.value?.id
        val idx = eps.indexOfFirst { it.id == currentId }
        val target = when {
            idx < 0 -> if (delta > 0) eps.firstOrNull() else eps.lastOrNull()
            else -> eps.getOrNull(idx + delta)
        } ?: return
        selectEpisode(target, autoPlay = true)
    }

    /** Auto-play the next episode when the current one ends. */
    fun playNextEpisode() {
        viewModelScope.launch {
            if (!settings.autoplayNext.first()) {
                player.stop()
                return@launch
            }
            val item = _selectedItem.value
            val eps = _episodes.value
            if (item == null || eps.isEmpty()) {
                player.stop()
                return@launch
            }
            val currentId = player.currentEpisode?.id
            val next = if (currentId == null) {
                eps.firstOrNull()
            } else {
                eps.dropWhile { it.id != currentId }.drop(1).firstOrNull()
            }
            if (next == null) {
                player.stop()
                return@launch
            }
            _selectedEpisode.value = next
            loadStreamsSuspend(item, next)
            val source = _streams.value.firstOrNull()
            if (source != null) {
                player.play(item, next, source)
            } else {
                player.stop()
            }
        }
    }

    // ---------- browse ----------

    fun openBrowse(catalog: CatalogRef) {
        _browse.value = BrowseState(
            catalog = catalog,
            items = emptyList(),
            page = 0,
            isLoading = true,
            endReached = false,
            error = null,
        )
        loadBrowsePage()
    }

    fun loadBrowsePage() {
        val state = _browse.value ?: return
        if (state.isLoading || state.endReached) return
        viewModelScope.launch {
            // Re-check inside the coroutine: a second call may have started a page
            // load between the guard above and this coroutine getting dispatched.
            val current = _browse.value ?: return@launch
            if (current.isLoading || current.endReached) return@launch
            _browse.value = current.copy(isLoading = true)
            val r = providerManager.catalogPage(current.catalog, current.page + 1)
            _browse.value = when (r) {
                is StormResult.Ok -> {
                    val newItems = r.value.filterAdult()
                    val merged = (current.items + newItems).distinctBy { it.id + it.providerId }
                    current.copy(
                        items = merged,
                        page = current.page + 1,
                        isLoading = false,
                        // Stop when the page is short or the provider keeps
                        // returning items we already have (no skip support).
                        endReached = newItems.size < BROWSE_PAGE_SIZE || merged.size == current.items.size,
                        error = null,
                    )
                }
                is StormResult.Err -> current.copy(isLoading = false, error = r.error.message)
            }
        }
    }

    // ---------- extensions ----------

    fun installStremio(url: String) = runInstall("Stremio addon") {
        providerManager.installStremioAddon(url)
    }

    fun installScraper(json: String) = runInstall("Scraper") {
        providerManager.installScraperConfig(json)
    }

    fun installIptv(name: String, url: String) = runInstall("IPTV playlist") {
        providerManager.installIptvPlaylist(name, url)
    }

    fun installJsPlugin(url: String, name: String?) = runInstall("JS plugin") {
        providerManager.installJsPlugin(url, fallbackName = name)
    }

    fun installSkyStream(url: String) = runInstall("SkyStream extension") {
        if (url.endsWith(".sky")) {
            providerManager.installSkyStreamPackage(url)
        } else {
            providerManager.installSkyStreamPlugin(
                name = url.substringAfterLast('/').removeSuffix(".js").ifBlank { "SkyStream plugin" },
                jsUrl = url,
            )
        }
    }

    fun installNuvio(url: String) = runInstall("Nuvio extension") {
        providerManager.installNuvioManifest(url).map { it.first() }
    }

    fun addRepo(url: String) {
        viewModelScope.launch {
            _busy.value = true
            try {
                repoManager.addRepo(url)
                message("Repository added")
            } catch (e: Throwable) {
                message("Failed to load repo: ${e.message}")
            } finally {
                _busy.value = false
            }
        }
    }

    fun refreshRepo(url: String) {
        viewModelScope.launch {
            runCatching { repoManager.refreshRepo(url) }
        }
    }

    fun removeRepo(url: String) {
        viewModelScope.launch {
            repoManager.removeRepo(url)
        }
    }

    fun installPlugin(plugin: RepoPlugin) {
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val r = repoManager.installPlugin(plugin)) {
                    is StormResult.Ok -> message("Installed ${plugin.name}")
                    is StormResult.Err -> message("Failed to install ${plugin.name}: ${r.error.message}")
                }
            } finally {
                _busy.value = false
                refreshHome()
            }
        }
    }

    fun uninstallExtension(providerId: String) {
        viewModelScope.launch {
            providerManager.uninstall(providerId)
            message("Extension removed")
            refreshHome()
        }
    }

    fun setExtensionEnabled(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            providerManager.setEnabled(providerId, enabled)
            refreshHome()
        }
    }

    private fun runInstall(kind: String, block: suspend () -> StormResult<ProviderConfig>) {
        viewModelScope.launch {
            _busy.value = true
            try {
                when (val r = block()) {
                    is StormResult.Ok -> message("$kind installed: ${r.value.name}")
                    is StormResult.Err -> message("Failed to install $kind: ${r.error.message}")
                }
            } finally {
                _busy.value = false
                refreshHome()
            }
        }
    }

    // ---------- settings ----------

    fun setTheme(value: String) { viewModelScope.launch { settings.setTheme(value) } }
    fun setAccent(value: String) { viewModelScope.launch { settings.setAccent(value) } }
    fun setHwdec(value: Boolean) { viewModelScope.launch { settings.setHwdec(value) } }
    fun setDefaultSpeed(value: Double) { viewModelScope.launch { settings.setDefaultSpeed(value) } }
    fun setAutoplayNext(value: Boolean) { viewModelScope.launch { settings.setAutoplayNext(value) } }
    fun setShowAdult(value: Boolean) { viewModelScope.launch { settings.setShowAdult(value) } }
    fun setSubtitleScale(value: Double) { viewModelScope.launch { settings.setSubtitleScale(value) } }

    fun clearCaches() {
        viewModelScope.launch {
            providerManager.http.evictCache()
            runCatching {
                val loader = getApplication<Application>().imageLoader
                loader.diskCache?.clear()
                loader.memoryCache?.clear()
            }
            message("Caches cleared")
        }
    }

    // ---------- helpers ----------

    fun clearProgress(item: MediaItem) {
        viewModelScope.launch {
            watchProgress.clear(WatchProgressStore.keyFor(item.providerId, item.id))
        }
    }

    fun providerName(providerId: String): String =
        providerManager.get(providerId)?.config?.name ?: providerId

    fun providerTypeLabel(type: ProviderType): String = when (type) {
        ProviderType.STREMIO -> "Stremio"
        ProviderType.SCRAPER -> "Scraper"
        ProviderType.IPTV -> "IPTV"
        ProviderType.JS -> "JS Plugin"
        ProviderType.SKYSTREAM -> "SkyStream"
        ProviderType.NUVIO -> "Nuvio"
    }

    /** Hide items from adult extensions unless the user opted in. */
    private suspend fun List<MediaItem>.filterAdult(): List<MediaItem> {
        val showAdult = settings.showAdult.first()
        if (showAdult) return this
        val adultIds = providerManager.providers.value.values
            .filter { it.config.adult }
            .map { it.config.id }
            .toSet()
        return filterNot { it.providerId in adultIds }
    }

}
