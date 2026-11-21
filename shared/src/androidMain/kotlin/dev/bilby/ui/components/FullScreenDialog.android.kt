package dev.bilby.ui.components

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.bilby.ui.player.findActivity

internal actual fun fullScreenDialogProperties(): DialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    // 还要铺到系统栏**底下**去。只给 usePlatformDefaultWidth 的话,窗口仍然避开
    // 状态栏和手势条,黑底铺不满,顶端会露出底层的评论列表 —— 真机上看得很清楚。
    decorFitsSystemWindows = false,
)

/**
 * 只有这个窗口铺进刘海所在的短边,不动 Activity 的窗口 —— 全局开 shortEdges 会让
 * 横屏下每一页的正文都可能压在挖孔底下。
 */
@Composable
internal actual fun DialogIntoDisplayCutout() {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(dialogWindow) {
        if (dialogWindow != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dialogWindow.attributes = dialogWindow.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        onDispose { }
    }
}

@Composable
internal actual fun ConfigureInputDialogWindow() {
    DialogIntoDisplayCutout()
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    val activity = LocalContext.current.findActivity()
    DisposableEffect(dialogWindow, activity) {
        if (dialogWindow != null) {
            dialogWindow.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // 宿主 Activity 处于横屏隐藏状态栏模式时同步隐藏对话窗口状态栏，避免弹出评论输入浮层把状态栏重新顶出来。
            val hostStatusBarVisible = activity?.window?.decorView?.let { decor ->
                ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.statusBars())
            } ?: true
            if (!hostStatusBarVisible) {
                val insets = WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
                insets.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insets.hide(WindowInsetsCompat.Type.statusBars())
            }
        }
        onDispose { }
    }
}
