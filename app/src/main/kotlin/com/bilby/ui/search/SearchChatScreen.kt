package com.bilby.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.bilby.agent.AnswerItem
import com.bilby.agent.TraceItem
import com.bilby.data.SearchUser
import com.bilby.data.SearchVideo
import com.bilby.ui.theme.BilbyTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private const val PrefetchThreshold = 5

enum class SearchMode(val label: String) { Normal("普通搜索"), Agent("助理搜索") }

sealed interface TurnResult {
    data class Normal(
        val videos: List<SearchVideo>,
        val users: List<SearchUser>,
        val loading: Boolean = false,
        val appending: Boolean = false,
        val hasMore: Boolean = true,
        val error: String? = null,
    ) : TurnResult

    data class Agent(
        val steps: List<AgentStep>,
        val answer: List<AnswerItem>,
        val running: Boolean = false,
        val error: String? = null,
    ) : TurnResult
}

data class AgentStep(val label: String, val items: List<TraceItem>, val finished: Boolean)

data class SearchTurn(val id: Long, val query: String, val mode: SearchMode, val result: TurnResult)

data class SearchChatUiState(
    val turns: List<SearchTurn> = emptyList(),
    val input: String = "",
    val mode: SearchMode = SearchMode.Normal,
)

/**
 * 对话式搜索(团队要求的形态):输入在下,一轮轮结果在上,像 Claude App 但内容是视频列表。
 * 结果页只有结果——无历史、无热搜、无"换一批"(DESIGN 2.2/3.4)。
 */
@Composable
fun SearchChatScreen(
    state: SearchChatUiState,
    onInputChange: (String) -> Unit,
    onModeChange: (SearchMode) -> Unit,
    onSend: () -> Unit,
    onVideoClick: (bvid: String) -> Unit,
    onUserClick: (mid: Long) -> Unit,
    onLoadMore: (turnId: Long) -> Unit,
    onRetry: (turnId: Long) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 新一轮出现就滚到底部,像聊天软件那样跟随最新消息。
    LaunchedEffect(state.turns.size) {
        if (state.turns.isNotEmpty()) {
            listState.animateScrollToItem(state.turns.lastIndex)
        }
    }

    // 触底翻页:只对最后一轮生效,翻上面轮次不会误触发(轮次一旦定型就不再变化)。
    LaunchedEffect(listState, state.turns.lastOrNull()?.id) {
        val lastTurn = state.turns.lastOrNull() ?: return@LaunchedEffect
        val lastResult = lastTurn.result as? TurnResult.Normal ?: return@LaunchedEffect
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> lastVisible != null && lastVisible >= total - 1 - PrefetchThreshold }
            .collect {
                if (lastResult.hasMore && !lastResult.appending) onLoadMore(lastTurn.id)
            }
    }

    Column(modifier = modifier.fillMaxSize()) {
        ModeSwitch(
            mode = state.mode,
            onModeChange = onModeChange,
            onNewSession = onNewSession,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(state.turns, key = { _, turn -> turn.id }) { _, turn ->
                TurnRow(
                    turn = turn,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onRetry = { onRetry(turn.id) },
                )
            }
        }

        InputBar(
            input = state.input,
            onInputChange = onInputChange,
            onSend = onSend,
            modifier = Modifier
                .fillMaxWidth()
                ,
        )
    }
}

@Composable
private fun ModeSwitch(
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        SearchMode.entries.forEach { candidate ->
            FilterChip(
                selected = candidate == mode,
                onClick = { onModeChange(candidate) },
                label = { Text(candidate.label) },
            )
        }
        Spacer(Modifier.weight(1f))
        // 助理会话在会话内共享上下文,可以追问;开新会话是清空上下文的唯一入口
        // (DESIGN 3.1:会话必须由用户显式开启)。
        IconButton(onClick = onNewSession) {
            Icon(Icons.Filled.Add, contentDescription = "新会话")
        }
    }
}

@Composable
private fun TurnRow(
    turn: SearchTurn,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        UserBubble(text = turn.query, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.padding(top = 12.dp))
        when (val result = turn.result) {
            is TurnResult.Normal -> NormalTurnResult(result, onVideoClick, onUserClick)
            is TurnResult.Agent -> AgentTurnResult(result, onVideoClick, onRetry)
        }
    }
}

/** 用户这次问的话,靠右的气泡,像聊天(团队要求的对话式外观)。 */
@Composable
private fun UserBubble(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.widthIn(max = 280.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

// ---- 普通模式 ----

@Composable
private fun NormalTurnResult(
    result: TurnResult.Normal,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (result.users.isNotEmpty()) {
            UserRow(users = result.users, onUserClick = onUserClick)
        }
        when {
            result.loading && result.videos.isEmpty() -> {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            result.error != null && result.videos.isEmpty() -> {
                Text(
                    text = result.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            result.videos.isEmpty() -> {
                Text(
                    text = "没有找到相关结果",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            else -> {
                // 一轮内的视频用普通 Column 平铺(不是嵌套 LazyColumn),触底翻页由外层
                // 大列表的滚动状态统一检测(见 SearchChatScreen 里的 LaunchedEffect)。
                result.videos.forEach { video ->
                    VideoRow(item = video, onClick = { onVideoClick(video.bvid) })
                }
                if (result.appending) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun UserRow(users: List<SearchUser>, onUserClick: (Long) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(users, key = { _, user -> user.mid }) { _, user ->
            UserChip(user = user, onClick = { onUserClick(user.mid) })
        }
    }
}

@Composable
private fun UserChip(user: SearchUser, onClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(user.avatarUrl)
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column {
            Text(user.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${formatCount(user.fansCount)}粉丝",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 视觉语言沿用现有 SearchScreen 的 VideoRow(封面 + 时长角标 + 标题两行 + UP + 计数)。 */
@Composable
private fun VideoRow(item: SearchVideo, onClick: () -> Unit, modifier: Modifier = Modifier) {
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
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.coverUrl)
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.durationText.isNotEmpty()) {
                Text(
                    text = item.durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                text = item.upName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                text = "${formatCount(item.playCount)}播放 · ${formatCount(item.danmakuCount)}弹幕",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ---- 助理模式 ----

@Composable
private fun AgentTurnResult(result: TurnResult.Agent, onVideoClick: (String) -> Unit, onRetry: () -> Unit) {
    // 答案出来前展开过程,答案一出现就自动折叠——但用户随时能点回来看(DESIGN 3.4)。
    var processExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(result.answer.isNotEmpty()) {
        if (result.answer.isNotEmpty()) processExpanded = false
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (result.steps.isNotEmpty()) {
            ProcessHeader(
                expanded = processExpanded,
                collapsedSummary = result.steps.lastOrNull()?.label.orEmpty(),
                onToggle = { processExpanded = !processExpanded },
            )
            if (processExpanded) {
                result.steps.forEach { step -> StepRow(step = step, onVideoClick = onVideoClick) }
            }
        }

        if (result.answer.isNotEmpty()) {
            Text(
                text = "为你找到",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            result.answer.forEach { answer ->
                AnswerCard(item = answer, onClick = { onVideoClick(answer.bvid) })
            }
        }

        when {
            result.error != null -> ErrorFooter(message = result.error, onRetry = onRetry)
            result.running -> RunningFooter()
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
                itemsIndexed(step.items, key = { _, item -> item.bvid }) { _, trace ->
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
            // 理由是助理搜索与推荐流的根本区别,必须显示(DESIGN 3.4)。
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(top = 8.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

// ---- 输入框 ----

@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 2.dp, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入想找的内容") },
                singleLine = true,
            )
            IconButton(onClick = onSend, enabled = input.isNotBlank()) {
                Icon(Icons.Default.Send, contentDescription = "发送")
            }
        }
    }
}

/** 搜索接口返回原始整数计数,这里自己做展示折算(与 SearchScreen 一致)。 */
private fun formatCount(count: Long): String = when {
    count >= 100_000_000 -> "%.1f亿".format(count / 100_000_000.0)
    count >= 10_000 -> "%.1f万".format(count / 10_000.0)
    else -> count.toString()
}

// ---- Preview ----

private fun previewVideo(bvid: String, title: String) = SearchVideo(
    bvid = bvid,
    title = title,
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    durationText = "12:34",
    upName = "某知名UP主",
    upMid = 12345L,
    publishedAtEpochSeconds = 0L,
    playCount = 123_000L,
    danmakuCount = 888L,
)

private fun previewTrace(bvid: String, title: String) = TraceItem(
    bvid = bvid,
    title = title,
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    upName = "某知名UP主",
)

@Preview(showBackground = true, name = "普通模式有结果")
@Composable
private fun SearchChatScreenNormalPreview() {
    BilbyTheme {
        SearchChatScreen(
            state = SearchChatUiState(
                turns = listOf(
                    SearchTurn(
                        id = 1L,
                        query = "泠鸢的新歌",
                        mode = SearchMode.Normal,
                        result = TurnResult.Normal(
                            videos = listOf(
                                previewVideo("BV1aa", "这是一个很长很长需要两行才能显示完的搜索结果标题示例文本内容"),
                                previewVideo("BV1bb", "第二条搜索结果"),
                            ),
                            users = listOf(SearchUser(mid = 1L, name = "示例UP主", avatarUrl = "", fansCount = 45_000L, signature = "")),
                            hasMore = false,
                        ),
                    ),
                ),
                mode = SearchMode.Normal,
            ),
            onInputChange = {},
            onModeChange = {},
            onSend = {},
            onVideoClick = {},
            onUserClick = {},
            onLoadMore = {},
            onRetry = {},
            onNewSession = {},
        )
    }
}

@Preview(showBackground = true, name = "助理模式进行中")
@Composable
private fun SearchChatScreenAgentRunningPreview() {
    BilbyTheme {
        SearchChatScreen(
            state = SearchChatUiState(
                turns = listOf(
                    SearchTurn(
                        id = 1L,
                        query = "适合上班摸鱼看的搞笑动画",
                        mode = SearchMode.Agent,
                        result = TurnResult.Agent(
                            steps = listOf(
                                AgentStep(
                                    label = "搜索:搞笑动画",
                                    items = listOf(previewTrace("BV1aa", "笑到打鸣的搞笑动画合集")),
                                    finished = true,
                                ),
                                AgentStep(label = "读取:BV1aa 的热评", items = emptyList(), finished = false),
                            ),
                            answer = emptyList(),
                            running = true,
                        ),
                    ),
                ),
                mode = SearchMode.Agent,
            ),
            onInputChange = {},
            onModeChange = {},
            onSend = {},
            onVideoClick = {},
            onUserClick = {},
            onLoadMore = {},
            onRetry = {},
            onNewSession = {},
        )
    }
}

@Preview(showBackground = true, name = "助理模式已出答案")
@Composable
private fun SearchChatScreenAgentAnswerPreview() {
    BilbyTheme {
        SearchChatScreen(
            state = SearchChatUiState(
                turns = listOf(
                    SearchTurn(
                        id = 1L,
                        query = "适合上班摸鱼看的搞笑动画",
                        mode = SearchMode.Agent,
                        result = TurnResult.Agent(
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
                    ),
                ),
                mode = SearchMode.Agent,
            ),
            onInputChange = {},
            onModeChange = {},
            onSend = {},
            onVideoClick = {},
            onUserClick = {},
            onLoadMore = {},
            onRetry = {},
            onNewSession = {},
        )
    }
}
