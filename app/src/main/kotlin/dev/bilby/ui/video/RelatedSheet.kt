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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.bilby.R
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
                related.running -> stringResource(R.string.related_title_running)
                related.error != null -> stringResource(R.string.related_title_error)
                videoCount > 0 -> stringResource(R.string.related_title_count, videoCount)
                else -> stringResource(R.string.related_title)
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
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }

            related.running -> {
                // 检索过程留痕:结果不是凭空出现的,是这些步骤的产物。
                related.steps.forEach { step ->
                    Text(
                        text = stringResource(R.string.related_step, step),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                InlineProgress(stringResource(R.string.related_searching))
            }

            related.blocks.isEmpty() -> Text(
                text = stringResource(R.string.agent_no_result),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // 与搜索页共用同一个渲染器:同一个助理的同一种输出,两处必须长得一样。
            else -> AnswerBlocks(blocks = related.blocks, onVideoClick = onVideoClick)
        }
    }
}
