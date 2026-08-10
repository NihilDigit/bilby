package dev.bilby.ui.video

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import dev.bilby.data.MemberCard
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorSegment
import kotlinx.coroutines.delay
import dev.bilby.data.VideoRelation
import dev.bilby.player.AudioPlaybackService
import dev.bilby.player.QueueItem
import dev.bilby.player.SubtitleCue
import dev.bilby.player.SubtitleTrack
import dev.bilby.data.DanmakuPrefs
import dev.nihildigit.danmaku.Danmaku
import dev.nihildigit.danmaku.SpecialDanmaku
import dev.bilby.ui.comment.CommentUiState
import dev.bilby.ui.listen.ListenScreen
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.barsAndCutout
import dev.bilby.ui.horizontalCutout
import dev.bilby.ui.ShareLink
import dev.bilby.ui.BilbyWindowSize
import dev.bilby.ui.isAtLeast
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.player.MediaBackButton
import dev.bilby.ui.theme.Breakpoints
import dev.bilby.ui.theme.FixedColors
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
    /**
     * 这一页在看哪条视频。**故意不用 `state.detail?.bvid`**——详情要等一次网络往返才回来,
     * 到得比"播放器切没切过来"这个判断晚,会正好错过要遮画面残留的那段窗口(见下面
     * `matchesCurrentPage` 的用法)。这个值由 `VideoPane` 直接传,它手上现成的路由参数就是它。
     */
    bvid: String,
    /**
     * 导航指名的那一 P。**0 = 不指名**,由服务按详情/观看记录决定 —— 除缓存列表外的所有入口
     * 都是 0,行为和以前一个字节都没变。
     */
    cid: Long = 0,
    state: VideoUiState,
    related: RelatedState,
    commentState: CommentUiState,
    sponsorSegments: List<SponsorSegment>,
    onReportProgress: (position: Long, duration: Long) -> Unit,
    onFindRelated: () -> Unit,
    /** 已缓存(或正在缓存)的 bvid。缓存面板拿它把已有的那几条标出来并禁选。 */
    cachedBvids: Set<String> = emptySet(),
    /** 缓存面板按下确认。清晰度与"要不要弹幕"都在面板里选,这里只负责把结果交出去。 */
    onCacheSelection: (List<QueueItem>, qualityId: Int, withDanmaku: Boolean) -> Unit = { _, _, _ -> },
    followState: FollowState,
    onToggleFollow: () -> Unit,
    /** UP 主等级,独立请求、独立失败——查不到就是 null,徽章不画(见 VideoViewModel.upCard)。 */
    upCard: MemberCard?,
    onUpClick: (mid: Long) -> Unit,
    /** 已关注的联合投稿成员;null = 还没查到。 */
    staffFollowed: Set<Long>?,
    onFollowStaff: (Long) -> Unit,
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
    onBack: () -> Unit,
    onRetry: () -> Unit,
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
    /** 弹幕总开关,默认关。只在看视频时有意义——听视频没有画面挂弹幕层。 */
    danmakuPrefs: DanmakuPrefs = DanmakuPrefs(),
    onDanmakuEnabledChange: (Boolean) -> Unit = {},
    /** 弹幕整体不透明度,由设置页 Slider 持久化。 */
    /** 已拉到的弹幕池,时间轴的编译在 BilbyPlayer 里做(需要 Compose 层的测量与画布宽度)。 */
    danmakuPool: List<Danmaku> = emptyList(),
    specialDanmakuPool: List<SpecialDanmaku> = emptyList(),
    /** 长按画面的临时倍速,来自设置页。 */
    fastForwardSpeed: Float = SettingsStore.DEFAULT_FAST_FORWARD_SPEED,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    /**
     * 控件锁。提到这一层是因为返回键要先解锁再退出全屏(见下面的 BackHandler),而它原先是
     * BilbyPlayer 内部的 rememberSaveable —— 全屏和非全屏是两个不同的调用点,那份状态本来
     * 就各存各的,对不上。
     */
    var locked by rememberSaveable { mutableStateOf(false) }
    // 退出全屏一律解锁:锁按钮只在全屏出现,带着 locked 回到内嵌播放器的话,那边的点击手势
    // 会被静默吞掉,而屏幕上没有任何东西提示它锁着。
    LaunchedEffect(fullscreen) { if (!fullscreen) locked = false }

    // DisposableEffect 捕获的是进入组合那一刻的 state,而 onDispose 要问的是离开那一刻。
    val latestState by rememberUpdatedState(state)
    val playerHoldsThisPage = {
        AudioPlaybackService.state.value.queue?.current?.bvid == latestState.detail?.bvid
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

    // 播放器此刻装的是不是这一页的视频。播放器全 app 共用一份、跨页面存活,点开一条新视频
    // 到播放器真正切过去之间有一段取流 + prepare 的窗口,这段时间 surfacePlayer 渲染的还是
    // 上一条视频的最后几帧——传给 BilbyPlayer,由它决定挂画面还是画占位(不在这里暂停或
    // 销毁播放器,那会打断后台连续播放)。
    //
    // 判据是**播放器装着的那条**,不是队列指着的那条:打开命令一到队列就指向新视频了,
    // 而取流还要几百毫秒。拿队列判的话,这段时间画面会被当成本页的挂上去,用户看到的是
    // 上一条视频冻住的最后一帧。
    val matchesCurrentPage = audioState.loadKey == bvid

    /**
     * 缓存面板开着没有。**只是这一页的一个浮层**,不是导航目的地也不是播放状态 ——
     * 选完就关,选的结果交给应用级的下载器,页面不留任何东西。
     */
    var cacheSheetOpen by rememberSaveable { mutableStateOf(false) }

    /** 队列的唯一来源是服务。页面只是把它摆出来,不自己攒一份。 */
    val shownQueue = QueueUiState(
        items = audioState.queue?.items.orEmpty(),
        currentBvid = audioState.queue?.current?.bvid,
        sourceLabel = audioState.queue?.sourceLabel.orEmpty(),
        shuffled = (audioState.queue?.shuffled == true),
        // 判据从"取流转圈且队列是空的"换成"完整队列建好了没有"。起播现在不等建队列,队列里
        // 那一条是临时占位而不是队列内容(见 AudioPlaybackService.openVideo),按旧判据永远
        // 是"有队列",面板上会摆出一条孤零零的视频。
        enriching = (audioState.queue?.enriching == true),
        incomplete = (audioState.queue?.incomplete == true),
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
     *
     * **这句话发两遍**,因为知道 bvid 和知道这条视频叫什么之间隔着一次网络请求:
     * 拿到 bvid 就发第一遍,服务据此立刻取流;详情回来再发一遍,补上标题、UP、封面和真正的
     * cid。第二遍落在服务的"同一条视频"分支上,只补元数据、不重新装载。
     */
    LaunchedEffect(active, bvid, cid) {
        if (active == null) return@LaunchedEffect
        // 默认不带 cid:服务会用视频详情补,而那份详情正是这个页面此刻也在等的同一份请求
        // (VideoRepository 按 bvid 合并并发详情请求),不会多打一次接口。
        //
        // **导航指名了哪一 P 时带上它**(只有缓存列表会指名):服务据此走缓存查找的精确匹配
        // 那一支 —— 用户点的是 P7 就得是 P7,拿别的 P 顶上比播不了更糟,画面在动而内容是错的。
        send(
            AudioPlaybackService.ACTION_OPEN_VIDEO,
            if (cid == 0L) {
                bundleOf(AudioPlaybackService.EXTRA_BVID to bvid)
            } else {
                bundleOf(
                    AudioPlaybackService.EXTRA_BVID to bvid,
                    AudioPlaybackService.EXTRA_CID to cid,
                )
            },
        )
    }

    LaunchedEffect(active, state.detail?.bvid) {
        val detail = state.detail ?: return@LaunchedEffect
        if (active == null) return@LaunchedEffect
        // **详情必须是这一页这条视频的。** 视频页现在整页只有一个 ViewModel、靠 switchTo 换
        // 内容,上一条的详情有可能还挂在状态里;不认身份就会拿旧 bvid 去开播,把用户刚点开的
        // 这条顶掉。
        if (detail.bvid != bvid) return@LaunchedEffect
        send(
            AudioPlaybackService.ACTION_OPEN_VIDEO,
            bundleOf(
                AudioPlaybackService.EXTRA_BVID to detail.bvid,
                AudioPlaybackService.EXTRA_CID to detail.cid,
                AudioPlaybackService.EXTRA_TITLE to detail.title,
                AudioPlaybackService.EXTRA_UP_NAME to detail.up.name,
                AudioPlaybackService.EXTRA_COVER_URL to detail.coverUrl,
            ),
        )
    }

    /**
     * 队列没建全时的重试。**没有专门的命令**:再发一遍 OPEN_VIDEO 就是重试 —— 它落在服务的
     * "同一条视频"分支,那里看到队列还停在临时队列上就会重新补全一次。
     */
    val retryQueue: () -> Unit = {
        val detail = state.detail
        // **不带 cid。** 带的话服务会拿它和正在播的那一 P 比,而这里给得出的只有默认 P1 ——
        // 在多 P 视频的第 3 P 上点一下重试,人会被送回第 1 P。重建队列和播到哪一 P 无关。
        send(
            AudioPlaybackService.ACTION_OPEN_VIDEO,
            bundleOf(
                AudioPlaybackService.EXTRA_BVID to bvid,
                AudioPlaybackService.EXTRA_TITLE to detail?.title.orEmpty(),
                AudioPlaybackService.EXTRA_UP_NAME to detail?.up?.name.orEmpty(),
                AudioPlaybackService.EXTRA_COVER_URL to detail?.coverUrl.orEmpty(),
            ),
        )
    }

    val toggleShuffle: () -> Unit = {
        send(
            AudioPlaybackService.ACTION_SET_SHUFFLE,
            bundleOf(AudioPlaybackService.EXTRA_SHUFFLED to !(audioState.queue?.shuffled == true)),
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
    val rawPlaybackError = audioState.error?.takeIf { audioState.queue?.current?.bvid == state.detail?.bvid }

    /**
     * **失败不立刻报。** 服务在取流路上自己会重试(见 AudioPlaybackService 的重试次数与退避),
     * 重试期间 error 就已经是有值的了 —— 照直显示的话,一次最终成功的加载中途也会闪一下
     * "播放失败",而那时它明明还在正常往下走。
     *
     * 等它稳定 [PlaybackErrorGraceMillis] 还在,才当成真失败。error 中途消失或换了内容,
     * 这个 effect 会重启,计时从头开始。
     */
    var playbackError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(rawPlaybackError) {
        if (rawPlaybackError == null) {
            playbackError = null
            return@LaunchedEffect
        }
        delay(PlaybackErrorGraceMillis)
        playbackError = rawPlaybackError
    }

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

    // **同步写,不发自定义命令。** 后者是异步投递的,而 Activity.onStop 会同步去读它 ——
    // "打开听视频后立刻锁屏"是这个功能最常见的用法,那条路径上命令很可能还没送到,
    // 服务读到的仍是"不允许后台播",刚开的听视频当场被摁停。
    //
    // 这不需要等 MediaController 连上:它是一个进程内的策略开关,与 session 无关。
    LaunchedEffect(listening) {
        AudioPlaybackService.setBackgroundPlaybackAllowed(listening)
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
            queue = audioState.queue?.items.orEmpty(),
            onPlayQueueItem = onPlayQueueItem,
            parts = state.detail?.pages.orEmpty(),
            currentCid = (audioState.queue?.currentCid ?: 0L),
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
            // 听视频这一屏是普通页面(封面 + 控件),不是全出血的媒体,所以要像导航层那样
            // 躲开横向挖孔 —— 播放页整个目的地被排除在 `CutoutSafe` 之外,这里没人替它做。
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.horizontalCutout),
        )
        return
    }

    // 全屏时播放器独占整屏,下面的简介/评论整块不参与布局。
    // 全屏时返回键的处理顺序照 PiliPlus 的 `onPopInvokedWithResult`
    // (plugin/pl_player/controller.dart):**先解锁,再退出全屏**,两级各吃一次返回。
    // 它那边 `canPop` 只看 isFullScreen,锁定态必然伴随全屏(锁按钮只在全屏出现),所以
    // 这里同样用 isFullscreen 当总开关就够。
    //
    // 放在播放页这一层而不是 BilbyPlayer 里:全屏分支要等 MediaController 连上才组合
    // (`fullscreen && active != null`),连上之前 BilbyPlayer 不在组合里,返回键没人接,
    // 一按就把整个播放页弹掉了。
    //
    // 左右滑动 seek 与这里无关,那是播放器自己的手势,不经过返回分发。
    BackHandler(enabled = fullscreen) {
        if (locked) locked = false else fullscreen = false
    }

    // 找相关做成底部 sheet:它是对当前视频问的一句话,不是一个要离开播放页的去处。
    // 用 BottomSheetScaffold 而不是 ModalBottomSheet —— 后者带遮罩会把视频压暗,而这个
    // 功能的前提就是"我还在看这个视频";peek 高度天生就能表达「问过之后常驻的把手」。
    val sheetState = rememberBottomSheetScaffoldState()
    val scope = rememberCoroutineScope()
    val expandedLayout = rememberBilbyWindowSize().isAtLeast(BilbyWindowSize.Expanded)

    // 把手只在问过之后存在,并活到离开播放页为止:它是**你自己那次提问的记忆**,
    // 不是打开播放页就在那儿等着的入口。换个视频就是新的 VideoRoute,自动没有。
    val peek = if (related.started) SheetHandleHeight else 0.dp

    // sheet 展开到**刚好盖住投币那一行**为止:上面的画面、标题、UP 行都还看得见,
    // 而「找相关」问的正是"这条视频"——把它盖掉就没有参照物了。
    //
    // 位置只认第一次量到的那个值。投币行跟着简介页一起滚,持续跟随的话用户滑一下简介、
    // sheet 就跟着变高变矮;而这条锚线表达的是版面上的一个位置,不是那个按钮此刻在哪。
    var actionsTop by remember { mutableIntStateOf(0) }
    // 把手画在 sheet 内容**之上**,是 sheet 总高的一部分。不扣掉的话锚线会整体上移一个把手的
    // 高度 —— 实测就是这样多盖住了 UP 那一行。它的高度不硬编码:BottomSheetDefaults.DragHandle
    // 没有公开的尺寸常量,而猜一个数字会在 M3 改版时悄悄错位。
    var handleHeight by remember { mutableIntStateOf(0) }
    val windowHeight = LocalWindowInfo.current.containerSize.height
    val sheetHeight = with(LocalDensity.current) {
        (windowHeight - actionsTop - handleHeight).coerceAtLeast(0).toDp()
    }.takeIf { actionsTop > 0 } ?: DefaultSheetHeight

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = if (fullscreen) 0.dp else peek,
        // M3 给标准 sheet 的容器色是 surface container low,而这一页的队列卡片用的是
        // surface container —— 两者叠在一起几乎没有色差,边界就消失了。抬高一档并加重
        // 投影:标准 sheet 没有遮罩,分隔完全靠容器色与阴影承担。
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        sheetShadowElevation = 12.dp,
        sheetDragHandle = {
            Box(modifier = Modifier.onGloballyPositioned { handleHeight = it.size.height }) {
                BottomSheetDefaults.DragHandle()
            }
        },
        sheetContent = {
            RelatedSheet(
                related = related,
                height = sheetHeight,
                onVideoClick = onRelatedVideoClick,
                onRetry = onFindRelated,
            )
        },
    ) { insets ->
        /**
         * **宽屏是 supporting pane:画面在左,简介与评论在右。**
         *
         * 规范给的正是这个例子(breakpoints 页 Expanded 一节配图):"The large, primary pane
         * has the video, title, and actions. The secondary pane has queued videos.",主区约占
         * 三分之二。同一页也允许视频类用单栏("a single-pane layout can work when displaying
         * visually- or information-dense content, such as videos"),这里选双栏 —— 单栏在平板上
         * 只剩画面加两条黑边,右边那块空间什么也没干。
         *
         * **横竖两种排布下,播放器都是同一个调用点。** 分成两个 `BilbyPlayer(...)` 会让切换
         * 时 PlayerSurface 销毁重建、弹幕整池重编。全屏时也走同一个分支(右栏不组合、左栏
         * 权重给满),所以最要紧的那次切换 —— 进出全屏 —— 不会重挂。
         */
        /*
         * **系统栏:画面全出血,文字躲开。**
         *
         * 两栏下画面顶到屏幕上边缘,状态栏被收起来(它会横跨黑画面和浅色的简介栏,而图标明暗
         * 只能整条设一次)。根容器一垫 inset,露出来的就是页面底色 —— 画面上方一道白带,
         * 既没有沉浸又损失了高度。
         *
         * 单栏(竖屏)只填一条黑边,画面本身不钻到状态栏底下:返回和分享贴在画面上角,状态栏
         * 会正好压住它们。图标仍然转白(见 `fullBleed`),因为它压着的是那条黑边。
         *
         * 文字那一栏自己躲。`windowInsetsPadding` 同时**消费**掉这份 inset,所以嵌在画面里的
         * 控制条和返回按钮无条件调用同一个躲避也不会重复叠加。
         */
        val safeInsets = WindowInsets.barsAndCutout
        val rootModifier = modifier
            .fillMaxSize()
            // 全屏不留任何 inset:系统栏是 FullscreenEffect 异步藏掉的,这中间有一两帧
            // statusBars 还报着高度,带着它会让画面先被顶下去再弹回来。
            .then(
                when {
                    fullscreen -> Modifier
                    // 两栏:根一律不躲,交给下面两个 pane 各自处理 —— 连底部也不躲,
                    // 躲了就在黑画面下面又垫出一条页面底色,和上面那条白带是同一个毛病。
                    expandedLayout -> Modifier
                    // 单栏:顶上归画面,底下的手势条要躲(简介和评论滚到底会压在上面)。
                    else -> Modifier.padding(bottom = insets.calculateBottomPadding())
                },
            )

        // 画面这一块。**整页只有这一处 `BilbyPlayer` 调用**:横排和竖排各写一份的话,转屏
        // 会让 PlayerSurface 销毁重建、弹幕整池重编。这里用一个接 Modifier 的 lambda,
        // 两种排布传不同的约束进去。
        val playerPane: @Composable (Modifier) -> Unit = { paneModifier ->
            Box(modifier = paneModifier.background(Color.Black)) {
                // **判据是"服务装上东西了没有",不是"有没有 playInfo"。**
                //
                // playInfo 是取流的产物,而放本地缓存那条路径压根不取流 —— 服务在命中缓存时
                // 故意把它置成 null(本地只有下载时选的那一档,画质菜单摆出来点了没用)。
                // 照 playInfo 判的话,缓存的视频永远落到下面那个转圈分支:播放器其实早就
                // READY 了(真机上量到 237ms),只是没有人把画面挂上去。
                val loaded = matchesCurrentPage || audioState.playInfo != null
                when {
                    loaded && active != null -> BilbyPlayer(
                        player = active,
                        surfacePlayer = surfacePlayer,
                        qualities = audioState.playInfo?.availableQualities.orEmpty(),
                        currentQuality = audioState.currentQuality,
                        onQualityChange = { setQuality(it) },
                        fastForwardSpeed = fastForwardSpeed,
                        isFullscreen = fullscreen,
                        onFullscreenChange = { fullscreen = it },
                        // 非全屏时画面一直顶到屏幕上边缘,状态栏压在它身上。
                        fullBleed = true,
                        // 但只有两栏要把状态栏收起来:那时它横跨黑画面和浅色的简介栏,
                        // 而图标明暗只能整条设一次(见 PlayerShell)。
                        hideStatusBar = expandedLayout,
                        onReportProgress = onReportProgress,
                        title = state.detail?.title.orEmpty(),
                seekBarSegments = sponsorSegments.toSeekBarSegments(),
                        subtitleTracks = subtitleTracks,
                        currentSubtitleLan = subtitleLan,
                        onSubtitleTrackChange = onSelectSubtitle,
                        subtitleCues = subtitleCues,
                        danmakuPrefs = danmakuPrefs,
                        onDanmakuEnabledChange = onDanmakuEnabledChange,
                                locked = locked,
                        onLockedChange = { locked = it },
                        danmakuPool = danmakuPool,
                        specialDanmakuPool = specialDanmakuPool,
                        danmakuCid = (audioState.queue?.currentCid ?: 0L),
                        matchesCurrentPage = matchesCurrentPage,
                        placeholderCoverUrl = state.detail?.coverUrl.orEmpty(),
                        modifier = Modifier.fillMaxSize(),
                    )

                    state.error != null -> PlaybackFailure(
                        message = state.error,
                        retrying = state.loading,
                        onRetry = onRetry,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                if (!fullscreen) {
                    MediaBackButton(
                        onBack = onBack,
                        onShare = {
                            ShareLink.video(context, bvid, state.detail?.title.orEmpty())
                        },
                    )
                }

                // 盖在画面上而不是排在下面:失败时画面本来就是黑的,而简介区在一屏之外,
                // 提示放那儿等于没有。state.error 那一支是"流都没取到",两者不会同时出现。
                //
                // **取流期间必须有东西在转。** BilbyPlayer 自己只有"画面"和"封面占位"两态,
                // 都是静止的;而失败提示被压后了 5 秒(见 playbackError),不在这里补一个
                // 指示器的话,那 5 秒里屏幕上是一张不动的封面,和卡死分不出来。
                val shownError = playbackError
                when {
                    shownError != null -> PlaybackFailure(
                        message = shownError,
                        // 两个 loading 都要看:重取那一步归本页(state.loading),重新装载
                        // 那一步归服务(audioState.loading)。只看后者的话,重取在飞的那一两秒
                        // 按钮会重新亮起来,能连按出好几次重取。
                        retrying = state.loading || audioState.loading,
                        onRetry = retryPlayback,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    audioState.loading || state.loading ->
                        PlaybackLoading(Modifier.align(Alignment.Center))
                }

                SkipToast(skippedCategory, Modifier.align(Alignment.TopCenter).padding(Spacing.Tight))
            }
        }

        // 简介与评论。竖排时在画面下面、拿剩下的高度;横排时是右边那个 secondary pane。
        val tabsPane: @Composable (Modifier) -> Unit = { paneModifier ->
            state.detail?.let { detail ->
                AdaptiveContent(modifier = paneModifier, maxWidth = Breakpoints.ReadableWidth) {
                    VideoTabs(
                        detail = detail,
                        currentCid = (audioState.queue?.currentCid ?: 0L),
                        related = related,
                        commentState = commentState,
                    // 点闪光:没问过就发起检索,问过就只是把 sheet 展开 —— 再点一次重跑
                    // 会把已有结果冲掉,而用户此刻多半只是想再看一眼。
                        onActionsTop = { top -> if (actionsTop == 0 && top > 0) actionsTop = top },
                        onFindRelated = {
                            if (!related.started) onFindRelated()
                            scope.launch { sheetState.bottomSheetState.expand() }
                        },
                        onListen = { onListeningChange(true) },
                        onCache = { cacheSheetOpen = true },
                        // 全屏下这一栏根本不组合,所以不必先退出全屏 —— 能点到这个按钮
                        // 就说明已经不在全屏了。
                        followState = followState,
                        onToggleFollow = onToggleFollow,
                        upCard = upCard,
                        queue = shownQueue,
                        onPlayQueueItem = onPlayQueueItem,
                        onToggleShuffle = toggleShuffle,
                        onRetryQueue = retryQueue,
                        onUpClick = onUpClick,
                        staffFollowed = staffFollowed,
                        onFollowStaff = onFollowStaff,
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
                    // 只在播放器装的确实是本页这一条时才跳。队列自动连播走到下一条之后,
                    // 这一页还停在原来那条的评论区(见页面顶部对 matchesCurrentPage 的说明),
                    // 不对身份就会拿着 A 的评论里的时间戳去跳 B。
                    //
                    // 再夹一次时长:评论里的时间戳可能指向分 P 或者干脆写错,超出末尾的 seek
                    // 会直接把这一条播完并翻到下一条。
                        onSeekComment = { millis ->
                            val controller = active
                            if (controller != null && matchesCurrentPage) {
                                val end = controller.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                                controller.seekTo(millis.coerceIn(0L, end))
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        if (expandedLayout) {
            // 主区约三分之二,和规范给的比例一致。**全屏也走这个分支**,只是右栏不组合、
            // 左栏权重给满 —— 这样进出全屏时布局树的形状不变,播放器不会重挂。
            Row(modifier = rootModifier) {
                // **左栏整列都是播放器**,画面按比例居中在里面,四周是它自己的黑底。
                //
                // 原先这里给的是 `fillMaxWidth().aspectRatio(16:9)`,于是画面只有左栏宽度的
                // 九分之十六那么高,在更高的列里垂直居中——上下各露出一条页面底色。那条白带
                // 才是"横屏不沉浸"的真身,和根容器的 inset 无关。
                playerPane(Modifier.weight(if (fullscreen) 1f else 2f).fillMaxHeight())
                // 文字这一栏躲开系统栏与刘海,**但不躲 start 那一侧**:它的左边挨着的是播放器,
                // 不是屏幕边缘,垫了就在画面和简介之间劈出一道缝。
                if (!fullscreen) {
                    tabsPane(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .windowInsetsPadding(
                                safeInsets.only(WindowInsetsSides.End + WindowInsetsSides.Vertical),
                            ),
                    )
                }
            }
        } else {
            Column(modifier = rootModifier) {
                // 状态栏那一条**填黑,但画面不钻进去**。
                //
                // 让画面整块顶到屏幕上边缘试过了:返回和分享按钮贴在画面左右上角,状态栏正好
                // 压在它们身上,而那两个是这一页仅有的页级动作。现在画面从状态栏下沿开始,
                // 上面那条黑边和画面的黑底连成一块 —— 拿到的是"没有一条突兀的浅色带",
                // 而不是"多出一块可用面积";后者本来也没多少,一条状态栏而已。
                if (!fullscreen) {
                    Spacer(
                        modifier = Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(safeInsets)
                            .background(Color.Black),
                    )
                }
                playerPane(
                    if (fullscreen) {
                        Modifier.fillMaxSize()
                    } else {
                        // **消费掉系统栏那份 inset。** 上面那条黑边只是"取了 inset 的高度"
                        // (`windowInsetsTopHeight` 不消费),不声明的话画面里的返回、分享和
                        // 控制条会以为自己还贴着屏幕边缘,各自再躲一次 —— 表现是箭头往画面
                        // 里缩了一条状态栏的高度。它上有黑边、下有简介栏,四周都不是屏幕边缘。
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .consumeWindowInsets(safeInsets)
                    },
                )
                // 全屏时画面独占整屏,简介整块不参与布局。
                //
                // **weight(1f) 而不是 fillMaxWidth()。** AdaptiveContent 内部会 fillMaxSize,
                // 不给权重的话它要的是整屏高度,加上上面 16:9 的画面就超出一屏 —— 表现是
                // 播放页能往下微微滑动一点。给了权重,它拿到的就是"画面之外剩下的高度",
                // 有没有分 P 都正好占满。
                //
                // 横向挖孔在这里躲:单栏也可能是横屏(600–840dp 的中等宽度),那时挖孔在侧边,
                // 画面照旧铺过去,但下面的标题和评论不能被切。竖屏时它量到 0。
                if (!fullscreen) {
                    tabsPane(Modifier.weight(1f).windowInsetsPadding(WindowInsets.horizontalCutout))
                }
            }
        }
    }

    // Android 13 起通知要运行时授权,而 manifest 里那句 uses-permission 只是声明。
    // 没人要过的话下载通知一条都发不出去 —— 这正是真机上"下载时没有通知"的第一层原因。
    val notificationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { /* 给不给都不影响下载,见下面调用处 */ }
    val requestNotificationPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // 缓存面板。挂在最外层而不是简介页里面:它是覆盖整页的 modal,而简介页是可滚动的内容,
    // 放进去会跟着滚。全屏时不开 —— 全屏下这个按钮本来就够不着。
    if (cacheSheetOpen && !fullscreen) {
        OfflineCacheSheet(
            items = shownQueue.items,
            cachedBvids = cachedBvids,
            // 档位清单来自当前这条视频的 accept_quality。队列里别的视频未必有同样的档,
            // 取流那边会自动降级(见 selectStreams),所以这里不必逐条去问。
            qualities = audioState.playInfo?.availableQualities.orEmpty(),
            defaultQuality = audioState.currentQuality,
            initialSelection = shownQueue.currentBvid,
            onConfirm = { selected, quality, withDanmaku ->
                cacheSheetOpen = false
                // 通知权限在**这里**要,不在开屏要:它唯一的用处是显示下载进度,而人此刻正好
                // 按下了"开始缓存"——弹窗解释得通。开屏问的话既没有语境,拒绝之后也再没有
                // 第二次自然的时机。
                //
                // **不等结果、也不因为被拒就不下载**:通知只是进度条,少了它下载照样跑完,
                // 缓存列表里也看得到进度。把功能压在一个可以被拒的权限上是本末倒置。
                requestNotificationPermission()
                onCacheSelection(selected, quality, withDanmaku)
            },
            onDismiss = { cacheSheetOpen = false },
        )
    }
}

/**
 * 取流/重试期间画面上那个转圈。
 *
 * 外观和 [PlaybackFailure] 里的重试指示是同一套媒体控件(24dp):它们出现在同一个位置,是同
 * 一件事的两个阶段,长得不一样会读成两个不同的东西。
 *
 * 底下垫一层半透明圆:这时候画面上多半是封面图,亮色封面上一个纯白的圈基本看不见。
 */
@Composable
private fun PlaybackLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(FixedColors.PlayerControlScrim),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            color = FixedColors.OnMedia,
            strokeWidth = 2.dp,
            modifier = Modifier.size(24.dp),
        )
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
            color = FixedColors.OnMedia,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        // 重试中不给按钮:此刻按下去只会打断已经在跑的那次。
        if (retrying) {
            CircularProgressIndicator(
                color = FixedColors.OnMedia,
                modifier = Modifier.padding(top = Spacing.Cozy).size(24.dp),
            )
        } else {
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.action_retry), color = FixedColors.OnMedia)
            }
        }
    }
}

/** 取流失败的宽限期。服务自己的重试最坏花 3 秒，留一点余地。 */
private const val PlaybackErrorGraceMillis = 5_000L
