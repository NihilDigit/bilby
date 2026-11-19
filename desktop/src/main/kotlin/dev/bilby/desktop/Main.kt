package dev.bilby.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bilby.ui.DesktopLanguageStore
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.awaitApplication
import coil3.SingletonImageLoader
import dev.bilby.AppBuild
import dev.bilby.AppContainer
import dev.bilby.BiliLog
import dev.bilby.DesktopPlatform
import dev.bilby.buildBilbyImageLoader
import dev.bilby.data.AppearancePrefs
import dev.bilby.ui.AppFullscreenController
import dev.bilby.ui.BilbyRoot
import dev.bilby.ui.DesktopSystemActions
import dev.bilby.ui.LocalAppFullscreen
import dev.bilby.ui.LocalPlaybackHost
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.LocalWindowVisible
import dev.bilby.ui.components.SkiaAnimatedImageDecoder
import dev.bilby.ui.player.LocalWindowFullscreen
import dev.bilby.ui.player.LocalDesktopPip
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.SystemReducedMotion
import dev.bilby.update.DesktopAppUpdater
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.openani.mediamp.mpv.MpvMediampPlayer
import java.awt.Dimension
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowStateListener
import java.io.File
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitAfterAotTraining()
    val versionName = System.getProperty("bilby.version") ?: "0.0.0-dev"
    val isPackaged = !System.getProperty("jpackage.app-path").isNullOrEmpty()
    val isDebug = System.getProperty("bilby.debug")?.toBooleanStrictOrNull()
        ?: (!isPackaged && versionName.endsWith("-dev"))
    val devLocalProps = if (isDebug && !isPackaged) loadDevLocalProperties() else null
    fun resolveDevLlmProp(sysKey: String, altSysKey: String, localKey: String): String {
        if (!isDebug || isPackaged) return ""
        return System.getProperty(sysKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getProperty(altSysKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: devLocalProps?.getProperty(localKey)?.trim().orEmpty()
    }
    AppBuild.init(
        debug = isDebug,
        versionName = versionName,
        applicationId = "dev.bilby.desktop",
        llmBaseUrl = resolveDevLlmProp("bilby.llm.baseUrl", "bilby.llm.base-url", "LLM_BASE_URL"),
        llmApiKey = resolveDevLlmProp("bilby.llm.apiKey", "bilby.llm.api-key", "LLM_API_KEY"),
        llmModel = resolveDevLlmProp("bilby.llm.model", "bilby.llm.model", "LLM_MODEL"),
    )
    useBundledMpvRuntime()
    lateinit var container: AppContainer
    val updater = DesktopAppUpdater.create()
    val platform = DesktopPlatform(container = { container }, updater = updater)
    val instance = SingleInstance.claimOrForward(platform.filesDir, args.toList()) ?: exitProcess(0)
    container = AppContainer(platform)

    // Coil 3 不会自动接上网络加载器,不注册的话 http(s) 图片静默不加载,同 Android 的 BilbyApplication。
    // 桌面端与 Android 共用 buildBilbyImageLoader 的内存/磁盘缓存与连接池配置,并挂上 SkiaAnimatedImageDecoder。
    SingletonImageLoader.setSafe { context ->
        buildBilbyImageLoader(
            context = context,
            diskCacheDir = platform.filesDir.resolve("coil_cache"),
            extraComponents = { add(SkiaAnimatedImageDecoder.Factory()) },
        )
    }

    SystemReducedMotion.ensureStarted()

    val bounds = WindowBounds(File(platform.filesDir, "window.properties"))
    // 在第一帧之前读出并设好默认 Locale,界面一起来就是选定的语言。
    val languageStore = DesktopLanguageStore(File(platform.filesDir, "language.properties"))

    runBlocking(AppMotionScale) {
        awaitApplication {
            val windowState = remember { bounds.initialState() }
        var fullscreen: WindowsFullscreen? = null
        var pip: WindowsPip? = null
        val exitWatchdogStarted = remember { AtomicBoolean(false) }
        val quit = {
            // 全屏时的尺寸是整块屏幕,记下来下次就开成一个没有边框位置的大窗口;小窗同理,
            // 下次会开成右下角一小块。
            if (fullscreen?.isFullscreen != true && pip?.active != true) bounds.save(windowState)
            // 退出看门狗：避免 mpv 停播或 Recomposer/Window 卸载阻塞导致应用挂死（尤其是更新重启时的 120s 超时）。
            if (exitWatchdogStarted.compareAndSet(false, true)) {
                startExitWatchdog()
            }
            // 关窗口就是结束这次观看:补上最后一条心跳再退出。
            runCatching { platform.playback.stop() }
                .onFailure { BiliLog.w("退出时停止播放失败", it) }
            exitApplication()
        }
        // 更新脚本已经在等这个进程退出,由它替换文件后再把应用拉起来。
        LaunchedEffect(updater) { updater.exitRequests.collect { quit() } }
        var windowVisible by remember { mutableStateOf(true) }
        BilbyWindow(
            onCloseRequest = quit,
            title = "Bilby",
            // 标题栏与任务栏的图标。exe 与快捷方式的图标另由打包配置的 icon.ico 给。
            icon = appIcon,
            state = windowState,
            windowVisible = windowVisible,
        ) {
            val normalMinimumSize = remember { Dimension(WindowBounds.MinWidthDp, WindowBounds.MinHeightDp) }
            LaunchedEffect(window) {
                window.minimumSize = normalMinimumSize
            }
            val windowsFullscreen = remember(window) { WindowsFullscreen(window).also { fullscreen = it } }
            val windowsPip = remember(window) {
                WindowsPip(
                    window = window,
                    fullscreen = windowsFullscreen,
                    isMaximized = { windowState.placement == WindowPlacement.Maximized },
                    normalMinimumSize = normalMinimumSize,
                ).also { pip = it }
            }
            var windowMinimized by remember(window) {
                mutableStateOf((window.extendedState and Frame.ICONIFIED) != 0)
            }
            var windowFocused by remember(window) { mutableStateOf(true) }
            DisposableEffect(window) {
                fun syncMinimized() {
                    windowMinimized = (window.extendedState and Frame.ICONIFIED) != 0
                }
                syncMinimized()
                val stateListener = WindowStateListener { syncMinimized() }
                val focusListener = object : WindowFocusListener {
                    override fun windowGainedFocus(e: WindowEvent?) {
                        windowFocused = true
                    }
                    override fun windowLostFocus(e: WindowEvent?) {
                        if (!isOwnedByWindow(e?.oppositeWindow, window)) {
                            windowFocused = false
                        }
                    }
                }
                val windowListener = object : WindowAdapter() {
                    override fun windowActivated(e: WindowEvent?) {
                        windowFocused = true
                    }
                    override fun windowDeactivated(e: WindowEvent?) {
                        if (!isOwnedByWindow(e?.oppositeWindow, window)) {
                            windowFocused = false
                        }
                    }
                    override fun windowIconified(e: WindowEvent?) {
                        windowMinimized = true
                    }
                    override fun windowDeiconified(e: WindowEvent?) {
                        windowMinimized = false
                    }
                }
                window.addWindowStateListener(stateListener)
                window.addWindowFocusListener(focusListener)
                window.addWindowListener(windowListener)
                onDispose {
                    window.removeWindowStateListener(stateListener)
                    window.removeWindowFocusListener(focusListener)
                    window.removeWindowListener(windowListener)
                }
            }
            var effectiveWindowVisible by remember(window) { mutableStateOf(true) }
            LaunchedEffect(windowMinimized, windowFocused, windowsPip.active) {
                if (windowsPip.active || (!windowMinimized && windowFocused)) {
                    effectiveWindowVisible = true
                } else if (windowMinimized) {
                    effectiveWindowVisible = false
                } else {
                    // 失焦但未最小化时短防抖，避免弹层、系统保存框等短暂焦点切换引起抖动。
                    delay(FocusLossEfficiencyDebounceMillis)
                    effectiveWindowVisible = false
                }
            }
            LaunchedEffect(effectiveWindowVisible) {
                windowVisible = effectiveWindowVisible
                WindowsEfficiencyMode.setEnabled(!effectiveWindowVisible)
                SkiaAnimatedImageDecoder.setWindowVisible(effectiveWindowVisible)
            }
            DisposableEffect(window) {
                onDispose {
                    windowVisible = true
                    WindowsEfficiencyMode.setEnabled(false)
                    SkiaAnimatedImageDecoder.setWindowVisible(true)
                }
            }
            var appFullscreen by remember { mutableStateOf(false) }
            var playerFullscreen by remember { mutableStateOf(false) }
            val playerExitCallbacks = remember { LinkedHashSet<() -> Unit>() }
            val desiredFullscreen = appFullscreen || playerFullscreen
            LaunchedEffect(desiredFullscreen, windowsPip.active) {
                if (windowsPip.active) return@LaunchedEffect
                if (desiredFullscreen) {
                    windowsFullscreen.enter(maximized = windowState.placement == WindowPlacement.Maximized)
                } else {
                    windowsFullscreen.exit()
                }
            }
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
            WindowsSmtcEffect(window, platform.playback)
            val systemActions = remember { DesktopSystemActions(languageStore, container.biliClient) }
            // 启动参数、二次启动转发与窗口拖放共用这一条外部链接入口。
            val incomingLink = remember { MutableStateFlow(args.firstNotNullOfOrNull(::extractIncomingLink)) }
            LaunchedEffect(window) {
                instance.listen { forwarded ->
                    SwingUtilities.invokeLater {
                        if (window.extendedState and Frame.ICONIFIED != 0) {
                            window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                        }
                        window.toFront()
                        forwarded.firstNotNullOfOrNull(::extractIncomingLink)?.let { link ->
                            incomingLink.value = link
                        }
                    }
                }
            }
            // 标题栏在 BilbyRoot 之外,主题要在这里再套一层。初值同 BilbyRoot 里那一层,两层读同一份设置。
            val appearance by container.settings.appearancePrefs.collectAsState(initial = AppearancePrefs())
            CompositionLocalProvider(
                LocalSystemActions provides systemActions,
                LocalPlaybackHost provides platform.playback,
                LocalDesktopPip provides windowsPip,
                LocalAppFullscreen provides appFullscreenController,
                LocalWindowFullscreen provides { on -> playerFullscreen = on },
                LocalWindowVisible provides effectiveWindowVisible,
            ) {
                BilbyTheme(appearance) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        WindowFrame(
                            title = "Bilby",
                            icon = appIcon,
                            // 全屏与小窗里整个窗口都是画面。
                            showTitleBar = !windowsFullscreen.isFullscreen && !windowsPip.active,
                        ) {
                            // 换语言:整棵界面拆掉一帧再建回来,等价于 Android 上换语言时 Activity 重建。
                            // 已经组合出来的文案不会因为默认 Locale 变了而自己重取,只有重新组合才会。
                            // 包在 SaveableStateProvider 里,拆掉时存下 rememberSaveable(返回栈、滚动位置),
                            // 建回来时原样恢复,人还停在设置页。
                            val saveableHolder = rememberSaveableStateHolder()
                            val language = languageStore.language
                            var composedLanguage by remember { mutableStateOf(language) }
                            if (composedLanguage == language) {
                                saveableHolder.SaveableStateProvider("root") {
                                    LinkDropTarget(onLinkDropped = { incomingLink.value = it }) {
                                        BilbyRoot(container, incomingLink, wrapTheme = false)
                                    }
                                }
                            } else {
                                SideEffect { composedLanguage = language }
                            }
                        }
                    }
                }
            }
        }
    }
    }
    // 窗口关了、application 返回之后显式结束进程。mpv 与 JNA 留下的非守护线程会让 JVM 一直
    // 活着:实测关窗后进程还在,占着近 1GB 内存,下次启动又多一个。
    exitProcess(0)
}

internal object AppMotionScale : MotionDurationScale {
    override val scaleFactor: Float
        get() = if (SystemReducedMotion.reduced) 0f else 1f
}

internal fun loadDevLocalProperties(): Properties? {
    val file = sequenceOf(
        File("local.properties"),
        File("..", "local.properties"),
    ).firstOrNull { it.isFile } ?: return null
    return runCatching {
        Properties().apply {
            file.inputStream().bufferedReader(Charsets.UTF_8).use(::load)
        }
    }.onFailure { BiliLog.w("读取 local.properties 失败", it) }.getOrNull()
}

/**
 * 安装包把 mpv 与 FFmpeg 的 DLL 放在资源目录的 mpv 子目录里(见 desktop/build.gradle.kts 的
 * bundledAppResources),这里指给 mediamp,免得它每次首次播放都把 DLL 解压到新的临时目录。
 *
 * 同步做,不放后台线程:它必须赶在第一个播放器创建之前,而播放器在打开视频页时才建,放后台
 * 就得另加一道等待。加载的是本地 DLL,耗时在一两百毫秒量级。
 */
internal fun useBundledMpvRuntime() {
    val dir = System.getProperty("compose.application.resources.dir")?.let { File(it, "mpv") } ?: return
    if (!dir.resolve("mediampv.dll").isFile) return
    MpvMediampPlayer.prepareLibraries(dir.absolutePath, extractRuntimeLibrary = false)
}

internal val appIcon: Painter? by lazy {
    val bytes = object {}.javaClass.getResourceAsStream("/app-icon.png")?.use { it.readBytes() } ?: return@lazy null
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

private const val FocusLossEfficiencyDebounceMillis = 600L
private const val ExitWatchdogGraceMillis = 5_000L
private const val ExitWatchdogHaltDelayMillis = 3_000L

private fun startExitWatchdog() {
    Thread({
        runCatching { Thread.sleep(ExitWatchdogGraceMillis) }
        BiliLog.w("应用退出超过 ${ExitWatchdogGraceMillis}ms 仍未结束，强制结束进程")
        // 另起守护线程做 halt 兜底，防止某个 shutdown hook 阻塞 exitProcess(0)。
        Thread({
            runCatching { Thread.sleep(ExitWatchdogHaltDelayMillis) }
            Runtime.getRuntime().halt(0)
        }, "bilby-exit-halt").apply {
            isDaemon = true
            start()
        }
        exitProcess(0)
    }, "bilby-exit-watchdog").apply {
        isDaemon = true
        start()
    }
}

private fun isOwnedByWindow(candidate: java.awt.Window?, root: java.awt.Window): Boolean {
    var current = candidate
    while (current != null) {
        if (current === root) return true
        current = current.owner
    }
    return false
}
