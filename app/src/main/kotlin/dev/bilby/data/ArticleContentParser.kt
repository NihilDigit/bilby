package dev.bilby.data

import dev.bilby.api.dto.ArticlePicDto
import dev.bilby.api.dto.LinkCardBodyDto
import dev.bilby.api.dto.ParagraphDto
import dev.bilby.api.dto.TextNodeDto
import dev.bilby.api.toHttpsUrl
import dev.bilby.data.model.ArticleBlock
import dev.bilby.data.model.ArticleImage
import dev.bilby.data.model.ArticleSpan
import dev.bilby.data.model.LinkCardKind
import dev.bilby.data.model.ListEntry

/**
 * 段落数组 -> 渲染块。纯函数,不碰网络,单测直接喂 DTO(见 ArticleContentParserTest)。
 *
 * `para_type` 的取值与各自的子对象来自 PiliPlus 的
 * `lib/pages/article/widgets/opus_content.dart:209-745`,逐条对照抄下来的,不是猜的。
 */
internal fun List<ParagraphDto>.toArticleBlocks(): List<ArticleBlock> = mapNotNull { it.toBlock() }

private fun ParagraphDto.toBlock(): ArticleBlock? = when (paraType) {
    PARA_TEXT, PARA_QUOTE -> text?.nodes.orEmpty().toSpans().takeIf { it.isNotEmpty() }?.let {
        ArticleBlock.Paragraph(spans = it, centered = align == ALIGN_CENTER, quote = paraType == PARA_QUOTE)
    }

    PARA_PIC -> pic?.pics.orEmpty().mapNotNull { it.toImage() }.takeIf { it.isNotEmpty() }
        ?.let { ArticleBlock.Images(it) }

    // 带图的分割线是作者排的版式的一部分,不能替换成一条普通的线。
    PARA_LINE -> ArticleBlock.Divider(line?.pic?.url?.takeIf { it.isNotBlank() }?.toHttpsUrl())

    PARA_LIST -> list?.items.orEmpty()
        .map { ListEntry(order = it.order, spans = it.nodes.toSpans()) }
        .filter { it.spans.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.let { ArticleBlock.BulletList(items = it, ordered = list?.style == LIST_STYLE_ORDERED) }

    PARA_LINK_CARD -> linkCard?.card?.toLinkCard()

    PARA_CODE -> code?.content?.takeIf { it.isNotBlank() }?.let {
        // lang 形如 `language-kotlin`,偶尔是 `language-clike`。这里只取名字给界面上那个
        // 语言角标用,没有做语法高亮 —— 高亮要引一整套词法库,而专栏里的代码块多是几行示例。
        ArticleBlock.Code(content = it, language = code?.lang.orEmpty().removePrefix("language-"))
    }

    PARA_HEADING -> heading?.nodes.orEmpty().toSpans().takeIf { it.isNotEmpty() }
        ?.let { ArticleBlock.Heading(it) }

    // 未知段落类型退回它的文字。PiliPlus 在这里画一行红字提示不支持,那是调试期的做法;
    // 读者要的是读到内容,读不到的那一块安静地不出现,比一行红字更接近"正常的一篇文章"。
    else -> text?.nodes.orEmpty().toSpans().takeIf { it.isNotEmpty() }?.let { ArticleBlock.Paragraph(it) }
}

private fun ArticlePicDto.toImage(): ArticleImage? = url.takeIf { it.isNotBlank() }?.let {
    ArticleImage(url = it.toHttpsUrl(), width = width.toInt(), height = height.toInt())
}

private fun LinkCardBodyDto.toLinkCard(): ArticleBlock.LinkCard? = when (type) {
    "LINK_CARD_TYPE_UGC" -> ugc?.let {
        card(it.title, it.descSecond, it.cover, it.jumpUrl, LinkCardKind.Video)
    }

    "LINK_CARD_TYPE_LIVE" -> live?.let {
        card(it.title, listOf(it.descFirst, it.descSecond).firstOrNull(String::isNotBlank).orEmpty(), it.cover, it.jumpUrl, LinkCardKind.Live)
    }

    "LINK_CARD_TYPE_OPUS" -> opus?.let {
        card(it.title, "", it.cover, it.jumpUrl, LinkCardKind.Article)
    }

    "LINK_CARD_TYPE_MUSIC" -> music?.let {
        card(it.title, it.label, it.cover, it.jumpUrl, LinkCardKind.Music)
    }

    "LINK_CARD_TYPE_COMMON" -> common?.let {
        card(it.title, listOf(it.desc1, it.desc2).firstOrNull(String::isNotBlank).orEmpty(), it.cover, it.jumpUrl, LinkCardKind.Common)
    }

    // 引用的内容没了。仍然画出来:作者是在这儿引了个东西,整块抹掉会让上下文断在半句上。
    "LINK_CARD_TYPE_ITEM_NULL" -> ArticleBlock.LinkCard(
        title = itemNull?.text.orEmpty(),
        description = "",
        coverUrl = "",
        destinationUrl = "",
        kind = LinkCardKind.Gone,
    )

    // 商品卡(LINK_CARD_TYPE_GOODS)落在这里,故意不画:带货卡片在这个应用里没有落点。
    else -> null
}

private fun card(
    title: String,
    description: String,
    cover: String,
    jumpUrl: String,
    kind: LinkCardKind,
): ArticleBlock.LinkCard? = title.takeIf { it.isNotBlank() }?.let {
    ArticleBlock.LinkCard(
        title = it,
        description = description,
        coverUrl = cover.toHttpsUrl(),
        destinationUrl = jumpUrl.toHttpsUrl(),
        kind = kind,
    )
}

private fun List<TextNodeDto>.toSpans(): List<ArticleSpan> = mapNotNull { it.toSpan() }

private fun TextNodeDto.toSpan(): ArticleSpan? = when {
    type == "TEXT_NODE_TYPE_FORMULA" && formula != null ->
        formula.latexContent.takeIf { it.isNotBlank() }?.let(ArticleSpan::Formula)

    type == "TEXT_NODE_TYPE_RICH" && rich != null -> when (rich.type) {
        "RICH_TEXT_NODE_TYPE_EMOJI" -> rich.emoji
            ?.let { listOf(it.webpUrl, it.gifUrl, it.iconUrl).firstOrNull(String::isNotBlank) }
            ?.let {
                ArticleSpan.Emoji(
                    url = it.toHttpsUrl(),
                    // 表情读屏时念的是 `[doge]` 这种原文,不是 URL。
                    alt = rich.origText.ifBlank { rich.text },
                    scale = rich.emoji.size.toFloat().takeIf { size -> size > 0f } ?: 1f,
                )
            }

        "RICH_TEXT_NODE_TYPE_AT" -> rich.rid.toLongOrNull()
            ?.let { ArticleSpan.Mention(text = rich.text, mid = it) }
            ?: rich.text.takeIf { it.isNotBlank() }?.let { ArticleSpan.Text(it) }

        // 纯文字节点也会包在 rich 里,这时它不是链接,不能染成可点的颜色。
        "RICH_TEXT_NODE_TYPE_TEXT" -> rich.text.takeIf { it.isNotBlank() }?.let {
            ArticleSpan.Text(
                text = it,
                bold = rich.style?.bold == true,
                italic = rich.style?.italic == true,
                strikethrough = rich.style?.strikethrough == true,
            )
        }

        // 站外链接、BV 号、话题、抽奖、投票都落在这里。有地址就是链接,没有(抽奖那种要
        // 自己拼 h5 地址)就退回纯文字 —— 画一个点了没反应的链接比不画更糟。
        else -> when {
            rich.jumpUrl.isNotBlank() -> ArticleSpan.Link(
                text = rich.text,
                url = rich.jumpUrl.toHttpsUrl(),
                showIcon = rich.type == "RICH_TEXT_NODE_TYPE_WEB",
            )

            rich.text.isNotBlank() -> ArticleSpan.Text(rich.text)
            else -> null
        }
    }

    else -> word?.words?.takeIf { it.isNotEmpty() }?.let {
        ArticleSpan.Text(
            text = it,
            bold = word.style?.bold == true,
            italic = word.style?.italic == true,
            strikethrough = word.style?.strikethrough == true,
            colorArgb = word.color.parseHexColor(),
            fontSizeSp = word.effectiveFontSize(),
        )
    }
}

/**
 * `font_size` 缺省时按 `font_level` 兜底,和 PiliPlus 的 `Word.effectiveFontSize` 同一套值:
 * small 13sp,其余 16sp(旧版 HTML 专栏的正文基准)。
 *
 * 返回 null 表示"按正文默认字号",不是 16 —— 我们的正文字号由主题定,硬写 16 会让专栏正文
 * 和应用里其他地方的正文差半档。只有作者真的调过字号才带出去。
 */
private fun dev.bilby.api.dto.ArticleWordDto.effectiveFontSize(): Float? = when {
    fontSize > 0 -> fontSize.toFloat()
    fontLevel == "small" -> 13f
    else -> null
}

/** `#RRGGBB` 或 `#AARRGGBB`。认不出来当作没给,不抛异常把整篇正文搞没。 */
internal fun String.parseHexColor(): Int? {
    if (!startsWith("#")) return null
    val hex = drop(1)
    val value = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> (0xFF000000L or value).toInt()
        8 -> value.toInt()
        else -> null
    }
}

private const val PARA_TEXT = 1
private const val PARA_PIC = 2
private const val PARA_LINE = 3
private const val PARA_QUOTE = 4
private const val PARA_LIST = 5
private const val PARA_LINK_CARD = 6
private const val PARA_CODE = 7
private const val PARA_HEADING = 8

private const val ALIGN_CENTER = 1
private const val LIST_STYLE_ORDERED = 2
