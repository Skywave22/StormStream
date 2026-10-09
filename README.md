# StormStream ⚡

**StormStream** is a universal streaming app for Android, inspired by the
Hikari concept — built from the ground up as a clean-room architecture that
is faster, more resilient, and easier to extend.

| # | Provider system | Status |
|---|-----------------|--------|
| 1 | **Stremio addons** (v3 manifest / catalog / meta / streams / subtitles) | ✅ fully working |
| 2 | **Universal HTML/JSON scrapers** (no-code CSS-selector configs) | ✅ fully working |
| 3 | **IPTV / M3U playlists** (group catalogs + HLS/DASH live playback) | ✅ fully working |
| 4 | **CloudStream `.cs3` plugins** | 🔌 Phase 3 (dex runtime) |
| 5 | **Vega providers** (CommonJS/QuickJS modules) | 🔌 Phase 3 |
| 6 | **SkyStream / Sora / Aniyomi / Nuvio / Manga** | 🔌 Phase 3 |
| 7 | **Native Storm (`.storm`) extensions** | 🔌 Phase 3 |

## What's new in v0.2 (Phase 1)

This build was a complete rewrite of the seed v0.1.0 (an AI-generated scaffold
with ~28 critical bugs). Phase 1 fixes include:

- 🧠 **Persistence** — installed extensions are saved to DataStore and restored
  on cold start; no more "everything disappears on restart".
- 🎬 **Working player** — the Player screen now actually receives the selected
  stream/episode, builds a MediaSource with per-source HTTP headers, and
  renders subtitles (VTT/SRT/ASS) via ExoPlayer's SubtitleConfiguration.
- ⚡ **Concurrent home loading** — all catalogs load in parallel with per-call
  timeouts so one slow addon never freezes the screen; results appear
  progressively.
- 🧰 **Async HTTP client** — OkHttp-backed with per-request timeouts, retries
  on idempotent GETs, shared cookies/cache/UA, proper error return types
  instead of throwing.
- 🔍 **Debounced search** (300 ms) — no more network flood per keystroke; has a
  loading state.
- 🧱 **Lighter bootstrap** — first launch uses a small news M3U instead of the
  20,000-channel iptv-org index that used to block the UI for 30+ seconds.
- 🚫 **Adult-content toggle** in Settings (persisted).
- ✅ **Enable/disable & uninstall** for every installed provider.
- 🧭 **Correct navigation** — broken path args are gone; the ViewModel holds
  the selected item/playback target.
- 🧯 **Error snackbars** — network/parse/timeout failures surface in the UI
  instead of silently disappearing.
- 🎨 **Polished UI** — poster placeholders, loading indicators, episode
  thumbnails, gradient backdrop, season grouping, stream chips with subtitle
  badges, better cards, and a refresh button.
- 🖼️ **Image loading** with Coil SubcomposeAsyncImage + placeholders and
  letter fallbacks when posters fail to load.
- 🔔 **Proper MediaSession** — PlaybackService owns an ExoPlayer with audio
  focus, wake lock, and MediaSession for lock-screen / headset controls.
- 🛑 **Parse caps** — IPTV parser is line-sequence and caps at 5000 channels /
  100 groups to protect against runaway public playlists.

### Phase 2 polish
- ⏯️ **Watch history with resume positions** — position saved every 5 seconds
  and on exit; history is capped and persisted.
- ▶️ **"Continue watching"** home row with progress bars; tapping resumes.
- 🔖 **Bookmarks** on any item (bookmark icon in the detail screen).
- 🎞️ **Auto-rotating hero carousel** of featured content.
- 🔁 **Auto-resume** for movies/series with "Resume" label and percent; auto-
  selects the next unfinished episode when you open a show.
- 🕶️ **Incognito mode** — watch without writing history.
- 🗑️ **Clear history** in Settings.
- ⚙️ **Revamped settings** with cards, stats, About dialog.
- 📺 **Player improvements** — buffering spinner, error overlay with retry,
  seek-to-resume on stream load.

## Architecture

100% Kotlin + Jetpack Compose with Material 3, dark-first navy/electric-blue
theme. The core contract every backend implements:

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

The UI, database, and player talk **only** to this interface. Each provider
type lives under `providers/<type>/` and adapts its native protocol into this
contract.

### Package layout

```
app/src/main/java/com/stormstream/app/
├── StormApp.kt / MainActivity.kt        — entry points
├── core/Result.kt                       — StormResult / StormError
├── data/
│   ├── Models.kt                        — MediaItem, Episode, StreamSource, etc.
│   ├── AppViewModel.kt                  — single shared VM
│   └── StormStore.kt                    — DataStore persistence
├── net/StormHttpClient.kt               — shared async OkHttp client (retry/timeout)
├── providers/
│   ├── StreamProvider.kt                — the universal contract
│   ├── ProviderManager.kt               — registry + fan-out queries
│   ├── plugin/PluginRepoManager.kt      — extension repo loader
│   ├── stremio/StremioAddonProvider.kt  — ✅ Stremio v3 addon protocol
│   ├── scraper/UniversalScraper…        — ✅ CSS-selector / JSON-API scrapers
│   ├── iptv/IptvProvider.kt             — ✅ Extended M3U + groups
│   └── cs3/, vega/, skystream/, sora/,
│       aniyomi/, nuvio/, manga/, storm/ — 🔌 adapter scaffolds (Phase 3)
├── player/PlaybackService.kt            — MediaSession service + MediaSource factory
└── ui/
    ├── theme/StormTheme.kt              — navy + electric-blue Material 3
    ├── navigation/Screens.kt
    ├── components/                      — PosterCard, ChannelCard, ContentRow
    └── screens/                         — Home, Search, Extensions, Settings,
                                           Detail, Player
```

## Building

1. Install Android Studio (Iguana or newer) with JDK 17.
2. Open this folder as an existing Android Studio project.
3. Let Gradle sync (Compose BOM, Media3 1.4, OkHttp 4, Coil, JSoup,
   kotlinx-serialization, DataStore).
4. Run the `app` configuration on an Android device or emulator (minSdk 24,
   targetSdk 35).

On first launch StormStream bootstraps two lightweight seed sources so Home
isn't empty:

- Stremio Cinemeta (`https://v3-cinemeta.strem.io/manifest.json`)
- A small news M3U playlist from iptv-org

Open the **Extensions** tab to add your own sources.

## Adding a provider

### Stremio addon
Extensions → **+** → *Stremio addon* → paste any `manifest.json` URL.

### Universal scraper
Extensions → **+** → *Universal JSON/HTML scraper* → paste a JSON config:

```json
{
  "name": "MySite",
  "baseUrl": "https://example.com",
  "mode": "HTML",
  "catalogs": [
    { "id": "home", "name": "Home", "type": "movie", "path": "/movies" }
  ],
  "search": { "path": "/search?q={query}" },
  "detail": { "description": { "selector": ".desc" } }
}
```

Default selectors (`.item`, `.card`, `h2`, `a`, `img`) work out of the box and
can be overridden per-catalog. JSON-API mode is enabled with `"mode": "JSON"`.

### IPTV / M3U
Extensions → **+** → *IPTV / M3U playlist* → name + M3U URL.

### Extension repos
Extensions → **Add repo** FAB → paste a `repo.json` URL or a
`github.com/owner/repo` shorthand. StormStream understands Hiki/Vega/Storm
repos, CloudStream `pluginLists` manifests, and plain plugin arrays.

## Playback

- AndroidX **Media3 ExoPlayer** with HLS, DASH, progressive (MP4/MKV)
- Per-source HTTP headers passed through to the data source
- Subtitles (VTT/SRT/ASS) attached via `SubtitleConfiguration` and selectable
  in the player's caption menu
- Stream switcher chips for quick source changes during playback
- Audio focus handled by the player; wake lock kept while playing
- MediaSession service for background playback / notification controls

## Differences vs. Hikari

This is a **clean-room build** — architecture, naming, package layout,
theme, and UI are original. The provider contract (`StreamProvider`) and its
adapters are written from scratch for StormStream rather than ported from
Hikari's codebase.

## Roadmap

- **Phase 2 (in progress / current)** — Watch history + continue watching +
  bookmarks + hero + resume (✅ shipped); next: loading skeletons, TMDB
  metadata enrichment, season jump selector, PiP, better search filters,
  trakt-style lists.
- **Phase 3** — Real plugin runtimes: CloudStream `.cs3` via dex classloader,
  Vega/Nuvio via QuickJS, native `.storm` extension API.
- **Phase 4** — Downloads/offline, Trakt/MAL sync, crash logs & diagnostics,
  backup/restore, Chromecast.

## License

MIT.
