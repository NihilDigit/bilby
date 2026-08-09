package dev.bilby.ui.history

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.R
import dev.bilby.ui.appendDistinctBy
import dev.bilby.api.BiliResult
import dev.bilby.data.HistoryItem
import dev.bilby.data.HistoryRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class HistoryUiState(
    val items: List<HistoryItem> = emptyList(),
    val cursorMax: Long = 0L,
    val cursorViewAt: Long = 0L,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 历史记录(DESIGN 2 节)。只做展示,不做删除/清空 —— 这一轮的边界是"看得到自己看过什么",
 * 增删属于另一次要不要做的判断,不在这次范围内。
 *
 * 分页照 [HistoryRepository] 的游标语义:每页把服务端给的 `max`/`view_at` 原样带下去。
 */
class HistoryViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    /**
     * refresh 和 append 共用同一对游标(cursorMax/cursorViewAt),不能并发改(性能计划 7.2)。
     * refresh 把游标清零重来,这时一条还在飞的旧 append 落地必须被当作过期丢弃 ——
     * 不然它会把清零前的游标重新写回去,或者把上一轮的条目拼进刷新后的空列表。
     */
    private var generation = 0
    private var loadJob: Job? = null

    init {
        loadMore()
    }

    fun retry() = loadMore()

    fun refresh() {
        generation++
        loadJob?.cancel()
        _state.update {
            it.copy(items = emptyList(), cursorMax = 0L, cursorViewAt = 0L, hasMore = true, refreshing = true, loading = false, appending = false)
        }
        loadMore()
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.appending || !current.hasMore) return
        val firstPage = current.items.isEmpty()
        val gen = generation
        _state.update { it.copy(loading = firstPage, appending = !firstPage, error = null) }
        loadJob = viewModelScope.launch {
            try {
                when (val result = repository.loadPage(current.cursorMax, current.cursorViewAt)) {
                    is BiliResult.Ok -> {
                        if (gen != generation) return@launch
                        _state.update {
                            it.copy(
                                // 读 it 而不是协程外那份 current 快照:刷新会把 items 清空,用陈旧快照
                                // 拼接等于把清空前的内容又写回去。
                                //
                                // 按 oid 去重是必需的:同一个视频重复观看在历史里本来就会再出现一条,
                                // 游标翻页时上一页的条目会跟着漂到下一页。
                                items = it.items.appendDistinctBy(result.value.items) { item -> item.oid },
                                cursorMax = result.value.nextMax,
                                cursorViewAt = result.value.nextViewAt,
                                hasMore = !result.value.isEnd,
                            )
                        }
                    }

                    else -> if (gen == generation) _state.update { it.copy(error = result.errorText()) }
                }
            } finally {
                // 按当前 generation 释放:被 refresh 取消的旧一代不该把刷新刚置上的
                // loading/refreshing 又扒下来。
                if (gen == generation) _state.update { it.copy(loading = false, appending = false, refreshing = false) }
            }
        }
    }

    private fun BiliResult<*>.errorText(): String = when (this) {
        is BiliResult.ApiError -> "$message($code)"
        is BiliResult.Failure -> cause.message ?: "网络错误"
        is BiliResult.Ok -> ""
    }
}

private const val PrefetchThreshold = 5

@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onItemClick: (HistoryItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    when {
        state.loading && state.items.isEmpty() -> FullScreenLoading(modifier)
        state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onRetry, modifier)
        state.items.isEmpty() -> EmptyState(stringResource(R.string.history_empty))
        else -> {
            val listState = rememberLazyListState()
            LaunchedEffect(listState, state.hasMore, state.appending) {
                snapshotFlow { listState.layoutInfo }
                    .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
                    .distinctUntilChanged()
                    .filter { (last, total) -> last != null && last >= total - 1 - PrefetchThreshold }
                    .collect { if (state.hasMore && !state.appending) onLoadMore() }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    items(state.items, key = { it.oid }) { item ->
                        VideoRow(item = item.toRowUi(), onClick = { onItemClick(item) })
                    }
                    item(key = "footer") {
                        ListFooter(appending = state.appending, hasMore = state.hasMore, hasItems = true)
                    }
                }
            }
        }
    }
}

/** internal(不是 private):个人页的历史记录预览区也要用同一份映射,见 ui/profile/ProfileScreen.kt。 */
@Composable
internal fun HistoryItem.toRowUi() = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = durationText,
    upName = upName,
    dateText = formatRelativeTime(viewAtEpochSeconds),
    meta = if (isFinished) {
        stringResource(R.string.history_finished)
    } else {
        stringResource(R.string.history_progress, formatProgress(progressSeconds))
    },
    progressFraction = when {
        isFinished -> 1f
        durationSeconds > 0 && progressSeconds > 0 -> progressSeconds.toFloat() / durationSeconds
        else -> null
    },
)

private fun formatProgress(seconds: Long): String =
    "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

private val AbsoluteDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * "3 小时前" 这类相对时间。同一份逻辑 FeedScreen 里也有一份(私有,不能跨文件复用)——
 * 两处都在标"这条内容是什么时候来的",字面完全一致,拆成公共函数反而要为一件小事
 * 新开一个文件,收益不大。
 */
@Composable
private fun formatRelativeTime(epochSeconds: Long, nowEpochSeconds: Long = Instant.now().epochSecond): String {
    val diff = nowEpochSeconds - epochSeconds
    return when {
        diff < 60 -> stringResource(R.string.time_just_now)
        diff < 3600 -> stringResource(R.string.time_minutes_ago, diff / 60)
        diff < 24 * 3600 -> stringResource(R.string.time_hours_ago, diff / 3600)
        diff < 2 * 24 * 3600 -> stringResource(R.string.time_yesterday)
        diff < 7 * 24 * 3600 -> stringResource(R.string.time_days_ago, diff / (24 * 3600))
        else -> Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(AbsoluteDateFormatter)
    }
}

// ---- Preview ----

private fun previewItem(oid: Long, title: String, progress: Long) = HistoryItem(
    oid = oid,
    bvid = "BV1aa",
    title = title,
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    durationText = "12:34",
    durationSeconds = 754,
    upName = "某知名UP主",
    viewAtEpochSeconds = Instant.now().epochSecond - 3600,
    progressSeconds = progress,
)

@Preview(showBackground = true, name = "列表")
@Composable
private fun HistoryScreenPreview() {
    BilbyTheme {
        HistoryScreen(
            state = HistoryUiState(
                items = listOf(
                    previewItem(1, "看到一半的视频", 300),
                    previewItem(2, "已经看完的视频", -1),
                ),
            ),
            onItemClick = {},
            onLoadMore = {},
            onRetry = {},
        )
    }
}

@Preview(showBackground = true, name = "空")
@Composable
private fun HistoryScreenEmptyPreview() {
    BilbyTheme {
        HistoryScreen(HistoryUiState(loading = false), {}, {}, {})
    }
}
