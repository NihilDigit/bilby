package dev.bilby.ui.space

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.FollowState
import dev.bilby.data.RelationRepository
import dev.bilby.data.SpaceArchiveOrder
import dev.bilby.data.SpaceCollectionItem
import dev.bilby.data.SpaceProfile
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceVideoItem
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.SquareCover
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
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

enum class SpaceTab(@param:StringRes val label: Int) {
    Archives(R.string.space_tab_archives),
    Dynamics(R.string.space_tab_dynamics),
    Collections(R.string.space_tab_collections),
}

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
    private val relationRepository: RelationRepository,
) : ViewModel() {

    /**
     * 关注/取关。乐观更新、不重拉,与播放页同一套规矩。
     *
     * 关注态存在 profile 里(由 [loadFollowState] 填),这里改的也是那一份,
     * 不额外维护第二处状态 —— 两份状态迟早对不上。
     */
    fun toggleFollow() {
        val profile = _state.value.profile ?: return
        val current = profile.followState
        if (current == FollowState.Self || current == FollowState.Blocked) return

        val following = current.isFollowing
        val next = if (following) FollowState.None else FollowState.Following
        _state.update { it.copy(profile = profile.copy(followState = next)) }
        viewModelScope.launch {
            val result =
                if (following) relationRepository.unfollow(mid) else relationRepository.follow(mid)
            if (result !is BiliResult.Ok) {
                BiliLog.w("${if (following) "取关" else "关注"}失败: $result")
                _state.update { it.copy(profile = profile) }
            }
        }
    }

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
                is BiliResult.Ok -> {
                    _state.update { it.copy(loading = false, profile = result.value) }
                    // 串在 profile 之后,不并发:并发时 profile 后到就会把查到的关注态盖回默认值。
                    loadFollowState()
                }
                else -> _state.update { it.copy(loading = false, error = result.errorText()) }
            }
        }
    }

    /**
     * 关注态**单独查**,不用 profile 里那份。
     *
     * 这里曾经直接读 `acc/info` 的 relation 字段,结果是关注按钮永远显示"关注" —— 网页端的
     * acc/info 不填这个字段,DTO 拿不到就默认 0,而 0 正好是 FollowState.None,一个缺失被
     * 静默读成了一个确定的答案。PiliPlus 的空间页看着也是读 relation,但它读的是**app 端**
     * 的空间接口(带 app UA 和 app 参数),和这条不是一回事。
     *
     * 用 `x/relation?fid=` —— 播放页一直用的就是它,已经验证过。多一次请求,换一个真值。
     */
    private suspend fun loadFollowState() {
        when (val result = relationRepository.stateOf(mid)) {
            is BiliResult.Ok -> _state.update { state ->
                state.copy(profile = state.profile?.copy(followState = result.value))
            }
            is BiliResult.ApiError -> BiliLog.w("空间页查关注态失败(${result.code}): ${result.message}")
            is BiliResult.Failure -> BiliLog.w("空间页查关注态异常", result.cause)
        }
    }

    private fun BiliResult<*>.errorText(): String = when (this) {
        is BiliResult.ApiError -> "$message($code)"
        is BiliResult.Failure -> cause.message ?: "网络错误"
        is BiliResult.Ok -> ""
    }
}

// ---------------- UI ----------------

/**
 * 个人空间。三个标签是这一页的主要内容分区,直接挂在顶栏下面,所以用 primary tabs
 * (M3:primary tabs 放在 app bar 之下,表示页面的主内容目的地)。
 *
 * 空间是纯拉取式界面,点进来本身带意图,风险为零(DESIGN 2.4)——所以这里可以放搜索、
 * 放排序,不用担心它变成一个刷不完的池子。
 */
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
    onToggleFollow: () -> Unit,
    onListenUp: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val detail = state.collections.detail
    if (detail != null) {
        CollectionDetailScreen(detail, onCollectionDetailBack, onLoadMoreCollectionDetail, onVideoClick, modifier)
        return
    }

    Scaffold(
        modifier = modifier,
        // 标题固定"个人空间":名字归下面的头部区,顶栏只是路牌。
        topBar = { BilbyTopBar(title = stringResource(R.string.space_title), onBack = onBack) },
    ) { insets ->
        Column(modifier = Modifier.fillMaxSize().padding(insets)) {
            state.profile?.let {
                SpaceHeader(it, onToggleFollow = onToggleFollow, onListenUp = onListenUp)
            }

            PrimaryTabRow(selectedTabIndex = state.activeTab.ordinal) {
                SpaceTab.entries.forEach { tab ->
                    Tab(
                        selected = state.activeTab == tab,
                        onClick = { onTabSelected(tab) },
                        text = { Text(stringResource(tab.label)) },
                    )
                }
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
                        emptyText = stringResource(R.string.space_empty_dynamics),
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
}

/**
 * 空间头部。参照 PiliPlus 的 `pages/member/widget/user_info_card.dart`:头像 + 名字 +
 * 一行数据 + 签名,签名单独占整行宽度。
 *
 * 名字放在这里而不是顶栏:顶栏的标题是路牌("个人空间"),头部才是这个人本身。
 * 两处都印名字的话同屏出现两遍,而顶栏那一份还会被截断得更早。
 *
 * **没有头图**。接口层的 `SpaceProfile` 目前不带 `top_photo`,补它要动 `api/dto`,
 * 不在这一轮的边界内 —— 见报告里的"需要接口层配合"。
 */
@Composable
private fun SpaceHeader(
    profile: SpaceProfile,
    onToggleFollow: () -> Unit,
    onListenUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(url = profile.faceUrl, size = Dimens.AvatarLarge)
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.space_level_followers,
                        profile.level,
                        formatCount(profile.follower),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Spacer(Modifier.weight(1f))
            // 听这位 UP 的投稿:队列取自当前投稿列表,和播放页那份队列同源
            // (DESIGN 2.4b:有限且用户显式选定的集合)。
            IconButton(onClick = onListenUp) {
                Icon(
                    Icons.Filled.Headphones,
                    contentDescription = stringResource(R.string.space_listen_up),
                )
            }
            SpaceFollowButton(state = profile.followState, onClick = onToggleFollow)
        }
        // 签名可能很长又基本没信息量,给两行封顶;放在下面一整行是因为它旁边没有头像时
        // 能多放十来个字,而挤在头像右边只剩半行。
        Text(
            text = profile.sign.ifBlank { stringResource(R.string.space_no_sign) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 计数折算。分档除数也是本地化资源:中文按万/亿分档,英文按 K/M,
 * 只翻译单位后缀会让英文差一个量级。
 */
@Composable
private fun formatCount(value: Long): String {
    val large = integerResource(R.integer.count_divisor_large)
    val small = integerResource(R.integer.count_divisor_small)
    return when {
        value >= large -> stringResource(R.string.count_large, value.toDouble() / large)
        value >= small -> stringResource(R.string.count_small, value.toDouble() / small)
        else -> value.toString()
    }
}

/** 「挖存货」的两条路:按时间看最近的,按播放量看代表作(DESIGN 2.4)。 */
private val ArchiveOrders = listOf(
    SpaceArchiveOrder.Pubdate to R.string.space_order_pubdate,
    SpaceArchiveOrder.Click to R.string.space_order_click,
)

/**
 * 投稿页。排序用 segmented button(M3 把"排序元素"明确划给它),
 * 空间内搜索回车才发请求 —— 输入即搜索会让每敲一个字打一次接口。
 */
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
        Column(
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ArchiveOrders.forEachIndexed { index, pair ->
                    SegmentedButton(
                        selected = state.order == pair.first,
                        onClick = { onOrderChanged(pair.first) },
                        shape = SegmentedButtonDefaults.itemShape(index, ArchiveOrders.size),
                        label = { Text(stringResource(pair.second)) },
                    )
                }
            }
            SearchField(
                value = state.keyword,
                onValueChange = onKeywordChanged,
                placeholder = stringResource(R.string.space_search_hint),
                onSearch = onSearch,
            )
        }
        VideoListTab(
            items = state.items,
            appending = state.appending,
            hasMore = state.hasMore,
            loading = state.loading,
            error = state.error,
            emptyText = stringResource(
                if (state.keyword.isBlank()) {
                    R.string.space_empty_archives
                } else {
                    R.string.space_empty_archives_search
                },
            ),
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
                if (state.items.isEmpty()) {
                    item(key = "empty") { EmptyState(stringResource(R.string.space_empty_collections)) }
                }
                items(state.items, key = { "${it.isSeason}-${it.id}" }) { item ->
                    CollectionRow(item, onClick = { onCollectionClick(item) })
                }
                item(key = "footer") {
                    ListFooter(state.appending, state.hasMore, state.items.isNotEmpty())
                }
            }
        }
    }
}

/**
 * 合集用方形封面,和视频行的 16:9 拉开 —— 一眼就能分出「这是一组视频」和「这是一个视频」,
 * 不用先去读下面那行小字。
 */
@Composable
private fun CollectionRow(item: SpaceCollectionItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SquareCover(url = item.coverUrl, size = CollectionCoverSize)
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = stringResource(
                    R.string.space_collection_meta,
                    stringResource(
                        if (item.isSeason) {
                            R.string.space_collection_season
                        } else {
                            R.string.space_collection_series
                        },
                    ),
                    item.total,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val CollectionCoverSize = 72.dp

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
        topBar = { BilbyTopBar(title = detail.collection.name, onBack = onBack) },
    ) { padding ->
        VideoListTab(
            items = detail.items,
            appending = detail.appending,
            hasMore = detail.hasMore,
            loading = detail.loading,
            error = detail.error,
            emptyText = stringResource(R.string.space_empty_collection_detail),
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
    emptyText: String,
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
                if (items.isEmpty()) {
                    item(key = "empty") { EmptyState(emptyText) }
                }
                items(items, key = { it.bvid }) { item ->
                    // 整页都是同一个 UP,不重复印 UP 名(upName 留空)。
                    VideoRow(
                        item = VideoRowUi(
                            title = item.title,
                            coverUrl = item.coverUrl,
                            durationText = item.durationText,
                            dateText = formatDate(item.publishedAtEpochSeconds),
                            playText = item.playCountText,
                            danmakuText = item.danmakuCountText,
                        ),
                        onClick = { onVideoClick(item) },
                    )
                }
                item(key = "footer") { ListFooter(appending, hasMore, items.isNotEmpty()) }
            }
        }
    }
}

private val DateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DateFormatter)

/** 与播放页的关注按钮同一套字面与强调规则,见 VideoTabs 里的那份说明。 */
@Composable
private fun SpaceFollowButton(state: FollowState, onClick: () -> Unit) {
    when (state) {
        FollowState.Self, FollowState.Blocked -> Unit
        FollowState.None -> Button(onClick = onClick) { Text(stringResource(R.string.follow_none)) }
        FollowState.Following -> OutlinedButton(onClick = onClick) { Text(stringResource(R.string.follow_following)) }
        FollowState.Mutual -> OutlinedButton(onClick = onClick) { Text(stringResource(R.string.follow_mutual)) }
    }
}
