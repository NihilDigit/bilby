package dev.bilby.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.ui.appendDistinctBy
import dev.bilby.ui.AdaptiveContent
import dev.bilby.api.BiliResult
import dev.bilby.data.HistoryItem
import dev.bilby.data.HistoryRepository
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.BilbyTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** 删除或清空正在飞。顶栏的那几个入口据此禁用,避免同一批记录被删两次。 */
    val mutating: Boolean = false,
    /**
     * 删除失败的原因。**不并进 [error]**:那一个归翻页用,写进去会让整页退成"重试"
     * 状态,而重试按钮做的是再拉一页,跟刚才失败的删除没有关系。
     */
    val mutationError: String? = null,
)

/**
 * 历史记录(DESIGN 2 节)。
 *
 * 分页照 [HistoryRepository] 的游标语义:每页把服务端给的 `max`/`view_at` 原样带下去。
 */
class HistoryViewModel(private val repository: HistoryRepository) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    /**
     * refresh 和 append 共用同一对游标(cursorMax/cursorViewAt),不能并发改(性能计划 7.2)。
     * refresh 把游标清零重来,但保留屏上的旧列表直到第一页成功;一条还在飞的旧 append
     * 落地必须被当作过期丢弃,否则它会把清零前的游标写回来,或把上一轮条目拼进新列表。
     */
    private var generation = 0
    private var loadJob: Job? = null
    private var lastRequestWasReplace = false

    init {
        loadMore()
    }

    fun retry() = loadMore(replace = lastRequestWasReplace)

    fun refresh() {
        generation++
        loadJob?.cancel()
        _state.update {
            it.copy(
                cursorMax = 0L,
                cursorViewAt = 0L,
                hasMore = true,
                refreshing = true,
                loading = false,
                appending = false,
                error = null,
            )
        }
        loadMore(replace = true)
    }

    fun loadMore(replace: Boolean = false) {
        val current = _state.value
        if (current.loading || current.appending || !current.hasMore) return
        lastRequestWasReplace = replace
        val firstPage = replace || current.items.isEmpty()
        val gen = generation
        _state.update { it.copy(loading = firstPage, appending = !firstPage, error = null) }
        loadJob = viewModelScope.launch {
            try {
                when (val result = repository.loadPage(current.cursorMax, current.cursorViewAt)) {
                    is BiliResult.Ok -> {
                        if (gen != generation) return@launch
                        _state.update {
                            it.copy(
                                // 读 it 而不是协程外那份 current 快照:并发刷新时不能把旧列表
                                // 重新拼回去。
                                //
                                // 按 oid 去重是必需的:同一个视频重复观看在历史里本来就会再出现一条,
                                // 游标翻页时上一页的条目会跟着漂到下一页。
                                items = if (replace) {
                                    result.value.items.distinctBy { item -> item.oid }
                                } else {
                                    it.items.appendDistinctBy(result.value.items) { item -> item.oid }
                                },
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

    /**
     * 删除选中的这些。**删成功才从列表里摘掉**:乐观更新的前提是失败能回滚,而这是一个
     * 游标列表,把行插回原来的位置没有可靠的做法。删除本身也不可撤销 —— 服务端没有恢复
     * 接口,所以入口那侧用确认对话框,不用可撤销的 Snackbar。
     */
    fun delete(items: List<HistoryItem>) {
        if (_state.value.mutating) return
        // kid 缺了就拼成 archive_0,那指向的是一条谁也不知道的记录。宁可少删一条。
        val deletable = items.filter { it.kid != 0L }
        if (deletable.size != items.size) {
            BiliLog.w("历史记录删除跳过 ${items.size - deletable.size} 条没有 kid 的条目")
        }
        if (deletable.isEmpty()) return
        val removedOids = deletable.map { it.oid }.toSet()
        cancelInFlightLoad()
        _state.update { it.copy(mutating = true, mutationError = null) }
        viewModelScope.launch {
            when (val result = repository.delete(deletable.map { it.kid })) {
                is BiliResult.Ok -> _state.update {
                    it.copy(mutating = false, items = it.items.filterNot { item -> item.oid in removedOids })
                }

                else -> _state.update { it.copy(mutating = false, mutationError = result.errorText()) }
            }
        }
    }

    /** 清空全部。服务端一条接口做完,不必逐条删(notes §3.7)。 */
    fun clearAll() {
        if (_state.value.mutating) return
        cancelInFlightLoad()
        _state.update { it.copy(mutating = true, mutationError = null) }
        viewModelScope.launch {
            when (val result = repository.clear()) {
                // 清完之后没有下一页:游标归零、hasMore 关掉,否则触底预取立刻再拉一次。
                // 下拉刷新会把 hasMore 重新打开,所以这不是把这一页锁死。
                is BiliResult.Ok -> _state.update {
                    it.copy(
                        mutating = false,
                        items = emptyList(),
                        cursorMax = 0L,
                        cursorViewAt = 0L,
                        hasMore = false,
                    )
                }

                else -> _state.update { it.copy(mutating = false, mutationError = result.errorText()) }
            }
        }
    }

    fun dismissMutationError() = _state.update { it.copy(mutationError = null) }

    /**
     * 掐掉在飞的那一页。删之前发出去的请求落地时会把刚删掉的条目原样拼回列表。
     * generation 一动,那个协程的 finally 就不再回写标志位,所以 loading/appending
     * 要在这里自己清干净;清不干净的话 appending 会一直挂着,之后再也翻不了页。
     */
    private fun cancelInFlightLoad() {
        generation++
        loadJob?.cancel()
        _state.update { it.copy(loading = false, appending = false, refreshing = false) }
    }

    private fun BiliResult<*>.errorText(): String = when (this) {
        is BiliResult.ApiError -> "$message($code)"
        is BiliResult.Failure -> cause.message ?: "网络错误"
        is BiliResult.Ok -> ""
    }
}


/**
 * @param selectedIds 已选中条目的 oid,`null` 表示不在多选态。
 *
 *   多选态和选中集合仍然是**一个**状态,不是一个布尔加一个集合 —— 后者能凑出互相矛盾的
 *   组合。但这里的空集合不能当作"不在多选态":顶栏的「选择」要在一条都还没选的时候就进入
 *   多选,那一刻集合本来就是空的。这是与 `OfflineScreen` 的偏离,那边长按是唯一入口,
 *   进入多选必然带着一条,所以"非空即多选"在那边成立。退出多选由 contextual 顶栏的返回
 *   箭头承担,空集合不是一个退不出去的状态。
 *
 *   用 oid 而不是 kid:列表本来就按 oid 去重、按 oid 作 key,选中集合跟着同一个键走。
 */
@Composable
fun HistoryScreen(
    state: HistoryUiState,
    onItemClick: (HistoryItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    selectedIds: Set<Long>? = null,
    onToggleSelection: (HistoryItem) -> Unit = {},
    onDismissMutationError: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val selecting = selectedIds != null

    // 删除失败要说出来。这条路径没有乐观更新,失败之后屏上什么都没变 —— 不给一句话的话,
    // 用户看到的是"点了删除,记录还在"。
    state.mutationError?.let { message ->
        AlertDialog(
            onDismissRequest = onDismissMutationError,
            title = { Text(stringResource(R.string.history_delete_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = onDismissMutationError) { Text(stringResource(R.string.action_confirm)) }
            },
        )
    }

    AdaptiveContent(modifier = modifier) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            PagedColumn(
                items = state.items,
                key = { it.oid },
                loading = state.loading,
                appending = state.appending,
                hasMore = state.hasMore,
                error = state.error,
                emptyText = stringResource(R.string.history_empty),
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                contentPadding = contentPadding,
            ) { item ->
                val selected = selectedIds != null && item.oid in selectedIds
                VideoRow(
                    item = item.toRowUi(),
                    // 多选态下点一行是勾选,不是打开:进了多选还去播放,等于长按一下就再也
                    // 删不成批。
                    onClick = { if (selecting) onToggleSelection(item) else onItemClick(item) },
                    // 长按只是快捷方式,不是唯一入口 —— 顶栏另有一个「选择」。长按没有任何
                    // 视觉提示,只发现得了点击的人也必须能进多选。
                    onLongClick = { onToggleSelection(item) },
                    // 整行染色而不是只画一个勾:勾在行尾,而人是从左往右扫的。
                    modifier = if (selected) {
                        Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                    } else {
                        Modifier
                    },
                    // onCheckedChange = null:整行已经是一个可点节点,勾选框自己再接一次
                    // 点击会让读屏把它和这一行当成两件事。
                    trailing = { if (selecting) Checkbox(checked = selected, onCheckedChange = null) },
                )
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
    kid = oid,
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

@Preview(showBackground = true, name = "多选")
@Composable
private fun HistoryScreenSelectingPreview() {
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
            selectedIds = setOf(1L),
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
