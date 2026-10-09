# StormStream — build plan

**StormStream** is a universal, source-agnostic media hub for Android (phone + TV): one player, one
library, one search — fed by pluggable sources written against **our own** extension system.

This is a clean-room project. See [`docs/CLEAN_ROOM.md`](docs/CLEAN_ROOM.md) for the no-copy rules and
the scope lines we will not cross. Everything below is a specification, not a port: interfaces,
naming, layout, algorithms and assets are ours.

---

## 1. Product definition

| | |
|---|---|
| **One-liner** | A fast, modular Android media hub: add a source, get Home + Search + Detail + Player for free. |
| **Platform** | Android (phone, tablet, foldable, TV), Kotlin + Jetpack Compose, single Activity. |
| **App id** | `com.stormstream.app` |
| **Min / target SDK** | min 24 · target 35 · compile 35 (bump only when a dependency requires it) |
| **License** | Apache-2.0 for the app and the extension SDK (so third-party sources can be written without GPL obligations on their own repo) — decided once, see §8 risk R6 |
| **Speed** | The product claim. We publish measured numbers per release (§`docs/PERFORMANCE.md`) and CI fails a build that regresses past a budget. |

### What "our system" means
1. **Own provider contract** — `StormSource` (§2 in `docs/ARCHITECTURE.md`), small, total, versioned.
2. **Own extension format** — `.stormx` (dexed JAR) + `storm-repo.json` index, with **signed,
   permission-scoped** extensions (ed25519 minisign + a declared host capability list). No prior
   streaming app of this family does this; it is our differentiator, not a nice-to-have.
3. **Own source kinds, first-party and legal by construction** — HTTP/JSON data-rule sources,
   user media servers, local files, open catalogs. See §3.
4. **Own UI system** — Material 3 expressive, our tokens, our layout engine for rows/grids, TV
   variant of the same screens rather than a forked screen set.

### Success criteria for v1.0
- A user with no dev tools installs one APK and, in < 60 s, has Home populated from ≥ 3 sources.
- Cold start to interactive Home: **p50 ≤ 450 ms** on a mid-range 2023 device.
- Detail header + episode list painted in **≤ 1.0 s** p50 (cached: ≤ 150 ms).
- First playable server in **≤ 1.2 s** p50 when the source answers fast.
- arm64 APK **≤ 12 MB**. No crash, no ANR, in 30 min of scripted scroll/seek.
- Every source failure explains itself in the UI (reason, not an empty list).

---

## 2. Engineering constraints we design around (read first)

1. **CI is our compiler.** The dev sandbox has no JDK, no Gradle, no Android SDK, 2 cores, 3 GB RAM.
   We cannot run `assembleDebug` locally. Consequences, all enforced in
   [`docs/BUILD_PIPELINE.md`](docs/BUILD_PIPELINE.md):
   - small vertical slices, one push = one coherent compilable change;
   - compile-safety rules (no unverified API guesses, no cross-module symbol drift, one module per PR
     touch-set where possible);
   - CI split into `gate` (no JVM, ~60 s: structure + doc/CHANGELOG + secret scan + version pins) and
     `build` (`:app:compileDebugKotlin` on PRs; full `assembleRelease` + R8 + size gate on `main`);
   - Gradle build cache + `actions/cache` in CI so a `main` round-trip is ~4 min, not 12.
2. **No vendored binaries in v1.** No third-party `.jar`/`.aar` blob committed to the repo, no
   prebuilt `.so`. Every binary dependency comes from a Maven coordinate. That is what makes a
   12 MB APK and an auditable supply chain possible.
3. **No `Application.onCreate` work.** Boot is a state machine with explicit phases
   (`cold → catalog → hydrated`); every subsystem is lazy per screen. This is where most
   "fast vs. slow" of this app family is won or lost.
4. **Docs are rules with the symptom that created them** (adopted as practice, not copied): each
   subsystem doc states the invariant and *why*, so a later refactor cannot silently delete it.

---

## 3. Source scope — what StormStream can host

**In v1 (all legal-by-construction, all behind the same contract):**

| source kind | what it is | why it earns its place |
|---|---|---|
| `LOCAL` | files on device + SAF trees, per-folder media scan | zero-network floor, makes the app useful offline, drives the player + library |
| `SERVER` | user's own media server: **Jellyfin**, **Navidrome/Subsonic**, **Emby** API, DLNA/UPnP root device | the real "add a source" story for a legal app; already has auth, catalogs, subtitles, transcode info |
| `OPEN` | first-party adapters for freely licensed catalogs (e.g. Internet Archive audio/video, Project Gutenberg audio, public-domain film sets) | proves the contract end-to-end with a real install-and-play flow, no user config |
| `DATARULE` | declarative source: a JSON doc with URL templates + CSS/JSON path selectors, `GET`/`POST`, auth headers, pagination, item→fields mapping | the genuinely novel piece; a general "HTTP API + selectors → catalogs/episodes/streams" engine. Also what `.stormx` extensions can target instead of writing parsing code |
| `PLAYLIST` | user's own M3U/M3U8 playlist files & URLs (channels, radio, podcast video feeds) | cheap, useful, and needs no scraping |
| `STORMX` | third-party dexed-JAR extension against `:sdk:storm-ext`, signed + capability-scoped | the ecosystem door; ships in M4 with a documented SDK and one sample extension |

**Explicitly out of scope for StormStream (see `docs/CLEAN_ROOM.md` §B):**
- Cloudflare/anti-bot challenge solving, `js-cookie` dances, `wsidchk`-class solvers.
- Deliberate header spoofing (`Referer`/`Origin`/desktop UA) to defeat hotlink protection of
  third-party CDN content the user has no right to fetch.
- Torrent/magnet streaming, tracker lists, embedded BitTorrent/HTTP-torrent engines, debrid bridges.
- Scrapers/plugins targeting sites whose catalog is infringing copies, and any first-party "repo of
  pirate sources" (no companion repo of that kind, no README instructions to add one).
- DRM bypass / key extraction of any kind. DRM *playback* with a license the user legitimately holds
  (Widevine L1/L3 with a provided license URL) is fine and stays in the player contract.
- Telegram/TDLib "watch videos from my chat" hosting — different product, huge binary, we skip it.

A `DATARULE` or `STORMX` source can in principle point anywhere; the app's *own* bundled content and
documentation will not point at infringing catalogs. That line is what keeps this project shippable.

---

## 4. Architecture summary (details in `docs/ARCHITECTURE.md`)

```
:app                      single Activity, nav graph, theme, DI wiring, TV/phone layouts
:core:contract            StormSource, models, error taxonomy  ← no Android deps, published SDK base
:core:boot                phased startup state machine, perf tracing, device class
:core:net                 OkHttp pool, cache, DoH, conditional GET, per-host politeness
:core:json                kotlinx.serialization, JSON-pointer/Path selector engine (shared by DATARULE)
:core:html                jsoup-based CSS selector + text transforms (shared by DATARULE)
:core:store               DataStore prefs + Room (catalog/episode/stream caches, library, history)
:core:registry            source registry, install/enable, capabilities, capability-gated perms
:core:scheduler           budgets, waves, hang detection, prefetch, dedupe  ← the speed machinery
:player                   Media3: ExoPlayer setup, headers, subs, audio, PiP, resume, effects
:ui:shell :ui:components  Material 3 tokens, row/grid primitives, image pipeline, error states
:feature:home :search :detail :sources :downloads :library :settings :stats :pair
:sdk:storm-ext            the public extension SDK (tiny, stable, versioned) — extensions compile vs this
:sdk:storm-repo           repo index format, signature verification, install pipeline
:tools:size-gate          python: APK/method/perm budget checker (runs in CI `gate`)
:tools:structure-check    python: module-boundary + doc/CHANGELOG invariants
:perf:baselineprofile     androidx.baselineprofile, generated & published
:perf:benchmark           macrobenchmark + per-frame budget checks (nightly CI on emulator)
```

Rules the architecture enforces:
- `:core:contract` has **zero** Android/OkHttp deps → the same file compiles into the SDK; extensions
  and the app share one truth about the data model.
- No feature module imports another feature; all cross-screen data flows through `:core:*` state
  holders exposed as `StateFlow` of immutable snapshots.
- **Every** source call goes through `:core:scheduler`. Nothing in `:feature:*` calls a source
  directly. This is what makes "fast" a system property instead of 13 different code paths.
- No screen is allowed to hold logic that could not run on TV; TV is a layout mode, not a fork.

---

## 5. Milestones

Each milestone ends with: green CI, an installable signed APK from the `continuous` prerelease, a
`CHANGELOG.md` entry, updated perf numbers, and a merged PR. No milestone ends with "code written".

### M0 — Walking skeleton (1 day of pushes)
Gradle multi-module scaffold, `libs.versions.toml` pins **resolved by the first CI run** (CI prints
the versions it actually took; we then freeze them), `:app` with a Compose "StormStream" screen,
`.github/workflows/ci.yml` (`gate` + `build`), signing via repo secrets, ABI splits, R8 full mode,
`size-gate` at 12 MB, README/PLAN/docs, `SECURITY.md`, issue templates, `dependabot.yml`.
**Accept:** APK downloads, installs, launches; CI green in < 6 min with warm cache; size report in
the job summary.

### M1 — Player first
`StreamSource` → Media3: HLS/DASH/MP4/MKV-over-range, per-source headers, subtitle tracks (embedded +
external WebVTT/SRT), audio track + delay, speed (1.0–4.0), seek-chapter, PiP, resume-to-position,
lock-screen/session controls, background-audio for audio types, buffer profile table by `deviceClass`.
**Accept:** plays a user-supplied m3u8 + mp4 + a Jellyfin stream; first frame ≤ 900 ms p50 on
mid-range; zero dropped frames on a 1080p30 test clip; `Player` never exceeds 200 lines of
per-feature glue (all logic in ViewModels/use-cases) — the anti-`PlayerActivity` rule.

### M2 — Source framework + first sources
`StormSource` implementation for `LOCAL`, `PLAYLIST`, `SERVER:Jellyfin`, plus the `DATARULE` engine
(URL templates, selectors, pagination, field mapping, auth headers) with a schema-validated JSON doc.
Registry UI: add/enable/edit/test-one-call with a live request inspector.
**Accept:** 4 sources installed; `DATARULE` can be pointed at any JSON API and produce a catalog;
the inspector shows the raw request/response + the exact mapping failure for a broken rule.

### M3 — Home / Search / Detail / metadata
Row-based Home (per-source catalogs, cached-first paint, streaming append), cross-source search with
budgets + per-source verdict chips, Detail with episodes/seasons, optional TMDB/AniList enrichment
**with the user's own key** in Settings (never a bundled key), library + continue-watching +
watch progress, ratings/cast panels.
**Accept:** Home paints from cache ≤ 150 ms; search shows every source's outcome (found/empty/why)
within 3 s; Detail ≤ 1.0 s p50; no blank state anywhere without a stated reason.

### M4 — Extension SDK + signed repo
`:sdk:storm-ext` (≤ 40 KB of API surface, `@Stable` annotations, `apiVersion` handshake),
`.stormx` packaging (dexed JAR + manifest + ed25519 signature), capability model
(`hosts`, `net`, `storage`, `webview` — none granted by default), `storm-repo.json` v1, install /
update / revoke flow, one **sample** open-source extension in `/extensions/sample` compiled against
the published SDK, `:doc:WRITING_A_SOURCE.md` with a build snippet.
**Accept:** a stranger can build a working source from the docs alone in one sitting; an unsigned or
over-capability extension is refused with an explicit error screen.

### M5 — Downloads, stats, profiles, lock
Media3 `DownloadManager` + our queue (episode ranges, retry, priority, mobile-data rules, export),
watch stats, multiple profiles (per-profile sources/library/lock), app lock (PIN + biometric),
backup/restore as a streamed file (never an in-memory string), device pair over LAN with a code
(no QR dependency required for TV).
**Accept:** 100-episode download survives reboot and app-kill; backup of a 500-row library peaks at
< 40 MB RSS; profile switch never shows another profile's data in a single frame.

### M6 — TV, polish, perf gates
TV layout (D-pad focus system, 10-foot rows, remote shortcuts), themes/accents/font, 12+ locales via
a documented CSV flow, `Perfetto` trace markers for every user-perceivable path, baseline profiles in
release, nightly `:perf:benchmark` gate in CI, APK size and method-count budgets in the PR body
automatically.
**Accept:** same feature set usable from a remote; nightly perf job green for 7 consecutive days;
v1.0 tagged with the published benchmark table.

### Later, decided by demand (not scheduled)
Community JS-script sources via a sandboxed engine (QuickJS) — requires a policy review first;
tablet/pen reader mode for comics; Live TV EPG; casting; a desktop (Compose) build.

---

## 6. Performance budgets (enforced, not aspirational)

Full table + how each is measured in [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md). Headline budgets:

| budget | target | enforced by |
|---|---|---|
| Cold start → interactive Home (p50 / p95) | 450 / 900 ms | nightly macrobenchmark, fails > +10 % |
| First frame after tap (p50) | ≤ 900 ms network, ≤ 250 ms local | benchmark + `reportFullyDrawn` marker |
| Detail: header+episodes painted | ≤ 1.0 s p50 | scheduler budget config + test |
| First playable server | ≤ 1.2 s p50 | origin fast-path in `:core:scheduler` |
| Home scroll p99 frame time (60 Hz) | ≤ 16.6 ms, no frame > 50 ms | `FrameMetrics` aggregation in benchmark |
| PSS at Home / in player | ≤ 180 / 260 MB | `MemoryReport` + benchmark |
| arm64 APK / dex methods after R8 | ≤ 12 MB / ≤ 120 k | `:tools:size-gate` on every PR |
| Boot main-thread work | ≤ 60 ms total | strict-mode trace in debug builds |
| Third-party `.so` in APK | 0 | `gate` job grep |

How we beat the app we are benchmarking against: no vendored runtime jar, no native blobs, no
13-ecosystem class-loading at boot, cached-first paint everywhere, one scheduler with per-source
budgets + waves + speculative prefetch, Compose-only UI with `remember`-stable row models, baseline
profiles from day one, and a hard size/perf gate in CI (an app that measures itself does not drift).

---

## 7. Repo & release process

- Branch: everything on `arena/8de5e4d7-stormstream` this session → PR to `main`.
- `main` = protected-by-convention: green CI required, one PR per milestone slice, squash-merge.
- Push to `main` publishes to the **`continuous` prerelease** only (test channel users install).
- A **versioned release** requires the owner's explicit ask in chat and a `CONFIRM-RELEASE`-style
  typed CI input — same double-gate discipline our reference project uses, because it demonstrably
  prevents an agent from shipping to every user by accident.
- `CHANGELOG.md`: every release section is user-facing prose in the house style (what a user
  notices, then why it broke). Internal post-mortems go in the PR body, not the changelog.
- `docs/` = rules. A PR touching a subsystem's hot path must update the matching doc or explain why
  not in the PR body (checked by `:tools:structure-check`).
- Never commit: agent workspace notes, tokens, keystore files, screenshots of user data. (Public
  lesson from the project we studied: one stray `README` in a `src/` dir documented "re-ask the user
  for their PAT in chat".)

---

## 8. Risks

| # | risk | mitigation |
|---|---|---|
| R1 | **CI-only builds make compile errors the main loop cost** (~4 min/iteration) | compile-safety rules in `docs/BUILD_PIPELINE.md`; `build` job runs `compileDebugKotlin` on PRs; module boundaries keep the graph small; a `gate` job catches structural errors in 60 s |
| R2 | Compose-only player can feel worse than `PlayerView` + XML for remote/TV key handling | `AndroidView(PlayerView)` for surface, Compose overlay for controls; D-pad tested from M1, not M6 |
| R3 | `DATARULE` engine grows into an unmaintainable mini-language | fixed selector grammar (`json-pointer`, `css`, `regex`, `map`, `concat`, `firstOf`); additions need a doc entry + a conformance test vector |
| R4 | Third-party sources used for infringement → DMCA pressure on the repo | bundled sources are legal-only; no pirate repos/instructions; extensions are signed + capability-scoped; a documented takedown/contact path in `SECURITY.md`; a clear "we do not host or index content" policy page |
| R5 | Media3 OEM variance (HDR, DV, audio passthrough) on cheap devices | `deviceClass` tiers, per-codec allowlist learned from failures, external-player handoff for cases we can't decode |
| R6 | License choice creates friction later (Apache vs GPL for a media app) | decide now, publish as `LICENSE` + `NOTICE` in M0, and say it in README |
| R7 | Single maintainer, 20-version-a-week pace caused the reference project's red-CI churn | we ship less often, each `main` push green-or-reverted; `continuous` channel absorbs testing |
| R8 | Scope creep back into scraping/DRM-adjacent features | `docs/CLEAN_ROOM.md` §B is a checkable list; `:tools:structure-check` greps for banned dependency coordinates and API names |

---

## 9. Decisions I need from you (then I start M0)

1. MVP source mix (default: `LOCAL` + `PLAYLIST` + `SERVER:Jellyfin` + `DATARULE`).
2. min SDK 24 vs 26, and whether TV is in v1 or v1.1.
3. License: Apache-2.0 (recommended) vs GPL-3.0.
4. Public or private repo, and whether the `continuous` prerelease + signing key is set up now (needs
   your repo secrets) or in M6.
