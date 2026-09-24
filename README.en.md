<p align="center"><img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/icon.png" alt="Bilby" width="96"></p>

# Bilby

[简体中文](README.md)

[![APK](https://raw.githubusercontent.com/NihilDigit/bilby/badges/apk-size.svg)](https://github.com/NihilDigit/bilby/releases/latest) [![Android 10+](https://img.shields.io/badge/Android-10%2B-4A5C92?style=flat-square&logo=android&logoColor=white)](#install) [![SLSA Build L3](https://raw.githubusercontent.com/NihilDigit/bilby/badges/slsa-l3.svg)](https://github.com/NihilDigit/bilby/attestations) [![GPL-3.0](https://img.shields.io/github/license/NihilDigit/bilby?style=flat-square&color=4A5C92&logo=gnu&logoColor=white)](LICENSE)

![Kotlin](https://img.shields.io/badge/Kotlin-4A5C92?style=flat-square&logo=kotlin&logoColor=white) ![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-4A5C92?style=flat-square&logo=jetpackcompose&logoColor=white) ![Material 3 Expressive](https://img.shields.io/badge/Material%203%20Expressive-4A5C92?style=flat-square&logo=materialdesign&logoColor=white)

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
<p align="center">The home feed carries only uploaders you follow; the queue follows the list the video was opened from; when needed, the agent can search for related content based on the video you are watching</p>

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

## Panes by window width

<p align="center">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/tablet-feed.png" width="380" height="238" alt="Feed on a tablet: timeline on the left, most-visited on the right">
<img src="https://raw.githubusercontent.com/NihilDigit/bilby/main/docs/screenshots/tablet-video.png" width="380" height="238" alt="Playback on a tablet: picture on the left, description and comments on the right">
</p>
<p align="center">The feed moves most-visited into a supporting pane; playback puts the picture on the left and
the description and comments on the right. Phone layouts are unaffected.</p>

## Features

Done:

- [x] Feed: following feed (with per-uploader muting), special or most-visited uploaders (live ones open the room directly), uploader pushes
- [x] Following list: filter by group, sort by most visited or most recent, search; special and mutual follows are marked, unfollow from the row
- [x] History: resume where you left; delete one, multi-select, clear watched or everything; pause recording
- [x] Watch later: clear invalid or watched videos
- [x] Moments: videos, reposts, image posts, text, live, articles and reservations; bodies and images shown as posted, emotes inline, reservations added to the calendar
- [x] Articles read in-app: text, images, link cards, code blocks and formulas
- [x] Uploader pages: uploads, moments, and collections/series tabs, with in-space search
- [x] Playback: fullscreen, picture-in-picture, video and audio quality, speed (WSOLA), double-tap to seek or pause, long-press fast-forward, drag-to-seek, swipe for brightness and volume, lock; progress synced to the cloud; default video and audio quality set separately for Wi-Fi and metered networks, and a pick in the player applies to the current playback only by default
- [x] Queue: follows the list the video was opened from (collection, uploads, favourites, watch later, offline), parts included; kept per page, so leaving the video page and coming back finds the queue intact
- [x] Listening: same player as normal playback, screen-off background play, notification, lock screen and headset controls, a sleep timer adjustable by the minute; shuffle queue; the subtitle track doubles as line-by-line lyrics, tap a line to seek
- [x] Live: watch a room with danmaku, quality, audio only, fullscreen, picture-in-picture and superchats; follow the streamer
- [x] Offline: pick videos from the playback queue (select-all available), choose a quality, danmaku kept alongside; concurrency is configurable and the speed is shown; cached items live under the profile tab, support long-press multi-delete, play without the network, and form a queue of the whole library; with no network the video page shows the cached title, description and counts; progress watched offline is reported once back online; on a weak network the local copy plays directly
- [x] Danmaku: scrolling, top and bottom, following the playback clock, adjustable opacity; post from the video page (yours appears at once) or from the bar under the live chat
- [x] Subtitles: multiple tracks, under the picture during normal playback, as a transcript while listening; AI subtitle repair
- [x] Comments: read, sort, expand reply threads, post and reply, like, delete, tap a timestamp to seek, zoom images and swipe between them; reply, mention and like notifications open the original comment
- [x] Actions: like, coin, favourite, follow, watch later; joint submissions credit each uploader separately and can be followed one by one; like and reply to moments; coin history
- [x] Favourites: create, rename and delete folders, edit the description and visibility; unfavouriting inside a folder can be undone
- [x] Follow groups and the blocklist: add, rename and delete groups, set groups per uploader; block and unblock
- [x] Agent: conversational site-wide search and "find related" from the video page; reads descriptions and top comments, candidates each with a reason; multi-turn follow-ups, visible execution trace, replies rendered as Markdown
- [x] Messages: chats, replies, mentions, likes and system notices; text chats send and receive, video and article messages open in the app; uploader pushes get their own page, with the note shown apart from the pushed video
- [x] Links and sharing: opens bilibili links and b23.tv short links, shares videos and live rooms
- [x] SponsorBlock segments skipped by default, server configurable
- [x] Interface: Material 3 Expressive, motion rebuilt from the spec; skeletons while loading; two-pane layout on tablets; edge-to-edge and display cutout handling
- [x] Appearance: follow the system, light or dark, with a pure black option for dark; system dynamic color or one of nine built-in palettes; interface language in Simplified Chinese or English
- [x] In-app self-update
- [x] Playback progress model rebuilt: parts, local copies and cloud conflicts handled correctly
- [x] Following feed rebuilt: uploads and articles in one stream, unfollowing and muting take effect at once, "where you left off" located per visit

Planned:

- [ ] Interface: keep refining the details
- [ ] Better adaptive layout
- [ ] Player behaviour refinement
- [ ] Refine coining, liking and the other site actions
- [ ] Filtering low-quality comments and moments
- [ ] Performance work
- [ ] Clean up abstractions and data flow
- [ ] Comment pruning
- [ ] Instrumented tests on a device in CI
- [ ] Agent harness work

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
not write code in a direction the project cannot take.

These are out of scope, and a pull request that adds them is unlikely to land:

- **Circumvention of any kind.** No defeating membership gates or paywalled quality tiers,
  and nothing that touches billing or entitlement; viewing and interaction data are
  reported back as usual.
- **Anime, film, courses and anything else that is not UGC.** The app plays user
  submissions only, and those links are not resolved.
- **Anything that interrupts the user or competes for their attention.** Recommendation
  feeds, a persistent related-videos rail and "for you" of any kind all fall here; the app
  implements neutral behaviour only, and what a list contains follows from what the user did.

When using LLM-assisted coding, make sure you understand the business logic of the code
you add and verify it on a real device.

## License

GPL-3.0-or-later, see [LICENSE](LICENSE).

The implementation of everything that talks to
bilibili — WBI signing, AppSign, the device fingerprint, TV qrcode login, playurl parameters,
reporting and write actions — is ported from
[PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus) (GPL-3.0). Our thanks to its
developers.
