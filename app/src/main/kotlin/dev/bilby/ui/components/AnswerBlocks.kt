package dev.bilby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
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
                is AnswerBlock.Text -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                )

                is AnswerBlock.Video -> CompactVideoRow(
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
