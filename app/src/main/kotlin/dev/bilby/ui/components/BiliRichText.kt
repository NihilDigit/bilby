package dev.bilby.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OndemandVideo
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.AnnotatedString
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
import dev.bilby.data.model.RichLinkIcon
import dev.bilby.data.model.RichSpan
import dev.bilby.ui.theme.LocalMentionColor

/**
 * 一段富文本。**专栏、动态、评论三处共用这一份** —— 它们的解析各不相同(见 [RichSpan]),
 * 而"一段能点的文字长什么样、点了去哪"只有这一份实现。
 *
 * 表情走 `InlineTextContent` 内联进文字流:占位符若在下面另摆一排,同一个表情出现两次就
 * 对不上了。链接、@提及、时间戳走 `LinkAnnotation`,由文本层负责命中、按压反馈和读屏 ——
 * 手写 `pointerInput` 那版要把 `TextLayoutResult` 存进 State 再当它的 key,于是每次布局都要
 * 撤销重建一次手势检测器,而评论列表滚动时那是热路径。
 *
 * @param prefix 接在正文之前、和正文同属一段文字流的一截。楼中楼预览层用它把发言人的名字
 *   放在正文前面 —— 名字单独一个 `Text` 的话,一条三个字的回复也要占掉两行,而预览层拢共
 *   只有两三行的位置。接进同一段之后,名字和正文一起折行、一起按 [maxLines] 截断。
 * @param onSeek 点了时间戳。**为 null 时时间戳画成普通文字**,不是画成能点然后什么都不做 ——
 *   动态详情页的评论区就没有可跳的视频,专栏和动态正文里的 `12:34` 更只是一串数字。一个看
 *   起来能点、点了没反应的东西比一段普通文字更糟:人会以为自己没点准,反复去点。
 */
@Composable
fun BiliRichText(
    spans: List<RichSpan>,
    style: TextStyle,
    onLinkClick: (String) -> Unit,
    onMentionClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    centered: Boolean = false,
    color: Color = LocalContentColor.current,
    maxLines: Int = Int.MAX_VALUE,
    prefix: AnnotatedString? = null,
    onSeek: ((Long) -> Unit)? = null,
) {
    val mentionColor = LocalMentionColor.current
    val surface = MaterialTheme.colorScheme.surface
    // 回调走 rememberUpdatedState:注解串是 remember 出来的,直接捕获会把第一次组合时的那份
    // 焊进去,而评论区那个 onSeek 捕获着当时的 MediaController。
    val currentOnLink by rememberUpdatedState(onLinkClick)
    val currentOnMention by rememberUpdatedState(onMentionClick)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val emojis = remember(spans) { spans.filterIsInstance<RichSpan.Emoji>() }
    val hasVideoIcon = remember(spans) {
        spans.any { it is RichSpan.Link && it.icon == RichLinkIcon.Video }
    }

    // 进注解串的 key,不能只靠 currentOnSeek —— 那一份每次重组都是新对象,拿它当 key 等于
    // 每次重组都重拼一遍整段。能不能跳是个布尔,跳到哪由回调决定。
    val seekable = onSeek != null

    val text = remember(spans, mentionColor, surface, prefix, seekable) {
        var emojiIndex = 0
        buildAnnotatedString {
            prefix?.let { append(it) }
            for (span in spans) {
                when (span) {
                    is RichSpan.Text -> withStyle(span.toSpanStyle(surface)) { append(span.text) }

                    is RichSpan.Link -> withLink(clickable(mentionColor, "rich-link") { currentOnLink(span.url) }) {
                        when (span.icon) {
                            // 站外链接和正文同色时读不出它能点,补一个链条记号 —— 与 PiliPlus
                            // 在 RICH_TEXT_NODE_TYPE_WEB 前面加 U+1F517 是同一处理。
                            RichLinkIcon.Web -> append("🔗")
                            // **不传 alternateText,用它默认的替换字符。** 传空串是不行的:
                            // 占位符的注解盖的就是这段文字,长度为 0 就等于没有占位符,图标不画。
                            RichLinkIcon.Video -> appendInlineContent(VideoIconId)
                            RichLinkIcon.None -> Unit
                        }
                        append(span.text)
                    }

                    // **mid 为 0 的只染色,不成链接。** 不是所有 @ 都能确定指向谁(见
                    // `data/CommentRichText.kt` 的 resolveMentions),而一个指向错误用户的
                    // 链接比不可点更糟。染色仍然要有:那一截确实是在叫一个人。
                    is RichSpan.Mention -> if (span.mid == 0L) {
                        withStyle(SpanStyle(color = mentionColor)) { append(span.text) }
                    } else {
                        withLink(clickable(mentionColor, "rich-mention") { currentOnMention(span.mid) }) {
                            append(span.text)
                        }
                    }

                    // 时间戳加下划线:它和 @、链接同色,而一句话里的 "12:34" 本来就长得像正文,
                    // 光靠颜色分不出这一串是能点的。跳不了的时候连颜色一起去掉,见 [onSeek]。
                    is RichSpan.Timestamp -> if (seekable) {
                        withLink(
                            LinkAnnotation.Clickable(
                                tag = "rich-timestamp",
                                styles = TextLinkStyles(
                                    style = SpanStyle(
                                        color = mentionColor,
                                        textDecoration = TextDecoration.Underline,
                                    ),
                                ),
                            ) { currentOnSeek?.invoke(span.millis) },
                        ) { append(span.text) }
                    } else {
                        append(span.text)
                    }

                    is RichSpan.Emoji -> {
                        appendInlineContent(emojiId(emojiIndex), span.alt.ifBlank { "[表情]" })
                        emojiIndex++
                    }

                    // 公式保留 LaTeX 源码(见 RichSpan.Formula 的说明),用等宽区分开,
                    // 免得夹在正文里读成一串乱码。
                    is RichSpan.Formula -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
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
    }.toMap() + if (!hasVideoIcon) {
        emptyMap()
    } else {
        // 图标跟着字号走,不跟着表情走:它是这段文字的一部分,比正文大一圈会把行撑开。
        val iconSize = style.fontSize
        mapOf(
            VideoIconId to InlineTextContent(
                Placeholder(iconSize, iconSize, PlaceholderVerticalAlign.TextCenter),
            ) {
                Icon(
                    imageVector = Icons.Outlined.OndemandVideo,
                    contentDescription = null,
                    tint = mentionColor,
                    modifier = Modifier.fillMaxSize(),
                )
            },
        )
    }

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

private fun emojiId(index: Int) = "rich-emoji-$index"

/**
 * 站内视频链接前那枚图标的 inline content id。**一个 id 就够**:图标只有一种,而
 * `InlineTextContent` 的 id 只需要在同一段 [AnnotatedString] 里区分不同的占位符 ——
 * 表情要一个一个 id,是因为每个表情的图不一样。
 */
private const val VideoIconId = "rich-video-icon"

private fun clickable(color: Color, tag: String, onClick: () -> Unit) = LinkAnnotation.Clickable(
    tag = tag,
    styles = TextLinkStyles(style = SpanStyle(color = color)),
) { onClick() }

/**
 * 作者指定的字色**先过一遍对比度再决定要不要用**。这些颜色全是照网页白底挑的,深色主题下
 * 有相当一部分会掉到看不清;不达 3:1 就丢掉,退回正文色。PiliPlus 在
 * `opus_content.dart` 的 `_getSpan` 里做的是同一件事。
 */
private fun RichSpan.Text.toSpanStyle(surface: Color) = SpanStyle(
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
