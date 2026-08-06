# Bilby

[简体中文](README.zh-CN.md)

An Android client for bilibili with the recommendation machinery taken out.

The home screen shows updates from the accounts you follow, newest first. What appears there
is decided entirely by who you follow; scrolling down moves further back in time, and
nothing is inserted along the way. There is no recommendation feed, no related-videos rail,
and no autoplay.

Everything that would normally hand you the next video has been replaced by something you
choose. Where the official app puts related recommendations, Bilby puts the current video's
collection and the uploader's other work: a finite set you selected by opening this video. A
queue built from that set plays to the end and stops. It does not wrap, loop, or refill from
a recommendation pool, because a queue that refills has quietly removed the moment where you
decide whether to keep watching.

To find something else, say what you want. Search works the usual way, and there is an
assistant you can describe things to: it searches, reads descriptions and comments, and
comes back with a few videos and its reasons for each. It has no access to your watch
history and will not be given any. Its context holds only what you told it this time.

Bilby reports to bilibili like any other client. History, heartbeats, coins, favourites, and
likes all go through. The part it drops is the part that pushes back at you.

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

Bilby is a personal project that happens to be open source, and its shape comes from one
person's opinion about how a video client should behave. That opinion is the thing being
maintained here.

Bug fixes, crash reports, and small corrections can go straight to a pull request.

Features and behaviour changes need an RFC first. Open an issue describing what you want,
what the app does about it today, and what the design would look like, and wait for
agreement before writing code. The constraints at the top of this page are the ones most
likely to be at stake; a pull request that moves them without an agreed RFC will be closed
on those grounds alone.

`CLAUDE.md` carries the working conventions worth knowing before a first change.

## License

GPL-3.0-or-later, following [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus). See
[LICENSE](LICENSE).

Everything Bilby knows about talking to bilibili it learned from PiliPlus: WBI signing,
AppSign, the device fingerprint, TV qrcode login, playurl parameters, reporting, and write
actions are all ported from it. That project has already worked out which endpoints accept
what, which ones risk control will refuse, and which documented behaviour no longer holds.
