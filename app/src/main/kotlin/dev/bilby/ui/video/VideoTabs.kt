package dev.bilby.ui.video

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.MonetizationOn
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material.icons.outlined.WatchLater
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.integerResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.formatDurationSeconds
import dev.bilby.agent.AgentTurnState
import dev.bilby.data.CommentSort
import dev.bilby.data.FavFolder
import dev.bilby.data.FollowState
import dev.bilby.data.MemberCard
import dev.bilby.data.VideoActionRepository.Companion.MAX_COIN_PER_VIDEO
import dev.bilby.data.VideoDetail
import dev.bilby.data.VideoRelation
import dev.bilby.data.VideoStat
import dev.bilby.data.VideoStaff
import dev.bilby.player.QueueItem
import dev.bilby.ui.comment.CommentSection
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.AvatarBadge
import dev.bilby.ui.components.BadgedAvatar
import dev.bilby.ui.components.ChoiceRow
import dev.bilby.ui.components.CompactVideoRow
import dev.bilby.ui.components.FollowButton
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.SectionHeader
import dev.bilby.ui.components.StatRow
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * 「找相关」的状态。
 *
 * [started] 是这一页自己的事(用户点过没有,决定 sheet 的把手在不在),助理那一轮长什么样
 * 全在 [turn] 里,和搜索页是同一份 [AgentTurnState]。这里原先把 steps/blocks/error 平铺开
 * 各存一份,那份 steps 还只是 `List<String>` —— 中间结果在播放页就是这样丢掉的。
 */
data class RelatedState(
    val started: Boolean = false,
    val turn: AgentTurnState = AgentTurnState(),
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
    /**
     * 完整队列还没建好。**此刻 [items] 里那一条不是队列,是占位** —— 起播时先装的临时队列
     * (见 AudioPlaybackService.openVideo),把它当队列摆出来会读成"这个 UP 只有一条投稿"。
     *
     * 这里不再看播放状态里的 `loading`:那一个说的是取流,而取流和建队列现在是并行的两件事。
     */
    val enriching: Boolean = false,
    /** 队列没建成,停在临时队列上。可重试,见 [QueueContent]。 */
    val incomplete: Boolean = false,
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
    /** 打开缓存选择面板。面板本身长在播放队列那一节上,见 [QueueContent]。 */
    onCache: () -> Unit,
    /** 投币那一行的顶边在窗口里的 y(px)。找相关 sheet 的高度锚在它上面,见 VideoScreen。 */
    onActionsTop: (Int) -> Unit,
    followState: FollowState,
    onToggleFollow: () -> Unit,
    upCard: MemberCard?,
    queue: QueueUiState,
    onPlayQueueItem: (bvid: String) -> Unit,
    onToggleShuffle: () -> Unit,
    onRetryQueue: () -> Unit,
    onUpClick: (Long) -> Unit,
    staffFollowed: Set<Long>?,
    onFollowStaff: (Long) -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    onAddToView: () -> Unit,
    /** 切到听视频那一屏。按钮在动作栏里,挨着稍后再看。 */
    onListen: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    coinAttempt: CoinAttempt,
    onCoinDialogClosed: () -> Unit,
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
    onSeekComment: (Long) -> Unit = {},
    /** 评论正文里的 @ 点开是那个人的空间,和上面 UP 那一行同一个去处。 */
    onCommentUserClick: (Long) -> Unit = {},
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
        // **用组件默认的指示条,不自己画。** 这里曾经读 `currentPage +
        // currentPageOffsetFraction` 做插值,让指示条全程贴着手指走;规范并不要求那样 ——
        // tabs 页对指示条只说"apply an underline and color change to the active tab",
        // 交互一节写的是 "The selected indicator becomes active and **shifts into position
        // once the touch has been engaged**",也就是选中之后移过去,而不是跟着拖动连续插值。
        //
        // 那份自定义代价不小:一个 `tabIndicatorLayout` 的手写测量、首帧 positions 为空的
        // 特判、以及一段"这个扩展的接收者到底是什么"的考据。删掉之后行为仍然合规,
        // 而滑动翻页本身照旧(内容区能滑是 tabs 页明写的用法)。
        SecondaryTabRow(selectedTabIndex = pagerState.currentPage) {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(title) },
                )
            }
        }
        // weight 而不是 fillMaxSize:在 Column 里 fillMaxSize 会让 pager 从 tab 栏下面再要
        // 一整屏的高度,底部那一截被推出可视区。
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            when (page) {
                0 -> IntroTab(
                    detail = detail,
                    currentCid = currentCid,
                    related = related,
                    onFindRelated = onFindRelated,
                    onCache = onCache,
                    onActionsTop = onActionsTop,
                    onUpClick = onUpClick,
                    staffFollowed = staffFollowed,
                    onFollowStaff = onFollowStaff,
                    followState = followState,
                    onToggleFollow = onToggleFollow,
                    upCard = upCard,
                    queue = queue,
                    onPlayQueueItem = onPlayQueueItem,
                    onToggleShuffle = onToggleShuffle,
                    onRetryQueue = onRetryQueue,
                    relation = relation,
                    favFolders = favFolders,
                    addedToView = addedToView,
                    onLike = onLike,
                    onAddToView = onAddToView,
                    onListen = onListen,
                    onCoin = onCoin,
                    coinAttempt = coinAttempt,
                    onCoinDialogClosed = onCoinDialogClosed,
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
                    onSeek = onSeekComment,
                    onUserClick = onCommentUserClick,
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
    onCache: () -> Unit,
    onActionsTop: (Int) -> Unit,
    followState: FollowState,
    onToggleFollow: () -> Unit,
    upCard: MemberCard?,
    queue: QueueUiState,
    onPlayQueueItem: (bvid: String) -> Unit,
    onToggleShuffle: () -> Unit,
    onRetryQueue: () -> Unit,
    onUpClick: (Long) -> Unit,
    staffFollowed: Set<Long>?,
    onFollowStaff: (Long) -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    onAddToView: () -> Unit,
    onListen: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    coinAttempt: CoinAttempt,
    onCoinDialogClosed: () -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onPlayPart: (Long) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onRelatedVideoClick: (String) -> Unit,
) {
    var infoExpanded by rememberSaveable { mutableStateOf(false) }
    // 这一栏内容区的底边(窗口坐标,px)。队列列表拿它算自己能长多高 —— 见 QueueContent。
    var contentBottomPx by remember { mutableIntStateOf(0) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                contentBottomPx = (it.positionInWindow().y + it.size.height).toInt()
            },
    ) {
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
                    mid = detail.up.mid,
                    faceUrl = detail.up.faceUrl,
                    name = detail.up.name,
                    upCard = upCard,
                    onUpClick = onUpClick,
                    staff = detail.staff,
                    staffFollowed = staffFollowed,
                    onFollowStaff = onFollowStaff,
                    followState = followState,
                    onToggleFollow = onToggleFollow,
                )

                ActionButtonsRow(
                    // 位置**只上报一次**(见 VideoScreen 那侧的取值):这一行跟着简介页滚动,
                    // 持续上报会让 sheet 的高度随手指变化,而 sheet 本来就不该动。
                    modifier = Modifier.onGloballyPositioned { onActionsTop(it.positionInWindow().y.toInt()) },
                    stat = detail.stat,
                    relation = relation,
                    favFolders = favFolders,
                    addedToView = addedToView,
                    onLike = onLike,
                    onAddToView = onAddToView,
                    onCoin = onCoin,
                    coinAttempt = coinAttempt,
                    onCoinDialogClosed = onCoinDialogClosed,
                    onOpenFavPicker = onOpenFavPicker,
                    onFavConfirm = onFavConfirm,
                    onListen = onListen,
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
                    contentBottomPx = contentBottomPx,
                    onPlayQueueItem = onPlayQueueItem,
                    onToggleShuffle = onToggleShuffle,
                    onFindRelated = onFindRelated,
                    onCache = onCache,
                    onRetryQueue = onRetryQueue,
                    modifier = Modifier.padding(top = Spacing.Hair),
                )
            }
        }
    }
}

/**
 * @param upCard UP 主等级(+粉丝数,这一轮不显示)。null 表示还没查到或查失败——徽章不画,
 *   不阻断这一行其余部分,和 UP 名/关注按钮相互独立(见 VideoViewModel.upCard 的说明)。
 */
@Composable
private fun UpRow(
    mid: Long,
    faceUrl: String,
    name: String,
    upCard: MemberCard?,
    onUpClick: (Long) -> Unit,
    staff: List<VideoStaff>,
    /** 已关注的联合投稿成员;null = 还没查到,此时一个加号都不画。 */
    staffFollowed: Set<Long>?,
    onFollowStaff: (Long) -> Unit,
    followState: FollowState,
    onToggleFollow: () -> Unit,
) {
    // mid 必须进 key:它是列表第一项的身份,也是点击跳转的目标。漏了它,队列走到同一个 UP
    // 的另一条视频上时头像和名字都没变,participants 不重建,第一项还指着上一条的 mid。
    val participants = remember(mid, faceUrl, name, staff) {
        buildList {
            add(VideoStaff(mid, "", name, faceUrl))
            addAll(staff.filter { it.mid != mid })
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (staff.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onUpClick(mid) }
                    .heightIn(min = Dimens.MinTouchTarget),
            ) {
                Avatar(url = faceUrl, size = Dimens.AvatarMedium)
                // 名字和粉丝数上下两行。粉丝数是**决定要不要关注时看的那个数**,而关注按钮
                // 就在这一行的另一端;原先它拉到了(`upCard.follower`)却没画出来,人得点进
                // 空间页才看得到。等级徽章跟着名字走,它说的是同一个人的另一件事。
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        upCard?.let {
                            LevelBadge(
                                level = it.level,
                                senior = it.isSeniorMember,
                                height = Dimens.LevelBadgeHeight,
                            )
                        }
                    }
                    // 拿不到就整行不画,不占位:这一行是独立请求、独立失败的(见 upCard 的说明)。
                    upCard?.let {
                        Text(
                            text = stringResource(R.string.space_followers, formatCount(it.follower)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            ) {
                items(participants, key = { "${it.mid}-${it.name}" }) { participant ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable { onUpClick(participant.mid) },
                    ) {
                        // 关注加号贴在头像右下角。**关注态未知时(staffFollowed 还是 null)不显示**,
                        // 否则每次打开都会先闪一排加号再消失。
                        //
                        // UP 主自己也走这一套,不再另开关注按钮:联合投稿这一排里他只是署名
                        // 第一位,单独给他一个按钮会让人以为关注的是"这条视频的作者"整体。
                        val followable = staffFollowed?.contains(participant.mid) == false
                        BadgedAvatar(
                            url = participant.faceUrl,
                            size = Dimens.AvatarMedium,
                            badge = if (!followable) null else AvatarBadge(
                                icon = Icons.Filled.Add,
                                contentDescription = stringResource(
                                    R.string.video_follow_staff,
                                    participant.name,
                                ),
                                onClick = { onFollowStaff(participant.mid) },
                            ),
                        )
                        // widthIn 而不是固定 width:固定 64dp 是照头像宽度定的,可它要装的是
                        // 名字 —— "飓多多StormCrew" 这种在 64dp 里只剩四个字加省略号。给一个
                        // 区间,短名字仍与头像对齐,长名字能多占一截。
                        Text(
                            participant.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(min = StaffLabelMinWidth, max = StaffLabelMaxWidth),
                        )
                        participant.title.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(min = StaffLabelMinWidth, max = StaffLabelMaxWidth),
                            )
                        }
                    }
                }
            }
        }
        // 联合投稿没有这个按钮:关注态全部由头像角上的加号表达,一个人一个。留着它就成了
        // 第二套状态——它只讲 UP 主一个人,而旁边那排讲所有人,两处对同一个人给出两种说法。
        //
        // 代价是联合投稿下没有取关入口,取关走那个人的空间页。这一排是署名,不是关系管理。
        if (staff.isEmpty()) {
            // 这一页的主角是这条视频,不是这个人 —— 关注用 tonal,别和左边那排动作抢。
            FollowButton(state = followState, onClick = onToggleFollow, prominent = false)
        }
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
    coinAttempt: CoinAttempt,
    onCoinDialogClosed: () -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onAddToView: () -> Unit,
    onListen: () -> Unit,
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
        // 听视频挨着稍后再看:这两件事是同一类决定——**这条视频我打算怎么消费**(现在只听、
        // 还是回头再看),而左边三个是对内容表态。它原先在画面底部那条控制条上,那里全是
        // "播放器现在怎么放"(倍速、清晰度、字幕、弹幕),听视频混在里面读不出它会换掉整页形态。
        //
        // 没有选中态:它不是开关,点下去就切到听视频那一屏了。
        ActionItem(
            modifier = Modifier.weight(1f),
            selected = false,
            enabled = true,
            label = stringResource(R.string.video_action_listen),
            contentDescription = stringResource(R.string.video_action_listen),
            selectedIcon = Icons.Filled.Headphones,
            icon = Icons.Filled.Headphones,
            onClick = onListen,
        )
        // 评论数只在 tab 标题上出现一次:同一个数字在同屏显示两遍没有信息量。
    }

    if (showCoinDialog) {
        CoinDialog(
            alreadyCoined = relation?.coined ?: 0,
            attempt = coinAttempt,
            onDismiss = {
                showCoinDialog = false
                onCoinDialogClosed()
            },
            // **确认之后面板不关**,等服务端认了再关(见下面的 LaunchedEffect)。投币不可逆,
            // 失败的那次必须让人看见,而这个 app 没有全局提示位可以在面板消失之后说话。
            onConfirm = onCoin,
        )
    }

    LaunchedEffect(coinAttempt) {
        if (coinAttempt is CoinAttempt.Succeeded) {
            showCoinDialog = false
            onCoinDialogClosed()
        }
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
    attempt: CoinAttempt,
    onDismiss: () -> Unit,
    onConfirm: (count: Int, alsoLike: Boolean) -> Unit,
) {
    var selectedCount by rememberSaveable { mutableIntStateOf(1) }
    var alsoLike by rememberSaveable { mutableStateOf(false) }
    val maxedOut = alreadyCoined >= MAX_COIN_PER_VIDEO
    // 上限是"这条视频一共两枚",不是"这一次两枚"。已投 1 枚时还列出 2,选中它必然换来
    // 一次服务端拒绝,而拒绝在界面上什么都不显示,看起来就是按钮没反应。
    val selectableCounts = (1..MAX_COIN_PER_VIDEO - alreadyCoined).toList()
    // rememberSaveable 存的可能是上一条视频选的 2 枚,而这一条已经投过 1 枚了。
    val effectiveCount = selectedCount.coerceIn(1, (MAX_COIN_PER_VIDEO - alreadyCoined).coerceAtLeast(1))

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
                    selectableCounts.forEach { count ->
                        ChoiceRow(
                            selected = effectiveCount == count,
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
                    //
                    // 失败之后这句换成失败原因,不两句并排:两句都是 error 色,读起来分不清
                    // 哪句在说刚才那一次。警告在重投之前还会再出现一遍(错误清掉之后)。
                    Text(
                        text = (attempt as? CoinAttempt.Failed)?.message
                            ?.let { stringResource(R.string.coin_failed, it) }
                            ?: stringResource(R.string.coin_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Spacing.Tight),
                    )
                }
            }
        },
        confirmButton = {
            // 请求在飞的时候按不动,否则连点两下就是两次投币,而它不可逆。
            TextButton(
                enabled = !maxedOut && attempt !is CoinAttempt.Running,
                onClick = { onConfirm(effectiveCount, alsoLike) },
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
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
            // 收藏夹可以有几十个,对话框里的固定高度列表要能滚,Column 撑高之后按钮会被挤出屏幕。
            LazyColumn(modifier = Modifier.heightIn(max = FavPickerMaxHeight)) {
                items(favFolders, key = { it.id }) { folder ->
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
 *
 * **条数多时给一个全部分集的入口,开的是 modal bottom sheet,不是对话框。**
 * dialogs 页写着 "Most dialog content should avoid scrolling",而几百 P 的课程视频必然要滚;
 * bottom sheets 页则明说内容超过半屏时可以拉到全屏并在内部滚动。就地展开也不行 ——
 * 那会把简介区撑得很长,和"播放页正好占一屏"直接打架。
 *
 * 收起态保持横滚而不是截断:横滚里还能看出当前是第几 P、相邻切换最快,截断则可能把当前
 * 这一 P 藏在看不见的地方。
 */
@Composable
private fun PartRow(
    labels: List<Pair<Int, String>>,
    isCurrent: (Int) -> Boolean,
    onClick: (Int) -> Unit,
) {
    var sheetOpen by rememberSaveable(labels.size) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
        SectionHeader(stringResource(R.string.video_parts)) {
            if (labels.size > PartRowExpandThreshold) {
                TextButton(onClick = { sheetOpen = true }) {
                    Text(stringResource(R.string.video_parts_expand, labels.size))
                }
            }
        }
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

    if (sheetOpen) {
        PartSheet(
            labels = labels,
            isCurrent = isCurrent,
            onPick = { index ->
                onClick(index)
                sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

/**
 * 全部分集。**一行一条而不是铺一片 chip**:几百 P 的标题是"第 12 讲 · 线性方程组"这种,
 * 挤成 chip 只剩截断后的两三个字,而用户要找的正是标题。
 *
 * 用 `ModalBottomSheet` 而不是页面自带的那个 `BottomSheetScaffold`(它归「找相关」):
 * 挑分集时人不在看画面,遮罩压暗反而帮着聚焦;而「找相关」是边看边问,所以那边才刻意
 * 避开遮罩。两者的取舍不同,不该共用一个 sheet 槽。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartSheet(
    labels: List<Pair<Int, String>>,
    isCurrent: (Int) -> Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 开的时候把当前这一 P 滚到可见:一百条里默认停在第一条,等于每次都要自己找。
    LaunchedEffect(Unit) {
        val current = labels.indices.firstOrNull { isCurrent(it) } ?: return@LaunchedEffect
        listState.scrollToItem(current)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(state = listState) {
            itemsIndexed(labels) { index, pair ->
                val selected = isCurrent(index)
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.video_part_label, pair.first, pair.second),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    // 当前这一条只换文字颜色,不加选中背景:sheet 里一整块染色比列表本身还重。
                    colors = ListItemDefaults.colors(
                        headlineColor = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            role = Role.Button,
                            onClick = { onPick(index) },
                        ),
                )
            }
        }
    }
}

/** 十条以内横滚划得完,再多就该给展开。 */
private const val PartRowExpandThreshold = 10

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
    /** 简介栏内容底边在窗口坐标里的 y(px)。队列列表靠它算自己该有多高,见 [QueueContent]。 */
    contentBottomPx: Int,
    onPlayQueueItem: (String) -> Unit,
    onToggleShuffle: () -> Unit,
    onFindRelated: () -> Unit,
    onCache: () -> Unit,
    onRetryQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!queue.enriching && !queue.incomplete && queue.items.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        QueueContent(
            queue = queue,
            contentBottomPx = contentBottomPx,
            onPlayQueueItem = onPlayQueueItem,
            onToggleShuffle = onToggleShuffle,
            onFindRelated = onFindRelated,
            onCache = onCache,
            onRetryQueue = onRetryQueue,
        )
    }
}

@Composable
private fun QueueContent(
    queue: QueueUiState,
    contentBottomPx: Int,
    onPlayQueueItem: (String) -> Unit,
    onToggleShuffle: () -> Unit,
    onFindRelated: () -> Unit,
    onCache: () -> Unit,
    onRetryQueue: () -> Unit,
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
            // **缓存的入口长在这里**,因为要选的东西就是这份列表:合集分集 / 这位 UP 的其他
            // 投稿。摆进上面那排动作栏不行 —— 那四格的宽度是照 360dp 屏量出来的(风格指南
            // §2.3),加第五格会把每格挤到 72dp,"赞 12.3万"那种标签就放不下了。
            IconButton(onClick = onCache) {
                Icon(
                    Icons.Outlined.DownloadForOffline,
                    contentDescription = stringResource(R.string.offline_cache_action),
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

        if (queue.enriching) {
            InlineProgress(
                stringResource(R.string.video_queue_loading),
                Modifier.padding(vertical = Spacing.Tight),
            )
        } else if (queue.incomplete) {
            // 只有一条的队列自己解释不了自己:看不出是"这个 UP 只发过这一条"还是"来源没拉到"。
            // 说出后者并给一次重试,重试走的是再发一遍 OPEN_VIDEO(见 VideoScreen)。
            // 文案不染 error 色,重试用 text button —— 一次拉取失败不该被渲染成需要下决心的事
            // (风格指南 §2.4)。
            Row(
                modifier = Modifier.padding(vertical = Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Text(
                    text = stringResource(R.string.video_queue_incomplete),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = onRetryQueue,
                    contentPadding = PaddingValues(horizontal = Spacing.Tight),
                ) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        } else {
            val listState = rememberLazyListState()

            // 高度必须有界(高度无界的 LazyColumn 会抛),但**界取自剩下多少空间,不是窗口的
            // 三分之一**。按窗口比例算出来的那个数和这一页实际剩多少无关:标题一行还是两行、
            // 有没有分 P 都会改变上面占掉的高度,于是队列底下常年吊着一截空白 —— 竖屏画面
            // 铺到状态栏底下之后又多出一条,那截空白正是这么来的。
            //
            // 量的是这份列表自己的顶边到窗口内容底边的距离,**只量第一次**:这一块跟着简介页
            // 滚动,持续跟随的话手指一动列表就跟着长高。够不到下限时(上面内容太长)退回下限,
            // 由简介页整体滚动兜住。
            var listTopPx by remember(queue.items.size) { mutableIntStateOf(0) }
            val density = LocalDensity.current
            val queueHeight = if (listTopPx > 0 && contentBottomPx > listTopPx) {
                // 减掉列表**底下还剩的两层内边距**:卡片自己的(Tight)和简介那一列的(Cozy)。
                // 只减一层的话整页会比一屏高出剩下那一层,表现是默认状态就能上下滑动一点点。
                (with(density) { (contentBottomPx - listTopPx).toDp() } - BelowQueueInsets)
                    .coerceAtLeast(Dimens.EmbeddedQueueMinHeight)
            } else {
                Dimens.EmbeddedQueueMinHeight
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .height(queueHeight)
                    .onGloballyPositioned {
                        if (listTopPx == 0) listTopPx = it.positionInWindow().y.toInt()
                    },
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
 * 队列列表底下压着的内边距之和:队列卡片自己的 [Spacing.Tight] 加简介那一列的
 * [Spacing.Cozy]。算列表高度时要一并减掉,见 [QueueContent]。
 */
private val BelowQueueInsets = Spacing.Tight + Spacing.Cozy

/** 名字栏的宽度区间。下限对齐头像，上限防止一个长名字把整排撑开。 */
private val StaffLabelMinWidth = 56.dp
private val StaffLabelMaxWidth = 104.dp

/** 收藏夹对话框里列表的高度上限。再高会把确认/取消按钮顶出屏幕。 */
private val FavPickerMaxHeight = 360.dp
