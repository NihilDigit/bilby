package dev.bilby.player

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只覆盖三个真会出错的分支:末尾不回绕(产品约束,回绕了不会崩只会"一直放")、切换随机时
 * 当前项不变(重排顺手把正在播的换掉是最容易写出来的 bug)、从中间开始时 N/M 的 N 正确。
 */
class PlaybackQueueTest {

    @Test
    fun `末尾 next 返回 null 且不回绕`() {
        val queue = PlaybackQueue(items(3), startIndex = 2)

        assertNull(queue.next())
        // 位置不能被这次失败的推进改掉,否则 UI 上的 N / M 会跳。
        assertEquals(2, queue.currentIndex)
        assertEquals("bv2", queue.current()!!.bvid)
    }

    @Test
    fun `切换随机时当前这条不变`() {
        // 固定种子:乱序表本身不断言,只断言当前项不受重排影响。
        val queue = PlaybackQueue(items(10), startIndex = 4, random = Random(1))
        val before = queue.current()

        queue.setShuffled(true)
        assertEquals(before, queue.current())
        assertEquals(10, queue.size)

        queue.setShuffled(false)
        assertEquals(before, queue.current())
        // 关掉随机后回到自然顺序,位置是它在原列表里的真实下标。
        assertEquals(4, queue.currentIndex)
    }

    @Test
    fun `从中间开始时 currentIndex 是播放顺序里的位置`() {
        val queue = PlaybackQueue(items(5), startIndex = 3)

        assertEquals(3, queue.currentIndex)
        assertEquals("bv3", queue.current()!!.bvid)

        queue.next()
        assertEquals(4, queue.currentIndex)

        // 打开随机后当前这条被移到表首,于是显示 1 / 5 —— 剩余条数才对得上。
        queue.setShuffled(true)
        assertEquals(0, queue.currentIndex)
        assertEquals("bv4", queue.current()!!.bvid)
    }

    private fun items(count: Int): List<QueueItem> = (0 until count).map {
        QueueItem(
            bvid = "bv$it",
            cid = it.toLong(),
            title = "t$it",
            upName = "up",
            coverUrl = "",
            durationSeconds = 60,
        )
    }
}
