package dev.bilby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bilby.agent.AnswerBlock
import dev.bilby.ui.theme.Spacing

/**
 * 助理回答的渲染:一段夹着视频卡片的正文。
 *
 * 搜索页和播放页的「找相关」共用这一份 —— 两处显示同一个助理的同一种输出,分成两套写法
 * 只会让它们慢慢长歪。容器各自不同(一个在对话流里,一个在 sheet 里),渲染必须一致。
 *
 * 卡片本身不带说明文字:为什么值得看由它前后的句子承担(见 AgentLoop 的 submit_answer)。
 *
 * 正文过 [MarkdownText]:模型本来就会写 `**` 和 `- `,不解析的话那些记号会原样印在答案里。
 */
@Composable
fun AnswerBlocks(
    blocks: List<AnswerBlock>,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                // 紧跟在卡片后面的那一段要剥掉开头的标点。模型写的是一句连贯的话
                // ("推荐 [[BV1xx]],它把这件事讲得最清楚"),而 `[[bvid]]` 那一刀切在句中,
                // 剩下的半句以逗号开头 —— 卡片是一个块,它下面一行顶着个逗号,读起来像漏了字。
                // 剥完只剩空白的(卡片后面本来就是句末)整块不画 —— 那种块以前画出来是一个
                // 零高度的 Column,但仍然吃掉一份 `spacedBy` 的间距,表现是卡片下面空一截。
                is AnswerBlock.Text -> {
                    val text = if (blocks.getOrNull(index - 1) is AnswerBlock.Video) {
                        block.text.trimStart(*LeadingPunctuation)
                    } else {
                        block.text
                    }
                    if (text.isNotBlank()) MarkdownText(text = text)
                }

                // 卡片套一层容器:它嵌在散文里,不套的话和正文一样贴着左边缘,读起来像
                // 段落中间突然插了一张图,分不清是"被提到的东西"还是"正文的一部分"。
                is AnswerBlock.Video -> Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(start = Spacing.Cozy),
                ) {
                    CompactVideoRow(
                        // 拿不到展示信息时只剩 bvid。这比不显示卡片好:句子里提到了它,
                        // 少一张卡片会让那句话指向空处。
                        title = block.trace?.title ?: block.bvid,
                        coverUrl = block.trace?.coverUrl.orEmpty(),
                        subtitle = block.trace?.upName,
                        selected = false,
                        onClick = { onVideoClick(block.bvid) },
                    )
                }
            }
        }
    }
}

/**
 * 卡片后面那一段开头要剥掉的字符:句读加空白。
 *
 * **引号和括号不剥。** 它们有可能就是这句话的开头("[[bvid]]「这个说法」其实……"),剥掉等于
 * 改了话。而逗号、顿号、分号、冒号、句号出现在一段的开头只有一个来源,就是上一刀切在了句中。
 */
private val LeadingPunctuation = charArrayOf(
    ',', '，', '、', '。', ':', '：', ';', '；',
    ' ', '\t', '\n', '\r', '　',
)
