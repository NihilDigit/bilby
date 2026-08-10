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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.agent.AgentStep
import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.AnswerBlock
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.AgentTurnView
import dev.bilby.ui.components.AnswerBlocks
import dev.bilby.agent.TraceItem
import dev.bilby.data.SearchUser
import dev.bilby.data.SearchVideo
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.KeepScrolledToBottom
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.SortRow
import dev.bilby.ui.components.rememberBottomFollow
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Breakpoints
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

/** 助理的一轮对话。普通搜索没有"轮"这个概念,见 [NormalSearchState]。 */
data class SearchTurn(val id: Long, val query: String, val result: AgentTurnState)

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
 * "问谁"和"新会话"都长在输入框那一条里(根 tab 不再有共用顶栏可借),但各是各的控件:
 * 前者在框内尾部,是状态;后者在框左边,是动作。新会话只在助理模式下出现——普通搜索压根
 * 没有"会话"这个概念,那不是藏起来了。
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
    /** 最近搜过的词,最近的在前。只在普通搜索里露面,时机见 [NormalPane]。 */
    searchHistory: List<String> = emptyList(),
    onHistoryClick: (String) -> Unit = {},
    onHistoryRemove: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var inputFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // **发送要收焦点。** 历史列表盖在结果上的判据之一就是"光标在输入框里"(见 NormalPane),
    // 而发送不动焦点,于是搜完之后这一屏还停在历史上 —— 而刚搜的那个词正好排在最上面,
    // 读起来像"搜索只是把词记进了历史,得再点一下才进得去"。点历史那条路早就收了焦点,
    // 漏的是发送这条。
    val send: () -> Unit = {
        focusManager.clearFocus()
        onSend()
    }
    val inputBar: @Composable () -> Unit = {
        InputBar(
            input = state.input,
            onInputChange = onInputChange,
            mode = state.mode,
            onModeChange = onModeChange,
            onSend = send,
            // 开新会话是清空助理上下文的唯一入口(DESIGN 3.1:会话必须由用户显式开启)。
            onNewSession = onNewSession,
            onFocusChange = { inputFocused = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // 宽屏下这一栏不拉满:结果是"封面 + 两行文字"的条目,助理那边整段都是正文,行长一超过
    // 可读宽度就得靠转头扫。输入框在同一个容器里,跟着一起收 —— 分开算的话底部那条会比它
    // 上面的结果宽出一大截,看着像两个页面。
    AdaptiveContent(modifier = modifier.fillMaxSize(), maxWidth = Breakpoints.ReadableWidth) {
        Column(modifier = Modifier.fillMaxSize()) {
            // **只有普通搜索能下拉刷新。** 助理模式下这个手势会重跑一整轮 LLM ——
            // 用户下拉时想要的是"再查一次同样的东西",而那边一次下拉是一次真金白银的请求,
            // 而且答案还会变。要重问就用输入框重新问,或者按输入框左边的新会话。
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
                        // 点了历史就是发起一次搜索,焦点该离开输入框 —— 不收的话结果出来了
                        // 这一屏还停在历史上,得再点一下别处才看得见。
                        onHistoryClick = { keyword ->
                            focusManager.clearFocus()
                            onHistoryClick(keyword)
                        },
                        onHistoryRemove = onHistoryRemove,
                        inputFocused = inputFocused,
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
    /** 输入框此刻有没有焦点。见下面对历史何时露面的说明。 */
    inputFocused: Boolean,
) {
    // 历史在两种时候露面:**还没搜过**,以及**光标回到输入框**。后者是原先漏掉的那一半 ——
    // 搜完之后历史就再也拿不到了,可"再搜一次刚才那个"这个念头恰恰常发生在看完一轮结果、
    // 手已经点回输入框的时候。绑在焦点上而不是一直挂着:结果还在读的时候摆一列旧关键词,
    // 才是在把人从当前结果引开。
    if (history.isNotEmpty() && (state.query.isEmpty() || inputFocused)) {
        SearchHistoryList(history, onHistoryClick, onHistoryRemove)
        return
    }
    if (state.query.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                message = stringResource(R.string.search_empty),
                // 普通搜索的空态给放大镜,和助理那一屏的图标分开:两种模式的空屏长得一样的话,
                // 只剩底下那个小小的模式标签能告诉人现在归谁答。
                icon = Icons.Outlined.Search,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    // LaunchedEffect 的块体捕获的是启动那一刻的 `state` —— 它是个 data class 值,不是 State
    // 对象,之后再怎么变都不会反映进来。原先直接读 `state.hasMore` 的写法读的是一份永远停在
    // 查询刚变那一刻的快照,两道守卫都是死的。用 rememberUpdatedState 拿最新的那一份。
    val current by rememberUpdatedState(state)
    LaunchedEffect(listState, state.query) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> lastVisible != null && lastVisible >= total - 1 - PrefetchThreshold }
            // 列表在首页返回之前就已经排好版,那时只有排序行和页脚,末项自然落在预取窗口里。
            // 没有 videos.isNotEmpty() 这一条,每次新搜索都会在首页还在飞的时候先要一次续页。
            .collect { if (current.videos.isNotEmpty() && current.hasMore && !current.appending) onLoadMore() }
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
                modifier = Modifier.padding(horizontal = Spacing.Comfortable),
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
            EmptyState(
                message = stringResource(R.string.search_agent_empty),
                icon = Icons.Outlined.AutoAwesome,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val follow = rememberBottomFollow(listState)
    val running = state.turns.lastOrNull()?.result?.running == true

    // 跑的时候跟着新冒出来的步骤走,直到用户自己往回滑。原先只在**轮数**变化时滚一次,
    // 于是一整轮检索里画面停在第一步:后面的步骤都长在屏幕外,而它们才是有内容的部分。
    KeepScrolledToBottom(listState, follow, enabled = running)

    // 答案落地时过程会自动折叠,内容一下子缩短。这时候要回到这一轮的开头 —— 答案是从
    // 第一句读起的,停在底边等于从结论的最后一行开始读。用户已经自己滑走了就不抢。
    LaunchedEffect(state.turns.size, running) {
        if (!running && follow.following) listState.animateScrollToItem(state.turns.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().nestedScroll(follow.connection),
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
        AgentTurnView(turn = turn.result, onVideoClick = onVideoClick, onRetry = onRetry)
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
    onNewSession: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val agent = mode == SearchMode.Agent
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                // 新会话单独一个按钮,不并进模式菜单:它是**动作**(把上下文清掉),而那个菜单
                // 里两项是**状态**(现在归谁答)。混在一起的话,一个会改选中项、一个不会,
                // 打开菜单还得先分辨哪行点下去是什么。只在助理模式出现,普通搜索没有会话。
                if (agent) {
                    IconButton(onClick = onNewSession) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.search_new_session),
                        )
                    }
                }
                SearchField(
                    value = input,
                    onValueChange = onInputChange,
                    // **不给占位文案。** 它和上面那句空态逐字重复,而两者总是同时出现:
                    // 输入框空的时候,结果区正好是那一屏空态。同一句话印两遍。
                    placeholder = "",
                    // 回车即发送。DESIGN 2.2 的快路原话是"输入直接回车 = 原始 B 站搜索",
                    // 换成 SearchField 之前这条根本没实现:OutlinedTextField 的 singleLine
                    // 只是不换行,键盘上那个键什么都不做。
                    onSearch = onSend,
                    trailing = { SearchModeMenu(mode, onModeChange) },
                    onFocusChange = onFocusChange,
                    modifier = Modifier.weight(1f),
                )
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
}

/**
 * 「谁来回答」:输入框尾部一个带标签的低强调按钮,点开是两项菜单。
 *
 * **它是一个页面级的模式,所以带文字标签**:切过去之后排序、翻页、下拉刷新、整块结果区
 * 全都换掉,用户看到的是换了一个页面,一个没有标签的图标说不清这件事。
 *
 * 但它**不该是一组按钮**。规范里这个位置的原文是 search 页的
 * "Trailing actions can include: Additional modes of searching like voice search" —— 换搜索
 * 方式本来就长在输入框尾部。而 connected button group(segmented button 的替代品)在
 * button groups 页要求 "should span the width of the page or surface it's placed on",
 * 摊开成一条按钮带,重量压过了它旁边的输入框和发送键。
 *
 * 菜单里只有模式,没有「新会话」——那是一个动作,不是一个可选状态,见 [InputBar]。
 */
@Composable
private fun SearchModeMenu(
    mode: SearchMode,
    onModeChange: (SearchMode) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = Spacing.Tight),
        ) {
            Text(
                stringResource(
                    if (mode == SearchMode.Agent) R.string.search_mode_agent
                    else R.string.search_mode_normal,
                ),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SearchMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (option == SearchMode.Agent) R.string.search_mode_agent
                                else R.string.search_mode_normal,
                            ),
                        )
                    },
                    trailingIcon = {
                        if (option == mode) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                    onClick = {
                        open = false
                        onModeChange(option)
                    },
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
                            result = AgentTurnState(
                                steps = listOf(
                                    AgentStep(
                                        label = "搜索:搞笑动画",
                                        items = listOf(previewTrace("BV1aa", "笑到打鸣的搞笑动画合集")),
                                        finished = true,
                                    ),
                                ),
                                blocks = listOf(
                                    AnswerBlock.Text("时长短、**弹幕密度高**,评论区反馈「摸鱼时长刚好一集」:"),
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
