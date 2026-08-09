package dev.bilby.ui.feed

import dev.bilby.data.model.FeedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 已读位置分隔线按 bvid 定位,不是按下标。这条不变式是 [FeedReadPositionEntity] 存在的
 * 唯一理由(见其注释),值得单独断言 —— 光看 [indexOfReadMarker] 的实现容易被"看起来对"
 * 骗过去,真正会出错的是"插入新投稿后还认得同一条"这件事本身。
 */
class FeedScreenTest {

    private fun item(bvid: String) = FeedItem(
        bvid = bvid,
        title = bvid,
        coverUrl = "",
        durationText = "",
        upName = "",
        upMid = 0L,
        publishedAtEpochSeconds = 0L,
        playCount = "",
        danmakuCount = "",
    )

    @Test
    fun `顶部插入新投稿后仍指向同一条视频`() {
        val before = listOf("bv1", "bv2", "bv3").map(::item)
        val markerBvid = "bv2"
        val indexBefore = before.indexOfReadMarker(markerBvid)
        assertEquals("bv2", before[indexBefore!!].bvid)

        // 关注的人又发了两条新的,插到最前面 —— 下标全体后移,id 不变。
        val after = listOf("bv4", "bv5").map(::item) + before
        val indexAfter = after.indexOfReadMarker(markerBvid)

        assertEquals("bv2", after[indexAfter!!].bvid)
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
}
