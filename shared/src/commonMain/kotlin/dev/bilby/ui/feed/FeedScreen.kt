package dev.bilby.ui.feed

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Mail
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.ListScrollbar
import dev.bilby.ui.components.RefreshAction
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.data.LiveUpBrief
import dev.bilby.data.UpBrief
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import dev.bilby.ui.maxWidthGridCells
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.Role
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import androidx.compose.ui.tooling.preview.Preview
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.data.model.FeedEntry
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FirstScreenState
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.fadingRightEdge
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.ContextMenuBox
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.outlined.PeopleAlt
import androidx.compose.foundation.layout.ColumnScope
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.chargingBadgeText
import dev.bilby.ui.theme.BilbyTheme
import java.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.bilby.ui.components.PrefetchNearEnd
import kotlinx.coroutines.flow.mapNotNull

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
data class ExcludeUndo(val id: Long, val mid: Long, val name: String)

/**
 * 刚把一条加进稍后再看。**只报一句,没有撤销** —— 稍后再看是只进不出的,移除在那一页做
 * (见 [FeedViewModel.addToView])。
 *
 * [succeeded] 要带上:这个动作在列表上留不下任何痕迹,不报的话成功和失败完全同形。
 * [id] 是本地递增的序号,理由同 [ExcludeUndo.id] —— 连着加两条要能各弹一次。
 */
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
        val result = snackbarHostState.showSnackbar(message = undoText, actionLabel = undoLabel)
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
                state, onRefresh, onLoadMore, onItemClick, onUpClick, onLiveClick, onExcludeUp,
                onAddToView, onOpenFollowings, onOpenOtherDynamics, onOpenPushes, onScrollPositionChanged, onLocated,
                scrollToTop, Modifier, contentPadding,
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
    val markerIndex = state.items.indexOfReadMarker(state.readMarkerEntryId)
    val wide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    // 那一排头像排在动态流前面,分隔线/条目在网格里的绝对下标要把它加回来。
    // 「最常访问」那一格在没人可显示时整格不画,正在直播的那一格自己也可以撑起它 ——
    // 判据必须和下面渲染时用的是同一个,差一格就是开屏定位落错一条。
    val hasUpsRow = state.topUps.isNotEmpty() || state.liveUps.isNotEmpty()
    // 标题行恒占一格。
    val baseOffset = (if (hasUpsRow) 1 else 0) + 1

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
        val target = markerIndex ?: return@LaunchedEffect
        listState.scrollToItem(baseOffset + target)
    }

    // 顶部可见条目上报给 ViewModel 去抖落盘。用 layoutInfo 里第一个「是投稿条目」的 key,
    // 不用 firstVisibleItemIndex 反查 —— 分隔线、顶部 UP 排都会占用网格的下标,
    // 换算回 state.items 的下标要跟着这两样是否存在反复调整,直接认 key 更不容易算错。
    val idSet = remember(state.items) { state.items.mapTo(HashSet()) { it.id } }
    LaunchedEffect(listState, idSet) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .mapNotNull { visible -> visible.firstOrNull { (it.key as? String) in idSet }?.key as? String }
            .distinctUntilChanged()
            .collect { onScrollPositionChanged(it) }
    }

    // **宽屏是网格,按行从左往右读。** 列表行是"封面 + 三行文字"的定宽版式,单列铺到
    // 1400dp 之后封面还是 128dp,右边多出来的全是空白,一屏只站得下七八条。按行读的网格
    // 在 B 站网页版和 PiliPlus 的宽屏上都是这条时间线的读法,时间序不因分列而打乱。
    // 窄屏是固定一列,与原来的单列列表同形;两种宽度共用一个网格,开屏定位、触底预取、
    // 位置上报都只认一套下标。
    val density = LocalDensity.current
    var actionsWidth by remember { mutableStateOf(0.dp) }
    var upsRowFits by remember { mutableStateOf(false) }
    val feedList: @Composable (Modifier) -> Unit = { listModifier ->
        RefreshBox(
            refreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = listModifier,
        ) {
            LazyVerticalGrid(
                columns = if (wide) maxWidthGridCells(Breakpoints.VideoRowMaxWidth) else GridCells.Fixed(1),
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
        // 首页装不下的另一半(图文、纯文字、转发、直播)的入口挂在标题行右边。**专栏不在
        // 里面**:它是投稿,和视频一样排在首页的时间序里(见 DynamicRepository 的分流)。
        // 宽屏一排放得下时,刷新与两个入口挪进头像那一排的右端:标题行只剩页名,头像排右边也
        // 不再空着半屏。放不下(窄屏,或关注的人多)就维持原样:入口在标题行,「关注列表」钉住。
        // 入口的宽度量一次记下,无论它此刻画在哪一行,判断都用同一个值,不会来回跳。
        val actionsInUpsRow = wide && hasUpsRow && upsRowFits
        val headerActions: @Composable () -> Unit = {
            FeedHeaderActions(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
                onOpenOtherDynamics = onOpenOtherDynamics,
                onOpenPushes = onOpenPushes,
                tonal = wide,
                modifier = Modifier.onSizeChanged { actionsWidth = with(density) { it.width.toDp() } },
            )
        }
        item(key = "header", span = FullLine) {
            FeedHeader(actions = headerActions.takeUnless { actionsInUpsRow })
        }
        // 「最常访问」跟着列表一起滚,不吸顶:吸顶会让它变成常驻的入口带,而这一页的主体是
        // 动态流。
        //
        // **宽屏也是这一排,不挪到侧栏。** 侧栏试过一轮:十来个人长期占住一整栏,下面空着,
        // 为了让它能关又要加开关、加设置、把标题行钉住。放在网格上方,这一排在宽屏上横铺
        // 一千多 dp,二十个人一行站得下、不用横滚,网格也拿到整个宽度。
        if (hasUpsRow) {
            item(key = "frequent-ups", span = FullLine) {
                FrequentUpsRow(
                    ups = state.topUps,
                    liveUps = state.liveUps,
                    liveCount = state.liveCount,
                    special = state.topUpsAreSpecial,
                    onUpClick = onUpClick,
                    onOpenLiveNow = { liveSheetOpen = true },
                    onOpenFollowings = onOpenFollowings,
                    trailing = headerActions.takeIf { actionsInUpsRow },
                    trailingWidth = actionsWidth.takeIf { wide },
                    onFitChange = { upsRowFits = it },
                )
            }
        }
        if (state.items.isEmpty()) {
            item(key = "empty", span = FullLine) { EmptyState(stringResource(Res.string.feed_empty)) }
        }
        val beforeMarker = if (markerIndex != null) state.items.subList(0, markerIndex) else state.items
        val fromMarker = if (markerIndex != null) state.items.subList(markerIndex, state.items.size) else emptyList()
        // animateItem:「不再显示这个 UP」当场生效,那一刻被摘掉的可能是连着好几条,
        // 下面几十行硬切着往上跳一格;条目认的是 FeedEntry.id,翻页追加也走同一条动效。
        gridItems(beforeMarker, key = { it.id }) { item ->
            FeedEntryItem(item, onItemClick, onExcludeUp, onAddToView, wide, Modifier.animateItem())
        }
        if (markerIndex != null) {
            // 横跨整行:分隔线前那一行没排满也照样断开,新旧两段不共用一行。
            item(key = "read-marker", span = FullLine) { ReadMarkerDivider(Modifier.animateItem()) }
        }
        gridItems(fromMarker, key = { it.id }) { item ->
            FeedEntryItem(item, onItemClick, onExcludeUp, onAddToView, wide, Modifier.animateItem())
        }
            item(key = "footer", span = FullLine) {
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

    if (wide) {
        feedList(modifier.fillMaxSize())
    } else {
        AdaptiveContent(modifier = modifier, maxWidth = Breakpoints.ReadableWidth) {
            feedList(Modifier.fillMaxSize())
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
 * 列表顶上那一行:页名,右边是「关注动态」。
 *
 * **标题跟着列表滚,不做顶栏。** 三个根页都没有顶栏,单给这一页加一条的话,底栏切过来时
 * 页面顶部的形状会变;而这一行只在开头有用,滚走之后底栏已经说明了这是哪一页。
 *
 * **入口挂在标题行上,不单占一行。** 它原先独占一行、贴右,左边整片空着;它通往的是这一页
 * 装不下的另一半,和页名放在一起读起来正是"这一页,以及它旁边那一页"。放在开头而不是列表
 * 末尾,是因为这条时间序流实际上翻不到底(见 [FeedScreen]),末尾没人到得了。
 *
 * **但不降调。** 它曾经是 onSurfaceVariant,理由抄的是 DESIGN 2.1 那句「折叠为一个不显眼的
 * 入口」—— 那正是 CLAUDE.md 点名删掉的那类发明:把入口做得更难找不是克制,是替用户决定
 * 他不该去那儿。TextButton 默认的 primary 就是一条普通入口该有的样子。
 *
 * **永远不给它红点、未读计数或带数量的角标。** 那些是 DESIGN 1.3 永不实现清单上的第一条,
 * 而这里正是它们最容易被加回来的位置 —— 「顺手显示有几条新的」听起来是信息,实际是把一条
 * 静态入口变成催人回来的提醒。
 */
@Composable
private fun FeedHeader(
    /**
     * 刷新与两个入口([FeedHeaderActions])。null 时它们在头像那一排的右端(宽屏),
     * 这一行只剩页名。
     */
    actions: (@Composable () -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(
                start = Spacing.Comfortable,
                end = Spacing.Comfortable - HeaderButtonEndInset,
                top = Spacing.Tight,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.feed_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        actions?.invoke()
    }
}

/** 刷新、UP 主推送、关注动态。右沿的 › 落在 16dp 页边线上,见 [HeaderButtonEndInset]。 */
@Composable
private fun FeedHeaderActions(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenOtherDynamics: () -> Unit,
    onOpenPushes: () -> Unit,
    /**
     * 宽屏:两个入口用 tonal 按钮。文字按钮在手机那一行里刚好;放进一千多 dp 宽、旁边一排
     * 48dp 头像的行里,没有底色的两行小字像链接,压不住那一排头像的分量。
     */
    tonal: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (tonal) Spacing.Tight else 0.dp),
        modifier = modifier,
    ) {
        RefreshAction(refreshing, onRefresh)
        // UP 主推送来的私信。**放这里,不放消息页**:推送里的视频就是这条时间线上的那些投稿,
        // 它们在私信列表里只是把真人对话往下挤;挪到订阅页,和"我关注的人发了什么"放在一起。
        // 同一条规矩:没有计数,没有红点。
        HeaderEntryButton(
            tonal = tonal,
            onClick = onOpenPushes,
            contentPadding = if (tonal) {
                ButtonDefaults.ButtonWithIconContentPadding
            } else {
                PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Tight)
            },
        ) {
            Icon(
                Icons.Outlined.Mail,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
            Text(
                text = stringResource(Res.string.feed_up_pushes_entry),
                modifier = Modifier.padding(start = Spacing.Tight),
            )
        }
        // 内边距写明而不取默认:箭头的右沿要落在 16dp 页边线上(见 [HeaderButtonEndInset]),
        // 默认值跟着 alpha 版本变,对齐也就跟着变。tonal 时按钮自带底色,右沿就是底色的边,
        // 箭头照常留内边距。
        HeaderEntryButton(
            tonal = tonal,
            onClick = onOpenOtherDynamics,
            contentPadding = if (tonal) {
                ButtonDefaults.ButtonWithIconContentPadding
            } else {
                PaddingValues(start = Spacing.Cozy, end = HeaderButtonEndInset, top = Spacing.Tight, bottom = Spacing.Tight)
            },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Article,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
            Text(
                text = stringResource(Res.string.dynamic_other_entry),
                modifier = Modifier.padding(start = Spacing.Tight, end = Spacing.Hair),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
        }
    }
}

/** 标题行的入口按钮:窄屏是文字按钮,宽屏是 tonal,见 [FeedHeaderActions] 的 tonal。 */
@Composable
private fun HeaderEntryButton(
    tonal: Boolean,
    onClick: () -> Unit,
    contentPadding: PaddingValues,
    content: @Composable RowScope.() -> Unit,
) {
    if (tonal) {
        FilledTonalButton(onClick = onClick, contentPadding = contentPadding, content = content)
    } else {
        TextButton(onClick = onClick, contentPadding = contentPadding, content = content)
    }
}

/** 单条投稿行,含「不再显示」的菜单。从 [FeedList] 拆出来是因为分隔线要把 items(...) 切成两段,两段用的是同一份行 UI。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeedEntryItem(
    item: FeedEntry,
    onItemClick: (FeedEntry) -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
    /** 宽屏网格:不画 ⋮,同一份菜单由右键或长按在按下处弹出,见 [ContextMenuBox]。 */
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val menu: @Composable ColumnScope.(close: () -> Unit) -> Unit = { close ->
        FeedEntryMenuItems(item, close, onExcludeUp, onAddToView)
    }
    if (wide) {
        ContextMenuBox(
            onClick = null,
            menuLabel = stringResource(Res.string.feed_item_actions),
            modifier = modifier,
            menu = menu,
        ) { openMenu ->
            VideoRow(item = item.toRowUi(), onClick = { onItemClick(item) }, onLongClick = openMenu)
        }
        return
    }
    // **窄屏菜单只有行尾这一个入口,不接长按。** 长按此前是并行的第二个入口,理由是"已经会用
    // 的人不必改习惯";但长按没有任何视觉提示,而 M3 手势那一页给长按定的语义是"选中项",
    // 留着它等于让同一个操作有一个说不通的别名。宽屏不同:那里的主要手势是右键,长按只是
    // 没有鼠标的平板上的同一个动作。
    var menuOpen by remember { mutableStateOf(false) }
    VideoRow(
        item = item.toRowUi(),
        onClick = { onItemClick(item) },
        modifier = modifier,
        overflow = {
            // 菜单挂在按钮上,不挂在整行上 —— 挂在行上时 Popup 以整行为锚,菜单从行的左下角
            // 弹出来,离按下去的那个点半屏远。
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(Res.string.feed_item_actions),
                    )
                }
                // M3E 的 vertical menu:容器圆角、standard 配色(surfaceContainerLow),菜单项
                // 自己也有形状 —— 基线菜单的项是一条通栏矩形,按下去的状态层跟着是方的。
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    shape = MenuDefaults.shape,
                    containerColor = MenuDefaults.containerColor,
                ) {
                    menu { menuOpen = false }
                }
            }
        },
    )
}

/**
 * 一条投稿的菜单项,⋮ 与右键共用。
 *
 * **形状按项数取。** 只有一项时它既是首项也是末项(standalone);两项时上下各取 leading /
 * trailing,中间那条边是直的 —— 两项都用 standalone 的话,两块圆角贴在一起会在中缝挤出一道
 * 空隙。专栏没有"稍后再看",所以这个菜单真的会在一项和两项之间变。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeedEntryMenuItems(
    item: FeedEntry,
    close: () -> Unit,
    onExcludeUp: (Long, String) -> Unit,
    onAddToView: (String) -> Unit,
) {
    val canAddToView = item is FeedEntry.Video
    if (canAddToView) {
        // 只进不出,和播放页那一格同一套规矩(见 [FeedViewModel.addToView])。
        DropdownMenuItem(
            onClick = {
                close()
                onAddToView((item as FeedEntry.Video).bvid)
            },
            text = { Text(stringResource(Res.string.video_action_toview_desc)) },
            // 与播放页动作栏那一格同一个图标,认得出是同一件事。
            leadingIcon = { Icon(Icons.Outlined.WatchLater, contentDescription = null) },
            shape = MenuDefaults.leadingItemShape,
        )
    }
    // **当场生效,撤销在 snackbar 上**(见 [ExcludeUndo])。从前这里还隔着一个确认对话框,
    // 理由是撤销无处可落;设置里那份名单可以逐个恢复之后,那个理由不成立了,而一个撤得回来的
    // 操作不值得一次拦截。
    DropdownMenuItem(
        onClick = {
            close()
            onExcludeUp(item.upMid, item.upName)
        },
        text = { Text(stringResource(Res.string.feed_exclude_up, item.upName)) },
        leadingIcon = { Icon(Icons.Outlined.VisibilityOff, contentDescription = null) },
        shape = if (canAddToView) MenuDefaults.trailingItemShape else MenuDefaults.standaloneItemShape,
    )
}

/**
 * 「以上是新内容」分隔线。DESIGN 2.1 只要求记住位置、不要红点/未读计数(4.2 节的永不实现
 * 清单),这条线本身不随时间变化去提醒用户回来看,进屏时算一次就不再动 —— 不落在那条禁令上。
 *
 * 一枚居中的 tonal 胶囊,不是线:它独占一整行,新旧两段照样在这里断开;两根细线夹一行小字
 * 的画法在一列带封面的行里太轻,扫过去认不出是分界(风格指南 §2.3c:不用分割线)。
 */
@Composable
private fun ReadMarkerDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.Cozy),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Text(
                text = stringResource(Res.string.feed_read_marker),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            )
        }
    }
}


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
private fun FeedEntry.toRowUi(): VideoRowUi = when (this) {
    is FeedEntry.Video -> VideoRowUi(
        title = title,
        coverUrl = coverUrl,
        durationText = durationText,
        upName = upName,
        dateText = formatRelativeTime(publishedAtEpochSeconds),
        upFaceUrl = upFaceUrl,
        typeBadge = chargingBadgeText(chargingOnly),
    )

    is FeedEntry.Article -> VideoRowUi(
        title = title,
        coverUrl = coverUrl,
        durationText = stringResource(Res.string.feed_article_badge),
        upName = upName,
        dateText = formatRelativeTime(publishedAtEpochSeconds),
        upFaceUrl = upFaceUrl,
        note = summary.takeIf { it.isNotBlank() },
    )
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
    val shownUps = ups.take(FrequentUpLimit)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
    val startPadding = Spacing.Comfortable - if (liveUps.isNotEmpty()) LiveNowSlotFaceInset else UpSlotFaceInset
    val slotCount = shownUps.size + 1
    val contentWidth = startPadding +
        (if (liveUps.isNotEmpty()) LiveNowSlotOuterWidth + SlotGap else 0.dp) +
        UpSlotOuterWidth * slotCount + SlotGap * (slotCount - 1)
    val fits = trailingWidth != null &&
        contentWidth + Spacing.Loose + trailingWidth + Spacing.Comfortable <= maxWidth
    LaunchedEffect(fits) { onFitChange(fits) }
    val followingsSlot: @Composable (Modifier) -> Unit = { slotModifier ->
        FollowingsSlot(onOpenFollowings, slotModifier)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.Cozy),
        verticalAlignment = Alignment.Top,
    ) {
        LazyRow(
            // 右边沿淡出。滚动到边界的那个头像会被切一半 —— 切口本身是"还有更多"的信号,
            // 但硬切在一个圆形上读起来像被右边那个入口盖住了。渐隐把切口变成"没画完",
            // 那正是它的意思。左边不淡:那儿是这一排的开头,不是被截断的地方。
            modifier = Modifier.weight(1f).then(if (fits) Modifier else Modifier.fadingRightEdge()),
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
                UpSlot(label = up.name, onClick = { onUpClick(up.mid) }) {
                    Avatar(url = up.faceUrl, size = Dimens.AvatarStack)
                }
            }
            if (fits) item(key = "followings") { followingsSlot(Modifier) }
        }
        if (fits && trailing != null) {
            // 入口对着头像的中线,右沿的 › 仍在 16dp 页边线上(同标题行,见 HeaderButtonEndInset)。
            Box(
                modifier = Modifier.padding(
                    end = Spacing.Comfortable - HeaderButtonEndInset,
                    top = Spacing.Hair + (Dimens.AvatarStack - Dimens.MinTouchTarget) / 2,
                ),
            ) { trailing() }
        } else {
            // 右沿落在 16dp 页边线上,与标题行的入口、下面视频行的溢出按钮同一条竖线。
            followingsSlot(Modifier.padding(end = Spacing.Comfortable - UpSlotFaceInset))
        }
    }
    }
}

/**
 * 「关注列表」那一格,和头像格同形:圆里一个人群图标,下面一行字。
 *
 * 先后试过:排尾一格的圆底箭头,读起来像队尾站着一个没有脸的人;不衬底、不写字的光箭头,看不出
 * 通向哪里;写「全部」的竖胶囊,「全部」离开这一排说不清是全部什么,字又小;横排的 tonal 胶囊
 * 或文字按钮,手机上占掉三分之一排,头像只剩四个,和上面标题行的文字按钮叠着又是两套入口的
 * 样子。同形的一格只占一个人的宽度;圆里是一群人、下面写明去处,不会被读成一个人。
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
 * 这一排里的一格:上面是 48dp 的圆,下面一行字,宽度固定,格与格之间才对得齐。
 *
 * **一行,不是两行。** 两行版的用意是长名字不被截成认不出的前四个字,实际效果是一排里有的
 * 格一行、有的两行,名字的下沿高低不齐,而这一排横着扫过去读的正是那条下沿。认人主要靠
 * 头像,名字是确认;截掉的后半截多半是「official」「Channel」这类后缀。
 */
@Composable
private fun UpSlot(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.Hair, horizontal = SlotInset)
            .width(AvatarSlotWidth),
    ) {
        content()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.Hair),
        )
    }
}

/** 网格里横跨整行的那几格:标题行、分隔线、页脚。 */
private val FullLine: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

/** 标题行入口按钮末端的内边距。行的右边距补足剩下的部分,箭头的右沿停在 16dp 页边线上。 */
private val HeaderButtonEndInset = Spacing.Hair

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


/**
 * 这一排格与格之间的间距。算能站下几格时要用到,所以是个具名值而不是写在两处的字面量。
 *
 * **8dp 而不是 12dp**:360dp 的屏减去左右内边距是 336dp,按 12dp 排第五格要 348dp,差的
 * 正好是一道间距,于是那 60dp 空着谁也进不来。收到 8dp 之后 `5×60 + 4×8 = 332` 站得下。
 * 格与格之间实际看到的空隙比这个数大 —— [UpSlot] 自己还有 2dp 的左右内边距,而头像只有
 * 48dp,在 60dp 的格子里两边各留 6dp。
 */
private val SlotGap = Spacing.Tight

/**
 * 这一排最多摆几个人。
 *
 * **这是个上限,不是版式**:摆几个由屏宽和滚动决定,这个数只挡住"portal 哪天返回上百个"
 * 那种情况 —— 那时 LazyRow 仍然只组合可见的几格,但 20 个已经远超"横着拖两下"的耐心,
 * 再多的人本来就该从右边那个入口进完整名单。
 */
private const val FrequentUpLimit = 20
