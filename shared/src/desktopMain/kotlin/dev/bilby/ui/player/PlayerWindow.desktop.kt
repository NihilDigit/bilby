package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/**
 * 把窗口切成全屏或还原。由桌面入口提供,它持有 WindowState;组合树里没有窗口的地方
 * (预览)什么也不做。
 */
val LocalWindowFullscreen = staticCompositionLocalOf<(Boolean) -> Unit> { {} }

/** 一个 1×1 的全透明光标。AWT 没有"无光标"这个常量,只能自己造一个看不见的。 */
private val BlankCursor = PointerIcon(
    Toolkit.getDefaultToolkit().createCustomCursor(
        BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
        Point(0, 0),
        "blank",
    ),
)

/**
 * 两种状态用同一个修饰符、只换参数:换成"藏时多挂一层、不藏时不挂"的写法,修饰符链的结构一变,
 * 下游的手势节点会被重建,正在识别的拖拽随之取消。
 */
internal actual fun Modifier.playerCursor(hidden: Boolean): Modifier =
    pointerHoverIcon(if (hidden) BlankCursor else PointerIcon.Default)

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
