package dev.bilby.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.GraphicsEnvironment

/**
 * 当前桌面显示器逻辑全宽（dp）。AWT `bounds.width` 在 Windows HIDPI 下已折算为逻辑像素，
 * 窗口半屏/缩放时仍按整块屏幕宽度为基线折算视频网格列数。
 */
@Composable
actual fun rememberDisplayWidthDp(): Dp {
    val windowWidthDp = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    if (GraphicsEnvironment.isHeadless()) return maxOf(1920.dp, windowWidthDp)
    val screenWidthDp = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .bounds
            .width
            .dp
    }.getOrDefault(1920.dp)
    return maxOf(screenWidthDp, windowWidthDp)
}
