package dev.bilby.ui.video

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
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import dev.bilby.BiliLog
import dev.bilby.data.CommentSort
import dev.bilby.data.FavFolder
import dev.bilby.data.FollowState
import dev.bilby.data.SponsorSegment
import kotlinx.coroutines.delay
import dev.bilby.data.VideoRelation
import dev.bilby.player.AudioPlaybackService
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.listen.ListenScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 播放页。**没有相关推荐栏、没有自动连播**(DESIGN 2.3/1.3);「找相关」占的是官方相关
 * 推荐的位置,但要用户点了才跑,见 VideoTabs。
 *
 * **这里不建播放器**(DESIGN 2.4b:一个播放状态,两个 UI)。播放器归 [AudioPlaybackService]
 * 所有,页面连上去,把要播的流交给它,再通过 MediaController 控制;切到听视频只是换掉这层 UI。
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
    onListen: () -> Unit,
    followState: FollowState,
    onToggleFollow: () -> Unit,
    queue: QueueUiState,
    onPlayQueueItem: (bvid: String) -> Unit,
    onToggleShuffle: () -> Unit,
    onUpClick: (mid: Long) -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    onAddToView: () -> Unit,
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
    startListening: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    /**
     * 听视频是**页面内的一个状态**,和全屏同构:不是导航目的地,所以页面不离开组合、
     * 播放器不换、进度不交接 —— 没有任何生命周期需要处理。退出就是把它置回 false。
     */
    var listening by rememberSaveable { mutableStateOf(startListening) }

    DisposableEffect(context) {
        val future = MediaController.Builder(context, AudioPlaybackService.sessionToken(context))
            .buildAsync()
        future.addListener(
            {
                controller = runCatching { future.get() }
                    .onFailure { BiliLog.w("播放页连接播放服务失败", it) }
                    .getOrNull()
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose {
            controller?.let { connected ->
                onSaveProgress(connected.currentPosition, connected.duration.coerceAtLeast(0))
                connected.pause()
                // 播放页离开就暂停。听视频是页面内的状态,不会走到这里。
            }
            // **不 release 播放器**:它归服务所有,不归这个页面。页面离开只断开连接——
            // 在这里 release 就等于把后台正在听的那条一起掐了,而"页面走了"和"播放结束"
            // 本来就是两件事。
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    // 局部变量是为了让下面的空判断能智能转换,不是随手起的别名。
    val active = controller

    val audioState by AudioPlaybackService.state.collectAsStateWithLifecycle()
    val sleepTimerState by AudioPlaybackService.sleepTimerState.collectAsStateWithLifecycle()

    // 画面必须接在真的 ExoPlayer 上:MediaController 没有 COMMAND_SET_VIDEO_SURFACE
    // (Surface 是本地对象,递不到 session 那侧),这是 Media3 的已知限制。服务与 UI 同进程,
    // 所以能直接拿到同一个播放器对象;控制仍然全部走 controller。
    val surfacePlayer = active?.let { AudioPlaybackService.currentPlayer }

    // SponsorBlock:默认开启。轮询而不是用 Player 的事件,是因为跳过要在片段**起点**发生,
    // 而播放器没有"位置越过某点"的回调。500ms 的粒度足够,漏跳的代价只是多看半秒。
    var skippedCategory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(active, sponsorSegments) {
        if (active == null || sponsorSegments.isEmpty()) return@LaunchedEffect
        while (true) {
            delay(500)
            if (!active.isPlaying) continue
            val target = nextSkipTarget(active.currentPosition, sponsorSegments) ?: continue
            skippedCategory = sponsorSegments.firstOrNull { active.currentPosition in it.startMillis..it.endMillis }?.category
            active.seekTo(target)
        }
    }

    val streams = state.playInfo?.streams
    // 换 P、切清晰度都会给出一份新的流地址,都从这里交给服务;续播位置由 ViewModel 给
    // (切清晰度时它带回来的是切换前的位置)。同一份地址重复交不会重播,服务那边会认出来。
    LaunchedEffect(active, streams) {
        if (active == null) return@LaunchedEffect
        val selected = streams ?: return@LaunchedEffect
        val detail = state.detail
        active.sendCustomCommand(
            SessionCommand(AudioPlaybackService.ACTION_PLAY_VIDEO, Bundle.EMPTY),
            bundleOf(
                AudioPlaybackService.EXTRA_BVID to detail?.bvid.orEmpty(),
                AudioPlaybackService.EXTRA_CID to state.currentCid,
                AudioPlaybackService.EXTRA_VIDEO_URL to selected.videoUrl,
                AudioPlaybackService.EXTRA_AUDIO_URL to selected.audioUrl,
                AudioPlaybackService.EXTRA_START_POSITION to state.resumeAtMillis,
                AudioPlaybackService.EXTRA_TITLE to detail?.title.orEmpty(),
                AudioPlaybackService.EXTRA_UP_NAME to detail?.up?.name.orEmpty(),
                AudioPlaybackService.EXTRA_COVER_URL to detail?.coverUrl.orEmpty(),
            ),
        )
    }

    // 听视频时把视频轨关掉:不关的话画面虽然不渲染,流还是照下,白费流量和电。
    // 用禁轨而不是重建只含音频的媒体源——后者要重新 prepare 和 seek,又把"切模式
    // 不需要交接"这个性质破坏掉了。
    LaunchedEffect(active, listening) {
        val player = active ?: return@LaunchedEffect
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, listening)
            .build()
    }

    if (listening && active != null) {
        ListenScreen(
            player = active,
            state = audioState,
            sleepTimer = sleepTimerState,
            queue = queue.items,
            onPlayQueueItem = onPlayQueueItem,
            onToggleShuffle = onToggleShuffle,
            onSleepTimer = { minutes ->
                active.sendCustomCommand(
                    SessionCommand(AudioPlaybackService.ACTION_SLEEP_TIMER, Bundle.EMPTY),
                    bundleOf(AudioPlaybackService.EXTRA_SLEEP_MINUTES to minutes),
                )
            },
            onBack = { listening = false },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // 全屏时播放器独占整屏,下面的简介/评论整块不参与布局。
    if (fullscreen && active != null) {
        BilbyPlayer(
            player = active,
            surfacePlayer = surfacePlayer,
            qualities = state.playInfo?.availableQualities.orEmpty(),
            currentQuality = state.currentQuality,
            onQualityChange = { onQualityChange(it, active.currentPosition) },
            isFullscreen = true,
            onFullscreenChange = { fullscreen = it },
            // 全屏下切听视频要先退出全屏,否则听视频界面会顶着一个已经隐藏的系统栏。
            onListen = { fullscreen = false; onListen(); listening = true },
            onSaveProgress = onSaveProgress,
            title = state.detail?.title.orEmpty(),
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
                streams != null && active != null -> BilbyPlayer(
                    player = active,
                    surfacePlayer = surfacePlayer,
                    qualities = state.playInfo?.availableQualities.orEmpty(),
                    currentQuality = state.currentQuality,
                    onQualityChange = { onQualityChange(it, active.currentPosition) },
                    isFullscreen = false,
                    onFullscreenChange = { fullscreen = it },
                    onListen = { onListen(); listening = true },
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
                followState = followState,
                onToggleFollow = onToggleFollow,
                queue = queue,
                onPlayQueueItem = onPlayQueueItem,
                onToggleShuffle = onToggleShuffle,
                onUpClick = { onUpClick(detail.up.mid) },
                relation = relation,
                favFolders = favFolders,
                addedToView = addedToView,
                onLike = onLike,
                onAddToView = onAddToView,
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
