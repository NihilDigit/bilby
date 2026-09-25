package dev.bilby.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import dev.bilby.R
import dev.bilby.agent.AgentStep
import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.AnswerBlock
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.AgentTurnView
import dev.bilby.agent.TraceItem
import dev.bilby.data.SearchVideo
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.KeepScrolledToBottom
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.rememberBottomFollow
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Spacing

// label 曾经存在但没有任何界面显示它,随抽取一并去掉。
enum class SearchMode { Normal, Agent }

/** 助理的一轮对话。普通搜索没有"轮"这个概念,见 [NormalSearchState]。 */
data class SearchTurn(val id: Long, val query: String, val result: AgentTurnState)

// NormalSearchState 与它的状态机搬去了 NormalSearchController.kt:标签结果页要的是同一套。

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
 * 搜索 tab。两种模式长两样,因为它们本来就不同构:
 *
 * - **普通搜索是一个搜索页**:搜索栏在顶上,下面是结果(或历史)。M3 search 页给搜索栏定的
 *   位置就在顶部,点开铺满成搜索视图、回车收起。
 * - **助理是一段对话**:输入在下,一轮轮结果在上,像 Claude App 但内容是视频列表。
 *
 * 两种模式原先共用底部同一个输入框,理由是"切模式时框从底跳到顶,闪得扎眼"。代价是普通搜索
 * 也被塞进了对话的形状:搜索词在屏幕最下面,结果从最上面开始,人要上下来回看。切模式是一次
 * 明确的页面切换(模式标签就写在框里),框换个位置正说明换了一种东西。
 *
 * 两种模式之间用右下角的悬浮按钮来回切([ModeSwitchFab]);"新会话"只在助理模式下出现,
 * 在输入框左边 —— 普通搜索压根没有"会话"这个概念,那不是藏起来了。
 *
 * 结果页只有结果——无热搜、无"换一批"(DESIGN 2.2/3.4)。历史是自己敲过的字,不在此列。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchChatScreen(
    state: SearchChatUiState,
    onInputChange: (String) -> Unit,
    onModeChange: (SearchMode) -> Unit,
    onSend: () -> Unit,
    onNewSession: () -> Unit,
    onVideoClick: (bvid: String) -> Unit,
    onRetry: () -> Unit,
    /** 普通搜索结果区的动作(三栏、筛选、翻页)。 */
    resultActions: SearchResultActions,
    /** 最近搜过的词,最近的在前。只在普通搜索里露面,时机见 [NormalBody]。 */
    searchHistory: List<String> = emptyList(),
    onHistoryClick: (String) -> Unit = {},
    onHistoryRemove: (String) -> Unit = {},
    onHistoryClear: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var inputFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // **发送要收焦点。** 历史面板盖在结果上的判据之一就是"光标在输入框里"(见 NormalBody),
    // 而发送不动焦点,于是搜完之后这一屏还停在历史上。点历史同理:它直接搜,也要收。
    val send: () -> Unit = {
        focusManager.clearFocus()
        onSend()
    }

    // 宽屏下这一栏不拉满:结果是"封面 + 两行文字"的条目,助理那边整段都是正文,行长一超过
    // 可读宽度就得靠转头扫。输入框在同一个容器里,跟着一起收。
    AdaptiveContent(modifier = modifier.fillMaxSize(), maxWidth = Breakpoints.ReadableWidth) {
        Column(modifier = Modifier.fillMaxSize()) {
            when (state.mode) {
                SearchMode.Normal -> {
                    NormalSearchBar(
                        input = state.input,
                        onInputChange = onInputChange,
                        onSend = send,
                        focused = inputFocused,
                        onFocusChange = { inputFocused = it },
                        onCollapse = { focusManager.clearFocus() },
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        NormalBody(
                            state = state.normal,
                            actions = resultActions,
                            history = searchHistory,
                            onHistoryClick = { query ->
                                focusManager.clearFocus()
                                onHistoryClick(query)
                            },
                            onHistoryRemove = onHistoryRemove,
                            onHistoryClear = onHistoryClear,
                            inputFocused = inputFocused,
                        )
                        // 正在打字(展开态)时不露:那时键盘占着下半屏,按钮会浮在历史上挡字。
                        if (!inputFocused) {
                            ModeSwitchFab(
                                current = SearchMode.Normal,
                                onModeChange = onModeChange,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                        }
                    }
                }

                SearchMode.Agent -> {
                    Box(modifier = Modifier.weight(1f)) {
                        AgentPane(
                            state = state.agent,
                            onVideoClick = onVideoClick,
                            onRetry = onRetry,
                        )
                        // 认键盘,不认焦点:返回键收起键盘后焦点仍留在底部输入框里,按焦点判断的话
                        // 按钮就再也不出来了。普通模式不同,那边焦点就是展开态,返回键会一并收掉。
                        if (!WindowInsets.isImeVisible) {
                            ModeSwitchFab(
                                current = SearchMode.Agent,
                                onModeChange = onModeChange,
                                modifier = Modifier.align(Alignment.BottomEnd),
                            )
                        }
                    }
                    InputBar(
                        input = state.input,
                        onInputChange = onInputChange,
                        onSend = send,
                        // 开新会话是清空助理上下文的唯一入口(DESIGN 3.1:会话必须由用户显式开启)。
                        onNewSession = onNewSession,
                        onFocusChange = { inputFocused = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 普通搜索的顶部搜索栏。**聚焦时就是展开态**:左边换成返回箭头,下面整块换成历史(见
 * [NormalBody]);回车或点历史收起。M3 的 search bar 展开成 search view 就是这两态,这里
 * 不用组件本身,因为它的展开态自己要铺满窗口,而这一页下面还有底栏。
 */
@Composable
private fun NormalSearchBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onCollapse: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (focused) Spacing.Hair else Spacing.Comfortable, end = Spacing.Comfortable)
            .padding(vertical = Spacing.Tight),
    ) {
        // 展开态的出口。系统返回同样能收起(见 NormalBody 里的 BackHandler)。
        if (focused) {
            IconButton(onClick = onCollapse) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        }
        SearchField(
            value = input,
            onValueChange = onInputChange,
            // 占位文案同时是这个框的无障碍标签,所以不能空着。
            placeholder = stringResource(R.string.search_empty),
            // 回车即搜。DESIGN 2.2 的快路原话是"输入直接回车 = 原始 B 站搜索"。
            onSearch = onSend,
            onFocusChange = onFocusChange,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 切到另一种搜索的悬浮按钮:普通搜索里是「助理」,助理里是「搜索」。
 *
 * **从输入框尾部的下拉挪到这里。** 那个下拉是"这个框现在归谁答"的状态,可两种模式早已不是
 * 同一个框(普通在顶、助理在底),下拉挂在哪个框上都只说了一半。一颗浮在内容右下角的按钮
 * 说的是"去另一种",位置在两种模式里一样。
 *
 * 只有图标:闪光(去助理)和放大镜(回搜索)是这两件事各自的通行符号,带字的 extended FAB
 * 横在结果列表右下角挡掉的宽度多出一倍。名字给读屏(contentDescription)。
 */
@Composable
private fun ModeSwitchFab(
    current: SearchMode,
    onModeChange: (SearchMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = if (current == SearchMode.Normal) SearchMode.Agent else SearchMode.Normal
    FloatingActionButton(
        onClick = { onModeChange(target) },
        modifier = modifier.padding(Spacing.Comfortable),
    ) {
        Icon(
            if (target == SearchMode.Agent) Icons.Outlined.AutoAwesome else Icons.Outlined.Search,
            contentDescription = stringResource(
                if (target == SearchMode.Agent) R.string.search_mode_agent else R.string.search_mode_normal,
            ),
        )
    }
}

/** 列表底部为悬浮按钮留出的高度:56dp 的按钮加上下各 16dp 的边距。 */
internal val ModeFabClearance = 88.dp

/**
 * 普通搜索的正文:历史,或者结果。
 *
 * 历史在两种时候露面:**还没搜过**,以及**光标回到搜索栏**。后者是搜完之后再拿到历史的唯一
 * 途径,而"再搜一次刚才那个"这个念头恰恰常发生在看完一轮结果、手已经点回搜索栏的时候。
 * 绑在焦点上而不是一直挂着:结果还在读的时候摆一片旧关键词,才是在把人从当前结果引开。
 */
@Composable
private fun NormalBody(
    state: NormalSearchState,
    actions: SearchResultActions,
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    inputFocused: Boolean,
) {
    val focusManager = LocalFocusManager.current
    // 展开态(搜索栏有焦点)时,返回先收起,不直接离开这一页。
    BackHandler(enabled = inputFocused) { focusManager.clearFocus() }

    when {
        history.isNotEmpty() && (inputFocused || state.query.isEmpty()) ->
            SearchHistoryPanel(history, onHistoryClick, onHistoryRemove, onHistoryClear)

        state.query.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                message = stringResource(R.string.search_empty),
                // 普通搜索的空态给放大镜,和助理那一屏的图标分开。
                icon = Icons.Outlined.Search,
            )
        }

        else -> SearchResults(state = state, actions = actions)
    }
}

/**
 * 最近搜过的词,一排可换行的 chip。**是自己敲过的字,不是热搜词** —— 后者是把别人的热门查询
 * 推给你,属于 DESIGN 2.2 禁掉的推送式入口;这里只是省一次重复输入。
 *
 * **chip 而不是一行一条。** 一行一条时五条就是一屏,上限因此只敢给五;chip 按词长排,几十个
 * 词也只占几行,要找的那个一眼扫得到。历史本身不设上限(见 SettingsStore.searchHistory)。
 *
 * **点一下直接搜,长按出菜单删这一条。** 删除从常驻的 × 收进长按:每个 chip 各带一个 ×,
 * 一半的面积是删除按钮,点偏一点就删掉了想搜的那个。
 *
 * **有「清空」,而且要确认。** 五条时逐条删就够;没有上限之后一条条长按删是折磨。清空不可
 * 撤销,所以隔一个确认框。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchHistoryPanel(
    history: List<String>,
    onClick: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
) {
    var confirmingClear by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.search_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmingClear = true }) {
                Text(stringResource(R.string.action_clear))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            history.forEach { query ->
                HistoryChip(query = query, onClick = { onClick(query) }, onRemove = { onRemove(query) })
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.search_history_clear_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    onClear()
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(R.string.follow_unfollow_keep))
                }
            },
        )
    }
}

/**
 * 历史里的一个词。形状与底色取 M3 的 suggestion chip,但自己拼:material3 的 chip 只给单击,
 * 这里还要长按。菜单挂在 chip 上,从按下去的那个词旁边弹出来。
 */
@Composable
private fun HistoryChip(query: String, onClick: () -> Unit, onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                // 视觉 32dp,触控撑到 48dp(风格指南 §3)。
                .minimumInteractiveComponentSize()
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.search_history_remove),
                ),
        ) {
            Text(
                query,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .heightIn(min = HistoryChipHeight)
                    .wrapContentHeight()
                    .padding(horizontal = Spacing.Cozy),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.search_history_remove)) },
                onClick = {
                    menuOpen = false
                    onRemove()
                },
            )
        }
    }
}

/** M3 chip 的容器高度。 */
private val HistoryChipHeight = 32.dp

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
        // 底部让出切回搜索的悬浮按钮,答案的最后一行才不被它盖住。
        contentPadding = PaddingValues(top = Spacing.Cozy, bottom = ModeFabClearance),
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
            // **上限按可用宽度的比例算,不写死 280dp。** 那个数是按 360dp 宽的手机定的,平板上
            // 一句长问句会在整屏宽度的中间断成好几行,右边留着一大片空;而窄屏上它比屏幕还宽,
            // 等于没有上限。留出的那两成是"这一侧是我说的话"这个形状本身 —— 气泡铺满整行就
            // 和下面助理的正文分不开了。
            modifier = Modifier.fillMaxWidth(BubbleWidthFraction).wrapContentWidth(Alignment.End),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            )
        }
    }
}

/** 用户气泡最宽占多少。留出的两成让"谁在说话"从形状上就读得出来。 */
private const val BubbleWidthFraction = 0.8f

// ---- 输入框 ----

/**
 * 助理模式的输入区,固定在底部。底色用 surfaceContainer 而不是 tonalElevation:M3 的做法是靠
 * surface container 这一族的色阶差表达层次,阴影和 tonal elevation 留给真正浮起来的东西。
 */
@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onNewSession: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                // 新会话:把助理上下文清掉的**动作**。普通搜索没有会话,这个按钮只长在这里。
                IconButton(onClick = onNewSession) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.search_new_session),
                    )
                }
                SearchField(
                    value = input,
                    onValueChange = onInputChange,
                    // 占位文案同时是这个框的无障碍标签,所以不能空着 —— 读屏对着一个裸输入框
                    // 只会念"编辑框"。它确实和空态那句重复,但只在还没问过的那一屏重复:
                    // 助理模式发完就清空输入框,那之后整屏没有任何东西说明这里该填什么。
                    placeholder = stringResource(R.string.search_agent_empty),
                    // 回车即发送,和按右边的发送键一样。
                    onSearch = onSend,
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
 * 搜索接口返回原始整数计数,这里自己做展示折算。分档除数取自资源:
 * 中文按万/亿分档,英文按 K/M,只翻译单位后缀会让英文差一个量级。
 */

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
                    videos = SearchListState(
                        items = listOf(
                            previewVideo("BV1aa", "宝可梦朱紫 全剧情流程"),
                            previewVideo("BV1bb", "宝可梦对战入门:属性克制"),
                        ),
                        started = true,
                    ),
                ),
            ),
            onInputChange = {}, onModeChange = {}, onSend = {}, onNewSession = {},
            onVideoClick = {}, onRetry = {}, resultActions = PreviewResultActions,
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
            onInputChange = {}, onModeChange = {}, onSend = {}, onNewSession = {},
            onVideoClick = {}, onRetry = {}, resultActions = PreviewResultActions,
        )
    }
}

private val PreviewResultActions = SearchResultActions(
    onTabSelected = {}, onOrderChange = {}, onDurationChange = {}, onArticleOrderChange = {},
    onVideoClick = {}, onUserClick = {}, onArticleClick = {}, onLoadMore = {}, onRetry = {},
)
