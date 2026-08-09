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
        blocks.forEach { block ->
            when (block) {
                is AnswerBlock.Text -> MarkdownText(text = block.text)

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
