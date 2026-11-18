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

Bilby is a bilibili client for Android and Windows (`x64` and `ARM64`).

Built with Kotlin Multiplatform and Compose Multiplatform, both platforms share the interface and
business logic, following Material 3 Expressive and a five-tier window-size adaptive layout: compact
windows use a bottom navigation bar and modal bottom sheets, while medium and wider windows switch to
a slim navigation rail, rounded desktop content islands, and resizable or two-pane side panels.

- **Android**: Jetpack Compose interface with a Media3 background playback service, system media
  controls, edge-to-edge display, and predictive back.
- **Windows**: Compose Multiplatform on Azul Zulu 25, with a D3D11→D3D12 shared-texture video ring
  for zero-copy compositing and GPU reset recovery, native Win32 caption and Snap Layouts
  integration, SMTC media controls, `F11` / `Alt+Enter` fullscreen and picture-in-picture, and
  pure-Java zstd delta and full in-app updates.

The interface keeps distractions down: what you see comes from the creators you follow and from your
own searches, with no recommendation feed, no persistent related videos, and no unread badges.

## Feed and library

The home feed shows video uploads, posts, and articles from creators you follow in chronological
order, with currently live creators at the top and no recommendation stream. The library organises
history, watch later, favourites, and offline cache into horizontal carousels, while Night Mode
restricts the view during scheduled hours to whitelisted creators and offline content.

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

Connect any OpenAI-compatible endpoint. The assistant runs multi-step searches across the site and
reads video descriptions and top comments to select cited candidates, carrying only the current
request without injecting watch history. On wide windows, the assistant opens as a persistent side
panel alongside standard search results.

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

Playback brings collections, multi-part videos, watch later, and manually assembled drafts into a
single finite queue, with danmaku and SponsorBlock segment skipping. There is no persistent
related-videos rail: "Find related" runs a one-shot search from an explicit tap, docking as a
right-hand pane on wide windows and opening as a modal bottom sheet on compact windows.

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

Listening mode switches in place inside the player without reloading the stream or interrupting the
queue, integrating with Android system media controls (screen-off playback, lock screen, and headset
buttons) and Windows SMTC. Subtitles follow line by line with tap-to-seek, alongside a sleep timer.

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

Download a build from [Releases](https://github.com/NihilDigit/bilby/releases/latest). Every
binary is built by GitHub Actions from the source in this repository.

- **Android**: requires Android 10 or later. Pick the APK for your device's architecture, or
  `universal` if you are not sure.
- **Windows**: requires 64-bit Windows 10 or later, with separate builds for `x64` and `ARM64`
  (pick `arm64` on Snapdragon and other ARM devices).
  - `.msi`: installs into your user profile, needs no administrator rights, and updates in
    the app afterwards.
  - `.zip`: portable; unzip it and run `Bilby.exe`.
- **Agent setup (optional)**: agent search needs an OpenAI-compatible endpoint. Enter the API
  address and key under Settings → Assistant; everything else works without it.

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
