package dev.bilby.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bilby.ui.components.AnswerBlocks
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.theme.Spacing

/** 收起时露出的把手高度。够放一行标题与状态,不占画面。 */
val SheetHandleHeight = 96.dp

/**
 * 找相关的结果面板。收起时只露出顶部一行(把手),展开是完整的回答。
 *
 * 把手上写清身份和结果数,不做成一根裸横条 —— 翻了两屏评论之后要能一眼认出它是什么。
 */
@Composable
fun RelatedSheet(
    related: RelatedState,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val videoCount = related.blocks.count { it is dev.bilby.agent.AnswerBlock.Video }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(
                start = Spacing.Comfortable,
                end = Spacing.Comfortable,
                top = Spacing.Cozy,
                // 展开后最后一块不要贴着屏幕下缘,手势区就在那里。
                bottom = Spacing.Loose,
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        // 把手上一眼能认出它是什么、找到了几条 —— 翻了两屏评论之后回来时靠的就是这行。
        Text(
            text = when {
                related.running -> "相关 · 检索中"
                related.error != null -> "相关 · 失败"
                videoCount > 0 -> "相关 · $videoCount 条"
                else -> "相关"
            },
            style = MaterialTheme.typography.titleMedium,
        )
        HorizontalDivider()

        when {
            related.error != null -> {
                Text(
                    text = related.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }

            related.running -> {
                // 检索过程留痕:结果不是凭空出现的,是这些步骤的产物。
                related.steps.forEach { step ->
                    Text(
                        text = "· $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InlineProgress("检索中…")
            }

            related.blocks.isEmpty() -> Text(
                text = "没找到合适的",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 与搜索页共用同一个渲染器:同一个助理的同一种输出,两处必须长得一样。
            else -> AnswerBlocks(blocks = related.blocks, onVideoClick = onVideoClick)
        }
    }
}
