# Build pipeline: how we write code we cannot compile locally

## The constraint

The Arena sandbox for this session has **no JDK, no Gradle, no Android SDK, 2 cores, 3 GB RAM**
(verified: `java`/`gradle`/`sdkmanager` absent, `ANDROID_HOME` empty). So:

> **GitHub Actions is the compiler.** The loop is: write a slice → push → read the log → fix.
> A green `build` job is the only proof a change compiles. Everything below exists to make that loop
> short and to make first-pass compile errors rare.

## Loop design

```
push (branch) →  gate  (~60 s, python3 only, no JVM)
               →  build (~4 min warm / ~10 min cold, :app:compileDebugKotlin + :feature:*)
push to main →  build + assembleRelease + R8 + size/method gate + signed APK → `continuous` prerelease
nightly      →  :perf:benchmark on emulator + baseline update PR + lint full pass
```

- `gate` (no JVM, so it never waits for a toolchain): structure check (module boundaries, forbidden
  deps/APIs from `docs/CLEAN_ROOM.md`, permission allowlist, no `src/`-style workspace notes),
  `CHANGELOG.md` + `docs/` touched-or-explained check, secret scan, JSON/XML/YAML parse of every
  resource + Gradle `settings` sanity, version-pin file lint, and a Kotlin **brace/paren balance +
  `TODO(` scan** as a cheap syntax proxy.
- `build` on PRs compiles only what changed's module plus dependents (`gradle -PchangedOnly` via
  `--build-cache` and configured cache save/load in `actions/cache`), so PR feedback stays fast.
- Nothing in CI publishes a versioned release. That path requires the owner's explicit written ask
  plus a typed `CONFIRM-RELEASE` input — borrowed as a *practice*, because it demonstrably stops an
  automation from shipping to every install by accident.

## Compile-safety rules for me (the agent) — the ones that actually prevent red builds

1. **One slice = one module's public API + its consumers.** Never change a `:core:*` signature and its
   call sites in different commits. If a contract change is needed, the same commit updates every
   caller found by grep, and the PR body lists them.
2. **Only APIs verified in this repo, in the pinned dependency, or in the Android SDK for our
   compileSdk.** New-to-me API → write it in a `// VERIFY:` comment and put it in the smallest possible
   call site, or don't use it. No "modern Compose API that may not be in this BOM" gambles: e.g.
   prefer `Modifier.focusRestorer`-style names only after they're proven by a green build.
3. **Prefer stdlib + kotlinx over cleverness.** No `replace(CharSequence, CharSequence)`-class overload
   guesses, no smart-cast-dependent code, no SAM-conversion on Kotlin interfaces, no `@Composable`
   lambdas passed where an inline non-composable function type is expected.
4. **Every new file: correct package matching the path, explicit imports** (no wildcard reliance on
   R class, remember `nonTransitiveRClass=true` → R must be imported from the owning module or use
   `androidx.core.R` etc.).
5. **Resources**: strings go in `values/strings.xml` (never hardcoded in a composable), drawables are
   vector XML we author, ids referenced from Compose-by-name are avoided entirely.
6. **Room**: schemas checked in (`exportSchema = true` + `room.schemaLocation`), so a missing
   migration never silently passes CI; migrations get a test.
7. **Gradle**: new module → registered in `settings.gradle.kts` + alias in `libs.versions.toml` in the
   same commit; never invent a plugin alias.
8. **Keep a `:core:contract` freeze file** (`docs/CONTRACT_API.md`, generated-ish list of public API):
   if a slice needs a contract change, it updates that doc too — the doc is the review surface.

## Working agreements

- Commit per slice with a message like `M2 DATARULE: selector engine + fixtures (+CI note)`; body
  contains what to watch in the log.
- On a red build I read `gh run view --log-failed` and fix **all** reported errors in one push
  (batched fixes, not one-error-one-push).
- Docs and CHANGELOG edits are pushed together with the code they describe.
- Never re-run a timed-out build command blindly; check `gh run list` state first.
- Long-running things (the CI poll) wait via `gh run watch`-style polling in background, not by
  blocking a single bash call.

## What we never commit

Keystores, tokens, `.env`, signing passwords, agent-workspace notes, user data, screenshots of user
accounts, vendored binaries, large datasets (fixtures stay < 200 KB each), or anything that reveals
credentials in a public repo. If a secret must exist, it is a repo **secret** set by the owner, and CI
reads it via `secrets.*`; the value never appears in chat or in a file.
