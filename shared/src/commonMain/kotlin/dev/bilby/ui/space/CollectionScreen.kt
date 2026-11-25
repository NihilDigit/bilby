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
import dev.bilby.data.CollectionSort
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceVideoItem
import dev.bilby.data.inferSeasonReverse
import dev.bilby.data.sortedByCollectionSort
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.SortMenu
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
    val sort: CollectionSort = CollectionSort.Default,
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
 *
 * 支持按默认顺序、最新发布、最早发布查看:
 * - 系列(`isSeason = false`): 直接由接口参数 `sort=desc|asc` 切换正倒序。
 * - 合集(`isSeason = true`): 300 条以内全量拉齐后按发布时间精确排序并本地缓存(后续切换零请求);
 *   超大合集按首屏时间戳走向推断 `sort_reverse` 方向分页拉取。
 */
class CollectionViewModel(
    private val mid: Long,
    private val id: Long,
    private val isSeason: Boolean,
    private val repository: SpaceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(CollectionUiState())
    val state: StateFlow<CollectionUiState> = _state.asStateFlow()

    /** 刷新或切换排序会使旧请求作废。 */
    private var generation = 0L

    /** 合集在默认顺序下完整缓存的条目;非空且达到 total 时切换排序直接本地重排。 */
    private var fullDefaultCache: List<SpaceVideoItem>? = null

    /** 默认顺序首屏,用于超大合集推断 sort_reverse 方向。 */
    private var defaultFirstPage: List<SpaceVideoItem> = emptyList()

    /** 超大合集在时间排序分页时选定的服务端反转标记。 */
    private var activeReverse: Boolean = false

    init {
        loadMore()
    }

    fun onSortChanged(sort: CollectionSort) {
        val current = _state.value
        if (current.sort == sort) return
        generation++

        // 合集全量缓存已就绪:直接本地排序,无需再发请求。
        val cached = fullDefaultCache
        if (isSeason && cached != null) {
            _state.update {
                it.copy(
                    sort = sort,
                    items = cached.sortedByCollectionSort(sort),
                    loading = false,
                    appending = false,
                    refreshing = false,
                    hasMore = false,
                    error = null,
                    errorRes = null,
                )
            }
            return
        }

        // 系列直接走服务端 desc/asc 分页;合集 Default 也走常规分页。
        if (!isSeason || sort == CollectionSort.Default) {
            activeReverse = !isSeason && sort == CollectionSort.Oldest
            _state.update {
                it.copy(
                    sort = sort,
                    items = emptyList(),
                    page = 1,
                    loading = false,
                    appending = false,
                    refreshing = false,
                    hasMore = true,
                    error = null,
                    errorRes = null,
                )
            }
            loadMore(replace = true)
            return
        }

        // 合集切换到按时间排序:若总数 <= 300 全量拉齐后精确按发布时间排序;超大合集按首屏推断反转方向分页。
        val knownTotal = current.total
        if (knownTotal in 1..FULL_SORT_MAX_ITEMS) {
            loadAllSeasonForSort(sort)
        } else if (knownTotal > FULL_SORT_MAX_ITEMS) {
            activeReverse = inferSeasonReverse(defaultFirstPage, sort)
            _state.update {
                it.copy(
                    sort = sort,
                    items = emptyList(),
                    page = 1,
                    loading = false,
                    appending = false,
                    refreshing = false,
                    hasMore = true,
                    error = null,
                    errorRes = null,
                )
            }
            loadMore(replace = true)
        } else {
            // 首屏还未回来时就点了排序:先走全量拉取流程,首屏返回后再决定是否需要翻页。
            loadAllSeasonForSort(sort)
        }
    }

    fun refresh() {
        generation++
        fullDefaultCache = null
        val sort = _state.value.sort
        if (isSeason && sort != CollectionSort.Default) {
            _state.update {
                it.copy(
                    refreshing = true,
                    loading = false,
                    appending = false,
                    error = null,
                    errorRes = null,
                )
            }
            loadAllSeasonForSort(sort, refreshing = true)
            return
        }
        activeReverse = !isSeason && sort == CollectionSort.Oldest
        _state.update {
            it.copy(
                page = 1,
                refreshing = true,
                loading = false, appending = false, hasMore = true, error = null, errorRes = null,
            )
        }
        loadMore(replace = true)
    }

    private fun loadAllSeasonForSort(sort: CollectionSort, refreshing: Boolean = false) {
        val requestedGeneration = generation
        _state.update {
            it.copy(
                sort = sort,
                loading = !refreshing,
                appending = false,
                refreshing = refreshing,
                hasMore = false,
                error = null,
                errorRes = null,
            )
        }
        viewModelScope.launch {
            val firstResult = repository.loadCollectionDetail(mid, id, isSeason = true, page = 1, reverse = false)
            if (requestedGeneration != generation) return@launch
            when (firstResult) {
                is BiliResult.Ok -> {
                    val total = firstResult.value.total
                    defaultFirstPage = firstResult.value.items
                    if (total > FULL_SORT_MAX_ITEMS) {
                        activeReverse = inferSeasonReverse(defaultFirstPage, sort)
                        _state.update {
                            it.copy(
                                items = emptyList(),
                                page = 1,
                                total = total,
                                loading = false,
                                appending = false,
                                refreshing = false,
                                hasMore = true,
                            )
                        }
                        loadMore(replace = true)
                        return@launch
                    }
                    val collected = firstResult.value.items.toMutableList()
                    var page = 2
                    while (collected.size < total && page <= FULL_SORT_MAX_PAGES) {
                        val next = repository.loadCollectionDetail(mid, id, isSeason = true, page = page, reverse = false)
                        if (requestedGeneration != generation) return@launch
                        when (next) {
                            is BiliResult.Ok -> {
                                if (next.value.items.isEmpty()) break
                                collected += next.value.items
                                page++
                            }
                            else -> break
                        }
                    }
                    val deduped = collected.distinctBy { it.bvid }
                    if (deduped.size >= total || collected.size < page * SpaceRepository.COLLECTION_PAGE_SIZE) {
                        fullDefaultCache = deduped
                    }
                    _state.update { state ->
                        if (requestedGeneration != generation) return@update state
                        state.copy(
                            sort = sort,
                            items = deduped.sortedByCollectionSort(sort),
                            page = page,
                            total = total,
                            loading = false,
                            appending = false,
                            refreshing = false,
                            hasMore = false,
                            error = null,
                            errorRes = null,
                        )
                    }
                }

                else -> _state.update { state ->
                    if (requestedGeneration != generation) return@update state
                    state.copy(
                        loading = false,
                        appending = false,
                        refreshing = false,
                        error = null,
                        errorRes = firstResult.errorTextRes("合集详情"),
                    )
                }
            }
        }
    }

    fun loadMore(replace: Boolean = false) {
        val current = _state.value
        if (current.loading || current.appending || !current.hasMore) return
        val firstPage = replace || current.items.isEmpty()
        val requestedPage = if (replace) 1 else current.page
        val requestedGeneration = generation
        val sort = current.sort
        val reverse = if (isSeason) activeReverse else sort == CollectionSort.Oldest
        _state.update { it.copy(loading = firstPage, appending = !firstPage, error = null, errorRes = null) }
        viewModelScope.launch {
            val result = repository.loadCollectionDetail(mid, id, isSeason, requestedPage, reverse = reverse)
            _state.update { state ->
                if (requestedGeneration != generation || state.page != requestedPage) return@update state
                when (result) {
                    is BiliResult.Ok -> {
                        val pageItems = result.value.items
                        if (isSeason && sort == CollectionSort.Default && requestedPage == 1) {
                            defaultFirstPage = pageItems
                        }
                        val merged = if (replace) {
                            pageItems.distinctBy { it.bvid }
                        } else {
                            state.items.appendDistinctBy(pageItems) { v -> v.bvid }
                        }
                        val hasMore = pageItems.isNotEmpty() && merged.size < result.value.total
                        if (isSeason && sort == CollectionSort.Default && !hasMore) {
                            fullDefaultCache = merged
                        }
                        val displayed = if (isSeason && sort != CollectionSort.Default) {
                            merged.sortedByCollectionSort(sort)
                        } else {
                            merged
                        }
                        state.copy(
                            items = displayed,
                            page = if (pageItems.isEmpty()) state.page else requestedPage + 1,
                            total = result.value.total,
                            loading = false,
                            appending = false,
                            refreshing = false,
                            hasMore = hasMore,
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

    private companion object {
        const val FULL_SORT_MAX_PAGES = 10
        const val FULL_SORT_MAX_ITEMS = FULL_SORT_MAX_PAGES * SpaceRepository.COLLECTION_PAGE_SIZE
    }
}

private val CollectionSortOptions = listOf(
    CollectionSort.Default to Res.string.collection_sort_default,
    CollectionSort.Newest to Res.string.collection_sort_newest,
    CollectionSort.Oldest to Res.string.collection_sort_oldest,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CollectionScreen(
    title: String,
    state: CollectionUiState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    onSortChanged: (CollectionSort) -> Unit = {},
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
                    SortMenu(
                        options = CollectionSortOptions,
                        selected = state.sort,
                        onSelect = onSortChanged,
                    )
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
