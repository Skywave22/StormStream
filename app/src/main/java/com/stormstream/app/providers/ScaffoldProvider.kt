package com.stormstream.app.providers

import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.StreamSource

/**
 * Placeholder provider used for extension types whose native/JS runtime is
 * not yet bundled in this build. It registers in the UI and reports its
 * config, but returns empty catalogs/search/stream lists so no crash
 * occurs. Phase 3 progressively replaces these with real runtimes
 * (Cs3Provider via DexClassLoader, VegaProvider via QuickJS, etc).
 */
internal class ScaffoldProvider(override val config: ProviderConfig) : StreamProvider {
    override suspend fun catalogs(): List<CatalogRef> = emptyList()
    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = emptyList()
    override suspend fun search(query: String, page: Int): List<MediaItem> = emptyList()
    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> = emptyList()
}
