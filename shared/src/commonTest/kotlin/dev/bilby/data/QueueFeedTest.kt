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
 * 当前视频落在第一页开头、游标接口两段拼接的方向、过滤失效条目时的偏移量稳定与夜间归并顺序。
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

    @Test
    fun `PagedFeed 往前续取遇到过滤减员页时 offsetOfFirst 按实际条数递减不跳变`() = runTest {
        val pageItems = mapOf(
            1 to (0 until 8).map(::item),
            2 to (10 until 17).map(::item),
            3 to (20 until 26).map(::item),
            4 to (30 until 40).map(::item),
            5 to (40 until 50).map(::item),
        )
        val feed = PagedFeed(anchorPage = 4, pageSize = 10) { page ->
            val list = pageItems[page] ?: return@PagedFeed null
            FeedPage(items = list, hasMore = page < 5, total = 50)
        }

        // 在第 4 页(起始偏移 30)打开 v35(页内下标 5,来源位置 36);fillWindow 往前拉第 3 页(6 条)后偏移应为 24。
        val opened = feed.open("v35")!!
        val idxAfterOpen = opened.indexOfFirst { it.bvid == "v35" }
        assertEquals(24, feed.offsetOfFirst)
        assertEquals(36, feed.offsetOfFirst!! + idxAfterOpen + 1)

        // 再往前续第 2 页(7 条):偏移从 24 减到 17,当前项来源位置仍稳定在 36。
        val page2 = feed.loadBefore()!!
        val combined = page2 + opened
        assertEquals(17, feed.offsetOfFirst)
        assertEquals(36, feed.offsetOfFirst!! + combined.indexOfFirst { it.bvid == "v35" } + 1)

        // 再往前续到第 1 页:触顶归零。
        feed.loadBefore()!!
        assertFalse(feed.hasBefore)
        assertEquals(0, feed.offsetOfFirst)
    }

    // 游标接口:列表按发布时间倒序,aid 越大越新。
    private val archive = (1L..200L).reversed().map { aid ->
        CursorArchiveItem(aid, BvidCodec.fromAid(aid), "", "", 0, "测试UP")
    }

    private fun cursorLoad(aid: Long, newer: Boolean, includeCursor: Boolean): CursorArchivePage? {
        val at = archive.indexOfFirst { it.aid == aid }
        if (at < 0) return null
        return if (newer) {
            // 接口仍按倒序给,紧邻游标的在末尾。
            val from = (at - 20).coerceAtLeast(0)
            CursorArchivePage(archive.subList(from, at), hasNewer = from > 0, hasOlder = true, total = archive.size)
        } else {
            val from = if (includeCursor) at else at + 1
            val to = (from + 20).coerceAtMost(archive.size)
            CursorArchivePage(archive.subList(from, to), hasNewer = true, hasOlder = to < archive.size, total = archive.size)
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
        assertEquals(200, feed.total)
        assertNull(feed.offsetOfFirst)
    }

    @Test
    fun `往前续的是更新的一段,接在最新那条之前`() = runTest {
        val feed = ArchiveCursorFeed(::cursorLoad)
        val first = feed.open(BvidCodec.fromAid(100))!!.first()

        val before = feed.loadBefore()!!

        assertEquals(BvidCodec.toAid(first.bvid) + 1, BvidCodec.toAid(before.last().bvid))
    }

    @Test
    fun `游标触达最新或最旧边界时精确给出 offsetOfFirst 与 total`() = runTest {
        // 1) 最新一段内打开第 5 条(aid=196):!hasBefore,offsetOfFirst 立即为 0。
        val newestFeed = ArchiveCursorFeed(::cursorLoad)
        val newestBvid = BvidCodec.fromAid(196)
        val newestWindow = newestFeed.open(newestBvid)!!
        assertFalse(newestFeed.hasBefore)
        assertTrue(newestFeed.hasAfter)
        assertEquals(0, newestFeed.offsetOfFirst)
        assertEquals(200, newestFeed.total)
        assertEquals(5, newestFeed.offsetOfFirst!! + newestWindow.indexOfFirst { it.bvid == newestBvid } + 1)

        // 2) 最旧一段内打开第 198 条(aid=3):!hasAfter,offsetOfFirst = 200 - loadedSize,且往前续取保持位置不变。
        val oldestFeed = ArchiveCursorFeed(::cursorLoad)
        val oldestBvid = BvidCodec.fromAid(3)
        var oldestWindow = oldestFeed.open(oldestBvid)!!
        assertTrue(oldestFeed.hasBefore)
        assertFalse(oldestFeed.hasAfter)
        assertEquals(200, oldestFeed.total)
        assertEquals(200 - oldestWindow.size, oldestFeed.offsetOfFirst)
        assertEquals(198, oldestFeed.offsetOfFirst!! + oldestWindow.indexOfFirst { it.bvid == oldestBvid } + 1)

        val prepended = oldestFeed.loadBefore()!!
        oldestWindow = prepended + oldestWindow
        assertEquals(200 - oldestWindow.size, oldestFeed.offsetOfFirst)
        assertEquals(198, oldestFeed.offsetOfFirst!! + oldestWindow.indexOfFirst { it.bvid == oldestBvid } + 1)

        // 3) 中间窗口往前续到顶后 offsetOfFirst 从 null 变为 0。
        val midFeed = ArchiveCursorFeed(::cursorLoad)
        midFeed.open(BvidCodec.fromAid(165))!!
        assertNull(midFeed.offsetOfFirst)
        while (midFeed.hasBefore) {
            midFeed.loadBefore()!!
        }
        assertEquals(0, midFeed.offsetOfFirst)
    }

    @Test
    fun `游标不在投稿里时返回 null`() = runTest {
        // 接口对不在投稿里的 aid 返回 -1200;这里模拟成返回了别人开头的一页。
        val feed = ArchiveCursorFeed { _, _, _ -> CursorArchivePage(archive.take(20), hasNewer = false, hasOlder = true) }

        assertNull(feed.open(BvidCodec.fromAid(100)))
    }

    @Test
    fun `ArchiveCursorFeed 边界条目被非 UGC 过滤时按原始首尾 aid 继续推进`() = runTest {
        val requested = mutableListOf<Triple<Long, Boolean, Boolean>>()
        val targetBvid = BvidCodec.fromAid(100)
        val nextBvid = BvidCodec.fromAid(98)
        val feed = ArchiveCursorFeed { aid, newer, includeCursor ->
            requested += Triple(aid, newer, includeCursor)
            when {
                aid == 100L && !newer && includeCursor -> CursorArchivePage(
                    items = listOf(CursorArchiveItem(100L, targetBvid, "t100", "", 60, "UP")),
                    hasNewer = false,
                    hasOlder = true,
                    total = 3,
                    firstRawAid = 100L,
                    lastRawAid = 99L, // aid=99 是被过滤掉的 PUGV 条目
                )
                aid == 100L && newer -> CursorArchivePage(emptyList(), hasNewer = false, hasOlder = true, total = 3)
                aid == 99L && !newer && !includeCursor -> CursorArchivePage(
                    items = listOf(CursorArchiveItem(98L, nextBvid, "t98", "", 60, "UP")),
                    hasNewer = true,
                    hasOlder = false,
                    total = 3,
                    firstRawAid = 98L,
                    lastRawAid = 98L,
                )
                else -> null
            }
        }

        val opened = feed.open(targetBvid)!!
        assertEquals(listOf(targetBvid), opened.map { it.bvid })
        assertTrue(feed.hasAfter)

        val after = feed.loadAfter()!!
        assertEquals(listOf(nextBvid), after.map { it.bvid })
        assertFalse(feed.hasAfter)
        assertTrue("应以原始末条 aid=99 继续向后翻页", Triple(99L, false, false) in requested)
    }

    @Test
    fun `ArchiveForwardCursorFeed 支持深页最多播放游标定位与失败重试`() = runTest {
        val pages = listOf(
            SpaceArchiveCursorPage(
                items = (1..10).map { i -> spaceVideo("c$i", ts = i.toLong()) },
                total = 35,
                nextCursorAid = 100L,
                hasMore = true,
            ),
            SpaceArchiveCursorPage(
                items = (11..20).map { i -> spaceVideo("c$i", ts = i.toLong()) },
                total = 35,
                nextCursorAid = 200L,
                hasMore = true,
            ),
            SpaceArchiveCursorPage(
                items = emptyList(), // 整页非 UGC 被过滤,但 hasMore 仍为 true 且游标前进
                total = 35,
                nextCursorAid = 250L,
                hasMore = true,
            ),
            SpaceArchiveCursorPage(
                items = (21..35).map { i -> spaceVideo("c$i", ts = i.toLong()) },
                total = 35,
                nextCursorAid = null,
                hasMore = false,
            ),
        )
        val cursorToIndex = mapOf<Long?, Int>(null to 0, 100L to 1, 200L to 2, 250L to 3)
        var failCursor250 = true
        val requestedCursors = mutableListOf<Long?>()

        val feed = ArchiveForwardCursorFeed(
            maxOpenPages = 10,
            fallbackUpName = "Lu小鱼",
        ) { cursorAid ->
            requestedCursors += cursorAid
            if (cursorAid == 250L && failCursor250) return@ArchiveForwardCursorFeed null
            pages[cursorToIndex.getValue(cursorAid)]
        }

        // 打开第 2 页末尾的 c18:应从 null 翻到 100L 命中,fillWindow 续翻 200L(空页跳过) 并在 250L 失败时停住
        val opened = feed.open("c18")!!
        assertEquals((1..20).map { "c$it" }, opened.map { it.bvid })
        assertEquals("Lu小鱼", opened.first().upName)
        assertFalse(feed.hasBefore)
        assertTrue(feed.hasAfter)
        assertEquals(0, feed.offsetOfFirst)
        assertEquals(35, feed.total)

        // 重试时必须继续请求游标 250L 而不重复或丢失
        assertNull(feed.loadAfter())
        failCursor250 = false
        val rest = feed.loadAfter()!!
        assertEquals((21..35).map { "c$it" }, rest.map { it.bvid })
        assertFalse(feed.hasAfter)
        assertEquals(35, feed.total)
        assertEquals(listOf(null, 100L, 200L, 250L, 250L, 250L), requestedCursors)
    }

    /** 动态按游标翻页:游标就是页号的字符串,[pages] 里一个元素是一页里带视频的那些。 */
    private fun dynamicPages(
        pages: List<List<Int>>,
        requested: MutableList<String?> = mutableListOf(),
    ): suspend (String?) -> DynamicVideoPage? = { offset ->
        requested += offset
        val index = offset?.toInt() ?: 0
        val hasMore = index + 1 < pages.size
        DynamicVideoPage(pages[index].map(::item), nextOffset = if (hasMore) "${index + 1}" else null, hasMore = hasMore)
    }

    @Test
    fun `动态视频超出页数上限就放弃,不继续往下翻`() = runTest {
        val requested = mutableListOf<String?>()
        val feed = DynamicVideoFeed(startOffset = null, maxPages = 3, load = dynamicPages(
            pages = listOf(listOf(1), listOf(2), listOf(3), listOf(4)),
            requested = requested,
        ))

        assertNull(feed.open("v4"))
        assertEquals("只翻前 3 页", 3, requested.size)
    }

    @Test
    fun `续取跳过一条视频都没有的页`() = runTest {
        // 第 2 页整页是转发与图文,续取时不能把它当成到底。
        val feed = DynamicVideoFeed(startOffset = null, maxPages = 3, load = dynamicPages(
            pages = listOf(listOf(1), emptyList(), listOf(2)),
        ))

        val items = feed.open("v1")!!

        assertEquals(listOf("v1", "v2"), items.map { it.bvid })
        assertEquals(0, feed.offsetOfFirst)
        assertEquals(2, feed.total)
    }

    @Test
    fun `相邻两页里重复出现的视频在队列里只有一条`() = runTest {
        // 翻页期间 UP 又发了动态,上一页末尾那条被挤到下一页开头。
        val feed = DynamicVideoFeed(startOffset = null, maxPages = 3, load = dynamicPages(
            pages = listOf(listOf(1, 2), listOf(2, 3)),
        ))

        val items = feed.open("v1")!!

        assertEquals(listOf("v1", "v2", "v3"), items.map { it.bvid })
        assertEquals(0, feed.offsetOfFirst)
        assertEquals(3, feed.total)
    }

    @Test
    fun `动态流从顶部命中即暴露 offsetOfFirst 为 0,翻到底后暴露 total`() = runTest {
        val feed = DynamicVideoFeed(startOffset = null, maxPages = 2, load = dynamicPages(
            pages = listOf((0 until 12).toList(), (12 until 24).toList(), (24 until 30).toList()),
        ))

        val opened = feed.open("v0")!!
        assertEquals(12, opened.size)
        assertEquals(0, feed.offsetOfFirst)
        assertTrue(feed.hasAfter)
        assertNull(feed.total)

        val p2 = feed.loadAfter()!!
        assertEquals(12, p2.size)
        assertTrue(feed.hasAfter)
        assertNull(feed.total)

        val p3 = feed.loadAfter()!!
        assertEquals(6, p3.size)
        assertFalse(feed.hasAfter)
        assertEquals(30, feed.total)

        // 从中间游标起步时不能谎报 offsetOfFirst = 0
        val midStartFeed = DynamicVideoFeed(startOffset = "1", maxPages = 2, load = dynamicPages(
            pages = listOf((0 until 12).toList(), (12 until 24).toList()),
        ))
        midStartFeed.open("v12")!!
        assertNull(midStartFeed.offsetOfFirst)
    }

    private fun spaceVideo(bvid: String, ts: Long, upName: String = "") = SpaceVideoItem(
        bvid = bvid,
        title = "Title $bvid",
        coverUrl = "",
        durationText = "03:20",
        publishedAtEpochSeconds = ts,
        playCountText = "100",
        danmakuCountText = "10",
        upName = upName,
    )

    @Test
    fun `NightMergeFeed 按发布时间全局倒序归并多位 UP 主并支持游标续取`() = runTest {
        val upA = WhitelistedUp(mid = 10L, name = "Alpha")
        val upB = WhitelistedUp(mid = 20L, name = "Beta")
        // 每位 UP 共 15 条,每页 10 条:Alpha 取偶数时间戳,Beta 取奇数时间戳。
        val alphaAll = (1..15).reversed().map { i -> i.toLong() to spaceVideo("a$i", ts = i * 20L) }
        val betaAll = (1..15).reversed().map { i -> (100L + i) to spaceVideo("b$i", ts = i * 20L + 10L, upName = "BetaAuthor") }
        val requestedCursors = mutableListOf<Pair<Long, Long?>>()

        val feed = NightMergeFeed(
            ups = listOf(upA, upB),
            batchSize = 10,
        ) { mid, cursorAid ->
            requestedCursors += mid to cursorAid
            val source = if (mid == 10L) alphaAll else betaAll
            val startIndex = if (cursorAid == null) 0 else source.indexOfFirst { it.first == cursorAid } + 1
            val slice = source.drop(startIndex).take(10)
            val hasMore = startIndex + slice.size < source.size
            SpaceArchiveCursorPage(
                items = slice.map { it.second },
                total = source.size,
                nextCursorAid = if (hasMore) slice.lastOrNull()?.first else null,
                hasMore = hasMore,
            )
        }

        val initial = feed.open("b15")!!
        assertFalse(feed.hasBefore)
        assertTrue(feed.hasAfter)
        assertEquals(0, feed.offsetOfFirst)
        assertEquals(30, feed.total)
        assertEquals("b15", initial.first().bvid)
        assertEquals("BetaAuthor", initial.first().upName)
        // Alpha 条目接口未填 upName 时回退到白名单名字
        assertEquals("Alpha", initial.first { it.bvid.startsWith("a") }.upName)

        val all = initial.toMutableList()
        while (feed.hasAfter) {
            all += feed.loadAfter()!!
        }
        assertEquals(30, all.size)
        assertEquals(30, feed.total)
        // 验证全局严格按时间倒序:b15(310), a15(300), b14(290), a14(280), ..., a1(20)
        val expected = (1..15).reversed().flatMap { i -> listOf("b$i", "a$i") }
        assertEquals(expected, all.map { it.bvid })
        assertTrue("Alpha 首屏传 null, 续页传末条 aid=6", (10L to null) in requestedCursors && (10L to 6L) in requestedCursors)
        assertTrue("Beta 首屏传 null, 续页传末条 aid=106", (20L to null) in requestedCursors && (20L to 106L) in requestedCursors)
    }

    @Test
    fun `NightMergeFeed 某位 UP 游标续取失败时停在原处等待重试,不乱序跳过`() = runTest {
        val upA = WhitelistedUp(mid = 10L, name = "Alpha")
        val upB = WhitelistedUp(mid = 20L, name = "Beta")
        val alphaPages = mapOf<Long?, SpaceArchiveCursorPage>(
            null to SpaceArchiveCursorPage(
                items = listOf(spaceVideo("a1", 500), spaceVideo("a2", 400)),
                total = 3,
                nextCursorAid = 1002L,
                hasMore = true,
            ),
            1002L to SpaceArchiveCursorPage(
                items = listOf(spaceVideo("a3", 100)),
                total = 3,
                nextCursorAid = null,
                hasMore = false,
            ),
        )
        val betaPages = mapOf<Long?, SpaceArchiveCursorPage>(
            null to SpaceArchiveCursorPage(
                items = listOf(spaceVideo("b1", 450)),
                total = 3,
                nextCursorAid = 2001L,
                hasMore = true,
            ),
            2001L to SpaceArchiveCursorPage(
                items = listOf(spaceVideo("b2", 350), spaceVideo("b3", 250)),
                total = 3,
                nextCursorAid = null,
                hasMore = false,
            ),
        )
        var failBetaCursor = true

        val feed = NightMergeFeed(
            ups = listOf(upA, upB),
            batchSize = 2,
        ) { mid, cursorAid ->
            if (mid == 20L && cursorAid == 2001L && failBetaCursor) return@NightMergeFeed null
            val pages = if (mid == 10L) alphaPages else betaPages
            pages[cursorAid]
        }

        // batchSize = 2,QUEUE_WINDOW 会继续尝试拉取直到 Beta 游标 2001L 失败后停在已确认顺序的前两条 ["a1", "b1"]
        val opened = feed.open("a1")!!
        assertEquals(listOf("a1", "b1"), opened.map { it.bvid })
        assertTrue(feed.hasAfter)

        // Beta 游标继续失败时 loadAfter 返回 null,绝不提前吐出 a2(400) 或 a3(100) 越过未拉到的 b2(350)
        assertNull(feed.loadAfter())

        // 恢复后重试继续严格按全局时间倒序吐出剩余条目
        failBetaCursor = false
        val rest = mutableListOf<QueueItem>()
        while (feed.hasAfter) {
            rest += feed.loadAfter()!!
        }
        assertEquals(listOf("a2", "b2", "b3", "a3"), rest.map { it.bvid })
    }

    @Test
    fun `QueueContext NightMode 与带 upName 的上下文可无损序列化往返`() {
        val night = QueueContext.NightMode(
            ups = listOf(WhitelistedUp(123L, "测试UP", "https://example.com/a.jpg")),
            selectedMid = 123L,
            anchorPage = 2,
        )
        assertEquals(night, decodeQueueContext(encodeQueueContext(night)))

        val archive = QueueContext.UpArchive(
            mid = 456L,
            order = SpaceArchiveOrder.Click,
            keyword = "刑法",
            page = 3,
            upName = "罗翔说刑法",
        )
        assertEquals(archive, decodeQueueContext(encodeQueueContext(archive)))

        val dynamics = QueueContext.UpDynamics(
            mid = 789L,
            pageOffset = "offset_1",
            upName = "老师好我叫何同学",
        )
        assertEquals(dynamics, decodeQueueContext(encodeQueueContext(dynamics)))
    }
}
