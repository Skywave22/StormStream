# StormStream ⚡

**StormStream** — a universal streaming app for Android, inspired by Hikari.
One player, every provider ecosystem:

| # | Provider system | Status |
|---|-----------------|--------|
| 1 | **Stremio addons** (v3 manifest / catalog / meta / streams / subtitles) | ✅ fully working |
| 2 | **Universal HTML/JSON scrapers** (no-code CSS-selector configs) | ✅ fully working |
| 3 | **IPTV / M3U playlists** (group catalogs + HLS/DASH live playback) | ✅ fully working |
| 4 | **CloudStream `.cs3` plugins** | 🔌 adapter scaffold (dex runtime slot) |
| 5 | **Vega providers** (CommonJS modules) | 🔌 adapter scaffold (QuickJS slot) |
| 6 | **SkyStream extensions** | 🔌 adapter scaffold |
| 7 | **Sora extensions** | 🔌 adapter scaffold |
| 8 | **Aniyomi extensions** | 🔌 adapter scaffold |
| 9 | **Nuvio JS/QuickJS scrapers** | 🔌 adapter scaffold |
| 10 | **Manga sources** | 🔌 adapter scaffold |
| 11 | **Native Storm (`.storm`) extensions** | 🔌 adapter scaffold |

## Architecture

StormStream is written in **100% Kotlin + Jetpack Compose** with **Material 3**
in a dark, electric-blue theme. The core contract every backend implements is:

```kotlin
interface StreamProvider {
    val config: ProviderConfig
    suspend fun catalogs(): List<CatalogRef>
    suspend fun homeCatalogs(): List<CatalogRef>
    suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem>
    suspend fun search(query: String, page: Int): List<MediaItem>
    suspend fun getMeta(item: MediaItem): MediaItem
    suspend fun getEpisodes(item: MediaItem): List<Episode>?
    suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource>
}
```

The UI, database, and player talk **only** to this interface — they never know
which backend a `MediaItem` came from. Each ecosystem adapter lives under
`providers/<type>/` and adapts that ecosystem's native protocol to
`StreamProvider`.

### Package layout

```
app/src/main/java/com/stormstream/app/
├── StormApp.kt / MainActivity.kt        — entry points
├── core/Result.kt                       — StormResult / StormError
├── data/                                — models, AppViewModel
├── net/StormHttpClient.kt               — shared OkHttp client
├── providers/
│   ├── StreamProvider.kt                — the universal contract
│   ├── ProviderManager.kt               — registry + fan-out queries
│   ├── plugin/PluginRepoManager.kt      — extension repo loader (.json repos)
│   ├── stremio/StremioAddonProvider.kt  — ✅ Stremio v3 addon protocol
│   ├── scraper/UniversalScraper…        — ✅ CSS-selector / JSON-API scrapers
│   ├── iptv/IptvProvider.kt             — ✅ Extended M3U + groups
│   ├── cs3/, vega/, skystream/, sora/,
│   │   aniyomi/, nuvio/, manga/, storm/ — 🔌 adapter scaffolds
├── player/PlaybackService.kt            — Media3 foreground service
└── ui/
    ├── theme/StormTheme.kt              — navy + electric-blue Material 3
    ├── navigation/Screens.kt
    ├── components/                      — PosterCard, ContentRow, ChannelCard
    └── screens/                         — Home, Search, Extensions, Settings,
                                           Detail, Player (ExoPlayer)
```

## Building

1. Install Android Studio (Iguana or newer) with JDK 17.
2. Open this folder as an existing Android Studio project.
3. Let Gradle sync. Android Studio will download the Gradle wrapper, AGP, and
   all dependencies (Compose BOM, Media3 1.4, OkHttp 4, Coil, JSoup,
   kotlinx-serialization).
4. Run the `app` configuration on an Android device or emulator (minSdk 24).

On first launch StormStream bootstraps two public sources so Home isn't empty:

- Stremio Cinemeta catalog (`https://v3-cinemeta.strem.io/manifest.json`)
- Public IPTV org playlist (`https://iptv-org.github.io/iptv/index.m3u`)

Open the **Extensions** tab to add your own sources.

## Adding a provider

### Stremio addon
Extensions → **Add** → *Stremio addon* → paste any `manifest.json` URL (community
addon lists, your own, etc.).

### Universal scraper
Extensions → **Add** → *Universal scraper* → paste a JSON config:

```json
{
  "name": "MySite",
  "baseUrl": "https://example.com",
  "mode": "HTML",
  "catalogs": [
    { "id": "home", "name": "Home", "type": "movie", "path": "/movies" }
  ],
  "search": {
    "path": "/search?q={query}"
  },
  "detail": { "description": { "selector": ".desc" } }
}
```

Selectors default to common classes (`.item`, `.card`, `h2`) out of the box and
can be overridden per-catalog. JSON-API mode is enabled with `"mode": "JSON"`
and dotted-path field names.

### IPTV / M3U
Extensions → **Add** → *IPTV playlist* → give it a name and paste an `.m3u` URL.

### Extension repos
Extensions → **Add repo** → paste a `repo.json` URL or a `github.com/owner/repo`
shorthand. StormStream understands Hiki/Vega-style repos, CloudStream-style
`pluginLists` manifests, and plain plugin arrays.

### CloudStream / Vega / SkyStream / Sora / Aniyomi / Nuvio / Manga / Storm
These adapter slots are present in `ProviderManager.installScaffold` and have
matching source files, so repos and install UI enumerate them uniformly.
Drop-in runtimes for each (QuickJS engine for JS-based ecosystems, dex
classloaders for Kotlin-based ones) plug into the corresponding scaffold
classes without changing anything else in the app.

## Playback

Playback uses AndroidX **Media3 ExoPlayer** with HLS, DASH, and progressive
(MP4/MKV) support, per-source HTTP headers passed through to the data source,
and a `MediaSessionService` for background playback / notification controls.
Subtitles from Stremio addons are already parsed and plumbed to the
`StreamSource.subtitles` field.

## Differences vs. Hikari

This is a **clean-room build** — the architecture, naming, package layout,
theme, and UI are original. It supports the same breadth of provider
ecosystems because they all solve the same problem (discover + resolve streams),
but they're adapted through StormStream's own `StreamProvider` contract rather
than copied code.

## License

MIT.
