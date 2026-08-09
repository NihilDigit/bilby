package dev.bilby.ui.fav

import dev.bilby.formatDurationSeconds
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.FavRepository
import dev.bilby.data.FavVideo
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class FavFolderUiState(
    val items: List<FavVideo> = emptyList(),
    // false 而不是 true:loadMore 的并发守卫现在直接读这个字段(见 FavFolderViewModel),
    // 默认 true 会让 init{} 里的第一次调用把自己挡在门外。首屏 loading 由 loadMore 显式置位。
    val loading: Boolean = false,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

class FavFolderViewModel(
    private val mediaId: Long,
    private val repository: FavRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FavFolderUiState())
    val state: StateFlow<FavFolderUiState> = _state.asStateFlow()

    private var page = 0

    /** 失效稿件的 bvid 是空的,不能拿它当 key;而 aid 一定有,收藏夹接口就是按 aid 组织的。 */
    private val seenAids = mutableSetOf<Long>()

    /**
     * reload 与 append 共用同一对游标(page、seenAids),不能并发改(性能计划 7.2):reload
     * 把它们清空重来,这时一条还在飞的旧 append 落地必须当作过期丢弃,否则它会拿清空前的
     * seenAids 过滤出的条目拼进 reload 后的空列表。
     */
    private var generation = 0
    private var job: Job? = null

    init {
        loadMore()
    }

    fun loadMore() {
        val current = _state.value
        if (current.loading || current.appending || !current.hasMore) return
        val next = page + 1
        val gen = generation
        _state.update { it.copy(loading = next == 1, appending = next > 1) }
        job = viewModelScope.launch {
            try {
                when (val result = repository.folderContents(mediaId, next)) {
                    is BiliResult.Ok -> {
                        if (gen != generation) return@launch
                        page = next
                        val fresh = result.value.items.filter { seenAids.add(it.aid) }
                        _state.update {
                            it.copy(items = it.items + fresh, hasMore = result.value.hasMore, error = null)
                        }
                    }

                    is BiliResult.ApiError -> if (gen == generation) fail("${result.message}(${result.code})")
                    is BiliResult.Failure -> if (gen == generation) fail(result.cause.message ?: "网络错误")
                }
            } finally {
                if (gen == generation) _state.update { it.copy(loading = false, appending = false, refreshing = false) }
            }
        }
    }

    fun retry() = reload(refreshing = false)

    /**
     * 下拉刷新。和 [retry] 是同一件事(整份重来),差别只在指示器:refreshing 得单独记,
     * 不能用 "loading 且列表非空" 去推 —— 重来的第一步正是把列表清空,那个条件永远不成立,
     * 指示器一次都不会显示。
     */
    fun refresh() = reload(refreshing = true)

    private fun reload(refreshing: Boolean) {
        generation++
        job?.cancel()
        page = 0
        seenAids.clear()
        _state.value = FavFolderUiState(refreshing = refreshing)
        loadMore()
    }

    private fun fail(message: String) {
        BiliLog.w("取收藏夹内容失败(media_id=$mediaId): $message")
        _state.update { it.copy(loading = false, appending = false, refreshing = false, error = message) }
    }
}

@Composable
fun FavFolderScreen(
    state: FavFolderUiState,
    onItemClick: (FavVideo) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    when {
        state.loading && state.items.isEmpty() -> FullScreenLoading(modifier)
        state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onRetry, modifier)
        state.items.isEmpty() -> EmptyState(stringResource(R.string.fav_empty))
        else -> {
            val listState = rememberLazyListState()
            LaunchedEffect(listState, state.hasMore, state.appending) {
                snapshotFlow { listState.layoutInfo }
                    .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
                    .distinctUntilChanged()
                    .filter { (last, total) -> last != null && last >= total - 3 }
                    .collect { if (state.hasMore && !state.appending) onLoadMore() }
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    items(state.items, key = { it.aid }) { item ->
                        VideoRow(
                            item = item.toRowUi(),
                            // 失效稿件照常列出但不可点:UP 删稿或转私密之后收藏夹里还留着这一条,
                            // 悄悄隐藏会让人以为自己记错了收藏过什么。
                            onClick = { if (!item.invalid) onItemClick(item) },
                        )
                    }
                    item(key = "footer") {
                        ListFooter(appending = state.appending, hasMore = state.hasMore, hasItems = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun FavVideo.toRowUi(): VideoRowUi {
    val invalidLabel = stringResource(R.string.fav_invalid_video)
    return VideoRowUi(
        title = if (invalid) invalidLabel else title,
        coverUrl = coverUrl,
        durationText = formatDurationSeconds(durationSeconds),
        upName = upName,
        // 收藏夹接口不给收藏时间,也不给弹幕数。传 null 而不是空串 —— StatRow 对 null 是
        // 整项不画,对空串是画个图标后面空着。
        dateText = null,
        playText = playCount.toString(),
        danmakuText = null,
    )
}
