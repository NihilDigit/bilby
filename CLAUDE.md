# Bilby

Single-user Android client for bilibili. The implementation basis is `DESIGN.md` in the
repository root, which is a local file and not tracked. Read it before changing structure.

## Product constraints

Bilby resists compulsive use through structure, not through prompts, timers, or warning
copy. These are not implementation details to be traded away:

- No recommendation feed, no related-videos rail, no autoplay. Lists must end.
- The slot where the official app puts related recommendations holds the video's collection
  and the uploader's other work: a finite set the user selected by opening this video. The
  playback queue is built from that set, which is why the queue is not a listening-mode
  feature and needs no special handling.
- A queue plays to the end and stops. No wrapping, no looping, no refilling from a
  recommendation pool. Bounded selection is the whole reason autoplay is permitted here at
  all; refilling deletes the decision point.
- The search assistant's context contains only the user's current request. Never inject
  watch history.
- Report to bilibili honestly (history, heartbeats, coins, favourites, likes). Do no
  personalization locally.

## Fixed conventions

**PiliPlus is the authority on API behaviour.** Its source is in `PiliPlus/`, local and
gitignored. Public documentation lags live behaviour; where they disagree, follow PiliPlus.
Three cases already paid for: `bili_ticket` parameters must go in the query, not the body;
web cookies are blocked by risk control on write actions and must be replaced by a TV
`access_key`; `batch-deal` for favourites requires both `add_media_ids` and `del_media_ids`
(empty string when absent) and a `resources` value shaped like `aid:2`.

`notes/auth-model.md` records that the cookie-to-`access_key` path returns `-101` today. Do
not reimplement it.

**Logging.** Every failure swallowed by `runCatching` logs path, code, and message through
`BiliLog`. Credentials never appear in logs: not SESSDATA, `bili_jct`, `access_key`, or the
LLM key. Cookies may be logged by key name only.

**`api/BiliClient.kt` is the only API exit.** Its five routes are distinct: `rawGet`,
`rawPostForm` (adds csrf), `rawPostQuery` (passport endpoints accept query parameters only),
`appPostForm` (`access_key` plus AppSign, no cookies, app UA), and `appPostQuery` (TV
login). Do not issue requests around it.

**Optimistic updates exclude refetching.** Likes, coins, and favourites adjust the count
locally and do not refetch; refetching makes the number flicker twice on popular videos.

## Architecture traps

There is exactly one player. It belongs to `player/AudioPlaybackService`, a
`MediaSessionService`. UI controls it through a `MediaController`, but video must be
attached to the same-process `currentPlayer` reference because `MediaController` has no
`COMMAND_SET_VIDEO_SURFACE`. Leaving a page disconnects the controller and never releases
the player.

Listening mode is a state inside the video page, structurally identical to fullscreen. The
page stays composed, the player is not swapped, and no progress is handed over, so there is
no lifecycle to manage. Three earlier attempts got this wrong by modelling it as a
navigation destination, adding a `listening` flag on the service, and adding a
"popped versus covered" judgement at the nav layer.

Multi-part videos and collections are different things. Shuffle changes play order only and
must not reorder the displayed list; the highlight scrolls instead.

Navigation 3 has no separate graph: the backstack is a `SnapshotStateList<NavKey>`. `entry`
is a member extension on `EntryProviderScope` and needs no import; `onBack` is
`() -> Unit`.

## Toolchain

AGP 9 has built-in Kotlin support, and applying `org.jetbrains.kotlin.android` is a hard
error. KGP and KSP versions are overridden in the root `build.gradle.kts` `buildscript`
classpath, where version catalog accessors are unavailable, so changes to
`libs.versions.toml` must be mirrored there.

M3 Expressive is merged into mainline material3, but `MaterialExpressiveTheme` is
`internal`; use `MaterialTheme`. `MaterialTheme` sets no `LocalContentColor`, so content
needs a `Surface` wrapper or dark mode renders black on black.

Coil 3 requires `OkHttpNetworkFetcherFactory` to be registered explicitly and fails silently
otherwise. Cover URLs arrive as `http://` and are blocked by the cleartext policy; rewrite
them to https during mapping rather than enabling `usesCleartextTraffic`.

kotlinx.serialization omits fields equal to their defaults, so tool schemas sent to the LLM
need `encodeDefaults = true`.

`app/proguard-rules.pro` is short deliberately. Every dependency that R8 would break ships
consumer rules, and this codebase never looks up a class or member by name. Code that adds
name-based reflection must add its keep rule in the same change.

## Building and verifying

```
./gradlew installDebug          # dev.bilby.debug
./gradlew assembleRelease       # dev.bilby, runs R8
./gradlew testDebugUnitTest
```

Smoke test changes on a real device and watch end-to-end behaviour. `adb shell input tap` is
a no-op while the screen is off and reads as an unresponsive button, so send
`input keyevent KEYCODE_WAKEUP` first. `adb shell input text` is swallowed by the pinyin
IME.

Gradle compilation is an exclusive resource. Parallel subagents compiling at the same time
crash the Kotlin daemon.

Write tests only where they can catch something. The queue logic in
`player/PlaybackQueue.kt`, WBI signing, stream selection, and the agent loop's protocol
correctness qualify. UI and network glue do not.
