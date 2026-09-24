package dev.bilby.data

import dev.bilby.BvidCodec
import dev.bilby.player.QueueItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列来源的定位与续取。钉的是几处只在特定位置才发作的边界:列表在点开之后挪了一页、
 * 当前视频落在第一页开头、游标接口两段拼接的方向。
 */
class QueueFeedTest {

    private fun item(n: Int) = QueueItem(bvid = "v$n", title = "", upName = "", coverUrl = "", durationSeconds = 0)

    /** 100 条、每页 10 条的分页来源,[shift] 模拟点开之后新发的条数(旧的整体往后挪)。 */
    private fun pages(shift: Int = 0, requested: MutableList<Int> = mutableListOf()): suspend (Int) -> FeedPage? = { page ->
        requested += page
        val all = (List(shift) { -1 - it } + (0 until 100)).map(::item)
        val from = (page - 1) * 10
        FeedPage(all.drop(from).take(10), hasMore = from + 10 < all.size, total = all.size)
    }

    @Test
    fun `列表挪了一页时在下一页找到`() = runTest {
        // 点开时 v25 在第 3 页;之后新发了 10 条,它挪到了第 4 页。
        val feed = PagedFeed(anchorPage = 3, pageSize = 10, loadPage = pages(shift = 10))

        val items = feed.open("v25")!!

        assertTrue(items.any { it.bvid == "v25" })
        val here = items.indexOfFirst { it.bvid == "v25" }
        assertTrue("前面至少 $QUEUE_WINDOW 条", here >= QUEUE_WINDOW)
        assertTrue("后面至少 $QUEUE_WINDOW 条", items.size - 1 - here >= QUEUE_WINDOW)
    }

    @Test
    fun `锚点与相邻两页都没有时返回 null`() = runTest {
        val feed = PagedFeed(anchorPage = 3, pageSize = 10, loadPage = pages())

        assertNull(feed.open("v99"))
    }

    @Test
    fun `第一页开头的视频不往前越界,往后补够窗口`() = runTest {
        val requested = mutableListOf<Int>()
        val feed = PagedFeed(anchorPage = 1, pageSize = 10, loadPage = pages(requested = requested))

        val items = feed.open("v0")!!

        assertEquals("v0", items.first().bvid)
        assertFalse(feed.hasBefore)
        assertTrue(items.size - 1 >= QUEUE_WINDOW)
        assertFalse("不请求第 0 页", 0 in requested)
        assertEquals(0, feed.offsetOfFirst)
    }

    @Test
    fun `往前续到第一页之后不再有前面`() = runTest {
        val feed = PagedFeed(anchorPage = 5, pageSize = 10, loadPage = pages())
        feed.open("v45")!!
        while (feed.hasBefore) feed.loadBefore()!!

        assertEquals(emptyList<QueueItem>(), feed.loadBefore())
        assertEquals(0, feed.offsetOfFirst)
    }

    @Test
    fun `一头请求失败不妨碍另一头`() = runTest {
        val base = pages()
        val feed = PagedFeed(anchorPage = 5, pageSize = 10) { page -> if (page < 5) null else base(page) }

        val items = feed.open("v41")!!

        assertEquals("v40", items.first().bvid)
        assertTrue(items.size - 1 - items.indexOfFirst { it.bvid == "v41" } >= QUEUE_WINDOW)
    }

    // 游标接口:列表按发布时间倒序,aid 越大越新。
    private val archive = (1L..200L).reversed().map { aid ->
        CursorArchiveItem(aid, BvidCodec.fromAid(aid), "", "", 0, "")
    }

    private fun cursorLoad(aid: Long, newer: Boolean, includeCursor: Boolean): CursorArchivePage? {
        val at = archive.indexOfFirst { it.aid == aid }
        if (at < 0) return null
        return if (newer) {
            // 接口仍按倒序给,紧邻游标的在末尾。
            val from = (at - 20).coerceAtLeast(0)
            CursorArchivePage(archive.subList(from, at), hasNewer = from > 0, hasOlder = true)
        } else {
            val from = if (includeCursor) at else at + 1
            val to = (from + 20).coerceAtMost(archive.size)
            CursorArchivePage(archive.subList(from, to), hasNewer = true, hasOlder = to < archive.size)
        }
    }

    @Test
    fun `游标两段按时间倒序接成一列,当前视频在中间`() = runTest {
        val feed = ArchiveCursorFeed(::cursorLoad)
        val bvid = BvidCodec.fromAid(100)

        val items = feed.open(bvid)!!

        val aids = items.map { BvidCodec.toAid(it.bvid) }
        assertEquals(aids.sortedDescending(), aids)
        assertEquals(20, aids.indexOf(100L))
        assertTrue(feed.hasBefore)
        assertTrue(feed.hasAfter)
    }

    @Test
    fun `往前续的是更新的一段,接在最新那条之前`() = runTest {
        val feed = ArchiveCursorFeed(::cursorLoad)
        val first = feed.open(BvidCodec.fromAid(100))!!.first()

        val before = feed.loadBefore()!!

        assertEquals(BvidCodec.toAid(first.bvid) + 1, BvidCodec.toAid(before.last().bvid))
    }

    @Test
    fun `游标不在投稿里时返回 null`() = runTest {
        // 接口对不在投稿里的 aid 返回 -1200;这里模拟成返回了别人开头的一页。
        val feed = ArchiveCursorFeed { _, _, _ -> CursorArchivePage(archive.take(20), hasNewer = false, hasOlder = true) }

        assertNull(feed.open(BvidCodec.fromAid(100)))
    }
}
