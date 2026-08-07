package dev.bilby.ui.video

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import dev.bilby.R
import dev.bilby.formatDurationMillis
import dev.bilby.data.QualityOption
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.player.cueAt
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.components.SeekBarSegment
import dev.bilby.ui.components.SubtitleTrackMenu
import dev.bilby.ui.theme.FixedColors
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 控件渐变最下面那一档。比 [FixedColors.PlayerControlScrim] 再深一点,兜住时间文字。 */
private val ControlScrimBottom = Color(0xB3000000)

/** 长按期间的临时倍速。 */
private const val FAST_FORWARD_SPEED = 3f

private const val CONTROLS_HIDE_DELAY_MILLIS = 3_000L

/** 双击两侧快进/快退的步长。 */
private const val DOUBLE_TAP_SEEK_MILLIS = 10_000L

/** 手势提示浮层停留多久。 */
private const val HINT_VISIBLE_MILLIS = 700L
private const val PROGRESS_REPORT_INTERVAL_MILLIS = 5_000L

/**
 * 播放器画面 + 控件。非全屏时被塞进 16:9 容器,全屏时铺满整屏,两种形态共用这一个 composable,
 * 靠 [isFullscreen] 切换布局与控件密度。
 *
 * 这里**不做**任何"下一个视频"的自动跳转(DESIGN 1.3/2.3),全屏下也不做。
 *
 * 播放器不归这里所有(DESIGN 2.4b:播放器归后台服务),所以这个 composable 只读状态、发命令,
 * 不 prepare、不 release。
 *
 * @param player 状态与控制的唯一入口,实际传进来的是连到播放服务的 MediaController。
 * @param surfacePlayer 只用来渲染画面。**MediaController 渲染不了画面**:Media3 不给它
 *   COMMAND_SET_VIDEO_SURFACE(Surface 是本地对象,递不到 session 那一侧),所以画面必须接
 *   在真的 ExoPlayer 上。两个参数指向的是同一份播放状态,不会打架。
 */
@OptIn(UnstableApi::class)
@Composable
fun BilbyPlayer(
    player: Player,
    surfacePlayer: Player?,
    qualities: List<QualityOption>,
    currentQuality: Int,
    onQualityChange: (Int) -> Unit,
    isFullscreen: Boolean,
    onFullscreenChange: (Boolean) -> Unit,
    onListen: () -> Unit,
    onReportProgress: (positionMillis: Long, durationMillis: Long) -> Unit,
    /** 会被自动跳过的片段。只染在进度条上,不参与交互,见 [SeekBar]。 */
    seekBarSegments: List<SeekBarSegment> = emptyList(),
    /** 这条(cid)有哪些字幕轨,含 AI 生成的。为空时控制条不出现字幕按钮。 */
    subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** 选中轨的语言代码,空字符串是关(默认)。 */
    currentSubtitleLan: String = "",
    onSubtitleTrackChange: (String) -> Unit = {},
    /** 选中轨的正文,按 fromMillis 升序。 */
    subtitleCues: List<SubtitleCue> = emptyList(),
    modifier: Modifier = Modifier,
    /** 只在全屏时显示。竖屏下标题就在播放器正下方,再印一遍是多余的。 */
    title: String = "",
) {
    val reportProgress by rememberUpdatedState(onReportProgress)

    // 竖屏视频、4:3 老片都存在,写死 16:9 会把画面拉变形。容器比例由外面定,画面按真实比例
    // 居中,多出来的地方留黑边。
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // 播放时屏幕常亮,暂停时不常亮——条件看的是播放状态,不是"页面在前台"。
    //
    // 作用域只覆盖内嵌与全屏这两种画面可见的形态,不覆盖听视频:后者的全部意义就是
    // 息屏后台放(DESIGN 2.4b「息屏后台,不能后台就不成立」),常亮会把它变成一个耗电的
    // 笑话。这里不用另外传一个 listening 标志去关掉它——VideoScreen 在听视频分支上提前
    // return,BilbyPlayer 根本不会被组合,作用域天然就是对的。
    val view = LocalView.current
    DisposableEffect(isPlaying) {
        view.keepScreenOn = isPlaying
        onDispose { view.keepScreenOn = false }
    }

    // 画面尺寸问渲染画面的那个播放器,不问 controller:controller 那边的 videoSize 要等 session
    // 同步,慢半拍就是画面先按 16:9 铺开再跳一下。
    DisposableEffect(surfacePlayer) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoAspect = videoSize.aspectOr(videoAspect)
            }
        }
        // 接上来时流可能已经在播了(页面重建、或从听视频切回来),那一次 onVideoSizeChanged
        // 早就发过,只监听会一直停在默认的 16:9。
        surfacePlayer?.let { videoAspect = it.videoSize.aspectOr(videoAspect) }
        surfacePlayer?.addListener(listener)
        onDispose { surfacePlayer?.removeListener(listener) }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var resumeAfterDrag by remember { mutableStateOf(false) }

    var userSpeed by remember { mutableFloatStateOf(1f) }
    var isFastForwarding by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }

    /**
     * 锁屏:横屏看视频时手容易碰到画面,一碰就暂停或快进。锁上之后除了解锁按钮,
     * 所有手势与控件都不响应。
     */
    var locked by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // 每次操作控件都让自动隐藏重新计时,靠这个计数把 LaunchedEffect 重启。
    var interactionNonce by remember { mutableIntStateOf(0) }

    val context = LocalContext.current
    val brightness = rememberSystemBrightness(context)
    val volume = rememberMediaVolume(context)

    /** 正在进行的手势;横划与纵划在第一段位移里定死,见下面的 onDrag。 */
    var gesture by remember { mutableStateOf<PlayerGesture?>(null) }
    var dragStartX by remember { mutableFloatStateOf(0f) }

    /** 纵划时浮层要显示的百分比。 */
    var adjustValue by remember { mutableFloatStateOf(0f) }

    /** 双击 ±10 秒的短暂提示,非 null 时显示;正负决定文案。 */
    var seekNudgeMillis by remember { mutableStateOf<Long?>(null) }

    /** 亮度权限没给时的提示。跳设置页是离开应用的动作,不说一声会很突兀。 */
    var needsWriteSettings by remember { mutableStateOf(false) }

    LaunchedEffect(seekNudgeMillis) {
        if (seekNudgeMillis == null) return@LaunchedEffect
        delay(HINT_VISIBLE_MILLIS)
        seekNudgeMillis = null
    }

    LaunchedEffect(needsWriteSettings) {
        if (!needsWriteSettings) return@LaunchedEffect
        delay(HINT_VISIBLE_MILLIS)
        needsWriteSettings = false
    }

    /**
     * 定下这一次拖拽在做什么。返回 null 表示这次不做事(亮度没权限)。
     *
     * 亮度是设备级设置,要 `WRITE_SETTINGS`,而那个权限只能跳系统设置页去拨
     * (见 [SystemBrightness])。第一次划到它时把人送过去,并留一句提示。
     */
    val startGesture: (Boolean, Long) -> PlayerGesture? = start@{ horizontal, playerPosition ->
        if (horizontal) return@start PlayerGesture.Seek(playerPosition)
        val onLeftHalf = dragStartX < 0.5f
        if (onLeftHalf) {
            if (!brightness.canWrite()) {
                needsWriteSettings = true
                brightness.requestPermission()
                return@start null
            }
            PlayerGesture.Adjust(VerticalAdjust.Brightness, brightness.current())
        } else {
            PlayerGesture.Adjust(VerticalAdjust.Volume, volume.current())
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (dragPosition == null) position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }

    // 进程被杀不会走 onDispose,所以播放中也定期回传一次进度。服务端那份是续播的唯一来源,
    // 只在退出时报一次的话,被杀掉的那次观看等于没发生过。
    LaunchedEffect(player) {
        while (true) {
            delay(PROGRESS_REPORT_INTERVAL_MILLIS)
            if (player.isPlaying) reportProgress(player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, dragPosition, menuOpen, interactionNonce) {
        // 暂停时控件常驻:此时用户多半正要点什么,把它藏掉只会逼人再点一次。
        if (controlsVisible && isPlaying && dragPosition == null && !menuOpen) {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
            controlsVisible = false
        }
    }

    // 传布尔而不是比例本身:比例是浮点,解码过程里会有细微抖动,直接当 DisposableEffect
    // 的 key 会让全屏反复重设方向。
    FullscreenEffect(isFullscreen, isPortraitVideo = videoAspect < 1f)

    BackHandler(enabled = isFullscreen) { onFullscreenChange(false) }

    val displayPosition = dragPosition ?: position

    // 二分查找,不逐帧线性扫:见 SubtitleCue.kt 上的注释。落在两句之间的空档里时是 null,
    // 什么都不画——句间停顿本来就没有字幕在念。
    val currentCue = remember(subtitleCues, displayPosition) { subtitleCues.cueAt(displayPosition) }

    Box(modifier = modifier.background(Color.Black)) {
        if (surfacePlayer != null) {
            PlayerSurface(
                player = surfacePlayer,
                modifier = Modifier.align(Alignment.Center).aspectRatio(videoAspect),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(player, locked) {
                    if (locked) {
                        // 锁上时只留"点一下把解锁按钮唤出来",其余手势一概不接。
                        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                        return@pointerInput
                    }
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            interactionNonce++
                        },
                        // 双击按落点分三段:两侧各三分之一是 ±10 秒,中间那段仍是播放/暂停。
                        //
                        // 中间保留下来是因为双击暂停本来就在,直接换掉等于拿走一个已有的常用
                        // 操作;而三分法是 YouTube 立起来的惯例,两侧那两块也正是横屏握持时
                        // 拇指自然落到的位置。
                        onDoubleTap = { offset ->
                            val third = size.width / 3f
                            when {
                                offset.x < third -> nudgeSeek(player, -DOUBLE_TAP_SEEK_MILLIS)
                                    .also { seekNudgeMillis = -DOUBLE_TAP_SEEK_MILLIS }

                                offset.x > size.width - third -> nudgeSeek(player, DOUBLE_TAP_SEEK_MILLIS)
                                    .also { seekNudgeMillis = DOUBLE_TAP_SEEK_MILLIS }

                                player.isPlaying -> player.pause()
                                // 播完之后位置停在末尾,直接 play() 不会有反应,应有行为是重播。
                                player.playbackState == Player.STATE_ENDED -> {
                                    player.seekTo(0)
                                    player.play()
                                }

                                else -> player.play()
                            }
                            interactionNonce++
                        },
                        onLongPress = {
                            isFastForwarding = true
                            player.setPlaybackSpeed(FAST_FORWARD_SPEED)
                        },
                        onPress = {
                            tryAwaitRelease()
                            if (isFastForwarding) {
                                isFastForwarding = false
                                // 恢复到用户选的倍速而不是 1.0:用户可能本来就在 1.5x 看,
                                // 长按只是临时叠加,松手不该把他的设置抹掉。
                                player.setPlaybackSpeed(userSpeed)
                            }
                        },
                    )
                }
                // 拖拽单独一个 pointerInput:和上面的点按检测并列而不是塞进同一个块。
                // 两者天然互斥 —— 位移超过 touch slop 之后点按检测就不会触发了。
                .pointerInput(player, locked, duration) {
                    if (locked) return@pointerInput
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val height = size.height.toFloat().coerceAtLeast(1f)
                    var accumulated = Offset.Zero

                    detectDragGestures(
                        onDragStart = { start ->
                            accumulated = Offset.Zero
                            dragStartX = start.x / width
                        },
                        onDragEnd = {
                            // 拖拽期间只动本地位置,松手才真 seek:每帧 seek 会让播放器不停丢
                            // 缓冲重新起播,表现为拖不动。和进度条的处理是同一套。
                            if (gesture is PlayerGesture.Seek) {
                                dragPosition?.let { target ->
                                    player.seekTo(target)
                                    position = target
                                    reportProgress(target, player.duration.coerceAtLeast(0))
                                }
                            }
                            gesture = null
                            dragPosition = null
                            interactionNonce++
                        },
                        onDragCancel = {
                            gesture = null
                            dragPosition = null
                        },
                    ) { change, delta ->
                        change.consume()
                        accumulated += delta

                        // 方向在第一段位移里定下,之后不再改判:不锁轴的话,横划途中手指
                        // 稍微飘一点就会跳去改音量。
                        val current = gesture ?: startGesture(
                            abs(accumulated.x) >= abs(accumulated.y),
                            player.currentPosition,
                        )?.also { gesture = it } ?: return@detectDragGestures

                        when (current) {
                            is PlayerGesture.Seek -> {
                                // 整屏宽对应多长:按时长的四分之一取,夹在 1 到 5 分钟之间。
                                // 定长(比如恒定 2 分钟)在长视频里要划很多次;按整段时长又会让
                                // 长视频一格几十秒,微调不了。
                                val span = (duration / 4).coerceIn(60_000L, 300_000L)
                                val target = current.startPositionMillis +
                                    (accumulated.x / width * span).toLong()
                                dragPosition = target.coerceIn(0L, duration.coerceAtLeast(0L))
                            }

                            is PlayerGesture.Adjust -> {
                                // 向上是变大,所以减去 y 的位移(屏幕坐标向下为正)。
                                val value = (current.startValue - accumulated.y / height)
                                    .coerceIn(0f, 1f)
                                adjustValue = value
                                when (current.kind) {
                                    VerticalAdjust.Brightness -> brightness.set(value)
                                    VerticalAdjust.Volume -> volume.set(value)
                                }
                            }
                        }
                    }
                },
        )

        // 纵划的浮层。和快进提示一样贴在正中偏上,不压住底部控件。
        (gesture as? PlayerGesture.Adjust)?.let { adjust ->
            Overlay(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    stringResource(
                        when (adjust.kind) {
                            VerticalAdjust.Brightness -> R.string.player_brightness
                            VerticalAdjust.Volume -> R.string.player_volume
                        },
                        (adjustValue * 100).roundToInt(),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = FixedColors.OnMedia,
                )
            }
        }

        seekNudgeMillis?.let { delta ->
            Overlay(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    stringResource(
                        if (delta >= 0) R.string.player_seek_forward else R.string.player_seek_backward,
                        abs(delta) / 1000,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = FixedColors.OnMedia,
                )
            }
        }

        if (needsWriteSettings) {
            Overlay(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    stringResource(R.string.player_need_write_settings),
                    style = MaterialTheme.typography.bodyMedium,
                    color = FixedColors.OnMedia,
                )
            }
        }

        if (isFastForwarding) {
            Overlay(modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.FastForward,
                        contentDescription = null,
                        tint = FixedColors.OnMedia,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "  " + stringResource(
                            R.string.player_fast_forwarding,
                            formatSpeed(FAST_FORWARD_SPEED),
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = FixedColors.OnMedia,
                    )
                }
            }
        }

        if (dragPosition != null) {
            Overlay(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    "${formatDurationMillis(displayPosition)} / ${formatDurationMillis(duration)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = FixedColors.OnMedia,
                )
            }
        }

        // 字幕层。**不走 Media3 的 SubtitleConfiguration**:那要求先把 JSON 转成 VTT 再挂到
        // MediaItem 上,而这里的 MediaItem 是 AudioPlaybackService 拼的 DASH 合并源,插字幕轨
        // 要动到播放器所有权那一层。还有个更硬的理由:听视频模式下 VideoScreen.kt 那句
        // `setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)` 会把视频轨连带的字幕轨一起关掉——
        // 走播放器原生字幕的话,切到听视频字幕就会跟着消失,而这里的字幕来自独立的接口,不受
        // 视频轨开关影响。贴底而不是压中间:不挡画面主体,也不常驻遮住控制条位置——控件常驻
        // 显示时字幕整体上移让开,和进度条那句浮层的处理是同一个思路。
        currentCue?.let { cue ->
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (controlsVisible && !locked) 88.dp else 24.dp)
                    .padding(horizontal = 24.dp),
            ) {
                Text(
                    cue.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = FixedColors.OnMedia,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(FixedColors.ScrimOnMedia)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // 全屏顶栏。全屏下没有别的东西说明"在看什么"和"怎么退出":系统栏是隐藏的,
        // 返回手势在锁屏态下也被吃掉了。竖屏不显示,那里标题就在播放器下面第一行。
        AnimatedVisibility(
            visible = isFullscreen && controlsVisible && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(ControlScrimBottom, Color.Transparent)),
                    )
                    .windowInsetsPadding(WindowInsets.displayCutout.union(WindowInsets.systemBars))
                    .padding(end = 16.dp, bottom = 16.dp),
            ) {
                IconButton(onClick = { onFullscreenChange(false) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_exit_fullscreen),
                        tint = FixedColors.OnMedia,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = FixedColors.OnMedia,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 锁按钮:锁上后它是唯一还能点的东西。放右侧——横屏握持时右手拇指够得到,
        // 而左侧那个位置和退出全屏的返回箭头在同一边,容易误按成退出。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
        ) {
            IconButton(onClick = { locked = !locked }) {
                Icon(
                    imageVector = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = stringResource(
                        if (locked) R.string.player_unlock else R.string.player_lock,
                    ),
                    tint = FixedColors.OnMedia,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            PlayerControlBar(
                segments = seekBarSegments,
                isPlaying = isPlaying,
                position = displayPosition,
                duration = duration,
                speed = userSpeed,
                qualities = qualities,
                currentQuality = currentQuality,
                isFullscreen = isFullscreen,
                onPlayPause = {
                    when {
                        player.isPlaying -> player.pause()
                        // 播完之后位置停在末尾,直接 play() 无事发生(或在某些实现上直接抛)。
                        // 应有行为是从头再播一遍。
                        player.playbackState == Player.STATE_ENDED -> {
                            player.seekTo(0)
                            player.play()
                        }

                        else -> player.play()
                    }
                    interactionNonce++
                },
                onSeekStart = {
                    resumeAfterDrag = player.isPlaying
                    player.pause()
                },
                // 拖拽中只更新本地位置:每帧 seek 会让播放器不停丢缓冲重新起播,
                // 表现为拖不动。真正的 seek 留到松手时一次完成。
                onSeekTo = { dragPosition = it },
                onSeekFinished = {
                    dragPosition?.let { target ->
                        player.seekTo(target)
                        position = target
                        dragPosition = null
                        if (resumeAfterDrag) player.play()
                        reportProgress(target, player.duration.coerceAtLeast(0))
                    }
                    interactionNonce++
                },
                onSpeedChange = {
                    userSpeed = it
                    if (!isFastForwarding) player.setPlaybackSpeed(it)
                    interactionNonce++
                },
                onQualityChange = {
                    onQualityChange(it)
                    interactionNonce++
                },
                subtitleTracks = subtitleTracks,
                currentSubtitleLan = currentSubtitleLan,
                onSubtitleTrackChange = {
                    onSubtitleTrackChange(it)
                    interactionNonce++
                },
                onFullscreenToggle = {
                    onFullscreenChange(!isFullscreen)
                    interactionNonce++
                },
                onListen = onListen,
                onMenuOpenChange = { menuOpen = it },
            )
        }
    }
}

@Composable
private fun PlayerControlBar(
    segments: List<SeekBarSegment>,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    speed: Float,
    qualities: List<QualityOption>,
    currentQuality: Int,
    isFullscreen: Boolean,
    onPlayPause: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onQualityChange: (Int) -> Unit,
    subtitleTracks: List<SubtitleTrack>,
    currentSubtitleLan: String,
    onSubtitleTrackChange: (String) -> Unit,
    onFullscreenToggle: () -> Unit,
    onListen: () -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
) {
    val safeInsets = WindowInsets.displayCutout.union(WindowInsets.systemBars)
    val container = Modifier
        .fillMaxWidth()
        // 渐变而不是一整条半透明黑。控件底下是画面本身,一条硬边的黑带会把画面横着切一刀,
        // 而渐变只在最需要对比度的地方(文字所在的下缘)压到最暗。B 站与 PiliPlus 的
        // 播放器同样是自下而上的渐变。
        .background(
            Brush.verticalGradient(
                listOf(Color.Transparent, FixedColors.PlayerControlScrim, ControlScrimBottom),
            ),
        )
        // 全屏时系统栏被藏了,但挖孔和手势条的位置照旧,控件贴边会被切掉一半。
        .then(if (isFullscreen) Modifier.windowInsetsPadding(safeInsets) else Modifier)
        .padding(
            start = if (isFullscreen) 16.dp else 8.dp,
            end = if (isFullscreen) 16.dp else 8.dp,
            top = 16.dp,
            bottom = if (isFullscreen) 8.dp else 0.dp,
        )

    // 进度条独占一行:挤在按钮行里只剩几十 dp 可拖,而拖拽是这里最主要的操作。
    Column(modifier = container) {
        SeekBar(position, duration, onSeekStart, onSeekTo, onSeekFinished, Modifier.fillMaxWidth(), segments = segments)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayPauseButton(isPlaying, onPlayPause, if (isFullscreen) 30.dp else 22.dp)
            Text(
                "${formatDurationMillis(position)} / ${formatDurationMillis(duration)}",
                style = if (isFullscreen) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.labelSmall,
                color = FixedColors.OnMedia,
            )
            Spacer(Modifier.weight(1f))
            SpeedButton(speed, onSpeedChange, onMenuOpenChange, isFullscreen)
            QualityButton(qualities, currentQuality, onQualityChange, onMenuOpenChange, isFullscreen)
            SubtitleButton(subtitleTracks, currentSubtitleLan, onSubtitleTrackChange, onMenuOpenChange, isFullscreen)
            // 听视频和全屏是同一类东西:都是播放页内的状态,都不换播放器、不交接进度。
            // 同构的两个动作放在一起,以前它在下面的简介区,和一堆内容动作混着。
            IconButton(onClick = onListen) {
                Icon(
                    Icons.Filled.Headphones,
                    contentDescription = stringResource(R.string.player_listen),
                    tint = FixedColors.OnMedia,
                    modifier = Modifier.size(if (isFullscreen) 26.dp else 22.dp),
                )
            }
            FullscreenButton(isFullscreen, onFullscreenToggle, if (isFullscreen) 26.dp else 22.dp)
        }
    }
}



@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit, iconSize: Dp) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(
                if (isPlaying) R.string.player_pause else R.string.player_play,
            ),
            tint = FixedColors.OnMedia,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun FullscreenButton(isFullscreen: Boolean, onClick: () -> Unit, iconSize: Dp) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            contentDescription = stringResource(
                if (isFullscreen) R.string.player_exit_fullscreen else R.string.player_fullscreen,
            ),
            tint = FixedColors.OnMedia,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun SpeedButton(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    isFullscreen: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ControlButton(
            expanded = expanded,
            onClick = { expanded = true; onMenuOpenChange(true) },
            label = if (speed == 1f) null else formatSpeed(speed),
            icon = { tint ->
                Icon(
                    Icons.Filled.Speed,
                    stringResource(R.string.player_speed),
                    tint = tint,
                    modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; onMenuOpenChange(false) },
        ) {
            SPEED_OPTIONS.forEach { option ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(option)) },
                    onClick = {
                        expanded = false
                        onMenuOpenChange(false)
                        onSpeedChange(option)
                    },
                    trailingIcon = if (option == speed) selectedMark else null,
                )
            }
        }
    }
}

@Composable
private fun QualityButton(
    qualities: List<QualityOption>,
    currentQuality: Int,
    onQualityChange: (Int) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    isFullscreen: Boolean,
) {
    if (qualities.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = qualities.firstOrNull { it.quality == currentQuality }?.label
    Box {
        ControlButton(
            expanded = expanded,
            onClick = { expanded = true; onMenuOpenChange(true) },
            label = if (isFullscreen) currentLabel else null,
            icon = { tint ->
                Icon(
                    Icons.Filled.HighQuality,
                    stringResource(R.string.player_quality),
                    tint = tint,
                    modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
                )
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; onMenuOpenChange(false) },
        ) {
            qualities.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = {
                        expanded = false
                        onMenuOpenChange(false)
                        onQualityChange(option.quality)
                    },
                    trailingIcon = if (option.quality == currentQuality) selectedMark else null,
                )
            }
        }
    }
}

/**
 * 字幕轨选择,和 [SpeedButton]/[QualityButton] 是同一套形状。没有轨(这条视频没有字幕、
 * 或者还没拉回来)时不出现——一个只有"关闭"一个选项的菜单是纯噪声。
 */
@Composable
private fun SubtitleButton(
    tracks: List<SubtitleTrack>,
    currentLan: String,
    onChange: (String) -> Unit,
    onMenuOpenChange: (Boolean) -> Unit,
    isFullscreen: Boolean,
) {
    if (tracks.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = tracks.firstOrNull { it.lan == currentLan }?.displayName
    Box {
        ControlButton(
            expanded = expanded,
            onClick = { expanded = true; onMenuOpenChange(true) },
            label = if (isFullscreen) currentLabel else null,
            icon = { tint ->
                Icon(
                    Icons.Filled.Subtitles,
                    stringResource(R.string.player_subtitle),
                    tint = tint,
                    modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp),
                )
            },
        )
        // 菜单内容和听视频封面右上角那个按钮共用一份,见 SubtitleTrackMenu 上的注释。
        SubtitleTrackMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false; onMenuOpenChange(false) },
            tracks = tracks,
            currentLan = currentLan,
            onSelect = onChange,
        )
    }
}

/** 图标按钮,可选地在图标右边挂一小段文字(当前倍速、当前清晰度)。 */
@Composable
private fun ControlButton(
    expanded: Boolean,
    onClick: () -> Unit,
    label: String?,
    icon: @Composable (Color) -> Unit,
) {
    val tint = if (expanded) MaterialTheme.colorScheme.primary else FixedColors.OnMedia
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 8.dp),
    ) {
        icon(tint)
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).widthIn(max = 64.dp),
            )
        }
    }
}

private val selectedMark: @Composable () -> Unit = {
    Text("·", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Overlay(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(FixedColors.ScrimOnMedia)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        content()
    }
}

/**
 * 全屏的两件事:Activity 转到画面朝向 + 隐藏系统栏。两者都是 Activity 级的全局状态,离开
 * 这个 composable 必须还原,否则退到列表页还卡在横屏。
 *
 * **朝向跟着画面走,不是一律横屏。** 竖屏视频转横屏之后画面只能缩到中间一条,两侧全是黑边,
 * 等于全屏把可视面积改小了;竖屏视频的全屏就该竖着占满。用 SENSOR_* 而不是 USER_*,
 * 是为了让人仍能把设备翻过来(倒持、左右手)。
 */
@Composable
private fun FullscreenEffect(isFullscreen: Boolean, isPortraitVideo: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(isFullscreen, isPortraitVideo) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            activity.requestedOrientation =
                if (isPortraitVideo) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

/**
 * LocalContext 拿到的不保证是 Activity:主题、配置覆写都会套一层 ContextWrapper,
 * 层数不固定,所以只能一路解包到底。
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** 尺寸未知时(还没解码出第一帧)返回 [fallback],别把画面压成 0 宽。 */
private fun VideoSize.aspectOr(fallback: Float): Float =
    if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else fallback

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"


/**
 * 双击两侧的定量跳转。夹在 [0, duration] 内 —— 末尾再往前跳会停在结尾并触发播完逻辑,
 * 那不是用户按这一下的意思。时长未知(还没解析出来)时不夹上界。
 */
private fun nudgeSeek(player: Player, deltaMillis: Long) {
    val duration = player.duration.coerceAtLeast(0)
    val target = player.currentPosition + deltaMillis
    player.seekTo(if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L))
}
