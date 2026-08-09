package dev.bilby.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.bilby.BiliLog
import kotlin.math.roundToInt

/** 纵划在调什么。左半屏亮度,右半屏音量。 */
internal enum class VerticalAdjust { Brightness, Volume }

/** 正在进行的手势。null 表示没有;横划与纵划在第一段位移里定下,之后不再改判。 */
internal sealed interface PlayerGesture {
    /** 横划改进度。[startPositionMillis] 是按下那一刻的位置,位移都从它算起。 */
    data class Seek(val startPositionMillis: Long) : PlayerGesture

    /** 纵划改亮度或音量。[startValue] 同理,是按下那一刻的值(0..1)。 */
    data class Adjust(val kind: VerticalAdjust, val startValue: Float) : PlayerGesture
}

/**
 * 播放页的亮度。**只改这个窗口的 `screenBrightness`,不写 `Settings.System`。**
 *
 * 这一条改过一次:原来调的是设备级亮度,理由是"看视频调暗的人多半想让它一直暗着"。
 * 代价太大了 —— `WRITE_SETTINGS` 是 `signature|appop` 级,`requestPermissions()` 对它无效,
 * 唯一路径是把用户整个甩进系统设置的「可修改系统设置」列表页自己找到本应用。为了一个手势
 * 让人离开应用一趟,而且从此这个应用能改全局亮度,不划算。
 *
 * 窗口级亮度不需要任何权限,离开播放页自动还原,而"还原"本来就是对的:调暗是为了看这一条
 * 视频,不是为了改设备。
 */
internal class WindowBrightness(private val window: Window?) {

    /**
     * 0..1。窗口没有覆盖值(`BRIGHTNESS_OVERRIDE_NONE`,即 -1)时读系统当前亮度当起点——
     * 否则第一次上划会从 0 开始,屏幕先黑一下。读系统亮度不需要权限,写才需要。
     */
    fun current(): Float {
        val override = window?.attributes?.screenBrightness ?: BRIGHTNESS_NONE
        if (override >= 0f) return override.coerceIn(0f, 1f)
        return runCatching {
            val resolver = window?.context?.contentResolver ?: return@runCatching 0.5f
            Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS).toFloat() / systemMax
        }.getOrDefault(0.5f).coerceIn(0f, 1f)
    }

    /** 下限不取 0:全黑之后连"划回去"都看不见。 */
    fun set(fraction: Float) {
        val window = window ?: return
        window.attributes = window.attributes.apply {
            screenBrightness = fraction.coerceIn(MIN_BRIGHTNESS, 1f)
        }
    }

    /** 交还给系统。离开播放页时调。 */
    fun release() {
        val window = window ?: return
        window.attributes = window.attributes.apply { screenBrightness = BRIGHTNESS_NONE }
    }

    /**
     * 系统亮度的上限**不是固定 255**:厂商常改成 1023、2047 甚至 4095(为了低亮度下有更细的
     * 档位)。写死 255 的话,在这类设备上读出来的起点只有真实值的四分之一。
     * 框架把真值放在 internal 资源 `config_screenBrightnessSettingMaximum` 里,按名字查得到
     * —— 这是查资源不是反射类成员,R8 不受影响。
     */
    private val systemMax: Int = runCatching {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        if (id != 0) res.getInteger(id) else DEFAULT_MAX
    }.getOrDefault(DEFAULT_MAX).coerceAtLeast(1)

    private companion object {
        const val DEFAULT_MAX = 255
        const val BRIGHTNESS_NONE = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        const val MIN_BRIGHTNESS = 0.02f
    }
}

/** 媒体音量。走 [AudioManager]，是真正的系统音量,不是播放器内部的增益。 */
internal class MediaVolume(context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    /** 档位通常只有 15 级,所以调节要按 0..1 的连续量算,最后一步才取整成档位。 */
    private val max: Int = (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15)
        .coerceAtLeast(1)

    fun current(): Float =
        (audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat() / max

    /**
     * 不带 `FLAG_SHOW_UI`:系统那条音量条会盖在画面上,而我们自己已经画了一个浮层,
     * 两个一起出现是重复的噪声。
     *
     * 勿扰模式下 `setStreamVolume` 会抛 SecurityException —— 属于"被吞掉的失败",
     * 按 DESIGN 8 留一行日志。
     */
    fun set(fraction: Float) {
        val index = (fraction.coerceIn(0f, 1f) * max).roundToInt().coerceIn(0, max)
        runCatching {
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }.onFailure { BiliLog.w("设置媒体音量失败(index=$index),可能处于勿扰模式", it) }
    }
}

/** 亮度跟着播放页的生命周期走:离开时把覆盖值交还系统,否则整个应用都停在调暗的那一档。 */
@Composable
internal fun rememberWindowBrightness(): WindowBrightness {
    val window = (LocalContext.current as? Activity)?.window
    val brightness = remember(window) { WindowBrightness(window) }
    DisposableEffect(brightness) { onDispose { brightness.release() } }
    return brightness
}

@Composable
internal fun rememberMediaVolume(context: Context): MediaVolume =
    remember(context) { MediaVolume(context) }
