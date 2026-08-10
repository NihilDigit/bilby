package dev.bilby.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 去重漏一条的后果是 `LazyColumn` 的 key 唯一性检查抛 `IllegalArgumentException` —— 线上
 * 崩过一次(评论的 rpid)。崩溃发生在翻页那一刻的界面上,而不是在这个函数里,所以只能在
 * 这一层拦住。
 */
class PaginationTest {

    private data class Row(val id: Long, val text: String)

    @Test
    fun `与已有条目重复的那些被丢掉`() {
        val page1 = listOf(Row(1, "a"), Row(2, "b"))
        val page2 = listOf(Row(2, "b"), Row(3, "c"))
        assertEquals(
            listOf(Row(1, "a"), Row(2, "b"), Row(3, "c")),
            page1.appendDistinctBy(page2) { it.id },
        )
    }

    @Test
    fun `同一页内部重复的也只留一条`() {
        // 热度排序下同一条会在一页里出现两次,不只是跨页边界。
        val page2 = listOf(Row(3, "c"), Row(3, "c"), Row(4, "d"))
        assertEquals(
            listOf(Row(1, "a"), Row(3, "c"), Row(4, "d")),
            listOf(Row(1, "a")).appendDistinctBy(page2) { it.id },
        )
        // 首页走的是另一条分支(列表为空时直接 distinctBy),同样要去重。
        assertEquals(
            listOf(Row(3, "c"), Row(4, "d")),
            emptyList<Row>().appendDistinctBy(page2) { it.id },
        )
    }

    @Test
    fun `冲突时留住列表里已有的那个实例`() {
        // 旧实例挂着用户可见的状态(楼中楼展开、点赞态),换成新实例会让它们塌回去。
        val existing = Row(1, "展开着")
        val result = listOf(existing).appendDistinctBy(listOf(Row(1, "服务端新给的"))) { it.id }
        assertSame(existing, result.single())
    }

    @Test
    fun `一条新的都没有时原样返回同一个列表`() {
        // 翻到底那一页常常整页重复。返回新列表会让 Compose 认为数据变了,白重组一次。
        val current = listOf(Row(1, "a"), Row(2, "b"))
        assertSame(current, current.appendDistinctBy(listOf(Row(2, "b"))) { it.id })
        assertSame(current, current.appendDistinctBy(emptyList()) { it.id })
    }
}
