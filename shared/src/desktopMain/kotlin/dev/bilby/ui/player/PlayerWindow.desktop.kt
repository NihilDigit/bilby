package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 把窗口切成全屏或还原。由桌面入口提供,它持有 WindowState;组合树里没有窗口的地方
 * (预览)什么也不做。
 */
val LocalWindowFullscreen = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

@Composable
internal actual fun KeepScreenOn(enabled: Boolean) = Unit

@Composable
internal actual fun FullscreenEffect(
    isFullscreen: Boolean,
    isPortraitVideo: Boolean,
    fullBleed: Boolean,
    hideStatusBar: Boolean,
) {
    val setFullscreen = LocalWindowFullscreen.current
    DisposableEffect(isFullscreen) {
        setFullscreen(isFullscreen)
        onDispose { if (isFullscreen) setFullscreen(false) }
    }
}
