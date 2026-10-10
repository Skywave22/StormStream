# StormStream — Extension System Analysis & v2 Design

Research basis (learning only — no code copied):
- **Hikari** (`codegeasse1/hikari`) — how one app *handles every provider ecosystem*
  (Stremio addons, universal scrapers, CloudStream `.cs3`, SkyStream `.sky`,
  Nuvio scrapers, Sora, Vega, its own `.hiki` jars) and its Material 3 UI/UX.
- **SkyStream** (`Skywave22/skystream-Enhanced`) — the SkyStream plugin system:
  JS-runtime plugins, `.sky` packages, repo format, data model.
- **Nuvio** (`NuvioMedia/NuvioMobile`) — the Nuvio plugin system: manifest
  scrapers, TMDB-keyed `getStreams`, plugin settings layouts, JS polyfills.

---

## 1. The three plugin ecosystems

### 1.1 SkyStream plugins (full-site JS providers)

A SkyStream extension is a **`.sky` file = a zip** containing:

- `plugin.json` — manifest: `packageName`, `name`, `version`, `baseUrl`
  (`mainUrl`), `languages`, `supportedTypes`
  (`movie|series|anime|livestream|other`), and **`settings`** — declarative
  plugin settings: `{key, title, type: bool|select|text|url, defaultValue,
  options: [{label, value}], reloadOnChange, isBaseUrl}`.
- `plugin.js` — the plugin source (ES module or plain script with top-level
  functions). The engine wraps it in a **namespaced installer** so plugins
  cannot collide on `globalThis`.

Plugin API (top-level functions or `module.exports`):

| Function | Returns |
|---|---|
| `getHome()` | `{ "Section": [item…] }` or `[item…]` — categorized home shelves |
| `search(query)` | `[item…]` |
| `load(url)` / `getDetails(url)` | one detailed item (may embed `episodes[]`, `streams[]`) |
| `loadStreams(url)` | `[stream…]` |

Item JSON keys (lenient parsing): `title, url, posterUrl, backgroundPosterUrl |
bannerUrl, logoUrl, description, type | contentType, episodes[], streams[],
provider, headers, year, score, duration, status | showStatus, tags, cast |
actors, trailers, recommendations, syncData, isAdult, tmdbId, imdbId, source`.

Stream JSON keys: `url, source, providerName, headers, subtitles[],
drmKid, drmKey, licenseUrl`.

JS bridge provided to plugins: namespaced `http` (request/get/post with
cancel tokens), `storage`/`get_storage`+`set_storage` (per-plugin key-value),
`getPreference`/`setPreference`, **cheerio** (`cheerio.load(html)`), timers
(`setTimeout`), and host classes `MultimediaItem` / `Episode` / `StreamResult`.
Engines are isolated per plugin; compiled bytecode is cached (`.qbc`).

Repository format (`repo.json`): `{name, description, iconUrl, pluginLists:
[urls], repos: [urls], plugins: [...]}` — `pluginLists` are CloudStream-style
lists of plugin objects, `repos` are nested repo URLs, `plugins` is a direct
list. Plugin object: `{name, url, version, packageName?, namespace?,
manifest?, files?}`.

### 1.2 Nuvio plugins (TMDB-keyed stream scrapers)

A Nuvio extension is a **manifest.json** describing one or more scrapers:

```json
{
  "name": "My Source", "version": "1.0", "author": "…",
  "scrapers": [{
    "id": "…", "name": "…", "version": "1.0",
    "filename": "scraper.js",
    "supportedTypes": ["movie", "tv"],
    "enabled": true, "hasSettings": true, "logo": "…",
    "contentLanguage": ["en"], "supportedPlatforms": […]
  }]
}
```

Each `scraper.js` is a JS module run in QuickJS with a **cheerio + fetch +
timers + TextEncoder/TextDecoder/Blob/URL/AbortController/CryptoJS** polyfill
layer, and two injected globals: `SCRAPER_ID` and `SCRAPER_SETTINGS` (the
user's saved settings for this scraper).

Plugin API:

| Function | Signature | Returns |
|---|---|---|
| `getStreams` | `(tmdbId, mediaType, season, episode)` | `[{title, name, url, quality, size, language, provider, type, seeders, peers, infoHash, headers, subtitles}]` |
| `onSettings` (optional) | `()` | settings **layout**: `[{type: info|text|select|toggle, key, title, options: [{label, value}], defaultValue}]` |

Nuvio plugins have **no catalogs/search of their own** — they are pure stream
sources keyed by TMDB id. Hosts browse TMDB themselves (Hikari does exactly
this: each Nuvio scraper becomes a TMDB-browsed "niche" whose items resolve
through that scraper). Crypto is bridged natively (digest/HMAC/AES via
`MessageDigest`/`Mac`/`Cipher`), so plugins can use a CryptoJS-compatible API.

Repositories: a list of **manifest URLs** (each URL points at a
`manifest.json`); refreshed automatically every 6 h.

### 1.3 Stremio addons (stateless HTTP JSON protocol)

Already implemented for real (v3 protocol). v2 redesign deepens it: full meta
(`videos[]`, genres, cast, ratings), stream `behaviorHints` (proxyHeaders,
bingeGroup, notWebReady), `skip` pagination, per-catalog `search` extras,
genre catalogs, and correct episode stream URLs (`/stream/series/<id:S:E>.json`).

---

## 2. How Hikari handles *all* providers (the architecture lessons)

Hikari's provider core is one `ContentProvider` contract with an **adapter per
ecosystem** (cs3 / skystream / nuvio / sora / vega / hiki / stremio / scraper /
iptv). The lessons worth adopting:

1. **Filter at the source, not in screens.** Adult/NSFW filtering and
   duplicate-id pruning happen in `ProviderManager.refresh()` so no screen
   (Home, search, continue-watching, collections) can leak a hidden provider.
2. **Coalesced refresh.** A `Mutex` + "refresh queued" flag: installs and
   repo syncs that arrive during a build trigger exactly one more build.
3. **Provider settings hook.** `settingsAvailable / prepareSettings /
   openSettings(activity)` — a provider can expose its OWN settings screen
   (CloudStream plugins do via `Plugin.openSettings`). The Extensions UI shows
   a gear only when the provider declares it.
4. **`adultExtension()` hook** — the extension itself answers whether it is
   18+ (CloudStream/Hikari/SkyStream/Nuvio declare it per title in code, so the
   host asks the extension once and remembers).
5. **`warmDetail(item)`** — prefetch meta for items as they become visible
   (cheap perf win for detail screens).
6. **`homeCatalogs()`** — a provider may hold catalogs back from Home (e.g. a
   Stremio catalog that requires a search query is useless as a Home row).
7. **Extension recovery** — a wedged/stale runtime is evicted and rebuilt
   instead of retried against the same dead object.
8. **Bounded load gate** — plugin/dex loads share process-wide slots so one
   jammed extension cannot abort a Home/search sweep.
9. **Nuvio integration pattern** — Nuvio scrapers plug into a TMDB meta layer:
   each scraper is browsed via TMDB search/catalogs, and streams are resolved
   per TMDB id. TMDB keys are public-by-design in OSS apps and user
   overridable (`tmdbApiKey` preference wins over bundled keys).
10. **Logs & diagnostics** — rolling app logs + a crash log (full stack trace
    + last 300 log lines), shareable from Settings. Bug reports carry real
    error text instead of screenshots.

### UI/UX lessons (Hikari + Nuvio + SkyStream)

- **One accent drives everything** (Hikari): a user-pickable accent palette
  (11 accents) flows through `MaterialTheme.colorScheme` AND the player, so
  one choice restyles the whole app consistently.
- **Aura rings on posters** (Hikari): a thin accent ring around poster cards —
  separable from the app accent ("follow accent" default).
- **Glass surfaces** (Hikari): translucent blurred bars/cards.
- **Loading styles + poster styles** (Hikari): skeleton shimmer vs. spinner;
  rounded vs. square posters; a separate **UI scale** setting.
- **Configurable Home shelves** (Nuvio): `HomeCatalogDefinitions` — the user
  can reorder/hide home rows; rows are derived from enabled providers'
  catalogs, cached by a descriptor signature.
- **Detail hero** (Nuvio): backdrop hero with actions, cast, episodes; shared
  -element transitions; content reveal animations.
- **Continue watching / watch progress** (Hikari history + Nuvio watch
  progress): a persisted per-item progress store feeding a Home shelf and a
  resume action on Detail.
- **Plugin-first onboarding** (SkyStream): the app ships with NO sources;
  Home shows an empty state that routes to Extensions. (We keep two curated
  defaults instead, but the Extensions screen is the center of gravity.)
- **Extensions as the center of gravity**: repos + installed list + per-
  provider settings + clear per-provider errors (Hikari shows the real load
  failure on the extension card).

---

## 3. StormStream v2 design

### 3.1 Provider layer (one by one, in the user's order)

1. **SkyStream plugins** — `ProviderType.SKYSTREAM`:
   - Install: `.sky` zip (download → unzip `plugin.json` + `plugin.js` into
     `filesDir/skystream/plugins/<packageName>/`), repo `plugins`/`pluginLists`
     entries, or a bare `plugin.js`/`plugin.json` URL pair.
   - Runtime: per-plugin QuickJS engine on a single-thread dispatcher,
     namespaced install (no `globalThis` collisions), dialect `skystream`.
   - Shim bridge: `manifest` global, `http.request/get/post`,
     `storage`/`getStorage`/`setStorage` (per-plugin SharedPreferences),
     `getPreference`/`setPreference`, `cheerio.load` (ksoup-backed subset),
     `setTimeout`, `MultimediaItem`/`Episode`/`StreamResult` classes.
   - API: `getHome()` → home shelves (becomes Home rows), `search(query)`,
     `load(url)`/`getDetails(url)` → meta+episodes, `loadStreams(url)` →
     streams. Manifest `settings[]` → declarative plugin settings dialog.
   - Repos: `pluginLists` (follow), `repos` (nested), direct `plugins`.
2. **Nuvio plugins** — `ProviderType.NUVIO`:
   - Install: manifest URL (download manifest + each scraper's `filename`),
     repo = manifest URLs, 6 h auto-refresh.
   - Runtime: per-scraper QuickJS engine, dialect `nuvio`; polyfills: fetch
     (async bridge), cheerio (ksoup-backed), timers, TextEncoder/TextDecoder,
     Blob, URL, AbortController, CryptoJS subset (native digest/HMAC/AES
     bridges via `MessageDigest`/`Mac`/`Cipher`); globals `SCRAPER_ID`,
     `SCRAPER_SETTINGS`.
   - API: `getStreams(tmdbId, mediaType, season, episode)`; `onSettings()` →
     layout rendered as the scraper's settings dialog (persisted per scraper).
   - TMDB meta layer: `search/multi`, `movie|tv/{id}`, `find/{imdbId}` with a
     user-overridable API key (Settings) and bundled public keys; Nuvio
     scrapers are browsed through TMDB (Hikari pattern): each scraper gets
     catalog rows ("<Scraper> · Movies/TV") whose items come from TMDB and
     whose streams resolve through that scraper.
3. **Stremio add-ons redesign** — deepen the real v3 implementation (see 1.3).

### 3.2 Shared provider contract additions (Hikari lessons)

- `settingsAvailable` / `prepareSettings` / `openSettings` (plugin settings UI).
- `adultExtension()` hook.
- `warmDetail(item)` prefetch.
- `homeCatalogs()` already exists — keep.
- ProviderManager: coalesced refresh (Mutex), duplicate-id pruning at the
  source, per-provider error isolation (already), source-level adult
  filtering (already).

### 3.3 UI/UX redesign (informed by the above)

- Accent color system (Settings → Appearance): 8–11 accents driving the whole
  theme + player controls.
- Aura ring on poster cards (follow-accent default, separately choosable).
- Glass top/bottom bars in the player.
- Loading style setting (shimmer skeletons vs. spinner) + poster shape +
  UI scale.
- Configurable Home shelves: reorder/hide rows (persisted), per-row cache.
- Continue watching shelf (new `WatchProgressStore`: item id → position/
  duration, updated by the player, surfaced on Home + Detail resume).
- Logs & diagnostics screen: rolling log (last N lines), share via
  ShareSheet, clear.
- Extensions screen: per-provider settings gear, real load errors, repo
  cards with plugin counts.
- Detail: richer hero (backdrop, meta line, actions), cast row, episodes
  with thumbs, streams section.
- Player: accent-driven controls, glass scrim, keep libmpv.

### 3.4 Player — unchanged

One player, internal libmpv (`StormMpvView` + `MpvPlayerController` +
`PlaybackService`). The accent system feeds the player's control colors.

---

## 4. Implementation order

1. `docs/SYSTEM_ANALYSIS.md` (this document).
2. Models + `ProviderType` + `StreamProvider` contract additions.
3. JS shim: `skystream` + `nuvio` dialects (bridge, cheerio subset, crypto
   bridges, polyfills) + harness tests for both.
4. SkyStream provider (runtime, provider, install/restore, repo format).
5. Nuvio provider (runtime, TMDB layer, provider, install/restore, repo
   format, settings dialog).
6. Stremio add-on redesign.
7. UI/UX redesign batch (accent system, aura rings, glass, home shelf
   config, watch progress, logs screen, detail polish).
8. CI + harness green on every step.
