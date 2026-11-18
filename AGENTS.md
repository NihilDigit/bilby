# AGENTS.md

Bilby is an open-source, single-account bilibili client for Android and Windows desktop (`x64` and `ARM64`).

## Product Boundaries

Authoritative rules live in `README.md` (contributing section):

- **Circumvention and entitlement.** Never bypass membership gates or paywalled quality tiers, touch billing/entitlement, or fake viewing/interaction reporting.
- **UGC only.** Only resolve user submissions; anime, film, and course links are not resolved.
- **Neutral, non-interruptive attention.** What a list contains follows strictly from user actions:
  - No recommendation feeds, no related-videos rail (`找相关` is an explicit one-shot tap with no persistence or "find more" loop), and no local personalization.
  - Finiteness is a consequence, not an invented cap (do not cap lists or block pagination).
  - Zero red-dot, unread-count, or reminder `Badge` components anywhere in the app.
  - Never separate metadata with a middle dot (`·`, `•`, or lookalikes); use `MetaSeparator` (two spaces, `ui/components/VideoRow.kt`), including prefixed queue source titles (`合集  …`).
  - Interface copy is written, never colloquial (`取消关注`, not `刷视频`/`删掉`/`再下一次`). Confirmations state the action and stop; assistant process rows name the object only (`agent/Tool.kt`).
  - Assistant context (`agent/AgentLoop.kt`) carries only the current request — never inject watch history; step ceiling, provenance check, and result count live in code.

## Collaboration & Device Control

- **Propose first** for product copy, redesigned business logic, or architecture changes (Wait for approval before writing code). **Bug fixes and mechanical work** go straight in.
- **Device control (`adb`)** is used only when the owner asks in the current session. When asked for a screenshot, take the screenshot only — never rebuild, reinstall, or relaunch the foreground activity.

## API, Logging & Verification Precedence

- **PiliPlus (`PiliPlus/`, gitignored) is the authority** on live API behaviour. Risk control is **per-action**: never generalize cookie/signing/`access_key` acceptance across endpoints. Record newly verified API facts in `notes/` with a one-line pointer at the call site.
- **`api/BiliClient.kt` is the sole network exit.** Add a new route there if an endpoint requires a different credential/signing/UA shape.
- **Logging:** Every `runCatching`-swallowed failure logs path, code, and message via `BiliLog`. Never log `SESSDATA`, `bili_jct`, `access_key`, or LLM keys (cookie keys by name only).
- **Optimistic mutations exclude refetching:** Likes, coins, favourites, follow, and block update local counts/state optimistically (rolling back on failure) without refetching.
- **Source-of-truth precedence:** Resolved artifact bytecode (`javap` on Gradle-cached AAR/JAR) > local documentation mirrors (`m3-material-mirror/`, `android-docs-mirror/`, `kotlin-docs-mirror/`) / `rtx` > memory. Verify library claims against bytecode.

## Architecture Invariants

- **Single player:**
  - Android: `player/AudioPlaybackService` (`MediaSessionService`) owns the sole player. UI talks to it only via `MediaController` (`AndroidPlaybackHost`) with full default `Player.Commands` (withholding `COMMAND_SET_VIDEO_SURFACE` silently produces a black picture). Leaving a screen disconnects the controller without releasing the player.
  - Desktop: `DesktopPlaybackHost` owns the sole mpv instance and `player/DesktopQueue.kt`. Desktop publishes `loadKey` before opening a stream and mounts the surface only when `loadKey` matches.
- **Listening mode (`ListenScreen`)** is an in-page state inside the video page (structurally identical to fullscreen), keeping the player and progress alive — never a separate navigation destination.
- **Queue & streams:**
  - `player/LazyMediaSource` resolves playurl at playback time (CDN URLs expire); resolution errors must surface through `maybeThrowSourceInfoRefreshError`.
  - `QueueOrder` (reverse/shuffle) changes playback order only while preserving natural display order; `QueuePagination` tracks `X / N` and boundary `errorBefore`/`errorAfter` latches.
  - Peer lists use their own `QueueContext` variants (including `QueueContext.NightMode` over `NightMergeFeed`); `QueueContext.Custom` is strictly for user-assembled draft/manual queues.
- **Navigation 3 (`BilbyApp`):** Backstack is a `SnapshotStateList<NavKey>` and does not deduplicate. Always push via `pushUnique` (`ui/NavBackStackPolicy.kt`), never `backStack.add` directly.
- **Adaptive & Desktop UI (`docs/ui-style-guide.md`):**
  - Layout follows `rememberBilbyWindowSize()` (`LocalWindowInfo.containerSize`), never device type.
  - Every sheet goes through `components/PaneSheet` (in-pane right column in two-pane media, `ModalSideSheet` when `useTwoPaneMediaLayout` is true — `Expanded+` or `Medium` with compact height `< 480dp` — otherwise `ModalBottomSheet` with `skipPartiallyExpanded = true` by default).
  - Paged lists use `components/PagedColumn` and `components/PrefetchNearEnd` (halting on error until explicit retry).
  - Persistent resizable side panels use `components/SidePanelLayout` (`SidePanelId`).
  - Video grids use `AdaptiveVideoGridContent` / `rememberVideoGridColumnCount`; non-video lists use `AdaptiveListContent`; long-form reading/chat uses `AdaptiveContent`.
  - Master-detail uses `ListDetailSceneStrategy` (`inListPane` on key). On desktop (`LocalWindowCaption.current != null`), `DesktopIslandFrame` / `DesktopIslandSurface` wraps content in `shapes.large` islands with `8.dp` `surfaceContainer` framing and gap.

## Modules, Toolchain & Desktop Packaging

- **Modules:** `:shared` holds KMP code and UI (`commonMain`, `androidMain`, `desktopMain`); `:app` is Android packaging (`BilbyApplication` populates `AppBuild`, `compileSdk = release(37) { minorApiLevel = 1 }`); `:desktop` is Windows desktop packaging; `:sqlite-woa64` builds `sqlite-bundled`'s missing `windows_arm64` native library on ARM64 hosts.
- **Unified Compose baseline:** Android (`compose-bom-beta 2026.11.00`) and Desktop (`CMP 1.13.0-rc01` / Skiko `0.156.1`) both target **Material3 `1.5.0-rc01`**, `navigation3 1.2.0`, and `adaptive 1.3.0`. Compiler stability rules live in `compose_stability.conf`.
- **Gradle & Kotlin:** AGP 9 has built-in Kotlin (`org.jetbrains.kotlin.android` is an error). Root `build.gradle.kts` `buildscript` classpath overrides `kotlin` and `ksp`, so `gradle/libs.versions.toml` bumps must be mirrored there.
- **Platform conventions:**
  - Read strings via `dev.bilby.stringResource` / `getString` (Compose's default doesn't format `%1$.1f` / `%%`).
  - Image loading uses `buildBilbyImageLoader` (`ImageLoaderConfig.kt`: bounded `MemoryCache` + 256 MB `DiskCache` + `OkHttpNetworkFetcherFactory`). Normalize `http://` and `//` cover URLs to `https://` during mapping (`usesCleartextTraffic` stays off).
  - `kotlinx.serialization`: LLM tool schemas require `encodeDefaults = true`; LLM request payloads keep `explicitNulls = false` so Gemini `extra_content.google.thought_signature` round-trips cleanly (`agent/LlmProtocol.kt`, `agent/LlmClient.kt`).
- **Desktop video surface & GPU watch:**
  - `BilbyMpvVideoSurface` (`shared/desktopMain`) decouples Bilby from `mediamp`'s Skiko version by reading Skiko `0.156.1` `Direct3DRenderer` via `SkikoAccess` + FFM `DirectXDeviceShim` and driving `mediamp` `0.5.3` `MPVHandle` D3D11 ring (`D3D11RingConsumer`) / readback (`D3D11ReadbackConsumer`) JNI directly.
  - `GpuDeviceWatch` (`desktop`) polls `ID3D12Device::GetDeviceRemovedReason` (`DEVICE_SLOT = 2`, vtable slot 37) to rebuild the window cleanly on GPU TDR/reset.
  - `compose-windows-touch` (`WM_POINTER`) and Skiko reflection members require matching keep rules in `desktop/proguard-rules.pro` (`app/proguard-rules.pro` for Android). Verify keep rules on CMP/Skiko upgrades.
- **Desktop JVM, ProGuard & AOT:**
  - Desktop builds, runs, and packages on **Azul Zulu 25** (bytecode target 17; FFM for `WindowsCaption`, `BilbyMpvVideoSurface`, `ZstdPatch`, and `PlayerSystemStatus`).
  - ProGuard shrinks dependencies only (`-dontobfuscate`, `-dontpreverify` because ProGuard emits invalid stack maps for `PlayerShell`), then copies Bilby's own jars back. Release builds generate a JDK 25 AOT cache (`app.aot`, even-second jar timestamps, AVX-512 adapter/stub caching disabled) and verify all classes with `desktop/package/VerifyClasses.java`.
- **Windows ARM64 & In-App Updater:**
  - Windows ARM64 is built natively on `windows-11-arm` (`:sqlite-woa64`, FFM `ZstdPatch` on both x64/arm64; `testAndroidHostTest` forces `robolectric.sqliteMode=LEGACY` and `robolectric.conscryptMode=OFF` on `isWindowsArm64Host`).
  - `desktop/build.gradle.kts` `upgradeUuid` (`8481dd59-0107-4e44-bfc0-bc0dba075087`) is permanent; versions come from `-Dbilby.version` (`-PbilbyVersion`).
  - In-app updates (`DesktopAppUpdater.kt`) stage and launch the standalone pure-`java.base` **`WindowsUpdateApplier`** (`shared/updaterHelper`, packaged as `bilby-update-applier.bin` via `javaw.exe -jar`, using `%APPDATA%\Bilby\update-helper`, conditional `runtime/` copy only for `msi`/`recover`/runtime-touching updates, `Redirect.DISCARD`, and journaled rollback) — never PowerShell/cmd scripts. Release assets require MSI + `-app.zip` + `-files.json` (plus optional zstd `-from-<ver>.zip` deltas).
- **Releases & LLM config:** Releases cut from `v*` tags via `.github/workflows/release.yml`; write Chinese release notes (`## 修复` / `## 变化`) covering only user-visible fixes/changes shipped since the prior tag, and preserve the checksum/attestation footer when editing draft notes. Debug/`:desktop:run` can read `LLM_BASE_URL`, `LLM_API_KEY`, `LLM_MODEL` from `local.properties`.

## Build, Test & Gradle Lock

Gradle compilation is an **exclusive resource** — never run parallel Gradle builds against the same daemon/cache.

```pwsh
./gradlew installDebug                                                # dev.bilby.debug
./gradlew assembleRelease                                             # dev.bilby (R8)
./gradlew :shared:compileKotlinDesktop :shared:compileAndroidMain     # quick compile, both targets
./gradlew :shared:testAndroidHostTest :shared:desktopTest             # unit tests, both targets
./gradlew :shared:desktopTest --tests "dev.bilby.data.QueueFeedTest"  # single test class
./gradlew :desktop:run                                                # run desktop app
./gradlew :desktop:captureScreenshots --args="--scenes=all --redact-account"  # hidden-desktop screenshots (see tools/screenshot/README.md)
./gradlew :desktop:packageReleaseMsi :desktop:packageReleaseUpdate    # MSI + update assets
java -Xverify:all desktop/package/VerifyClasses.java desktop/build/compose/binaries/main-release/app/Bilby/app
```

Write unit tests only where logic can catch real bugs (load resolution, WBI signing, stream selection, agent protocol, update applier), not UI/network glue.
