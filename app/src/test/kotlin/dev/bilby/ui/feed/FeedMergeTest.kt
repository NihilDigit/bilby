package dev.bilby.ui.feed

import dev.bilby.data.model.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 动态流本地缓存头部合并的几条不变式,都是真会出错的地方,不是把 [mergeFeedAtHead] 的实现
 * 抄一遍断言:新条目按抓取顺序整体插到最前面、旧条目位置不因合并而变、重叠到的旧条目被更新
 * 而不是复制出第二份、排除的 UP 主在合并结果里依然存在(过滤是读取时才做的事)。
 */
class FeedMergeTest {

    private fun item(bvid: String, playCount: String = "1", upMid: Long = 1L) = FeedItem(
        bvid = bvid,
        title = bvid,
        coverUrl = "",
        durationText = "",
        upName = "",
        upMid = upMid,
        publishedAtEpochSeconds = 0L,
        playCount = playCount,
        danmakuCount = "",
    )

    @Test
    fun `新条目按抓取顺序整体插到最前面`() {
        val current = listOf(item("bv1"), item("bv2"))
        val fresh = listOf(item("bv-new1"), item("bv-new2"))

        val merged = mergeFeedAtHead(current, fresh)

        assertEquals(listOf("bv-new1", "bv-new2", "bv1", "bv2"), merged.map { it.bvid })
    }

    @Test
    fun `旧条目相对顺序不变且不重复`() {
        val current = listOf(item("bv1"), item("bv2"), item("bv3"))
        // 头部请求拿到的一页里,前面是真正新的,后面正好是已经缓存过的最新几条——两批本来就
        // 该有重叠,这是时间序流分页的正常形态。
        val fresh = listOf(item("bv-new"), item("bv1", playCount = "999"), item("bv2"))

        val merged = mergeFeedAtHead(current, fresh)

        assertEquals(listOf("bv-new", "bv1", "bv2", "bv3"), merged.map { it.bvid })
        assertEquals(merged.size, merged.map { it.bvid }.distinct().size)
    }

    @Test
    fun `重叠条目原地更新字段`() {
        val current = listOf(item("bv1", playCount = "100"))
        val fresh = listOf(item("bv1", playCount = "200"))

        val merged = mergeFeedAtHead(current, fresh)

        assertEquals("200", merged.single { it.bvid == "bv1" }.playCount)
    }

    @Test
    fun `排除的 UP 主在合并结果里仍然存在,只在读取时被过滤`() {
        val current = listOf(item("bv-excluded", upMid = 999L))
        val fresh = listOf(item("bv1"))

        val merged = mergeFeedAtHead(current, fresh)

        // 合并这一层不知道排除名单,过滤是 FeedViewModel.publishItems 的事,不是这里的事。
        assertEquals(2, merged.size)
        assertEquals(listOf("bv1"), merged.filterNot { it.upMid == 999L }.map { it.bvid })
    }
}
