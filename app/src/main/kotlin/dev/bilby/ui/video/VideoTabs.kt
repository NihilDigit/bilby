package dev.bilby.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.bilby.agent.AnswerItem
import dev.bilby.api.toHttpsUrl
import dev.bilby.data.CommentSort
import dev.bilby.data.FavFolder
import dev.bilby.data.VideoDetail
import dev.bilby.data.VideoRelation
import dev.bilby.data.VideoStat
import dev.bilby.player.QueueItem
import dev.bilby.ui.comment.CommentSection
import dev.bilby.ui.comment.CommentUiState
import kotlinx.coroutines.launch

private const val REFERER = "https://www.bilibili.com"

/** 「找相关」的状态。started=false 表示用户还没点过,此时只显示按钮。 */
data class RelatedState(
    val started: Boolean = false,
    /** 助理的正文回答(用户问的是问题而不是"给我视频"时)。 */
    val summary: String? = null,
    val running: Boolean = false,
    val steps: List<String> = emptyList(),
    val answer: List<AnswerItem> = emptyList(),
    val error: String? = null,
)

/**
 * 播放队列(DESIGN 2.4b):合集分集或该 UP 的其他投稿,同时也是「听视频」要播的队列本身。
 * currentBvid 驱动列表里的高亮,shuffled 驱动顺序/随机按钮的文案。
 */
data class QueueUiState(
    val items: List<QueueItem> = emptyList(),
    val currentBvid: String? = null,
    val sourceLabel: String = "",
    val shuffled: Boolean = false,
    val loading: Boolean = false,
)

/**
 * 播放页下半部分:简介 / 评论左右滑动切换(DESIGN 2.3)。「找相关」挂在简介页末尾,
 * 是唯一的关联入口,三态由 [RelatedState] 驱动,绝不在进页时自动触发。
 */
@Composable
fun VideoTabs(
    detail: VideoDetail,
    currentCid: Long,
    related: RelatedState,
    commentState: CommentUiState,
    onFindRelated: () -> Unit,
    onListen: () -> Unit,
    queue: QueueUiState,
    onPlayQueueItem: (bvid: String) -> Unit,
    onToggleShuffle: () -> Unit,
    onUpClick: () -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    onLike: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onPlayPart: (cid: Long) -> Unit,
    onPlayEpisode: (bvid: String) -> Unit,
    onRelatedVideoClick: (bvid: String) -> Unit,
    onCommentSort: (CommentSort) -> Unit,
    onCommentLoadMore: () -> Unit,
    onExpandReplies: (Long) -> Unit,
    onSendComment: (String, Long?) -> Unit,
    onLikeComment: (Long) -> Unit,
    onDeleteComment: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 评论数用服务端给的总数,不是已渲染条数 —— 后者会随翻页一路变大,像个假计数器。
    val commentLabel = if (commentState.total > 0) "评论 ${formatCount(commentState.total.toLong())}" else "评论"
    val titles = listOf("简介", commentLabel)
    val pagerState = rememberPagerState(pageCount = { titles.size })
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(if (index == 1) commentLabel else title) },
                )
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> IntroTab(
                    detail = detail,
                    currentCid = currentCid,
                    related = related,
                    onFindRelated = onFindRelated,
                    onUpClick = onUpClick,
                    onListen = onListen,
                    queue = queue,
                    onPlayQueueItem = onPlayQueueItem,
                    onToggleShuffle = onToggleShuffle,
                    relation = relation,
                    favFolders = favFolders,
                    onLike = onLike,
                    onCoin = onCoin,
                    onOpenFavPicker = onOpenFavPicker,
                    onFavConfirm = onFavConfirm,
                    onPlayPart = onPlayPart,
                    onPlayEpisode = onPlayEpisode,
                    onRelatedVideoClick = onRelatedVideoClick,
                )
                else -> CommentSection(
                    state = commentState,
                    onSort = onCommentSort,
                    onLoadMore = onCommentLoadMore,
                    onExpandReplies = onExpandReplies,
                    onSend = onSendComment,
                    onLike = onLikeComment,
                    onDelete = onDeleteComment,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * 简介页:整体可滚动,内容比一屏长。找相关放在最后——它是关联入口,不是页面主角。
 */
@Composable
private fun IntroTab(
    detail: VideoDetail,
    currentCid: Long,
    related: RelatedState,
    onFindRelated: () -> Unit,
    onListen: () -> Unit,
    queue: QueueUiState,
    onPlayQueueItem: (bvid: String) -> Unit,
    onToggleShuffle: () -> Unit,
    onUpClick: () -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    onLike: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onPlayPart: (Long) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onRelatedVideoClick: (String) -> Unit,
) {
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(detail.title, style = MaterialTheme.typography.titleMedium)

                Text(
                    text = "${formatCount(detail.stat.view)}播放 · ${formatCount(detail.stat.danmaku)}弹幕 · " +
                        formatDate(detail.publishedAtEpochSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp).clickable(onClick = onUpClick),
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(detail.up.faceUrl)
                            .httpHeaders(NetworkHeaders.Builder().add("Referer", REFERER).build())
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(32.dp).clip(CircleShape),
                    )
                    Text(
                        detail.up.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // 听视频:队列来自当前合集,不属于合集时退化为该 UP 的投稿
                    // (DESIGN 2.4b:有限且用户显式选定的集合)。
                    TextButton(onClick = onListen) { Text("听视频") }
                }

                ActionButtonsRow(
                    stat = detail.stat,
                    relation = relation,
                    favFolders = favFolders,
                    onLike = onLike,
                    onCoin = onCoin,
                    onOpenFavPicker = onOpenFavPicker,
                    onFavConfirm = onFavConfirm,
                    modifier = Modifier.padding(top = 8.dp),
                )

                if (detail.description.isNotBlank()) {
                    Text(
                        text = detail.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .then(
                                if (descriptionExpanded) {
                                    Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                                } else {
                                    Modifier
                                },
                            )
                            .clickable { descriptionExpanded = !descriptionExpanded },
                    )
                }

                if (detail.pages.size > 1) {
                    PartRow(
                        title = "分 P",
                        prefix = "P",
                        labels = detail.pages.map { it.index to it.title },
                        isCurrent = { index -> detail.pages.getOrNull(index)?.cid == currentCid },
                        onClick = { index -> detail.pages[index].cid.let(onPlayPart) },
                    )
                }

                // 合集的分集 chip 行不再单独显示:内容已经在下面的播放队列列表里,
                // 重复一遍没有信息量(合集场景下队列来源就是这个合集,见 QueueSourceRepository.fromSeason)。

                QueueSection(
                    queue = queue,
                    onPlayQueueItem = onPlayQueueItem,
                    onToggleShuffle = onToggleShuffle,
                    onListen = onListen,
                    modifier = Modifier.padding(top = 16.dp),
                )

                RelatedSection(
                    related = related,
                    onFindRelated = onFindRelated,
                    onRelatedVideoClick = onRelatedVideoClick,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

/**
 * 点赞/投币/收藏/评论四个入口。前三个的激活态由 [relation] 驱动,relation 为 null
 * (还没查到当前账号的互动状态)时按钮不可点、显示未激活图标,避免"看起来能点但语义未知"。
 */
@Composable
private fun ActionButtonsRow(
    stat: VideoStat,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    onLike: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCoinDialog by rememberSaveable { mutableStateOf(false) }
    // 收藏夹列表是异步拉的:点击时先发起请求,等 favFolders 到位再弹框。
    var awaitingFavFolders by rememberSaveable { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = modifier) {
        ActionIconButton(
            label = "点赞",
            count = stat.like,
            active = relation?.liked == true,
            enabled = relation != null,
            activeIcon = Icons.Filled.ThumbUp,
            inactiveIcon = Icons.Outlined.ThumbUp,
            onClick = onLike,
        )
        ActionIconButton(
            label = "投币",
            count = stat.coin,
            active = (relation?.coined ?: 0) > 0,
            enabled = relation != null,
            activeIcon = Icons.Filled.MonetizationOn,
            inactiveIcon = Icons.Outlined.MonetizationOn,
            onClick = { showCoinDialog = true },
        )
        ActionIconButton(
            label = "收藏",
            count = stat.favorite,
            active = relation?.favored == true,
            enabled = relation != null,
            activeIcon = Icons.Filled.Star,
            inactiveIcon = Icons.Outlined.StarBorder,
            onClick = {
                awaitingFavFolders = true
                onOpenFavPicker()
            },
        )
        // 评论数只在 tab 标题上出现一次:同一个数字在同屏显示两遍没有信息量。
    }

    if (showCoinDialog) {
        CoinDialog(
            alreadyCoined = relation?.coined ?: 0,
            onDismiss = { showCoinDialog = false },
            onConfirm = { count, alsoLike ->
                showCoinDialog = false
                onCoin(count, alsoLike)
            },
        )
    }

    if (awaitingFavFolders && favFolders.isNotEmpty()) {
        FavPickerDialog(
            favFolders = favFolders,
            onDismiss = { awaitingFavFolders = false },
            onConfirm = { addIds, delIds ->
                awaitingFavFolders = false
                onFavConfirm(addIds, delIds)
            },
        )
    }
}

@Composable
private fun ActionIconButton(
    label: String,
    count: Long,
    active: Boolean,
    enabled: Boolean,
    activeIcon: ImageVector,
    inactiveIcon: ImageVector,
    onClick: () -> Unit,
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(if (active) activeIcon else inactiveIcon, contentDescription = label, tint = tint)
        Text(
            text = "$label ${formatCount(count)}",
            style = MaterialTheme.typography.labelMedium,
            color = tint,
        )
    }
}

/** 一次最多投 2 枚(VideoActionRepository.coin),已投满时按钮禁用并报告已投数量。 */
@Composable
private fun CoinDialog(
    alreadyCoined: Int,
    onDismiss: () -> Unit,
    onConfirm: (count: Int, alsoLike: Boolean) -> Unit,
) {
    var selectedCount by rememberSaveable { mutableStateOf(1) }
    var alsoLike by rememberSaveable { mutableStateOf(false) }
    val maxedOut = alreadyCoined >= 2

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("投币") },
        text = {
            Column {
                if (maxedOut) {
                    Text("已投 2 枚,不能再投了", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    listOf(1, 2).forEach { count ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { selectedCount = count },
                        ) {
                            RadioButton(selected = selectedCount == count, onClick = { selectedCount = count })
                            Text("$count 枚")
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { alsoLike = !alsoLike },
                    ) {
                        Checkbox(checked = alsoLike, onCheckedChange = { alsoLike = it })
                        Text("同时点赞")
                    }
                    // 投币是不可逆的:B 站没有撤销接口,币也不退。误触的代价由用户承担,
                    // 所以这句必须出现在确认之前。
                    Text(
                        "投币不可撤销,币不会退回",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !maxedOut, onClick = { onConfirm(selectedCount, alsoLike) }) {
                Text("确认")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/**
 * 收藏夹多选。确认时只算增量(新勾选的 add,取消勾选的 del),不把全部勾选项都当 add 传——
 * 服务端 fav/resource/deal 的 add/del 语义本身就是增量操作,传全集会把"没变的"也当动作提交。
 */
@Composable
private fun FavPickerDialog(
    favFolders: List<FavFolder>,
    onDismiss: () -> Unit,
    onConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
) {
    val selected = remember(favFolders) {
        mutableStateMapOf<Long, Boolean>().apply { favFolders.forEach { put(it.id, it.containsThis) } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("收藏到") },
        text = {
            Column {
                favFolders.forEach { folder ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable {
                            selected[folder.id] = !(selected[folder.id] ?: folder.containsThis)
                        },
                    ) {
                        Checkbox(
                            checked = selected[folder.id] ?: folder.containsThis,
                            onCheckedChange = { checked -> selected[folder.id] = checked },
                        )
                        Text(folder.title, modifier = Modifier.weight(1f))
                        Text(
                            formatCount(folder.count.toLong()),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val addIds = favFolders.filter { selected[it.id] == true && !it.containsThis }.map { it.id }
                val delIds = favFolders.filter { selected[it.id] == false && it.containsThis }.map { it.id }
                onConfirm(addIds, delIds)
            }) {
                Text("确认")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun PartRow(
    title: String,
    prefix: String,
    labels: List<Pair<Int, String>>,
    isCurrent: (Int) -> Boolean,
    onClick: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            itemsIndexed(labels) { index, (number, name) ->
                FilterChip(
                    selected = isCurrent(index),
                    onClick = { onClick(index) },
                    label = {
                        val label = if (prefix.isEmpty()) "$number. $name" else "$prefix$number $name"
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

/**
 * 播放队列:合集分集 / 该 UP 的其他投稿(DESIGN 2.4b)。官方在简介下方放算法召回的相关
 * 推荐,这里放的是确定性的有限集合——合集本身有限,空间投稿也在数据层被截成前后各 25 条
 * (见 QueueSourceRepository),不是"从推荐池续接",不违反 1.3 的推荐禁令。
 *
 * 这个列表同时就是「听视频」要播的队列本身,点条目直接切歌,不需要另外构造队列。
 * queue.items 为空且不在加载中时不显示这一块——合集/空间投稿都取不到时没有队列可言。
 */
private val QueueListHeight = 320.dp

@Composable
private fun QueueSection(
    queue: QueueUiState,
    onPlayQueueItem: (String) -> Unit,
    onToggleShuffle: () -> Unit,
    onListen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!queue.loading && queue.items.isEmpty()) return

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                queue.sourceLabel,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onToggleShuffle, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(if (queue.shuffled) "随机" else "顺序")
            }
            TextButton(onClick = onListen, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("听视频")
            }
        }

        if (queue.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Text(
                    "加载队列…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        } else {
            val listState = rememberLazyListState()

            // 定高列表:高度有界才能嵌在可滚动的简介页里(高度无界的 LazyColumn 会抛)。
            // 顺带让队列有个固定的占位,不会因为条数不同把下面的内容顶来顶去。
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = 4.dp).height(QueueListHeight),
            ) {
                items(queue.items, key = { it.bvid }) { item ->
                    QueueItemRow(
                        item = item,
                        current = item.bvid == queue.currentBvid,
                        onClick = { onPlayQueueItem(item.bvid) },
                    )
                }
            }

            // 当前项居中:队列是"前后各 25 条",只滚到可见位置的话它会贴在顶或底,
            // 看不出前后还有多少。
            LaunchedEffect(queue.currentBvid, queue.items) {
                val index = queue.items.indexOfFirst { it.bvid == queue.currentBvid }
                if (index >= 0) {
                    listState.scrollToItem(index)
                    val info = listState.layoutInfo
                    val row = info.visibleItemsInfo.firstOrNull { it.index == index }
                    if (row != null) {
                        listState.scrollToItem(index, -(info.viewportSize.height - row.size) / 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueItemRow(item: QueueItem, current: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.coverUrl.toHttpsUrl())
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", REFERER).build())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 72.dp, height = 45.dp).clip(RoundedCornerShape(4.dp)),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.durationSeconds > 0) {
                    Text(
                        formatDuration(item.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 官方在这个位置放相关推荐,我们放助理的「找相关」(DESIGN 2.3)。三态:
 * 未开始只有按钮和一句说明、检索中逐步展示过程、有结果展示带理由的条目。
 * 不做分页——固定一次性给 3–5 条,过程本身(搜了什么、读了谁的热评)是信任来源。
 */
@Composable
private fun RelatedSection(
    related: RelatedState,
    onFindRelated: () -> Unit,
    onRelatedVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        HorizontalDivider()
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text("找相关", style = MaterialTheme.typography.labelLarge)
            when {
                related.error != null -> {
                    Text(
                        related.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = onFindRelated, contentPadding = PaddingValues(vertical = 4.dp)) {
                        Text("重试")
                    }
                }
                !related.started -> {
                    Text(
                        "助理会搜索并翻看相关视频,给出几条带理由的结果——只在你点下面这个按钮时才会检索。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    TextButton(onClick = onFindRelated, contentPadding = PaddingValues(vertical = 4.dp)) {
                        Text("找相关")
                    }
                }
                related.running -> {
                    Column(modifier = Modifier.padding(top = 4.dp)) {
                        related.steps.forEach { step ->
                            Text(
                                "· $step",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            Text(
                                "检索中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
                else -> {
                    // 检索过程留痕:结果不是凭空出现的,是这些步骤的产物。
                    if (related.steps.isNotEmpty()) {
                        Column(modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                            related.steps.forEach { step ->
                                Text(
                                    "· $step",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                    if (related.answer.isEmpty()) {
                        Text(
                            "没找到合适的",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Column(modifier = Modifier.padding(top = 4.dp)) {
                            related.answer.forEach { item ->
                                RelatedAnswerRow(item, onClick = { onRelatedVideoClick(item.bvid) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RelatedAnswerRow(item: AnswerItem, onClick: () -> Unit) {
    val trace = item.trace
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (trace != null) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(trace.coverUrl.toHttpsUrl())
                        .httpHeaders(NetworkHeaders.Builder().add("Referer", REFERER).build())
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 72.dp, height = 45.dp).clip(RoundedCornerShape(4.dp)),
                )
            } else {
                Box(modifier = Modifier.size(width = 72.dp, height = 45.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    trace?.title ?: item.bvid,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (trace?.upName != null) {
                    Text(
                        trace.upName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                // 理由是这个区块与推荐流的根本区别,永远显示,不折叠。
                Text(
                    item.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Icon(
                Icons.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatCount(value: Long): String = when {
    value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000.0)
    value >= 10_000 -> "%.1f万".format(value / 10_000.0)
    else -> value.toString()
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun formatDate(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
