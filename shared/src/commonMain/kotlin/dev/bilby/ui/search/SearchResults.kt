package dev.bilby.ui.search

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabIndicatorScope
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp as dpLerp
import dev.bilby.data.SearchArticle
import dev.bilby.data.SearchUser
import dev.bilby.data.SearchVideo
import dev.bilby.parseDurationSeconds
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.CoverAspectRatio
import dev.bilby.ui.components.LocalContentBlockMatcher
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PagedLayout
import dev.bilby.ui.components.PersonRowSkeleton
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.SkeletonLine
import dev.bilby.ui.components.VideoCommonMenuItems
import dev.bilby.ui.components.VideoGridCard
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowSkeleton
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.menuSelectedMark
import dev.bilby.ui.components.rememberCopyAction
import dev.bilby.ui.components.selectedSemantics
import dev.bilby.ui.components.skeleton
import dev.bilby.ui.components.touchOnlyPaging
import dev.bilby.ui.components.verticalWheelScrollsRow
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.rememberVideoGridColumnCount
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.tabular
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/** 结果区的全部动作。搜索 tab 和标签结果页各自接到自己的 ViewModel 上。 */
@Stable
class SearchResultActions(
    val onTabSelected: (SearchTab) -> Unit,
    val onOrderChange: (SearchOrder) -> Unit,
    val onDurationChange: (SearchDuration) -> Unit,
    val onPubTimeChange: (SearchPubTime) -> Unit,
    val onZoneChange: (SearchZone) -> Unit,
    val onArticleOrderChange: (SearchOrder) -> Unit,
    val onUserOrderChange: (SearchUserOrder) -> Unit,
    val onVideoClick: (bvid: String) -> Unit,
    val onUserClick: (mid: Long) -> Unit,
    /** cv 号。 */
    val onArticleClick: (id: Long) -> Unit,
    val onLoadMore: () -> Unit,
    val onRetry: () -> Unit,
)

/** 视频的六档,顺序照 B 站搜索页本身(notes 2.4 的 ArchiveFilterType)。 */
private val VideoOrders = listOf(
    SearchOrder.Comprehensive,
    SearchOrder.Play,
    SearchOrder.NewPublished,
    SearchOrder.Danmaku,
    SearchOrder.Favorite,
    SearchOrder.Comments,
).map { it to it.labelRes }

/** 专栏的五档(notes 2.4 的 ArticleOrderType)。「click」在这里是阅读数。 */
private val ArticleOrders = listOf(
    SearchOrder.Comprehensive to Res.string.search_order_comprehensive,
    SearchOrder.Play to Res.string.search_order_read,
    SearchOrder.NewPublished to Res.string.search_order_pubdate,
    SearchOrder.Likes to Res.string.search_order_likes,
    SearchOrder.Comments to Res.string.search_order_comments,
)

private val Durations = SearchDuration.entries.map { it to it.labelRes }
private val PubTimes = SearchPubTime.entries.map { it to it.labelRes }
private val Zones = SearchZone.entries.map { it to it.labelRes }
private val UserOrders = SearchUserOrder.entries.map { it to it.labelRes }

/**
 * 一个关键词的结果:视频 / 用户 / 专栏三栏,各自翻页。搜索 tab 的普通模式和标签结果页共用 ——
 * 两处是同一份结果的两个入口,长两样的话同一个词在两处给人两种页面。
 *
 * **筛选行钉在列表外面**,不进列表表头:表头在首屏加载时整个让给转圈,换一次排序筛选控件
 * 就消失一下再回来,手指刚点过的东西闪没了。它长在每一页里,跟着那一页一起划走。
 *
 * **三栏可以左右划**,和空间页、播放页的简介/评论一样由 pager 承载;标签行和 pager 双向同步。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SearchResults(
    state: NormalSearchState,
    actions: SearchResultActions,
    modifier: Modifier = Modifier,
    columns: GridCells = GridCells.Fixed(1),
    /** 列表底部额外留出的高度,给浮在结果上的按钮。 */
    bottomPadding: Dp = 0.dp,
) {
    val tabs = SearchTab.entries
    val pagerState = rememberPagerState(initialPage = state.tab.ordinal) { tabs.size }
    val scope = rememberCoroutineScope()

    val currentTab by rememberUpdatedState(state.tab)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            val tab = tabs[page]
            if (tab != currentTab) actions.onTabSelected(tab)
        }
    }
    LaunchedEffect(state.tab) {
        if (pagerState.currentPage != state.tab.ordinal) pagerState.animateScrollToPage(state.tab.ordinal)
    }

    val selectTab: (Int) -> Unit = { index -> scope.launch { pagerState.animateScrollToPage(index) } }
    val currentPageTab = tabs[pagerState.currentPage]
    val density = LocalDensity.current
    val wide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    var containerWidth by remember { mutableStateOf(0.dp) }
    val resolvedWidth = if (containerWidth > 0.dp) {
        containerWidth
    } else if (wide) {
        Breakpoints.Expanded
    } else {
        Breakpoints.Medium - 1.dp
    }
    val joined = resolvedWidth >= JoinedHeaderMinWidth

    val tabItems: @Composable () -> Unit = {
        tabs.forEachIndexed { index, tab ->
            val selected = pagerState.currentPage == index
            Tab(
                selected = selected,
                onClick = { selectTab(index) },
                text = {
                    Text(
                        text = stringResource(tab.labelRes),
                        style = if (selected) {
                            MaterialTheme.typography.titleSmallEmphasized
                        } else {
                            MaterialTheme.typography.titleSmall
                        },
                    )
                },
            )
        }
    }
    val tabIndicator: @Composable TabIndicatorScope.() -> Unit = {
        SearchPagerTabIndicator(pagerState = pagerState)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerWidth = with(density) { it.width.toDp() } },
    ) {
        if (joined) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = pagerState.currentPage,
                    edgePadding = 0.dp,
                    indicator = tabIndicator,
                    divider = {},
                    tabs = tabItems,
                    modifier = Modifier.weight(1f),
                )
                FilterChips(
                    tab = currentPageTab,
                    state = state,
                    actions = actions,
                    modifier = Modifier.padding(end = Spacing.Comfortable),
                )
            }
        } else {
            // 不要默认那条通栏分割线,理由同空间页的标签栏。
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                indicator = tabIndicator,
                divider = {},
                tabs = tabItems,
            )
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).touchOnlyPaging()) { page ->
            ResultPage(
                tab = tabs[page],
                state = state,
                actions = actions,
                layout = PagedLayout.Grid(columns),
                showFilters = !joined,
                bottomPadding = bottomPadding,
            )
        }
    }
}

@Composable
private fun TabIndicatorScope.SearchPagerTabIndicator(pagerState: PagerState) {
    TabRowDefaults.PrimaryIndicator(
        width = Dp.Unspecified,
        modifier = Modifier.tabIndicatorLayout { measurable, constraints, tabPositions ->
            if (tabPositions.isEmpty()) {
                layout(0, 0) {}
            } else {
                val selectedIndex = pagerState.currentPage.coerceIn(0, tabPositions.lastIndex)
                val pos = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                    .coerceIn(0f, tabPositions.lastIndex.toFloat())
                val low = pos.toInt().coerceIn(0, tabPositions.lastIndex)
                val high = (low + 1).coerceAtMost(tabPositions.lastIndex)
                val frac = pos - low
                val lowWidth = tabPositions[low].contentWidth.coerceAtLeast(SearchTabIndicatorMinWidth)
                val highWidth = tabPositions[high].contentWidth.coerceAtLeast(SearchTabIndicatorMinWidth)
                val lowX = tabPositions[low].left + (tabPositions[low].width - lowWidth) / 2
                val highX = tabPositions[high].left + (tabPositions[high].width - highWidth) / 2
                val indicatorWidth = dpLerp(lowWidth, highWidth, frac).roundToPx()
                val indicatorX = dpLerp(lowX, highX, frac).roundToPx()
                val placeable = measurable.measure(
                    constraints.copy(minWidth = indicatorWidth, maxWidth = indicatorWidth),
                )
                val parentX = ((tabPositions[selectedIndex].width.roundToPx() - indicatorWidth) / 2).coerceAtLeast(0)
                layout(placeable.width, placeable.height) {
                    placeable.placeRelative(indicatorX - parentX, 0)
                }
            }
        },
    )
}

/** 栏名与筛选并成一行所需的宽度:三个标签的最小宽加上视频栏的四枚筛选按钮。 */
private val JoinedHeaderMinWidth = 760.dp

private val FilterChipVisualMinHeight = 36.dp

/**
 * 一栏的筛选,每一项是一枚轻量化透明文字下拉按钮,参照评论区排序右对齐;
 * 窄屏多筛选项超出宽度时保持横向滚动且首项不被裁切。
 */
@Composable
private fun FilterChips(
    tab: SearchTab,
    state: NormalSearchState,
    actions: SearchResultActions,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
    fillWidth: Boolean = false,
) {
    val scroll = rememberScrollState()
    val chips: @Composable () -> Unit = {
        when (tab) {
            SearchTab.Video -> {
                MenuChip(VideoOrders, state.order, SearchOrder.Comprehensive, actions.onOrderChange)
                MenuChip(Durations, state.duration, SearchDuration.All, actions.onDurationChange)
                MenuChip(PubTimes, state.pubTime, SearchPubTime.All, actions.onPubTimeChange)
                MenuChip(Zones, state.zone, SearchZone.All, actions.onZoneChange)
            }

            SearchTab.Article ->
                MenuChip(ArticleOrders, state.articleOrder, SearchOrder.Comprehensive, actions.onArticleOrderChange)

            SearchTab.User ->
                MenuChip(UserOrders, state.userOrder, SearchUserOrder.Default, actions.onUserOrderChange)
        }
    }
    if (fillWidth) {
        BoxWithConstraints(modifier = modifier) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair, Alignment.End),
                modifier = Modifier
                    .verticalWheelScrollsRow(scroll)
                    .horizontalScroll(scroll)
                    .widthIn(min = maxWidth)
                    .padding(horizontal = contentPadding),
            ) {
                chips()
            }
        }
    } else {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Hair, Alignment.End),
            modifier = modifier
                .verticalWheelScrollsRow(scroll)
                .horizontalScroll(scroll)
                .padding(horizontal = contentPadding),
        ) {
            chips()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> MenuChip(
    options: List<Pair<T, StringResource>>,
    selected: T,
    default: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: return
    val active = selected != default
    val contentColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val motionScheme = if (LocalPointerSource.current.isTouchLike) {
        MaterialTheme.motionScheme
    } else {
        SearchDesktopMenuMotionScheme
    }
    Box {
        TextButton(
            onClick = { open = true },
            colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
            contentPadding = PaddingValues(start = Spacing.Cozy, end = Spacing.Tight),
            modifier = Modifier
                .defaultMinSize(minWidth = 1.dp, minHeight = FilterChipVisualMinHeight)
                .heightIn(min = FilterChipVisualMinHeight),
        ) {
            Text(
                text = stringResource(selectedLabel),
                style = if (active) {
                    MaterialTheme.typography.labelLargeEmphasized
                } else {
                    MaterialTheme.typography.labelLarge
                },
                maxLines = 1,
                softWrap = false,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
        }
        MaterialExpressiveTheme(motionScheme = motionScheme) {
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                shape = MenuDefaults.shape,
                containerColor = MenuDefaults.containerColor,
            ) {
                options.forEachIndexed { index, (value, label) ->
                    val isSelected = value == selected
                    DropdownMenuItem(
                        onClick = {
                            open = false
                            onSelect(value)
                        },
                        text = { Text(stringResource(label)) },
                        shape = menuItemShape(index, options.size),
                        trailingContent = if (isSelected) menuSelectedMark else null,
                        modifier = Modifier.selectedSemantics(isSelected),
                    )
                }
            }
        }
    }
}

/** 一栏:可选的筛选行,下面是可下拉刷新的列表。 */
@Composable
private fun ResultPage(
    tab: SearchTab,
    state: NormalSearchState,
    actions: SearchResultActions,
    layout: PagedLayout,
    showFilters: Boolean,
    bottomPadding: Dp,
) {
    val listPadding = PaddingValues(bottom = bottomPadding + navigationBarsBottom())
    Column(modifier = Modifier.fillMaxSize()) {
        if (showFilters) {
            // 边距放在滚动的内容里,不放在外面:放外面的话横滑时按钮在 16dp 处被截断。
            FilterChips(
                tab = tab,
                state = state,
                actions = actions,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = Spacing.Comfortable,
                fillWidth = true,
            )
        }

        val list = when (tab) {
            SearchTab.Video -> state.videos
            SearchTab.User -> state.users
            SearchTab.Article -> state.articles
        }
        RefreshBox(
            refreshing = list.loading && list.items.isNotEmpty(),
            onRefresh = actions.onRetry,
            modifier = Modifier.weight(1f),
        ) {
            when (tab) {
                SearchTab.Video -> VideoResults(
                    state = state,
                    actions = actions,
                    contentPadding = listPadding,
                    bottomPadding = bottomPadding,
                )

                SearchTab.User -> {
                    val blockMatcher = LocalContentBlockMatcher.current
                    val visibleUsers = remember(state.users.items, blockMatcher) {
                        if (blockMatcher.isEmpty) {
                            state.users.items
                        } else {
                            state.users.items.filterNot { user ->
                                blockMatcher.matchesUser(mid = user.mid, name = user.name)
                            }
                        }
                    }
                    PagedColumn(
                        layout = layout,
                        items = visibleUsers,
                        key = { it.mid },
                        skeletonRow = { PersonRowSkeleton() },
                        loading = state.users.loading && state.users.items.isEmpty(),
                        appending = state.users.appending,
                        hasMore = state.users.hasMore,
                        error = state.users.error.takeIf { state.users.items.isEmpty() },
                        emptyText = stringResource(Res.string.search_no_results),
                        onLoadMore = actions.onLoadMore,
                        onRetry = actions.onRetry,
                        contentPadding = listPadding,
                    ) { user ->
                        UserResultRow(user, onClick = { actions.onUserClick(user.mid) })
                    }
                }

                SearchTab.Article -> PagedColumn(
                    layout = layout,
                    items = state.articles.items,
                    key = { it.id },
                    loading = state.articles.loading && state.articles.items.isEmpty(),
                    appending = state.articles.appending,
                    hasMore = state.articles.hasMore,
                    error = state.articles.error.takeIf { state.articles.items.isEmpty() },
                    emptyText = stringResource(Res.string.search_no_results),
                    onLoadMore = actions.onLoadMore,
                    onRetry = actions.onRetry,
                    contentPadding = listPadding,
                ) { article ->
                    ArticleResultRow(
                        article = article,
                        onClick = { actions.onArticleClick(article.id) },
                    )
                }
            }
        }
    }
}

private val GridCellOuterPadding = 2.dp

/**
 * 视频栏:
 * - `Medium+` / `Expanded+` 多列空间下以纵向 16:10 [VideoGridCard] 排布(含右键/长按菜单与点击 UP 名进空间);
 * - 单列 `Compact` 或侧栏压缩后的窄主区下采用拇指友好的 [VideoRow](窄屏附行尾溢出菜单)。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VideoResults(
    state: NormalSearchState,
    actions: SearchResultActions,
    contentPadding: PaddingValues,
    bottomPadding: Dp,
) {
    val videos = state.videos
    val blockMatcher = LocalContentBlockMatcher.current
    val visibleVideos = remember(videos.items, blockMatcher) {
        if (blockMatcher.isEmpty) {
            videos.items
        } else {
            videos.items.filterNot { video ->
                blockMatcher.matchesVideo(
                    upMid = video.upMid,
                    upName = video.upName,
                    title = video.title,
                )
            }
        }
    }
    val wideWindow = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    val density = LocalDensity.current
    var containerWidth by remember { mutableStateOf(0.dp) }
    val resolvedWidth = if (containerWidth > 0.dp) {
        containerWidth
    } else if (wideWindow) {
        Breakpoints.Expanded
    } else {
        Breakpoints.Medium - 1.dp
    }
    val gridCols = rememberVideoGridColumnCount(resolvedWidth)
    val useGrid = gridCols > 1
    val effectiveLayout = if (useGrid) {
        PagedLayout.Grid(GridCells.Fixed(gridCols))
    } else {
        PagedLayout.SingleColumn
    }
    val effectivePadding = if (useGrid) {
        PaddingValues(
            start = Spacing.Tight - GridCellOuterPadding,
            end = Spacing.Tight - GridCellOuterPadding,
            top = Spacing.Hair,
            bottom = bottomPadding + Spacing.Comfortable + navigationBarsBottom(),
        )
    } else {
        contentPadding
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerWidth = with(density) { it.width.toDp() } },
    ) {
        PagedColumn(
            layout = effectiveLayout,
            items = visibleVideos,
            key = { it.bvid },
            loading = videos.loading && videos.items.isEmpty(),
            appending = videos.appending,
            hasMore = videos.hasMore,
            error = videos.error.takeIf { videos.items.isEmpty() },
            emptyText = stringResource(Res.string.search_no_results),
            onLoadMore = actions.onLoadMore,
            onRetry = actions.onRetry,
            contentPadding = effectivePadding,
            skeletonRow = if (useGrid) {
                {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.Tight - GridCellOuterPadding),
                    ) {
                        repeat(gridCols) {
                            VideoGridCardSkeleton(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(GridCellOuterPadding),
                            )
                        }
                    }
                }
            } else {
                { VideoRowSkeleton() }
            },
        ) { video ->
            val dateText = formatDate(video.publishedAtEpochSeconds)
            val playText = formatCount(video.playCount)
            val danmakuText = formatCount(video.danmakuCount)
            val rowUi = remember(video, dateText, playText, danmakuText) {
                video.toRowUi(dateText = dateText, playText = playText, danmakuText = danmakuText)
            }
            val menu: @Composable ColumnScope.(close: () -> Unit) -> Unit = { close ->
                VideoCommonMenuItems(
                    bvid = video.bvid,
                    title = video.title,
                    close = close,
                    upMid = video.upMid,
                    onOpenUpSpace = actions.onUserClick,
                    coverUrl = video.coverUrl,
                    upName = video.upName,
                    durationSeconds = parseDurationSeconds(video.durationText),
                )
            }
            ContextMenuBox(
                onClick = null,
                menuLabel = stringResource(Res.string.menu_item_actions),
                modifier = if (useGrid) Modifier.padding(GridCellOuterPadding) else Modifier,
                menu = menu,
            ) { openMenu ->
                if (useGrid) {
                    VideoGridCard(
                        ui = rowUi,
                        onClick = { actions.onVideoClick(video.bvid) },
                        onLongClick = openMenu,
                        onUpClick = video.upMid.takeIf { it != 0L }?.let { mid -> { actions.onUserClick(mid) } },
                    )
                } else if (wideWindow) {
                    VideoRow(
                        item = rowUi,
                        onClick = { actions.onVideoClick(video.bvid) },
                        onLongClick = openMenu,
                    )
                } else {
                    var menuOpen by remember { mutableStateOf(false) }
                    VideoRow(
                        item = rowUi,
                        onClick = { actions.onVideoClick(video.bvid) },
                        onLongClick = openMenu,
                        overflow = {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        imageVector = Icons.Outlined.MoreVert,
                                        contentDescription = stringResource(Res.string.menu_item_actions),
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false },
                                    shape = MenuDefaults.shape,
                                    containerColor = MenuDefaults.containerColor,
                                ) {
                                    menu { menuOpen = false }
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoGridCardSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CoverAspectRatio)
                .skeleton(RoundedCornerShape(Dimens.CoverCornerGrid)),
        )
        SkeletonLine(Modifier.fillMaxWidth())
        SkeletonLine(Modifier.fillMaxWidth(0.65f))
        SkeletonLine(Modifier.fillMaxWidth(0.45f), height = 10.dp)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArticleResultRow(
    article: SearchArticle,
    onClick: () -> Unit,
) {
    val copy = rememberCopyAction()
    val copyTitleLabel = stringResource(Res.string.action_copy_title)
    val copyArticleIdLabel = stringResource(Res.string.action_copy_article_id)

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        menu = { close ->
            DropdownMenuItem(
                onClick = {
                    close()
                    copy(article.title)
                },
                text = { Text(copyTitleLabel) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                shape = menuItemShape(0, 2),
            )
            DropdownMenuItem(
                onClick = {
                    close()
                    copy("cv${article.id}")
                },
                text = { Text(copyArticleIdLabel) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                shape = menuItemShape(1, 2),
            )
        },
    ) { openMenu ->
        val articleBadge = stringResource(Res.string.feed_article_badge)
        val viewsMeta = stringResource(Res.string.search_article_views, formatCount(article.viewCount))
        val rowUi = remember(article, articleBadge, viewsMeta) {
            article.toRowUi(articleBadge = articleBadge, viewsMeta = viewsMeta)
        }
        VideoRow(
            item = rowUi,
            onClick = onClick,
            onLongClick = openMenu,
        )
    }
}

/** 「用户」栏的一行:头像、名字、粉丝与投稿数,签名一行。 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UserResultRow(user: SearchUser, onClick: () -> Unit) {
    val copy = rememberCopyAction()
    val copyNicknameLabel = stringResource(Res.string.action_copy_nickname)
    val copyUidLabel = stringResource(Res.string.action_copy_uid)

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        menu = { close ->
            DropdownMenuItem(
                onClick = {
                    close()
                    copy(user.name)
                },
                text = { Text(copyNicknameLabel) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                shape = menuItemShape(0, 2),
            )
            DropdownMenuItem(
                onClick = {
                    close()
                    copy(user.mid.toString())
                },
                text = { Text(copyUidLabel) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                shape = menuItemShape(1, 2),
            )
        },
    ) { openMenu ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Tight, vertical = 2.dp)
                .interactiveCard(
                    shape = MaterialTheme.shapes.large,
                    baseColor = Color.Transparent,
                    hoverColor = MaterialTheme.colorScheme.surfaceContainerLow,
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                )
                .padding(horizontal = Spacing.Tight, vertical = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        ) {
            Avatar(url = user.avatarUrl, size = Dimens.AvatarStack)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2)) {
                Text(
                    text = user.name,
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        Res.string.search_user_meta,
                        formatCount(user.fansCount),
                        formatCount(user.videoCount),
                    ),
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 没填签名就整行不画,同空间页头部的判据。
                if (user.signature.isNotBlank()) {
                    Text(
                        text = user.signature,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun SearchVideo.toRowUi(
    dateText: String?,
    playText: String?,
    danmakuText: String?,
) = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = durationText,
    upName = upName,
    // 搜索结果里发布时间是判据之一(教程类尤其看新旧),和动态、空间保持同一行形态。
    dateText = dateText,
    playText = playText,
    danmakuText = danmakuText,
    titleHighlights = titleHighlights,
)

/**
 * 专栏与视频同一种行([VideoRow]),封面角标写「文章」,和首页那条专栏的写法一致;
 * 接口不给作者名,UP 名那一格换成分区。摘要放在最后一行。
 */
private fun SearchArticle.toRowUi(
    articleBadge: String,
    viewsMeta: String,
) = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = articleBadge,
    upName = categoryName.takeIf { it.isNotBlank() },
    dateText = formatDate(publishedAtEpochSeconds),
    meta = viewsMeta,
    note = summary.takeIf { it.isNotBlank() },
    titleHighlights = titleHighlights,
)

private val SearchTabIndicatorMinWidth = 24.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object SearchDesktopMenuMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
}

private val DateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatDate(epochSeconds: Long): String =
    if (epochSeconds <= 0) {
        ""
    } else {
        Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.systemDefault())
            .format(DateFormatter)
    }
