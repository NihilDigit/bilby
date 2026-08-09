package dev.bilby.ui.follow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.ui.appendDistinctBy
import dev.bilby.ui.AdaptiveContent
import dev.bilby.api.BiliResult
import dev.bilby.data.FollowRepository
import dev.bilby.data.UpBrief
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.theme.Dimens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

data class FollowingsUiState(
    val items: List<UpBrief> = emptyList(),
    // false 而不是 true:loadMore 的并发守卫现在直接读这个字段(见 FollowingsViewModel),
    // 默认 true 会让 init{} 里的第一次调用把自己挡在门外。首屏 loading 由 loadMore 显式置位。
    val loading: Boolean = false,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 关注列表。顺序用 `order_type=attention`,与动态页顶上那排一致 ——
 * 从那排点进来却换一种排法,会让人以为进错了地方。
 */
class FollowingsViewModel(private val repository: FollowRepository) : ViewModel() {

    private val _state = MutableStateFlow(FollowingsUiState())
    val state: StateFlow<FollowingsUiState> = _state.asStateFlow()

    private var page = 0

    /**
     * refresh 与 append 共用同一个 page 游标,不能并发改(性能计划 7.2):refresh 把 page
     * 清零重来,但保留屏上的旧列表直到第一页成功。这时一条还在飞的旧 append 落地必须
     * 当作过期丢弃,否则它会把清零后的 page 又向前推一格,或混入新列表。
     */
    private var generation = 0
    private var job: Job? = null

    init {
        loadMore()
    }

    fun loadMore(replace: Boolean = false) {
        val current = _state.value
        if (current.loading || current.appending || !current.hasMore) return
        val next = if (replace) 1 else page + 1
        val gen = generation
        _state.update {
            it.copy(
                loading = replace || next == 1,
                appending = !replace && next > 1,
            )
        }
        job = viewModelScope.launch {
            try {
                when (val result = repository.followings(next)) {
                    is BiliResult.Ok -> {
                        if (gen != generation) return@launch
                        page = next
                        _state.update {
                            it.copy(
                                items = if (replace) {
                                    result.value.distinctBy { up -> up.mid }
                                } else {
                                    it.items.appendDistinctBy(result.value) { up -> up.mid }
                                },
                                // 接口不给 has_more,按"这一页没满就是最后一页"判断。
                                hasMore = result.value.isNotEmpty(),
                                error = null,
                            )
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

    fun refresh() {
        generation++
        job?.cancel()
        page = 0
        // loading 留 false:loadMore 的并发守卫要看到它才会真的发请求,这里置 true
        // 反而会把紧跟着的 loadMore() 自己挡在门外(见 loadMore 顶部的守卫)。
        _state.update {
            it.copy(error = null, loading = false, appending = false, hasMore = true, refreshing = true)
        }
        loadMore(replace = true)
    }

    fun retry() {
        if (_state.value.items.isNotEmpty()) loadMore(replace = page == 0) else refresh()
    }

    private fun fail(message: String) {
        BiliLog.w("取关注列表失败: $message")
        _state.update { it.copy(loading = false, appending = false, refreshing = false, error = message) }
    }
}

@Composable
fun FollowingsScreen(
    state: FollowingsUiState,
    onUpClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    AdaptiveContent(modifier = modifier) {
        when {
            state.loading && state.items.isEmpty() -> FullScreenLoading(Modifier.padding(contentPadding))
            state.error != null && state.items.isEmpty() ->
                FullScreenError(state.error, onRetry, Modifier.padding(contentPadding))
            state.items.isEmpty() ->
                EmptyState(stringResource(R.string.followings_empty), Modifier.fillMaxSize().padding(contentPadding))
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
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                    ) {
                        items(state.items, key = { it.mid }) { up ->
                            FollowingRow(up, onClick = { onUpClick(up.mid) })
                        }
                        item(key = "footer") {
                            ListFooter(
                                appending = state.appending,
                                hasMore = state.hasMore,
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

@Composable
private fun FollowingRow(up: UpBrief, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(up.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = up.sign.takeIf { it.isNotBlank() }?.let { sign ->
            {
                Text(
                    sign,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingContent = {
            BiliAsyncImage(
                url = up.faceUrl,
                contentDescription = null,
                modifier = Modifier.size(Dimens.AvatarMedium).clip(CircleShape),
            )
        },
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}
