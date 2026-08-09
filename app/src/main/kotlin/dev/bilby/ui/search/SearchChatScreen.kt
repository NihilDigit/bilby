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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.bilby.R
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
import dev.bilby.ui.components.SortRow
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

private const val PrefetchThreshold = 5

/** 综合 / 最多播放 / 最新发布,顺序和取值都照 B 站搜索页本身。 */
private val SearchOrders = SearchOrder.entries.map { it to it.labelRes }

// label 曾经存在但没有任何界面显示它,随抽取一并去掉。
enum class SearchMode { Normal, Agent }

sealed interface TurnResult {
    data class Agent(
        val steps: List<AgentStep>,
        /** 一段夹着视频卡片的正文,见 AnswerBlocks。 */
        val blocks: List<AnswerBlock> = emptyList(),
        val running: Boolean = false,
        val error: String? = null,
    ) : TurnResult
}

data class AgentStep(val label: String, val items: List<TraceItem>, val finished: Boolean)

/** 助理的一轮对话。普通搜索没有"轮"这个概念,见 [NormalSearchState]。 */
data class SearchTurn(val id: Long, val query: String, val result: TurnResult.Agent)

/**
 * 普通搜索的状态:**一次查询一份结果**,不留历史。它就是一个搜索页,上一次搜了什么
 * 和这一次无关。
 */
data class NormalSearchState(
    val query: String = "",
    val order: SearchOrder = SearchOrder.Comprehensive,
    val videos: List<SearchVideo> = emptyList(),
    val users: List<SearchUser> = emptyList(),
    // 视频和用户两路请求并行、互相独立(性能计划 7.1):慢的那路失败或还没回来
    // 不能挡住已经到手的另一路,所以 loading/error 各记各的,不共用一份粗粒度状态。
    val videoLoading: Boolean = false,
    val videoError: String? = null,
    val userLoading: Boolean = false,
    val userError: String? = null,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
)

/** 助理的状态:一段可以追问下去的对话。 */
data class AgentSearchState(val turns: List<SearchTurn> = emptyList())

/**
 * 两种模式各持一份状态。合用一条轮次列表时,普通搜索的结果集和助理对话会交替出现,
 * 两边都读不下去 —— 一个是可翻页的列表,另一个是带工具轨迹的对话,本来就不同构。
 * 切换模式只是换显示哪一份,两份都留着。
 */
data class SearchChatUiState(
    val mode: SearchMode = SearchMode.Normal,
    val input: String = "",
    val normal: NormalSearchState = NormalSearchState(),
    val agent: AgentSearchState = AgentSearchState(),
)

/**
 * 对话式搜索(团队要求的形态):输入在下,一轮轮结果在上,像 Claude App 但内容是视频列表。
 * 结果页只有结果——无历史、无热搜、无"换一批"(DESIGN 2.2/3.4)。
 *
 * "新会话"长在这一页自己的内容里(根 tab 不再有共用顶栏可借):只在助理模式下才有会话
 * 可开,所以只在 `mode == Agent` 时画一个靠右的图标,别的时候不占位置——不是"藏起来",
 * 是普通搜索模式压根没有"会话"这个概念。
 */
@Composable
fun SearchChatScreen(
    state: SearchChatUiState,
    onInputChange: (String) -> Unit,
    onModeChange: (SearchMode) -> Unit,
    onOrderChange: (SearchOrder) -> Unit,
    onSend: () -> Unit,
    onNewSession: () -> Unit,
    onVideoClick: (bvid: String) -> Unit,
    onUserClick: (mid: Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    /** 最近搜过的词,最近的在前。只在普通搜索、且还没搜过东西时露面。 */
    searchHistory: List<String> = emptyList(),
    onHistoryClick: (String) -> Unit = {},
    onHistoryRemove: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val inputBar: @Composable () -> Unit = {
        InputBar(
            input = state.input,
            onInputChange = onInputChange,
            mode = state.mode,
            onModeChange = onModeChange,
            onSend = onSend,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 开新会话是清空助理上下文的唯一入口(DESIGN 3.1:会话必须由用户显式开启),
        // 属于"改变整页状态"的动作。IconButton 自带 48dp 触摸区,不需要额外撑。
        if (state.mode == SearchMode.Agent) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onNewSession) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.search_new_session))
                }
            }
        }
        // **只有普通搜索能下拉刷新。** 助理模式下这个手势会重跑一整轮 LLM ——
        // 用户下拉时想要的是"再查一次同样的东西",而那边一次下拉是一次真金白银的请求,
        // 而且答案还会变。要重问就用输入框重新问,或者用右上角的新会话。
        when (state.mode) {
            SearchMode.Normal -> PullToRefreshBox(
                isRefreshing = state.normal.videoLoading && state.normal.videos.isNotEmpty(),
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f),
            ) {
                NormalPane(
                    state = state.normal,
                    onOrderChange = onOrderChange,
                    onVideoClick = onVideoClick,
                    onUserClick = onUserClick,
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    history = searchHistory,
                    onHistoryClick = onHistoryClick,
                    onHistoryRemove = onHistoryRemove,
                )
            }

            SearchMode.Agent -> Box(modifier = Modifier.weight(1f)) {
                AgentPane(
                    state = state.agent,
                    onVideoClick = onVideoClick,
                    onRetry = onRetry,
                )
            }
        }

        // **输入框常驻底部,两种模式都一样。** 位置由模式决定过一版,切模式时框会从底跳到顶,
        // 闪得扎眼 —— 共用一个框就该只有一个位置。
        //
        // 内容仍然从上往下排:上面那个 Box 占满剩余高度,两种 pane 的列表都从它的顶边开始。
        // 底部这一条不参与内容的排布,它是常驻的输入区。
        inputBar()
    }
}

/** 普通搜索:一份结果、可翻页,没有"轮次"。翻到底自动续页。 */
@Composable
private fun NormalPane(
    state: NormalSearchState,
    onOrderChange: (SearchOrder) -> Unit,
    onVideoClick: (String) -> Unit,
    onUserClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
) {
    if (state.query.isEmpty()) {
        // 还没搜过东西时,这一屏原先只有一句空态。历史摆在这里而不是搜完之后:它的用处是
        // "再搜一次刚才那个",而那个念头只发生在还没开始搜的时候;结果出来之后再挂一份
        // 旧关键词,是在把人从当前结果引开。
        if (history.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(stringResource(R.string.search_empty))
            }
        } else {
            SearchHistoryList(history, onHistoryClick, onHistoryRemove)
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(listState, state.query) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> lastVisible != null && lastVisible >= total - 1 - PrefetchThreshold }
            .collect { if (state.hasMore && !state.appending) onLoadMore() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.Cozy),
    ) {
        // 排序放结果列表上方,随内容一起滚 —— 这个页面对话式布局里没有一块常驻的
        // "结果区顶部",硬做一个会跟输入在下、结果在上的整体结构对不上。
        item(key = "order") {
            SortRow(
                options = SearchOrders,
                selected = state.order,
                onSelect = onOrderChange,
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Hair),
            )
        }
        if (state.users.isNotEmpty()) {
            item(key = "users") { UserRow(state.users, onUserClick) }
        }
        items(state.videos, key = { it.bvid }) { video ->
            VideoRow(item = video.toRowUi(), onClick = { onVideoClick(video.bvid) })
        }
        item(key = "footer") {
            // 只看视频这一路的 loading/error:用户那路失败或还没回来不改变视频列表的落地状态,
            // 表现就是没有用户条,不是整屏错误。
            when {
                state.videoError != null -> FullScreenError(state.videoError, onRetry, Modifier.fillMaxWidth())
                state.videoLoading -> InlineProgress(
                    stringResource(R.string.search_loading),
                    Modifier.padding(Spacing.Comfortable),
                )

                state.appending -> InlineProgress(
                    stringResource(R.string.search_loading_more),
                    Modifier.padding(Spacing.Comfortable),
                )

                state.videos.isEmpty() -> EmptyState(stringResource(R.string.search_no_results))
            }
        }
    }
}

/**
 * 最近搜过的词。**是自己敲过的字,不是热搜词** —— 后者是把别人的热门查询推给你,属于
 * DESIGN 2.2 禁掉的推送式入口;这里只是省一次重复输入,点开的还是自己上次找的东西。
 *
 * 每条都能单独删,所以不另做「全部清空」:一共至多五条,一个额外的破坏性按钮换不到多少便利,
 * 反而多一处误触。
 */
@Composable
private fun SearchHistoryList(
    history: List<String>,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    // **从下往上排。** 输入框常驻在这一屏底部,最近搜过的那条应该离手指最近;正常方向下
     // 五条历史会贴在屏幕顶端,离输入框最远的位置反而放着最可能被点的东西。
    //
    // `reverseLayout` 让内容从底边开始往上长,于是发射顺序也跟着倒过来读:先发的在下面。
    // 所以 items 在前(最近的那条落在最底、紧挨输入框),标题最后发,落在最上面。
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.Cozy),
        reverseLayout = true,
    ) {
        items(history, key = { it }) { query ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onClick(query) }
                    .padding(start = Spacing.Comfortable, end = Spacing.Tight),
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    query,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = Spacing.Tight, top = Spacing.Tight, bottom = Spacing.Tight),
                )
                IconButton(onClick = { onRemove(query) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.search_history_remove),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item(key = "history_title") {
            Text(
                stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            )
        }
    }
}

/** 助理:一段可以追问下去的对话,跟随最新一轮。 */
@Composable
private fun AgentPane(
    state: AgentSearchState,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    if (state.turns.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(stringResource(R.string.search_agent_empty))
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(state.turns.size) {
        listState.animateScrollToItem(state.turns.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.Cozy),
        verticalArrangement = Arrangement.spacedBy(Spacing.Loose),
    ) {
        items(state.turns, key = { it.id }) { turn ->
            TurnRow(turn = turn, onVideoClick = onVideoClick, onRetry = onRetry)
        }
    }
}

@Composable
private fun TurnRow(
    turn: SearchTurn,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Cozy)) {
        UserBubble(
            text = turn.query,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable),
        )
        AgentTurnResult(result = turn.result, onVideoClick = onVideoClick, onRetry = onRetry)
    }
}

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
                text = stringResource(R.string.search_fans, formatCount(user.fansCount)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
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
                text = stringResource(R.string.agent_running),
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

// ---- 输入框 ----

/**
 * 输入区固定在底部。底色用 surfaceContainer 而不是 tonalElevation:M3 的做法是靠
 * surface container 这一族的色阶差表达层次,阴影和 tonal elevation 留给真正浮起来的东西。
 */
@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val agent = mode == SearchMode.Agent
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            SearchField(
                value = input,
                onValueChange = onInputChange,
                placeholder = stringResource(
                    if (agent) R.string.search_agent_empty else R.string.search_empty,
                ),
                // 回车即发送。DESIGN 2.2 的快路原话是"输入直接回车 = 原始 B 站搜索",
                // 换成 SearchField 之前这条根本没实现:OutlinedTextField 的 singleLine
                // 只是不换行,键盘上那个键什么都不做。
                onSearch = onSend,
                modifier = Modifier.weight(1f),
            )
            // 闪光亮起 = 这一句交给助理。整个 app 里闪光只表示助理(播放页的「找相关」用的
            // 是同一个图标),所以它不需要文字解释;放在输入框上是因为要决定的是**这一句话
            // 由谁回答**,那是输入的属性,不是一个页面级的模式开关。
            IconToggleButton(checked = agent, onCheckedChange = { onModeChange(if (it) SearchMode.Agent else SearchMode.Normal) }) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = stringResource(
                        if (agent) R.string.search_mode_to_normal else R.string.search_mode_to_agent,
                    ),
                    tint = if (agent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 发送是这一屏的主行动,用实心图标按钮 —— M3 说要提升某个动作的可见度就换成
            // filled/tonal,并且一屏只留一个。
            FilledIconButton(onClick = onSend, enabled = input.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.action_send),
                )
            }
        }
    }
}

/**
 * 搜索接口返回原始整数计数,这里自己做展示折算。分档除数取自资源:
 * 中文按万/亿分档,英文按 K/M,只翻译单位后缀会让英文差一个量级。
 */
@Composable
private fun formatCount(count: Long): String {
    val large = integerResource(R.integer.count_divisor_large)
    val small = integerResource(R.integer.count_divisor_small)
    return when {
        count >= large -> stringResource(R.string.count_large, count.toDouble() / large)
        count >= small -> stringResource(R.string.count_small, count.toDouble() / small)
        else -> count.toString()
    }
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

@Preview
@Composable
private fun SearchChatScreenNormalPreview() {
    BilbyTheme {
        SearchChatScreen(
            state = SearchChatUiState(
                mode = SearchMode.Normal,
                normal = NormalSearchState(
                    query = "宝可梦",
                    videos = listOf(
                        previewVideo("BV1aa", "宝可梦朱紫 全剧情流程"),
                        previewVideo("BV1bb", "宝可梦对战入门:属性克制"),
                    ),
                ),
            ),
            onInputChange = {}, onModeChange = {}, onOrderChange = {}, onSend = {}, onNewSession = {},
            onVideoClick = {}, onUserClick = {}, onLoadMore = {}, onRetry = {},
        )
    }
}

@Preview
@Composable
private fun SearchChatScreenAgentAnswerPreview() {
    BilbyTheme {
        SearchChatScreen(
            state = SearchChatUiState(
                mode = SearchMode.Agent,
                agent = AgentSearchState(
                    turns = listOf(
                        SearchTurn(
                            id = 1L,
                            query = "适合上班摸鱼看的搞笑动画",
                            result = TurnResult.Agent(
                                steps = listOf(
                                    AgentStep("搜索:搞笑动画", listOf(previewTrace("BV1aa", "笑到打鸣的搞笑动画合集")), true),
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
            ),
            onInputChange = {}, onModeChange = {}, onOrderChange = {}, onSend = {}, onNewSession = {},
            onVideoClick = {}, onUserClick = {}, onLoadMore = {}, onRetry = {},
        )
    }
}
