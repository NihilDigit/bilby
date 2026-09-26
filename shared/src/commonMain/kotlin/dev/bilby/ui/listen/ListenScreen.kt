package dev.bilby.ui.listen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import dev.bilby.ui.components.PaneSheet
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import dev.bilby.ui.BackHandler
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ToggleButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.text.TextStyle
import dev.bilby.ui.theme.rememberFlexFont
import dev.bilby.ui.theme.ReadoutAxes
import dev.bilby.ui.theme.rememberClockFont
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import dev.bilby.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import dev.bilby.player.PlayerHandle
import dev.bilby.player.PlayerListener
import dev.bilby.ui.LocalPlaybackHost
import dev.bilby.resources.*
import dev.bilby.ui.player.EpisodeList
import dev.bilby.ui.player.EpisodeTarget
import dev.bilby.ui.player.playOrReplay
import dev.bilby.ui.player.CenterPlayButton
import dev.bilby.ui.player.ControlButton
import dev.bilby.ui.player.PlayerIconButton
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
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 倍速折算的除数下限。倍速滑到极小值时不让剩余时长炸成天文数字。 */
private const val MinSpeedForEstimate = 0.1f

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

/** 歌词模式顶栏里那张封面的宽度。高度由 16:10 定,见风格指南 §1.3b。 */
private val LyricsHeaderCoverWidth = 88.dp

/** 上一条 / 下一条的图标与容器。比控制条上的图标按钮大一档 —— 它们是这一屏的主要操作之一。 */
private val SkipIconSize = 28.dp
private val SkipButtonSize = 52.dp

/** 倍速、定时那两枚 chip 里的图标,和视频控制条上的 chip 同一档。 */
private val ChipIconSize = 18.dp

/** 队列顺序那个按钮的图标,跟在文字左边。M3 给按钮前置图标定的就是 18dp。 */
private val OrderIconSize = 18.dp

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
@Composable
private fun TimeReadout(
    positionMillis: Long,
    durationMillis: Long,
    active: Boolean,
    cancelArmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val spec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
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
    val annotated = buildAnnotatedString {
        append(text)
        if (totalText.isNotEmpty()) {
            withStyle(SpanStyle(fontSize = ReadoutTotalSize, color = totalColor)) {
                append(" / ")
                append(totalText)
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
    onToggleShuffle: () -> Unit,
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
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var resumeAfterDrag by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var sleepSheetOpen by remember { mutableStateOf(false) }
    val playback = LocalPlaybackHost.current

    // Media3 的 Player 没有位置回调,只能轮询;和 BilbyPlayer 一致的 500ms 间隔。
    LaunchedEffect(player) {
        while (true) {
            if (dragPosition == null) position = player?.currentPosition ?: 0L
            bufferedPosition = player?.bufferedPosition ?: 0L
            duration = player?.duration?.coerceAtLeast(0) ?: 0L
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

    val displayPosition = dragPosition ?: position
    // 当前这条还要放多久。倍速折进去:2x 下剩的十分钟视频只放五分钟,不折算的话这个数会
    // 一直偏大。控制行的定时 chip 和定时面板的大数字都用它。
    val itemRemainingMillis = (duration - position)
        .takeIf { duration > 0L && it > 0L }
        ?.let { (it / speed.coerceAtLeast(MinSpeedForEstimate)).toLong() }

    // 拖动进度的三步,进度条和唱片共用(见 DiscView):按下先停,拖动只改显示的位置,松手才真
    // seek —— 每帧 seek 会让播放器不停丢缓冲重新起播。
    val seekStart: () -> Unit = {
        resumeAfterDrag = player?.isPlaying == true
        player?.pause()
    }
    val seekTo: (Long) -> Unit = { dragPosition = it }
    val seekFinished: () -> Unit = {
        dragPosition?.let { target ->
            player?.seekTo(target)
            position = target
            if (resumeAfterDrag) player?.play()
        }
        dragPosition = null
    }

    /** 转唱片时手指离开盘面够远:松手就不 seek。和播放器横划进退的取消区是同一件事。 */
    var scrubCancelArmed by remember { mutableStateOf(false) }
    val seekCancelled: () -> Unit = {
        dragPosition = null
        scrubCancelArmed = false
        if (resumeAfterDrag) player?.play()
    }

    // **歌词是这一页的一个状态,不是另一页;而且这个状态是推导出来的,不另存一份。**
    // 入口只有唱片右上角那个字幕按钮:选中一条轨就进歌词,选「无字幕」就回唱片。
    //
    // 这里曾经是 HorizontalPager + 圆点指示器 + 点唱片翻页,三个入口。问题不是入口太少而是
    // 全都看不见:圆点是 0.28 alpha 的 onSurfaceVariant 画在接近白的 surface 上,还压在进度条
    // 旁边,读起来像播放控件的一部分;呼吸动效也加过,照样没人发现 —— 缺的从来不是动静大小。
    // 而"选一条字幕轨"和"看字幕"本来就是同一个意图,拆成两步之后,中间那道缝正是没人跨过去
    // 的地方。
    //
    // 推导而不是存 `var showLyrics`:选中态与显示态一旦是两份状态就会分家,唱片页上会出现
    // 一盏亮着却什么都没显示的灯,换视频、关字幕也各要一个 LaunchedEffect 去同步。
    // 顺带解决了空窗——`cues` 是选轨之后才去取的,等它到了才切,不会先闪一页空歌词。
    val showLyrics = subtitleLan.isNotEmpty() && subtitleCues.isNotEmpty()

    // 必须自己给顶栏:听视频是播放页内的一个状态,VideoScreen 在这条分支上提前 return,
    // 外层那句 windowInsetsPadding(statusBars) 走不到 —— 不处理的话标题和返回箭头
    // 会直接压在状态栏的时钟上。用 Scaffold 而不是手贴 padding,和其余页面一致。
    Scaffold(
        modifier = modifier.fillMaxSize(),
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
            val subtitleButton: @Composable () -> Unit = {
                SubtitleBarButton(
                    tracks = subtitleTracks,
                    currentLan = subtitleLan,
                    lyricsShown = showLyrics,
                    onSelect = onSelectSubtitle,
                )
            }
            if (showLyrics && item != null) {
                LyricsHeader(item = item, onBack = onBack, trailing = subtitleButton)
            } else {
                BilbyTopBar(title = "", onBack = onBack, actions = { subtitleButton() })
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

        val sheetState = rememberBottomSheetScaffoldState()
        // BottomSheetScaffold 不管返回键(ModalBottomSheet 才管),不拦的话展开队列之后一按
        // 返回,落到的是外面那句"退出听视频"。看 targetValue 而不是 currentValue:拉到一半
        // 松手、正往上弹的那一刻按返回,也该是收回。
        val sheetScope = rememberCoroutineScope()
        BackHandler(enabled = sheetState.bottomSheetState.targetValue == SheetValue.Expanded) {
            sheetScope.launch { sheetState.bottomSheetState.partialExpand() }
        }

        // 队列没建过时收起到 0——一个空把手拉起来什么都没有,是纯噪声。建过之后把手常显,
        // 不需要再点一行入口才能看到它。建队列中与建失败也露出来:把手那一行正是说明这两件事
        // 的地方。
        val hasQueue = queue.rows.isNotEmpty() || queue.enriching || queue.incomplete
        val peek = if (hasQueue) QueueHandleHeight else 0.dp

        BottomSheetScaffold(
            scaffoldState = sheetState,
            modifier = Modifier.fillMaxSize().padding(insets),
            sheetPeekHeight = peek,
            sheetContent = {
                AdaptiveContent(modifier = Modifier.fillMaxWidth(), maxWidth = Breakpoints.MediaWidth) {
                    QueueSheetContent(
                        queue = queue,
                        playing = playing,
                        onToggleShuffle = onToggleShuffle,
                        onSelectEpisode = onSelectEpisode,
                        onRetryQueue = onRetryQueue,
                        onOpenQueueSource = onOpenQueueSource,
                    )
                }
            },
        ) { sheetInsets ->
            AdaptiveContent(modifier = Modifier.fillMaxSize(), maxWidth = Breakpoints.MediaWidth) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = sheetInsets.calculateBottomPadding()),
                ) {
                // 唱片(或歌词)吃掉除控制区之外的全部空间;没有字幕时这里就是空间的唯一主角,
                // 不用再另外做"整体居中"的特判——weight(1f) 本身就把它撑满了。
                // 唱片与歌词是**同一块区域的两页**,左右滑动切换。
                //
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (showLyrics) {
                        LyricsView(
                            cues = subtitleCues,
                            player = player,
                            dragPosition = dragPosition,
                            onSeekTo = { player.seekTo(it) },
                            // 退出歌词就是**选中「无字幕」**,不是另一个动作。
                            onBack = { onSelectSubtitle("") },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        DiscView(
                            state = state,
                            speed = speed,
                            subtitleTracks = subtitleTracks,
                            subtitleLan = subtitleLan,
                            onSelectSubtitle = onSelectSubtitle,
                            duration = duration,
                            position = displayPosition,
                            scrubbing = dragPosition != null,
                            onScrubStart = seekStart,
                            onScrub = seekTo,
                            onScrubEnd = seekFinished,
                            onScrubCancelArmed = { scrubCancelArmed = it },
                            onScrubCancel = seekCancelled,
                            cancelArmed = scrubCancelArmed,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // 进度条 + 控制行贴底,不跟着有没有字幕挪位置——两种呈现共用同一段。
                //
                // **两端的时间叠在进度条触控区的下半截里,不另占一行。** 进度条为了 48dp 的触控
                // 高度,细线上下各空着二十多 dp;时间再排在它下面,线和控制行之间就隔了两段空白。
                // 时间不接触摸,叠进去之后拖动照样落在进度条上。
                //
                // 大数字、进度条、控制行三样也彼此压住一截(见 [pullUp]):进度条上下那两段空白
                // 是触控区,不是间距,照原样排开的话三样东西之间各隔着二十多 dp 的空。
                if (!showLyrics) {
                    TimeReadout(
                        positionMillis = displayPosition,
                        durationMillis = duration,
                        active = dragPosition != null,
                        cancelArmed = scrubCancelArmed,
                        modifier = Modifier.fillMaxWidth().padding(top = Spacing.Comfortable),
                    )
                }
                Box(
                    modifier = Modifier
                        .then(if (showLyrics) Modifier else Modifier.pullUp(SeekBarOverlapAbove))
                        .padding(horizontal = Spacing.Comfortable),
                ) {
                    // 和播放器用同一个进度条组件:两处的拖拽语义完全一样,分开写迟早各自漂移。
                    SeekBar(
                        position = displayPosition,
                        duration = duration,
                        bufferedPosition = bufferedPosition,
                        onSeekStart = seekStart,
                        onSeekTo = seekTo,
                        onSeekFinished = seekFinished,
                        // 这一页没有画面,进度条是整屏唯一在动的东西,见 SeekBar 的 wavy。
                        wavy = true,
                        playing = state.isPlaying,
                        speed = speed,
                        // 时间就在进度条正下方,手指上方再印一遍是重复。
                        timeBubble = false,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 唱片模式下两个时间都在大数字里([TimeReadout]),这里不写;歌词模式没有
                    // 那个大数字,两端各写一个小字。
                    //
                    // **两个时间自动缩到放得下为止。** 长视频是 `h:mm:ss`,字体缩放调大之后
                    // 这一行放不下 —— 默认行为是换行,禁掉换行则变成截断,一个时间少一位比换行
                    // 更糟。两边各 `weight(1f)`:autoSize 要有界宽度才知道该缩多少。
                    if (showLyrics) Row(modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter)) {
                        val timeAutoSize = TextAutoSize.StepBased(
                            minFontSize = 9.sp,
                            maxFontSize = MaterialTheme.typography.labelMedium.fontSize,
                        )
                        Text(
                            text = formatDurationMillis(displayPosition),
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            autoSize = timeAutoSize,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatDurationMillis(duration),
                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                            // outline 那一档是给描边和分隔线定的,压在正文里对比度不够
                            // (风格指南 §1.1)。次要文字一律 onSurfaceVariant。
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            autoSize = timeAutoSize,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                PlaybackControls(
                    // 歌词模式下进度条底下叠着两端的时间,只能压得浅一点。
                    modifier = Modifier.pullUp(if (showLyrics) ControlsOverlapLyrics else ControlsOverlapDisc),
                    isPlaying = state.isPlaying,
                    loading = state.loading,
                    speed = speed,
                    hasPrevious = state.queue?.canPrevious == true,
                    hasNext = state.queue?.canNext == true,
                    sleepTimer = sleepTimer,
                    itemRemainingMillis = itemRemainingMillis,
                    onPlayPause = { if (player.isPlaying) player.pause() else player.playOrReplay(playback) },
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
        }
    }

    if (sleepSheetOpen) {
        SleepTimerDialog(
            sleepTimer = sleepTimer,
            itemRemainingMillis = itemRemainingMillis,
            onSet = onSleepTimer,
            onAdjust = onSleepTimerAdjust,
            onDismiss = { sleepSheetOpen = false },
        )
    }
}

/**
 * 没有字幕、或还没点开歌词页时的默认呈现:圆形唱片居中 + 标题 + UP 名/队列位置。
 *
 * 封面裁成圆形、center crop。**这条推翻了这里以前"保持封面比例、不裁方形"的结论**——
 * 旧结论成立的前提是封面上可能正好写着标题,裁掉等于丢信息;但标题在这一屏是单独渲染的
 * 真文字(下面这一行),封面在这里已经降格成纯装饰,被圆形裁掉的边不再独占任何信息,
 * 旧的反对理由不再成立。B 站封面原图是 16:10 宽幅,圆形只取正中间的正方形。
 *
 * 不做旋转动画:这一页最常见的状态是息屏后台播放,转盘只在盯着看的那一刻才有意义,
 * 而旋转动画每帧都要重绘,为一个多数时间没人看的效果长期占着刷新预算不值得。
 *
 * 字幕轨按钮仍浮在唱片右上角——它是播放器控件层的一部分(docs/ui-style-guide.md §4.3),
 * 不是"有文稿才出现"的东西:没有它,用户在这个页面上就永远打不开字幕,只能先跳回看视频页
 * 选一次。没有可用轨时按钮自己不出现。
 */
@Composable
private fun DiscView(
    state: AudioPlaybackUiState,
    speed: Float,
    subtitleTracks: List<SubtitleTrack>,
    subtitleLan: String,
    onSelectSubtitle: (String) -> Unit,
    duration: Long,
    /** 显示用位置:拖动中是目标位置。转唱片从按下那一刻的这个值起算。 */
    position: Long,
    /** 正在拖进度(唱片或进度条)。此时唱片的角度跟着手,不再自己转。 */
    scrubbing: Boolean,
    onScrubStart: () -> Unit,
    onScrub: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    /** 手指进出了取消区(离开盘面够远)。 */
    onScrubCancelArmed: (Boolean) -> Unit,
    /** 在取消区里松手:不 seek,一切回到按下之前。 */
    onScrubCancel: () -> Unit,
    /** 此刻松手是取消,读数换成那句话。 */
    cancelArmed: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = state.queue?.current ?: return
    val currentPosition by rememberUpdatedState(position)
    // 外面套一层 Box,只为让字幕按钮的参照物是**传进来的整块区域**,和歌词模式一致。
    // 挂在下面那个 Column 上不行:它自带 Loose/Comfortable 的内边距,按钮会被一并推进去,
    // 于是两个模式里同一个按钮仍然落在两个位置 —— 而它是同一个按钮的两个状态,
    // 来回切时应该原地不动。
    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                // 底边不留:标题和 UP 名下面紧接着就是大数字(排在这一块外面)。
                .padding(start = Spacing.Loose, end = Spacing.Loose, top = Spacing.Comfortable),
        ) {
            // BoxWithConstraints 而不是 fillMaxWidth(fraction) + aspectRatio(1f):唱片要占的是
            // "剩余空间"里较短的那一边,横屏或者窗口矮的时候按宽度定size会把圆形顶出可视区域。
            //
            // **唱片贴着这块区域的下沿,不居中。** 竖屏上区域比宽高,居中时多出来的高度上下
            // 对半分,下面那一半正夹在唱片和标题之间,标题读起来不像这张唱片的。多出来的
            // 高度全留在上面,落在顶栏和唱片之间。
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                val discSize = min(maxWidth, maxHeight) * DiscSizeFraction

                // 播放时缓慢转动,暂停时停在当前角度(不归零——归零会让"暂停"看起来像"换了一张")。
                //
                // 曾经以"息屏后台是常态,旋转每帧重绘不值"为由不做这个动画。那条理由是错的:
                // 页面不可见时应用根本不出帧,旋转的开销只存在于用户正盯着它看的时候,
                // 而那恰好是它唯一有价值的时候。
                val angle = remember { Animatable(0f) }
                // 转唱片时手指拧过去的角度。单独一份,叠在 [angle] 上;松手时并进去。
                var scrubTurn by remember { mutableFloatStateOf(0f) }
                val scope = rememberCoroutineScope()
                // 这一次拖动是不是在唱片上拧的。不是的话就是在拖进度条。
                var discGesture by remember { mutableStateOf(false) }

                // **拖进度条时唱片也跟着转**,按同一个比例:整条时长对应一圈。转唱片能拖进度,
                // 反过来拖进度时唱片纹丝不动的话,两件事读起来就不是同一回事了。
                //
                // 起点记在按下之前的那一刻:进度条按下去就已经跳到手指的位置,等拖动开始再记,
                // 点下去那一跳就不转了。记在普通字段里而不是状态里,它只供下面换算用,不驱动重组。
                val barScrub = remember { BarScrub() }
                if (scrubbing && !barScrub.active && !discGesture) {
                    barScrub.active = true
                    barScrub.baseAngle = angle.value
                } else if (!scrubbing) {
                    barScrub.active = false
                    barScrub.restPosition = position
                }
                LaunchedEffect(position, scrubbing) {
                    if (barScrub.active && duration > 0) {
                        val turn = (position - barScrub.restPosition).toFloat() / duration * 360f
                        angle.snapTo(barScrub.baseAngle + turn)
                    }
                }
                LaunchedEffect(state.isPlaying, speed, scrubbing) {
                    if (!state.isPlaying || scrubbing) return@LaunchedEffect
                    val rotationPeriod = (DiscRotationPeriodMillis / speed.coerceAtLeast(0.1f))
                        .roundToInt()
                        .coerceAtLeast(1)
                    angle.animateTo(
                        targetValue = angle.value + 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(rotationPeriod, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart,
                        ),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(discSize)
                        // **转唱片就是拖进度。** 转一圈等于整条时长,和从进度条左端拖到右端是
                        // 同一段;顺时针往前,逆时针往后。唱片跟着手指转,松手才 seek,和拖进度条
                        // 同一套三步(见 ListenScreen 的 seekStart/seekTo/seekFinished)。
                        //
                        // 只认落在盘面上的按下,轴孔外、圆以外的都不接:这块区域的四角是空白,
                        // 在那儿划一下不该拨动进度。起手要过触摸阈值,单击不算。
                        .pointerInput(duration) {
                            if (duration <= 0) return@pointerInput
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                if ((down.position - center).getDistance() > minOf(size.width, size.height) / 2f) {
                                    return@awaitEachGesture
                                }
                                val first = awaitTouchSlopOrCancellation(down.id) { change, _ ->
                                    change.consume()
                                } ?: return@awaitEachGesture
                                val startPosition = currentPosition
                                var lastAngle = (first.position - center).degrees()
                                var turned = 0f
                                // **手指离开盘面够远就是取消**,松手不 seek。和播放器横划进退的取消区
                                // 是同一种做法:拧过头了、改主意了,不用再拧回去。
                                // 边界放在盘面外再走出半个半径:贴着盘沿拧时手指常常滑出圆外一点,
                                // 那不该算取消。
                                val cancelRadius = minOf(size.width, size.height) / 2f * CancelRadiusRatio
                                var cancelArmed = false
                                discGesture = true
                                onScrubStart()
                                drag(first.id) { change ->
                                    val armed = (change.position - center).getDistance() > cancelRadius
                                    if (armed != cancelArmed) {
                                        cancelArmed = armed
                                        onScrubCancelArmed(armed)
                                    }
                                    val a = (change.position - center).degrees()
                                    // 跨过 ±180° 那条线时按最短的一段算,否则一跨线就差一整圈。
                                    var delta = a - lastAngle
                                    if (delta > 180f) delta -= 360f
                                    if (delta < -180f) delta += 360f
                                    lastAngle = a
                                    turned += delta
                                    scrubTurn = turned
                                    val target = startPosition + (turned / 360f * duration).toLong()
                                    onScrub(target.coerceIn(0L, duration))
                                    change.consume()
                                }
                                if (cancelArmed) {
                                    onScrubCancel()
                                    // 取消:唱片转回按下时的角度,拧过的那一段不作数。
                                    scope.launch {
                                        Animatable(scrubTurn).animateTo(0f) { scrubTurn = value }
                                        discGesture = false
                                    }
                                } else {
                                    onScrubEnd()
                                    // 拧过的角度并进唱片本身的角度:松手后它从手放开的地方接着转。
                                    scope.launch {
                                        angle.snapTo(angle.value + scrubTurn)
                                        scrubTurn = 0f
                                        discGesture = false
                                    }
                                }
                            }
                        },
                ) {
                    // 盘体、同心纹路、轴孔。纹路的透明度低到几乎看不见是**故意**的:
                    // 它不是给人读的装饰,是让旋转成为可见事实——纯色圆盘转起来和静止的一模一样。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = angle.value + scrubTurn }
                            .drawBehind {
                                val r = size.minDimension / 2f
                                val c = Offset(size.width / 2f, size.height / 2f)
                                drawCircle(FixedColors.VinylBody, radius = r, center = c)
                                // 纹路只画在盘体那一圈(标签纸之外),画进标签底下是白费。
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
                    // 浮在唱片正中,不进布局流,见 CancelPill。排在转动的那一层外面:它是提示,
                    // 不是唱片上的字,不跟着转。
                    // 写全名:外层是 Column,不写的话解析到 ColumnScope 那个重载,那个在 Box 里调不了。
                    androidx.compose.animation.AnimatedVisibility(
                        visible = cancelArmed,
                        enter = fadeIn() + scaleIn(initialScale = CancelPillEnterScale),
                        exit = fadeOut() + scaleOut(targetScale = CancelPillEnterScale),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        CancelPill()
                    }
                }
            }
            // 整屏的主角是这张唱片,标题跟着抬一档到 titleLarge —— 听视频页一屏只有一条内容,
            // 不像列表要压字号换密度。
            Text(
                item.title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.Loose),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                modifier = Modifier.padding(top = Spacing.Tight),
            ) {
                Text(
                    item.upName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val sourcePosition = state.queue?.sourcePosition
                val sourceTotal = state.queue?.sourceTotal
                if (sourcePosition != null && sourceTotal != null) {
                    // 队列位置和 UP 名同行:它是"这是第几条",属于同一句话的后半截,
                    // 单独占一行会让唱片和进度条之间空出一整行。
                    //
                    // 数的是在整份来源里的位置,不是 playlist 下标:队列只读了来源的一段,
                    // 拿已读条数当 M 会让三千条的 UP 读成"共 40 条"。来源给不出总数时不显示。
                    Text(
                        "$sourcePosition / $sourceTotal",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}


/**
 * 对称五格:`[倍速] [上一条] [▶] [下一条] [定时]`,播放键在几何中心。每一格套一个
 * `weight(1f)` 的 `Box` 而不是让内容自然排开——这样中间那格永远是行宽的正中央,
 * 不会因为两侧内容宽度不一样(倍速带文字、定时只是个图标)把播放键推偏。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    loading: Boolean,
    speed: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    sleepTimer: SleepTimerState,
    /** 当前这条还要放多久,已按倍速折算;拿不到时长时为 null。见 [sleepTimerLabel]。 */
    itemRemainingMillis: Long?,
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        // **这一行和视频播放器是同一套控件**:倍速、定时是 chip(带容器、写着当前档位),上一条/
        // 下一条是带容器的图标按钮,正中是那颗会变形的播放键(见 ui/player/PlayerControls.kt)。
        // 同一个播放器两个壳,按钮长两样的话,从看视频切到听视频像是换了一个应用。
        var speedSheetOpen by remember { mutableStateOf(false) }
        val speedLabel = stringResource(Res.string.player_speed)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            ControlButton(
                expanded = speedSheetOpen,
                onClick = { speedSheetOpen = true },
                label = formatSpeed(speed),
                icon = { tint ->
                    Icon(Icons.Filled.Speed, speedLabel, tint = tint, modifier = Modifier.size(ChipIconSize))
                },
            )
        }
        // 倍速面板和视频播放器那块是同一份内容:倍速与音质两段。听视频没有画面,清晰度不给;
        // 字幕在顶栏的歌词按钮上。
        if (speedSheetOpen) {
            PaneSheet(onDismissRequest = { speedSheetOpen = false }) {
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
                )
                Spacer(modifier = Modifier.height(Spacing.Loose))
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            PlayerIconButton(
                onClick = onPrevious,
                icon = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(Res.string.player_previous),
                enabled = hasPrevious,
                iconSize = SkipIconSize,
                modifier = Modifier.size(SkipButtonSize),
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // 和视频画面正中那颗是同一个组件:停着是 primary 的正圆,放着变成圆角方块,取流时
            // 变回正圆、里面转加载指示 —— 加载不再是换掉按钮的另一个圈,按钮所在的这一格也就
            // 不会在取流那一瞬间塌下去。
            CenterPlayButton(
                isPlaying = isPlaying,
                loading = loading,
                // 不用全屏那一档(72dp,放着时 83dp 宽):控制行五等分,360dp 宽的屏上每格
                // 只有 65.6dp。
                large = false,
                onClick = onPlayPause,
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            PlayerIconButton(
                onClick = onNext,
                icon = Icons.Filled.SkipNext,
                contentDescription = stringResource(Res.string.player_next),
                enabled = hasNext,
                iconSize = SkipIconSize,
                modifier = Modifier.size(SkipButtonSize),
            )
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val label = sleepTimerLabel(sleepTimer, itemRemainingMillis)
            val sleepLabel = stringResource(Res.string.sleep_timer_off)
            // **计时中就只剩数字,图标让位,不是在图标下面再挂一行。** 挂一行的写法会让整条
            // 控制行在开始计时的那一刻长高一截,播放键跟着往下跳。同一枚 chip 里图标与数字
            // 二选一,计时中换成选中态的底色,行高恒定。
            ControlButton(
                expanded = label != null,
                onClick = onOpenSleepTimer,
                label = label,
                icon = { tint ->
                    if (label == null) {
                        Icon(Icons.Filled.Bedtime, sleepLabel, tint = tint, modifier = Modifier.size(ChipIconSize))
                    }
                },
            )
        }
    }
}

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
@Composable
private fun QueueSheetContent(
    queue: QueueUiState,
    playing: Boolean,
    onToggleShuffle: () -> Unit,
    onSelectEpisode: (EpisodeTarget) -> Unit,
    onRetryQueue: () -> Unit,
    onOpenQueueSource: (QueueSource) -> Unit,
) {
    val shuffled = queue.shuffled
    Column(modifier = Modifier.fillMaxWidth()) {
        // 标题是这份队列的来源,和详情页那一段的标题行是同一个组件、同一个判据:合集/系列点得进
        // 目录,UP 投稿之类没有目录页的不给入口。来源名还没有时退回「播放队列」。
        //
        // **不带条数**。条数在唱片页的「N / M」里,而且它在这里没有可操作性 —— 知道有 23 条,
        // 既不改变要不要展开,也不改变点哪一条。
        SectionHeader(
            title = queue.sourceLabel.ifEmpty { stringResource(Res.string.listen_queue_title) },
            onTitleClick = queue.source?.let { source -> { onOpenQueueSource(source) } },
            modifier = Modifier.padding(start = Spacing.Comfortable, end = Spacing.Tight),
        ) {
            // 靠右:左边是标题,顺序切换是对整个列表的操作,和标题分列两端读起来是一组。
            //
            // **图标跟着状态换,和文字说的是同一件事。** 这里原先恒定是 `Shuffle`:文字写着
            // 「顺序播放」而旁边画着交叉的箭头,两个符号一个说顺序、一个说随机,而它们本来
            // 是同一个读数。图标不给 contentDescription —— 文字已经念出来了。
            val orderLabel =
                stringResource(if (shuffled) Res.string.queue_order_shuffle else Res.string.queue_order_sequential)
            TextButton(
                onClick = onToggleShuffle,
                // 读屏念的是「顺序播放,按钮」,听起来像"按下去会变成顺序播放",而实际相反。
                // stateDescription 把这句归到状态那一栏,念出来是「按钮,顺序播放」。
                modifier = Modifier.semantics { stateDescription = orderLabel },
            ) {
                Icon(
                    if (shuffled) Icons.Filled.Shuffle else Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = null,
                    modifier = Modifier.size(OrderIconSize),
                )
                Text(orderLabel, modifier = Modifier.padding(start = Spacing.Hair))
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
                contentPadding = PaddingValues(
                    horizontal = Spacing.Comfortable,
                    vertical = Spacing.Tight,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 定时停止:一张底部 sheet,**点一下就生效并收起**,没有「确认」。
 *
 * 原先是对话框 + 滑块 + 确认:10 到 120 分钟、5 分钟一档,拖到想要的那一档再按确认,三步做
 * 一件事;而睡前定时要的几乎总是那么几个整数。现在是一片预设(15 到 120 分钟)加「播完当前」,
 * 选中哪个就是哪个。两种定时互斥,摆在同一片单选里,它们是"什么时候停"这一个问题的答案。
 *
 * 顶上是一块闹钟式的钟面([SleepClock]),下面是预设。当前生效的那一档亮着。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SleepTimerDialog(
    sleepTimer: SleepTimerState,
    /** 当前这条还要放多久,已按倍速折算。「播完当前视频」时钟面上显示它。 */
    itemRemainingMillis: Long?,
    onSet: (SleepTimerMode) -> Unit,
    onAdjust: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val mode = sleepTimer.mode
    val choose: (SleepTimerMode) -> Unit = {
        onSet(it)
        onDismiss()
    }
    PaneSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.Comfortable, end = Spacing.Comfortable, bottom = Spacing.Loose),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            SleepClock(
                mode = mode,
                remainingMillis = when (mode) {
                    is SleepTimerMode.After -> sleepTimer.remainingMillis
                    SleepTimerMode.EndOfItem -> itemRemainingMillis
                    SleepTimerMode.Off -> null
                },
                onAdjust = onAdjust,
                onCancel = { choose(SleepTimerMode.Off) },
            )

            Spacer(modifier = Modifier.height(Spacing.Hair))
            SleepPresets.chunked(SleepPresetColumns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                    row.forEach { minutes ->
                        ToggleButton(
                            checked = (mode as? SleepTimerMode.After)?.minutes == minutes,
                            onCheckedChange = { choose(SleepTimerMode.After(minutes)) },
                            modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
                        ) {
                            Text(stringResource(Res.string.sleep_timer_minutes, minutes))
                        }
                    }
                }
            }
            ToggleButton(
                checked = mode == SleepTimerMode.EndOfItem,
                onCheckedChange = { choose(SleepTimerMode.EndOfItem) },
                modifier = Modifier.fillMaxWidth().semantics { role = Role.RadioButton },
            ) {
                Text(stringResource(Res.string.sleep_timer_end_of_item_option))
            }
        }
    }
}

/**
 * 定时面板的顶栏:左边是钟面,右边是控件,像一只闹钟。
 *
 * **左边**有时间就是一个大的瘦高数字,不另写"还剩":一个在走的数本身就说明了。
 * - 按时长:剩余时间。
 * - 播完当前视频:这一条还剩多久。
 * - 没有定时(或这一条的时长还没拿到):没有数可写,这个位置写面板标题「定时关闭」。
 *
 * **右边**的控件一直在,不可用时置灰,不隐藏:位置固定,人记得住它们在哪;有无定时之间
 * 切换时这一行也不变形。
 * - 「−1」「+1」挪的是正在走的倒计时本身(秒数原样保留,见 SleepTimer.extend),点了不收
 *   面板 —— 微调就是要连着按几下、看着数字变。只在按时长计时时可用。
 * - 「取消定时」在它们下面。取消在这里是一个动作,不是关掉面板;关面板用下滑或点外面。
 *
 * 这一行的高度由右边那两层控件定,比大数字高,所以左边在标题和数字之间换时整行不跳。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SleepClock(
    mode: SleepTimerMode,
    remainingMillis: Long?,
    onAdjust: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    val adjustable = mode is SleepTimerMode.After
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(modifier = Modifier.weight(1f)) {
            if (remainingMillis != null) {
                Text(
                    text = formatDurationMillis(remainingMillis),
                    style = TextStyle(
                        fontFamily = rememberClockFont(),
                        fontSize = CountdownSize,
                        lineHeight = CountdownSize,
                        fontFeatureSettings = "tnum",
                    ),
                    maxLines = 1,
                )
            } else {
                Text(
                    stringResource(Res.string.sleep_timer_off),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                val less = stringResource(Res.string.sleep_timer_less)
                val more = stringResource(Res.string.sleep_timer_more)
                FilledTonalIconButton(
                    onClick = { onAdjust(-1) },
                    // 剩余不到两分钟时再减就只剩一分钟的下限了(见 SleepTimer),按不动比按了没反应清楚。
                    enabled = adjustable && (remainingMillis ?: 0L) > SleepAdjustFloorMillis,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.semantics { contentDescription = less },
                ) {
                    Text("−1", style = MaterialTheme.typography.labelLarge)
                }
                FilledTonalIconButton(
                    onClick = { onAdjust(1) },
                    enabled = adjustable,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.semantics { contentDescription = more },
                ) {
                    Text("+1", style = MaterialTheme.typography.labelLarge)
                }
            }
            TextButton(onClick = onCancel, enabled = mode != SleepTimerMode.Off) {
                Text(stringResource(Res.string.sleep_timer_cancel))
            }
        }
    }
}

/** 倒计时的字号。字形是瘦高的那一种(ClockAxes),和听视频那块矮胖的读数正好相反。 */
private val CountdownSize = 72.sp

/** 定时的几档。睡前常用的就这几个整数;要更细的档位时,「播完当前」往往才是想要的。 */
private val SleepPresets = listOf(15, 30, 45, 60, 90, 120)
private const val SleepPresetColumns = 3

/** 剩余不到这么多时「−1」按不动:再减就撞上 SleepTimer 那一分钟的下限。 */
private const val SleepAdjustFloorMillis = 120_000L


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
    Surface(color = MaterialTheme.colorScheme.surface) {
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
            IconButton(onClick = onBack) {
                // AutoMirrored:supportsRtl 开着,RTL 语言下返回箭头必须翻过来。
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                )
            }
            VideoCover(
                url = item.coverUrl,
                modifier = Modifier.width(LyricsHeaderCoverWidth).align(Alignment.CenterVertically),
            )
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).align(Alignment.CenterVertically),
            )
            trailing()
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
    /** 用户正在拖动下方进度条时的目标位置,和页面顶层 `displayPosition` 用的是同一个值——
     *  拖拽期间要立刻跟手,不能等下一次 100ms 轮询。 */
    dragPosition: Long?,
    onSeekTo: (Long) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var polledPosition by remember { mutableLongStateOf(player.currentPosition) }
    LaunchedEffect(player) {
        while (true) {
            polledPosition = player.currentPosition
            delay(LyricsPollIntervalMillis)
        }
    }
    val positionMillis = dragPosition ?: polledPosition

    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 空白处点一下回唱片。铺在最底层,文字自己的点击(seek)盖在它上面。
            Box(modifier = Modifier.fillMaxSize().clickable(role = Role.Button, onClick = onBack))
            LyricsList(
                cues = cues,
                positionMillis = positionMillis,
                onSeekTo = onSeekTo,
                dragging = dragPosition != null,
                // 上下留白:歌词原先直接顶着上面的信息行和下面的控制区,滚动的字紧贴着两条
                // 边界出现和消失,像被裁掉而不是滚出去。留白加在这里而不是 LazyColumn 的
                // contentPadding 上 —— 后者是给"当前句居中"用的(halfViewport),两者混在
                // 一个参数里,以后改任何一个都要重新算另一个。
                modifier = Modifier.fillMaxSize().padding(vertical = Spacing.Loose),
            )
            // 字幕按钮在顶栏里(见 ListenScreen 的 topBar),点它退出歌词。
        }
    }
}

/**
 * 歌词滚动列表。当前句高亮(满色 + 抬一档字号),前后句降透明度,居中,跟播滚动,
 * 点某一句直接跳过去。
 *
 * 定位用二分(见 [dev.bilby.player.indexNear] 的注释),不逐帧线性扫——一条长视频几千条
 * cue,跟着轮询线性扫是白烧 CPU。落在两句之间的空档里时 [indexNear] 给最近讲完的那一句:
 * 滚动跟着它走,但不高亮它——句子已经念完了,继续标它是错的信息。
 *
 * **居中滚动只做一次 `animateScrollToItem`。** 旧写法是切集清单那一套(见
 * `ui/player/EpisodeList.kt`):先把目标项滚到贴顶,再读它的实际高度补一次居中动画。两次动画背靠背
 * 播放,在切歌这种偶发场景不明显,歌词是每一句都触发,肉眼看到的就是"跳一下、再挪一下"的
 * 一跳一跳。改成用 [BoxWithConstraints] 在真正开始滚动之前就拿到视口高度,把它的一半设成
 * `contentPadding` 的上下边——`animateScrollToItem(index)` 把目标项滚到贴着这段留白的
 * 末尾时,视觉上正好落在视口中间,一次动画到位。副作用是这个高度在真正滚动前就已知,
 * 不再需要等 `LazyColumn` 完成一次布局才能读 `layoutInfo`,切集清单里那个等布局就绪的
 * `snapshotFlow` 也就不需要了(那是给"滚完再读实际高度"这一步准备的,这里没有那一步)。
 *
 * 颜色用主题色,不再是 [FixedColors.OnMedia] 上兑 alpha。那一套的前提是"背后是调暗后的
 * 任意封面",封面去掉之后前提没了:底就是 surface,当前句用强调色 `primary`、其余
 * `onSurfaceVariant`,对比度由主题保证,不随视频封面变化。当前句同时靠颜色和字重两维区分,
 * 只靠其中一维在小字号或高亮度环境下都不够。
 */
@Composable
private fun LyricsList(
    cues: List<SubtitleCue>,
    positionMillis: Long,
    onSeekTo: (Long) -> Unit,
    /** 用户正按着下面的进度条。跟随方式不同,见下面那个 [LaunchedEffect]。 */
    dragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val nearIndex = cues.indexNear(positionMillis)
    val highlightIndex = nearIndex.takeIf { it >= 0 && positionMillis < cues[it].toMillis }
    // 列表第 0 项是片头那条虚拟空行(见下面 `item`),所以 cue 的下标要整体加一。
    // 第一句开始之前 [indexNear] 给 -1,加一之后正好落在虚拟行上。
    val focusIndex = nearIndex + 1

    // **拖进度条时瞬时定位,不补间。** 平时一句切一次,补间是对的 —— 它让"换了一句"这件事
    // 看得出来。拖动时 nearIndex 每帧都在变,`animateScrollToItem` 会被不断打断重启,每次
    // 都从头缓动,结果是歌词追不上拇指、看起来像没跟着拖。松手后 dragging 变回 false,
    // 这个 effect 重跑一次,用补间落到最终位置。
    LaunchedEffect(focusIndex, dragging) {
        if (dragging) listState.scrollToItem(focusIndex) else listState.animateScrollToItem(focusIndex)
    }

    // **被滚走之后自己回来。** 上面那个 effect 只在 focusIndex 变化时跑,也就是每句一次;
    // 在两句之间的几秒里,任何把列表推走的东西(拉队列时蹭到的一下滑动、误触的一次惯性滚动)
    // 都不会被纠正,当前句就那么停在屏幕外,直到下一句才回来 —— 看起来像"高亮丢了"。
    //
    // 只在**滚动停下来、且当前句已经不在可见范围内**时才纠正:还看得见就不动,免得跟正在
    // 往前后翻看的人抢列表。
    LaunchedEffect(listState, focusIndex) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == focusIndex }
                if (!visible) listState.animateScrollToItem(focusIndex)
            }
    }

    BoxWithConstraints(modifier = modifier) {
        val halfViewport = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = Spacing.Loose, vertical = halfViewport),
        ) {
            // **片头那段没有歌词的时间轴,焦点落在这条虚拟空行上。**
            // 它不显示任何东西,但占一整行的高度,于是"当前句永远在正中"这条在片头也成立:
            // 第一句到来时是从这里平滑滚过去,而不是列表先贴着顶、等第一句出现再顿一下。
            // 用空串 Text 而不是定高 Spacer —— 行高跟着同一套字号走,字号或 fontScale 变了
            // 不需要再对一遍魔数。
            item(key = LeadInKey) {
                Text(
                    "",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.Tight),
                )
            }
            itemsIndexed(cues, key = { _, cue -> cue.fromMillis }) { index, cue ->
                val highlighted = index == highlightIndex
                Text(
                    cue.text,
                    textAlign = TextAlign.Center,
                    style = if (highlighted) {
                        MaterialTheme.typography.titleMediumEmphasized
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    // 强调色 + 字号 + 字重三维一起区分。颜色单独一维在强光下不够,
                    // 字号单独一维在长句折行时看不出来。
                    fontWeight = if (highlighted) FontWeight.Bold else null,
                    color = if (highlighted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onSeekTo(cue.fromMillis) }
                        .padding(vertical = Spacing.Tight),
                )
            }
        }
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
    Box {
        IconButton(onClick = { if (lyricsShown) onSelect("") else expanded = true }) {
            Icon(
                Icons.Filled.Subtitles,
                contentDescription = stringResource(Res.string.player_subtitle),
                tint = if (currentLan.isNotEmpty()) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
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

/**
 * 距离真正停下来还有多久。不定时时返回 null。
 *
 * **只给一个数,不拼文案。** 以前是「58:12 · 播完这条」,那两截读起来像两件事,而人想知道的
 * 只有一件:什么时候会静下来。两种模式各有一个显然的答案 —— 定时是它自己的剩余,播完这条
 * 是这一条的剩余。模式互斥之后这里不再需要在两者之间取舍(见 [SleepTimerMode])。
 *
 * @param itemRemainingMillis 当前这条还要放多久,**已按倍速折算**。拿不到时长时为 null。
 */
@Composable
private fun sleepTimerLabel(state: SleepTimerState, itemRemainingMillis: Long?): String? = when (state.mode) {
    SleepTimerMode.Off -> null
    is SleepTimerMode.After ->
        formatRemaining(state.remainingMillis ?: (state.mode.minutes * 60_000L))
    // 时长还没到手(刚起播、还在取流)时先用文案占位:这一格退回图标的话,就和"没设定时"
    // 长得一模一样了。拿到时长后自动换成数字。
    SleepTimerMode.EndOfItem -> itemRemainingMillis?.takeIf { it > 0L }?.let { formatRemaining(it) }
        ?: stringResource(Res.string.sleep_timer_end_of_item)
}

/**
 * 定时剩余用「分:秒」,不是 `formatDurationMillis` 的「时:分:秒」。
 *
 * 定时上限是 120 分钟,所以分位最多三位,`120:00` 比 `1:59:55` 短两个字符 —— 而它要挤在控制行
 * 五等分里的一格,长度就是能不能读全的关键。语义也更直接:这里问的是"还有多久",答案本来就是
 * 一个分钟数,拆成时和分反而要在心里再加一次。
 */
private fun formatRemaining(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"
