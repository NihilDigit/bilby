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
import kotlinx.coroutines.launch

data class FavFolderUiState(
    val items: List<FavVideo> = emptyList(),
    val loading: Boolean = true,
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
    private var loading = false

    /** 失效稿件的 bvid 是空的,不能拿它当 key;而 aid 一定有,收藏夹接口就是按 aid 组织的。 */
    private val seenAids = mutableSetOf<Long>()

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || !_state.value.hasMore) return
        loading = true
        val next = page + 1
        if (next > 1) _state.update { it.copy(appending = true) }
        viewModelScope.launch {
            when (val result = repository.folderContents(mediaId, next)) {
                is BiliResult.Ok -> {
                    page = next
                    val fresh = result.value.items.filter { seenAids.add(it.aid) }
                    _state.update {
                        it.copy(
                            items = it.items + fresh,
                            loading = false,
                            appending = false,
                            refreshing = false,
                            hasMore = result.value.hasMore,
                            error = null,
                        )
                    }
                }

                is BiliResult.ApiError -> fail("${result.message}(${result.code})")
                is BiliResult.Failure -> fail(result.cause.message ?: "网络错误")
            }
            loading = false
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
