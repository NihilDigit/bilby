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
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
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
import dev.bilby.data.QueueSource
import dev.bilby.data.SettingsStore
import dev.bilby.data.SponsorSegment
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import dev.bilby.data.VideoRelation
import dev.bilby.player.AudioPlaybackService
import dev.bilby.player.QueueItem
import dev.bilby.player.SleepTimerMode
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.media3.common.Player
import dev.bilby.offline.CachedIndex
import dev.bilby.offline.offlineId
import dev.bilby.ui.components.collapsingHeader
import dev.bilby.ui.components.rememberCollapsingHeaderState

/**
 * 播放页。**没有相关推荐栏、没有自动连播**(DESIGN 2.3/1.3);「找相关」占的是官方相关
 * 推荐的位置,但要用户点了才跑,见 VideoTabs。
 *
 * **这里不建播放器**(DESIGN 2.4b:一个播放状态,两个 UI)。播放器归 [AudioPlaybackService]
 * 所有,页面连上去,把要播的流交给它,再通过 MediaController 控制;切到听视频只是换掉这层 UI。
 */
@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    /** 盘上已有的东西。缓存面板拿它把已有的那几条标出来并禁选。 */
    cached: CachedIndex = CachedIndex(),
    /** 缓存面板按下确认。清晰度与"要不要弹幕"都在面板里选,这里只负责把结果交出去。 */
    onCacheSelection: (List<QueueItem>, qualityId: Int) -> Unit = { _, _ -> },
    followState: FollowState,
    onToggleFollow: () -> Unit,
    /** UP 主等级,独立请求、独立失败——查不到就是 null,徽章不画(见 VideoViewModel.upCard)。 */
    upCard: MemberCard?,
    onUpClick: (mid: Long) -> Unit,
    /** 打开这份队列来自的那个合集/系列的目录。来源没有目录页时那一行点不动,不会走到这里。 */
    onOpenQueueSource: (QueueSource) -> Unit,
    /** 已关注的联合投稿成员;null = 还没查到。 */
    staffFollowed: Set<Long>?,
    onFollowStaff: (Long) -> Unit,
    relation: VideoRelation?,
    favFolders: List<FavFolder>,
    addedToView: Boolean,
    onLike: () -> Unit,
    onAddToView: () -> Unit,
    onCoin: (count: Int, alsoLike: Boolean) -> Unit,
    coinAttempt: CoinAttempt,
    onCoinDialogClosed: () -> Unit,
    onOpenFavPicker: () -> Unit,
    onFavConfirm: (addIds: List<Long>, delIds: List<Long>) -> Unit,
    onPlayEpisode: (bvid: String) -> Unit,
    onRelatedVideoClick: (bvid: String) -> Unit,
    onCommentSort: (CommentSort) -> Unit,
    onCommentRefresh: () -> Unit,
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
    /** 自己刚发出去的那条,立刻上屏。**不进弹幕池**,理由见 VideoViewModel.selfDanmaku。 */
    selfDanmaku: Flow<Danmaku> = emptyFlow(),
    /** 发弹幕这一次的进展。面板靠它显示转圈与失败原因,[DanmakuSend.Sent] 即关面板。 */
    danmakuSend: DanmakuSend = DanmakuSend.Idle,
    /** cid 与进度由这一层给:播放器装着哪一 P、放到了哪儿,权威在服务那侧。 */
    onSendDanmaku: (text: String, cid: Long, progressMillis: Long) -> Unit = { _, _, _ -> },
    onDanmakuSendConsumed: () -> Unit = {},
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

    /**
     * 已经忽略过的云端位置。和 [locked] 同样的理由提到这一层:[CloudResumeHint] 也有全屏和
     * 非全屏两个调用点,状态留在组件内部的话,转一次屏就换成另一个实例,忽略过的提示会重新弹。
     *
     * 存的是位置本身而不是一个布尔,因为要挡的是"同一个云端时间不再弹"。换一集之后云端位置是
     * 另一个值,那一条该弹还是要弹。
     */
    var dismissedResumeMillis by rememberSaveable { mutableStateOf<Long?>(null) }

    // 判据用路由参数 [bvid],与 `matchesCurrentPage` 同源。用 `state.detail?.bvid` 的那一版
    // 在详情请求失败时是 null:那时页面早已凭 bvid 发过打开命令、播放器正放着本页这一条,
    // 而 null 谁也对不上,离开页面音频就继续响。
    val playerHoldsThisPage = {
        AudioPlaybackService.state.value.queue?.current?.bvid == bvid
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
                //
                // **发 [ACTION_PAGE_LEFT] 而不是 pause()。** 后者和用户按下暂停键是同一条路,
                // 服务分不出"他不想听了"和"他去看 UP 主页了" —— 于是回到这一页只能一律停着。
                // 谁按的这个信息只有发命令的这一方有,所以由这一方说清楚。
                if (playerHoldsThisPage()) {
                    connected.sendCustomCommand(
                        SessionCommand(AudioPlaybackService.ACTION_PAGE_LEFT, Bundle.EMPTY),
                        Bundle.EMPTY,
                    )
                }
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

    // 忽略过的那个位置直接不往下传,两个调用点各自的 visible 就不会再被点亮。判据见上面
    // [dismissedResumeMillis] 的注释。
    val cloudResumeMillis = audioState.cloudResumeMillis?.takeIf { it != dismissedResumeMillis }

    // 播放器此刻装的是不是这一页的视频。播放器全 app 共用一份、跨页面存活,点开一条新视频
    // 到播放器真正切过去之间有一段取流 + prepare 的窗口,这段时间画面渲染的还是上一条视频的
    // 最后几帧——传给 BilbyPlayer,由它决定挂画面还是画占位(不在这里暂停或销毁播放器,那会
    // 打断后台连续播放)。
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

    /** 发弹幕的输入层开着没有。和缓存面板一样,只是这一页的一个浮层。 */
    var danmakuInputOpen by rememberSaveable { mutableStateOf(false) }

    /**
     * 输入层里的草稿。**按 bvid 存**:连播走到下一条时这一页并不重建(见 VideoViewModel 的
     * switchTo),不带 key 的话上一条没发出去的半句话会跟到下一条视频上。
     */
    var danmakuDraft by rememberSaveable(bvid) { mutableStateOf("") }

    /**
     * 这条弹幕属于哪一刻。**在打开输入层那一刻取值,之后不再动** —— 弹幕是发给人按下"发弹幕"
     * 时看到的那一帧的。取完就暂停,所以进度也不会再走。
     */
    var danmakuProgress by remember { mutableLongStateOf(0L) }

    /** 队列的唯一来源是服务。页面只是把它摆出来,不自己攒一份。 */
    val shownQueue = QueueUiState(
        items = audioState.queue?.items.orEmpty(),
        currentBvid = audioState.queue?.current?.bvid,
        sourceLabel = audioState.queue?.sourceLabel.orEmpty(),
        source = audioState.queue?.source,
        shuffled = (audioState.queue?.shuffled == true),
        // 判据从"取流转圈且队列是空的"换成"完整队列建好了没有"。起播现在不等建队列,队列里
        // 那一条是临时占位而不是队列内容(见 AudioPlaybackService.openVideo),按旧判据永远
        // 是"有队列",面板上会摆出一条孤零零的视频。
        enriching = (audioState.queue?.enriching == true),
        incomplete = (audioState.queue?.incomplete == true),
    )

    /** 发一条自定义命令的简写。 */
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

    /**
     * 打开/关闭发弹幕的输入层。**暂停与恢复复用服务已有的那一对命令,不新造一套。**
     *
     * 打开发 [AudioPlaybackService.ACTION_PAGE_LEFT]:它的语义正是"暂停,但不动 playIntent"
     * ——也就是"这次停下不是用户的意思"。关闭再发一遍 [AudioPlaybackService.ACTION_OPEN_VIDEO],
     * 落在服务的幂等分支上,那里那句 `if (playIntent && !playWhenReady)` 就是恢复。
     *
     * 于是**打开面板期间用户从通知栏按了暂停**这种情况自动是对的:那一下走 `QueuePlayer.pause()`
     * 把 playIntent 清了,关面板时不会自作主张再放起来。页面自己记一个"打开前在不在播"的
     * 快照做不到这一点,它只知道打开那一刻的事。
     *
     * 两边都先问[playerHoldsThisPage]:队列自动连播走到下一条时,这一页可能还没被换掉,
     * 而那时发 OPEN_VIDEO 等于把播放器拽回这条视频——用户并没有要求。
     *
     * **不带 cid。** 带的话服务会拿它和正在播的那一 P 比,而这里给得出的只有默认 P1,
     * 多 P 视频在第 3 P 上发条弹幕就会被送回第 1 P(同 retryQueue 那条)。
     */
    val openDanmakuInput: () -> Unit = {
        danmakuProgress = active?.currentPosition?.coerceAtLeast(0L) ?: 0L
        if (playerHoldsThisPage()) send(AudioPlaybackService.ACTION_PAGE_LEFT, Bundle.EMPTY)
        danmakuInputOpen = true
    }

    /**
     * 输入层的唯一出口。四条路径(发送成功、点空白、系统返回、按了发送键)都走这里,
     * 分开写必然漏掉一条 —— 漏掉的那条表现为"退出输入之后视频不再自己接着放"。
     */
    val closeDanmakuInput: () -> Unit = {
        danmakuInputOpen = false
        onDanmakuSendConsumed()
        if (playerHoldsThisPage()) {
            send(
                AudioPlaybackService.ACTION_OPEN_VIDEO,
                bundleOf(AudioPlaybackService.EXTRA_BVID to bvid),
            )
        }
    }

    // 发出去了就关面板并清草稿。**清草稿只在这里**:失败时留着,人改一个字就能再发一次。
    LaunchedEffect(danmakuSend) {
        if (danmakuSend is DanmakuSend.Sent) {
            danmakuDraft = ""
            closeDanmakuInput()
        }
    }

    // 随机、上下一条、跳到队列里的某一条,现在都是标准 Player 命令:队列住在播放器的 playlist
    // 里,它自己就能答"有没有下一条",而且答完会发 onXxxChanged —— controller 那份命令缓存
    // 于是跟得上,不必再为此单开一组自定义命令。
    val toggleShuffle: () -> Unit = {
        active?.shuffleModeEnabled = !(audioState.queue?.shuffled == true)
    }

    /** 点队列里的一条 = 让队列跳过去。页面不自己导航,它跟着队列走(见 VideoRoute)。 */
    val onPlayQueueItem: (String) -> Unit = { target ->
        // 面板里的列表就是 playlist 的自然顺序(随机只改播放顺序),下标可以直接用。
        val index = shownQueue.items.indexOfFirst { it.bvid == target }
        if (index >= 0) active?.seekTo(index, 0)
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
            onNext = { active?.seekToNextMediaItem() },
            onPrevious = { active?.seekToPreviousMediaItem() },
            onToggleShuffle = toggleShuffle,
            onRetry = retryPlayback,
            onSleepTimer = { mode ->
                // 三种模式压进一个 Int:分钟数,或服务那边定义的两个哨兵。字面量不在这里重复写。
                val minutes = when (mode) {
                    SleepTimerMode.Off -> AudioPlaybackService.SLEEP_TIMER_OFF
                    SleepTimerMode.EndOfItem -> AudioPlaybackService.SLEEP_END_OF_ITEM
                    is SleepTimerMode.After -> mode.minutes
                }
                active.sendCustomCommand(
                    SessionCommand(AudioPlaybackService.ACTION_SLEEP_TIMER, Bundle.EMPTY),
                    Bundle().apply { putInt(AudioPlaybackService.EXTRA_SLEEP_MINUTES, minutes) },
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

    /*
     * **暂停之后画面跟着页面滚走,播放中钉在页顶。**
     *
     * 播放中必须钉着:一边看画面一边翻评论正是这一页的用法。暂停之后这个理由不成立 ——
     * 此刻屏幕上三分之一是一张不动的画,而人正在读下面的字。
     *
     * **两个 tab 一视同仁。** 曾经只在评论那一边收、简介那边钉死,因为简介里的播放队列按
     * 窗口坐标算高度,画面一收它就重新量高重组。队列改成由布局撑开之后那条限制没了,而
     * "同一个手势在两个 tab 上做两件事"本身就是别扭的来源。
     *
     * 两栏布局不做:画面是左边那一整列,和右栏的滚动没有共同的方向可言。
     */
    var playing by remember { mutableStateOf(false) }
    DisposableEffect(active) {
        val player = active ?: return@DisposableEffect onDispose { }
        playing = player.isPlaying
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val tabPager = rememberPagerState(pageCount = { VideoTabCount })
    val playerPinned = playing || fullscreen || expandedLayout
    /*
     * **简介那一边只许把画面拉回来,不许把它收走。**
     *
     * 收起是在评论那边做的动作:人在读评论,画面是多余的。简介这一边的下半屏是播放队列,
     * 它自己就要占满剩下的高度,再把画面收掉换来的空间没有去处 —— 换来的是队列忽然长一截,
     * 而人并没有要求它长。
     *
     * 反过来要留着:从评论页收着画面翻回简介,得有办法把画面拿回来,否则这一页会一直停在
     * "画面不见了"的状态。展开走 onPostScroll,不受这个开关管(见 CollapsingHeaderState),
     * 所以这里只写"能不能收"就够,单向限制天然成立。
     *
     * 用 settledPage 而不是 currentPage:后者翻过中点就变,允许的方向会在手势中途翻转。
     */
    val canCollapsePlayer = !playerPinned && tabPager.settledPage != VideoTabIntro
    val playerScroll = rememberCollapsingHeaderState { canCollapsePlayer }
    // 又钉起来就把画面收回来。**不是 snap 而是动画**:此刻手指多半不在屏幕上(点的是通知栏
    // 或者画面上的播放键),瞬移读不出"它回来了"这件事。翻回简介页是个例外,那时手指在滑,
    // 但那一下本来就跟着页面走,动画反而顺。
    LaunchedEffect(playerPinned) {
        if (playerPinned) playerScroll.expand()
    }

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
                        // 返回/分享画在壳外面(下面那个 if),渐变归壳画,才落得到弹幕下面。
                        topScrim = !fullscreen,
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
                        selfDanmaku = selfDanmaku,
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

                    // 和壳内缓冲用同一个指示器:这里盖的是"服务还没装上这一条"的窗口,
                    // 壳内那个盖的是装上之后的取流与缓冲,两段接起来观感是同一次等待。
                    // CircularProgressIndicator 不用:alpha 版画一圈背景轨道,黑底上是两层圆环。
                    else -> LoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                if (!fullscreen) {
                    MediaBackButton(
                        onBack = onBack,
                        onShare = {
                            ShareLink.video(context, bvid, state.detail?.title.orEmpty())
                        },
                        scrim = false,
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

                // 两条提示摞成一列而不是各自对齐:同时出现的机会很小(一条在起播那一刻,一条在
                // 跳过赞助段的时候),但真撞上时叠在一起谁都读不出来。
                //
                // 跳过提示贴画面顶部:它说的是"刚跳过了一段",归画面。
                //
                // **「别处已看到」只有全屏时才在这一层。** 它是要按的,而这个 Box 只有画面那么
                // 大 —— 竖排时它的"底部"就是进度条那一带,提示会浮在进度条上,两个可按的东西
                // 叠在一起。竖排那一份挂在整页那一层(见下面 rootBox 里那处),锚在屏幕的下
                // 四分之一,落在简介/评论上方,拇指够得着又不压任何控件。
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
                    modifier = Modifier.align(Alignment.TopCenter).padding(Spacing.Comfortable),
                ) {
                    if (fullscreen) {
                        CloudResumeHint(
                            positionMillis = cloudResumeMillis,
                            // 直接 seek:这一下是用户点出来的,不是播放头自己动。
                            onJump = { active?.seekTo(it) },
                            onDismiss = { dismissedResumeMillis = it },
                        )
                    }
                    SkipToast(skippedCategory)
                }
            }
        }

        // 简介与评论。竖排时在画面下面、拿剩下的高度;横排时是右边那个 secondary pane。
        val tabsPane: @Composable (Modifier) -> Unit = { paneModifier ->
            state.detail?.let { detail ->
                AdaptiveContent(modifier = paneModifier, maxWidth = Breakpoints.ReadableWidth) {
                    VideoTabs(
                        pagerState = tabPager,
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
                        onSendDanmaku = openDanmakuInput,
                        danmakuEnabled = danmakuPrefs.enabled,
                        onDanmakuEnabledChange = onDanmakuEnabledChange,
                        onCache = { cacheSheetOpen = true },
                        // 全屏下这一栏根本不组合,所以不必先退出全屏 —— 能点到这个按钮
                        // 就说明已经不在全屏了。
                        followState = followState,
                        onToggleFollow = onToggleFollow,
                        upCard = upCard,
                        queue = shownQueue,
                        onPlayQueueItem = onPlayQueueItem,
                        onOpenQueueSource = onOpenQueueSource,
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
                        coinAttempt = coinAttempt,
                        onCoinDialogClosed = onCoinDialogClosed,
                        onOpenFavPicker = onOpenFavPicker,
                        onFavConfirm = onFavConfirm,
                        onPlayPart = onPlayPart,
                        onPlayEpisode = onPlayEpisode,
                        onRelatedVideoClick = onRelatedVideoClick,
                        onCommentSort = onCommentSort,
                        onCommentRefresh = onCommentRefresh,
                        // 画面收起着的时候下滑先把它拉回来,那一下不该被读成刷新。
                        playerExpanded = playerScroll.offsetPx == 0f,
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
                        onCommentUserClick = onUpClick,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        // 整页一层。**竖排时那条「别处已看到」挂在这里**,而不是挂在画面那个 Box 上:
        // 它要按,而画面那个 Box 的下四分之一正是进度条。挂在整页这一层,它落在简介/评论
        // 上方,离手近又不压任何控件;而且它是这一层的**兄弟节点而不是列表的孩子**,进出
        // 不会让下面那一整栏重新布局。
        // **发弹幕的输入层是整页的兄弟节点,不在 rootModifier 那个 Box 里面。** 那个 Box 单栏时
        // 自己垫了一条手势条的高度(而且是普通 padding,不是消费掉的 inset),输入层套在里面的话
        // 键盘弹起来时会在两者之间空出正好那么一条。
        Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = rootModifier) {
        if (expandedLayout) {
            // 主区约三分之二,和规范给的比例一致。**全屏也走这个分支**,只是右栏不组合、
            // 左栏权重给满 —— 这样进出全屏时布局树的形状不变,播放器不会重挂。
            Row(modifier = Modifier.fillMaxSize()) {
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
            // 连接**始终挂着**,钉不钉由 playerScroll 自己判(建它时传进去的那个 lambda)。
            // 曾经按 playerPinned 增删这个修饰符,而它会在左右翻页翻到一半时翻转 —— 修饰符链
            // 一变,正在拖的那一下就被取消,表现是滑动卡在两页中间。
            Column(modifier = Modifier.fillMaxSize().nestedScroll(playerScroll.connection)) {
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
                        // **两个修饰符都在 aspectRatio 外面。** aspectRatio 会把自己量出来的
                        // 16:9 高度直接上报,套在它里面的话收起量根本传不到上一层;
                        // clipToBounds 再包一层,把挪出可视区的那一截裁掉 —— 不裁的话画面会
                        // 盖在上面那条状态栏黑边上。
                        Modifier
                            .clipToBounds()
                            .collapsingHeader(playerScroll)
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

        if (!fullscreen) {
            CloudResumeHint(
                positionMillis = cloudResumeMillis,
                onJump = { active?.seekTo(it) },
                onDismiss = { dismissedResumeMillis = it },
                modifier = Modifier.align(ToastAnchorInDetail).padding(Spacing.Comfortable),
            )
        }
        }

        // 全屏下不给:那时这一行按钮根本不组合,而横屏起键盘会铺掉大半个画面——发弹幕要看着
        // 画面发,盖掉画面就没有可发的对象了。想发先退出全屏。
        if (danmakuInputOpen && !fullscreen) {
            DanmakuInputLayer(
                text = danmakuDraft,
                onTextChange = { danmakuDraft = it },
                state = danmakuSend,
                onSend = {
                    onSendDanmaku(
                        danmakuDraft,
                        audioState.queue?.currentCid ?: 0L,
                        danmakuProgress,
                    )
                },
                onDismiss = closeDanmakuInput,
            )
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
        // 当前这条视频摊成分 P,别的队列项各占一行。**分 P 清单只有当前这条有** —— 它来自
        // 已经在手上的这份详情,而队列里其他视频要各打一次详情请求才知道有几 P,不值得在
        // 打开面板时先发十几个请求去换一个多数视频用不上的选项。
        val currentParts = state.detail?.takeIf { it.bvid == shownQueue.currentBvid }?.pages.orEmpty()
        val targets = shownQueue.items.flatMap { item ->
            if (item.bvid != shownQueue.currentBvid || currentParts.size <= 1) {
                listOf(OfflineTarget(item))
            } else {
                currentParts.map { part ->
                    OfflineTarget(
                        item = item.copy(cid = part.cid),
                        partTitle = stringResource(R.string.video_part_label, part.index, part.title),
                    )
                }
            }
        }
        OfflineCacheSheet(
            targets = targets,
            cached = cached,
            // 档位清单来自当前这条视频的 accept_quality。队列里别的视频未必有同样的档,
            // 取流那边会自动降级(见 selectStreams),所以这里不必逐条去问。
            qualities = audioState.playInfo?.availableQualities.orEmpty(),
            defaultQuality = audioState.currentQuality,
            // 默认勾中正在播的**这一 P**,不是这条视频的第一 P:多 P 视频里用户看到第 7 P 才
            // 想起来缓存,想要的显然是第 7 P。
            initialSelection = shownQueue.currentBvid?.let {
                offlineId(it, audioState.queue?.currentCid ?: 0L)
            },
            onConfirm = { selected, quality ->
                cacheSheetOpen = false
                // 通知权限在**这里**要,不在开屏要:它唯一的用处是显示下载进度,而人此刻正好
                // 按下了"开始缓存"——弹窗解释得通。开屏问的话既没有语境,拒绝之后也再没有
                // 第二次自然的时机。
                //
                // **不等结果、也不因为被拒就不下载**:通知只是进度条,少了它下载照样跑完,
                // 缓存列表里也看得到进度。把功能压在一个可以被拒的权限上是本末倒置。
                requestNotificationPermission()
                onCacheSelection(selected, quality)
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

/**
 * 竖排时「别处已看到」落在**整页**的下四分之一处 —— 简介/评论那一片,不是画面里。
 *
 * `BiasAlignment` 的纵向取值 −1 是顶、0 是中、1 是底,所以 0.5 落在中点与底边的正中间,
 * 也就是从下边缘往上约四分之一。**用比例而不是从底边往上垫固定 dp**:屏幕高度差得远,
 * 固定 dp 在小屏上会压到底部手势条。
 */
private val ToastAnchorInDetail = BiasAlignment(horizontalBias = 0f, verticalBias = 0.5f)
