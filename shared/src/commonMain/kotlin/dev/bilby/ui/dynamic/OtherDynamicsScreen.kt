package dev.bilby.ui.dynamic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.theme.Breakpoints
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import dev.bilby.ui.components.PrefetchNearEnd
import androidx.compose.ui.Modifier
import dev.bilby.data.model.DynamicCard
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.DynamicCardSkeleton
import dev.bilby.ui.components.ListSkeleton
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.theme.Spacing

/**
 * 其他动态:图文、转发、直播、专栏、剧集更新(DESIGN 2.1 的"图文/转发折叠为一个不显眼的
 * 入口")。
 *
 * **它和首页是同一条时间序流的两半**,不是第二个信息流:数据来自同一个 `feed/all`,同样只含
 * 关注的人发的东西,同样翻到底就没了。分开只因为转发混进投稿时间序会把首页变成半个广场
 * (DESIGN 2.1 的原话)。
 *
 * 因此这一页受首页同样的约束,逐条对过 DESIGN 1.1 的机制表:**服务端给的时间序原样渲染**,
 * 本地不排序、不打分、不去重成"精选";翻页只在末尾追加更旧的内容,**滚动时不往列表里插入
 * 任何东西**;没有红点,没有未读计数。加一个"热门图文"之类的分区就正好落在 1.3 的清单上。
 */
@Composable
fun OtherDynamicsScreen(
    state: OtherDynamicsUiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onAction: (DynamicAction) -> Unit,
    onLike: (id: String, like: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            BilbyTopBar(title = stringResource(Res.string.dynamic_other_title), onBack = onBack) {
                RefreshAction(refreshing = state.refreshing, onRefresh = onRefresh)
            }
        },
    ) { padding ->
        when {
            state.loading && state.items.isEmpty() ->
                ListSkeleton(Modifier.padding(padding), row = { DynamicCardSkeleton() })
            state.error != null && state.items.isEmpty() ->
                FullScreenError(state.error, onRetry, Modifier.padding(padding))

            // 宽屏是瀑布流,不再限宽,理由同 AdaptiveListContent;一条动态从一行字到半屏九宫格
            // 都有,按行排的网格会在矮的那条下面空出一大截。代价是读序只大致从上往下,
            // 不再逐条顺下去。
            else -> if (rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)) {
                OtherDynamicsList(
                    state, onRefresh, onLoadMore, onAction, onLike,
                    columns = StaggeredGridCells.Adaptive(Breakpoints.DynamicColumnMinWidth),
                    modifier = Modifier.padScaffoldExceptBottom(padding),
                )
            } else {
                AdaptiveContent(modifier = Modifier.padScaffoldExceptBottom(padding)) {
                    OtherDynamicsList(
                        state, onRefresh, onLoadMore, onAction, onLike,
                        columns = StaggeredGridCells.Fixed(1),
                    )
                }
            }
        }
    }
}

@Composable
private fun OtherDynamicsList(
    state: OtherDynamicsUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onAction: (DynamicAction) -> Unit,
    onLike: (id: String, like: Boolean) -> Unit,
    columns: StaggeredGridCells,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyStaggeredGridState()

    PrefetchNearEnd(
        listState,
        canLoad = state.hasMore && !state.appending && state.error == null,
        onLoadMore = onLoadMore,
    )

    RefreshBox(refreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier) {
        // 窄屏是一列的瀑布流,与原来的单列同形;两种宽度共用一个列表,预取只认一套状态。
        LazyVerticalStaggeredGrid(
            columns = columns,
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 左右 16 与投稿列表(VideoRow 的 horizontal padding)对齐 —— 两页里同一条边。
            contentPadding = PaddingValues(
                start = Spacing.Comfortable,
                end = Spacing.Comfortable,
                top = Spacing.Cozy,
                bottom = Spacing.Cozy + navigationBarsBottom(),
            ),
            verticalItemSpacing = Spacing.Tight,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            if (state.items.isEmpty()) {
                item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                    EmptyState(stringResource(Res.string.dynamic_other_empty))
                }
            }
            // 一条动态一张卡片,条目之间不画线。这些条目高矮不一(一行字到九宫格图都有),
            // 早先靠整宽分割线断开,而一条动态内部本来就有好几块带底色的内容(转发块、直播卡、
            // 预约块),再叠一层横线之后整页全是线,读不出哪条线是分界、哪条是内部结构。
            // 底色加圆角把边界画在卡片自己身上,层次只用 container 色阶(风格指南 §1.1)。
            // 卡片的底色、圆角、内边距都归 DynamicCardView 自己(见那边的 BlockStyle),
            // 这一页只决定条目之间留多少 gap。
            items(state.items, key = { it.id }) { card ->
                DynamicCardView(
                    card = card,
                    onAction = onAction,
                    onLike = { like -> onLike(card.id, like) },
                )
            }
            item(key = "footer", span = StaggeredGridItemSpan.FullLine) {
                ListFooter(
                    appending = state.appending,
                    hasMore = state.hasMore,
                    hasItems = state.items.isNotEmpty(),
                )
            }
        }
    }
}
