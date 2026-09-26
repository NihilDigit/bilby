package dev.bilby.ui.space

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import dev.bilby.ui.maxWidthGridCells
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.ui.components.PaneTitle
import dev.bilby.ui.components.SidePanelLayout
import dev.bilby.ui.components.SidePanelToggle
import dev.bilby.ui.components.panelCard
import dev.bilby.ui.components.EmptyState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.bilby.ui.components.UnfollowConfirmDialog
import dev.bilby.ui.components.touchOnlyPaging
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import dev.bilby.ui.components.SortMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import dev.bilby.BiliLog
import dev.bilby.resources.*
import dev.bilby.stringResource
import org.jetbrains.compose.resources.StringResource
import dev.bilby.ui.dynamic.DynamicAction
import dev.bilby.ui.dynamic.DynamicCardView
import dev.bilby.appendDistinctBy
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.errorTextRes
import dev.bilby.api.BiliResult
import dev.bilby.data.FollowGroup
import dev.bilby.data.SettingsStore
import dev.bilby.data.SideSheetPrefs
import dev.bilby.data.SidePanelId
import dev.bilby.data.FollowRepository
import dev.bilby.data.FollowState
import dev.bilby.data.UpBrief
import dev.bilby.data.RelationRepository
import dev.bilby.data.QueueContext
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
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.PlayingIndicator
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PagedLayout
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.material3.HorizontalDivider
import dev.bilby.ui.components.DynamicCardSkeleton
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.SearchField
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

enum class SpaceTab(val label: StringResource) {
    Archives(Res.string.space_tab_archives),
    Dynamics(Res.string.space_tab_dynamics),
    Collections(Res.string.space_tab_collections),
}

data class SpaceUiState(
    val loading: Boolean = true, // 首次加载 profile
    /** 失败说哪一句,存的是资源 id。映射与理由见 [dev.bilby.ui.errorTextRes]。 */
    val error: StringResource? = null,
    val profile: SpaceProfile? = null,
    val activeTab: SpaceTab = SpaceTab.Archives,
    /**
     * 这个人有没有这一类内容。null = 第一页还没回来;false = 已确认没有,那一栏(或宽屏的动态
     * 侧栏)不出现。三栏同一个规矩:不假设每个 UP 都投过稿、发过动态、建过合集。取失败按"有"算,
     * 理由见 [SpaceViewModel.loadMoreCollections] 失败那一支。
     */
    val archivesAvailable: Boolean? = null,
    val dynamicsAvailable: Boolean? = null,
    val collectionsAvailable: Boolean? = null,
    val refreshing: Boolean = false,
    val archives: SpaceArchiveTabState = SpaceArchiveTabState(),
    val dynamics: SpaceListTabState = SpaceListTabState(),
    val collections: SpaceCollectionsTabState = SpaceCollectionsTabState(),
    /** 可选的关注分组。空着直到用户第一次打开分组面板。 */
    val groups: List<FollowGroup> = emptyList(),
    /** 非空时正在给这个人设置分组。 */
    val picker: GroupPickerState? = null,
    /**
     * 宽屏右侧的动态侧栏,见 [SettingsStore.sidePanel]。null 是设置还没读出来:
     * 这时不画侧栏,读出来之后直接摆到位,不播进场动画 —— 否则关掉过它的人每次进页面都会
     * 看到它先弹出来再收回去。
     */
    val dynamicsSheet: SideSheetPrefs? = null,
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
    val error: StringResource? = null,
)

/**
 * 投稿栏眼前这份列表作为队列上下文:排序与**生效中的**搜索词(输入框里没按回车的字不算),
 * 页号由调用方按点中的那条算。
 */
fun SpaceArchiveTabState.queueContext(mid: Long, page: Int) =
    QueueContext.UpArchive(mid = mid, order = order, keyword = appliedKeyword, page = page)

data class SpaceListTabState(
    val items: List<SpaceDynamicItem> = emptyList(),
    val nextOffset: String? = null,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: StringResource? = null,
)

data class SpaceCollectionsTabState(
    val items: List<SpaceCollectionItem> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val loading: Boolean = false,
    val appending: Boolean = false,
    val hasMore: Boolean = true,
    val error: StringResource? = null,
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
    private val settings: SettingsStore,
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
                    val card = item.card
                    val interaction = card.interaction
                    if (interaction == null || card.id != id || interaction.liked == like) {
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
        // 三栏的第一页一进来就取:哪一栏没有内容,要在栏目画出来之前知道(见 SpaceUiState 的
        // archivesAvailable)。动态以前等切过去才取,这里提前,只是把那一次请求挪到了进页面时。
        loadMoreArchives()
        loadMoreDynamics()
        loadMoreCollections()
        // 分组名单和面板归 controller,这一页的 UI 状态只是把它们抄进来。
        viewModelScope.launch {
            groupPicker.groups.collect { groups -> _state.update { it.copy(groups = groups) } }
        }
        viewModelScope.launch {
            groupPicker.picker.collect { picker -> _state.update { it.copy(picker = picker) } }
        }
        viewModelScope.launch {
            settings.sidePanel(SidePanelId.SpaceDynamics).collect { prefs -> _state.update { it.copy(dynamicsSheet = prefs) } }
        }
    }

    fun setDynamicsSheetOpen(open: Boolean) {
        viewModelScope.launch { settings.saveSidePanelOpen(SidePanelId.SpaceDynamics, open) }
    }

    /** 拖动松手时才存,拖动过程中的宽度留在界面里,不每帧写一次盘。 */
    fun setDynamicsSheetWidth(widthDp: Float) {
        viewModelScope.launch { settings.saveSidePanelWidth(SidePanelId.SpaceDynamics, widthDp) }
    }

    fun retry() {
        if (_state.value.profile == null) loadProfile()
        when (_state.value.activeTab) {
            SpaceTab.Archives -> if (_state.value.archives.items.isEmpty()) loadMoreArchives()
            SpaceTab.Dynamics -> if (_state.value.dynamics.items.isEmpty()) loadMoreDynamics()
            SpaceTab.Collections -> if (_state.value.collections.items.isEmpty()) loadMoreCollections()
        }
    }

    /**
     * 刷新页头和此刻看得见的几栏。窄屏只看得见 [SpaceUiState.activeTab] 那一栏;宽屏动态与
     * 投稿(或合集)并排,两栏都要刷,只刷一栏的话另一栏停在旧内容上,看不出刷没刷。
     */
    fun refresh(visibleTabs: Set<SpaceTab>) {
        _state.update { it.copy(refreshing = true) }
        loadProfile()
        visibleTabs.forEach(::refreshTab)
    }

    private fun refreshTab(tab: SpaceTab) {
        when (tab) {
            SpaceTab.Archives -> {
                archivesGeneration++
                _state.update {
                    it.copy(archives = it.archives.copy(
                        page = 1,
                        loading = false, appending = false, hasMore = true, error = null,
                    ))
                }
                loadMoreArchives(replace = true)
            }
            SpaceTab.Dynamics -> {
                dynamicsGeneration++
                _state.update {
                    it.copy(dynamics = it.dynamics.copy(
                        nextOffset = null,
                        loading = false, appending = false, hasMore = true, error = null,
                    ))
                }
                loadMoreDynamics(replace = true)
            }
            SpaceTab.Collections -> {
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
                            // 只认不带搜索词的第一页:搜不到东西不等于没投过稿,那时栏目不能消失。
                            archivesAvailable = if (requested.page == 1 && requested.keyword.isBlank()) {
                                result.value.total > 0 || merged.isNotEmpty()
                            } else {
                                state.archivesAvailable
                            },
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
                        archivesAvailable = state.archivesAvailable ?: true,
                        archives = archives.copy(
                            loading = false,
                            appending = false,
                            error = result.errorTextRes("空间页取投稿"),
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
                        // 一半内容是刚在上一栏看过的。以动态形式发的视频不在投稿栏,留在这里。
                        //
                        // 只在这里滤,不在 repository 里滤:建播放队列那条路
                        // (QueueSourceRepository.fromUpDynamics)两种视频都要。
                        val fresh = result.value.items.filterNot { it.listedInArchive }
                        val hasMore = result.value.hasMore && result.value.nextOffset != null
                        state.copy(
                            refreshing = false,
                            // 第一页滤完是空的、又还有下一页时判不出来(见本函数开头的说明),
                            // 按"有"算:多一栏点进去是空的,好过少一栏。
                            dynamicsAvailable = if (requestedOffset == null) {
                                fresh.isNotEmpty() || hasMore
                            } else {
                                state.dynamicsAvailable
                            },
                            dynamics = dynamics.copy(
                                items = if (replace) {
                                    fresh.distinctBy { it.key }
                                } else {
                                    dynamics.items.appendDistinctBy(fresh) { d -> d.key }
                                },
                                nextOffset = result.value.nextOffset,
                                loading = false,
                                appending = false,
                                hasMore = hasMore,
                            ),
                        )
                    }

                    else -> state.copy(
                        refreshing = false,
                        dynamicsAvailable = state.dynamicsAvailable ?: true,
                        dynamics = dynamics.copy(
                            loading = false,
                            appending = false,
                            error = result.errorTextRes("空间页取动态"),
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
                            error = result.errorTextRes("空间页取合集"),
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
                else -> _state.update {
                    it.copy(loading = false, error = profile.errorTextRes("空间页取资料"))
                }
            }
        }
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
    /** 收到的是此刻看得见的几栏,见 [SpaceViewModel.refresh]。 */
    onRefresh: (Set<SpaceTab>) -> Unit = {},
    onDynamicsSheetOpenChange: (Boolean) -> Unit = {},
    /** 拖动松手时的侧栏宽度,单位 dp。 */
    onDynamicsSheetWidthChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val system = LocalSystemActions.current

    // 页头的收起量。**在 Scaffold 外面声明**:页头自己(缩掉高度)、列表那一侧(把滚动喂给它)
    // 和顶栏(收到底才显示名字)三处都要读。
    val headerScroll = rememberCollapsingHeaderState()
    val windowSize = rememberBilbyWindowSize()
    val wide = windowSize.isAtLeast(BilbyWindowSize.Expanded)

    // **栏目等三类内容的第一页都回来才画。** 先画三个再抽掉一个的话,栏目宽度会重新分配、
    // 下面整块内容跟着上跳,而这一切发生在用户已经开始看页面之后。三个请求是并发的,
    // 等的是其中最慢的那个。
    val sectionsKnown = state.archivesAvailable != null &&
        state.dynamicsAvailable != null &&
        state.collectionsAvailable != null
    val allTabs = SpaceTab.entries.filter { tab ->
        when (tab) {
            SpaceTab.Archives -> state.archivesAvailable == true
            SpaceTab.Dynamics -> state.dynamicsAvailable == true
            SpaceTab.Collections -> state.collectionsAvailable == true
        }
    }
    // 宽屏时动态不是标签,是右侧可关的侧栏(见 [DynamicsSideSheet]),主区只剩投稿与合集。
    // 侧栏最宽 400dp,expanded 下限 840dp 时主区还剩 440dp,排得下一列视频行。
    // 主区没有别的内容时,动态回到主区当唯一一栏,不留一片空主区配一条侧栏。
    val sheetAvailable = wide && SpaceTab.Dynamics in allTabs && allTabs.size > 1
    val mainTabs = if (sheetAvailable) allTabs - SpaceTab.Dynamics else allTabs
    val sheetOpen = sheetAvailable && state.dynamicsSheet?.open == true
    // activeTab 若不在主区里(从窄屏拉宽过来时停在动态上),按主区第一栏算,pager 同步时会写回去。
    val mainTab = state.activeTab.takeIf { it in mainTabs } ?: mainTabs.firstOrNull()
    val visibleTabs = setOfNotNull(mainTab, SpaceTab.Dynamics.takeIf { sheetOpen })

    // **名字归页头,顶栏展开时空着,页头收到底才接过名字。**
    //
    // 上一版把名字放在 medium flexible 顶栏里,整行 headlineMedium,页头因此不印名字。代价是
    // 「返回/分享」「大号名字」「头像那一行」三层摞着,内容开始前先占掉三分之一屏,名字和头像
    // 还隔着一行。名字回到头像右边之后,顶栏退回 small 一档,那一行只有返回和分享。
    //
    // 收到底才显示,不是一直显示:页头在的时候它就在名字正上方,两处印同一个名字。
    val headerGone = headerScroll.collapsedFraction >= 1f
    val barTitle = state.profile?.name?.takeIf { headerGone }.orEmpty()

    // 顶栏的搜索态。**筛选生效期间顶栏一直是输入框**,不看这个开关:上一次把搜索放进顶栏时,
    // 收起输入框而筛选还在,投稿就"莫名其妙变少了"。现在收起只有一条路 —— 关闭按钮,它同时
    // 清掉关键词并重新拉取,于是"输入框不见了"和"没在筛"永远是同一件事。
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searching = searchOpen || state.archives.appliedKeyword.isNotBlank()

    Scaffold(
        modifier = modifier,
        topBar = {
            SpaceTopBar(
                title = barTitle,
                searching = searching,
                keyword = state.archives.keyword,
                refreshing = state.refreshing,
                onRefresh = { onRefresh(visibleTabs) },
                onKeywordChanged = onArchiveKeywordChanged,
                onSearch = onArchiveSearch,
                onOpenSearch = {
                    searchOpen = true
                    // 搜的是投稿,人在动态或合集那一栏时先切过去,结果才看得见。
                    onTabSelected(SpaceTab.Archives)
                },
                onCloseSearch = {
                    searchOpen = false
                    onArchiveKeywordChanged("")
                    // 没真筛过(只敲了字没回车)就不必重拉,列表本来就是全部。
                    if (state.archives.appliedKeyword.isNotEmpty()) onArchiveSearch()
                },
                onShare = { system.shareSpace(mid, state.profile?.name.orEmpty()) },
                onBack = onBack,
                // 设置没读出来之前不给开关,免得按下去的是一个还不知道当前值的状态。
                dynamicsSheetOpen = state.dynamicsSheet?.open?.takeIf { sheetAvailable },
                onDynamicsSheetOpenChange = onDynamicsSheetOpenChange,
                // 搜的是投稿;没投过稿的人没有东西可搜。
                canSearch = SpaceTab.Archives in allTabs,
            )
        },
    ) { insets ->

        val header: @Composable (Modifier) -> Unit = { paneModifier ->
            state.profile?.let {
                SpaceHeader(
                    it,
                    signBesideAvatar = wide,
                    onToggleFollow = onToggleFollow,
                    onSetBlocked = onSetBlocked,
                    onOpenGroupPicker = onOpenGroupPicker,
                    onLiveClick = onLiveClick,
                    modifier = paneModifier,
                )
            }
        }

        // 投稿与合集在宽屏上是网格,规则同订阅页;动态是正文,仍是一列(见 [DynamicRow])。
        val listColumns = if (wide) maxWidthGridCells(Breakpoints.VideoRowMaxWidth) else GridCells.Fixed(1)

        // 首屏的三种状态,单栏与双栏共用。
        val profileGate: @Composable (@Composable () -> Unit) -> Unit = { content ->
            when {
                state.loading && state.profile == null -> FullScreenLoading()
                state.error != null && state.profile == null ->
                    FullScreenError(stringResource(state.error), onRetry)
                // tab 栏还没画出来,内容先不画:否则内容会先顶在页头下面,等 tab 栏出现
                // 再被推下去一截。
                !sectionsKnown -> FullScreenLoading()
                else -> content()
            }
        }

        val dynamicsList: @Composable (Modifier) -> Unit = { listModifier ->
            DynamicListTab(
                state = state.dynamics,
                onLoadMore = onLoadMoreDynamics,
                onAction = onDynamicAction,
                onLikeDynamic = onLikeDynamic,
                flat = wide,
                modifier = listModifier,
            )
        }

        // 带标签的那一块。单栏时是全部三栏;双栏时动态单独成列,这里只剩投稿与合集。
        val tabbedPane: @Composable ColumnScope.(List<SpaceTab>) -> Unit = { tabs ->
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

            if (sectionsKnown && tabs.isNotEmpty()) {
                val tabItems: @Composable () -> Unit = {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(stringResource(tab.label)) },
                        )
                    }
                }
                val selectedIndex = pagerState.currentPage.coerceIn(tabs.indices)
                // 不要默认那条通栏分割线:指示条已经标出了这一行的下沿,再划一道是整页最硬的
                // 一条线,横在页头和列表之间。
                //
                // 宽屏靠左、按字宽排:等分的话三个两字标签摊在上千 dp 上,彼此隔着三四百 dp,
                // 读起来不像同一组。scrollable 款正是按内容定宽的那一种,三个标签不会真的滚。
                if (wide) {
                    // **宽屏时标签与听投稿、排序合成一行**:标签靠左只占几个字宽,右边本来空着,
                    // 表头再单占一行是白白多出一行高度。代价是这两颗按钮不再随列表滚走。
                    // 只剩一栏时不画成标签 —— 一个孤零零的选中标签读起来像另外几个没加载出来;
                    // 写成栏名。窄屏同理。
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(end = Spacing.Tight),
                    ) {
                        if (tabs.size > 1) {
                            PrimaryScrollableTabRow(
                                selectedTabIndex = selectedIndex,
                                edgePadding = 0.dp,
                                divider = {},
                                tabs = tabItems,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            PaneTitle(stringResource(tabs.first().label), Modifier.weight(1f))
                        }
                        if (tabs.getOrNull(pagerState.currentPage) == SpaceTab.Archives) {
                            ArchiveListenButton(state.archives, onListenUp)
                            ArchiveSortMenu(state.archives, onArchiveOrderChanged)
                        }
                    }
                } else if (tabs.size == 1) {
                    PaneTitle(stringResource(tabs.first().label))
                } else {
                    PrimaryTabRow(
                        selectedTabIndex = selectedIndex,
                        divider = {},
                        tabs = tabItems,
                    )
                }
            }
            RefreshBox(
                refreshing = state.refreshing,
                onRefresh = { onRefresh(visibleTabs) },
                modifier = Modifier.weight(1f),
            ) {
                // **页头的连接挂在这里,不是挂在外层那个 Column 上。**
                //
                // 嵌套滚动从内往外传:列表 → 这里 → RefreshBox → 外层。挂在外层时
                // 页头排在下拉刷新之后,列表到顶后剩下的下滑量先被刷新吃掉,页头再也拿不到
                // —— 表现是收起之后展不开,而且"想把页头拉回来"这个动作变成了刷新。
                // 挂在刷新框里面之后顺序对了:先把页头顶回来,它满了才轮到刷新。
                profileGate {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize().nestedScroll(headerScroll.connection).touchOnlyPaging(),
                    ) { page ->
                        when (tabs.getOrNull(page)) {
                            SpaceTab.Archives -> ArchivesTab(
                                state.archives,
                                onOrderChanged = onArchiveOrderChanged,
                                onListenUp = onListenUp,
                                onLoadMore = onLoadMoreArchives,
                                onVideoClick = onVideoClick,
                                columns = listColumns,
                                showControls = !wide,
                            )

                            // 宽屏上动态在主区时(没有投稿也没有合集),同样整块成卡,四周留出页边。
                            SpaceTab.Dynamics -> dynamicsList(
                                if (wide) {
                                    Modifier
                                        .padding(start = Spacing.Comfortable, end = Spacing.Comfortable, bottom = Spacing.Comfortable)
                                        .panelCard()
                                } else {
                                    Modifier
                                },
                            )

                            SpaceTab.Collections -> CollectionsTab(
                                state.collections,
                                onLoadMoreCollections,
                                onCollectionClick,
                                columns = listColumns,
                            )

                            null -> Unit
                        }
                    }
                }
            }
        }

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
         *
         * 宽屏也是这个排法,页头不限宽。页头放进侧栏试过两轮:按三分之一分时整栏只有顶上
         * 一张名片,定宽 320dp 之后仍是一整条竖着的空白;而横在顶上的页头滚一下就收走。
         */
        val mainColumn: @Composable ColumnScope.() -> Unit = {
            header(Modifier.collapsingHeader(headerScroll))
            // 三类内容都确认没有:页头下面一句话,不画任何栏目。取失败不会走到这里(失败按
            // "有"算),所以这句话只在服务端明确说没有时出现。
            if (sectionsKnown && allTabs.isEmpty() && state.profile != null) {
                EmptyState(stringResource(Res.string.space_empty_all), Modifier.weight(1f))
            } else {
                tabbedPane(mainTabs)
            }
        }
        if (wide) {
            /*
             * **投稿与合集是主区,动态是右侧可关的侧栏。** 并排的两栏试过一轮:一边卡片、一边
             * 网格,看上去是两页拼在一起。侧栏把主次说清楚 —— 这一页主要看他做了什么,他说了
             * 什么是旁边的补充,不想看可以关掉。
             *
             * 侧栏从顶栏下面通到底,不在页头下面:页头说的是主区这个人是谁,收起时只有主区
             * 跟着动;侧栏自己滚,和主区互不牵连(side-sheets.md Behavior 一节)。主区不限宽,
             * 投稿是网格,行长由格宽管。
             */
            SidePanelLayout(
                prefs = state.dynamicsSheet,
                // 三栏的探测回来之前不组合侧栏:没发过动态的人不会看到它弹一下又收走。
                ready = sectionsKnown,
                available = sheetAvailable,
                title = stringResource(SpaceTab.Dynamics.label),
                closeDescription = stringResource(Res.string.space_dynamics_sheet_close),
                onOpenChange = onDynamicsSheetOpenChange,
                onWidthChange = onDynamicsSheetWidthChange,
                defaultWidth = DynamicsPanelDefaultWidth,
                minWidth = DynamicsPanelMinWidth,
                modifier = Modifier.fillMaxSize().padScaffoldExceptBottom(insets),
                main = { Column(modifier = Modifier.fillMaxSize(), content = mainColumn) },
                panel = { profileGate { dynamicsList(Modifier.fillMaxSize()) } },
            )
        } else {
            AdaptiveContent(
                modifier = Modifier.fillMaxSize().padScaffoldExceptBottom(insets),
                maxWidth = Breakpoints.ReadableWidth,
            ) {
                Column(modifier = Modifier.fillMaxSize(), content = mainColumn)
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
 * 空间页顶栏:返回、搜索、分享;搜索态下标题位换成输入框。
 *
 * **搜索回到顶栏。** 它曾经从这里挪进投稿列表的表头,常驻一个输入框;那一行加上排序、页头、
 * 标签栏,列表要到半屏以下才开始。放回顶栏之后平时只是一个图标,而上一次放在这里时的毛病
 * ("收起了却还在筛")由调用方的判据堵住:筛选生效期间这里一直是输入框,见 [SpaceScreen]。
 *
 * 搜索态不留分享:输入框要那一截宽度,而正在筛投稿的人此刻不是要分享主页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpaceTopBar(
    title: String,
    searching: Boolean,
    keyword: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onKeywordChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onOpenSearch: () -> Unit,
    onCloseSearch: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit,
    /** 动态侧栏的开关状态;null 时不给开关(窄屏,或设置还没读出来)。 */
    dynamicsSheetOpen: Boolean?,
    onDynamicsSheetOpenChange: (Boolean) -> Unit,
    /** 有没有投稿可搜。确认没有投稿时不给搜索图标。 */
    canSearch: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    TopAppBar(
        title = {
            if (searching) {
                SearchField(
                    value = keyword,
                    onValueChange = onKeywordChanged,
                    placeholder = stringResource(Res.string.space_search_hint),
                    onSearch = onSearch,
                    focusRequester = focusRequester,
                )
                // 点图标打开时直接进输入;筛选生效着、从别处回到这一页时不抢焦点 —— 那时人是
                // 来看结果的,弹出键盘会盖住一半列表。
                LaunchedEffect(Unit) {
                    if (keyword.isEmpty()) focusRequester.requestFocus()
                }
            } else {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                )
            }
        },
        actions = {
            RefreshAction(refreshing = refreshing, onRefresh = onRefresh)
            if (searching) {
                IconButton(onClick = onCloseSearch) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.space_search_close),
                    )
                }
            } else {
                if (canSearch) {
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(Res.string.space_search_hint),
                        )
                    }
                }
                IconButton(onClick = onShare) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = stringResource(Res.string.action_share),
                    )
                }
            }
            // 侧栏关掉之后回来的唯一入口。放在最右,正对着侧栏所在的那一侧;搜索态也留着,
            // 它和搜什么无关。
            if (dynamicsSheetOpen != null) {
                SidePanelToggle(
                    open = dynamicsSheetOpen,
                    onOpenChange = onDynamicsSheetOpenChange,
                    description = stringResource(Res.string.space_dynamics_sheet_toggle),
                )
            }
        },
    )
}

/**
 * 空间头部。参照 PiliPlus 的 `pages/member/widget/user_info_card.dart`:头像 + 名字与数据 +
 * 关注,签名单独占整行宽度。
 *
 * **名字在头像右边。** 放在 medium flexible 顶栏里的那一版见 [SpaceScreen] 开头的说明。
 *
 * **没有单独的动作行。** 头像那一行原先右端挤着耳机、溢出菜单和关注三样,后来拆成单独一行,
 * 又多占一行高度。现在各归其位:关注与它的附属动作(分组、取关、拉黑)合成一个分体按钮
 * ([SpaceFollowControl]),听投稿去了投稿表头(它听的正是那份列表),搜索回到顶栏。
 *
 * **没有头图**。接口层的 `SpaceProfile` 目前不带 `top_photo`,补它要动 `api/dto`,
 * 不在这一轮的边界内 —— 见报告里的"需要接口层配合"。
 */
@Composable
private fun SpaceHeader(
    profile: SpaceProfile,
    /**
     * 签名放进名字那一列(宽屏)。宽屏上头像右边有的是地方,签名单占一行只会让页头多出一行、
     * 而且左沿退回到头像下面,和名字不在一条线上。
     */
    signBesideAvatar: Boolean,
    onToggleFollow: () -> Unit,
    onSetBlocked: (Boolean) -> Unit,
    onOpenGroupPicker: () -> Unit,
    onLiveClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // **没填签名就不画。** 原先兜一句「这个人很懒,什么都没写」—— 那是 B 站网页端的
    // 占位文案,而它说的是一件我们并不知道的事(没填签名不等于懒),还替这个人下了判断。
    // 空着的那一行也不是"数据还没到",没有需要说明的东西。「我的」页(`AccountHeader`)
    // 一直是这个做法,两页现在对上了。
    //
    // 签名可能很长又基本没信息量,给两行封顶。
    val sign: @Composable () -> Unit = {
        if (profile.sign.isNotBlank()) {
            Text(
                text = profile.sign,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
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
                        text = stringResource(Res.string.space_followers, formatCount(profile.follower)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (signBesideAvatar) sign()
            }
            SpaceFollowControl(
                followState = profile.followState,
                name = profile.name,
                onToggleFollow = onToggleFollow,
                onSetBlocked = onSetBlocked,
                onOpenGroupPicker = onOpenGroupPicker,
            )
        }
        // 窄屏放在下面一整行:它旁边没有头像时能多放十来个字,挤在头像右边只剩半行。
        if (!signBesideAvatar) sign()

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
                    PlayingIndicator(
                        active = true,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(Dimens.PlayingIndicatorInline),
                    )
                    Text(
                        text = stringResource(Res.string.space_live_now),
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

/**
 * 关注,以及跟着关注走的那几件事:设置分组、取消关注、拉黑。M3 Expressive 的分体按钮。
 *
 * **已关注时整颗按钮都打开同一份菜单。** 关注之后这颗按钮能做的只剩这三件,原先点它直接弹
 * 取关确认,分组和拉黑却藏在旁边一个 ⋮ 里 —— 同一个人身上的几件事分在两处。
 *
 * **没关注时左半是关注,右半 ▾ 打开只有「拉黑」的菜单。** 拉黑一个没关注的人正是最常见的
 * 情形(骚扰、搬运),入口不能只在关注之后才出现;放在同一颗按钮的右半,它的位置就不随关注
 * 状态变。
 *
 * 一屏只留一个 filled 的名额是关注的(风格指南 §2.4):没关注时 filled,关注之后 outlined ——
 * 关系已经建立,再点能做的都是往回撤的事,不该抢眼。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpaceFollowControl(
    followState: FollowState,
    name: String,
    onToggleFollow: () -> Unit,
    onSetBlocked: (Boolean) -> Unit,
    onOpenGroupPicker: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingBlock by remember { mutableStateOf(false) }
    var confirmingUnfollow by remember { mutableStateOf(false) }

    when (followState) {
        FollowState.Self -> return
        // 拉黑之后关注无从谈起,这里只剩解除 —— 不补这个出口的话,能解除的地方只剩设置里的
        // 黑名单列表。
        FollowState.Blocked -> {
            TextButton(onClick = { onSetBlocked(false) }) {
                Text(stringResource(Res.string.blacklist_unblock))
            }
            return
        }
        else -> Unit
    }

    val following = followState.isFollowing
    val label = stringResource(
        when (followState) {
            FollowState.Mutual -> Res.string.follow_mutual
            FollowState.Following -> Res.string.follow_following
            else -> Res.string.follow_none
        },
    )
    val onLeading = if (following) ({ menuOpen = true }) else onToggleFollow

    Box {
        SplitButtonLayout(
            leadingButton = {
                if (following) {
                    SplitButtonDefaults.OutlinedLeadingButton(onClick = onLeading) { Text(label) }
                } else {
                    SplitButtonDefaults.LeadingButton(onClick = onLeading) { Text(label) }
                }
            },
            trailingButton = {
                val description = stringResource(Res.string.follow_row_actions, name)
                if (following) {
                    SplitButtonDefaults.OutlinedTrailingButton(
                        checked = menuOpen,
                        onCheckedChange = { menuOpen = it },
                        modifier = Modifier.semantics { contentDescription = description },
                    ) { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }
                } else {
                    SplitButtonDefaults.TrailingButton(
                        checked = menuOpen,
                        onCheckedChange = { menuOpen = it },
                        modifier = Modifier.semantics { contentDescription = description },
                    ) { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) }
                }
            },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            // 只有关注了的人才谈得上分组与取关:没关注的人不在任何一份关注名单里。
            if (following) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.follow_set_groups)) },
                    onClick = {
                        menuOpen = false
                        onOpenGroupPicker()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.follow_unfollow_confirm_title)) },
                    onClick = {
                        menuOpen = false
                        confirmingUnfollow = true
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.blacklist_block)) },
                onClick = {
                    menuOpen = false
                    confirmingBlock = true
                },
            )
        }
    }

    // 名字传给取关确认框:那个框上写着要取关谁,比只写"取消关注"少一次回想。
    if (confirmingUnfollow) {
        UnfollowConfirmDialog(
            name = name,
            onConfirm = onToggleFollow,
            onDismiss = { confirmingUnfollow = false },
        )
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
    SpaceArchiveOrder.Pubdate to Res.string.space_order_pubdate,
    SpaceArchiveOrder.Click to Res.string.space_order_click,
)

/**
 * 投稿页。表头左边是「听投稿」,右边是排序下拉([SortMenu])。搜索在顶栏,
 * 见 [SpaceTopBar]。
 *
 * **听投稿在这里,不在页头。** 它听的就是这份列表 —— 队列取自当前投稿,带着此刻的排序与
 * 搜索词(DESIGN 2.4b:有限且用户显式选定的集合)。放在列表头上,"听的是哪些"不用解释。
 *
 * **表头进 `PagedColumn` 的 header 槽,不钉在列表上面。** 钉住的话它和上面的页头、tab 栏
 * 三层叠着占掉小半屏,而这一页要看的是列表;放进表头之后它跟着列表一起滚走,要用时往上一拉
 * 就回来。风格指南 §7 引 transitions 页那句"Components can enter and exit from beyond the
 * screen bounds based on a scroll gesture. This allows for more screen space to browse."
 */
@Composable
private fun ArchivesTab(
    state: SpaceArchiveTabState,
    onOrderChanged: (SpaceArchiveOrder) -> Unit,
    onListenUp: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    columns: GridCells,
    /** 听投稿与排序是否画在列表表头。双栏时它们在栏顶,这里不再画。 */
    showControls: Boolean,
    modifier: Modifier = Modifier,
) {
    VideoListTab(
        columns = columns,
        items = state.items,
        appending = state.appending,
        hasMore = state.hasMore,
        loading = state.loading,
        // state 里存的是资源 id,文案在这一层取(见 [SpaceArchiveTabState.error])。
        error = state.error?.let { stringResource(it) },
        // 空是因为**生效中**的那个关键词没搜到东西,不是因为输入框里现在有什么字。
        emptyText = stringResource(
            if (state.appliedKeyword.isBlank()) {
                Res.string.space_empty_archives
            } else {
                Res.string.space_empty_archives_search
            },
        ),
        onLoadMore = onLoadMore,
        onVideoClick = onVideoClick,
        modifier = modifier,
        // 双栏时这一行挪到栏顶,与动态栏的栏名同一行高(见 [SpaceScreen] 的双栏分支)。
        header = if (!showControls) null else {
            {
                // 排序原先独占一行、只有靠右的两个词,左边整片空着;听投稿补上了那一半。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = Spacing.Tight, end = Spacing.Tight, top = Spacing.Hair),
                ) {
                    ArchiveListenButton(state, onListenUp, Modifier.alignByBaseline())
                    Spacer(modifier = Modifier.weight(1f))
                    ArchiveSortMenu(state, onOrderChanged, Modifier.alignByBaseline())
                }
            }
        },
    )
}

/**
 * text button:这一行是列表的表头,不该比页头的关注按钮还重。列表空着(还没加载或搜不到)时
 * 按下去没有东西可听,不给按。
 *
 * 与 [ArchiveSortMenu] 并排时**按文字基线对齐,不按盒子居中**:两颗按钮的内边距与图标不一样
 * (左边图标在字前、右边在字后,右边还收窄了内边距),各自居中之后两行字的下沿差着一两 dp。
 */
@Composable
private fun ArchiveListenButton(state: SpaceArchiveTabState, onListenUp: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onListenUp,
        enabled = state.items.isNotEmpty(),
        modifier = modifier,
    ) {
        Icon(
            Icons.Filled.Headphones,
            contentDescription = null,
            modifier = Modifier.size(Dimens.IconInline),
        )
        Text(
            stringResource(Res.string.space_listen_up),
            modifier = Modifier.padding(start = Spacing.Tight),
        )
    }
}

/** 排序只有两档、又不常换,收成一个下拉,当前是哪一档仍然写在按钮上。 */
@Composable
private fun ArchiveSortMenu(
    state: SpaceArchiveTabState,
    onOrderChanged: (SpaceArchiveOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    SortMenu(
        options = ArchiveOrders,
        selected = state.order,
        onSelect = onOrderChanged,
        modifier = modifier,
    )
}

@Composable
private fun CollectionsTab(
    state: SpaceCollectionsTabState,
    onLoadMore: () -> Unit,
    onCollectionClick: (SpaceCollectionItem) -> Unit,
    columns: GridCells,
    modifier: Modifier = Modifier,
) {
    PagedColumn(
        items = state.items,
        key = { "${it.isSeason}-${it.id}" },
        layout = PagedLayout.Grid(columns),
        loading = state.loading,
        appending = state.appending,
        hasMore = state.hasMore,
        error = state.error?.let { stringResource(it) },
        emptyText = stringResource(Res.string.space_empty_collections),
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
                    Res.string.space_collection_meta,
                    stringResource(
                        if (item.isSeason) {
                            Res.string.space_collection_season
                        } else {
                            Res.string.space_collection_series
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

/** 动态侧栏从没拖过时的宽度:standard side sheet 的规格上限(side-sheets.md 的 measurements 表),正好一列动态。 */
private val DynamicsPanelDefaultWidth = 400.dp

/** 拖窄的下限。再窄三张配图各不到 90dp,正文一行不到二十个字。 */
private val DynamicsPanelMinWidth = 320.dp

@Composable
private fun DynamicListTab(
    state: SpaceListTabState,
    onLoadMore: () -> Unit,
    onAction: (DynamicAction) -> Unit,
    onLikeDynamic: (String, Boolean) -> Unit,
    /**
     * 宽屏:整片动态区是一张卡(调用方用 [panelCard] 画),条目平铺在里面、用分割线隔开,
     * 按宽度排成瀑布流。投稿与合集是平铺的行,动态若一条一张卡,同一页里两种东西两种画法;
     * 整块成卡之后,"这一块是动态"由区域说明,条目本身和投稿一样平。
     */
    flat: Boolean,
    modifier: Modifier = Modifier,
) {
    PagedColumn(
        items = state.items,
        key = { it.key },
        skeletonRow = { DynamicCardSkeleton() },
        loading = state.loading,
        appending = state.appending,
        hasMore = state.hasMore,
        error = state.error?.let { stringResource(it) },
        emptyText = stringResource(Res.string.space_empty_dynamics),
        onLoadMore = onLoadMore,
        modifier = modifier,
        // 瀑布流:一条三行的和一条半屏的并排时,按网格排会在矮的那条下面空出一大截。
        // 列数按宽度来,侧栏默认宽度下是一列,拖宽或占满主区时自然变成两列、三列。
        layout = if (flat) {
            PagedLayout.Staggered(StaggeredGridCells.Adaptive(DynamicColumnMinWidth), Spacing.Tight)
        } else {
            PagedLayout.SingleColumn
        },
    ) { dynamic ->
        if (flat) {
            Column {
                DynamicCardView(
                    card = dynamic.card,
                    onAction = onAction,
                    onLike = { like -> onLikeDynamic(dynamic.card.id, like) },
                    showAuthor = false,
                    contained = false,
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(horizontal = Spacing.Cozy),
                )
            }
        } else {
            DynamicRow(dynamic = dynamic, onAction = onAction, onLikeDynamic = onLikeDynamic)
        }
    }
}

/**
 * 瀑布流一列的最窄宽度。按下限而不是上限定列数:动态的配图方格要 490dp 才铺得满
 * (见 DynamicCardView 的 GridImageMaxSide),一列压到三百来 dp,配图和正文都挤。
 * 侧栏默认 400dp 正好一列,拖过 800 变两列。
 */
private val DynamicColumnMinWidth = 400.dp

/**
 * 一条动态。**全部类型走 [DynamicCardView]** —— 那一份是动态渲染的唯一实现,
 * 这一页与「其他动态」页共用。以前这里另写了一套只认五种形态的分支,于是同一位 UP 发的直播、
 * 音频、番剧更新在空间页悄悄消失,而在别处是有的。
 */
@Composable
private fun DynamicRow(
    dynamic: SpaceDynamicItem,
    onAction: (DynamicAction) -> Unit,
    onLikeDynamic: (String, Boolean) -> Unit,
) {
    // 一条动态一张卡片,与「其他动态」页同一份处理:条目之间不画分割线,边界由底色和圆角
    // 画在卡片自己身上。动态内部本来就有带底色的块(转发、直播、预约),再叠一层横线之后
    // 整页全是线,分不清哪条是条目边界。
    //
    // 整页都是同一个人,所以不重复印他的头像和名字 —— 与上面视频行留空 upName 同一个理由。
    DynamicCardView(
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

/**
 * 投稿 tab 与合集目录([CollectionScreen])共用的视频列表。
 *
 * @param error 已经取好文案的那一句。**这里仍然收字符串**,不收资源 id:两个调用方一个把
 *   资源 id 存在 state 里(投稿 tab),一个还在 state 里存字符串(合集目录),统一成 id 要
 *   一起动那一页,不在这一轮的边界内。
 * @param header 列表顶上跟着一起滚的那一块(投稿 tab 的搜索框与排序)。**它是列表的第一个
 *   条目,不是钉在列表上面的一条** —— 钉住的话页头、tab 栏、它三层叠起来占掉小半屏。
 */
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
    header: (@Composable () -> Unit)? = null,
    /** 宽屏的空间投稿是网格;合集目录页仍是一列。 */
    columns: GridCells = GridCells.Fixed(1),
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
        layout = PagedLayout.Grid(columns),
        header = header,
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

