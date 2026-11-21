package dev.bilby.player

import dev.bilby.data.QueueOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `move preserves current item identity and adjusts current index`() {
        val queue = DesktopQueue(Random(1))
        queue.set(items("A", "B", "C", "D"), 1) // current = B
        assertEquals(true, queue.move(0, 2))
        assertEquals(listOf("B", "C", "A", "D"), queue.bvids())
        assertEquals("B", queue.current?.bvid)
        assertEquals(0, queue.index)

        assertEquals(true, queue.move(0, 3))
        assertEquals(listOf("C", "A", "D", "B"), queue.bvids())
        assertEquals("B", queue.current?.bvid)
        assertEquals(3, queue.index)
    }

    @Test
    fun `removeAt and clearExceptCurrent preserve current item and shuffle invariants`() {
        val queue = DesktopQueue(Random(3))
        queue.set(items("A", "B", "C", "D", "E"), 2) // current = C
        queue.order = QueueOrder.Shuffle

        assertEquals(false, queue.removeAt(2)) // cannot remove current directly
        assertEquals(true, queue.removeAt(0))
        assertEquals(listOf("B", "C", "D", "E"), queue.bvids())
        assertEquals("C", queue.current?.bvid)
        assertEquals(1, queue.index)
        assertEquals(setOf("B", "C", "D", "E"), queue.playOrder().toSet())

        assertEquals(true, queue.clearExceptCurrent())
        assertEquals(listOf("C"), queue.bvids())
        assertEquals(0, queue.index)
        assertEquals(listOf("C"), queue.playOrder())
    }

    @Test
    fun `enqueue playNext honors Forward Reverse and Shuffle orders`() {
        // Forward
        val forward = DesktopQueue(Random(1))
        forward.set(items("A", "B", "C"), 1) // B
        forward.enqueue(item("X"), playNext = true)
        assertEquals(listOf("A", "B", "X", "C"), forward.bvids())
        assertEquals(2, forward.nextIndex())
        // Moving existing item to next
        forward.enqueue(item("A"), playNext = true)
        assertEquals(listOf("B", "A", "X", "C"), forward.bvids())
        assertEquals(0, forward.index)
        assertEquals(1, forward.nextIndex())

        // Reverse
        val reverse = DesktopQueue(Random(1))
        reverse.set(items("A", "B", "C"), 1) // B
        reverse.order = QueueOrder.Reverse
        reverse.enqueue(item("X"), playNext = true)
        assertEquals(listOf("A", "X", "B", "C"), reverse.bvids())
        assertEquals("B", reverse.current?.bvid)
        assertEquals(1, reverse.nextIndex()) // X

        // Shuffle
        val shuffle = DesktopQueue(Random(9))
        shuffle.set(items("A", "B", "C", "D"), 1) // B
        shuffle.order = QueueOrder.Shuffle
        shuffle.enqueue(item("Z"), playNext = true)
        assertEquals("Z", shuffle[shuffle.nextIndex()!!].bvid)
        assertEquals(5, shuffle.playOrder().size)
    }

    @Test
    fun `prepend and append preserve exact sourcePosition across duplicate bvid filtering`() {
        val queue = DesktopQueue(Random(1))
        queue.set(items("v10", "v11", "v12"), 1) // current = v11
        queue.bindFeedMeta(offsetOfFirst = 10, total = 50, hasBefore = true, hasAfter = true)

        assertEquals(12, queue.sourcePosition)
        val initialState = queue.toQueueState()
        assertEquals(10, initialState.remainingBefore)
        assertEquals(37, initialState.remainingAfter)
        assertTrue(initialState.isWindowed)

        // Prepend ["v8", "v9", "v10"] where "v10" is duplicate: only 2 fresh items inserted -> offsetOfFirst = 8
        assertTrue(queue.beginExtend(before = true))
        val addedBefore = queue.prepend(items("v8", "v9", "v10"), feedHasBefore = true, feedHasAfter = true, feedTotal = 50)
        assertEquals(2, addedBefore)
        assertEquals(8, queue.offsetOfFirst)
        assertEquals(3, queue.index)
        assertEquals(12, queue.sourcePosition) // 8 + 3 + 1 == 12 (no jump!)

        // Reaching top edge (!feedHasBefore) snaps offsetOfFirst to 0
        assertTrue(queue.beginExtend(before = true))
        queue.prepend(items("v0", "v1", "v2", "v3", "v4", "v5", "v6", "v7"), feedHasBefore = false, feedHasAfter = true, feedTotal = 50)
        assertEquals(0, queue.offsetOfFirst)
        assertFalse(queue.hasBefore)
        assertEquals(12, queue.sourcePosition)
    }

    @Test
    fun `middle window resolves offsetOfFirst at tail edge and reconciles closed window total`() {
        // Middle cursor window (offsetOfFirst = null, total = 10)
        val queue = DesktopQueue(Random(1))
        queue.set(items("v4", "v5", "v6"), 1)
        queue.bindFeedMeta(offsetOfFirst = null, total = 10, hasBefore = true, hasAfter = true)
        assertNull(queue.sourcePosition)
        assertEquals(10, queue.sourceTotal)

        // Append reaches tail (!feedHasAfter): offsetOfFirst resolves to 10 - 6 = 4
        assertTrue(queue.beginExtend(before = false))
        queue.append(items("v7", "v8", "v9"), feedHasBefore = true, feedHasAfter = false, feedTotal = 10)
        assertEquals(4, queue.offsetOfFirst)
        assertEquals(6, queue.sourcePosition) // v5 is #6 of 10

        // Prepend reaches head (!feedHasBefore) with 1 filtered item (only 3 valid instead of 4):
        // !hasBefore && !hasAfter && offsetOfFirst == 0 reconciles sourceTotal to exact playlist size (9)
        assertTrue(queue.beginExtend(before = true))
        queue.prepend(items("v1", "v2", "v3"), feedHasBefore = false, feedHasAfter = false, feedTotal = 10)
        assertEquals(0, queue.offsetOfFirst)
        assertEquals(9, queue.sourceTotal)
        assertFalse(queue.toQueueState().isWindowed)
    }

    @Test
    fun `errorBefore and errorAfter latch halts auto-extend until explicit retry`() {
        val queue = DesktopQueue(Random(1))
        queue.set(items("A", "B", "C", "D"), 2) // index = 2 (size - 2 in Forward)
        queue.bindFeedMeta(offsetOfFirst = 0, total = 20, hasBefore = true, hasAfter = true)

        // 距离末尾还差 1 条时不提前自动续取，真正播到最后一条才续取
        assertFalse(queue.shouldAutoExtendAfter())
        assertFalse(queue.shouldAutoExtendBefore())

        queue.moveTo(3) // index = 3 (last item in Forward)
        assertTrue(queue.shouldAutoExtendAfter())
        assertFalse(queue.shouldAutoExtendBefore())

        // 正在全部加载期间不重复触发单头自动续取
        assertTrue(queue.beginLoadAll())
        assertTrue(queue.toQueueState().loadingAll)
        assertFalse(queue.shouldAutoExtendAfter())
        queue.finishLoadAll()
        assertFalse(queue.toQueueState().loadingAll)
        assertTrue(queue.shouldAutoExtendAfter())

        // Simulate failed loadAfter
        assertTrue(queue.beginExtend(before = false))
        assertTrue(queue.loadingAfter)
        queue.failExtend(before = false)
        assertFalse(queue.loadingAfter)
        assertTrue(queue.errorAfter)
        assertTrue(queue.toQueueState().errorAfter)

        // Error latch prevents infinite auto-extend loop
        assertFalse(queue.shouldAutoExtendAfter())

        // Explicit retry via beginExtend clears errorAfter
        assertTrue(queue.beginExtend(before = false))
        assertFalse(queue.errorAfter)
        queue.append(items("E", "F"), feedHasBefore = true, feedHasAfter = true, feedTotal = 20)
        assertFalse(queue.errorAfter)

        // Reverse boundary check (only index == 0, the very first item)
        queue.order = QueueOrder.Reverse
        queue.moveTo(1)
        assertFalse(queue.shouldAutoExtendBefore()) // index = 1 > 0
        queue.moveTo(0)
        assertTrue(queue.shouldAutoExtendBefore())
        queue.beginExtend(before = true)
        queue.failExtend(before = true)
        assertTrue(queue.errorBefore)
        assertFalse(queue.shouldAutoExtendBefore())

        // Shuffle never auto-extends
        queue.order = QueueOrder.Shuffle
        queue.moveTo(0)
        assertFalse(queue.shouldAutoExtendBefore())
        assertFalse(queue.shouldAutoExtendAfter())

        // Detaching to custom queue clears edges and errors and pins exact 1..N
        queue.detachToCustomQueue()
        val detachedState = queue.toQueueState(detached = true)
        assertFalse(detachedState.canExtendBefore)
        assertFalse(detachedState.canExtendAfter)
        assertFalse(detachedState.errorBefore)
        assertFalse(detachedState.errorAfter)
        assertFalse(detachedState.loadingAll)
        assertEquals(1, detachedState.sourcePosition)
        assertEquals(6, detachedState.sourceTotal)
    }
}
