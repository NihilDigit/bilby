package dev.bilby.ui.comment

import dev.bilby.data.CommentLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 正文扫描器把 `content.jump_url` 的 key 一起认出来(notes §1.4a)。
 *
 * 这里只测**切分**,不测渲染:key 的长短相互遮盖是一条真会出错的规则,而它的表现是正文里
 * 多出半截数字,肉眼扫代码看不出来。
 */
class RichTokenRegexTest {

    private fun link(title: String) = CommentLink(title = title, url = "https://example.invalid")

    private fun tokens(message: String, links: Map<String, CommentLink>) =
        richTokenRegex(links).findAll(message).map { it.value }.toList()

    @Test
    fun `没有链接时复用同一个正则,不为每条评论重新编译`() {
        // 评论列表滚动时这是热路径,而绝大多数评论没有链接。assertSame 而不是 assertEquals:
        // 要断的正是"同一个对象",相等的新对象没有意义。
        assertSame(RichTokenRegex, richTokenRegex(emptyMap()))
    }

    @Test
    fun `长的 key 优先,短的不把长的咬掉一截`() {
        // 一条评论里 av2 和 av2333 可以同时出现。按 map 原序拼正则(PiliPlus 的做法)时,
        // av2 排在前面就会先匹配掉 "av2",剩下 "333" 落回正文。
        val links = mapOf("av2" to link("字幕君交流场所"), "av2333" to link("新华保险入店歌"))
        assertEquals(listOf("av2333"), tokens("看看 av2333 这条", links))
        assertEquals(listOf("av2", "av2333"), tokens("av2 和 av2333", links))
    }

    @Test
    fun `整条链接按 key 匹配,不落到裸 URL 那一支`() {
        // 两支都能匹配这串字,只有 key 那一支拿得到标题。key 排在 RichTokenRegex 之前,
        // 正则的交替是最左最先,所以先命中 key。
        val url = "https://www.bilibili.com/video/BV1xx411c7mD"
        val tokens = tokens("推荐 $url 谢谢", mapOf(url to link("字幕君交流场所")))
        assertEquals(listOf(url), tokens)
    }

    @Test
    fun `key 里的正则元字符按字面匹配`() {
        // key 是正文里的字面文本,不是模式。`?` 和 `.` 不转义的话这条会匹配到别的地方去,
        // 或者直接抛 PatternSyntaxException。
        val key = "https://b23.tv/a?b=1"
        assertEquals(listOf(key), tokens("看 $key 啊", mapOf(key to link("标题"))))
    }

    @Test
    fun `有链接时仍然认得出表情和时间戳`() {
        val links = mapOf("av2" to link("字幕君交流场所"))
        assertEquals(
            listOf("[doge]", "av2", "12:34"),
            tokens("[doge] av2 跳到 12:34", links),
        )
    }
}
