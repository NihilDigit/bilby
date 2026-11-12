package dev.bilby.player

import dev.bilby.data.QueueOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class DesktopQueueTest {

    private fun item(bvid: String) = QueueItem(bvid, title = "", upName = "", coverUrl = "", durationSeconds = 0)

    private fun items(vararg bvids: String) = bvids.map(::item)

    private fun DesktopQueue.bvids() = items.map { it.bvid }

    /** 从当前条往前走到播放顺序的第一条,再往后走到底,得到整份播放顺序。 */
    private fun DesktopQueue.playOrder(): List<String> {
        val start = index
        while (true) previousIndex()?.let { moveTo(it) } ?: break
        val order = mutableListOf(current!!.bvid)
        while (true) nextIndex()?.let { moveTo(it); order += current!!.bvid } ?: break
        moveTo(start)
        return order
    }

    // 补全的插入次序:先插后面那段,再在当前下标处插前面那段。当前条与它的下标要跟着走。
    @Test
    fun `filling around the current item keeps it current`() {
        val queue = DesktopQueue(Random(1))
        queue.set(items("C"), 0)
        queue.insert(queue.index + 1, items("D", "E"))
        queue.insert(queue.index, items("A", "B"))
        assertEquals(listOf("A", "B", "C", "D", "E"), queue.bvids())
        assertEquals("C", queue.current?.bvid)
        assertEquals(2, queue.index)
    }

    @Test
    fun `duplicates never enter the queue`() {
        val queue = DesktopQueue(Random(1))
        queue.set(items("A", "B", "A", "C"), 2)
        assertEquals(listOf("A", "B", "C"), queue.bvids())
        assertEquals("A", queue.current?.bvid)

        val added = queue.insert(queue.size, items("C", "D", "D"))
        assertEquals(1, added)
        assertEquals(listOf("A", "B", "C", "D"), queue.bvids())
    }

    @Test
    fun `reverse order traverses backwards while preserving natural list order`() {
        val queue = DesktopQueue(Random(1))
        queue.set(items("A", "B", "C", "D"), 2)
        queue.order = QueueOrder.Reverse

        // 自然顺序与当前下标不动,只改上下一条方向:倒序下 C 的下一条是 B、上一条是 D。
        assertEquals(listOf("A", "B", "C", "D"), queue.bvids())
        assertEquals(2, queue.index)
        assertEquals(1, queue.nextIndex())
        assertEquals(3, queue.previousIndex())
        assertEquals(listOf("D", "C", "B", "A"), queue.playOrder())

        // 两端边界:列表头(A)在倒序下没有下一条,列表尾(D)在倒序下没有上一条。
        queue.moveTo(0)
        assertNull(queue.nextIndex())
        assertEquals(1, queue.previousIndex())

        queue.moveTo(3)
        assertEquals(2, queue.nextIndex())
        assertNull(queue.previousIndex())

        // 两头续取插入后仍保持完整逆序且不挪当前条。
        queue.moveTo(1) // "B"
        queue.insert(0, items("X"))
        queue.insert(queue.size, items("Z"))
        assertEquals("B", queue.current?.bvid)
        assertEquals(listOf("Z", "D", "C", "B", "A", "X"), queue.playOrder())
    }

    @Test
    fun `cycling Forward to Reverse to Shuffle to Forward switches traversal cleanly`() {
        val queue = DesktopQueue(Random(7))
        queue.set(items("A", "B", "C", "D", "E"), 2)

        assertEquals(listOf("A", "B", "C", "D", "E"), queue.playOrder())

        queue.order = QueueOrder.Reverse
        assertEquals(listOf("E", "D", "C", "B", "A"), queue.playOrder())

        queue.order = QueueOrder.Shuffle
        val shuffled = queue.playOrder()
        assertEquals(setOf("A", "B", "C", "D", "E"), shuffled.toSet())
        assertEquals(5, shuffled.size)

        queue.order = QueueOrder.Forward
        assertEquals(listOf("A", "B", "C", "D", "E"), queue.playOrder())
    }

    // 随机顺序是一个排列:从头走到尾每条恰好一次,上一条是下一条的逆。插入之后旧条目之间的
    // 先后不变(Media3 的 cloneAndInsert 语义),而新条目都走得到。
    @Test
    fun `shuffle order is a permutation that survives insertion`() {
        repeat(50) { seed ->
            val queue = DesktopQueue(Random(seed))
            queue.set(items("A", "B", "C", "D", "E"), 2)
            queue.order = QueueOrder.Shuffle
            val before = queue.playOrder()
            assertEquals(queue.bvids().toSet(), before.toSet())
            assertEquals(5, before.size)

            queue.insert(0, items("X", "Y"))
            queue.insert(queue.size, items("Z"))
            assertEquals("C", queue.current?.bvid)
            val after = queue.playOrder()
            assertEquals(queue.bvids().toSet(), after.toSet())
            assertEquals(8, after.size)
            assertEquals(before, after.filter { it in before })
        }
    }
}
