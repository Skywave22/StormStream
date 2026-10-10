# StormStream ⚡

A universal streaming app for Android — **100% Kotlin + Jetpack Compose (Material 3)**,
dark-first UI, and **exactly one player: libmpv, bundled inside the app**.

> One player. Every source. No external player apps, no ExoPlayer/Media3 —
> playback is rendered by the app's own internal libmpv instance
> (`io.github.abdallahmehiz:mpv-android-lib`, which packages `libmpv.so` for
> arm64/arm/armeabi/x86/x86_64 inside the APK).

## What it does

| Feature | How it works |
|---------|--------------|
| **Player** | Internal libmpv (`MpvPlayerController` + `BaseMPVView`). Gestures (tap/double-tap/drag seek/brightness/volume), subtitle & audio track selection, speed control, background playback with a media notification (`PlaybackService`). |
| **Stremio addons** | Real v3 addon protocol: manifest → catalogs → meta → streams → subtitles (`StremioAddonProvider`). Install by manifest URL or `stremio://` deep link. |
| **Universal scrapers** | No-code JSON configs (CSS selectors for HTML, dotted paths for JSON) — paste a config, get a full source (`UniversalScraperProvider`). |
| **IPTV / M3U** | M3U/M3U8 playlists become group catalogs of live channels (`IptvProvider`). |
| **JS plugins (StormJS)** | JavaScript plugins run **in-app** in an embedded QuickJS engine (`quickjs-kt`) with a sandboxed `storm` API: HTTP, HTML parsing, key-value storage (`JsProvider` + `JsPluginRuntime` + `assets/storm-js-shim.js`). |
| **Vega providers** | JSON-based Vega-style providers (CommonJS `catalog`/`posts`/`meta`/`episodes`/`stream` modules) are auto-detected and bridged through the same runtime (`providerContext.axios` etc.). |
| **Extension repos** | Add a `repo.json` URL (Hiki/Vega/SkyStream shape, CloudStream `pluginLists` shape, plain JSON array, or a `github.com/owner/repo` shorthand) and browse + install plugins from it (`PluginRepoManager`). |
| **Persistence** | Every install / uninstall / enable-toggle / repo is stored in DataStore and replayed on app start — extensions survive restarts (`ExtensionStore`, `ProviderManager.restore()`). |

On first run the app bootstraps two curated sources so Home is never empty:
Cinemeta (Stremio) and the IPTV-org demo playlist.

## Architecture

```
app/src/main/java/com/stormstream/app/
├── MainActivity.kt            navigation root, bottom bar, deep links, snackbars
├── StormApp.kt                notification channel + crash logger
├── core/Result.kt             StormResult / StormError
├── data/                      Models, ExtensionStore, SettingsStore, AppViewModel
├── net/StormHttpClient.kt     shared OkHttp client (cache, UA, download)
├── player/
│   ├── MpvPlayerController.kt THE player: one internal libmpv instance, state flows,
│   │                          track selection, headers/subs, eof events
│   └── PlaybackService.kt     foreground service + media notification + wake lock
├── providers/
│   ├── StreamProvider.kt      the ONE contract every backend implements
│   ├── ProviderManager.kt     registry, install/uninstall/restore, fan-out queries
│   ├── stremio/               Stremio v3 addon protocol
│   ├── scraper/               universal HTML/JSON scraper configs
│   ├── iptv/                  M3U playlists
│   ├── js/                    QuickJS runtime (JsPluginRuntime, JsProvider, DTOs)
│   └── plugin/                extension repository loader (PluginRepoManager)
├── ui/                        theme, navigation, components, screens (Home, Search,
│                              Extensions, Settings, Detail, Browse, Player)
└── util/Json.kt               lenient app-wide JSON
```

The UI, persistence and player talk **only** to the `StreamProvider` interface —
they never know which backend a `MediaItem` came from.

```kotlin
interface StreamProvider {
    val config: ProviderConfig
    suspend fun catalogs(): List<CatalogRef>
    suspend fun homeCatalogs(): List<CatalogRef> = catalogs()
    suspend fun getCatalog(ref: CatalogRef, page: Int): List<MediaItem>
    suspend fun search(query: String, page: Int): List<MediaItem>
    suspend fun getMeta(item: MediaItem): MediaItem = item
    suspend fun getEpisodes(item: MediaItem): List<Episode>? = null
    suspend fun getStreams(item: MediaItem, episode: Episode?): List<StreamSource>
}
```

## The StormJS plugin contract

A JS plugin is one or more CommonJS `.js` files. Install it from
**Extensions → + → JS plugin** by URL (a single `.js` file, or a
`storm.plugin.json` manifest describing several module files), or with a
`storm://host/path` deep link. Files are downloaded to
`filesDir/plugins/js-<slug>/` and re-loaded on every app start.

```js
// main.js — a complete StormJS plugin
module.exports = {
  name: "My Source",              // metadata (also read from storm.plugin.json)
  version: "1.0.0",
  description: "...",
  icon: "https://.../icon.png",
  adult: false,

  async getCatalogs() {
    return [{ id: "trending", name: "Trending", type: "movie" }];
  },
  async getCatalog({ catalogId, page }) {
    const r = await storm.http.get("https://api.example.com/" + catalogId + "?page=" + page);
    return r.json.metas.map(m => ({
      id: m.id, title: m.title, type: m.type || "movie",
      poster: m.poster, backdrop: m.backdrop, year: m.year,
      rating: m.rating, description: m.description,
      genres: m.genres || [], url: m.url,           // url = internal link for meta/streams
    }));
  },
  async search({ query, page }) { /* -> [item] */ },
  async getMeta({ item }) { /* -> item (enriched) */ },
  async getEpisodes({ item }) { /* -> [episode] | null */ },
  async getStreams({ item, episode }) {
    return [{
      name: "Server A",
      url: "https://cdn.example.com/video.m3u8",
      type: "hls",                 // "hls" | "dash" | "mp4" | "mkv" | "auto"
      quality: "1080p",
      headers: { "Referer": "https://example.com/" },   // sent to libmpv
      subtitles: [{ label: "English", lang: "en", url: "https://cdn.example.com/en.vtt", format: "vtt" }],
    }];
  },
};
```

Shapes:

```
item    = { id, title, type, poster, backdrop, year, rating, description, genres[], url }
episode = { id, title, season, number, thumbnail, description, url }
stream  = { name, url, type, quality, headers{}, subtitles[{ label, lang, url, format }] }
catalog = { id, name, type }        // type: "movie" | "series" | "anime" | "tv" | ...
```

All methods receive **one args object** (see above) and may be `async`.
Returning `null`/`undefined` for `getEpisodes` means "this is a movie".

### The `storm` API (injected into every plugin)

| API | Type | Notes |
|-----|------|-------|
| `storm.http.get(url, headers?)` | async → `{ status, body, json }` | `json` is the parsed body (or `undefined`) |
| `storm.http.post(url, body?, headers?)` | async → `{ status, body, json }` | body is a string |
| `storm.kv.get(key)` / `storm.kv.set(key, value)` | sync | per-plugin persistent storage |
| `storm.html.selectText(html, selector)` | sync → `string \| null` | CSS-selector text of first match |
| `storm.html.selectTextAll(html, selector)` | sync → `string[]` | text of all matches |
| `storm.html.selectAttr(html, selector, attr)` | sync → `string \| null` | attribute of first match |
| `storm.html.selectAttrAll(html, selector, attr)` | sync → `string[]` | attribute of all matches |
| `storm.html.selectHtml(html, selector)` | sync → `string \| null` | outer HTML of first match |
| `storm.html.selectHtmlAll(html, selector)` | sync → `string[]` | outer HTML of all matches |
| `storm.log(...)` / `storm.warn(...)` / `storm.error(...)` | sync | goes to logcat (`StormJs`) |

Plugins are sandboxed: no file system, no `require()` of npm packages —
use the `storm` API. `require()` of another plugin module throws a clear error.

### Multi-file plugins (`storm.plugin.json`)

```json
{
  "name": "My Multi-Module Source",
  "version": "2.0.0",
  "description": "...",
  "icon": "https://.../icon.png",
  "adult": false,
  "main": "main",
  "files": { "main": "main.js", "extra": "lib/extra.js" },
  "types": ["movie", "series"]
}
```

Relative file URLs resolve against the manifest URL. Every `.js` file in the
plugin directory is registered as a CommonJS module named after the file
(without extension); `main` (or the only module) is the entry point.

### Vega providers (compatibility bridge)

JSON-based Vega-style providers are auto-detected and bridged — no rewriting
needed. Provide the classic CommonJS modules:

```js
// catalog.js
exports.catalog = [{ title: "Movies", filter: "movies" }];
// posts.js
exports.getPosts = async ({ filter, page, providerContext }) => [{ title, link, image, tag }];
exports.getSearchPosts = async ({ searchQuery, page, providerContext }) => [/* posts */];
// meta.js
exports.getMeta = async ({ link, providerContext }) => ({
  title, synopsis, image, poster, imdbId, type,
  linkList: [{ title: "Season 1", directLinks: [{ title, link }] }],
});
// stream.js (or episodes.js)
exports.getStreams = async ({ link, providerContext }) => [{
  title, url, quality, headers, subtitles: [{ label, lang, url }],
}];
```

`providerContext` provides `axios.get/post(url, { headers }) → { status, data }`
(backed by the app's HTTP client), `commonHeaders`, `kvStore` and `console`.
`cheerio`-based providers are **not** supported (no DOM emulation) — they fail
with a clear error at load time.

### Testing the shim / contract

The plugin contract is verified by a Node test harness (no Android needed):

```bash
node tools/shim-test/harness.js
```

It emulates the native bridges, loads `app/src/main/assets/storm-js-shim.js`,
and exercises a StormJS plugin, a Vega multi-module provider, dialect
detection and error paths (25 assertions).

## Extension repositories

Add a repository from **Extensions → + → Extension repository**:

- a `repo.json` URL in the Hiki/Vega/SkyStream shape:
  `{ "name": "...", "plugins": [{ "name", "url", "version", "tvTypes", "icon", "providerType", "description", "files": { "<module>": "<url>" } }] }`
- a CloudStream-style manifest: `{ "name": "...", "pluginLists": ["<url>"] }`
- a plain JSON array of plugin objects
- a `github.com/owner/repo` shorthand (rewritten to the repo's `builds/repo.json`)

`providerType` values map onto what StormStream can actually install:
`stremio` → addon, `scraper`/`universal` → scraper config, `iptv`/`m3u` →
playlist, everything else (`vega`, `nuvio`, `sora`, `skystream`, `cloudstream`,
…) → JS plugin through the QuickJS runtime. Plugins with a `files` map are
downloaded as multi-file JS plugins.

## Deep links

- `stremio://addon.example.com/manifest.json` → installs the Stremio addon
- `storm://plugins.example.com/my-plugin.js` → installs the JS plugin

## Settings

Theme (dark / light / system), hardware decoding, default playback speed,
subtitle scale, auto-play next episode, adult-content toggle, cache clearing.
Everything persists across restarts (DataStore).

## Build

```bash
./gradlew assembleDebug
```

Requires JDK 17 + Android SDK (compileSdk 36, targetSdk 34, minSdk 24).
Toolchain: AGP 9.4.0, Kotlin 2.4.10, Gradle 9.7.1, Compose BOM 2026.08.00 (AGP 9.x
has built-in Kotlin support, so `org.jetbrains.kotlin.android` is not applied)
(the native-runtime libraries — mpv-android-lib, quickjs-kt, ksoup — are
published only at versions built with Kotlin 2.4 / compileSdk 36, so the app
adopts the same toolchain as the production apps that ship them).
CI builds the debug APK on every push (`.github/workflows/build-apk.yml`).

## Player details (libmpv)

- One `MPV` instance per process, created lazily and reused for every stream.
- Per-source HTTP headers are applied through mpv's `http-header-fields`.
- External subtitle tracks (from addons/plugins) are attached with `sub-add`
  and selectable from the player UI; embedded tracks come from `track-list`.
- Audio/subtitle track selection, speed (0.25×–8×), seek, pause — all through
  mpv properties/commands, surfaced as Kotlin `StateFlow`s.
- End-of-file events drive "auto play next episode".
- Background playback keeps a `mediaPlayback` foreground service with
  play/pause/stop notification actions and a partial wake lock.
