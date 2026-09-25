package dev.bilby.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 回退壳的价值全在"出事的时候"——正常路径由 [WsolaAudioProcessorTest] 覆盖。这里只测三件
 * 会真的发生、而且一旦坏掉就完全静默的事:
 *
 * 1. WSOLA 不认识的格式**不能抛出去**。抛出去的后果是 `DefaultAudioSink.configure` 变成
 *    `ExoPlaybackException`,在 [AudioPlaybackService] 里被当成播放失败跳到下一条——
 *    用户看到的是"这个视频打不开"。
 * 2. 回退之后**倍速仍然生效**。只保住"不崩"但把 2× 播成 1× 是更糟的失败,因为它看起来像正常。
 * 3. 回退原因**必须留下**(日志里带得上采样率/编码,而不是一句 "fallback"),而且换个格式要能
 *    重新用回 WSOLA——不是一次失败就永久降级。
 */
class ResilientSpeedProcessorTest {

    private val fallbackLogs = mutableListOf<String>()

    private fun processor(forced: SpeedAlgorithm? = null) = ResilientSpeedProcessor(
        forced = forced,
        log = { message, _, fallback -> if (fallback) fallbackLogs += message },
    )

    @Test
    fun `WSOLA 不支持的编码不抛异常而是回退到 Sonic`() {
        val processor = processor()
        processor.setSpeed(2f)

        // float PCM:WSOLA 只接 16 位整型,Sonic 两种都接。
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))

        assertEquals(SpeedAlgorithm.Sonic, processor.state.algorithm)
        assertNotNull("回退必须留下原因", processor.state.fallbackReason)
        assertTrue(processor.state.fallbackReason!!.contains("16 位"))

        // 日志要能定位:光有"回退了"三个字,将来查"倍速音质怎么变差了"无从下手。
        assertEquals(1, fallbackLogs.size)
        val line = fallbackLogs.single()
        assertTrue("日志缺少采样率: $line", line.contains("48000Hz"))
        assertTrue("日志缺少声道数: $line", line.contains("2ch"))
        assertTrue("日志缺少原因: $line", line.contains("16 位"))
    }

    @Test
    fun `回退之后倍速依然生效`() {
        val processor = processor()
        processor.setSpeed(2f)
        val format = AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT)
        processor.configure(format)
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(SpeedAlgorithm.Sonic, processor.state.algorithm)

        val inputFrames = 48_000 * 4
        var outputFrames = 0L
        var fed = 0
        while (fed < inputFrames) {
            val chunk = minOf(1024, inputFrames - fed)
            processor.queueInput(floatTone(fed, chunk))
            outputFrames += drain(processor)
            fed += chunk
        }
        processor.queueEndOfStream()
        outputFrames += drain(processor)

        // Sonic 的 float 通路每帧 4 字节,drain 按 4 字节算过了。
        val ratio = inputFrames.toDouble() / outputFrames
        assertEquals("回退到 Sonic 后倍速没生效", 2.0, ratio, 0.05)
    }

    @Test
    fun `换成支持的格式后重新用回 WSOLA`() {
        val processor = processor()
        processor.setSpeed(1.5f)
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(SpeedAlgorithm.Sonic, processor.state.algorithm)

        // 下一条流是普通的 16 位 PCM:上一条的失败不该带过来。
        processor.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))

        assertEquals(SpeedAlgorithm.Wsola, processor.state.algorithm)
        assertNull(processor.state.fallbackReason)
    }

    @Test
    fun `请求变调时回退给能变调的 Sonic`() {
        val processor = processor()
        processor.setSpeed(1.5f)
        processor.setPitch(1.2f)
        processor.configure(AudioProcessor.AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))

        assertEquals(SpeedAlgorithm.Sonic, processor.state.algorithm)
        assertTrue(processor.state.fallbackReason!!.contains("变调"))
    }

    private fun drain(processor: AudioProcessor): Long {
        var frames = 0L
        while (true) {
            val out = processor.output
            if (!out.hasRemaining()) return frames
            frames += out.remaining() / (4L * 2) // float PCM,双声道
            out.position(out.limit())
        }
    }

    private fun floatTone(startFrame: Int, frames: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * 4 * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val t = (startFrame + i).toDouble() / 48_000
            val v = (kotlin.math.sin(2 * Math.PI * 220 * t) * 0.3).toFloat()
            repeat(2) { buffer.putFloat(v) }
        }
        buffer.flip()
        return buffer
    }
}
