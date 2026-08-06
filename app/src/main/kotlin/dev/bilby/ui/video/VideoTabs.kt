package dev.bilby.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.agent.AnswerItem
import dev.bilby.data.CommentSort
import dev.bilby.data.FavFolder
import dev.bilby.data.VideoDetail
import dev.bilby.data.VideoRelation
import dev.bilby.data.VideoStat
import dev.bilby.player.QueueItem
import dev.bilby.ui.comment.CommentSection
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.CompactVideoRow
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.SectionHeader
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.launch

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
 * 播放页下半部分:简介 / 评论左右滑动切换(DESIGN 2.3)。
 *
 * 用 **secondary** tabs:M3 把 primary tabs 定义为"贴在 app bar 下面、代表页面主内容分区"的,
 * secondary tabs 才是"在内容区域内部再分一层"。这两个标签上面顶着播放器而不是顶栏,
 * 属于后者。空间页那三个标签紧贴顶栏,那边才用 primary。
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
    addedToView: Boolean,
    onLike: () -> Unit,
    onAddToView: () -> Unit,
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
        SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) },
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
                    addedToView = addedToView,
                    onLike = onLike,
                    onAddToView = onAddToView,
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
    addedToView: Boolean,
    onLike: () -> Unit,
    onAddToView: () -> Unit,
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
            Column(
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Text(detail.title, style = MaterialTheme.typography.titleMedium)

                Text(
                    text = "${formatCount(detail.stat.view)}播放 · ${formatCount(detail.stat.danmaku)}弹幕 · " +
                        formatDate(detail.publishedAtEpochSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                UpRow(
                    faceUrl = detail.up.faceUrl,
                    name = detail.up.name,
                    onUpClick = onUpClick,
                    onListen = onListen,
                )

                ActionButtonsRow(
                    stat = detail.stat,
                    relation = relation,
                    favFolders = favFolders,
                    addedToView = addedToView,
                    onLike = onLike,
                    onAddToView = onAddToView,
                    onCoin = onCoin,
                    onOpenFavPicker = onOpenFavPicker,
                    onFavConfirm = onFavConfirm,
                )

                if (detail.description.isNotBlank()) {
                    Description(
                        text = detail.description,
                        expanded = descriptionExpanded,
                        onToggle = { descriptionExpanded = !descriptionExpanded },
                    )
                }

                if (detail.pages.size > 1) {
                    PartRow(
                        labels = detail.pages.map { it.index to it.title },
                        isCurrent = { index -> detail.pages.getOrNull(index)?.cid == currentCid },
                        onClick = { index -> detail.pages[index].cid.let(onPlayPart) },
                    )
                }

                // 合集的分集 chip 行不再单独显示:内容已经在下面的播放队列列表里,
                // 重复一遍没有信息量(合集场景下队列来源就是这个合集,
                // 见 QueueSourceRepository.fromSeason)。

                QueueSection(
                    queue = queue,
                    onPlayQueueItem = onPlayQueueItem,
                    onToggleShuffle = onToggleShuffle,
                    modifier = Modifier.padding(top = Spacing.Tight),
                )

                RelatedSection(
                    related = related,
                    onFindRelated = onFindRelated,
                    onRelatedVideoClick = onRelatedVideoClick,
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }
        }
    }
}

@Composable
private fun UpRow(
    faceUrl: String,
    name: String,
    onUpClick: () -> Unit,
    onListen: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onUpClick)
                .heightIn(min = Dimens.MinTouchTarget),
        ) {
            Avatar(url = faceUrl, size = Dimens.AvatarMedium)
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 听视频:队列来自当前合集,不属于合集时退化为该 UP 的投稿
        // (DESIGN 2.4b:有限且用户显式选定的集合)。它是这一页的第二主要动作(第一是播放),
        // 用 tonal button —— M3 说要提升某个动作的可见度就换成 filled/tonal,一屏只留一个。
        FilledTonalButton(onClick = onListen) {
            Icon(Icons.Filled.Headphones, contentDescription = null, modifier = Modifier.size(Dimens.IconInline))
            Text("听视频", modifier = Modifier.padding(start = Spacing.Tight))
        }
    }
}

/** 简介默认两行,展开后给一段有界高度可滚,不让它把整页顶到看不见队列。 */
@Composable
private fun Description(text: String, expanded: Boolean, onToggle: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (expanded) Int.MAX_VALUE else 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (expanded) {
                    Modifier.heightIn(max = ExpandedDescriptionMaxHeight).verticalScroll(rememberScrollState())
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onToggle),
    )
}

private val ExpandedDescriptionMaxHeight = 180.dp

/**
 * 点赞 / 投币 / 收藏 / 稍后再看。
 *
 * 用 [ButtonGroup] 而不是自己排一个 Row(material3 1.5.0-alpha25 才有这个组件):
 *
 * - **选中态配色归组件管。** 以前是一个裸的 `Column.clickable`,自己 `if (active) primary else
 *   onSurfaceVariant` 调 tint,还得自己撑 48dp 触摸目标。`toggleableItem` 两件事都自带。
 * - **放不下时自动收进溢出菜单。** 四个带计数的动作在 360dp 宽的屏上已经很挤,以前是硬挤,
 *   窄屏或大字号下文字直接截断;现在挤不下的自动进 [overflowIndicator] 的菜单,
 *   而且菜单项用的就是这里给的 label,不用另写一份。
 *
 * relation 为 null(还没查到当前账号的互动状态)时前三个不可点 ——
 * 避免"看起来能点但语义未知"。稍后再看不依赖 relation:它是只进不出的动作,
 * 不需要知道当前状态就能执行。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionButtonsRow(
    stat: VideoStat,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onAddToView: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showCoinDialog by rememberSaveable { mutableStateOf(false) }
    // 收藏夹列表是异步拉的:点击时先发起请求,等 favFolders 到位再弹框。
    var awaitingFavFolders by rememberSaveable { mutableStateOf(false) }

    ButtonGroup(
        modifier = modifier.fillMaxWidth(),
        overflowIndicator = { menuState ->
            FilledTonalIconButton(onClick = { menuState.show() }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
            }
        },
    ) {
        toggleableItem(
            checked = relation?.liked == true,
            onCheckedChange = { onLike() },
            label = "赞 ${formatCount(stat.like)}",
            icon = {
                Icon(
                    imageVector = if (relation?.liked == true) Icons.Filled.ThumbUp else Icons.Outlined.ThumbUp,
                    contentDescription = null,
                )
            },
            weight = 1f,
            enabled = relation != null,
        )
        // 投币也用 toggleableItem。它点下去是弹框(问投几枚)而不是直接投,但"已投币"是一个
        // 实实在在的可显示状态,而 clickableItem 会渲染成实心按钮 —— 在一排里看起来像主操作,
        // 而投币恰恰是这排里最不该被误触的那个。
        toggleableItem(
            checked = (relation?.coined ?: 0) > 0,
            onCheckedChange = { showCoinDialog = true },
            label = "币 ${formatCount(stat.coin)}",
            icon = {
                Icon(
                    imageVector = if ((relation?.coined ?: 0) > 0) {
                        Icons.Filled.MonetizationOn
                    } else {
                        Icons.Outlined.MonetizationOn
                    },
                    contentDescription = null,
                )
            },
            weight = 1f,
            enabled = relation != null,
        )
        // 收藏同样要弹框选收藏夹,但它有明确的"已收藏"状态,所以仍用 toggleableItem 表达状态,
        // 点击时只负责把框拉起来,真正的增删在框里确认。
        toggleableItem(
            checked = relation?.favored == true,
            onCheckedChange = {
                awaitingFavFolders = true
                onOpenFavPicker()
            },
            label = "藏 ${formatCount(stat.favorite)}",
            icon = {
                Icon(
                    imageVector = if (relation?.favored == true) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = null,
                )
            },
            weight = 1f,
            enabled = relation != null,
        )
        // 稍后再看:**只进不出**。取消勾选不做任何事 —— 移除在稍后再看页面做,
        // 那里是个列表,划掉一条是自然动作;在这里做 toggle 就得先拉整个列表才能知道当前状态。
        toggleableItem(
            checked = addedToView,
            onCheckedChange = { checked -> if (checked) onAddToView() },
            label = if (addedToView) "已加入" else "稍后看",
            icon = {
                Icon(
                    imageVector = if (addedToView) Icons.Filled.WatchLater else Icons.Outlined.WatchLater,
                    contentDescription = null,
                )
            },
            weight = 1f,
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

/** 一次最多投 2 枚(VideoActionRepository.coin),已投满时按钮禁用并报告已投数量。 */
@Composable
private fun CoinDialog(
    alreadyCoined: Int,
    onDismiss: () -> Unit,
    onConfirm: (count: Int, alsoLike: Boolean) -> Unit,
) {
    var selectedCount by rememberSaveable { mutableIntStateOf(1) }
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
                        ChoiceRow(
                            selected = selectedCount == count,
                            onSelect = { selectedCount = count },
                            label = "$count 枚",
                        )
                    }
                    ToggleRow(
                        checked = alsoLike,
                        onToggle = { alsoLike = it },
                        label = "同时点赞",
                    )
                    // 投币是不可逆的:B 站没有撤销接口,币也不退。误触的代价由用户承担,
                    // 所以这句必须出现在确认之前 —— 这是全应用少数几个用 error 色的地方。
                    Text(
                        text = "投币不可撤销,币不会退回",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.Tight),
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
 * 单选行。用 `Modifier.selectable` 而不是给 RadioButton 单独挂 onClick:
 * 前者让整行成为一个可选中的语义节点(读屏念"单选按钮,已选中,2 枚"),
 * 后者会让读屏把控件和文字当成两件无关的东西,而且只有那个小圆点能点。
 */
@Composable
private fun ChoiceRow(selected: Boolean, onSelect: () -> Unit, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = Spacing.Tight))
    }
}

/** 同上,复选版。 */
@Composable
private fun ToggleRow(checked: Boolean, onToggle: (Boolean) -> Unit, label: String, count: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onToggle),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = label,
            modifier = Modifier.weight(1f).padding(start = Spacing.Tight),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        count?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
                    ToggleRow(
                        checked = selected[folder.id] ?: folder.containsThis,
                        onToggle = { checked -> selected[folder.id] = checked },
                        label = folder.title,
                        count = formatCount(folder.count.toLong()),
                    )
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

/**
 * 分 P。这里是 chip 而不是 segmented button:条数随视频变(1 到几百都有)、要横滚、
 * 是"当前任务的分支路径"—— M3 给 chip 的定义。segmented button 是固定的几个视图切换。
 */
@Composable
private fun PartRow(
    labels: List<Pair<Int, String>>,
    isCurrent: (Int) -> Boolean,
    onClick: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
        SectionHeader("分 P")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            itemsIndexed(labels) { index, pair ->
                FilterChip(
                    selected = isCurrent(index),
                    onClick = { onClick(index) },
                    label = {
                        Text("P${pair.first} ${pair.second}", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
 * queue.items 为空且不在加载中时不显示这一块。
 */
@Composable
private fun QueueSection(
    queue: QueueUiState,
    onPlayQueueItem: (String) -> Unit,
    onToggleShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!queue.loading && queue.items.isEmpty()) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
        SectionHeader(title = queue.sourceLabel) {
            // 顺序/随机只有两态,是个开关而不是两个选项,所以用带图标的 text button
            // 而不是 segmented button —— 后者会让人以为还有第三格。
            TextButton(onClick = onToggleShuffle, contentPadding = PaddingValues(horizontal = Spacing.Tight)) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(Dimens.IconInline))
                Text(
                    text = if (queue.shuffled) "随机" else "顺序",
                    modifier = Modifier.padding(start = Spacing.Hair),
                )
            }
        }

        if (queue.loading) {
            InlineProgress("加载队列…", Modifier.padding(vertical = Spacing.Tight))
        } else {
            val listState = rememberLazyListState()

            // 定高列表:高度有界才能嵌在可滚动的简介页里(高度无界的 LazyColumn 会抛)。
            // 顺带让队列有个固定的占位,不会因为条数不同把下面的内容顶来顶去。
            LazyColumn(
                state = listState,
                modifier = Modifier.height(Dimens.EmbeddedQueueHeight),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(queue.items, key = { it.bvid }) { item ->
                    CompactVideoRow(
                        title = item.title,
                        coverUrl = item.coverUrl,
                        subtitle = if (item.durationSeconds > 0) formatDuration(item.durationSeconds) else null,
                        selected = item.bvid == queue.currentBvid,
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
        HorizontalDivider()
        SectionHeader("找相关")
        when {
            related.error != null -> {
                Text(
                    text = related.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onFindRelated) { Text("重试") }
            }

            !related.started -> {
                Text(
                    text = "助理会搜索并翻看相关视频,给出几条带理由的结果——只在你点下面这个按钮时才会检索。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 这是整页唯一的"关联入口",而且必须由用户显式发起,所以给它一个真正的按钮,
                // 不是一行看起来像链接的字。
                FilledTonalButton(onClick = onFindRelated) { Text("找相关") }
            }

            related.running -> {
                related.steps.forEach { step -> StepLine(step) }
                InlineProgress("检索中…")
            }

            else -> {
                // 检索过程留痕:结果不是凭空出现的,是这些步骤的产物。
                related.steps.forEach { step -> StepLine(step) }
                if (related.answer.isEmpty()) {
                    Text(
                        text = "没找到合适的",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    related.answer.forEach { item ->
                        RelatedAnswerRow(item, onClick = { onRelatedVideoClick(item.bvid) })
                    }
                }
            }
        }
    }
}

@Composable
private fun StepLine(step: String) {
    Text(
        text = "· $step",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * 找相关的结果。和列表页用同一个 [VideoRow],只是多带一句理由 ——
 * 理由是这个区块与推荐流的根本区别,永远显示,不折叠、不截断。
 */
@Composable
private fun RelatedAnswerRow(item: AnswerItem, onClick: () -> Unit) {
    VideoRow(
        item = VideoRowUi(
            title = item.trace?.title ?: item.bvid,
            coverUrl = item.trace?.coverUrl.orEmpty(),
            upName = item.trace?.upName,
            note = item.reason,
            accentNote = true,
        ),
        onClick = onClick,
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        },
    )
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
