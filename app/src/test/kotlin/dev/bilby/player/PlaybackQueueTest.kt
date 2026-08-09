package dev.bilby.player

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

/**
 * 起播先装一份只有当前视频的临时队列,完整来源随后换上来(AudioPlaybackService.enrichQueue)。
 * 这几条覆盖那次替换里会静默播错视频的分支;"替换时没有重新 prepare"由服务那边不调 playCurrent
 * 保证,在这一层测不到。
 */
class ReplaceKeepingTest {

    private fun item(bvid: String, cid: Long = 0) = QueueItem(bvid, cid, bvid, "up", "", 0)

    /** 临时队列换成完整来源:当前这条按 bvid 落位,而不是落在来源列表的开头。 */
    @Test
    fun `replacement positions on the current bvid`() {
        val queue = PlaybackQueue(listOf(item("B", cid = 7)))

        assertTrue(queue.replaceKeeping("B", listOf(item("A"), item("B"), item("C"))))
        assertEquals("B", queue.current()?.bvid)
        assertEquals(1, queue.currentIndex)
        assertEquals(3, queue.size)
    }

    /** 来源给的是这条视频的默认 P1,而正在播的那一 P 是页面带进来的,不能被它盖掉。 */
    @Test
    fun `replacement keeps the part being played`() {
        val queue = PlaybackQueue(listOf(item("B", cid = 7)))

        queue.replaceKeeping("B", listOf(item("A", cid = 1), item("B", cid = 2)))
        assertEquals(7L, queue.current()?.cid)
    }

    /**
     * 迟到的补全结果不能覆盖当前队列。用户在补全期间换了视频时,服务那边靠 generation 挡;
     * 这里是第二道:结果里根本没有正在播的这条,说明它属于上一次打开,或者来源定位不到时
     * 降级成了"从最新 N 条开始"的那份列表。
     */
    @Test
    fun `a result without the current video is refused`() {
        val queue = PlaybackQueue(listOf(item("B", cid = 7)))

        assertFalse(queue.replaceKeeping("B", listOf(item("X"), item("Y"))))
        assertEquals("B", queue.current()?.bvid)
        assertEquals(7L, queue.current()?.cid)
        assertEquals(1, queue.size)
    }

    /**
     * 页面拿到 bvid 就发打开命令,标题和封面要等详情回来才补。空值不覆盖已有值——从队列点进来
     * 的那条本来就带着完整信息,而后续那几条命令是不带元数据的。
     */
    @Test
    fun `metadata fills in blanks without clobbering what is there`() {
        val queue = PlaybackQueue(listOf(QueueItem("B", 0, "", "", "", 0)))

        assertTrue(queue.fillCurrentMetadata("标题", "UP", "cover"))
        assertFalse(queue.fillCurrentMetadata("", "", ""))
        assertEquals("标题", queue.current()?.title)
        assertEquals("UP", queue.current()?.upName)
        assertEquals("cover", queue.current()?.coverUrl)
    }

    /** 随机是开着的时候补全:表要按新内容重来,而正在响的这条不能被换掉。 */
    @Test
    fun `replacement rebuilds the shuffle order without changing what is playing`() {
        val queue = PlaybackQueue(listOf(item("B")), random = Random(1))
        queue.setShuffled(true)

        queue.replaceKeeping("B", (0..9).map { item("v$it") } + item("B"))

        assertTrue(queue.shuffled)
        assertEquals("B", queue.current()?.bvid)
        assertEquals(11, queue.size)
        // 打开随机时当前这条被放到表首,于是 N / M 是 1 / 11。
        assertEquals(0, queue.currentIndex)
    }
}

class UpdateCurrentCidTest {

    private fun item(bvid: String, cid: Long) =
        QueueItem(bvid, cid, bvid, "up", "", 0)

    /** 换 P 之后走开再回来,应该回到离开时那一 P,而不是队列自带的默认 P1。 */
    @Test
    fun `returning to a video restores the part it was left on`() {
        val queue = PlaybackQueue(listOf(item("A", 1), item("B", 10)))
        queue.updateCurrentCid(3)
        queue.next()
        queue.previous()
        assertEquals(3L, queue.current()?.cid)
    }

    @Test
    fun `updating touches only the current entry`() {
        val queue = PlaybackQueue(listOf(item("A", 1), item("B", 10)))
        queue.updateCurrentCid(3)
        assertEquals(listOf(3L, 10L), queue.itemsNatural().map { it.cid })
    }

    /** 空队列时是无操作 —— 看视频时队列本来就是空的,这条路每次换 P 都会走到。 */
    @Test
    fun `updating an empty queue does nothing`() {
        PlaybackQueue(emptyList()).updateCurrentCid(3)
    }
}
