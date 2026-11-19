package dev.bilby.ui.feed

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.HorizontalUncontainedCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.data.LiveUpBrief
import dev.bilby.data.UpBrief
import dev.bilby.data.model.FeedEntry
import dev.bilby.parseDurationSeconds
import dev.bilby.ui.LocalAppFullscreen
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FirstScreenState
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.ListScrollbar
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.MediaBadge
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.PrefetchNearEnd
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.RegisterPageRefresh
import dev.bilby.ui.components.VideoCommonMenuItems
import dev.bilby.ui.components.VideoGridCard
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.chargingBadgeText
import dev.bilby.ui.components.fadingRightEdge
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.rememberCopyAction
import dev.bilby.ui.components.verticalWheelScrollsRow
import dev.bilby.ui.components.videoCommonMenuItemCount
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.rememberVideoGridColumnCount
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.rememberReducedMotion
import dev.bilby.ui.theme.tabular
import dev.bilby.ui.windowDragArea
import java.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

@Immutable
data class FeedUiState(
    val items: List<FeedEntry> = emptyList(),
    val loading: Boolean = false, // 首屏加载
    val appending: Boolean = false, // 追加下一页
    val hasMore: Boolean = true,
    val error: String? = null,
    /** 下拉刷新中。与 loading 分开:首屏空白加载和「列表还在、顶上转圈」是两种反馈。 */
    val refreshing: Boolean = false,
    /**
     * 顶上那排人。**优先是「特别关注」分组**(`tagid = -10`),没划过特别关注时退回 portal 的
     * `up_list`(最常访问),由 [topUpsAreSpecial] 说明当前是哪一份。
     *
     * 这里原本只有最常访问 —— 一份 B 站按访问频次算好的排序,依据不透明也调不了,而这一排是
     * 这一页最显眼的导航。特别关注是用户自己划出来的一组人,谁在这里由他自己决定。两者的顺序
     * 都用服务端给的,本地不排也不缓存。
     *
     * 取不到就是空列表,整排消失,不占位、不显示错误:这一排是快捷方式,
     * 拿不到它不妨碍这一页做正事。
     */
    val topUps: List<UpBrief> = emptyList(),
    /** [topUps] 是特别关注(true)还是退回来的最常访问(false)。小标题按它取词。 */
    val topUpsAreSpecial: Boolean = false,
    /**
     * 关注的人里此刻正在直播的那些。和 [topUps] 是两份名单,不是同一份的子集 ——
     * 一个人可以在播而不在特别关注里。
     */
    val liveUps: List<LiveUpBrief> = emptyList(),
    /** 一共有几个人在播。可能大于 [liveUps] 的长度,服务端只给这一屏的那几个。 */
    val liveCount: Int = 0,
    /**
     * 进这一屏时读到的「上次读到哪儿了」(DESIGN 2.1)。每次进屏取一次快照,进屏期间不随滚动
     * 落盘而更新 —— 跟着落盘走的话分隔线会追着当前滚动位置跑,什么都分不出来;而只在 VM 创建
     * 时取一次的那一版,这条线会钉在一个越来越旧的位置活满整个进程(见 FeedViewModel.onEnterScreen)。
     * null 表示从没记过(第一次用)或还没读出来。
     */
    val readMarkerEntryId: String? = null,
    /**
     * 开屏定位还没做过。做过之后永远为 false —— 它和 [readMarkerEntryId] 是两件事:分隔线要一直
     * 画着(用户翻回去还得认得出哪儿是分界),而"滚到分隔线"只发生一次。
     */
    val pendingLocate: Boolean = true,
    /** 刚排除掉一位,等着给一句话和一个撤销。见 [ExcludeUndo]。 */
    val excludeUndo: ExcludeUndo? = null,
    /** 刚加过稍后再看,报一句就完。见 [ToViewNotice]。 */
    val toViewNotice: ToViewNotice? = null,
)

/**
 * 刚被排除的那一位。**排除当场生效,撤销在这条 snackbar 上**,不再先弹一个确认对话框:
 * 一个可撤销的操作不值得一次拦截,而排除现在真的撤得回来了(设置里那份名单可以逐个恢复)。
 *
 * [id] 是本地递增的序号,不是 mid:连着排除同一个人两次(撤销之后又排除)要能重新弹一次,
 * 而按 mid 做 key 的 LaunchedEffect 认不出第二次。
 */
@Immutable
data class ExcludeUndo(val id: Long, val mid: Long, val name: String)

/**
 * 刚把一条加进稍后再看。**只报一句,没有撤销** —— 稍后再看是只进不出的,移除在那一页做
 * (见 [FeedViewModel.addToView])。
 *
 * [succeeded] 要带上:这个动作在列表上留不下任何痕迹,不报的话成功和失败完全同形。
 * [id] 是本地递增的序号,理由同 [ExcludeUndo.id] —— 连着加两条要能各弹一次。
 */
@Immutable
data class ToViewNotice(val id: Long, val succeeded: Boolean)

/**
 * 已读位置在当前已加载列表里的下标。**用 id 定位而不是记下标本身**是 DESIGN 2.1 的原话
 * (`FeedReadPositionEntity` 的注释同理):新投稿插入顶部会让下标整体位移,记下标就会指错。
 *
 * 返回 null 的三种情况:没记过;记录不在当前已加载的范围内(翻页翻不到那么远,老实放弃,
 * 不为了找它而自动多翻页 —— 见 FeedViewModel 的取舍);记录就是列表最新一条,上面没有
 * 「新内容」可分。
 */
internal fun List<FeedEntry>.indexOfReadMarker(lastReadEntryId: String?): Int? =
    lastReadEntryId
        ?.let { entryId -> indexOfFirst { it.id == entryId } }
        ?.takeIf { it > 0 }

/**
 * 动态流。列表本身刻意不做特殊设计(DESIGN 2.1):没有红点、没有未读计数。
 *
 * **动态流不是有限集合。** 向下翻页可以持续到关注 UP 主很早以前的投稿,实践中不会到达末尾。
 * 这一点此前在本注释、README 与风格指南中均被写成"天生能刷完",与事实不符,已一并更正。
 * 需要保证的是翻页只增补所关注 UP 主更早时间的投稿,不存在随翻页扩充的候选池。
 *
 * **保留下拉刷新。** 该功能一度被移除,理由是"下拉刷新属于变比率奖励的仪式"。该理由的前提
 * 不成立:变比率奖励要求每次操作都可能产生新结果,而此处刷新返回的只有关注 UP 主在此期间的
 * 实际投稿。移除刷新不减少使用时长,只会导致退出重进,或在无从判断是否有更新的情况下等待。
 *
 * @param contentPadding 由外层给的内边距(顶栏和底部导航栏的高度)。用 contentPadding
 *   而不是外层 padding,内容才能滚到栏底下去而静止时又不被遮住。
 */
@Composable
fun FeedScreen(
    state: FeedUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onItemClick: (FeedEntry) -> Unit,
    onUpClick: (Long) -> Unit,
    /** 从「正在直播」那张名单里选了一个,进他的直播间。 */
    onLiveClick: (Long) -> Unit,
    onOpenFollowings: () -> Unit,
    /** 折起来的那一半:图文、转发、直播……(DESIGN 2.1)。 */
    onOpenOtherDynamics: () -> Unit = {},
    /** UP 主推送来的私信,见 FeedHeader。 */
    onOpenPushes: () -> Unit = {},
    /** 手动立即进入夜间静息模式。 */
    onEnterNightMode: () -> Unit = {},
    onExcludeUp: (Long, String) -> Unit = { _, _ -> },
    onScrollPositionChanged: (String) -> Unit = {},
    /** 开屏定位已经做过(或确定做不成)。见 [FeedUiState.pendingLocate]。 */
    onLocated: () -> Unit = {},
    /** 进这一屏。分隔线的位置在这时取快照,见 [FeedUiState.readMarkerEntryId]。 */
    onEnter: () -> Unit = {},
    /** snackbar 上那个「撤销」被按了。 */
    onUndoExclude: (Long) -> Unit = {},
    /** 那句话说完了,见 [FeedViewModel.clearExcludeUndo]。 */
    onExcludeUndoShown: () -> Unit = {},
    /** 把一条投稿加进稍后再看。只对视频给,专栏没有这个动作。 */
    onAddToView: (String) -> Unit = {},
    /** 那句话说完了,见 [FeedViewModel.clearToViewNotice]。 */
    onToViewNoticeShown: () -> Unit = {},
    /** 每变一次就回到顶部。重按底栏上当前这一格时由 MainActivity 递增。 */
    scrollToTop: Int = 0,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    // 这段 composition 的寿命就是"这一次进屏":进 UP 空间、切 tab 都会销毁它,回来时重跑。
    LaunchedEffect(Unit) { onEnter() }

    // snackbar 的宿主在这一页自己身上,不穿到导航层 —— 同一条判断见 FavFolderScreen 的注释。
    val snackbarHostState = remember { SnackbarHostState() }
    val undo = state.excludeUndo
    val undoText = undo?.let { stringResource(Res.string.feed_excluded, it.name) }
    val undoLabel = stringResource(Res.string.action_undo)
    LaunchedEffect(undo?.id) {
        if (undo == null || undoText == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = undoText,
            actionLabel = undoLabel,
            duration = SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed) onUndoExclude(undo.mid) else onExcludeUndoShown()
    }

    val toViewNotice = state.toViewNotice
    val toViewAdded = stringResource(Res.string.feed_toview_added)
    val toViewFailed = stringResource(Res.string.feed_toview_failed)
    LaunchedEffect(toViewNotice?.id) {
        if (toViewNotice == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(if (toViewNotice.succeeded) toViewAdded else toViewFailed)
        onToViewNoticeShown()
    }

    Box(modifier = modifier.fillMaxSize()) {
        FirstScreenState(
            loading = state.loading,
            error = state.error,
            isEmpty = state.items.isEmpty(),
            onRetry = onRetry,
        ) {
            FeedList(
                state = state,
                onRefresh = onRefresh,
                onLoadMore = onLoadMore,
                onItemClick = onItemClick,
                onUpClick = onUpClick,
                onLiveClick = onLiveClick,
                onExcludeUp = onExcludeUp,
                onAddToView = onAddToView,
                onOpenFollowings = onOpenFollowings,
                onOpenOtherDynamics = onOpenOtherDynamics,
                onOpenPushes = onOpenPushes,
                onScrollPositionChanged = onScrollPositionChanged,
                onLocated = onLocated,
                scrollToTop = scrollToTop,
                modifier = Modifier,
                contentPadding = contentPadding,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
    }
}

@Composable
private fun FeedList(
    state: FeedUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (FeedEntry) -> Unit,
    onUpClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenOtherDynamics: () -> Unit,
    onOpenPushes: () -> Unit,
    onScrollPositionChanged: (String) -> Unit,
    onLocated: () -> Unit,
    scrollToTop: Int,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyGridState()
    // 只认"进这次组合之后又变了"。计数器由 MainActivity 持有,切走再切回来时它带着上一次的
    // 值,而 LaunchedEffect 进组合就跑一次 —— 光判非零的话,每次回到动态页都会补滚一下,
    // 把下面那套「上次看到哪」的定位覆盖掉。
    var handledScrollToTop by remember { mutableIntStateOf(scrollToTop) }
    LaunchedEffect(scrollToTop) {
        if (scrollToTop != handledScrollToTop) {
            handledScrollToTop = scrollToTop
            listState.animateScrollToItem(0)
        }
    }
    val markerIndex = remember(state.items, state.readMarkerEntryId) {
        state.items.indexOfReadMarker(state.readMarkerEntryId)
    }
    val windowSize = rememberBilbyWindowSize()
    val wide = windowSize.isAtLeast(BilbyWindowSize.Expanded)
    val pinnedHeader = LocalWindowCaption.current != null
    // 那一排头像排在动态流前面,分隔线/条目在网格里的绝对下标要把它加回来。
    // 「最常访问」那一格在没人可显示时整格不画,正在直播的那一格自己也可以撑起它 ——
    // 判据必须和下面渲染时用的是同一个,差一格就是开屏定位落错一条。
    val hasUpsRow = state.topUps.isNotEmpty() || state.liveUps.isNotEmpty()
    // 标题行在网格外（桌面固定标题栏）时不占网格下标。
    val baseOffset = (if (pinnedHeader) 0 else 1) + (if (hasUpsRow) 1 else 0)

    val beforeMarker = remember(state.items, markerIndex) {
        if (markerIndex != null) state.items.subList(0, markerIndex) else state.items
    }
    val fromMarker = remember(state.items, markerIndex) {
        if (markerIndex != null) state.items.subList(markerIndex, state.items.size) else emptyList()
    }
    val beforeNodes = remember(beforeMarker) { groupFeedBursts(beforeMarker) }
    val fromNodes = remember(fromMarker) { groupFeedBursts(fromMarker) }
    var expandedBurstIds by rememberSaveable(stateSaver = BurstIdSetSaver) { mutableStateOf(emptySet()) }
    val collapseScope = rememberCoroutineScope()
    val collapseBurst: (String) -> Unit = { burstId ->
        val nextExpanded = expandedBurstIds - burstId
        var targetIndex: Int? = null
        var cursor = baseOffset
        for (node in beforeNodes) {
            if (node is FeedDisplayNode.Burst && node.anchorId == burstId) {
                targetIndex = cursor
                break
            }
            cursor += 1
        }
        if (targetIndex == null && markerIndex != null) {
            cursor += 1
            for (node in fromNodes) {
                if (node is FeedDisplayNode.Burst && node.anchorId == burstId) {
                    targetIndex = cursor
                    break
                }
                cursor += 1
            }
        }
        val leadAlreadyVisible = listState.layoutInfo.visibleItemsInfo.any { it.key == burstId }
        expandedBurstIds = nextExpanded
        if (!leadAlreadyVisible && targetIndex != null) {
            collapseScope.launch { listState.scrollToItem(targetIndex) }
        }
    }

    var liveSheetOpen by rememberSaveable { mutableStateOf(false) }

    PrefetchNearEnd(
        listState,
        canLoad = state.hasMore && !state.appending && state.error == null,
        onLoadMore = onLoadMore,
    )

    // **开屏只定位这一次**,之后不管列表怎么变(翻页、排除 UP 主)都不再自动跳 —— 用户一旦
    // 开始自己滚,视图跳动比找不到分隔线更打扰人。
    //
    // "已经定位过"记在 ViewModel 里,不记在这里的 remember。进 UP 空间、切 tab 都会销毁这段
    // composition,而滚动位置由 SaveableStateHolder 还原得好好的 —— flag 用 remember 记的话
    // 它已经忘了,于是在还原好的位置上又跳一次。VM 挂在 Activity 的 store 上(见 FeedPane),
    // 活的正好是"这一次开屏"。
    LaunchedEffect(state.items) {
        if (!state.pendingLocate || state.items.isEmpty()) return@LaunchedEffect
        // 找不到分隔线也算定位过:那说明记录不在已加载范围内,再等下去只会在某次翻页之后
        // 突然跳一下。
        onLocated()
        if (markerIndex == null) return@LaunchedEffect
        listState.scrollToItem(baseOffset + beforeNodes.size)
    }

    // 顶部可见条目上报给 ViewModel 去抖落盘。直接用网格 item key 反查对应的真实条目 id。
    val keyToAnchorId = remember(beforeNodes, fromNodes) {
        buildMap<Any, String>(state.items.size) {
            fun indexNodes(nodes: List<FeedDisplayNode>) {
                for (node in nodes) {
                    when (node) {
                        is FeedDisplayNode.Single -> put(node.entry.id, node.entry.id)
                        is FeedDisplayNode.Burst -> {
                            for (entry in node.all) put(entry.id, entry.id)
                        }
                    }
                }
            }
            indexNodes(beforeNodes)
            indexNodes(fromNodes)
        }
    }
    LaunchedEffect(listState, keyToAnchorId) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .mapNotNull { visible -> visible.firstNotNullOfOrNull { keyToAnchorId[it.key] } }
            .distinctUntilChanged()
            .collect { onScrollPositionChanged(it) }
    }

    // Medium+ 多列网格渲染纵向 16:10 VideoGridCard,Compact 单列渲染拇指友好的横排 VideoRow;
    // 两档保持同一棵组合树层级,跨断点拖窗口时不丢失滚动位置。
    RegisterPageRefresh(refreshing = state.refreshing, onRefresh = onRefresh)
    val density = LocalDensity.current
    var containerWidth by remember { mutableStateOf(0.dp) }
    var actionsWidth by remember { mutableStateOf(0.dp) }
    var upsRowFits by remember { mutableStateOf(false) }
    val actionsInUpsRow = wide && hasUpsRow && upsRowFits
    val isTouch = LocalPointerSource.current.isTouchLike
    val appFullscreen = LocalAppFullscreen.current
    val upsTrailingActions: @Composable () -> Unit = {
        FeedHeaderActions(
            onOpenOtherDynamics = onOpenOtherDynamics,
            onOpenPushes = onOpenPushes,
            includeNavActions = true,
            includeFullscreen = false,
            includeRefresh = false,
            refreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.onSizeChanged { actionsWidth = with(density) { it.width.toDp() } },
        )
    }
    val showHeaderFullscreen = appFullscreen != null
    val showHeaderRefresh = appFullscreen != null || LocalWindowCaption.current != null || !isTouch
    val headerActions: (@Composable () -> Unit)? = if (!actionsInUpsRow || showHeaderFullscreen || showHeaderRefresh) {
        {
            FeedHeaderActions(
                onOpenOtherDynamics = onOpenOtherDynamics,
                onOpenPushes = onOpenPushes,
                includeNavActions = !actionsInUpsRow,
                includeFullscreen = showHeaderFullscreen,
                includeRefresh = showHeaderRefresh,
                refreshing = state.refreshing,
                onRefresh = onRefresh,
            )
        }
    } else {
        null
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .then(if (wide) Modifier else Modifier.widthIn(max = Breakpoints.ReadableWidth))
                .fillMaxSize()
                .onSizeChanged { containerWidth = with(density) { it.width.toDp() } },
        ) {
            val resolvedWidth = if (containerWidth > 0.dp) {
                containerWidth
            } else if (wide) {
                Breakpoints.Medium
            } else {
                Breakpoints.Medium - 1.dp
            }
            val columns = rememberVideoGridColumnCount(resolvedWidth)
            val multiColumn = columns > 1

            Column(modifier = Modifier.fillMaxSize()) {
                if (pinnedHeader) {
                    FeedHeader(
                        actions = headerActions,
                    )
                }
                RefreshBox(
                    refreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(columns),
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = contentPadding,
                    ) {
                        if (!pinnedHeader) {
                            item(key = "header", span = FullLine, contentType = "header") {
                                FeedHeader(
                                    actions = headerActions,
                                )
                            }
                        }
                        if (hasUpsRow) {
                            item(key = "frequent-ups", span = FullLine, contentType = "frequent-ups") {
                                FrequentUpsRow(
                                    ups = state.topUps,
                                    liveUps = state.liveUps,
                                    liveCount = state.liveCount,
                                    special = state.topUpsAreSpecial,
                                    onUpClick = onUpClick,
                                    onOpenLiveNow = { liveSheetOpen = true },
                                    onOpenFollowings = onOpenFollowings,
                                    trailing = upsTrailingActions.takeIf { wide },
                                    trailingWidth = (if (actionsWidth > 0.dp) actionsWidth else DefaultUpsActionsWidth).takeIf { wide },
                                    onFitChange = { upsRowFits = it },
                                )
                            }
                        }
                        if (state.items.isEmpty()) {
                            item(key = "empty", span = FullLine, contentType = "empty") {
                                EmptyState(stringResource(Res.string.feed_empty))
                            }
                        }
                        feedDisplayNodes(
                            nodes = beforeNodes,
                            expandedBurstIds = expandedBurstIds,
                            multiColumn = multiColumn,
                            wide = wide,
                            onExpandBurst = { expandedBurstIds = expandedBurstIds + it },
                            onCollapseBurst = collapseBurst,
                            onItemClick = onItemClick,
                            onUpClick = onUpClick,
                            onExcludeUp = onExcludeUp,
                            onAddToView = onAddToView,
                        )
                        if (markerIndex != null) {
                            item(key = "read-marker", span = FullLine, contentType = "read-marker") {
                                ReadMarkerDivider(Modifier.animateItem())
                            }
                        }
                        feedDisplayNodes(
                            nodes = fromNodes,
                            expandedBurstIds = expandedBurstIds,
                            multiColumn = multiColumn,
                            wide = wide,
                            onExpandBurst = { expandedBurstIds = expandedBurstIds + it },
                            onCollapseBurst = collapseBurst,
                            onItemClick = onItemClick,
                            onUpClick = onUpClick,
                            onExcludeUp = onExcludeUp,
                            onAddToView = onAddToView,
                        )
                        item(key = "footer", span = FullLine, contentType = "footer") {
                            ListFooter(
                                appending = state.appending,
                                hasMore = state.hasMore,
                                hasItems = state.items.isNotEmpty(),
                            )
                        }
                    }
                    ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }

    // 名单空了就把 sheet 收掉:刷新之后最后一个人下播了,留着的是一张空 sheet。
    if (liveSheetOpen && state.liveUps.isNotEmpty()) {
        LiveNowSheet(
            liveUps = state.liveUps,
            onLiveClick = { room ->
                liveSheetOpen = false
                onLiveClick(room)
            },
            onDismiss = { liveSheetOpen = false },
        )
    }
}

/**
 * 列表顶上那一行:左侧为主锚点页名(`headlineSmallEmphasized`)及紧邻的方案 C 小圆角刷新钮(仅键鼠模式)，
 * 右侧承接推送与关注动态入口(宽屏或够宽时内联至此，否则在 UP 排或次行承接)。
 *
 * **永远不给它红点、未读计数或带数量的角标。**
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeedHeader(
    actions: (@Composable () -> Unit)?,
) {
    val captionSlot = rememberCaptionSlot()
    Row(
        modifier = captionSlot.modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(
                start = Spacing.Comfortable,
                end = if (captionSlot.buttons != null) Spacing.Hair else Spacing.Comfortable,
                top = Spacing.Tight,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.feed_title),
            style = MaterialTheme.typography.headlineSmallEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.windowDragArea(captionSlot.atTop),
        )
        Spacer(
            modifier = Modifier
                .weight(1f)
                .height(CaptionDragHeight)
                .windowDragArea(captionSlot.atTop),
        )
        actions?.invoke()
        captionSlot.buttons?.invoke()
    }
}

/**
 * 推送、关注动态、桌面全屏与刷新入口：收口进 M3E [ButtonGroup]（`animateWidth` 弹性伸缩 + 窄屏自动折叠溢出菜单），
 * 内层延续 `32.dp` 高、`10.dp` 小圆角的轻量 `surfaceContainerLow` 方案 C 视觉，刷新按钮始终处于最右侧。
 */
@Composable
private fun FeedHeaderActions(
    onOpenOtherDynamics: () -> Unit,
    onOpenPushes: () -> Unit,
    includeNavActions: Boolean,
    includeFullscreen: Boolean,
    includeRefresh: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val appFullscreen = LocalAppFullscreen.current
    val refreshLabel = stringResource(Res.string.action_refresh)
    val pushesLabel = stringResource(Res.string.feed_up_pushes_entry)
    val dynamicsLabel = stringResource(Res.string.dynamic_other_entry)
    val isWindowFullscreen = appFullscreen?.isFullscreen == true
    val fullscreenLabel = stringResource(
        if (isWindowFullscreen) Res.string.player_exit_fullscreen else Res.string.player_fullscreen,
    )
    ButtonGroup(
        overflowIndicator = { menuState ->
            ButtonGroupDefaults.OverflowIndicator(
                menuState = menuState,
                shape = HeaderTileShape,
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = colorScheme.surfaceContainerLow,
                    contentColor = colorScheme.onSurfaceVariant,
                ),
            )
        },
        horizontalArrangement = Arrangement.spacedBy(HeaderTileGap),
        modifier = modifier,
    ) {
        if (includeNavActions) {
            // UP 主推送来的私信。同一条规矩:没有计数,没有红点。
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    FeedToolbarTile(
                        icon = Icons.Outlined.Mail,
                        label = pushesLabel,
                        interactionSource = interactionSource,
                        modifier = Modifier.animateWidth(interactionSource),
                        onClick = onOpenPushes,
                    )
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(pushesLabel) },
                        leadingIcon = { Icon(Icons.Outlined.Mail, contentDescription = null) },
                        onClick = {
                            menuState.dismiss()
                            onOpenPushes()
                        },
                    )
                },
            )
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    FeedToolbarTile(
                        icon = Icons.AutoMirrored.Outlined.Article,
                        label = dynamicsLabel,
                        interactionSource = interactionSource,
                        modifier = Modifier.animateWidth(interactionSource),
                        onClick = onOpenOtherDynamics,
                    )
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(dynamicsLabel) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Article, contentDescription = null) },
                        onClick = {
                            menuState.dismiss()
                            onOpenOtherDynamics()
                        },
                    )
                },
            )
        }
        if (includeFullscreen && appFullscreen != null) {
            val fullscreenIcon = if (isWindowFullscreen) {
                Icons.Outlined.FullscreenExit
            } else {
                Icons.Outlined.Fullscreen
            }
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    BilbyTooltip(text = fullscreenLabel, shortcut = "F11") {
                        FeedToolbarTile(
                            icon = fullscreenIcon,
                            label = null,
                            contentDescription = fullscreenLabel,
                            interactionSource = interactionSource,
                            modifier = Modifier.animateWidth(interactionSource),
                            onClick = { appFullscreen.toggle() },
                        )
                    }
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(fullscreenLabel) },
                        leadingIcon = { Icon(fullscreenIcon, contentDescription = null) },
                        onClick = {
                            menuState.dismiss()
                            appFullscreen.toggle()
                        },
                    )
                },
            )
        }
        if (includeRefresh) {
            customItem(
                buttonGroupContent = {
                    val interactionSource = remember { MutableInteractionSource() }
                    BilbyTooltip(text = refreshLabel, shortcut = "F5") {
                        FeedToolbarTile(
                            icon = Icons.Filled.Refresh,
                            label = null,
                            contentDescription = refreshLabel,
                            loading = refreshing,
                            interactionSource = interactionSource,
                            modifier = Modifier.animateWidth(interactionSource),
                            onClick = onRefresh,
                        )
                    }
                },
                menuContent = { menuState ->
                    DropdownMenuItem(
                        text = { Text(refreshLabel) },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        enabled = !refreshing,
                        onClick = {
                            menuState.dismiss()
                            onRefresh()
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun FeedToolbarTile(
    icon: ImageVector,
    label: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    loading: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
) {
    val colorScheme = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = Dimens.MinTouchTarget)
            .semantics(mergeDescendants = true) {
                if (contentDescription != null) this.contentDescription = contentDescription
            }
            .clickable(
                enabled = !loading,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(HeaderTileIconGap, Alignment.CenterHorizontally),
            modifier = Modifier
                .height(HeaderTileHeight)
                .then(if (label == null) Modifier.width(HeaderTileIconWidth) else Modifier)
                .interactiveCard(
                    shape = HeaderTileShape,
                    baseColor = colorScheme.surfaceContainerLow,
                    hoverColor = colorScheme.surfaceContainerHigh,
                )
                .border(HeaderTileBorderWidth, colorScheme.outlineVariant.copy(alpha = HeaderTileBorderAlpha), HeaderTileShape)
                .then(
                    if (label != null) {
                        Modifier.padding(horizontal = HeaderTileHorizontalPadding)
                    } else {
                        Modifier
                    },
                ),
        ) {
            if (loading) {
                LoadingSpinner(size = HeaderTileIconSize)
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(HeaderTileIconSize),
                )
            }
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

private val HeaderTileHeight = 32.dp
private val HeaderTileIconWidth = 32.dp
private val HeaderTileCorner = 10.dp
private val HeaderTileShape = RoundedCornerShape(HeaderTileCorner)
private val HeaderTileBorderWidth = 1.dp
private const val HeaderTileBorderAlpha = 0.55f
private val HeaderTileGap = 6.dp
private val HeaderTileIconSize = 16.dp
private val HeaderTileIconGap = 6.dp
private val HeaderTileHorizontalPadding = 12.dp

/** 单条投稿卡片或行,含「不再显示」与复制标题菜单。 */
@Composable
private fun FeedEntryItem(
    item: FeedEntry,
    onItemClick: (FeedEntry) -> Unit,
    onUpClick: (Long) -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
    multiColumn: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val itemModifier = if (multiColumn) {
        modifier.padding(horizontal = GridCardHorizontalPadding, vertical = GridCardVerticalPadding)
    } else {
        modifier
    }
    val rowUi = rememberFeedEntryRowUi(item, showArticleNote = !multiColumn)
    FeedEntryRow(
        item = item,
        rowUi = rowUi,
        onClick = { onItemClick(item) },
        onUpClick = onUpClick,
        onExcludeUp = onExcludeUp,
        onAddToView = onAddToView,
        multiColumn = multiColumn,
        wide = wide || multiColumn,
        modifier = itemModifier,
    )
}

/** 共用投稿条目渲染(`columns > 1` 为 [VideoGridCard],单列为 [VideoRow])与长按/右键/⋮ 菜单挂载。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeedEntryRow(
    item: FeedEntry,
    rowUi: VideoRowUi,
    onClick: () -> Unit,
    onUpClick: (Long) -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
    multiColumn: Boolean,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val menu: @Composable ColumnScope.(close: () -> Unit) -> Unit = { close ->
        FeedEntryMenuItems(item, close, onUpClick, onExcludeUp, onAddToView)
    }
    val upClickAction: (() -> Unit)? = if (item.upMid != 0L) ({ onUpClick(item.upMid) }) else null
    if (wide) {
        ContextMenuBox(
            onClick = null,
            menuLabel = stringResource(Res.string.feed_item_actions),
            modifier = modifier,
            menu = menu,
        ) { openMenu ->
            if (multiColumn) {
                VideoGridCard(
                    ui = rowUi,
                    onClick = onClick,
                    onLongClick = openMenu,
                    onUpClick = upClickAction,
                )
            } else {
                VideoRow(item = rowUi, onClick = onClick, onLongClick = openMenu)
            }
        }
        return
    }

    var menuOpen by remember { mutableStateOf(false) }
    val overflowSlot: @Composable () -> Unit = {
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = stringResource(Res.string.feed_item_actions),
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                shape = MenuDefaults.shape,
                containerColor = MenuDefaults.containerColor,
            ) {
                menu { menuOpen = false }
            }
        }
    }
    if (multiColumn) {
        VideoGridCard(
            ui = rowUi,
            onClick = onClick,
            onLongClick = { menuOpen = true },
            onUpClick = upClickAction,
            overflow = overflowSlot,
            modifier = modifier,
        )
    } else {
        VideoRow(
            item = rowUi,
            onClick = onClick,
            onLongClick = { menuOpen = true },
            modifier = modifier,
            overflow = overflowSlot,
        )
    }
}

/**
 * 一条投稿的菜单项,⋮ 与右键共用。向 [VideoCommonMenuItems] 显式传入 `title`,
 * 专栏条目也补充复制标题项,保证所有订阅卡片都能一键复制标题。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeedEntryMenuItems(
    item: FeedEntry,
    close: () -> Unit,
    onUpClick: (Long) -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
) {
    val video = item as? FeedEntry.Video
    val itemTitle = when (item) {
        is FeedEntry.Video -> item.title
        is FeedEntry.Article -> item.title
    }
    val videoBvid = video?.bvid.orEmpty()
    val articleCopyTitle = videoBvid.isEmpty() && itemTitle.isNotBlank()
    val copyAction = rememberCopyAction()
    val leadingCount = when {
        videoBvid.isNotEmpty() -> 1
        articleCopyTitle -> 1
        else -> 0
    }
    val commonCount = videoCommonMenuItemCount(
        bvid = videoBvid,
        upMid = item.upMid,
        onOpenUpSpace = onUpClick,
        title = itemTitle,
    )
    val totalCount = leadingCount + commonCount + 1

    if (videoBvid.isNotEmpty()) {
        DropdownMenuItem(
            onClick = {
                close()
                onAddToView(videoBvid)
            },
            text = { Text(stringResource(Res.string.video_action_toview_desc)) },
            leadingIcon = { Icon(Icons.Outlined.WatchLater, contentDescription = null) },
            shape = menuItemShape(0, totalCount),
        )
    } else if (articleCopyTitle) {
        DropdownMenuItem(
            onClick = {
                close()
                copyAction(itemTitle)
            },
            text = { Text(stringResource(Res.string.action_copy_title)) },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            shape = menuItemShape(0, totalCount),
        )
    }
    VideoCommonMenuItems(
        bvid = videoBvid,
        title = itemTitle,
        close = close,
        upMid = item.upMid,
        onOpenUpSpace = onUpClick,
        coverUrl = video?.coverUrl.orEmpty(),
        upName = item.upName,
        durationSeconds = video?.let { parseDurationSeconds(it.durationText) } ?: 0L,
        startIndex = leadingCount,
        totalCount = totalCount,
    )
    DropdownMenuItem(
        onClick = {
            close()
            onExcludeUp(item.upMid, item.upName)
        },
        text = { Text(stringResource(Res.string.feed_exclude_up, item.upName)) },
        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
        shape = menuItemShape(totalCount - 1, totalCount),
    )
}

/**
 * 「上次看到这里」通栏提示条：沿用原有满宽卡片骨架，升级为 M3 `shapes.medium` 色阶容器与
 * 主色箭头引导，紧凑贴合列表而不割裂。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReadMarkerDivider(modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight - Spacing.Hair / 2),
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(ReadMarkerIconSize),
            )
            Text(
                text = stringResource(Res.string.feed_read_marker),
                style = MaterialTheme.typography.labelMediumEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val ReadMarkerIconSize = 16.dp


/**
 * 两种投稿共用 [VideoRow] 的版式,不另画一种行:一条专栏在这条时间线里和一条视频是同一层
 * 东西,版式差一点点就会在滑动时看出接缝(这正是 VideoRow 当初把五处合成一份的理由)。
 *
 * 区别落在两处:封面角标那一格,视频写时长、专栏写「文章」——它是"这条是什么"最先被扫到的
 * 位置;以及正文摘要那一行,视频没有。
 *
 * **不带播放数和弹幕数。** 在这条时间线上决定点不点的是谁、什么时候发的,那一行已经有了;
 * 刚发几分钟的投稿计数都是个位数,摆出来只是一行噪音,还把每一行撑高一截。搜索结果里计数
 * 是判断依据,那里照旧带。
 */
@Composable
private fun rememberFeedEntryRowUi(
    entry: FeedEntry,
    showArticleNote: Boolean = true,
): VideoRowUi {
    val articleBadge = stringResource(Res.string.feed_article_badge)
    val dateText = formatRelativeTime(entry.publishedAtEpochSeconds)
    val typeBadge = if (entry is FeedEntry.Video) chargingBadgeText(entry.chargingOnly) else ""
    return remember(entry, articleBadge, dateText, typeBadge, showArticleNote) {
        when (entry) {
            is FeedEntry.Video -> VideoRowUi(
                title = entry.title,
                coverUrl = entry.coverUrl,
                durationText = entry.durationText,
                upName = entry.upName,
                dateText = dateText,
                upFaceUrl = entry.upFaceUrl,
                typeBadge = typeBadge,
            )

            is FeedEntry.Article -> VideoRowUi(
                title = entry.title,
                coverUrl = entry.coverUrl,
                durationText = articleBadge,
                upName = entry.upName,
                dateText = dateText,
                upFaceUrl = entry.upFaceUrl,
                note = if (showArticleNote) entry.summary.takeIf { it.isNotBlank() } else null,
            )
        }
    }
}

// ---- Preview ----

private fun previewItem(bvid: String, title: String, minutesAgo: Long) = FeedEntry.Video(
    bvid = bvid,
    title = title,
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    durationText = "12:34",
    upName = "某知名UP主",
    upMid = 12345L,
    upFaceUrl = "",
    publishedAtEpochSeconds = Instant.now().epochSecond - minutesAgo * 60,
    playCount = "12.3万",
    danmakuCount = "888",
)

private val previewItems = listOf(
    previewItem("BV1aa", "这是一个很长很长需要两行才能显示完的视频标题示例文本内容", 5),
    previewItem("BV1bb", "三小时前发布的视频", 3 * 60),
    previewItem("BV1cc", "昨天发布的视频", 30 * 60),
    previewItem("BV1dd", "三天前发布的视频", 3 * 24 * 60),
    previewItem("BV1ee", "很久以前发布的视频", 30 * 24 * 60),
)

@Preview(showBackground = true, name = "列表")
@Composable
private fun FeedScreenListPreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(items = previewItems, hasMore = true), {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "已刷完")
@Composable
private fun FeedScreenNoMorePreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(items = previewItems, hasMore = false), {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "空")
@Composable
private fun FeedScreenEmptyPreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(items = emptyList(), hasMore = false), {}, {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "错误")
@Composable
private fun FeedScreenErrorPreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(error = "网络连接失败"), {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * 顶上那排「最常访问」。
 *
 * **这是导航,不是推荐。** 里面每个人都是用户自己关注的,顺序由 B 站按账号给出,本地不排也
 * 不缓存;点进去是空间页,DESIGN 1.1 把"进空间"列为带意图的入口。它不往动态流里插任何条目,
 * 也不影响下面那条时间序流的顺序 —— 那条边界是这一排能存在的前提。
 *
 * 服务端还给了每个人的"有更新"标记,这里不取也不画:红点在 DESIGN 1.3 的永不实现清单上。
 */
@Composable
private fun FrequentUpsRow(
    ups: List<UpBrief>,
    liveUps: List<LiveUpBrief>,
    liveCount: Int,
    special: Boolean,
    onUpClick: (Long) -> Unit,
    onOpenLiveNow: () -> Unit,
    onOpenFollowings: () -> Unit,
    /** 宽屏整排放得下时画在右端的入口(刷新、推送、关注动态)。null 时不画。 */
    trailing: (@Composable () -> Unit)?,
    /** [trailing] 的宽度;null 表示不考虑并成一行(窄屏),只用钉住的排法。 */
    trailingWidth: Dp?,
    /** 这一排是否放得下并成一行。标题行据此决定入口画在哪。 */
    onFitChange: (Boolean) -> Unit,
) {
    // **不给这一排小标题,也不给分割线,和列表之间只隔一段留白。**
    //
    // 小标题要跟着名单来源在「特别关注」和「最常访问」之间切换 —— 一行会变的字,读者每次都得
    // 先认一遍;一排头像本来就说得清自己是谁。分割线曾经留着,依据是 divider.md 的"Use
    // dividers to group things",但它横在页面最上面一屏,是整页最硬的一道线,而导航区和内容区
    // 的形状本来就不同(一排圆、一列方封面),靠留白分得开。
    //
    // **不给它 surfaceContainer 底色。** 试过,不好看,而且是误用:roles.md:172 把 surface
    // 分给 background area、surface container 分给 **navigation area**(底栏、rail、抽屉
    // 那种),拿它刷正文里的一块是把导航的颜色用进了 body。
    // **头像横滚,「全部关注」钉在右边不参与滚动。**
    //
    // 原来那版把整排(含入口)塞进一个 LazyRow,入口于是躲在滚动尽头,想进完整名单得先横拖到头。
    // 后来改成"按宽度算出能站几个、不滚",入口是露出来了,代价是每种屏宽都剩一段放不下一格的
    // 余量,而且看得见的人变少了 —— 那段余量不是省下来的空间,是浪费掉的。
    //
    // 钉住入口之后两头都成立:名单要多长有多长,入口的位置不随屏宽和关注人数变。
    //
    // **宽屏整排放得下时另一种排法**:「关注列表」跟在最后一个人后面,标题行的刷新与两个入口
    // 挪到这一排右端([trailing])。钉在右端是为横滚准备的;不滚的时候它和头像之间隔着半屏空白,
    // 读不出是一组。放不下就回到钉住的排法,和窄屏一样。
    val density = LocalDensity.current
    var rowWidth by remember { mutableStateOf(0.dp) }
    val shownUps = remember(ups) { ups.take(FrequentUpLimit) }
    val startPadding = Spacing.Comfortable -
        if (liveUps.isNotEmpty()) liveNowSlotFaceInset(liveUps.size) else UpSlotFaceInset
    val slotCount = shownUps.size + 1
    val contentWidth = startPadding +
        (if (liveUps.isNotEmpty()) liveNowSlotOuterWidth(liveUps.size) + SlotGap else 0.dp) +
        UpSlotOuterWidth * slotCount + SlotGap * (slotCount - 1)
    val fits = trailingWidth != null &&
        rowWidth > 0.dp &&
        contentWidth + Spacing.Loose + trailingWidth + Spacing.Comfortable <= rowWidth
    LaunchedEffect(fits) { onFitChange(fits) }
    val followingsSlot: @Composable (Modifier) -> Unit = { slotModifier ->
        FollowingsSlot(onOpenFollowings, slotModifier)
    }
    val listState = rememberLazyListState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { rowWidth = with(density) { it.width.toDp() } }
            .padding(bottom = Spacing.Cozy),
        verticalAlignment = Alignment.Top,
    ) {
        LazyRow(
            state = listState,
            // 右边沿淡出。滚动到边界的那个头像会被切一半 —— 切口本身是"还有更多"的信号,
            // 但硬切在一个圆形上读起来像被右边那个入口盖住了。渐隐把切口变成"没画完",
            // 那正是它的意思。左边不淡:那儿是这一排的开头,不是被截断的地方。
            modifier = Modifier
                .weight(1f)
                .verticalWheelScrollsRow(listState)
                .then(if (fits) Modifier else Modifier.fadingRightEdge()),
            contentPadding = PaddingValues(
                // 排在最前的那一格决定起点:直播那一格更宽、头像叠着偏左,让出的量不一样。
                start = startPadding,
                end = Spacing.Cozy,
            ),
            horizontalArrangement = Arrangement.spacedBy(SlotGap),
        ) {
            // 正在直播的那一格排在最前面,是这一排的**前置项**而不是成员之一(见 LiveNowSlot)。
            // 没人在播时它整格不画,这一排就还是原来那排。
            if (liveUps.isNotEmpty()) {
                item(key = "live-now") {
                    LiveNowSlot(liveUps = liveUps, count = liveCount, onClick = onOpenLiveNow)
                }
            }
            items(shownUps, key = { it.mid }) { up ->
                UpSlot(
                    label = up.name,
                    emphasized = special,
                    onClick = { onUpClick(up.mid) },
                ) {
                    if (special) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(Dimens.AvatarStack)
                                .clip(CircleShape)
                                .border(SpecialUpRingWidth, MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                .padding(SpecialUpRingWidth),
                        ) {
                            Avatar(
                                url = up.faceUrl,
                                size = Dimens.AvatarStack - SpecialUpRingWidth * 2,
                            )
                        }
                    } else {
                        Avatar(url = up.faceUrl, size = Dimens.AvatarStack)
                    }
                }
            }
            if (fits) item(key = "followings") { followingsSlot(Modifier) }
        }
        if (fits && trailing != null) {
            // 入口对着头像的中线,胶囊右沿落在 16dp 页边线上。
            Box(
                modifier = Modifier.padding(
                    end = Spacing.Comfortable,
                    top = Spacing.Hair + (Dimens.AvatarStack - Dimens.MinTouchTarget) / 2,
                ),
            ) { trailing() }
        } else {
            // 右沿落在 16dp 页边线上,与标题行的入口、下面视频行的溢出按钮同一条竖线。
            followingsSlot(Modifier.padding(end = Spacing.Comfortable - UpSlotFaceInset))
        }
    }
}

/**
 * 「关注列表」那一格,和头像格同形:圆里一个人群图标,下面一行字。
 */
@Composable
private fun FollowingsSlot(onClick: () -> Unit, modifier: Modifier = Modifier) {
    UpSlot(
        label = stringResource(Res.string.feed_open_followings),
        onClick = onClick,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(Dimens.AvatarStack)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(
                Icons.Outlined.PeopleAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 这一排里的一格:上面是 48dp 的圆,下面一行字,带平滑指针悬停底色与点按微缩反馈。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun UpSlot(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .interactiveCard(
                shape = MaterialTheme.shapes.medium,
                baseColor = Color.Transparent,
                hoverColor = MaterialTheme.colorScheme.surfaceContainerLow,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = Spacing.Hair, horizontal = SlotInset)
            .width(AvatarSlotWidth),
    ) {
        content()
        Text(
            text = label,
            style = if (emphasized) {
                MaterialTheme.typography.labelSmallEmphasized
            } else {
                MaterialTheme.typography.labelSmall
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.Hair),
        )
    }
}

private val BurstIdSetSaver = listSaver<Set<String>, String>(
    save = { it.toList() },
    restore = { it.toSet() },
)

private fun LazyGridScope.feedDisplayNodes(
    nodes: List<FeedDisplayNode>,
    expandedBurstIds: Set<String>,
    multiColumn: Boolean,
    wide: Boolean,
    onExpandBurst: (String) -> Unit,
    onCollapseBurst: (String) -> Unit,
    onItemClick: (FeedEntry) -> Unit,
    onUpClick: (Long) -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
) {
    nodes.forEach { node ->
        when (node) {
            is FeedDisplayNode.Single -> item(key = node.entry.id, contentType = "feed-entry") {
                FeedEntryItem(
                    item = node.entry,
                    onItemClick = onItemClick,
                    onUpClick = onUpClick,
                    onExcludeUp = onExcludeUp,
                    onAddToView = onAddToView,
                    multiColumn = multiColumn,
                    wide = wide,
                    modifier = Modifier.animateItem(),
                )
            }

            is FeedDisplayNode.Burst -> {
                val expanded = node.anchorId in expandedBurstIds
                if (!expanded) {
                    item(key = node.lead.id, contentType = "burst-stacked") {
                        BurstStackedItem(
                            node = node,
                            multiColumn = multiColumn,
                            wide = wide,
                            onExpand = { onExpandBurst(node.anchorId) },
                            onUpClick = onUpClick,
                            onExcludeUp = onExcludeUp,
                            onAddToView = onAddToView,
                            modifier = Modifier.animateItem(),
                        )
                    }
                } else {
                    item(
                        key = node.lead.id,
                        span = FullLine,
                        contentType = "burst-expanded-carousel",
                    ) {
                        BurstExpandedCarousel(
                            node = node,
                            multiColumn = multiColumn,
                            wide = wide,
                            onCollapse = { onCollapseBurst(node.anchorId) },
                            onItemClick = onItemClick,
                            onUpClick = onUpClick,
                            onExcludeUp = onExcludeUp,
                            onAddToView = onAddToView,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 连发组收起态叠卡:保持与普通 [VideoRow] / [VideoGridCard] 紧凑一致的高度,不另起底部横条占空间;
 * 仅以 M3 三层色阶容器(`surfaceContainerLow` / `surfaceContainerHigh` / `surfaceContainerHighest`)
 * 做紧贴右下的轻错位叠放,封面角标标明「连发 N 条」(保留充电角标),点击展开其余连发。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BurstStackedItem(
    node: FeedDisplayNode.Burst,
    multiColumn: Boolean,
    wide: Boolean,
    onExpand: () -> Unit,
    onUpClick: (Long) -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val baseRowUi = rememberFeedEntryRowUi(node.lead, showArticleNote = !multiColumn)
    val stackBadge = stringResource(Res.string.feed_burst_stack_badge, node.totalCount)
    val rowUi = remember(baseRowUi, stackBadge) {
        baseRowUi.copy(
            typeBadge = baseRowUi.typeBadge.takeIf { it.isNotBlank() }?.let { "$stackBadge$MetaSeparator$it" }
                ?: stackBadge,
        )
    }
    val cardShape = if (multiColumn) MaterialTheme.shapes.large else MaterialTheme.shapes.medium
    val outerModifier = if (multiColumn) {
        modifier
            .fillMaxWidth()
            .padding(horizontal = GridCardHorizontalPadding, vertical = GridCardVerticalPadding)
    } else {
        modifier.fillMaxWidth()
    }
    val reducedMotion = rememberReducedMotion()
    val deckInteraction = remember { MutableInteractionSource() }
    val hovered by deckInteraction.collectIsHoveredAsState()
    var pressed by remember { mutableStateOf(false) }
    val fanProgress by animateFloatAsState(
        targetValue = if ((hovered || pressed) && !reducedMotion) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "burst-deck-fan",
    )

    Box(
        modifier = outerModifier
            .hoverable(deckInteraction)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        pressed = event.changes.any { it.pressed }
                    }
                }
            },
    ) {
        if (multiColumn) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        if (fanProgress > 0f) {
                            translationX = DeckFanBackShift.toPx() * fanProgress
                            translationY = DeckFanMidShift.toPx() * fanProgress
                            rotationZ = DeckFanAngle * fanProgress
                        }
                    }
                    .padding(start = Spacing.Tight, top = DeckGridBackOffset)
                    .clip(cardShape)
                    .background(colors.surfaceContainerHighest),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        if (fanProgress > 0f) {
                            translationX = DeckFanMidShift.toPx() * fanProgress
                            translationY = DeckFanMidYShift.toPx() * fanProgress
                            rotationZ = -DeckFanAngle * fanProgress
                        }
                    }
                    .padding(
                        start = Spacing.Hair,
                        end = DeckGridMidOffset,
                        top = DeckGridMidOffset,
                        bottom = DeckGridMidOffset,
                    )
                    .clip(cardShape)
                    .background(colors.surfaceContainerHigh),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = DeckGridBackOffset, bottom = DeckGridBackOffset)
                    .clip(cardShape)
                    .background(colors.surfaceContainerLow),
            ) {
                FeedEntryRow(
                    item = node.lead,
                    rowUi = rowUi,
                    onClick = onExpand,
                    onUpClick = onUpClick,
                    onExcludeUp = onExcludeUp,
                    onAddToView = onAddToView,
                    multiColumn = true,
                    wide = true,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        if (fanProgress > 0f) {
                            translationX = DeckFanBackShift.toPx() * fanProgress
                            translationY = DeckFanMidShift.toPx() * fanProgress
                            rotationZ = DeckFanAngle * fanProgress
                        }
                    }
                    .padding(start = Spacing.Comfortable, end = DeckOuterEndInset, top = Spacing.Cozy)
                    .clip(cardShape)
                    .background(colors.surfaceContainerHighest),
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        if (fanProgress > 0f) {
                            translationX = DeckFanMidShift.toPx() * fanProgress
                            translationY = DeckFanMidYShift.toPx() * fanProgress
                            rotationZ = -DeckFanAngle * fanProgress
                        }
                    }
                    .padding(
                        start = Spacing.Cozy,
                        end = DeckMidEndInset,
                        top = Spacing.Tight,
                        bottom = DeckStepOffset,
                    )
                    .clip(cardShape)
                    .background(colors.surfaceContainerHigh),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Tight, vertical = Spacing.Hair)
                    .clip(cardShape)
                    .background(colors.surfaceContainerLow),
            ) {
                FeedEntryRow(
                    item = node.lead,
                    rowUi = rowUi,
                    onClick = onExpand,
                    onUpClick = onUpClick,
                    onExcludeUp = onExcludeUp,
                    onAddToView = onAddToView,
                    multiColumn = false,
                    wide = wide,
                )
            }
        }
    }
}

/**
 * 连发组展开态：横跨整行以 M3E `HorizontalMultiBrowseCarousel`（减弱动效时回退等宽
 * `HorizontalUncontainedCarousel`）横向浏览同一 UP 主的连续投稿，保留顶部作者信息、收起入口
 * 以及每张卡片的正常点按与右键/长按操作菜单。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BurstExpandedCarousel(
    node: FeedDisplayNode.Burst,
    multiColumn: Boolean,
    wide: Boolean,
    onCollapse: () -> Unit,
    onItemClick: (FeedEntry) -> Unit,
    onUpClick: (Long) -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val carouselState = rememberCarouselState { node.all.size }
    val reducedMotion = rememberReducedMotion()
    val useWideMetrics = wide || multiColumn
    val preferredWidth = if (useWideMetrics) BurstCarouselPreferredWidthWide else BurstCarouselPreferredWidthCompact
    val cardHeight = if (useWideMetrics) BurstCarouselHeightWide else BurstCarouselHeightCompact
    val outerHorizontalPadding = if (multiColumn) GridCardHorizontalPadding else Spacing.Tight
    val outerVerticalPadding = if (multiColumn) GridCardVerticalPadding else Spacing.Hair

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = outerHorizontalPadding, vertical = outerVerticalPadding)
            .clip(MaterialTheme.shapes.large)
            .background(colors.surfaceContainerLow)
            .padding(bottom = Spacing.Cozy),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.Cozy, end = Spacing.Tight, top = Spacing.Hair, bottom = Spacing.Hair),
        ) {
            val upMid = node.lead.upMid
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                modifier = Modifier
                    .weight(1f)
                    .clip(MaterialTheme.shapes.small)
                    .then(
                        if (upMid != 0L) {
                            Modifier.clickable(role = Role.Button) { onUpClick(upMid) }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = Spacing.Hair, vertical = Spacing.Hair),
            ) {
                Avatar(url = node.lead.upFaceUrl, size = BurstHeaderAvatarSize)
                Text(
                    text = stringResource(Res.string.feed_burst_header, node.lead.upName, node.totalCount),
                    style = MaterialTheme.typography.titleSmallEmphasized.tabular,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = onCollapse) {
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconInline),
                )
                Spacer(Modifier.width(Spacing.Hair))
                Text(text = stringResource(Res.string.feed_burst_collapse))
            }
        }

        val carouselModifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
            .verticalWheelScrollsRow(carouselState)
        val contentPadding = PaddingValues(horizontal = Spacing.Cozy)
        if (reducedMotion) {
            HorizontalUncontainedCarousel(
                state = carouselState,
                itemWidth = preferredWidth,
                modifier = carouselModifier,
                itemSpacing = Spacing.Tight,
                contentPadding = contentPadding,
            ) { index ->
                node.all.getOrNull(index)?.let { entry ->
                    BurstCarouselCard(
                        entry = entry,
                        onClick = { onItemClick(entry) },
                        onUpClick = onUpClick,
                        onExcludeUp = onExcludeUp,
                        onAddToView = onAddToView,
                    )
                }
            }
        } else {
            HorizontalMultiBrowseCarousel(
                state = carouselState,
                preferredItemWidth = preferredWidth,
                modifier = carouselModifier,
                itemSpacing = Spacing.Tight,
                flingBehavior = CarouselDefaults.multiBrowseFlingBehavior(
                    state = carouselState,
                    snapAnimationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
                ),
                contentPadding = contentPadding,
            ) { index ->
                node.all.getOrNull(index)?.let { entry ->
                    BurstCarouselCard(
                        entry = entry,
                        onClick = { onItemClick(entry) },
                        onUpClick = onUpClick,
                        onExcludeUp = onExcludeUp,
                        onAddToView = onAddToView,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun CarouselItemScope.BurstCarouselCard(
    entry: FeedEntry,
    onClick: () -> Unit,
    onUpClick: (Long) -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val rowUi = rememberFeedEntryRowUi(entry, showArticleNote = false)
    val cardShape = MaterialTheme.shapes.largeIncreased

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.feed_item_actions),
        modifier = modifier
            .fillMaxSize()
            .maskClip(cardShape),
        menu = { close ->
            FeedEntryMenuItems(entry, close, onUpClick, onExcludeUp, onAddToView)
        },
    ) { openMenu ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorScheme.surfaceContainerHigh)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                ),
        ) {
            BiliAsyncImage(
                url = rowUi.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (rowUi.typeBadge.isNotEmpty()) {
                MediaBadge(
                    text = rowUi.typeBadge,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(Spacing.Tight),
                )
            }
            if (rowUi.durationText.isNotEmpty()) {
                MediaBadge(
                    text = rowUi.durationText,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Spacing.Tight),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .graphicsLayer {
                        val ratio = carouselItemDrawInfo.size / carouselItemDrawInfo.maxSize.coerceAtLeast(1f)
                        alpha = ((ratio - 0.52f) / 0.48f).coerceIn(0f, 1f)
                    }
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.38f to FixedColors.ScrimOnMedia.copy(alpha = 0.68f),
                            1f to FixedColors.ScrimOnMedia,
                        ),
                    )
                    .padding(
                        start = Spacing.Cozy,
                        end = Spacing.Cozy,
                        top = Spacing.Comfortable,
                        bottom = Spacing.Tight,
                    ),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = rowUi.title,
                        style = MaterialTheme.typography.bodyMediumEmphasized,
                        color = FixedColors.OnMedia,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    rowUi.dateText?.takeIf { it.isNotEmpty() }?.let { dateText ->
                        Text(
                            text = dateText,
                            style = MaterialTheme.typography.labelSmall.tabular,
                            color = FixedColors.OnMedia.copy(alpha = 0.86f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                val ratio = carouselItemDrawInfo.size / carouselItemDrawInfo.maxSize.coerceAtLeast(1f)
                                alpha = ((ratio - 0.68f) / 0.32f).coerceIn(0f, 1f)
                            },
                        )
                    }
                }
            }
        }
    }
}

private val BurstHeaderAvatarSize = 24.dp
private val BurstCarouselPreferredWidthCompact = 196.dp
private val BurstCarouselPreferredWidthWide = 220.dp
private val BurstCarouselHeightCompact = 122.dp
private val BurstCarouselHeightWide = 138.dp

/** 网格里横跨整行的那几格:标题行、分隔线、页脚。 */
private val FullLine: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/** 多列网格卡片外围半间距:两张相邻卡片的悬停容器相距 12dp。 */
private val GridCardHorizontalPadding = 6.dp
private val GridCardVerticalPadding = 4.dp

/** 特别关注头像外侧低饱和强调环宽度。 */
private val SpecialUpRingWidth = 2.dp

/** 比头像宽一点,名字多站得下一两个字。 */
private val AvatarSlotWidth = 60.dp

/** 一格连内边距的总宽,算这一排放不放得下用。 */
private val UpSlotOuterWidth get() = AvatarSlotWidth + SlotInset * 2

/** 每格自己的左右内边距。算箭头对齐时要用到,见「关注列表」那一格的注释。 */
private val SlotInset = 2.dp

/**
 * 格子左沿到头像左沿的距离:格子自己的 [SlotInset],加上格子比头像宽出的一半。头像排的起点
 * 用它倒推,让第一个**头像**(不是格子)的左沿落在 16dp 页边线上,和下面封面的左沿对齐。
 */
private val UpSlotFaceInset = SlotInset + (AvatarSlotWidth - Dimens.AvatarStack) / 2

private val SlotGap = Spacing.Tight

private const val FrequentUpLimit = 20

/** 单列连发叠卡错位步长。 */
private val DeckStepOffset = 2.dp
private val DeckMidEndInset = 5.dp
private val DeckOuterEndInset = 2.dp

/** 多列连发叠卡右下微错位步长。 */
private val DeckGridMidOffset = 3.dp
private val DeckGridBackOffset = 6.dp
private val DeckFanMidShift = 2.dp
private val DeckFanMidYShift = 1.dp
private val DeckFanBackShift = 4.dp
private const val DeckFanAngle = 1.2f
private val DefaultUpsActionsWidth = 210.dp
