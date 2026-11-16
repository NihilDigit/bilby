package dev.bilby.ui.feed

import dev.bilby.data.model.FeedEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 已读位置分隔线按条目 id 定位,不是按下标。这条不变式是 [FeedReadPositionEntity] 存在的
 * 唯一理由(见其注释),值得单独断言 —— 光看 [indexOfReadMarker] 的实现容易被"看起来对"
 * 骗过去,真正会出错的是"插入新投稿后还认得同一条"这件事本身。
 */
class FeedScreenTest {

    private fun item(bvid: String, upMid: Long = 0L, publishedAt: Long = 0L) = FeedEntry.Video(
        bvid = bvid,
        title = bvid,
        coverUrl = "",
        durationText = "",
        upName = "UP$upMid",
        upMid = upMid,
        upFaceUrl = "",
        publishedAtEpochSeconds = publishedAt,
        playCount = "",
        danmakuCount = "",
    )

    @Test
    fun `顶部插入新投稿后仍指向同一条视频`() {
        val before = listOf("bv1", "bv2", "bv3").map(::item)
        val markerBvid = "bv2"
        val indexBefore = before.indexOfReadMarker(markerBvid)
        assertEquals("bv2", before[indexBefore!!].id)

        // 关注的人又发了两条新的,插到最前面 —— 下标全体后移,id 不变。
        val after = listOf("bv4", "bv5").map(::item) + before
        val indexAfter = after.indexOfReadMarker(markerBvid)

        assertEquals("bv2", after[indexAfter!!].id)
        // 下标本身必须跟着位移,否则就是"记下标"而不是"记 id",证明不了这条测试要证明的事。
        assertEquals(indexBefore + 2, indexAfter)
    }

    @Test
    fun `已读位置是最新一条时不显示分隔线`() {
        val items = listOf("bv1", "bv2").map(::item)
        assertNull(items.indexOfReadMarker("bv1"))
    }

    @Test
    fun `已读位置不在已加载范围内时不显示分隔线`() {
        val items = listOf("bv1", "bv2").map(::item)
        assertNull(items.indexOfReadMarker("bv-not-loaded"))
    }

    @Test
    fun `从没记过时不显示分隔线`() {
        val items = listOf("bv1", "bv2").map(::item)
        assertNull(items.indexOfReadMarker(null))
    }

    @Test
    fun `同一 UP 连发达到三条时折叠而两条不折叠`() {
        val t = 1_700_000_000L
        val entries = listOf(
            item("a1", upMid = 1L, publishedAt = t),
            item("a2", upMid = 1L, publishedAt = t - 60),
            item("a3", upMid = 1L, publishedAt = t - 120),
            item("b1", upMid = 2L, publishedAt = t - 180),
            item("b2", upMid = 2L, publishedAt = t - 240),
        )
        val grouped = groupFeedBursts(entries)
        assertEquals(3, grouped.size)
        val burst = grouped[0] as FeedDisplayNode.Burst
        assertEquals("a1", burst.lead.bvid)
        assertEquals(listOf("a2", "a3"), burst.rest.map { it.bvid })
        assertEquals("b1", (grouped[1] as FeedDisplayNode.Single).entry.id)
        assertEquals("b2", (grouped[2] as FeedDisplayNode.Single).entry.id)
    }

    @Test
    fun `两人轮换连发各自折叠且保留中间单发条目顺序`() {
        val t = 1_700_000_000L
        val entries = listOf(
            item("a1", upMid = 1L, publishedAt = t),
            item("b1", upMid = 2L, publishedAt = t - 10),
            item("c1", upMid = 3L, publishedAt = t - 20),
            item("a2", upMid = 1L, publishedAt = t - 30),
            item("b2", upMid = 2L, publishedAt = t - 40),
            item("a3", upMid = 1L, publishedAt = t - 50),
            item("b3", upMid = 2L, publishedAt = t - 60),
        )
        val grouped = groupFeedBursts(entries)
        assertEquals(3, grouped.size)
        assertEquals(listOf("a1", "a2", "a3"), (grouped[0] as FeedDisplayNode.Burst).all.map { it.bvid })
        assertEquals(listOf("b1", "b2", "b3"), (grouped[1] as FeedDisplayNode.Burst).all.map { it.bvid })
        assertEquals("c1", (grouped[2] as FeedDisplayNode.Single).entry.id)
    }

    @Test
    fun `间隔超过六小时不折叠成同一连发组`() {
        val t = 1_700_000_000L
        val entries = listOf(
            item("a1", upMid = 1L, publishedAt = t),
            item("a2", upMid = 1L, publishedAt = t - 60),
            item("a3", upMid = 1L, publishedAt = t - (6L * 3600L + 120L)),
        )
        val grouped = groupFeedBursts(entries)
        assertEquals(3, grouped.size)
        grouped.forEach { node -> assert(node is FeedDisplayNode.Single) }
    }

    @Test
    fun `展开与收起态网格占格数与连发分组一致`() {
        val t = 1_700_000_000L
        val entries = listOf(
            item("s1", upMid = 10L, publishedAt = t),
            item("a1", upMid = 1L, publishedAt = t - 10),
            item("a2", upMid = 1L, publishedAt = t - 20),
            item("a3", upMid = 1L, publishedAt = t - 30),
            item("a4", upMid = 1L, publishedAt = t - 40),
        )
        val grouped = groupFeedBursts(entries)
        assertEquals(2, grouped.size)
        val single = grouped[0] as FeedDisplayNode.Single
        val burst = grouped[1] as FeedDisplayNode.Burst

        assertEquals(1, single.emittedGridItemCount(expanded = false))
        assertEquals(1, single.emittedGridItemCount(expanded = true))
        assertEquals(1, burst.emittedGridItemCount(expanded = false))
        assertEquals(burst.totalCount + 1, burst.emittedGridItemCount(expanded = true))
        assertEquals(5, burst.emittedGridItemCount(expanded = true))
    }

    @Test
    fun `索引间隔超过上限时不跨段合并连发`() {
        val t = 1_700_000_000L
        val entries = buildList {
            add(item("a1", upMid = 1L, publishedAt = t))
            repeat(BURST_MAX_INDEX_GAP) { idx ->
                add(item("other$idx", upMid = 100L + idx, publishedAt = t - (idx + 1) * 10L))
            }
            add(item("a2", upMid = 1L, publishedAt = t - 100))
            add(item("a3", upMid = 1L, publishedAt = t - 110))
        }
        val grouped = groupFeedBursts(entries)
        assertEquals(entries.size, grouped.size)
        grouped.forEach { node -> assert(node is FeedDisplayNode.Single) }
    }
}
