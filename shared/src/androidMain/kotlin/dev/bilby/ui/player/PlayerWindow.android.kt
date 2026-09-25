package dev.bilby.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal actual fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * 全屏的两件事:Activity 转到画面朝向 + 隐藏系统栏。两者都是 Activity 级的全局状态,离开
 * 这个 composable 必须还原,否则退到列表页还卡在横屏。
 *
 * **朝向跟着画面走,不是一律横屏。** 竖屏视频转横屏之后画面只能缩到中间一条,两侧全是黑边,
 * 等于全屏把可视面积改小了;竖屏视频的全屏就该竖着占满。用 SENSOR_* 而不是 USER_*,
 * 是为了让人仍能把设备翻过来(倒持、左右手)。
 */
@Composable
internal actual fun FullscreenEffect(
    isFullscreen: Boolean,
    isPortraitVideo: Boolean,
    fullBleed: Boolean,
    hideStatusBar: Boolean,
) {
    val activity = LocalContext.current.findActivity() ?: return
    val window = activity.window
    val insets = remember(window) { WindowCompat.getInsetsController(window, window.decorView) }
    // 只记录普通页面的基线。effect 会因视频比例变化重新创建,不能把全屏时已经设成
    // false 的图标明暗再次当成"退出全屏后的正常值"保存下来。
    val normalLightStatusBars = remember(window) { insets.isAppearanceLightStatusBars }
    val normalLightNavigationBars = remember(window) { insets.isAppearanceLightNavigationBars }

    DisposableEffect(isFullscreen, isPortraitVideo, fullBleed, hideStatusBar) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // 画面铺到边缘的那两种情形(全屏、横屏两栏的左栏)才允许它钻进刘海所在的短边,
        // 别的时候还原。**不在主题里全局开 shortEdges**:那样横屏下每一页的正文都可能压在
        // 挖孔底下,而 `Scaffold` 默认消费的是 systemBars,不含 displayCutout。
        val bleeding = isFullscreen || fullBleed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (bleeding) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }
        val focusListener = if (isFullscreen) {
            ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
                // 返回桌面/锁屏后系统可能重新显示 system bars;重新获得焦点时要把
                // 沉浸状态补回去,否则画面仍是横屏但底部突然多出导航栏。
                if (hasFocus) {
                    insets.isAppearanceLightStatusBars = false
                    insets.isAppearanceLightNavigationBars = false
                    insets.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    insets.hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        } else {
            null
        }
        focusListener?.let { window.decorView.viewTreeObserver.addOnWindowFocusChangeListener(it) }

        if (isFullscreen) {
            activity.requestedOrientation =
                if (isPortraitVideo) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // 控件浮在视频的黑底/渐变上,系统栏短暂被手势拉出来时也必须使用白色图标。
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = false
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else if (fullBleed) {
            // 朝向不锁:全屏之外这一页始终跟着设备转,横过来才排得成两栏。
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            // **导航栏一直留着**,简介和评论还要滚,手势条也得在。
            insets.show(WindowInsetsCompat.Type.navigationBars())
            if (hideStatusBar) {
                insets.hide(WindowInsetsCompat.Type.statusBars())
                insets.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                insets.show(WindowInsetsCompat.Type.statusBars())
                insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
            // 状态栏(收起时是下拉唤出的那一瞬间)压在画面上,要白图标。
            insets.isAppearanceLightStatusBars = false
            insets.isAppearanceLightNavigationBars = normalLightNavigationBars
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            insets.isAppearanceLightStatusBars = normalLightStatusBars
            insets.isAppearanceLightNavigationBars = normalLightNavigationBars
        }
        onDispose {
            focusListener?.let { window.decorView.viewTreeObserver.removeOnWindowFocusChangeListener(it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            insets.isAppearanceLightStatusBars = normalLightStatusBars
            insets.isAppearanceLightNavigationBars = normalLightNavigationBars
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
