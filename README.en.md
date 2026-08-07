<img src="docs/icon.png" alt="" width="88" align="right">

# Bilby

[简体中文](README.md)

An Android client for bilibili with the recommendation machinery taken out.

> **This project is under active development.** The interface and the API layer are both
> still changing, and neither stability nor compatibility is guaranteed.

The home screen shows updates from the accounts you follow, newest first. What appears there
follows from who you follow; scrolling down moves further back in time, and the act of
scrolling produces nothing on its own. Recommendation feed, related-videos rail, autoplay:
none of the three.

Wherever the official app hands you the next video, Bilby leaves the choice to you. In the
slot for related recommendations sits the current video's collection and the uploader's
other work, a finite set fixed the moment you opened this video. A queue built from it plays
to the end and stops: its contents are settled when playback starts and stay that way. A
queue that refills has taken away the moment where you decide whether to keep watching.

To find something else, say what you want. Search works the usual way, and there is an
assistant you can describe things to: it searches, reads descriptions and comments, and
comes back with a few videos and its reasons for each. It knows only what you told it this
time, by design and permanently.

Bilby reports to bilibili like any other client, and history, heartbeats, coins, favourites,
and likes all go through. What it drops is the part that pushes back at you.

## What it does

Following feed, search, watch-later, and uploader pages.

Playback covers fullscreen, quality switching, speed, long-press fast-forward, drag-to-seek,
lock, double-tap play/pause, and multi-part videos. Listening mode puts a different UI in
front of the same player, with background playback, a queue, shuffle, and a sleep timer.
SponsorBlock segments are skipped by default.

Comments can be read, sorted, expanded into their reply threads, posted, liked, and deleted.

## Getting it

```
./gradlew installDebug
```

Sign in with the TV qrcode flow. One account, once.

The assistant needs an OpenAI-compatible endpoint. Put it in `local.properties` for debug
builds, or fill it in under settings on any build:

```properties
LLM_BASE_URL=https://.../v1
LLM_API_KEY=sk-...
```

Requires Android 10 or later. Built on Compose with Material 3, Navigation 3, Media3, Room,
and Ktor.

## Contributing

Contributions are welcome. Bug fixes, crash reports, documentation and small corrections can
go straight to a pull request.

For features and breaking changes, **please open an RFC issue first**, describing what you
want, what the app does about it today, and what the design would look like. This is not an
approval step; it exists so you do not write code in a direction the project cannot take.
The constraints at the top of this page are fixed, a pull request that moves them is
unlikely to land, and finding that out afterwards costs you the work.

**LLM-assisted code is fine.** The one requirement is that you understand what the code you
submit does in terms of behaviour — enough to say why it works and what it touches. Writing
every line by hand is not the bar.

The license follows from what Bilby is built on: the API layer is ported from PiliPlus,
which is GPL-3.0, so this project cannot move off the GPL. There is no CLA, and you keep
copyright over what you write.

`CLAUDE.md` carries the working conventions worth knowing before a first change.

## License

GPL-3.0-or-later, following [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus). See
[LICENSE](LICENSE).

Everything Bilby knows about talking to bilibili it learned from PiliPlus: WBI signing,
AppSign, the device fingerprint, TV qrcode login, playurl parameters, reporting, and write
actions are all ported from it. That project has already worked out which endpoints accept
what, which ones risk control will refuse, and which documented behaviour no longer holds.
