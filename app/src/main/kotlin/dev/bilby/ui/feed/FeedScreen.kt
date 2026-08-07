package dev.bilby.ui.feed

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import dev.bilby.data.model.FeedItem
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.BilbyTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

data class FeedUiState(
    val items: List<FeedItem> = emptyList(),
    val loading: Boolean = false, // 首屏加载
    val appending: Boolean = false, // 追加下一页
    val hasMore: Boolean = true,
    val error: String? = null,
)

private const val PrefetchThreshold = 5

/**
 * 动态流。列表本身刻意不做特殊设计(DESIGN 2.1):没有下拉刷新仪式动画、没有红点、
 * 没有未读计数,底部会明确说"没有更多了"—— 时间序动态流天生能刷完,刷完就得看得出来。
 *
 * @param contentPadding 由外层给的内边距(顶栏和底部导航栏的高度)。用 contentPadding
 *   而不是外层 padding,内容才能滚到栏底下去而静止时又不被遮住。
 */
@Composable
fun FeedScreen(
    state: FeedUiState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onItemClick: (FeedItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    when {
        state.loading && state.items.isEmpty() -> FullScreenLoading(modifier)
        state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onRetry, modifier)
        else -> FeedList(state, onLoadMore, onItemClick, modifier, contentPadding)
    }
}

@Composable
private fun FeedList(
    state: FeedUiState,
    onLoadMore: () -> Unit,
    onItemClick: (FeedItem) -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()

    // 触底预取:在 composition 外用 snapshotFlow 观察滚动位置,避免在 composable 里直接调用副作用。
    LaunchedEffect(listState, state.hasMore, state.appending) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> lastVisible != null && lastVisible >= total - 1 - PrefetchThreshold }
            .collect {
                if (state.hasMore && !state.appending) onLoadMore()
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        if (state.items.isEmpty()) {
            item(key = "empty") { EmptyState("关注的 UP 主还没有新投稿") }
        }
        items(state.items, key = { it.bvid }) { item ->
            VideoRow(item = item.toRowUi(), onClick = { onItemClick(item) })
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

private fun FeedItem.toRowUi() = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = durationText,
    upName = upName,
    dateText = formatRelativeTime(publishedAtEpochSeconds),
    playText = playCount,
    danmakuText = danmakuCount,
)

private val AbsoluteDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatRelativeTime(epochSeconds: Long, nowEpochSeconds: Long = Instant.now().epochSecond): String {
    val diff = nowEpochSeconds - epochSeconds
    return when {
        diff < 60 -> "刚刚"
        diff < 3600 -> "${diff / 60}分钟前"
        diff < 24 * 3600 -> "${diff / 3600}小时前"
        diff < 2 * 24 * 3600 -> "昨天"
        diff < 7 * 24 * 3600 -> "${diff / (24 * 3600)}天前"
        else -> Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(AbsoluteDateFormatter)
    }
}

// ---- Preview ----

private fun previewItem(bvid: String, title: String, minutesAgo: Long) = FeedItem(
    bvid = bvid,
    title = title,
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    durationText = "12:34",
    upName = "某知名UP主",
    upMid = 12345L,
    publishedAtEpochSeconds = Instant.now().epochSecond - minutesAgo * 60,
    playCount = "12.3万",
    danmakuCount = "888",
)

private val previewItems = listOf(
    previewItem("BV1aa", "这是一个很长很长需要两行才能显示完的视频标题示例文本内容", 5),
    previewItem("BV1bb", "三小时前发布的视频", 3 * 60),
    previewItem("BV1cc", "昨天发布的视频", 30 * 60),
    previewItem("BV1dd", "三天前发布的视频", 3 * 24 * 60),
    previewItem("BV1ee", "很久以前发布的视频", 30 * 24 * 60),
)

@Preview(showBackground = true, name = "列表")
@Composable
private fun FeedScreenListPreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(items = previewItems, hasMore = true), {}, {}, {})
    }
}

@Preview(showBackground = true, name = "已刷完")
@Composable
private fun FeedScreenNoMorePreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(items = previewItems, hasMore = false), {}, {}, {})
    }
}

@Preview(showBackground = true, name = "空")
@Composable
private fun FeedScreenEmptyPreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(items = emptyList(), hasMore = false), {}, {}, {})
    }
}

@Preview(showBackground = true, name = "错误")
@Composable
private fun FeedScreenErrorPreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(error = "网络连接失败"), {}, {}, {})
    }
}
