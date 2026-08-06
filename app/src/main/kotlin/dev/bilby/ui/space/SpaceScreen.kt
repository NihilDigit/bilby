package dev.bilby.ui.space

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.bilby.api.BiliResult
import dev.bilby.data.SpaceArchiveOrder
import dev.bilby.data.SpaceCollectionItem
import dev.bilby.data.SpaceProfile
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceVideoItem
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
import kotlinx.coroutines.launch

// ---------------- 状态 ----------------

enum class SpaceTab { Archives, Dynamics, Collections }

data class SpaceUiState(
    val loading: Boolean = true, // 首次加载 profile
    val error: String? = null,
    val profile: SpaceProfile? = null,
    val activeTab: SpaceTab = SpaceTab.Archives,
    val archives: SpaceArchiveTabState = SpaceArchiveTabState(),
    val dynamics: SpaceListTabState = SpaceListTabState(),
    val collections: SpaceCollectionsTabState = SpaceCollectionsTabState(),
)

data class SpaceArchiveTabState(
    val order: SpaceArchiveOrder = SpaceArchiveOrder.Pubdate,
    val keyword: String = "",
    val items: List<SpaceVideoItem> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

data class SpaceListTabState(
    val items: List<SpaceVideoItem> = emptyList(),
    val nextOffset: String? = null,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

data class SpaceCollectionsTabState(
    val items: List<SpaceCollectionItem> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    /** 非空即表示正在看某个合集/系列的目录,当作页内抽屉而不是独立导航目的地。 */
    val detail: SpaceCollectionDetailState? = null,
)

data class SpaceCollectionDetailState(
    val collection: SpaceCollectionItem,
    val items: List<SpaceVideoItem> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val loading: Boolean = true,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

// ---------------- ViewModel ----------------

class SpaceViewModel(
    private val mid: Long,
    private val repository: SpaceRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SpaceUiState())
    val state: StateFlow<SpaceUiState> = _state.asStateFlow()

    init {
        loadProfile()
        loadMoreArchives()
    }

    fun retry() {
        if (_state.value.profile == null) loadProfile()
        when (_state.value.activeTab) {
            SpaceTab.Archives -> if (_state.value.archives.items.isEmpty()) loadMoreArchives()
            SpaceTab.Dynamics -> if (_state.value.dynamics.items.isEmpty()) loadMoreDynamics()
            SpaceTab.Collections -> if (_state.value.collections.items.isEmpty()) loadMoreCollections()
        }
    }

    fun onTabSelected(tab: SpaceTab) {
        _state.update { it.copy(activeTab = tab) }
        when (tab) {
            SpaceTab.Archives -> if (_state.value.archives.items.isEmpty()) loadMoreArchives()
            SpaceTab.Dynamics -> if (_state.value.dynamics.items.isEmpty()) loadMoreDynamics()
            SpaceTab.Collections -> if (_state.value.collections.items.isEmpty()) loadMoreCollections()
        }
    }

    fun onArchiveOrderChanged(order: SpaceArchiveOrder) {
        if (order == _state.value.archives.order) return
        _state.update { it.copy(archives = SpaceArchiveTabState(order = order, keyword = it.archives.keyword)) }
        loadMoreArchives()
    }

    fun onArchiveKeywordChanged(keyword: String) {
        _state.update { it.copy(archives = it.archives.copy(keyword = keyword)) }
    }

    /** 空间内搜索复用投稿接口(notes 1.3 节),回车/点确认时才真正发请求,不做输入即请求。 */
    fun onArchiveSearch() {
        _state.update { it.copy(archives = it.archives.copy(items = emptyList(), page = 1, hasMore = true)) }
        loadMoreArchives()
    }

    fun loadMoreArchives() {
        val current = _state.value.archives
        if (current.loading || current.appending || !current.hasMore) return
        val firstPage = current.items.isEmpty()
        _state.update {
            it.copy(archives = it.archives.copy(loading = firstPage, appending = !firstPage, error = null))
        }
        viewModelScope.launch {
            when (val result = repository.loadArchives(mid, current.page, current.order, current.keyword)) {
                is BiliResult.Ok -> _state.update {
                    val merged = current.items + result.value.items
                    it.copy(
                        archives = current.copy(
                            items = merged,
                            page = current.page + 1,
                            total = result.value.total,
                            loading = false,
                            appending = false,
                            hasMore = merged.size < result.value.total,
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(archives = current.copy(loading = false, appending = false, error = result.errorText()))
                }
            }
        }
    }

    fun loadMoreDynamics() {
        val current = _state.value.dynamics
        if (current.loading || current.appending || !current.hasMore) return
        val firstPage = current.items.isEmpty()
        _state.update {
            it.copy(dynamics = it.dynamics.copy(loading = firstPage, appending = !firstPage, error = null))
        }
        viewModelScope.launch {
            when (val result = repository.loadDynamics(mid, current.nextOffset)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(
                        dynamics = current.copy(
                            items = current.items + result.value.items,
                            nextOffset = result.value.nextOffset,
                            loading = false,
                            appending = false,
                            hasMore = result.value.hasMore && result.value.nextOffset != null,
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(dynamics = current.copy(loading = false, appending = false, error = result.errorText()))
                }
            }
        }
    }

    fun loadMoreCollections() {
        val current = _state.value.collections
        if (current.loading || current.appending || !current.hasMore) return
        val firstPage = current.items.isEmpty()
        _state.update {
            it.copy(collections = it.collections.copy(loading = firstPage, appending = !firstPage, error = null))
        }
        viewModelScope.launch {
            when (val result = repository.loadCollections(mid, current.page)) {
                is BiliResult.Ok -> _state.update {
                    val merged = current.items + result.value.items
                    it.copy(
                        collections = current.copy(
                            items = merged,
                            page = current.page + 1,
                            total = result.value.total,
                            loading = false,
                            appending = false,
                            hasMore = merged.size < result.value.total,
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(collections = current.copy(loading = false, appending = false, error = result.errorText()))
                }
            }
        }
    }

    fun openCollection(item: SpaceCollectionItem) {
        _state.update { it.copy(collections = it.collections.copy(detail = SpaceCollectionDetailState(item))) }
        loadMoreCollectionDetail()
    }

    fun closeCollectionDetail() {
        _state.update { it.copy(collections = it.collections.copy(detail = null)) }
    }

    fun loadMoreCollectionDetail() {
        val detail = _state.value.collections.detail ?: return
        if (detail.loading || detail.appending || !detail.hasMore) return
        val firstPage = detail.items.isEmpty()
        _state.update {
            it.copy(
                collections = it.collections.copy(
                    detail = detail.copy(loading = firstPage, appending = !firstPage, error = null),
                ),
            )
        }
        viewModelScope.launch {
            when (val result = repository.loadCollectionDetail(mid, detail.collection, detail.page)) {
                is BiliResult.Ok -> _state.update {
                    val merged = detail.items + result.value.items
                    it.copy(
                        collections = it.collections.copy(
                            detail = detail.copy(
                                items = merged,
                                page = detail.page + 1,
                                total = result.value.total,
                                loading = false,
                                appending = false,
                                hasMore = merged.size < result.value.total,
                            ),
                        ),
                    )
                }

                else -> _state.update {
                    it.copy(
                        collections = it.collections.copy(
                            detail = detail.copy(loading = false, appending = false, error = result.errorText()),
                        ),
                    )
                }
            }
        }
    }

    private fun loadProfile() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.loadProfile(mid)) {
                is BiliResult.Ok -> _state.update { it.copy(loading = false, profile = result.value) }
                else -> _state.update { it.copy(loading = false, error = result.errorText()) }
            }
        }
    }

    private fun BiliResult<*>.errorText(): String = when (this) {
        is BiliResult.ApiError -> "$message($code)"
        is BiliResult.Failure -> cause.message ?: "网络错误"
        is BiliResult.Ok -> ""
    }
}

// ---------------- UI ----------------

@Composable
fun SpaceScreen(
    state: SpaceUiState,
    onTabSelected: (SpaceTab) -> Unit,
    onArchiveOrderChanged: (SpaceArchiveOrder) -> Unit,
    onArchiveKeywordChanged: (String) -> Unit,
    onArchiveSearch: () -> Unit,
    onLoadMoreArchives: () -> Unit,
    onLoadMoreDynamics: () -> Unit,
    onLoadMoreCollections: () -> Unit,
    onCollectionClick: (SpaceCollectionItem) -> Unit,
    onCollectionDetailBack: () -> Unit,
    onLoadMoreCollectionDetail: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.collections.detail
    if (detail != null) {
        CollectionDetailScreen(detail, onCollectionDetailBack, onLoadMoreCollectionDetail, onVideoClick, modifier)
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (state.profile != null) {
            SpaceHeader(state.profile)
        }
        PrimaryTabRow(selectedTabIndex = state.activeTab.ordinal) {
            Tab(
                selected = state.activeTab == SpaceTab.Archives,
                onClick = { onTabSelected(SpaceTab.Archives) },
                text = { Text("投稿") },
            )
            Tab(
                selected = state.activeTab == SpaceTab.Dynamics,
                onClick = { onTabSelected(SpaceTab.Dynamics) },
                text = { Text("动态") },
            )
            Tab(
                selected = state.activeTab == SpaceTab.Collections,
                onClick = { onTabSelected(SpaceTab.Collections) },
                text = { Text("合集") },
            )
        }
        when {
            state.loading && state.profile == null -> FullScreenLoading()
            state.error != null && state.profile == null -> FullScreenError(state.error, onRetry)
            else -> when (state.activeTab) {
                SpaceTab.Archives -> ArchivesTab(
                    state.archives,
                    onArchiveOrderChanged,
                    onArchiveKeywordChanged,
                    onArchiveSearch,
                    onLoadMoreArchives,
                    onVideoClick,
                )

                SpaceTab.Dynamics -> VideoListTab(
                    items = state.dynamics.items,
                    appending = state.dynamics.appending,
                    hasMore = state.dynamics.hasMore,
                    loading = state.dynamics.loading,
                    error = state.dynamics.error,
                    onLoadMore = onLoadMoreDynamics,
                    onVideoClick = onVideoClick,
                )

                SpaceTab.Collections -> CollectionsTab(
                    state.collections,
                    onLoadMoreCollections,
                    onCollectionClick,
                )
            }
        }
    }
}

@Composable
private fun SpaceHeader(profile: SpaceProfile, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val context = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(profile.faceUrl)
                .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp).clip(CircleShape),
        )
        Column {
            Text(profile.name, style = MaterialTheme.typography.titleMedium)
            Text(
                profile.sign.ifBlank { "这个人很懒,什么都没写" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Lv${profile.level} · ${profile.follower.formatFollower()}粉丝",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Long.formatFollower(): String = when {
    this >= 100_000_000 -> "%.1f亿".format(this / 100_000_000.0)
    this >= 10_000 -> "%.1f万".format(this / 10_000.0)
    else -> toString()
}

@Composable
private fun ArchivesTab(
    state: SpaceArchiveTabState,
    onOrderChanged: (SpaceArchiveOrder) -> Unit,
    onKeywordChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = state.order == SpaceArchiveOrder.Pubdate,
                onClick = { onOrderChanged(SpaceArchiveOrder.Pubdate) },
                label = { Text("最新") },
            )
            FilterChip(
                selected = state.order == SpaceArchiveOrder.Click,
                onClick = { onOrderChanged(SpaceArchiveOrder.Click) },
                label = { Text("最多播放") },
            )
        }
        OutlinedTextField(
            value = state.keyword,
            onValueChange = onKeywordChanged,
            label = { Text("在这个空间内搜索") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                imeAction = androidx.compose.ui.text.input.ImeAction.Search,
            ),
        )
        VideoListTab(
            items = state.items,
            appending = state.appending,
            hasMore = state.hasMore,
            loading = state.loading,
            error = state.error,
            onLoadMore = onLoadMore,
            onVideoClick = onVideoClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CollectionsTab(
    state: SpaceCollectionsTabState,
    onLoadMore: () -> Unit,
    onCollectionClick: (SpaceCollectionItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading && state.items.isEmpty() -> FullScreenLoading(modifier)
        state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onLoadMore, modifier)
        else -> {
            val listState = rememberLazyListState()
            LaunchedEffect(listState, state.hasMore, state.appending) {
                snapshotFlow { listState.layoutInfo }
                    .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
                    .distinctUntilChanged()
                    .filter { (last, total) -> last != null && last >= total - 1 - PrefetchThreshold }
                    .collect { if (state.hasMore && !state.appending) onLoadMore() }
            }
            LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
                itemsIndexed(state.items, key = { _, item -> "${item.isSeason}-${item.id}" }) { _, item ->
                    CollectionRow(item, onClick = { onCollectionClick(item) })
                }
                item(key = "footer") {
                    ListFooter(state.appending, state.hasMore, state.items.isNotEmpty())
                }
            }
        }
    }
}

@Composable
private fun CollectionRow(item: SpaceCollectionItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(item.coverUrl)
                .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(120.dp).aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
        )
        Column {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "${if (item.isSeason) "合集" else "系列"} · 共${item.total}个视频",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CollectionDetailScreen(
    detail: SpaceCollectionDetailState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(detail.collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        VideoListTab(
            items = detail.items,
            appending = detail.appending,
            hasMore = detail.hasMore,
            loading = detail.loading,
            error = detail.error,
            onLoadMore = onLoadMore,
            onVideoClick = onVideoClick,
            modifier = Modifier.padding(padding),
        )
    }
}

private const val PrefetchThreshold = 5

@Composable
private fun VideoListTab(
    items: List<SpaceVideoItem>,
    appending: Boolean,
    hasMore: Boolean,
    loading: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        loading && items.isEmpty() -> FullScreenLoading(modifier)
        error != null && items.isEmpty() -> FullScreenError(error, onLoadMore, modifier)
        else -> {
            val listState = rememberLazyListState()
            LaunchedEffect(listState, hasMore, appending) {
                snapshotFlow { listState.layoutInfo }
                    .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
                    .distinctUntilChanged()
                    .filter { (last, total) -> last != null && last >= total - 1 - PrefetchThreshold }
                    .collect { if (hasMore && !appending) onLoadMore() }
            }
            LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
                itemsIndexed(items, key = { _, item -> item.bvid }) { _, item ->
                    VideoRow(item, onClick = { onVideoClick(item) })
                }
                item(key = "footer") { ListFooter(appending, hasMore, items.isNotEmpty()) }
            }
        }
    }
}

@Composable
private fun VideoRow(item: SpaceVideoItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(item.coverUrl)
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", "https://www.bilibili.com").build())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.durationText.isNotEmpty()) {
                Text(
                    text = item.durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(
                            androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.padding(top = 4.dp))
            Text(
                "${item.playCountText}播放 · ${item.danmakuCountText}弹幕 · ${formatDate(item.publishedAtEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ListFooter(appending: Boolean, hasMore: Boolean, hasItems: Boolean) {
    if (!hasItems) return
    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        when {
            appending -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            !hasMore -> Text(
                "没有更多了",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FullScreenLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun FullScreenError(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.padding(top = 12.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

private val DateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DateFormatter)
