# Bilby

Android client for bilibili, single account, open source.

**Three boundaries, stated in `README.md`'s contributing section, which is authoritative on
them.** Restated here so they can be applied without a second file open:

- **Circumvention and entitlement.** No defeating membership gates or paywalled quality
  tiers, nothing touching billing or entitlement; viewing and interaction data are reported
  back as they would be from the official app.
- **UGC only.** The app plays user submissions; anime, film and course links are not
  resolved.
- **Interruption and attention.** Nothing designed to interrupt the user or compete for
  their attention. The app implements neutral behaviour only, and what a list contains
  follows from what the user did.

When a request seems to cross one of them, that file settles it.

**This is the owner's own repository**, so features and behaviour changes are agreed in
conversation before code, not filed as issues. If a request arrives as "add X" with no agreed
shape, say so and work the design out first. Skipping issues is a consequence of the owner
being in the room — it is not advice for contributors, who should still open one.

**Product copy, reworked business logic and architecture changes get a proposal first.**
Show the shape and wait for a yes. These are the things the owner reads word by word or has
to live with afterwards, and a diff is the wrong place to meet them for the first time. Bug
fixes and mechanical work go straight in.

## Product shape

A position, and it can change in conversation. The test is whether the app interrupts the
user or competes for their attention: what a list contains follows from what the user did,
and the app implements neutral behaviour only. It is not a screen-time tool — using the app
less is not the goal, and "that would make the app too engaging" is not an argument here.

**Finiteness is a result, not a rule.** A subscription feed runs out and a collection ends
because the content follows the user's own choices. Do not reason backwards from it: capping
a list, refusing a second page, or calling an upstream limit a feature are all inventions.

The concrete rules:

- No recommendation feed and no related-videos rail. 找相关 is one explicit tap that returns
  a handful of candidates with reasons, and does not persist on the page.
- The search assistant's context contains only the user's current request. Never inject
  watch history. The step ceiling, the provenance check, and the result count live in code,
  not in the prompt (see `agent/AgentLoop.kt`).
- Do no personalization locally. Reporting to bilibili is one thing; deciding anything on
  the basis of what came back is not allowed.

## Fixed conventions

**PiliPlus is the authority on API behaviour.** Its source is in `PiliPlus/`, local and
gitignored. Public documentation lags live behaviour; where they disagree, follow PiliPlus.
**Risk control is per-action**: an endpoint that accepts web cookies tells you nothing about
the next one, and a write path that works over `access_key` tells you nothing about the one
beside it. Never generalise from one action to the next — check what PiliPlus actually sends
for that specific call. The parameter, header and signing facts already paid for live in
`notes/` and at their call sites.

**A newly established API fact goes into the matching file under `notes/`**, with the call
site left holding one line that points there. KDoc explains why this call is written the way
it is; `notes/` records what the endpoint actually does, which is what the next feature will
need. Facts that only ever reached a KDoc get established twice.

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
because those lines are the assistant reporting on itself (see `Tool.label`); and
confirmations, which state the action and stop. `取消关注` is the whole dialog — spelling
out that unfollowing means finding the person again tells the reader something they already
know, and a dialog that explains itself gets dismissed without being read.

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

Navigation 3 has no separate graph: the backstack is a `SnapshotStateList<NavKey>`, and it
does not deduplicate. Both entry decorators index by the key, so one key appearing twice
means a shared ViewModel and a shared saveable slot, popping either clears the other's
store, and composing both at once trips `SaveableStateHolder`'s `require`. Push through
`pushUnique` in `ui/NavBackStackPolicy.kt` — never `backStack.add` directly.

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

**修复 lists what was broken in the released version, not what broke on the way here.** Half
of a batch is usually self-inflicted and self-repaired before anyone saw it; reporting that
asks the reader to verify something they never had. The diff since the tag answers it — a
problem inside a file that is new in this release was never shipped.

`.github/workflows/apk-size-badge.yml` refreshes the size badge and runs on its own after a
release is published; it can also be dispatched against any older tag. It writes
`.github/badges/apk-size.json`, and that file has to live in the repository: shields.io
rejects `github.com` as an endpoint host, so serving the JSON as a release asset returns
`domain is blocked`.

**The device is the owner's, and driving it needs their say-so.** Reach for `adb` — install,
launch, tap, screenshot — only after they have asked for it in this session. Otherwise hand
them the steps to run and wait.

**When they ask for a screenshot, take the screenshot and nothing else.** No relaunch, no
`installDebug`, no `am start`, nothing that tears down the activity they are looking at:
they are pointing at what is on screen right now, and rebuilding it answers a different
question. Install the new build when they ask for the new build.

While driving is authorised: `adb shell input tap` is a no-op with the screen off and reads
as an unresponsive button, so send `input keyevent KEYCODE_WAKEUP` first, and
`adb shell input text` is swallowed by the pinyin IME.

Gradle compilation is an exclusive resource. Parallel subagents compiling at the same time
crash the Kotlin daemon.

The assistant needs an OpenAI-compatible endpoint. Debug builds can bake one in through
`LLM_BASE_URL` and `LLM_API_KEY` in `local.properties` (see `local.properties.example`);
release builds leave them empty, and any build accepts them from the settings page.

Write tests only where they can catch something. The queue logic in
`player/PlaybackQueue.kt`, WBI signing, stream selection, and the agent loop's protocol
correctness qualify. UI and network glue do not.
