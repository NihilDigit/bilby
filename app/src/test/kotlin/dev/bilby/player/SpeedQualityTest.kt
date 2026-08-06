package dev.bilby.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 倍速后端为什么选 WSOLA 而不是 Media3 默认的 Sonic——判据和数字都在这里,顺带守住一条
 * 会真的回归的性质。
 *
 * 指标是**谐波纯度**:输入是基频固定的谐波复合音(浊音段的物理模型)。理想的时间伸缩只改
 * 时长不改频谱,输出应当还是那组谐波。接缝处的相位不连续会把能量洒到谐波之间的频点上——
 * 这正是人耳听成"金属感""沙沙声"的东西。于是"非谐波能量 / 谐波能量"就是可听失真的代理量,
 * 越低越好;输入自身的读数是基线(窗函数泄漏的地板)。
 *
 * 实测结论(见 [benchmark] 的打印):单人声上两者都是透明的,读数等于基线;**两个同时发声的
 * 基频**(人声压着 BGM 的模型)上 Sonic 崩掉——非谐波能量升到与谐波能量相当甚至更高,而
 * WSOLA 仍低 13–16 dB。原因在算法前提:Sonic 用 AMDF 估**一个**基音周期再整周期拼接,
 * 两个周期同时存在时这个前提不成立;WSOLA 不假设周期性,只找波形最像的位置。
 *
 * [双人声下 WSOLA 的失真必须显著低于谐波能量] 是唯一的断言。它守的不是"我写的逻辑",
 * 是"相似度搜索确实在起作用"——搜索一旦写坏(比如相关度没归一化、粗扫步长过大跳过真峰),
 * 这个数字会立刻掉到 0 dB 附近,而单元测试里的伸缩比断言完全看不出来。
 */
class SpeedQualityTest {

    @Test
    fun benchmark() {
        // 三种输入,分别对应三个假设:
        //   稳态单人声 —— 两个算法都该是透明的,差别应为零。
        //   带颤音单人声 —— 真人基频始终在动,Sonic 每块重估基音,这里是它的主场。
        //   双人声(人声 + BGM 的模型)—— 同时存在两个基频时 AMDF 只能估出一个,
        //     PSOLA 的前提破了;WSOLA 不假设周期性,理应在这里领先。这是"要不要换"的关键一格。
        for (case in Case.entries) {
            val input = voiced(case.f0s, case.vibrato)
            println("== ${case.label}  输入基线 ${"%.1f".format(artifactDb(input, case.f0s))} dB ==")
            for (speed in listOf(1.25f, 1.5f, 2.0f)) {
                val sonic = run(SonicAudioProcessor().apply { setSpeed(speed) }, input)
                val wsola = run(WsolaAudioProcessor().apply { setSpeed(speed) }, input)
                val wsolaDb = artifactDb(wsola.pcm, case.f0s)
                println(
                    "  %.2fx  Sonic %6.1f dB %4d ms   |  WSOLA %6.1f dB %4d ms".format(
                        speed,
                        artifactDb(sonic.pcm, case.f0s), sonic.millis,
                        wsolaDb, wsola.millis,
                    )
                )
                if (case == Case.TwoVoices) {
                    assertTrue(
                        "WSOLA 在双音源 $speed× 下的非谐波能量 ${wsolaDb}dB 过高,相似度搜索可能失效",
                        wsolaDb < -5.0,
                    )
                }
            }
        }
    }

    private enum class Case(val label: String, val f0s: List<Double>, val vibrato: Double) {
        Steady("稳态单人声 120Hz", listOf(120.0), 0.0),
        Vibrato("带颤音单人声 120Hz±0.5%", listOf(120.0), 0.005),
        TwoVoices("双人声 120Hz + 185Hz", listOf(120.0, 185.0), 0.0);

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

    /** 浊音段模型:每个基频 30 次谐波、1/k 衰减。[vibratoDepth] 是基频起伏的相对幅度。 */
    private fun voiced(f0s: List<Double>, vibratoDepth: Double): ShortArray {
        val frames = SAMPLE_RATE * SECONDS
        val pcm = ShortArray(frames * CHANNELS)
        val phases = DoubleArray(f0s.size)
        val gain = 6000.0 / f0s.size
        for (i in 0 until frames) {
            val t = i.toDouble() / SAMPLE_RATE
            var v = 0.0
            for ((n, f0) in f0s.withIndex()) {
                phases[n] += 2 * PI * f0 * (1 + vibratoDepth * sin(2 * PI * 4.0 * t)) / SAMPLE_RATE
                for (k in 1..HARMONICS) v += sin(k * phases[n]) / k
            }
            val s = (v * gain).roundToInt().coerceIn(-32768, 32767).toShort()
            repeat(CHANNELS) { pcm[i * CHANNELS + it] = s }
        }
        return pcm
    }

    /** 非谐波能量与谐波能量之比,dB。取输出正中间一段,避开首尾的淡入淡出。 */
    private fun artifactDb(pcm: ShortArray, f0s: List<Double>): Double {
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
        val harmonicBins = f0s.flatMap { f0 ->
            (1..HARMONICS).flatMap { k ->
                val center = (k * f0 * binPerHz).roundToInt()
                (center - HARMONIC_HALF_WIDTH)..(center + HARMONIC_HALF_WIDTH)
            }
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
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val CHUNK = 1024
        const val SECONDS = 8
        const val HARMONICS = 30
        const val FFT_SIZE = 32768
        const val HARMONIC_HALF_WIDTH = 5
        val FORMAT = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT)
    }
}
