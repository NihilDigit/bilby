package dev.bilby.ui.listen

import android.os.Build
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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import dev.bilby.R
import dev.bilby.data.VideoPart
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
import dev.bilby.ui.components.CompactVideoRow
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.components.SubtitleTrackMenu
import dev.bilby.ui.components.VideoCover
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** 定时 Slider 的范围与步进:10~120 分钟,每格 5 分钟。 */
private val SLEEP_TIMER_RANGE = 10f..120f
private const val SLEEP_TIMER_STEPS = 21
private const val SLEEP_TIMER_DEFAULT_MINUTES = 30f

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

/** 歌词区上下各留出的空白。 */
private val LyricsEdgeSpacing = 24.dp

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
 * 听视频界面。播放器归 [dev.bilby.player.AudioPlaybackService] 所有,这里只读状态、发命令
 * (DESIGN 2.4b「一个播放状态,两个 UI」)——不 prepare、不 release,和看视频共用同一个播放器。
 *
 * 比看视频少两样:不渲染画面、不能调画质,其余(队列、连播、顺序/随机、进度、倍速)是同一套。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(
    player: Player?,
    state: AudioPlaybackUiState,
    sleepTimer: SleepTimerState,
    queue: List<QueueItem>,
    onPlayQueueItem: (bvid: String) -> Unit,
    /**
     * 当前视频的分 P。**分 P 与队列是两条并列的轴**(CLAUDE.md:多 P 视频和合集是两回事):
     * 队列装的是不同 bvid 的视频,分 P 是同一个 bvid 下的不同 cid。播放页两条都给,
     * 听视频这边一度只有队列 —— 于是多 P 视频进来就换不了 P。
     *
     * DESIGN 2.4b 说的是"一个播放状态,两个 UI",两个 UI 能去的地方本来就该一样,
     * 差别只在有没有画面。**分 P 留在页面上、不进队列 Sheet**:它是当前这条稿件的内部
     * 导航,和队列是并列的两条轴,塞进同一个 Sheet 会读成"分 P 也是队列的一部分"。
     */
    parts: List<VideoPart> = emptyList(),
    currentCid: Long = 0L,
    onPlayPart: (cid: Long) -> Unit = {},
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    /** [minutes] 为 null 表示不设时长;两个参数独立,见 [dev.bilby.player.SleepTimer.start]。 */
    onSleepTimer: (minutes: Int?, finishCurrentItem: Boolean) -> Unit,
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
    var duration by remember { mutableLongStateOf(0L) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var resumeAfterDrag by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }
    var sleepSheetOpen by remember { mutableStateOf(false) }

    // Media3 的 Player 没有位置回调,只能轮询;和 BilbyPlayer 一致的 500ms 间隔。
    LaunchedEffect(player) {
        while (true) {
            if (dragPosition == null) position = player?.currentPosition ?: 0L
            duration = player?.duration?.coerceAtLeast(0) ?: 0L
            delay(500)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: androidx.media3.common.PlaybackParameters) {
                speed = playbackParameters.speed
            }
        }
        player?.let {
            speed = it.playbackParameters.speed
            it.addListener(listener)
        }
        onDispose { player?.removeListener(listener) }
    }

    val displayPosition = dragPosition ?: position

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
        topBar = {
            val item = state.current
            if (showLyrics && item != null) {
                LyricsHeader(item = item, onBack = onBack)
            } else {
                BilbyTopBar(title = "", onBack = onBack)
            }
        },
    ) { insets ->
        if (player == null || state.current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(insets).padding(Spacing.Spacious),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.listen_not_playing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        val sheetState = rememberBottomSheetScaffoldState()

        // 队列没建过(queueSize == 0)时收起到 0——一个空把手拉起来什么都没有,是纯噪声。
        // 建过之后把手常显,不需要再点一行入口才能看到它,见 VideoScreen 找相关那个 sheet
        // 的同一个判据。
        val peek = if (state.queueSize > 0) QueueHandleHeight else 0.dp

        BottomSheetScaffold(
            scaffoldState = sheetState,
            modifier = Modifier.fillMaxSize().padding(insets),
            sheetPeekHeight = peek,
            sheetContent = {
                QueueSheetContent(
                    queue = queue,
                    currentBvid = state.current?.bvid,
                    shuffled = state.shuffled,
                    onToggleShuffle = onToggleShuffle,
                    onPlayQueueItem = onPlayQueueItem,
                )
            },
        ) { sheetInsets ->
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
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // 进度条 + 控制行贴底,不跟着有没有字幕挪位置——两种呈现共用同一段。
                Column(modifier = Modifier.padding(horizontal = Spacing.Comfortable)) {
                    // 和播放器用同一个进度条组件:两处的拖拽语义完全一样,分开写迟早各自漂移。
                    SeekBar(
                        position = displayPosition,
                        duration = duration,
                        onSeekStart = {
                            resumeAfterDrag = player.isPlaying
                            player.pause()
                        },
                        onSeekTo = { dragPosition = it },
                        onSeekFinished = {
                            dragPosition?.let { target ->
                                player.seekTo(target)
                                position = target
                                if (resumeAfterDrag) player.play()
                            }
                            dragPosition = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // **两个时间自动缩到放得下为止。** 长视频是 `h:mm:ss`,字体缩放调大之后
                    // 这一行放不下 —— 默认行为是换行,表现成最后一位数字掉到第二行,还把整条
                    // 控制区顶高一行;禁掉换行则变成截断,一个时间少一位比换行更糟。
                    //
                    // 两边各 `weight(1f)`,不是 `SpaceBetween`:autoSize 要有界宽度才知道该缩
                    // 多少,松约束下它永远认为放得下。对齐改由 textAlign 给,视觉位置不变。
                    Row(modifier = Modifier.fillMaxWidth()) {
                        val timeAutoSize = TextAutoSize.StepBased(
                            minFontSize = 9.sp,
                            maxFontSize = MaterialTheme.typography.labelSmall.fontSize,
                        )
                        Text(
                            text = formatDurationMillis(displayPosition),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            autoSize = timeAutoSize,
                            textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatDurationMillis(duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            autoSize = timeAutoSize,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                PlaybackControls(
                    isPlaying = state.isPlaying,
                    loading = state.loading,
                    speed = speed,
                    hasPrevious = state.positionInQueue > 1,
                    hasNext = state.positionInQueue in 1 until state.queueSize,
                    sleepTimer = sleepTimer,
                    onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onSpeedChange = { player.setPlaybackSpeed(it) },
                    onOpenSleepTimer = { sleepSheetOpen = true },
                )

                // 失败就摆在控制条底下,不悄悄跳到下一条:跳过之后用户只看到"忽然换了一条",
                // 而原因一个字都没留下。跳还是再试由这里交回给用户。
                state.error?.let { message ->
                    FailureRow(message = message, retrying = state.loading, onRetry = onRetry)
                }

                // 分 P 排在最后、紧贴队列把手之上:它是"正在放的这一条"的内部结构,和队列是
                // 两条并列的轴(见上面参数上的注释),不能塞进队列 Sheet。单 P 视频不显示——
                // 一个只有 P1 的选择器是纯噪声。
                if (parts.size > 1) {
                    PartRow(parts = parts, currentCid = currentCid, onPlayPart = onPlayPart)
                }
            }
        }
    }

    if (sleepSheetOpen) {
        SleepTimerDialog(
            sleepTimer = sleepTimer,
            onSet = onSleepTimer,
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
    modifier: Modifier = Modifier,
) {
    val item = state.current ?: return
    // 外面套一层 Box,只为让字幕按钮的参照物是**传进来的整块区域**,和歌词模式一致。
    // 挂在下面那个 Column 上不行:它自带 Loose/Comfortable 的内边距,按钮会被一并推进去,
    // 于是两个模式里同一个按钮仍然落在两个位置 —— 而它是同一个按钮的两个状态,
    // 来回切时应该原地不动。
    Box(modifier = modifier) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.Loose, vertical = Spacing.Comfortable),
        ) {
            // BoxWithConstraints 而不是 fillMaxWidth(fraction) + aspectRatio(1f):唱片要占的是
            // "剩余空间"里较短的那一边,横屏或者窗口矮的时候按宽度定size会把圆形顶出可视区域。
            BoxWithConstraints(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                val discSize = min(maxWidth, maxHeight) * DiscSizeFraction

                // 播放时缓慢转动,暂停时停在当前角度(不归零——归零会让"暂停"看起来像"换了一张")。
                //
                // 曾经以"息屏后台是常态,旋转每帧重绘不值"为由不做这个动画。那条理由是错的:
                // 页面不可见时应用根本不出帧,旋转的开销只存在于用户正盯着它看的时候,
                // 而那恰好是它唯一有价值的时候。
                val angle = remember { Animatable(0f) }
                LaunchedEffect(state.isPlaying, speed) {
                    if (!state.isPlaying) return@LaunchedEffect
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

                Box(modifier = Modifier.size(discSize)) {
                    // 盘体、同心纹路、轴孔。纹路的透明度低到几乎看不见是**故意**的:
                    // 它不是给人读的装饰,是让旋转成为可见事实——纯色圆盘转起来和静止的一模一样。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationZ = angle.value }
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
                if (state.queueSize > 0) {
                    // 队列位置和 UP 名同行:它是"这是第几条",属于同一句话的后半截,
                    // 单独占一行会让唱片和进度条之间空出一整行。
                    Text(
                        "${state.positionInQueue} / ${state.queueSize}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        SubtitleTrackCornerButton(
            tracks = subtitleTracks,
            currentLan = subtitleLan,
            onSelect = onSelectSubtitle,
            modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.Cozy),
        )
    }
}


/**
 * 对称五格:`[倍速] [上一条] [▶] [下一条] [定时]`,播放键在几何中心。每一格套一个
 * `weight(1f)` 的 `Box` 而不是让内容自然排开——这样中间那格永远是行宽的正中央,
 * 不会因为两侧内容宽度不一样(倍速带文字、定时只是个图标)把播放键推偏。
 */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    loading: Boolean,
    speed: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    sleepTimer: SleepTimerState,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onOpenSleepTimer: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        var speedMenuOpen by remember { mutableStateOf(false) }
        val speedLabel = stringResource(R.string.player_speed)
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // 只有数字,不配图标:「1x」自己已经读得出是倍速,旁边那个表盘图标不添信息,
            // 却让这一格比同排其余四个纯图标按钮宽出一截。读屏靠 contentDescription 补。
            TextButton(
                onClick = { speedMenuOpen = true },
                modifier = Modifier.semantics { contentDescription = speedLabel },
            ) {
                Text(formatSpeed(speed))
            }
            DropdownMenu(expanded = speedMenuOpen, onDismissRequest = { speedMenuOpen = false }) {
                SPEED_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(formatSpeed(option)) },
                        onClick = { speedMenuOpen = false; onSpeedChange(option) },
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(onClick = onPrevious, enabled = hasPrevious) {
                Icon(
                    Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.player_previous),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (loading) {
                Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (isPlaying) R.string.player_pause else R.string.player_play,
                        ),
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            IconButton(onClick = onNext, enabled = hasNext) {
                Icon(
                    Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.player_next),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            val label = sleepTimerLabel(sleepTimer)
            val sleepLabel = stringResource(R.string.sleep_timer_off)
            // **计时中就只剩数字,图标让位,不是在图标下面再挂一行。** 挂一行的写法会让整条
            // 控制行在开始计时的那一刻长高一截,播放键跟着往下跳 —— 一个设定动作不该挪动
            // 旁边四个按钮的位置。换成同一格里图标与数字二选一,行高恒定。
            if (label != null) {
                TextButton(
                    onClick = onOpenSleepTimer,
                    // **内边距压到最小。** 控制行是五个等宽格子,每格约 1/5 屏宽,而 TextButton
                    // 默认左右各 24dp —— `1:59:55` 这种 h:mm:ss 在剩下的空间里放不下,默认行为
                    // 是换行,于是最后一位掉到第二行、整条控制行跟着长高一行。
                    contentPadding = PaddingValues(horizontal = Spacing.Hair),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = sleepLabel },
                ) {
                    Text(
                        label,
                        color = MaterialTheme.colorScheme.primary,
                        // 压完边距仍可能不够(大字号 + h:mm:ss),再让它自己缩。缩比换行和截断
                        // 都好:时间少一位就是错的信息,而换行会挪动旁边四个按钮。
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 9.sp,
                            maxFontSize = MaterialTheme.typography.labelLarge.fontSize,
                        ),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                IconButton(onClick = onOpenSleepTimer) {
                    Icon(
                        Icons.Filled.Bedtime,
                        contentDescription = sleepLabel,
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
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
            text = if (retrying) stringResource(R.string.listen_retrying, message) else message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        // 重试中不给按钮:此刻按下去只会打断已经在跑的那次。
        if (!retrying) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
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
    queue: List<QueueItem>,
    currentBvid: String?,
    shuffled: Boolean,
    onToggleShuffle: () -> Unit,
    onPlayQueueItem: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // 标题只写名字,**不带条数**。条数在唱片页的「N / M」里,而且它在这里没有可操作性
        // ——知道有 23 条,既不改变要不要展开,也不改变点哪一条;它原先还独占一整行,
        // 那一行的高度还不如让队列多露一截出来。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(start = Spacing.Comfortable, end = Spacing.Tight),
        ) {
            Text(
                stringResource(R.string.listen_queue_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 靠右:左边是标题,顺序切换是对整个列表的操作,和标题分列两端读起来是一组。
            TextButton(onClick = onToggleShuffle) {
                Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    stringResource(if (shuffled) R.string.queue_order_shuffle else R.string.queue_order_sequential),
                    modifier = Modifier.padding(start = Spacing.Hair),
                )
            }
        }
        QueueList(
            queue = queue,
            currentBvid = currentBvid,
            onPlayQueueItem = onPlayQueueItem,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 定时停止。**是 Dialog 不是 BottomSheet**:定时长是一次性的、要确认的决定,确认完就走;
 * BottomSheet 暗示的是"可以边看边调"。
 *
 * **值只在「确认」时提交。** 这里原先把 `onSet` 挂在 `Slider` 的 `onValueChange` 上,于是
 * 值只在滑块**移动**时才生效——打开时停在默认的 30 分钟,想要 30 就必须先拖走再拖回来,
 * 因为"没动"这个状态永远提交不了。默认值成了唯一选不中的值。顺带那个写法还会在拖动过程中
 * 每一帧重启一次计时器。
 *
 * 5 分钟一档由 `SLEEP_TIMER_STEPS` 保证(10..120 分 22 段),刻度点不画:22 个点连成一串
 * 比轨道本身还抢眼,而步进是 `SliderState` 自己管的,不靠画出来才生效。
 */
@Composable
private fun SleepTimerDialog(
    sleepTimer: SleepTimerState,
    onSet: (minutes: Int?, finishCurrentItem: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var minutes by remember {
        mutableFloatStateOf(
            (sleepTimer.mode as? SleepTimerMode.After)?.minutes?.toFloat() ?: SLEEP_TIMER_DEFAULT_MINUTES,
        )
    }
    var finishCurrentItem by remember { mutableStateOf(sleepTimer.finishCurrentItem) }
    val active = sleepTimer.mode != SleepTimerMode.Off || sleepTimer.finishCurrentItem

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sleep_timer_off)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sleep_timer_stop_in, minutes.roundToInt()),
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = minutes,
                    onValueChange = { minutes = it },
                    valueRange = SLEEP_TIMER_RANGE,
                    steps = SLEEP_TIMER_STEPS,
                    track = { sliderState ->
                        SliderDefaults.Track(sliderState = sliderState, drawTick = { _, _ -> })
                    },
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.Tight),
                ) {
                    Text(stringResource(R.string.sleep_timer_finish_switch))
                    Switch(checked = finishCurrentItem, onCheckedChange = { finishCurrentItem = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSet(minutes.roundToInt(), finishCurrentItem)
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.action_confirm))
            }
        },
        dismissButton = {
            Row {
                // 已经在计时时,「关闭定时」和「取消」是两件事:前者停掉计时,后者只是关掉
                // 这个弹窗、什么都不改。没在计时时前者没有对象,不出现。
                if (active) {
                    TextButton(
                        onClick = {
                            onSet(null, false)
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(R.string.sleep_timer_cancel))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

@Composable
private fun QueueList(
    queue: List<QueueItem>,
    currentBvid: String?,
    onPlayQueueItem: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // 列表按自然顺序摆着不动,切歌时让高亮那条滚到中间。随机播放不重排列表——
    // 列表跟着重排会让人找不到刚才看的那条在哪。
    LaunchedEffect(currentBvid, queue) {
        val index = queue.indexOfFirst { it.bvid == currentBvid }
        if (index < 0) return@LaunchedEffect
        // 这个 LazyColumn 现在挂在常驻的 BottomSheetScaffold 上,不再是每次打开都重新进组合的
        // ModalBottomSheet——但展开动画期间第一帧 layoutInfo 仍可能是空的,时序坑本身没变,
        // 等布局真的跑过一次(totalItemsCount > 0)再滚,不然滚动会扑空、居中那步被整个跳过。
        // 成本是零,删掉会退回原 bug,所以保留。
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        listState.animateScrollToItem(index)
        val info = listState.layoutInfo
        val row = info.visibleItemsInfo.firstOrNull { it.index == index }
        if (row != null) {
            listState.animateScrollToItem(index, -(info.viewportSize.height - row.size) / 2)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    ) {
        items(queue, key = { it.bvid }) { item ->
            CompactVideoRow(
                title = item.title,
                coverUrl = item.coverUrl,
                subtitle = item.upName,
                selected = item.bvid == currentBvid,
                onClick = { onPlayQueueItem(item.bvid) },
            )
        }
    }
}


/**
 * 歌词模式的顶栏:返回箭头 + 封面 + 标题(至多三行)。
 *
 * 只有这个模式需要它 —— 唱片模式下标题就在唱片正下方,顶栏留一个退出按钮就够。两个模式的
 * 顶栏因此不一样高,右上角那个字幕按钮的位置会跟着差一截;这是权衡过的:让歌词模式认得出
 * 在听哪一条,比让一个按钮在两个模式间纹丝不动更值。
 *
 * 不用 [BilbyTopBar]:`TopAppBar` 的高度是固定的一行,塞不下封面加三行标题。自己排一个 Row,
 * 状态栏内边距用 `TopAppBarDefaults.windowInsets` 取,和其余页面的顶栏对齐同一套。
 */
@Composable
private fun LyricsHeader(item: QueueItem, onBack: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(TopAppBarDefaults.windowInsets)
                .padding(end = Spacing.Comfortable, top = Spacing.Tight, bottom = Spacing.Tight),
        ) {
            IconButton(onClick = onBack) {
                // AutoMirrored:supportsRtl 开着,RTL 语言下返回箭头必须翻过来。
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
            VideoCover(url = item.coverUrl, modifier = Modifier.width(LyricsHeaderCoverWidth))
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
    player: Player,
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
            Box(modifier = Modifier.fillMaxSize().clickable(onClick = onBack))
            LyricsList(
                cues = cues,
                positionMillis = positionMillis,
                onSeekTo = onSeekTo,
                dragging = dragPosition != null,
                // 上下留白:歌词原先直接顶着上面的信息行和下面的控制区,滚动的字紧贴着两条
                // 边界出现和消失,像被裁掉而不是滚出去。留白加在这里而不是 LazyColumn 的
                // contentPadding 上 —— 后者是给"当前句居中"用的(halfViewport),两者混在
                // 一个参数里,以后改任何一个都要重新算另一个。
                modifier = Modifier.fillMaxSize().padding(vertical = LyricsEdgeSpacing),
            )
            // 和唱片页那个字幕按钮同一个位置、同一个图标,亮着表示字幕开着。点它 =
            // **选中「无字幕」**,退出歌词是这件事的结果,不是另一个动作。
            // 底下是 surface 不是封面,所以不套 scrim 底、不用 FixedColors —— 那一套是给
            // "压在画面上"的控件准备的,这一页已经没有画面了。
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.Cozy)) {
                Icon(
                    Icons.Filled.Subtitles,
                    contentDescription = stringResource(R.string.player_subtitle),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
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
 * **居中滚动只做一次 `animateScrollToItem`,不再"滚一次、量高度、补第二次"。** 旧写法是
 * `QueueList` 那一套:先把目标项滚到贴顶,再读它的实际高度补一次居中动画。两次动画背靠背
 * 播放,在切歌这种偶发场景不明显,歌词是每一句都触发,肉眼看到的就是"跳一下、再挪一下"的
 * 一跳一跳。改成用 [BoxWithConstraints] 在真正开始滚动之前就拿到视口高度,把它的一半设成
 * `contentPadding` 的上下边——`animateScrollToItem(index)` 把目标项滚到贴着这段留白的
 * 末尾时,视觉上正好落在视口中间,一次动画到位。副作用是这个高度在真正滚动前就已知,
 * 不再需要等 `LazyColumn` 完成一次布局才能读 `layoutInfo`,`QueueList` 里那个等布局就绪的
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
                        .clickable { onSeekTo(cue.fromMillis) }
                        .padding(vertical = Spacing.Tight),
                )
            }
        }
    }
}

/**
 * 字幕轨切换,浮在唱片 / 歌词区右上角。**归属播放器控件层,不是页面顶栏**:它和倍速、
 * 进度条是同一类东西,听视频模式下唱片占的正是播放器画面的位置,浮在它上面等价于全屏时
 * 浮在画面上的控件(docs/ui-style-guide.md §4.3)。唱片模式和歌词模式都要挂——没有它,
 * 字幕就只能从看视频页开,这个页面上永远打不开。没有可用轨时整个按钮不出现。
 *
 * 圆形 scrim 底 + [FixedColors.OnMedia] 图标,和 [SubtitleTrackMenu] 那份下拉菜单共用,
 * 与看视频控制条上的字幕按钮是同一功能的两具躯壳,见 SubtitleTrackMenu 上的注释。
 */
@Composable
private fun SubtitleTrackCornerButton(
    tracks: List<SubtitleTrack>,
    currentLan: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier
                .clip(CircleShape)
                .background(FixedColors.ScrimOnMedia),
        ) {
            Icon(
                Icons.Filled.Subtitles,
                contentDescription = stringResource(R.string.player_subtitle),
                tint = if (currentLan.isNotEmpty()) MaterialTheme.colorScheme.primary else FixedColors.OnMedia,
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

/** 组合出「58:12」「58:12 · 播完这条」「播完这条」三种读法;都不生效时返回 null。 */
@Composable
private fun sleepTimerLabel(state: SleepTimerState): String? {
    val duration = when (val mode = state.mode) {
        SleepTimerMode.Off -> null
        is SleepTimerMode.After -> state.remainingMillis?.let { formatRemaining(it) }
            ?: stringResource(R.string.sleep_timer_minutes, mode.minutes)
    }
    val finish = if (state.finishCurrentItem) stringResource(R.string.sleep_timer_end_of_item) else null
    return when {
        duration != null && finish != null -> "$duration · $finish"
        duration != null -> duration
        finish != null -> finish
        else -> null
    }
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


/**
 * 分 P 选择器。横排而不是竖排:分 P 通常几条到十几条,标题短,横着一行扫得完;
 * 竖排会把它撑成和下面的队列一样重的一块,而它只是当前这条视频的内部结构。
 */
@Composable
private fun PartRow(
    parts: List<VideoPart>,
    currentCid: Long,
    onPlayPart: (cid: Long) -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = Spacing.Comfortable),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        modifier = Modifier.padding(bottom = Spacing.Cozy),
    ) {
        itemsIndexed(parts, key = { _, part -> part.cid }) { index, part ->
            val selected = part.cid == currentCid
            FilterChip(
                selected = selected,
                onClick = { onPlayPart(part.cid) },
                label = {
                    Text(
                        "P${index + 1} ${part.title}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 160.dp),
                    )
                },
            )
        }
    }
}
