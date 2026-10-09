# Performance budgets — the numbers CI holds us to

StormStream's product claim is speed. "Fast" is only real if a build fails when it stops being fast,
so every row below has an owner: `gate` (every PR), `nightly` (`:perf:benchmark` on an emulator), or
`release` (measured by hand on the owner's device and written into the release notes).

Reference device classes for measurements:
`high` = 8-core / 8 GB / 120 Hz · `mid` = 6–8 core / 4–6 GB / 60–90 Hz (budget 2023 phone) ·
`low` = 4-core / 3 GB / API 24.

## 1. Budgets

| id | metric | mid | low | enforced |
|---|---|---|---|---|
| P1 | cold start → interactive Home p50 / p95 | 450 / 900 ms | 700 / 1 400 ms | nightly |
| P2 | Home rows painted from disk cache (no network) | ≤ 150 ms | ≤ 250 ms | nightly |
| P3 | `Application.onCreate` main-thread work | ≤ 15 ms | ≤ 25 ms | nightly (trace span) |
| P4 | Detail header + episode list p50 | ≤ 1.0 s | ≤ 1.6 s | nightly |
| P5 | First playable server p50 (fast source) | ≤ 1.2 s | ≤ 2.0 s | nightly |
| P6 | First frame after play tap (network / local) | 900 / 250 ms | 1 400 / 400 ms | nightly |
| P7 | Home scroll: p99 frame, frames > 50 ms | ≤ 16.6 ms, 0 | ≤ 20 ms, ≤ 3 | nightly |
| P8 | PSS: Home / player | ≤ 180 / 260 MB | ≤ 140 / 220 MB | nightly |
| P9 | arm64 APK size | ≤ 12 MB | — | **gate, every PR** |
| P10 | dex methods after R8 | ≤ 120 k | — | **gate** |
| P11 | Native `.so` shipped in v1 | 0 | 0 | **gate** |
| P12 | Crash-free sessions (from `continuous`) | ≥ 99.7 % | — | release review |
| P13 | Backup of 500-row library, peak RSS delta | < 40 MB | < 24 MB | test |
| P14 | Search fan-out: all sources answered | ≤ 3 s | ≤ 6 s | nightly |

Nightly jobs fail the day (and open an auto-issue) when a number moves > 10 % versus the tracked
baseline in `docs/perf/baseline.json`. We never "fix" a red nightly by editing the baseline file
without a sentence in the PR saying why the new number is acceptable.

## 2. The structural choices that buy these numbers

1. **No vendored runtime, no native blobs.** Everything from Maven coordinates, R8 full mode +
   resource shrinking, `enableJetifier=false`, `nonTransitiveRClass`, `abi { arm64-v8a + universal }`
   (armv7 only if installs ask for it). This alone is the difference between a 12 MB and a 40 MB APK
   in this app family, and a smaller dex means fewer class-load page faults at startup.
2. **Baseline profile from M0.** `:perf:baselineprofile` runs on `high`+`mid` device types and ships in
   the release APK; ProfileInstaller for older devices. Startup and first-scroll gains here are larger
   than any hand micro-optimization.
3. **Deferred, lazy source hydration.** Boot does not enumerate sources, scan install dirs, create
   `DexClassLoader`s, or read prefs synchronously. Registry hydration is per-screen, per-kind, and
   memoized in the DI graph.
4. **Cached-first paint.** Every list renders a Room snapshot before awaiting the network, so the
   user's perceived "app speed" is a disk read (P2), not a foreign server's p99. The network appends.
5. **One scheduler, hard budgets.** Per-source measured budgets, waves, dedupe of in-flight calls,
   per-host politeness, supersession of stale results before they hit Compose. A single slow source can
   never make the whole screen slow, and no retry storm can follow it.
6. **Zero per-frame allocation in row rendering.** `@Immutable` snapshots, `remember`-stable key
   lambdas, fixed-measure card sizes, no blur/haze in scrollable content, no `derivedStateOf` chains
   deeper than 2, no Compose `Modifier.composed`.
7. **Image pipeline discipline.** Coil 3 with `size()` matching the layout, hardware bitmaps on
   API 26+, memory cache scaled by `isLowRamDevice`, and `onTrimMemory(TRIM_MEMORY_RUNNING_CRITICAL)`
   clearing both image caches (a documented one-liner, not an ad-hoc save).
8. **Streaming JSON.** kotlinx.serialization over Okio `BufferedSource`; no `response.string()` for
   anything above a few KB, so large catalogs and backups can't OOM the low class.
9. **Player buffer profiles by device class** (`LoadControl` `bufferFor*`), `MediaItem` built from the
   already-resolved `Stream` (never re-resolved), and `preloadingConfigurationItems` for the next
   episode so "Next" is instant.
10. **Effect chain rebuilt once per gesture, not per tick** (`:player` §7 of ARCHITECTURE) + a stall
    watchdog that detects "READY + playing but position didn't advance in 3 s" and re-creates the chain
    once instead of wedging the decoder.
11. **No work while the user is scrolling or the player is running**: `userScrolling` and
    `playerActive` gates on the idle sweep; `PerfMode` reduces prefetch depth on `low`.

## 3. Codec / capability notes (why a stream "is slow" and isn't)

- AV1/HEVC decode is absent or weak on many cheap devices → we keep a per-device `MediaCodecList`
  probe result in prefs and use it to *rank* servers (prefer H.264 on devices that drop frames on
  1080p AV1), not just to filter.
- 4K + 10-bit on `low`/`mid` → capped by `preferredMaxWidth` and a downscale hint, so we do not burn
  the decoder on a track that will stutter.
- Single-use signed URLs: a resolve must never be re-run implicitly by the player retry path. The
  `resolve` token is consumed once and cached with its `expiration`.
- `Range`-friendly progressive MP4 with moov at the front gets a lower time-to-first-frame than HLS
  with a 10 s segment target — so `Stream.kind` is trusted over URL suffix guessing.

## 4. Measuring it (repeatable, so the numbers mean something)

- `reportFullyDrawn()` after P1/P2 paint; `Trace.beginSection` markers named `boot:*`, `home:paint`,
  `detail:paint`, `player:firstFrame`, `sched:wave:<n>` — the nightly job reads these, not wall-clock
  logs.
- `FrameMetrics` aggregation in a `TestRule` that scrolls Home for 2 000 items and asserts P7.
- Macrobenchmark startup: 5 warm + 5 cold iterations per run, p50 and p95 recorded, written to
  `docs/perf/baseline.json` by the nightly job (its own commit, so the history is auditable).
- Memory: `Debug.getMemoryInfo` + `Runtime` heap delta after a forced trim; the nightly dumps a
  `MemoryReport` artifact onto the run page for humans.
- Every release note section for a perf change must include the before/after number, or say
  "no user-visible timing change" — no unmeasured perf claims in the changelog.
