package dev.bilby.ui.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.core.net.toUri
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
 * 系统亮度。**改的是设备级设置,不是窗口级的 `screenBrightness`。**
 *
 * 窗口级那种离开页面就还原,而看视频调暗屏幕的人多半是想让它一直暗着 —— 退出播放页
 * 又跳回原亮度会闪一下眼。代价是要 `WRITE_SETTINGS`。
 *
 * **`WRITE_SETTINGS` 不是运行时权限**:它是 `signature|appop` 级,`requestPermissions()`
 * 对它无效(API 23 起直接拒绝)。唯一路径是跳 [Settings.ACTION_MANAGE_WRITE_SETTINGS],
 * 那会整个跳到系统设置的「可修改系统设置」列表页,用户自己找到本应用拨开关再返回。
 *
 * **不动 `SCREEN_BRIGHTNESS_MODE`。** 自动亮度开着时写入仍然立即生效,之后系统会按环境光
 * 继续调整 —— 那正是"全局亮度"该有的样子。为了让数值钉死而把用户的自动亮度关掉,
 * 是拿一个设备级开关去换一次手势的确定性,不划算。
 */
internal class SystemBrightness(private val context: Context) {

    /**
     * 亮度的上限**不是固定 255**。AOSP 默认是 255,但厂商常改成 1023、2047 甚至 4095
     * (为了低亮度下有更细的档位)。写死 255 的话,在这类设备上一划就顶到天花板的四分之一。
     *
     * 框架把真值放在 `config_screenBrightnessSettingMaximum` 里。它是 internal 资源,
     * 拿不到 `R` 常量,但可以按名字查 —— 这是查资源不是反射类成员,R8 不受影响。
     */
    private val max: Int = runCatching {
        val res = android.content.res.Resources.getSystem()
        val id = res.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
        if (id != 0) res.getInteger(id) else DEFAULT_MAX
    }.getOrDefault(DEFAULT_MAX).coerceAtLeast(1)

    fun canWrite(): Boolean = Settings.System.canWrite(context)

    fun requestPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_WRITE_SETTINGS,
            "package:${context.packageName}".toUri(),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { BiliLog.w("跳转「可修改系统设置」失败", it) }
    }

    /** 0..1。读不到时按中间档,总比按 0 强 —— 后者会让第一次上划从全黑开始。 */
    fun current(): Float = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            .toFloat() / max
    }.getOrDefault(0.5f).coerceIn(0f, 1f)

    /** 下限取 1 而不是 0:0 在部分设备上是全黑,屏幕黑掉之后连"划回去"都看不见了。 */
    fun set(fraction: Float) {
        val value = (fraction.coerceIn(0f, 1f) * max).roundToInt().coerceIn(1, max)
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        }.onFailure { BiliLog.w("写系统亮度失败(value=$value)", it) }
    }

    private companion object {
        const val DEFAULT_MAX = 255
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

@Composable
internal fun rememberSystemBrightness(context: Context): SystemBrightness =
    remember(context) { SystemBrightness(context) }

@Composable
internal fun rememberMediaVolume(context: Context): MediaVolume =
    remember(context) { MediaVolume(context) }
