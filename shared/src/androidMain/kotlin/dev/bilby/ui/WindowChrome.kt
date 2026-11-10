package dev.bilby.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import dev.bilby.ui.theme.LocalIsDarkTheme

/**
 * 应用级的 edge-to-edge 基线。
 *
 * 跟应用自己的明暗,不跟系统:设置里可以把明暗定死(见 [LocalIsDarkTheme])。普通页面的系统栏
 * 始终透明、图标颜色跟主题走,Android 10+ 的导航栏对比度遮罩一并由 [SystemBarStyle] 关闭。
 * 播放器全屏时只临时接管隐藏/显示和图标明暗,退出后恢复到这里的基线。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BilbyWindowChrome() {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val darkTheme = LocalIsDarkTheme.current
    val view = LocalView.current
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val imeTargetBottom = WindowInsets.imeAnimationTarget.getBottom(density)
    val imeVisible = WindowInsets.isImeVisible

    // Compose foundation-layout 的 InsetsListener 在 Android 预测式返回左滑收起输入法
    // (ImeBackAnimationController, animation.durationMillis == -1L) 结束时,onEnd 只在
    // durationMillis > 0L 时调 composeInsets.update(insets),导致 imeAnimationTarget 与
    // isImeVisible 已归零、WindowInsets.ime 却卡在键盘高度。检测到这种失步时主动重发一次
    // insets 分发,把 WindowInsets.ime 同步归零。
    LaunchedEffect(view, imeBottom, imeTargetBottom, imeVisible) {
        if (!imeVisible && imeTargetBottom == 0 && imeBottom > 0) {
            view.requestApplyInsets()
        }
    }

    DisposableEffect(activity, darkTheme) {
        val barStyle = if (darkTheme) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        activity.enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        onDispose { }
    }
}
