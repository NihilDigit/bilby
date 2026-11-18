package dev.bilby.ui.theme

import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontFamily

actual val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

actual val NoFontPadding: PlatformTextStyle? = PlatformTextStyle(includeFontPadding = false)

actual val CjkFontFamily: FontFamily = FontFamily.Default

actual val platformUsesStandardMotion: Boolean = false

@Composable
internal actual fun dynamicColorSchemes(): PaletteSchemes {
    val context = LocalContext.current
    return PaletteSchemes(dynamicLightColorScheme(context), dynamicDarkColorScheme(context))
}

/**
 * Android 上"减弱动效"表现为动画缩放被调到 0(开发者选项里的三个缩放,或无障碍的"移除动画")。
 * 读 `TRANSITION_ANIMATION_SCALE` 并监听系统设置变更:应用处于后台时用户改动无障碍开关，
 * 回到前台即可实时响应退化。
 */
@Composable
actual fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val resolver = remember(context) { context.contentResolver }
    fun readReduced(): Boolean = runCatching {
        Settings.Global.getFloat(
            resolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f,
        ) == 0f
    }.getOrDefault(false)

    var reduced by remember(resolver) { mutableStateOf(readReduced()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val next = readReduced()
                if (reduced != next) reduced = next
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.TRANSITION_ANIMATION_SCALE),
                false,
                observer,
            )
            true
        }.getOrDefault(false)
        onDispose {
            if (registered) runCatching { resolver.unregisterContentObserver(observer) }
        }
    }
    return reduced
}
