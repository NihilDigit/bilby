package dev.bilby.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import dev.bilby.ui.components.verticalWheelScrollsRow
import dev.bilby.ui.components.menuSelectedMark
import dev.bilby.ui.components.selectedSemantics
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.resources.*
import dev.bilby.data.SearchArticle
import dev.bilby.data.SearchUser
import dev.bilby.data.SearchVideo
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.touchOnlyPaging
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PagedLayout
import androidx.compose.foundation.lazy.grid.GridCells
import dev.bilby.ui.components.PersonRowSkeleton
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/** 结果区的全部动作。搜索 tab 和标签结果页各自接到自己的 ViewModel 上。 */
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

    // 两个方向各一条:划 pager 回写当前栏(顺带触发那一栏的首次加载),当前栏变了滚 pager。
    // **比较用 rememberUpdatedState 读当前值**:这个效应只在 pagerState 变化时重启,直接读
    // `state.tab` 读到的永远是启动那一刻的那一份,划回原来那一栏时会判成"没变"不写回
    // (空间页踩过同一个坑)。
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 够宽时栏名和筛选并成一行:栏名按字宽靠左,筛选在右端,和视频页宽屏的简介/评论同一种
        // 标签栏。窄了就是通栏标签,筛选下到每一页顶上。按这一块的实际宽度判,不按窗口:宽屏
        // 上助理侧栏打开时主区可以只剩四百多 dp。
        val joined = maxWidth >= JoinedHeaderMinWidth
        Column(modifier = Modifier.fillMaxSize()) {
            if (joined) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = pagerState.currentPage,
                        edgePadding = 0.dp,
                        divider = {},
                        modifier = Modifier.weight(1f),
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            Tab(
                                selected = pagerState.currentPage == index,
                                onClick = { selectTab(index) },
                                text = { Text(stringResource(tab.labelRes)) },
                            )
                        }
                    }
                    FilterChips(
                        tab = currentPageTab,
                        state = state,
                        actions = actions,
                        modifier = Modifier.padding(end = Spacing.Comfortable),
                    )
                }
            } else {
                // 不要默认那条通栏分割线,理由同空间页的标签栏。
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage, divider = {}) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { selectTab(index) },
                            text = { Text(stringResource(tab.labelRes)) },
                        )
                    }
                }
            }
            HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).touchOnlyPaging()) { page ->
                ResultPage(tabs[page], state, actions, PagedLayout.Grid(columns), showFilters = !joined, bottomPadding)
            }
        }
    }
}

/** 栏名与筛选并成一行所需的宽度:三个标签的最小宽加上视频栏的四枚筛选 chip。 */
private val JoinedHeaderMinWidth = 760.dp

/**
 * 一栏的筛选,每一项是一枚带下拉的 filter chip,chip 上写着当前选的那一档。
 *
 * **不再是右端两个文字下拉。** 那两个只有字和一个小三角,读起来像标签不像控件;chip 有容器,
 * 一看就能点,而且偏离默认值时换成选中态,扫一眼就知道这份结果被筛过。排序不铺成一排
 * 单选 chip:六档加上另外三枚,手机上一屏看不全。
 *
 * 视频栏四枚在窄屏排不下一行,整行横滑;鼠标滚轮也能横滚,见 [verticalWheelScrollsRow]。
 */
@Composable
private fun FilterChips(
    tab: SearchTab,
    state: NormalSearchState,
    actions: SearchResultActions,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
) {
    val scroll = rememberScrollState()
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = modifier
            .verticalWheelScrollsRow(scroll)
            .horizontalScroll(scroll)
            .padding(horizontal = contentPadding),
    ) {
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
}

@Composable
private fun <T> MenuChip(
    options: List<Pair<T, StringResource>>,
    selected: T,
    default: T,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: return
    Box {
        FilterChip(
            selected = selected != default,
            onClick = { open = true },
            label = { Text(stringResource(selectedLabel)) },
            trailingIcon = {
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = MenuDefaults.shape,
            containerColor = MenuDefaults.containerColor,
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        onSelect(value)
                    },
                    trailingIcon = if (isSelected) menuSelectedMark else null,
                    modifier = Modifier.selectedSemantics(isSelected),
                )
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
            // 边距放在滚动的内容里,不放在外面:放外面的话横滑时 chip 在 16dp 处被截断。
            FilterChips(
                tab = tab,
                state = state,
                actions = actions,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = Spacing.Comfortable,
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
                SearchTab.Video -> VideoResults(state, actions, layout, listPadding)
                SearchTab.User -> PagedColumn(
                    layout = layout,
                    items = state.users.items,
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
                ) { user -> UserResultRow(user, onClick = { actions.onUserClick(user.mid) }) }

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
                    VideoRow(item = article.toRowUi(), onClick = { actions.onArticleClick(article.id) })
                }
            }
        }
    }
}

/**
 * 视频栏。**顶上不再摆一排搜到的 UP 主**:找人有「用户」那一栏,一划就到;那一排挤在视频
 * 列表头上,每次搜什么都先看到几张不相干的脸。
 */
@Composable
private fun VideoResults(
    state: NormalSearchState,
    actions: SearchResultActions,
    layout: PagedLayout,
    contentPadding: PaddingValues,
) {
    val videos = state.videos
    PagedColumn(
        layout = layout,
        items = videos.items,
        key = { it.bvid },
        loading = videos.loading && videos.items.isEmpty(),
        appending = videos.appending,
        hasMore = videos.hasMore,
        error = videos.error.takeIf { videos.items.isEmpty() },
        emptyText = stringResource(Res.string.search_no_results),
        onLoadMore = actions.onLoadMore,
        onRetry = actions.onRetry,
        contentPadding = contentPadding,
    ) { video -> VideoRow(item = video.toRowUi(), onClick = { actions.onVideoClick(video.bvid) }) }
}

/** 「用户」栏的一行:头像、名字、粉丝与投稿数,签名一行。 */
@Composable
private fun UserResultRow(user: SearchUser, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        Avatar(url = user.avatarUrl, size = Dimens.AvatarStack)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2)) {
            Text(user.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.search_user_meta, formatCount(user.fansCount), formatCount(user.videoCount)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 没填签名就整行不画,同空间页头部的判据。
            if (user.signature.isNotBlank()) {
                Text(
                    user.signature,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    titleHighlights = titleHighlights,
)

/**
 * 专栏与视频同一种行([VideoRow]),封面角标写「文章」,和首页那条专栏的写法一致;
 * 接口不给作者名,UP 名那一格换成分区。摘要放在最后一行。
 */
@Composable
private fun SearchArticle.toRowUi() = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = stringResource(Res.string.feed_article_badge),
    upName = categoryName.takeIf { it.isNotBlank() },
    dateText = formatDate(publishedAtEpochSeconds),
    meta = stringResource(Res.string.search_article_views, formatCount(viewCount)),
    note = summary.takeIf { it.isNotBlank() },
    titleHighlights = titleHighlights,
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
