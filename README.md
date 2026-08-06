# Bilby

A single-user Android client for bilibili.

Bilby is smaller than the official app on purpose. It has no recommendation feed, no
related-videos rail, and no autoplay. The home screen shows updates from the accounts you
follow, in order, and it ends. When it ends, there is nothing underneath it.

Everything that would normally hand you the next video has been replaced by something you
have to choose. Where the official app puts related recommendations, Bilby puts the current
video's collection and the uploader's other work: a finite set you picked by opening this
video. A queue built from that set plays to the end and stops. It does not wrap, loop, or
refill from a recommendation pool, because a queue that refills has quietly removed the
moment where you decide whether to keep watching.

To find something else, search for it, or describe it to the built-in assistant. The
assistant searches, reads descriptions and comments, and comes back with a few results and
its reasons for each. It has no access to your watch history and will not be given any; its
context contains only what you told it this time.

None of this is adversarial toward bilibili. History, heartbeats, coins, favourites, and
likes are all reported normally. What Bilby removes is the part that pushes back at you.

## What it does

Following feed, search, watch-later, and uploader pages. Playback with fullscreen, quality
switching, speed control, long-press fast-forward, drag-to-seek, lock, double-tap
play/pause, and multi-part videos. Listening mode reuses the same player behind a different
UI, with background playback, a queue, shuffle, and a sleep timer. Comments can be read,
sorted, expanded, posted, liked, and deleted. SponsorBlock segments are skipped by default.

## Build

```
./gradlew installDebug     # installs dev.bilby.debug
./gradlew assembleRelease  # runs R8, installs dev.bilby, coexists with the debug build
```

Debug builds read LLM credentials from `local.properties`, which is not tracked:

```properties
LLM_BASE_URL=https://.../v1
LLM_API_KEY=sk-...
```

Release builds get empty strings. At runtime the source of truth is in-app settings either
way.

Login goes through the TV qrcode flow. Web cookies are blocked by risk control on every
write action, so likes, coins, and favourites all return `-403 账号异常` under that path; a
TV `access_key` works.

Targets `compileSdk`/`targetSdk` 37 with `minSdk` 29. Built on Compose with Material 3,
Navigation 3, Media3, Room, Ktor 3, and kotlinx.serialization.

## License

GPL-3.0-or-later, following [PiliPlus](https://github.com/bggRGjQaUbCoE/PiliPlus). See
[LICENSE](LICENSE).

Everything Bilby knows about talking to bilibili it learned from PiliPlus: WBI signing,
AppSign, the device fingerprint, TV qrcode login, playurl parameters, reporting, and write
actions are all ported from it. That project has already worked out which endpoints accept
what, which ones risk control will refuse, and which documented behaviour no longer holds.
