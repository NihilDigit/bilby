package dev.bilby.ui.space

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.ui.dynamicTypeLabel
import dev.bilby.ui.appendDistinctBy
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.ShareLink
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.api.BiliResult
import dev.bilby.data.FollowState
import dev.bilby.data.RelationRepository
import dev.bilby.data.SpaceArchiveOrder
import dev.bilby.data.SpaceCollectionItem
import dev.bilby.data.SpaceProfile
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceDynamicItem
import dev.bilby.data.SpaceVideoItem
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.SortRow
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.ImageViewer
import dev.bilby.ui.components.SquareCover
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.theme.Breakpoints
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
    /** null = 尚未取合集列表；false = 已确认该 UP 没有合集/系列。 */
    val collectionsAvailable: Boolean? = null,
    val refreshing: Boolean = false,
    val archives: SpaceArchiveTabState = SpaceArchiveTabState(),
    val dynamics: SpaceListTabState = SpaceListTabState(),
    val collections: SpaceCollectionsTabState = SpaceCollectionsTabState(),
)

data class SpaceArchiveTabState(
    val order: SpaceArchiveOrder = SpaceArchiveOrder.Pubdate,
    /** 输入框里的字。**还没生效**,回车/点确认才会被抄进 [appliedKeyword]。 */
    val keyword: String = "",
    /**
     * 列表现在反映的是哪个关键词。与 [keyword] 分开是因为响应回来时要判断"这还是当初那次
     * 筛选吗",而输入框每敲一个字就变一次 —— 拿它当判据的话,翻页途中随手打个字就会把那一页
     * 丢掉。
     */
    val appliedKeyword: String = "",
    val items: List<SpaceVideoItem> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

data class SpaceListTabState(
    val items: List<SpaceDynamicItem> = emptyList(),
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
    /**
     * **只表示"有请求在飞",不表示"该画转圈了"。** 这两件事合用一个字段时,默认值必须是
     * false:[loadMoreCollectionDetail] 拿它当重入闸,而 [openCollection] 是先建状态再拉
     * 第一页 —— 默认 true 会让第一页被自己的闸挡掉,一个请求都不发,合集详情永远停在初始
     * 状态。表现是"点进合集打不开",且因为根本没发请求,日志里一个字都没有。
     */
    val loading: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
)

/**
 * 一次投稿请求的身份。响应回来时用它回答"这份结果还属于列表现在的样子吗"。
 *
 * 三个字段就是接口的全部可变入参:排序、生效中的关键词、页号。再加一个请求代次,处理刷新
 * 时同一页会重发的情况 —— 这时旧请求和新请求的三个入参完全一样,只靠入参无法区分。
 */
private data class ArchiveRequest(
    val order: SpaceArchiveOrder,
    val keyword: String,
    val page: Int,
    val generation: Long,
) {
    fun matches(state: SpaceArchiveTabState): Boolean =
        state.order == order && state.appliedKeyword == keyword && state.page == page
}

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

    // 刷新/切筛选会重置分页游标。代次让迟到的旧首页即使和新首页参数相同也不能写回。
    private var archivesGeneration = 0L
    private var dynamicsGeneration = 0L
    private var collectionsGeneration = 0L
    private var collectionDetailGeneration = 0L

    init {
        loadProfile()
        loadMoreArchives()
        loadMoreCollections()
    }

    fun retry() {
        if (_state.value.profile == null) loadProfile()
        when (_state.value.activeTab) {
            SpaceTab.Archives -> if (_state.value.archives.items.isEmpty()) loadMoreArchives()
            SpaceTab.Dynamics -> if (_state.value.dynamics.items.isEmpty()) loadMoreDynamics()
            SpaceTab.Collections -> if (_state.value.collections.items.isEmpty()) loadMoreCollections()
        }
    }

    fun refresh() {
        _state.update { it.copy(refreshing = true) }
        loadProfile()
        val current = _state.value
        when {
            current.collections.detail != null -> {
                collectionDetailGeneration++
                _state.update {
                    it.copy(
                        collections = it.collections.copy(
                            detail = it.collections.detail?.copy(
                                page = 1,
                                loading = false, appending = false, hasMore = true, error = null,
                            ),
                        ),
                    )
                }
                loadMoreCollectionDetail(replace = true)
            }
            current.activeTab == SpaceTab.Archives -> {
                archivesGeneration++
                _state.update {
                    it.copy(archives = it.archives.copy(
                        page = 1,
                        loading = false, appending = false, hasMore = true, error = null,
                    ))
                }
                loadMoreArchives(replace = true)
            }
            current.activeTab == SpaceTab.Dynamics -> {
                dynamicsGeneration++
                _state.update {
                    it.copy(dynamics = it.dynamics.copy(
                        nextOffset = null,
                        loading = false, appending = false, hasMore = true, error = null,
                    ))
                }
                loadMoreDynamics(replace = true)
            }
            else -> {
                collectionsGeneration++
                _state.update {
                    it.copy(collections = it.collections.copy(
                        page = 1,
                        loading = false, appending = false, hasMore = true, error = null,
                    ))
                }
                loadMoreCollections(replace = true)
            }
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
        archivesGeneration++
        _state.update {
            it.copy(
                archives = SpaceArchiveTabState(
                    order = order,
                    keyword = it.archives.keyword,
                    appliedKeyword = it.archives.appliedKeyword,
                ),
            )
        }
        loadMoreArchives()
    }

    fun onArchiveKeywordChanged(keyword: String) {
        _state.update { it.copy(archives = it.archives.copy(keyword = keyword)) }
    }

    /**
     * 空间内搜索复用投稿接口(notes 1.3 节),回车/点确认时才真正发请求,不做输入即请求。
     *
     * loading/appending 也要一起清:上一次翻页可能还在飞,不清的话 [loadMoreArchives] 的重入
     * 闸会把这次搜索整个挡掉 —— 一个请求都不发,列表停在刚被清空的状态。旧那次的响应由请求
     * 身份挡下,不会写回来。
     */
    fun onArchiveSearch() {
        archivesGeneration++
        _state.update {
            it.copy(
                archives = it.archives.copy(
                    appliedKeyword = it.archives.keyword,
                    items = emptyList(), page = 1, total = 0,
                    loading = false, appending = false, hasMore = true, error = null,
                ),
            )
        }
        loadMoreArchives()
    }

    /**
     * 投稿分页。两条纪律,都是线上那个"投稿列表看不到最新几十条"的成因:
     *
     * **一、游标只在这一页真的带回了东西时才前进。** 原先无论响应里有什么都写
     * `page = current.page + 1`,于是一页返回空列表(HTTP 200、`vlist` 为空,空间投稿接口被
     * 短时风控挡下时就是这个样子)之后,那一页再也没有机会被请求第二次:列表从第二页开始,
     * 最新的三十条整段消失,而日志里一个失败都没有。从播放页进 UP 空间最容易撞上 —— 队列
     * 补全刚刚为同一个 mid 连打了七八次同一个接口。现在空页当作到头,用户看到"没有更多",
     * 下拉刷新能重来。
     *
     * **二、响应要先认领自己那次请求。** 排序、关键词、页号在飞行途中都可能已经换了(下拉
     * 刷新、切排序、空间内搜索都不取消旧请求)。原先拿发请求前的快照当基底 `copy`,等于把
     * 用户刚选的排序、刚输的关键词一起打回旧值,还会把属于新请求的 loading 标志清掉。
     * 现在对不上就整份丢掉。
     */
    fun loadMoreArchives(replace: Boolean = false) {
        val current = _state.value.archives
        if (current.loading || current.appending || !current.hasMore) return
        val firstPage = replace || current.items.isEmpty()
        val requested = ArchiveRequest(
            order = current.order,
            keyword = current.appliedKeyword,
            page = if (replace) 1 else current.page,
            generation = archivesGeneration,
        )
        _state.update {
            it.copy(archives = it.archives.copy(loading = firstPage, appending = !firstPage, error = null))
        }
        viewModelScope.launch {
            val result = repository.loadArchives(mid, requested.page, requested.order, requested.keyword)
            _state.update { state ->
                val archives = state.archives
                if (requested.generation != archivesGeneration || !requested.matches(archives)) return@update state
                when (result) {
                    is BiliResult.Ok -> {
                        val pageItems = result.value.items
                        val merged = if (replace) {
                            pageItems.distinctBy { it.bvid }
                        } else {
                            archives.items.appendDistinctBy(pageItems) { v -> v.bvid }
                        }
                        state.copy(
                            refreshing = false,
                            archives = archives.copy(
                                items = merged,
                                // 从请求本身推进,不从状态推进:同一页被请求两次(刷新撞上在飞的
                                // 首页)时,两份响应都写 `state.page + 1` 会把游标推到第三页。
                                page = if (pageItems.isEmpty()) archives.page else requested.page + 1,
                                total = result.value.total,
                                loading = false,
                                appending = false,
                                hasMore = pageItems.isNotEmpty() && merged.size < result.value.total,
                            ),
                        )
                    }

                    else -> state.copy(
                        refreshing = false,
                        archives = archives.copy(
                            loading = false,
                            appending = false,
                            error = result.errorText(),
                        ),
                    )
                }
            }
        }
    }

    /**
     * 空间动态分页。同样按请求身份认领响应,但**不套"空页即到头"那条判据**:
     *
     * 动态的游标由服务端给(`nextOffset`),不是本地算出来的,所以没有"游标白白前进"这回事;
     * 而这里的一页可能真的是空的 —— `toDynamicItem` 会丢掉不认识的动态类型,整页都是转发或
     * 直播预约时过滤完就什么都不剩。把空页当到头会在这种页上停住,而服务端明明说了 hasMore。
     */
    fun loadMoreDynamics(replace: Boolean = false) {
        val current = _state.value.dynamics
        if (current.loading || current.appending || !current.hasMore) return
        val firstPage = replace || current.items.isEmpty()
        val requestedOffset = if (replace) null else current.nextOffset
        val requestedGeneration = dynamicsGeneration
        _state.update {
            it.copy(dynamics = it.dynamics.copy(loading = firstPage, appending = !firstPage, error = null))
        }
        viewModelScope.launch {
            val result = repository.loadDynamics(mid, requestedOffset)
            _state.update { state ->
                val dynamics = state.dynamics
                if (requestedGeneration != dynamicsGeneration || dynamics.nextOffset != requestedOffset) return@update state
                when (result) {
                    is BiliResult.Ok -> state.copy(
                        refreshing = false,
                        dynamics = dynamics.copy(
                            items = if (replace) {
                                result.value.items.distinctBy { it.key }
                            } else {
                                dynamics.items.appendDistinctBy(result.value.items) { d -> d.key }
                            },
                            nextOffset = result.value.nextOffset,
                            loading = false,
                            appending = false,
                            hasMore = result.value.hasMore && result.value.nextOffset != null,
                        ),
                    )

                    else -> state.copy(
                        refreshing = false,
                        dynamics = dynamics.copy(
                            loading = false,
                            appending = false,
                            error = result.errorText(),
                        ),
                    )
                }
            }
        }
    }

    /** 与投稿同一套纪律:空页不推进游标,响应先认领自己那次请求。 */
    fun loadMoreCollections(replace: Boolean = false) {
        val current = _state.value.collections
        if (current.loading || current.appending || !current.hasMore) return
        val firstPage = replace || current.items.isEmpty()
        val requestedPage = if (replace) 1 else current.page
        val requestedGeneration = collectionsGeneration
        _state.update {
            it.copy(collections = it.collections.copy(loading = firstPage, appending = !firstPage, error = null))
        }
        viewModelScope.launch {
            val result = repository.loadCollections(mid, requestedPage)
            _state.update { state ->
                val collections = state.collections
                if (requestedGeneration != collectionsGeneration || collections.page != requestedPage) return@update state
                when (result) {
                    is BiliResult.Ok -> {
                        val pageItems = result.value.items
                        val merged = if (replace) {
                            pageItems.distinctBy { c -> "${c.isSeason}-${c.id}" }
                        } else {
                            collections.items.appendDistinctBy(pageItems) { c -> "${c.isSeason}-${c.id}" }
                        }
                        state.copy(
                            refreshing = false,
                            // 不再有"发现是空的就把用户从合集 tab 弹回投稿 tab"那一段:tab 栏现在
                            // 要等这次探测回来才渲染(见 SpaceScreen),用户根本没机会点进一个
                            // 不存在的 tab,那段强制切换也就没有触发条件了。
                            collectionsAvailable = if (requestedPage == 1) {
                                result.value.total > 0 || merged.isNotEmpty()
                            } else {
                                state.collectionsAvailable
                            },
                            collections = collections.copy(
                                items = merged,
                                page = if (pageItems.isEmpty()) collections.page else requestedPage + 1,
                                total = result.value.total,
                                loading = false,
                                appending = false,
                                hasMore = pageItems.isNotEmpty() && merged.size < result.value.total,
                            ),
                        )
                    }

                    // 探测失败按"有合集"算,tab 照常显示。宁可留一个点进去报错能重试的 tab,
                    // 也不要因为一次网络抖动就把这个 UP 的合集整个藏起来 —— 藏起来之后用户
                    // 没有任何线索知道它存在过。
                    else -> state.copy(
                        refreshing = false,
                        collectionsAvailable = state.collectionsAvailable ?: true,
                        collections = collections.copy(
                            loading = false,
                            appending = false,
                            error = result.errorText(),
                        ),
                    )
                }
            }
        }
    }

    fun openCollection(item: SpaceCollectionItem) {
        collectionDetailGeneration++
        _state.update { it.copy(collections = it.collections.copy(detail = SpaceCollectionDetailState(item))) }
        loadMoreCollectionDetail()
    }

    fun closeCollectionDetail() {
        collectionDetailGeneration++
        _state.update { it.copy(collections = it.collections.copy(detail = null)) }
    }

    /**
     * 合集目录分页。除了那两条纪律,这里的身份校验还多挡一件事:**目录已经被关掉时,迟到的
     * 响应不能把它重新支起来。** 原先响应无条件写 `detail = detail.copy(...)`,用户点返回退出
     * 目录后那一页回来,抽屉会自己弹回来。
     */
    fun loadMoreCollectionDetail(replace: Boolean = false) {
        val detail = _state.value.collections.detail ?: return
        if (detail.loading || detail.appending || !detail.hasMore) return
        val firstPage = replace || detail.items.isEmpty()
        val requestedCollection = detail.collection
        val requestedPage = if (replace) 1 else detail.page
        val requestedGeneration = collectionDetailGeneration
        _state.update {
            it.copy(
                collections = it.collections.copy(
                    detail = detail.copy(loading = firstPage, appending = !firstPage, error = null),
                ),
            )
        }
        viewModelScope.launch {
            val result = repository.loadCollectionDetail(mid, requestedCollection, requestedPage)
            _state.update { state ->
                val current = state.collections.detail ?: return@update state
                if (
                    requestedGeneration != collectionDetailGeneration ||
                    current.collection != requestedCollection ||
                    current.page != requestedPage
                ) {
                    return@update state
                }
                when (result) {
                    is BiliResult.Ok -> {
                        val pageItems = result.value.items
                        val merged = if (replace) {
                            pageItems.distinctBy { it.bvid }
                        } else {
                            current.items.appendDistinctBy(pageItems) { v -> v.bvid }
                        }
                        state.copy(
                            refreshing = false,
                            collections = state.collections.copy(
                                detail = current.copy(
                                    items = merged,
                                    page = if (pageItems.isEmpty()) current.page else requestedPage + 1,
                                    total = result.value.total,
                                    loading = false,
                                    appending = false,
                                    hasMore = pageItems.isNotEmpty() && merged.size < result.value.total,
                                ),
                            ),
                        )
                    }

                    else -> state.copy(
                        refreshing = false,
                        collections = state.collections.copy(
                            detail = current.copy(
                                loading = false,
                                appending = false,
                                error = result.errorText(),
                            ),
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
                // 这里不碰 refreshing:下拉刷新会同时发 profile 和当前 tab 两个请求,谁都清一次
                // 的话先回来的那个就把指示器关掉了,而列表还在转。指示器跟着列表走。
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
    onLiveClick: (Long) -> Unit,
    onToggleFollow: () -> Unit,
    onListenUp: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    /** 分享要给出 `space.bilibili.com/<mid>`,而 mid 不在 [state] 里。 */
    mid: Long,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val detail = state.collections.detail
    if (detail != null) {
        CollectionDetailScreen(
            detail, onCollectionDetailBack, onLoadMoreCollectionDetail, onVideoClick,
            onRefresh, state.refreshing, modifier,
        )
        return
    }

    // 空间内搜索是页内的次要动作,不是空间内容本身,所以展开态只是本页的 UI 状态,
    // 不进 ViewModel —— 离开页面就该忘掉,不需要记住"上次展开过"。
    var archiveSearchExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        // 标题固定"个人空间":名字归下面的头部区,顶栏只是路牌。
        topBar = {
            BilbyTopBar(
                title = stringResource(R.string.space_title),
                onBack = onBack,
                actions = {
                    // 只在投稿页给这个入口:动态、合集两个标签没有可搜索的内容。
                    if (state.activeTab == SpaceTab.Archives) {
                        IconButton(
                            onClick = {
                                // 收起即清空:图标只有两个诚实的状态可言 —— 展开 = 可能在筛,
                                // 收起 = 一定没筛。只隐藏输入框而留着关键词的话,列表会在看不见
                                // 筛选条件的情况下继续被筛,读起来像"投稿莫名其妙变少了"。
                                //
                                // 清输入框和重拉列表是两件事,判据也各是各的:输入框里有字才要
                                // 清,筛选**真的生效过**才要重拉。合成一个条件时,"打了字没按
                                // 回车就收起"会白白重拉一次全量,而"把字删空但没按回车再收起"
                                // 反而留着上一次的筛选不动 —— 正是上面说的那种看不见的筛。
                                if (archiveSearchExpanded) {
                                    if (state.archives.keyword.isNotEmpty()) onArchiveKeywordChanged("")
                                    if (state.archives.appliedKeyword.isNotEmpty()) onArchiveSearch()
                                }
                                archiveSearchExpanded = !archiveSearchExpanded
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = stringResource(R.string.space_search_action),
                            )
                        }
                    }
                    // 分享这位 UP 的主页。放顶栏而不是头部区:头部区那一行是"对这个人的
                    // 动作"(关注、听他的投稿),分享的是页面本身。
                    IconButton(onClick = { ShareLink.space(context, mid, state.profile?.name.orEmpty()) }) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = stringResource(R.string.action_share),
                        )
                    }
                },
            )
        },
    ) { insets ->
        // **tab 栏等合集探测回来才画。** 先画三个再抽掉一个的话,栏目宽度会重新分配、
        // 下面整块内容跟着上跳,而这一切发生在用户已经开始看页面之后。合集探测和投稿
        // 第一页是并发的,等的是两者里慢的那个,通常不额外多花时间。
        val collectionsKnown = state.collectionsAvailable != null
        val tabs = SpaceTab.entries.filter { tab ->
            tab != SpaceTab.Collections || state.collectionsAvailable == true
        }

        val header: @Composable (Modifier) -> Unit = { paneModifier ->
            state.profile?.let {
                SpaceHeader(
                    it,
                    canListen = state.archives.items.isNotEmpty(),
                    onToggleFollow = onToggleFollow,
                    onListenUp = onListenUp,
                    onLiveClick = onLiveClick,
                    modifier = paneModifier,
                )
            }
        }

        val tabsAndContent: @Composable ColumnScope.() -> Unit = {
            if (collectionsKnown) {
                val selectedTabIndex = tabs.indexOf(state.activeTab).coerceAtLeast(0)
                PrimaryTabRow(selectedTabIndex = selectedTabIndex) {
                    tabs.forEach { tab ->
                        Tab(
                            selected = state.activeTab == tab,
                            onClick = { onTabSelected(tab) },
                            text = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f),
            ) {
                when {
                    state.loading && state.profile == null -> FullScreenLoading()
                    state.error != null && state.profile == null -> FullScreenError(state.error, onRetry)
                    // tab 栏还没画出来,内容先不画:否则内容会先顶在页头下面,等 tab 栏出现
                    // 再被推下去一截。
                    !collectionsKnown -> FullScreenLoading()
                    else -> when (state.activeTab) {
                        SpaceTab.Archives -> ArchivesTab(
                            state.archives,
                            searchExpanded = archiveSearchExpanded,
                            onOrderChanged = onArchiveOrderChanged,
                            onKeywordChanged = onArchiveKeywordChanged,
                            onSearch = onArchiveSearch,
                            onLoadMore = onLoadMoreArchives,
                            onVideoClick = onVideoClick,
                        )

                        SpaceTab.Dynamics -> DynamicListTab(
                            state = state.dynamics,
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

        if (rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)) {
            /*
             * **宽屏把头部挪到旁边,而不是钉在上面。**
             *
             * 这一页有两种内容:"这个人是谁"和"他发了什么"。canonical-examples 页对
             * supporting pane 的定义正好是这个分工 —— 主区放主内容并占约三分之二,次区放
             * 支持性内容。头部横钉在顶上时它两头都不讨好:横向被拉成一条稀疏的长行,
             * 纵向又从列表里永久扣掉一块高度,而列表才是这一页要看的东西。
             *
             * 次区自己能滚:签名可以很长,而它不该把关注按钮顶出屏幕。
             */
            Row(
                modifier = Modifier.fillMaxSize().padding(insets),
            ) {
                header(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                )
                Column(
                    modifier = Modifier
                        .weight(2f)
                        .fillMaxHeight(),
                    content = tabsAndContent,
                )
            }
        } else {
            AdaptiveContent(
                modifier = Modifier.fillMaxSize().padding(insets),
                maxWidth = Breakpoints.ReadableWidth,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    header(Modifier)
                    tabsAndContent()
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
    canListen: Boolean,
    onToggleFollow: () -> Unit,
    onListenUp: () -> Unit,
    onLiveClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(url = profile.faceUrl, size = Dimens.AvatarLarge)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    // LV 换成真徽章而不是拼进字符串里的"Lv5" ——
                    // 空间接口没有硬核会员字段,不为这一个装饰性标记单独换接口,传 false。
                    LevelBadge(level = profile.level, senior = false, height = Dimens.LevelBadgeHeight)
                    Text(
                        text = stringResource(R.string.space_followers, formatCount(profile.follower)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            SpaceHeaderActions(
                followState = profile.followState,
                canListen = canListen,
                onToggleFollow = onToggleFollow,
                onListenUp = onListenUp,
            )
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

        // 正在直播时才出现,而且只出现在这里 —— 直播间的唯一入口是"我点了这个人",
        // 不是一个可以浏览的列表(DESIGN 1.1)。
        profile.liveRoom?.let { live ->
            Surface(
                onClick = { onLiveClick(live.roomId) },
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(Spacing.Cozy),
                ) {
                    Text(
                        text = stringResource(R.string.space_live_now),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = live.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = Spacing.Tight),
                    )
                    Text(
                        text = stringResource(R.string.live_online, live.online),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpaceHeaderActions(
    followState: FollowState,
    canListen: Boolean,
    onToggleFollow: () -> Unit,
    onListenUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 听这位 UP 的投稿:队列取自当前投稿列表,和播放页那份队列同源
        // (DESIGN 2.4b:有限且用户显式选定的集合)。
        IconButton(onClick = onListenUp, enabled = canListen) {
            Icon(
                Icons.Filled.Headphones,
                contentDescription = stringResource(R.string.space_listen_up),
            )
        }
        SpaceFollowButton(state = followState, onClick = onToggleFollow)
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
 * 投稿页。排序用 [SortRow](风格指南 §2.1),空间内搜索回车才发请求 —— 输入即搜索会让
 * 每敲一个字打一次接口。
 *
 * 搜索输入框不再常驻:顶栏的搜索图标(见 [SpaceScreen])才是入口,点开才展开这个输入框,
 * 收起后连排序都不用跟它分宽度 —— 之前两个占满宽度的控件叠在一起,视觉重量压过了下面
 * 的投稿列表。
 */
@Composable
private fun ArchivesTab(
    state: SpaceArchiveTabState,
    searchExpanded: Boolean,
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
            SortRow(
                options = ArchiveOrders,
                selected = state.order,
                onSelect = onOrderChanged,
            )
            if (searchExpanded) {
                // 图标的意义是省版面,不是多加一次点击 —— 展开完还要再点一下输入框,
                // 省下的成本就还回去了。keyboard.show() 是保险:某些机型上 requestFocus
                // 不是用户手势触发的直接点击,系统不一定会自动弹键盘。
                val focusRequester = remember { FocusRequester() }
                val keyboard = LocalSoftwareKeyboardController.current
                LaunchedEffect(Unit) {
                    focusRequester.requestFocus()
                    keyboard?.show()
                }
                SearchField(
                    value = state.keyword,
                    onValueChange = onKeywordChanged,
                    placeholder = stringResource(R.string.space_search_hint),
                    focusRequester = focusRequester,
                    onSearch = onSearch,
                )
            }
        }
        VideoListTab(
            items = state.items,
            appending = state.appending,
            hasMore = state.hasMore,
            loading = state.loading,
            error = state.error,
            // 空是因为**生效中**的那个关键词没搜到东西,不是因为输入框里现在有什么字。
            emptyText = stringResource(
                if (state.appliedKeyword.isBlank()) {
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
                    ListFooter(
                        appending = state.appending,
                        hasMore = state.hasMore,
                        hasItems = state.items.isNotEmpty(),
                        error = state.error,
                        onRetry = onLoadMore,
                    )
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
    ListItem(
        headlineContent = {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
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
        },
        leadingContent = { SquareCover(url = item.coverUrl, size = CollectionCoverSize) },
        modifier = modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
    )
}

private val CollectionCoverSize = 72.dp

@Composable
private fun CollectionDetailScreen(
    detail: SpaceCollectionDetailState,
    onBack: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { BilbyTopBar(title = detail.collection.name, onBack = onBack) },
    ) { padding ->
        AdaptiveContent(
            modifier = Modifier.fillMaxSize().padding(padding),
            maxWidth = Breakpoints.ReadableWidth,
        ) {
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                VideoListTab(
                    items = detail.items,
                    appending = detail.appending,
                    hasMore = detail.hasMore,
                    loading = detail.loading,
                    error = detail.error,
                    emptyText = stringResource(R.string.space_empty_collection_detail),
                    onLoadMore = onLoadMore,
                    onVideoClick = onVideoClick,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private const val PrefetchThreshold = 5

@Composable
private fun DynamicListTab(
    state: SpaceListTabState,
    onLoadMore: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
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
                    item(key = "empty") { EmptyState(stringResource(R.string.space_empty_dynamics)) }
                }
                itemsIndexed(state.items, key = { _, item -> item.key }) { index, dynamic ->
                    if (index > 0) HorizontalDivider()
                    DynamicRow(dynamic = dynamic, onVideoClick = onVideoClick)
                }
                item(key = "footer") {
                    ListFooter(
                        appending = state.appending,
                        hasMore = state.hasMore,
                        hasItems = state.items.isNotEmpty(),
                        error = state.error,
                        onRetry = onLoadMore,
                    )
                }
            }
        }
    }
}

/**
 * 一条动态。**五种形态走同一个入口**,因为转发要把被转发的那条原样嵌进来 —— 分散成五个
 * 调用点的话,嵌套那一层就得再挑一遍类型,于是同一套判断会有两份。
 *
 * [nested] 为真时是"被转发的那一条":收窄一档、不再画自己的日期(外层已经有了),
 * 也不再允许继续嵌套 —— B 站的转发链最长就是两层,原动态本身若是转发,展示的也是它的正文。
 */
@Composable
private fun DynamicRow(
    dynamic: SpaceDynamicItem,
    onVideoClick: (SpaceVideoItem) -> Unit,
    nested: Boolean = false,
) {
    val context = LocalContext.current
    when (dynamic) {
        is SpaceDynamicItem.Video -> {
            val item = dynamic.item
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

        is SpaceDynamicItem.Text -> DynamicTextBlock(
            label = stringResource(dynamicTypeLabel(dynamic.type)),
            text = dynamic.text,
            dateText = formatDate(dynamic.publishedAtEpochSeconds).takeIf { !nested },
        )

        is SpaceDynamicItem.Draw -> Column(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = Spacing.Comfortable,
                vertical = Spacing.Cozy,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            if (dynamic.text.isNotEmpty()) {
                Text(dynamic.text, style = MaterialTheme.typography.bodyLarge)
            }
            DynamicImageGrid(dynamic.images)
            if (!nested) {
                Text(
                    formatDate(dynamic.publishedAtEpochSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        is SpaceDynamicItem.Article -> ListItem(
            overlineContent = { Text(stringResource(R.string.dynamic_type_article)) },
            headlineContent = {
                Text(dynamic.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = if (dynamic.summary.isNotEmpty()) {
                {
                    Text(
                        text = dynamic.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                null
            },
            leadingContent = if (dynamic.coverUrl.isNotEmpty()) {
                { SquareCover(url = dynamic.coverUrl, size = CollectionCoverSize) }
            } else {
                null
            },
            // **正文还没有站内阅读器**,所以先交给浏览器。专栏正文不在动态接口里,要另外一次
            // 请求并渲染一整套富文本节点;在那之前跳出去至少是能读到的,而画一个点不开的卡片
            // 不是。
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                if (dynamic.url.isNotEmpty()) ShareLink.openInBrowser(context, dynamic.url)
            },
        )

        is SpaceDynamicItem.Forward -> Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Tight),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            DynamicTextBlock(
                label = stringResource(R.string.dynamic_type_forward),
                text = dynamic.text,
                dateText = formatDate(dynamic.publishedAtEpochSeconds),
            )
            // 被转发的那条装进一个容器里,和转发者说的话分开。**源没了也要画**,
            // 否则这条动态看起来像转发者对着空气说话。
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Comfortable),
            ) {
                val origin = dynamic.origin
                if (origin == null) {
                    Text(
                        text = dynamic.originTips.ifEmpty { stringResource(R.string.dynamic_origin_gone) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(Spacing.Cozy),
                    )
                } else {
                    DynamicRow(dynamic = origin, onVideoClick = onVideoClick, nested = true)
                }
            }
        }
    }
}

@Composable
private fun DynamicTextBlock(label: String, text: String, dateText: String?) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = Spacing.Comfortable,
            vertical = Spacing.Cozy,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        if (text.isNotEmpty()) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
        dateText?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.Tight),
            )
        }
    }
}

/**
 * 图文动态的配图。**和评论区那个网格是同一套规矩**(单张给宽一点、多张等分方格、点开进
 * [ImageViewer] 并能左右翻),但没有抽成共用组件:评论那份的列宽判断绑着评论行的缩进,
 * 抽出来要先把两处的边距参数化,收益不抵读起来多绕的那一层。真要合并,先合边距。
 */
@Composable
private fun DynamicImageGrid(images: List<String>) {
    var viewerIndex by remember(images) { mutableStateOf<Int?>(null) }
    val columns = if (images.size == 2 || images.size == 4) 2 else 3

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
        images.withIndex().chunked(columns).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { (index, url) ->
                    BiliAsyncImage(
                        url = url,
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable(role = Role.Button) { viewerIndex = index },
                    )
                }
                // 最后一行不满时补空位,否则两张图会被拉宽到占满整行。
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }

    viewerIndex?.let { index ->
        ImageViewer(urls = images, initialIndex = index, onDismiss = { viewerIndex = null })
    }
}

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
                item(key = "footer") {
                    ListFooter(
                        appending = appending,
                        hasMore = hasMore,
                        hasItems = items.isNotEmpty(),
                        error = error,
                        onRetry = onLoadMore,
                    )
                }
            }
        }
    }
}

private val DateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DateFormatter)

/**
 * 与播放页的关注按钮同一套字面与强调规则,见 VideoTabs 里的那份说明。
 * 取关二次确认同样复用那边的判据和字符串:关注可逆不用确认,取关会丢关系要确认,
 * 确认放在 `onClick`(乐观更新 + 发请求)之前。
 */
@Composable
private fun SpaceFollowButton(state: FollowState, onClick: () -> Unit) {
    var confirmingUnfollow by remember { mutableStateOf(false) }
    when (state) {
        FollowState.Self, FollowState.Blocked -> Unit
        FollowState.None -> Button(onClick = onClick) { Text(stringResource(R.string.follow_none)) }
        FollowState.Following -> OutlinedButton(onClick = { confirmingUnfollow = true }) {
            Text(stringResource(R.string.follow_following))
        }
        FollowState.Mutual -> OutlinedButton(onClick = { confirmingUnfollow = true }) {
            Text(stringResource(R.string.follow_mutual))
        }
    }
    if (confirmingUnfollow) {
        AlertDialog(
            onDismissRequest = { confirmingUnfollow = false },
            title = { Text(stringResource(R.string.follow_unfollow_confirm_title)) },
            text = { Text(stringResource(R.string.follow_unfollow_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingUnfollow = false
                    onClick()
                }) { Text(stringResource(R.string.follow_unfollow_confirm_title)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingUnfollow = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
