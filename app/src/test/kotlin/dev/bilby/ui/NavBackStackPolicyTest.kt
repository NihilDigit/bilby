package dev.bilby.ui

import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压栈的去重规则。这几条挡的都是发作过或者会直接抛异常的情况,不是把实现再抄一遍:
 * 同 key 重复在 Nav3 里意味着共用 ViewModel 与 saveable 状态、弹出时互相清账、同时组合时
 * `SaveableStateHolder` 直接 require 失败(依据见 [pushUnique])。
 */
class NavBackStackPolicyTest {

    @Test
    fun `目标就是栈顶时不再压一层,并报出没变`() {
        // 空间页里点他自己的 @:目标与栈顶是同一个 Space。真机上表现为画面不动、返回键要多按
        // 一次(mid 1298779265)。返回值 false 是调用方弹那句"已在当前页面"的依据。
        val stack = mutableListOf<NavKey>(Home, Space(1298779265L))

        val changed = stack.pushUnique(Space(1298779265L))

        assertFalse(changed)
        assertEquals(listOf<NavKey>(Home, Space(1298779265L)), stack)
    }

    @Test
    fun `栈里已有的目标移到栈顶而不是再来一份`() {
        // 空间页 → 视频 → 点视频里的 UP 头像回到同一个空间页。
        val stack = mutableListOf<NavKey>(Home, Space(1L), Video("BV1"))

        val changed = stack.pushUnique(Space(1L))

        // 移到栈顶算"变了":界面确实换了一页,不该弹"已在当前页面"。
        assertTrue(changed)
        assertEquals(listOf<NavKey>(Home, Video("BV1"), Space(1L)), stack)
    }

    @Test
    fun `移到栈顶不会连累中间那几页`() {
        // 中间这些是用户自己走过来的,不能因为"目标已经在下面"就被弹掉。
        val stack = mutableListOf<NavKey>(Home, Space(1L), Video("BV1"), ArticlePage("42", isRead = false))

        stack.pushUnique(Space(1L))

        assertEquals(
            listOf<NavKey>(Home, Video("BV1"), ArticlePage("42", isRead = false), Space(1L)),
            stack,
        )
    }

    @Test
    fun `只有值相同才算同一个目的地`() {
        // 参数是身份的一部分:同一条视频以听视频状态打开是另一个目的地,同一串数字在两套专栏
        // 编号里是两篇文章(见 Destinations.kt)。它们不该互相顶掉。
        val stack = mutableListOf<NavKey>(Home, Video("BV1"))

        stack.pushUnique(Video("BV1", listening = true))
        stack.pushUnique(ArticlePage("42", isRead = false))
        stack.pushUnique(ArticlePage("42", isRead = true))

        assertEquals(
            listOf<NavKey>(
                Home,
                Video("BV1"),
                Video("BV1", listening = true),
                ArticlePage("42", isRead = false),
                ArticlePage("42", isRead = true),
            ),
            stack,
        )
    }

    @Test
    fun `连着压同一个目标只留一份`() {
        // 连点是常态:那条 @ 当时被点了五下。
        val stack = mutableListOf<NavKey>(Home)

        repeat(5) { stack.pushUnique(Space(2L)) }

        assertEquals(listOf<NavKey>(Home, Space(2L)), stack)
    }
}
