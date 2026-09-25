package dev.bilby.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
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
import kotlinx.coroutines.flow.MutableStateFlow

fun main() {
    AppBuild.init(
        debug = System.getProperty("bilby.debug").toBoolean(),
        versionName = System.getProperty("bilby.version") ?: "0.0.0-dev",
        applicationId = "dev.bilby.desktop",
    )
    lateinit var container: AppContainer
    val platform = DesktopPlatform { container }
    container = AppContainer(platform)

    // Coil 3 不会自动接上网络加载器,不注册的话 http(s) 图片静默不加载,同 Android 的 BilbyApplication。
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .crossfade(true)
            .build()
    }

    application {
        val windowState = rememberWindowState(width = 1280.dp, height = 800.dp)
        Window(
            onCloseRequest = {
                // 关窗口就是结束这次观看:补上最后一条心跳再退出。
                platform.playback.stop()
                exitApplication()
            },
            title = "Bilby",
            state = windowState,
        ) {
            val systemActions = remember { DesktopSystemActions() }
            // 桌面没有外部链接唤起,这条流只是 BilbyRoot 的入参,始终为空。
            val incomingLink = remember { MutableStateFlow<String?>(null) }
            CompositionLocalProvider(
                LocalSystemActions provides systemActions,
                LocalPlaybackHost provides platform.playback,
                LocalWindowFullscreen provides { fullscreen ->
                    windowState.placement = if (fullscreen) WindowPlacement.Fullscreen else WindowPlacement.Floating
                },
            ) {
                BilbyRoot(container, incomingLink)
            }
        }
    }
}
