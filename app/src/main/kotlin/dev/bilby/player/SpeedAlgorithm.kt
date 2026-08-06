package dev.bilby.player

import android.content.Context
import android.provider.Settings
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor

/**
 * 倍速的时间伸缩算法。Media3 的默认是 [SonicAudioProcessor](见
 * `DefaultAudioSink.DefaultAudioProcessorChain`),[Wsola] 是我们自己实现的替代品,两者的算法
 * 差别写在 [WsolaAudioProcessor] 的类注释里。
 *
 * **Wsola 是默认,并且带透明回退**:跑不起来时自动落回 Sonic,播放不中断,见
 * [ResilientSpeedProcessor]。所以这个枚举描述的是"此刻谁在干活",不是一个用户选项——
 * 设置里不提供开关,回退已经覆盖了开关能解决的问题。
 */
enum class SpeedAlgorithm { Sonic, Wsola }

/**
 * A/B 试听用的强制开关,**只在 debug 构建生效**。
 *
 * 算法本身可以在运行中切换([ResilientSpeedProcessor] 就是这么做回退的),但"强制用哪个"要在
 * 建播放器时定死:处理链挂在 `DefaultAudioSink` 上,而 sink 由 `RenderersFactory` 在
 * `ExoPlayer` 构造时创建。改完 force-stop 重进即可:
 *
 * ```
 * adb shell settings put global bilby_speed_algorithm sonic   # 强制 Media3 默认的 Sonic
 * adb shell settings put global bilby_speed_algorithm wsola   # 强制 WSOLA(关掉回退)
 * adb shell settings delete global bilby_speed_algorithm      # 回到默认:WSOLA + 自动回退
 * adb shell am force-stop dev.bilby.debug
 * ```
 *
 * 用 `Settings.Global` 而不是 app 私有目录下的旗标文件:后者只能靠 `adb shell run-as` 写,
 * 而 ColorOS 的 SELinux 策略拒绝 `run-as`(`couldn't set SELinux security context`),
 * 在这台机器上根本切不了。`settings put` 走 settings provider,不受此限。
 */
@UnstableApi
internal object SpeedAlgorithmSelector {

    private const val SETTING_KEY = "bilby_speed_algorithm"

    /** null = 不强制,按默认走(WSOLA 优先 + 失败回退)。 */
    fun forcedAlgorithm(context: Context): SpeedAlgorithm? {
        if (!PlayerLog.isDebug) return null
        val value = runCatching {
            Settings.Global.getString(context.contentResolver, SETTING_KEY)
        }.getOrNull() ?: return null
        return when {
            value.equals("sonic", ignoreCase = true) -> SpeedAlgorithm.Sonic
            value.equals("wsola", ignoreCase = true) -> SpeedAlgorithm.Wsola
            else -> null
        }
    }
}

/**
 * 把倍速处理器接进 `DefaultAudioSink` 的处理链,取代默认链里的 Sonic。
 *
 * 静音跳过原样保留:它和时间伸缩是两件事,`DefaultAudioSink` 通过同一个 chain 接口同时驱动
 * 这两者,替掉整条链就得把静音跳过一起接回来,否则 `setSkipSilenceEnabled` 会变成空操作。
 *
 * **强制 Sonic 时走的也是这条链**(壳里只是永远选 Sonic),不是切回 Media3 的默认链。
 * A/B 要比的是算法,不是插入位置和缓冲行为,共用一条链才谈得上对照。
 */
@UnstableApi
internal class SpeedAudioProcessorChain(
    forced: SpeedAlgorithm? = null,
) : DefaultAudioSink.AudioProcessorChain {

    private val silenceSkipping = SilenceSkippingAudioProcessor()
    val speedProcessor = ResilientSpeedProcessor(forced)

    override fun getAudioProcessors(): Array<AudioProcessor> =
        arrayOf(silenceSkipping, speedProcessor)

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        speedProcessor.setSpeed(playbackParameters.speed)
        speedProcessor.setPitch(playbackParameters.pitch)
        // 返回值是播放器对外报告的实际参数。变调请求会让壳回退到 Sonic,而 Sonic 真的能变调,
        // 所以这里如实回传 pitch —— UI 上显示的和听到的必须是一回事。
        return playbackParameters
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkipping.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        speedProcessor.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = silenceSkipping.skippedFrames
}
