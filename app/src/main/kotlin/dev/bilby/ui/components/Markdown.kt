package dev.bilby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.bilby.ui.theme.Spacing

/** 正文行高。24 与评论正文同一档,理由见 [MarkdownText] 的 `style` 参数。 */
private val BodyLineHeight = 24.sp

/**
 * 助理答案里的 markdown。**只认一个最小子集**,没有引第三方渲染器。
 *
 * 支持:`**加粗**`、`*斜体*`、`` `行内代码` ``、`- ` 无序列表、`1. ` 有序列表、`#`~`###` 小标题。
 * 不支持:表格、代码块、链接、图片、引用块 —— system prompt 里同样这么写,两边必须一致,
 * 否则模型写出来的东西会原样露出记号。
 *
 * 不引渲染器的理由不是体积,是**块边界已经被占用了**:答案先按 `[[bvid]]` 切成
 * [dev.bilby.agent.AnswerBlock],视频卡片就落在切口上,到这里每段文字都已经是残缺的片段
 * (可能从半句话开始)。全量解析器要求输入是完整文档,拿片段喂它,列表和段落的归属得
 * 另找一套规则重新对齐,省不下来。
 *
 * 用 `*` 表示斜体,**不认 `_`**:这个 app 的正文里 `page_size`、`web_location` 这类下划线
 * 标识符出现得比斜体多,认 `_` 的代价是把它们拦腰斜掉。
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    /**
     * 正文那一档。**行高 24sp,字号仍是 `bodyMedium` 的 14sp** —— 判据和评论正文那条完全一样
     * (风格指南 §2.7b:行高,不是字号):助理的答案常是五六行连排的汉字,而汉字墨迹几乎占满
     * em 框,`bodyMedium` 自带的 14/22 在这个长度上会糊成一片。不去改 `Typography.bodyMedium`,
     * 那一档还给列表标题和队列条目用着,它们要的是紧凑。
     */
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = BodyLineHeight),
    /**
     * 遇到标题内容属于这一组的,这一节连同它后面的全部不画。
     *
     * 给更新日志用:release 正文是手写的日志加上发布流程拼在后面的固定几节(见
     * `.github/workflows/release.yml`),而那几节讲的是下载页上怎么挑 ABI 包、怎么核校验和,
     * 应用内更新一个字都用不上。**判断放在这里而不是取回来时裁字符串**:日志本身没有问题,
     * 是这块界面只该画它的一部分,而"哪一部分"按解析出来的标题分节,不按字符位置。
     */
    stopAtHeadings: Set<String> = emptySet(),
) {
    val blocks = remember(text, stopAtHeadings) { parseMarkdown(text).upTo(stopAtHeadings) }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHighest
    // 块间距 8dp。**行高抬到 24 之后 4dp 不够了**:行内间距(24 − 14 ≈ 10dp 分摊到上下)已经
    // 超过块间距,于是同一段里的换行看起来比段与段之间还开,一段答案读不出分了几段。
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = block.spans.toAnnotated(codeBackground),
                    style = style,
                )

                // 助理答案里的 `#` 只是分节,不是页面标题 —— 按标题层级去渲染会让一段回答
                // 看起来像一篇文档。三级都收在正文量级里,只拉开字重。
                is MdBlock.Heading -> Text(
                    text = block.spans.toAnnotated(codeBackground),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleSmall
                        2 -> MaterialTheme.typography.labelLarge
                        else -> MaterialTheme.typography.labelLarge
                    },
                    modifier = Modifier.padding(top = Spacing.Tight),
                )

                // 记号单独一列,正文挂在右边:第二行要缩进对齐到第一行的文字,
                // 而不是回到记号下面。
                is MdBlock.ListItem -> Row(modifier = Modifier.padding(start = Spacing.Tight)) {
                    Text(text = block.marker, style = style)
                    Text(
                        text = block.spans.toAnnotated(codeBackground),
                        style = style,
                        modifier = Modifier.padding(start = Spacing.Hair),
                    )
                }
            }
        }
    }
}

private fun List<MdSpan>.toAnnotated(codeBackground: Color): AnnotatedString = buildAnnotatedString {
    // 上一段落的末字要带进下一段。**记号切开的是样式,不是句子**:"这样**加粗**,那样"里那个
    // 逗号是新一段的第一个字符,单看这一段它前面什么都没有,而它在读者眼里紧跟着一个汉字。
    var tail: Char? = null
    this@toAnnotated.forEach { span ->
        // 行内代码原样照抄:`page_size,` 里那个逗号是代码的一部分,换成全角就不是同一串字了。
        val text = if (span.code) span.text else normalizeCjkPunctuation(span.text, tail)
        withStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBackground else Color.Unspecified,
            ),
        ) {
            append(text)
        }
        tail = text.lastOrNull() ?: tail
    }
}

/**
 * 汉字之间的半角标点改成全角。
 *
 * **在渲染这一侧做,不在 prompt 里要求。** 模型混用半角和全角是稳定现象,而 prompt 管不住它:
 * 同一段答案里"第一,"是半角、"第二,"是全角的情况实测就有。半角逗号在汉字之间的问题不是好不
 * 好看 —— 它自带的右侧空白只有全角的一半,而汉字之间本来没有词间空隙,于是断句处几乎看不出
 * 停顿;反过来全角标点后面再跟一个 ASCII 空格又会开出一道两个字宽的缝。
 *
 * **判据是两侧,不只是前面一个字:**
 *
 * - 前一个字符必须是 CJK。`bvid, 3` 这种参数列表因此不动。
 * - 后一个字符是行尾、空白,或者又是 CJK。`宽高比 16:9`、`见 notes/comment.md:12` 里的冒号
 *   后面跟的是数字和字母,不动 —— 那些是数值和路径,不是句读。
 *
 * 换成全角之后**紧跟的一个 ASCII 空格一并吃掉**:全角标点自带的右侧空白就是那一格,
 * 再留一个空格等于连开两格。
 *
 * @param precededBy 这一段之前那个字符(样式记号把一句话切成几段时用得上,见 `toAnnotated`)。
 *   null 表示这一段就是一行的开头,那时行首的标点留在原样 —— 它没有"前一个字"。
 */
internal fun normalizeCjkPunctuation(text: String, precededBy: Char? = null): String {
    if (text.none { it in HalfWidthPunctuation }) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val fullWidth = FullWidthOf[c]
        // 前一个字符看的是已经写出去的那一份,不是原文 —— 上一轮吃掉空格之后两者会错开。
        val previous = out.lastOrNull() ?: precededBy
        val next = text.getOrNull(i + 1)
        if (fullWidth != null && previous != null && previous.isCjk() &&
            (next == null || next.isWhitespace() || next.isCjk())
        ) {
            out.append(fullWidth)
            i++
            if (next == ' ') i++
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}

private val FullWidthOf = mapOf(',' to '，', ':' to '：', ';' to '；', '?' to '？', '!' to '！')

private val HalfWidthPunctuation = FullWidthOf.keys

/**
 * 汉字、日文假名、以及全角标点本身。**全角标点算在内**,否则"这样,那样,"里第二个半角逗号
 * 前面是一个全角逗号,会被判成"前面不是汉字"而留在原样。
 */
private fun Char.isCjk(): Boolean = this in '一'..'鿿' || // CJK 统一汉字
    this in '぀'..'ヿ' || // 平假名与片假名
    this in '　'..'〿' || // CJK 符号与标点(、。「」)
    this in '＀'..'￯' // 全角形式(，：；?!)

// ---- 解析。以下与 Compose 无关,单测直接吃这一段。 ----

internal data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
)

internal sealed interface MdBlock {
    val spans: List<MdSpan>

    data class Paragraph(override val spans: List<MdSpan>) : MdBlock

    data class Heading(val level: Int, override val spans: List<MdSpan>) : MdBlock

    /** [marker] 是行首那个记号本身:无序是 `•`,有序保留模型写的序号。 */
    data class ListItem(val marker: String, override val spans: List<MdSpan>) : MdBlock
}

private val HeadingLine = Regex("""^(#{1,3})\s+(.*)$""")
private val BulletLine = Regex("""^\s*[-*+]\s+(.*)$""")
private val OrderedLine = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")

/**
 * 截到第一个标题内容落在 [stopAtHeadings] 里的地方,那一节连同后面的全部丢掉。
 * 空集合表示不截。见 [MarkdownText] 的同名参数。
 */
internal fun List<MdBlock>.upTo(stopAtHeadings: Set<String>): List<MdBlock> {
    if (stopAtHeadings.isEmpty()) return this
    return takeWhile { block ->
        block !is MdBlock.Heading || block.spans.joinToString("") { it.text } !in stopAtHeadings
    }
}

/**
 * **一行一块,不合并相邻行。** 标准 markdown 会把连续的非空行接成一段,这里不:模型换行
 * 通常是在断句(每条推荐一行),接起来会把本该分开的两句黏成一句。
 */
internal fun parseMarkdown(text: String): List<MdBlock> = text.lines()
    .map { it.trimEnd() }
    .filter { it.isNotBlank() }
    .map { line ->
        val heading = HeadingLine.find(line)
        val ordered = OrderedLine.find(line)
        val bullet = BulletLine.find(line)
        when {
            heading != null -> MdBlock.Heading(
                level = heading.groupValues[1].length,
                spans = parseInline(heading.groupValues[2]),
            )
            // 有序在无序之前判:`- ` 与 `1. ` 不会互相匹配,但顺序写死了才不用去想。
            ordered != null -> MdBlock.ListItem(
                marker = "${ordered.groupValues[1]}.",
                spans = parseInline(ordered.groupValues[2]),
            )

            bullet != null -> MdBlock.ListItem("•", parseInline(bullet.groupValues[1]))
            else -> MdBlock.Paragraph(parseInline(line))
        }
    }

/**
 * 行内记号。递归下降,所以 `**粗里有 `代码`**` 这种嵌套自然成立,不需要另设规则。
 *
 * 强调的两端都要求**紧贴非空白字符**(CommonMark 的 flanking 规则的简化版):少了这一条,
 * 「2 * 3 * 4」里的 ` 3 ` 会被斜体掉。配不上对的记号一律当普通字符留在正文里 —— 模型写了
 * 半个记号时,让它露出来比把后面半句吞掉好。
 */
private fun parseInline(text: String, bold: Boolean = false, italic: Boolean = false): List<MdSpan> {
    val out = mutableListOf<MdSpan>()
    val buffer = StringBuilder()

    fun flush() {
        if (buffer.isNotEmpty()) {
            out += MdSpan(buffer.toString(), bold, italic)
            buffer.clear()
        }
    }

    var i = 0
    while (i < text.length) {
        val code = if (text[i] == '`') text.indexOf('`', i + 1) else -1
        // `***两者***` 要先于 `**` 判:按 `**` 切的话内层只剩一个落单的 `*`,配不上对,
        // 于是它会作为普通字符印在正文里。
        val both = if (text.startsWith("***", i)) emphasisEnd(text, i, "***") else -1
        val strong = if (both < 0 && text.startsWith("**", i)) emphasisEnd(text, i, "**") else -1
        val emphasis = if (both < 0 && strong < 0 && text[i] == '*') emphasisEnd(text, i, "*") else -1

        when {
            code > i -> {
                flush()
                out += MdSpan(text.substring(i + 1, code), bold, italic, code = true)
                i = code + 1
            }

            both > 0 -> {
                flush()
                out += parseInline(text.substring(i + 3, both), bold = true, italic = true)
                i = both + 3
            }

            strong > 0 -> {
                flush()
                out += parseInline(text.substring(i + 2, strong), bold = true, italic = italic)
                i = strong + 2
            }

            emphasis > 0 -> {
                flush()
                out += parseInline(text.substring(i + 1, emphasis), bold = bold, italic = true)
                i = emphasis + 1
            }

            else -> {
                buffer.append(text[i])
                i++
            }
        }
    }
    flush()
    return out
}

/** 返回闭合记号的起始下标,配不上或内容两端带空白时返回 -1。 */
private fun emphasisEnd(text: String, open: Int, marker: String): Int {
    val contentStart = open + marker.length
    if (contentStart >= text.length || text[contentStart].isWhitespace()) return -1
    val end = text.indexOf(marker, contentStart)
    if (end <= contentStart || text[end - 1].isWhitespace()) return -1
    return end
}
