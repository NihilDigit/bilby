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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.formatDurationSeconds
import dev.bilby.agent.AnswerBlock
import dev.bilby.data.CommentSort
import dev.bilby.data.FavFolder
import dev.bilby.data.FollowState
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
import dev.bilby.ui.components.StatRow
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.launch

/** 「找相关」的状态。started=false 表示用户还没点过,此时只显示按钮。 */
data class RelatedState(
    val started: Boolean = false,
    val running: Boolean = false,
    val steps: List<String> = emptyList(),
    /** 一段夹着视频卡片的正文,和搜索页共用 AnswerBlocks 渲染。 */
    val blocks: List<AnswerBlock> = emptyList(),
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
    followState: FollowState,
    onToggleFollow: () -> Unit,
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
    val commentLabel = if (commentState.total > 0) {
        stringResource(R.string.video_tab_comment_count, formatCount(commentState.total.toLong()))
    } else {
        stringResource(R.string.video_tab_comment)
    }
    val titles = listOf(stringResource(R.string.video_tab_intro), commentLabel)
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
                    followState = followState,
                    onToggleFollow = onToggleFollow,
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
    followState: FollowState,
    onToggleFollow: () -> Unit,
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
    var infoExpanded by rememberSaveable { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                TitleBlock(
                    detail = detail,
                    expanded = infoExpanded,
                    onToggle = { infoExpanded = !infoExpanded },
                )

                UpRow(
                    faceUrl = detail.up.faceUrl,
                    name = detail.up.name,
                    onUpClick = onUpClick,
                    followState = followState,
                    onToggleFollow = onToggleFollow,
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

                // 找相关的结果不在这里,在页面底部的 sheet 里(见 VideoScreen):
                // 它是对当前视频问的一句话,不该把简介页顶下去。
                QueueSection(
                    queue = queue,
                    onPlayQueueItem = onPlayQueueItem,
                    onToggleShuffle = onToggleShuffle,
                    onFindRelated = onFindRelated,
                    modifier = Modifier.padding(top = Spacing.Hair),
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
    followState: FollowState,
    onToggleFollow: () -> Unit,
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
        FollowButton(state = followState, onClick = onToggleFollow)
    }
}

/**
 * 标题 + 计数行 + (展开后)bvid 与简介。
 *
 * 这三段合成一个可展开的块,是照 PiliPlus 的 `introduction/ugc/view.dart`(它的
 * `ExpandablePanel` 收起时只给标题两行,展开后才露出 bvid、简介和标签)。
 * 以前标题不限行数、简介另有一个展开开关,长标题会把 UP 主那一行和动作栏一起顶下去,
 * 而简介的展开箭头又落在半屏之外 —— 两个开关管的其实是同一件事:这条视频要看多细。
 *
 * 展开指示放在计数行右端,不放标题末尾:标题会截断,截断处的箭头看起来像正文的一部分。
 */
@Composable
private fun TitleBlock(detail: VideoDetail, expanded: Boolean, onToggle: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Text(
            text = detail.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatRow(
                modifier = Modifier.weight(1f),
                playText = formatCount(detail.stat.view),
                danmakuText = formatCount(detail.stat.danmaku),
                dateText = formatDate(detail.publishedAtEpochSeconds),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.video_intro_collapse else R.string.video_intro_expand,
                ),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(Dimens.IconInline),
            )
        }
        if (expanded) {
            Text(
                text = detail.bvid,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
            if (detail.description.isNotBlank()) {
                Text(
                    text = detail.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 点赞 / 投币 / 收藏 / 稍后再看。**图标在上、计数在下,四个等宽平分一行**,
 * 照 PiliPlus 的 `introduction/ugc/widgets/action_item.dart`(它是 48dp 高的 Row,
 * 每项 `Expanded`,图标 18dp、计数用 labelSmall,未选中取 `outline`、选中取 `primary`)。
 *
 * 这里换掉了上一轮的 `ButtonGroup`。换回来的理由不是"更像 B 站",是**四个带计数的动作
 * 在 360dp 宽的屏上排成横向药丸根本放不下**:一项分到 85dp,而 "赞 12.3万" 这样的
 * 标签在 labelLarge 下要 90dp 往上,于是 `ButtonGroup` 每次都把最后一两个动作收进溢出
 * 菜单 —— 投币和收藏藏在一个 ⋮ 后面。图标叠计数的排法把同样的信息压到 55dp 宽,
 * 四项全部露出,还能各自占满 90dp × 48dp 的触摸区。
 *
 * 代价是选中态配色和触摸目标要自己写(`ButtonGroup` 自带),两者都在 [ActionItem] 里。
 *
 * relation 为 null(还没查到当前账号的互动状态)时前三个不可点 ——
 * 避免"看起来能点但语义未知"。稍后再看不依赖 relation:它是只进不出的动作,
 * 不需要知道当前状态就能执行。
 */
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

    Row(modifier = modifier.fillMaxWidth()) {
        ActionItem(
            modifier = Modifier.weight(1f),
            selected = relation?.liked == true,
            enabled = relation != null,
            label = formatCount(stat.like),
            contentDescription = stringResource(
                if (relation?.liked == true) R.string.video_action_unlike else R.string.video_action_like,
            ),
            selectedIcon = Icons.Filled.ThumbUp,
            icon = Icons.Outlined.ThumbUp,
            onClick = onLike,
        )
        // 投币点下去是弹框(问投几枚)而不是直接投,但"已投币"是一个实实在在的可显示状态,
        // 所以它和点赞用同一种表达,区别只在点击后发生什么。
        ActionItem(
            modifier = Modifier.weight(1f),
            selected = (relation?.coined ?: 0) > 0,
            enabled = relation != null,
            label = formatCount(stat.coin),
            contentDescription = stringResource(R.string.video_action_coin),
            selectedIcon = Icons.Filled.MonetizationOn,
            icon = Icons.Outlined.MonetizationOn,
            onClick = { showCoinDialog = true },
        )
        ActionItem(
            modifier = Modifier.weight(1f),
            selected = relation?.favored == true,
            enabled = relation != null,
            label = formatCount(stat.favorite),
            contentDescription = stringResource(R.string.video_action_favorite),
            selectedIcon = Icons.Filled.Star,
            icon = Icons.Outlined.StarBorder,
            onClick = {
                awaitingFavFolders = true
                onOpenFavPicker()
            },
        )
        // 稍后再看:**只进不出**。已加入后点击不做任何事 —— 移除在稍后再看页面做,
        // 那里是个列表,划掉一条是自然动作;在这里做 toggle 就得先拉整个列表才能知道当前状态。
        ActionItem(
            modifier = Modifier.weight(1f),
            selected = addedToView,
            enabled = true,
            label = stringResource(
                if (addedToView) R.string.video_action_toview_added else R.string.video_action_toview,
            ),
            contentDescription = stringResource(R.string.video_action_toview_desc),
            selectedIcon = Icons.Filled.WatchLater,
            icon = Icons.Outlined.WatchLater,
            onClick = { if (!addedToView) onAddToView() },
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

/**
 * 动作栏里的一项:图标叠计数,整块可点。
 *
 * 触摸目标由 `heightIn(min = 48dp)` 加上外面给的 `weight(1f)` 撑起来(360dp 屏上一项
 * 90dp × 48dp),不靠 `IconButton` 的默认尺寸 —— 那样计数文字会掉到触摸区外面。
 * 未选中用 `outline` 而不是 `onSurfaceVariant`:这一排四个图标同时出现,再亮一档就会和
 * 上面的标题抢注意力,而它们本来是"想做才做"的动作。
 */
@Composable
private fun ActionItem(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    contentDescription: String,
    icon: ImageVector,
    selectedIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, onClick = onClick)
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(vertical = Spacing.Hair),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(ActionIconSize),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val ActionIconSize = 20.dp

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
        title = { Text(stringResource(R.string.video_action_coin)) },
        text = {
            Column {
                if (maxedOut) {
                    Text(
                        stringResource(R.string.coin_maxed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    listOf(1, 2).forEach { count ->
                        ChoiceRow(
                            selected = selectedCount == count,
                            onSelect = { selectedCount = count },
                            label = stringResource(R.string.coin_count, count),
                        )
                    }
                    ToggleRow(
                        checked = alsoLike,
                        onToggle = { alsoLike = it },
                        label = stringResource(R.string.coin_also_like),
                    )
                    // 投币是不可逆的:B 站没有撤销接口,币也不退。误触的代价由用户承担,
                    // 所以这句必须出现在确认之前 —— 这是全应用少数几个用 error 色的地方。
                    Text(
                        text = stringResource(R.string.coin_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.Tight),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !maxedOut, onClick = { onConfirm(selectedCount, alsoLike) }) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
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
        title = { Text(stringResource(R.string.fav_dialog_title)) },
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
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
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
        SectionHeader(stringResource(R.string.video_parts))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            itemsIndexed(labels) { index, pair ->
                FilterChip(
                    selected = isCurrent(index),
                    onClick = { onClick(index) },
                    label = {
                        Text(
                            stringResource(R.string.video_part_label, pair.first, pair.second),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
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
 *
 * **整块装在一层 [Surface] 容器里**,照 PiliPlus 的 `introduction/ugc/widgets/season.dart`
 * (它把合集面板包进 `Material(color: onInverseSurface, borderRadius: 6)`)。
 * 换掉的是"动作栏和队列之间只隔一点空白"——那样队列的标题行看起来像还属于上面那一坨,
 * 而它其实是另一件事。用容器而不是分割线:这里要表达的是"以下是一组被圈起来的条目",
 * 分割线只能说"上下不是一回事",说不出边界在哪里结束。
 *
 * 底色取 `surfaceContainer` 而不是 `surfaceVariant`(风格指南 §1.1)。不取更浅的
 * `surfaceContainerLow`:它在浅色主题下和页面的 `surface` 只差一点,真机上那圈边界几乎看不出来,
 * 等于白做了一个容器。圆角遵守 optical roundness:外 16dp − 内边距 8dp = 内层条目的 8dp
 * (`shapes.small`)。
 */
@Composable
private fun QueueSection(
    queue: QueueUiState,
    onPlayQueueItem: (String) -> Unit,
    onToggleShuffle: () -> Unit,
    onFindRelated: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!queue.loading && queue.items.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        QueueContent(queue, onPlayQueueItem, onToggleShuffle, onFindRelated)
    }
}

@Composable
private fun QueueContent(
    queue: QueueUiState,
    onPlayQueueItem: (String) -> Unit,
    onToggleShuffle: () -> Unit,
    onFindRelated: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        SectionHeader(title = queue.sourceLabel) {
            // 「找相关」长在这份列表上:这个位置在别的客户端是相关推荐,在这里是合集/UP 投稿,
            // 把"要不要另找几个"做成这份列表的一个小动作,力度正好 —— 可用,但不劝你用。
            //
            // 用闪光而不是星形:星形在这个 app 里已经是收藏(动作栏那排),同一界面里两个星星
            // 表示两件事,用户会以为点了是收藏。闪光是"AI 辅助动作"的通行符号。
            IconButton(onClick = onFindRelated) {
                Icon(
                    Icons.Filled.AutoAwesome,
                    contentDescription = stringResource(R.string.video_find_related),
                    modifier = Modifier.size(Dimens.IconInline),
                )
            }
            // 顺序/随机只有两态,是个开关而不是两个选项,所以用带图标的 text button
            // 而不是 segmented button —— 后者会让人以为还有第三格。
            TextButton(onClick = onToggleShuffle, contentPadding = PaddingValues(horizontal = Spacing.Tight)) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(Dimens.IconInline))
                Text(
                    text = stringResource(
                        if (queue.shuffled) {
                            R.string.queue_order_shuffle
                        } else {
                            R.string.queue_order_sequential
                        },
                    ),
                    modifier = Modifier.padding(start = Spacing.Hair),
                )
            }
        }

        if (queue.loading) {
            InlineProgress(
                stringResource(R.string.video_queue_loading),
                Modifier.padding(vertical = Spacing.Tight),
            )
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
                        subtitle = if (item.durationSeconds > 0) formatDurationSeconds(item.durationSeconds) else null,
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
 * 计数折算。分档除数取自资源:中文按万/亿分档,英文按 K/M,
 * 只翻译单位后缀会让英文差一个量级。
 */
@Composable
private fun formatCount(value: Long): String {
    val large = integerResource(R.integer.count_divisor_large)
    val small = integerResource(R.integer.count_divisor_small)
    return when {
        value >= large -> stringResource(R.string.count_large, value.toDouble() / large)
        value >= small -> stringResource(R.string.count_small, value.toDouble() / small)
        else -> value.toString()
    }
}


private fun formatDate(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

/**
 * 关注按钮。三种可关注状态各有各的字面:互关不能显示成"已关注",那会把"对方也关注了你"
 * 这条信息抹掉,而这正是 B 站用户会去看的东西。
 *
 * 已关注用 outlined、未关注用 filled:M3 里强调程度对应动作的主次,已经关注之后"取关"
 * 不该继续抢眼。自己的空间和已拉黑都不显示按钮 —— 前者没有这个动作,后者要先解除拉黑。
 */
@Composable
private fun FollowButton(state: FollowState, onClick: () -> Unit) {
    when (state) {
        FollowState.Self, FollowState.Blocked -> Unit
        FollowState.None -> Button(onClick = onClick) { Text(stringResource(R.string.follow_none)) }
        FollowState.Following -> OutlinedButton(onClick = onClick) { Text(stringResource(R.string.follow_following)) }
        FollowState.Mutual -> OutlinedButton(onClick = onClick) { Text(stringResource(R.string.follow_mutual)) }
    }
}
