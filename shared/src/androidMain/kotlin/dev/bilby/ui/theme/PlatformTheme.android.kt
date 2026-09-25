package dev.bilby.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily

actual val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

actual val NoFontPadding: PlatformTextStyle? = PlatformTextStyle(includeFontPadding = false)

actual val CjkFontFamily: FontFamily = FontFamily.Default

@Composable
internal actual fun dynamicColorSchemes(): PaletteSchemes {
    val context = LocalContext.current
    return PaletteSchemes(dynamicLightColorScheme(context), dynamicDarkColorScheme(context))
}

/**
 * Android 上"减弱动效"表现为动画缩放被调到 0(开发者选项里的三个缩放,或无障碍的"移除动画")。
 * 读 `TRANSITION_ANIMATION_SCALE`:它管的正是窗口/页面级转场,和这里要退化的东西对得上。
 *
 * **组件那一侧不用自己处理。** Compose 的动画跑在 `AndroidUiDispatcher` 的
 * `MotionDurationScale` 下,那个值读的就是系统的动画时长缩放,关掉动画时所有 spring 自动
 * 变成瞬时。这里补的是另一个设置项,两者可以分别为 0。
 */
@Composable
actual fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    // 进程存活期间当它不变:这个开关在系统设置里,改完基本都会重进应用;为它挂一个
    // ContentObserver,换来的是每次重组都要读一次 Settings。
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.TRANSITION_ANIMATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
