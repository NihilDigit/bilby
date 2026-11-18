<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/icon.png" alt="Bilby" width="96"></p>

<h1 align="center">Bilby</h1>

<p align="center"><a href="README.md">简体中文</a> | <b>English</b></p>

<p align="center">
<a href="#install"><img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-4A5C92?style=flat-square&logo=android&logoColor=white"></a>
<a href="#install"><img alt="Windows 10+ x64 / ARM64" src="https://img.shields.io/badge/Windows-10%2B%20x64%20%2F%20ARM64-4A5C92?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0yIDJoOS41djkuNUgyek0xMi41IDJIMjJ2OS41aC05LjV6TTIgMTIuNWg5LjVWMjJIMnpNMTIuNSAxMi41SDIyVjIyaC05LjV6Ii8+PC9zdmc+"></a>
<a href="https://github.com/NihilDigit/bilby/attestations"><img alt="SLSA Build L3" src="https://raw.githubusercontent.com/NihilDigit/bilby/badges/slsa-l3.svg"></a>
<a href="LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/github/license/NihilDigit/bilby?style=flat-square&color=4A5C92&logo=gnu&logoColor=white"></a>
<br>
<img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/Kotlin%20Multiplatform-4A5C92?style=flat-square&logo=kotlin&logoColor=white">
<img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose%20Multiplatform-4A5C92?style=flat-square&logo=jetpackcompose&logoColor=white">
<img alt="Material 3 Expressive" src="https://img.shields.io/badge/Material%203%20Expressive-4A5C92?style=flat-square&logo=materialdesign&logoColor=white">
</p>

Bilby is a lightweight, high-performance bilibili client for Android and Windows (`x64` and `ARM64`).

Built around Material Design with a responsive layout, it is tailored for both mouse and keyboard on desktop and touch on mobile. There is no recommendation feed, no persistent related videos, and no unread badges—everything shown comes from creators you follow and your own searches.

## Feed and library

Shows videos, posts, and articles from creators you follow in chronological order, with no recommendation feed. The library brings together history, watch later, favourites, and offline cache, with an optional Night Mode whitelist.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/feed-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/feed-wide-light.avif">
    <img src="docs/screenshots/feed-wide-light.avif" width="760" alt="Wide home feed: following grid and slim navigation rail">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/library-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/library-wide-light.avif">
    <img src="docs/screenshots/library-wide-light.avif" width="536" alt="Wide library: history, watch later, and favourites carousels">
  </picture>
  &nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/feed-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/feed-compact-light.avif">
    <img src="docs/screenshots/feed-compact-light.avif" width="176" alt="Compact home feed: single-column list and bottom navigation bar">
  </picture>
</p>

## Agent search

With an OpenAI-compatible endpoint configured, the assistant searches across bilibili and reads descriptions and top comments to filter candidate videos, opening alongside standard search results on wide screens.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/agent-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/agent-wide-light.avif">
    <img src="docs/screenshots/agent-wide-light.avif" width="760" alt="Wide search: standard results alongside the persistent assistant side panel">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/agent-running-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/agent-running-compact-light.avif">
    <img src="docs/screenshots/agent-running-compact-light.avif" width="260" alt="Compact view: multi-step agent search in progress">
  </picture>
  &nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/agent-answer-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/agent-answer-compact-light.avif">
    <img src="docs/screenshots/agent-answer-compact-light.avif" width="260" alt="Compact view: candidate videos and summary returned by the agent">
  </picture>
</p>

## Playback and related search

Collections, multi-part videos, and watch later play in a unified queue with danmaku and segment skipping. Playback has no persistent related-videos rail; tapping "Find related" runs a one-shot search from the current video.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/video-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/video-wide-light.avif">
    <img src="docs/screenshots/video-wide-light.avif" width="760" alt="Wide playback: two-pane player and playback queue">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/in-video-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/in-video-wide-light.avif">
    <img src="docs/screenshots/in-video-wide-light.avif" width="536" alt="Wide Find related: search results docked in the right pane">
  </picture>
  &nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/in-video-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/in-video-compact-light.avif">
    <img src="docs/screenshots/in-video-compact-light.avif" width="176" alt="Compact Find related: search results in a modal bottom sheet">
  </picture>
</p>

## Listening

Switch seamlessly between watching and listening without reloading, with background playback and system media controls. Subtitles follow line by line with tap-to-seek, alongside a sleep timer.

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/listen-wide-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/listen-wide-light.avif">
    <img src="docs/screenshots/listen-wide-light.avif" width="760" alt="Wide listening mode: two-pane turntable controls and playback queue">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/lyrics-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/lyrics-compact-light.avif">
    <img src="docs/screenshots/lyrics-compact-light.avif" width="260" alt="Compact listening mode: line-by-line subtitles">
  </picture>
  &nbsp;&nbsp;
  <picture>
    <source media="(prefers-color-scheme: dark)" type="image/avif" srcset="docs/screenshots/sleep-timer-compact-dark.avif">
    <source media="(prefers-color-scheme: light)" type="image/avif" srcset="docs/screenshots/sleep-timer-compact-light.avif">
    <img src="docs/screenshots/sleep-timer-compact-light.avif" width="260" alt="Compact listening mode: sleep timer">
  </picture>
</p>

## Planned

- [ ] Windows: rendering performance for advanced danmaku
- [ ] Filter low-quality comments and posts
- [ ] Architecture cleanup, simpler data flow and comment cleanup
- [ ] On-device CI checks and a better agent harness

## Install

Download from [Releases](https://github.com/NihilDigit/bilby/releases/latest). Every package is built from source by GitHub Actions.

- **Android**: Android 10+, pick the APK for your architecture (`universal` if unsure).
- **Windows**: 64-bit Windows 10+, with separate `x64` and `ARM64` builds (pick `arm64` on Snapdragon and other ARM devices).
  - `.msi`: installs into your user profile without administrator rights and supports in-app updates.
  - `.zip`: portable build; extract and run `Bilby.exe`.
- **Agent setup (optional)**: enter an OpenAI-compatible API address and key under Settings → Assistant; everything else works without it.

## Contributing

Issues and pull requests are welcome. Small bug fixes, crash reports and documentation fixes
can go straight in.

For a new feature or an architectural change, please open an RFC issue first, describing the
use case, what the app does today and the design you have in mind, so that nobody writes code
in a direction the project cannot take.

These are out of scope:

- **Circumvention and entitlement**: no bypassing membership quality tiers or feature gates,
  nothing that touches billing or entitlement, and data is reported back as usual.
- **Non-UGC content**: only regular user submissions are resolved; anime, film and courses are
  not.
- **Anything that competes for attention**: no recommendation feed and no persistent related
  videos; the app implements neutral behaviour only.

If you use an LLM to help write code, make sure you understand the logic you add and verify it
on a real device.

## License and acknowledgements

- Released under [GPL-3.0-or-later](LICENSE).
- The low-level client code that talks to bilibili (WBI signing, AppSign, the device
  fingerprint, TV QR code login, playurl parameters, reporting and write actions) is ported
  from [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) (GPL-3.0). Our thanks to its
  developers.

## Links

- [LINUX DO](https://linux.do/)
