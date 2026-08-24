package dev.bilby.ui.video

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.DownloadForOffline
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
import androidx.compose.material3.HorizontalDivider
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
import android.os.SystemClock
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.player.EpisodePart
import dev.bilby.ui.player.EpisodeRow
import dev.bilby.ui.player.EpisodeTarget
import dev.bilby.ui.player.currentIndex
import dev.bilby.formatDurationSeconds
import dev.bilby.agent.AgentTurnState
import dev.bilby.data.CommentSort
import dev.bilby.data.FavFolder
import dev.bilby.data.FollowState
import dev.bilby.data.MemberCard
import dev.bilby.data.QueueSource
import dev.bilby.data.VideoDetail
import dev.bilby.data.VideoRelation
import dev.bilby.data.VideoStat
import dev.bilby.data.VideoStaff
import dev.bilby.data.VideoTag
import dev.bilby.player.QueueItem
import dev.bilby.ui.comment.CommentSection
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.AvatarBadge
import dev.bilby.ui.components.BilbyIcons
import dev.bilby.ui.components.formatCount
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
    /**
     * 这份队列摊出来的切集清单,**三种形态(详情页、听视频、全屏)共用同一份**,构造点只有
     * `VideoScreen` 一处(见 [dev.bilby.ui.player.buildEpisodeRows])。差别只在怎么画:
     * 这一页把当前那条的分 P 摊成一排 chip,另外两种摊成二级列表。
     */
    val rows: List<EpisodeRow> = emptyList(),
    val sourceLabel: String = "",
    /** 来源的身份。非空时标题行可以点进那个合集的目录;为空的来源没有目录页可去。 */
    val source: QueueSource? = null,
    val shuffled: Boolean = false,
    /**
     * 完整队列还没建好。**此刻 [rows] 里那一条不是队列,是占位** —— 起播时先装的临时队列
     * (见 AudioPlaybackService.openVideo),把它当队列摆出来会读成"这个 UP 只有一条投稿"。
     *
     * 这里不再看播放状态里的 `loading`:那一个说的是取流,而取流和建队列现在是并行的两件事。
     */
    val enriching: Boolean = false,
    /** 队列没建成,停在临时队列上。可重试,见 [QueueContent]。 */
    val incomplete: Boolean = false,
)

/** 简介页的下标。它是不是当前页决定画面能不能被收起,见 VideoScreen 的 canCollapsePlayer。 */
const val VideoTabIntro = 0

const val VideoTabCount = 2

/**
 * 播放页下半部分:简介 / 评论左右滑动切换(DESIGN 2.3)。
 *
 * 用 **secondary** tabs:M3 把 primary tabs 定义为"贴在 app bar 下面、代表页面主内容分区"的,
 * secondary tabs 才是"在内容区域内部再分一层"。这两个标签上面顶着播放器而不是顶栏,
 * 属于后者。空间页那三个标签紧贴顶栏,那边才用 primary。
 */
@Composable
fun VideoTabs(
    /**
     * 简介/评论分页。**由 VideoScreen 持有**,因为"画面能不能被收起"要看现在停在哪一页
     * (见它那边的 canCollapsePlayer)。页数与下标因此也一起搬到 [VideoTabCount] /
     * [VideoTabIntro]:创建 pager 的那一层要先知道有几页。
     */
    pagerState: PagerState,
    detail: VideoDetail,
    onSelectEpisode: (EpisodeTarget) -> Unit,
    /** 这条视频的标签,展开简介才显示。空列表不画,见 VideoViewModel.videoTags。 */
    videoTags: List<VideoTag>,
    /** 第一次展开简介时拉标签,幂等,见 VideoViewModel.loadVideoTags。 */
    onLoadTags: () -> Unit,
    /** 点一枚标签 = 拿它的原文开一页普通搜索结果(Destinations.kt 的 SearchResult)。 */
    onTagClick: (String) -> Unit,
    related: RelatedState,
    commentState: CommentUiState,
    onFindRelated: () -> Unit,
    /** 打开发弹幕的输入层。面板本身挂在页面那一层(见 VideoScreen),这里只是入口。 */
    onSendDanmaku: () -> Unit,
    /** 弹幕显示开关。全屏时这一行不组合,那时的开关在播放控制条上。 */
    danmakuEnabled: Boolean,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    /** 打开缓存选择面板。面板本身长在播放队列那一节上,见 [QueueContent]。 */
    onCache: () -> Unit,
    /** 投币那一行的顶边在窗口里的 y(px)。找相关 sheet 的高度锚在它上面,见 VideoScreen。 */
    onActionsTop: (Int) -> Unit,
    followState: FollowState,
    onToggleFollow: () -> Unit,
    upCard: MemberCard?,
    queue: QueueUiState,
    onOpenQueueSource: (QueueSource) -> Unit,
    onToggleShuffle: () -> Unit,
    onRetryQueue: () -> Unit,
    onUpClick: (Long) -> Unit,
    staffFollowed: Set<Long>?,
    onFollowStaff: (Long) -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    /** 一键三连,长按点赞触发。见 [ActionButtonsRow]。 */
    onTriple: () -> Unit,
    onAddToView: () -> Unit,
    /** 切到听视频那一屏。按钮在动作栏里,挨着稍后再看。 */
    onListen: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    coinAttempt: CoinAttempt,
    onCoinDialogClosed: () -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onPlayEpisode: (bvid: String) -> Unit,
    onRelatedVideoClick: (bvid: String) -> Unit,
    /** 评论正文里引的那条链接。站内解析归导航层,见 MainActivity 的 openLink。 */
    onOpenLink: (String) -> Unit,
    onCommentSort: (CommentSort) -> Unit,
    onCommentRefresh: () -> Unit,
    /** 播放器是不是完全展开着。评论区的下拉刷新只在这时接管手势。 */
    playerExpanded: Boolean,
    onCommentLoadMore: () -> Unit,
    onExpandReplies: (Long) -> Unit,
    onSendComment: (String, Long?) -> Unit,
    onLikeComment: (Long) -> Unit,
    onDeleteComment: (Long) -> Unit,
    /** 点了评论里的时间戳。null 表示此刻跳不了,时间戳会画成普通文字。 */
    onSeekComment: ((Long) -> Unit)? = null,
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
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        /*
         * **标签吃掉右边两个控件之外的全部宽度。**
         *
         * 这里曾经照 PiliPlus 把标签限死在每个 96dp(`pages/video/view.dart:1376-1406`),
         * 结果是两头不讨好:"评论 1234" 在 96dp 里放不下,标签换到第二行、整条行跟着变高;
         * 而右边又空出一大块——限宽块靠左、按钮靠右,中间那段谁都不占。PiliPlus 自己不换行
         * (它给 Tab 传的是 `softWrap: false`,宁可溢出),但那是拿截断换的,同一个问题没有解掉。
         *
         * 给标签 `weight(1f)` 之后两件事一起没了:宽度按屏幕分,4 位数装得下;中间也不再有
         * 无主的空白。标签本身仍然单行不换行 —— 计数再长(“评论 1.2万”)也只该截断,不该把
         * 这一行撑成两倍高。
         *
         * **弹幕的显示开关也在这里,挨着"发弹幕"**,照 PiliPlus 的同一行。两个控件说的是同一
         * 件事——这条视频的弹幕看不看、发不发,摆在一起才读得出它们是一对;开关原先在播放控制条
         * 上,和倍速、清晰度、字幕并排,那条条控制的是"播放器怎么放",弹幕混在里面像是第五个
         * 播放参数。
         *
         * 全屏没有这一行,开关回到控制条上(见 BilbyPlayer.SecondaryControls),那里是它唯一
         * 够得着的位置;发弹幕全屏不给。
         */
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                // **用组件默认的指示条,不自己画。** 这里曾经读 `currentPage +
                // currentPageOffsetFraction` 做插值,让指示条全程贴着手指走;规范并不要求那样
                // —— tabs 页对指示条只说"apply an underline and color change to the active
                // tab",交互一节写的是 "The selected indicator becomes active and **shifts into
                // position once the touch has been engaged**",也就是选中之后移过去,而不是
                // 跟着拖动连续插值。
                //
                // 那份自定义代价不小:一个 `tabIndicatorLayout` 的手写测量、首帧 positions 为空
                // 的特判、以及一段"这个扩展的接收者到底是什么"的考据。删掉之后行为仍然合规,
                // 而滑动翻页本身照旧(内容区能滑是 tabs 页明写的用法)。
                //
                // 自带的分割线要关掉:它只画到自己那点宽度为止,右半行会缺一截。通栏那条画在
                // 整行下面。
                SecondaryTabRow(selectedTabIndex = pagerState.currentPage, divider = {}) {
                    titles.forEachIndexed { index, title ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = {
                                Text(
                                    text = title,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                        )
                    }
                }
            }
            // 发弹幕用文字、显示开关用图标:两者一个是动作、一个是状态,形状不同才不会被
            // 读成两个并列的按钮。文字在前,和 PiliPlus 的顺序一致。
            TextButton(
                onClick = onSendDanmaku,
                contentPadding = PaddingValues(horizontal = Spacing.Cozy),
            ) {
                Text(
                    text = stringResource(R.string.danmaku_send),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            DanmakuVisibilityButton(
                enabled = danmakuEnabled,
                onEnabledChange = onDanmakuEnabledChange,
            )
        }
        HorizontalDivider()
        // weight 而不是 fillMaxSize:在 Column 里 fillMaxSize 会让 pager 从 tab 栏下面再要
        // 一整屏的高度,底部那一截被推出可视区。
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
            when (page) {
                VideoTabIntro -> IntroTab(
                    detail = detail,
                    onSelectEpisode = onSelectEpisode,
                    videoTags = videoTags,
                    onLoadTags = onLoadTags,
                    onTagClick = onTagClick,
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
                    onOpenQueueSource = onOpenQueueSource,
                    onToggleShuffle = onToggleShuffle,
                    onRetryQueue = onRetryQueue,
                    relation = relation,
                    favFolders = favFolders,
                    addedToView = addedToView,
                    onLike = onLike,
                    onTriple = onTriple,
                    onAddToView = onAddToView,
                    onListen = onListen,
                    onCoin = onCoin,
                    coinAttempt = coinAttempt,
                    onCoinDialogClosed = onCoinDialogClosed,
                    onOpenFavPicker = onOpenFavPicker,
                    onFavConfirm = onFavConfirm,
                    onPlayEpisode = onPlayEpisode,
                    onRelatedVideoClick = onRelatedVideoClick,
                )

                else -> CommentSection(
                    onOpenLink = onOpenLink,
                    state = commentState,
                    onSort = onCommentSort,
                    onRefresh = onCommentRefresh,
                    // 播放器收起着的时候下滑的意思是"把它拉回来",不是刷新(见 CommentSection)。
                    refreshEnabled = playerExpanded,
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
 * 弹幕显示开关(标签行版)。
 *
 * **不复用 [dev.bilby.ui.player.DanmakuButton]**:那一个的未选中色是 `FixedColors.OnMedia`
 * (压在画面上的白),摆到这条浅色的标签行上就是白底白字。图标([BilbyIcons.Danmaku])两处
 * 共用,于是全屏与内嵌看起来仍是同一个开关。
 *
 * 内容描述说的是**按下去会怎样**,不是当前状态:状态由字形和颜色一起表达,而读屏用户需要的
 * 是这一下的后果。
 */
@Composable
private fun DanmakuVisibilityButton(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    IconButton(onClick = { onEnabledChange(!enabled) }) {
        Icon(
            imageVector = if (enabled) BilbyIcons.Danmaku else BilbyIcons.DanmakuOff,
            contentDescription = stringResource(
                if (enabled) R.string.danmaku_hide else R.string.danmaku_show,
            ),
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
            modifier = Modifier.size(Dimens.IconInline),
        )
    }
}

/**
 * 简介页:整体可滚动,内容比一屏长。找相关放在最后——它是关联入口,不是页面主角。
 */
@Composable
private fun IntroTab(
    detail: VideoDetail,
    onSelectEpisode: (EpisodeTarget) -> Unit,
    videoTags: List<VideoTag>,
    onLoadTags: () -> Unit,
    onTagClick: (String) -> Unit,
    related: RelatedState,
    onFindRelated: () -> Unit,
    onCache: () -> Unit,
    onActionsTop: (Int) -> Unit,
    followState: FollowState,
    onToggleFollow: () -> Unit,
    upCard: MemberCard?,
    queue: QueueUiState,
    onOpenQueueSource: (QueueSource) -> Unit,
    onToggleShuffle: () -> Unit,
    onRetryQueue: () -> Unit,
    onUpClick: (Long) -> Unit,
    staffFollowed: Set<Long>?,
    onFollowStaff: (Long) -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    onTriple: () -> Unit,
    onAddToView: () -> Unit,
    onListen: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    coinAttempt: CoinAttempt,
    onCoinDialogClosed: () -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onPlayEpisode: (String) -> Unit,
    onRelatedVideoClick: (String) -> Unit,
) {
    var infoExpanded by rememberSaveable { mutableStateOf(false) }

    /*
     * **这一栏整体不滚动。** 上面那几块各占自己那点高度,剩下多少全归播放队列(weight),
     * 队列在自己那块里滚。
     *
     * 原先是一个 `verticalScroll` 的 Column,队列则按"这份列表的顶边到窗口内容底边还剩多少"
     * 反算自己该多高 —— 那串窗口坐标是这一页最难缠的一段:简介一展开、播放器一收起、
     * 页面一滚动,顶边就变,队列跟着重新量高重组。现在高度由布局给,那段算法整个不存在了,
     * 播放器的收起也不再牵动这一栏。
     *
     * 可用高度变了(播放器收起、简介展开)就重排一次,队列跟着变高变矮。**这一栏不吃用户的
     * 滑动去收缩自己** —— 收播放器是队列那个列表的滚动带起来的,而不是这一栏在整体挪。
     */
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        TitleBlock(
            detail = detail,
            onOpenIntro = {
                infoExpanded = true
                // 标签到这一刻才拉,理由见 VideoViewModel.loadVideoTags。
                onLoadTags()
            },
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
            maxCoins = detail.maxCoins,
            relation = relation,
            favFolders = favFolders,
            addedToView = addedToView,
            onLike = onLike,
            onTriple = onTriple,
            onAddToView = onAddToView,
            onCoin = onCoin,
            coinAttempt = coinAttempt,
            onCoinDialogClosed = onCoinDialogClosed,
            onOpenFavPicker = onOpenFavPicker,
            onFavConfirm = onFavConfirm,
            onListen = onListen,
        )

        // 分 P 来自切集清单里当前那一条,不再另从详情取一遍。清单已经把"这一刻能不能切 P"
        // 判过了(见 buildEpisodeRows):对不上身份时它是空的,这一排因此整个不出现。
        val currentParts = queue.rows.firstOrNull { it.isCurrent }?.parts.orEmpty()
        if (currentParts.isNotEmpty()) {
            PartRow(parts = currentParts, onSelect = onSelectEpisode)
        }

        // 合集的分集 chip 行不再单独显示:内容已经在下面的播放队列列表里,
        // 重复一遍没有信息量(合集场景下队列来源就是这个合集,
        // 见 QueueSourceRepository.fromSeason)。

        /*
         * **剩下这块空间永远归播放队列。**
         *
         * 这一栏的性质是"各组件按需吃满整屏,整栏不产生滚动条",队列在自己那块里滚。简介
         * 原先内联展开,破坏的正是这条:标题不限行、简介整段、标签铺开,这一块自己就可能超过
         * 一屏,而它上面没有任何可滚的容器。溢出之后 Column 给后面兄弟节点的 maxHeight 是 0,
         * `UpRow` 里那枚等级徽章的 `height(11.dp)` 被夹成 0,`aspectRatio` 在高度约束不可满足时
         * 退到按宽度算,于是横向铺满整行——"徽章忽然变得很大"和"简介滑不动"是同一次溢出。
         *
         * 修过一版是"展开时把这块的占用者从队列换成简介"。它不再溢出,但**控件和效果不挨着**:
         * 三角在标题那一行,真正变的是隔着 UP 行和动作栏的这一块,点下去像别的地方变了。
         *
         * 现在简介走面板([IntroSheet]):从下方推上来盖住这一页,关掉回到原样。找相关用的是
         * 同一种形状(见 VideoScreen),理由也一样 —— 它是对当前这条视频的一次追问,
         * 不该把这一栏的结构顶掉。
         */
        QueueSection(
            queue = queue,
            onSelectEpisode = onSelectEpisode,
            onOpenQueueSource = onOpenQueueSource,
            onToggleShuffle = onToggleShuffle,
            onFindRelated = onFindRelated,
            onCache = onCache,
            onRetryQueue = onRetryQueue,
            modifier = Modifier.padding(top = Spacing.Hair).weight(1f),
        )
    }

    if (infoExpanded) {
        IntroSheet(
            detail = detail,
            tags = videoTags,
            // **先关面板,再跳走。** `ModalBottomSheet` 自己注册了一个 BackHandler(预测式返回
            // 要用),它在组合树里比导航那一层更靠后,于是先接住返回。留着面板跳到搜索页之后,
            // 这一页仍在栈里、面板仍在组合中,搜索页的第一次返回被它吃掉 —— 表现是"返回键
            // 没反应",而实际上是在关一个看不见的面板。`LiveNowSheet` 那处是同一条规矩。
            onTagClick = { tag ->
                infoExpanded = false
                onTagClick(tag)
            },
            onDismiss = { infoExpanded = false },
        )
    }
}

/**
 * 简介面板:完整标题、bvid、简介正文、标签。
 *
 * **走面板而不是就地展开**,理由见 [IntroTab] 里那段说明 —— 简介长度没有上界,而那一栏
 * 的性质是"整栏不产生滚动条"。面板自成一层,想多长有多长,在自己内部滚。
 *
 * **完整标题在这里再给一次。** 上面那一行恒定两行截断,长标题正是最需要看全的那一种;
 * 而这一层盖住了页面,不重复给的话人得先关掉面板才能读标题。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun IntroSheet(
    detail: VideoDetail,
    tags: List<VideoTag>,
    onTagClick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable)
                .padding(bottom = Spacing.Loose)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Text(text = detail.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = detail.bvid,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail.description.isNotBlank()) {
                Text(
                    text = detail.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    tags.forEach { tag -> TagToken(tag, onClick = { onTagClick(tag.name) }) }
                }
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
                Avatar(url = faceUrl, size = Dimens.AvatarRow)
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
                            size = Dimens.AvatarRow,
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * 标题 + 计数行 + 展开指示。**两态都只有这些**,展开后多出来的 bvid、简介、标签归
 * [IntroDetail],它接管的是这一栏底下那块空间。
 *
 * 标题与计数行合成一个可展开的块,是照 PiliPlus 的 `introduction/ugc/view.dart`(它的
 * `ExpandablePanel` 收起时只给标题两行,展开后才露出 bvid、简介和标签)。以前标题不限行数、
 * 简介另有一个展开开关,长标题会把 UP 主那一行和动作栏一起顶下去,而简介的展开箭头又落在
 * 半屏之外 —— 两个开关管的其实是同一件事:这条视频要看多细。
 *
 * 展开指示不放标题末尾:标题会截断,截断处的箭头看起来像正文的一部分。
 *
 * **两态都留在计数行右端,不再跟着内容末端走。** 它以前展开后会移到简介末尾,理由是"收起"
 * 这个动作不该离它作用的那段文字隔着大半屏;而正文现在住在一块自己滚动的区域里,箭头跟过去
 * 就等于藏在滚动条底下——不滚到底根本看不见它,而收起是此刻唯一的出路。区域有界之后原来那条
 * 理由不再成立,固定位置反倒让两态之间只有箭头方向在变。
 *
 * 展开态的标题限行:标题最长八十字,`titleMedium` 下约四行,不封顶的话它自己也能吃掉半屏,
 * 而这里的整块高度是有界的。
 */
@Composable
private fun TitleBlock(
    detail: VideoDetail,
    onOpenIntro: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenIntro),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        // **标题恒定两行。** 点它开的是 [IntroSheet],完整标题在那里 —— 这一行不再跟着变,
        // 上下两块的位置因此是稳的。原先点一下标题会从 2 行长到 4 行,把底下整栏推一截,
        // 而人此刻眼睛盯着的是标题本身。
        Text(
            text = detail.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatRow(
                modifier = Modifier.weight(1f),
                playText = formatCount(detail.stat.view),
                danmakuText = formatCount(detail.stat.danmaku),
                dateText = formatDate(detail.publishedAtEpochSeconds),
            )
            // **不用上下箭头。** 那一对说的是"就地展开/收起",而这里点下去是从下方推上来
            // 一层面板 —— 内容不在这一行底下长出来。指向右的那一枚是 Material 里"点进去
            // 还有东西"的惯用记号,和这件事对得上。
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.video_intro_open),
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(Dimens.IconInline),
            )
        }
    }
}

/**
 * 一枚标签,点开是这个词的普通搜索结果页。带底色的一小块,判据同动态卡片的「置顶」
 * (DynamicCardView 的 DynamicTagChip):排成灰字会和上面的简介连成一片。底色取
 * surfaceContainerHigh 而不是 secondaryContainer —— 那边是单枚的排序标记,这边是成排的
 * 元数据,染 secondary 会比简介正文还重。可点但不染 primary:一排七八枚全上 primary,
 * 这一块会比标题还响,底色块本身已经说了"这是控件"。
 *
 * 搜索用的是 tag_name 原文,不是展示文本:`#` 和「BGM:」都是这里加的装饰,带进搜索词
 * 只会让结果变差。PiliPlus 对 topic/bgm 各有专页可去,本项目没有,三种类型都落到搜索。
 */
@Composable
private fun TagToken(tag: VideoTag, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(
            text = tag.displayText(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hair / 2),
        )
    }
}

/**
 * topic 加 #:话题和普通标签在服务端是两个体系,官方端也用 # 区分。bgm 的 tag_name 形如
 * 「发现《歌名》」—— 那是官方 App 里 BGM 入口的按钮文案,不是标签本身,照 PiliPlus 的
 * `_buildTags` 改写(它写作 ♫ BGM:,这里不要那个音符,字体里未必有)。
 */
private fun VideoTag.displayText(): String = when (type) {
    "topic" -> "#$name"
    "bgm" -> name.replaceFirst("发现", "BGM：")
    else -> name
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
    /** 这条视频一共收几枚币,见 [dev.bilby.data.VideoDetail.maxCoins]。 */
    maxCoins: Int,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    onTriple: () -> Unit,
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

    /** 手指此刻按在点赞那一格上没有。见 [ActionItem] 的 `onHoldingChange`。 */
    var holdingLike by remember { mutableStateOf(false) }

    /**
     * 按住点赞时那圈进度环画到哪儿了。**长满的那一刻正是三连成立的那一刻。**
     *
     * 这一圈不是装饰,是这个动作唯一的可发现性:长按点赞三连是 B 站的老手势,但按下去到它
     * 成立之间什么都不发生,没有环的话第一次按的人会以为自己点歪了,松手就只剩一次普通点赞。
     *
     * **进度提到这一层,是因为环要同时出现在赞、币、收藏三格上。** 三连动的就是这三样,
     * 环只长在点赞那一格的话,读出来是"按住点赞会发生点什么",而不是"这三样会一起发生"。
     * 手势仍然只在点赞那一格,另外两格光画环、不接管长按。
     */
    val holdProgress by animateFloatAsState(
        targetValue = if (holdingLike) 1f else 0f,
        animationSpec = if (holdingLike) {
            // 前 [TapMaxMillis] 毫秒不画,那一段还属于"点一下"。环一出现就意味着这一按已经
            // 不是点赞了(松手也不点赞,见 [ActionItem] 里的 onClick),两者因此必须同一个数。
            tween(
                durationMillis = TripleHoldMillis - TapMaxMillis,
                delayMillis = TapMaxMillis,
                easing = LinearEasing,
            )
        } else {
            snap()
        },
        label = "tripleHoldProgress",
    )

    Row(modifier = modifier.fillMaxWidth()) {
        // 长按三连挂在点赞上,这是 B 站的老手势;不另开一格,那一排已经有五个动作了。
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
            onLongClick = onTriple,
            longClickLabel = stringResource(R.string.video_triple),
            onHoldingChange = { holdingLike = it },
            holdProgress = holdProgress,
        )
        // 投币点下去是弹框(问投几枚)而不是直接投,但"已投币"是一个实实在在的可显示状态,
        // 所以它和点赞用同一种表达,区别只在点击后发生什么。
        ActionItem(
            modifier = Modifier.weight(1f),
            selected = (relation?.coined ?: 0) > 0,
            enabled = relation != null,
            label = formatCount(stat.coin),
            contentDescription = stringResource(R.string.video_action_coin),
            // 硬币这一格不走 Material 图标,自己画一个圆加一个 B,见 [CoinGlyph]。
            glyph = { tint -> CoinGlyph(tint = tint, filled = (relation?.coined ?: 0) > 0) },
            onClick = { showCoinDialog = true },
            holdProgress = holdProgress,
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
            holdProgress = holdProgress,
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
            maxCoins = maxCoins,
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
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 未选中的字形。传 [glyph] 的那一格不用它。 */
    icon: ImageVector? = null,
    /** 选中的字形。同上。 */
    selectedIcon: ImageVector? = null,
    /**
     * 长按做的事。只有点赞那一格有(一键三连),别的格传 null —— 传了就意味着这一格能长按。
     */
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    /**
     * 手指按在能长按的那一格上时报上去,由 [ActionButtonsRow] 换算成进度。
     *
     * **不读 `interactionSource` 的按下态。** `clickable` 在可滚动容器里会把按下这条交互
     * 压后 150ms 才发出(免得滑动时一路闪涟漪),而这一排正长在评论区那条滚动列表里 ——
     * 环于是比手指晚 150ms 起步,在 400ms 的长按窗口里只画得出三分之二就被判定长按了,
     * 看上去和没有环一样。这里直接听指针按下,`requireUnconsumed = false` 且全程不消费,
     * 底下那层 `combinedClickable` 照常收到同一串事件。
     */
    onHoldingChange: (Boolean) -> Unit = {},
    /** 这一格上那圈进度环画到哪儿了。0 就是不画,见 [ActionButtonsRow]。 */
    holdProgress: Float = 0f,
    /**
     * 自己画这一格的字形,不走 [icon]/[selectedIcon]。硬币那一格用它 —— 圆里的 B 是一个
     * 真字,交给字体画比自己描点靠谱(见 [CoinGlyph])。
     */
    glyph: (@Composable (tint: Color) -> Unit)? = null,
) {
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val holdable = enabled && onLongClick != null
    val currentOnHoldingChange by rememberUpdatedState(onHoldingChange)

    /**
     * 这一按是什么时候按下去的。用来分开"点一下"和"按住又松手",见下面 `onClick` 那一句。
     *
     * **读的是按下那一刻,不是松手那一刻。** 两个 pointerInput 节点在 Main 这一趟的先后
     * 不定,松手之后再去比标志位可能已经晚了;按下必然远early于松手,拿它当基准没有竞态。
     */
    var pressedAtMillis by remember { mutableLongStateOf(0L) }

    WithHoldTimeout(holdable) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .then(
                if (!holdable) {
                    Modifier
                } else {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            pressedAtMillis = SystemClock.uptimeMillis()
                            currentOnHoldingChange(true)
                            waitForUpOrCancellation()
                            currentOnHoldingChange(false)
                        }
                    }
                },
            )
            .combinedClickable(
                enabled = enabled,
                // **按住又松手不算点赞。** 手指一压过 [TapMaxMillis] 那圈进度环就开始画,
                // 这一下从那一刻起表达的已经是"我要三连";没走完就松手是**取消**,
                // 补一次点赞等于把取消办成了另一件事,而这两件事在硬币上不可逆的那一半正好相反。
                //
                // 判据是这一按持续了多久,不是环画到哪儿:环是 [ActionButtonsRow] 那一层的
                // 动画状态,这里够不着,而且它退场时还有一段回落,拿它判会把刚松手那几十毫秒
                // 也算成"还在按"。
                onClick = {
                    val heldMillis = SystemClock.uptimeMillis() - pressedAtMillis
                    if (!holdable || heldMillis <= TapMaxMillis) onClick()
                },
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel,
            )
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(vertical = Spacing.Hair),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val ring = MaterialTheme.colorScheme.primary
        Box(
            contentAlignment = Alignment.Center,
            // **环画在图标外面,但不占布局。** 原先它是一个 28dp 的 `Canvas` 兄弟节点,
            // 于是这个 Box 在环出现的那一刻从 20dp 长到 28dp,整排跟着抬一下 —— 一个为了
            // 说明"正在按住"的东西,自己先把界面顶动了。
            //
            // `drawBehind` 只画不量:Box 始终是图标那么大,环从它的边界往外画。这一层没有
            // 裁剪(外面那个 `clip` 在整格上,90×48 里放得下 28dp 的环),所以画得出来。
            modifier = Modifier.drawBehind {
                if (holdProgress <= 0f) return@drawBehind
                val inset = HoldRingInset.toPx()
                drawArc(
                    color = ring,
                    startAngle = -90f,
                    sweepAngle = 360f * holdProgress,
                    useCenter = false,
                    topLeft = Offset(-inset, -inset),
                    size = Size(size.width + inset * 2, size.height + inset * 2),
                    style = Stroke(width = HoldRingStroke.toPx(), cap = StrokeCap.Round),
                )
            },
        ) {
            val vector = if (selected) selectedIcon else icon
            when {
                glyph != null -> glyph(tint)
                vector != null -> Icon(
                    imageVector = vector,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(ActionIconSize),
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    }
}

/**
 * 把长按阈值换成 [TripleHoldMillis]。**必须包住整个子树,不能写成一个 Modifier** ——
 * `combinedClickable` 的手势节点是从自己所在的那个组合位置读 `LocalViewConfiguration` 的,
 * 挂在它后面的修饰符改不到它。
 *
 * 只覆盖长按这一项,触摸 slop、双击间隔照旧走系统那一份:换掉的是"按多久算长按",不是
 * 这台设备的手感。
 */
@Composable
private fun WithHoldTimeout(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
        return
    }
    val base = LocalViewConfiguration.current
    val overridden = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis: Long get() = TripleHoldMillis.toLong()
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides overridden, content = content)
}

/**
 * 硬币:一个圆,中间一个 B。
 *
 * **B 交给字体画,不自己描点。** 先手写过一版路径(一竖加两个碗,实心态再靠 evenOdd 挖出
 * 两个字腔),在 20dp 上是糊的 —— 字腔只剩一两个物理像素,而字体厂商为这个尺寸做了 hinting,
 * 手写坐标做不到。`Text` 还顺带解决了粗细和光学重心。
 *
 * **字号按 dp 折算,不跟系统字号缩放。** 圆是 dp 定死的,B 若跟着系统字号长大就会顶破它;
 * 这一个字是图标的一部分,不是可读的正文。
 *
 * 已投是实心圆挖出白字,未投是圈环加同色的字 —— 两个明显不同的字形,不只靠颜色区分
 * (风格指南 §2.6)。挖出来那个字取 `background`,也就是主题根部那层 `Surface` 真正画的底色
 * (见 `ui/theme/Theme.kt`)。
 */
@Composable
private fun CoinGlyph(tint: Color, filled: Boolean, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.background
    val density = LocalDensity.current
    val letterSize = remember(density) { with(density) { CoinLetterSize.toSp() } }
    Box(
        modifier = modifier
            .size(ActionIconSize)
            .then(
                if (filled) {
                    Modifier.background(tint, CircleShape)
                } else {
                    Modifier.border(CoinRingStroke, tint, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "B",
            color = if (filled) background else tint,
            style = TextStyle(
                fontSize = letterSize,
                // 行高等于字号、并关掉字体自带的上下留白,字才落在圆心上 —— 默认那两样都会
                // 把这一个字往下推,在 20dp 的圆里看得出来。
                lineHeight = letterSize,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            ),
        )
    }
}

private val ActionIconSize = 20.dp

/** 圈环的粗细,以及圆里那个 B 的字号。字占圆的六成左右,再大就贴边。 */
private val CoinRingStroke = 1.6.dp
private val CoinLetterSize = 12.dp

/** 进度环离图标的距离,以及它自己的粗细。环要绕开图标,不能压在字形上。 */
private val HoldRingInset = 4.dp
private val HoldRingStroke = 2.dp

/**
 * 按住多久算一次三连。
 *
 * 系统默认的长按是 400–500ms,对一个不可逆、要花掉当天硬币的动作太短 —— 在评论区往下滑的
 * 时候指腹压住这一格半秒是常有的事。
 *
 * **一秒试下来还是短。** 三连要花硬币,而误触的代价是不可逆的;这一格又长在一片可滑动的
 * 内容里,手指落上去本来就带着别的意图。宁可让真心想三连的人多按半拍,也不要让滑动的人
 * 花掉一枚币。一秒半同时给了那圈环足够的时间被看清 —— 环走完就是三连成立,松手什么都不发生。
 */
private const val TripleHoldMillis = 1_500

/**
 * 按到多久还算"点一下"。
 *
 * 超过它就当作已经在为三连蓄力:那圈进度环从这一刻开始画,松手是取消、不补点赞
 * (见 [ActionItem] 的 `onClick`)。两处必须用同一个数,否则会出现"环已经在转,松手却点了赞"。
 *
 * 200ms 明显长过一次有意的轻点(通常在 120ms 以内),又远短于 [TripleHoldMillis]。
 */
private const val TapMaxMillis = 200

/**
 * 投币面板。上限是**这条视频一共几枚**而不是"这一次几枚":自制稿两枚,转载稿一枚
 * (见 [dev.bilby.data.VideoDetail.maxCoins]),已投的数量还要从里面扣掉。投满时按钮禁用并
 * 报告已投数量。
 */
@Composable
private fun CoinDialog(
    maxCoins: Int,
    alreadyCoined: Int,
    attempt: CoinAttempt,
    onDismiss: () -> Unit,
    onConfirm: (count: Int, alsoLike: Boolean) -> Unit,
) {
    var selectedCount by rememberSaveable { mutableIntStateOf(1) }
    var alsoLike by rememberSaveable { mutableStateOf(false) }
    val remaining = maxCoins - alreadyCoined
    val maxedOut = remaining <= 0
    // 已投 1 枚时还列出 2,选中它必然换来一次服务端拒绝,而拒绝在界面上什么都不显示,
    // 看起来就是按钮没反应。转载稿同理:那里从一开始就只有 1 这一个选项。
    val selectableCounts = (1..remaining).toList()
    // rememberSaveable 存的可能是上一条视频选的 2 枚,而这一条是转载稿或者已经投过 1 枚了。
    val effectiveCount = selectedCount.coerceIn(1, remaining.coerceAtLeast(1))

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
    parts: List<EpisodePart>,
    onSelect: (EpisodeTarget) -> Unit,
) {
    var sheetOpen by rememberSaveable(parts.size) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
        SectionHeader(stringResource(R.string.video_parts)) {
            if (parts.size > PartRowExpandThreshold) {
                TextButton(onClick = { sheetOpen = true }) {
                    Text(stringResource(R.string.video_parts_expand, parts.size))
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
            items(parts, key = { it.cid }) { part ->
                FilterChip(
                    selected = part.isCurrent,
                    onClick = { onSelect(EpisodeTarget.Part(part.cid)) },
                    label = {
                        Text(
                            stringResource(R.string.video_part_label, part.ordinal, part.title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 分 P 名常常把视频标题又抄一遍("1-四时小路只是在聊天+"),不封顶
                            // 的话一个 chip 就吃掉大半屏:这条带子既看不出能横滚,又在简介页
                            // 上半部分占走一大块可滑区域,而**能滚的横向条会完整吃掉左右拖动**
                            // (实测:在它上面左右滑只滚 chip,不翻页)。封顶之后一屏能露出两个
                            // 半,它读起来才是一条带子,也把抢走手势的面积压下来。
                            //
                            // 封顶不是截断成"P1 P2 P3":分 P 名在课程、纪录片这类视频里是真信息,
                            // 而当前这一 P 有选中态,认得出自己在哪。
                            modifier = Modifier.widthIn(max = PartChipMaxWidth),
                        )
                    },
                )
            }
        }
    }

    if (sheetOpen) {
        PartSheet(
            parts = parts,
            onPick = {
                onSelect(EpisodeTarget.Part(it.cid))
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
    parts: List<EpisodePart>,
    onPick: (EpisodePart) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState()
    // 开的时候把当前这一 P 滚到可见:一百条里默认停在第一条,等于每次都要自己找。
    LaunchedEffect(Unit) {
        val current = parts.indexOfFirst { it.isCurrent }
        if (current >= 0) listState.scrollToItem(current)
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(state = listState) {
            items(parts, key = { it.cid }) { part ->
                val selected = part.isCurrent
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.video_part_label, part.ordinal, part.title),
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
                            onClick = { onPick(part) },
                        ),
                )
            }
        }
    }
}

/** 十条以内横滚划得完,再多就该给展开。 */
private const val PartRowExpandThreshold = 10


/** 分 P chip 的宽度上限。见 [PartRow] 里那段说明:够窄到一屏能露出两个半,读起来才是一条带子。 */
private val PartChipMaxWidth = 160.dp

/**
 * 播放队列:合集分集 / 该 UP 的其他投稿(DESIGN 2.4b)。官方在简介下方放算法召回的相关
 * 推荐,这里放的是确定性的有限集合——合集本身有限,空间投稿也在数据层被截成前后各 25 条
 * (见 QueueSourceRepository),不是"从推荐池续接",不违反 1.3 的推荐禁令。
 *
 * 这个列表同时就是「听视频」要播的队列本身,点条目直接切歌,不需要另外构造队列。
 * queue.rows 为空且不在加载中时不显示这一块。
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
    onSelectEpisode: (EpisodeTarget) -> Unit,
    onOpenQueueSource: (QueueSource) -> Unit,
    onToggleShuffle: () -> Unit,
    onFindRelated: () -> Unit,
    onCache: () -> Unit,
    onRetryQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!queue.enriching && !queue.incomplete && queue.rows.isEmpty()) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        QueueContent(
            queue = queue,
            onSelectEpisode = onSelectEpisode,
            onOpenQueueSource = onOpenQueueSource,
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
    onSelectEpisode: (EpisodeTarget) -> Unit,
    onOpenQueueSource: (QueueSource) -> Unit,
    onToggleShuffle: () -> Unit,
    onFindRelated: () -> Unit,
    onCache: () -> Unit,
    onRetryQueue: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        // 标题就是这份队列的来源。是合集/系列时它点得进目录 —— 队列只有当前视频前后一小段,
        // "这个合集里还有什么"要去目录看。UP 投稿和 UP 动态那两种来源没有目录页,不给入口。
        SectionHeader(
            title = queue.sourceLabel,
            // 标题左边和下面第一张封面对齐。队列行(CompactVideoRow)自己还有一层 Tight 内边距,
            // 而标题直接贴着这一列的内边距,不补这一层的话标题会比整列封面靠左 8dp。
            modifier = Modifier.padding(start = Spacing.Tight),
            onTitleClick = queue.source?.let { source -> { onOpenQueueSource(source) } },
        ) {
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

            // **高度由上一层给,这里只管填满。** 简介那一栏把剩下的空间整块交给这一节
            // (weight),所以这个 LazyColumn 拿到的约束本来就是有界的。
            //
            // 这里曾经有一段自己算高度的代码:量列表顶边到窗口内容底边的距离,再加回已经滚走
            // 的那一段。它是为了让一个 LazyColumn 活在可滚动的 Column 里 —— 那种嵌套没有
            // 有界高度可言,只能自己算一个。代价是这一页的高度成了窗口坐标的函数:简介一展开、
            // 播放器一收起、页面一滚动,顶边就变,列表跟着重新量高重组。简介栏不再整体滚动
            // 之后,这些全部消失。
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(queue.rows, key = { it.bvid }) { row ->
                    CompactVideoRow(
                        title = row.title,
                        coverUrl = row.coverUrl,
                        subtitle = if (row.durationSeconds > 0) formatDurationSeconds(row.durationSeconds) else null,
                        selected = row.isCurrent,
                        onClick = { onSelectEpisode(EpisodeTarget.Video(row.bvid)) },
                    )
                }
            }

            // 当前项居中:队列是"前后各 25 条",只滚到可见位置的话它会贴在顶或底,
            // 看不出前后还有多少。
            LaunchedEffect(queue.rows) {
                val index = queue.rows.currentIndex()
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



private fun formatDate(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

/** 名字栏的宽度区间。下限对齐头像，上限防止一个长名字把整排撑开。 */
private val StaffLabelMinWidth = 56.dp
private val StaffLabelMaxWidth = 104.dp

/** 收藏夹对话框里列表的高度上限。再高会把确认/取消按钮顶出屏幕。 */
private val FavPickerMaxHeight = 360.dp
