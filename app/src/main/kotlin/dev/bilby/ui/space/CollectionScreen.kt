package dev.bilby.ui.space

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceVideoItem
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.appendDistinctBy
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.theme.Breakpoints
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CollectionUiState(
    val items: List<SpaceVideoItem> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 一个合集/系列的目录。**曾经是空间页里的一个状态**,升成独立目的地的理由见
 * [dev.bilby.ui.CollectionContents]。
 *
 * 页面身份由 (mid, id, isSeason) 三样构成,全部来自导航参数 —— 所以这里不需要"当前打开的是
 * 哪个合集"这种可变状态,也就没有了原先那条"目录被关掉后迟到的响应把它重新支起来"的路。
 */
class CollectionViewModel(
    private val mid: Long,
    private val id: Long,
    private val isSeason: Boolean,
    private val repository: SpaceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()

    /** 刷新会把游标退回第一页。代次让迟到的旧第一页即使参数相同也写不回来。 */
    private var generation = 0L

    init {
        loadMore()
    }

    fun refresh() {
        generation++
        _state.update {
            it.copy(
                page = 1,
                refreshing = true,
                loading = false, appending = false, hasMore = true, error = null,
            )
        }
        loadMore(replace = true)
    }

    fun loadMore(replace: Boolean = false) {
        val current = _state.value
        if (current.loading || current.appending || !current.hasMore) return
        val firstPage = replace || current.items.isEmpty()
        val requestedPage = if (replace) 1 else current.page
        val requestedGeneration = generation
        _state.update { it.copy(loading = firstPage, appending = !firstPage, error = null) }
        viewModelScope.launch {
            val result = repository.loadCollectionDetail(mid, id, isSeason, requestedPage)
            _state.update { state ->
                if (requestedGeneration != generation || state.page != requestedPage) return@update state
                when (result) {
                    is BiliResult.Ok -> {
                        val pageItems = result.value.items
                        val merged = if (replace) {
                            pageItems.distinctBy { it.bvid }
                        } else {
                            state.items.appendDistinctBy(pageItems) { v -> v.bvid }
                        }
                        state.copy(
                            items = merged,
                            // 空页当作到头:游标只在这一页真的带回了东西时才前进,理由同
                            // SpaceViewModel.loadMoreArchives。
                            page = if (pageItems.isEmpty()) state.page else requestedPage + 1,
                            total = result.value.total,
                            loading = false,
                            appending = false,
                            refreshing = false,
                            hasMore = pageItems.isNotEmpty() && merged.size < result.value.total,
                        )
                    }

                    else -> state.copy(
                        loading = false,
                        appending = false,
                        refreshing = false,
                        error = result.errorText(),
                    )
                }
            }
        }
    }
}

private fun BiliResult<*>.errorText(): String = when (this) {
    is BiliResult.ApiError -> "$message($code)"
    is BiliResult.Failure -> cause.message ?: "网络错误"
    is BiliResult.Ok -> ""
}

@Composable
fun CollectionScreen(
    title: String,
    state: CollectionUiState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { BilbyTopBar(title = title, onBack = onBack) },
    ) { padding ->
        AdaptiveContent(
            modifier = Modifier.fillMaxSize().padding(padding),
            maxWidth = Breakpoints.ReadableWidth,
        ) {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                VideoListTab(
                    items = state.items,
                    appending = state.appending,
                    hasMore = state.hasMore,
                    loading = state.loading,
                    error = state.error,
                    emptyText = stringResource(R.string.space_empty_collection_detail),
                    onLoadMore = onLoadMore,
                    onVideoClick = onVideoClick,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}
