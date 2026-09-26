package dev.bilby.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * 触底预取:最后一个可见条目离末尾不到 [PrefetchThreshold] 条时取下一页。
 *
 * **全应用只有这一份。** 它曾经在订阅页、关注动态、评论区、楼中楼面板和 [PagedColumn] 里各抄
 * 一遍,五份都漏了同一件事:出过错还接着取。翻页失败之后 appending 落回 false,效应随之重启,
 * 人还停在列表底部,于是立刻再发一次,失败了再来 —— 空间页的动态栏遇到 -412 时 3 秒打了
 * 25 次。风控下的失败,多打几次只会让封禁更久。
 *
 * @param canLoad 此刻能不能取:还有下一页、没有在取、**上一次没有失败**。失败之后只等页脚或
 *   整屏上的重试,不自动再来。它变化时效应重启,变回 true 的那一刻若人仍在底部,会立即补取。
 */
@Composable
fun PrefetchNearEnd(state: LazyListState, canLoad: Boolean, onLoadMore: () -> Unit) =
    PrefetchNearEnd(state, canLoad, onLoadMore) {
        state.layoutInfo.let { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
    }

/** 网格版,规则同上。 */
@Composable
fun PrefetchNearEnd(state: LazyGridState, canLoad: Boolean, onLoadMore: () -> Unit) =
    PrefetchNearEnd(state, canLoad, onLoadMore) {
        state.layoutInfo.let { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
    }

/**
 * 瀑布流版。可见条目按列交错排,列表里的最后一个不一定是下标最大的那个,取最大下标。
 */
@Composable
fun PrefetchNearEnd(state: LazyStaggeredGridState, canLoad: Boolean, onLoadMore: () -> Unit) =
    PrefetchNearEnd(state, canLoad, onLoadMore) {
        state.layoutInfo.let { info -> info.visibleItemsInfo.maxOfOrNull { it.index } to info.totalItemsCount }
    }

/**
 * 在 composition 之外用 snapshotFlow 观察滚动位置:在 composable body 里直接调 onLoadMore 的话,
 * 每次重组都会再请求一次。
 */
@Composable
private fun PrefetchNearEnd(
    key: Any,
    canLoad: Boolean,
    onLoadMore: () -> Unit,
    lastVisibleAndTotal: () -> Pair<Int?, Int>,
) {
    val latestOnLoadMore by rememberUpdatedState(onLoadMore)
    LaunchedEffect(key, canLoad) {
        if (!canLoad) return@LaunchedEffect
        snapshotFlow(lastVisibleAndTotal)
            .distinctUntilChanged()
            .filter { (last, total) -> last != null && last >= total - 1 - PrefetchThreshold }
            .collect { latestOnLoadMore() }
    }
}

/**
 * 还剩几条时开始取下一页。5 是各页原本就在用的值,收编时照搬 —— 再小会让人等在列表底部,
 * 再大就是在用户根本不会滚到的地方提前发请求,而那在风控上不是免费的。
 */
private const val PrefetchThreshold = 5
