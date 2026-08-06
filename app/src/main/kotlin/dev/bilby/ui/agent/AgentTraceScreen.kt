package dev.bilby.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.bilby.agent.AnswerItem
import dev.bilby.agent.TraceItem
import dev.bilby.ui.theme.BilbyTheme

data class AgentUiState(
    val intentLabel: String = "",
    val steps: List<AgentStep> = emptyList(),
    val answer: List<AnswerItem> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

data class AgentStep(
    val label: String,
    val items: List<TraceItem>,
    val finished: Boolean,
)

@Composable
fun AgentTraceScreen(
    state: AgentUiState,
    onVideoClick: (bvid: String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 答案出来前展开过程,答案一出现就自动折叠——但用户随时能点回来看(DESIGN 3.4)。
    var processExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(state.answer.isNotEmpty()) {
        if (state.answer.isNotEmpty()) processExpanded = false
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = WindowInsets.systemBars.asPaddingValues(),
    ) {
        if (state.intentLabel.isNotEmpty()) {
            item(key = "intent") {
                Text(
                    text = state.intentLabel,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

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

        if (state.answer.isNotEmpty()) {
            item(key = "answer_header") {
                Text(
                    text = "为你找到",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(state.answer, key = { it.bvid }) { answer ->
                AnswerCard(item = answer, onClick = { onVideoClick(answer.bvid) })
            }
        }

        item(key = "footer") {
            when {
                state.error != null -> ErrorFooter(message = state.error, onRetry = onRetry)
                state.running -> RunningFooter()
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (step.finished) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            Text(text = step.label, style = MaterialTheme.typography.bodyMedium)
        }
        if (step.items.isNotEmpty()) {
            Spacer(Modifier.padding(top = 4.dp))
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
    val context = LocalContext.current
    Column(
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.coverUrl)
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.padding(top = 4.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AnswerCard(item: AnswerItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            if (item.trace != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(item.trace.coverUrl)
                        .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.trace?.title ?: item.bvid,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.trace != null) {
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    text = item.trace.upName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.padding(top = 4.dp))
            // 理由是这个功能与推荐流的根本区别,必须显示,不能只给一排封面(团队指示)。
            Text(
                text = item.reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RunningFooter(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Spacer(Modifier.padding(horizontal = 4.dp))
        Text(
            text = "还在找…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorFooter(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(top = 12.dp))
        Button(onClick = onRetry) { Text("重试") }
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
                answer = listOf(
                    AnswerItem(
                        bvid = "BV1aa",
                        reason = "时长短、弹幕密度高,评论区反馈「摸鱼时长刚好一集」,符合你的场景。",
                        trace = previewTrace("BV1aa", "笑到打鸣的搞笑动画合集"),
                    ),
                    AnswerItem(
                        bvid = "BV1bb",
                        reason = "同系列第二集,热评区多人接续讨论第一集内容。",
                        trace = previewTrace("BV1bb", "办公室摸鱼指南 · 第二集"),
                    ),
                ),
                running = false,
            ),
            onVideoClick = {},
            onRetry = {},
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
        )
    }
}
