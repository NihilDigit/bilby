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

/**
 * 桌面的画中画:主窗口本身缩成一块置顶的小画面。实现在桌面入口(WindowsPip),它持有窗口;
 * 组合树里没有窗口的地方(预览)为 null,画中画的入口不出现。
 */
interface DesktopPip : PipWindow {
    /** 在不在画中画里。快照状态,进出时重组。 */
    val active: Boolean

    /** [aspect] 是画面的宽高比,未知时按 16:9。 */
    fun enter(aspect: Float?)
}

val LocalDesktopPip = staticCompositionLocalOf<DesktopPip?> { null }

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
