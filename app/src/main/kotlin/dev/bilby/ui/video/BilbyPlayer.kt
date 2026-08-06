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
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Fullscreen
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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
import dev.bilby.data.QualityOption
import kotlinx.coroutines.delay

private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

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
    onSaveProgress: (positionMillis: Long, durationMillis: Long) -> Unit,
    modifier: Modifier = Modifier,
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
            modifier = Modifier.fillMaxSize().pointerInput(player) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
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
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        "  ${formatSpeed(FAST_FORWARD_SPEED)} 快进中",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }

        if (dragPosition != null) {
            Overlay(modifier = Modifier.align(Alignment.Center)) {
                Text(
                    "${formatPlayerTime(displayPosition)} / ${formatPlayerTime(duration)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
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
                    if (player.isPlaying) player.pause() else player.play()
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
    onMenuOpenChange: (Boolean) -> Unit,
) {
    val safeInsets = WindowInsets.displayCutout.union(WindowInsets.systemBars)
    val container = Modifier
        .fillMaxWidth()
        .background(Color.Black.copy(alpha = 0.45f))
        // 全屏时系统栏被藏了,但挖孔和手势条的位置照旧,控件贴边会被切掉一半。
        .then(if (isFullscreen) Modifier.windowInsetsPadding(safeInsets) else Modifier)
        .padding(horizontal = if (isFullscreen) 16.dp else 4.dp, vertical = if (isFullscreen) 8.dp else 0.dp)

    // 进度条独占一行:挤在按钮行里只剩几十 dp 可拖,而拖拽是这里最主要的操作。
    Column(modifier = container) {
        SeekBar(position, duration, onSeekStart, onSeekTo, onSeekFinished, Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayPauseButton(isPlaying, onPlayPause, if (isFullscreen) 30.dp else 22.dp)
            Text(
                "${formatPlayerTime(position)} / ${formatPlayerTime(duration)}",
                style = if (isFullscreen) MaterialTheme.typography.labelLarge
                else MaterialTheme.typography.labelSmall,
                color = Color.White,
            )
            Spacer(Modifier.weight(1f))
            SpeedButton(speed, onSpeedChange, onMenuOpenChange, isFullscreen)
            QualityButton(qualities, currentQuality, onQualityChange, onMenuOpenChange, isFullscreen)
            FullscreenButton(isFullscreen, onFullscreenToggle, if (isFullscreen) 26.dp else 22.dp)
        }
    }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    onSeekStart: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    Slider(
        value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
        onValueChange = { fraction ->
            if (!dragging) {
                dragging = true
                onSeekStart()
            }
            onSeekTo((fraction * duration).toLong())
        },
        onValueChangeFinished = {
            dragging = false
            onSeekFinished()
        },
        enabled = duration > 0,
        colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = Color.White.copy(alpha = 0.4f),
        ),
        modifier = modifier,
    )
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit, iconSize: Dp) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "暂停" else "播放",
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun FullscreenButton(isFullscreen: Boolean, onClick: () -> Unit, iconSize: Dp) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
            contentDescription = if (isFullscreen) "退出全屏" else "全屏",
            tint = Color.White,
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
            icon = { tint -> Icon(Icons.Filled.Speed, "倍速", tint = tint, modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp)) },
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
            icon = { tint -> Icon(Icons.Filled.HighQuality, "清晰度", tint = tint, modifier = Modifier.size(if (isFullscreen) 22.dp else 18.dp)) },
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
    val tint = if (expanded) MaterialTheme.colorScheme.primary else Color.White
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
            .background(Color.Black.copy(alpha = 0.6f))
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
