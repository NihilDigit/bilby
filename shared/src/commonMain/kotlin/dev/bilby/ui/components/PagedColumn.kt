package dev.bilby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import dev.bilby.ui.navigationBarsBottom

/**
 * [PagedColumn] 的排法。
 *
 * [Grid] 是等高的行:视频行、关注行这些定高的东西,一行几列,行与行对齐。
 * [Staggered] 是瀑布流:动态这种一条三行、一条半屏的东西,并排时按网格排会在矮的那条下面空出
 * 一大截,瀑布流让每一列各自往下接。
 */
sealed interface PagedLayout {
    data class Grid(val cells: GridCells) : PagedLayout

    /** @param spacing 列与列、条目与条目之间的间距。 */
    data class Staggered(val cells: StaggeredGridCells, val spacing: Dp) : PagedLayout

    companion object {
        val SingleColumn: PagedLayout = Grid(GridCells.Fixed(1))
    }
}

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
 * @param header 列表顶部的固定内容(空间页的排序行、动态页的搜索框)。**放在列表里面**
 *   而不是外面一层 Column:它要跟着列表一起滚走,而不是常驻。多列时它横跨整行。
 * @param layout 默认一列。宽屏的视频列表传 [dev.bilby.ui.maxWidthGridCells] 的网格,动态传
 *   瀑布流;单列与多列共用预取、空态和页脚,不另起一份。
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
    layout: PagedLayout = PagedLayout.SingleColumn,
    header: (@Composable () -> Unit)? = null,
    /** 翻到底时写不写「没有更多了」。短名单一眼看得到头,写了反倒像还藏着什么。 */
    showEndMarker: Boolean = true,
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
        // 预取放在内容这一支里,和三态收敛之前的位置等价:首屏还在转圈时它本来也不该发请求。
        val canLoad = hasMore && !appending && error == null
        val footer: @Composable () -> Unit = {
            ListFooter(
                appending = appending,
                hasMore = hasMore,
                hasItems = items.isNotEmpty(),
                error = error,
                onRetry = onLoadMore,
                showEndMarker = showEndMarker,
            )
        }
        // **`animateItem` 在条目外面给一次,所有翻页列表就都有了。** 条目的增删在这些页面是
        // 常事(历史删一批、关注切分组、收藏夹取消收藏),硬切会让下面几十行瞬移一格。
        // 它靠 [key] 认条目,所以调用方那个 key 必须是真的稳定标识,不能是下标。
        //
        // 包一层 Box 而不是把 modifier 递给 itemContent:递出去等于要求每个调用方都把它
        // 接到自己那一行的根上,漏一个就是这一页没有动效,而漏没漏要逐页读才看得出来。
        when (layout) {
            is PagedLayout.Grid -> {
                val state = rememberLazyGridState()
                PrefetchNearEnd(state, canLoad, onLoadMore)
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalGrid(
                        columns = layout.cells,
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                    ) {
                        if (header != null) {
                            item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
                        }
                        if (items.isEmpty()) {
                            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                ViewportEmptyState(emptyText, state.layoutInfo.viewportSize.height)
                            }
                        }
                        items(items, key = key) { item ->
                            Box(modifier = Modifier.animateItem()) { itemContent(item) }
                        }
                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { footer() }
                    }
                    ListScrollbar(state, Modifier.align(Alignment.CenterEnd))
                }
            }

            is PagedLayout.Staggered -> {
                val state = rememberLazyStaggeredGridState()
                PrefetchNearEnd(state, canLoad, onLoadMore)
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyVerticalStaggeredGrid(
                        columns = layout.cells,
                        state = state,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                        horizontalArrangement = Arrangement.spacedBy(layout.spacing),
                        verticalItemSpacing = layout.spacing,
                    ) {
                        if (header != null) {
                            item(key = "header", span = StaggeredGridItemSpan.FullLine) { header() }
                        }
                        if (items.isEmpty()) {
                            item(key = "empty", span = StaggeredGridItemSpan.FullLine) {
                                ViewportEmptyState(emptyText, state.layoutInfo.viewportSize.height)
                            }
                        }
                        items(items, key = key) { item ->
                            Box(modifier = Modifier.animateItem()) { itemContent(item) }
                        }
                        item(key = "footer", span = StaggeredGridItemSpan.FullLine) { footer() }
                    }
                    ListScrollbar(state, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}

/**
 * 空态占满列表视口,不是只占一个条目的高度 —— 一行灰字挂在顶上读起来像加载没完。
 * 网格与瀑布流的条目作用域都没有 fillParentMaxSize,视口高度从 layoutInfo 取,量的是同一个值。
 */
@Composable
private fun ViewportEmptyState(text: String, viewportHeightPx: Int) {
    val viewportHeight = with(LocalDensity.current) { viewportHeightPx.toDp() }
    EmptyState(text, Modifier.fillMaxWidth().height(viewportHeight))
}
