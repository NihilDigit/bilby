# Bilby

Android client for bilibili, single account, open source. The implementation basis is
`DESIGN.md` in the repository root, which is a local file and not tracked. Read it before
changing structure.

**This is the owner's own repository**, so features and behaviour changes are agreed in
conversation before code, not filed as issues. If a request arrives as "add X" with no agreed
shape, say so and work the design out first. Skipping issues is a consequence of the owner
being in the room — it is not advice for contributors, who should still open one.

## Product constraints

Bilby takes an anti-algorithmic stance: what the user sees comes from their own
subscriptions and from searches they start, never from a ranker deciding on their behalf.
The point is that the content is worth their attention, not that they spend less time in
the app — this is not a screen-time tool, and arguments of the form "that would make the
app too engaging" do not belong here. Every rule below is load-bearing; treat them as
fixed:

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
**Risk control is per-action**: an endpoint that accepts web cookies tells you nothing about
the next one, and a write path that works over `access_key` tells you nothing about the one
beside it. Never generalise from one action to the next — check what PiliPlus actually sends
for that specific call. The parameter, header and signing facts already paid for live in
`notes/` and at their call sites.

Two paths were built, tested against the live API, and removed. Do not reimplement either:
the cookie-to-`access_key` exchange, which returns `-101`; and web login with cookie
refresh, because the refresh token this app holds comes from TV login and refreshes an
app-side token, which is a different thing from what the web `cookie/refresh` endpoint
wants. PiliPlus implements no cookie refresh either — when credentials expire it asks for
another qrcode scan, and so do we. A refresh path that looks like it works is worse than
none, because it stops anyone from handling expiry.

**Logging.** Every failure swallowed by `runCatching` logs path, code, and message through
`BiliLog`. Credentials never appear in logs: not SESSDATA, `bili_jct`, `access_key`, or the
LLM key. Cookies may be logged by key name only.

**`api/BiliClient.kt` is the only API exit.** Its routes differ in credential, signing and
UA, and each one exists because some endpoint refused the others. A call that needs a shape
none of them has gets a new route there — never a request issued around it.

**Optimistic updates exclude refetching.** Likes, coins, and favourites adjust the count
locally and do not refetch; refetching makes the number flicker twice on popular videos.

**Never separate metadata with a middle dot.** Not `·`, not `•`, not any of their
lookalikes. Use `MetaSeparator` (two spaces, in `ui/components/VideoRow.kt`). The last
segment of these lines is usually the one that gets truncated — an uploader name, an IP
region — and the dot truncates with it, leaving a dot hanging at the end of the line; on a
narrow screen the dots also wrap before the content does. A literal dot as a *selection
mark* in a dropdown is a different thing and stays.

**Interface copy is written, never spoken.** No `刷视频`, no `删掉`, no `再下一次`. The
register that makes an irreversible action sound casual is the register that gets it
misread. Two carve-outs: the assistant's process rows, where `瞟了一眼` is exactly right
because those lines are the assistant reporting on itself (see `Tool.label`); and copy the
user is owed nothing extra — confirmations state the action and stop. `取消关注` is the
whole dialog. Explaining that unfollowing means finding the person again is user education
this app does not do, and PiliPlus does not either.

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

Navigation 3 has no separate graph: the backstack is a `SnapshotStateList<NavKey>`.

## Toolchain

AGP 9 has built-in Kotlin support, and applying `org.jetbrains.kotlin.android` is a hard
error. KGP and KSP versions are overridden in the root `build.gradle.kts` `buildscript`
classpath, where version catalog accessors are unavailable, so changes to
`libs.versions.toml` must be mirrored there.

M3 Expressive is merged into mainline material3, and the pinned version is an alpha that
moves. Check the resolved artifact before assuming a symbol is internal, absent, or needs an
opt-in — visibility and experimental gating have both changed under this project already,
and `M3ApiProbe.kt` only catches symbols that disappear, not opt-ins that become
unnecessary. Neither theme sets `LocalContentColor`, so content needs a `Surface` wrapper or
dark mode renders black on black.

Coil 3 requires `OkHttpNetworkFetcherFactory` to be registered explicitly and fails silently
otherwise. Cover URLs arrive as `http://` and are blocked by the cleartext policy; rewrite
them to https during mapping, leaving `usesCleartextTraffic` off.

kotlinx.serialization omits fields equal to their defaults, so tool schemas sent to the LLM
need `encodeDefaults = true`.

`docs/ui-style-guide.md` carries the interface conventions: the design tokens, which
component to reach for, and which alpha-only APIs this build depends on. Read it before
changing anything under `ui/`.

`app/proguard-rules.pro` is short because this codebase never looks up a class or member by
name. Code that adds name-based reflection must add its keep rule in the same change.

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

The workflow writes an install-and-verify section into the release body, and publishes as a
draft. **`gh release edit --notes-file` replaces the whole body, it does not append** — pass
the changelog plus that section, or read the existing body back and prepend to it. Getting
this wrong drops the checksum and `gh attestation verify` instructions from the download
page, which is where they are of any use. It has happened once.

**Release notes are written for the person downloading the APK.** One line of summary, then
`## 修复` and `## 变化`, one written sentence per entry, in Chinese and in the same register
as the rest of the interface. Each line says what the reader will notice — the symptom that
is gone, the behaviour that is different — not what moved in the code. No file names, no
type names, no commit subjects, no thanks or filler. Skip anything the reader cannot see;
a refactor with no visible effect does not belong in the notes at all. Match the previous
release: read it back with `gh release view <tag> --json body` before writing the next one.

`.github/workflows/apk-size-badge.yml` refreshes the size badge and runs on its own after a
release is published; it can also be dispatched against any older tag. It writes
`.github/badges/apk-size.json`, and that file has to live in the repository: shields.io
rejects `github.com` as an endpoint host, so serving the JSON as a release asset returns
`domain is blocked`.

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
