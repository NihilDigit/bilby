package dev.bilby.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.bilby.agent.AnswerBlock
import dev.bilby.ui.components.AnswerBlocks
import dev.bilby.agent.TraceItem
import dev.bilby.data.SearchUser
import dev.bilby.data.SearchVideo
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.ListCover
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
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
        /** 一段夹着视频卡片的正文,见 AnswerBlocks。 */
        val blocks: List<AnswerBlock> = emptyList(),
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
 *
 * "新会话"在顶栏(见 MainActivity),不在这一层。
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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        )

        if (state.turns.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // 空态说清楚两条路各是什么。这是唯一会解释助理的地方,别让它藏在一个开关后面。
                EmptyState("普通搜索直接问 B 站要结果。\n助理搜索会翻热评、进空间比对,再给几条带理由的。")
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(vertical = Spacing.Cozy),
                verticalArrangement = Arrangement.spacedBy(Spacing.Loose),
            ) {
                items(state.turns, key = { it.id }) { turn ->
                    TurnRow(
                        turn = turn,
                        onVideoClick = onVideoClick,
                        onUserClick = onUserClick,
                        onRetry = { onRetry(turn.id) },
                    )
                }
            }
        }

        InputBar(
            input = state.input,
            onInputChange = onInputChange,
            onSend = onSend,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 普通 / 助理。用 segmented button 而不是 filter chip:M3 把"切换视图"明确划给 segmented
 * button,chip 是给动态的、上下文相关的、可以横滚的一组选项用的(比如下面分 P 那一排)。
 * 这两个选项是固定的两条路,不随内容变,也永远不会变成三个。
 */
@Composable
private fun ModeSwitch(
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier) {
        SearchMode.entries.forEachIndexed { index, candidate ->
            SegmentedButton(
                selected = candidate == mode,
                onClick = { onModeChange(candidate) },
                shape = SegmentedButtonDefaults.itemShape(index, SearchMode.entries.size),
                label = { Text(candidate.label) },
            )
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
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        UserBubble(text = turn.query, modifier = Modifier.padding(horizontal = Spacing.Comfortable))
        when (val result = turn.result) {
            is TurnResult.Normal -> NormalTurnResult(result, onVideoClick, onUserClick, onRetry)
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
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.large,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .widthIn(max = BubbleMaxWidth)
                    .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            )
        }
    }
}

private val BubbleMaxWidth = 280.dp

// ---- 普通模式 ----

@Composable
private fun NormalTurnResult(
    result: TurnResult.Normal,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (result.users.isNotEmpty()) {
            UserRow(users = result.users, onUserClick = onUserClick)
        }
        when {
            result.loading && result.videos.isEmpty() ->
                Box(Modifier.fillMaxWidth().padding(Spacing.Loose), Alignment.Center) {
                    InlineProgress("搜索中…")
                }

            result.error != null && result.videos.isEmpty() ->
                FullScreenError(result.error, onRetry, Modifier.fillMaxWidth())

            result.videos.isEmpty() -> EmptyState("没有找到相关结果")

            else -> {
                // 一轮内的视频用普通 Column 平铺(不是嵌套 LazyColumn),触底翻页由外层
                // 大列表的滚动状态统一检测(见 SearchChatScreen 里的 LaunchedEffect)。
                result.videos.forEach { video ->
                    VideoRow(item = video.toRowUi(), onClick = { onVideoClick(video.bvid) })
                }
                ListFooter(
                    appending = result.appending,
                    hasMore = result.hasMore,
                    hasItems = true,
                )
            }
        }
    }
}

@Composable
private fun UserRow(users: List<SearchUser>, onUserClick: (Long) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Tight),
        contentPadding = PaddingValues(horizontal = Spacing.Comfortable),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
    ) {
        items(users, key = { it.mid }) { user ->
            UserChip(user = user, onClick = { onUserClick(user.mid) })
        }
    }
}

@Composable
private fun UserChip(user: SearchUser, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Avatar(url = user.avatarUrl, size = Dimens.AvatarMedium)
        Column {
            Text(
                text = user.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${formatCount(user.fansCount)}粉丝",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun SearchVideo.toRowUi() = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = durationText,
    upName = upName,
    // 搜索结果里发布时间是判据之一(教程类尤其看新旧),和动态、空间保持同一行形态。
    dateText = formatDate(publishedAtEpochSeconds),
    playText = formatCount(playCount),
    danmakuText = formatCount(danmakuCount),
)

private val DateFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatDate(epochSeconds: Long): String =
    if (epochSeconds <= 0) {
        ""
    } else {
        java.time.Instant.ofEpochSecond(epochSeconds)
            .atZone(java.time.ZoneId.systemDefault())
            .format(DateFormatter)
    }

// ---- 助理模式 ----

@Composable
private fun AgentTurnResult(result: TurnResult.Agent, onVideoClick: (String) -> Unit, onRetry: () -> Unit) {
    // 答案出来前展开过程,答案一出现就自动折叠——但用户随时能点回来看(DESIGN 3.4)。
    var processExpanded by remember { mutableStateOf(true) }
    val hasAnswer = result.blocks.isNotEmpty()
    LaunchedEffect(hasAnswer) {
        if (hasAnswer) processExpanded = false
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

        // 正文与视频卡片是同一段话,不再分成"总结"加"为你找到"两块。
        AnswerBlocks(
            blocks = result.blocks,
            onVideoClick = onVideoClick,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        )

        when {
            result.error != null -> FullScreenError(result.error, onRetry, Modifier.fillMaxWidth())
            result.running -> InlineProgress(
                text = "还在找…",
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            )
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

// ---- 输入框 ----

/**
 * 输入区固定在底部。底色用 surfaceContainer 而不是 tonalElevation:M3 的做法是靠
 * surface container 这一族的色阶差表达层次,阴影和 tonal elevation 留给真正浮起来的东西。
 */
@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            SearchField(
                value = input,
                onValueChange = onInputChange,
                placeholder = "输入想找的内容",
                // 回车即发送。DESIGN 2.2 的快路原话是"输入直接回车 = 原始 B 站搜索",
                // 换成 SearchField 之前这条根本没实现:OutlinedTextField 的 singleLine
                // 只是不换行,键盘上那个键什么都不做。
                onSearch = onSend,
                modifier = Modifier.weight(1f),
            )
            // 发送是这一屏的主行动,用实心图标按钮 —— M3 说要提升某个动作的可见度就换成
            // filled/tonal,并且一屏只留一个。
            FilledIconButton(onClick = onSend, enabled = input.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

/** 搜索接口返回原始整数计数,这里自己做展示折算。 */
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

@Preview(showBackground = true, name = "空态")
@Composable
private fun SearchChatScreenEmptyPreview() {
    BilbyTheme {
        SearchChatScreen(SearchChatUiState(), {}, {}, {}, {}, {}, {}, {})
    }
}

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
                            users = listOf(
                                SearchUser(mid = 1L, name = "示例UP主", avatarUrl = "", fansCount = 45_000L, signature = ""),
                            ),
                            hasMore = false,
                        ),
                    ),
                ),
            ),
            onInputChange = {}, onModeChange = {}, onSend = {},
            onVideoClick = {}, onUserClick = {}, onLoadMore = {}, onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "助理模式已出答案")
@Composable
private fun SearchChatScreenAgentAnswerPreview() {
    BilbyTheme {
        SearchChatScreen(
            state = SearchChatUiState(
                mode = SearchMode.Agent,
                turns = listOf(
                    SearchTurn(
                        id = 1L,
                        query = "适合上班摸鱼看的搞笑动画",
                        mode = SearchMode.Agent,
                        result = TurnResult.Agent(
                            steps = listOf(
                                AgentStep("搜索:搞笑动画", listOf(previewTrace("BV1aa", "笑到打鸣的搞笑动画合集")), true),
                                AgentStep("读取:BV1aa 的热评", emptyList(), true),
                            ),
                            blocks = listOf(
                                AnswerBlock.Text("时长短、弹幕密度高,评论区反馈「摸鱼时长刚好一集」:"),
                                AnswerBlock.Video("BV1aa", previewTrace("BV1aa", "笑到打鸣的搞笑动画合集")),
                                AnswerBlock.Text("再往后是同一个 UP 的旧作,节奏一致。"),
                            ),
                        ),
                    ),
                ),
            ),
            onInputChange = {}, onModeChange = {}, onSend = {},
            onVideoClick = {}, onUserClick = {}, onLoadMore = {}, onRetry = {},
        )
    }
}
