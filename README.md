# StormStream

**StormStream** is a fast, open, source-agnostic media hub for Android: one player, one library, one
search — and any source you (or a third party) build for it.

Add your own media server, your own playlists, your own files, or a declarative HTTP/JSON source
description, and StormStream gives it the same Home rows, the same search, the same detail page, the
same player, the same downloads and the same watch progress. Sources that fail say **why**, in plain
words.

- ⚡ **Speed is the feature.** Cold start ≤ 450 ms p50, Home painted from cache ≤ 150 ms, first
  playable server ≤ 1.2 s p50, arm64 APK ≤ 12 MB. These are CI gates, not marketing.
- 🧩 **StormStream Extensions** — `.stormx` sources built against a tiny published SDK, **signed**
  (ed25519) and **capability-scoped** (`hosts` allowlist, net, storage) with a plain-language install
  prompt. Nothing is installed silently and nothing runs with ambient power.
- 📺 Phone, tablet, foldable and **TV** from one screen set — TV is a layout mode, not a fork.
- 🎞 Media3 player: HLS/DASH/progressive, per-source headers, embedded + sidecar subtitles, audio
  delay, speed to 4×, PiP, resume, session controls, video-enhance presets that never stall playback.
- 🎚 Local-first: no accounts, no telemetry, no analytics SDK, logs stay on the device, backups are
  streamed (and optionally encrypted with your passphrase).
- 🧱 No vendored binaries and no native blobs: everything from Maven coordinates, auditable and small.

**StormStream is a client.** It hosts content you have a right to access. It does not bundle catalogs,
solve anti-bot challenges, bypass hotlink protection, stream torrents, or touch DRM keys — the full
scope statement is [`docs/CLEAN_ROOM.md`](docs/CLEAN_ROOM.md).

## Start here

| | |
|---|---|
| What we're building and in what order | [`PLAN.md`](PLAN.md) |
| Contracts, models, scheduler, caching, player | [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) |
| The perf budgets and how CI enforces them | [`docs/PERFORMANCE.md`](docs/PERFORMANCE.md) |
| No-copy policy + refusals + security posture | [`docs/CLEAN_ROOM.md`](docs/CLEAN_ROOM.md) |
| How code gets compiled when the dev box has no Android SDK | [`docs/BUILD_PIPELINE.md`](docs/BUILD_PIPELINE.md) |

## Build

```bash
gradle :app:assembleDebug          # needs JDK 17 + Android SDK 35 locally
```

In this project, **CI is the authoritative build**: pushes run `gate` (structural checks, ~60 s) and
`build` (Kotlin compile), and a green push to `main` publishes a signed APK to the `continuous`
prerelease. Versioned releases are manual and double-gated — see `docs/BUILD_PIPELINE.md`.

## Status

Pre-M0: the plan is written, the scaffolding comes next. Milestones M0 → M6 in `PLAN.md` §5.

## License

To be set in M0 (recommended: Apache-2.0 for the app and the extension SDK) — see `PLAN.md` §9.
