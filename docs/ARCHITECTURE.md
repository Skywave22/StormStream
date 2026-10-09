# StormStream architecture

Contract-level specification. Written from first principles for this project; no external code,
comments, or asset files are reproduced here (see `docs/CLEAN_ROOM.md`).

## 1. The provider contract

Everything a source can do fits in one interface. It is the only file extensions compile against, so
it stays small and boring.

```kotlin
// :core:contract  (also the body of :sdk:storm-ext)
public interface StormSource {
    public val meta: SourceMeta              // id, name, version, apiLevel, kinds, capabilities

    /** Cheap, no network: what this source can show. */
    public fun catalogs(): List<CatalogId> = emptyList()

    suspend fun catalogPage(id: CatalogId, page: Page): CatalogPage
    suspend fun search(query: String, page: Page): CatalogPage
    suspend fun details(ref: MediaRef): Details
    suspend fun episodes(ref: MediaRef): List<EpisodeRef>?            // null ⇒ single-item media
    suspend fun streams(ref: MediaRef, ep: EpisodeRef?): StreamSet     // required
    suspend fun subtitles(ref: MediaRef, ep: EpisodeRef?): List<SubTrack> = emptyList()
    suspend fun resolve(token: ResolveToken): Stream? = null           // lazy/expansion-only URLs
}
```

Design rules (each exists to kill a specific failure mode):

- **Total, not optional-heavy.** `streams` is the only mandatory call; everything else has a default,
  so a 40-line source is legal. No `init`/`dispose` (the registry owns lifecycle), no `context`
  leaking Android into extensions.
- **`CatalogPage` carries a verdict.** `items + next + outcome` where `outcome` is
  `Complete | Partial(reason) | Failed(reason) | BlockedByPolicy(reason)`. An empty list must never be
  ambiguous: "site had nothing", "selector matched nothing", "403", "timed out", "extension lacks the
  `hosts` capability for that URL" are five different user messages and five different code paths.
- **`Page` is 1-based** and every caller passes 1 first; a source with no paging returns content for
  `page == 1` and nothing after. (Documented in the SDK because it is the classic off-by-one that
  makes a source look permanently empty.)
- **`Stream.headers` is a first-class field** (needed for user media servers with auth tokens and for
  CDN signed URLs) and **is scrubbed before logging/sharing**. The engine adds nothing on its own —
  no UA or `Referer` rewriting for third-party hosts (see Refusals).
- **No blocking API in the contract.** Everything is `suspend`; the scheduler owns dispatch and
  timeouts, so a misbehaving source cannot consume the app's threads.
- `apiLevel` (int) + `minAppVersion` gate compatibility: the registry refuses to load a source built
  against a newer contract and says so.

## 2. Models (`:core:contract`)

```kotlin
MediaRef(sourceId, externalId, kind)                    // kind: MOVIE | SERIES | SEASON | EPISODE | AUDIO | LIVE
CatalogId(sourceId, key, title, kind, filters)
CatalogPage(items: List<Title>, next: Page?, outcome: Outcome)
Title(ref, name, originalName?, poster?, backdrop?, year?, rating?, overview?, genres, seasonHint, flags)
EpisodeRef(ref, index /*absolute*/, season?, number?, name?, still?, overview?, airDate?, runtime?, rating?)
Stream(url, kind /*HLS|DASH|Progressive|Dash|Rtmp*/, headers, quality?, bitrate?, drmLicense?,
       subtitles, lang?, isDefault?, expiration?)
StreamSet(streams: List<Stream>, outcome: Outcome)
SubTrack(url | inlineVtt, lang, title, format)
Outcome / Reason                                        // sealed, stable strings + a code for telemetry
```

`Title.uniqueId = "src:<sourceId>:<externalId>"`; identity for library/history is `uniqueId`, never a
title string. All models are `@Immutable` so Compose can skip.

## 3. The scheduler — where "fast" is implemented

`:core:scheduler` is the only thing that calls a source. One place to make every tradeoff.

```
CallSpec(target, budgetMs, priority, dedupeKey, cachePolicy, attemptPolicy)
   │
   ├─ cache-first: Room snapshot emitted immediately (stale-while-revalidate)
   ├─ budget: withTimeoutOrNull(budgetMs) → Outcome.timedOut, source marked suspect
   ├─ waves: sources grouped by measured latency class → wave 0 (fast, ≤3) then the rest
   ├─ dedupe: in-flight map keyed by dedupeKey → a second subscriber attaches, no double request
   ├─ hang detection: 2 consecutive timeouts → cooldown, source shown as "asleep", retried on tap
   ├─ politeness: per-host token bucket (max N concurrent, min gap), so one source can't eat the pool
   ├─ cancellation: ViewModel-scoped; a superseded search result is dropped before it reaches UI
   └─ speculative: on Detail open → prefetch streams for the resume episode; on episode N playing →
      warm resolve for N+1 (one source call, low priority, cancelled if the player is busy)
```

Budget defaults (tunable in Settings → Advanced, stored per source after measurement):
`catalog 6 s · search 10 s · details 5 s · episodes 8 s · streams 20 s (first 5 s) · resolve 12 s`.
A source's *measured* p50 becomes its budget after 20 samples (floor 1.5 s, ceiling 3× default).

Concurrency: `Dispatchers.IO` bounded by a `Semaphore` sized from `deviceClass`
(2 high / 4 mid / 8 default network-parallel), never `Unconfined`, never `runBlocking` on main.

## 4. Data & caching (`:core:store`)

| cache | store | TTL / eviction | key |
|---|---|---|---|
| catalog pages | Room `catalog_page` | 6 h, LRU 2 000 pages | `(sourceId, catalogKey, page)` |
| details | Room `details` | 7 d | `uniqueId` |
| episodes | Room `episodes` | 7 d, invalidated on season change | `uniqueId` |
| streams | memory LRU 200 + Room 1 h | signed URLs expire: `expiration` honored | `(uniqueId, episodeIndex, variant)` |
| artwork | Coil memory (device-scaled) + disk 150 MB | 30 d | url |
| library/history/progress | Room, source of truth | never auto-evicted | `uniqueId` |
| prefs / source rows | DataStore proto | — | typed keys |

- **JSON never parsed on the main thread**, always via kotlinx.serialization over Okio streams (no
  intermediate `String` for large payloads) — the exact class of bug that produces OOM on backup in
  this app family.
- Room entities are append-only per page with `next == null` as the stop condition, so a "load more"
  that returns duplicates is impossible by schema (`PRIMARY KEY(key, index)` + `REPLACE`).
- All caches store the `Outcome` too, so a cold launch can render "3 sources asleep" from disk.

## 5. Startup phases (`:core:boot`)

```
cold   Application: DI graph only. No disk reads, no class loading of extensions.  (< 15 ms)
shell  Activity: theme + empty Home skeleton, BaselineProfile emitted at first frame.
data   Home reads Room snapshot (one query) → rows paint. No network awaited.
warm   Registry hydrates enabled sources lazily (per screen, per kind), extension DCLs created
       on first use of that source, not at boot.
idle   Background sweep (update catalogs, prefetch next episodes) at low priority; suspended while
       the player is active, while `isLowBattery`, or while `userScrolling`.
```

Invariant: **nothing in `Application.onCreate` may touch the network or a class loader.** Verified by
a debug-build `StrictMode` + a `traceBegin/End` pair that `:perf:benchmark` asserts on.

## 6. Extension loading (`:sdk:storm-repo`, `:core:registry`)

1. `storm-repo.json` → list of `{id, name, version, url, signature, apiLevel, capabilities, sha256}`.
2. Download → verify sha256 **and** ed25519 signature against a trusted key set (repo key or
   extension-author key, shown in UI as a fingerprint) → unverified = refused, with a visible reason.
3. `DexClassLoader` over the `.stormx` (a dexed JAR) with `libraryDir` for extraction; the parent
   classloader exposes only `:core:contract` + an allowlist of shared libs (`okhttp3`, `kotlinx.coroutines`,
   `kotlinx.serialization`, `org.json`) — extensions bundle nothing else.
4. Instantiate `StormSource` via a declared service entry point; wrap every call with the capability
   proxy: an HTTP call to a host not in the source's granted `hosts` list throws
   `BlockedByPolicy` and shows up in the source's diagnostic panel.
5. Failure isolation: a throwing constructor/`init` removes only that source, logs the trace, and the
   process keeps running. (A crash that relaunches the app to Home is the worst possible UX here.)
6. `webview` capability is **not** grantable in v1 (no embedded WebView execution in extensions).

Capability grants are shown at install time as a plain-language list, and are revocable per source.

## 7. Player (`:player`)

Media3 `ExoPlayer` + `AndroidView(PlayerView)` surface, Compose overlay for controls.
- `OkHttpDataSource` from `:core:net` (shared pool + cache), headers applied per `Stream`.
- `DefaultTrackSelector` with an allowlist of preferred MIMEs (see `docs/PERFORMANCE.md` §codecs) and
  adaptive-`preferredMaxWidth` by `deviceClass`; `setAudioAttributes` per content type.
- Effects as a `preset: List<Effect>` (brightness/contrast/saturation/warmth/volume-boost) applied via
  `SetSpeedWithAudioMerge`/`CustomEffect` chain — **rebuilt on gesture-end only, never per tick**
  (per-tick GL chain rebuilds are a known stall cause on 4K/AV1 in this app family; we design it out
  by construction and add a stall watchdog that re-creates the chain once and toasts).
- Live sources detected by `MediaItem.LiveConfiguration`; no episode UI, no resume write.
- Resume: `ProgressRepository` (throttled 2 s, batched write, process-wide scope so `onStop` can't
  drop it).
- Subtitles: sidecar VTT/SRT via `MergingMediaSource`, embedded handled by Media3; a subtitle search
  panel is available for tracks the user legitimately has rights to.
- DRM: `DrmSessionManager` fed from `Stream.drmLicense` (Widevine) — playback of licensed content the
  user is entitled to; nothing about key extraction.

## 8. UI system

- Single Activity, `navigation3`/type-safe routes, `ViewModel` per feature exposing `StateFlow<UiState>`;
  screens are stateless composables taking `(state, onEvent)`.
- Row/grid primitives in `:ui:components`: `TitleRow`, `TitleGrid`, `Pager`, `HeroBand`, all
  `LazyList` + stable keys (`uniqueId`) + `hazeEffect`-free (blur is the #1 jank source in this
  app family; we ship a static glass material instead, and gate any blur on `deviceClass == high`).
- Images: Coil 3, `size(poster)`, `allowHardware()` on API 26+, crossfade 120 ms, no `subcompose` per
  card. Every card is a fixed-measure pass (`Modifier.width(x).aspectRatio`) so scroll has no
  re-measure storm.
- Error/empty/loading are components, not strings: `SourceVerdict`, `EmptyWithReason`, `RetryBar`.
- TV: same screens + `focusRestoration`, D-pad key handling in a `Modifier` at the shell level,
  focus rings drawn by a shared modifier (never per-widget).
- No screen may block: if a suspend call can exceed 16 ms it belongs in the VM.

## 9. Testing strategy (no local emulator, so CI + device)

| layer | how |
|---|---|
| selector/`DATARULE` mapping | pure-JVM unit tests with checked-in JSON/HTML fixtures (`:core:json`, `:core:html`) |
| scheduler budgets/waves/dedupe | `kotlinx-coroutines-test` `TestScope` virtual time — deterministic, no sleeps |
| Room DAOs + migrations | Robolectric or instrumented on the nightly emulator job |
| UI | Compose `onDevice` snapshot tests, nightly; plus a `Structure` test asserting no `runBlocking`/`Thread.sleep` in `:feature:*` |
| perf | `:perf:benchmark` macrobenchmark (cold start, scroll p99, first frame) nightly, gate on regression |
| on-device smoke | checklist doc `docs/TESTING.md`, run by the owner before each tag |
