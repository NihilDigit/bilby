package dev.bilby.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import dev.bilby.ui.navigationBarsBottom
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * 一页页往下翻的列表。**这个应用里的列表页全长这样**:首屏骨架 / 首屏失败 / 空态 /
 * 条目 / 触底预取 / 底部的"翻页中·没有更多了·翻页失败重试"。
 *
 * 抽出来之前,这套骨架在历史、关注、收藏夹、空间的三个 tab 里各抄了一份,其中那段
 * `snapshotFlow` 预取逐字重复了六遍。重复本身不致命,致命的是它们会各自漂移:
 * ListFooter 加上错误重试那次,就只改到了其中几处;哪几处漏了要逐个文件读才看得出来。
 *
 * **不做下拉刷新**:那是 [RefreshBox] 的事,由调用方在外面套 —— 有的页面有刷新
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
    /**
     * 默认只让出手势条:列表铺到屏幕下沿,最后一项停在手势条上方。外层已经让过并消费掉时
     * 量到 0。
     */
    contentPadding: PaddingValues = PaddingValues(bottom = navigationBarsBottom()),
    listState: LazyListState = rememberLazyListState(),
    header: (LazyListScope.() -> Unit)? = null,
    /** 首屏读取中的一行占位,见 [FirstScreenState]。默认是视频行。 */
    skeletonRow: @Composable () -> Unit = { VideoRowSkeleton() },
    itemContent: @Composable (T) -> Unit,
) {
    FirstScreenState(
        loading = loading,
        error = error,
        isEmpty = items.isEmpty(),
        onRetry = onRetry,
        modifier = modifier,
        skeleton = { ListSkeleton(Modifier.padding(contentPadding), row = skeletonRow) },
    ) {
        // 触底预取。**在 composition 之外用 snapshotFlow 观察**,不能在 composable body 里直接
        // 调 onLoadMore —— 那样每次重组都会再请求一次。
        //
        // 放在内容这一支里,和三态收敛之前的位置等价:首屏还在转圈时它本来也不该发请求。
        LaunchedEffect(listState, hasMore, appending) {
            snapshotFlow { listState.layoutInfo }
                .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
                .distinctUntilChanged()
                .filter { (last, total) -> last != null && last >= total - 1 - PrefetchThreshold }
                .collect { if (hasMore && !appending) onLoadMore() }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
                header?.invoke(this)

                if (items.isEmpty()) {
                    // 空态占满列表视口,不是只占一个条目的高度 —— 一行灰字挂在顶上读起来像加载没完。
                    item(key = "empty") { EmptyState(emptyText, Modifier.fillParentMaxSize()) }
                }

                // **`animateItem` 在这里给一次,所有翻页列表就都有了。** 条目的增删在这些页面是
                // 常事(历史删一批、关注切分组、收藏夹取消收藏),硬切会让下面几十行瞬移一格。
                // 它靠 [key] 认条目,所以调用方那个 key 必须是真的稳定标识,不能是下标。
                //
                // 包一层 Box 而不是把 modifier 递给 itemContent:递出去等于要求每个调用方都把它
                // 接到自己那一行的根上,漏一个就是这一页没有动效,而漏没漏要逐页读才看得出来。
                items(items, key = key) { item ->
                    Box(modifier = Modifier.animateItem()) { itemContent(item) }
                }

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
            ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/**
 * 还剩几条时开始取下一页。5 是各页原本就在用的值,收编时照搬 —— 再小会让人等在列表底部,
 * 再大就是在用户根本不会滚到的地方提前发请求,而那在风控上不是免费的。
 */
private const val PrefetchThreshold = 5
