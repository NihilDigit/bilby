package dev.danmaku.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 帧率上限的验收标准是"长期平均接近目标",不是"每两帧出一帧"——面板刷新率不是目标帧率的
 * 整数倍时,任何"隔 N 帧"或"距上次输出够久了"的写法都会稳定偏低(144Hz 选 60 实际约 48,
 * 90Hz 约 45,165Hz 约 55)。所以这里喂真实刷新率的 vsync 序列,断言平均值。
 */
class FrameDeadlineSchedulerTest {

    private fun vsyncSeries(refreshHz: Double, seconds: Double): List<Long> {
        val periodNanos = 1_000_000_000.0 / refreshHz
        val count = (refreshHz * seconds).toInt()
        return (0 until count).map { (it * periodNanos).toLong() }
    }

    private fun averageFps(refreshHz: Double, cap: DanmakuFrameRateCap, seconds: Double = 10.0): Double {
        val scheduler = FrameDeadlineScheduler(cap.frameIntervalNanos)
        val frames = vsyncSeries(refreshHz, seconds)
        val drawn = frames.count { scheduler.shouldDraw(it) }
        return drawn / seconds
    }

    @Test
    fun `非整数倍面板上长期平均帧率接近目标`() {
        // 容差 1fps:相位对齐后每秒的取整误差最多一帧。
        listOf(90.0, 144.0, 165.0, 120.0, 60.0).forEach { hz ->
            val fps60 = averageFps(hz, DanmakuFrameRateCap.FPS_60)
            assertTrue("${hz}Hz 上 60fps 档实际只有 $fps60", kotlin.math.abs(fps60 - 60.0) <= 1.0)

            val fps30 = averageFps(hz, DanmakuFrameRateCap.FPS_30)
            assertTrue("${hz}Hz 上 30fps 档实际只有 $fps30", kotlin.math.abs(fps30 - 30.0) <= 1.0)
        }
    }

    @Test
    fun `面板刷新率低于目标时出满每一帧`() {
        val scheduler = FrameDeadlineScheduler(DanmakuFrameRateCap.FPS_60.frameIntervalNanos)
        val frames = vsyncSeries(30.0, seconds = 1.0)
        assertEquals(frames.size, frames.count { scheduler.shouldDraw(it) })
    }

    @Test
    fun `跟随屏幕档不跳帧`() {
        val scheduler = FrameDeadlineScheduler(DanmakuFrameRateCap.DISPLAY.frameIntervalNanos)
        val frames = vsyncSeries(144.0, seconds = 1.0)
        assertEquals(frames.size, frames.count { scheduler.shouldDraw(it) })
    }

    @Test
    fun `挂起归来不补画积压的帧`() {
        val scheduler = FrameDeadlineScheduler(DanmakuFrameRateCap.FPS_60.frameIntervalNanos)
        scheduler.shouldDraw(0L)

        // 帧循环挂起了 5 秒(暂停、屏上没有弹幕),回来后按 144Hz 继续。
        val resumeNanos = 5_000_000_000L
        val periodNanos = (1_000_000_000.0 / 144.0).toLong()
        val drawn = (0 until 144).count { scheduler.shouldDraw(resumeNanos + it * periodNanos) }

        assertTrue("挂起归来后连出了 $drawn 帧,把空档当成了欠账", drawn <= 61)
    }
}
