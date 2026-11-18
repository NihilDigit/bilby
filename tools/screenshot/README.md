# Hidden-Desktop Screenshot Harness

Developer-only Windows screenshot automation for capturing 2x HiDPI Light and Dark UI showcases without disturbing the active desktop or mutating live user state.

## Architecture

- **`tools/screenshot/HiddenDesktopLauncher.java`** — Pure `java.base` FFM launcher (`CreateDesktopW` + `CreateProcessW` on `winsta0\<DesktopName>`) that never calls `SwitchDesktop`, so window creation, resizing, and focus never steal your active desktop.
- **Isolated `:desktop` `screenshot` sourceSet** (`desktop/src/screenshot/kotlin/dev/bilby/desktop/screenshot/`) — Associated with `:desktop`'s `main` compilation at dev time only; excluded from `:desktop:jar`, `packageReleaseMsi`, `packageReleaseUpdate`, and Android release builds.
- **Compose + Skia Driver** (`ScreenshotMain.kt`, `ScreenshotCapture.kt`) — Drives navigation and UI via `AppContainer`, `incomingLink`, AWT `KeyEvent`s (`Ctrl+1/2/3`, `Alt+Left`), and `ComposeWindow.semanticsOwners`, captures `SkiaLayer.screenshot()` at `-Dsun.java2d.uiScale=2.0` + `-Dskiko.renderApi=SOFTWARE_FAST` (so `BilbyMpvVideoSurface` uses D3D11 CPU readback for video frames), composites anti-aliased rounded corners, and encodes 2-stream `yuv444p` + alpha AVIF via `ffmpeg` (`libaom-av1`).

## Privacy & Profile Sandbox Guarantees

- **Sandboxed DataStore clone:** `HiddenDesktopLauncher` clones **only** `datastore/*.preferences_pb` from `%APPDATA%\Bilby` (or `--from-profile=<dir>`) into a temporary `%TEMP%\bilby-shot-sandbox-*` directory, sets `APPDATA` for the child JVM, and deletes the sandbox unconditionally in `finally` (plus a JVM shutdown hook).
- **Zero live DB touch:** Live `bilby.db` is never copied or opened.
- **No watch-history mutations or speaker output:** `AppContainer(..., reportHeartbeats = false)` disables `HeartbeatReporter` and `DesktopPlatform(..., silentAudioOutput = true)` sets mpv `ao=null` and `mute=yes`.
- **Account redaction (`--redact-account`):** Replaces the logged-in `AccountInfo` (`mid`, `name`, `faceUrl`, `sign`, `coins`, `following`) with neutral synthetic values and a locally generated avatar PNG inside the temp sandbox.
- **Credential safety:** `SESSDATA`, `bili_jct`, `access_key`, and `LLM_API_KEY` are never printed or logged.

## Usage

```pwsh
# Capture all 12 Light + Dark AVIF pairs into docs/screenshots/ with redacted account identity
./gradlew :desktop:captureScreenshots --args="--scenes=all --redact-account"

# Fast single-scene PNG smoke test into a custom directory
./gradlew :desktop:captureScreenshots --args="--scenes=feed-wide --format=png --redact-account --out-dir=build/tmp-shots"
```

### Options

| Flag | Default | Description |
|---|---|---|
| `--scenes=<list>` | `all` | Comma-separated scene IDs or groups (`all`, `feed`, `library`, `agent`, `video`, `listen`). `all` captures the 12 showcase IDs: `feed-wide`, `library-wide`, `feed-compact`, `agent-wide`, `agent-running-compact`, `agent-answer-compact`, `video-wide`, `in-video-wide`, `in-video-compact`, `listen-wide`, `lyrics-compact`, `sleep-timer-compact` (plus optional `profile-compact`). |
| `--format=avif\|png\|both` | `avif` | Output format (`avif` requires `ffmpeg` with `libaom-av1` on `PATH`). |
| `--theme=light\|dark\|both` | `both` | Theme filter per scene. |
| `--out-dir=<path>` | `docs/screenshots` | Output directory (relative to repo root or absolute). |
| `--redact-account` | off | Masks logged-in UID, username, avatar, bio, coins, and following count. |
| `--agent-query=<text>` | `CS2 新加坡 Major 一阶段预测` | Query used for Search and Agent scenes. |
| `--listen-query=<text>` | `小Lin说 一口气了解外汇` | Query used to locate a subtitled video for Listen scenes. |
| `--video-bvid=<BV...>` | auto | Explicit BV ID for `video-wide` and `in-video-*`. |
| `--listen-bvid=<BV...>` | auto | Explicit BV ID for `listen-wide`, `lyrics-compact`, and `sleep-timer-compact`. |
| `--from-profile=<dir>` | `%APPDATA%\Bilby` | Source Bilby profile directory whose `datastore/*.preferences_pb` are cloned. |
| `--fresh-profile` | off | Starts with an empty sandbox (requires an authenticated profile for logged-in scenes). |
| `--copy-coil-cache` | off | Optionally copies `coil_cache` into the temporary sandbox for faster repeat runs. |
| `--desktop-name=<name>` | `BilbyShot` | Win32 virtual desktop name (`winsta0\<name>`). |
| `--timeout-sec=<sec>` | `420` | Child JVM timeout in seconds before termination and sandbox cleanup. |
