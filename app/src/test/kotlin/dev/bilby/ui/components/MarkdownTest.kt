package dev.bilby.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 只测解析,不测渲染:记号该不该生效是这段代码自己的判断,而 SpanStyle 的映射是一一对应的
 * 转写,断言它等于把同一张表抄第二遍。
 */
class MarkdownTest {

    @Test
    fun `强调两端必须紧贴非空白字符`() {
        // 「2 * 3 * 4」不是斜体。助理答案里出现乘号的概率远高于用 * 包一个带空格的短语。
        assertEquals(
            listOf(MdSpan("时长 2 * 3 * 4 分钟")),
            parseMarkdown("时长 2 * 3 * 4 分钟").single().spans,
        )
        assertEquals(
            listOf(MdSpan("节奏最好的是 "), MdSpan("这一期", bold = true), MdSpan(",")),
            parseMarkdown("节奏最好的是 **这一期**,").single().spans,
        )
    }

    @Test
    fun `下划线不是斜体记号`() {
        // page_size 这类标识符在这个 app 的语境里比斜体常见得多。
        assertEquals(
            listOf(MdSpan("参数是 page_size 和 web_location")),
            parseMarkdown("参数是 page_size 和 web_location").single().spans,
        )
    }

    @Test
    fun `行内代码原样保留,内部记号不再解析`() {
        assertEquals(
            listOf(MdSpan("传 "), MdSpan("a*b*c", code = true), MdSpan(" 进去")),
            parseMarkdown("传 `a*b*c` 进去").single().spans,
        )
    }

    @Test
    fun `配不上对的记号当普通字符`() {
        // 模型写了半个记号时,吞掉后半句比露出一个 ** 糟糕得多。
        assertEquals(listOf(MdSpan("**没有闭合")), parseMarkdown("**没有闭合").single().spans)
        assertEquals(listOf(MdSpan("反引号 ` 落单")), parseMarkdown("反引号 ` 落单").single().spans)
    }

    @Test
    fun `列表与标题按行识别`() {
        val blocks = parseMarkdown(
            """
            ## 两条推荐
            - 入门向
            2) 进阶向
            正文一句话
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MdBlock.Heading(2, listOf(MdSpan("两条推荐"))),
                MdBlock.ListItem("•", listOf(MdSpan("入门向"))),
                MdBlock.ListItem("2.", listOf(MdSpan("进阶向"))),
                MdBlock.Paragraph(listOf(MdSpan("正文一句话"))),
            ),
            blocks,
        )
    }

    @Test
    fun `相邻行不合并`() {
        // 标准 markdown 会把这两行接成一段。模型换行通常是在断句,接起来会把两条推荐黏成一句。
        assertEquals(2, parseMarkdown("第一条\n第二条").size)
    }

    @Test
    fun `嵌套记号`() {
        assertEquals(
            listOf(MdSpan("很重要", bold = true, italic = true)),
            parseMarkdown("***很重要***").single().spans,
        )
        assertEquals(
            listOf(MdSpan("传 ", bold = true), MdSpan("wts", bold = true, code = true)),
            parseMarkdown("**传 `wts`**").single().spans,
        )
    }
}
