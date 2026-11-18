package dev.bilby.ui

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 桌面端应用级无边框全屏控制器（Windows `F11` / `Alt+Enter` / 订阅页顶栏全屏按钮）。
 * 非桌面平台或无窗口宿主的预览环境为 null，调用侧据此隐藏窗口级全屏入口。
 */
@Stable
interface AppFullscreenController {
    val isFullscreen: Boolean
    fun setFullscreen(fullscreen: Boolean)
    fun toggle() = setFullscreen(!isFullscreen)
}

val LocalAppFullscreen = staticCompositionLocalOf<AppFullscreenController?> { null }
