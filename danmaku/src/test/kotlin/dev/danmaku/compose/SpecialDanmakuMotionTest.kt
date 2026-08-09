package dev.danmaku.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 定位弹幕是时间的纯函数,这组测试断言的是那条曲线在几个**接缝**上的取值:寿命两端、位移延迟
 * 前后、位移结束之后。中间段的线性插值不再逐点复述——那是把实现里那行 lerp 抄一遍。
 */
class SpecialDanmakuMotionTest {

    private val sample = SpecialDanmaku(
        id = "1",
        text = "hi",
        color = 0xFFFFFF,
        startTimeMillis = 1000,
        durationMillis = 4000,
        fromX = 0f,
        fromY = 0f,
        toX = 1f,
        toY = 1f,
        translationDelayMillis = 1000,
        translationDurationMillis = 2000,
        fromAlpha = 1f,
        toAlpha = 0f,
    )

    @Test
    fun `寿命区间左闭右开`() {
        assertNull(sample.motionAt(999))
        assertEquals(1f, sample.motionAt(1000)!!.alpha, EPSILON)
        // 5000 是 start + duration,这一刻弹幕已经不在了——闭区间会让它多显示一帧,
        // 也会让"上一条刚走、下一条已到"的时刻同时存在两条。
        assertNull(sample.motionAt(5000))
    }

    @Test
    fun `延迟期内停在起点,但 alpha 已经在走`() {
        val motion = sample.motionAt(1500)!!
        assertEquals(0f, motion.x, EPSILON)
        assertEquals(0f, motion.y, EPSILON)
        // alpha 铺满整个寿命,不受位移延迟影响:4000ms 里过了 500ms。
        assertEquals(0.875f, motion.alpha, EPSILON)
    }

    @Test
    fun `位移结束后停在终点,不回弹也不继续外推`() {
        assertEquals(1f, sample.motionAt(4000)!!.x, EPSILON)
        assertEquals(1f, sample.motionAt(4999)!!.y, EPSILON)
    }

    @Test
    fun `位移时长为 0 时,延迟一到就跳到终点`() {
        val instant = sample.copy(translationDurationMillis = 0)
        assertEquals(0f, instant.motionAt(1999)!!.x, EPSILON)
        assertEquals(1f, instant.motionAt(2000)!!.x, EPSILON)
    }

    @Test
    fun `easeInCubic 起步慢于线性`() {
        // 位移过半时,easeInCubic 的进度是 0.5³ = 0.125,线性是 0.5。这两个数是判断
        // "缓动到底有没有接上"的唯一可观察差别。
        val eased = sample.copy(easing = SpecialDanmakuEasing.EASE_IN_CUBIC)
        assertEquals(0.125f, eased.motionAt(3000)!!.x, EPSILON)
        assertEquals(0.5f, sample.motionAt(3000)!!.x, EPSILON)
    }

    @Test
    fun `时长非正的弹幕在任何时刻都不存在`() {
        assertNull(sample.copy(durationMillis = 0).motionAt(1000))
        assertNull(sample.copy(durationMillis = -1).motionAt(1000))
    }

    private companion object {
        const val EPSILON = 1e-4f
    }
}
