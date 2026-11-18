package dev.bilby.ui.space

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.appendDistinctBy
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceVideoItem
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveListContent
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular
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
 * 一个合集/系列的目录。页面身份由 (mid, id, isSeason) 三样构成,全部来自导航参数。
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CollectionScreen(
    title: String,
    state: CollectionUiState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    modifier: Modifier = Modifier,
    onListenAll: (() -> Unit)? = null,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            BilbyTopBar(
                title = title,
                onBack = onBack,
                scrollBehavior = scrollBehavior,
            ) {
                RefreshAction(refreshing = state.refreshing, onRefresh = onRefresh)
            }
        },
    ) { padding ->
        RefreshBox(
            refreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize().padScaffoldExceptBottom(padding),
        ) {
            VideoListTab(
                items = state.items,
                appending = state.appending,
                hasMore = state.hasMore,
                loading = state.loading,
                error = state.error,
                emptyText = stringResource(Res.string.space_empty_collection_detail),
                onLoadMore = onLoadMore,
                onVideoClick = onVideoClick,
                modifier = Modifier.fillMaxSize(),
                header = if (state.items.isEmpty() && state.total <= 0) null else {
                    {
                        CollectionSummaryCard(
                            totalCount = state.total.takeIf { it > 0 } ?: state.items.size,
                            canListen = state.items.isNotEmpty(),
                            onListenAll = {
                                if (onListenAll != null) {
                                    onListenAll()
                                } else {
                                    state.items.firstOrNull()?.let(onVideoClick)
                                }
                            },
                        )
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CollectionSummaryCard(
    totalCount: Int,
    canListen: Boolean,
    onListenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.Tight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(Res.string.collection_total_count, totalCount),
                style = MaterialTheme.typography.titleSmallEmphasized.tabular,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FilledTonalButton(
                onClick = onListenAll,
                enabled = canListen,
                contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
            ) {
                Icon(
                    Icons.Filled.Headphones,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconInline),
                )
                Text(
                    stringResource(Res.string.toview_listen_all),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    modifier = Modifier.padding(start = Spacing.Hair + 2.dp),
                )
            }
        }
    }
}
