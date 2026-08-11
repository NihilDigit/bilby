package dev.bilby.ui.article

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.bilby.data.model.ArticleSpan
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.inlineEmoteSize
import dev.bilby.ui.theme.LocalMentionColor

/**
 * 一段正文。表情走 `InlineTextContent` 内联进文字流(与评论区同一条理由:占位符若在下面另摆
 * 一排,同一个表情出现两次就对不上了),链接与 @提及走 `LinkAnnotation`,由文本层负责命中、
 * 按压反馈和读屏。
 */
@Composable
internal fun ArticleParagraph(
    spans: List<ArticleSpan>,
    style: TextStyle,
    onLinkClick: (String) -> Unit,
    onMentionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    color: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
) {
    val mentionColor = LocalMentionColor.current
    val surface = MaterialTheme.colorScheme.surface
    val currentOnLink by rememberUpdatedState(onLinkClick)
    val currentOnMention by rememberUpdatedState(onMentionClick)
    val emojis = remember(spans) { spans.filterIsInstance<ArticleSpan.Emoji>() }

    val text = remember(spans, mentionColor, surface) {
        var emojiIndex = 0
        buildAnnotatedString {
            for (span in spans) {
                when (span) {
                    is ArticleSpan.Text -> withStyle(span.toSpanStyle(surface)) { append(span.text) }

                    is ArticleSpan.Link -> withLink(clickable(mentionColor, "article-link") { currentOnLink(span.url) }) {
                        // 站外链接和正文同色时读不出它能点,补一个链条记号 —— 与 PiliPlus 在
                        // RICH_TEXT_NODE_TYPE_WEB 前面加 U+1F517 是同一处理。
                        append(if (span.showIcon) "🔗${span.text}" else span.text)
                    }

                    is ArticleSpan.Mention ->
                        withLink(clickable(mentionColor, "article-mention") { currentOnMention(span.mid) }) {
                            append(span.text)
                        }

                    is ArticleSpan.Emoji -> {
                        appendInlineContent(emojiId(emojiIndex), span.alt.ifBlank { "[表情]" })
                        emojiIndex++
                    }

                    // 公式保留 LaTeX 源码(见 ArticleSpan.Formula 的说明),用等宽区分开,
                    // 免得夹在正文里读成一串乱码。
                    is ArticleSpan.Formula -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                        append(span.latex)
                    }
                }
            }
        }
    }

    // 尺寸按 sp 给(跟着系统字号缩放),图片撑满占位符即可。判断在 inlineEmoteSize 里,
    // 评论区共用同一处。
    val inline = emojis.mapIndexed { index, emoji ->
        val size = inlineEmoteSize(style, emoji.scale)
        emojiId(index) to InlineTextContent(
            Placeholder(size, size, PlaceholderVerticalAlign.TextCenter),
        ) {
            BiliAsyncImage(url = emoji.url, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }.toMap()

    Text(
        text = text,
        style = style,
        color = color,
        inlineContent = inline,
        textAlign = if (centered) TextAlign.Center else TextAlign.Unspecified,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

private fun emojiId(index: Int) = "article-emoji-$index"

private fun clickable(color: Color, tag: String, onClick: () -> Unit) = LinkAnnotation.Clickable(
    tag = tag,
    styles = TextLinkStyles(style = SpanStyle(color = color)),
) { onClick() }

/**
 * 作者指定的字色**先过一遍对比度再决定要不要用**。这些颜色全是照网页白底挑的,深色主题下
 * 有相当一部分会掉到看不清;不达 3:1 就丢掉,退回正文色。PiliPlus 在
 * `opus_content.dart` 的 `_getSpan` 里做的是同一件事。
 */
private fun ArticleSpan.Text.toSpanStyle(surface: Color) = SpanStyle(
    color = colorArgb?.let { Color(it) }?.takeIf { it.readableOn(surface) } ?: Color.Unspecified,
    fontSize = fontSizeSp?.sp ?: TextUnit.Unspecified,
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = if (strikethrough) TextDecoration.LineThrough else null,
)

private fun Color.readableOn(surface: Color): Boolean {
    val a = luminance()
    val b = surface.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f) > MIN_CONTRAST
}

/** WCAG AA 对大号文字与图形的下限。正文那档 4.5 会把大半作者色都判掉,而它们本来是有意义的。 */
private const val MIN_CONTRAST = 3.0f
