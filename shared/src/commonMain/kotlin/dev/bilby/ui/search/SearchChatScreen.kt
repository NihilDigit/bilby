package dev.bilby.ui.search

import dev.bilby.ui.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import dev.bilby.data.SearchSuggestion
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import dev.bilby.ui.components.PillInputField
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.agent.AgentStep
import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.AgentAnswer
import dev.bilby.agent.AnswerSource
import dev.bilby.agent.citation
import dev.bilby.agent.StepKind
import dev.bilby.data.SideSheetPrefs
import dev.bilby.ui.AdaptiveListContent
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.components.AgentTurnView
import dev.bilby.ui.components.AgentQuestionBubble
import dev.bilby.agent.TraceItem
import dev.bilby.data.SearchVideo
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.KeepScrolledToBottom
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.SidePanelLayout
import dev.bilby.ui.components.SidePanelToggle
import dev.bilby.ui.components.rememberBottomFollow
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Spacing
import kotlin.math.roundToInt

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
    /** 窄屏显示哪一种。宽屏两种同时在屏,不看它。 */
    val mode: SearchMode = SearchMode.Normal,
    /** 搜索框。搜完词留在框里,见 SearchChatViewModel.search。 */
    val input: String = "",
    /** 助理的追问框。和搜索框分开:宽屏两个框同时在屏,各写各的。 */
    val agentInput: String = "",
    /** 搜索框当前内容的补全词,见 SearchChatViewModel.onInputChange。 */
    val suggestions: List<SearchSuggestion> = emptyList(),
    val normal: NormalSearchState = NormalSearchState(),
    val agent: AgentSearchState = AgentSearchState(),
)

/** 助理那一侧的全部动作。 */
class AgentActions(
    val onInputChange: (String) -> Unit,
    val onSend: () -> Unit,
    val onNewSession: () -> Unit,
    val onRetry: () -> Unit,
    val onVideoClick: (bvid: String) -> Unit,
)

/**
 * 搜索 tab:一个搜索框,回车是 B 站原始结果;助理在旁边另起一块。
 *
 * - **普通搜索是一个搜索页**:搜索栏在顶上,下面是结果(或历史)。
 * - **助理是一段对话**:输入在下,一轮轮结果在上。它是进阶工具,不是每个人都用:模型没配时
 *   它的入口一概不出现。
 *
 * **宽屏两者并排**:结果在主区,助理是右侧可关、可拖宽的侧栏,自带输入框,和空间页的动态
 * 同一个组件([SidePanelLayout]),搜索框旁不另设按钮。**窄屏**一次只放得下一种,右下角的
 * 「问助理」拿框里的词切进助理那一屏,左上的返回回到结果。
 *
 * 结果页只有结果——无热搜、无"换一批"(DESIGN 2.2/3.4)。历史是自己敲过的字,不在此列。
 */
@Composable
fun SearchChatScreen(
    state: SearchChatUiState,
    onInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAskAgent: () -> Unit,
    onLeaveAgent: () -> Unit,
    agentActions: AgentActions,
    /** 普通搜索结果区的动作(三栏、筛选、翻页)。 */
    resultActions: SearchResultActions,
    /** 模型配好了没有。没配时助理的入口一概不出现。 */
    agentAvailable: Boolean = false,
    /** 宽屏助理侧栏的开关与宽度;null 是还没读出来。 */
    agentPanel: SideSheetPrefs? = null,
    onAgentPanelOpenChange: (Boolean) -> Unit = {},
    onAgentPanelWidthChange: (Float) -> Unit = {},
    /** 最近搜过的词,最近的在前。 */
    searchHistory: List<String> = emptyList(),
    onHistoryClick: (String) -> Unit = {},
    onHistoryRemove: (String) -> Unit = {},
    onHistoryClear: () -> Unit = {},
    onSuggestionClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val wide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    val focusManager = LocalFocusManager.current
    // **发送要收焦点。** 历史盖在结果上的判据之一就是"光标在输入框里"(见 NormalSearch),
    // 而发送不动焦点,于是搜完之后这一屏还停在历史上。点历史、问助理同理。
    val search: () -> Unit = {
        focusManager.clearFocus()
        onSearch()
    }
    val askAgent: () -> Unit = {
        focusManager.clearFocus()
        onAskAgent()
    }
    val normalSearch: @Composable (panelToggle: (@Composable () -> Unit)?) -> Unit = { panelToggle ->
        NormalSearch(
            state = state.normal,
            input = state.input,
            onInputChange = onInputChange,
            onSearch = search,
            onAskAgent = askAgent.takeIf { agentAvailable },
            wide = wide,
            panelToggle = panelToggle,
            actions = resultActions,
            history = searchHistory,
            onHistoryClick = { query ->
                focusManager.clearFocus()
                onHistoryClick(query)
            },
            onHistoryRemove = onHistoryRemove,
            onHistoryClear = onHistoryClear,
            suggestions = state.suggestions,
            onSuggestionClick = { term ->
                focusManager.clearFocus()
                onSuggestionClick(term)
            },
        )
    }

    if (wide) {
        SidePanelLayout(
            prefs = agentPanel,
            available = agentAvailable,
            title = stringResource(Res.string.search_mode_agent),
            closeDescription = stringResource(Res.string.search_agent_panel_close),
            onOpenChange = onAgentPanelOpenChange,
            onWidthChange = onAgentPanelWidthChange,
            defaultWidth = AgentPanelDefaultWidth,
            minWidth = AgentPanelMinWidth,
            // 侧栏卡片与搜索栏同一条上沿。
            modifier = modifier.fillMaxSize().padding(top = Spacing.Tight),
            main = {
                normalSearch(
                    agentPanel?.takeIf { agentAvailable }?.let { prefs ->
                        {
                            SidePanelToggle(
                                open = prefs.open,
                                onOpenChange = onAgentPanelOpenChange,
                                description = stringResource(Res.string.search_agent_panel_toggle),
                            )
                        }
                    },
                )
            },
            panel = { AgentConversation(state.agent, state.agentInput, agentActions) },
        )
    } else if (state.mode == SearchMode.Agent && agentAvailable) {
        BackHandler { onLeaveAgent() }
        Column(modifier = modifier.fillMaxSize()) {
            AgentTopBar(onBack = onLeaveAgent)
            AgentConversation(state.agent, state.agentInput, agentActions, modifier = Modifier.weight(1f))
        }
    } else {
        Box(modifier = modifier.fillMaxSize()) { normalSearch(null) }
    }
}

/** 侧栏默认宽度:答案是整段正文,比空间页的动态要宽一些才读得顺。 */
private val AgentPanelDefaultWidth = 420.dp

private val AgentPanelMinWidth = 320.dp

/** 宽屏搜索栏的最大宽度。再宽,左端的放大镜和右端的清除键就隔得太远,一眼看不全。 */
private val SearchBarMaxWidth = 720.dp

/** 搜索栏行尾那颗侧栏开关的宽度(M3 图标按钮的触控格)。 */
private val PanelToggleWidth = 48.dp

/** 窄屏助理那一屏的顶栏:返回和栏名。返回回到搜索结果,助理的对话留着。 */
@Composable
private fun AgentTopBar(onBack: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Hair, vertical = Spacing.Tight),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
        }
        Text(
            stringResource(Res.string.search_mode_agent),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = Spacing.Hair),
        )
    }
}

/**
 * 普通搜索:搜索栏,下面是历史或结果。
 *
 * **聚焦时就是 M3 search 的展开态**,建议(这里是历史)的摆法按窗口分:窄屏整块换成历史
 * (full-screen 布局),宽屏从搜索栏下方垂下一张卡,底下的结果盖一层 scrim(docked 布局)。
 * search.md 原话是紧凑窗口用前者,中等以上换后者。
 *
 * 窄屏的搜索栏随列表上滑收起、下滑露出(search.md Scroll 一节的第一种),结果多占一截屏。
 * 宽屏不收:竖向不缺那 64dp,而侧栏开关长在这一行上。
 */
@Composable
private fun NormalSearch(
    state: NormalSearchState,
    input: String,
    onInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    onAskAgent: (() -> Unit)?,
    wide: Boolean,
    panelToggle: (@Composable () -> Unit)?,
    actions: SearchResultActions,
    history: List<String>,
    onHistoryClick: (String) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    suggestions: List<SearchSuggestion>,
    onSuggestionClick: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    // 展开态(搜索栏有焦点)时,返回先收起,不直接离开这一页。
    BackHandler(enabled = focused) { focusManager.clearFocus() }

    // 宽屏的助理是常驻侧栏,不要这个按钮(见 [AskAgentFab])。
    val askAgentFab = onAskAgent.takeIf { !wide }
    val fab = askAgentFab != null

    val collapsing = remember { CollapsingBar() }
    // 打字时和换了一次查询时栏一定在:前者正对着它,后者要看得见查的是什么。
    LaunchedEffect(focused, state.query) { collapsing.offsetPx = 0f }

    val bar: @Composable ((@Composable (Modifier) -> Unit)?) -> Unit = { dropdown ->
        SearchBarRow(
            input = input,
            onInputChange = onInputChange,
            onSearch = onSearch,
            focused = focused,
            onFocusChange = { focused = it },
            onCollapse = { focusManager.clearFocus() },
            // 宽屏不给返回箭头:docked 布局点 scrim 就收起,箭头在桌面上是多余的一格。
            showBack = !wide,
            wide = wide,
            trailing = panelToggle,
            dropdown = dropdown,
        )
    }
    val historyPanel: @Composable (Modifier) -> Unit = { panelModifier ->
        SearchHistoryPanel(history, onHistoryClick, onHistoryRemove, onHistoryClear, panelModifier)
    }
    // 聚焦时盖在结果上的那一块:正在打字而且有补全就是补全,否则是历史。
    val typing = focused && input.isNotBlank() && suggestions.isNotEmpty()
    val focusPanel: (@Composable (Modifier) -> Unit)? = when {
        typing -> { panelModifier -> SuggestionPanel(suggestions, onSuggestionClick, panelModifier) }
        history.isNotEmpty() -> historyPanel
        else -> null
    }
    val scrollingPanel: @Composable (@Composable (Modifier) -> Unit) -> Unit = { panel ->
        if (wide) {
            // 和搜索框同一条中线、同样宽,一眼对得上是这个框的历史与补全。照搜索栏那一行的
            // 结构排([SearchBarRow]):行尾让出侧栏开关那一格,框在剩下那段里居中。按整个主区
            // 居中的话,框和下面的历史各自对着不同的中线,左沿错开一截,历史看着像被收窄了。
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = Spacing.Comfortable, end = if (panelToggle != null) Spacing.Hair else Spacing.Comfortable),
            ) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    panel(Modifier.widthIn(max = SearchBarMaxWidth).fillMaxSize().verticalScroll(rememberScrollState()))
                }
                if (panelToggle != null) Spacer(Modifier.width(PanelToggleWidth))
            }
        } else {
            panel(Modifier.fillMaxSize().verticalScroll(rememberScrollState()))
        }
    }
    val body: @Composable () -> Unit = {
        when {
            // 窄屏展开态:整块换成补全或历史(full-screen 布局)。
            !wide && focused && focusPanel != null -> scrollingPanel(focusPanel)

            // 还没搜过时历史就是正文。
            state.query.isEmpty() && history.isNotEmpty() -> scrollingPanel(historyPanel)

            state.query.isEmpty() -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    message = stringResource(Res.string.search_empty),
                    icon = Icons.Outlined.Search,
                )
            }

            else -> Column(modifier = Modifier.fillMaxSize()) {
                uidCandidate(state.query)?.let { mid ->
                    OpenUidRow(mid = mid, onClick = { actions.onUserClick(mid) })
                }
                AdaptiveListContent(modifier = Modifier.weight(1f)) { columns ->
                    SearchResults(
                        state = state,
                        actions = actions,
                        columns = columns,
                        // 最后一条不被悬浮按钮盖住。
                        bottomPadding = if (fab) AskAgentFabClearance else 0.dp,
                    )
                }
            }
        }
    }
    // 宽屏展开态的卡片。历史已经是正文(还没搜过)时不再垂一份一样的下来。
    val dockedPanel = focusPanel.takeIf { focused && (typing || state.query.isNotEmpty()) }

    if (wide) {
        // 搜索栏叠在最上层:展开时它向下长成一张卡,压在结果与 scrim 之上。
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(Modifier.height(WideBarHeight))
                Box(modifier = Modifier.weight(1f)) { body() }
            }
            if (dockedPanel != null) DockedScrim(onDismiss = { focusManager.clearFocus() })
            bar(dockedPanel)
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().nestedScroll(collapsing.connection)) {
            Box(modifier = collapsing.modifier) { bar(null) }
            Box(modifier = Modifier.weight(1f)) {
                body()
                // 打字时不露:那时键盘占着下半屏,按钮会浮在历史上挡字。
                if (!focused) {
                    askAgentFab?.let {
                        AskAgentFab(
                            onClick = it,
                            expanded = collapsing.offsetPx == 0f,
                            modifier = Modifier.align(Alignment.BottomEnd),
                        )
                    }
                }
            }
        }
    }
}

/** 搜索栏那一行:搜索框,宽屏行尾再挂侧栏开关。 */
@Composable
private fun SearchBarRow(
    input: String,
    onInputChange: (String) -> Unit,
    onSearch: () -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onCollapse: () -> Unit,
    showBack: Boolean,
    wide: Boolean,
    trailing: (@Composable () -> Unit)?,
    /** 宽屏展开态接在框下面的那一截(补全或历史),见 [DockedSurface]。 */
    dropdown: (@Composable (Modifier) -> Unit)? = null,
) {
    val back = showBack && focused
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (back) Spacing.Hair else Spacing.Comfortable, end = if (trailing != null) Spacing.Hair else Spacing.Comfortable)
            // 宽屏的上边距由外层给(见 SearchChatScreen),框与侧栏卡片同一条上沿。
            .padding(top = if (wide) 0.dp else Spacing.Tight, bottom = Spacing.Tight),
    ) {
        if (back) {
            IconButton(onClick = onCollapse) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.action_back))
            }
        }
        // 宽屏框居中、限宽;侧栏开关在行尾,不算进居中的那一段。
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            DockedSurface(
                dropdown = dropdown,
                modifier = if (wide) Modifier.widthIn(max = SearchBarMaxWidth) else Modifier,
            ) {
                SearchField(
                    value = input,
                    onValueChange = onInputChange,
                    // 占位文案同时是这个框的无障碍标签,所以不能空着。
                    placeholder = stringResource(Res.string.search_empty),
                    // 回车即搜。DESIGN 2.2 的快路原话是"输入直接回车 = 原始 B 站搜索"。
                    onSearch = onSearch,
                    onFocusChange = onFocusChange,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * 宽屏展开态:搜索框向下长成一张卡,补全或历史接在框下面(search.md 的 docked 布局)。
 *
 * **卡片一直在,收起时是透明的、没有下半截。** 展开时才包一层的话,框在组合树里换了位置,
 * 会被当成另一个输入框重建,焦点随之丢掉,卡片又立刻收起。
 *
 * 框和列表是同一张卡,不是框下面另挂一张:分开的两块中线各自对齐各自的容器,行尾有侧栏
 * 开关时就错开了。
 */
@Composable
private fun DockedSurface(
    dropdown: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
    field: @Composable () -> Unit,
) {
    val expanded = dropdown != null
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = if (expanded) MaterialTheme.colorScheme.surfaceContainerHigh else Color.Transparent,
        modifier = modifier,
    ) {
        Column {
            field()
            // 框与下面的列表之间不画线:展开时框有焦点,底色是 surfaceContainerHighest,比卡片
            // 高一档,色阶差已经把两块分开。
            if (dropdown != null) {
                dropdown(Modifier.heightIn(max = DockedPanelMaxHeight).verticalScroll(rememberScrollState()))
            }
        }
    }
}

/**
 * 窄屏的「问助理」:拿框里的词起助理,框是空的就只切过去。
 *
 * 宽屏没有它:助理是常驻的侧栏,自己带输入框,和空间页的动态侧栏一样不需要另一个入口。
 * 窄屏一次只放得下一种,才要一个去助理那一屏的按钮。带字的 extended FAB,列表往下读时
 * 随搜索栏一起收成图标:助理是进阶功能,没用过的人认不出那个闪光符号,静止时要读得出来。
 */
@Composable
private fun AskAgentFab(onClick: () -> Unit, expanded: Boolean, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        expanded = expanded,
        icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
        text = { Text(stringResource(Res.string.search_ask_agent)) },
        modifier = modifier.padding(Spacing.Comfortable),
    )
}

/** 列表底部为悬浮按钮留出的高度:56dp 的按钮加上下各 16dp 的边距。 */
private val AskAgentFabClearance = 88.dp

/**
 * 补全词,一行一条。点一条直接搜它(同点历史)。命中输入的那几段标成 primary,读得出补的是
 * 哪一截。
 *
 * **不是热搜。** 这里列的每一条都从用户正在敲的字延伸出来,换一个字就换一批;热搜是不管你
 * 敲了什么都推给你的那一份,不做(DESIGN 2.2)。
 */
@Composable
private fun SuggestionPanel(
    suggestions: List<SearchSuggestion>,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(Res.string.search_suggestions)
    val highlight = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .padding(vertical = Spacing.Tight)
            // 列表一换,读屏要念出来(search.md Accessibility 的 Autosuggest 一节)。
            .semantics { paneTitle = title; liveRegion = LiveRegionMode.Polite },
    ) {
        suggestions.forEach { suggestion ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SuggestionRowHeight)
                    .clickable(role = Role.Button) { onClick(suggestion.term) }
                    .padding(horizontal = Spacing.Comfortable),
            ) {
                Icon(
                    Icons.Outlined.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = buildAnnotatedString {
                        append(suggestion.text)
                        suggestion.highlights.forEach { range ->
                            if (range.last < suggestion.text.length) {
                                addStyle(SpanStyle(color = highlight), range.first, range.last + 1)
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** M3 单行 list item 的高度。 */
private val SuggestionRowHeight = 56.dp

/**
 * 查询是一串纯数字时,结果顶上的一行:把它当 UID 打开那个人的空间。
 *
 * 不直接跳:一串数字也可能就是要搜的东西(番号、年份、型号),替人猜会把这些搜索都劫走。
 */
@Composable
private fun OpenUidRow(mid: Long, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight)
            .fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            modifier = Modifier.heightIn(min = SuggestionRowHeight).padding(horizontal = Spacing.Comfortable),
        ) {
            Icon(Icons.Outlined.AccountCircle, contentDescription = null)
            Text(
                stringResource(Res.string.search_open_uid, mid.toString()),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
        }
    }
}

/** 宽屏展开态盖在结果上的 scrim,点它收起。卡片画在它上面一层,点卡片不会落到这里。 */
@Composable
private fun DockedScrim(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = ScrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
    )
}

/** 宽屏搜索栏那一行的高度:框 48dp 加下边距。结果区从这条线往下排,展开的卡片压在结果上。 */
private val WideBarHeight = 48.dp + Spacing.Tight

private const val ScrimAlpha = 0.32f

private val DockedPanelMaxHeight = 400.dp

/**
 * 随列表收起的搜索栏。列表往下读时栏先让出位置再滚列表,往回拉时栏先回来(M3 的
 * enter-always)。**位移由栏自己吃掉**,不交给列表:栏收的同时列表区跟着变高,列表再滚
 * 同样的距离,内容就以两倍速度往上跑。
 */
private class CollapsingBar {
    /** 栏的完整高度,测量时写入。只在滚动回调里读,不必是快照状态。 */
    private var heightPx = 0f
    var offsetPx by mutableFloatStateOf(0f)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val before = offsetPx
            offsetPx = (offsetPx + available.y).coerceIn(-heightPx, 0f)
            return Offset(0f, offsetPx - before)
        }
    }

    val modifier = Modifier
        .clipToBounds()
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            heightPx = placeable.height.toFloat()
            val offset = offsetPx.roundToInt()
            layout(placeable.width, (placeable.height + offset).coerceAtLeast(0)) {
                placeable.place(0, offset)
            }
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
    modifier: Modifier = Modifier,
) {
    var confirmingClear by remember { mutableStateOf(false) }
    Column(modifier = modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.search_history_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { confirmingClear = true }) {
                Text(stringResource(Res.string.action_clear))
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier.padding(bottom = Spacing.Tight),
        ) {
            history.forEach { query ->
                HistoryChip(query = query, onClick = { onClick(query) }, onRemove = { onRemove(query) })
            }
        }
    }

    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(Res.string.search_history_clear_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    onClear()
                }) { Text(stringResource(Res.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClear = false }) {
                    Text(stringResource(Res.string.follow_unfollow_keep))
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
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier
                // 视觉 32dp,触控撑到 48dp(风格指南 §3)。
                .minimumInteractiveComponentSize()
                .clip(MaterialTheme.shapes.small)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(Res.string.search_history_remove),
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
                text = { Text(stringResource(Res.string.search_history_remove)) },
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

/** 助理:上面是对话,下面是追问框。窄屏的助理一屏和宽屏的侧栏是同一块。 */
@Composable
private fun AgentConversation(
    state: AgentSearchState,
    input: String,
    actions: AgentActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            AgentPane(state = state, onVideoClick = actions.onVideoClick, onRetry = actions.onRetry)
        }
        InputBar(
            input = input,
            onInputChange = actions.onInputChange,
            onSend = actions.onSend,
            // 开新会话是清空助理上下文的唯一入口(DESIGN 3.1:会话必须由用户显式开启)。
            onNewSession = actions.onNewSession,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 助理的对话,跟随最新一轮。 */
@Composable
private fun AgentPane(
    state: AgentSearchState,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    if (state.turns.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                message = stringResource(Res.string.search_agent_empty),
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
        AgentQuestionBubble(
            text = turn.query,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
        )
        AgentTurnView(turn = turn.result, onVideoClick = onVideoClick, onRetry = onRetry)
    }
}

// ---- 输入框 ----

/**
 * 助理的追问框,固定在底部。和私信的输入栏同一个胶囊([PillInputField]):发送键装在框里、
 * 有字才出现,新会话在框的左端。
 *
 * 上一版是一条 surfaceContainer 底条,里面左右各一颗按钮、中间一个搜索框:底条一层、框一层,
 * 底部横着两层容器,灰掉的发送键还常驻在右边;而且它长得像搜索框,读不出这是在和谁说话。
 * 这是一段对话,输入栏就该和私信一个样子。
 */
@Composable
private fun InputBar(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onNewSession: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PillInputField(
        value = input,
        onValueChange = onInputChange,
        // 占位文案同时是这个框的无障碍标签,所以不能空着。它确实和空态那句重复,但只在还没问过的
        // 那一屏重复:发完就清空输入框,那之后整屏没有任何东西说明这里该填什么。
        placeholder = stringResource(Res.string.search_agent_empty),
        canSend = input.isNotBlank(),
        // 发出去就是新的一轮,上一轮还在跑会被取消(见 SearchChatViewModel.runAgent),不必锁住框。
        sending = false,
        onSend = onSend,
        // 新会话:把助理上下文清掉的**动作**。普通搜索没有会话,这个按钮只长在这里。
        leading = {
            IconButton(onClick = onNewSession) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.search_new_session))
            }
        },
        modifier = modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
    )
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
                input = "宝可梦",
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
            onInputChange = {}, onSearch = {}, onAskAgent = {}, onLeaveAgent = {},
            agentActions = PreviewAgentActions, resultActions = PreviewResultActions,
            agentAvailable = true,
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
                                        id = 0,
                                        kind = StepKind.SearchVideos,
                                        text = "搞笑动画",
                                        items = listOf(previewTrace("BV1aa", "笑到打鸣的搞笑动画合集")),
                                        finished = true,
                                    ),
                                ),
                                answer = AgentAnswer(
                                    text = "时长短、**弹幕密度高**,评论区反馈「摸鱼时长刚好一集」${citation(1)}。",
                                    sources = listOf(AnswerSource("BV1aa", previewTrace("BV1aa", "笑到打鸣的搞笑动画合集"))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            onInputChange = {}, onSearch = {}, onAskAgent = {}, onLeaveAgent = {},
            agentActions = PreviewAgentActions, resultActions = PreviewResultActions,
            agentAvailable = true,
        )
    }
}

private val PreviewAgentActions = AgentActions(
    onInputChange = {}, onSend = {}, onNewSession = {}, onRetry = {}, onVideoClick = {},
)

private val PreviewResultActions = SearchResultActions(
    onTabSelected = {}, onOrderChange = {}, onDurationChange = {}, onPubTimeChange = {}, onZoneChange = {},
    onArticleOrderChange = {}, onUserOrderChange = {},
    onVideoClick = {}, onUserClick = {}, onArticleClick = {}, onLoadMore = {}, onRetry = {},
)
