package dev.bilby.ui.toview

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.bilby.api.BiliResult
import dev.bilby.data.ToViewItem
import dev.bilby.data.ToViewRepository
import dev.bilby.ui.theme.BilbyTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ToViewUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val items: List<ToViewItem> = emptyList(),
    val count: Int = 0,
    val capacity: Int = ToViewRepository.CAPACITY,
    val clearing: Boolean = false,
)

/**
 * DESIGN 2.5:原生列表双向同步,不建本地队列,原生 100 条上限本身很小,一次拉满(见
 * ToViewRepository.CAPACITY)就是全部,所以这里没有分页/触底加载。
 */
class ToViewViewModel(private val repository: ToViewRepository) : ViewModel() {

    private val _state = MutableStateFlow(ToViewUiState())
    val state: StateFlow<ToViewUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.loadList()) {
                is BiliResult.Ok -> _state.update {
                    it.copy(loading = false, items = result.value.items, count = result.value.count)
                }

                else -> _state.update { it.copy(loading = false, error = result.errorText()) }
            }
        }
    }

    fun retry() = refresh()

    /** 就地更新:删除成功后直接从列表摘掉这一条,不整页重拉(团队要求)。 */
    fun delete(item: ToViewItem) {
        viewModelScope.launch {
            when (val result = repository.delete(item.aid)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(items = it.items - item, count = (it.count - 1).coerceAtLeast(0))
                }

                else -> _state.update { it.copy(error = result.errorText()) }
            }
        }
    }

    fun clearFinished() {
        _state.update { it.copy(clearing = true) }
        viewModelScope.launch {
            when (val result = repository.clearFinished()) {
                is BiliResult.Ok -> _state.update {
                    val remaining = it.items.filterNot { item -> item.isFinished }
                    it.copy(clearing = false, items = remaining, count = remaining.size)
                }

                else -> _state.update { it.copy(clearing = false, error = result.errorText()) }
            }
        }
    }

    private fun BiliResult<*>.errorText(): String = when (this) {
        is BiliResult.ApiError -> "$message($code)"
        is BiliResult.Failure -> cause.message ?: "网络错误"
        is BiliResult.Ok -> ""
    }
}

@Composable
fun ToViewScreen(
    state: ToViewUiState,
    onDelete: (ToViewItem) -> Unit,
    onClearFinished: () -> Unit,
    onItemClick: (ToViewItem) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading && state.items.isEmpty() -> FullScreenLoading(modifier)
        state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onRetry, modifier)
        else -> Column(modifier = modifier.fillMaxSize()) {
            ToViewHeader(state.count, state.capacity, state.clearing, onClearFinished)
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.items, key = { it.aid }) { item ->
                    ToViewRow(item, onClick = { onItemClick(item) }, onDelete = { onDelete(item) })
                }
                if (state.items.isEmpty()) {
                    item { EmptyHint() }
                }
            }
        }
    }
}

@Composable
private fun ToViewHeader(count: Int, capacity: Int, clearing: Boolean, onClearFinished: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 反囤积设计的一部分:上限要亮给用户看,不是藏起来的实现细节(DESIGN 2.5)。
        Text("已用 $count / $capacity", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onClearFinished, enabled = !clearing) {
            Text(if (clearing) "清空中…" else "清空已看完")
        }
    }
}

@Composable
private fun ToViewRow(item: ToViewItem, onClick: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.coverUrl)
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (!item.isFinished && item.progressSeconds > 0) {
                val durationSeconds = item.durationText.toSecondsOrNull()
                if (durationSeconds != null && durationSeconds > 0) {
                    LinearProgressIndicator(
                        progress = { (item.progressSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().align(Alignment.BottomStart),
                    )
                }
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                item.upName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                if (item.isFinished) "已看完" else "看到 ${item.progressSeconds / 60}:${(item.progressSeconds % 60).toString().padStart(2, '0')}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除")
        }
    }
}

/** "mm:ss" -> 秒数,只用于算进度条比例,拿不到就不画进度条。 */
private fun String.toSecondsOrNull(): Long? {
    val parts = split(":")
    if (parts.size != 2) return null
    val minutes = parts[0].toLongOrNull() ?: return null
    val seconds = parts[1].toLongOrNull() ?: return null
    return minutes * 60 + seconds
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        Text(
            "稍后再看是空的",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun FullScreenError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(top = 12.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

// ---- Preview ----

private fun previewItem(aid: Long, title: String, progress: Long) = ToViewItem(
    aid = aid,
    bvid = "BV1aa",
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    title = title,
    durationText = "12:34",
    upName = "某知名UP主",
    progressSeconds = progress,
)

@Preview(showBackground = true, name = "列表")
@Composable
private fun ToViewScreenPreview() {
    BilbyTheme {
        ToViewScreen(
            state = ToViewUiState(
                loading = false,
                items = listOf(
                    previewItem(1, "看到一半的视频", 300),
                    previewItem(2, "已经看完的视频", -1),
                ),
                count = 2,
            ),
            onDelete = {},
            onClearFinished = {},
            onItemClick = {},
            onRetry = {},
        )
    }
}
