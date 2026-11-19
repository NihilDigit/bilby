package dev.bilby.ui.listen

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import dev.bilby.ui.CaptionDragHeight
import dev.bilby.ui.LocalWindowCaption
import dev.bilby.ui.rememberCaptionSlot
import dev.bilby.ui.windowDragArea
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Comment
import androidx.compose.runtime.saveable.rememberSaveable
import dev.bilby.resources.video_tab_comment
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.player.QueuePlaybackControls
import dev.bilby.ui.player.SleepTimerStandaloneContent
import dev.bilby.ui.player.computeItemRemainingMillis
import dev.bilby.ui.player.sleepTimerLabel
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import dev.bilby.ui.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.text.TextStyle
import dev.bilby.ui.theme.rememberFlexFont
import dev.bilby.ui.theme.ReadoutAxes
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.bilby.player.PlayerHandle
import dev.bilby.player.PlayerListener
import dev.bilby.ui.LocalPlaybackHost
import dev.bilby.ui.LocalWindowVisible
import dev.bilby.resources.*
import dev.bilby.ui.player.EpisodeList
import dev.bilby.ui.player.EpisodeTarget
import dev.bilby.ui.player.playOrReplay
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import dev.bilby.ui.components.LocalEditableFocusTracker
import dev.bilby.ui.player.PlayerVolumeControl
import dev.bilby.ui.video.PlayerSettingsContent
import dev.bilby.data.QueueSource
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.SectionHeader
import dev.bilby.ui.video.QueueUiState
import dev.bilby.formatDurationMillis
import dev.bilby.player.AudioPlaybackUiState
import dev.bilby.player.QueueItem
import dev.bilby.player.SleepTimerMode
import dev.bilby.player.SleepTimerState
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.indexNear
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.components.SubtitleTrackMenu
import dev.bilby.ui.components.VideoCover
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.components.panelCard
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.onLongClick
import dev.bilby.player.QueueState
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.ListScrollbar
import dev.bilby.ui.components.LoadingSpinner
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.rememberCopyAction
import dev.bilby.ui.player.QueuePositionText
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.rememberReducedMotion
import dev.bilby.ui.theme.tabular
import dev.bilby.ui.video.PlayerSettingsSection
import kotlin.math.exp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 唱片占「剩余空间」的比例(取宽高中较小的一边)。留一点边距而不是顶满,
 * 不然唱片贴着两侧/上下边缘,看起来像裁切出了问题而不是有意为之。
 */
private const val DiscSizeFraction = 0.84f

/** 封面缩到盘面正中当标签纸,外面留出来的一圈才是黑胶盘体。 */
private const val DiscLabelFraction = 0.58f

/** 轴孔占整张唱片的比例。 */
private const val DiscHoleFraction = 0.05f

private const val DiscGrooveCount = 4
private const val DiscGrooveWidthPx = 1.2f

/** 转一圈的时长。真唱片是 33⅓ 转/分,约 1.8 秒一圈——那个速度在手机上像个转盘动画,放慢到这里。 */
private const val DiscRotationPeriodMillis = 36_000

/** 唱片前台旋转节流间隔（~30fps）：10°/s 慢速旋转无需在 120–240Hz 屏上每帧重绘，休眠期间不挂 vsync。 */
private const val DiscFrameDelayMillis = 33L
private const val DiscMinFrameIntervalNanos = 33_000_000L

/** 歌词模式顶栏里那张封面的宽度。高度由 16:10 定,见风格指南 §1.3b。 */
private val LyricsHeaderCoverWidth = 88.dp

/** 上一条 / 下一条的图标尺寸。比控制条上的图标按钮大一档 —— 它们是这一屏的主要操作之一。 */
private val SkipIconSize = 28.dp

/** 倍速、定时那两枚 chip 里的图标,和视频控制条上的 chip 同一档。 */
private val ChipIconSize = 18.dp

/** 片头虚拟空行的 key。cue 的 key 是 `fromMillis`(Long),这里用字符串不会撞上。 */
private const val LeadInKey = "lyrics_lead_in"

/**
 * 队列把手收起时的高度:系统默认抓手 + 顺序切换那一行 + **露出小半条视频**。
 *
 * 原先是 72dp,刚好卡在「队列 · N」那行标签下沿 —— 收起时看到的是一行字,而一行字要读了
 * 才知道下面是什么。露出半条封面和标题不用读:它自己就说明了下面是一列视频,顺带也说明了
 * 它能拉。删掉那行标签腾出来的高度正好给这件事。
 */
private val QueueHandleHeight = 132.dp

/** 宽屏右栏的宽度:队列条目的封面加两行标题排得开,同空间页动态侧栏的默认宽。 */
private val QueuePaneWidth = 400.dp

/**
 * 歌词页单独的位置轮询间隔。整页其余部分(进度条、控制行)用的是 500ms
 * (见 `ListenScreen` 里那个 `LaunchedEffect(player)`),那是给数字和进度条挑的粒度;
 * 歌词逐句切换,500ms 的量化会让句子切换晚半秒且晚多久不固定,是"跳一下"的来源之一。
 * 100ms 对最短也有一两秒的句子够用,不需要拉到帧级——帧级驱动是弹幕那种场景才要的。
 */
private const val LyricsPollIntervalMillis = 100L

/**
 * 唱片下面那个大数字:放到哪了。**这一页没有画面,除了唱片之外能看的就是这个数。**
 *
 * - 位置在标题和 UP 名之下、进度条之上,居中:它和进度条说的是同一件事,挨着放。
 *   总时长也写在这里("1:35 / 2:28"),进度条两端不再有字。
 * - 字体是可变的 Roboto Flex([rememberFlexFont]),"Flex" 字样那种黑体块:字重顶满、
 *   字宽拉满,笔画再加粗、字腔收窄,读起来像一块计数牌而不是一行字。
 *   一按住唱片或进度条就染主题色 —— "你正攥着它";松手变回去。字形不跟着变:它平时已经
 *   是最黑的那一档附近,按住再变黑看不出来,只让整块数字晃一下。
 *   `h:mm:ss` 比 `mm:ss` 长,不缩字号,而是把字宽轴收回一些。
 * - 数字等宽(`tnum`):秒数每跳一下,不等宽的数字会让整行左右抖。
 * - 取消区里数字不换,只退成次要色;"松手取消进退"浮在唱片上([CancelPill]),不占这一行。
 */
@Stable
private class ListenProgressState {
    var positionMillis by mutableLongStateOf(0L)
    var bufferedPositionMillis by mutableLongStateOf(0L)
    var durationMillis by mutableLongStateOf(0L)
    var dragPositionMillis by mutableStateOf<Long?>(null)
    var scrubCancelArmed by mutableStateOf(false)

    val displayPositionMillis: Long get() = dragPositionMillis ?: positionMillis
    val isScrubbing: Boolean get() = dragPositionMillis != null
}

@Composable
private fun BoundTimeReadout(progress: ListenProgressState, modifier: Modifier = Modifier) {
    TimeReadout(
        positionMillis = progress.displayPositionMillis,
        durationMillis = progress.durationMillis,
        active = progress.isScrubbing,
        cancelArmed = progress.scrubCancelArmed,
        modifier = modifier,
    )
}

@Composable
private fun BoundListenSeekBar(
    progress: ListenProgressState,
    playing: Boolean,
    speed: Float,
    onSeekStart: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SeekBar(
        position = progress.displayPositionMillis,
        duration = progress.durationMillis,
        bufferedPosition = progress.bufferedPositionMillis,
        onSeekStart = onSeekStart,
        onSeekTo = onSeekTo,
        onSeekFinished = onSeekFinished,
        wavy = true,
        playing = playing,
        speed = speed,
        timeBubble = false,
        modifier = modifier,
    )
}

@Composable
private fun BoundLyricsTimeRow(progress: ListenProgressState, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        val timeAutoSize = TextAutoSize.StepBased(
            minFontSize = 9.sp,
            maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
        )
        Text(
            text = formatDurationMillis(progress.displayPositionMillis),
            style = MaterialTheme.typography.labelMedium.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            autoSize = timeAutoSize,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = formatDurationMillis(progress.durationMillis),
            style = MaterialTheme.typography.labelMedium.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            autoSize = timeAutoSize,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TimeReadout(
    positionMillis: Long,
    durationMillis: Long,
    active: Boolean,
    cancelArmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val colorSpec = MaterialTheme.motionScheme.fastEffectsSpec<Color>()
    val text = formatDurationMillis(positionMillis)
    val totalText = if (durationMillis > 0L) formatDurationMillis(durationMillis) else ""
    val width by animateFloatAsState(
        if (maxOf(text.length, totalText.length) > ShortTimeLength) ReadoutWidthLong else ReadoutWidthNormal,
        spec,
        label = "readout-width",
    )
    // 取消区里退成次要色:这个数此刻不会生效,松手就回到原处。
    val color by animateColorAsState(
        when {
            cancelArmed -> MaterialTheme.colorScheme.onSurfaceVariant
            active -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = colorSpec,
        label = "readout-color",
    )
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        ReadoutText(text, totalText, width, color)
    }
}

/**
 * 取消区里浮出来的那一句:图标加字,装在 errorContainer 的胶囊里,**浮在唱片正中,不占布局**。
 *
 * 和视频播放器横划取消同一种说法(叉号加"松手取消进退",见 PlayerHudOverlay)。试过两种
 * 占位的写法:把大数字换成一行红字,红字比数字高,进出取消区时整页上下跳;红字本身浮在一片
 * 空白里,既不像提示也不像按钮。浮在唱片上时手指正离开唱片,眼睛还在那儿。
 */
@Composable
private fun CancelPill(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = CircleShape,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(ChipIconSize))
            Text(
                stringResource(Res.string.player_seek_release_to_cancel),
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ReadoutText(text: String, totalText: String, width: Float, color: Color) {
    // 总时长跟在后面,同一个字体实例,只是小一号、次要色:它一起加粗一起收窄,
    // 读起来是这块计数牌的下半句,而不是另一处的另一个数。
    val totalColor = MaterialTheme.colorScheme.onSurfaceVariant
    val annotated = remember(text, totalText, totalColor) {
        buildAnnotatedString {
            append(text)
            if (totalText.isNotEmpty()) {
                withStyle(SpanStyle(fontSize = ReadoutTotalSize, color = totalColor)) {
                    append(" / ")
                    append(totalText)
                }
            }
        }
    }
    Text(
        annotated,
        style = TextStyle(
            fontFamily = rememberFlexFont(ReadoutWeight, width, ReadoutFigureHeight, ReadoutInk),
            fontSize = ReadoutSize,
            lineHeight = ReadoutLineHeight,
            fontFeatureSettings = "tnum",
            textAlign = TextAlign.Center,
        ),
        color = color,
        maxLines = 1,
    )
}

/** 取消提示出现时从这个比例放大到原大,和播放器中央按钮一样的进场。 */
private const val CancelPillEnterScale = 0.8f

private val ReadoutSize = 44.sp
private val ReadoutTotalSize = 22.sp
/**
 * 比字号小:数字只有字号的六成多高,按字号给行高,上下各空出一截,数字和进度条之间就隔着
 * 一段看不见的行距。字形不会被裁,Text 不裁剪越出行框的部分。
 */
private val ReadoutLineHeight = 34.sp
// 轴值见 ReadoutAxes。
private const val ReadoutWeight = ReadoutAxes.Weight
private const val ReadoutInk = ReadoutAxes.Ink
private const val ReadoutWidthNormal = ReadoutAxes.Width
private const val ReadoutWidthLong = ReadoutAxes.WidthLong
private const val ReadoutFigureHeight = ReadoutAxes.FigureHeight
/** "mm:ss" 的长度。更长就是 "h:mm:ss",收窄字宽。 */
private const val ShortTimeLength = 5

/**
 * 往上挪 [amount],对外报的高度同样减掉这么多:压住上一个兄弟的底边,下面的东西跟着上来。
 * 不用 `offset`:它只挪画的位置,量出来的高度不变,下面照样空着那一截。越出报告尺寸的
 * 那一截仍然接得到触摸,排在后面的兄弟画在上层,所以被压住的一方要是那个不接触摸的。
 */
private fun Modifier.pullUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val px = amount.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height - px).coerceAtLeast(0)) { placeable.place(0, -px) }
}

/** 进度条压进大数字行框的量:数字行框下半截和进度条触控区上半截都是空的。 */
private val SeekBarOverlapAbove = 8.dp
/** 控制行压进进度条触控区下半截的量。 */
private val ControlsOverlapDisc = 16.dp
private val ControlsOverlapLyrics = 6.dp

/** 转唱片的取消区:离圆心超过半径的这么多倍。 */
private const val CancelRadiusRatio = 1.5f

/** 拖进度条时唱片跟转的换算起点,见 DiscView。 */
private class BarScrub {
    var active = false
    var baseAngle = 0f
    var restPosition = 0L
}

/** 这个向量指向的角度,度数,屏幕坐标下顺时针为正。 */
private fun Offset.degrees(): Float = Math.toDegrees(kotlin.math.atan2(y, x).toDouble()).toFloat()

private fun listenDigitIndex(key: Key): Int? = when (key) {
    Key.Zero, Key.NumPad0 -> 0
    Key.One, Key.NumPad1 -> 1
    Key.Two, Key.NumPad2 -> 2
    Key.Three, Key.NumPad3 -> 3
    Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5
    Key.Six, Key.NumPad6 -> 6
    Key.Seven, Key.NumPad7 -> 7
    Key.Eight, Key.NumPad8 -> 8
    Key.Nine, Key.NumPad9 -> 9
    else -> null
}

/**
 * 听视频界面。播放器归 [dev.bilby.player.AudioPlaybackService] 所有,这里只读状态、发命令
 * (DESIGN 2.4b「一个播放状态,两个 UI」)——不 prepare、不 release,和看视频共用同一个播放器。
 *
 * 比看视频少两样:不渲染画面、不能调画质,其余(队列、连播、顺序/随机、进度、倍速)是同一套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(
    player: PlayerHandle?,
    state: AudioPlaybackUiState,
    sleepTimer: SleepTimerState,
    /**
     * 播放队列,**和详情页、全屏是同一份** `QueueUiState`(构造点在 `VideoScreen`)。
     *
     * 原先这里只拿切集清单、续取和随机开关三样,于是完整队列还在建的时候,起播时那一条
     * 临时占位被当成整份队列摆出来,建失败也没有重试;标题只能写「播放队列」,不知道放的是
     * 哪份收藏夹、哪个合集。整份传进来,这几件事和详情页同一个判据。
     *
     * 分 P 摊在当前那条底下(见 [dev.bilby.ui.player.EpisodeList]):摆成两块时"走到第几条"
     * 和"放到第几 P"是两个高亮,人要在两块之间对位。
     */
    queue: QueueUiState,
    /** 正在放还是停着,决定队列里当前那条的指示跳不跳。 */
    playing: Boolean,
    onSelectEpisode: (EpisodeTarget) -> Unit,
    /** 完整队列没建成时重试。 */
    onRetryQueue: () -> Unit,
    /** 点队列标题进来源的目录(合集、系列)。 */
    onOpenQueueSource: (QueueSource) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onCycleEndMode: () -> Unit = {},
    onCycleOrder: () -> Unit,
    /** 三选一,见 [dev.bilby.player.SleepTimerMode]。 */
    onSleepTimer: (SleepTimerMode) -> Unit,
    /** 在正在走的定时上加减几分钟。 */
    onSleepTimerAdjust: (Int) -> Unit,
    /** 倍速面板里的音质一段,见 [PlayerSettingsContent]。 */
    onAudioQualityChange: (Int) -> Unit = {},
    /** 手动重试当前这条。退避耗尽之后由用户决定是再试还是按下一条跳过。 */
    onRetry: () -> Unit,
    onBack: () -> Unit,
    /** 这条(cid)有哪些字幕轨,含 AI 生成的;和看视频共用同一份 VideoViewModel 状态。 */
    subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** 选中轨的语言代码,空字符串是关(默认)。 */
    subtitleLan: String = "",
    onSelectSubtitle: (String) -> Unit = {},
    /** 选中轨的正文,按 fromMillis 升序;没选字幕时是空列表。有正文才能翻进歌词页。 */
    subtitleCues: List<SubtitleCue> = emptyList(),
    /** 这条视频的评论区,和简介/评论的评论页是同一份(见 VideoScreen 的 commentsPane)。 */
    comments: (@Composable (Modifier) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val progress = remember { ListenProgressState() }
    var commentsOpen by rememberSaveable { mutableStateOf(false) }
    var resumeAfterDrag by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var sleepSheetOpen by remember { mutableStateOf(false) }
    val playback = LocalPlaybackHost.current
    val windowVisible = LocalWindowVisible.current

    // Media3 的 Player 没有位置回调,只能轮询;和 BilbyPlayer 一致的 500ms 间隔。
    // 写入 ListenProgressState，仅由最末端读数/进度条叶子读取，避免顶层每 500ms 或拖动时 60fps 重组。
    // 窗口不可见时暂停轮询，恢复可见时立即刷新一次。
    LaunchedEffect(player, windowVisible) {
        if (!windowVisible) return@LaunchedEffect
        while (true) {
            if (progress.dragPositionMillis == null) progress.positionMillis = player?.currentPosition ?: 0L
            progress.bufferedPositionMillis = player?.bufferedPosition ?: 0L
            progress.durationMillis = player?.duration?.coerceAtLeast(0) ?: 0L
            delay(500)
        }
    }

    DisposableEffect(player) {
        val listener = object : PlayerListener {
            @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE")
            override fun onPlaybackSpeedChanged(newSpeed: Float) {
                speed = newSpeed
            }
        }
        player?.let {
            speed = it.playbackSpeed
            it.addListener(listener)
        }
        onDispose { player?.removeListener(listener) }
    }

    // 拖动进度的三步,进度条和唱片共用(见 DiscView):按下先停,拖动只改显示的位置,松手才真
    // seek —— 每帧 seek 会让播放器不停丢缓冲重新起播。
    val seekStart: () -> Unit = {
        resumeAfterDrag = player?.isPlaying == true
        player?.pause()
    }
    val seekTo: (Long) -> Unit = { progress.dragPositionMillis = it }
    val seekFinished: () -> Unit = {
        progress.dragPositionMillis?.let { target ->
            player?.seekTo(target)
            progress.positionMillis = target
            if (resumeAfterDrag) player?.play()
        }
        progress.dragPositionMillis = null
    }

    /** 转唱片时手指离开盘面够远:松手就不 seek。和播放器横划进退的取消区是同一件事。 */
    val seekCancelled: () -> Unit = {
        progress.dragPositionMillis = null
        progress.scrubCancelArmed = false
        if (resumeAfterDrag) player?.play()
    }

    // **歌词是这一页的一个状态,不是另一页;而且这个状态是推导出来的,不另存一份。**
    // 入口只有唱片右上角那个字幕按钮:选中一条轨就进歌词,选「无字幕」就回唱片。
    val showLyrics = subtitleLan.isNotEmpty() && subtitleCues.isNotEmpty()
    val editableFocus = LocalEditableFocusTracker.current
    val listenFocusRequester = remember { FocusRequester() }
    LaunchedEffect(player) {
        if (player != null && !editableFocus.isEditableFocused) {
            runCatching { listenFocusRequester.requestFocus() }
        }
    }

    // 必须自己给顶栏:听视频是播放页内的一个状态,VideoScreen 在这条分支上提前 return,
    // 外层那句 windowInsetsPadding(statusBars) 走不到 —— 不处理的话标题和返回箭头
    // 会直接压在状态栏的时钟上。用 Scaffold 而不是手贴 padding,和其余页面一致。
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || editableFocus.isEditableFocused) return@onKeyEvent false
                if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return@onKeyEvent false
                val activePlayer = player ?: return@onKeyEvent false
                val currentDur = progress.durationMillis
                val digit = listenDigitIndex(event.key)
                if (digit != null) {
                    if (event.isShiftPressed || currentDur <= 0L) return@onKeyEvent false
                    val target = (currentDur * digit / 10L).coerceIn(0L, currentDur)
                    activePlayer.seekTo(target)
                    progress.positionMillis = target
                    return@onKeyEvent true
                }
                when (event.key) {
                    Key.Spacebar -> {
                        if (activePlayer.playWhenReady) activePlayer.pause() else activePlayer.playOrReplay(playback)
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val step = if (event.isShiftPressed) 60_000L else 10_000L
                        val delta = if (event.key == Key.DirectionLeft) -step else step
                        val target = if (currentDur > 0L) {
                            (progress.positionMillis + delta).coerceIn(0L, currentDur)
                        } else {
                            (progress.positionMillis + delta).coerceAtLeast(0L)
                        }
                        activePlayer.seekTo(target)
                        progress.positionMillis = target
                    }
                    Key.DirectionUp -> {
                        if (!playback.hasSoftwareVolume) return@onKeyEvent false
                        playback.setSoftwareVolume((playback.softwareVolume.value + 0.05f).coerceIn(0f, 1f))
                    }
                    Key.DirectionDown -> {
                        if (!playback.hasSoftwareVolume) return@onKeyEvent false
                        playback.setSoftwareVolume((playback.softwareVolume.value - 0.05f).coerceIn(0f, 1f))
                    }
                    Key.M -> {
                        if (!playback.hasSoftwareVolume) return@onKeyEvent false
                        playback.toggleSoftwareMute()
                    }
                    Key.LeftBracket -> {
                        if (state.queue?.canPrevious != true) return@onKeyEvent false
                        onPrevious()
                    }
                    Key.RightBracket -> {
                        if (state.queue?.canNext != true) return@onKeyEvent false
                        onNext()
                    }
                    else -> return@onKeyEvent false
                }
                true
            }
            .focusRequester(listenFocusRequester)
            .focusable(),
        // **两个模式的顶栏不一样,因为它们缺的东西不一样。**
        //
        // 唱片模式只要一个退出按钮:那一屏自己说明了在干什么(一张转着的唱片),在听哪一条也
        // 写在唱片正下方,顶栏再写「听视频」是把画面已经说清的事又说一遍。
        //
        // 歌词模式要封面 + 标题:整屏只剩歌词,没有任何地方写着这是哪一条。
        //
        // **字幕按钮在顶栏的操作位,两个模式同一处。** 它原先浮在唱片/歌词区的右上角,而歌词
        // 模式的顶栏比唱片模式高一截,一选字幕、标题一出来,按钮就被顶栏往下压一段。放进顶栏
        // 并贴顶对齐(见 [LyricsHeader])之后,两种顶栏里它的位置分毫不差。
        topBar = {
            val item = state.queue?.current
            val barActions: @Composable () -> Unit = {
                PlayerVolumeControl(
                    surfaceTone = true,
                    modifier = Modifier.padding(end = Spacing.Hair),
                )
                if (comments != null) {
                    val commentLabel = stringResource(Res.string.video_tab_comment)
                    BilbyTooltip(text = commentLabel) {
                        IconButton(onClick = { commentsOpen = true }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Comment,
                                contentDescription = commentLabel,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                SubtitleBarButton(
                    tracks = subtitleTracks,
                    currentLan = subtitleLan,
                    lyricsShown = showLyrics,
                    onSelect = onSelectSubtitle,
                )
            }
            if (showLyrics && item != null) {
                LyricsHeader(item = item, onBack = onBack, trailing = barActions)
            } else {
                BilbyTopBar(title = "", onBack = onBack, actions = { barActions() })
            }
        },
    ) { insets ->
        if (player == null || state.queue?.current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(insets).padding(Spacing.Spacious),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    state.loading -> FullScreenLoading()
                    state.error != null -> Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    ) {
                        Text(
                            text = state.error,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = onRetry) {
                            Text(stringResource(Res.string.action_retry))
                        }
                    }
                    else -> Text(
                        stringResource(Res.string.listen_not_playing),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            return@Scaffold
        }

        // 队列没建过时不给——一个空把手拉起来什么都没有,是纯噪声。建过之后常显,不需要再点
        // 一行入口才能看到它。建队列中与建失败也露出来:标题那一行正是说明这两件事的地方。
        val hasQueue = queue.rows.isNotEmpty() || queue.enriching || queue.incomplete
        val windowSize = rememberBilbyWindowSize()
        val density = LocalDensity.current
        val containerHeightPx = LocalWindowInfo.current.containerSize.height
        val isHeightCompact = containerHeightPx > 0 && containerHeightPx < with(density) { 480.dp.roundToPx() }
        val expandedWindow = windowSize.isAtLeast(BilbyWindowSize.Expanded) ||
            (windowSize == BilbyWindowSize.Medium && isHeightCompact)
        val sheetState = rememberBottomSheetScaffoldState()
        val sheetScope = rememberCoroutineScope()
        val toggleQueueSheet: () -> Unit = {
            sheetScope.launch {
                if (sheetState.bottomSheetState.currentValue == SheetValue.Expanded) {
                    sheetState.bottomSheetState.partialExpand()
                } else {
                    sheetState.bottomSheetState.expand()
                }
            }
        }
        val queueContent: @Composable (fillHeight: Boolean, onToggleSheet: (() -> Unit)?) -> Unit =
            { fillHeight, onToggleSheet ->
                QueueSheetContent(
                    queue = queue,
                    queueState = queue.state ?: state.queue,
                    playing = playing,
                    sheetExpanded = sheetState.bottomSheetState.targetValue == SheetValue.Expanded,
                    onToggleSheet = onToggleSheet,
                    onCycleEndMode = onCycleEndMode,
                    onCycleOrder = onCycleOrder,
                    onSelectEpisode = onSelectEpisode,
                    onRetryQueue = onRetryQueue,
                    onOpenQueueSource = onOpenQueueSource,
                    fillHeight = fillHeight,
                )
            }

        // 正在放的那一列:唱片或歌词、读数、进度条、控制行。窄屏和宽屏是同一份,只是外面不同。
        val nowPlaying: @Composable (Modifier) -> Unit = { columnModifier ->
                Column(modifier = columnModifier) {
                // 唱片(或歌词)吃掉除控制区之外的全部空间;没有字幕时这里就是空间的唯一主角,
                // 不用再另外做"整体居中"的特判——weight(1f) 本身就把它撑满了。
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (showLyrics) {
                        LyricsView(
                            cues = subtitleCues,
                            player = player,
                            dragPosition = { progress.dragPositionMillis },
                            onSeekTo = { player.seekTo(it) },
                            // 退出歌词就是**选中「无字幕」**,不是另一个动作。
                            onBack = { onSelectSubtitle("") },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        DiscView(
                            state = state,
                            queue = queue,
                            onOpenQueueSource = onOpenQueueSource,
                            // 双栏模式下右侧队列标题已常驻展示来源与位置，左侧唱片下方省略重复胶囊。
                            showSourcePill = !(expandedWindow && hasQueue),
                            speed = speed,
                            progress = progress,
                            onScrubStart = seekStart,
                            onScrub = seekTo,
                            onScrubEnd = seekFinished,
                            onScrubCancelArmed = { progress.scrubCancelArmed = it },
                            onScrubCancel = seekCancelled,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // 进度条 + 控制行贴底,不跟着有没有字幕挪位置——两种呈现共用同一段。
                if (!showLyrics) {
                    BoundTimeReadout(
                        progress = progress,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.Comfortable),
                    )
                }
                Box(
                    modifier = Modifier
                        .then(if (showLyrics) Modifier else Modifier.pullUp(SeekBarOverlapAbove))
                        .padding(horizontal = Spacing.Comfortable),
                ) {
                    // 和播放器用同一个进度条组件:两处的拖拽语义完全一样,分开写迟早各自漂移。
                    // 窗口不可见时传 false 以自动摘除波浪进度条的 rememberInfiniteTransition。
                    BoundListenSeekBar(
                        progress = progress,
                        playing = state.isPlaying && windowVisible,
                        speed = speed,
                        onSeekStart = seekStart,
                        onSeekTo = seekTo,
                        onSeekFinished = seekFinished,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showLyrics) {
                        BoundLyricsTimeRow(
                            progress = progress,
                            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                        )
                    }
                }

                PlaybackControls(
                    // 歌词模式下进度条底下叠着两端的时间,只能压得浅一点。
                    modifier = Modifier.pullUp(if (showLyrics) ControlsOverlapLyrics else ControlsOverlapDisc),
                    isPlaying = state.isPlaying,
                    loading = state.loading,
                    speed = speed,
                    hasPrevious = state.queue.canPrevious,
                    hasNext = state.queue.canNext,
                    sleepTimer = sleepTimer,
                    progress = progress,
                    onPlayPause = { if (player.playWhenReady) player.pause() else player.playOrReplay(playback) },
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSpeedChange = { player.setPlaybackSpeed(it) },
                    onOpenSleepTimer = { sleepSheetOpen = true },
                    audioOptions = state.playInfo?.streams?.audioOptions.orEmpty(),
                    currentAudio = state.playInfo?.streams?.audioId ?: 0,
                    onAudioChange = onAudioQualityChange,
                )

                // 失败就摆在控制条底下,不悄悄跳到下一条:跳过之后用户只看到"忽然换了一条",
                // 而原因一个字都没留下。跳还是再试由这里交回给用户。
                state.error?.let { message ->
                    FailureRow(message = message, retrying = state.loading, onRetry = onRetry)
                }

                }
        }

        if (expandedWindow) {
            // **宽屏或紧凑高度横屏两栏:左边正在放,右边队列常驻。**
            var twoPaneWidth by remember { mutableStateOf(0.dp) }
            val queuePaneWidth = if (twoPaneWidth > 0.dp) {
                (twoPaneWidth * 0.44f).coerceIn(280.dp, QueuePaneWidth)
            } else {
                QueuePaneWidth
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(insets)
                    .onSizeChanged { twoPaneWidth = with(density) { it.width.toDp() } },
            ) {
                AdaptiveContent(modifier = Modifier.weight(1f).fillMaxHeight(), maxWidth = Breakpoints.MediaWidth) {
                    nowPlaying(Modifier.fillMaxSize().padding(bottom = Spacing.Comfortable))
                }
                if (hasQueue) {
                    Box(
                        modifier = Modifier
                            .width(queuePaneWidth)
                            .fillMaxHeight()
                            .padding(end = Spacing.Comfortable, bottom = Spacing.Comfortable)
                            .panelCard()
                            .padding(top = Spacing.Tight),
                    ) {
                        queueContent(true, null)
                    }
                }
            }
        } else {
            // BottomSheetScaffold 不管返回键(ModalBottomSheet 才管),不拦的话展开队列之后一按
            // 返回,落到的是外面那句"退出听视频"。看 targetValue 而不是 currentValue:拉到一半
            // 松手、正往上弹的那一刻按返回,也该是收回。
            BackHandler(enabled = sheetState.bottomSheetState.targetValue == SheetValue.Expanded) {
                sheetScope.launch { sheetState.bottomSheetState.partialExpand() }
            }
            BottomSheetScaffold(
                scaffoldState = sheetState,
                modifier = Modifier.fillMaxSize().padding(insets),
                sheetPeekHeight = if (hasQueue) QueueHandleHeight else 0.dp,
                sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                sheetShape = RoundedCornerShape(
                    topStart = SheetTopCornerRadius,
                    topEnd = SheetTopCornerRadius,
                ),
                sheetDragHandle = if (hasQueue) {
                    {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = stringResource(Res.string.listen_queue_title),
                                    onClick = toggleQueueSheet,
                                )
                                .padding(top = Spacing.Cozy, bottom = Spacing.Hair),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = SheetDragHandleWidth, height = SheetDragHandleHeight)
                                    .background(
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        shape = CircleShape,
                                    ),
                            )
                        }
                    }
                } else {
                    null
                },
                sheetContent = {
                    AdaptiveContent(modifier = Modifier.fillMaxWidth(), maxWidth = Breakpoints.MediaWidth) {
                        queueContent(false, toggleQueueSheet)
                    }
                },
            ) { sheetInsets ->
                AdaptiveContent(modifier = Modifier.fillMaxSize(), maxWidth = Breakpoints.MediaWidth) {
                    nowPlaying(Modifier.fillMaxSize().padding(bottom = sheetInsets.calculateBottomPadding()))
                }
            }
        }
    }

    if (commentsOpen && comments != null) {
        PaneSheet(
            onDismissRequest = { commentsOpen = false },
            title = stringResource(Res.string.video_tab_comment),
            applyBottomInsetsWhenEmpty = false,
        ) {
            comments(Modifier.fillMaxWidth().weight(1f, fill = false))
        }
    }

    if (sleepSheetOpen) {
        SleepTimerDialog(
            sleepTimer = sleepTimer,
            itemRemainingMillis = computeItemRemainingMillis(progress.positionMillis, progress.durationMillis, speed),
            onSet = onSleepTimer,
            onAdjust = onSleepTimerAdjust,
            onDismiss = { sleepSheetOpen = false },
        )
    }
}

private const val DiscHaloAlpha = 0.11f
private const val DiscHaloRadiusRatio = 1.18f
private val SheetTopCornerRadius = 28.dp
private val SheetDragHandleWidth = 32.dp
private val SheetDragHandleHeight = 4.dp
private val QueueBadgeVerticalPadding = 2.dp

/**
 * 没有字幕、或还没点开歌词页时的默认呈现:圆形唱片居中 + 标题 + UP 名/队列来源与位置胶囊。
 *
 * 封面裁成圆形、center crop。**这条推翻了这里以前"保持封面比例、不裁方形"的结论**——
 * 旧结论成立的前提是封面上可能正好写着标题,裁掉等于丢信息;但标题在这一屏是单独渲染的
 * 真文字(下面这一行),封面在这里已经降格成纯装饰,被圆形裁掉的边不再独占任何信息,
 * 旧的反对理由不再成立。B 站封面原图是 16:10 宽幅,圆形只取正中间的正方形。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DiscView(
    state: AudioPlaybackUiState,
    queue: QueueUiState,
    onOpenQueueSource: (QueueSource) -> Unit,
    showSourcePill: Boolean,
    speed: Float,
    progress: ListenProgressState,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    /** 手指进出了取消区(离开盘面够远)。 */
    onScrubCancelArmed: (Boolean) -> Unit,
    /** 在取消区里松手:不 seek,一切回到按下之前。 */
    onScrubCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.queue?.current ?: return
    val reducedMotion = rememberReducedMotion()
    val windowVisible = LocalWindowVisible.current
    val density = LocalDensity.current
    val haloColor = MaterialTheme.colorScheme.primary.copy(alpha = DiscHaloAlpha)
    val scrubArcColor = MaterialTheme.colorScheme.primary
    val cancelArcColor = MaterialTheme.colorScheme.error
    val copyTitle = rememberCopyAction()
    val copyBvid = rememberCopyAction(
        clipLabel = stringResource(Res.string.video_bvid_clip_label),
        copiedMessage = stringResource(Res.string.video_bvid_copied),
    )
    val copyTitleLabel = stringResource(Res.string.action_copy_title)
    val copyBvidLabel = stringResource(Res.string.video_bvid_copy)
    val menuLabel = stringResource(Res.string.menu_item_actions)

    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                // 底边不留:标题和 UP 名下面紧接着就是大数字(排在这一块外面)。
                .padding(start = Spacing.Loose, end = Spacing.Loose, top = Spacing.Comfortable),
        ) {
            var discAreaMinDp by remember { mutableStateOf(0.dp) }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { size ->
                        discAreaMinDp = with(density) { minOf(size.width, size.height).toDp() }
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                val discSize = discAreaMinDp * DiscSizeFraction

                var discAngle by remember { mutableFloatStateOf(0f) }
                var discOmega by remember { mutableFloatStateOf(0f) }
                var scrubTurn by remember { mutableFloatStateOf(0f) }
                var scrubStartAngle by remember { mutableFloatStateOf(0f) }
                val scope = rememberCoroutineScope()
                var discGesture by remember { mutableStateOf(false) }
                val isScrubbing by remember(progress) { derivedStateOf { progress.isScrubbing } }
                val isCancelArmed by remember(progress) { derivedStateOf { progress.scrubCancelArmed } }

                val barScrub = remember { BarScrub() }
                LaunchedEffect(progress) {
                    snapshotFlow {
                        Triple(progress.isScrubbing, progress.displayPositionMillis, progress.durationMillis)
                    }.collect { (scrub, pos, dur) ->
                        if (scrub && !discGesture) {
                            if (!barScrub.active) {
                                barScrub.active = true
                                barScrub.baseAngle = discAngle
                            }
                            if (dur > 0L) {
                                val turn = (pos - barScrub.restPosition).toFloat() / dur * 360f
                                discAngle = barScrub.baseAngle + turn
                            }
                        } else if (!scrub) {
                            barScrub.active = false
                            barScrub.restPosition = pos
                        }
                    }
                }
                // 真实唱机转动惯性：播放启动平稳加速、暂停阻尼滑停至静止，reducedMotion 或窗口不可见时暂停并保留当前角度。
                LaunchedEffect(state.isPlaying, speed, isScrubbing, reducedMotion, windowVisible) {
                    if (reducedMotion || isScrubbing || !windowVisible) {
                        discOmega = 0f
                        return@LaunchedEffect
                    }
                    val targetOmega = if (state.isPlaying) {
                        360f / (DiscRotationPeriodMillis / 1000f) * speed.coerceAtLeast(0.1f)
                    } else {
                        0f
                    }
                    if (targetOmega == 0f && kotlin.math.abs(discOmega) < 0.05f) {
                        discOmega = 0f
                        return@LaunchedEffect
                    }
                    var lastNanos = 0L
                    while (true) {
                        val frameNanos = withFrameNanos { it }
                        if (lastNanos == 0L) {
                            lastNanos = frameNanos
                            delay(DiscFrameDelayMillis)
                            continue
                        }
                        val elapsedNanos = frameNanos - lastNanos
                        if (elapsedNanos < DiscMinFrameIntervalNanos) {
                            delay(DiscFrameDelayMillis)
                            continue
                        }
                        val dt = (elapsedNanos / 1_000_000_000f).coerceIn(0f, 0.1f)
                        lastNanos = frameNanos
                        val rate = if (targetOmega > discOmega) 7.5f else 4.0f
                        val alpha = 1f - exp(-rate * dt)
                        discOmega += (targetOmega - discOmega) * alpha
                        discAngle = (discAngle + discOmega * dt) % 360f
                        if (targetOmega == 0f && kotlin.math.abs(discOmega) < 0.05f) {
                            discOmega = 0f
                            break
                        }
                        delay(DiscFrameDelayMillis)
                    }
                }

                val cancelReturnSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
                if (discSize > 0.dp) Box(
                    modifier = Modifier
                        .size(discSize)
                        // 静态盘体托底微光（无呼吸动画）。
                        .drawBehind {
                            val r = size.minDimension / 2f
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val haloRadius = r * DiscHaloRadiusRatio
                            if (haloRadius > 0f) {
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            haloColor,
                                            haloColor.copy(alpha = DiscHaloAlpha * 0.36f),
                                            Color.Transparent,
                                        ),
                                        center = c,
                                        radius = haloRadius,
                                    ),
                                    radius = haloRadius,
                                    center = c,
                                )
                            }
                        }
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val dur = progress.durationMillis
                                if (dur <= 0L) return@awaitEachGesture
                                val center = Offset(size.width / 2f, size.height / 2f)
                                if ((down.position - center).getDistance() > minOf(size.width, size.height) / 2f) {
                                    return@awaitEachGesture
                                }
                                val first = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                    change.consume()
                                } ?: return@awaitEachGesture
                                val startPosition = progress.displayPositionMillis
                                var lastAngle = (first.position - center).degrees()
                                var turned = 0f
                                val cancelRadius = minOf(size.width, size.height) / 2f * CancelRadiusRatio
                                var cancelArmed = false
                                scrubStartAngle = lastAngle
                                discGesture = true
                                onScrubStart()
                                drag(first.id) { change ->
                                    val armed = (change.position - center).getDistance() > cancelRadius
                                    if (armed != cancelArmed) {
                                        cancelArmed = armed
                                        onScrubCancelArmed(armed)
                                    }
                                    val a = (change.position - center).degrees()
                                    var delta = a - lastAngle
                                    if (delta > 180f) delta -= 360f
                                    if (delta < -180f) delta += 360f
                                    lastAngle = a
                                    turned += delta
                                    scrubTurn = turned
                                    val target = startPosition + (turned / 360f * dur).toLong()
                                    onScrub(target.coerceIn(0L, dur))
                                    change.consume()
                                }
                                if (cancelArmed) {
                                    onScrubCancel()
                                    scope.launch {
                                        if (reducedMotion) {
                                            scrubTurn = 0f
                                        } else {
                                            Animatable(scrubTurn).animateTo(0f, cancelReturnSpec) {
                                                scrubTurn = value
                                            }
                                        }
                                        discGesture = false
                                    }
                                } else {
                                    onScrubEnd()
                                    scope.launch {
                                        discAngle = (discAngle + scrubTurn) % 360f
                                        scrubTurn = 0f
                                        discGesture = false
                                    }
                                }
                            }
                        },
                ) {
                    // 盘体、同心纹路、轴孔。保持 100% CircleShape 真黑胶唱片。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = discAngle + scrubTurn }
                            .drawBehind {
                                val r = size.minDimension / 2f
                                val c = Offset(size.width / 2f, size.height / 2f)
                                drawCircle(FixedColors.VinylBody, radius = r, center = c)
                                val inner = r * DiscLabelFraction
                                repeat(DiscGrooveCount) { i ->
                                    val t = (i + 1f) / (DiscGrooveCount + 1f)
                                    drawCircle(
                                        color = FixedColors.VinylGroove,
                                        radius = inner + (r - inner) * t,
                                        center = c,
                                        style = Stroke(width = DiscGrooveWidthPx),
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        BiliAsyncImage(
                            url = item.coverUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize(DiscLabelFraction)
                                .clip(CircleShape),
                        )
                        // 轴孔压在标签纸中央,是这张唱片"是个实物"的最后一笔。
                        Box(
                            modifier = Modifier
                                .size(discSize * DiscHoleFraction)
                                .clip(CircleShape)
                                .background(FixedColors.VinylHole),
                        )
                    }
                    // 仅在手指搓盘拖拽时绘制因果扇形弧线与取消环。
                    if (discGesture) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2f
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val strokeW = 3.dp.toPx()
                            val arcInset = strokeW / 2f
                            drawArc(
                                color = if (isCancelArmed) cancelArcColor else scrubArcColor,
                                startAngle = scrubStartAngle,
                                sweepAngle = scrubTurn.coerceIn(-359.9f, 359.9f),
                                useCenter = false,
                                topLeft = Offset(arcInset, arcInset),
                                size = Size(size.width - strokeW, size.height - strokeW),
                                style = Stroke(width = strokeW, cap = StrokeCap.Round),
                            )
                            if (isCancelArmed) {
                                drawCircle(
                                    color = cancelArcColor.copy(alpha = 0.28f),
                                    radius = r * CancelRadiusRatio,
                                    center = c,
                                    style = Stroke(width = 1.5.dp.toPx()),
                                )
                            }
                        }
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isCancelArmed,
                        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()) +
                            scaleIn(
                                initialScale = CancelPillEnterScale,
                                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                            ),
                        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()) +
                            scaleOut(
                                targetScale = CancelPillEnterScale,
                                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                            ),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        CancelPill()
                    }
                }
            }
            // 听视频整屏只有一条内容,曲目名抬至 headlineSmallEmphasized 建立主视觉锚点。
            val titleMenuCount = (if (item.title.isNotBlank()) 1 else 0) + (if (item.bvid.isNotBlank()) 1 else 0)
            ContextMenuBox(
                onClick = null,
                menuLabel = menuLabel,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.Comfortable),
                menu = { close ->
                    var idx = 0
                    if (item.title.isNotBlank()) {
                        DropdownMenuItem(
                            onClick = {
                                close()
                                copyTitle(item.title)
                            },
                            text = { Text(copyTitleLabel) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            shape = menuItemShape(idx++, titleMenuCount),
                        )
                    }
                    if (item.bvid.isNotBlank()) {
                        DropdownMenuItem(
                            onClick = {
                                close()
                                copyBvid(item.bvid)
                            },
                            text = { Text(copyBvidLabel) },
                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                            shape = menuItemShape(idx, titleMenuCount),
                        )
                    }
                },
            ) { openMenu ->
                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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
                )
            }

            if (item.upName.isNotBlank()) {
                Text(
                    item.upName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.Hair),
                )
            }

            val qState = queue.state ?: state.queue
            val posText = qState?.let { QueuePositionText(it) }
            val sourceText = queue.sourceLabel.ifBlank { qState?.sourceLabel.orEmpty() }
            val edgeLoading = qState?.let { it.loadingBefore || it.loadingAfter } == true
            // 仅在单栏且存在真实集合来源时显示胶囊（绝不重复充当底部 Sheet 开关）；双栏右侧已展示时完全省略。
            val source = queue.source
            if (showSourcePill && source != null && (sourceText.isNotBlank() || !posText.isNullOrBlank())) {
                val pillClick: () -> Unit = { onOpenQueueSource(source) }
                Box(
                    modifier = Modifier
                        .padding(top = Spacing.Tight)
                        .interactiveCard(
                            shape = CircleShape,
                            baseColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            hoverColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            enableScale = true,
                        )
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button, onClick = pillClick)
                        .padding(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    ) {
                        if (sourceText.isNotBlank()) {
                            Text(
                                text = sourceText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                        if (!posText.isNullOrBlank()) {
                            Text(
                                text = posText,
                                style = MaterialTheme.typography.labelMediumEmphasized.tabular,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        if (edgeLoading) {
                            LoadingSpinner()
                        } else {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(Dimens.IconInline),
                            )
                        }
                    }
                }
            }
        }
    }
}


/**
 * 底部悬浮传输工具栏([HorizontalFloatingToolbar])承载对称五格动作组([ButtonGroup]):
 * `[倍速] [上一条] [▶] [下一条] [定时]`，各按钮按压时触发物理 `animateWidth` 膨胀让位。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    loading: Boolean,
    speed: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    sleepTimer: SleepTimerState,
    progress: ListenProgressState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onOpenSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
    audioOptions: List<Int> = emptyList(),
    currentAudio: Int = 0,
    onAudioChange: (Int) -> Unit = {},
) {
    var speedSheetOpen by remember { mutableStateOf(false) }
    val speedLabel = stringResource(Res.string.player_speed)
    val prevLabel = stringResource(Res.string.player_previous)
    val playLabel = stringResource(if (isPlaying) Res.string.player_pause else Res.string.player_play)
    val nextLabel = stringResource(Res.string.player_next)

    val toolbarColors = FloatingToolbarDefaults.standardFloatingToolbarColors(
        toolbarContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    val pillToggleColors = ToggleButtonDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
        checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    )
    val pillIconColors = IconButtonDefaults.filledTonalIconButtonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val speedInteraction = remember { MutableInteractionSource() }
    val prevInteraction = remember { MutableInteractionSource() }
    val nextInteraction = remember { MutableInteractionSource() }
    val sleepInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        contentAlignment = Alignment.Center,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = toolbarColors,
            shape = FloatingToolbarDefaults.ContainerShape,
            contentPadding = PaddingValues(Spacing.Tight),
            expandedShadowElevation = 0.dp,
            collapsedShadowElevation = 0.dp,
            modifier = Modifier.wrapContentWidth(),
        ) {
            ButtonGroup(
                overflowIndicator = {},
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                customItem(
                    buttonGroupContent = {
                        val speedText = formatSpeed(speed)
                        BilbyTooltip(text = speedLabel, anchorAbove = true) {
                            ToggleButton(
                                checked = speedSheetOpen || speed != 1f,
                                onCheckedChange = { speedSheetOpen = true },
                                interactionSource = speedInteraction,
                                colors = pillToggleColors,
                                contentPadding = PaddingValues(horizontal = Spacing.Cozy),
                                modifier = Modifier
                                    .height(ListenControlHeight)
                                    .widthIn(min = ListenSidePillMinWidth)
                                    .animateWidth(speedInteraction)
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .semantics { contentDescription = "$speedLabel $speedText" },
                            ) {
                                Icon(
                                    Icons.Filled.Speed,
                                    contentDescription = null,
                                    modifier = Modifier.size(ChipIconSize),
                                )
                                Spacer(modifier = Modifier.width(Spacing.Hair))
                                Text(
                                    text = speedText,
                                    style = MaterialTheme.typography.labelLarge.tabular,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    },
                    menuContent = {},
                )
                customItem(
                    buttonGroupContent = {
                        BilbyTooltip(text = prevLabel, shortcut = "[", anchorAbove = true) {
                            FilledTonalIconButton(
                                onClick = onPrevious,
                                enabled = hasPrevious,
                                interactionSource = prevInteraction,
                                shapes = IconButtonDefaults.shapes(),
                                colors = pillIconColors,
                                modifier = Modifier
                                    .size(ListenControlHeight)
                                    .animateWidth(prevInteraction)
                                    .then(if (hasPrevious) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipPrevious,
                                    contentDescription = prevLabel,
                                    modifier = Modifier.size(SkipIconSize),
                                )
                            }
                        }
                    },
                    menuContent = {},
                )
                customItem(
                    buttonGroupContent = {
                        BilbyTooltip(text = playLabel, shortcut = "Space", anchorAbove = true) {
                            Box(modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)) {
                                ListenPlayButton(
                                    isPlaying = isPlaying,
                                    loading = loading,
                                    onClick = onPlayPause,
                                )
                            }
                        }
                    },
                    menuContent = {},
                )
                customItem(
                    buttonGroupContent = {
                        BilbyTooltip(text = nextLabel, shortcut = "]", anchorAbove = true) {
                            FilledTonalIconButton(
                                onClick = onNext,
                                enabled = hasNext,
                                interactionSource = nextInteraction,
                                shapes = IconButtonDefaults.shapes(),
                                colors = pillIconColors,
                                modifier = Modifier
                                    .size(ListenControlHeight)
                                    .animateWidth(nextInteraction)
                                    .then(if (hasNext) Modifier.pointerHoverIcon(PointerIcon.Hand) else Modifier),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipNext,
                                    contentDescription = nextLabel,
                                    modifier = Modifier.size(SkipIconSize),
                                )
                            }
                        }
                    },
                    menuContent = {},
                )
                customItem(
                    buttonGroupContent = {
                        BoundSleepTimerButton(
                            sleepTimer = sleepTimer,
                            progress = progress,
                            speed = speed,
                            colors = pillToggleColors,
                            interactionSource = sleepInteraction,
                            onClick = onOpenSleepTimer,
                            modifier = Modifier
                                .height(ListenControlHeight)
                                .widthIn(min = ListenSidePillMinWidth)
                                .animateWidth(sleepInteraction),
                        )
                    },
                    menuContent = {},
                )
            }
        }
    }

    // 倍速面板和视频播放器那块是同一份内容:倍速与音质两段。听视频没有画面,清晰度不给;
    // 字幕在顶栏的歌词按钮上。
    if (speedSheetOpen) {
        PaneSheet(
            onDismissRequest = { speedSheetOpen = false },
            title = stringResource(
                if (audioOptions.size > 1) Res.string.player_settings else Res.string.player_speed,
            ),
        ) {
            PlayerSettingsContent(
                speed = speed,
                onSpeedChange = onSpeedChange,
                qualities = emptyList(),
                currentQuality = 0,
                onQualityChange = {},
                subtitleTracks = emptyList(),
                currentSubtitleLan = "",
                onSubtitleTrackChange = {},
                audioOptions = audioOptions,
                currentAudio = currentAudio,
                onAudioChange = onAudioChange,
                only = if (audioOptions.size > 1) null else PlayerSettingsSection.Speed,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BoundSleepTimerButton(
    sleepTimer: SleepTimerState,
    progress: ListenProgressState,
    speed: Float,
    colors: androidx.compose.material3.ToggleButtonColors,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemRemainingMillis = if (sleepTimer.mode is SleepTimerMode.EndOfItem) {
        computeItemRemainingMillis(progress.positionMillis, progress.durationMillis, speed)
    } else {
        null
    }
    val label = sleepTimerLabel(sleepTimer, itemRemainingMillis)
    val sleepLabel = stringResource(Res.string.sleep_timer_off)
    BilbyTooltip(text = sleepLabel, anchorAbove = true) {
        ToggleButton(
            checked = label != null,
            onCheckedChange = { onClick() },
            interactionSource = interactionSource,
            colors = colors,
            contentPadding = PaddingValues(horizontal = Spacing.Cozy),
            modifier = modifier
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics { contentDescription = label ?: sleepLabel },
        ) {
            if (label != null) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.tabular,
                    maxLines = 1,
                    softWrap = false,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Bedtime,
                    contentDescription = null,
                    modifier = Modifier.size(ChipIconSize),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ListenPlayButton(
    isPlaying: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    val activeShape = isPlaying && !loading
    val width by animateDpAsState(
        targetValue = if (activeShape) ListenPlayButtonPlayingWidth else ListenPlayButtonIdleWidth,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "listen-play-width",
    )
    val corner by animateDpAsState(
        targetValue = if (activeShape) ListenPlayButtonPlayingCorner else ListenControlHeight / 2,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "listen-play-corner",
    )
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (loading || isPlaying) colors.primaryContainer else colors.primary,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "listen-play-container",
    )
    val contentColor by animateColorAsState(
        targetValue = if (loading || isPlaying) colors.onPrimaryContainer else colors.onPrimary,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "listen-play-content",
    )
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = container,
        contentColor = contentColor,
        modifier = Modifier.size(width = width, height = ListenControlHeight),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                LoadingIndicator(color = contentColor, modifier = Modifier.size(ListenPlayLoadingSize))
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) Res.string.player_pause else Res.string.player_play),
                    modifier = Modifier.size(ListenPlayIconSize),
                )
            }
        }
    }
}

private val ListenControlHeight = 48.dp
private val ListenSidePillMinWidth = 56.dp
private val ListenPlayButtonIdleWidth = 56.dp
private val ListenPlayButtonPlayingWidth = 64.dp
private val ListenPlayButtonPlayingCorner = 14.dp
private val ListenPlayIconSize = 26.dp
private val ListenPlayLoadingSize = 26.dp

/**
 * 播放失败那一行。重试期间只说明正在重试 —— 退避的那几秒是静默的,不给解释就和"卡住了"
 * 没有区别;这也是不放进度条上方的原因,它要和播放控制读成一句话。
 */
@Composable
private fun FailureRow(message: String, retrying: Boolean, onRetry: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable),
    ) {
        Text(
            text = if (retrying) stringResource(Res.string.listen_retrying, message) else message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        // 重试中不给按钮:此刻按下去只会打断已经在跑的那次。
        if (!retrying) {
            TextButton(onClick = onRetry) { Text(stringResource(Res.string.action_retry)) }
        }
    }
}

/**
 * 队列 Sheet 的内容:随机播放开关(从旧版 `BottomRow` 搬过来)+ 队列列表。挂在
 * `BottomSheetScaffold` 上而不是 `ModalBottomSheet` —— 队列因此常驻一个把手,不再是要点开
 * 才能看见的入口,同时听视频没有画面要保护,不需要 `ModalBottomSheet` 那层遮罩本来就要
 * 遮住的东西。
 *
 * 收起状态下只露出这里的第一行文字(见 [QueueHandleHeight]),内容本身不需要为收起态
 * 另写一份——`BottomSheetScaffold` 只是把超出 peek 高度的部分裁掉。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueSheetContent(
    queue: QueueUiState,
    queueState: QueueState?,
    playing: Boolean,
    sheetExpanded: Boolean,
    onToggleSheet: (() -> Unit)?,
    onCycleEndMode: () -> Unit,
    onCycleOrder: () -> Unit,
    onSelectEpisode: (EpisodeTarget) -> Unit,
    onRetryQueue: () -> Unit,
    onOpenQueueSource: (QueueSource) -> Unit,
    /** 宽屏右栏:撑满栏高,列表在栏里自己滚。Sheet 里由 Sheet 定高,不撑。 */
    fillHeight: Boolean = false,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(queue.rows.size) {
        if (queue.rows.size <= 1) editing = false
    }
    val posText = queueState?.let { QueuePositionText(it) }
    val edgeLoading = queueState?.let { it.loadingBefore || it.loadingAfter } == true
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val sourceClick = queue.source?.let { source -> { onOpenQueueSource(source) } }
    // 标题只负责打开真实集合来源；Sheet 展开/收起由右侧唯一尖角按钮与把手承担，消除重复触点。
    val headerPrimaryClick = sourceClick

    Column(modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
        // 标题是这份队列的来源,配「X / N」等宽胶囊。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Dimens.MinTouchTarget)
                .padding(start = Spacing.Comfortable, end = Spacing.Tight),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = Dimens.MinTouchTarget)
                    .clip(MaterialTheme.shapes.small)
                    .then(
                        if (headerPrimaryClick != null) {
                            Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable(role = Role.Button, onClick = headerPrimaryClick)
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = Spacing.Hair),
            ) {
                Text(
                    text = queue.sourceLabel.ifEmpty { stringResource(Res.string.listen_queue_title) },
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (sourceClick != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                }
                if (!posText.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        shape = CircleShape,
                    ) {
                        Text(
                            text = posText,
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
                if (edgeLoading) {
                    LoadingSpinner()
                }
            }
            QueuePlaybackControls(
                endMode = queue.endMode,
                order = queue.order,
                onCycleEndMode = onCycleEndMode,
                onCycleOrder = onCycleOrder,
                showOrder = queue.rows.size > 1,
            )
            if (queue.editActions != null && queue.rows.size > 1) {
                val editLabel = stringResource(if (editing) Res.string.action_done else Res.string.queue_edit)
                IconButton(
                    onClick = { editing = !editing },
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = if (editing) Icons.Filled.Check else Icons.Outlined.Edit,
                        contentDescription = editLabel,
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                }
            }
            if (onToggleSheet != null) {
                val toggleLabel = stringResource(
                    if (sheetExpanded) Res.string.feed_burst_collapse else Res.string.dynamic_text_expand,
                )
                IconButton(
                    onClick = onToggleSheet,
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(
                        imageVector = if (sheetExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = toggleLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                }
            }
        }
        val showReset = queue.editActions?.onReset != null
        val showClearOthers = editing && queue.editActions?.onClearOthers != null && queue.rows.size > 1
        if (showReset || showClearOthers) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.End),
            ) {
                if (showReset) {
                    TextButton(
                        onClick = {
                            queue.editActions.onReset()
                            editing = false
                        },
                    ) {
                        Text(stringResource(Res.string.queue_reset))
                    }
                }
                if (showClearOthers) {
                    TextButton(
                        onClick = {
                            queue.editActions.onClearOthers()
                            editing = false
                        },
                    ) {
                        Text(stringResource(Res.string.queue_clear_others))
                    }
                }
            }
        }
        // 三种状态与详情页那一段(VideoTabs 的 queueItems)同一套文案与判据。
        when {
            // 此刻列表里那一条是起播时的临时占位,不是队列内容,摆出来会读成"来源只有这一条"。
            queue.enriching -> InlineProgress(
                stringResource(Res.string.video_queue_loading),
                Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            )

            queue.incomplete -> Row(
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

            else -> EpisodeList(
                rows = queue.rows,
                onSelect = onSelectEpisode,
                edges = queue.edges,
                playing = playing,
                editActions = queue.editActions,
                editing = editing,
                errorBefore = queueState?.errorBefore == true || queue.edges?.errorBefore == true,
                errorAfter = queueState?.errorAfter == true || queue.edges?.errorAfter == true,
                remainingBefore = queueState?.remainingBefore ?: queue.edges?.remainingBefore,
                remainingAfter = queueState?.remainingAfter ?: queue.edges?.remainingAfter,
                contentPadding = PaddingValues(
                    start = Spacing.Comfortable,
                    end = Spacing.Comfortable,
                    top = Spacing.Tight,
                    bottom = Spacing.Tight + if (fillHeight) 0.dp else navBottom,
                ),
                modifier = Modifier.fillMaxWidth().then(if (fillHeight) Modifier.weight(1f) else Modifier),
            )
        }
    }
}

/**
 * 定时停止:统一二级面板([PaneSheet]),**点一下就生效并收起**,没有「确认」。
 * 正文与视频播放器控制条的定时面板共用 [SleepTimerStandaloneContent]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerDialog(
    sleepTimer: SleepTimerState,
    /** 当前这条还要放多久,已按倍速折算。「播完当前视频」时钟面上显示它。 */
    itemRemainingMillis: Long?,
    onSet: (SleepTimerMode) -> Unit,
    onAdjust: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PaneSheet(
        onDismissRequest = onDismiss,
        title = null,
    ) {
        SleepTimerStandaloneContent(
            sleepTimer = sleepTimer,
            itemRemainingMillis = itemRemainingMillis,
            onSet = onSet,
            onAdjust = onAdjust,
            onDismiss = onDismiss,
            modifier = Modifier.padding(
                start = Spacing.Loose,
                end = Spacing.Loose,
                bottom = Spacing.Comfortable,
            ),
        )
    }
}


/**
 * 歌词模式的顶栏:返回箭头 + 封面 + 标题(至多三行)。
 *
 * 只有这个模式需要它 —— 唱片模式下标题就在唱片正下方,顶栏留一个退出按钮就够。两个模式的
 * 顶栏因此不一样高;按钮位置靠贴顶对齐保持不动,见下面 Row 里的说明。
 *
 * 不用 [BilbyTopBar]:`TopAppBar` 的高度是固定的一行,塞不下封面加三行标题。自己排一个 Row,
 * 状态栏内边距用 `TopAppBarDefaults.windowInsets` 取,和其余页面的顶栏对齐同一套。
 */
@Composable
private fun LyricsHeader(item: QueueItem, onBack: () -> Unit, trailing: @Composable () -> Unit) {
    LocalWindowCaption.current?.Host()
    val captionSlot = rememberCaptionSlot()
    Surface(color = MaterialTheme.colorScheme.surface, modifier = captionSlot.modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                // 左右各 4dp:TopAppBar 给导航图标和操作区的边距就是这么多,横向也对得上。
                .padding(horizontal = Spacing.Hair, vertical = Spacing.Tight),
        ) {
            // **两颗按钮贴顶,不居中。** 贴顶时它们的中心离上沿 8 + 24 = 32dp,正是 64dp 高的
            // 普通顶栏里按钮的位置 —— 在唱片和歌词之间切换,返回和字幕都原地不动。居中的话
            // 这一栏有多高(标题一行还是三行)它们就往下挪多少。
            val backLabel = stringResource(Res.string.action_back)
            BilbyTooltip(text = backLabel, shortcut = "Alt+←") {
                IconButton(onClick = onBack) {
                    // AutoMirrored:supportsRtl 开着,RTL 语言下返回箭头必须翻过来。
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = backLabel,
                    )
                }
            }
            VideoCover(
                url = item.coverUrl,
                modifier = Modifier.width(LyricsHeaderCoverWidth).align(Alignment.CenterVertically),
            )
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .weight(1f)
                    .align(Alignment.CenterVertically)
                    .heightIn(min = CaptionDragHeight)
                    .windowDragArea(captionSlot.atTop),
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                trailing()
                captionSlot.buttons?.invoke()
            }
        }
    }
}

/**
 * 歌词模式:取代唱片的呈现,逐句滚动。
 *
 * **不画封面当背景。** 这里曾经是模糊后的封面 + 一层 scrim,代价是可读性变成随机的:底是
 * 任意一帧视频,遇到亮的那一帧,固定 scrim 压不住,白字直接糊掉;而且深色块从顶栏下方硬起、
 * 在控制区上方硬停,把整页横着切两刀,一页两套配色 —— 与 §4.1b 给播放器 scrim 得出的结论
 * 同源。封面在这一页本来也不承载信息(标题和 UP 名就在上面一行,是真文字),它是纯装饰,
 * 而装饰不该拿可读性去换。去掉之后文字用主题色,对比度由主题保证而不是碰运气。
 *
 * [onBack] 语义是**选中「无字幕」**,退出歌词是它的结果。除了右上角那个按钮,文字之外的空白
 * 也接同一个动作——[LyricsList] 里每一句自己有 `onSeekTo` 的点击,落在文字上应该跳转;点到
 * 行与行之间、或者文字块之外的边距,才会穿透到底层。
 *
 * 位置不是从外面传 [AudioPlaybackUiState] 或整页那个 500ms 轮询算的,而是这里自己单独起
 * 一个更细的 tick(见下面的 `LaunchedEffect`)——500ms 的量化对进度条够用,但歌词是逐句
 * 切换的,句子边界卡在两次轮询之间时切换会晚半秒,而且晚多久不固定,肉眼就是"跳一下"。
 * 只在歌词页起独立 tick 而不是把整页轮询都调快:这个 Composable 只在翻到歌词页时才会
 * 进组合,调快不会影响没人看的时候的功耗;要是把整页的 500ms 一起改快,进度条不需要这个
 * 精度,纯粹多耗电。
 */
@Composable
private fun LyricsView(
    cues: List<SubtitleCue>,
    player: PlayerHandle,
    dragPosition: () -> Long?,
    onSeekTo: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 空白处点一下回唱片。铺在最底层,文字自己的点击(seek)盖在它上面。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = stringResource(Res.string.action_close),
                        onClick = onBack,
                    ),
            )
            LyricsList(
                cues = cues,
                player = player,
                dragPosition = dragPosition,
                onSeekTo = onSeekTo,
                modifier = Modifier.fillMaxSize().padding(vertical = Spacing.Loose),
            )
            // 字幕按钮在顶栏里(见 ListenScreen 的 topBar),点它退出歌词。
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LyricsList(
    cues: List<SubtitleCue>,
    player: PlayerHandle,
    dragPosition: () -> Long?,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val windowVisible = LocalWindowVisible.current
    var polledPosition by remember { mutableLongStateOf(player.currentPosition) }
    LaunchedEffect(player, windowVisible) {
        if (!windowVisible) return@LaunchedEffect
        while (true) {
            polledPosition = player.currentPosition
            delay(LyricsPollIntervalMillis)
        }
    }
    val activeDrag = dragPosition()
    val positionMillis = activeDrag ?: polledPosition
    val dragging = activeDrag != null

    val listState = rememberLazyListState()
    val nearIndex = cues.indexNear(positionMillis)
    val highlightIndex = nearIndex.takeIf { it >= 0 && positionMillis < cues[it].toMillis }
    val focusIndex = nearIndex + 1

    LaunchedEffect(focusIndex, dragging) {
        if (dragging) listState.scrollToItem(focusIndex) else listState.animateScrollToItem(focusIndex)
    }

    LaunchedEffect(listState, focusIndex) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == focusIndex }
                if (!visible) listState.animateScrollToItem(focusIndex)
            }
    }

    val density = LocalDensity.current
    var viewportHeight by remember { mutableStateOf(0.dp) }
    val halfViewport = viewportHeight / 2

    Box(
        modifier = modifier.onSizeChanged { viewportHeight = with(density) { it.height.toDp() } },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = Spacing.Loose, vertical = halfViewport),
        ) {
            item(key = LeadInKey, contentType = "lead-in") {
                Text(
                    "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Tight),
                )
            }
            itemsIndexed(
                cues,
                key = { _, cue -> cue.fromMillis },
                contentType = { _, _ -> "lyric" },
            ) { index, cue ->
                val highlighted = index == highlightIndex
                Text(
                    cue.text,
                    textAlign = TextAlign.Center,
                    style = if (highlighted) {
                        MaterialTheme.typography.titleMediumEmphasized
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    color = if (highlighted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(role = Role.Button) { onSeekTo(cue.fromMillis) }
                        .padding(vertical = Spacing.Tight),
                )
            }
        }
        ListScrollbar(listState, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * 顶栏右端的字幕按钮,唱片与歌词两个模式同一颗。没有它,字幕就只能从看视频页开,这个页面上
 * 永远打不开;没有可用轨时整个按钮不出现。
 *
 * - 唱片模式:点开是轨道菜单(和看视频控制条那份共用,见 [SubtitleTrackMenu]),选一条就进歌词。
 * - 歌词模式:点它就是**选中「无字幕」**,退出歌词是这件事的结果,不是另一个动作。
 *
 * 字幕开着时图标染 primary,这是"当前状态",不是"点了会怎样"。放在顶栏上,底色就是顶栏的,
 * 不再垫一圈 scrim —— 那圈底是它浮在唱片区上时为了压住底下内容才要的。
 */
@Composable
private fun SubtitleBarButton(
    tracks: List<SubtitleTrack>,
    currentLan: String,
    lyricsShown: Boolean,
    onSelect: (String) -> Unit,
) {
    if (tracks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val subtitleLabel = stringResource(Res.string.player_subtitle)
    Box {
        BilbyTooltip(text = subtitleLabel) {
            IconButton(onClick = { if (lyricsShown) onSelect("") else expanded = true }) {
                Icon(
                    Icons.Filled.Subtitles,
                    contentDescription = subtitleLabel,
                    tint = if (currentLan.isNotEmpty()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        SubtitleTrackMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            tracks = tracks,
            currentLan = currentLan,
            onSelect = onSelect,
        )
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
