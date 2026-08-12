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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.ui.dynamic.DynamicAction
import dev.bilby.ui.dynamic.DynamicCardView
import dev.bilby.ui.appendDistinctBy
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.ShareLink
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.api.BiliResult
import dev.bilby.data.FollowGroup
import dev.bilby.data.FollowRepository
import dev.bilby.data.FollowState
import dev.bilby.data.UpBrief
import dev.bilby.data.RelationRepository
import dev.bilby.data.SpaceArchiveOrder
import dev.bilby.data.SpaceCollectionItem
import dev.bilby.data.SpaceProfile
import dev.bilby.data.DynamicRepository
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceDynamicItem
import dev.bilby.data.SpaceVideoItem
import dev.bilby.data.model.DynamicAdditional
import dev.bilby.data.model.DynamicCard
import dev.bilby.ui.follow.BlockConfirmDialog
import dev.bilby.ui.follow.GroupPickerController
import dev.bilby.ui.follow.GroupPickerSheet
import dev.bilby.ui.follow.GroupPickerState
import dev.bilby.ui.components.FollowButton
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.LivePulse
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.SortRow
import dev.bilby.ui.components.SquareCover
import dev.bilby.ui.components.collapsingHeader
import dev.bilby.ui.components.rememberCollapsingHeaderState
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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** 可选的关注分组。空着直到用户第一次打开分组面板。 */
    val groups: List<FollowGroup> = emptyList(),
    /** 非空时正在给这个人设置分组。 */
    val picker: GroupPickerState? = null,
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
    private val dynamicRepository: DynamicRepository,
    followRepository: FollowRepository,
) : ViewModel() {

    /** 分组面板与关注列表页共用同一份实现,覆盖式写回的那些坑都在里面。 */
    private val groupPicker =
        GroupPickerController(followRepository, relationRepository, viewModelScope)

    /**
     * 打开分组面板。**分组名单在这里才拉**,不在页面初始化时:空间页绝大多数时候只是看内容,
     * 为一个藏在溢出菜单里的动作先打一个请求不划算。
     */
    fun openGroupPicker() {
        val profile = _state.value.profile ?: return
        groupPicker.open(UpBrief(mid = mid, name = profile.name, faceUrl = profile.faceUrl))
    }

    fun closeGroupPicker() = groupPicker.close()

    fun toggleGroup(groupId: Long) = groupPicker.toggle(groupId)

    fun saveGroups() = groupPicker.save()

    /**
     * 关注/取关。乐观更新、不重拉,与播放页同一套规矩。
     *
     * 关注态存在 profile 里(由 [loadProfile] 填),这里改的也是那一份,
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

    /**
     * 拉黑/取消拉黑。形状与 [toggleFollow] 相同:乐观更新、失败回滚、不重拉。
     *
     * 拉黑会连带解除关注,所以成功之后关注态就是 [FollowState.Blocked] 这一个值,不需要
     * 再问一次服务端。取消拉黑回到 [FollowState.None] —— 解除拉黑不会把关注还回来。
     */
    fun setBlocked(blocked: Boolean) {
        val profile = _state.value.profile ?: return
        if (profile.followState == FollowState.Self) return
        val next = if (blocked) FollowState.Blocked else FollowState.None
        _state.update { it.copy(profile = profile.copy(followState = next)) }
        viewModelScope.launch {
            val result =
                if (blocked) relationRepository.block(mid) else relationRepository.unblock(mid)
            if (result !is BiliResult.Ok) {
                BiliLog.w("${if (blocked) "拉黑" else "取消拉黑"}失败: $result")
                _state.update { it.copy(profile = profile) }
            }
        }
    }

    /**
     * 空间页的动态点赞。与 [dev.bilby.ui.dynamic.OtherDynamicsViewModel.like] 同形 ——
     * 同一条动态在两页里必须是同一个行为,乐观更新、失败回滚、不重拉。
     */
    fun likeDynamic(id: String, like: Boolean) {
        applyDynamicLike(id, like)
        viewModelScope.launch {
            val result = dynamicRepository.likeDynamic(id, like)
            if (result is BiliResult.ApiError || result is BiliResult.Failure) {
                BiliLog.w("动态 $id 点赞失败,已回滚")
                applyDynamicLike(id, !like)
            }
        }
    }

    private fun applyDynamicLike(id: String, like: Boolean) = _state.update { current ->
        current.copy(
            dynamics = current.dynamics.copy(
                items = current.dynamics.items.map { item ->
                    val card = (item as? SpaceDynamicItem.Card)?.card
                    val interaction = card?.interaction
                    if (card == null || interaction == null || card.id != id || interaction.liked == like) {
                        item
                    } else {
                        item.copy(
                            card = card.copy(
                                interaction = interaction.copy(
                                    liked = like,
                                    likeCount = (interaction.likeCount + if (like) 1 else -1)
                                        .coerceAtLeast(0),
                                ),
                            ),
                        )
                    }
                },
            ),
        )
    }

    private val _state = MutableStateFlow(SpaceUiState())
    val state: StateFlow<SpaceUiState> = _state.asStateFlow()

    // 刷新/切筛选会重置分页游标。代次让迟到的旧首页即使和新首页参数相同也不能写回。
    private var archivesGeneration = 0L
    private var dynamicsGeneration = 0L
    private var collectionsGeneration = 0L

    init {
        loadProfile()
        loadMoreArchives()
        loadMoreCollections()
        // 分组名单和面板归 controller,这一页的 UI 状态只是把它们抄进来。
        viewModelScope.launch {
            groupPicker.groups.collect { groups -> _state.update { it.copy(groups = groups) } }
        }
        viewModelScope.launch {
            groupPicker.picker.collect { picker -> _state.update { it.copy(picker = picker) } }
        }
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
                    is BiliResult.Ok -> {
                        // **投稿视频不进这一栏。** 隔壁「投稿」栏装的就是它们,而且那边按发布时间
                        // 排得整整齐齐、还能搜。同一条稿件在两栏里各出现一次,翻动态时读到的
                        // 一半内容是刚在上一栏看过的。
                        //
                        // 只在这里滤,不在 repository 里滤:建播放队列那条路
                        // (QueueSourceRepository.fromUpDynamics)要的正是这些 Video ——
                        // 以动态形式发的视频不进 arc/search,只有这条路找得到它们。
                        val fresh = result.value.items.filterNot { it is SpaceDynamicItem.Video }
                        state.copy(
                            refreshing = false,
                            dynamics = dynamics.copy(
                                items = if (replace) {
                                    fresh.distinctBy { it.key }
                                } else {
                                    dynamics.items.appendDistinctBy(fresh) { d -> d.key }
                                },
                                nextOffset = result.value.nextOffset,
                                loading = false,
                                appending = false,
                                hasMore = result.value.hasMore && result.value.nextOffset != null,
                            ),
                        )
                    }

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

    /**
     * **两条请求并发发,等齐了再一起写进 state。**
     *
     * **关注态要单独查**,不能读 `acc/info` 的 relation:网页端那条接口不填这个字段,DTO 拿不到
     * 就默认 0,而 0 正好是 `FollowState.None` —— 一个缺失被静默读成确定答案,表现是关注按钮
     * 永远显示"关注"。PiliPlus 的空间页看着也读 relation,但它读的是 **app 端**的空间接口
     * (带 app UA 和 app 参数),和这条不是一回事。这里用 `x/relation?fid=`,播放页一直用它。
     *
     * 关注态既然不在 profile 那条接口里,而按钮的默认值 `None` 就是
     * 「未关注」—— 先写 profile 再补关注态的话,已关注的人身上会先闪一下"关注"再跳成
     * "已关注"。那一下不是加载中,是一个错误答案被显示了一瞬。
     *
     * 并发之前这里是串行的(profile 回来才查关注态),理由写着"并发时 profile 后到会把查到的
     * 关注态盖回默认值" —— 那说的是两条各自写 state 的写法。等齐了一起写,这个问题不存在,
     * 而且总耗时从两条之和变成两条里慢的那条。
     */
    private fun loadProfile() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val profileResult = async { repository.loadProfile(mid) }
            val relationResult = async { relationRepository.stateOf(mid) }
            val profile = profileResult.await()
            val relation = relationResult.await()
            // 这里不碰 refreshing:下拉刷新会同时发 profile 和当前 tab 两个请求,谁都清一次
            // 的话先回来的那个就把指示器关掉了,而列表还在转。指示器跟着列表走。
            when (profile) {
                is BiliResult.Ok -> {
                    val followState = when (relation) {
                        is BiliResult.Ok -> relation.value
                        // 查不到就退回 profile 自带的那个默认值。**这仍然是"未关注"**,
                        // 和查到的"未关注"分不开 —— 但这一步已经不会再闪,而给按钮加一个
                        // "不知道"的第三态是另一件事(它会牵动播放页共用的 FollowButton)。
                        else -> {
                            BiliLog.w("空间页查关注态失败: $relation")
                            profile.value.followState
                        }
                    }
                    _state.update {
                        it.copy(loading = false, profile = profile.value.copy(followState = followState))
                    }
                }
                else -> _state.update { it.copy(loading = false, error = profile.errorText()) }
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
    onVideoClick: (SpaceVideoItem) -> Unit,
    /** 动态卡片被点开时去哪儿。由 MainActivity 接到 backstack 上,这一页不认识导航。 */
    onDynamicAction: (DynamicAction) -> Unit,
    onLikeDynamic: (String, Boolean) -> Unit,
    onLiveClick: (Long) -> Unit,
    onToggleFollow: () -> Unit,
    onSetBlocked: (Boolean) -> Unit,
    onOpenGroupPicker: () -> Unit,
    onCloseGroupPicker: () -> Unit,
    onToggleGroup: (Long) -> Unit,
    onSaveGroups: () -> Unit,
    onListenUp: () -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    /** 分享要给出 `space.bilibili.com/<mid>`,而 mid 不在 [state] 里。 */
    mid: Long,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

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

        // 页头的收起量。**在这里声明而不是在窄屏那个分支里**:窄屏下它同时被两处用到 ——
        // 页头自己(缩掉高度)和列表那一侧(把滚动喂给它),而后者在 tabsAndContent 里面。
        // 宽屏下页头不收起,那时它的 heightPx 恒为 0,连接因此什么都不消费。
        val headerScroll = rememberCollapsingHeaderState()

        val header: @Composable (Modifier) -> Unit = { paneModifier ->
            state.profile?.let {
                SpaceHeader(
                    it,
                    canListen = state.archives.items.isNotEmpty(),
                    onToggleFollow = onToggleFollow,
                    onSetBlocked = onSetBlocked,
                    onOpenGroupPicker = onOpenGroupPicker,
                    onListenUp = onListenUp,
                    onLiveClick = onLiveClick,
                    modifier = paneModifier,
                )
            }
        }

        val tabsAndContent: @Composable ColumnScope.() -> Unit = {
            // 三个标签用 pager 承载,和播放页的简介/评论一样可以左右划。tabs 页把"内容区能横滑
            // 翻页"写成 tabs 的常规用法,而这一页原先只有点标签一条路 —— 三栏讲的是同一个人的
            // 三种内容,横向切换本来就是它们之间最短的距离。
            //
            // pageCount 跟着 tabs 走(合集探测不到时只有两栏),所以 pager 的 key 也要带上它:
            // 栏目数变了还留着旧 pager,currentPage 会指到一个不存在的下标。
            val pagerState = rememberPagerState(initialPage = tabs.indexOf(state.activeTab).coerceAtLeast(0)) {
                tabs.size
            }
            val scope = rememberCoroutineScope()

            // 两个方向各一条:点标签滚 pager,划 pager 回写 activeTab(后者顺带触发那一栏的
            // 首次加载,和 onTabSelected 走的是同一个入口)。
            //
            // **比较用 [rememberUpdatedState] 读当前值,不能直接读 `state.activeTab`。**
            // 这个效应只在 (pagerState, tabs) 变化时重启,而 `state` 是启动那一刻捕获的那一份 ——
            // 之后它永远是"进这一页时的那个 tab"。真机上的表现:从投稿划到动态(旧值是投稿,
            // 不相等,写回去了),再划回投稿时旧值仍然是投稿,判成"没变"于是不写回,
            // ViewModel 里的 activeTab 就卡在动态上,顶栏那个只在投稿页出现的搜索图标再也回不来。
            val currentTab by rememberUpdatedState(state.activeTab)
            LaunchedEffect(pagerState, tabs) {
                snapshotFlow { pagerState.currentPage }
                    .collect { page -> tabs.getOrNull(page)?.let { if (it != currentTab) onTabSelected(it) } }
            }
            LaunchedEffect(state.activeTab, tabs) {
                val target = tabs.indexOf(state.activeTab)
                if (target >= 0 && target != pagerState.currentPage) pagerState.animateScrollToPage(target)
            }

            if (collectionsKnown) {
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage.coerceIn(tabs.indices)) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
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
                    // **页头的连接挂在这里,不是挂在外层那个 Column 上。**
                    //
                    // 嵌套滚动从内往外传:列表 → 这里 → PullToRefreshBox → 外层。挂在外层时
                    // 页头排在下拉刷新之后,列表到顶后剩下的下滑量先被刷新吃掉,页头再也拿不到
                    // —— 表现是收起之后展不开,而且"想把页头拉回来"这个动作变成了刷新。
                    // 挂在刷新框里面之后顺序对了:先把页头顶回来,它满了才轮到刷新。
                    else -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().nestedScroll(headerScroll.connection),
                    ) { page ->
                        when (tabs.getOrNull(page)) {
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
                                onAction = onDynamicAction,
                                onLikeDynamic = onLikeDynamic,
                            )

                            SpaceTab.Collections -> CollectionsTab(
                                state.collections,
                                onLoadMoreCollections,
                                onCollectionClick,
                            )

                            null -> Unit
                        }
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
                /*
                 * **头部跟着滚动退出屏幕,tab 栏留在原位。**
                 *
                 * 依据是 transitions 页 enter/exit 那一节:"Components can enter and exit from
                 * beyond the screen bounds based on a scroll gesture. This allows for more
                 * screen space to browse."(它给的例子正是顶栏和导航栏随滚动进出)。
                 *
                 * tab 栏不跟着走:tabs 页说 "Tabs control the UI region displayed below them",
                 * 滚起来之后还要知道自己在哪一栏、还要能换栏,它是这块区域的控制器而不是内容。
                 *
                 * 收起靠**缩掉它占的高度**而不是盖住它:后者会让 tab 栏悬在一段空白上,
                 * 而且列表顶部会被一块看不见的东西挡住。
                 */
                Column(modifier = Modifier.fillMaxSize()) {
                    header(Modifier.collapsingHeader(headerScroll))
                    tabsAndContent()
                }
            }
        }
    }

    // 分组面板与关注列表页共用同一个组件,那一页的入口在行尾溢出菜单里,这一页在头部的
    // 溢出菜单里,面板本身一模一样。
    state.picker?.let { picker ->
        GroupPickerSheet(
            state = picker,
            groups = state.groups,
            onToggle = onToggleGroup,
            onSave = onSaveGroups,
            onDismiss = onCloseGroupPicker,
        )
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
    onSetBlocked: (Boolean) -> Unit,
    onOpenGroupPicker: () -> Unit,
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
            Avatar(url = profile.faceUrl, size = Dimens.AvatarHeader)
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
                name = profile.name,
                onToggleFollow = onToggleFollow,
                onSetBlocked = onSetBlocked,
                onOpenGroupPicker = onOpenGroupPicker,
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
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    modifier = Modifier.padding(Spacing.Cozy),
                ) {
                    // 与首页那一排、动态里的直播格同一个符号:「正在直播」在全应用只有这一种
                    // 长相,换个位置就换个说法的话,这四个字得重新认一遍。
                    LivePulse(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Dimens.LivePulseInline),
                    )
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
                    // 这里曾经画一个"N 人在看"。**空间接口给的那个数是人气值**,一个按互动
                    // 算出来的分数,和"有多少人在看"没有换算关系;而这条链路上拿不到真的人数
                    // (watched_show 只在房间详情里)。为一个位置去多打一次房间接口不值,
                    // 索性不画 —— 少一个数字,好过一个看着像人数的分数。
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
    name: String,
    onToggleFollow: () -> Unit,
    onSetBlocked: (Boolean) -> Unit,
    onOpenGroupPicker: () -> Unit,
    onListenUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingBlock by remember { mutableStateOf(false) }

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
        if (followState == FollowState.Blocked) {
            // [FollowButton] 在这一档什么都不画,不补一个出口的话这一页就没有回头路 ——
            // 拉黑之后唯一能解除的地方会变成设置里的黑名单列表。
            TextButton(onClick = { onSetBlocked(false) }) {
                Text(stringResource(R.string.blacklist_unblock))
            }
        } else {
            if (followState != FollowState.Self) {
                // 拉黑收进溢出菜单,不在头部多摆一个按钮:一屏只留一个强调按钮
                // (风格指南 §2.4),那个名额是关注的。
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.follow_row_actions, name),
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    // 只有关注了的人才谈得上分组:没关注的人不在任何一份关注名单里,写回去
                    // 服务端也不认。
                    if (followState.isFollowing) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.follow_set_groups)) },
                            onClick = {
                                menuOpen = false
                                onOpenGroupPicker()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.blacklist_block)) },
                        onClick = {
                            menuOpen = false
                            confirmingBlock = true
                        },
                    )
                }
            }
            // 空间页整页都在讲这个人,关注是这一页最主要的动作,用 filled。
            FollowButton(state = followState, onClick = onToggleFollow)
        }
    }

    if (confirmingBlock) {
        BlockConfirmDialog(
            name = name,
            onConfirm = { onSetBlocked(true) },
            onDismiss = { confirmingBlock = false },
        )
    }
}

/**
 * 计数折算。分档除数也是本地化资源:中文按万/亿分档,英文按 K/M,
 * 只翻译单位后缀会让英文差一个量级。
 */

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
    PagedColumn(
        items = state.items,
        key = { "${it.isSeason}-${it.id}" },
        loading = state.loading,
        appending = state.appending,
        hasMore = state.hasMore,
        error = state.error,
        emptyText = stringResource(R.string.space_empty_collections),
        onLoadMore = onLoadMore,
        modifier = modifier,
    ) { item ->
        CollectionRow(item, onClick = { onCollectionClick(item) })
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
private fun DynamicListTab(
    state: SpaceListTabState,
    onLoadMore: () -> Unit,
    onAction: (DynamicAction) -> Unit,
    onLikeDynamic: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    PagedColumn(
        items = state.items,
        key = { it.key },
        loading = state.loading,
        appending = state.appending,
        hasMore = state.hasMore,
        error = state.error,
        emptyText = stringResource(R.string.space_empty_dynamics),
        onLoadMore = onLoadMore,
        modifier = modifier,
    ) { dynamic ->
        DynamicRow(dynamic = dynamic, onAction = onAction, onLikeDynamic = onLikeDynamic)
    }
}

/**
 * 一条动态。**投稿视频之外的全部类型走 [DynamicCardView]** —— 那一份是动态渲染的唯一实现,
 * 这一页与「其他动态」页共用。以前这里另写了一套只认五种形态的分支,于是同一位 UP 发的直播、
 * 音频、番剧更新在空间页悄悄消失,而在别处是有的。
 */
@Composable
private fun DynamicRow(
    dynamic: SpaceDynamicItem,
    onAction: (DynamicAction) -> Unit,
    onLikeDynamic: (String, Boolean) -> Unit,
) {
    when (dynamic) {
        // 投稿视频在进 state 之前就被滤掉了(见 loadMoreDynamics),这一栏里不会有 ——
        // 它们是隔壁「投稿」栏的内容。这个分支留着只因为 [SpaceDynamicItem] 还有这一支:
        // 建播放队列那条路要认它。
        is SpaceDynamicItem.Video -> Unit

        // 一条动态一张卡片,与「其他动态」页同一份处理:条目之间不画分割线,边界由底色和圆角
        // 画在卡片自己身上。动态内部本来就有带底色的块(转发、直播、预约),再叠一层横线之后
        // 整页全是线,分不清哪条是条目边界。
        //
        // 整页都是同一个人,所以不重复印他的头像和名字 —— 与上面视频行留空 upName 同一个理由。
        is SpaceDynamicItem.Card -> DynamicCardView(
            card = dynamic.card,
            onAction = onAction,
            onLike = { like -> onLikeDynamic(dynamic.card.id, like) },
            showAuthor = false,
            // 底色、圆角、内边距归卡片自己;这里只给边距和条目间的 gap,与「关注动态」页
            // 取同一组数,同一条动态在两页里才是同一个样子。
            // 上下各 4 合成 8 的 gap,与「关注动态」页的 spacedBy(Tight) 相同。
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Hair),
        )
    }
}

/** 投稿 tab 与合集目录([CollectionScreen])共用的视频列表。 */
@Composable
internal fun VideoListTab(
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
    PagedColumn(
        items = items,
        key = { it.bvid },
        loading = loading,
        appending = appending,
        hasMore = hasMore,
        error = error,
        emptyText = emptyText,
        onLoadMore = onLoadMore,
        modifier = modifier,
    ) { item ->
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
}

private val DateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DateFormatter)

