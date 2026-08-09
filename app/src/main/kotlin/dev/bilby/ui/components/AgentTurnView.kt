package dev.bilby.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.agent.AgentStep
import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.TraceItem
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 助理一轮的完整界面:过程 + 答案 + 在跑/出错/没结果。
 *
 * 搜索页的一轮对话和播放页「找相关」显示的是同一个助理的同一种输出,**容器不同不构成两份
 * 实现的理由**:一个在对话流里,一个在 sheet 里,那是外面那层的事。分成两份的那一版里,
 * 播放页少了中间结果卡片和完成标记,而这件事从任何一份代码里都看不出来。
 *
 * 过程直播是信任的来源,也是等待体验本身(DESIGN 3.4):中间结果可点,助理翻到一半用户
 * 看中了可以直接点走。答案一出现过程自动折叠,但随时能点回来。
 */
@Composable
fun AgentTurnView(
    turn: AgentTurnState,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var processExpanded by remember { mutableStateOf(true) }
    val hasAnswer = turn.blocks.isNotEmpty()
    LaunchedEffect(hasAnswer) {
        if (hasAnswer) processExpanded = false
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (turn.steps.isNotEmpty()) {
            ProcessHeader(
                expanded = processExpanded,
                collapsedSummary = turn.steps.lastOrNull()?.label.orEmpty(),
                onToggle = { processExpanded = !processExpanded },
            )
            if (processExpanded) {
                turn.steps.forEach { step -> StepRow(step = step, onVideoClick = onVideoClick) }
            }
        }

        // 正文与视频卡片是同一段话,不分成"总结"加"为你找到"两块。
        AnswerBlocks(
            blocks = turn.blocks,
            onVideoClick = onVideoClick,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        )

        when {
            // 出错这一格用行内样式,不用 FullScreenError:两个容器都不是"一屏",一个是对话流里
            // 的一轮,一个是半屏 sheet,居中占满只会把上下文顶开。
            turn.error != null -> Column(
                modifier = Modifier.padding(horizontal = Spacing.Comfortable),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                Text(
                    text = turn.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }

            turn.running -> InlineProgress(
                text = stringResource(R.string.agent_running),
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            )

            !hasAnswer -> EmptyState(stringResource(R.string.agent_no_result))
        }
    }
}

@Composable
private fun ProcessHeader(
    expanded: Boolean,
    collapsedSummary: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Text(
            text = if (expanded) {
                stringResource(R.string.agent_process)
            } else {
                stringResource(R.string.agent_process_collapsed, collapsedSummary)
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = stringResource(
                if (expanded) R.string.agent_process_collapse else R.string.agent_process_expand,
            ),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepRow(step: AgentStep, onVideoClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.Hair),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        if (step.finished) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.Comfortable),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimens.IconInline),
                )
                Text(text = step.label, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            InlineProgress(step.label, Modifier.padding(horizontal = Spacing.Comfortable))
        }

        if (step.items.isNotEmpty()) {
            // 中间结果可点:助理翻到一半用户看中了可以直接点走(DESIGN 3.4)。
            LazyRow(
                contentPadding = PaddingValues(horizontal = Spacing.Comfortable),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                items(step.items, key = { it.bvid }) { trace ->
                    TraceCard(item = trace, onClick = { onVideoClick(trace.bvid) })
                }
            }
        }
    }
}

@Composable
private fun TraceCard(item: TraceItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.width(Dimens.TraceCardWidth).clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        ListCover(url = item.coverUrl, width = Dimens.TraceCardWidth)
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
