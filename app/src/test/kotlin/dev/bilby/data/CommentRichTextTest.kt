package dev.bilby.data

import dev.bilby.data.model.RichLinkIcon
import dev.bilby.data.model.RichSpan
import dev.bilby.data.model.plainText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 评论正文的解析:切分、时间点、@ 归属与产出的节。
 *
 * 这里只测**切分**,不测渲染:key 的长短相互遮盖是一条真会出错的规则,而它的表现是正文里
 * 多出半截数字,肉眼扫代码看不出来。
 */
class CommentRichTextTest {

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

    @Test
    fun `全角冒号的时间点同样认得出`() {
        // 中文输入法默认打出来的就是全角。原先只认半角,表现是这一半时间点根本不可点,
        // 而"不可点"和"作者没写时间点"在界面上完全同形。
        assertEquals(listOf("12：34"), tokens("跳到 12：34 看", emptyMap()))
        assertEquals(1000L * (12 * 60 + 34), parseTimestampMillis("12：34"))
    }

    @Test
    fun `分秒越界的不算时间点`() {
        // "1:99" 这种编号不查的话会变成一个能点的时间点,跳过去落在一个和它无关的位置上。
        assertEquals(null, parseTimestampMillis("1:99"))
        assertEquals(null, parseTimestampMillis("1:70:00"))
    }

    @Test
    fun `站内链接产出带视频记号的链接节,而不是一串裸地址`() {
        val url = "https://www.bilibili.com/video/BV1xx411c7mD"
        val spans = parseCommentSpans("推荐 $url 谢谢", emptyMap(), mapOf(url to link("字幕君交流场所")))
        val linkSpan = spans.filterIsInstance<RichSpan.Link>().single()
        assertEquals("字幕君交流场所", linkSpan.text)
        assertEquals(RichLinkIcon.Video, linkSpan.icon)
    }

    @Test
    fun `服务端没标出来的裸链接照样是链接,显示的是地址原文`() {
        // jump_url 只收录它认得的那些。剩下的以前是染了色的死字 —— 显示原文而不是标题,
        // 因为这一支根本拿不到标题。
        val url = "https://example.invalid/a?b=1"
        val linkSpan = parseCommentSpans("看 $url 啊", emptyMap())
            .filterIsInstance<RichSpan.Link>()
            .single()
        assertEquals(url, linkSpan.text)
        assertEquals(url, linkSpan.url)
        assertEquals(RichLinkIcon.None, linkSpan.icon)
    }

    @Test
    fun `认不出去处的 at 产出 mid 为 0 的提及,不产出链接`() {
        // 渲染层据 mid 判断画不画链接:一个指向错误用户的链接比不可点更糟。
        val spans = parseCommentSpans("@某人 你看", emptyMap())
        assertEquals(0L, spans.filterIsInstance<RichSpan.Mention>().single().mid)
    }

    @Test
    fun `表情复制出去的是占位符原文`() {
        // 进剪贴板的应该是 `[doge]` —— 粘出去对方看得懂,粘出去一个空洞看不懂。
        val spans = parseCommentSpans("哈哈[doge]", mapOf("[doge]" to "https://example.invalid/d.png"))
        assertEquals("哈哈[doge]", spans.plainText())
    }

    @Test
    fun `一个 token 都没有时整段就是一节纯文字`() {
        assertEquals(listOf(RichSpan.Text("今天天气不错")), parseCommentSpans("今天天气不错", emptyMap()))
    }
}
