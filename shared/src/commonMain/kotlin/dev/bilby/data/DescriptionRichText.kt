package dev.bilby.data

import dev.bilby.api.dto.DescSegmentDto
import dev.bilby.data.model.RichLinkIcon
import dev.bilby.data.model.RichSpan

/**
 * 视频简介解析成 [RichSpan] 序列,由 `ui/components/BiliRichText.kt` 渲染。
 *
 * @ 来自 `desc_v2` 的 type 2 段,自带 mid,不用像评论那样按名字去配人。普通文本段里再认
 * 网址、av/BV 号和时间点,照 PiliPlus `introduction/ugc/view.dart` 的 `buildDesc`。
 *
 * **不复用评论的 [parseCommentSpans]。** 那边认表情占位符,查不到表的 `[xxx]` 会落进"认不出
 * 去处的 @"一支被染色;简介没有表情,一段「[预告]」不该变色。那边也不认 av/BV 号。
 *
 * @param segments `desc_v2`。为空时(离线缓存只存了纯文本)退回 [plain],整段当普通文本认,
 *   链接和时间点照样能点,@ 只剩字面。
 */
internal fun parseDescriptionSpans(segments: List<DescSegmentDto>, plain: String): List<RichSpan> {
    if (segments.isEmpty()) return scanDescriptionText(plain)
    return segments.flatMap { segment ->
        when (segment.type) {
            MENTION_SEGMENT -> listOf(RichSpan.Mention("@${segment.rawText}", segment.bizId))
            // 认不得的类型按文字留着。PiliPlus 在这里返回空,那会让一段字凭空消失。
            else -> scanDescriptionText(segment.rawText)
        }
    }
}

/**
 * 普通文本里能点的三样:网址、av/BV 号、时间点。
 *
 * 网址只吃可打印 ASCII,不用 `\S+`:简介里网址后面直接接中文是常态("……见 https://x.com/a。"),
 * `\S` 会把句号乃至后半句一起吞进链接。真实的中文路径都是百分号编码过的。
 *
 * av/BV 的前缀不分大小写,照 PiliPlus(`caseSensitive: false`);BV 号本体区分大小写,
 * 所以只放宽前缀两个字母。前后不许再粘 ASCII 字母数字,否则 "BV1xx4x1x7xxabc" 这种会被截出
 * 一个错的号。中文紧挨着号码是常态("见BV1xx…"),而 `\b` 在 Java 正则里只认 ASCII 单词
 * 字符,正好放行。
 */
private val DescriptionTokenRegex = Regex(
    """https?://[\x21-\x7E]+|\b[aA][vV]\d+\b|\b[bB][vV][0-9A-Za-z]{10}\b|(?<!\d)(?:\d{1,2}[:：])?\d{1,2}[:：]\d{2}(?!\d)""",
)

private fun scanDescriptionText(text: String): List<RichSpan> {
    val spans = mutableListOf<RichSpan>()
    fun addText(part: String) {
        if (part.isNotEmpty()) spans += RichSpan.Text(part)
    }
    var last = 0
    for (match in DescriptionTokenRegex.findAll(text)) {
        addText(text.substring(last, match.range.first))
        val token = match.value
        spans += when {
            token.startsWith("http", ignoreCase = true) -> RichSpan.Link(token, token)
            // 交给调用方的 onOpenLink,那一层按站内解析,于是落在应用里的视频页。
            token.startsWith("av", ignoreCase = true) ->
                RichSpan.Link(token, "$VIDEO_URL_PREFIX${token.lowercase()}", RichLinkIcon.Video)
            token.startsWith("bv", ignoreCase = true) ->
                RichSpan.Link(token, "${VIDEO_URL_PREFIX}BV${token.drop(2)}", RichLinkIcon.Video)
            else -> parseTimestampMillis(token)?.let { RichSpan.Timestamp(token, it) } ?: RichSpan.Text(token)
        }
        last = match.range.last + 1
    }
    addText(text.substring(last))
    return spans
}

private const val MENTION_SEGMENT = 2
private const val VIDEO_URL_PREFIX = "https://www.bilibili.com/video/"
