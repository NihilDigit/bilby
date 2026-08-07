package dev.bilby.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import dev.bilby.agent.AnswerBlock
import dev.bilby.agent.TraceItem
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.AnswerBlocks
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.ListCover
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

data class AgentUiState(
    val intentLabel: String = "",
    val steps: List<AgentStep> = emptyList(),
    val blocks: List<AnswerBlock> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

data class AgentStep(
    val label: String,
    val items: List<TraceItem>,
    val finished: Boolean,
)

/**
 * 助理的独立结果页(搜索慢路、找相关的全屏版)。
 *
 * 过程直播是信任的来源,也是等待体验本身(DESIGN 3.4):中间结果可点,
 * 助理翻到一半用户看中了可以直接点走。答案一出现过程自动折叠,但随时能点回来。
 */
@Composable
fun AgentTraceScreen(
    state: AgentUiState,
    onVideoClick: (bvid: String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var processExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(state.blocks.isNotEmpty()) {
        if (state.blocks.isNotEmpty()) processExpanded = false
    }

    Scaffold(
        modifier = modifier,
        // 意图当标题:这一页存在的理由就是"在找什么",没必要在正文里再印一遍。
        topBar = { BilbyTopBar(title = state.intentLabel.ifEmpty { "助理" }, onBack = onBack) },
    ) { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = insets,
        ) {
            if (state.steps.isNotEmpty()) {
                item(key = "process_header") {
                    ProcessHeader(
                        expanded = processExpanded,
                        collapsedSummary = state.steps.lastOrNull()?.label.orEmpty(),
                        onToggle = { processExpanded = !processExpanded },
                    )
                }
                if (processExpanded) {
                    items(state.steps, key = { it.label }) { step ->
                        StepRow(step = step, onVideoClick = onVideoClick)
                    }
                }
            }

            if (state.blocks.isNotEmpty()) {
                item(key = "answer") {
                    // 与搜索页、播放页的找相关共用同一个渲染器。
                    AnswerBlocks(
                        blocks = state.blocks,
                        onVideoClick = onVideoClick,
                        modifier = Modifier.padding(
                            horizontal = Spacing.Comfortable,
                            vertical = Spacing.Cozy,
                        ),
                    )
                }
            }

            item(key = "footer") {
                when {
                    state.error != null -> FullScreenError(state.error, onRetry, Modifier.fillMaxWidth())

                    state.running -> InlineProgress(
                        text = "还在找…",
                        modifier = Modifier.padding(Spacing.Comfortable),
                    )

                    // 答完就退场,不留"再找一批"——那正是 DESIGN 1.1 要反的变比率奖励。
                    state.blocks.isEmpty() -> Text(
                        text = "没找到合适的",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.Comfortable),
                    )
                }
            }
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
            text = if (expanded) "过程" else "过程 · $collapsedSummary",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "收起过程" else "展开过程",
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

// ---- Preview ----

private fun previewTrace(bvid: String, title: String) = TraceItem(
    bvid = bvid,
    title = title,
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    upName = "某知名UP主",
)

@Preview(showBackground = true, name = "进行中")
@Composable
private fun AgentTraceScreenRunningPreview() {
    BilbyTheme {
        AgentTraceScreen(
            state = AgentUiState(
                intentLabel = "在找:适合上班摸鱼看的搞笑动画",
                steps = listOf(
                    AgentStep(
                        label = "搜索:搞笑动画",
                        items = listOf(previewTrace("BV1aa", "笑到打鸣的搞笑动画合集"), previewTrace("BV1bb", "办公室摸鱼指南")),
                        finished = true,
                    ),
                    AgentStep(label = "读取:BV1aa 的热评", items = emptyList(), finished = false),
                ),
                running = true,
            ),
            onVideoClick = {},
            onRetry = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "已出答案")
@Composable
private fun AgentTraceScreenAnswerPreview() {
    BilbyTheme {
        AgentTraceScreen(
            state = AgentUiState(
                intentLabel = "在找:适合上班摸鱼看的搞笑动画",
                steps = listOf(
                    AgentStep(
                        label = "搜索:搞笑动画",
                        items = listOf(previewTrace("BV1aa", "笑到打鸣的搞笑动画合集")),
                        finished = true,
                    ),
                ),
                blocks = listOf(
                    AnswerBlock.Text("时长短、弹幕密度高,评论区反馈「摸鱼时长刚好一集」:"),
                    AnswerBlock.Video("BV1aa", previewTrace("BV1aa", "笑到打鸣的搞笑动画合集")),
                    AnswerBlock.Text("热评里有人接着讨论第二集,想连着看可以直接跳过去:"),
                    AnswerBlock.Video("BV1bb", previewTrace("BV1bb", "办公室摸鱼指南 · 第二集")),
                ),
                running = false,
            ),
            onVideoClick = {},
            onRetry = {},
            onBack = {},
        )
    }
}

@Preview(showBackground = true, name = "出错")
@Composable
private fun AgentTraceScreenErrorPreview() {
    BilbyTheme {
        AgentTraceScreen(
            state = AgentUiState(
                intentLabel = "在找:适合上班摸鱼看的搞笑动画",
                steps = listOf(
                    AgentStep(label = "搜索:搞笑动画", items = emptyList(), finished = true),
                ),
                error = "网络连接失败",
            ),
            onVideoClick = {},
            onRetry = {},
            onBack = {},
        )
    }
}
