package dev.bilby.ui.feed

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.data.UpBrief
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.res.stringResource
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.SectionHeader
import dev.bilby.ui.theme.Breakpoints
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
     * 顶上那排"最常访问"的 UP。顺序是服务端给的,本地不排序也不缓存 ——
     * 它是导航(点进空间),不参与也不影响下面这条时间序动态流。
     *
     * 取不到就是空列表,整排消失,不占位、不显示错误:这一排是快捷方式,
     * 拿不到它不妨碍这一页做正事。
     */
    val frequentUps: List<UpBrief> = emptyList(),
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
    onOpenFollowings: () -> Unit,
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
            state, onRefresh, onLoadMore, onItemClick, onUpClick, onExcludeUp,
            onOpenFollowings, onScrollPositionChanged, onLocated, modifier, contentPadding,
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
    onExcludeUp: (Long) -> Unit,
    onOpenFollowings: () -> Unit,
    onScrollPositionChanged: (String) -> Unit,
    onLocated: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    val markerIndex = state.items.indexOfReadMarker(state.readMarkerBvid)
    // frequentUps 那一格排在动态流前面,分隔线/条目在 LazyColumn 里的绝对下标要把它加回来。
    val baseOffset = if (state.frequentUps.isNotEmpty()) 1 else 0

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
    AdaptiveContent(modifier = modifier, maxWidth = Breakpoints.ReadableWidth) {
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = contentPadding,
            ) {
        // 跟着列表一起滚,不吸顶:吸顶会让它变成常驻的入口带,而这一页的主体是动态流。
        if (state.frequentUps.isNotEmpty()) {
            item(key = "frequent-ups") {
                FrequentUpsRow(
                    ups = state.frequentUps,
                    onUpClick = onUpClick,
                    onOpenFollowings = onOpenFollowings,
                )
            }
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


// @Composable 只为了取字符串资源:相对时间的字面("刚刚""3分钟前")是本地化内容,
// 不能写死在这里。调用点本来就在 composable 作用域内。
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

private val AbsoluteDateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

@Composable
private fun formatRelativeTime(epochSeconds: Long, nowEpochSeconds: Long = Instant.now().epochSecond): String {
    val diff = nowEpochSeconds - epochSeconds
    return when {
        diff < 60 -> stringResource(R.string.time_just_now)
        diff < 3600 -> stringResource(R.string.time_minutes_ago, diff / 60)
        diff < 24 * 3600 -> stringResource(R.string.time_hours_ago, diff / 3600)
        diff < 2 * 24 * 3600 -> stringResource(R.string.time_yesterday)
        diff < 7 * 24 * 3600 -> stringResource(R.string.time_days_ago, diff / (24 * 3600))
        else -> Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(AbsoluteDateFormatter)
    }
}

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
        FeedScreen(FeedUiState(items = previewItems, hasMore = true), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "已刷完")
@Composable
private fun FeedScreenNoMorePreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(items = previewItems, hasMore = false), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "空")
@Composable
private fun FeedScreenEmptyPreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(items = emptyList(), hasMore = false), {}, {}, {}, {}, {}, {})
    }
}

@Preview(showBackground = true, name = "错误")
@Composable
private fun FeedScreenErrorPreview() {
    BilbyTheme {
        FeedScreen(FeedUiState(error = "网络连接失败"), {}, {}, {}, {}, {}, {})
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
    onUpClick: (Long) -> Unit,
    onOpenFollowings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
    // 小标题:没有它,这一排头像和下面的动态之间只有间距,读起来像同一块内容的一部分。
    // 加了标题就说清了"这是谁",也顺带把它和时间序流的边界画出来。
    SectionHeader(
        title = stringResource(R.string.feed_frequent_ups),
        modifier = Modifier.padding(horizontal = Spacing.Comfortable),
    )
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(ups, key = { it.mid }) { up ->
            UpSlot(label = up.name, onClick = { onUpClick(up.mid) }) {
                BiliAsyncImage(
                    url = up.faceUrl,
                    contentDescription = null,
                    modifier = Modifier.size(AvatarSize).clip(CircleShape),
                )
            }
        }
        // 入口做成横排的最后一项,而不是浮在右边的一个文字按钮。
        //
        // 放在右侧时它和头像不是同一种东西却并排站着:头像是有名字的方格,它是一块悬空的文字,
        // 还会把最后一个头像压在底下,滚到头也露不全。做成同样的圆形槽位之后,它成了这一排的
        // 一员 —— 读法是"...还有全部",而不是"这排东西,以及右边那个按钮"。
        item(key = "open-followings") {
            UpSlot(
                label = stringResource(R.string.feed_open_followings),
                onClick = onOpenFollowings,
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(AvatarSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    // 分割线把这一排和下面的时间序流断开。它不只是装饰:这一排是**导航**(点进空间),
    // 下面是**内容**(时间序动态),两者读法不同,中间没有边界的话整块会被读成一个列表的开头。
    HorizontalDivider()
    }
}

/** 这一排里的一格:上面是 48dp 的圆,下面一行字,宽度固定,格与格之间才对得齐。 */
@Composable
private fun UpSlot(
    label: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp)
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

private val AvatarSize = 48.dp

/** 比头像宽一点,让两行字的名字也能各自居中而不互相挤。 */
private val AvatarSlotWidth = 60.dp
