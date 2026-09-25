<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/icon.png" alt="Bilby" width="96"></p>

<h1 align="center">Bilby</h1>

<p align="center"><a href="README.md">简体中文</a> | <b>English</b></p>

<p align="center">
<a href="#install"><img alt="Android 10+" src="https://img.shields.io/badge/Android-10%2B-4A5C92?style=flat-square&logo=android&logoColor=white"></a>
<a href="#install"><img alt="Windows 10+ x64" src="https://img.shields.io/badge/Windows-10%2B%20x64-4A5C92?style=flat-square&logo=data:image/svg%2bxml;base64,PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAyNCAyNCI+PHBhdGggZmlsbD0id2hpdGUiIGQ9Ik0yIDJoOS41djkuNUgyek0xMi41IDJIMjJ2OS41aC05LjV6TTIgMTIuNWg5LjVWMjJIMnpNMTIuNSAxMi41SDIyVjIyaC05LjV6Ii8+PC9zdmc+"></a>
<a href="https://github.com/NihilDigit/bilby/attestations"><img alt="SLSA Build L3" src="https://raw.githubusercontent.com/NihilDigit/bilby/badges/slsa-l3.svg"></a>
<a href="LICENSE"><img alt="GPL-3.0" src="https://img.shields.io/github/license/NihilDigit/bilby?style=flat-square&color=4A5C92&logo=gnu&logoColor=white"></a>
<br>
<img alt="Kotlin Multiplatform" src="https://img.shields.io/badge/Kotlin%20Multiplatform-4A5C92?style=flat-square&logo=kotlin&logoColor=white">
<img alt="Compose Multiplatform" src="https://img.shields.io/badge/Compose%20Multiplatform-4A5C92?style=flat-square&logo=jetpackcompose&logoColor=white">
<img alt="Material 3 Expressive" src="https://img.shields.io/badge/Material%203%20Expressive-4A5C92?style=flat-square&logo=materialdesign&logoColor=white">
</p>

Bilby is a bilibili client for Android and Windows.

It is built with Kotlin Multiplatform: both platforms share the interface and the business
logic, and the interface follows Material 3 Expressive.
- **Android**: a native, high-performance app, with the interface on Jetpack Compose and
  playback on Media3.
- **Windows**: a GPU-accelerated interface on Compose Multiplatform, with video frames kept in
  GPU memory and composited without a copy.

The interface keeps distractions down; what you see comes from the people you follow and from
your own searches.

> Windows support is experimental.

## Feed and playback

The home feed shows posts from uploaders you follow, with no recommendation feed. The player
offers a "Find related" action that runs a one-off search for candidates based on the current
video.

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/feed.jpg" width="240" height="528" alt="Home: following feed">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/video.jpg" width="240" height="528" alt="Playback and queue">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/in-video.jpg" width="240" height="528" alt="On tap, the agent searches based on the current video">
</p>

## Listening

On Android, listening mode works with the system media controls: it keeps playing with the
screen off and responds to the lock screen and headset buttons. Switching between watching and
listening is instant, with nothing to reload; subtitles follow line by line, and tapping a line
seeks to it.

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/listen.jpg" width="240" height="528" alt="Listening interface">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/lyrics.jpg" width="240" height="528" alt="Line-by-line subtitles">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/sleep-timer.jpg" width="240" height="528" alt="Sleep timer">
</p>

## Agent search

Connect any OpenAI-compatible endpoint. The agent searches the whole site and uses video
descriptions and top comments to pick candidates.

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/agent-running.jpg" width="240" height="528" alt="The agent at work">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/agent-answer.jpg" width="240" height="528" alt="Candidate videos returned by the agent">
</p>

## Planned

- [ ] Polish interface details and improve the responsive layout
- [ ] Windows: platform behaviour
- [ ] Windows: rendering performance for advanced danmaku
- [ ] Filter low-quality comments and posts
- [ ] Architecture cleanup, simpler data flow and comment cleanup
- [ ] On-device CI checks and a better agent harness

## Install

Download a build from [Releases](https://github.com/NihilDigit/bilby/releases/latest). Every
binary is built by GitHub Actions from the source in this repository.

- **Android**: requires Android 10 or later. Pick the APK for your device's architecture, or
  `universal` if you are not sure.
- **Windows**: requires 64-bit Windows 10 or later.
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
