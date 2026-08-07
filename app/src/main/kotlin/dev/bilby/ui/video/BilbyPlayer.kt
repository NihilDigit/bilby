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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import dev.bilby.data.QualityOption
import dev.bilby.ui.components.SeekBar
import dev.bilby.ui.theme.FixedColors
import kotlinx.coroutines.delay

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 控件渐变最下面那一档。比 [FixedColors.PlayerControlScrim] 再深一点,兜住时间文字。 */
private val ControlScrimBottom = Color(0xB3000000)

/** 长按期间的临时倍速。 */
private const val FAST_FORWARD_SPEED = 3f

private const val CONTROLS_HIDE_DELAY_MILLIS = 3_000L
private const val PROGRESS_SAVE_INTERVAL_MILLIS = 5_000L

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
    onSaveProgress: (positionMillis: Long, durationMillis: Long) -> Unit,
    modifier: Modifier = Modifier,
    /** 只在全屏时显示。竖屏下标题就在播放器正下方,再印一遍是多余的。 */
    title: String = "",
) {
    val saveProgress by rememberUpdatedState(onSaveProgress)

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

    LaunchedEffect(player) {
        while (true) {
            if (dragPosition == null) position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }

    // 进程被杀不会走 onDispose,所以播放中也定期落盘一次进度。
    LaunchedEffect(player) {
        while (true) {
            delay(PROGRESS_SAVE_INTERVAL_MILLIS)
            if (player.isPlaying) saveProgress(player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, dragPosition, menuOpen, interactionNonce) {
        // 暂停时控件常驻:此时用户多半正要点什么,把它藏掉只会逼人再点一次。
        if (controlsVisible && isPlaying && dragPosition == null && !menuOpen) {
            delay(CONTROLS_HIDE_DELAY_MILLIS)
            controlsVisible = false
        }
    }

    FullscreenEffect(isFullscreen)

    BackHandler(enabled = isFullscreen) { onFullscreenChange(false) }

    val displayPosition = dragPosition ?: position

    Box(modifier = modifier.background(Color.Black)) {
        if (surfacePlayer != null) {
            PlayerSurface(
                player = surfacePlayer,
                modifier = Modifier.align(Alignment.Center).aspectRatio(videoAspect),
            )
        }

        Box(
            modifier = Modifier.fillMaxSize().pointerInput(player, locked) {
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
                    onDoubleTap = {
                        when {
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
            },
        )

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
                    "${formatPlayerTime(displayPosition)} / ${formatPlayerTime(duration)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = FixedColors.OnMedia,
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

        // 锁按钮:锁上后它是唯一还能点的东西。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
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
                        saveProgress(target, player.duration.coerceAtLeast(0))
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
        SeekBar(position, duration, onSeekStart, onSeekTo, onSeekFinished, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayPauseButton(isPlaying, onPlayPause, if (isFullscreen) 30.dp else 22.dp)
            Text(
                "${formatPlayerTime(position)} / ${formatPlayerTime(duration)}",
                style = if (isFullscreen) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.labelSmall,
                color = FixedColors.OnMedia,
            )
            Spacer(Modifier.weight(1f))
            SpeedButton(speed, onSpeedChange, onMenuOpenChange, isFullscreen)
            QualityButton(qualities, currentQuality, onQualityChange, onMenuOpenChange, isFullscreen)
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
 * 全屏的两件事:Activity 转横屏 + 隐藏系统栏。两者都是 Activity 级的全局状态,离开这个
 * composable 必须还原,否则退到列表页还卡在横屏。
 */
@Composable
private fun FullscreenEffect(isFullscreen: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    DisposableEffect(isFullscreen) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        if (isFullscreen) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
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

private fun formatPlayerTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
