package dev.bilby.ui.listen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import dev.bilby.api.toHttpsUrl
import dev.bilby.player.AudioPlaybackUiState
import dev.bilby.player.QueueItem
import dev.bilby.player.SleepTimerMode
import dev.bilby.player.SleepTimerState
import kotlinx.coroutines.delay

private const val REFERER = "https://www.bilibili.com"
private val SPEED_OPTIONS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
private val SLEEP_TIMER_MINUTES = listOf(15, 30, 60)

/**
 * 听视频界面。播放器归 [dev.bilby.player.AudioPlaybackService] 所有,这里只读状态、发命令
 * (DESIGN 2.4b「一个播放状态,两个 UI」)——不 prepare、不 release,和看视频共用同一个播放器。
 *
 * 比看视频少两样:不渲染画面、不能调画质,其余(队列、连播、顺序/随机、进度、倍速)是同一套。
 */
@Composable
fun ListenScreen(
    player: Player?,
    state: AudioPlaybackUiState,
    sleepTimer: SleepTimerState,
    queue: List<QueueItem>,
    onPlayQueueItem: (bvid: String) -> Unit,
    onToggleShuffle: () -> Unit,
    onSleepTimer: (minutes: Int) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragPosition by remember { mutableStateOf<Long?>(null) }
    var resumeAfterDrag by remember { mutableStateOf(false) }
    var speed by remember { mutableStateOf(1f) }

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

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("听视频", style = MaterialTheme.typography.titleMedium)
        }

        if (player == null || state.current == null) {
            Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("未在播放", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            CoverAndInfo(state)

            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Slider(
                    value = if (duration > 0) (displayPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                    onValueChange = { fraction ->
                        if (dragPosition == null) {
                            resumeAfterDrag = player.isPlaying
                            player.pause()
                        }
                        // 拖拽中只更新本地显示,不 seek:Media3 每帧收到 seek 都要重新起播,
                        // 频繁调用会让画面/声音卡顿,真正的 seek 留到松手时一次性做。
                        dragPosition = (fraction * duration).toLong()
                    },
                    onValueChangeFinished = {
                        dragPosition?.let { target ->
                            player.seekTo(target)
                            position = target
                            if (resumeAfterDrag) player.play()
                        }
                        dragPosition = null
                    },
                    enabled = duration > 0,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(displayPosition), style = MaterialTheme.typography.labelSmall)
                    Text(formatTime(duration), style = MaterialTheme.typography.labelSmall)
                }
            }

            PlaybackControls(
                isPlaying = state.isPlaying,
                loading = state.loading,
                speed = speed,
                hasPrevious = state.positionInQueue > 1,
                hasNext = state.positionInQueue in 1 until state.queueSize,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play() },
                onPrevious = { player.seekToPrevious() },
                onNext = { player.seekToNext() },
                onSpeedChange = { player.setPlaybackSpeed(it) },
            )

            BottomRow(
                shuffled = state.shuffled,
                sleepTimer = sleepTimer,
                onToggleShuffle = onToggleShuffle,
                onSleepTimer = onSleepTimer,
            )
        }

        QueueList(
            queue = queue,
            currentBvid = state.current?.bvid,
            onPlayQueueItem = onPlayQueueItem,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun CoverAndInfo(state: AudioPlaybackUiState) {
    val item = state.current ?: return
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(24.dp),
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(item.coverUrl.toHttpsUrl())
                .httpHeaders(NetworkHeaders.Builder().add("Referer", REFERER).build())
                .build(),
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
        )
        Text(
            item.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            item.upName,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (state.queueSize > 0) {
            Text(
                "${state.positionInQueue} / ${state.queueSize}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    loading: Boolean,
    speed: Float,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSpeedChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        IconButton(onClick = onPrevious, enabled = hasPrevious) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "上一条", modifier = Modifier.size(32.dp))
        }
        if (loading) {
            Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            }
        } else {
            IconButton(onClick = onPlayPause, modifier = Modifier.size(56.dp)) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        IconButton(onClick = onNext, enabled = hasNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = "下一条", modifier = Modifier.size(32.dp))
        }

        var speedMenuOpen by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { speedMenuOpen = true }) {
                Icon(Icons.Filled.Speed, contentDescription = "倍速", modifier = Modifier.size(18.dp))
                Text(formatSpeed(speed), modifier = Modifier.padding(start = 4.dp))
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
    }
}

@Composable
private fun BottomRow(
    shuffled: Boolean,
    sleepTimer: SleepTimerState,
    onToggleShuffle: () -> Unit,
    onSleepTimer: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        TextButton(onClick = onToggleShuffle) {
            Icon(Icons.Filled.Shuffle, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (shuffled) "随机" else "顺序", modifier = Modifier.padding(start = 4.dp))
        }

        var sleepMenuOpen by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { sleepMenuOpen = true }) {
                Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(sleepTimerLabel(sleepTimer), modifier = Modifier.padding(start = 4.dp))
            }
            DropdownMenu(expanded = sleepMenuOpen, onDismissRequest = { sleepMenuOpen = false }) {
                SLEEP_TIMER_MINUTES.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text("$minutes 分钟") },
                        onClick = { sleepMenuOpen = false; onSleepTimer(minutes) },
                    )
                }
                DropdownMenuItem(
                    text = { Text("播完当前") },
                    onClick = { sleepMenuOpen = false; onSleepTimer(0) },
                )
                if (sleepTimer.mode != SleepTimerMode.Off) {
                    DropdownMenuItem(
                        text = { Text("取消") },
                        onClick = { sleepMenuOpen = false; onSleepTimer(-1) },
                    )
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
        if (index >= 0) {
            listState.animateScrollToItem(index)
            val info = listState.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.index == index }
            if (row != null) {
                listState.animateScrollToItem(index, -(info.viewportSize.height - row.size) / 2)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(queue, key = { it.bvid }) { item ->
            QueueRow(item = item, current = item.bvid == currentBvid, onClick = { onPlayQueueItem(item.bvid) })
        }
    }
}

@Composable
private fun QueueRow(item: QueueItem, current: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (current) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(item.coverUrl.toHttpsUrl())
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", REFERER).build())
                    .build(),
                contentDescription = null,
                modifier = Modifier.size(width = 72.dp, height = 45.dp).clip(RoundedCornerShape(4.dp)),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (current) {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.upName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun sleepTimerLabel(state: SleepTimerState): String = when (val mode = state.mode) {
    SleepTimerMode.Off -> "定时关闭"
    is SleepTimerMode.After -> state.remainingMillis?.let { formatTime(it) } ?: "${mode.minutes} 分钟"
    SleepTimerMode.EndOfItem -> "播完当前"
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
