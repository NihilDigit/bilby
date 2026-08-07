package dev.bilby.ui.video

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.data.CommentSort
import dev.bilby.data.FavFolder
import dev.bilby.data.FollowState
import dev.bilby.data.SponsorSegment
import kotlinx.coroutines.delay
import dev.bilby.data.VideoRelation
import dev.bilby.player.AudioPlaybackService
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.listen.ListenScreen
import dev.bilby.ui.theme.Spacing
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 播放页。**没有相关推荐栏、没有自动连播**(DESIGN 2.3/1.3);「找相关」占的是官方相关
 * 推荐的位置,但要用户点了才跑,见 VideoTabs。
 *
 * **这里不建播放器**(DESIGN 2.4b:一个播放状态,两个 UI)。播放器归 [AudioPlaybackService]
 * 所有,页面连上去,把要播的流交给它,再通过 MediaController 控制;切到听视频只是换掉这层 UI。
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VideoScreen(
    state: VideoUiState,
    related: RelatedState,
    commentState: CommentUiState,
    sponsorSegments: List<SponsorSegment>,
    onReportProgress: (position: Long, duration: Long) -> Unit,
    onFindRelated: () -> Unit,
    followState: FollowState,
    onToggleFollow: () -> Unit,
    onUpClick: (mid: Long) -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    onAddToView: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onPlayEpisode: (bvid: String) -> Unit,
    onRelatedVideoClick: (bvid: String) -> Unit,
    onCommentSort: (CommentSort) -> Unit,
    onCommentLoadMore: () -> Unit,
    onExpandReplies: (Long) -> Unit,
    onSendComment: (String, Long?) -> Unit,
    onLikeComment: (Long) -> Unit,
    onDeleteComment: (Long) -> Unit,
    /**
     * 听视频开着没有。**状态在上一层**(见 MainActivity 的 VideoRoute):队列往前走一条会
     * 换掉整个页面,存在这里的话自动连播就会把人踢回有画面的界面。
     */
    listening: Boolean,
    onListeningChange: (Boolean) -> Unit,
    /** 当前这条(cid)有哪些字幕轨,含 AI 生成的;看视频的控制条和听视频的文稿共用同一份。 */
    subtitleTracks: List<SubtitleTrack> = emptyList(),
    /** 选中轨的语言代码,空字符串是关(默认)。 */
    subtitleLan: String = "",
    subtitleCues: List<SubtitleCue> = emptyList(),
    onSelectSubtitle: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    // DisposableEffect 捕获的是进入组合那一刻的 state,而 onDispose 要问的是离开那一刻。
    val latestState by rememberUpdatedState(state)
    val playerHoldsThisPage = {
        AudioPlaybackService.state.value.current?.bvid == latestState.detail?.bvid
    }

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
                onReportProgress(connected.currentPosition, connected.duration.coerceAtLeast(0))
                // 播放页离开就暂停,**但只在播放器装的还是本页这一条时**。听视频是页面内的
                // 状态,不会走到这里。
                //
                // 合集里点下一集是"压上新页、弹掉旧页":新页先组合,把自己的流交给那个唯一的
                // 播放器并起播,旧页的 onDispose 之后才跑。不认身份就会把刚起播的下一集摁停,
                // 表现为"进播放页不自动播放"。这与进度串味是同一个毛病——页面对着共享播放器
                // 发命令,却没问播放器还是不是自己的。
                if (playerHoldsThisPage()) connected.pause()
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

    /** 队列的唯一来源是服务。页面只是把它摆出来,不自己攒一份。 */
    val shownQueue = QueueUiState(
        items = audioState.items,
        currentBvid = audioState.current?.bvid,
        sourceLabel = audioState.sourceLabel,
        shuffled = audioState.shuffled,
        loading = audioState.loading && audioState.items.isEmpty(),
    )

    /** 发一条自定义命令的简写。控制一律走 controller,不碰 currentPlayer。 */
    val send: (String, Bundle) -> Unit = { action, args ->
        active?.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }

    /**
     * 打开这条视频。**这是页面对播放器说的唯一一句话**,而且是幂等的:服务那边队列已经是
     * 这条、播放器也正装着它时,这条命令什么都不做。
     *
     * 于是转屏、退出全屏、从听视频退回、通知栏切过一条之后再回到界面,全都不会重新装载。
     * 原先页面交的是流地址,"是不是同一次播放"只能靠字符串相等去猜,而 playurl 每次签名
     * 都不同 —— 那正是重试当初必须再加一个 force 标志位去绕过的东西。
     */
    LaunchedEffect(active, state.detail?.bvid) {
        val detail = state.detail ?: return@LaunchedEffect
        if (active == null) return@LaunchedEffect
        send(
            AudioPlaybackService.ACTION_OPEN_VIDEO,
            bundleOf(
                AudioPlaybackService.EXTRA_BVID to detail.bvid,
                AudioPlaybackService.EXTRA_CID to detail.cid,
                AudioPlaybackService.EXTRA_MID to detail.up.mid,
                AudioPlaybackService.EXTRA_TITLE to detail.title,
                AudioPlaybackService.EXTRA_UP_NAME to detail.up.name,
                AudioPlaybackService.EXTRA_COVER_URL to detail.coverUrl,
            ),
        )
    }

    val toggleShuffle: () -> Unit = {
        send(
            AudioPlaybackService.ACTION_SET_SHUFFLE,
            bundleOf(AudioPlaybackService.EXTRA_SHUFFLED to !audioState.shuffled),
        )
    }

    /** 点队列里的一条 = 让队列跳过去。页面不自己导航,它跟着队列走(见 VideoRoute)。 */
    val onPlayQueueItem: (String) -> Unit = { target ->
        send(
            AudioPlaybackService.ACTION_SEEK_TO_BVID,
            bundleOf(AudioPlaybackService.EXTRA_BVID to target),
        )
    }

    val onPlayPart: (Long) -> Unit = { cid ->
        send(
            AudioPlaybackService.ACTION_PLAY_PART,
            bundleOf(AudioPlaybackService.EXTRA_CID to cid),
        )
    }

    /** 切清晰度。位置不用页面带过去了 —— 取流的那一侧就是持有播放器的那一侧。 */
    val setQuality: (Int) -> Unit = { quality ->
        send(
            AudioPlaybackService.ACTION_SET_QUALITY,
            bundleOf(AudioPlaybackService.EXTRA_QUALITY to quality),
        )
    }

    /** 重试只有一个实现了:流归服务取,它自己就能重来。 */
    val retryPlayback: () -> Unit = { send(AudioPlaybackService.ACTION_RETRY, Bundle.EMPTY) }

    /**
     * 播放失败的提示。**只在播放器装的确实是本页这一条时显示** —— 播放器全 app 共用一个,
     * 队列走到别的视频上时,那一条的失败不该盖到这一页上。
     */
    val playbackError = audioState.error?.takeIf { audioState.current?.bvid == state.detail?.bvid }

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

    // TODO(动效):听视频与全屏在 M3 里都属于 container transform 的 "within a screen"
    // 子类——同一个容器改形态,播放器是 persistent element。现在三种形态是三段各自 return
    // 的独立子树,没有共同的布局树,`Modifier.animateBounds` 无从下手(它要求同一个
    // composable 实例在 LookaheadScope 里改变边界)。
    //
    // 做的话要先把这三段合成一棵树:一个播放器容器,边界按形态取(内嵌 16:9 / 全屏铺满 /
    // 听视频的封面位),外面套 LookaheadScope,其余内容按形态显隐。依赖已具备,
    // compose.animation 解析到 1.12.0-rc01,`SharedTransitionLayout` 与 `animateBounds`
    // 都在(标 @ExperimentalSharedTransitionApi)。
    //
    // 注意听视频这一段与全屏不同:全屏是同一份画面换尺寸,而听视频把视频轨关掉、显示的是
    // 封面,persistent element 到底是"播放器"还是"封面"需要先定,否则 bounds 两端接的
    // 不是同一个东西。
    if (listening && active != null) {
        // 听视频时系统返回键/侧滑不会自己落到 ListenScreen 顶栏的 onBack 上——不拦的话它
        // 直接穿透到 MainActivity 的 backStack.removeLastOrNull(),整个播放页被弹掉、
        // 人回到动态流而播放器还在后台放。全屏那半边已经有同样一句(BilbyPlayer.kt)。
        //
        // 队列 Sheet 打开时不用另外处理:ModalBottomSheet 自己注册的 BackHandler 在组合树里
        // 更靠后,会先接住那一次返回把 Sheet 关掉,这句退听视频要按第二次才轮到——这个先后
        // 顺序是被依赖的,不是巧合。
        BackHandler { onListeningChange(false) }
        ListenScreen(
            player = active,
            state = audioState,
            sleepTimer = sleepTimerState,
            queue = audioState.items,
            onPlayQueueItem = onPlayQueueItem,
            parts = state.detail?.pages.orEmpty(),
            currentCid = audioState.currentCid,
            onPlayPart = onPlayPart,
            onNext = { send(AudioPlaybackService.ACTION_NEXT, Bundle.EMPTY) },
            onPrevious = { send(AudioPlaybackService.ACTION_PREVIOUS, Bundle.EMPTY) },
            onToggleShuffle = toggleShuffle,
            onRetry = retryPlayback,
            onSleepTimer = { minutes, finishCurrentItem ->
                // 哨兵值(不设时长的缺省)只在 AudioPlaybackService 里定义,这里不重复写字面量——
                // 不带这个 key 就是"没设",取值那边自己有默认。
                val args = Bundle().apply {
                    if (minutes != null) putInt(AudioPlaybackService.EXTRA_SLEEP_MINUTES, minutes)
                    putBoolean(AudioPlaybackService.EXTRA_SLEEP_FINISH_CURRENT, finishCurrentItem)
                }
                active.sendCustomCommand(
                    SessionCommand(AudioPlaybackService.ACTION_SLEEP_TIMER, Bundle.EMPTY),
                    args,
                )
            },
            // 退出听视频就是把这个壳关掉,**没有第二件事**。队列不动、播放不停、视频轨由上面
            // 那个 LaunchedEffect 自己打开。页面此刻可能已经不是队列当前那一条了(听的时候
            // 连播过去了),跟过去是 VideoRoute 的职责 —— 它盯着队列,不需要这里通知。
            onBack = { onListeningChange(false) },
            subtitleTracks = subtitleTracks,
            subtitleLan = subtitleLan,
            onSelectSubtitle = onSelectSubtitle,
            subtitleCues = subtitleCues,
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    // 全屏时播放器独占整屏,下面的简介/评论整块不参与布局。
    if (fullscreen && active != null) {
        // 全屏也要能看到失败并重试,否则唯一的出路是先退出全屏 —— 而失败时画面是黑的,
        // 连"退出全屏"那个按钮在哪都不明显。
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            BilbyPlayer(
                player = active,
                surfacePlayer = surfacePlayer,
                qualities = audioState.playInfo?.availableQualities.orEmpty(),
                currentQuality = audioState.currentQuality,
                onQualityChange = { setQuality(it) },
                isFullscreen = true,
                onFullscreenChange = { fullscreen = it },
                // 全屏下切听视频要先退出全屏,否则听视频界面会顶着一个已经隐藏的系统栏。
                onListen = { fullscreen = false; onListeningChange(true) },
                onReportProgress = onReportProgress,
                title = state.detail?.title.orEmpty(),
                subtitleTracks = subtitleTracks,
                currentSubtitleLan = subtitleLan,
                onSubtitleTrackChange = onSelectSubtitle,
                subtitleCues = subtitleCues,
                modifier = Modifier.fillMaxSize(),
            )
            if (playbackError != null) {
                PlaybackFailure(
                    message = playbackError,
                    retrying = audioState.loading,
                    onRetry = retryPlayback,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        return
    }

    // 找相关做成底部 sheet:它是对当前视频问的一句话,不是一个要离开播放页的去处。
    // 用 BottomSheetScaffold 而不是 ModalBottomSheet —— 后者带遮罩会把视频压暗,而这个
    // 功能的前提就是"我还在看这个视频";peek 高度天生就能表达「问过之后常驻的把手」。
    val sheetState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()

    // 把手只在问过之后存在,并活到离开播放页为止:它是**你自己那次提问的记忆**,
    // 不是打开播放页就在那儿等着的入口。换个视频就是新的 VideoRoute,自动没有。
    val peek = if (related.started) SheetHandleHeight else 0.dp

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = peek,
        // M3 给标准 sheet 的容器色是 surface container low,而这一页的队列卡片用的是
        // surface container —— 两者叠在一起几乎没有色差,边界就消失了。抬高一档并加重
        // 投影:标准 sheet 没有遮罩,分隔完全靠容器色与阴影承担。
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetShadowElevation = 12.dp,
        sheetContent = {
            RelatedSheet(
                related = related,
                onVideoClick = onRelatedVideoClick,
                onRetry = onFindRelated,
            )
        },
    ) { insets ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(bottom = insets.calculateBottomPadding())
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black),
            ) {
                when {
                    audioState.playInfo != null && active != null -> BilbyPlayer(
                        player = active,
                        surfacePlayer = surfacePlayer,
                        qualities = audioState.playInfo?.availableQualities.orEmpty(),
                        currentQuality = audioState.currentQuality,
                        onQualityChange = { setQuality(it) },
                        isFullscreen = false,
                        onFullscreenChange = { fullscreen = it },
                        onListen = { onListeningChange(true) },
                        onReportProgress = onReportProgress,
                seekBarSegments = sponsorSegments.toSeekBarSegments(),
                        subtitleTracks = subtitleTracks,
                        currentSubtitleLan = subtitleLan,
                        onSubtitleTrackChange = onSelectSubtitle,
                        subtitleCues = subtitleCues,
                        modifier = Modifier.fillMaxSize(),
                    )

                    state.error != null -> Text(
                        state.error,
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    )

                    else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                // 盖在画面上而不是排在下面:失败时画面本来就是黑的,而简介区在一屏之外,
                // 提示放那儿等于没有。state.error 那一支是"流都没取到",两者不会同时出现。
                if (playbackError != null) {
                    PlaybackFailure(
                        message = playbackError,
                        // 两个 loading 都要看:重取那一步归本页(state.loading),重新装载
                        // 那一步归服务(audioState.loading)。只看后者的话,重取在飞的那一两秒
                        // 按钮会重新亮起来,能连按出好几次重取。
                        retrying = state.loading || audioState.loading,
                        onRetry = retryPlayback,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                SkipToast(skippedCategory, Modifier.align(Alignment.TopCenter).padding(8.dp))
            }

            state.detail?.let { detail ->
                VideoTabs(
                    detail = detail,
                    currentCid = audioState.currentCid,
                    related = related,
                    commentState = commentState,
                    // 点闪光:没问过就发起检索,问过就只是把 sheet 展开 —— 再点一次重跑
                    // 会把已有结果冲掉,而用户此刻多半只是想再看一眼。
                    onFindRelated = {
                        if (!related.started) onFindRelated()
                        scope.launch { sheetState.bottomSheetState.expand() }
                    },
                    followState = followState,
                    onToggleFollow = onToggleFollow,
                    queue = shownQueue,
                    onPlayQueueItem = onPlayQueueItem,
                    onToggleShuffle = toggleShuffle,
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
}

/**
 * 播放失败盖在画面上的那一块。
 *
 * 和听视频页那一行是两个实现,因为形态差得远:这里画面是黑的、要盖在正中,那边是一行
 * 贴着播放控制的小字。共用一个 composable 只会得到一个到处是 if 的壳。
 */
@Composable
private fun PlaybackFailure(
    message: String,
    retrying: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(Spacing.Comfortable),
    ) {
        Text(
            text = message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        // 重试中不给按钮:此刻按下去只会打断已经在跑的那次。
        if (retrying) {
            CircularProgressIndicator(
                color = Color.White,
                modifier = Modifier.padding(top = Spacing.Cozy).size(24.dp),
            )
        } else {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry), color = Color.White)
            }
        }
    }
}
