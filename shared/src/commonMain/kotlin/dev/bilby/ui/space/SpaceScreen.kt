package dev.bilby.ui.space

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.api.BiliResult
import dev.bilby.appendDistinctBy
import dev.bilby.data.DynamicRepository
import dev.bilby.data.FollowGroup
import dev.bilby.data.FollowRepository
import dev.bilby.data.FollowState
import dev.bilby.data.QueueContext
import dev.bilby.data.RelationRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.SidePanelId
import dev.bilby.data.SideSheetPrefs
import dev.bilby.data.SpaceArchiveOrder
import dev.bilby.data.SpaceCollectionItem
import dev.bilby.data.SpaceDynamicItem
import dev.bilby.data.SpaceProfile
import dev.bilby.data.SpaceRepository
import dev.bilby.data.SpaceVideoItem
import dev.bilby.data.UpBrief
import dev.bilby.parseDurationSeconds
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.LocalSystemActions
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.BilbySplitButton
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.CoverAspectRatio
import dev.bilby.ui.components.CoverCornerRadius
import dev.bilby.ui.components.DynamicCardSkeleton
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.LocalCopyNotice
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PagedLayout
import dev.bilby.ui.components.PaneTitle
import dev.bilby.ui.components.PlayingIndicator
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.SearchField
import dev.bilby.ui.components.SelectableTextDialog
import dev.bilby.ui.components.SidePanelLayout
import dev.bilby.ui.components.SidePanelToggle
import dev.bilby.ui.components.SkeletonLine
import dev.bilby.ui.components.SortMenu
import dev.bilby.ui.components.UnfollowConfirmDialog
import dev.bilby.ui.components.VideoCommonMenuItems
import dev.bilby.ui.components.VideoGridCard
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.chargingBadgeText
import dev.bilby.ui.components.collapsingHeader
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.panelCard
import dev.bilby.ui.components.plainTextClip
import dev.bilby.ui.components.rememberCollapsingHeaderState
import dev.bilby.ui.components.skeleton
import dev.bilby.ui.components.touchOnlyPaging
import dev.bilby.ui.dynamic.DynamicAction
import dev.bilby.ui.dynamic.DynamicCardView
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.follow.BlockConfirmDialog
import dev.bilby.ui.follow.GroupPickerController
import dev.bilby.ui.follow.GroupPickerSheet
import dev.bilby.ui.follow.GroupPickerState
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.navigationBarsBottom
import dev.bilby.ui.padScaffoldExceptBottom
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.rememberVideoGridColumnCount
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.tabular
import dev.bilby.ui.windowDragArea
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

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
fun SpaceArchiveTabState.queueContext(mid: Long, page: Int, upName: String = "") =
    QueueContext.UpArchive(mid = mid, order = order, keyword = appliedKeyword, page = page, upName = upName)

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
        loadMoreArchives()
        loadMoreDynamics()
        loadMoreCollections()
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
                            archivesAvailable = if (requested.page == 1 && requested.keyword.isBlank()) {
                                result.value.total > 0 || merged.isNotEmpty()
                            } else {
                                state.archivesAvailable
                            },
                            archives = archives.copy(
                                items = merged,
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
                        val fresh = result.value.items.filterNot { it.listedInArchive }
                        val hasMore = result.value.hasMore && result.value.nextOffset != null
                        state.copy(
                            refreshing = false,
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

    private fun loadProfile() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            val profileResult = async { repository.loadProfile(mid) }
            val relationResult = async { relationRepository.stateOf(mid) }
            val profile = profileResult.await()
            val relation = relationResult.await()
            when (profile) {
                is BiliResult.Ok -> {
                    val followState = when (relation) {
                        is BiliResult.Ok -> relation.value
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

private val HeroBannerCompactHeight = 120.dp
private val HeroBannerExpandedHeight = 132.dp
private val TopBarStandardHeight = 64.dp
private val AvatarRingWidth = 3.dp
private val AvatarBannerOverlap = 34.dp
private val CollectionGridHalfGap = 6.dp
private val FolderLipHeight = 6.dp
private val FolderLipGap = 2.dp

/**
 * 个人空间。头图与身份卡随滚动平滑收起,主标签栏吸顶停留,宽屏右侧承载可收放的动态侧栏。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    /** 动态栏里点开这位 UP 自己发的视频,队列取动态列表。见 [DynamicListTab]。 */
    onDynamicVideoClick: (SpaceDynamicItem) -> Unit,
    onLikeDynamic: (String, Boolean) -> Unit,
    onLiveClick: ((Long) -> Unit)? = null,
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
    vodOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val system = LocalSystemActions.current

    val headerScroll = rememberCollapsingHeaderState()
    val windowSize = rememberBilbyWindowSize()
    val wide = windowSize.isAtLeast(BilbyWindowSize.Expanded)

    val sectionsKnown = state.archivesAvailable != null &&
        state.dynamicsAvailable != null &&
        state.collectionsAvailable != null
    val allTabs = SpaceTab.entries.filter { tab ->
        when (tab) {
            SpaceTab.Archives -> state.archivesAvailable == true
            SpaceTab.Dynamics -> !vodOnly && state.dynamicsAvailable == true
            SpaceTab.Collections -> state.collectionsAvailable == true
        }
    }
    val sheetAvailable = wide && SpaceTab.Dynamics in allTabs && allTabs.size > 1
    val mainTabs = if (sheetAvailable) allTabs - SpaceTab.Dynamics else allTabs
    val sheetOpen = sheetAvailable && state.dynamicsSheet?.open == true
    val mainTab = state.activeTab.takeIf { it in mainTabs } ?: mainTabs.firstOrNull()
    val visibleTabs = setOfNotNull(mainTab, SpaceTab.Dynamics.takeIf { sheetOpen })

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val searching = searchOpen || state.archives.appliedKeyword.isNotBlank()

    // 无论窄屏还是横屏/桌面宽屏，非搜索态有档案数据时一律让头图顶到最上沿，顶栏透明沉浸叠放其上。
    val overlayHero = !searching && state.profile != null
    val collapseFraction = headerScroll.collapsedFraction
    val archiveTotal = state.archives.total.takeIf {
        state.archivesAvailable == true && state.archives.appliedKeyword.isBlank() && it > 0
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            SpaceTopBar(
                profile = state.profile,
                collapseFraction = collapseFraction,
                overlayHero = overlayHero,
                searching = searching,
                keyword = state.archives.keyword,
                refreshing = state.refreshing,
                onRefresh = { onRefresh(visibleTabs) },
                onKeywordChanged = onArchiveKeywordChanged,
                onSearch = onArchiveSearch,
                onOpenSearch = {
                    searchOpen = true
                    onTabSelected(SpaceTab.Archives)
                },
                onCloseSearch = {
                    searchOpen = false
                    onArchiveKeywordChanged("")
                    if (state.archives.appliedKeyword.isNotEmpty()) onArchiveSearch()
                },
                onShare = { system.shareSpace(mid, state.profile?.name.orEmpty()) },
                onBack = onBack,
                dynamicsSheetOpen = state.dynamicsSheet?.open?.takeIf { sheetAvailable },
                onDynamicsSheetOpenChange = onDynamicsSheetOpenChange,
                canSearch = SpaceTab.Archives in allTabs,
            )
        },
    ) { insets ->
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val topInset = insets.calculateTopPadding()
        val targetMinVisiblePx = if (overlayHero) with(density) { topInset.toPx() } else 0f
        if (headerScroll.minVisiblePx != targetMinVisiblePx) {
            headerScroll.minVisiblePx = targetMinVisiblePx
            if (headerScroll.heightPx > 0f) {
                headerScroll.offsetPx = headerScroll.offsetPx.coerceIn(
                    -(headerScroll.heightPx - targetMinVisiblePx).coerceAtLeast(0f),
                    0f,
                )
            }
        }

        val baseBannerHeight = if (wide) HeroBannerExpandedHeight else HeroBannerCompactHeight
        val bannerHeight = if (overlayHero) {
            (topInset - TopBarStandardHeight).coerceAtLeast(0.dp) + baseBannerHeight
        } else {
            baseBannerHeight
        }

        val header: @Composable (Modifier) -> Unit = { paneModifier ->
            state.profile?.let { profile ->
                SpaceHeader(
                    profile = profile,
                    archiveCount = archiveTotal,
                    bannerHeight = bannerHeight,
                    overlayTopBar = overlayHero,
                    onToggleFollow = onToggleFollow,
                    onSetBlocked = onSetBlocked,
                    onOpenGroupPicker = onOpenGroupPicker,
                    onLiveClick = if (vodOnly) null else onLiveClick,
                    modifier = paneModifier,
                )
            }
        }

        val listColumns = GridCells.Fixed(1)

        val profileGate: @Composable (@Composable () -> Unit) -> Unit = { content ->
            when {
                state.loading && state.profile == null -> FullScreenLoading()
                state.error != null && state.profile == null ->
                    FullScreenError(stringResource(state.error), onRetry)
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
                onOpenOwnVideo = onDynamicVideoClick,
                flat = wide,
                modifier = listModifier,
            )
        }

        val tabbedPane: @Composable ColumnScope.(List<SpaceTab>) -> Unit = { tabs ->
            val pagerState = rememberPagerState(initialPage = tabs.indexOf(state.activeTab).coerceAtLeast(0)) {
                tabs.size
            }
            val scope = rememberCoroutineScope()

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
                        val selected = pagerState.currentPage == index
                        Tab(
                            selected = selected,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    text = stringResource(tab.label),
                                    style = if (selected) {
                                        MaterialTheme.typography.titleSmallEmphasized
                                    } else {
                                        MaterialTheme.typography.titleSmall
                                    },
                                )
                            },
                        )
                    }
                }
                val selectedIndex = pagerState.currentPage.coerceIn(tabs.indices)
                if (wide) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        modifier = Modifier.fillMaxWidth().padding(end = Spacing.Comfortable),
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

                            SpaceTab.Dynamics -> dynamicsList(
                                if (wide) {
                                    Modifier
                                        .padding(
                                            start = Spacing.Comfortable,
                                            end = Spacing.Comfortable,
                                            bottom = Spacing.Comfortable,
                                        )
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

        val mainColumn: @Composable ColumnScope.() -> Unit = {
            header(Modifier.collapsingHeader(headerScroll))
            if (sectionsKnown && allTabs.isEmpty() && state.profile != null) {
                EmptyState(stringResource(Res.string.space_empty_all), Modifier.weight(1f))
            } else {
                tabbedPane(mainTabs)
            }
        }

        val containerModifier = if (overlayHero) {
            val horizontalHandled = PaddingValues(
                start = insets.calculateStartPadding(layoutDirection),
                end = insets.calculateEndPadding(layoutDirection),
            )
            Modifier
                .fillMaxSize()
                .padding(horizontalHandled)
                .consumeWindowInsets(
                    PaddingValues(
                        start = horizontalHandled.calculateStartPadding(layoutDirection),
                        top = topInset,
                        end = horizontalHandled.calculateEndPadding(layoutDirection),
                    ),
                )
        } else {
            Modifier.fillMaxSize().padScaffoldExceptBottom(insets)
        }

        if (wide) {
            SidePanelLayout(
                prefs = state.dynamicsSheet,
                ready = sectionsKnown,
                available = sheetAvailable,
                title = stringResource(SpaceTab.Dynamics.label),
                closeDescription = stringResource(Res.string.space_dynamics_sheet_close),
                onOpenChange = onDynamicsSheetOpenChange,
                onWidthChange = onDynamicsSheetWidthChange,
                defaultWidth = DynamicsPanelDefaultWidth,
                minWidth = DynamicsPanelMinWidth,
                modifier = containerModifier,
                main = { Column(modifier = Modifier.fillMaxSize(), content = mainColumn) },
                panel = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = if (overlayHero) topInset else 0.dp),
                    ) {
                        profileGate { dynamicsList(Modifier.fillMaxSize()) }
                    }
                },
            )
        } else {
            AdaptiveContent(
                modifier = containerModifier,
                maxWidth = Breakpoints.ReadableWidth,
            ) {
                Column(modifier = Modifier.fillMaxSize(), content = mainColumn)
            }
        }
    }

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
 * 空间页顶栏:随头图收起进度平滑填充底色,并在头图区名字滚过顶栏后淡入小头像与昵称。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpaceTopBar(
    profile: SpaceProfile?,
    collapseFraction: Float,
    overlayHero: Boolean,
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
    dynamicsSheetOpen: Boolean?,
    onDynamicsSheetOpenChange: (Boolean) -> Unit,
    canSearch: Boolean,
) {
    val focusRequester = remember { FocusRequester() }
    LocalWindowCaption.current?.Host()
    val captionSlot = rememberCaptionSlot()
    val colorScheme = MaterialTheme.colorScheme

    val barFillFraction = if (searching) 1f else ((collapseFraction - 0.15f) / 0.7f).coerceIn(0f, 1f)
    val baseColor = if (overlayHero) colorScheme.surfaceContainer.copy(alpha = 0f) else colorScheme.surface
    val containerColor = lerp(baseColor, colorScheme.surfaceContainer, barFillFraction)
    val titleReveal = ((collapseFraction - 0.55f) / 0.30f).coerceIn(0f, 1f)

    TopAppBar(
        modifier = captionSlot.modifier,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = colorScheme.surfaceContainer,
        ),
        title = {
            if (searching) {
                SearchField(
                    value = keyword,
                    onValueChange = onKeywordChanged,
                    placeholder = stringResource(Res.string.space_search_hint),
                    onSearch = onSearch,
                    focusRequester = focusRequester,
                )
                LaunchedEffect(Unit) {
                    if (keyword.isEmpty()) focusRequester.requestFocus()
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = CaptionDragHeight)
                        .windowDragArea(captionSlot.atTop),
                ) {
                    if (profile != null && titleReveal > 0f) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            modifier = Modifier.graphicsLayer {
                                alpha = titleReveal
                                translationY = (1f - titleReveal) * 6.dp.toPx()
                            },
                        ) {
                            Avatar(url = profile.faceUrl, size = Dimens.AvatarNested)
                            Text(
                                text = profile.name,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        navigationIcon = {
            val backLabel = stringResource(Res.string.action_back)
            BilbyTooltip(text = backLabel, shortcut = "Alt+←") {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backLabel,
                    )
                }
            }
        },
        actions = {
            RefreshAction(refreshing = refreshing, onRefresh = onRefresh)
            if (searching) {
                val closeLabel = stringResource(Res.string.space_search_close)
                BilbyTooltip(text = closeLabel) {
                    IconButton(onClick = onCloseSearch) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = closeLabel,
                        )
                    }
                }
            } else {
                if (canSearch) {
                    val searchLabel = stringResource(Res.string.space_search_hint)
                    BilbyTooltip(text = searchLabel) {
                        IconButton(onClick = onOpenSearch) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = searchLabel,
                            )
                        }
                    }
                }
                val shareLabel = stringResource(Res.string.action_share)
                BilbyTooltip(text = shareLabel) {
                    IconButton(onClick = onShare) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = shareLabel,
                        )
                    }
                }
            }
            if (dynamicsSheetOpen != null) {
                SidePanelToggle(
                    open = dynamicsSheetOpen,
                    onOpenChange = onDynamicsSheetOpenChange,
                    description = stringResource(Res.string.space_dynamics_sheet_toggle),
                )
            }
            captionSlot.buttons?.invoke()
        },
    )
}

/**
 * 空间主视觉头部:
 * - 通栏 `top_photo` 头图(空地址时退回富层次主题渐变),顶缘铺可读性遮罩、底缘平滑融进 `surface`;
 * - 72dp `AvatarHero` 套 `3dp` `surface` 描边跨压头图底边,右侧与关注分体按钮基线对齐;
 * - 昵称提升至 `headlineSmallEmphasized`,配 `LevelBadge`、一键复制 `UID` 胶囊与右键/长按复制菜单;
 * - 粉丝与投稿计数采用 `titleSmallEmphasized.tabular`;
 * - 签名支持点击展开/收起、右键/长按复制或打开自由选词弹窗,桌面展开态支持直接划词;
 * - 直播入口升级为 `primaryContainer` / `onPrimaryContainer` 大圆角卡片。
 */
@OptIn(
    ExperimentalMaterial3ExpressiveApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class,
)
@Composable
private fun SpaceHeader(
    profile: SpaceProfile,
    archiveCount: Int?,
    bannerHeight: Dp,
    overlayTopBar: Boolean,
    onToggleFollow: () -> Unit,
    onSetBlocked: (Boolean) -> Unit,
    onOpenGroupPicker: () -> Unit,
    onLiveClick: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val copyNotice = LocalCopyNotice.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(Res.string.comment_copied)

    val copyValue: (String, String) -> Unit = { label, text ->
        scope.launch {
            clipboard.setClipEntry(plainTextClip(label, text))
            copyNotice(copiedText)
        }
    }

    var bioExpanded by rememberSaveable(profile.mid) { mutableStateOf(false) }
    var bioHasOverflow by remember(profile.sign) { mutableStateOf(false) }
    var selectingBio by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.Cozy),
    ) {
        // 1. 头图 + 跨底边头像与关注控制栏
        Box(modifier = Modifier.fillMaxWidth()) {
            val fallbackGradient = Brush.verticalGradient(
                colors = listOf(
                    colorScheme.primaryContainer.copy(alpha = 0.45f),
                    colorScheme.surfaceContainer,
                    colorScheme.surface,
                ),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bannerHeight)
                    .background(fallbackGradient),
            ) {
                if (profile.topPhotoUrl.isNotBlank()) {
                    BiliAsyncImage(
                        url = profile.topPhotoUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // 顶缘主题同向渐变遮罩:兼顾浅色与深色主题下顶栏图标与窗口按钮对比度
                    val topScrimAlpha = if (overlayTopBar) 0.84f else 0.26f
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to colorScheme.surface.copy(alpha = topScrimAlpha),
                                    0.46f to colorScheme.surface.copy(alpha = if (overlayTopBar) 0.36f else 0.08f),
                                    0.72f to Color.Transparent,
                                ),
                            ),
                    )
                    // 底缘平滑汇入页面 surface,消除头图硬切线
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0.42f to Color.Transparent,
                                    0.78f to colorScheme.surface.copy(alpha = 0.72f),
                                    1f to colorScheme.surface,
                                ),
                            ),
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = (bannerHeight - AvatarBannerOverlap).coerceAtLeast(0.dp),
                        start = Spacing.Comfortable,
                        end = Spacing.Comfortable,
                    ),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Box(
                    modifier = Modifier
                        .size(Dimens.AvatarHero + AvatarRingWidth * 2)
                        .background(colorScheme.surface, CircleShape)
                        .padding(AvatarRingWidth),
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(url = profile.faceUrl, size = Dimens.AvatarHero)
                }

                SpaceFollowControl(
                    followState = profile.followState,
                    name = profile.name,
                    onToggleFollow = onToggleFollow,
                    onSetBlocked = onSetBlocked,
                    onOpenGroupPicker = onOpenGroupPicker,
                )
            }
        }

        // 2. 身份信息、统计、签名与直播入口
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = Spacing.Comfortable,
                    end = Spacing.Comfortable,
                    top = Spacing.Tight,
                ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            val copyNicknameLabel = stringResource(Res.string.action_copy_nickname)
            val copyUidLabel = stringResource(Res.string.action_copy_uid)
            val nameMenuLabel = stringResource(Res.string.menu_item_actions)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                ContextMenuBox(
                    onClick = null,
                    menuLabel = nameMenuLabel,
                    menu = { close ->
                        DropdownMenuItem(
                            onClick = {
                                close()
                                copyValue(copyNicknameLabel, profile.name)
                            },
                            text = { Text(copyNicknameLabel) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            shape = menuItemShape(0, 2),
                        )
                        DropdownMenuItem(
                            onClick = {
                                close()
                                copyValue(copyUidLabel, profile.mid.toString())
                            },
                            text = { Text(copyUidLabel) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            shape = menuItemShape(1, 2),
                        )
                    },
                ) { openMenu ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        modifier = Modifier
                            .clip(MaterialTheme.shapes.extraSmall)
                            .semantics {
                                onLongClick(label = nameMenuLabel) {
                                    openMenu()
                                    true
                                }
                            }
                            .pointerInput(openMenu) {
                                detectTapGestures(onLongPress = { openMenu() })
                            },
                    ) {
                        Text(
                            text = profile.name,
                            style = MaterialTheme.typography.headlineSmallEmphasized,
                            color = colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        LevelBadge(
                            level = profile.level,
                            senior = false,
                            height = Dimens.LevelBadgeHeight,
                        )
                    }
                }

                UidCopyPill(
                    mid = profile.mid,
                    onCopy = { copyValue(copyUidLabel, profile.mid.toString()) },
                )
            }

            // 粉丝数与投稿数(等宽强调数字 + 低强调标签,段间留白,绝不用中点)
            SpaceStatsRow(
                followerCount = profile.follower,
                archiveCount = archiveCount,
            )

            // 签名:点按展开/收起 + 右键/长按复制或自由选词 + 桌面展开态划词
            if (profile.sign.isNotBlank()) {
                val signText = profile.sign.trim()
                val copyBioLabel = stringResource(Res.string.action_copy_bio)
                val selectTextLabel = stringResource(Res.string.action_select_text)
                val pointerSelectsBio = bioExpanded && !LocalPointerSource.current.isTouchLike

                ContextMenuBox(
                    onClick = if (pointerSelectsBio) null else ({ bioExpanded = !bioExpanded }),
                    menuLabel = nameMenuLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .animateContentSize(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()),
                    menu = { close ->
                        DropdownMenuItem(
                            onClick = {
                                close()
                                copyValue(copyBioLabel, signText)
                            },
                            text = { Text(copyBioLabel) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            shape = menuItemShape(0, 2),
                        )
                        DropdownMenuItem(
                            onClick = {
                                close()
                                selectingBio = true
                            },
                            text = { Text(selectTextLabel) },
                            leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                            shape = menuItemShape(1, 2),
                        )
                    },
                ) { _ ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    ) {
                        val bioBody: @Composable () -> Unit = {
                            Text(
                                text = signText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = if (bioExpanded) Int.MAX_VALUE else 2,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { result ->
                                    if (!bioExpanded) {
                                        bioHasOverflow = result.hasVisualOverflow
                                    }
                                },
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            if (pointerSelectsBio) {
                                SelectionContainer(content = bioBody)
                            } else {
                                bioBody()
                            }
                        }
                        if (bioHasOverflow || bioExpanded) {
                            Icon(
                                imageVector = if (bioExpanded) {
                                    Icons.Filled.KeyboardArrowUp
                                } else {
                                    Icons.Filled.KeyboardArrowDown
                                },
                                contentDescription = if (bioExpanded) {
                                    stringResource(Res.string.feed_burst_collapse)
                                } else {
                                    null
                                },
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .then(
                                        if (pointerSelectsBio) {
                                            Modifier
                                                .clip(CircleShape)
                                                .clickable(role = Role.Button) { bioExpanded = false }
                                        } else {
                                            Modifier
                                        },
                                    )
                                    .padding(2.dp)
                                    .size(Dimens.IconInline),
                            )
                        }
                    }
                }
            }

            if (onLiveClick != null) {
                profile.liveRoom?.let { live ->
                    SpaceLiveRoomCard(
                        title = live.title,
                        onClick = { onLiveClick(live.roomId) },
                    )
                }
            }
        }
    }

    if (selectingBio && profile.sign.isNotBlank()) {
        SelectableTextDialog(
            text = profile.sign.trim(),
            onDismiss = { selectingBio = false },
        )
    }
}

@Composable
private fun UidCopyPill(
    mid: Long,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copyUidLabel = stringResource(Res.string.action_copy_uid)
    Surface(
        onClick = onCopy,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier.semantics { contentDescription = "$copyUidLabel $mid" },
    ) {
        Text(
            text = "UID: $mid",
            style = MaterialTheme.typography.labelSmall.tabular,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = 2.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpaceStatsRow(
    followerCount: Long,
    archiveCount: Int?,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val numberStyle = MaterialTheme.typography.titleSmallEmphasized.tabular
    val labelStyle = MaterialTheme.typography.labelSmall

    val followerNum = formatCount(followerCount)
    val followerFull = stringResource(Res.string.space_followers, followerNum)
    val followerAnnotated = buildAnnotatedString {
        val idx = followerFull.indexOf(followerNum)
        if (idx >= 0) {
            val prefix = followerFull.substring(0, idx)
            val suffix = followerFull.substring(idx + followerNum.length)
            if (prefix.isNotEmpty()) {
                withStyle(labelStyle.toSpanStyle().copy(color = colorScheme.onSurfaceVariant)) {
                    append(prefix)
                }
            }
            withStyle(numberStyle.toSpanStyle().copy(color = colorScheme.onSurface)) {
                append(followerNum)
            }
            if (suffix.isNotEmpty()) {
                val paddedSuffix = if (suffix.startsWith(" ")) suffix else " $suffix"
                withStyle(labelStyle.toSpanStyle().copy(color = colorScheme.onSurfaceVariant)) {
                    append(paddedSuffix)
                }
            }
        } else {
            withStyle(numberStyle.toSpanStyle().copy(color = colorScheme.onSurface)) {
                append(followerFull)
            }
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable),
    ) {
        Text(text = followerAnnotated, maxLines = 1)
        if (archiveCount != null && archiveCount > 0) {
            val archiveNum = formatCount(archiveCount.toLong())
            val archiveLabel = stringResource(Res.string.space_tab_archives)
            Text(
                text = buildAnnotatedString {
                    withStyle(numberStyle.toSpanStyle().copy(color = colorScheme.onSurface)) {
                        append(archiveNum)
                    }
                    withStyle(labelStyle.toSpanStyle().copy(color = colorScheme.onSurfaceVariant)) {
                        append(" ")
                        append(archiveLabel)
                    }
                },
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SpaceLiveRoomCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        modifier = modifier
            .fillMaxWidth()
            .interactiveCard(
                shape = MaterialTheme.shapes.large,
                baseColor = colorScheme.primaryContainer,
                hoverColor = lerp(colorScheme.primaryContainer, colorScheme.primary, 0.14f),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.Cozy, vertical = Spacing.Cozy),
    ) {
        Surface(
            color = colorScheme.primary,
            contentColor = colorScheme.onPrimary,
            shape = CircleShape,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                modifier = Modifier.padding(horizontal = Spacing.Tight + 2.dp, vertical = Spacing.Hair),
            ) {
                PlayingIndicator(
                    active = true,
                    color = colorScheme.onPrimary,
                    modifier = Modifier.size(Dimens.PlayingIndicatorInline),
                )
                Text(
                    text = stringResource(Res.string.space_live_now),
                    style = MaterialTheme.typography.labelSmallEmphasized,
                    color = colorScheme.onPrimary,
                    maxLines = 1,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMediumEmphasized,
            color = colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Surface(
            color = colorScheme.surface.copy(alpha = 0.36f),
            contentColor = colorScheme.onPrimaryContainer,
            shape = CircleShape,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(Spacing.Hair + 2.dp).size(16.dp),
            )
        }
    }
}

/**
 * 关注,以及跟着关注走的那几件事:设置分组、取消关注、拉黑。M3 Expressive 的分体按钮。
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
        BilbySplitButton(
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

private val ArchiveOrders = listOf(
    SpaceArchiveOrder.Pubdate to Res.string.space_order_pubdate,
    SpaceArchiveOrder.Click to Res.string.space_order_click,
)

@Composable
private fun ArchivesTab(
    state: SpaceArchiveTabState,
    onOrderChanged: (SpaceArchiveOrder) -> Unit,
    onListenUp: () -> Unit,
    onLoadMore: () -> Unit,
    onVideoClick: (SpaceVideoItem) -> Unit,
    columns: GridCells,
    showControls: Boolean,
    modifier: Modifier = Modifier,
) {
    VideoListTab(
        columns = columns,
        items = state.items,
        appending = state.appending,
        hasMore = state.hasMore,
        loading = state.loading,
        error = state.error?.let { stringResource(it) },
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
        header = if (!showControls) null else {
            {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.Tight),
                ) {
                    ArchiveListenButton(state, onListenUp)
                    Spacer(modifier = Modifier.weight(1f))
                    ArchiveSortMenu(state, onOrderChanged)
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ArchiveListenButton(
    state: SpaceArchiveTabState,
    onListenUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilledTonalButton(
        onClick = onListenUp,
        enabled = state.items.isNotEmpty(),
        contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
        modifier = modifier,
    ) {
        Icon(
            Icons.Filled.Headphones,
            contentDescription = null,
            modifier = Modifier.size(Dimens.IconInline),
        )
        Text(
            stringResource(Res.string.space_listen_up),
            style = MaterialTheme.typography.labelLargeEmphasized,
            modifier = Modifier.padding(start = Spacing.Hair + 2.dp),
        )
    }
}

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
    @Suppress("UNUSED_PARAMETER") columns: GridCells,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val gridCols = rememberVideoGridColumnCount(maxWidth)
        PagedColumn(
            items = state.items,
            key = { "${it.isSeason}-${it.id}" },
            layout = PagedLayout.Grid(GridCells.Fixed(gridCols)),
            skeletonRow = { CollectionFolderSkeleton(Modifier.padding(CollectionGridHalfGap)) },
            loading = state.loading,
            appending = state.appending,
            hasMore = state.hasMore,
            error = state.error?.let { stringResource(it) },
            emptyText = stringResource(Res.string.space_empty_collections),
            onLoadMore = onLoadMore,
            contentPadding = PaddingValues(
                start = Spacing.Comfortable - CollectionGridHalfGap,
                end = Spacing.Comfortable - CollectionGridHalfGap,
                top = Spacing.Tight,
                bottom = Spacing.Comfortable + navigationBarsBottom(),
            ),
            modifier = Modifier.fillMaxSize(),
        ) { item ->
            CollectionFolderCard(
                item = item,
                onClick = { onCollectionClick(item) },
                modifier = Modifier.padding(CollectionGridHalfGap),
            )
        }
    }
}

/**
 * 叠层媒体夹卡片:顶缘阶梯式文件夹唇边 + 16:10 封面 + 右下角等宽计数胶囊 + 两行强调标题。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun CollectionFolderCard(
    item: SpaceCollectionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val copyNotice = LocalCopyNotice.current
    val scope = rememberCoroutineScope()
    val copyTitleLabel = stringResource(Res.string.action_copy_title)
    val copiedText = stringResource(Res.string.comment_copied)

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        modifier = modifier,
        menu = { close ->
            DropdownMenuItem(
                onClick = {
                    close()
                    scope.launch {
                        clipboard.setClipEntry(plainTextClip(copyTitleLabel, item.name))
                        copyNotice(copiedText)
                    }
                },
                text = { Text(copyTitleLabel) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                shape = menuItemShape(0, 1),
            )
        },
    ) { openMenu ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .interactiveCard(
                    shape = MaterialTheme.shapes.large,
                    baseColor = colorScheme.surfaceContainer,
                    hoverColor = lerp(colorScheme.surfaceContainer, colorScheme.surfaceContainerHigh, 0.6f),
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                )
                .padding(Spacing.Tight),
        ) {
            // 阶梯文件夹唇边(surfaceContainerHigh)
            Box(
                modifier = Modifier
                    .padding(horizontal = Spacing.Cozy)
                    .fillMaxWidth()
                    .height(FolderLipHeight)
                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                    .background(colorScheme.surfaceContainerHigh),
            )
            Spacer(modifier = Modifier.height(FolderLipGap))

            // 16:10 封面与右下角「合集  24」/「系列  12」计数胶囊
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(CoverAspectRatio)
                    .clip(RoundedCornerShape(CoverCornerRadius))
                    .background(colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (item.coverUrl.isNotBlank()) {
                    BiliAsyncImage(
                        url = item.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                    )
                }

                val kindText = stringResource(
                    if (item.isSeason) Res.string.space_collection_season else Res.string.space_collection_series,
                )
                Text(
                    text = "$kindText$MetaSeparator${item.total}",
                    style = MaterialTheme.typography.labelSmallEmphasized.tabular,
                    color = FixedColors.OnMedia,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(FixedColors.ScrimOnMedia, MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = Spacing.Hair + 2.dp, vertical = 2.dp),
                )
            }

            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colorScheme.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.Tight, start = 2.dp, end = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
private fun CollectionFolderSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CoverAspectRatio)
                .skeleton(RoundedCornerShape(CoverCornerRadius)),
        )
        SkeletonLine(Modifier.fillMaxWidth(0.8f))
        SkeletonLine(Modifier.fillMaxWidth(0.5f))
    }
}

private val DynamicsPanelDefaultWidth = 400.dp
private val DynamicsPanelMinWidth = 320.dp

@Composable
private fun DynamicListTab(
    state: SpaceListTabState,
    onLoadMore: () -> Unit,
    onAction: (DynamicAction) -> Unit,
    onLikeDynamic: (String, Boolean) -> Unit,
    onOpenOwnVideo: (SpaceDynamicItem) -> Unit,
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
        layout = if (flat) {
            PagedLayout.Staggered(StaggeredGridCells.Adaptive(Breakpoints.DynamicColumnMinWidth), Spacing.Tight)
        } else {
            PagedLayout.SingleColumn
        },
        contentPadding = if (flat) {
            PaddingValues(start = Spacing.Tight, end = Spacing.Tight, bottom = Spacing.Tight + navigationBarsBottom())
        } else {
            PaddingValues(bottom = navigationBarsBottom())
        },
    ) { dynamic ->
        val itemAction: (DynamicAction) -> Unit = { action ->
            val ownVideo = dynamic.video
            if (action is DynamicAction.OpenVideo && ownVideo != null && action.bvid == ownVideo.bvid) {
                onOpenOwnVideo(dynamic)
            } else {
                onAction(action)
            }
        }
        if (flat) {
            DynamicCardView(
                card = dynamic.card,
                onAction = itemAction,
                onLike = { like -> onLikeDynamic(dynamic.card.id, like) },
                showAuthor = false,
            )
        } else {
            DynamicRow(dynamic = dynamic, onAction = itemAction, onLikeDynamic = onLikeDynamic)
        }
    }
}

@Composable
private fun DynamicRow(
    dynamic: SpaceDynamicItem,
    onAction: (DynamicAction) -> Unit,
    onLikeDynamic: (String, Boolean) -> Unit,
) {
    DynamicCardView(
        card = dynamic.card,
        onAction = onAction,
        onLike = { like -> onLikeDynamic(dynamic.card.id, like) },
        showAuthor = false,
        modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Hair),
    )
}

/**
 * 投稿 tab 与合集目录([CollectionScreen])共用的响应式视频列表:
 * - 单列(`Compact` 或侧栏压缩后的窄主区)采用拇指友好的 [VideoRow];
 * - 多列(`columns > 1`)采用纵向 16:10 [VideoGridCard],保留充电角标与右键/长按上下文菜单。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
    @Suppress("UNUSED_PARAMETER") columns: GridCells = GridCells.Fixed(1),
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val gridCols = rememberVideoGridColumnCount(maxWidth)
        val useGrid = gridCols > 1
        val effectiveColumns = if (useGrid) GridCells.Fixed(gridCols) else GridCells.Fixed(1)
        val contentPadding = if (useGrid) {
            PaddingValues(
                start = Spacing.Tight - 2.dp,
                end = Spacing.Tight - 2.dp,
                top = Spacing.Hair,
                bottom = Spacing.Comfortable + navigationBarsBottom(),
            )
        } else {
            PaddingValues(bottom = navigationBarsBottom())
        }

        PagedColumn(
            items = items,
            key = { it.bvid },
            loading = loading,
            appending = appending,
            hasMore = hasMore,
            error = error,
            emptyText = emptyText,
            onLoadMore = onLoadMore,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            layout = PagedLayout.Grid(effectiveColumns),
            header = header?.let { headerContent ->
                {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = if (useGrid) Spacing.Tight + 2.dp else Spacing.Comfortable),
                    ) {
                        headerContent()
                    }
                }
            },
        ) { item ->
            val rowUi = VideoRowUi(
                title = item.title,
                coverUrl = item.coverUrl,
                durationText = item.durationText,
                dateText = formatDate(item.publishedAtEpochSeconds),
                playText = item.playCountText,
                danmakuText = item.danmakuCountText,
                typeBadge = chargingBadgeText(item.chargingOnly),
            )
            ContextMenuBox(
                onClick = null,
                menuLabel = stringResource(Res.string.menu_item_actions),
                menu = { close ->
                    VideoCommonMenuItems(
                        bvid = item.bvid,
                        title = item.title,
                        close = close,
                        coverUrl = item.coverUrl,
                        upName = item.upName,
                        durationSeconds = parseDurationSeconds(item.durationText),
                    )
                },
            ) { openMenu ->
                if (useGrid) {
                    VideoGridCard(
                        ui = rowUi,
                        onClick = { onVideoClick(item) },
                        onLongClick = openMenu,
                        modifier = Modifier.padding(2.dp),
                    )
                } else {
                    VideoRow(
                        item = rowUi,
                        onClick = { onVideoClick(item) },
                        onLongClick = openMenu,
                    )
                }
            }
        }
    }
}

private val DateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatDate(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(DateFormatter)
