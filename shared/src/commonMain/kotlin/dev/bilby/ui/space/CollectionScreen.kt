package dev.bilby.ui.space

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.appendDistinctBy
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceVideoItem
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.TopBarRefreshButton
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.theme.tabular
import dev.bilby.ui.windowDragArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

@Immutable
data class CollectionUiState(
    val items: List<SpaceVideoItem> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val errorRes: StringResource? = null,
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
                loading = false, appending = false, hasMore = true, error = null, errorRes = null,
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
        _state.update { it.copy(loading = firstPage, appending = !firstPage, error = null, errorRes = null) }
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
                        error = null,
                        errorRes = result.errorTextRes("合集详情"),
                    )
                }
            }
        }
    }
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
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val totalCount = state.total.takeIf { it > 0 } ?: state.items.size
    val subtitle = if (totalCount > 0) stringResource(Res.string.collection_total_count, totalCount) else null
    val errorText = state.errorRes?.let { stringResource(it) } ?: state.error

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LocalWindowCaption.current?.Host()
            val captionSlot = rememberCaptionSlot()
            val backLabel = stringResource(Res.string.action_back)
            MediumFlexibleTopAppBar(
                modifier = captionSlot.modifier,
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = CaptionDragHeight)
                            .windowDragArea(captionSlot.atTop),
                    ) {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                subtitle = subtitle?.let { countLabel ->
                    {
                        Text(
                            text = countLabel,
                            style = MaterialTheme.typography.bodyMedium.tabular,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BilbyTooltip(text = backLabel, shortcut = "Alt+←") {
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = backLabel,
                                )
                            }
                        }
                        TopBarRefreshButton(refreshing = state.refreshing, onRefresh = onRefresh)
                    }
                },
                actions = {
                    captionSlot.buttons?.invoke()
                },
                scrollBehavior = scrollBehavior,
            )
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
                error = errorText,
                emptyText = stringResource(Res.string.space_empty_collection_detail),
                onLoadMore = onLoadMore,
                onVideoClick = onVideoClick,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
