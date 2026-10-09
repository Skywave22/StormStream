# Clean-room policy and scope limits

Two independent rules live in this file. Both are non-negotiable for this project.

## A. No copying — how "inspired by" stays clean

StormStream is written from specifications and general engineering practice. It is not derived from
any other streaming app's source.

Allowed:
- Public protocol formats and file formats defined by *their* upstreams (Stremio's addon manifest,
  M3U playlists, Jellyfin's public REST API, Mihon/Aniyomi extension interfaces, the CloudStream
  plugin **API concept**) — these are documented interfaces, and interoperability with a public
  interface is legitimate. Implementations of them are written here from scratch.
- Ideas at the architecture level (a narrow provider contract, per-source time budgets, cached-first
  paint, docs-as-rules, a signed extension repo, CI-gated release publishing).
- Apache-2.0 / MIT / BSD third-party code, pulled as **Maven/npm coordinates with attribution in
  `NOTICE`**, never pasted into our tree as source files.

Not allowed (checked in CI by `:tools:structure-check`):
- Copying source files, comments, string literals, JSON/YAML asset files, drawables, icons,
  screenshots, or documentation prose from any other repo — including the project analyzed before this
  plan was written. "Read it to understand the problem, then write ours from the spec" is the whole
  procedure.
- Vendoring prebuilt binaries (`cloudstream3.jar`-style runtime blobs, TDLib/`libtdjni.so`,
  QuickJS `.aar`s, `.so` packs). If a capability needs a native blob, that capability is deferred; we
  do not ship a binary we cannot audit.
- Transliterated or "renamed" versions of another project's file structure, identifier strings, or
  error messages. Our file names, error text, repo-format field names, and SDK symbols are ours.
- Copying another project's README marketing, screenshot set, or community links.
- Reusing another app's *extension ecosystem* artifacts: we do not load, redistribute, index, or
  advertise other apps' plugin archives. Our extensions are `.stormx`, built against `:sdk:storm-ext`.

Every PR adds a one-line provenance note where anything could look borrowed ("implemented from the
Jellyfin public API docs v1.14; no external code referenced"). Reviewers reject ambiguous cases.

## B. Legal and safety scope — what we refuse to build

StormStream is a media hub for content the user has a right to access. We therefore do **not**
implement, and issues/PRs requesting these are closed with a link to this file:

1. **Anti-bot evasion** — Cloudflare/`cf_clearance` challenge solving, `wsidchk`/`js-cookie`
   solvers, interstitial "verify you are human" automation, browser-fingerprint spoofing to reach a
   protected origin.
2. **Hotlink-circumvention header rewriting** for third-party CDNs (injecting another site's
   `Referer`/`Origin`, or a desktop browser UA, to make a CDN that refused us serve content).
   A user's own server may be given any header the *user* configures — that's their infra.
3. **Torrent/magnet streaming** — embedded BitTorrent engines, TorrServer-style local HTTP-over-BT,
   tracker lists, `.torrent`/`magnet:` handling as a playback path, and debrid/torrent-bridge
   integrations. (`.torrent` files are not a `Stream.kind`; the parser for a torrent *metadata file*
   for a download queue would also be refused, to keep the line bright.)
4. **Bundled catalogs of infringing sources** — no first-party "repo of scrapers" for pirate sites,
   no README/onboarding instructions pointing users at such a repo, no curated lists of them, no
   in-app promotion of them.
5. **DRM circumvention or key extraction** of any kind. License-based playback (Widevine with a
   license URL the user is entitled to) is in scope; `L3` software-decode is not a bypass and stays
   because it is the platform's own path, but nothing that extracts, exports, or harvests keys.
6. **Ad-blocking of third-party services / request-injection into sites the user doesn't own** — no
   userscript engine that rewrites third-party pages, no "promo remover" patches to other apps'
   sites. (A local, user-configured DNS/blocklist for their own device is a separate product; not
   ours.)
7. **Scraping with the purpose of reselling access** — the `DATARULE` engine is a general HTTP+
   selector tool. We ship it with open/freely-licensed example sources and our own media-server
   presets; we do not ship rules for specific pirate sites, and we won't add "make rule for site X"
   as a task.
8. **Anything requiring us to store or proxy other people's content** — no relay, no mirror, no
   "shared source" backend run by us. The app is a client; the only server we operate is GitHub.

If a request is legal-but-grey (e.g. "add a scrapers tab that lists community repos"), the answer is
"not bundled in the app, and not advertised in the README"; the extension SDK stays generic so a
third party can build against it and carry their own responsibility — with signing and capability
prompts that make what they installed visible to the user.

## C. Security posture (built in, not bolted on)

- Extensions: signed (ed25519), checksum-verified, capability-scoped (`hosts` allowlist, `net`,
  `storage`), revocable per source, no `webview`/`files-all` grants in v1, install UI shows the key
  fingerprint.
- No telemetry, no analytics SDK, no crash reporting to third parties. Logs stay on-device, shareable
  by the user, with a **Clear all logs** control. Header values, license URLs and media-server tokens
  are redacted before logging or sharing.
- Media-server credentials in `EncryptedSharedPreferences`/Android keystore, never in the backup file
  unless the user opts in with a passphrase (backup encrypted with AEAD, passphrase-derived key).
- Network: TLS by default, certificate transparency not disabled, DoH optional and off by default,
  cleartext only allowed per-source-by-explicit-user-choice (needed for LAN media servers, which use
  `http`), with `android:usesCleartextTraffic=false` at the manifest level and a
  `network_security_config` permitting LAN ranges only.
- No `WebView` with `addJavascriptInterface` anywhere in v1.
- Permissions minimal: `INTERNET`, `READ_MEDIA_*`/SAF (user-picked), `POST_NOTIFICATIONS` (downloads),
  `FOREGROUND_SERVICE` (downloads) — asserted by `gate` against a checked-in allowlist so a new
  permission requires a plan-level PR.
