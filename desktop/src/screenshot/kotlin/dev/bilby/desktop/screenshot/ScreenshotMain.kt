package dev.bilby.desktop.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.awaitApplication
import coil3.SingletonImageLoader
import dev.bilby.AppBuild
import dev.bilby.AppContainer
import dev.bilby.DesktopPlatform
import dev.bilby.api.BiliResult
import dev.bilby.buildBilbyImageLoader
import dev.bilby.data.AccountInfo
import dev.bilby.data.AppearancePrefs
import dev.bilby.data.SidePanelId
import dev.bilby.data.StartTab
import dev.bilby.data.ThemeMode
import dev.bilby.desktop.AppMotionScale
import dev.bilby.desktop.BilbyWindow
import dev.bilby.desktop.DesktopToastOverlay
import dev.bilby.desktop.PixelAlignedContentEffect
import dev.bilby.desktop.WindowFrame
import dev.bilby.desktop.appIcon
import dev.bilby.desktop.loadDevLocalProperties
import dev.bilby.desktop.screenshot.SemanticsDriver.Companion.containsAnyLabel
import dev.bilby.desktop.screenshot.SemanticsDriver.Companion.descriptions
import dev.bilby.desktop.screenshot.SemanticsDriver.Companion.hasAnyLabel
import dev.bilby.desktop.screenshot.SemanticsDriver.Companion.isEditable
import dev.bilby.desktop.useBundledMpvRuntime
import dev.bilby.player.PlayerHandle
import dev.bilby.ui.AppFullscreenController
import dev.bilby.ui.BilbyRoot
import dev.bilby.ui.DesktopLanguageStore
import dev.bilby.ui.DesktopSystemActions
import dev.bilby.ui.LocalAppFullscreen
import dev.bilby.ui.LocalPlaybackHost
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.components.SkiaAnimatedImageDecoder
import dev.bilby.ui.player.LocalWindowFullscreen
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.SystemReducedMotion
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.io.File
import kotlin.system.exitProcess

private const val WIDE_WIDTH_DP = 1280
private const val WIDE_HEIGHT_DP = 809
private const val COMPACT_WIDTH_DP = 420
private const val COMPACT_HEIGHT_DP = 809

private object Labels {
    val feedTab = arrayOf("订阅", "Following")
    val searchTab = arrayOf("搜索", "Search")
    val libraryOrProfileTab = arrayOf("媒体库", "Library", "我的", "Me")
    val historyTitle = arrayOf("历史记录", "History")
    val favFoldersTitle = arrayOf("收藏夹", "Favorites")
    val searchPlaceholder = arrayOf("搜索视频、UP 主与专栏", "Search videos, creators and articles")
    val askAgent = arrayOf("问助理", "Ask assistant")
    val newSession = arrayOf("新会话", "New session")
    val agentRunning = arrayOf("检索中…", "Searching…")
    val agentSources = arrayOf("提到的视频", "Videos mentioned")
    val findRelated = arrayOf("找相关", "Find related")
    val relatedFollowUp = arrayOf("继续提问", "Ask a follow-up")
    val relatedRunning = arrayOf("相关  检索中", "Related  searching")
    val listenAction = arrayOf("听视频", "Listen")
    val listenTitle = arrayOf("听视频", "Listen")
    val subtitle = arrayOf("字幕", "Subtitles")
    val subtitleOff = arrayOf("关闭字幕", "Subtitles off")
    val sleepTimerOff = arrayOf("定时关闭", "Sleep timer")
    val sleepTimer30 = arrayOf("30 分钟", "30 min")
    val back = arrayOf("返回", "Back")
}

fun main(args: Array<String>) {
    var exitCode = 0
    try {
        val config = ScreenshotConfig.parse(args)
        runScreenshotHarness(config)
    } catch (t: Throwable) {
        ShotLogger.error("Screenshot harness failed: ${t.message}", t)
        exitCode = 1
    }
    exitProcess(exitCode)
}

private fun runScreenshotHarness(config: ScreenshotConfig) {
    val versionName = System.getProperty("bilby.version") ?: "0.0.0-dev"
    val devLocalProps = loadDevLocalProperties()
    fun resolveDevLlmProp(sysKey: String, altSysKey: String, localKey: String): String {
        return System.getProperty(sysKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getProperty(altSysKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: devLocalProps?.getProperty(localKey)?.trim().orEmpty()
    }
    AppBuild.init(
        debug = true,
        versionName = versionName,
        applicationId = "dev.bilby.desktop.screenshot",
        llmBaseUrl = resolveDevLlmProp("bilby.llm.baseUrl", "bilby.llm.base-url", "LLM_BASE_URL"),
        llmApiKey = resolveDevLlmProp("bilby.llm.apiKey", "bilby.llm.api-key", "LLM_API_KEY"),
        llmModel = resolveDevLlmProp("bilby.llm.model", "bilby.llm.model", "LLM_MODEL"),
    )
    useBundledMpvRuntime()

    lateinit var container: AppContainer
    val platform = DesktopPlatform(
        container = { container },
        updater = null,
        silentAudioOutput = true,
    )
    val redactedAvatarBytes = if (config.redactAccount) createRedactedAvatarPngBytes() else null
    val accountTransform: (AccountInfo) -> AccountInfo = if (config.redactAccount) {
        { info ->
            info.copy(
                mid = 100_000L,
                name = "BilbyUser",
                faceUrl = REDACTED_AVATAR_URL,
                level = 6,
                isSeniorMember = false,
                sign = "",
                coins = 42.0,
                following = 18,
            )
        }
    } else {
        { it }
    }
    container = AppContainer(
        platform = platform,
        reportHeartbeats = false,
        accountTransform = accountTransform,
    )

    SingletonImageLoader.setSafe { context ->
        buildBilbyImageLoader(
            context = context,
            diskCacheDir = platform.filesDir.resolve("coil_cache"),
            extraComponents = {
                if (redactedAvatarBytes != null) {
                    add(RedactedAvatarFetcherFactory(redactedAvatarBytes))
                }
                add(SkiaAnimatedImageDecoder.Factory())
            },
        )
    }

    SystemReducedMotion.ensureStarted()
    val languageStore = DesktopLanguageStore(File(platform.filesDir, "language.properties"))

    runBlocking(AppMotionScale) {
        val creds = container.settings.credentials.first()
        check(creds.isLoggedIn) {
            "No logged-in session found in sandbox DataStore. Sign in via :desktop:run first or pass --from-profile=<BilbyDir>."
        }
        container.settings.saveStartTab(StartTab.Feed)
        container.settings.saveSearchLanding(true)
        container.settings.saveThemeMode(if (config.theme.includesLight()) ThemeMode.Light else ThemeMode.Dark)

        val windowReady = CompletableDeferred<ComposeWindow>()
        val incomingLink = MutableStateFlow<String?>(null)
        val showTitleBarState = mutableStateOf(true)
        val windowState = WindowState(
            position = WindowPosition.Aligned(Alignment.Center),
            size = DpSize(WIDE_WIDTH_DP.dp, (WIDE_HEIGHT_DP + 11).dp),
        )

        awaitApplication {
            BilbyWindow(
                onCloseRequest = { exitApplication() },
                title = "Bilby",
                icon = appIcon,
                state = windowState,
            ) {
                LaunchedEffect(window) {
                    window.minimumSize = Dimension(320, 480)
                    if (!windowReady.isCompleted) {
                        windowReady.complete(window)
                    }
                }
                var appFullscreen by remember { mutableStateOf(false) }
                var playerFullscreen by remember { mutableStateOf(false) }
                val playerExitCallbacks = remember { LinkedHashSet<() -> Unit>() }
                val appFullscreenController = remember {
                    object : AppFullscreenController {
                        override val isFullscreen: Boolean get() = appFullscreen
                        override val isAnyFullscreen: Boolean
                            get() = appFullscreen || playerFullscreen || playerExitCallbacks.isNotEmpty()

                        override fun setFullscreen(fullscreen: Boolean) {
                            appFullscreen = fullscreen
                        }

                        override fun exitAll() {
                            appFullscreen = false
                            playerFullscreen = false
                            playerExitCallbacks.toList().forEach { it() }
                        }

                        override fun registerPlayerFullscreenExit(onExit: () -> Unit): () -> Unit {
                            playerExitCallbacks.add(onExit)
                            return { playerExitCallbacks.remove(onExit) }
                        }
                    }
                }
                PixelAlignedContentEffect(window)
                val systemActions = remember { DesktopSystemActions(languageStore, container.biliClient) }
                val appearance by container.settings.appearancePrefs.collectAsState(initial = AppearancePrefs())
                CompositionLocalProvider(
                    LocalSystemActions provides systemActions,
                    LocalPlaybackHost provides platform.playback,
                    LocalAppFullscreen provides appFullscreenController,
                    LocalWindowFullscreen provides { on -> playerFullscreen = on },
                ) {
                    BilbyTheme(appearance) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            WindowFrame(
                                title = "Bilby",
                                icon = appIcon,
                                showTitleBar = showTitleBarState.value,
                                forceActiveCaption = true,
                            ) {
                                BilbyRoot(container, incomingLink, wrapTheme = false)
                            }
                            DesktopToastOverlay(
                                toastState = systemActions.toastState,
                                onDismiss = systemActions::dismissToast,
                                modifier = Modifier.align(Alignment.BottomCenter),
                            )
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                try {
                    val window = windowReady.await()
                    val driver = ScreenshotDriver(
                        config = config,
                        container = container,
                        platform = platform,
                        window = window,
                        windowState = windowState,
                        showTitleBarState = showTitleBarState,
                        incomingLink = incomingLink,
                    )
                    driver.run()
                } finally {
                    platform.playback.stop()
                    exitApplication()
                }
            }
        }
    }
}

private class ScreenshotDriver(
    private val config: ScreenshotConfig,
    private val container: AppContainer,
    private val platform: DesktopPlatform,
    private val window: ComposeWindow,
    private val windowState: WindowState,
    private val showTitleBarState: androidx.compose.runtime.MutableState<Boolean>,
    private val incomingLink: MutableStateFlow<String?>,
) {
    private val semantics = SemanticsDriver(window)

    suspend fun run() {
        val selected = config.scenes
        ShotLogger.info(
            "Capturing ${selected.size} scene(s): ${selected.joinToString(", ") { it.cliName }} " +
                "(redactAccount=${config.redactAccount}, format=${config.format.name.lowercase()})",
        )

        // Wait for root UI to mount after DataStore credentials + nightPrefs settle
        setFrameSize(WIDE_WIDTH_DP, WIDE_HEIGHT_DP, showTitleBar = true)
        semantics.waitUntil(timeoutMs = 20_000L, description = "root feed tab") {
            semantics.hasNode { it.hasAnyLabel(*Labels.feedTab) }
        }

        if (SceneId.FEED_WIDE in selected) {
            setFrameSize(WIDE_WIDTH_DP, WIDE_HEIGHT_DP, showTitleBar = true)
            semantics.sendKey(KeyEvent.VK_1, modifiers = InputEvent.CTRL_DOWN_MASK)
            waitForFeedLoaded()
            capturePair(SceneId.FEED_WIDE)
        }

        if (SceneId.LIBRARY_WIDE in selected) {
            setFrameSize(WIDE_WIDTH_DP, WIDE_HEIGHT_DP, showTitleBar = true)
            semantics.clickNode("library tab") { it.hasAnyLabel(*Labels.libraryOrProfileTab) }
            semantics.waitUntil(timeoutMs = 20_000L, description = "library screen loaded") {
                semantics.hasNode { it.hasAnyLabel(*Labels.historyTitle) } &&
                    semantics.hasNode { it.hasAnyLabel(*Labels.favFoldersTitle) }
            }
            delay(1_800L)
            capturePair(SceneId.LIBRARY_WIDE)
        }

        if (SceneId.PROFILE_COMPACT in selected) {
            setFrameSize(COMPACT_WIDTH_DP, COMPACT_HEIGHT_DP, showTitleBar = false)
            semantics.clickNode("profile tab") { it.hasAnyLabel(*Labels.libraryOrProfileTab) }
            semantics.waitUntil(timeoutMs = 20_000L, description = "profile account card loaded") {
                semantics.hasNode { it.containsAnyLabel("UID") } &&
                    semantics.hasNode { it.hasAnyLabel(*Labels.historyTitle) }
            }
            delay(1_800L)
            capturePair(SceneId.PROFILE_COMPACT)
        }

        if (SceneId.FEED_COMPACT in selected) {
            setFrameSize(COMPACT_WIDTH_DP, COMPACT_HEIGHT_DP, showTitleBar = false)
            semantics.clickNode("feed tab") { it.hasAnyLabel(*Labels.feedTab) }
            waitForFeedLoaded()
            capturePair(SceneId.FEED_COMPACT)
        }

        val needsAgentScenes = SceneId.AGENT_WIDE in selected ||
            SceneId.AGENT_RUNNING_COMPACT in selected ||
            SceneId.AGENT_ANSWER_COMPACT in selected

        if (needsAgentScenes) {
            val llmConfigured = container.settings.llmConfig.first().isConfigured
            check(llmConfigured) {
                "Agent scenes require LLM_BASE_URL / LLM_API_KEY / LLM_MODEL in local.properties or DataStore."
            }

            if (SceneId.AGENT_RUNNING_COMPACT in selected || SceneId.AGENT_ANSWER_COMPACT in selected) {
                setFrameSize(COMPACT_WIDTH_DP, COMPACT_HEIGHT_DP, showTitleBar = false)
                semantics.clickNode("search tab") { it.hasAnyLabel(*Labels.searchTab) }
                semantics.clickNode("ask agent FAB") { it.hasAnyLabel(*Labels.askAgent) }
                semantics.setTextAndSubmit("agent input", config.agentQuery) {
                    it.isEditable() && it.descriptions().none { d -> Labels.searchPlaceholder.any { p -> p in d } }
                }

                if (SceneId.AGENT_RUNNING_COMPACT in selected) {
                    semantics.waitUntil(timeoutMs = 20_000L, description = "agent running trace") {
                        semantics.hasNode { it.containsAnyLabel(*Labels.agentRunning) }
                    }
                    delay(2_200L)
                    if (config.theme.includesLight()) {
                        captureSingle(SceneId.AGENT_RUNNING_COMPACT, dark = false)
                    }
                    if (config.theme.includesDark()) {
                        if (!semantics.hasNode { it.containsAnyLabel(*Labels.agentRunning) }) {
                            semantics.clickNode("new agent session") { it.hasAnyLabel(*Labels.newSession) }
                            semantics.setTextAndSubmit("agent input", config.agentQuery) {
                                it.isEditable() && it.descriptions().none { d -> Labels.searchPlaceholder.any { p -> p in d } }
                            }
                            semantics.waitUntil(timeoutMs = 20_000L, description = "agent running trace (dark)") {
                                semantics.hasNode { it.containsAnyLabel(*Labels.agentRunning) }
                            }
                            delay(2_200L)
                        }
                        captureSingle(SceneId.AGENT_RUNNING_COMPACT, dark = true)
                    }
                }

                if (SceneId.AGENT_ANSWER_COMPACT in selected) {
                    semantics.waitUntil(timeoutMs = 90_000L, description = "agent answer completed") {
                        !semantics.hasNode { it.containsAnyLabel(*Labels.agentRunning) } &&
                            semantics.hasNode { it.containsAnyLabel(*Labels.agentSources) }
                    }
                    delay(1_500L)
                    capturePair(SceneId.AGENT_ANSWER_COMPACT)
                }
            }

            if (SceneId.AGENT_WIDE in selected) {
                container.settings.saveSidePanelOpen(SidePanelId.SearchAgent, true)
                setFrameSize(WIDE_WIDTH_DP, WIDE_HEIGHT_DP, showTitleBar = true)
                semantics.clickNode("search tab") { it.hasAnyLabel(*Labels.searchTab) }
                semantics.setTextAndSubmit("normal search bar", config.agentQuery) {
                    it.isEditable() && it.descriptions().any { d -> Labels.searchPlaceholder.any { p -> p in d } }
                }
                if (!semantics.hasNode { it.containsAnyLabel(*Labels.agentSources) }) {
                    semantics.setTextAndSubmit("agent side-panel input", config.agentQuery) {
                        it.isEditable() && it.descriptions().none { d -> Labels.searchPlaceholder.any { p -> p in d } }
                    }
                    semantics.waitUntil(timeoutMs = 90_000L, description = "wide agent answer") {
                        !semantics.hasNode { it.containsAnyLabel(*Labels.agentRunning) } &&
                            semantics.hasNode { it.containsAnyLabel(*Labels.agentSources) }
                    }
                }
                delay(1_800L)
                capturePair(SceneId.AGENT_WIDE)
            }
        }

        val needsVideoScenes = SceneId.VIDEO_WIDE in selected ||
            SceneId.IN_VIDEO_WIDE in selected ||
            SceneId.IN_VIDEO_COMPACT in selected

        if (needsVideoScenes) {
            returnToHome()
            val bvid = resolveVideoBvid()
            setFrameSize(WIDE_WIDTH_DP, WIDE_HEIGHT_DP, showTitleBar = true)
            openVideoAndSettle(bvid, seekTargetMillis = 15_000L)

            if (SceneId.VIDEO_WIDE in selected) {
                capturePair(SceneId.VIDEO_WIDE)
            }

            if (SceneId.IN_VIDEO_WIDE in selected || SceneId.IN_VIDEO_COMPACT in selected) {
                val llmConfigured = container.settings.llmConfig.first().isConfigured
                check(llmConfigured) {
                    "in-video-* scenes require LLM_BASE_URL / LLM_API_KEY / LLM_MODEL in local.properties or DataStore."
                }
                semantics.clickNode("find related") { it.hasAnyLabel(*Labels.findRelated) }
                semantics.waitUntil(timeoutMs = 90_000L, description = "related sheet finished") {
                    semantics.hasNode { it.containsAnyLabel(*Labels.relatedFollowUp) } &&
                        !semantics.hasNode { it.containsAnyLabel(*Labels.relatedRunning) }
                }
                delay(1_500L)
                if (SceneId.IN_VIDEO_WIDE in selected) {
                    capturePair(SceneId.IN_VIDEO_WIDE)
                }
                if (SceneId.IN_VIDEO_COMPACT in selected) {
                    setFrameSize(COMPACT_WIDTH_DP, COMPACT_HEIGHT_DP, showTitleBar = false)
                    delay(600L)
                    capturePair(SceneId.IN_VIDEO_COMPACT)
                }
            }
        }

        val needsListenScenes = SceneId.LISTEN_WIDE in selected ||
            SceneId.LYRICS_COMPACT in selected ||
            SceneId.SLEEP_TIMER_COMPACT in selected

        if (needsListenScenes) {
            returnToHome()
            val (listenBvid, preferredCueMillis) = resolveListenBvidAndCue()
            setFrameSize(WIDE_WIDTH_DP, WIDE_HEIGHT_DP, showTitleBar = true)
            openVideoAndSettle(listenBvid, seekTargetMillis = preferredCueMillis)
            semantics.clickNode("enter listen mode") { it.hasAnyLabel(*Labels.listenAction) }
            semantics.waitUntil(timeoutMs = 15_000L, description = "ListenScreen visible") {
                semantics.hasNode { it.hasAnyLabel(*Labels.listenTitle) }
            }
            delay(1_200L)

            if (SceneId.LISTEN_WIDE in selected) {
                capturePair(SceneId.LISTEN_WIDE)
            }

            if (SceneId.LYRICS_COMPACT in selected) {
                setFrameSize(COMPACT_WIDTH_DP, COMPACT_HEIGHT_DP, showTitleBar = false)
                semantics.clickNode("subtitle menu button") { it.hasAnyLabel(*Labels.subtitle) }
                semantics.waitUntil(timeoutMs = 10_000L, description = "subtitle track menu") {
                    semantics.hasNode { it.hasAnyLabel(*Labels.subtitleOff) }
                }
                semantics.clickNode("first subtitle track") { node ->
                    node.descriptions().isEmpty() &&
                        !node.hasAnyLabel(*Labels.subtitleOff) &&
                        node.config.contains(SemanticsProperties.Selected)
                }
                delay(1_000L)
                capturePair(SceneId.LYRICS_COMPACT)
                if (SceneId.SLEEP_TIMER_COMPACT in selected) {
                    semantics.clickNode("exit lyrics") { it.hasAnyLabel(*Labels.subtitle) }
                    delay(500L)
                }
            }

            if (SceneId.SLEEP_TIMER_COMPACT in selected) {
                setFrameSize(COMPACT_WIDTH_DP, COMPACT_HEIGHT_DP, showTitleBar = false)
                semantics.clickNode("sleep timer button") { it.hasAnyLabel(*Labels.sleepTimerOff) }
                semantics.clickNode("30 minutes option") { it.hasAnyLabel(*Labels.sleepTimer30) }
                delay(500L)
                capturePair(SceneId.SLEEP_TIMER_COMPACT)
            }
        }
    }

    private suspend fun waitForFeedLoaded() {
        semantics.waitUntil(timeoutMs = 25_000L, description = "feed items loaded") {
            container.dynamicFeedStore.home.first().isNotEmpty()
        }
        delay(2_200L)
    }

    private suspend fun returnToHome() {
        repeat(4) {
            if (semantics.hasNode { it.hasAnyLabel(*Labels.feedTab) }) return
            val clicked = runCatching {
                semantics.clickNode("back button", timeoutMs = 1_500L) { it.hasAnyLabel(*Labels.back) }
                true
            }.getOrDefault(false)
            if (!clicked) {
                semantics.sendKey(KeyEvent.VK_LEFT, modifiers = InputEvent.ALT_DOWN_MASK)
            }
            delay(350L)
        }
    }

    private suspend fun resolveVideoBvid(): String {
        config.videoBvid?.let { return it }
        val result = withContext(Dispatchers.IO) {
            container.searchRepository.searchVideos(config.agentQuery, page = 1, order = "")
        }
        val items = (result as? BiliResult.Ok)?.value?.items.orEmpty()
        return items.firstOrNull()?.bvid
            ?: error("Could not resolve a video BV ID for query '${config.agentQuery}'. Pass --video-bvid=BV...")
    }

    private suspend fun resolveListenBvidAndCue(): Pair<String, Long> {
        suspend fun findCueMillis(bvid: String): Long? {
            val detail = (container.videoRepository.getVideoDetail(bvid) as? BiliResult.Ok)?.value ?: return null
            val tracks = container.subtitleRepository.getTracks(bvid, detail.cid)
            val track = tracks.firstOrNull() ?: return null
            val cues = container.subtitleRepository.getCues(track.subtitleUrl)
            return cues.getOrNull(cues.size / 3)?.fromMillis
        }

        config.listenBvid?.let { bvid ->
            val cue = withContext(Dispatchers.IO) { findCueMillis(bvid) } ?: 180_000L
            return bvid to cue
        }

        val search = withContext(Dispatchers.IO) {
            container.searchRepository.searchVideos(config.listenQuery, page = 1, order = "")
        }
        val candidates = (search as? BiliResult.Ok)?.value?.items.orEmpty()
        check(candidates.isNotEmpty()) {
            "Could not resolve any video for --listen-query='${config.listenQuery}'. Pass --listen-bvid=BV..."
        }
        for (candidate in candidates.take(6)) {
            val cue = withContext(Dispatchers.IO) { findCueMillis(candidate.bvid) }
            if (cue != null) return candidate.bvid to cue
        }
        return candidates.first().bvid to 180_000L
    }

    private suspend fun openVideoAndSettle(bvid: String, seekTargetMillis: Long) {
        var connectedHandle: PlayerHandle? = null
        val connection = platform.playback.connect { connectedHandle = it }
        try {
            incomingLink.value = "https://www.bilibili.com/video/$bvid"
            semantics.waitUntil(timeoutMs = 30_000L, description = "playback duration ready for $bvid") {
                val queue = platform.playback.state.value.queue
                val duration = connectedHandle?.duration ?: 0L
                queue?.current?.bvid == bvid && !queue.enriching && duration > 0L
            }
            val player = checkNotNull(connectedHandle)
            val duration = player.duration.coerceAtLeast(1_000L)
            val seekPos = seekTargetMillis.coerceIn(1_000L, (duration - 2_000L).coerceAtLeast(1_000L))
            player.seekTo(seekPos)
            delay(1_200L)
            player.pause()
            delay(1_200L)
        } finally {
            connection.release()
        }
    }

    private suspend fun setFrameSize(targetW: Int, targetH: Int, showTitleBar: Boolean) {
        showTitleBarState.value = showTitleBar
        windowState.size = DpSize(targetW.dp, targetH.dp)
        delay(180L)
        val layer = findSkiaLayer(window) ?: return
        repeat(4) {
            val (lw, lh) = onEdt { layer.width to layer.height }
            if (lw == targetW && lh == targetH) return@repeat
            val curW = windowState.size.width.value.toInt()
            val curH = windowState.size.height.value.toInt()
            windowState.size = DpSize((curW + (targetW - lw)).dp, (curH + (targetH - lh)).dp)
            delay(180L)
        }
        delay(280L)
    }

    private suspend fun capturePair(scene: SceneId) {
        if (config.theme.includesLight()) captureSingle(scene, dark = false)
        if (config.theme.includesDark()) captureSingle(scene, dark = true)
    }

    private suspend fun captureSingle(scene: SceneId, dark: Boolean) {
        container.settings.saveThemeMode(if (dark) ThemeMode.Dark else ThemeMode.Light)
        delay(500L)
        val layer = checkNotNull(findSkiaLayer(window)) { "SkiaLayer not found on ComposeWindow" }
        val bitmap = onEdt { layer.screenshot() }
            ?: error("SkiaLayer.screenshot() returned null for ${scene.cliName}")
        val pngBytes = roundBitmapToPngBytes(bitmap, dark = dark)
        val suffix = if (dark) "dark" else "light"
        writeScreenshot(pngBytes, config.outDir, "${scene.cliName}-$suffix", config.format)
    }
}
