package dev.bilby.ui.toview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.ToViewItem
import dev.bilby.data.ToViewRepository
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Spacing
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
        // 清空按钮在没有已看完项目时应当是 no-op，避免空列表上仍发起一次
        // 没有可见反馈的网络请求。UI 层也会同步禁用入口，但 ViewModel 自己
        // 仍要守住这个条件，防止重复点击或其它入口绕过 UI。
        if (_state.value.clearing || _state.value.items.none { it.isFinished }) return
        _state.update { it.copy(clearing = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.clearFinished()) {
                is BiliResult.Ok -> _state.update {
                    val remaining = it.items.filterNot { item -> item.isFinished }
                    it.copy(clearing = false, error = null, items = remaining, count = remaining.size)
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

/**
 * 稍后再看。"清空已看完"在顶栏(见 MainActivity),这里只剩容量条和列表。
 */
@Composable
fun ToViewScreen(
    state: ToViewUiState,
    onDelete: (ToViewItem) -> Unit,
    onItemClick: (ToViewItem) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    AdaptiveContent(modifier = modifier) {
        when {
            state.loading && state.items.isEmpty() -> FullScreenLoading()
            state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onRetry)
            else -> PullToRefreshBox(
                isRefreshing = state.loading && state.items.isNotEmpty(),
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    CapacityMeter(state.count, state.capacity)
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (state.items.isEmpty()) {
                            // 让空态填满列表视口，而不是只占一个列表项高度；保留
                            // LazyColumn 也能确保清空后仍支持下拉刷新。
                            item(key = "empty") {
                                EmptyState(
                                    stringResource(R.string.toview_empty),
                                    modifier = Modifier.fillParentMaxSize(),
                                )
                            }
                        }
                        items(state.items, key = { it.aid }) { item ->
                            VideoRow(
                                item = item.toRowUi(),
                                onClick = { onItemClick(item) },
                                trailing = {
                                    // 图标用 Close 而不是 Delete:这里是"从列表里拿掉",
                                    // 不是把视频删了。垃圾桶图标承诺的破坏性比实际动作大。
                                    IconButton(onClick = { onDelete(item) }) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = stringResource(R.string.toview_remove, item.title),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                            )
                        }
                        if (state.error != null) {
                            item(key = "error") {
                                ListFooter(
                                    appending = false,
                                    hasMore = true,
                                    hasItems = true,
                                    error = state.error,
                                    onRetry = onRetry,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 容量条。上限是反囤积设计的一部分,要亮给用户看,不是藏起来的实现细节(DESIGN 2.5)。
 *
 * 除了数字还画一条进度条:"87 / 100" 要读一下才知道快满了,一条快到头的进度条是扫一眼的事。
 * 快满时转成 error 色 —— 这是少数几个用 error 色的地方,因为它确实要求用户做点什么(清一清)。
 */
@Composable
private fun CapacityMeter(count: Int, capacity: Int, modifier: Modifier = Modifier) {
    val fraction = if (capacity > 0) (count.toFloat() / capacity).coerceIn(0f, 1f) else 0f
    val nearlyFull = fraction >= 0.9f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.toview_capacity, count, capacity),
                style = MaterialTheme.typography.titleSmall,
                color = if (nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            color = if (nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** internal(不是 private):个人页的稍后再看预览区也要用同一份映射,见 ui/profile/ProfileScreen.kt。 */
@Composable
internal fun ToViewItem.toRowUi() = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = durationText,
    upName = upName,
    meta = if (isFinished) {
        stringResource(R.string.toview_finished)
    } else {
        stringResource(R.string.toview_progress, formatProgress(progressSeconds))
    },
    progressFraction = if (isFinished) 1f else progressFraction(),
)

private fun ToViewItem.progressFraction(): Float? {
    val total = durationText.toSecondsOrNull() ?: return null
    if (total <= 0 || progressSeconds <= 0) return null
    return progressSeconds.toFloat() / total
}

private fun formatProgress(seconds: Long): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** "mm:ss" -> 秒数,只用于算进度条比例,拿不到就不画进度条。 */
private fun String.toSecondsOrNull(): Long? {
    val parts = split(":")
    if (parts.size != 2) return null
    val minutes = parts[0].toLongOrNull() ?: return null
    val seconds = parts[1].toLongOrNull() ?: return null
    return minutes * 60 + seconds
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
            onItemClick = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "快满了")
@Composable
private fun ToViewScreenNearlyFullPreview() {
    BilbyTheme {
        ToViewScreen(
            state = ToViewUiState(
                loading = false,
                items = listOf(previewItem(1, "看到一半的视频", 300)),
                count = 95,
            ),
            onDelete = {},
            onItemClick = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "空")
@Composable
private fun ToViewScreenEmptyPreview() {
    BilbyTheme {
        ToViewScreen(ToViewUiState(loading = false), {}, {}, {})
    }
}
