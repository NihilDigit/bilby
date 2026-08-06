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
 * 倍速用哪个时间伸缩算法。
 *
 * Media3 的默认是 [SonicAudioProcessor](见 `DefaultAudioSink.DefaultAudioProcessorChain`),
 * [Wsola] 是我们自己实现的替代品,两者的算法差别写在 [WsolaAudioProcessor] 的类注释里。
 *
 * **算法只能在建播放器时定死**:处理链挂在 `DefaultAudioSink` 上,而 sink 由
 * `RenderersFactory` 在 `ExoPlayer` 构造时创建。想中途换算法只能重建播放器,而全 app 只有
 * 一个播放器实例(DESIGN 2.4b),重建就等于打断播放。所以这里不做运行时开关,只留一个
 * debug 构建下的旗标——A/B 试听时用它切,切完 force-stop 重进即可:
 *
 * ```
 * adb shell settings put global bilby_speed_algorithm wsola   # 换成 WSOLA
 * adb shell settings delete global bilby_speed_algorithm      # 换回 Sonic
 * adb shell am force-stop dev.bilby.debug
 * ```
 *
 * 用 `Settings.Global` 而不是 app 私有目录下的旗标文件:后者只能靠 `adb shell run-as` 写,
 * 而 ColorOS 的 SELinux 策略拒绝 `run-as`(`couldn't set SELinux security context`),
 * 在这台机器上根本切不了。`settings put` 走 settings provider,不受此限。
 */
enum class SpeedAlgorithm { Sonic, Wsola }

@UnstableApi
internal object SpeedAlgorithmSelector {

    private const val SETTING_KEY = "bilby_speed_algorithm"

    fun current(context: Context): SpeedAlgorithm {
        if (!PlayerLog.isDebug) return DEFAULT
        val value = runCatching {
            Settings.Global.getString(context.contentResolver, SETTING_KEY)
        }.getOrNull()
        return if (value.equals("wsola", ignoreCase = true)) SpeedAlgorithm.Wsola else DEFAULT
    }

    /**
     * 默认值。真机 A/B 之后定为 Sonic,理由见 notes/player-speed.md:B 站以人声解说为主,
     * 1.25×–2× 区间里 Sonic 的基音同步接缝在语音上就是最优解,WSOLA 的固定步长 + 相似度搜索
     * 反而多引入了一点周期对不齐的混响感。改这个默认值要重新做一遍试听。
     */
    private val DEFAULT = SpeedAlgorithm.Sonic
}

/**
 * 把 [WsolaAudioProcessor] 接进 `DefaultAudioSink` 的处理链,取代默认链里的 Sonic。
 *
 * 静音跳过原样保留:它和时间伸缩是两件事,`DefaultAudioSink` 通过同一个 chain 接口同时驱动
 * 这两者,替掉整条链就得把静音跳过一起接回来,否则 `setSkipSilenceEnabled` 会变成空操作。
 */
@UnstableApi
internal class WsolaAudioProcessorChain : DefaultAudioSink.AudioProcessorChain {

    private val silenceSkipping = SilenceSkippingAudioProcessor()
    private val wsola = WsolaAudioProcessor()

    override fun getAudioProcessors(): Array<AudioProcessor> = arrayOf(silenceSkipping, wsola)

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters {
        wsola.setSpeed(playbackParameters.speed)
        wsola.setPitch(playbackParameters.pitch)
        // 返回值是播放器对外报告的实际参数。WSOLA 不变调,如实报 pitch=1,
        // 不然 UI 上显示的和听到的会是两回事。
        return PlaybackParameters(playbackParameters.speed, 1f)
    }

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean {
        silenceSkipping.setEnabled(skipSilenceEnabled)
        return skipSilenceEnabled
    }

    override fun getMediaDuration(playoutDuration: Long): Long =
        wsola.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long = silenceSkipping.skippedFrames
}
