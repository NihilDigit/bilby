package dev.bilby.data

import dev.bilby.api.dto.ArticleCodeDto
import dev.bilby.api.dto.ArticleEmojiDto
import dev.bilby.api.dto.ArticleLineDto
import dev.bilby.api.dto.ArticlePicDto
import dev.bilby.api.dto.ArticleRichDto
import dev.bilby.api.dto.ArticleTextStyleDto
import dev.bilby.api.dto.ArticleWordDto
import dev.bilby.api.dto.CardItemNullDto
import dev.bilby.api.dto.CardUgcDto
import dev.bilby.api.dto.LinkCardBodyDto
import dev.bilby.api.dto.LinkCardDto
import dev.bilby.api.dto.ParagraphDto
import dev.bilby.api.dto.ParagraphListDto
import dev.bilby.api.dto.ParagraphListItemDto
import dev.bilby.api.dto.ParagraphPicDto
import dev.bilby.api.dto.RichTextDto
import dev.bilby.api.dto.TextNodeDto
import dev.bilby.data.model.ArticleBlock
import dev.bilby.data.model.ArticleSpan
import dev.bilby.data.model.LinkCardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 富文本解析。**测的是分发本身容易错的那几处**,不是把 `toArticleBlocks` 的每个分支再抄一遍
 * 断言:`para_type` 与子对象的对应关系、节点类型在没有跳转地址时该退回纯文字、以及协议相对
 * 地址要补 https —— 这三条错了都表现为"这一段没画出来"或"点了没反应",在界面上指不出原因。
 */
class ArticleContentParserTest {

    private fun word(text: String, color: String = "", bold: Boolean = false) =
        TextNodeDto(type = "TEXT_NODE_TYPE_WORD", word = ArticleWordDto(words = text, color = color, style = ArticleTextStyleDto(bold = bold)))

    @Test
    fun `para_type 1 是正文,4 是引用,两者结构相同只差标记`() {
        val blocks = listOf(
            ParagraphDto(paraType = 1, text = RichTextDto(listOf(word("正文")))),
            ParagraphDto(paraType = 4, text = RichTextDto(listOf(word("引用")))),
        ).toArticleBlocks()

        assertEquals(2, blocks.size)
        assertEquals(false, (blocks[0] as ArticleBlock.Paragraph).quote)
        assertEquals(true, (blocks[1] as ArticleBlock.Paragraph).quote)
    }

    @Test
    fun `para_type 8 是标题,不是又一段正文`() {
        val blocks = listOf(
            ParagraphDto(paraType = 8, heading = RichTextDto(listOf(word("小节")))),
        ).toArticleBlocks()

        assertTrue(blocks.single() is ArticleBlock.Heading)
    }

    @Test
    fun `align 为 1 才是居中`() {
        val blocks = listOf(
            ParagraphDto(paraType = 1, align = 1, text = RichTextDto(listOf(word("居中")))),
            ParagraphDto(paraType = 1, align = 0, text = RichTextDto(listOf(word("左对齐")))),
        ).toArticleBlocks()

        assertEquals(true, (blocks[0] as ArticleBlock.Paragraph).centered)
        assertEquals(false, (blocks[1] as ArticleBlock.Paragraph).centered)
    }

    @Test
    fun `图片走 pic pics,协议相对地址补成 https`() {
        val blocks = listOf(
            ParagraphDto(
                paraType = 2,
                pic = ParagraphPicDto(listOf(ArticlePicDto(url = "//i0.hdslb.com/a.jpg", width = 800.0, height = 600.0))),
            ),
        ).toArticleBlocks()

        val image = (blocks.single() as ArticleBlock.Images).images.single()
        assertEquals("https://i0.hdslb.com/a.jpg", image.url)
        assertEquals(800, image.width)
    }

    @Test
    fun `长图按高宽比认出来,普通图不算`() {
        val blocks = listOf(
            ParagraphDto(paraType = 2, pic = ParagraphPicDto(listOf(ArticlePicDto(url = "//a", width = 100.0, height = 900.0)))),
            ParagraphDto(paraType = 2, pic = ParagraphPicDto(listOf(ArticlePicDto(url = "//b", width = 100.0, height = 150.0)))),
        ).toArticleBlocks()

        assertEquals(true, (blocks[0] as ArticleBlock.Images).images.single().isLongImage)
        assertEquals(false, (blocks[1] as ArticleBlock.Images).images.single().isLongImage)
    }

    @Test
    fun `para_type 3 不带图时是分割线,带图时保留那张图`() {
        val blocks = listOf(
            ParagraphDto(paraType = 3),
            ParagraphDto(paraType = 3, line = ArticleLineDto(ArticlePicDto(url = "http://i0.hdslb.com/l.png"))),
        ).toArticleBlocks()

        assertNull((blocks[0] as ArticleBlock.Divider).imageUrl)
        assertEquals("https://i0.hdslb.com/l.png", (blocks[1] as ArticleBlock.Divider).imageUrl)
    }

    @Test
    fun `列表 style 为 2 是有序,序号取 order 不取下标`() {
        val blocks = listOf(
            ParagraphDto(
                paraType = 5,
                list = ParagraphListDto(
                    style = 2,
                    items = listOf(
                        ParagraphListItemDto(order = 3, nodes = listOf(word("第三条"))),
                        ParagraphListItemDto(order = 4, nodes = listOf(word("第四条"))),
                    ),
                ),
            ),
        ).toArticleBlocks()

        val list = blocks.single() as ArticleBlock.BulletList
        assertEquals(true, list.ordered)
        assertEquals(listOf(3, 4), list.items.map { it.order })
    }

    @Test
    fun `代码块去掉 language 前缀`() {
        val blocks = listOf(
            ParagraphDto(paraType = 7, code = ArticleCodeDto(content = "val a = 1", lang = "language-kotlin")),
        ).toArticleBlocks()

        assertEquals("kotlin", (blocks.single() as ArticleBlock.Code).language)
    }

    @Test
    fun `链接卡片按 card type 挑子对象,商品卡不画`() {
        val blocks = listOf(
            ParagraphDto(
                paraType = 6,
                linkCard = LinkCardDto(
                    LinkCardBodyDto(
                        type = "LINK_CARD_TYPE_UGC",
                        ugc = CardUgcDto(title = "视频标题", descSecond = "10万播放", cover = "//c.jpg", jumpUrl = "//www.bilibili.com/video/BV1"),
                    ),
                ),
            ),
            ParagraphDto(paraType = 6, linkCard = LinkCardDto(LinkCardBodyDto(type = "LINK_CARD_TYPE_GOODS"))),
        ).toArticleBlocks()

        val card = blocks.single() as ArticleBlock.LinkCard
        assertEquals(LinkCardKind.Video, card.kind)
        assertEquals("https://www.bilibili.com/video/BV1", card.destinationUrl)
    }

    @Test
    fun `失效的引用卡片仍然画出来,但没有可点的地址`() {
        val blocks = listOf(
            ParagraphDto(
                paraType = 6,
                linkCard = LinkCardDto(LinkCardBodyDto(type = "LINK_CARD_TYPE_ITEM_NULL", itemNull = CardItemNullDto("内容已失效"))),
            ),
        ).toArticleBlocks()

        val card = blocks.single() as ArticleBlock.LinkCard
        assertEquals(LinkCardKind.Gone, card.kind)
        assertEquals("", card.destinationUrl)
    }

    @Test
    fun `rich 里的纯文字节点不是链接`() {
        val blocks = listOf(
            ParagraphDto(
                paraType = 1,
                text = RichTextDto(
                    listOf(
                        TextNodeDto(type = "TEXT_NODE_TYPE_RICH", rich = ArticleRichDto(type = "RICH_TEXT_NODE_TYPE_TEXT", text = "普通文字")),
                    ),
                ),
            ),
        ).toArticleBlocks()

        assertTrue((blocks.single() as ArticleBlock.Paragraph).spans.single() is ArticleSpan.Text)
    }

    @Test
    fun `没有跳转地址的富文本节点退回纯文字,不做成点不动的链接`() {
        val blocks = listOf(
            ParagraphDto(
                paraType = 1,
                text = RichTextDto(
                    listOf(
                        TextNodeDto(type = "TEXT_NODE_TYPE_RICH", rich = ArticleRichDto(type = "RICH_TEXT_NODE_TYPE_LOTTERY", text = "互动抽奖")),
                        TextNodeDto(type = "TEXT_NODE_TYPE_RICH", rich = ArticleRichDto(type = "RICH_TEXT_NODE_TYPE_WEB", text = "站外", jumpUrl = "//example.com")),
                    ),
                ),
            ),
        ).toArticleBlocks()

        val spans = (blocks.single() as ArticleBlock.Paragraph).spans
        assertTrue(spans[0] is ArticleSpan.Text)
        assertEquals(ArticleSpan.Link("站外", "https://example.com", showIcon = true), spans[1])
    }

    @Test
    fun `at 节点的 rid 不是数字时退回纯文字,不产出 mid 为 0 的提及`() {
        val blocks = listOf(
            ParagraphDto(
                paraType = 1,
                text = RichTextDto(
                    listOf(
                        TextNodeDto(type = "TEXT_NODE_TYPE_RICH", rich = ArticleRichDto(type = "RICH_TEXT_NODE_TYPE_AT", text = "@某人", rid = "12345")),
                        TextNodeDto(type = "TEXT_NODE_TYPE_RICH", rich = ArticleRichDto(type = "RICH_TEXT_NODE_TYPE_AT", text = "@坏数据", rid = "")),
                    ),
                ),
            ),
        ).toArticleBlocks()

        val spans = (blocks.single() as ArticleBlock.Paragraph).spans
        assertEquals(ArticleSpan.Mention("@某人", 12345L), spans[0])
        assertTrue(spans[1] is ArticleSpan.Text)
    }

    @Test
    fun `表情按 webp gif icon 的顺序取第一个非空`() {
        val blocks = listOf(
            ParagraphDto(
                paraType = 1,
                text = RichTextDto(
                    listOf(
                        TextNodeDto(
                            type = "TEXT_NODE_TYPE_RICH",
                            rich = ArticleRichDto(
                                type = "RICH_TEXT_NODE_TYPE_EMOJI",
                                origText = "[doge]",
                                emoji = ArticleEmojiDto(webpUrl = "", gifUrl = "//g.gif", iconUrl = "//i.png", size = 2.0),
                            ),
                        ),
                    ),
                ),
            ),
        ).toArticleBlocks()

        val emoji = (blocks.single() as ArticleBlock.Paragraph).spans.single() as ArticleSpan.Emoji
        assertEquals("https://g.gif", emoji.url)
        assertEquals("[doge]", emoji.alt)
        assertEquals(2f, emoji.scale, 0f)
    }

    @Test
    fun `未知段落类型退回它带的文字,而不是整段丢掉`() {
        val blocks = listOf(
            ParagraphDto(paraType = 99, text = RichTextDto(listOf(word("将来才有的段落")))),
            ParagraphDto(paraType = 99),
        ).toArticleBlocks()

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is ArticleBlock.Paragraph)
    }

    @Test
    fun `字色认 6 位和 8 位十六进制,认不出来当作没给`() {
        assertEquals(0xFF66CCFF.toInt(), "#66CCFF".parseHexColor())
        assertEquals(0x8066CCFF.toInt(), "#8066CCFF".parseHexColor())
        assertNull("66CCFF".parseHexColor())
        assertNull("#GGG".parseHexColor())
        assertNull("".parseHexColor())
    }
}
