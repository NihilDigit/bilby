package dev.bilby.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import dev.bilby.ui.theme.LocalIsDarkTheme

/**
 * 应用级的 edge-to-edge 基线。
 *
 * 跟应用自己的明暗,不跟系统:设置里可以把明暗定死(见 [LocalIsDarkTheme])。普通页面的系统栏
 * 始终透明、图标颜色跟主题走,Android 10+ 的导航栏对比度遮罩一并由 [SystemBarStyle] 关闭。
 * 播放器全屏时只临时接管隐藏/显示和图标明暗,退出后恢复到这里的基线。
 */
@Composable
internal fun BilbyWindowChrome() {
    val activity = LocalContext.current as? ComponentActivity ?: return
    val darkTheme = LocalIsDarkTheme.current

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
