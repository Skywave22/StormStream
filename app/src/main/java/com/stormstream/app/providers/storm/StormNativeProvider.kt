package com.stormstream.app.providers.storm

import com.stormstream.app.data.CatalogRef
import com.stormstream.app.data.Episode
import com.stormstream.app.data.MediaItem
import com.stormstream.app.data.ProviderConfig
import com.stormstream.app.data.StreamSource
import com.stormstream.app.providers.StreamProvider

/**
 * storm adapter scaffold.
 *
 * The real runtime boots plugins in a dedicated sandbox (QuickJS/JS or dex
 * classloader depending on the ecosystem) and maps results to [StreamProvider].
 * This scaffold registers the provider type with the manager so repos, install
 * UI and catalog discovery work uniformly end-to-end; stream resolution returns
 * an empty list until the runtime is wired in.
 */
class StormNativeProvider(override val config: ProviderConfig) : StreamProvider {
    override suspend fun catalogs(): List<CatalogRef> = emptyList()
    override suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem> = emptyList()
    override suspend fun search(query: String, page: Int): List<MediaItem> = emptyList()
    override suspend fun getMeta(item: MediaItem): MediaItem = item
    override suspend fun getEpisodes(item: MediaItem): List<Episode>? = null
    override suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource> = emptyList()
}
