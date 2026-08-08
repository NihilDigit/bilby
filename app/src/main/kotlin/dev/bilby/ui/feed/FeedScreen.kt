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
import dev.bilby.ui.components.SectionHeader
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
)

private const val PrefetchThreshold = 5

/**
 * 动态流。列表本身刻意不做特殊设计(DESIGN 2.1):没有红点、没有未读计数,底部会明确说
 * "没有更多了" —— 时间序动态流天生能刷完,刷完就得看得出来。
 *
 * **下拉刷新是有的。** 这里一度把它一并禁掉,理由是"下拉刷新属于变比率奖励的仪式"。那条
 * 推理套错了对象:老虎机的前提是每拉一次都可能掉出新东西,而关注动态是个有限集合,刷出来的
 * 只有关注的人真的发了的那些,发完就没了——底部那句"没有更多了"就是证据。拒绝刷新并不能让人
 * 少看,只会让人退出重进,或者干等着不知道有没有更新。
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
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    when {
        state.loading && state.items.isEmpty() -> FullScreenLoading(modifier)
        state.error != null && state.items.isEmpty() -> FullScreenError(state.error, onRetry, modifier)
        else -> FeedList(state, onRefresh, onLoadMore, onItemClick, onUpClick, onExcludeUp, onOpenFollowings, modifier, contentPadding)
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
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    val listState = rememberLazyListState()

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

    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
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
        items(state.items, key = { it.bvid }) { item ->
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
