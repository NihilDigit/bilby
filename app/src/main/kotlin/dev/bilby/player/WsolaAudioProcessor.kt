package dev.bilby.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.bilby.BiliLog
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * WSOLA(Waveform Similarity Overlap-Add)时间伸缩,可替代 Media3 默认的 `SonicAudioProcessor`。
 *
 * **两者的差别在"接缝落在哪"**,不在"要不要交叉淡化"——两个算法都做重叠相加:
 *
 * - Sonic 走 PSOLA 路线:每块先用 AMDF 在 65–400 Hz 范围内估一个基音周期,再整周期地丢弃
 *   (加速)或复制(减速)。接缝天然落在基音周期边界上,对单人说话是最优解;代价是基音一旦
 *   估错(合唱、背景音乐压过人声、气声辅音),接缝就落在错误的相位上。
 * - WSOLA 不估基音:合成步长固定,分析位置在 ±搜索窗内滑动,取与上一块尾部**波形互相关最大**
 *   的那个位置。不依赖基音假设,所以对多声源同样稳定;代价是搜索窗有限,基音周期比搜索窗长的
 *   低沉男声可能对不齐整周期,残留一点"混响感"。
 *
 * 参数取 SoundTouch 的自动档——WSOLA 在语音上被调了二十年的一组经验值:序列长度随倍速线性
 * 收缩(倍速越高每块越短,接缝更密但每块内失真更少),搜索窗与之反向。
 *
 * **不支持变调**:[setSpeed] 只改速度。变调要在时间伸缩之外再做一次重采样,而 Bilby 的倍速
 * 菜单从来只调速度。
 */
@UnstableApi
class WsolaAudioProcessor : BaseAudioProcessor() {

    private var speed = 1f
    private var pendingSpeed = 1f

    /** 每块的合成长度、重叠长度、相似度搜索范围,单位都是"每声道帧数",[configureLengths] 里算。 */
    private var sequenceFrames = 0
    private var overlapFrames = 0
    private var seekFrames = 0

    /** 处理一块必须攒够的输入帧数:分析位置最远到 seekFrames,再往后读一整个序列。 */
    private var requiredFrames = 0

    /** 分析位置的推进步长。是小数,余数累加进 [skipRemainder],否则长时间播放会攒出可闻的漂移。 */
    private var nominalSkip = 0.0
    private var skipRemainder = 0.0

    private var channelCount = 1

    /** 待处理输入,交错排列。用 ShortArray 而不是 ByteBuffer:相关度计算要随机访问几十万次。 */
    private var pending = ShortArray(0)
    private var pendingFrames = 0

    /** 上一块输出的尾巴。下一块要找的就是"和它最像"的位置,WSOLA 的相似度基准就是它。 */
    private var midBuffer = ShortArray(0)

    /** 搜索用的单声道下混。相似度只用来定位,不必逐声道算——各声道本来就要保持同相位。 */
    private var monoPending = FloatArray(0)
    private var monoMid = FloatArray(0)

    /** 实测伸缩比。播放器要拿它把播放时长换算回媒体时长,用标称 speed 会有微小误差。 */
    private var consumedFrames = 0L
    private var producedFrames = 0L

    fun setSpeed(speed: Float) {
        require(speed > 0f)
        pendingSpeed = speed
    }

    fun setPitch(pitch: Float) {
        if (abs(pitch - 1f) > SPEED_EPSILON) BiliLog.w("WSOLA 不支持变调,忽略 pitch=$pitch")
    }

    /** 播放时长 → 媒体时长。2× 时 1 秒播放对应 2 秒媒体。 */
    fun getMediaDuration(playoutDuration: Long): Long =
        if (producedFrames > MIN_FRAMES_FOR_MEASURED_RATIO) {
            playoutDuration * consumedFrames / producedFrames
        } else {
            (playoutDuration * speed.toDouble()).toLong()
        }

    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        (durationUs / speed.toDouble()).toLong()

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        // 只接 16 位整型 PCM。DefaultAudioSink 在 float 输出、直通、tunneling 三种情况下本来就
        // 绕过整条处理链(shouldApplyAudioProcessorPlaybackParameters),这里拒掉不会让倍速静默失效。
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean =
        super.isActive() && abs(pendingSpeed - 1f) >= SPEED_EPSILON

    private var endOfStreamQueued = false

    /**
     * 基类判的是 `inputEnded && outputBuffer == EMPTY_BUFFER`,而收尾那一次即使一个字节都没写,
     * outputBuffer 也已经被 `replaceOutputBuffer` 换成了非 EMPTY 的实例——照基类判会永远结束不了,
     * 表现是每条播到最后卡在 STATE_BUFFERING 不进 STATE_ENDED。改判"还有没有没读走的输出"。
     */
    override fun isEnded(): Boolean = endOfStreamQueued && !hasPendingOutput()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channelCount)
        if (frames == 0) return
        appendInput(inputBuffer, frames)
        process(drainTail = false)
    }

    override fun onQueueEndOfStream() {
        endOfStreamQueued = true
        // 攒不满一整块的尾巴原样吐出。这段没被伸缩,长度不超过一个序列(几十毫秒),
        // 但不吐的话每条的结尾都会被吞掉,表现是"最后一句没说完"。
        process(drainTail = true)
    }

    override fun onFlush() {
        speed = pendingSpeed
        endOfStreamQueued = false
        configureLengths()
        pendingFrames = 0
        skipRemainder = 0.0
        consumedFrames = 0
        producedFrames = 0
        midBuffer = ShortArray(overlapFrames * channelCount)
        monoMid = FloatArray(max(overlapFrames, 1))
    }

    override fun onReset() {
        speed = 1f
        pendingSpeed = 1f
        endOfStreamQueued = false
        pending = ShortArray(0)
        midBuffer = ShortArray(0)
        monoPending = FloatArray(0)
        monoMid = FloatArray(0)
        pendingFrames = 0
    }

    private fun configureLengths() {
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        val rate = inputAudioFormat.sampleRate
        val clamped = speed.coerceIn(AUTO_TEMPO_LOW, AUTO_TEMPO_HIGH)
        val sequenceMs = AUTO_SEQUENCE_C + AUTO_SEQUENCE_K * clamped
        val seekMs = AUTO_SEEK_C + AUTO_SEEK_K * clamped

        sequenceFrames = (rate * sequenceMs / 1000f).roundToInt()
        seekFrames = (rate * seekMs / 1000f).roundToInt()
        // 一块里要放下淡入、淡出两段重叠,否则中间的复制段长度为负。
        overlapFrames = min((rate * OVERLAP_MS / 1000f).roundToInt(), sequenceFrames / 2 - 1)
        requiredFrames = sequenceFrames + seekFrames

        // 分析步长 = 合成步长 × 倍速;合成步长是每块真正贡献的输出长度(整块减去与下一块重叠的部分)。
        nominalSkip = speed.toDouble() * (sequenceFrames - overlapFrames)
    }

    private fun appendInput(inputBuffer: ByteBuffer, frames: Int) {
        val needed = (pendingFrames + frames) * channelCount
        if (pending.size < needed) pending = pending.copyOf(max(needed, pending.size * 2))
        inputBuffer.asShortBuffer().get(pending, pendingFrames * channelCount, frames * channelCount)
        inputBuffer.position(inputBuffer.position() + frames * 2 * channelCount)
        pendingFrames += frames
    }

    private fun process(drainTail: Boolean) {
        // 输出容量的上界:每块最多写一个完整序列,块数按分析步长估。宁可多要一点,
        // 真正写了多少由下面的 limit 决定。
        val blocks = if (nominalSkip <= 0.0) 0 else (pendingFrames / nominalSkip).toInt() + 1
        val capacityFrames = blocks * sequenceFrames + if (drainTail) pendingFrames else 0
        val byteBuffer = replaceOutputBuffer(capacityFrames * 2 * channelCount)
        val out = byteBuffer.asShortBuffer()

        while (true) {
            // 先还上一块欠下的推进量,再判断输入够不够。
            //
            // 顺序不能反:2× 时分析步长约 84ms,而一块只需要 65ms 输入,于是"跳过"经常比手头的
            // 输入还长。把 skip 放在块尾做、并用 coerceAtMost 截断,截掉的那部分就永远还不上了,
            // 表现是倍速越高实际播得越慢、音画越走越偏。这里把它记成欠账,下一批输入到了接着还。
            val debt = skipRemainder.toInt()
            if (debt > 0) {
                val paid = min(debt, pendingFrames)
                consume(paid)
                skipRemainder -= paid
                if (paid < debt) break
            }
            if (pendingFrames < requiredFrames) break

            val offset = findBestOverlapOffset()

            // 1) 与上一块的尾巴交叉淡化。用线性淡化而不是等功率:相似度搜索已经让两段高度相关,
            //    等功率曲线在相关信号上会鼓出一块。
            for (i in 0 until overlapFrames) {
                val w = i.toFloat() / overlapFrames
                val midBase = i * channelCount
                val inBase = (offset + i) * channelCount
                for (c in 0 until channelCount) {
                    val a = midBuffer[midBase + c].toFloat()
                    val b = pending[inBase + c].toFloat()
                    out.put((a * (1f - w) + b * w).toInt().toShort())
                }
            }
            // 2) 序列中段原样复制。
            val copyFrames = sequenceFrames - 2 * overlapFrames
            out.put(pending, (offset + overlapFrames) * channelCount, copyFrames * channelCount)
            producedFrames += overlapFrames + copyFrames

            // 3) 序列末尾留作下一块的相似度基准。
            System.arraycopy(
                pending, (offset + sequenceFrames - overlapFrames) * channelCount,
                midBuffer, 0, overlapFrames * channelCount,
            )

            // 4) 记下要前移多少,实际前移在下一轮开头。小数余数自然留在 skipRemainder 里。
            skipRemainder += nominalSkip
        }

        if (drainTail && pendingFrames > 0) {
            out.put(pending, 0, pendingFrames * channelCount)
            producedFrames += pendingFrames
            consumedFrames += pendingFrames
            pendingFrames = 0
        }

        // ShortBuffer 是视图,写它不会推进 ByteBuffer 的 position,得手动对齐再 flip。
        byteBuffer.position(out.position() * 2)
        byteBuffer.flip()
    }

    private fun consume(frames: Int) {
        if (frames <= 0) return
        System.arraycopy(
            pending, frames * channelCount,
            pending, 0, (pendingFrames - frames) * channelCount,
        )
        pendingFrames -= frames
        consumedFrames += frames
    }

    /**
     * 在 `[0, seekFrames)` 里找与 [midBuffer] 波形最像的起点,判据是归一化互相关
     * (除以候选段能量;不归一化的话相关度会被响的地方带偏,一路选到最大音量处)。
     *
     * 两遍搜索:先按 [COARSE_STEP] 粗扫,再在最佳点附近逐点细扫。全逐点扫在 48kHz 立体声下是
     * 每秒近千万次乘加,粗细两遍降一个数量级;相关曲线在基音周期尺度上是平滑的,粗扫不会跳过真峰。
     */
    private fun findBestOverlapOffset(): Int {
        if (overlapFrames == 0) return 0
        ensureMonoBuffers()
        downmix(pending, monoPending, seekFrames + overlapFrames)
        downmix(midBuffer, monoMid, overlapFrames)

        var bestOffset = 0
        var bestCorr = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < seekFrames) {
            val corr = correlation(i)
            if (corr > bestCorr) {
                bestCorr = corr
                bestOffset = i
            }
            i += COARSE_STEP
        }
        val from = max(0, bestOffset - COARSE_STEP + 1)
        val to = min(seekFrames - 1, bestOffset + COARSE_STEP - 1)
        for (j in from..to) {
            val corr = correlation(j)
            if (corr > bestCorr) {
                bestCorr = corr
                bestOffset = j
            }
        }
        return bestOffset
    }

    private fun correlation(offset: Int): Float {
        var dot = 0f
        var energy = 0f
        for (j in 0 until overlapFrames) {
            val b = monoPending[offset + j]
            dot += monoMid[j] * b
            energy += b * b
        }
        return dot / sqrt(energy + 1f)
    }

    private fun ensureMonoBuffers() {
        val needed = seekFrames + overlapFrames
        if (monoPending.size < needed) monoPending = FloatArray(needed)
        if (monoMid.size < overlapFrames) monoMid = FloatArray(overlapFrames)
    }

    private fun downmix(source: ShortArray, dest: FloatArray, frames: Int) {
        if (channelCount == 1) {
            for (i in 0 until frames) dest[i] = source[i].toFloat()
            return
        }
        for (i in 0 until frames) {
            var sum = 0f
            val base = i * channelCount
            for (c in 0 until channelCount) sum += source[base + c].toFloat()
            dest[i] = sum
        }
    }

    private companion object {
        /** SoundTouch 自动档:序列 125ms@0.5× → 50ms@2×,搜索窗 25ms@0.5× → 15ms@2×,重叠固定 8ms。 */
        const val AUTO_TEMPO_LOW = 0.5f
        const val AUTO_TEMPO_HIGH = 2.0f
        const val AUTO_SEQUENCE_K = -50f
        const val AUTO_SEQUENCE_C = 150f
        const val AUTO_SEEK_K = -20f / 3f
        const val AUTO_SEEK_C = 25f + 20f / 3f * 0.5f
        const val OVERLAP_MS = 8f

        const val COARSE_STEP = 4

        /** 低于这个数说明刚 flush 完没多久,实测比例还没意义,先用标称倍速。 */
        const val MIN_FRAMES_FOR_MEASURED_RATIO = 30_000L

        const val SPEED_EPSILON = 0.01f
    }
}
