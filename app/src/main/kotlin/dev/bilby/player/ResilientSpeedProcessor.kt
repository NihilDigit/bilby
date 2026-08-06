package dev.bilby.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.bilby.BiliLog
import java.nio.ByteBuffer

/** 当前这条流上倍速实际由谁在做,以及(如果回退了)为什么。设置页显示这个。 */
data class SpeedProcessorState(
    val algorithm: SpeedAlgorithm = SpeedAlgorithm.Wsola,
    /** 非 null 表示本次是回退过来的,内容是具体原因,不是一句 "fallback"。 */
    val fallbackReason: String? = null,
)

/**
 * 倍速处理器的外壳:优先用 [WsolaAudioProcessor],跑不起来就**无声无息地**换成 Media3 默认的
 * [SonicAudioProcessor] 继续播。
 *
 * 为什么要这层壳:WSOLA 是我们自己写的代码,而它跑在音频链路的正中间——它一旦拒绝某个格式或
 * 在运行时抛异常,`DefaultAudioSink.configure` 会抛 `ConfigurationException`,最终变成
 * `ExoPlaybackException`,在 [AudioPlaybackService] 里被当成播放出错跳到下一条。**用户看到的是
 * 视频被跳过,而真实原因只是倍速算法不认识这个采样率。** 代价与收益完全不成比例,所以这里兜住。
 *
 * 三条规矩:
 *
 * 1. **回退是静默的**:不弹提示、不停播、不把倍速重置成 1×。Sonic 在纯人声上和 WSOLA 读数相同
 *    (见 `SpeedQualityTest`),回退后用户大概率听不出区别,没有打扰的理由。
 * 2. **回退必须留日志**(DESIGN 第 8 节)。日志里带上采样率、声道数、编码和失败点——只写
 *    "fallback" 的话,将来"倍速音质怎么变差了"这个问题没有任何抓手。
 * 3. **每次音频格式变化重新判定**,不是一次失败就全局关掉 WSOLA。换一个视频、换一条音轨,
 *    条件可能就满足了。运行时异常的黑名单只在**当前这条流**内有效,[configure] 时清零。
 *
 * **两个处理器始终都配置好**。切换只改 [active] 这一个引用,不需要临时补 configure——
 * 而在音频线程上做 configure 是要分配缓冲区的,那正是回退最不该添乱的时刻。
 */
@UnstableApi
class ResilientSpeedProcessor(
    /** 非 null 时强制用指定算法,只给真机 A/B 用(见 [SpeedAlgorithmSelector])。 */
    private val forced: SpeedAlgorithm? = null,
    /**
     * 日志出口。做成参数而不是直接调 [BiliLog],是因为"回退必须留下可定位的原因"本身就是一条
     * 要守的行为(DESIGN 第 8 节),测试要能断言它真的发生了;顺带把 `android.util.Log` 从这个
     * 纯逻辑类里摘出去,JVM 测试不必依赖 Android 框架的 mock。
     *
     * `fallback = true` 的才是被吞掉的失败,走 [BiliLog];否则只是陈述当前用了哪个算法,走
     * [PlayerLog]——把成功路径的陈述打成 warning 会把真正的失败淹掉。
     */
    private val log: (message: String, cause: Throwable?, fallback: Boolean) -> Unit = ::defaultLog,
) : AudioProcessor {

    private val wsola = WsolaAudioProcessor()
    private val sonic = SonicAudioProcessor()

    private var active: AudioProcessor = sonic

    private var speed = 1f
    private var pitch = 1f

    /** 当前格式下 WSOLA 不可用的原因;null 表示可用。[configure] 时重算。 */
    private var formatReason: String? = null

    /** 本条流上 WSOLA 运行时炸过。留到下次 [configure] 才清,避免每个缓冲区都重试一遍。 */
    private var runtimeReason: String? = null

    @Volatile
    var state: SpeedProcessorState = SpeedProcessorState(SpeedAlgorithm.Sonic)
        private set

    /**
     * 状态变化的回调,由 [PlayerFactory] 接成 StateFlow 给 UI。
     *
     * **在音频线程上调用**。只在算法真的变化时触发(一次流最多几次),不是每个缓冲区都调,
     * 所以在这条线程上做一次 StateFlow 赋值是可以接受的;实现方不要在里面做阻塞或分配大对象的事。
     */
    @Volatile
    var onStateChanged: ((SpeedProcessorState) -> Unit)? = null

    fun setSpeed(speed: Float) {
        this.speed = speed
        wsola.setSpeed(speed)
        sonic.setSpeed(speed)
    }

    /**
     * WSOLA 只做时间伸缩,不变调。Bilby 的倍速菜单从来只调速度,所以正常情况下 pitch 恒为 1;
     * 真收到变调请求就当作"WSOLA 干不了这件事"回退给 Sonic,而不是默默把它忽略掉。
     */
    fun setPitch(pitch: Float) {
        this.pitch = pitch
        sonic.setPitch(pitch)
    }

    /** 播放时长 → 媒体时长。必须问当前生效的那一个,回退之后问错人会让进度条走偏。 */
    fun getMediaDuration(playoutDuration: Long): Long =
        if (active === wsola) wsola.getMediaDuration(playoutDuration)
        else sonic.getMediaDuration(playoutDuration)

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // 新格式 = 新机会:上一条流里的运行时失败不带过来。
        runtimeReason = null
        formatReason = WsolaAudioProcessor.unsupportedReason(inputAudioFormat)

        val outputFormat = sonic.configure(inputAudioFormat)
        if (formatReason == null) {
            // 两个都配置好,之后切换只是换个引用。configure 会分配缓冲区,
            // 不能留到音频线程上真出事的那一刻再做。
            runCatching { wsola.configure(inputAudioFormat) }
                .onFailure { formatReason = "WSOLA configure 失败:${it.javaClass.simpleName}" }
        }
        chooseActive(inputAudioFormat)
        return outputFormat
    }

    override fun isActive(): Boolean = active.isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (active !== wsola) {
            active.queueInput(inputBuffer)
            return
        }
        try {
            wsola.queueInput(inputBuffer)
        } catch (t: Throwable) {
            // 走到这里说明 WSOLA 有 bug,而不是格式不支持——但用户不该为此丢一条视频。
            runtimeReason = "WSOLA 运行时异常:${t.javaClass.simpleName}: ${t.message}"
            switchToSonic(t)
            // 这个缓冲区已经被消费了一部分,剩下多少喂多少。丢掉的不超过一个解码器输出块
            // (几十毫秒),比抛出去让整条视频被跳过便宜得多。
            if (inputBuffer.hasRemaining()) sonic.queueInput(inputBuffer)
        }
    }

    override fun queueEndOfStream() = active.queueEndOfStream()

    override fun getOutput(): ByteBuffer = active.output

    override fun isEnded(): Boolean = active.isEnded

    override fun flush(streamMetadata: AudioProcessor.StreamMetadata) {
        // 两个都 flush:不在用的那个也要保持在可随时接手的状态。
        sonic.flush(streamMetadata)
        if (formatReason == null) wsola.flush(streamMetadata)
        chooseActive(null)
    }

    @Deprecated("Deprecated in Java", ReplaceWith("flush(AudioProcessor.StreamMetadata.DEFAULT)"))
    override fun flush() = flush(AudioProcessor.StreamMetadata.DEFAULT)

    override fun reset() {
        wsola.reset()
        sonic.reset()
        active = sonic
        speed = 1f
        pitch = 1f
        formatReason = null
        runtimeReason = null
    }

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        active.getDurationAfterProcessorApplied(durationUs)

    /**
     * 决定这一刻由谁干活。[format] 只用于日志,为 null 表示这次是 flush 触发的重新判定
     * (格式没变,原因也已经在 [configure] 时记过一次了,不重复刷屏)。
     */
    private fun chooseActive(format: AudioProcessor.AudioFormat?) {
        val reason = when {
            forced == SpeedAlgorithm.Sonic -> null // 强制 Sonic 不算回退,不记原因
            forced == SpeedAlgorithm.Wsola -> null
            formatReason != null -> formatReason
            runtimeReason != null -> runtimeReason
            !pitchIsNeutral() -> "请求了变调(pitch=$pitch),WSOLA 不支持"
            else -> null
        }
        val useWsola = when (forced) {
            SpeedAlgorithm.Sonic -> false
            SpeedAlgorithm.Wsola -> true
            null -> reason == null
        }
        active = if (useWsola) wsola else sonic

        val next = SpeedProcessorState(
            algorithm = if (useWsola) SpeedAlgorithm.Wsola else SpeedAlgorithm.Sonic,
            fallbackReason = reason,
        )
        if (next == state) return
        state = next
        onStateChanged?.invoke(next)
        if (reason != null && format != null) {
            log(
                "倍速回退到 Sonic:$reason" +
                    "(${format.sampleRate}Hz ${format.channelCount}ch encoding=${format.encoding})",
                null,
                true,
            )
        } else if (format != null) {
            log("倍速算法: ${next.algorithm}", null, false)
        }
    }

    private fun pitchIsNeutral() = kotlin.math.abs(pitch - 1f) < 0.01f

    /** 运行时失败的收尾:换人 + 记账。日志走 BiliLog,这是被吞掉的失败。 */
    private fun switchToSonic(cause: Throwable) {
        active = sonic
        state = SpeedProcessorState(SpeedAlgorithm.Sonic, runtimeReason)
        onStateChanged?.invoke(state)
        log("倍速回退到 Sonic(运行时):$runtimeReason", cause, true)
    }

    private companion object {
        fun defaultLog(message: String, cause: Throwable?, fallback: Boolean) {
            when {
                !fallback -> PlayerLog.d(message)
                cause != null -> BiliLog.w(message, cause)
                else -> BiliLog.w(message)
            }
        }
    }
}
