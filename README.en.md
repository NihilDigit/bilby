<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/icon.png" alt="Bilby" width="96"></p>

# Bilby

[![简体中文](https://img.shields.io/badge/README-%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-4A5C92?style=flat-square)](README.md) [![APK](https://img.shields.io/endpoint?style=flat-square&url=https%3A%2F%2Fraw.githubusercontent.com%2FNihilDigit%2Fbilby%2Fmain%2F.github%2Fbadges%2Fapk-size.json)](https://github.com/NihilDigit/bilby/releases/latest) [![SLSA Build Level 2](https://slsa.dev/images/gh-badge-level2.svg)](https://github.com/NihilDigit/bilby/attestations) [![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/NihilDigit/bilby)

Bilby is a native Android client for bilibili, offering a subscription-style design with no
recommendation feed, an improved listening experience, and agentic search and
suggestions.

> **This project is under active development.** The interface and the API layer are both
> still changing, and neither stability nor compatibility is guaranteed.

## A subscription-style experience, away from the recommendation feed

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/feed.png" width="240" height="528" alt="Home: following feed">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/video.png" width="240" height="528" alt="Playback and queue">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/in-video.png" width="240" height="528" alt="On tap, the agent searches based on the current video">
</p>
<p align="center">The home feed carries only uploaders you follow; the queue comes from the collection or the uploader; when needed, the agent can search for related content based on the video you are watching</p>

## Redesigned, native listening support

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/listen.png" width="240" height="528" alt="Listening interface">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/lyrics.png" width="240" height="528" alt="Line-by-line subtitles">
</p>
<p align="center">Shares the player with normal playback, supports screen-off background play, lock-screen
and headset controls, and a sleep timer; subtitles follow line by line, tap a line to seek</p>

## Pan for gold with agent search

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/agent-running.png" width="240" height="528" alt="Assistant search in progress">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/agent-answer.png" width="240" height="528" alt="Assistant candidates with reasons">
</p>
<p align="center">Searches, reads descriptions and top comments, and returns candidates each with its reason</p>

## Features

Done:

- [x] Feed: following feed (with per-uploader muting), most-visited uploaders, full following list, watch-later, history with cloud progress and resume
- [x] Uploader pages: uploads, moments, and collections/series tabs, with in-space search
- [x] Playback: fullscreen, quality, speed (WSOLA), double-tap to seek or pause, long-press fast-forward, drag-to-seek, swipe for brightness and volume, lock, multi-part videos; progress synced to the cloud
- [x] Listening: same player as normal playback, screen-off background play, notification, lock screen and headset controls, sleep timer; shuffle queue; the subtitle track doubles as line-by-line lyrics, tap a line to seek
- [x] Danmaku: scrolling, top and bottom, following the playback clock, adjustable opacity
- [x] Subtitles: multiple tracks, under the picture during normal playback, as a transcript while listening; AI subtitle repair
- [x] Comments: read, sort, expand reply threads, post and reply, like, delete, tap a timestamp to seek
- [x] Actions: like, coin, favourite, follow; joint submissions credit each uploader separately
- [x] Agent: conversational site-wide search and "find related" from the video page; reads descriptions and top comments, candidates each with a reason; multi-turn follow-ups, visible execution trace
- [x] SponsorBlock segments skipped by default, server configurable
- [x] In-app self-update; Material You dynamic color

Planned:

- [ ] Sending danmaku
- [ ] Rich text rendering in agent replies
- [ ] CI regression tests
- [ ] Interface and motion brought in line with Material Design
- [ ] Edge-to-edge and status bar handling
- [ ] Responsive layout
- [ ] Rebuilt keyword search
- [ ] Agent harness work
- [ ] Live streams
- [ ] Picture-in-picture
- [ ] Columns (articles)
- [ ] Opening and sharing bilibili links
- [ ] Video sharing
- [ ] Video downloads
- [ ] Filtering low-quality comments

## Install

Requires Android 10 or later.

Download the latest version from
[Releases](https://github.com/NihilDigit/bilby/releases/latest); the binaries are built by
GitHub Actions from the source in this repository.

**Assistant (optional).** The assistant needs an OpenAI-compatible endpoint. Enter the
address and key under Assistant in settings; everything else works without it.

## Contributing

Contributions are welcome. Bug fixes, crash reports, documentation and other small fixes
can go straight to an issue or a pull request.

For features and breaking changes, please open an RFC issue first, describing what you want,
what the app does about it today, and what the design would look like. It exists so you do
not write code in a direction the project cannot take. Introducing no recommendation
algorithms or other attention-grabbing features is a settled constraint of the project;
a pull request that moves it is unlikely to land; filing the issue first avoids the time
and effort both sides would lose to a mismatch in goals.

When using LLM-assisted coding, make sure you understand the business logic of the code
you add and verify it on a real device.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE).

The implementation of everything that talks to
bilibili — WBI signing, AppSign, the device fingerprint, TV qrcode login, playurl parameters,
reporting and write actions — is ported from
[PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) (GPL-3.0). Our thanks to its
developers.
