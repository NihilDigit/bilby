package dev.bilby.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 桌面端应用级无边框全屏控制器（Windows `F11` / `Alt+Enter` / 订阅页顶栏全屏按钮）。
 * 非桌面平台或无窗口宿主的预览环境为 null，调用侧据此隐藏窗口级全屏入口。
 */
@Stable
interface AppFullscreenController {
    val isFullscreen: Boolean
    val isAnyFullscreen: Boolean get() = isFullscreen
    fun setFullscreen(fullscreen: Boolean)
    fun exitAll() = setFullscreen(false)
    fun toggleWindowFullscreen() {
        if (isAnyFullscreen) exitAll() else setFullscreen(true)
    }
    fun registerPlayerFullscreenExit(onExit: () -> Unit): () -> Unit = {}
    fun toggle() = setFullscreen(!isFullscreen)
}

val LocalAppFullscreen = staticCompositionLocalOf<AppFullscreenController?> { null }

/**
 * 当播放页或直播间处于播放器内全屏时向窗口级全屏控制器注册退出回调，
 * 使 `F11` / `Alt+Enter` 能一并退出播放器全屏与应用级全屏。
 */
@Composable
fun RegisterPlayerFullscreenExit(
    isFullscreen: Boolean,
    onExit: () -> Unit,
) {
    val controller = LocalAppFullscreen.current ?: return
    val currentOnExit by rememberUpdatedState(onExit)
    DisposableEffect(controller, isFullscreen) {
        if (isFullscreen) {
            val unregister = controller.registerPlayerFullscreenExit { currentOnExit() }
            onDispose { unregister() }
        } else {
            onDispose {}
        }
    }
}
