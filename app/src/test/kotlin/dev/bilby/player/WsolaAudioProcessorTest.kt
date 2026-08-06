package dev.bilby.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

/**
 * 时间伸缩唯一能在 JVM 上验的、也是唯一真会错的地方:**输出长度和倍速的比值**。
 *
 * 音质好不好只能靠耳朵,写不出断言;但"2× 播出来的样本数是不是输入的一半"是硬指标,而且它
 * 是这类算法最容易静默出错的地方——分析步长比一块的输入还长时,截断掉的推进量如果不记账,
 * 表现就是倍速越高越对不上,而人耳只会觉得"好像没那么快",不会察觉是 bug。
 */
class WsolaAudioProcessorTest {

    @Test
    fun `倍速 2 倍时输出长度约为输入的一半`() = assertRatio(speed = 2.0f)

    @Test
    fun `倍速 1_25 倍`() = assertRatio(speed = 1.25f)

    @Test
    fun `倍速 1_5 倍`() = assertRatio(speed = 1.5f)

    @Test
    fun `慢放 0_75 倍`() = assertRatio(speed = 0.75f)

    @Test
    fun `倍速为 1 时处理器不激活`() {
        val processor = WsolaAudioProcessor()
        processor.configure(FORMAT)
        processor.setSpeed(1f)
        assertTrue(!processor.isActive)
    }

    private fun assertRatio(speed: Float) {
        val processor = WsolaAudioProcessor()
        processor.setSpeed(speed)
        processor.configure(FORMAT)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)

        val inputFrames = SAMPLE_RATE * 10
        var outputFrames = 0L
        // 分批喂,模拟解码器每次交一小块的真实节奏——一次性喂完会掩盖跨批次的状态错误。
        var fed = 0
        while (fed < inputFrames) {
            val chunk = minOf(CHUNK_FRAMES, inputFrames - fed)
            processor.queueInput(tone(fed, chunk))
            outputFrames += drain(processor)
            fed += chunk
        }
        processor.queueEndOfStream()
        outputFrames += drain(processor)
        assertTrue("处理完应当进入 ended", processor.isEnded)

        val actual = inputFrames.toDouble() / outputFrames
        assertEquals("实际伸缩比偏离目标倍速", speed.toDouble(), actual, 0.02)
    }

    private fun drain(processor: AudioProcessor): Long {
        var frames = 0L
        while (true) {
            val out = processor.output
            if (!out.hasRemaining()) return frames
            frames += out.remaining() / (2L * CHANNELS)
            out.position(out.limit())
        }
    }

    /** 220 Hz 正弦,带一个可辨的基音周期——相似度搜索在纯噪声上没有意义。 */
    private fun tone(startFrame: Int, frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 2 * CHANNELS).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val t = (startFrame + i).toDouble() / SAMPLE_RATE
            val value = (sin(2 * Math.PI * 220 * t) * 12000).toInt().toShort()
            repeat(CHANNELS) { buffer.putShort(value) }
        }
        buffer.flip()
        return buffer
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val CHUNK_FRAMES = 1024
        val FORMAT = AudioProcessor.AudioFormat(SAMPLE_RATE, CHANNELS, C.ENCODING_PCM_16BIT)
    }
}
