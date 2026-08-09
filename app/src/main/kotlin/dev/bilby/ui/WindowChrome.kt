package dev.bilby.ui

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

/**
 * 应用级的 edge-to-edge 基线。
 *
 * [ComponentActivity.enableEdgeToEdge] 负责把窗口铺到系统栏下面,这里负责把这条规则固定
 * 成 Compose 生命周期的一部分:普通页面的系统栏始终透明、图标颜色跟主题走,Android 10+
 * 不再额外加一块导航栏对比度 scrim。播放器全屏时只临时接管隐藏/显示和图标明暗,
 * 退出后由播放器恢复到这里的基线。
 */
@Composable
internal fun BilbyWindowChrome() {
    val activity = LocalContext.current as? Activity ?: return
    val darkTheme = isSystemInDarkTheme()

    DisposableEffect(activity, darkTheme) {
        val window = activity.window
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 三键导航下也由内容和主题自己提供对比度,避免系统再叠一层半透明灰条;
            // 手势导航下则保持真正的 edge-to-edge。
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        onDispose { }
    }
}
