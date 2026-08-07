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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.widthIn
import dev.bilby.data.VideoPart
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.media3.common.Player
import dev.bilby.R
import dev.bilby.formatDurationMillis
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberBottomSheetScaffoldState
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.BilbyTopBar
import dev.bilby.ui.components.CompactVideoRow
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.components.SubtitleTrackMenu
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.player.AudioPlaybackUiState
import dev.bilby.player.QueueItem
import dev.bilby.player.SleepTimerMode
import dev.bilby.player.SleepTimerState
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.indexNear
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

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

/** 页面指示器:非当前那个点的呼吸周期。 */
private const val PageDotPulsePeriodMillis = 1_400

private val PageDotSize = 6.dp

/** 队列把手收起时的高度:够放系统默认抓手 + 一行「队列 · N」,不多不少。 */
private val QueueHandleHeight = 72.dp

/**
 * 歌词页单独的位置轮询间隔。整页其余部分(进度条、控制行)用的是 500ms
 * (见 `ListenScreen` 里那个 `LaunchedEffect(player)`),那是给数字和进度条挑的粒度;
 * 歌词逐句切换,500ms 的量化会让句子切换晚半秒且晚多久不固定,是"跳一下"的来源之一。
 * 100ms 对最短也有一两秒的句子够用,不需要拉到帧级——帧级驱动是弹幕那种场景才要的。
 */
private const val LyricsPollIntervalMillis = 100L

/** 歌词页背景的模糊半径。只在 API 31+ 生效,见 `LyricsView` 里的版本判断。 */
private val LyricsBackgroundBlurRadius = 24.dp

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

    // 必须自己给顶栏:听视频是播放页内的一个状态,VideoScreen 在这条分支上提前 return,
    // 外层那句 windowInsetsPadding(statusBars) 走不到 —— 不处理的话标题和返回箭头
    // 会直接压在状态栏的时钟上。用 Scaffold 而不是手贴 padding,和其余页面一致。
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BilbyTopBar(title = stringResource(R.string.listen_title), onBack = onBack) },
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
                    queueSize = state.queueSize,
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
                // 用 HorizontalPager 而不是自己接横向拖拽:点击这个手势在歌词页已经被
                // "点某句 seek 过去"占掉了,出口只能另找一个手势;而滑动的边界处理
                // (跟手、越界回弹、松手判定该翻页还是弹回)自己写一遍不会比它好。
                // 竖向也不冲突——把手是竖向拖拽、歌词是竖向滚动,和横向不同轴。
                val pagerState = rememberPagerState(pageCount = { if (subtitleCues.isEmpty()) 1 else 2 })
                val pagerScope = rememberCoroutineScope()

                // 换视频、或者字幕被关掉时退回唱片页。后者不退的话会停在一张空歌词页上,
                // 而那一页此刻根本不存在(pageCount 变成 1),停在那儿是个没有出口的状态。
                LaunchedEffect(state.current?.bvid, subtitleCues.isEmpty()) {
                    if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
                }

                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
                        if (page == 0) {
                            DiscView(
                                state = state,
                                subtitleTracks = subtitleTracks,
                                subtitleLan = subtitleLan,
                                onSelectSubtitle = onSelectSubtitle,
                                canFlipToLyrics = subtitleCues.isNotEmpty(),
                                onFlipToLyrics = { pagerScope.launch { pagerState.animateScrollToPage(1) } },
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            LyricsView(
                                state = state,
                                cues = subtitleCues,
                                player = player,
                                dragPosition = dragPosition,
                                onSeekTo = { player.seekTo(it) },
                                subtitleTracks = subtitleTracks,
                                subtitleLan = subtitleLan,
                                onSelectSubtitle = onSelectSubtitle,
                                onBackgroundClick = { pagerScope.launch { pagerState.animateScrollToPage(0) } },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    // 指示器放在 pager **外面**:它描述的是"你在两页中的哪一页",不属于任何一页,
                    // 放进去会跟着页面一起滑走。
                    if (subtitleCues.isNotEmpty()) {
                        PageDots(
                            current = pagerState.currentPage,
                            count = 2,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
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
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = formatDurationMillis(displayPosition),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            text = formatDurationMillis(duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
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
        SleepTimerSheet(
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
    subtitleTracks: List<SubtitleTrack>,
    subtitleLan: String,
    onSelectSubtitle: (String) -> Unit,
    canFlipToLyrics: Boolean,
    onFlipToLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.current ?: return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(horizontal = Spacing.Loose, vertical = Spacing.Comfortable),
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
            LaunchedEffect(state.isPlaying) {
                if (!state.isPlaying) return@LaunchedEffect
                angle.animateTo(
                    targetValue = angle.value + 360f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(DiscRotationPeriodMillis, easing = LinearEasing),
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
                // 点击留作第二条路(主路是左右滑动)。挂在最外层而不是图片上——图片现在
                // 只占中间那一小块,挂在它身上的话点盘体没反应,而用户看到的是一整张唱片。
                if (canFlipToLyrics) {
                    Box(modifier = Modifier.fillMaxSize().clip(CircleShape).clickable(onClick = onFlipToLyrics))
                }
                SubtitleTrackCornerButton(
                    tracks = subtitleTracks,
                    currentLan = subtitleLan,
                    onSelect = onSelectSubtitle,
                    modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.Hair),
                )
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
}

/**
 * 两页的指示器。非当前那个点做轻微呼吸 —— 左右滑动没有任何天然的可见痕迹,
 * 不给提示的话"这一页右边还有东西"这件事只能靠用户瞎划试出来。
 *
 * 幅度压得很小:它要做的是被余光扫到,不是被盯着看。
 */
@Composable
private fun PageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "page_dot_pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(PageDotPulsePeriodMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "page_dot_alpha",
    )
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(vertical = Spacing.Tight),
    ) {
        repeat(count) { index ->
            val alpha = if (index == current) 1f else pulseAlpha
            Canvas(modifier = Modifier.size(PageDotSize)) {
                drawCircle(color = dotColor.copy(alpha = alpha), radius = size.minDimension / 2f)
            }
        }
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
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            TextButton(onClick = { speedMenuOpen = true }) {
                Icon(
                    Icons.Filled.Speed,
                    contentDescription = stringResource(R.string.player_speed),
                    modifier = Modifier.size(18.dp),
                )
                Text(formatSpeed(speed), modifier = Modifier.padding(start = Spacing.Hair))
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
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = onOpenSleepTimer) {
                    Icon(
                        Icons.Filled.Bedtime,
                        contentDescription = stringResource(R.string.sleep_timer_off),
                        tint = if (label != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                // 定时生效时才占这一行——原来 BottomRow 那个常驻的文字标签挪没了地方,
                // 只能在图标下面挂一行小字,不生效就不留空。
                if (label != null) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
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
    queueSize: Int,
    shuffled: Boolean,
    onToggleShuffle: () -> Unit,
    onPlayQueueItem: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.listen_queue_count, queueSize),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable)
                .padding(bottom = Spacing.Tight),
        )
        TextButton(
            onClick = onToggleShuffle,
            modifier = Modifier.padding(horizontal = Spacing.Comfortable),
        ) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(if (shuffled) R.string.queue_order_shuffle else R.string.queue_order_sequential),
                modifier = Modifier.padding(start = Spacing.Hair),
            )
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
 * 定时关闭 Sheet。用 `ModalBottomSheet` 而不是原来的 `DropdownMenu`——Slider 要横向空间,
 * 菜单那几十 dp 宽放不下。
 *
 * 时长(Slider)与"播完当前再停"(Switch)各改各的,拖一下/点一下就直接调用 [onSet]
 * 生效,不设"确定"按钮——和播放页其余设置(倍速、画质)一样是即时生效,不是表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerSheet(
    sleepTimer: SleepTimerState,
    onSet: (minutes: Int?, finishCurrentItem: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Slider 初始位置取当前生效的时长;没设过时给个居中的默认值,不然一打开就顶在最左端。
    var minutes by remember {
        mutableFloatStateOf((sleepTimer.mode as? SleepTimerMode.After)?.minutes?.toFloat() ?: SLEEP_TIMER_DEFAULT_MINUTES)
    }
    var finishCurrentItem by remember { mutableStateOf(sleepTimer.finishCurrentItem) }
    val active = sleepTimer.mode != SleepTimerMode.Off || sleepTimer.finishCurrentItem

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
        ) {
            Text(
                stringResource(R.string.sleep_timer_stop_in, minutes.roundToInt()),
                style = MaterialTheme.typography.titleMedium,
            )
            Slider(
                value = minutes,
                onValueChange = {
                    minutes = it
                    onSet(minutes.roundToInt(), finishCurrentItem)
                },
                valueRange = SLEEP_TIMER_RANGE,
                steps = SLEEP_TIMER_STEPS,
                // 22 个 step 用默认 track 会画出 22 个刻度点,连成一串比轨道本身还抢眼的
                // 噪声。步进行为(snap 到 5 分钟一档)是 SliderState 自己管的,不靠这些点
                // 画出来才生效——只是不画,不是关掉步进。
                track = { sliderState -> SliderDefaults.Track(sliderState = sliderState, drawTick = { _, _ -> }) },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.Tight),
            ) {
                Text(stringResource(R.string.sleep_timer_finish_switch))
                Switch(
                    checked = finishCurrentItem,
                    onCheckedChange = {
                        finishCurrentItem = it
                        onSet(minutes.roundToInt(), it)
                    },
                )
            }
            if (active) {
                TextButton(
                    onClick = {
                        onSet(null, false)
                        onDismiss()
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.sleep_timer_cancel))
                }
            }
        }
    }
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
 * 歌词模式:点开唱片后取代它的呈现,封面调暗当底,逐句叠在上面滚动。
 *
 * [DiscView] 上那句「不裁成方形、不当背景板」的推翻针对的是**唱片本身作为主角**的情况;
 * 这里反过来——封面本来就要被压暗当背景,被裁掉的边和被 scrim 盖住的字都不再是要传达的
 * 信息,那条约束同样不适用。`ContentScale.Crop` 铺满这块区域,不保 16:10。
 *
 * 标题/UP 名**不叠在封面上**:歌词已经是这块区域唯一的主角,再叠一层文字只会在 scrim 上
 * 摞出第二层视觉噪声,而且深色 scrim 上同时读歌词和标题会分不清谁是谁。挪到区域上方一行,
 * 用正常(非 scrim)配色——和唱片模式下标题排在唱片下面是同一个"标题不抢封面"的判断,
 * 只是这次封面本身让位给了内容,标题只好挪到另一侧而不是消失。
 *
 * [onBackgroundClick] 挂在最底层的封面/scrim 上,不是整个区域——[LyricsList] 里每一句
 * 自己有 `onSeekTo` 的点击,落在文字上应该跳转不是翻页;点到行与行之间的空白、或者文字块
 * 之外的边距,才会穿透到底层触发翻回唱片。
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
    state: AudioPlaybackUiState,
    cues: List<SubtitleCue>,
    player: Player,
    /** 用户正在拖动下方进度条时的目标位置,和页面顶层 `displayPosition` 用的是同一个值——
     *  拖拽期间要立刻跟手,不能等下一次 100ms 轮询。 */
    dragPosition: Long?,
    onSeekTo: (Long) -> Unit,
    subtitleTracks: List<SubtitleTrack>,
    subtitleLan: String,
    onSelectSubtitle: (String) -> Unit,
    onBackgroundClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.current ?: return

    var polledPosition by remember { mutableLongStateOf(player.currentPosition) }
    LaunchedEffect(player) {
        while (true) {
            polledPosition = player.currentPosition
            delay(LyricsPollIntervalMillis)
        }
    }
    val positionMillis = dragPosition ?: polledPosition

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        ) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                modifier = Modifier.padding(top = Spacing.Hair),
            ) {
                Text(
                    item.upName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.queueSize > 0) {
                    Text(
                        "${state.positionInQueue} / ${state.queueSize}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 模糊只在 API 31+ 生效——`Modifier.blur` 底层是 RenderEffect,minSdk 29 上
            // 这个调用是静默空操作,不报错也不模糊,不能假设它总生效。29/30 完全靠下面那层
            // 更重的 ScrimOnLyrics 单独扛住可读性,31+ 是模糊 + 同一层 scrim 一起上。
            val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            BiliAsyncImage(
                url = item.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .let { base -> if (blurSupported) base.blur(LyricsBackgroundBlurRadius) else base }
                    .clickable(onClick = onBackgroundClick),
            )
            // 用专门给歌词页开的 ScrimOnLyrics,不是封面角标那个 ScrimOnMedia——原因见
            // FixedColors 上的注释:整屏铺满歌词和一个角落的小角标不是一个对比度量级。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(FixedColors.ScrimOnLyrics)
                    .clickable(onClick = onBackgroundClick),
            )
            LyricsList(
                cues = cues,
                positionMillis = positionMillis,
                onSeekTo = onSeekTo,
                modifier = Modifier.fillMaxSize(),
            )
            SubtitleTrackCornerButton(
                tracks = subtitleTracks,
                currentLan = subtitleLan,
                onSelect = onSelectSubtitle,
                modifier = Modifier.align(Alignment.TopEnd).padding(Spacing.Cozy),
            )
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
 * 颜色固定用 [FixedColors.OnMedia] 而不是主题色:背后是调暗后的任意封面,和播放器控件
 * 压在画面上是同一处境(见 FixedColors 上的说明)。降透明度是在这同一个固定色上做的,
 * 不是"不要用 alpha 兑主题容器色"那条(风格指南 §3)禁止的用法——那条防的是主题 surface
 * 色随深浅模式变化导致对比度失控,这里底色是固定的纯黑 scrim,alpha 的效果是可预测的。
 * 非当前句从 0.45 提到 0.6:背景现在多了一层更重的 scrim(加了模糊的机型还多一层模糊),
 * 原来压低透明度是为了不和背景抢,现在背景已经压下去了,压得更低只是白白牺牲可读性。
 */
@Composable
private fun LyricsList(
    cues: List<SubtitleCue>,
    positionMillis: Long,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val nearIndex = cues.indexNear(positionMillis)
    val highlightIndex = nearIndex.takeIf { it >= 0 && positionMillis < cues[it].toMillis }

    LaunchedEffect(nearIndex) {
        if (nearIndex < 0) return@LaunchedEffect
        listState.animateScrollToItem(nearIndex)
    }

    BoxWithConstraints(modifier = modifier) {
        val halfViewport = maxHeight / 2
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(horizontal = Spacing.Loose, vertical = halfViewport),
        ) {
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
                    color = if (highlighted) FixedColors.OnMedia else FixedColors.OnMedia.copy(alpha = 0.6f),
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
        is SleepTimerMode.After -> state.remainingMillis?.let { formatDurationMillis(it) }
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
