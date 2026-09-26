package dev.bilby.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import dev.bilby.ui.DesktopLanguageStore
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import dev.bilby.AppBuild
import dev.bilby.AppContainer
import dev.bilby.DesktopPlatform
import dev.bilby.ui.BilbyRoot
import dev.bilby.ui.DesktopSystemActions
import dev.bilby.ui.LocalPlaybackHost
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.player.LocalWindowFullscreen
import dev.bilby.ui.player.LocalDesktopPip
import dev.bilby.update.DesktopAppUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import org.openani.mediamp.mpv.MpvMediampPlayer
import java.awt.Dimension
import kotlin.system.exitProcess
import java.io.File

fun main() {
    AppBuild.init(
        debug = System.getProperty("bilby.debug").toBoolean(),
        versionName = System.getProperty("bilby.version") ?: "0.0.0-dev",
        applicationId = "dev.bilby.desktop",
    )
    useBundledMpvRuntime()
    lateinit var container: AppContainer
    val updater = DesktopAppUpdater.create()
    val platform = DesktopPlatform(container = { container }, updater = updater)
    container = AppContainer(platform)

    // Coil 3 不会自动接上网络加载器,不注册的话 http(s) 图片静默不加载,同 Android 的 BilbyApplication。
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
    }

    val bounds = WindowBounds(File(platform.filesDir, "window.properties"))
    // 在第一帧之前读出并设好默认 Locale,界面一起来就是选定的语言。
    val languageStore = DesktopLanguageStore(File(platform.filesDir, "language.properties"))

    application {
        val windowState = remember { bounds.initialState() }
        var fullscreen: WindowsFullscreen? = null
        var pip: WindowsPip? = null
        val quit = {
            // 全屏时的尺寸是整块屏幕,记下来下次就开成一个没有边框位置的大窗口;小窗同理,
            // 下次会开成右下角一小块。
            if (fullscreen?.isFullscreen != true && pip?.active != true) bounds.save(windowState)
            // 关窗口就是结束这次观看:补上最后一条心跳再退出。
            platform.playback.stop()
            exitApplication()
        }
        // 更新脚本已经在等这个进程退出,由它替换文件后再把应用拉起来。
        LaunchedEffect(updater) { updater.exitRequests.collect { quit() } }
        Window(
            onCloseRequest = quit,
            title = "Bilby",
            // 标题栏与任务栏的图标。exe 与快捷方式的图标另由打包配置的 icon.ico 给。
            icon = appIcon,
            state = windowState,
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
            val systemActions = remember { DesktopSystemActions(languageStore) }
            // 桌面没有外部链接唤起,这条流只是 BilbyRoot 的入参,始终为空。
            val incomingLink = remember { MutableStateFlow<String?>(null) }
            CompositionLocalProvider(
                LocalSystemActions provides systemActions,
                LocalPlaybackHost provides platform.playback,
                LocalDesktopPip provides windowsPip,
                // 最大化着进去的,出来还是最大化,见 WindowsFullscreen。
                LocalWindowFullscreen provides { on ->
                    if (on) {
                        windowsFullscreen.enter(maximized = windowState.placement == WindowPlacement.Maximized)
                    } else {
                        windowsFullscreen.exit()
                    }
                },
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
                        BilbyRoot(container, incomingLink)
                    }
                } else {
                    SideEffect { composedLanguage = language }
                }
            }
        }
    }
    // 窗口关了、application 返回之后显式结束进程。mpv 与 JNA 留下的非守护线程会让 JVM 一直
    // 活着:实测关窗后进程还在,占着近 1GB 内存,下次启动又多一个。
    exitProcess(0)
}

/**
 * 安装包把 mpv 与 FFmpeg 的 DLL 放在资源目录的 mpv 子目录里(见 desktop/build.gradle.kts 的
 * bundledAppResources),这里指给 mediamp,免得它每次首次播放都把 DLL 解压到新的临时目录。
 *
 * 同步做,不放后台线程:它必须赶在第一个播放器创建之前,而播放器在打开视频页时才建,放后台
 * 就得另加一道等待。加载的是本地 DLL,耗时在一两百毫秒量级。
 */
private fun useBundledMpvRuntime() {
    val dir = System.getProperty("compose.application.resources.dir")?.let { File(it, "mpv") } ?: return
    if (!dir.resolve("mediampv.dll").isFile) return
    MpvMediampPlayer.prepareLibraries(dir.absolutePath, extractRuntimeLibrary = false)
}

private val appIcon: Painter? by lazy {
    val bytes = object {}.javaClass.getResourceAsStream("/app-icon.png")?.use { it.readBytes() } ?: return@lazy null
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}
