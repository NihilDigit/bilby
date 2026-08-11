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
import dev.bilby.ui.theme.Spacing

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
    style: TextStyle = MaterialTheme.typography.bodyMedium,
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
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
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
    this@toAnnotated.forEach { span ->
        withStyle(
            SpanStyle(
                fontWeight = if (span.bold) FontWeight.Bold else null,
                fontStyle = if (span.italic) FontStyle.Italic else null,
                fontFamily = if (span.code) FontFamily.Monospace else null,
                background = if (span.code) codeBackground else Color.Unspecified,
            ),
        ) {
            append(span.text)
        }
    }
}

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
