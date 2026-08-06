package dev.bilby.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 临时基准,不是回归测试:把同一段人声样信号分别喂给 Media3 默认的 Sonic 和我们的 WSOLA,
 * 量一个和"金属音/毛刺"直接对应的客观指标,再决定要不要换后端。跑完即删。
 *
 * 指标是**谐波纯度**:输入是基频固定的谐波复合音(浊音段的物理模型)。理想的时间伸缩只改
 * 时长不改频谱,输出应当还是那组谐波。接缝处的相位不连续会把能量洒到谐波之间的频点上——
 * 这正是人耳听成"金属感""沙沙声"的东西。于是"非谐波能量 / 谐波能量"就是可听失真的代理量,
 * 越低越好。输入本身的读数作为基线(窗函数泄漏的地板)。
 */
class SpeedQualityBenchmark {

    @Test
    fun benchmark() {
        println("speed  algo    非谐波能量(dB)  处理耗时(ms)")
        for (speed in listOf(1.25f, 1.5f, 2.0f)) {
            for (vibrato in listOf(false, true)) {
                val input = voicedSignal(SECONDS, vibrato)
                val tag = if (vibrato) "(带颤音)" else "(稳态)  "
                if (speed == 1.25f) {
                    println("  输入基线$tag ${"%.1f".format(artifactDb(input))} dB")
                }
                val sonic = run(SonicAudioProcessor().apply { setSpeed(speed) }, input)
                val wsola = run(WsolaAudioProcessor().apply { setSpeed(speed) }, input)
                println(
                    "%.2fx  Sonic$tag %6.1f  %6d".format(speed, artifactDb(sonic.pcm), sonic.millis) +
                        "   |  WSOLA %6.1f  %6d".format(artifactDb(wsola.pcm), wsola.millis)
                )
            }
        }
    }

    private class Result(val pcm: ShortArray, val millis: Long)

    private fun run(processor: AudioProcessor, input: ShortArray): Result {
        processor.configure(FORMAT)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val out = ArrayList<Short>(input.size)
        val start = System.nanoTime()
        var fed = 0
        while (fed < input.size) {
            val n = minOf(CHUNK * CHANNELS, input.size - fed)
            val buf = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder())
            for (i in 0 until n) buf.putShort(input[fed + i])
            buf.flip()
            processor.queueInput(buf)
            drain(processor, out)
            fed += n
        }
        processor.queueEndOfStream()
        drain(processor, out)
        val millis = (System.nanoTime() - start) / 1_000_000
        return Result(out.toShortArray(), millis)
    }

    private fun drain(processor: AudioProcessor, sink: MutableList<Short>) {
        while (true) {
            val out = processor.output
            if (!out.hasRemaining()) return
            val shorts = out.asShortBuffer()
            while (shorts.hasRemaining()) sink.add(shorts.get())
            out.position(out.limit())
        }
    }

    /**
     * 浊音段模型:30 次谐波、1/k 衰减。[vibrato] 打开时基频有 ±3% 的慢速起伏——真人说话的基频
     * 从不恒定,而 Sonic 每块重估基音、WSOLA 用固定步长加搜索,差别正是在基频变动时拉开的。
     */
    private fun voicedSignal(seconds: Int, vibrato: Boolean): ShortArray {
        val frames = SAMPLE_RATE * seconds
        val pcm = ShortArray(frames * CHANNELS)
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toDouble() / SAMPLE_RATE
            val f0 = if (vibrato) F0 * (1 + 0.03 * sin(2 * PI * 4.0 * t)) else F0
            phase += 2 * PI * f0 / SAMPLE_RATE
            var v = 0.0
            for (k in 1..HARMONICS) v += sin(k * phase) / k
            val s = (v * 6000).roundToInt().coerceIn(-32768, 32767).toShort()
            repeat(CHANNELS) { pcm[i * CHANNELS + it] = s }
        }
        return pcm
    }

    /** 非谐波能量与谐波能量之比,dB。取输出正中间一段,避开首尾的淡入淡出。 */
    private fun artifactDb(pcm: ShortArray): Double {
        val frames = pcm.size / CHANNELS
        val start = (frames - FFT_SIZE) / 2
        val re = DoubleArray(FFT_SIZE)
        val im = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            // Hann 窗:矩形窗的旁瓣会淹掉我们要测的谐波间能量。
            val w = 0.5 * (1 - cos(2 * PI * i / (FFT_SIZE - 1)))
            re[i] = pcm[(start + i) * CHANNELS].toDouble() * w
        }
        fft(re, im)

        var harmonic = 0.0
        var other = 0.0
        val binPerHz = FFT_SIZE.toDouble() / SAMPLE_RATE
        val harmonicBins = (1..HARMONICS).flatMap { k ->
            val center = (k * F0 * binPerHz).roundToInt()
            (center - HARMONIC_HALF_WIDTH)..(center + HARMONIC_HALF_WIDTH)
        }.toSet()
        for (bin in 1 until FFT_SIZE / 2) {
            val power = re[bin] * re[bin] + im[bin] * im[bin]
            if (bin in harmonicBins) harmonic += power else other += power
        }
        return 10 * log10(other / harmonic)
    }

    /** 就地 radix-2 FFT。写在这里是因为只为这一个基准引一个 DSP 依赖不划算。 */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) {
                for (k in 0 until len / 2) {
                    val wr = cos(ang * k)
                    val wi = sin(ang * k)
                    val ur = re[i + k]
                    val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr
                    im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr
                    im[i + k + len / 2] = ui - vi
                }
            }
            len = len shl 1
        }
        hypot(0.0, 0.0) // 保持 import 不被清理
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val CHUNK = 1024
        const val SECONDS = 8
        const val F0 = 120.0
        const val HARMONICS = 30
        const val FFT_SIZE = 32768
        const val HARMONIC_HALF_WIDTH = 3
        val FORMAT = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT)
    }
}
