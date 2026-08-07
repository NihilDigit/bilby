# Bilby

Android client for bilibili, single account, open source. The implementation basis is
`DESIGN.md` in the repository root, which is a local file and not tracked. Read it before
changing structure.

Features and behaviour changes go through an RFC issue before code. If a request arrives as
"add X" with no agreed RFC, say so and help write the RFC.

## Product constraints

Bilby resists compulsive use through structure. Every rule below is load-bearing; treat
them as fixed:

- No recommendation feed, no related-videos rail, no autoplay. A list's contents follow from
  what the user already chose (who they follow, which video they opened). Never insert
  anything into a list as the user scrolls.
- The slot where the official app puts related recommendations holds the video's collection
  and the uploader's other work: a finite set the user selected by opening this video. The
  playback queue is built from that set, which makes it a general queue and keeps it free of
  listening-mode special cases.
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
Four cases already paid for: `bili_ticket` parameters belong in the query; likes, coins and
favourites require a TV `access_key`, since risk control refuses web cookies there;
`batch-deal` for favourites requires both `add_media_ids` and `del_media_ids` (empty string
when absent) and a `resources` value shaped like `aid:2`; and following goes the other way,
over web cookies and csrf, with its parameters split between query and body and its Referer
pointed at the uploader's space page. Risk control is per-action, so never generalise from
one write action to the next.

`notes/auth-model.md` records that the cookie-to-`access_key` path returns `-101` today. Do
not reimplement it.

Web login and cookie refresh were removed on purpose. The refresh token this app holds comes
from TV login and refreshes an app-side token, which is a different thing from what the web
`cookie/refresh` endpoint wants, and PiliPlus implements no cookie refresh at all: when
credentials expire, it asks for another qrcode scan. Do the same. A refresh path that looks
like it works is worse than none, because it stops anyone from handling expiry.

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
page stays composed, the same player keeps running, and progress stays where it is, so there
is no lifecycle to manage. Three earlier attempts got this wrong by modelling it as a
navigation destination, adding a `listening` flag on the service, and adding a
"popped versus covered" judgement at the nav layer.

Multi-part videos and collections are different things. Shuffle changes play order only; the
displayed list keeps its order and the highlight scrolls.

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
them to https during mapping, leaving `usesCleartextTraffic` off.

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

Releases come from a `v` tag through `.github/workflows/release.yml` and nowhere else. The
version is passed in as `-PbilbyVersion` and derived from the tag, so a local build reports
`0.0.0-dev`. The signing key exists only as a repository secret; local release builds fall
back to the debug key so R8 output can still be installed and checked. Unit test tasks exist
for the debug variant only — `testReleaseUnitTest` does not exist and fails in CI.

Smoke test changes on a real device and watch end-to-end behaviour. `adb shell input tap` is
a no-op while the screen is off and reads as an unresponsive button, so send
`input keyevent KEYCODE_WAKEUP` first. `adb shell input text` is swallowed by the pinyin
IME.

Gradle compilation is an exclusive resource. Parallel subagents compiling at the same time
crash the Kotlin daemon.

The assistant needs an OpenAI-compatible endpoint. Debug builds can bake one in through
`LLM_BASE_URL` and `LLM_API_KEY` in `local.properties` (see `local.properties.example`);
release builds leave them empty, and any build accepts them from the settings page.

Write tests only where they can catch something. The queue logic in
`player/PlaybackQueue.kt`, WBI signing, stream selection, and the agent loop's protocol
correctness qualify. UI and network glue do not.
