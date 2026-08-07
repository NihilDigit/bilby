package dev.bilby.data

import dev.bilby.api.dto.HistoryItemDto
import dev.bilby.api.dto.HistoryRefDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [computeHistoryPage] 是这个仓库唯一容易踩错的地方:游标要按**过滤前**的服务端列表算,
 * 而过滤本身容易被误写成"按过滤后的最后一条算"——两者在丢弃过非稿件条目的那一页上会给出
 * 不同的游标,后一种写法会让下一页请求错位置(见函数上的注释)。
 */
class HistoryRepositoryTest {

    private fun archive(oid: Long, viewAt: Long, bvid: String = "BV$oid") = HistoryItemDto(
        title = "video $oid",
        history = HistoryRefDto(oid = oid, bvid = bvid, business = "archive"),
        viewAt = viewAt,
    )

    private fun live(oid: Long, viewAt: Long) = HistoryItemDto(
        title = "live $oid",
        history = HistoryRefDto(oid = oid, bvid = "", business = "live"),
        viewAt = viewAt,
    )

    @Test
    fun `游标取过滤前最后一条的 oid 和 view_at`() {
        val raw = listOf(archive(1, 100), live(2, 200), archive(3, 300))
        val page = computeHistoryPage(raw, fallbackMax = 0, fallbackViewAt = 0)

        assertEquals(3L, page.nextMax)
        assertEquals(300L, page.nextViewAt)
        // 直播那条被丢了,但游标仍然是它的位置——服务端下一页是接在它后面的,不是接在
        // 过滤后剩下的最后一条稿件(oid=3)后面(这里两者恰好相同,但逻辑不能巧合成立)。
        assertEquals(2, page.items.size)
    }

    @Test
    fun `非 archive 或没有 bvid 的条目被丢弃并计入日志`() {
        val raw = listOf(
            archive(1, 100),
            live(2, 200),
            HistoryItemDto(history = HistoryRefDto(oid = 3, bvid = "", business = "archive")), // 缺 bvid
        )
        val page = computeHistoryPage(raw, fallbackMax = 0, fallbackViewAt = 0)

        assertEquals(listOf(1L), page.items.map { it.oid })
    }

    @Test
    fun `空列表即到底,游标退回上一次的值`() {
        val page = computeHistoryPage(emptyList(), fallbackMax = 42, fallbackViewAt = 99)

        assertTrue(page.isEnd)
        assertEquals(42L, page.nextMax)
        assertEquals(99L, page.nextViewAt)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun `非空列表未到底`() {
        val page = computeHistoryPage(listOf(archive(1, 100)), fallbackMax = 0, fallbackViewAt = 0)
        assertFalse(page.isEnd)
    }
}
