package dev.bilby.ui.video

import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.components.touchOnlyPaging
import dev.bilby.ui.components.trackEditableFocus
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.WindowInsets
import dev.bilby.ui.imeTarget
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.indication
import androidx.compose.ui.semantics.role
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Tab
import androidx.compose.material3.ButtonDefaults
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.windowDragArea
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.material3.ListItemDefaults
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
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
import dev.bilby.elapsedRealtimeMillis
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalWindowInfo
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.resources.*
import dev.bilby.ui.player.EpisodePart
import dev.bilby.ui.player.EpisodeRow
import dev.bilby.ui.player.EpisodeTarget
import dev.bilby.ui.player.QueueEdges
import dev.bilby.ui.player.QueueEditActions
import dev.bilby.ui.player.QueuePlaybackControls
import dev.bilby.ui.player.QueuePlaybackControlsRow
import dev.bilby.ui.player.QueueRowItem
import dev.bilby.ui.player.currentIndex
import dev.bilby.formatDurationSeconds
import dev.bilby.agent.AgentTurnState
import dev.bilby.data.FavFolder
import dev.bilby.data.FollowState
import dev.bilby.data.MemberCard
import dev.bilby.data.PlayEndMode
import dev.bilby.data.QueueOrder
import dev.bilby.data.QueueSource
import dev.bilby.data.VideoDetail
import dev.bilby.data.VideoRelation
import dev.bilby.data.VideoStat
import dev.bilby.data.VideoStaff
import dev.bilby.data.VideoTag
import dev.bilby.player.QueueItem
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.AvatarBadge
import dev.bilby.ui.components.CoinGlyph
import dev.bilby.ui.components.BilbyIcons
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.components.BadgedAvatar
import dev.bilby.ui.components.ChoiceRow
import dev.bilby.ui.components.FollowButton
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.LevelBadge
import dev.bilby.ui.components.SectionHeader
import dev.bilby.ui.components.StatRow
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import dev.bilby.BvidCodec
import dev.bilby.data.model.plainText
import dev.bilby.player.QueueState
import dev.bilby.ui.components.PlayingIndicator
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.CopyableIdPill
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.SelectableTextDialog
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.rememberCopyAction
import kotlinx.coroutines.delay
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.fillMaxHeight
import dev.bilby.ui.player.EpisodeList
import dev.bilby.ui.player.QueuePositionText
import dev.bilby.ui.components.BiliRichText
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.rememberReducedMotion
import dev.bilby.ui.theme.tabular

/**
 * 「找相关」的状态:一段以这条视频为上下文的助理会话。第一轮是找相关本身,之后是用户的追问
 * (「评论区怎么评价」「有没有更入门的」)。
 *
 * [started] 是这一页自己的事(用户点过没有,决定再点一次是重新检索还是只打开面板)。每一轮长什么样
 * 全在 [RelatedTurn.result] 里,和搜索页是同一份 [AgentTurnState]。
 */
data class RelatedState(
    val started: Boolean = false,
    val turns: List<RelatedTurn> = emptyList(),
) {
    val running: Boolean get() = turns.lastOrNull()?.result?.running == true
}

/** @param question 用户的追问;第一轮(找相关本身)没有提问,为 null。 */
data class RelatedTurn(val id: Long, val question: String?, val result: AgentTurnState)

/**
 * 播放队列(DESIGN 2.4b):合集分集或该 UP 的其他投稿,同时也是「听视频」要播的队列本身。
 * currentBvid 驱动列表里的高亮,[order] 驱动「正序 / 倒序 / 随机」按钮的文案。
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
    val order: QueueOrder = QueueOrder.Forward,
    val endMode: PlayEndMode = PlayEndMode.Continue,
    /**
     * 完整队列还没建好。**此刻 [rows] 里那一条不是队列,是占位** —— 起播时先装的临时队列
     * (见 AudioPlaybackService.openFrame),把它当队列摆出来会读成"这个 UP 只有一条投稿"。
     *
     * 这里不再看播放状态里的 `loading`:那一个说的是取流,而取流和建队列现在是并行的两件事。
     */
    val enriching: Boolean = false,
    /** 队列没建成,停在临时队列上。可重试,见 [QueueContent]。 */
    val incomplete: Boolean = false,
    /** 两头续取,只给完整队列面板用。页内那一段只摊当前项附近几条,不续。 */
    val edges: QueueEdges? = null,
    val editActions: QueueEditActions? = null,
    /** 服务侧队列快照,供标题栏、页内队列与听视频统一读取「X / N」位置与边界加载态。 */
    val state: QueueState? = null,
)

private const val VideoTabIntro = 0

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
    /** 简介/评论分页,由 VideoScreen 持有,页数见 [VideoTabCount]。 */
    pagerState: PagerState,
    detail: VideoDetail,
    onSelectEpisode: (EpisodeTarget) -> Unit,
    /** 这条视频的标签,展开简介才显示。空列表不画,见 VideoViewModel.videoTags。 */
    videoTags: List<VideoTag>,
    /** 第一次展开简介时拉标签,幂等,见 VideoViewModel.loadVideoTags。 */
    onLoadTags: () -> Unit,
    /** 点一枚标签 = 拿它的原文开一页普通搜索结果(Destinations.kt 的 SearchResult)。 */
    onTagClick: ((String) -> Unit)?,
    /** 服务端给的评论总数,标签上用。 */
    commentTotal: Int,
    /** 评论页的内容。由 VideoScreen 搭好,听视频那一屏用的是同一份,见那里的 commentsPane。 */
    comments: @Composable (Modifier) -> Unit,
    onFindRelated: (() -> Unit)?,
    /** 写弹幕,见 [DanmakuInput]。 */
    danmakuInput: DanmakuInput,
    /**
     * 标签行右边画不画弹幕胶囊。双栏时开关与输入框都在画面的控制条上(见 ControlBarDanmakuField),
     * 这里再放一份就是同一件事的两个入口。
     */
    showDanmakuCapsule: Boolean,
    /** 弹幕显示开关。全屏时这一行不组合,那时的开关在播放控制条上。 */
    danmakuEnabled: Boolean,
    onDanmakuEnabledChange: (Boolean) -> Unit,
    /** 打开缓存选择面板。面板本身长在播放队列那一节上,见 [QueueContent]。 */
    onCache: () -> Unit,
    followState: FollowState,
    onToggleFollow: () -> Unit,
    upCard: MemberCard?,
    queue: QueueUiState,
    /** 播放器此刻在不在放。队列里正在播的那一条据此跳动或静止,见 [PlayingIndicator]。 */
    playing: Boolean,
    onOpenQueueSource: (QueueSource) -> Unit,
    onCycleOrder: () -> Unit,
    onCycleEndMode: () -> Unit = {},
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
    onToggleToView: () -> Unit,
    /** 切到听视频那一屏。按钮在动作栏里,挨着稍后再看。 */
    onListen: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    coinAttempt: CoinAttempt,
    onCoinDialogClosed: () -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    /** 评论正文里引的那条链接。站内解析归导航层,见 MainActivity 的 openLink。 */
    onOpenLink: (String) -> Unit,
    /** 点了简介里的时间戳。null 表示此刻跳不了,时间戳会画成普通文字。 */
    onSeek: ((Long) -> Unit)? = null,
    banner: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 评论数用服务端给的总数,不是已渲染条数 —— 后者会随翻页一路变大,像个假计数器。
    val commentLabel = if (commentTotal > 0) {
        stringResource(Res.string.video_tab_comment_count, formatCount(commentTotal.toLong()))
    } else {
        stringResource(Res.string.video_tab_comment)
    }
    val titles = listOf(stringResource(Res.string.video_tab_intro), commentLabel)
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize()) {
        /*
         * **左边一组连接按钮切换简介/评论,右边一个弹幕胶囊(开关 + 发弹幕)。**
         *
         * 同一行放视图切换和弹幕入口是哔哩系客户端的惯例(PiliPlus `pages/video/view.dart` 的
         * `buildTabBar`,官方客户端同样如此)。两个弹幕入口在这里读得出是"这条视频的弹幕";
         * 挪进播放控制条就混进倍速、清晰度那一组"播放器怎么放"里。
         *
         * **不用 tabs。** tabs 页要求容器 "extend the full width of the window and be divided into
         * equal sections",右边一放弹幕入口这一条就守不住;按惯例把标签挤在左边一截,又是一排
         * 小字加一根只画到一半的分割线,和右边的按钮各说各话。button groups 页把连接组的用途写成
         * "select options, switch views, or sort elements in a page",切换视图正在其中,于是两边
         * 做视图切换;右边的胶囊和它同高、同底色,一行是一个整体,和下面的内容只靠留白分开,不画线。
         *
         * 配色和简介页的动作栏同一套:未选中 `surfaceContainer`,选中 `secondaryContainer`。组件
         * 默认的选中是 primary 实心,一行两组都顶着 primary 色块比下面的内容还重(风格指南 §2.1)。
         *
         * 内容区仍然能左右滑着翻页,左边那组跟着 pager 的页码走。全屏没有这一行,双栏时这一行
         * 不画胶囊:两者的控制条都是宽排法,弹幕开关与输入框就在画面上(见 BilbyPlayer 的
         * PlayerControlBar)。
         */
        /*
         * **双栏时是 tabs。** 上面不用 tabs 的理由是右边那枚胶囊;双栏没有胶囊,这一行就是右栏
         * 最上面一整条。用 primary:它顶着的是右栏的上沿,是这一栏的内容分区,不是播放器下面
         * 再分一层。
         *
         * **按字宽靠左,不等分。** 右栏六七百 dp,两格等分时各占三百多,两个词各自悬在一大片
         * 中间。可滚动的那一种按内容给宽度;只有两格,滚不起来。edgePadding 取 0:标签自带 16dp
         * 内边距,字的左沿正好落在下面内容的页边线上。
         *
         * **不画底下那条通栏分割线。** 它标的是"铺满全宽的标签栏"的下沿;标签靠左之后线还画到头,
         * 读起来是一条和标签无关的横杠。和窄屏那一行一样,与下面的内容只靠留白分开。
         */
        if (!showDanmakuCapsule) {
            val captionSlot = rememberCaptionSlot()
            BoxWithConstraints(
                modifier = captionSlot.modifier.fillMaxWidth(),
            ) {
                val leadingInset = ((maxWidth - Breakpoints.ReadableWidth) / 2).coerceAtLeast(0.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = if (captionSlot.buttons != null) Spacing.Hair else 0.dp),
                ) {
                    if (leadingInset > 0.dp) {
                        Spacer(
                            modifier = Modifier
                                .width(leadingInset)
                                .height(48.dp)
                                .windowDragArea(captionSlot.atTop),
                        )
                    }
                    CompactPrimaryTabStrip(
                        titles = titles,
                        pagerState = pagerState,
                        onSelect = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                    )
                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .windowDragArea(captionSlot.atTop),
                    )
                    captionSlot.buttons?.invoke()
                }
            }
        }
        AdaptiveContent(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            maxWidth = Breakpoints.ReadableWidth,
        ) {
        Column(modifier = Modifier.fillMaxSize()) {
        banner?.invoke()
        val toggleColors = ToggleButtonDefaults.toggleButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (showDanmakuCapsule) Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 写弹幕时视图切换退场,胶囊铺满整行:这时要的是输入的宽度,切页可以等写完。
            AnimatedVisibility(
                visible = !danmakuInput.open,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                titles.forEachIndexed { index, title ->
                    ToggleButton(
                        checked = pagerState.currentPage == index,
                        onCheckedChange = { scope.launch { pagerState.animateScrollToPage(index) } },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            titles.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        colors = toggleColors,
                        contentPadding = PaddingValues(horizontal = Spacing.Cozy),
                        modifier = Modifier.semantics { role = Role.Tab },
                    ) {
                        Text(text = title, maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(modifier = Modifier.width(Spacing.Tight))
            }
            }
            DanmakuCapsule(
                enabled = danmakuEnabled,
                onEnabledChange = onDanmakuEnabledChange,
                input = danmakuInput,
                modifier = Modifier.weight(1f),
            )
        }
        // 发送失败的原因就在胶囊底下一行,草稿留在胶囊里,改一个字再按发送就是重试。
        (danmakuInput.send as? DanmakuSend.Failed)?.takeIf { danmakuInput.open && showDanmakuCapsule }?.let { failed ->
            Text(
                text = stringResource(Res.string.danmaku_send_failed, failed.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.Comfortable),
            )
        }
        // weight 而不是 fillMaxSize:在 Column 里 fillMaxSize 会让 pager 从 tab 栏下面再要
        // 一整屏的高度,底部那一截被推出可视区。
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth().touchOnlyPaging()) { page ->
            when (page) {
                VideoTabIntro -> IntroTab(
                    detail = detail,
                    onSelectEpisode = onSelectEpisode,
                    videoTags = videoTags,
                    onLoadTags = onLoadTags,
                    onTagClick = onTagClick,
                    onFindRelated = onFindRelated,
                    onCache = onCache,
                    onUpClick = onUpClick,
                    staffFollowed = staffFollowed,
                    onFollowStaff = onFollowStaff,
                    followState = followState,
                    onToggleFollow = onToggleFollow,
                    upCard = upCard,
                    queue = queue,
                    playing = playing,
                    onOpenQueueSource = onOpenQueueSource,
                    onCycleOrder = onCycleOrder,
                    onCycleEndMode = onCycleEndMode,
                    onRetryQueue = onRetryQueue,
                    relation = relation,
                    favFolders = favFolders,
                    addedToView = addedToView,
                    onLike = onLike,
                    onTriple = onTriple,
                    onToggleToView = onToggleToView,
                    onListen = onListen,
                    onCoin = onCoin,
                    coinAttempt = coinAttempt,
                    onCoinDialogClosed = onCoinDialogClosed,
                    onOpenFavPicker = onOpenFavPicker,
                    onFavConfirm = onFavConfirm,
                    onOpenLink = onOpenLink,
                    onSeek = onSeek,
                )

                else -> comments(Modifier.fillMaxSize())
            }
        }
        }
        }
    }
}

private class CompactTabMetrics {
    var lefts: IntArray = IntArray(0)
    var widths: IntArray = IntArray(0)
}

/**
 * 双栏顶部按字宽紧贴左侧的标签组(照 Piko 的 `IslandTabRow`):只占实际标签宽度,不铺满整行,
 * 把「评论」到右上角窗口按钮之间的空白留给 [windowDragArea],无底部分割线。
 */
@Composable
private fun CompactPrimaryTabStrip(
    titles: List<String>,
    pagerState: PagerState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val metrics = remember { CompactTabMetrics() }
    val indicatorPath = remember { Path() }
    Layout(
        content = {
            titles.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { onSelect(index) },
                    selectedContentColor = primary,
                    unselectedContentColor = onSurfaceVariant,
                    text = { Text(text = title, maxLines = 1, softWrap = false) },
                )
            }
        },
        modifier = modifier
            .selectableGroup()
            .drawWithContent {
                drawContent()
                val lefts = metrics.lefts
                val widths = metrics.widths
                val count = widths.size
                if (count > 0 && lefts.size == count) {
                    val padPx = 16.dp.toPx()
                    val minWidthPx = 24.dp.toPx()
                    val indicatorHeightPx = 3.dp.toPx()
                    val pos = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
                        .coerceIn(0f, (count - 1).toFloat())
                    val low = pos.toInt().coerceIn(0, count - 1)
                    val high = (low + 1).coerceAtMost(count - 1)
                    val frac = pos - low
                    val lowW = (widths[low] - padPx * 2f).coerceAtLeast(minWidthPx)
                    val lowX = lefts[low] + (widths[low] - lowW) / 2f
                    val highW = (widths[high] - padPx * 2f).coerceAtLeast(minWidthPx)
                    val highX = lefts[high] + (widths[high] - highW) / 2f
                    val indLeft = lowX + (highX - lowX) * frac
                    val indWidth = lowW + (highW - lowW) * frac
                    val radius = CornerRadius(indicatorHeightPx, indicatorHeightPx)
                    indicatorPath.reset()
                    indicatorPath.addRoundRect(
                        RoundRect(
                            rect = Rect(
                                left = indLeft,
                                top = size.height - indicatorHeightPx,
                                right = indLeft + indWidth,
                                bottom = size.height,
                            ),
                            topLeft = radius,
                            topRight = radius,
                        ),
                    )
                    drawPath(path = indicatorPath, color = primary)
                }
            },
    ) { measurables, constraints ->
        // M3 的 Tab 内部带 fillMaxWidth(),须以无限上限测量才会收缩为标签自身的文字加内边距宽度。
        val tabConstraints = constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity)
        val placeables = measurables.map { it.measure(tabConstraints) }
        val lefts = IntArray(placeables.size)
        val widths = IntArray(placeables.size)
        var x = 0
        var maxHeight = 0
        placeables.forEachIndexed { index, placeable ->
            lefts[index] = x
            widths[index] = placeable.width
            x += placeable.width
            if (placeable.height > maxHeight) maxHeight = placeable.height
        }
        metrics.lefts = lefts
        metrics.widths = widths
        val totalWidth = x.coerceIn(constraints.minWidth, constraints.maxWidth)
        val totalHeight = maxHeight.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(totalWidth, totalHeight) {
            placeables.forEachIndexed { index, placeable ->
                placeable.placeRelative(lefts[index], (totalHeight - placeable.height) / 2)
            }
        }
    }
}


/**
 * 发弹幕这件事在标签行上的全部状态。**写弹幕就在胶囊里写**,不另开面板:弹幕是对着这一帧发的,
 * 胶囊就在画面正下方,人写的时候眼睛不用离开画面;键盘从底下升起,盖住的是简介和评论,
 * 不是画面。
 *
 * 开关输入层归调用方(VideoScreen 的 openDanmakuInput / closeDanmakuInput),这里只报时机。
 */
class DanmakuInput(
    /** 此刻是不是在写。 */
    val open: Boolean,
    val draft: String,
    val onDraftChange: (String) -> Unit,
    val send: DanmakuSend,
    val onOpen: () -> Unit,
    /** 退出输入:返回、键盘收起、点别处、发送成功都走这里。草稿不清,清在发送成功那一处。 */
    val onClose: () -> Unit,
    val onSend: () -> Unit,
)

/**
 * 弹幕胶囊:平时左端是弹幕开关、其余是"发一条弹幕"的输入口;点输入口,胶囊本身就变成输入框。
 * 照哔哩哔哩官方客户端竖屏播放页那一个:长得像输入框,人一眼知道点这里是写弹幕;开关长在它
 * 左端,两件事读得出是一对。
 *
 * **触摸区 48dp,画出来的胶囊 40dp**:和左边那组连接按钮同高(它们也是视觉 40、触摸 48),
 * 涟漪只画在胶囊里,不溢出到上下那两截透明的触摸区上。
 *
 * 写的时候开关退场:它和"正在写的这条"无关,留着只是挤掉输入的宽度。退出键在最右端
 * ([CapsuleCloseButton])。
 *
 * **长度上限 100 字**(服务端的,PiliPlus `danmaku.dart:12` 注明),超出的按键在这里拦住;
 * 过了 [CounterFrom] 在尾部显示计数,不然第 100 个字之后按键静默失效,看起来是键盘坏了。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DanmakuCapsule(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    input: DanmakuInput,
    modifier: Modifier = Modifier,
) {
    val capsuleColor = if (input.open) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val sending = input.send is DanmakuSend.Sending
    val hint = stringResource(Res.string.danmaku_input_hint)
    val stillOpen by rememberUpdatedState(input.open)
    val canSend = input.draft.isNotBlank() && !sending

    LaunchedEffect(input.open) {
        if (input.open) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    // **键盘开始收起就退出输入。** 返回键先被输入法拿去收键盘,焦点却还留在胶囊上;看的是
    // 键盘的动画目标而不是可不可见,`isImeVisible` 要等收起动画播完才变,那时画面已经晚了一拍
    // 才接着放。只在它变化时判断:请求焦点的那一帧目标还是 0。
    val imeHiding = WindowInsets.imeTarget.getBottom(LocalDensity.current) == 0
    LaunchedEffect(imeHiding) {
        if (imeHiding && input.open) focusManager.clearFocus()
    }

    Box(modifier = modifier.height(Dimens.MinTouchTarget)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = CapsuleInset)
                .background(capsuleColor, CircleShape),
        )
        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(
                visible = !input.open,
                enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut(),
            ) {
                DanmakuToggle(enabled = enabled, onEnabledChange = onEnabledChange)
            }
            if (input.open) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = Spacing.Comfortable),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (input.draft.isEmpty()) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    var wasFocused by remember { mutableStateOf(false) }
                    BasicTextField(
                        value = input.draft,
                        onValueChange = { if (it.length <= DanmakuMaxLength) input.onDraftChange(it) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (canSend) input.onSend() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .trackEditableFocus()
                            // 放掉焦点就是退出输入。判"曾经拿到过":刚进组合的那一帧焦点还没到,
                            // 那一下的 false 不能当成放掉。
                            .onFocusChanged { state ->
                                if (state.isFocused) wasFocused = true
                                else if (wasFocused && stillOpen) input.onClose()
                            }
                            .semantics { contentDescription = hint },
                    )
                }
                if (input.draft.length >= CounterFrom) {
                    Text(
                        text = stringResource(Res.string.danmaku_length_counter, input.draft.length, DanmakuMaxLength),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.Hair),
                    )
                }
                AnimatedVisibility(
                    visible = canSend || sending,
                    enter = scaleIn() + fadeIn(),
                    exit = scaleOut() + fadeOut(),
                ) {
                    // 胶囊里唯一的强调:填充的发送键,和胶囊同一个圆。
                    FilledIconButton(
                        onClick = input.onSend,
                        enabled = canSend,
                        modifier = Modifier.size(ButtonDefaults.MinHeight),
                    ) {
                        if (sending) {
                            LoadingSpinner()
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(Res.string.action_send),
                                modifier = Modifier.size(Dimens.IconInline),
                            )
                        }
                    }
                }
                CapsuleCloseButton(
                    onClick = {
                        keyboard?.hide()
                        input.onClose()
                    },
                )
            } else {
                val sendSource = remember { MutableInteractionSource() }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = sendSource,
                            indication = null,
                            role = Role.Button,
                            onClick = input.onOpen,
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    CapsuleRipple(sendSource, RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50))
                    Text(
                        // 草稿还在就把它亮出来:写了一半退出去,胶囊上看得见那半句。
                        text = input.draft.ifEmpty { hint },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(end = Spacing.Comfortable),
                    )
                }
            }
        }
    }
}

/** 胶囊左端的弹幕开关。`toggleable`,读屏念"开关,已开启";开与关换的是字形,不只是颜色。 */
@Composable
private fun DanmakuToggle(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(Dimens.MinTouchTarget)
            .toggleable(
                value = enabled,
                interactionSource = source,
                indication = null,
                role = Role.Switch,
                onValueChange = onEnabledChange,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CapsuleRipple(source, RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
        Icon(
            imageVector = if (enabled) BilbyIcons.Danmaku else BilbyIcons.DanmakuOff,
            contentDescription = stringResource(Res.string.danmaku_show),
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconAction),
        )
    }
}

/**
 * 写弹幕时胶囊右端的退出键,在发送键外侧。胶囊写的时候盖满整个标签行,返回键与收键盘之外
 * 没有别的出口,桌面上连这两条都没有。
 */
@Composable
private fun CapsuleCloseButton(onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(Dimens.MinTouchTarget)
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        CapsuleRipple(source, RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50))
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(Res.string.action_cancel),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconAction),
        )
    }
}

/** 服务端对弹幕正文的长度上限(PiliPlus `danmaku.dart:12` 注明的 100 字符)。 */
internal const val DanmakuMaxLength = 100

/** 到这个长度才显示计数。写一句话的人不需要被提醒还剩多少。 */
private const val CounterFrom = 80

/** 按压涟漪,只画在胶囊那 40dp 里。 */
@Composable
private fun BoxScope.CapsuleRipple(source: MutableInteractionSource, shape: Shape) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .padding(vertical = CapsuleInset)
            .clip(shape)
            .indication(source, ripple()),
    )
}

/**
 * 48dp 的触摸区里,胶囊上下各让出这么多,画出来和左边那组按钮一样高。
 *
 * **取 `ToggleButtonDefaults.MinHeight`,不取 `ButtonDefaults.MinHeight`。** 后者在 1.5.0-alpha25
 * 里不是常量:判定为精确指针时返回 36dp,否则才是 Small 按钮的 40dp(javap 看的 getter),真机上
 * 就拿到了 36,胶囊比旁边的按钮矮一截。左边那组是 `ToggleButton`,它量的是前者,一个固定的 40dp。
 */
private val CapsuleInset = (Dimens.MinTouchTarget - ToggleButtonDefaults.MinHeight) / 2

/**
 * 简介页:视频信息在上,播放队列接在下面,整页是一个列表。
 */
@Composable
private fun IntroTab(
    detail: VideoDetail,
    onSelectEpisode: (EpisodeTarget) -> Unit,
    videoTags: List<VideoTag>,
    onLoadTags: () -> Unit,
    onTagClick: ((String) -> Unit)?,
    onFindRelated: (() -> Unit)?,
    onCache: () -> Unit,
    followState: FollowState,
    onToggleFollow: () -> Unit,
    upCard: MemberCard?,
    queue: QueueUiState,
    playing: Boolean,
    onOpenQueueSource: (QueueSource) -> Unit,
    onCycleOrder: () -> Unit,
    onCycleEndMode: () -> Unit = {},
    onRetryQueue: () -> Unit,
    onUpClick: (Long) -> Unit,
    staffFollowed: Set<Long>?,
    onFollowStaff: (Long) -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    onTriple: () -> Unit,
    onToggleToView: () -> Unit,
    onListen: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    coinAttempt: CoinAttempt,
    onCoinDialogClosed: () -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onOpenLink: (String) -> Unit,
    /** 简介里的时间点点下去跳到哪。null 表示此刻跳不了,时间点画成普通文字。 */
    onSeek: ((Long) -> Unit)?,
) {
    var infoExpanded by rememberSaveable { mutableStateOf(false) }

    var fullQueueOpen by rememberSaveable { mutableStateOf(false) }
    var partSheetOpen by rememberSaveable { mutableStateOf(false) }
    val currentParts = queue.rows.firstOrNull { it.isCurrent }?.parts.orEmpty()

    /*
     * **页内只摊当前项前后几条,不续取。** 完整队列在 [FullQueueSheet] 里。
     *
     * 页内的队列曾经两头都跟着滚动续取。往上续取的那一头把这一页变成了陷阱:队列段上面还有
     * 标题、UP 行和动作栏,每续一页它们就远一页,打开一部长合集的最后一集之后,不翻完整部合集
     * 就回不到页顶。两头都能无限延伸的列表只能待在自己的视口里,那就是 sheet。
     */
    //
    // 窗口是当前项前后各 [InlineQueueRadius] 条。当前项在队列两头时窗口往里挪,始终凑满,
    // 打开最后一集看到的是最后几集。
    val shownRows = queue.rows.inlineWindow(queue.rows.currentIndex())

    /*
     * **整页一个列表,和评论页一样滚、一样收画面。**
     *
     * 这一栏曾经整体不滚,队列占满剩下的高度、在自己的视口里滚,好让当前项居中、前后各摊开
     * 25 条。由那个前提派生出一串补丁:简介页不许收画面,简介正文被挤进面板,找相关的 sheet
     * 要从这里回报投币行的窗口坐标,队列自带一份居中逻辑。
     * 当前项改为排在队列段的第一条、此前的条目收成一行之后,队列不再需要自己的视口。
     *
     * 块与块之间的间距写在各自的 bottom padding 上,不用 `verticalArrangement`:队列那一段
     * 的条与条之间是 2dp 的缝,一个统一的 spacedBy 给不出两种间距。
     */
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
    ) {
        item(key = "title") {
            TitleBlock(
                detail = detail,
                tags = videoTags,
                expanded = infoExpanded,
                onToggle = {
                    infoExpanded = !infoExpanded
                    // 标签到这一刻才拉,理由见 VideoViewModel.loadVideoTags。
                    if (infoExpanded) onLoadTags()
                },
                onTagClick = onTagClick,
                onOpenLink = onOpenLink,
                onMentionClick = onUpClick,
                onSeek = onSeek,
                modifier = Modifier.padding(bottom = IntroBlockGap),
            )
        }

        item(key = "up") {
            Box(modifier = Modifier.padding(bottom = IntroBlockGap)) {
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
            }
        }

        item(key = "actions") {
            ActionButtonsRow(
                modifier = Modifier.padding(bottom = BeforeSectionHeaderGap),
                stat = detail.stat,
                maxCoins = detail.maxCoins,
                relation = relation,
                favFolders = favFolders,
                addedToView = addedToView,
                onLike = onLike,
                onTriple = onTriple,
                onToggleToView = onToggleToView,
                onCoin = onCoin,
                coinAttempt = coinAttempt,
                onCoinDialogClosed = onCoinDialogClosed,
                onOpenFavPicker = onOpenFavPicker,
                onFavConfirm = onFavConfirm,
                onListen = onListen,
            )
        }

        // 分 P 来自切集清单里当前那一条,不再另从详情取一遍。清单已经把"这一刻能不能切 P"
        // 判过了(见 buildEpisodeRows):对不上身份时它是空的,这一排因此整个不出现。
        //
        // 合集的分集不在这里另排一行 chip:它们就是下面的播放队列。
        if (currentParts.isNotEmpty()) {
            partItems(
                parts = currentParts,
                playing = playing,
                onOpenAll = { partSheetOpen = true },
                onSelect = onSelectEpisode,
            )
        }

        queueItems(
            queue = queue,
            playing = playing,
            shownRows = shownRows,
            onOpenFullQueue = { fullQueueOpen = true },
            onOpenQueueSource = onOpenQueueSource,
            onSelectEpisode = onSelectEpisode,
            onCycleEndMode = onCycleEndMode,
            onCycleOrder = onCycleOrder,
            onFindRelated = onFindRelated,
            onCache = onCache,
            onRetryQueue = onRetryQueue,
        )
    }

    if (partSheetOpen && currentParts.isNotEmpty()) {
        PartSheet(
            parts = currentParts,
            playing = playing,
            onPick = { part ->
                partSheetOpen = false
                onSelectEpisode(EpisodeTarget.Part(part.cid))
            },
            onDismiss = { partSheetOpen = false },
        )
    }

    if (fullQueueOpen) {
        FullQueueSheet(
            queue = queue,
            playing = playing,
            onCycleEndMode = onCycleEndMode,
            onCycleOrder = onCycleOrder,
            onSelectEpisode = { target ->
                fullQueueOpen = false
                onSelectEpisode(target)
            },
            onDismiss = { fullQueueOpen = false },
        )
    }
}

/**
 * 完整队列。页内那一段只摊当前项附近几条(见 [IntroTab]),这里是整份队列,当前项居中,
 * 有自己的视口 —— 两头都能延伸的列表只能待在这种地方。列表本身是听视频与全屏共用的
 * [EpisodeList]。
 *
 * 走统一 [PaneSheet] 顶栏(来源标题 + 「X / N」位置胶囊 + 边界加载指示)与固定副头栏,列表用
 * `weight(1f, fill = false)` 在面板高度上限内弹性伸缩。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FullQueueSheet(
    queue: QueueUiState,
    playing: Boolean,
    onCycleEndMode: () -> Unit,
    onCycleOrder: () -> Unit,
    onSelectEpisode: (EpisodeTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(queue.rows.size) {
        if (queue.rows.size <= 1) editing = false
    }
    val title = queue.sourceLabel.ifBlank { stringResource(Res.string.listen_queue_title) }
    val posText = queue.state?.takeIf { !queue.enriching }?.let { QueuePositionText(it) }
    val edgeLoading = queue.state?.let { it.loadingBefore || it.loadingAfter } == true

    PaneSheet(
        onDismissRequest = onDismiss,
        title = title,
        skipPartiallyExpanded = true,
        headerTrailing = if (!posText.isNullOrBlank() || edgeLoading) {
            {
                if (!posText.isNullOrBlank()) {
                    QueuePositionBadge(text = posText)
                }
                if (edgeLoading) {
                    LoadingSpinner()
                }
            }
        } else {
            null
        },
        subheader = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Hair),
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    val actions = queue.editActions
                    if (actions != null && queue.rows.size > 1) {
                        TextButton(
                            onClick = { editing = !editing },
                            contentPadding = PaddingValues(horizontal = Spacing.Tight),
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                        ) {
                            Text(stringResource(if (editing) Res.string.action_done else Res.string.queue_edit))
                        }
                        if (editing && actions.onClearOthers != null) {
                            TextButton(
                                onClick = {
                                    actions.onClearOthers.invoke()
                                    editing = false
                                },
                                contentPadding = PaddingValues(horizontal = Spacing.Tight),
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                            ) {
                                Text(stringResource(Res.string.queue_clear_others))
                            }
                        }
                    }
                    val onReset = actions?.onReset
                    if (onReset != null) {
                        TextButton(
                            onClick = {
                                onReset()
                                editing = false
                            },
                            contentPadding = PaddingValues(horizontal = Spacing.Tight),
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                        ) {
                            Text(stringResource(Res.string.queue_reset))
                        }
                    }
                }
                QueuePlaybackControls(
                    endMode = queue.endMode,
                    order = queue.order,
                    onCycleEndMode = onCycleEndMode,
                    onCycleOrder = onCycleOrder,
                    showOrder = queue.rows.size > 1,
                )
            }
        },
    ) {
        EpisodeList(
            rows = queue.rows,
            onSelect = onSelectEpisode,
            edges = queue.edges,
            playing = playing,
            editActions = queue.editActions,
            editing = editing,
            errorBefore = queue.edges?.errorBefore == true,
            errorAfter = queue.edges?.errorAfter == true,
            remainingBefore = queue.edges?.remainingBefore,
            remainingAfter = queue.edges?.remainingAfter,
            // 左右留页边距:分段列表项自己不带外边距,贴着 sheet 边缘时圆角看不出来。
            contentPadding = PaddingValues(
                start = Spacing.Comfortable,
                end = Spacing.Comfortable,
                bottom = Spacing.Loose,
            ),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        )
    }
}

/**
 * @param upCard UP 主等级与粉丝数。null 表示还没查到或查失败——徽章不画,
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
    val participants = remember(mid, faceUrl, name, staff) {
        if (staff.any { it.mid == mid }) staff else listOf(VideoStaff(mid, "", name, faceUrl)) + staff
    }
    val copyText = rememberCopyAction()
    val copyUpNameLabel = stringResource(Res.string.action_copy_up_name)
    val copyUidLabel = stringResource(Res.string.action_copy_uid)
    val menuLabel = stringResource(Res.string.menu_item_actions)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Cozy, vertical = UpCardVerticalPadding),
        ) {
            if (staff.isEmpty()) {
                val itemCount = (if (name.isNotBlank()) 1 else 0) + (if (mid > 0L) 1 else 0)
                ContextMenuBox(
                    onClick = { onUpClick(mid) },
                    menuLabel = menuLabel,
                    modifier = Modifier
                        .weight(1f)
                        .interactiveCard(
                            shape = MaterialTheme.shapes.medium,
                            baseColor = Color.Transparent,
                            hoverColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    menu = { close ->
                        var cursor = 0
                        if (name.isNotBlank()) {
                            DropdownMenuItem(
                                onClick = {
                                    close()
                                    copyText(name)
                                },
                                text = { Text(copyUpNameLabel) },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                shape = menuItemShape(cursor++, itemCount),
                            )
                        }
                        if (mid > 0L) {
                            DropdownMenuItem(
                                onClick = {
                                    close()
                                    copyText(mid.toString())
                                },
                                text = { Text(copyUidLabel) },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                shape = menuItemShape(cursor++, itemCount),
                            )
                        }
                    },
                ) { _ ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = Dimens.MinTouchTarget)
                            .padding(horizontal = Spacing.Hair / 2),
                    ) {
                        Avatar(url = faceUrl, size = UpAvatarSize)
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                            ) {
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.titleSmall,
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
                            upCard?.let {
                                Text(
                                    text = stringResource(Res.string.space_followers, formatCount(it.follower)),
                                    style = MaterialTheme.typography.labelSmall.tabular,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            } else {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    items(participants, key = { "${it.mid}-${it.name}" }) { participant ->
                        val itemCount = (if (participant.name.isNotBlank()) 1 else 0) +
                            (if (participant.mid > 0L) 1 else 0)
                        ContextMenuBox(
                            onClick = { onUpClick(participant.mid) },
                            menuLabel = menuLabel,
                            modifier = Modifier
                                .width(StaffCellWidth)
                                .interactiveCard(
                                    shape = MaterialTheme.shapes.small,
                                    baseColor = Color.Transparent,
                                    hoverColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                ),
                            menu = { close ->
                                var cursor = 0
                                if (participant.name.isNotBlank()) {
                                    DropdownMenuItem(
                                        onClick = {
                                            close()
                                            copyText(participant.name)
                                        },
                                        text = { Text(copyUpNameLabel) },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                        shape = menuItemShape(cursor++, itemCount),
                                    )
                                }
                                if (participant.mid > 0L) {
                                    DropdownMenuItem(
                                        onClick = {
                                            close()
                                            copyText(participant.mid.toString())
                                        },
                                        text = { Text(copyUidLabel) },
                                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                        shape = menuItemShape(cursor++, itemCount),
                                    )
                                }
                            },
                        ) { _ ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Spacing.Hair / 2),
                            ) {
                                val followable = staffFollowed?.contains(participant.mid) == false
                                BadgedAvatar(
                                    url = participant.faceUrl,
                                    size = Dimens.AvatarRow,
                                    badge = if (!followable) null else AvatarBadge(
                                        icon = Icons.Filled.Add,
                                        contentDescription = stringResource(
                                            Res.string.video_follow_staff,
                                            participant.name,
                                        ),
                                        onClick = { onFollowStaff(participant.mid) },
                                    ),
                                )
                                Text(
                                    participant.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    participant.title,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
            if (staff.isEmpty()) {
                FollowButton(
                    state = followState,
                    onClick = onToggleFollow,
                    prominent = false,
                    name = name,
                )
            }
        }
    }
}

/**
 * 标题、元信息胶囊行和就地平滑展开的版权声明、简介与标签。
 *
 * - 标题升至 `titleLargeEmphasized`,长按/右键支持复制标题、BV 号、AV 号与自由选词;
 * - BV 号与 AV 号在收起与展开两态均提供单击即复制的 [CopyableIdPill](独立消费点按,不误触展开切换);
 * - 展开区以 `expandVertically + fadeIn` 在列表内原地生长,绝不卸载下方的播放队列。
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TitleBlock(
    detail: VideoDetail,
    tags: List<VideoTag>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTagClick: ((String) -> Unit)?,
    onOpenLink: (String) -> Unit,
    onMentionClick: (Long) -> Unit,
    onSeek: ((Long) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val isTouch = LocalPointerSource.current.isTouchLike
    val pointerSelectsText = expanded && !isTouch
    val resolvedAid = remember(detail.aid, detail.bvid) {
        detail.aid.takeIf { it > 0L } ?: BvidCodec.toAid(detail.bvid).takeIf { it > 0L }
    }
    val avidText = resolvedAid?.let { "av$it" }.orEmpty()

    val copyText = rememberCopyAction()
    val copyBvid = rememberCopyAction(
        clipLabel = stringResource(Res.string.video_bvid_clip_label),
        copiedMessage = stringResource(Res.string.video_bvid_copied),
    )
    val menuLabel = stringResource(Res.string.menu_item_actions)
    val copyTitleLabel = stringResource(Res.string.action_copy_title)
    val copyBvidLabel = stringResource(Res.string.video_bvid_copy)
    val copyAvidLabel = stringResource(Res.string.action_copy_avid)
    val copyDescLabel = stringResource(Res.string.action_copy_desc)
    val selectTextLabel = stringResource(Res.string.action_select_text)

    var selectableText by rememberSaveable { mutableStateOf<String?>(null) }

    val arrow: @Composable () -> Unit = {
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = stringResource(Res.string.video_intro_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconInline),
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        val titleMenuCount = (if (detail.title.isNotBlank()) 1 else 0) +
            (if (detail.bvid.isNotBlank()) 1 else 0) +
            (if (avidText.isNotEmpty()) 1 else 0) +
            (if (detail.title.isNotBlank()) 1 else 0)

        ContextMenuBox(
            onClick = if (pointerSelectsText) null else onToggle,
            menuLabel = menuLabel,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraSmall),
            menu = { close ->
                var cursor = 0
                if (detail.title.isNotBlank()) {
                    DropdownMenuItem(
                        onClick = {
                            close()
                            copyText(detail.title)
                        },
                        text = { Text(copyTitleLabel) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        shape = menuItemShape(cursor++, titleMenuCount),
                    )
                }
                if (detail.bvid.isNotBlank()) {
                    DropdownMenuItem(
                        onClick = {
                            close()
                            copyBvid(detail.bvid)
                        },
                        text = { Text(copyBvidLabel) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        shape = menuItemShape(cursor++, titleMenuCount),
                    )
                }
                if (avidText.isNotEmpty()) {
                    DropdownMenuItem(
                        onClick = {
                            close()
                            copyText(avidText)
                        },
                        text = { Text(copyAvidLabel) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        shape = menuItemShape(cursor++, titleMenuCount),
                    )
                }
                if (detail.title.isNotBlank()) {
                    DropdownMenuItem(
                        onClick = {
                            close()
                            selectableText = detail.title
                        },
                        text = { Text(selectTextLabel) },
                        leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                        shape = menuItemShape(cursor++, titleMenuCount),
                    )
                }
            },
        ) { _ ->
            val titleNode: @Composable () -> Unit = {
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.titleLargeEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (pointerSelectsText) {
                SelectionContainer(content = titleNode)
            } else {
                titleNode()
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.extraSmall)
                .clickable(
                    interactionSource = null,
                    indication = null,
                    onClick = onToggle,
                ),
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                StatRow(
                    playText = formatCount(detail.stat.view),
                    danmakuText = formatCount(detail.stat.danmaku),
                )
                Text(
                    text = if (expanded) {
                        formatFullTimestamp(detail.publishedAtEpochSeconds)
                    } else {
                        formatDate(detail.publishedAtEpochSeconds)
                    },
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                if (detail.chargingOnly) {
                    MetadataBadgePill(
                        text = stringResource(Res.string.video_badge_charging),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                if (detail.copyright == 2) {
                    MetadataBadgePill(
                        text = stringResource(Res.string.video_badge_repost),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (detail.bvid.isNotBlank()) {
                    CopyableIdPill(text = detail.bvid)
                }
                if (avidText.isNotEmpty()) {
                    CopyableIdPill(text = avidText)
                }
            }
            if (!expanded) {
                Spacer(modifier = Modifier.width(Spacing.Hair))
                arrow()
            }
        }

        val hasCopyrightNotice = detail.copyright == 1 && detail.noReprint
        val hasDescription = detail.description.isNotBlank() || detail.descriptionSpans.isNotEmpty()

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeIn(MaterialTheme.motionScheme.defaultEffectsSpec()),
            exit = shrinkVertically(MaterialTheme.motionScheme.defaultSpatialSpec()) +
                fadeOut(MaterialTheme.motionScheme.defaultEffectsSpec()),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                if (hasCopyrightNotice) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Block,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(CopyrightIconSize),
                        )
                        Text(
                            text = stringResource(Res.string.video_copyright_no_reprint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (hasDescription) {
                    val plainDesc = remember(detail.descriptionSpans, detail.description) {
                        detail.descriptionSpans.plainText().ifBlank { detail.description }
                    }
                    ContextMenuBox(
                        onClick = null,
                        menuLabel = menuLabel,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.extraSmall),
                        menu = { close ->
                            DropdownMenuItem(
                                onClick = {
                                    close()
                                    copyText(plainDesc)
                                },
                                text = { Text(copyDescLabel) },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                shape = menuItemShape(0, 2),
                            )
                            DropdownMenuItem(
                                onClick = {
                                    close()
                                    selectableText = plainDesc
                                },
                                text = { Text(selectTextLabel) },
                                leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                                shape = menuItemShape(1, 2),
                            )
                        },
                    ) { openMenu ->
                        val descriptionContent: @Composable () -> Unit = {
                            BiliRichText(
                                spans = detail.descriptionSpans,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                onLinkClick = onOpenLink,
                                onMentionClick = onMentionClick,
                                onSeek = onSeek,
                            )
                        }
                        if (pointerSelectsText) {
                            SelectionContainer(content = descriptionContent)
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics {
                                        onLongClick(label = menuLabel) {
                                            openMenu()
                                            true
                                        }
                                    }
                                    .pointerInput(openMenu) {
                                        detectTapGestures(onLongPress = { openMenu() })
                                    },
                            ) {
                                descriptionContent()
                            }
                        }
                    }
                }
                if (tags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    ) {
                        tags.forEach { tag ->
                            TagToken(
                                tag = tag,
                                onClick = onTagClick?.let { click -> { click(tag.name) } },
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.extraSmall)
                        .clickable(role = Role.Button, onClick = onToggle)
                        .padding(vertical = Spacing.Hair / 2),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    arrow()
                }
            }
        }
    }

    selectableText?.let { target ->
        SelectableTextDialog(
            text = target,
            onDismiss = { selectableText = null },
        )
    }
}

@Composable
private fun MetadataBadgePill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = Spacing.Hair, vertical = MetadataBadgeVerticalPadding),
        )
    }
}

/**
 * 一枚标签,点开是这个词的普通搜索结果页,右键/长按复制标签原文。
 *
 * 搜索用的是 tag_name 原文,不是展示文本:`#` 和「BGM：」都是这里加的装饰,带进搜索词
 * 只会让结果变差。
 */
@Composable
private fun TagToken(tag: VideoTag, onClick: (() -> Unit)?) {
    val copyTag = rememberCopyAction()
    val copyTagLabel = stringResource(Res.string.action_copy_tag)
    val menuLabel = stringResource(Res.string.menu_item_actions)

    ContextMenuBox(
        onClick = onClick,
        menuLabel = menuLabel,
        modifier = Modifier.interactiveCard(
            shape = CircleShape,
            baseColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        menu = { close ->
            DropdownMenuItem(
                onClick = {
                    close()
                    copyTag(tag.name)
                },
                text = { Text(copyTagLabel) },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                shape = menuItemShape(0, 1),
            )
        },
    ) { openMenu ->
        Box(
            modifier = Modifier
                .heightIn(min = TagHeight)
                .then(
                    if (onClick == null) {
                        Modifier
                            .semantics {
                                onLongClick(label = menuLabel) {
                                    openMenu()
                                    true
                                }
                            }
                            .pointerInput(openMenu) {
                                detectTapGestures(onLongPress = { openMenu() })
                            }
                    } else {
                        Modifier
                    },
                )
                .padding(horizontal = Spacing.Cozy),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = tag.displayText(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
 * 点赞 / 投币 / 收藏 / 稍后再看 / 听视频。**图标在上、计数在下,五格等宽平分一行**,
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
    onToggleToView: () -> Unit,
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

    // **一排分段的格子**,和下面的队列同一种外形:格与格之间留缝,整排首尾大圆角、中间小圆角。
    // 格子曾经没有底色,图标和计数在格内居中,最左最右两格离页边各空出半格,这一排看上去缩在
    // 中间,和上下贴着页边距的标题、队列对不齐。有了底色,格子的外沿就是页边距。
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ActionSegmentGap),
    ) {
        // 长按三连挂在点赞上,这是 B 站的老手势;不另开一格,那一排已经有五个动作了。
        ActionItem(
            modifier = Modifier.weight(1f),
            shape = horizontalSegmentShape(index = 0, count = ActionCount),
            selected = relation?.liked == true,
            enabled = relation != null,
            label = formatCount(stat.like),
            contentDescription = stringResource(
                if (relation?.liked == true) Res.string.video_action_unlike else Res.string.video_action_like,
            ),
            selectedIcon = Icons.Filled.ThumbUp,
            icon = Icons.Outlined.ThumbUp,
            onClick = onLike,
            onLongClick = onTriple,
            longClickLabel = stringResource(Res.string.video_triple),
            onHoldingChange = { holdingLike = it },
            holdProgress = holdProgress,
        )
        // 投币点下去是弹框(问投几枚)而不是直接投,但"已投币"是一个实实在在的可显示状态,
        // 所以它和点赞用同一种表达,区别只在点击后发生什么。
        ActionItem(
            modifier = Modifier.weight(1f),
            shape = horizontalSegmentShape(index = 1, count = ActionCount),
            selected = (relation?.coined ?: 0) > 0,
            enabled = relation != null,
            label = formatCount(stat.coin),
            contentDescription = stringResource(Res.string.video_action_coin),
            // 硬币这一格不走 Material 图标,自己画一个圆加一个 B,见 [CoinGlyph]。
            glyph = { tint, container ->
                CoinGlyph(tint = tint, filled = (relation?.coined ?: 0) > 0, cutout = container)
            },
            onClick = { showCoinDialog = true },
            holdProgress = holdProgress,
        )
        ActionItem(
            modifier = Modifier.weight(1f),
            shape = horizontalSegmentShape(index = 2, count = ActionCount),
            selected = relation?.favored == true,
            enabled = relation != null,
            label = formatCount(stat.favorite),
            contentDescription = stringResource(Res.string.video_action_favorite),
            selectedIcon = Icons.Filled.Star,
            icon = Icons.Outlined.StarBorder,
            iconSize = ActionStarSize,
            onClick = {
                awaitingFavFolders = true
                onOpenFavPicker()
            },
            holdProgress = holdProgress,
        )
        // 稍后再看:在这一页里是个 toggle,进页面时总是未加入态(见 VideoViewModel.addedToView)。
        ActionItem(
            modifier = Modifier.weight(1f),
            shape = horizontalSegmentShape(index = 3, count = ActionCount),
            selected = addedToView,
            enabled = true,
            label = stringResource(
                if (addedToView) Res.string.video_action_toview_added else Res.string.video_action_toview,
            ),
            contentDescription = stringResource(Res.string.video_action_toview_desc),
            selectedIcon = Icons.Filled.WatchLater,
            icon = Icons.Outlined.WatchLater,
            onClick = onToggleToView,
        )
        // 听视频挨着稍后再看:这两件事是同一类决定——**这条视频我打算怎么消费**(现在只听、
        // 还是回头再看),而左边三个是对内容表态。它原先在画面底部那条控制条上,那里全是
        // "播放器现在怎么放"(倍速、清晰度、字幕、弹幕),听视频混在里面读不出它会换掉整页形态。
        //
        // 没有选中态:它不是开关,点下去就切到听视频那一屏了。
        ActionItem(
            modifier = Modifier.weight(1f),
            shape = horizontalSegmentShape(index = 4, count = ActionCount),
            selected = false,
            enabled = true,
            label = stringResource(Res.string.video_action_listen),
            contentDescription = stringResource(Res.string.video_action_listen),
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
        FavPickerSheet(
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
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ActionItem(
    selected: Boolean,
    enabled: Boolean,
    label: String,
    contentDescription: String,
    onClick: () -> Unit,
    /** 这一格在整排里的外形,见 [horizontalSegmentShape]。 */
    shape: Shape,
    modifier: Modifier = Modifier,
    /** 未选中的字形。传 [glyph] 的那一格不用它。 */
    icon: ImageVector? = null,
    /** 选中的字形。同上。 */
    selectedIcon: ImageVector? = null,
    /**
     * 字形的尺寸。默认 [ActionIconSize];星形单独放大,它的字形四周留白多,同尺寸下看上去比
     * 拇指、钟表小一圈。
     */
    iconSize: Dp = ActionIconSize,
    /**
     * 长按做的事。只有点赞那一格有(一键三连),别的格传 null —— 传了就意味着这一格能长按。
     */
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    /**
     * 手指按在能长按的那一格上时报上去,由 [ActionButtonsRow] 换算成进度。
     *
     * **不读 `interactionSource` 的按下态。** `clickable` 在可滚动容器里会把按下这条交互
     * 压后 150ms 才发出(免得滑动时一路闪涟漪),而这一排正长在滚动列表里 —— 这里直接听指针按下,
     * `requireUnconsumed = false` 且全程不消费,底下那层 `combinedClickable` 照常收到同一串事件。
     */
    onHoldingChange: (Boolean) -> Unit = {},
    /** 这一格上那圈进度环画到哪儿了。0 就是不画,见 [ActionButtonsRow]。 */
    holdProgress: Float = 0f,
    /**
     * 自己画这一格的字形,不走 [icon]/[selectedIcon]。硬币那一格用它 —— 圆里的 B 是一个
     * 真字,交给字体画比自己描点靠谱(见 [CoinGlyph])。
     */
    glyph: (@Composable (tint: Color, container: Color) -> Unit)? = null,
) {
    val targetContainer = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }
    val container by animateColorAsState(
        targetValue = targetContainer,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "actionItemContainer",
    )
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.outlineVariant
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val holdable = enabled && onLongClick != null
    val currentOnHoldingChange by rememberUpdatedState(onHoldingChange)
    val reducedMotion = rememberReducedMotion()

    var pressedAtMillis by remember { mutableLongStateOf(0L) }
    var isPressed by remember { mutableStateOf(false) }
    var popActive by remember { mutableStateOf(false) }
    var prevSelected by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !prevSelected && !reducedMotion) {
            popActive = true
            delay(ActionPopMillis)
            popActive = false
        }
        prevSelected = selected
    }
    val targetScale = when {
        reducedMotion || !enabled -> 1f
        popActive -> ActionPopScale
        isPressed -> ActionPressScale
        else -> 1f
    }
    val itemScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "actionItemScale",
    )

    WithHoldTimeout(holdable) {
    Column(
        modifier = modifier
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .clip(shape)
            .background(container)
            .pointerInput(enabled, holdable) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isPressed = true
                    if (holdable) {
                        pressedAtMillis = elapsedRealtimeMillis()
                        currentOnHoldingChange(true)
                    }
                    waitForUpOrCancellation()
                    isPressed = false
                    if (holdable) {
                        currentOnHoldingChange(false)
                    }
                }
            }
            .combinedClickable(
                enabled = enabled,
                onClick = {
                    val heldMillis = elapsedRealtimeMillis() - pressedAtMillis
                    if (!holdable || heldMillis <= TapMaxMillis) onClick()
                },
                onLongClick = onLongClick,
                onLongClickLabel = longClickLabel,
            )
            .heightIn(min = Dimens.MinTouchTarget)
            .padding(vertical = Spacing.Tight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val ring = MaterialTheme.colorScheme.primary
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(ActionIconBox).drawBehind {
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
                glyph != null -> glyph(tint, container)
                vector != null -> Icon(
                    imageVector = vector,
                    contentDescription = contentDescription,
                    tint = tint,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
        Spacer(modifier = Modifier.height(ActionLabelGap))
        Text(
            text = label,
            style = if (selected) {
                MaterialTheme.typography.labelSmallEmphasized.tabular
            } else {
                MaterialTheme.typography.labelSmall.tabular
            },
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

private val ActionIconSize = 20.dp

/** 星形字形的尺寸,见 [ActionItem] 的 iconSize。 */
private val ActionStarSize = 24.dp

/** 字形所在那个定高的框,容得下放大后的星形。 */
private val ActionIconBox = 24.dp

/** 字形与下面那行计数之间的距离。 */
private val ActionLabelGap = 4.dp

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
        title = { Text(stringResource(Res.string.video_action_coin)) },
        text = {
            Column {
                if (maxedOut) {
                    Text(
                        stringResource(Res.string.coin_maxed),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    selectableCounts.forEach { count ->
                        ChoiceRow(
                            selected = effectiveCount == count,
                            onSelect = { selectedCount = count },
                            label = stringResource(Res.string.coin_count, count),
                        )
                    }
                    ToggleRow(
                        checked = alsoLike,
                        onToggle = { alsoLike = it },
                        label = stringResource(Res.string.coin_also_like),
                    )
                    // 投币是不可逆的:B 站没有撤销接口,币也不退。误触的代价由用户承担,
                    // 所以这句必须出现在确认之前 —— 这是全应用少数几个用 error 色的地方。
                    //
                    // 失败之后这句换成失败原因,不两句并排:两句都是 error 色,读起来分不清
                    // 哪句在说刚才那一次。警告在重投之前还会再出现一遍(错误清掉之后)。
                    Text(
                        text = (attempt as? CoinAttempt.Failed)?.message
                            ?.let { stringResource(Res.string.coin_failed, it) }
                            ?: stringResource(Res.string.coin_warning),
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
                Text(stringResource(Res.string.action_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.action_cancel)) }
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
            .clip(MaterialTheme.shapes.small)
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
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 收藏夹多选面板。走统一 [PaneSheet](`skipPartiallyExpanded = true`):宽屏两栏播放页内嵌进右栏,
 * 不给左侧画面打遮罩;确认时只算增量(新勾选的 add,取消勾选的 del)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FavPickerSheet(
    favFolders: List<FavFolder>,
    onDismiss: () -> Unit,
    onConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
) {
    val selected = remember(favFolders) {
        mutableStateMapOf<Long, Boolean>().apply { favFolders.forEach { put(it.id, it.containsThis) } }
    }

    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.fav_dialog_title),
        skipPartiallyExpanded = true,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
                Button(
                    onClick = {
                        val addIds = favFolders.filter { selected[it.id] == true && !it.containsThis }.map { it.id }
                        val delIds = favFolders.filter { selected[it.id] == false && it.containsThis }.map { it.id }
                        onConfirm(addIds, delIds)
                    },
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            }
        },
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = Spacing.Loose, vertical = Spacing.Tight),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            items(favFolders, key = { it.id }) { folder ->
                ToggleRow(
                    checked = selected[folder.id] ?: folder.containsThis,
                    onToggle = { checked -> selected[folder.id] = checked },
                    label = folder.title,
                    count = formatCount(folder.count.toLong()),
                )
            }
        }
    }
}

/**
 * 分 P,作为简介列表里的一节:标题行加一排横滑的格子,多了在标题行给「全部」,开 [PartSheet]。
 *
 * 格子的外形和动作栏同一套(整排首尾大圆角、中间小圆角、格间留缝),选中态和队列条目同一组
 * 颜色。做过一版竖排、和队列同一种列表,多 P 视频的简介页因此长出一大截;横排只占一行高。
 * 横滑还保留了"相邻切换最快、看得出当前是第几 P"这两样,chip 时代留它的理由仍然成立。
 */
private fun LazyListScope.partItems(
    parts: List<EpisodePart>,
    playing: Boolean,
    onOpenAll: () -> Unit,
    onSelect: (EpisodeTarget) -> Unit,
) {
    item(key = "parts-header") {
        SectionHeader(title = stringResource(Res.string.video_parts)) {
            // 内边距与队列标题行的「顺序/随机」同一档,两节标题行右端的字才对得齐。
            TextButton(onClick = onOpenAll, contentPadding = PaddingValues(horizontal = Spacing.Tight)) {
                Text(
                    text = stringResource(Res.string.video_parts_expand, parts.size),
                    style = MaterialTheme.typography.labelLarge.tabular,
                )
            }
        }
    }
    item(key = "parts-row") {
        // 停在当前 P 的前一格:左边露出一格,看得出前面还有。**跟着当前 P 滚,不只在首次组合时
        // 定位**:这一行组合出来时播放器常常还没报出 cid,那一刻找不到当前 P;只定一次初始位置的
        // 话它就永远停在开头。切 P 时同理要跟过去。
        val rowState = rememberLazyListState()
        val currentIndex = parts.indexOfFirst { it.isCurrent }
        LaunchedEffect(currentIndex) {
            if (currentIndex >= 0) rowState.animateScrollToItem((currentIndex - 1).coerceAtLeast(0))
        }
        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(ActionSegmentGap),
            modifier = Modifier.padding(bottom = BeforeSectionHeaderGap),
        ) {
            itemsIndexed(parts, key = { _, part -> part.cid }) { index, part ->
                PartCard(
                    part = part,
                    playing = playing,
                    shape = horizontalSegmentShape(index = index, count = parts.size),
                    onClick = { onSelect(EpisodeTarget.Part(part.cid)) },
                )
            }
        }
    }
}

/** 横排里的一格:编号标记接两行标题,当前那格右下角浮一枚播放指示。 */
@Composable
private fun PartCard(part: EpisodePart, playing: Boolean, shape: Shape, onClick: () -> Unit) {
    val selected = part.isCurrent
    Surface(
        onClick = onClick,
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.width(PartCardWidth).semantics { this.selected = selected },
    ) {
        Box(modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight)) {
            // **编号嵌在标题第一行的行首**,标题紧跟在它后面,折行后第二行从格子左边缘开始。
            // 编号单独占一行的那一版,两行标题上面还压着一行,格子高了一截,编号也比标题显眼。
            //
            // 占位宽度按这一格的编号现量:P1 和 P12 不一样宽,写死一个宽度要么留空要么挤字。
            val tagText = stringResource(Res.string.video_part_ordinal, part.ordinal)
            val tagStyle = MaterialTheme.typography.labelSmall
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val placeholder = remember(tagText, tagStyle, density) {
                val size = measurer.measure(tagText, tagStyle).size
                with(density) {
                    Placeholder(
                        // 标记本身的宽,再加上它和标题之间的间距。间距算在占位里而不是追加一个空格:
                        // 空格有多宽随字体走,算在这里是一个确定的数。
                        width = (size.width.toDp() + PartTagPadding * 2 + PartTagGap).toSp(),
                        height = (size.height.toDp() + PartTagPadding / 2).toSp(),
                        placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                    )
                }
            }
            // 两行都占着:标题只有一行的格子也和两行的一样高,整排是一条齐的带子。
            //
            // **播放指示浮在右下角,不占排版。** 放过一版为它在右侧让出一截,会把"01_Lec1a"这种
            // 断不开的词整个挤到第二行,第一行只剩一枚标记。
            Text(
                text = buildAnnotatedString {
                    appendInlineContent(PartTagId, tagText)
                    append(part.title)
                },
                inlineContent = mapOf(
                    PartTagId to InlineTextContent(placeholder) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            PartOrdinalTag(ordinal = part.ordinal, selected = selected)
                        }
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (selected) {
                // 垫一块与格子同色的底:第二行文字若延伸到右下角,被它盖住而不是和竖条叠在一起。
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                    modifier = Modifier.align(Alignment.BottomEnd),
                ) {
                    PlayingIndicator(
                        active = playing,
                        contentDescription = stringResource(
                            if (playing) Res.string.video_queue_now_playing else Res.string.video_queue_paused,
                        ),
                        modifier = Modifier.padding(start = Spacing.Hair).size(Dimens.IconInline),
                    )
                }
            }
        }
    }
}

/**
 * 页内队列摊出来的窗口:当前项前后各 [InlineQueueRadius] 条,当前项在两头时窗口往里挪,
 * 始终凑满。[currentIndex] 为 -1(找不到当前项)时从头取。
 */
private fun <T> List<T>.inlineWindow(currentIndex: Int): List<T> {
    val count = minOf(InlineQueueRadius * 2 + 1, size)
    val from = (currentIndex.coerceAtLeast(0) - InlineQueueRadius).coerceIn(0, size - count)
    return subList(from, from + count)
}

/**
 * 分 P 的编号,做成一枚小标记,嵌在标题行首。编号是索引,不是这一格要读的内容;原先用 labelLarge 写成一行,
 * 比下面的标题还重。当前那格用 primary 实底,和格子本身的 secondaryContainer 拉开一档。
 */
@Composable
private fun PartOrdinalTag(ordinal: Int, selected: Boolean) {
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = stringResource(Res.string.video_part_ordinal, ordinal),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = PartTagPadding, vertical = PartTagPadding / 4),
        )
    }
}

/** 全部分集里的一条。左边是编号,其余和队列条目([QueueListItem])同一套外形与选中态。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PartListItem(part: EpisodePart, playing: Boolean, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        selected = part.isCurrent,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = {
            Text(
                text = stringResource(Res.string.video_part_ordinal, part.ordinal),
                style = MaterialTheme.typography.labelLarge.tabular,
            )
        },
        trailingContent = if (part.isCurrent) {
            {
                PlayingIndicator(
                    active = playing,
                    contentDescription = stringResource(
                        if (playing) Res.string.video_queue_now_playing else Res.string.video_queue_paused,
                    ),
                    modifier = Modifier.size(Dimens.IconInline),
                )
            }
        } else {
            null
        },
    ) {
        Text(text = part.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * 全部分集。**一行一条而不是铺一片 chip**:几百 P 的标题是"第 12 讲 线性方程组"这种,
 * 挤成 chip 只剩截断后的两三个字,而用户要找的正是标题。
 *
 * 走统一 [PaneSheet](`skipPartiallyExpanded = true`),顶栏右端带「当前 P / 总 P 数」胶囊,
 * 打开时当前 P 上方留两条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartSheet(
    parts: List<EpisodePart>,
    playing: Boolean,
    onPick: (EpisodePart) -> Unit,
    onDismiss: () -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (parts.indexOfFirst { it.isCurrent } - InlineQueueRadius).coerceAtLeast(0),
    )
    val currentOrdinal = parts.firstOrNull { it.isCurrent }?.ordinal
        ?: parts.indexOfFirst { it.isCurrent }.takeIf { it >= 0 }?.plus(1)
    val badgeText = if (currentOrdinal != null) {
        "$currentOrdinal / ${parts.size}"
    } else {
        parts.size.toString()
    }
    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.video_parts),
        skipPartiallyExpanded = true,
        headerTrailing = { QueuePositionBadge(text = badgeText) },
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = Spacing.Comfortable,
                end = Spacing.Comfortable,
                bottom = Spacing.Loose,
            ),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
            itemsIndexed(parts, key = { _, part -> part.cid }) { index, part ->
                PartListItem(
                    part = part,
                    playing = playing,
                    index = index,
                    count = parts.size,
                    onClick = { onPick(part) },
                )
            }
        }
    }
}




/**
 * 播放队列:合集分集 / 该 UP 的其他投稿(DESIGN 2.4b),作为简介列表里的一段条目。
 * 官方在简介下方放算法召回的相关推荐,这里放的是确定性的有限集合,不是"从推荐池续接",
 * 不违反 1.3 的推荐禁令。这个列表同时就是「听视频」要播的队列本身,点条目直接切歌。
 *
 * **只摊开当前项附近几条**,完整队列在面板里,由段尾的分段收底块([ShowAllRow])打开。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun LazyListScope.queueItems(
    queue: QueueUiState,
    playing: Boolean,
    /** 摊开的那部分,当前项前后各若干条。 */
    shownRows: List<EpisodeRow>,
    onOpenFullQueue: () -> Unit,
    onOpenQueueSource: (QueueSource) -> Unit,
    onSelectEpisode: (EpisodeTarget) -> Unit,
    onCycleEndMode: () -> Unit,
    onCycleOrder: () -> Unit,
    onFindRelated: (() -> Unit)?,
    onCache: () -> Unit,
    onRetryQueue: () -> Unit,
) {
    if (!queue.enriching && !queue.incomplete && queue.rows.isEmpty()) return

    item(key = "queue-header") {
        QueueHeader(
            queue = queue,
            onOpenQueueSource = onOpenQueueSource,
            onCycleEndMode = onCycleEndMode,
            onCycleOrder = onCycleOrder,
            onFindRelated = onFindRelated,
            onCache = onCache,
        )
    }

    when {
        queue.enriching -> item(key = "queue-status") {
            InlineProgress(
                stringResource(Res.string.video_queue_loading),
                Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            )
        }

        queue.incomplete -> item(key = "queue-status") {
            Row(
                modifier = Modifier.padding(start = Spacing.Comfortable),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Text(
                    text = stringResource(Res.string.video_queue_incomplete),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                TextButton(
                    onClick = onRetryQueue,
                    contentPadding = PaddingValues(horizontal = Spacing.Tight),
                ) {
                    Text(stringResource(Res.string.action_retry))
                }
            }
        }

        else -> {
            val fullIndexByBvid = queue.rows.withIndex().associate { (i, r) -> r.bvid to i }
            val showAllShown = queue.rows.isNotEmpty()
            val segmentedTotal = if (showAllShown) shownRows.size + 1 else shownRows.size
            itemsIndexed(shownRows, key = { _, row -> row.bvid }) { index, row ->
                QueueRowItem(
                    row = row,
                    playing = playing,
                    index = index,
                    count = segmentedTotal,
                    onClick = { onSelectEpisode(EpisodeTarget.Video(row.bvid)) },
                    videoIndex = fullIndexByBvid[row.bvid] ?: index,
                    videoCount = queue.rows.size,
                    editActions = queue.editActions,
                )
            }
            if (showAllShown) {
                item(key = "queue-show-all") {
                    ShowAllRow(
                        queue = queue,
                        hasPrecedingRows = shownRows.isNotEmpty(),
                        onClick = onOpenFullQueue,
                    )
                }
            }
        }
    }
}

/**
 * 页内队列分段列表的收底入口块:与上方条目保持 2dp 分段缝、上沿 4dp 内角、下沿 16dp 外角,
 * 并把窗口态/全量条数透明报出(`video_queue_show_all_windowed` / `video_queue_show_all_count`)。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ShowAllRow(
    queue: QueueUiState,
    hasPrecedingRows: Boolean,
    onClick: () -> Unit,
) {
    val qState = queue.state
    val loadedCount = queue.rows.size
    val isWindowed = qState?.isWindowed == true ||
        queue.edges?.canLoadBefore == true ||
        queue.edges?.canLoadAfter == true
    val totalCount = qState?.displayTotal ?: loadedCount
    val label = when {
        isWindowed && qState?.sourceTotal != null ->
            stringResource(Res.string.video_queue_show_all_windowed, loadedCount, qState.sourceTotal)
        totalCount > 0 ->
            stringResource(Res.string.video_queue_show_all_count, totalCount)
        else ->
            stringResource(Res.string.video_queue_show_all)
    }
    val topCorner = if (hasPrecedingRows) ActionInnerCorner else ActionOuterCorner
    val shape = RoundedCornerShape(
        topStart = topCorner,
        topEnd = topCorner,
        bottomStart = ActionOuterCorner,
        bottomEnd = ActionOuterCorner,
    )

    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (hasPrecedingRows) ListItemDefaults.SegmentedGap else 0.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Hair, Alignment.CenterHorizontally),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                .padding(horizontal = Spacing.Comfortable),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.tabular,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
        }
    }
}

/**
 * 队列的标题行:来源名 + 「X / N」位置胶囊,以及找相关、缓存、播放模式三个动作。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueHeader(
    queue: QueueUiState,
    onOpenQueueSource: (QueueSource) -> Unit,
    onCycleEndMode: () -> Unit,
    onCycleOrder: () -> Unit,
    onFindRelated: (() -> Unit)?,
    onCache: () -> Unit,
) {
    val onTitleClick = queue.source?.let { source -> { onOpenQueueSource(source) } }
    val resolvedLabel = queue.sourceLabel.ifBlank { stringResource(Res.string.listen_queue_title) }
    val posText = queue.state?.takeIf { !queue.enriching }?.let { QueuePositionText(it) }
    val edgeLoading = queue.state?.let { it.loadingBefore || it.loadingAfter } == true

    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                modifier = Modifier
                    .heightIn(min = Dimens.MinTouchTarget)
                    .then(
                        if (onTitleClick != null) {
                            Modifier
                                .clip(MaterialTheme.shapes.small)
                                .clickable(role = Role.Button, onClick = onTitleClick)
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = Spacing.Tight),
            ) {
                Text(
                    text = resolvedLabel,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (onTitleClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                }
                if (!posText.isNullOrBlank()) {
                    QueuePositionBadge(text = posText)
                }
                if (edgeLoading) {
                    LoadingSpinner()
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onFindRelated != null) {
                    IconButton(onClick = onFindRelated) {
                        Icon(
                            Icons.Filled.AutoAwesome,
                            contentDescription = stringResource(Res.string.video_find_related),
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                    }
                }
                IconButton(onClick = onCache) {
                    Icon(
                        Icons.Outlined.DownloadForOffline,
                        contentDescription = stringResource(Res.string.offline_cache_action),
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                }
            }
            QueuePlaybackControlsRow(
                endMode = queue.endMode,
                order = queue.order,
                onCycleEndMode = onCycleEndMode,
                onCycleOrder = onCycleOrder,
            )
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val iconsPlaceable = measurables[1].measure(loose)
        val controlsPlaceable = measurables[2].measure(loose)
        val gapPx = Spacing.Tight.roundToPx()
        val minTouchPx = Dimens.MinTouchTarget.roundToPx()
        val minInlineTitlePx = (if (!posText.isNullOrBlank()) {
            QueueInlineTitleWithBadgeMinWidth
        } else {
            QueueInlineTitleMinWidth
        }).roundToPx()
        val titleNaturalWidth = measurables[0].maxIntrinsicWidth(Constraints.Infinity)
        val requiredTitleWidth = minOf(titleNaturalWidth, minInlineTitlePx)
        val trailingInlineWidth = iconsPlaceable.width + controlsPlaceable.width
        val singleRow = requiredTitleWidth + gapPx + trailingInlineWidth <= constraints.maxWidth

        if (singleRow) {
            val titleMaxWidth = (constraints.maxWidth - gapPx - trailingInlineWidth).coerceAtLeast(0)
            val titlePlaceable = measurables[0].measure(loose.copy(maxWidth = titleMaxWidth))
            val height = maxOf(minTouchPx, titlePlaceable.height, iconsPlaceable.height, controlsPlaceable.height)
            layout(constraints.maxWidth, height) {
                titlePlaceable.placeRelative(0, (height - titlePlaceable.height) / 2)
                val controlsX = constraints.maxWidth - controlsPlaceable.width
                val iconsX = controlsX - iconsPlaceable.width
                iconsPlaceable.placeRelative(iconsX, (height - iconsPlaceable.height) / 2)
                controlsPlaceable.placeRelative(controlsX, (height - controlsPlaceable.height) / 2)
            }
        } else {
            val titleMaxWidth = (constraints.maxWidth - gapPx - iconsPlaceable.width).coerceAtLeast(0)
            val titlePlaceable = measurables[0].measure(loose.copy(maxWidth = titleMaxWidth))
            val row1Height = maxOf(minTouchPx, titlePlaceable.height, iconsPlaceable.height)
            val totalHeight = row1Height + controlsPlaceable.height
            layout(constraints.maxWidth, totalHeight) {
                titlePlaceable.placeRelative(0, (row1Height - titlePlaceable.height) / 2)
                iconsPlaceable.placeRelative(
                    constraints.maxWidth - iconsPlaceable.width,
                    (row1Height - iconsPlaceable.height) / 2,
                )
                controlsPlaceable.placeRelative(
                    (constraints.maxWidth - controlsPlaceable.width).coerceAtLeast(0),
                    row1Height,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueuePositionBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = CircleShape,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmallEmphasized.tabular,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(
                horizontal = Spacing.Tight,
                vertical = QueueBadgeVerticalPadding,
            ),
        )
    }
}

/** 标题与动作按钮挤在同一行时,为长合集标题保留的最小宽度;带「X / N」位置胶囊时放宽,避免挤扁。 */
private val QueueInlineTitleMinWidth = 84.dp
private val QueueInlineTitleWithBadgeMinWidth = 132.dp
private val QueueBadgeVerticalPadding = 2.dp
private val MetadataBadgeVerticalPadding = 2.dp
private val CopyrightIconSize = 14.dp
private val UpCardVerticalPadding = 10.dp
private const val ActionPressScale = 0.95f
private const val ActionPopScale = 1.06f
private const val ActionPopMillis = 140L

private fun formatDate(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

private fun formatFullTimestamp(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/** 联合投稿一排里每一格的宽度。约五个汉字的名字放得下,再长截断。 */
private val StaffCellWidth = 72.dp

/** 标签的高度,照 M3 chip。 */
private val TagHeight = 32.dp

/**
 * 简介页里标题、UP 行、动作栏、分 P 这几块之间的距离。块内的行距是 8dp,块间再宽一档,
 * 几块才分得出组。
 */
private val IntroBlockGap = 12.dp

/**
 * 后面紧跟小节标题(分 P、队列)的块,下间距只留这么多。小节标题行被右边的按钮撑到 48dp 高,
 * 文字居中,上下本来就各空出十几 dp;再叠一个 [IntroBlockGap],块与标题之间就空得比块与块之间
 * 还大。
 */
private val BeforeSectionHeaderGap = 4.dp

/** 单个 UP 那一行的头像。比联合投稿那一排大一档:这里只有一个人,名字也升到了 titleSmall。 */
private val UpAvatarSize = 40.dp

/** 分 P 横排里一格的宽度。两行 bodySmall 标题各放得下七八个字。 */
private val PartCardWidth = 128.dp

/** 分 P 编号标记的左右内边距。行内占位的宽度按它算,两处必须同一个数。 */
private val PartTagPadding = 4.dp

/** 编号标记与标题之间的距离。 */
private val PartTagGap = 6.dp

private const val PartTagId = "partTag"

/** 动作栏有几格。 */
private const val ActionCount = 5

/** 横排分段(动作栏、分 P)格与格之间的缝,与队列分段(ListItemDefaults.SegmentedGap)同宽。 */
private val ActionSegmentGap = 2.dp

/**
 * 横排分段(动作栏、分 P)第 [index] 格的外形:整排首尾那一侧 16dp,其余 4dp,和 M3 Expressive
 * 分段列表同一套圆角(lists.md:外侧 16dp、内侧 4dp),只是横过来。
 */
private fun horizontalSegmentShape(index: Int, count: Int): Shape {
    val start = if (index == 0) ActionOuterCorner else ActionInnerCorner
    val end = if (index == count - 1) ActionOuterCorner else ActionInnerCorner
    return RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end)
}

private val ActionOuterCorner = 16.dp
private val ActionInnerCorner = 4.dp

/** 页内队列在当前项前后各摊几条。其余在完整队列里,由段尾的「查看全部」打开。 */
private const val InlineQueueRadius = 2
