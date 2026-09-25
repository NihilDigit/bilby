package dev.bilby.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.data.SearchArticle
import dev.bilby.data.SearchUser
import dev.bilby.data.SearchVideo
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PersonRowSkeleton
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.SortMenu
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
    val onArticleOrderChange: (SearchOrder) -> Unit,
    val onVideoClick: (bvid: String) -> Unit,
    val onUserClick: (mid: Long) -> Unit,
    /** cv 号。 */
    val onArticleClick: (id: Long) -> Unit,
    val onLoadMore: () -> Unit,
    val onRetry: () -> Unit,
)

/** 综合 / 最多播放 / 最新发布,顺序和取值都照 B 站搜索页本身。 */
private val VideoOrders = SearchOrder.entries.map { it to it.labelRes }

/** 专栏的三档。取值与视频相同(notes 2.4),只是「click」在这里是阅读数。 */
private val ArticleOrders = listOf(
    SearchOrder.Comprehensive to R.string.search_order_comprehensive,
    SearchOrder.Play to R.string.search_order_read,
    SearchOrder.NewPublished to R.string.search_order_pubdate,
)

private val Durations = SearchDuration.entries.map { it to it.labelRes }

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

    Column(modifier = modifier.fillMaxSize()) {
        // 不要默认那条通栏分割线,理由同空间页的标签栏。
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage, divider = {}) {
            tabs.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(tab.labelRes)) },
                )
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            ResultPage(tabs[page], state, actions)
        }
    }
}

/** 一栏:可选的筛选行,下面是可下拉刷新的列表。 */
@Composable
private fun ResultPage(tab: SearchTab, state: NormalSearchState, actions: SearchResultActions) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 筛选靠右,和其余列表上方的排序同一个位置、同一个组件(SortMenu)。用户那一栏没有
        // 可选的排序,整行不画。
        when (tab) {
            SearchTab.Video -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Tight),
                horizontalArrangement = Arrangement.End,
            ) {
                SortMenu(Durations, state.duration, actions.onDurationChange)
                SortMenu(VideoOrders, state.order, actions.onOrderChange)
            }

            SearchTab.Article -> Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Tight),
                horizontalArrangement = Arrangement.End,
            ) {
                SortMenu(ArticleOrders, state.articleOrder, actions.onArticleOrderChange)
            }

            SearchTab.User -> Unit
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
                SearchTab.Video -> VideoResults(state, actions)
                SearchTab.User -> PagedColumn(
                    items = state.users.items,
                    key = { it.mid },
                    skeletonRow = { PersonRowSkeleton() },
                    loading = state.users.loading && state.users.items.isEmpty(),
                    appending = state.users.appending,
                    hasMore = state.users.hasMore,
                    error = state.users.error.takeIf { state.users.items.isEmpty() },
                    emptyText = stringResource(R.string.search_no_results),
                    onLoadMore = actions.onLoadMore,
                    onRetry = actions.onRetry,
                    contentPadding = PaddingValues(bottom = ModeFabClearance + navigationBarsBottom()),
                ) { user -> UserResultRow(user, onClick = { actions.onUserClick(user.mid) }) }

                SearchTab.Article -> PagedColumn(
                    items = state.articles.items,
                    key = { it.id },
                    loading = state.articles.loading && state.articles.items.isEmpty(),
                    appending = state.articles.appending,
                    hasMore = state.articles.hasMore,
                    error = state.articles.error.takeIf { state.articles.items.isEmpty() },
                    emptyText = stringResource(R.string.search_no_results),
                    onLoadMore = actions.onLoadMore,
                    onRetry = actions.onRetry,
                    contentPadding = PaddingValues(bottom = ModeFabClearance + navigationBarsBottom()),
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
private fun VideoResults(state: NormalSearchState, actions: SearchResultActions) {
    val videos = state.videos
    PagedColumn(
        items = videos.items,
        key = { it.bvid },
        loading = videos.loading && videos.items.isEmpty(),
        appending = videos.appending,
        hasMore = videos.hasMore,
        error = videos.error.takeIf { videos.items.isEmpty() },
        emptyText = stringResource(R.string.search_no_results),
        onLoadMore = actions.onLoadMore,
        onRetry = actions.onRetry,
        // 底部让出切换助理的悬浮按钮(见 SearchChatScreen 的 ModeSwitchFab),最后一条才不被它盖住。
        contentPadding = PaddingValues(bottom = ModeFabClearance + navigationBarsBottom()),
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
                stringResource(R.string.search_user_meta, formatCount(user.fansCount), formatCount(user.videoCount)),
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
)

/**
 * 专栏与视频同一种行([VideoRow]),封面角标写「文章」,和首页那条专栏的写法一致;
 * 接口不给作者名,UP 名那一格换成分区。摘要放在最后一行。
 */
@Composable
private fun SearchArticle.toRowUi() = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = stringResource(R.string.feed_article_badge),
    upName = categoryName.takeIf { it.isNotBlank() },
    dateText = formatDate(publishedAtEpochSeconds),
    meta = stringResource(R.string.search_article_views, formatCount(viewCount)),
    note = summary.takeIf { it.isNotBlank() },
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
