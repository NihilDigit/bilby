package dev.bilby.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 系列与动态两条来源开窗的边界。这段以前在两处各写了一遍(`fromSeries` 与 `fromUpDynamics`),
 * 内容逐字相同 —— 合成一处之后这些用例钉的是两端夹取:差一位就是 `IndexOutOfBounds`,
 * 而它只在"这条视频恰好是最新或最旧的一条"时才发作,平时翻十个 UP 也碰不到。
 */
class QueueWindowTest {

    private val items = (1..100).toList()

    @Test
    fun `中间取前后各 half 条,连自己一共 2 half + 1`() {
        val window = items.windowAround(position = 50, half = 12)

        assertEquals(25, window.size)
        assertEquals(39, window.first())
        assertEquals(63, window.last())
        assertEquals(51, window[12])
    }

    @Test
    fun `当前是第一条时窗口从头开始,不往前越界`() {
        val window = items.windowAround(position = 0, half = 12)

        assertEquals(13, window.size)
        assertEquals(1, window.first())
    }

    @Test
    fun `当前是最后一条时窗口停在末尾`() {
        val window = items.windowAround(position = items.lastIndex, half = 12)

        assertEquals(13, window.size)
        assertEquals(100, window.last())
    }

    @Test
    fun `两端都够不着时整条列表都在窗口里`() {
        // 一个只发过三条视频的系列:窗口不该为了凑够 25 条去别处要内容。
        val short = listOf("a", "b", "c")

        assertEquals(short, short.windowAround(position = 1, half = 12))
    }

    @Test
    fun `窗口里一定含有当前这条`() {
        // 队列建出来必须含有当前视频,否则调用方会把页面带来的 cid 写到别人头上,
        // playurl 直接回 -404(见 QueueSourceRepository 的类注释)。
        for (position in items.indices) {
            val window = items.windowAround(position, half = 12)
            assertTrue("position=$position", window.contains(items[position]))
        }
    }
}
