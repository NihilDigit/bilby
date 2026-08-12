package dev.bilby.ui.feed

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.LivePulse
import dev.bilby.data.LiveUpBrief
import dev.bilby.data.UpBrief
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.semantics.Role
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.components.TrailingEntry
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import androidx.compose.ui.tooling.preview.Preview
import dev.bilby.R
import dev.bilby.data.model.FeedItem
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.ListFooter
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.BilbyTheme
import java.time.Instant
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

data class FeedUiState(
    val items: List<FeedItem> = emptyList(),
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
     * 进这一屏时读到的「上次读到哪儿了」(DESIGN 2.1)。只在进屏那一刻取一次快照,
     * 不随之后的滚动落盘而更新 —— 否则分隔线会追着当前滚动位置跑,变成什么都分不出来。
     * null 表示从没记过(第一次用)或还没读出来。
     */
    val readMarkerBvid: String? = null,
    /**
     * 开屏定位还没做过。做过之后永远为 false —— 它和 [readMarkerBvid] 是两件事:分隔线要一直
     * 画着(用户翻回去还得认得出哪儿是分界),而"滚到分隔线"只发生一次。
     */
    val pendingLocate: Boolean = true,
)

/**
 * 已读位置在当前已加载列表里的下标。**用 id 定位而不是记下标本身**是 DESIGN 2.1 的原话
 * (`FeedReadPositionEntity` 的注释同理):新投稿插入顶部会让下标整体位移,记下标就会指错。
 *
 * 返回 null 的三种情况:没记过;记录不在当前已加载的范围内(翻页翻不到那么远,老实放弃,
 * 不为了找它而自动多翻页 —— 见 FeedViewModel 的取舍);记录就是列表最新一条,上面没有
 * 「新内容」可分。
 */
internal fun List<FeedItem>.indexOfReadMarker(lastReadBvid: String?): Int? =
    lastReadBvid
        ?.let { bvid -> indexOfFirst { it.bvid == bvid } }
        ?.takeIf { it > 0 }

private const val PrefetchThreshold = 5

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
    onItemClick: (FeedItem) -> Unit,
    onUpClick: (Long) -> Unit,
    /** 从「正在直播」那张名单里选了一个,进他的直播间。 */
    onLiveClick: (Long) -> Unit,
    onOpenFollowings: () -> Unit,
    /** 折起来的那一半:图文、转发、直播……(DESIGN 2.1)。 */
    onOpenOtherDynamics: () -> Unit = {},
    onExcludeUp: (Long) -> Unit = {},
    onScrollPositionChanged: (String) -> Unit = {},
    /** 开屏定位已经做过(或确定做不成)。见 [FeedUiState.pendingLocate]。 */
    onLocated: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    when {
        state.loading && state.items.isEmpty() -> FullScreenLoading(modifier)
        state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onRetry, modifier)
        else -> FeedList(
            state, onRefresh, onLoadMore, onItemClick, onUpClick, onLiveClick, onExcludeUp,
            onOpenFollowings, onOpenOtherDynamics, onScrollPositionChanged, onLocated, modifier, contentPadding,
        )
    }
}

@Composable
private fun FeedList(
    state: FeedUiState,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (FeedItem) -> Unit,
    onUpClick: (Long) -> Unit,
    onLiveClick: (Long) -> Unit,
    onExcludeUp: (Long) -> Unit,
    onOpenFollowings: () -> Unit,
    onOpenOtherDynamics: () -> Unit,
    onScrollPositionChanged: (String) -> Unit,
    onLocated: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val markerIndex = state.items.indexOfReadMarker(state.readMarkerBvid)
    val wide = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)
    // 那一排头像排在动态流前面,分隔线/条目在 LazyColumn 里的绝对下标要把它加回来。
    // **宽屏下它不在这个列表里**(挪到了旁边的次区),这时不能加,否则开屏定位会差一格。
    // 「最常访问」那一格在没人可显示时整格不画,正在直播的那一格自己也可以撑起它 ——
    // 判据必须和下面渲染时用的是同一个,差一格就是开屏定位落错一条。
    val hasUpsRow = state.topUps.isNotEmpty() || state.liveUps.isNotEmpty()
    // 「其他动态」那一行**两种宽度下都在列表里**,所以恒占一格。它不像「最常访问」那样会被
    // 挪到旁边:那一排是一组人,占得住次区一整栏;这一行只有一句话。
    val baseOffset = (if (!wide && hasUpsRow) 1 else 0) + 1

    var liveSheetOpen by rememberSaveable { mutableStateOf(false) }

    // 触底预取:在 composition 外用 snapshotFlow 观察滚动位置,避免在 composable 里直接调用副作用。
    LaunchedEffect(listState, state.hasMore, state.appending) {
        snapshotFlow { listState.layoutInfo }
            .map { it.visibleItemsInfo.lastOrNull()?.index to it.totalItemsCount }
            .distinctUntilChanged()
            .filter { (lastVisible, total) -> lastVisible != null && lastVisible >= total - 1 - PrefetchThreshold }
            .collect {
                if (state.hasMore && !state.appending) onLoadMore()
            }
    }

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

    // 顶部可见条目上报给 ViewModel 去抖落盘。用 layoutInfo 里第一个「是视频条目」的 key,
    // 不用 firstVisibleItemIndex 反查 —— 分隔线、顶部 UP 排都会占用 LazyColumn 的下标,
    // 换算回 state.items 的下标要跟着这两样是否存在反复调整,直接认 key 更不容易算错。
    val bvidSet = remember(state.items) { state.items.mapTo(HashSet()) { it.bvid } }
    LaunchedEffect(listState, bvidSet) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo }
            .mapNotNull { visible -> visible.firstOrNull { (it.key as? String) in bvidSet }?.key as? String }
            .distinctUntilChanged()
            .collect { onScrollPositionChanged(it) }
    }

    // 宽屏只收窄行长,不拆栏。列表行是"封面 + 三行文字"的定宽版式,铺到 1400dp 之后封面
    // 还是 128dp,右边多出来的全是空白;而拆成两栏会让"下一条是什么"变成两条线索,
    // 这一页的读法本来就是一条时间线往下走。
    val feedList: @Composable (Modifier) -> Unit = { listModifier ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = listModifier,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
        // 窄屏时「最常访问」仍然跟着列表一起滚,不吸顶:吸顶会让它变成常驻的入口带,
        // 而这一页的主体是动态流。宽屏下它挪到旁边的次区去了,这里就不再出现。
        if (!wide && hasUpsRow) {
            item(key = "frequent-ups") {
                FrequentUpsRow(
                    ups = state.topUps,
                    liveUps = state.liveUps,
                    liveCount = state.liveCount,
                    special = state.topUpsAreSpecial,
                    onUpClick = onUpClick,
                    onOpenLiveNow = { liveSheetOpen = true },
                    onOpenFollowings = onOpenFollowings,
                )
            }
        }
        // 首页装不下的另一半(图文、转发、直播、专栏)的入口。
        //
        // **一行字,不是一格卡片,也不占顶栏。** 首页的主体是投稿时间序,这条入口通往的是
        // 另一种东西,不是它的续篇。放在这里而不是列表末尾,是因为这条时间序流实际上翻不到底
        // (见函数头注释),末尾没人到得了。
        //
        // **但不降调。** 这一行的字曾经是 onSurfaceVariant,理由抄的是 DESIGN 2.1 那句
        // 「折叠为一个不显眼的入口」—— 而那正是 CLAUDE.md 点名删掉的那类发明:把入口做得
        // 更难找不是克制,是替用户决定他不该去那儿。它是一条普通入口,就按普通入口画。
        //
        // **永远不给它红点、未读计数或带数量的角标。** 那些是 DESIGN 1.3 永不实现清单上的
        // 第一条,而这一行正是它们最容易被加回来的位置 —— 「顺手显示有几条新的」听起来是
        // 信息,实际是把一条静态入口变成催人回来的提醒。
        //
        // 文案不写「刷」这类口语,也不用中点分隔(见 MetaSeparator)。
        item(key = "other-dynamics") {
            TrailingEntry(
                text = stringResource(R.string.dynamic_other_entry),
                icon = Icons.AutoMirrored.Filled.Article,
                onClick = onOpenOtherDynamics,
            )
        }
        if (state.items.isEmpty()) {
            item(key = "empty") { EmptyState(stringResource(R.string.feed_empty)) }
        }
        val beforeMarker = if (markerIndex != null) state.items.subList(0, markerIndex) else state.items
        val fromMarker = if (markerIndex != null) state.items.subList(markerIndex, state.items.size) else emptyList()
        items(beforeMarker, key = { it.bvid }) { item ->
            FeedVideoItem(item, onItemClick, onExcludeUp)
        }
        if (markerIndex != null) {
            item(key = "read-marker") { ReadMarkerDivider() }
        }
        items(fromMarker, key = { it.bvid }) { item ->
            FeedVideoItem(item, onItemClick, onExcludeUp)
        }
            item(key = "footer") {
                ListFooter(
                    appending = state.appending,
                    hasMore = state.hasMore,
                    hasItems = state.items.isNotEmpty(),
                )
            }
            }
        }
    }

    if (wide) {
        /*
         * **宽屏把「最常访问」挪到旁边。**
         *
         * 它本来就不是这条时间线的一部分 —— 它是"去谁那儿看看"的导航,混在流里当第一条,
         * 读起来像"今天的第一条动态是一排头像"。canonical-examples 对 supporting pane 的
         * 说法正好:主区放主内容,次区放支持性内容。
         *
         * 顺带解决另一件事:这一页的行是"定宽封面 + 三行文字",宽屏下右边那片空白本来
         * 什么也没干,现在装的是原本要占掉列表一屏高度的那排头像。
         */
        Row(modifier = modifier.fillMaxSize()) {
            feedList(Modifier.weight(2f).fillMaxHeight())
            FrequentUpsPane(
                ups = state.topUps,
                liveUps = state.liveUps,
                liveCount = state.liveCount,
                special = state.topUpsAreSpecial,
                onUpClick = onUpClick,
                onOpenLiveNow = { liveSheetOpen = true },
                onOpenFollowings = onOpenFollowings,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
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
 * 宽屏的次区:竖着排的「最常访问」,末尾是关注列表入口。
 *
 * 竖排而不是把那条横滚原样搬过来:次区窄而高,横滚在这个形状里既浪费高度、又要求用户在
 * 一个不该有滚动的地方滚动。
 */
@Composable
private fun FrequentUpsPane(
    ups: List<UpBrief>,
    liveUps: List<LiveUpBrief>,
    liveCount: Int,
    special: Boolean,
    onUpClick: (Long) -> Unit,
    onOpenLiveNow: () -> Unit,
    onOpenFollowings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        // 竖排里它同样排在最前面,并且和这一栏其余的行同形(ListItem + leading 头像)——
        // 横排那一格是给方格排布的,原样搬进来会是一块比周围矮一截、字也小一号的补丁。
        if (liveUps.isNotEmpty()) {
            item(key = "live-now") {
                LiveNowListRow(liveUps = liveUps, count = liveCount, onClick = onOpenLiveNow)
            }
        }
        items(ups, key = { it.mid }) { up ->
            ListItem(
                headlineContent = {
                    Text(up.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Avatar(url = up.faceUrl, size = Dimens.AvatarStack) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onUpClick(up.mid) },
            )
        }
        item(key = "all-followings") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.feed_all_followings)) },
                leadingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onOpenFollowings),
            )
        }
    }
}

/** 单条动态行,含「不再显示」的长按菜单。从 [FeedList] 拆出来是因为分隔线要把 items(...) 切成两段,两段用的是同一份行 UI。 */
@Composable
private fun FeedVideoItem(item: FeedItem, onItemClick: (FeedItem) -> Unit, onExcludeUp: (Long) -> Unit) {
    // 「不再显示」挂在长按上,不占行内位置:这是一个偶尔用一次的操作,而每一行都摆一个
    // 三点按钮,等于让一个次要动作在整页里重复几十遍,还把标题能用的宽度切掉一块。
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        VideoRow(
            item = item.toRowUi(),
            onClick = { onItemClick(item) },
            onLongClick = { menuOpen = true },
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feed_exclude_up, item.upName)) },
                onClick = {
                    menuOpen = false
                    onExcludeUp(item.upMid)
                },
            )
        }
    }
}

/**
 * 「以上是新内容」分隔线。DESIGN 2.1 只要求记住位置、不要红点/未读计数(4.2 节的永不实现
 * 清单),这条线本身不随时间变化去提醒用户回来看,进屏时算一次就不再动 —— 不落在那条禁令上。
 *
 * full-width divider:两侧内容(还没读过的新投稿 / 上次已经看到过的旧内容)在时间序流里
 * 是两段不相关的东西,符合 ui-style-guide §2.3c 里 full-width divider 的判据。
 */
@Composable
private fun ReadMarkerDivider(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(R.string.feed_read_marker),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}


@Composable
private fun FeedItem.toRowUi() = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = durationText,
    upName = upName,
    dateText = formatRelativeTime(publishedAtEpochSeconds),
    playText = playCount,
    danmakuText = danmakuCount,
)

// ---- Preview ----

private fun previewItem(bvid: String, title: String, minutesAgo: Long) = FeedItem(
    bvid = bvid,
    title = title,
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    durationText = "12:34",
    upName = "某知名UP主",
    upMid = 12345L,
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
) {
    Column(modifier = Modifier.fillMaxWidth()) {
    // **不给这一排小标题,但保留下面那条分割线。**
    //
    // 依据是 divider.md 的两条:"Only use dividers if items can't be grouped with open space"
    // 和 "Use dividers to group things, not separate individual items"。这一排是**导航**
    // (点进空间),下面是**内容**(时间序动态),分的是两个区域而不是两个条目 —— 正是它该用
    // 的地方;而留白在这里不够用,底下紧跟着就是列表项,一段空白只会被读成内边距。
    //
    // 标题去掉是因为它和分割线在说同一件事(cards.md:219 把 spacing / headlines / dividers
    // 并列为三选一),而它还要跟着名单来源在「特别关注」和「最常访问」之间切换 —— 一行会变的
    // 字,读者每次都得先认一遍;一排头像本来就说得清自己是谁。
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        LazyRow(
            // 右边沿淡出。滚动到边界的那个头像会被切一半 —— 切口本身是"还有更多"的信号,
            // 但硬切在一个圆形上读起来像被右边那个入口盖住了。渐隐把切口变成"没画完",
            // 那正是它的意思。左边不淡:那儿是这一排的开头,不是被截断的地方。
            modifier = Modifier.weight(1f).fadingRightEdge(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(SlotGap),
        ) {
            // 正在直播的那一格排在最前面,是这一排的**前置项**而不是成员之一(见 LiveNowSlot)。
            // 没人在播时它整格不画,这一排就还是原来那排。
            if (liveUps.isNotEmpty()) {
                item(key = "live-now") {
                    LiveNowSlot(liveUps = liveUps, count = liveCount, onClick = onOpenLiveNow)
                }
            }
            items(ups.take(FrequentUpLimit), key = { it.mid }) { up ->
                UpSlot(label = up.name, onClick = { onUpClick(up.mid) }) {
                    Avatar(url = up.faceUrl, size = Dimens.AvatarStack)
                }
            }
        }
        // **箭头不衬圆底。** 那个圆底原来是为了让入口读起来是这一排的一员 —— 当时它确实是,
        // 排在 LazyRow 的最后一项。现在它钉在滚动区外面,是这一排旁边的一个控件,再顶着一张
        // 和头像同形同大的圆,反倒像队尾站了个没有脸的人。
        // **只有一个箭头,不写字。** 「关注列表」四个字说的是箭头本来就在说的事,而它顶在
        // 一排人名中间,读起来像队尾还站着一个叫这个名字的人。名字留给读屏(contentDescription)。
        //
        // 箭头和下面那行「关注动态」的箭头竖着对齐:那一行是 ListItem,尾部图标按 M3 的 16dp
        // 内边距摆,24dp 图标的中心落在右边缘往里 28dp;这里 48dp 的方框里图标居中,末尾留
        // [SlotInset] × 2,中心同样是 4 + 24 = 28。
        //
        // 高度和头像那一格的头像对齐(都是 48dp、都从行顶往下 4dp),所以下面少一行字也不会
        // 让它浮在半空。
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .padding(vertical = 4.dp)
                .padding(end = SlotInset * 2)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onOpenFollowings)
                .size(Dimens.AvatarStack),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.feed_open_followings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // 见这个函数开头:这条线分的是导航区和内容区两块,不是两个条目。
    HorizontalDivider()
    }
}

/**
 * 这一排里的一格:上面是 48dp 的圆,下面一行字,宽度固定,格与格之间才对得齐。
 *
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
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = SlotInset)
            .width(AvatarSlotWidth),
    ) {
        content()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 比头像宽一点,让两行字的名字也能各自居中而不互相挤。 */
private val AvatarSlotWidth = 60.dp

/** 每格自己的左右内边距。算箭头对齐时要用到,见「关注列表」那一格的注释。 */
private val SlotInset = 2.dp


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
 * 右边沿渐隐:内容画完之后,用一道从不透明到透明的渐变按 `DstIn` 混合把最右边那几 dp 擦掉。
 *
 * **必须 `CompositingStrategy.Offscreen`**:混合模式作用在"已经画好的一层"上,不离屏合成的话
 * `DstIn` 会跟这一层底下的东西作用,把背景一起擦出一个透明洞。
 */
private fun Modifier.fadingRightEdge(width: Dp = 24.dp): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Black, Color.Transparent),
                startX = size.width - width.toPx(),
                endX = size.width,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * 这一排最多摆几个人。
 *
 * **这是个上限,不是版式**:摆几个由屏宽和滚动决定,这个数只挡住"portal 哪天返回上百个"
 * 那种情况 —— 那时 LazyRow 仍然只组合可见的几格,但 20 个已经远超"横着拖两下"的耐心,
 * 再多的人本来就该从右边那个入口进完整名单。
 */
private const val FrequentUpLimit = 20
