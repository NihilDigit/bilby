package dev.bilby.ui.comment

import dev.bilby.data.CommentMention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * @ 与 `content.members` 的配对。这些用例来自 `x/v2/reply/main` 的真实响应:抽样的 11 个
 * mention 里有 5 个的 uname 与正文对不上,全是发帖后改过名的人。
 */
class MentionResolutionTest {

    private fun resolve(message: String, vararg mentions: CommentMention) =
        resolveMentions(RichTokenRegex.findAll(message).toList(), mentions.toList())

    @Test
    fun `名字对得上时链接只盖住名字那一截`() {
        val message = "@张三,你看看这个"
        val links = resolve(message, CommentMention(42L, "张三"))

        val link = links.getValue(message.indexOf('@'))
        assertEquals(42L, link.mid)
        // token 是 "@张三,你看看这个"(正则按空白切),只有 "@张三" 属于人。
        assertEquals("@张三".length, link.length)
    }

    @Test
    fun `改过名的人按位置配对`() {
        // 正文留的是发帖当时的昵称,members 给的是现在的。
        val message = "回复 @惠惠Megumin_ :相 对 论"
        val links = resolve(message, CommentMention(495970426L, "-Megumin_"))

        val link = links.getValue(message.indexOf('@'))
        assertEquals(495970426L, link.mid)
        assertEquals("@惠惠Megumin_".length, link.length)
    }

    @Test
    fun `数量对不上时一个都不配`() {
        // 正文里两个 @,接口只给一个人 —— 无法判断是哪一个,宁可都不可点。
        val message = "@老王 @老李 你们看"
        val links = resolve(message, CommentMention(1L, "已改名的人"))

        assertTrue(links.isEmpty())
    }

    @Test
    fun `长名字优先认领,短名字不抢前缀`() {
        val message = "@abcd 和 @abc 都在"
        val links = resolve(message, CommentMention(1L, "abc"), CommentMention(2L, "abcd"))

        assertEquals(2L, links.getValue(message.indexOf("@abcd")).mid)
        assertEquals(1L, links.getValue(message.indexOf("@abc ")).mid)
    }

    @Test
    fun `名字认领之后剩下的才按位置配`() {
        val message = "@张三 说得对,@旧名字 你怎么看"
        val links = resolve(message, CommentMention(1L, "张三"), CommentMention(2L, "新名字"))

        assertEquals(1L, links.getValue(message.indexOf("@张三")).mid)
        assertEquals(2L, links.getValue(message.indexOf("@旧名字")).mid)
    }

    @Test
    fun `没有 members 就没有链接`() {
        val message = "@某人 在吗"
        assertTrue(resolve(message).isEmpty())
    }

    @Test
    fun `正文没有 @ 时不凭空造链接`() {
        assertTrue(resolve("这条评论没有提到谁", CommentMention(1L, "张三")).isEmpty())
    }
}
