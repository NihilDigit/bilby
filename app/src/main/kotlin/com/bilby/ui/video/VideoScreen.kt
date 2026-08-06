package com.bilby.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.bilby.data.CommentSort
import com.bilby.data.FavFolder
import com.bilby.data.SponsorSegment
import kotlinx.coroutines.delay
import com.bilby.data.VideoRelation
import com.bilby.player.PlayerFactory
import com.bilby.ui.comment.CommentUiState

/**
 * 播放页。**没有相关推荐栏、没有自动连播**(DESIGN 2.3/1.3);「找相关」占的是官方相关
 * 推荐的位置,但要用户点了才跑,见 VideoTabs。
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoScreen(
    state: VideoUiState,
    related: RelatedState,
    commentState: CommentUiState,
    sponsorSegments: List<SponsorSegment>,
    onSaveProgress: (position: Long, duration: Long) -> Unit,
    onQualityChange: (quality: Int, positionMillis: Long) -> Unit,
    onFindRelated: () -> Unit,
    onUpClick: (mid: Long) -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    onLike: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onPlayPart: (cid: Long) -> Unit,
    onPlayEpisode: (bvid: String) -> Unit,
    onRelatedVideoClick: (bvid: String) -> Unit,
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
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(player) {
        onDispose {
            onSaveProgress(player.currentPosition, player.duration.coerceAtLeast(0))
            player.release()
        }
    }

    // SponsorBlock:默认开启。轮询而不是用 Player 的事件,是因为跳过要在片段**起点**发生,
    // 而播放器没有"位置越过某点"的回调。500ms 的粒度足够,漏跳的代价只是多看半秒。
    var skippedCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player, sponsorSegments) {
        if (sponsorSegments.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(500)
            if (!player.isPlaying) continue
            val target = nextSkipTarget(player.currentPosition, sponsorSegments) ?: continue
            skippedCategory = sponsorSegments.firstOrNull { player.currentPosition in it.startMillis..it.endMillis }?.category
            player.seekTo(target)
        }
    }

    val streams = state.playInfo?.streams
    LaunchedEffect(streams) {
        val selected = streams ?: return@LaunchedEffect
        player.setMediaSource(PlayerFactory.createMediaSource(selected.videoUrl, selected.audioUrl))
        player.prepare()
        if (state.resumeAtMillis > 0) player.seekTo(state.resumeAtMillis)
        player.playWhenReady = true
    }

    // 全屏时播放器独占整屏,下面的简介/评论整块不参与布局。
    if (fullscreen) {
        BilbyPlayer(
            player = player,
            qualities = state.playInfo?.availableQualities.orEmpty(),
            currentQuality = state.currentQuality,
            onQualityChange = { onQualityChange(it, player.currentPosition) },
            isFullscreen = true,
            onFullscreenChange = { fullscreen = it },
            onSaveProgress = onSaveProgress,
            modifier = Modifier.fillMaxSize().background(Color.Black),
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
        ) {
            when {
                streams != null -> BilbyPlayer(
                    player = player,
                    qualities = state.playInfo?.availableQualities.orEmpty(),
                    currentQuality = state.currentQuality,
                    onQualityChange = { onQualityChange(it, player.currentPosition) },
                    isFullscreen = false,
                    onFullscreenChange = { fullscreen = it },
                    onSaveProgress = onSaveProgress,
                    modifier = Modifier.fillMaxSize(),
                )

                state.error != null -> Text(
                    state.error,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )

                else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }

            // 跳过要让用户看见,否则会以为播放器抽了。
            SkipToast(skippedCategory, Modifier.align(Alignment.TopCenter).padding(8.dp))
        }

        state.detail?.let { detail ->
            VideoTabs(
                detail = detail,
                currentCid = state.currentCid,
                related = related,
                commentState = commentState,
                onFindRelated = onFindRelated,
                onUpClick = { onUpClick(detail.up.mid) },
                relation = relation,
                favFolders = favFolders,
                onLike = onLike,
                onCoin = onCoin,
                onOpenFavPicker = onOpenFavPicker,
                onFavConfirm = onFavConfirm,
                onPlayPart = onPlayPart,
                onPlayEpisode = onPlayEpisode,
                onRelatedVideoClick = onRelatedVideoClick,
                onCommentSort = onCommentSort,
                onCommentLoadMore = onCommentLoadMore,
                onExpandReplies = onExpandReplies,
                onSendComment = onSendComment,
                onLikeComment = onLikeComment,
                onDeleteComment = onDeleteComment,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
