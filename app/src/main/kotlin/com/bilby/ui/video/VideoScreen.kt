package com.bilby.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.bilby.api.BiliConstants
import com.bilby.data.VideoDetail
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.PlayerSurface
import com.bilby.player.PlayerFactory
import com.bilby.data.CommentSort
import com.bilby.ui.comment.CommentSection
import com.bilby.ui.comment.CommentUiState
import kotlinx.coroutines.delay

/**
 * 播放页。**没有相关推荐栏、没有自动连播**(DESIGN 2.3/1.3)——合集内的下一 P 导航是
 * 确定性导航,不是推荐,那个保留。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(
    state: VideoUiState,
    commentState: CommentUiState,
    onSaveProgress: (position: Long, duration: Long) -> Unit,
    onFindRelated: (title: String, upName: String) -> Unit,
    onUpClick: (mid: Long) -> Unit,
    onPlayPart: (cid: Long) -> Unit,
    onPlayEpisode: (bvid: String) -> Unit,
    onCommentSort: (CommentSort) -> Unit,
    onCommentLoadMore: () -> Unit,
    onExpandReplies: (Long) -> Unit,
    onSendComment: (String, Long?) -> Unit,
    onLikeComment: (Long) -> Unit,
    onDeleteComment: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember { PlayerFactory.createPlayer(context) }

    DisposableEffect(player) {
        onDispose {
            onSaveProgress(player.currentPosition, player.duration.coerceAtLeast(0))
            player.release()
        }
    }

    // 竖屏视频、4:3 老片都存在,写死 16:9 会把画面拉变形。容器保持 16:9(否则页面高度
    // 会随视频跳动),画面按真实比例居中,多出来的地方留黑边。
    var videoAspect by remember { mutableFloatStateOf(16f / 9f) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val height = videoSize.height
                if (height > 0 && videoSize.width > 0) {
                    videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / height
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val streams = state.playInfo?.streams
    LaunchedEffect(streams) {
        val selected = streams ?: return@LaunchedEffect
        player.setMediaSource(PlayerFactory.createMediaSource(selected.videoUrl, selected.audioUrl))
        player.prepare()
        if (state.resumeAtMillis > 0) player.seekTo(state.resumeAtMillis)
        player.playWhenReady = true
    }

    Column(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            if (streams != null) {
                PlayerSurface(
                    player = player,
                    modifier = Modifier.align(Alignment.Center).aspectRatio(videoAspect),
                )
                PlayerControls(player, onSaveProgress, Modifier.align(Alignment.BottomCenter))
            } else if (state.error != null) {
                Text(
                    state.error,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }

        state.detail?.let { detail ->
            VideoDetailHeader(
                detail = detail,
                currentCid = state.currentCid,
                onUpClick = { onUpClick(detail.up.mid) },
                onFindRelated = { onFindRelated(detail.title, detail.up.name) },
                onPlayPart = onPlayPart,
                onPlayEpisode = onPlayEpisode,
            )
        }

        CommentSection(
            state = commentState,
            onSort = onCommentSort,
            onLoadMore = onCommentLoadMore,
            onExpandReplies = onExpandReplies,
            onSend = onSendComment,
            onLike = onLikeComment,
            onDelete = onDeleteComment,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 详情块。简介默认折叠、展开后限高可滚 —— 播放页的主角是画面和评论,简介不该把评论顶出屏幕。
 * 分 P 与合集分集是确定性导航,不是推荐(DESIGN 2.3),所以它们只在真的存在时出现。
 */
@Composable
private fun VideoDetailHeader(
    detail: VideoDetail,
    currentCid: Long,
    onUpClick: () -> Unit,
    onFindRelated: () -> Unit,
    onPlayPart: (Long) -> Unit,
    onPlayEpisode: (String) -> Unit,
) {
    var descriptionExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(detail.title, style = MaterialTheme.typography.titleMedium)

        Text(
            text = "${formatCount(detail.stat.view)}播放 · ${formatCount(detail.stat.danmaku)}弹幕 · " +
                formatDate(detail.publishedAtEpochSeconds),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp).clickable(onClick = onUpClick),
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(detail.up.faceUrl)
                    .httpHeaders(NetworkHeaders.Builder().add("Referer", BiliConstants.REFERER).build())
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp).clip(CircleShape),
            )
            Text(
                detail.up.name,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 唯一的关联入口:显式点一次,给 3–5 条带理由的,没有第二页,不常驻
            // (DESIGN 2.3)。这里绝不能长成常驻的相关推荐栏。
            TextButton(onClick = onFindRelated, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("找相关")
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            StatText("点赞", detail.stat.like)
            StatText("投币", detail.stat.coin)
            StatText("收藏", detail.stat.favorite)
            StatText("评论", detail.stat.reply)
        }

        if (detail.description.isNotBlank()) {
            Text(
                text = detail.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (descriptionExpanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .then(if (descriptionExpanded) Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()) else Modifier)
                    .clickable { descriptionExpanded = !descriptionExpanded },
            )
        }

        if (detail.pages.size > 1) {
            PartRow(
                title = "分 P",
                prefix = "P",
                labels = detail.pages.map { it.index to it.title },
                isCurrent = { index -> detail.pages.getOrNull(index)?.cid == currentCid },
                onClick = { index -> detail.pages[index].cid.let(onPlayPart) },
            )
        }

        if (detail.seasonEpisodes.isNotEmpty()) {
            PartRow(
                title = detail.seasonTitle.ifBlank { "合集" },
                // 合集的编号语义是"第几集",不是分 P
                prefix = "",
                labels = detail.seasonEpisodes.mapIndexed { i, ep -> i + 1 to ep.title },
                isCurrent = { index -> detail.seasonEpisodes[index].bvid == detail.bvid },
                onClick = { index -> onPlayEpisode(detail.seasonEpisodes[index].bvid) },
            )
        }
    }
}

@Composable
private fun StatText(label: String, value: Long) {
    Text(
        text = "$label ${formatCount(value)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PartRow(
    title: String,
    prefix: String,
    labels: List<Pair<Int, String>>,
    isCurrent: (Int) -> Boolean,
    onClick: (Int) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            itemsIndexed(labels) { index, (number, name) ->
                FilterChip(
                    selected = isCurrent(index),
                    onClick = { onClick(index) },
                    label = {
                        val label = if (prefix.isEmpty()) "$number. $name" else "$prefix$number $name"
                        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
        }
    }
}

private fun formatCount(value: Long): String = when {
    value >= 100_000_000 -> "%.1f亿".format(value / 100_000_000.0)
    value >= 10_000 -> "%.1f万".format(value / 10_000.0)
    else -> value.toString()
}

private fun formatDate(epochSeconds: Long): String =
    java.time.Instant.ofEpochSecond(epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

/**
 * 自绘控制条。media3-ui-compose 只给画面(PlayerSurface),不给控件;引 media3-ui 的
 * PlayerView 能白拿一整套控件,但那是 View 体系的老组件,会把整棵 Compose 树套进
 * AndroidView 里。控件只有播放/暂停和进度两件事,自己画更划算。
 */
@Composable
private fun PlayerControls(
    player: Player,
    onSaveProgress: (Long, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    // 播放器状态没有 Flow,只能轮询。500ms 对进度条足够,也不至于让每帧都重组。
    LaunchedEffect(player) {
        while (true) {
            isPlaying = player.isPlaying
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }

    // 进程被杀不会走 onDispose,所以播放中也定期落盘一次进度。
    LaunchedEffect(player) {
        while (true) {
            delay(5_000)
            if (player.isPlaying) onSaveProgress(player.currentPosition, player.duration.coerceAtLeast(0))
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "暂停" else "播放",
                tint = Color.White,
            )
        }
        Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = Color.White)
        Slider(
            value = if (duration > 0) position.toFloat() / duration else 0f,
            onValueChange = { player.seekTo((it * duration).toLong()) },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

private fun formatTime(millis: Long): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
