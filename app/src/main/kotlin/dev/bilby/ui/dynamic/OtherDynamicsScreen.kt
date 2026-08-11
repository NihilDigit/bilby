package dev.bilby.ui.dynamic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.bilby.R
import dev.bilby.data.model.DynamicCard
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

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
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { BilbyTopBar(title = stringResource(R.string.dynamic_other_title), onBack = onBack) },
    ) { padding ->
        when {
            state.loading && state.items.isEmpty() -> FullScreenLoading(Modifier.padding(padding))
            state.error != null && state.items.isEmpty() ->
                FullScreenError(state.error, onRetry, Modifier.padding(padding))

            else -> AdaptiveContent(modifier = Modifier.padding(padding)) {
                OtherDynamicsList(state, onRefresh, onLoadMore, onAction)
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
) {
    val listState = rememberLazyListState()

    LaunchedEffect(listState, state.hasMore, state.appending) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> lastVisible != null && lastVisible >= total - 1 - PrefetchThreshold }
            .collect { if (state.hasMore && !state.appending) onLoadMore() }
    }

    PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // 左右 16 与投稿列表(VideoRow 的 horizontal padding)对齐 —— 两页里同一条边。
            contentPadding = PaddingValues(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            if (state.items.isEmpty()) {
                item(key = "empty") { EmptyState(stringResource(R.string.dynamic_other_empty)) }
            }
            // 一条动态一张卡片,条目之间不画线。这些条目高矮不一(一行字到九宫格图都有),
            // 早先靠整宽分割线断开,而一条动态内部本来就有好几块带底色的内容(转发块、直播卡、
            // 预约块),再叠一层横线之后整页全是线,读不出哪条线是分界、哪条是内部结构。
            // 底色加圆角把边界画在卡片自己身上,层次只用 container 色阶(风格指南 §1.1)。
            // 卡片的底色、圆角、内边距都归 DynamicCardView 自己(见那边的 BlockStyle),
            // 这一页只决定条目之间留多少 gap。
            items(state.items, key = { it.id }) { card ->
                DynamicCardView(card = card, onAction = onAction)
            }
            item(key = "footer") {
                ListFooter(
                    appending = state.appending,
                    hasMore = state.hasMore,
                    hasItems = state.items.isNotEmpty(),
                )
            }
        }
    }
}

private const val PrefetchThreshold = 5
