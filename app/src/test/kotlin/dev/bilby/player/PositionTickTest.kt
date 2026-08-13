package dev.bilby.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 外推是弹幕对不对得上口型的全部依据,而它错了只表现为"弹幕早了半秒",没有任何日志。
 * 倍速那一条是这次改动的由来:锚点不跟着倍速走,长按加速时弹幕会集体跳一下。
 */
class PositionTickTest {

    @Test
    fun `按倍速外推`() {
        val tick = PositionTick(
            positionMillis = 10_000,
            isPlaying = true,
            speed = 2f,
            anchorMillis = 1_000,
        )
        // 锚点之后过了 500ms,2 倍速,内容前进 1000ms。
        assertEquals(11_000, tick.positionAt(1_500))
    }

    @Test
    fun `停着不外推`() {
        val tick = PositionTick(positionMillis = 10_000, isPlaying = false, anchorMillis = 1_000)
        assertEquals(10_000, tick.positionAt(9_999))
    }

    @Test
    fun `时钟倒着走时停在锚点上`() {
        val tick = PositionTick(positionMillis = 10_000, isPlaying = true, anchorMillis = 1_000)
        // 单调时钟不该倒走,但读数来自另一个线程,允许它比锚点旧一点点。
        assertEquals(10_000, tick.positionAt(900))
    }
}
