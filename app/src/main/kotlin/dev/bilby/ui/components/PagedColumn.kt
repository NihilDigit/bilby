package dev.bilby.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * 一页页往下翻的列表。**这个应用里的列表页全长这样**:首屏转圈 / 首屏失败 / 空态 /
 * 条目 / 触底预取 / 底部的"翻页中·没有更多了·翻页失败重试"。
 *
 * 抽出来之前,这套骨架在历史、关注、收藏夹、空间的三个 tab 里各抄了一份,其中那段
 * `snapshotFlow` 预取逐字重复了六遍。重复本身不致命,致命的是它们会各自漂移:
 * ListFooter 加上错误重试那次,就只改到了其中几处;哪几处漏了要逐个文件读才看得出来。
 *
 * **不做下拉刷新**:那是 `PullToRefreshBox` 的事,由调用方在外面套 —— 有的页面有刷新
 * (关注列表),有的没有(空间的合集 tab),塞进来只会多一个到处传的空回调。
 *
 * @param error 只在**首屏**(列表为空)时占整屏;列表已经有内容时它交给底部那一行,
 *   已经读到的东西不该被一次翻页失败清掉。
 * @param header 列表顶部的固定内容(空间页的排序行、动态页的搜索框)。**放在 LazyColumn
 *   里面**而不是外面一层 Column:它要跟着列表一起滚走,而不是常驻。
 */
@Composable
fun <T> PagedColumn(
    items: List<T>,
    key: (T) -> Any,
    loading: Boolean,
    appending: Boolean,
    hasMore: Boolean,
    error: String?,
    emptyText: String,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    /** 首屏失败时那个按钮做的事。默认与翻页重试同一个动作。 */
    onRetry: () -> Unit = onLoadMore,
    contentPadding: PaddingValues = PaddingValues(),
    listState: LazyListState = rememberLazyListState(),
    header: (LazyListScope.() -> Unit)? = null,
    itemContent: @Composable (T) -> Unit,
) {
    if (loading && items.isEmpty()) {
        FullScreenLoading(modifier)
        return
    }
    if (error != null && items.isEmpty()) {
        FullScreenError(error, onRetry, modifier)
        return
    }

    // 触底预取。**在 composition 之外用 snapshotFlow 观察**,不能在 composable body 里直接
    // 调 onLoadMore —— 那样每次重组都会再请求一次。
    LaunchedEffect(listState, hasMore, appending) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (last, total) -> last != null && last >= total - 1 - PrefetchThreshold }
            .collect { if (hasMore && !appending) onLoadMore() }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        header?.invoke(this)

        if (items.isEmpty()) {
            // 空态占满列表视口,不是只占一个条目的高度 —— 一行灰字挂在顶上读起来像加载没完。
            item(key = "empty") { EmptyState(emptyText, Modifier.fillParentMaxSize()) }
        }

        items(items, key = key) { item -> itemContent(item) }

        item(key = "footer") {
            ListFooter(
                appending = appending,
                hasMore = hasMore,
                hasItems = items.isNotEmpty(),
                error = error,
                onRetry = onLoadMore,
            )
        }
    }
}

/**
 * 还剩几条时开始取下一页。5 是各页原本就在用的值,收编时照搬 —— 再小会让人等在列表底部,
 * 再大就是在用户根本不会滚到的地方提前发请求,而那在风控上不是免费的。
 */
private const val PrefetchThreshold = 5
