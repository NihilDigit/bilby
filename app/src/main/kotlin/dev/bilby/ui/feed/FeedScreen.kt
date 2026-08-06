package dev.bilby.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.bilby.data.model.FeedItem
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

@Composable
fun FeedScreen(
    state: FeedUiState,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onItemClick: (FeedItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading && state.items.isEmpty() -> FullScreenLoading(modifier)
        state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onRetry, modifier)
        else -> FeedList(state, onLoadMore, onItemClick, modifier)
    }
}

@Composable
private fun FeedList(
    state: FeedUiState,
    onLoadMore: () -> Unit,
    onItemClick: (FeedItem) -> Unit,
    modifier: Modifier = Modifier,
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

    // 内容要能滚到状态栏/手势条底下(edge-to-edge),但静止时不被遮住,所以是
    // contentPadding 而不是外层 padding。
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = WindowInsets.systemBars.asPaddingValues(),
    ) {
        itemsIndexed(state.items, key = { _, item -> item.bvid }) { _, item ->
            FeedRow(item = item, onClick = { onItemClick(item) })
        }
        item(key = "footer") {
            FeedFooter(appending = state.appending, hasMore = state.hasMore, hasItems = state.items.isNotEmpty())
        }
    }
}

@Composable
private fun FeedRow(item: FeedItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .width(140.dp)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.coverUrl)
                    // B 站图床有防盗链,不带 Referer 会拿到 403。
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 时长决定"现在看不看",压在封面上比排进文字行更快被扫到。
            if (item.durationText.isNotEmpty()) {
                Text(
                    text = item.durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                text = item.upName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                text = "${item.playCount}播放 · ${item.danmakuCount}弹幕 · ${formatRelativeTime(item.publishedAtEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FeedFooter(appending: Boolean, hasMore: Boolean, hasItems: Boolean) {
    if (!hasItems) return // 空列表时不额外显示"没有更多了"
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            appending -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            !hasMore -> Text(
                "没有更多了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun FullScreenError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(top = 12.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

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
        FeedScreen(
            state = FeedUiState(items = previewItems, hasMore = true),
            onLoadMore = {},
            onRetry = {},
            onItemClick = {},
        )
    }
}

@Preview(showBackground = true, name = "追加加载中")
@Composable
private fun FeedScreenAppendingPreview() {
    BilbyTheme {
        FeedScreen(
            state = FeedUiState(items = previewItems, appending = true),
            onLoadMore = {},
            onRetry = {},
            onItemClick = {},
        )
    }
}

@Preview(showBackground = true, name = "已刷完")
@Composable
private fun FeedScreenNoMorePreview() {
    BilbyTheme {
        FeedScreen(
            state = FeedUiState(items = previewItems, hasMore = false),
            onLoadMore = {},
            onRetry = {},
            onItemClick = {},
        )
    }
}

@Preview(showBackground = true, name = "首屏加载")
@Composable
private fun FeedScreenLoadingPreview() {
    BilbyTheme {
        FeedScreen(
            state = FeedUiState(loading = true),
            onLoadMore = {},
            onRetry = {},
            onItemClick = {},
        )
    }
}

@Preview(showBackground = true, name = "错误")
@Composable
private fun FeedScreenErrorPreview() {
    BilbyTheme {
        FeedScreen(
            state = FeedUiState(error = "网络连接失败"),
            onLoadMore = {},
            onRetry = {},
            onItemClick = {},
        )
    }
}
