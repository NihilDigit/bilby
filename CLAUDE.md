# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Bilby is a client for bilibili on Android and Windows desktop, single account, open source.

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

## Documentation mirrors

**Three local mirrors carry the upstream documentation this codebase leans on. Read the
relevant one before designing anything architectural, and before any change that turns on
what a framework actually does.**

- `m3-material-mirror/` — m3.material.io: tokens, component anatomy, motion.
- `android-docs-mirror/` — developer.android.com: Media3 guides and reference, Compose,
  Navigation 3, foreground services and background work.
- `kotlin-docs-mirror/` — kotlinlang.org: language reference, the coroutines guide, stdlib.

All three have the same shape. `pages/` holds the cleaned Markdown, `pages/INDEX.md` maps
topics to files, and every page carries its source URL in the header. Generated content stays
out of Git through `.git/info/exclude`; `README.md` and `refresh.py` are committed, so a
fresh clone re-fetches with `python <dir>/refresh.py --workers 10`. Read the index, grep
`pages/`, then follow the header URL back to the source when the answer carries weight.

**Precedence: the resolved artifact beats the mirror, the mirror beats memory.** Whether a
symbol exists in the pinned version, and what its signature is, is answered by the aar or jar
in the Gradle cache — unzip its `classes.jar` and run `javap`. The mirror answers what an API
is for and how it is meant to be assembled. Documentation lags the library the same way the
public bilibili documentation lags PiliPlus.

**This rule exists because of a claim nobody checked.** `player/AudioPlaybackService` used to
state that `MediaController` has no `COMMAND_SET_VIDEO_SURFACE` and that a Surface cannot
reach the session; the shipped `media3-session` carries all four `setVideoSurface*` methods
and passes the Surface over the session binder. That sentence became the justification for a
static player reference, and from there for treating same-process as an architectural
premise. A comment asserting that an API does not exist is a claim about the library, and
claims about the library are checkable.

## Architecture traps

There is exactly one player. It belongs to `player/AudioPlaybackService`, a
`MediaSessionService`, and nothing outside the service holds a reference to it. UI reaches
it only through a `MediaController` — control, video Surface and video size all go over the
session. Session connections must keep the full default `Player.Commands`: with
`COMMAND_SET_VIDEO_SURFACE` withheld the controller returns silently and the symptom is a
black picture with nothing in the log. Leaving a page disconnects the controller and never
releases the player.

Common UI sees the player only as `PlaybackHost` (state, connect) and `PlayerHandle`
(control), and sends service commands as `PlaybackCommand`. On Android these wrap the
MediaController and its custom `SessionCommand`s (`AndroidPlaybackHost`); on desktop
`DesktopPlaybackHost` owns the single mpv player. mpv holds one item at a time, so the queue
lives in a state machine beside it (`player/DesktopQueue.kt`) that follows the Android rules;
see `docs/playback-refactor.md`. The desktop publishes `loadKey` before opening a stream, the
opposite of Android: mpv's D3D11 output is created by the surface, and the page only mounts
the surface once `loadKey` matches.

Listening mode is a state inside the video page, structurally identical to fullscreen. The
page stays composed, the same player keeps running, and progress stays where it is, so there
is no lifecycle to manage. Three earlier attempts got this wrong by modelling it as a
navigation destination, adding a `listening` flag on the service, and adding a
"popped versus covered" judgement at the nav layer.

On Android the queue is the ExoPlayer playlist, and there is no second copy of it. Items
carry the bvid as their `mediaId`; the cid is load state on the service, never written back
into the item. Streams are fetched by `player/LazyMediaSource` at the moment the player
reaches an entry, because playurl hands out time-limited CDN links — a link fetched when the
queue was built has expired by the time a later entry is reached. A resolution failure has to
reach `maybeThrowSourceInfoRefreshError`; swallowing one leaves the player buffering forever
with an empty log.

Multi-part videos and collections are different things. Shuffle changes play order only; the
displayed list keeps its order and the highlight scrolls, so the queue panel's position
counter is the index in the list, not the position in the shuffled play order.

Navigation 3 has no separate graph: the backstack is a `SnapshotStateList<NavKey>`, and it
does not deduplicate. Both entry decorators index by the key, so one key appearing twice
means a shared ViewModel and a shared saveable slot, popping either clears the other's
store, and composing both at once trips `SaveableStateHolder`'s `require`. Push through
`pushUnique` in `ui/NavBackStackPolicy.kt` — never `backStack.add` directly.

## Wide windows

Width decides layout, never the platform: `rememberBilbyWindowSize()` reads the window, and
the same breakpoints apply to a phone in landscape, a tablet and a desktop window.

- **Every sheet goes through `components/PaneSheet`.** Inside the video page's two-pane
  layout it draws in the right column; with no column to host it and the window at least
  expanded (840dp) it is a modal side sheet from the end edge; otherwise a bottom sheet.
  Calling `ModalBottomSheet` directly skips the first two.
- **Paged lists go through `components/PagedColumn`**, and `PagedLayout` picks one column,
  a grid (`maxWidthGridCells`: columns capped in width, count rounded up) or a staggered
  grid. Header, empty state, footer and prefetch come with it.
- **`components/PrefetchNearEnd` is the only near-end prefetch.** It stops while the last
  load has an error; only the retry loads again. Seven hand-copied versions without that
  check sent 25 requests in three seconds into a -412.
- **Persistent, resizable side panels use `components/SidePanelLayout`** (the space page's
  dynamics). Open state and width live in settings under a `SidePanelId`.
- **List pages wrap themselves in `AdaptiveListContent`**: from expanded they drop the
  readable-width cap and take a grid of cells capped at `VideoRowMaxWidth`. `AdaptiveContent`'s
  cap is for reading pages only (article, dynamic detail, comment thread, search assistant).
- **List and detail side by side is a scene, not page state.** material3 adaptive's
  `ListDetailSceneStrategy` (set up in `BilbyApp`) joins the entries at the top of the back stack
  tagged `listPane` / `detailPane` with the same scene key: messages and UP pushes with a whisper
  or comment thread, settings with its pages. It does not check that a detail sits on its list,
  so a detail is tagged only when opened from the list (`inListPane` on the key); a whisper
  opened from a space page would otherwise get an empty left pane.

## Modules and platforms

The app is Kotlin Multiplatform with two targets, Android and desktop JVM (Windows x64).

- `:shared` holds all code and UI. `commonMain` is everything that is not a platform
  framework; `androidMain` holds Media3, the playback service, WorkManager and window
  handling; `desktopMain` holds the mpv player (mediamp) and the desktop platform objects.
- `:app` is only the Android packaging entry: manifest, launcher resources, signing,
  BuildConfig. `BilbyApplication` fills `AppBuild` from BuildConfig.
- `:desktop` is only the desktop entry: the window and the MSI.

Both targets run on the JVM, so KGP does not compile `commonMain` as metadata and common code
may use JDK and plain Java libraries (`java.io.File`, OkHttp, zxing). Platform differences go
behind `expect`/`actual` or behind `Platform` / `SystemActions` / `PlaybackHost`; common code
never tests which platform it is on. A capability one platform lacks is a `supports*` flag
that hides the entry, never a button that does nothing.

UI strings live in `shared/src/commonMain/composeResources` and are read through
`dev.bilby.stringResource` / `getString`, not the Compose resources functions of the same
name: those only substitute `%1$s` and `%1$d`, and the English strings use `%1$.1f` and `%%`.

Compose is on Compose Multiplatform 1.12.1, pinned by mediamp's Skiko (see
`libs.versions.toml`). material3 therefore compiles against two versions: the BOM's
1.5.0-alpha25 on Android and CMP's alpha22 on desktop. Code in `commonMain` must compile
against both — build `:shared:compileKotlinDesktop` as well as Android.

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
./gradlew installDebug                                       # dev.bilby.debug
./gradlew assembleRelease                                    # dev.bilby, runs R8
./gradlew :shared:compileKotlinDesktop :shared:compileAndroidMain  # quick compile, both targets
./gradlew :shared:testAndroidHostTest :shared:desktopTest    # unit tests, both targets
./gradlew :shared:desktopTest --tests "dev.bilby.data.QueueFeedTest"  # one test class
./gradlew :desktop:run                                       # desktop app
./gradlew :desktop:packageReleaseMsi :desktop:packageReleaseUpdate  # MSI + update assets
java -Xverify:all desktop/package/VerifyClasses.java desktop/build/compose/binaries/main-release/app/Bilby/app
```

**Desktop packaging.** jlink and jpackage run on an Azul Zulu 25 toolchain, because Temurin 25
ships without jmods; bytecode stays at 17. **The desktop build does not run ProGuard.** Its
preverifier recomputed a wrong stack map for `PlayerShell` (a `long` local inferred as `top`),
so the packaged app threw `VerifyError` on entering the player; 7.8.0 and 7.10.0 both did, and
`:desktop:run` never goes through it. The last command above loads every packaged `dev.bilby`
class so the JVM verifies it; the release workflow runs the same check.

The MSI's `upgradeUuid` in `desktop/build.gradle.kts` is fixed forever: Windows Installer
recognises an upgrade by it, and changing it makes the next MSI install alongside the old one.
The same value reaches the app as `-Dbilby.upgrade-code`, which the in-app updater uses to ask
Windows Installer whether this install directory belongs to our MSI.

The desktop updater (`update/DesktopAppUpdater.kt`) needs three assets per release —
`bilby-windows-x64-<ver>.msi`, `-app.zip` and `-files.json` — and treats a version as available
only when all three exist. A patch update swaps the files marked `patch` in `files.json`;
anything else that differs forces a full MSI reinstall, so keep large unchanging files (native
libraries) out of the jars marked `patch`. Versions come from `-Dbilby.version`, never from
`jpackage.app-version`: the latter is the MSI version, which is 1.0.0 for every local build.

Releases come from a `v` tag through `.github/workflows/release.yml` and nowhere else. The
version is passed in as `-PbilbyVersion` and derived from the tag, so a local build reports
`0.0.0-dev`. The signing key exists only as a repository secret; local release builds fall
back to the debug key so R8 output can still be installed and checked. Unit tests live in
`:shared`: platform-free ones in `commonTest` run on both targets, Media3 and Robolectric ones
in `androidHostTest`. `:app` has no unit tests.

**A workflow triggered by a `release` event runs the file as it exists at the tag**, not the
one on the default branch. Fixing a release-time workflow therefore does nothing for the
release being cut; it takes effect from the next tag on. Dispatching the same workflow by
hand does read the default branch, which is the way to apply a fix to a tag already out.

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

The README's SLSA badge is `slsa-l3.svg` **on the orphan `badges` branch**, which nothing else
touches: an image the README links to has to live in the repository, but it does not have to
live on main.

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

Write tests only where they can catch something. Which part to play
(`player/LoadResolver.kt`), WBI signing, stream selection, and the agent loop's protocol
correctness qualify. UI and network glue do not.
