package dev.bilby.ui.follow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.ui.appendDistinctBy
import dev.bilby.api.BiliResult
import dev.bilby.data.FollowRepository
import dev.bilby.data.UpBrief
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow

data class FollowingsUiState(
    val items: List<UpBrief> = emptyList(),
    val loading: Boolean = true,
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
    private var loading = false

    init {
        loadMore()
    }

    fun loadMore() {
        if (loading || !_state.value.hasMore) return
        loading = true
        val next = page + 1
        if (next > 1) _state.update { it.copy(appending = true) }
        viewModelScope.launch {
            when (val result = repository.followings(next)) {
                is BiliResult.Ok -> {
                    page = next
                    _state.update {
                        it.copy(
                            items = it.items.appendDistinctBy(result.value) { up -> up.mid },
                            loading = false,
                            appending = false,
                            refreshing = false,
                            // 接口不给 has_more,按"这一页没满就是最后一页"判断。
                            hasMore = result.value.isNotEmpty(),
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

    fun refresh() {
        page = 0
        _state.update {
            it.copy(items = emptyList(), error = null, loading = true, appending = false, hasMore = true, refreshing = true)
        }
        loadMore()
    }

    fun retry() = refresh()

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
    when {
        state.loading && state.items.isEmpty() -> FullScreenLoading(modifier)
        state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onRetry, modifier)
        state.items.isEmpty() -> EmptyState(stringResource(R.string.followings_empty))
        else -> {
            val listState = rememberLazyListState()
            LaunchedEffect(listState, state.hasMore, state.appending) {
                snapshotFlow { listState.layoutInfo }
                    .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
                    .distinctUntilChanged()
                    .filter { (last, total) -> last != null && last >= total - 3 }
                    .collect { if (state.hasMore && !state.appending) onLoadMore() }
            }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                ) {
                    items(state.items, key = { it.mid }) { up ->
                        FollowingRow(up, onClick = { onUpClick(up.mid) })
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
private fun FollowingRow(up: UpBrief, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        BiliAsyncImage(
            url = up.faceUrl,
            contentDescription = null,
            modifier = Modifier.size(44.dp).clip(CircleShape),
        )
        Column(modifier = Modifier.padding(start = 12.dp)) {
            Text(up.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (up.sign.isNotBlank()) {
                Text(
                    up.sign,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
