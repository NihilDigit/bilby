package dev.bilby.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 当前设备屏幕在当前方向下的逻辑全宽（dp）。分屏或自由窗口下仍反映整块屏幕宽度，
 * 供 [rememberVideoGridColumnCount] 按显示器全宽比例折算多列网格列数。
 */
@Composable
actual fun rememberDisplayWidthDp(): Dp {
    LocalConfiguration.current
    val density = LocalDensity.current
    val windowWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val displayWidthDp = with(density) { LocalContext.current.resources.displayMetrics.widthPixels.toDp() }
    return maxOf(displayWidthDp, windowWidthDp).coerceAtLeast(1.dp)
}
