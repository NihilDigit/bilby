package dev.bilby.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.bilby.BilbyApplication
import dev.bilby.BiliLog
import dev.bilby.BvidCodec
import dev.bilby.PerfTrace
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.HeartbeatReporter
import dev.bilby.data.LiveRepository
import dev.bilby.data.PlayInfo
import dev.bilby.data.QueueBuildResult
import dev.bilby.data.QueueSource
import dev.bilby.data.QueueSourceRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.SubtitleRepository
import dev.bilby.data.VideoRepository
import dev.bilby.data.resumeAtMillisFor
import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import dev.bilby.offline.OfflineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 通知栏、锁屏与播放页共用的展示信息。**哪种源都有**,所以它挂在状态顶层而不是队列上 ——
 * 队列曾经兼任元数据来源,那正是直播这类"不是队列"的源塞不进来的原因。
 */
data class NowPlaying(
    val title: String,
    /** 视频是 UP 名,直播是主播名。 */
    val subtitle: String,
    val coverUrl: String,
)

/**
 * 队列那一部分的状态。**打开过任何东西之后总是非空** —— 直播也是队列里的一条(设计文档
 * 「决定 5」),只是那份队列只有一条、上下一条都按不动。
 */
data class QueueState(
    /** 队列当前这一条。 */
    val current: QueueItem? = null,
    /** 队列内容,自然顺序(随机只改播放顺序,不改列表怎么摆)。 */
    val items: List<QueueItem> = emptyList(),
    /** 1-based,直接显示。队列空时为 0。 */
    val positionInQueue: Int = 0,
    val size: Int = 0,
    val shuffled: Boolean = false,
    /**
     * 上/下一条此刻按不按得动,来自播放器,认随机顺序。按钮可用态用这两个,不要拿
     * [positionInQueue] 推:那是列表位置,随机播放下列表第 1 条照样可以有上一条。
     */
    val canPrevious: Boolean = false,
    val canNext: Boolean = false,
    /** 队列的来源,如"直播回放""UP 主投稿"。见 [QueueBuildResult.sourceLabel]。 */
    val sourceLabel: String = "",
    /** 来源的身份,非空时 [sourceLabel] 那一行可以点进目录。见 [QueueBuildResult.source]。 */
    val source: QueueSource? = null,
    /**
     * 队列还在补全,现在这份队列只有正在播的这一条。**播放不等它**,所以这不是"正在加载"
     * ([AudioPlaybackUiState.loading] 说的是取流);它给队列面板用,免得那一格看起来像
     * "这个 UP 只有一条投稿"。
     */
    val enriching: Boolean = false,
    /**
     * 队列补全失败了,现在这份队列只有正在播的这一条。**播放本身是好的**,失败的只是"这条
     * 视频属于哪个集合"。摆出来是因为队列里只剩一条这件事本身看不出是"这个 UP 只有一条投稿"
     * 还是"来源没拉到",而后者重试一下往往就好了。重试点是再发一次 [ACTION_OPEN_VIDEO]。
     */
    val incomplete: Boolean = false,
)

/**
 * 直播已经下播了。**和"暂时取不到流"是两回事**:后者退避重试还有意义,这一种等多久都不会好,
 * 所以它一路抛到 [AudioPlaybackService.PlayerListener.onPlayerError] 只为了在那里停下来。
 */
internal class LiveEndedException(message: String) : IOException(message)

data class AudioPlaybackUiState(
    /** 正在放什么。没打开过任何东西时为 null。 */
    val nowPlaying: NowPlaying? = null,
    /**
     * **播放器此刻真正装着的东西的标识**,与 [QueueState.current] 不是一回事:队列在收到打开
     * 命令的那一刻就指向新视频了,而播放器要等取流回来才切过去,这中间画面上还是上一条的
     * 最后一帧。
     *
     * 播放页据此决定挂画面还是画占位。用队列那一条来判会把上一条视频的残帧当成本页的画面。
     * 值就是条目的 mediaId:视频是 bvid,直播是 [dev.bilby.player.liveMediaId]。
     */
    val loadKey: String? = null,
    /**
     * 正在播的分 P。**它是播放层的状态,不挂在队列上**:队列项的身份只有 bvid,分 P 是这条
     * 视频内部的结构。上报进度、取弹幕、取字幕认的都是这个值 —— 装载层确认过的那一个。
     */
    val currentCid: Long = 0,
    val isPlaying: Boolean = false,
    /** 正在取流或正在重试。这一步要走一次网络,不给反馈的话按下"下一条"后会有一两秒静默。 */
    val loading: Boolean = false,
    /**
     * 最近一次播放失败的原因,给用户看的一句话。**失败摆在界面上,不悄悄跳过下一条** ——
     * 跳过让人只看到"忽然换了一条",而真正的原因(直链过期、网络断了、解码器不可用)
     * 一个字都没留下。重试期间也非空,配合 [loading] 表示"正在重试"。
     */
    val error: String? = null,
    /** 画质菜单要用的清单,以及正在播的那一份流。取流归服务,页面只读。 */
    val playInfo: PlayInfo? = null,
    val currentQuality: Int = 0,
    /** 还没打开过任何东西时为 null。见 [QueueState]。 */
    val queue: QueueState? = null,
    /**
     * 别处看到的位置(毫秒),只在放本地副本、且服务端那份确实比本机新时非空。
     *
     * **是一条建议,不是一次跳转。** 播放已经从本地进度起播了,这个值只让界面摆一条可点的提示,
     * 用户点了才 seek。自动跳过去会让播放头在没有任何操作的情况下自己动 —— 那比停在一个稍旧的
     * 位置更难理解,而"稍旧"本身是有下限的:它就是本机上次看到的地方。
     */
    val cloudResumeMillis: Long? = null,
)

/**
 * 播放器与播放队列的唯一持有者(DESIGN 2.4b)。
 *
 * **一个播放器,一份队列,三个壳。** 内嵌播放、全屏、听视频都只是 UI 形态:它们读
 * [state]、发命令,不持有"现在在放什么",也不自己取流。听视频比另外两个多做的只有
 * 一件事——把视频轨关掉。于是"两个播放器同时发声""切模式要交接进度""页面和播放器
 * 指着两条不同的视频"这几类问题不是被解决,而是不存在。
 *
 * **方向是单向的:队列变,界面跟。** 界面永远不反过来推播放器。通知栏按下一条、耳机线控
 * 双击、听视频里点队列中的一条,走的都是同一条路——改队列,然后由界面跟到 [state] 上来。
 *
 * **打开界面是幂等的。** [ACTION_OPEN_VIDEO] 报的是 bvid 而不是流地址:队列当前就是这条、
 * 播放器也正装着它时,这条命令什么都不做。转屏、退出全屏、从听视频退回、通知栏切过一条
 * 之后再回到界面,全都落在这条分支上。
 *
 * **队列就是播放器的 playlist。** 服务不另存一份列表:两份列表意味着"队列现在是什么"有两个
 * 答案,而它们只在没人动过队列时相等。于是上/下一条、随机、跳到某一条都是标准 Player 命令,
 * 通知栏、车机和 app 内的按钮走同一条路,`onXxxChanged` 也照常发得出去。
 *
 * **播完即停**:队列走完就停在那里,不循环、不从任何地方续接下一条。
 * 允许连播的前提是集合有限且由用户显式选定,续接推荐池就等于恢复了被禁的自动连播。
 * 关掉自动连播则由 `pauseAtEndOfMediaItems` 表达,见 [applyStopAtEndOfItem]。
 *
 * **逐条取流**:播到某条时才调 [VideoRepository.getPlayUrl],由 [LazyMediaSource] 在播放器
 * 要放这一条的那一刻调起 [resolveStream]。playurl 给的是带时效的 CDN 直链,一次性把整个队列的
 * 地址取好,排在后面的那些等轮到时早就过期了,表现为播到某条突然 403 而前面几条都正常——这种
 * 失败很难归因。这里也不做预取,理由同上:预取越早,过期风险越大。
 */
@UnstableApi
class AudioPlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var videoRepository: VideoRepository

    /** 只为了问"上次播到哪一 P" —— 那个字段只有 `x/player/wbi/v2` 有,而它归这个仓库。 */
    private lateinit var subtitleRepository: SubtitleRepository

    /** 直播那条取流路径,见 [resolveLiveStream]。 */
    private lateinit var liveRepository: LiveRepository
    private lateinit var queueSourceRepository: QueueSourceRepository

    /** 只为了在取流之前问一句"这一条缓存过没有",见 [resolveStream]。 */
    private lateinit var offlineStore: OfflineStore
    private lateinit var settings: SettingsStore

    /** 缓存列表点某一行时留下的指名,见 [PartRequest]。 */
    private lateinit var partRequest: PartRequest

    /** 进度会话的上报出口,见 [ProgressSession]。 */
    private lateinit var heartbeatReporter: HeartbeatReporter

    /** 这次装载放哪一 P、从哪儿起播,一次解析。见 [LoadResolver]。 */
    private lateinit var loadResolver: LoadResolver

    private lateinit var sleepTimer: SleepTimer
    private var session: MediaSession? = null

    /**
     * 每装载一次 +1,写进条目的 [loadNonce]。见那里:重试和切清晰度靠它跟"只补个标题"区分开。
     */
    private var loadCounter = 0

    private var sourceLabel = ""
    private var queueSource: QueueSource? = null

    /**
     * 正在解析流的那一条(mediaId),解析完(成功或失败)置回 null。
     *
     * 取流归 [LazyMediaSource] 之后,服务这边不再有一个"装载 Job"可以问 isActive,而
     * [openVideo] 的幂等分支要知道"起播还在飞",理由见那里。
     */
    private var resolvingMediaId: String? = null

    /**
     * 队列补全。**和取流那条完全分开**:起播不等它,它失败也只是队列短一格,不影响正在播的
     * 这一条。取流那条现在归 [LazyMediaSource] 自己管,两者连生命周期都不在一处了。
     */
    private var enrichJob: Job? = null

    /** 每打开一条新视频 +1。迟到的补全结果靠它作废,见 [enrichQueue]。 */
    private var openGeneration = 0

    /** 补全在飞/补全失败,两者都表示当前停在只有一条的临时队列上。 */
    private var queueEnriching = false
    private var queueIncomplete = false

    /** 起播链路的测量。只在 [openVideo] 起头,只在 [finishOpenChain] 收尾。 */
    private var openChain: PerfTrace.Chain? = null

    /** 当前这一条已经连续失败了几次。见 [retryAfterFailure]。真的播出声(STATE_READY)时清零。 */
    private var failedAttempts = 0
    private var retryJob: Job? = null

    /**
     * 最近一次失败的原因,给界面看。重试期间保留 —— 退避的那几秒是静默的,不给解释就和
     * "卡住了"没有区别。真的播出声或换到别的内容时清掉。
     */
    private var lastError: String? = null

    /**
     * 当前装进播放器的是哪一条(mediaId)、哪一 P。**分 P 的真相只有这一份** —— 条目上那个
     * [MediaItem.cidHint] 是这一次装载的指名,解析出来的那一个不回写。上报、弹幕、字幕认的
     * 都是这里。直播没有分 P,[loadedCid] 恒为 0。
     */
    private var loadedMediaId: String? = null
    private var loadedCid: Long = 0

    /**
     * 解析完了但播放器还没走到那一条。
     *
     * **取流不是在切条那一刻发生的。** ExoPlayer 开着 lazy preparation,`MaskingMediaSource`
     * 在**装载周期**被建出来时就 prepare 内层源(1.10.1 的 `MaskingMediaSource.createPeriod`
     * → `prepareChildSource`,javap 验证),而装载周期跑在播放周期前面:当前这条缓冲满了就
     * 轮到下一条,短视频上可能提前几十秒。于是 [resolveStream] 会在还在播上一条的时候就
     * 解析好下一条。
     *
     * 解析结果直接写成"现在放的是什么"因此是错的:cid、画质清单会提前几十秒跳到下一条,
     * 播放页据 [AudioPlaybackUiState.loadKey] 判身份,画面当场换成占位封面,而
     * [adoptResolved] 里那句起播 seek 会落在**正在播的这一条**上。结果先存这里,等播放器
     * 真的走到那一条再落。
     */
    private val resolvedItems = mutableMapOf<String, LoadedItem>()

    /**
     * 正在播的这条内容的全部进度上报,见 [ProgressSession]。直播没有会话 —— "直播不上报"
     * 由"没有会话"表达,不写成分支。
     */
    private var progressSession: ProgressSession? = null

    /** 位置刻度的循环。只在放着的时候跑,见 [emitPositionTick]。 */
    private var tickJob: Job? = null

    /** 装的是本地副本还是网络流。写本地进度、核对云端进度只在前者有意义。 */
    private var loadedLocalCopy = false

    private var playInfo: PlayInfo? = null
    private var currentQuality: Int = 0

    /** 见 [AudioPlaybackUiState.cloudResumeMillis]。装载任何新东西时清掉。 */
    private var cloudResumeMillis: Long? = null

    /**
     * 一条播完了要不要接着放下一条。见 [dev.bilby.data.PlaybackPrefs.autoNext]。
     *
     * 在这里存一份镜像而不是每次现读:判断点在 `onPlaybackStateChanged` 里,那是个同步回调,
     * 而读设置是挂起的。启动一个协程去读、读完再决定,中间那一小段里播放器已经停在片尾了。
     */
    private var autoNextEnabled = true

    /**
     * 用户还想让它响着。**这个 bit 归服务所有,页面不碰。**
     *
     * 播放器暂停的原因不止一种,而只有一种是"我不想听了":暂停按钮(界面、通知栏、耳机线控)、
     * 睡眠定时器到点、队列走完。离开播放页去 UP 空间、切后台、来电避让都会让播放器停下来,但
     * 它们表达的不是这个意思,回来时该接着播。
     *
     * 判据放在这里而不是导航层,是因为导航层回答不了。它只知道页面被压住还是被弹掉,而这两者
     * 与用户想不想听没有对应关系 —— 之前三次尝试都栽在这个判断上(CLAUDE.md)。页面重新组合时
     * 只问一句"播放器装的还是这一条吗、这个 bit 还立着吗",两个问题都不需要知道自己是怎么来的。
     */
    private var playIntent = false

    override fun onCreate() {
        super.onCreate()
        runningService = this
        val container = (application as BilbyApplication).container
        videoRepository = container.videoRepository
        subtitleRepository = container.subtitleRepository
        liveRepository = container.liveRepository
        queueSourceRepository = container.queueSourceRepository
        offlineStore = container.offlineStore
        settings = container.settings
        partRequest = container.partRequest
        heartbeatReporter = container.heartbeatReporter
        loadResolver = LoadResolver(
            localCopy = offlineStore::completedFor,
            parts = { bvid ->
                (videoRepository.getVideoDetail(bvid) as? BiliResult.Ok)?.value?.let { detail ->
                    VideoParts(detail.cid, detail.pages.map { it.cid })
                }
            },
            // 问的是 `x/player/wbi/v2`:它和 playurl 都带着服务端记的那一对,但不返回流地址 ——
            // 为读一个数字去取一整份带时效的 CDN 地址再丢掉,是在风控额度上白花钱(notes §8.2.1)。
            serverPart = subtitleRepository::lastPlayedCid,
        )

        player = PlayerFactory.createPlayer(this, BilbyMediaSourceFactory(scope, ::resolveStream)).apply {
            // 声明成音乐用途并交给播放器处理音频焦点:来电、别的 app 出声时自动暂停/避让。
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // 拔耳机/断蓝牙时暂停,不然声音会突然从外放出来。
            setHandleAudioBecomingNoisy(true)
            // 息屏后 CPU 会休眠,不持锁的话播到一半就断流。WAKE_MODE_NETWORK 连 WifiLock 一起管。
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(PlayerListener())
        }

        // 定时到点是"这次听完了",和按下暂停同一类,所以连 playIntent 一起清 —— 不清的话
        // 回到播放页它会自己响起来,而用户设定时器正是为了让它别再响。
        sleepTimer = SleepTimer(scope) {
            playIntent = false
            player.pause()
        }
        scope.launch {
            sleepTimer.state.collect {
                _sleepTimerState.value = it
                applyStopAtEndOfItem()
            }
        }
        scope.launch {
            settings.playbackPrefs.collect {
                autoNextEnabled = it.autoNext
                applyStopAtEndOfItem()
            }
        }

        session = MediaSession.Builder(this, QueuePlayer(player))
            .setCallback(SessionCallback())
            .build()
    }

    /**
     * 这一条播完之后停不停。**队列前进归播放器,所以"不前进"也得由播放器表达。**
     *
     * 关掉自动连播,和定时器设成"播完这条就停",要的是同一件事:走到这一条的末尾就停在那里。
     * `pauseAtEndOfMediaItems` 正是这个语义,而且播放器会照常报一次 STATE_ENDED,
     * [PlayerListener.onPlaybackStateChanged] 那条收尾路径不必分情况。
     *
     * 自己在 ENDED 里判"要不要 seekToNext"是走不通的:自动连播开着时播放器根本不经过 ENDED,
     * 它直接换条。
     */
    private fun applyStopAtEndOfItem() {
        player.pauseAtEndOfMediaItems =
            !autoNextEnabled || sleepTimer.state.value.mode == SleepTimerMode.EndOfItem
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉任务卡片时只保留听视频:普通视频没有理由在没界面的情况下继续放。
        if (!player.playWhenReady || !backgroundPlaybackAllowed) stopSelf()
    }

    private fun pauseForAppBackground() {
        if (!backgroundPlaybackAllowed && player.playWhenReady) player.pause()
    }

    override fun onDestroy() {
        // 定格补发这次观看的最终位置。**排在 scope.cancel() 前面不是为了赶上它** —— 心跳跑在
        // 应用级 scope 上(见 [HeartbeatReporter]),这里只是把"内容离开了"这件事说出来。
        closeProgressSession()
        tickJob?.cancel()
        retryJob?.cancel()
        // 下面的 scope.cancel() 本来就会带走它,列在这里是为了和上面两个 Job 一起读:
        // 服务没了之后没有任何一个还在飞的请求值得跑完。
        enrichJob?.cancel()
        scope.cancel()
        session?.release()
        session = null
        if (runningService === this) runningService = null
        // 服务都没了，“允许后台播”这个策略也跟着作废，否则下一次开普通视频会继承到上一次听视频的设置。
        backgroundPlaybackAllowed = false
        player.release()
        _state.value = AudioPlaybackUiState()
        _positionTicks.value = PositionTick()
        _sleepTimerState.value = SleepTimerState()
        super.onDestroy()
    }

    /**
     * 打开一个直播间。
     *
     * **直播就是队列里的一条**(设计文档「决定 5」):条目带着房间号、档位和"只要声音",
     * 取流由 [resolveLiveStream] 在轮到它时做。于是重试、切档、元数据回填走的都是视频那几条
     * 路,服务这边没有第二套状态要清。
     *
     * 和 [openVideo] 一样是幂等的,报的是房间号而不是流地址 —— 页面拿到房间详情后会再发一遍
     * 带标题的命令,那一趟只该更新元数据,不该把刚起好的流掐掉重来。**只有装载参数变了才
     * 重来**:切清晰度和开关纯音频都落在这条分支上,它们要的正是重新取一次流。
     *
     * 队列因此只有这一条:直播是单条无限流,上/下一条按不动,通知栏和车机由 Timeline 自己
     * 得出这个结论,不必再有一个"直播时队列为 null"的特判。
     */
    private fun playLive(args: Bundle) {
        val roomId = args.getLong(EXTRA_ROOM_ID)
        if (roomId == 0L) {
            BiliLog.w("OPEN_LIVE 没带 roomId,忽略")
            return
        }
        val qn = args.getInt(EXTRA_LIVE_QN)
        val onlyAudio = args.getBoolean(EXTRA_LIVE_ONLY_AUDIO)
        val display = QueueItem(
            bvid = liveMediaId(roomId),
            title = args.getString(EXTRA_TITLE).orEmpty(),
            upName = args.getString(EXTRA_UP_NAME).orEmpty(),
            coverUrl = args.getString(EXTRA_COVER_URL).orEmpty(),
            durationSeconds = 0,
        )

        val current = player.currentMediaItem
        if (current?.liveRoomId == roomId && player.playbackState != Player.STATE_IDLE) {
            if (current.liveQn != qn || current.liveOnlyAudio != onlyAudio) {
                failedAttempts = 0
                lastError = null
                reloadCurrent { item, nonce ->
                    item.withLoadParams(nonce, liveQn = qn, onlyAudio = onlyAudio)
                }
                return
            }
            fillItemDisplay(display.bvid, display.title, display.upName, display.coverUrl)
            if (playIntent && !player.playWhenReady) player.playWhenReady = true
            publishState()
            return
        }

        enrichJob?.cancel()
        sourceLabel = ""
        queueSource = null
        queueEnriching = false
        queueIncomplete = false
        setQueue(
            listOf(
                liveMediaItem(
                    display = display,
                    roomId = roomId,
                    qn = qn,
                    onlyAudio = onlyAudio,
                    loadNonce = nextLoadNonce(),
                )
            ),
            startIndex = 0,
        )
    }

    /**
     * 播放页打开了一条视频。
     *
     * **这条命令是幂等的**,而且是结构性的幂等:它报的是 bvid,不是流地址。队列当前就是这条、
     * 播放器也正装着它时直接返回 —— 转屏、退出全屏、从听视频退回、通知栏切过一条之后再回到
     * 界面,走的都是这条分支。原先页面交的是流地址,"是不是同一次播放"只能靠字符串相等去猜,
     * 而 playurl 每次签名都不同,于是重试还得专门加一个标志位去绕过那道比较。
     *
     * **它带的只有 bvid 与展示信息,没有 cid。** 放到哪一 P 由 [LoadResolver] 在装载时解析,
     * 页面手上只有详情里的默认 P —— 送过来就是拿一个更差的答案盖掉刚解析出来的那个。
     *
     * 队列里已经有这条(合集里换一集、点队列中的一条)就跳过去;没有就**先装一份只有这条的
     * 临时队列并立刻起播**,真正的来源(合集,或退到 UP 投稿,DESIGN 2.4b)由 [enrichQueue]
     * 在后台补上。
     *
     * 起播曾经压在建队列后面。建队列要拉一次视频详情、再二分探测空间投稿(约 log2(页数) 次
     * 请求),这些都是"这条视频属于哪个集合"的元数据,和"这条视频怎么放出声"没有关系 ——
     * 每一次点开都要先等完一轮它们。队列仍然是唯一真相,只是它先短一格。
     */
    private fun openVideo(args: Bundle) {
        val bvid = args.getString(EXTRA_BVID).orEmpty()
        if (bvid.isEmpty()) {
            BiliLog.w("OPEN_VIDEO 没带 bvid,忽略")
            return
        }

        if (currentItem()?.bvid == bvid) {
            // **这一趟多半是来送元数据的。** 页面拿到 bvid 就发了第一遍命令(那时它还不知道
            // 这条视频叫什么),详情回来再发第二遍 —— 落到的就是这里。不采纳的话通知栏和队列
            // 面板上这条永远没有标题和封面。
            fillItemDisplay(
                bvid,
                title = args.getString(EXTRA_TITLE).orEmpty(),
                upName = args.getString(EXTRA_UP_NAME).orEmpty(),
                coverUrl = args.getString(EXTRA_COVER_URL).orEmpty(),
            )
            // 回到这一页了。播放器停着而 [playIntent] 还立着,说明上次停下不是用户的意思
            // (多半是离开页面去看别的),接着播。**这一句就是"非本意的停止,回来续播"的
            // 全部实现** —— 它不需要知道自己是被弹回来的还是被重新露出来的,那两个问题
            // 正是之前三次尝试栽进去的地方。
            //
            // **这条命令再也动不了分 P。** 它以前带着页面手上那个默认 cid(多 P 时就是 P1),
            // 于是服务刚按观看记录切到第 7 P,页面的第二遍命令就把它推回 P1;为此长出过两道
            // 防御(记下"被续播替换掉的那个 cid"、记下"现在放的是本地副本")。入口收成只有
            // bvid 之后,页面没有可以覆盖的东西,两道防御连同这条分支一起删掉。换 P 是
            // [ACTION_PLAY_PART],那是用户当场表达的意思。
            if (playIntent && !player.playWhenReady) player.playWhenReady = true
            publishState()
            // 上一次补全失败就停在了单条队列上。这条命令在每次回到播放页时都会再发一遍,
            // 拿它当重试点,不必为此单开一条命令和一个按钮。
            if (queueIncomplete) enrichQueue(bvid)
            // 回到这一页也要重新核对一次云端进度,理由见 [reconcileIfLocalCopy]。
            // **这一趟是唯一的机会**:播放器还装着这一条,下面那些装载路径一条都走不到。
            reconcileIfLocalCopy(bvid)
            return
        }
        val existing = indexOfMediaId(bvid)
        if (existing >= 0) {
            seekToQueueIndex(existing)
            return
        }

        finishOpenChain("superseded")
        openChain = PerfTrace.chain("openVideo").also { it.mark("command") }

        // 临时队列用命令里带着的东西现造,**带多少算多少**:页面在拿到详情之前就发第一遍
        // 命令了,那时它手里只有 bvid。标题和封面由第二遍命令补(见上面的幂等分支),
        // 放哪一 P 由 [LoadResolver] 在装载时解析。
        sourceLabel = ""
        queueSource = null
        openChain?.mark("tempQueue")

        setQueue(
            listOf(
                QueueItem(
                    bvid = bvid,
                    title = args.getString(EXTRA_TITLE).orEmpty(),
                    upName = args.getString(EXTRA_UP_NAME).orEmpty(),
                    coverUrl = args.getString(EXTRA_COVER_URL).orEmpty(),
                    durationSeconds = 0,
                ).toMediaItem(loadNonce = nextLoadNonce())
            ),
            startIndex = 0,
        )
        enrichQueue(bvid)
    }

    /**
     * 把临时队列换成真正的来源。与起播并行,失败只是队列短一格。
     *
     * **结果要过两道校验才敢用**,各挡一件事:
     * - generation 挡"补全期间用户又打开了别的视频"。那一次已经装了自己的临时队列并起播,
     *   这份结果属于上一条,写进去就是把队列换成另一条视频的集合。
     * - 当前 bvid 再挡一次,因为队列还会被 SEEK_TO_BVID、通知栏的上/下一条移动,那些路径
     *   不碰 generation。
     *
     * 补进去的两段按 bvid 在来源里定位,不依赖来源给的下标:定位不到时来源会降级成
     * "从最新 N 条开始",那份列表里根本没有这条视频。
     */
    private fun enrichQueue(bvid: String) {
        enrichJob?.cancel()
        queueIncomplete = false
        queueEnriching = true
        val generation = ++openGeneration
        val chain = PerfTrace.chain("queueEnrich")
        enrichJob = scope.launch {
            val built = offlineQueue(bvid) ?: queueSourceRepository.forVideo(bvid)
            chain.mark("built")
            if (generation != openGeneration || currentItem()?.bvid != bvid) {
                // 不动 queueEnriching:此刻它属于顶掉这次的那一轮补全。
                chain.mark("stale")
                chain.end()
                return@launch
            }
            queueEnriching = false
            if (built == null || !fillQueueAround(bvid, built.items)) {
                // 宁可只有一条,也不能换上一份不含这条视频的队列:正在播的那一条会从队列里
                // 消失,而队列界面高亮的是别人。
                BiliLog.w("队列补全失败或来源里没有当前视频,留在单条队列 bvid=$bvid")
                queueIncomplete = true
                chain.mark("failed")
                chain.end()
                publishQueueChange()
                return@launch
            }
            sourceLabel = built.sourceLabel
            queueSource = built.source
            player.shuffleModeEnabled = settings.playbackPrefs.first().shuffled
            chain.count("items", player.mediaItemCount.toLong())
            chain.end()
            publishQueueChange()
        }
        // 补全在飞这件事本身要发出去:上面 setQueue 发的那一份还是"队列只有一条"。
        publishQueueChange()
    }

    /**
     * 把来源里当前这条的前后两段插进队列。
     *
     * **正在播的那一条原样留着,不换掉。** 它的源已经解析好、正在出声,换成一个等价的新条目
     * 就是重新取一次流、画面从头开始 —— 而这次补全跟"正在放什么"没有关系。
     *
     * 先插后面那段再插前面那段:先插前面会把当前下标推走,后面那段就落错位置。
     */
    private fun fillQueueAround(bvid: String, items: List<QueueItem>): Boolean {
        // 一个 bvid 在队列里最多一条:两个队列面板都拿 bvid 当 LazyColumn key,来源列表
        // 里的重复条目(转发动态、系列收录两次)不挡在这里就会走到那边崩掉。
        val unique = items.distinctBy { it.bvid }
        val here = unique.indexOfFirst { it.bvid == bvid }
        if (here < 0) return false
        val current = player.currentMediaItemIndex
        player.addMediaItems(current + 1, unique.drop(here + 1).map { it.toMediaItem() })
        player.addMediaItems(current, unique.take(here).map { it.toMediaItem() })
        return true
    }

    /**
     * 本地有完整副本时,队列就是**整个缓存库**(owner 定)。
     *
     * 判据和 [resolveStream] 挑本地副本用的是同一条:盘上有这条视频的完整副本。两处必须一致 ——
     * 一旦放的是本地文件而队列却是"这条视频所属的合集",队列里除了这一条以外全要联网,而人
     * 此刻多半正好没网,下一条就停在取流失败上。
     *
     * 顺带解决的是补全在离线时必然失败:[QueueSourceRepository.forVideo] 要拉详情和空间投稿,
     * 没网就只剩一条的队列 —— 而缓存列表本身是一份用户亲手选定的有限集合,拿它当队列不违反
     * "队列内容由用户自己选定"(DESIGN 2.4b)。
     */
    private suspend fun offlineQueue(bvid: String): QueueBuildResult? {
        val cached = offlineStore.list()
            .filter { it.status == OfflineStatus.Completed }
            .sortedByDescending { it.createdAtMillis }
            // 缓存库按 (bvid, cid) 一 P 一条,队列行是视频不是分 P(多 P 不是队列项)。
            // 不收拢的话同一视频缓了两个 P 就是两行同 bvid,队列面板拿 bvid 当 LazyColumn
            // key,真机上直接崩(Key was already used)。
            .distinctBy { it.bvid }
        if (cached.none { it.bvid == bvid }) return null
        return QueueBuildResult(
            items = cached.map {
                QueueItem(
                    bvid = it.bvid,
                    title = it.title,
                    upName = it.upName,
                    coverUrl = it.coverUrl,
                    durationSeconds = it.durationSeconds,
                )
            },
            sourceLabel = "已缓存",
        )
    }

    /**
     * 队列变了,但播放没变。**不重新装载**:当前项、cid 和播放位置都没动,播放器不知道这件事
     * 发生过。loading 沿用上一次发布的值 —— 换队列的时刻取流可能还在飞,顺手清成 false 就是
     * 在别人的进度上关掉了转圈。
     */
    private fun publishQueueChange() = publishState(loading = _state.value.loading)

    /** 队列就是 playlist,面板要的东西全从这里读。**服务不另存一份列表。** */
    private val queueItems: List<QueueItem>
        get() {
            val items = List(player.mediaItemCount) { player.getMediaItemAt(it).toQueueItem() }
            val unique = items.distinctBy { it.bvid }
            if (unique.size != items.size) {
                // 面板拿 bvid 当 LazyColumn key,重复条目到那边是崩溃。发布口去重只是兜底,
                // 出现这行日志说明又有生产者把同一 bvid 塞进了 playlist,要去查它。
                BiliLog.w(
                    "playlist 出现重复 bvid: " +
                        items.groupingBy { it.bvid }.eachCount().filterValues { it > 1 }.keys,
                )
            }
            return unique
        }

    /** 队列当前这一条。直播也是队列里的一条(设计文档「决定 5」),所以这里不分情况。 */
    private fun currentItem(): QueueItem? = player.currentMediaItem?.toQueueItem()

    private fun indexOfMediaId(mediaId: String): Int =
        (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == mediaId }
            ?: -1

    /**
     * 换一份队列并从第 [startIndex] 条起播。
     *
     * `setMediaItems` 之外不另发起播命令:每一条的取流由它自己的源在轮到时做
     * (见 [LazyMediaSource]),所以这里交出去的是一整份队列,不是一条流。
     *
     * 上一条留下的装载状态在这里一并作废。画质清单尤其不能留:直播的档位和视频不同源,
     * 留着上一条视频的会给出一个点了没用的菜单。
     */
    private fun setQueue(items: List<MediaItem>, startIndex: Int) {
        persistCachedProgress()
        // 上一条内容到此为止:定格补发它的最终位置。整份队列被换掉时不会有 transition 事件,
        // 会话自己缓存的那个位置就是唯一的来源。
        closeProgressSession()
        resolvedItems.clear()
        retryJob?.cancel()
        failedAttempts = 0
        lastError = null
        playIntent = true
        resolvingMediaId = items.getOrNull(startIndex)?.mediaId
        loadedMediaId = null
        loadedCid = 0
        loadedLocalCopy = false
        playInfo = null
        currentQuality = 0
        cloudResumeMillis = null
        publishState(loading = true)
        player.setMediaItems(items, startIndex, C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    /** 见 [loadNonce]:每一次"要重新取流"的装载都要一个新的。 */
    private fun nextLoadNonce(): Int = ++loadCounter

    /**
     * 跳到队列里的第 [index] 条:点队列中的一项、合集里换一集。
     *
     * 上一条的进度不在这里写:跨条目的 seek 会走 [PlayerListener.onPositionDiscontinuity],
     * 那里已经是所有"换了一条"的唯一收尾处 —— 通知栏的上/下一条压根不经过这个函数。
     */
    private fun seekToQueueIndex(index: Int) {
        if (index !in 0 until player.mediaItemCount) return
        retryJob?.cancel()
        failedAttempts = 0
        lastError = null
        playIntent = true
        resolvingMediaId = player.getMediaItemAt(index).mediaId
        player.seekToDefaultPosition(index)
        player.playWhenReady = true
        publishState(loading = true)
    }

    /**
     * 当前这一条按新的装载参数重来一遍:换 P、重试、切清晰度、直播换档与切纯音频都走这里。
     * [rebuild] 拿到现在这一条和一个新的 [loadNonce],给出新参数下的那一条。
     *
     * 换掉的参数让 [LazyMediaSource.canUpdateMediaItem] 判假,播放器于是重建这一条的源、
     * 重新取一次流 —— 这正是重试要的:直链过期是最常见的那种失败,拿同一条地址再 prepare
     * 一次必然还是同样的错。**直播因此不需要自己的重试路径**:它的地址同样是重新解析出来的。
     *
     * **顺序是插入 → seek → 删除,不用 `replaceMediaItem`。** replace 的实现同样是先插后删,
     * 但删除正在播的条目时落点由 `resolveSubsequentPeriod` 解析,而它认随机顺序:顺序播放时
     * 落到替换条目,随机模式下落到乱序表里随机一条。真机上出过(随机下切 P 跳去队列里
     * 不知道哪条)。显式 seek 不经过这层解析,先 seek 过去再删旧条,落点就钉死了。
     */
    private fun reloadCurrent(
        playWhenReady: Boolean = true,
        rebuild: (MediaItem, Int) -> MediaItem,
    ) {
        val index = player.currentMediaItemIndex
        val existing = player.currentMediaItem ?: return
        persistCachedProgress()
        resolvingMediaId = existing.mediaId
        playIntent = playWhenReady
        publishState(loading = true)
        player.addMediaItem(index + 1, rebuild(existing, nextLoadNonce()))
        player.seekToDefaultPosition(index + 1)
        player.removeMediaItem(index)
        player.prepare()
        player.playWhenReady = playWhenReady
        // 上面那句 seek 会**同步**跑 onPositionDiscontinuity(ListenerSet.flushEvents 在调用
        // 线程上直接 run),那次 publishState 读到的是删掉旧条之前的 playlist,同 bvid 两条。
        // 这里必须再发布一次,否则带重复的快照就是本条消息的最终状态,队列面板下一帧按
        // bvid 当 key 直接崩。真机上崩过。
        publishQueueChange()
    }

    /**
     * 把一个队列项解析成能播的源。**全 app 唯一一处取流**,由播放器在要放这一条时调起
     * (见 [LazyMediaSource]),于是它跑在"马上要出声"的那一刻,而不是建队列的那一刻 ——
     * playurl 给的 CDN 直链带时效,提前取好的那些等轮到时早过期了。
     *
     * **失败一律抛出去。** 播放器会把它变成 `onPlayerError`,退避重试在那里收口(见
     * [retryAfterFailure])。在这里吞掉的话播放器会永远停在 BUFFERING:界面转圈转到天荒地老,
     * 日志里一个字都没有。
     */
    private suspend fun resolveStream(mediaItem: MediaItem): MediaSource {
        if (mediaItem.isLive) return resolveLiveStream(mediaItem)
        val bvid = mediaItem.mediaId
        val positionOverrideMillis = mediaItem.startPositionHint
        // 指名的那一 P:页内切 P、切清晰度与重试带着上一次解析出来的那个,缓存列表点某行
        // 留在 [PartRequest] 里。两者都是一次性的意图,取走即弃。
        val requestedCid = mediaItem.cidHint.takeIf { it != 0L } ?: partRequest.consume(bvid)

        when (val plan = loadResolver.resolve(bvid, requestedCid)) {
            is LoadPlan.LocalCopy -> {
                val cached = plan.item
                // 标题/UP/封面从索引里填。离线时这是元数据唯一的来源。
                fillItemDisplay(bvid, cached.title, cached.upName, cached.coverUrl)
                val audio = offlineStore.audioFile(cached.bvid, cached.cid).takeIf { it.isFile }
                onResolved(
                    LoadedItem(
                        mediaId = cached.bvid,
                        cid = cached.cid,
                        startPositionMillis = positionOverrideMillis ?: plan.startPositionMillis,
                        localCopy = true,
                        // 画质菜单留空:本地只有下载时选的那一档,摆一个点了没用的菜单不如不摆。
                        playInfo = null,
                        quality = cached.qualityId,
                    )
                )
                return PlayerFactory.createLocalMediaSource(
                    offlineStore.videoFile(cached.bvid, cached.cid).path,
                    audio?.path,
                )
            }

            LoadPlan.Unresolved -> {
                BiliLog.w("解析不出要放哪一 P bvid=$bvid")
                throw IOException(getString(R.string.playback_error_detail))
            }

            is LoadPlan.Online -> return resolveOnlineStream(bvid, plan.cid, positionOverrideMillis)
        }
    }

    /** [resolveStream] 的在线那一支。分出来只是因为解析与取流是两件事,读起来不该缠在一起。 */
    private suspend fun resolveOnlineStream(
        bvid: String,
        cid: Long,
        positionOverrideMillis: Long?,
    ): MediaSource {
        fillDisplayFromDetail(bvid)
        val prefs = settings.playerPrefs.first()
        // 当次播放里手动切过就用那一个;否则按此刻计不计费取对应的那一档。判据每次取流
        // 现算,所以出门断了 WiFi 之后**下一条**自然就降下来了,当前这条不动。
        val quality = currentQuality.takeIf { it != 0 }
            ?: prefs.defaultQualityOn(isOnMeteredNetwork())
        openChain?.mark("playurlStart")
        val result = videoRepository.getPlayUrl(
            bvid,
            cid,
            preferredQuality = quality,
            preferredCodecs = prefs.codec.codecIds,
        )
        val playUrl = when (result) {
            is BiliResult.Ok -> result.value
            is BiliResult.ApiError -> {
                BiliLog.w("取流失败 bvid=$bvid code=${result.code} ${result.message}")
                throw IOException(getString(R.string.playback_error_stream, result.message))
            }
            is BiliResult.Failure -> {
                BiliLog.w("取流失败 bvid=$bvid", result.cause)
                throw IOException(getString(R.string.playback_error_network))
            }
        }
        openChain?.mark("playurlEnd")
        // **秒数一定属于问的这一 P。** playurl 的 `last_play_cid` 是填的,语义是"你问的这一 P
        // 有记录吗":对得上给记录,对不上给 0(实测,notes §8.2.1)。[dev.bilby.data.resumeAtMillisFor]
        // 就是照这条判的,所以解析出哪一 P 就拿哪一 P 的秒数,这里不需要再核一遍。
        onResolved(
            LoadedItem(
                mediaId = bvid,
                cid = cid,
                startPositionMillis = positionOverrideMillis ?: playUrl.resumeAtMillisFor(cid),
                localCopy = false,
                playInfo = playUrl,
                quality = quality,
            )
        )
        return PlayerFactory.createMediaSource(
            playUrl.streams.videoUrl,
            playUrl.streams.audioUrl,
        )
    }

    /**
     * [resolveStream] 的直播那一支。
     *
     * **每次都重新要一次地址,不重用手上那条。** 直播直链和 playurl 一样带时效,而这一支被
     * 调起的时刻正是"马上要出声"。断流重试因此不需要自己的路径:重来一遍就是重新解析一遍,
     * 和视频那边同一条。
     *
     * 页面也问一次同一个接口(它要开播状态和档位清单),这一趟是第二次。没有让页面把地址
     * 递进来省掉它:递进来的是页面拿到时的那条地址,重试、切档、切纯音频都会把它变成一条
     * 对不上的旧地址,而那正是"直播是普通 MediaItem"要消掉的东西。
     */
    private suspend fun resolveLiveStream(item: MediaItem): MediaSource {
        val roomId = item.liveRoomId
        // qn=0 是"页面还没拿到档位就发了命令",按默认档要,别把 0 原样传出去。
        val qn = item.liveQn.takeIf { it > 0 } ?: LiveRepository.DEFAULT_QN
        val playback = liveRepository.loadPlayback(roomId, qn, onlyAudio = item.liveOnlyAudio)
        when (playback) {
            is BiliResult.Ok -> Unit
            is BiliResult.ApiError -> {
                BiliLog.w("直播取流失败 roomId=$roomId code=${playback.code} ${playback.message}")
                throw IOException(getString(R.string.playback_error_live_stream))
            }
            is BiliResult.Failure -> {
                BiliLog.w("直播取流失败 roomId=$roomId", playback.cause)
                throw IOException(getString(R.string.playback_error_live_stream))
            }
        }
        val url = playback.value.stream?.url?.takeIf { playback.value.isLive }
            ?: throw LiveEndedException(getString(R.string.playback_error_live_ended))
        onResolved(
            LoadedItem(
                mediaId = item.mediaId,
                // 直播没有分 P,于是也没有进度会话,见 [startProgressSession]。
                cid = 0,
                startPositionMillis = 0,
                localCopy = false,
                // 档位清单归页面(它问的是同一个接口),视频那套画质菜单在直播上点不出东西。
                playInfo = null,
                quality = 0,
            )
        )
        return PlayerFactory.createLiveMediaSource(url)
    }

    /**
     * 把解析途中拿到的标题、UP 名、封面补到队列项上。
     *
     * **推迟一拍再改。** 这一刻还在解析协程里,而这一条的源正处在自己的 prepare 中途;
     * 改动只碰 `mediaMetadata`,[LazyMediaSource.canUpdateMediaItem] 因此判真、播放器只换条目
     * 不重建源 —— 但让一次 playlist 改动落在源的 prepare 中途,是一条没必要留着的路。
     *
     * 空值不覆盖:空间投稿来源的条目 upName 恒为空,而从队列面板点进来的那条本来就带着完整
     * 信息,一次空参数不该把它擦掉。
     */
    private fun fillItemDisplay(bvid: String, title: String, upName: String, coverUrl: String) {
        scope.launch(Dispatchers.Main) {
            val index = indexOfMediaId(bvid)
            if (index < 0) return@launch
            val existing = player.getMediaItemAt(index)
            val merged = existing.toQueueItem().let {
                it.copy(
                    title = title.ifEmpty { it.title },
                    upName = upName.ifEmpty { it.upName },
                    coverUrl = coverUrl.ifEmpty { it.coverUrl },
                )
            }
            if (merged == existing.toQueueItem()) return@launch
            player.replaceMediaItem(index, existing.withDisplay(merged))
            publishQueueChange()
        }
    }

    /**
     * 条目还没有标题时才去问详情。空间投稿来源的列表不返回标题和 UP 名,而别处进来的条目
     * 本来就带着完整信息 —— 每次装载都问一遍,是给切 P、重试、切清晰度各预约一次请求。
     */
    private suspend fun fillDisplayFromDetail(bvid: String) {
        val index = indexOfMediaId(bvid)
        if (index >= 0 && !player.getMediaItemAt(index).mediaMetadata.title.isNullOrEmpty()) return
        val detail = videoRepository.getVideoDetail(bvid) as? BiliResult.Ok ?: return
        fillItemDisplay(bvid, detail.value.title, detail.value.up.name, detail.value.coverUrl)
    }

    /**
     * 一次装载解析出来的全部结果。**在被 [adoptResolved] 采纳之前,它不代表"现在放的是什么"**
     * ——解析可能跑在播放器还没走到这一条的时候,见 [resolvedItems]。
     */
    private data class LoadedItem(
        val mediaId: String,
        val cid: Long,
        val startPositionMillis: Long,
        val localCopy: Boolean,
        val playInfo: PlayInfo?,
        val quality: Int,
    )

    /** 解析成功。轮到它了就当场落地,没轮到就先存着。 */
    private fun onResolved(loaded: LoadedItem) {
        resolvedItems[loaded.mediaId] = loaded
        adoptResolved(loaded.mediaId)
    }

    /**
     * 解析结果落到"播放器正装着的那一条"上。**分 P 的真相从这一刻起是 [loadedCid]。**
     *
     * [AudioPlaybackUiState.loadKey] 认的就是它,页面据此决定挂画面还是画占位 —— 所以不能在
     * 发出装载命令的那一刻就置上:那中间还隔着一整趟取流,画面上还是上一条的最后几帧。
     *
     * seek 得起作用,是因为播放器允许对还没拿到时间线的条目定位:内层源此刻刚造好、时间线还
     * 没发出来,位置先记在 masking 周期上,真时间线到了再落(`MaskingMediaSource` 的
     * `onChildSourceInfoRefreshed`,非零的准备位置优先于窗口默认位置)。
     */
    private fun adoptResolved(mediaId: String?) {
        if (mediaId == null || player.currentMediaItem?.mediaId != mediaId) return
        // 取走即弃:回到这一条时它会重新 prepare、重新解析,留着只会让一份旧的抢在新的前面。
        val loaded = resolvedItems.remove(mediaId) ?: return
        loadedMediaId = loaded.mediaId
        loadedCid = loaded.cid
        loadedLocalCopy = loaded.localCopy
        playInfo = loaded.playInfo
        currentQuality = loaded.quality
        resolvingMediaId = null
        cloudResumeMillis = null
        openChain?.mark("prepare")
        // **起播定位排在建会话之前**,于是那一次 seek 落在"还没有会话"的窗口里,不会被当成
        // 用户跳到了这里而立刻上报。缓存条目的云端核对(下面那句)要赶在本地位置被报上去之前
        // 问到服务端的值,否则比对的对象就是我们自己刚写进去的那个。
        closeProgressSession()
        if (loaded.startPositionMillis > 0) player.seekTo(loaded.startPositionMillis)
        startProgressSession(loaded.mediaId, loaded.cid)
        startTicking()
        publishState()
        emitPositionTick()
        // 起播之后才去核对云端进度。**它不再是装载协程的子协程** —— 装载协程现在归
        // MediaSource,换一条时这份核对靠自己开头那句"播放器装的还是这一条吗"作废。
        if (loaded.localCopy) {
            scope.launch {
                offlineStore.completedFor(loaded.mediaId, loaded.cid)?.let { reconcileCachedProgress(it) }
            }
        }
    }

    /**
     * 换一个进度会话。旧的先 close(定格补发),新的从这一刻起拥有这条内容的全部上报。
     *
     * aid 由 bvid 换算([BvidCodec]),不为它去取一次详情:心跳接口 aid/bvid 二选一,而队列项
     * 身上只有 bvid。换不出来(理论上只有 bvid 本身是脏的)就不建会话——宁可这一条不上报,
     * 也不能拿一个编出来的号往服务端写,那是把进度记到别人的稿件上。
     *
     * **直播落在同一条判断上**:它没有 cid,也换不出 aid。"直播不上报"因此是没有会话,
     * 不是某处写着一个 if。
     */
    private fun startProgressSession(bvid: String, cid: Long) {
        closeProgressSession()
        // 没有 cid 的只有直播,那不是异常,只是这条内容没有进度可报;换不出 aid 才是。
        if (cid == 0L) return
        val aid = BvidCodec.toAid(bvid)
        if (aid <= 0) {
            BiliLog.w("建不了进度会话 bvid=$bvid cid=$cid")
            return
        }
        progressSession = ProgressSession(aid, cid) { playedTimeSeconds, finished ->
            heartbeatReporter.report(aid, cid, playedTimeSeconds, finished) { reportedMillis ->
                // 条目不在盘上时 recordServerBase 自己就什么都不做,这里不必先查一遍。
                // 心跳成功是 serverBase 推进的唯一入口,见 [mergeCachedProgress]。
                offlineStore.recordServerBase(bvid, cid, reportedMillis)
            }
        }
    }

    /** 见 [ProgressSession.close]:幂等,任何退出路径只管调,不必关心顺序和重复。 */
    private fun closeProgressSession(atMillis: Long? = null, completed: Boolean = false) {
        progressSession?.close(atMillis, completed)
        progressSession = null
    }

    /**
     * 本地副本起播之后,到云端核对一次进度。
     *
     * **不挡起播,失败什么也不中止。** "缓存了却播不动"那个 bug 的根因是补 cid 那次网络调用排在
     * 本地检查前面、失败即 return,判据是"阻塞且失败会中止",不是"有网络调用" —— 所以这一句放在
     * 起播之后是安全的,把它挪到前面就不是了。
     *
     * **云端赢了也不 seek**,只把位置摆上界面等用户点(见 [AudioPlaybackUiState.cloudResumeMillis])。
     *
     * 走 `x/player/wbi/v2` 而不是 playurl:两者都带这一对续播位置,但前者不返回流地址。为了读一个
     * 数字去取一整份带时效的 CDN 地址再丢掉,是在风控额度上白花钱。
     *
     * **服务端记的那一对属于哪一 P 必须核** —— v2 回的是整条视频当前那一对,问 P1 也会回 P7 的值
     * (实测,见 [dev.bilby.api.dto.PlayerV2Dto])。对不上就当"这一 P 服务端没有记录",本地那份直接
     * 说了算:全站每条视频只存一对,分 P 各自的进度它不存,所以这不是"去别处取"的问题,那份根本
     * 不存在。
     *
     * 顺带把 base 校准到云端此刻的值:这一次我们确实问到了它。用户不点、继续在本地看下去时,停止
     * 时写下的本地位置配上这个 base,下次打开就是"云端没再动过,本地说了算" —— 他刚做的那个选择
     * 被记住了。
     */
    /**
     * 播放器已经装着这一条时,重新去云端核对一次进度。
     *
     * **不是装载路径的一部分,而是"又进了一次这条视频"。** 核对本来只发生在装载那一刻,
     * 而播放器是单例、离开播放页也不卸载 —— 从缓存目录点进来看一会儿、退出去、再点回来,
     * 走的是 [openVideo] 里那条"已经是它了"的捷径,装载路径一步都不走,于是这中间别处产生的
     * 新进度一次也问不到。表现是只有重启 app 那条提示才弹得出来。
     *
     * 时间上赶得及:心跳按位置每 5 秒才报一次([ProgressSession.HEARTBEAT_INTERVAL_SECONDS]),
     * 而这一趟只是一次请求。不赶在它前面的话,云端那份记录会先被本地进度盖掉,提示就再也没有
     * 可比的对象了。装载路径上那次核对更靠前——起播定位排在建会话之前,见 [adoptResolved]。
     *
     * 只对本地副本做:在线播放的进度本来就以服务端那份为准,没有第二份可比。
     */
    private fun reconcileIfLocalCopy(bvid: String) {
        if (!loadedLocalCopy || bvid != loadedMediaId) return
        val cid = loadedCid
        scope.launch {
            offlineStore.completedFor(bvid, cid)?.let { reconcileCachedProgress(it) }
        }
    }

    private suspend fun reconcileCachedProgress(cached: OfflineItem) {
        // **问不到就什么都不做。** 离线时这一句必然失败,而那正是这个功能存在的场景 ——
        // 把失败当成"服务端说 0"会把基线抹成 0,于是"云端没动过、本地说了算"这一支再也成立
        // 不了,而下次联网时 0 又必然不等于云端真值,弹一条用户根本没做过的"别处已看到"。
        val lastPlayed = subtitleRepository.lastPlayed(cached.bvid, cached.cid) ?: return
        // 回来时播放器可能已经换到别的东西上了(手快点了下一条)。
        if (loadedMediaId != cached.bvid || loadedCid != cached.cid) return

        val serverMillis = if (lastPlayed.cid == cached.cid) lastPlayed.positionMillis else 0L
        offlineStore.recordServerBase(cached.bvid, cached.cid, serverMillis)

        val merged = mergeCachedProgress(
            localMillis = cached.watchedPositionMillis,
            base = cached.serverProgressBaseMillis,
            serverMillis = serverMillis,
        )
        // 没冲突就把上一次的结果清掉,不是直接 return。**回到这一页会重新组合一次**,
        // 那时 [CloudResumeHint] 的 visible 从 false 起步,状态里留着的旧值会让它再弹一遍 ——
        // 而这一次核对刚刚说了"云端没有更新的位置"。
        if (merged == cached.watchedPositionMillis) {
            if (cloudResumeMillis != null) {
                cloudResumeMillis = null
                publishState()
            }
            return
        }
        cloudResumeMillis = resumePositionMillis(merged, cached.durationSeconds * 1000)
            .takeIf { it > 0 }
        publishState()
    }

    /**
     * 把本地副本播到哪儿了写进它的 meta.json。
     *
     * 只在放本地副本时有意义:装在线流时服务端那份才是真相,本地不掺和(见
     * [dev.bilby.data.resumeAtMillisFor])。[loadedLocalCopy] 正是"此刻放的是本地副本"。
     *
     * `NonCancellable`:调用点都在"正要停下来"的时刻(暂停、播完、换一条),而那些时刻紧挨着
     * scope 被取消 —— 写盘是这次观看留下的唯一痕迹,不能跟着一起没。
     */
    private fun persistCachedProgress(positionMillis: Long = player.currentPosition) {
        val bvid = loadedMediaId?.takeIf { loadedLocalCopy } ?: return
        val cid = loadedCid
        val position = positionMillis.coerceAtLeast(0)
        if (position <= 0) return
        scope.launch(NonCancellable) { offlineStore.recordProgress(bvid, cid, position) }
    }

    /** 换分 P。**分 P 是这条视频内部的结构,不是队列里的另一条**(CLAUDE.md),所以不动队列位置。 */
    private fun playPart(cid: Long) {
        failedAttempts = 0
        lastError = null
        reloadCurrent { item, nonce -> item.withLoadParams(nonce, requestedCid = cid) }
    }

    /**
     * 切清晰度。在播放页改画质就是改默认画质,**改的是当前网络那一档**。
     *
     * 设置页那两行和这里不是"两处能改同一件事":它们是同一个值按计不计费分成的两格,播放页
     * 这一下写进当下所在的那一格。在 WiFi 上调高不会连带把出门时用的那一档也调高。
     *
     * NonCancellable 落盘:切完清晰度就退出页面是常见操作,而 DataStore 的 edit 是挂起函数。
     */
    private fun setQuality(quality: Int) {
        currentQuality = quality
        val metered = isOnMeteredNetwork()
        scope.launch(NonCancellable) { settings.saveDefaultQuality(quality, metered) }
        val position = player.currentPosition.coerceAtLeast(0)
        reloadCurrent { item, nonce ->
            item.withLoadParams(
                nonce,
                requestedCid = currentItemCid(),
                startPositionMillis = position,
            )
        }
    }

    /**
     * 当前这一条播不了:**退避重试它,不往下跳**。
     *
     * 往下跳是错的。失败里没有哪一位能区分"这条视频没了"和"网络/解码器整体坏了" ——
     * 取流的 ApiError 和播放器的 4003 在两种情形下长得一样。而后者每一条都会失败,
     * "跳下一条"以毫秒为单位推进:线上见过一次 SSL 握手失败连跳 42 条、停在队尾,
     * 用户看到的只是"忽然不放了",队列位置也回不去了。
     *
     * 退避是因为失败大多是瞬时的:握手失败、直链过期、解码器被别的进程占着。立刻重试
     * 只会连着撞上同一个原因,而每档翻倍的等待让重试落在原因可能已经消失的时刻。
     *
     * 退到 [MAX_ATTEMPTS] 次仍然失败就停在这一条,把原因摆到界面上,跳还是再试由用户决定。
     * 这和"播完即停、不从推荐池续接"是同一条约束:决策点不替用户取消。
     */
    private fun retryAfterFailure(reason: String, playWhenReady: Boolean) {
        finishOpenChain("failed")
        failedAttempts++
        lastError = reason
        if (failedAttempts >= MAX_ATTEMPTS) {
            BiliLog.w("放弃重试(已失败 $failedAttempts 次): $reason")
            stopPlayback()
            return
        }
        val delayMillis = RETRY_BASE_DELAY_MILLIS shl (failedAttempts - 1)
        BiliLog.w("第 $failedAttempts 次失败,${delayMillis}ms 后重试: $reason")
        publishState(loading = true)
        retryJob = scope.launch {
            delay(delayMillis)
            reloadThisItem(playWhenReady)
        }
    }

    /** 界面上"重试"按下。手动重试是一份新的额度,退避从头算起。 */
    private fun retryNow() {
        failedAttempts = 0
        lastError = null
        retryJob?.cancel()
        reloadThisItem(playWhenReady = true)
    }

    /**
     * 当前这一条原地重来。装载参数一个不换,只换 [loadNonce] —— 要的就是"同样的东西再取一次
     * 流",视频沿用它那一 P,直播沿用它的档位和纯音频开关。
     *
     * 这一条以前分成两支(直播走一段自己的取流),而 `ACTION_RETRY` 只走视频那支:在直播间
     * 按重试等于把直播当成一条视频重建。直播 MediaItem 化之后两支合成一支,那个 bug 随之消失。
     */
    private fun reloadThisItem(playWhenReady: Boolean) {
        val cid = currentItemCid()
        reloadCurrent(playWhenReady) { item, nonce -> item.withLoadParams(nonce, requestedCid = cid) }
    }

    /**
     * 当前这一条已经确认的分 P,**播放器装的不是它时为 0**。
     *
     * 重来一遍(重试、切清晰度)要沿用上次解析出来的那一 P,否则多 P 视频一按重试就回到第一 P。
     * 但装载失败时 [loadedCid] 还停在上一条视频上 —— 连播到下一条、下一条取流就失败,是最常
     * 出现的一次。拿那个值去取流是问另一条视频的分 P,服务端回 -404,而重试从此每次都失败。
     */
    private fun currentItemCid(): Long =
        loadedCid.takeIf { loadedMediaId != null && loadedMediaId == player.currentMediaItem?.mediaId }
            ?: 0L

    /**
     * 随机开关翻过了。**开关本身归播放器**(`shuffleModeEnabled`),这里只把它记成下次新建
     * 队列的初值 —— 通知栏、车机和 app 内的按钮走的都是标准命令,不再各有一条路。
     */
    private fun persistShuffled(shuffled: Boolean) {
        scope.launch(NonCancellable) {
            val prefs = settings.playbackPrefs.first()
            settings.savePlaybackPrefs(prefs.copy(shuffled = shuffled))
        }
        publishState()
    }

    /**
     * 停下来,而且是**结束意义上的停**:队列走完、睡眠定时器到点、队列空了。只暂停不停服务,
     * 用户可能想按上一条回去重听。
     *
     * 清掉 [playIntent] —— 这几种都是"这次听完了",回到播放页不该自己再响起来。
     */
    private fun stopPlayback() {
        playIntent = false
        player.playWhenReady = false
        publishState()
    }

    /**
     * 位置刻度的循环。**只在放着的时候跑** —— 停着的时候位置不动,刻度也就没有新内容,而这是
     * 个每半秒醒一次的循环,停不下来就是一直醒着。
     *
     * 半秒一次是给弹幕时钟的:心跳按位置节流(≥5 秒),再密也不会多发。弹幕时钟在两次刻度之间
     * 自己外推(见 [PositionTick]),所以这个间隔决定的不是弹幕的平滑度,而是外推最多偏多久。
     */
    private fun startTicking() {
        if (!player.isPlaying || tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                delay(POSITION_TICK_INTERVAL_MILLIS)
                emitPositionTick()
                progressSession?.onPosition(player.currentPosition, playerDurationMillis())
            }
        }
    }

    private fun emitPositionTick() {
        _positionTicks.value = PositionTick(
            positionMillis = player.currentPosition.coerceAtLeast(0),
            durationMillis = playerDurationMillis(),
            isPlaying = player.isPlaying,
            speed = player.playbackParameters.speed,
            anchorMillis = SystemClock.elapsedRealtime(),
        )
    }

    /** 时间线还没到、或者放的是直播时为 0。完播判定据此按"不知道时长"处理。 */
    private fun playerDurationMillis(): Long =
        player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0

    private fun publishState(loading: Boolean = false) {
        _state.value = AudioPlaybackUiState(
            nowPlaying = nowPlaying(),
            loadKey = loadedMediaId,
            currentCid = loadedCid,
            isPlaying = player.isPlaying,
            loading = loading,
            error = lastError,
            playInfo = playInfo,
            currentQuality = currentQuality,
            cloudResumeMillis = cloudResumeMillis,
            queue = QueueState(
                current = currentItem(),
                items = queueItems,
                // **随机播放下这个数字是"列表里的第几条",不是"播放顺序里的第几个"。**
                // 列表本身不重排(随机只改播放顺序),高亮跟着滚动 —— 那么这一格跟着列表走才
                // 对得上眼睛看到的位置,而且开关随机时它不会平白跳一下。
                positionInQueue = if (player.mediaItemCount > 0) {
                    player.currentMediaItemIndex + 1
                } else {
                    0
                },
                size = player.mediaItemCount,
                shuffled = player.shuffleModeEnabled,
                canPrevious = player.hasPreviousMediaItem(),
                canNext = player.hasNextMediaItem(),
                sourceLabel = sourceLabel,
                source = queueSource,
                enriching = queueEnriching,
                incomplete = queueIncomplete,
            ),
        )
    }

    /**
     * 起播链路只在这一处收尾,收完就把引用清掉 —— 同一条链路 end 两次会打出两行,而这些行
     * 是拿来互相对比的。
     *
     * 首帧是这条链路真正的终点,但**听视频关掉视频轨之后不会有首帧**,起播失败和被下一次
     * 打开顶掉也走不到那里。所以那几处各自带着自己的终点事件来收,免得链路永远不落地。
     */
    private fun finishOpenChain(event: String) {
        val chain = openChain ?: return
        openChain = null
        chain.mark(event)
        chain.end()
    }

    /**
     * 正在放什么,给界面用。
     *
     * **通知栏不走这里**:队列项自己带着 `mediaMetadata`(见 [toMediaItem]),MediaSession
     * 直接从 playlist 读得到 —— 服务不再需要覆写 `getMediaMetadata` 往里喂。直播的那一份
     * 同样挂在它自己的条目上。
     */
    private fun nowPlaying(): NowPlaying? = currentItem()?.let {
        NowPlaying(title = it.title, subtitle = it.upName, coverUrl = it.coverUrl)
    }

    private inner class PlayerListener : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            // 真的播出声了才算这条链路是好的。清零放在 load() 里是不够的:装载成功、解码失败
            // 的组合会让每一条都先清零再失败,退避的档位永远停在第一级。
            if (playbackState == Player.STATE_READY) {
                openChain?.mark("ready")
                failedAttempts = 0
                lastError = null
            }
            emitPositionTick()
            if (playbackState != Player.STATE_ENDED) {
                publishState()
                return
            }
            // 播到底了。位置此刻就是时长,写下去之后 [isWatchedToEnd] 认得出它 —— 不需要为
            // "看完"单独存一个标记,也就不会出现标记和位置各说各话。
            persistCachedProgress()
            // 完播上报,**但不关会话**:这一条还装在播放器里,用户按下播放或者拖回去还能接着
            // 看,而关掉的会话是死的,那之后的位置一个字都报不出去。会话在内容离开时才关。
            progressSession?.onCompleted()
            // 走到 ENDED 有两种可能:队列走完了,或者被 [pauseAtEndOfMediaItems] 拦在了这一条
            // 的末尾(关掉自动连播、或者定时器设的是"播完这条")。三种都是"这次听完了",
            // 所以都归到停。
            //
            // **手动的下一条不受影响** —— 那走 `seekToNextMediaItem`,是用户当场表达的意思。
            sleepTimer.onItemFinished()
            stopPlayback()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 真停下来了就把本地副本的进度写下去。**缓冲造成的 isPlaying=false 不写** ——
            // 那时 playWhenReady 还立着,而缓冲一分钟能有好几次,每次写下的位置和上一次没区别。
            if (!isPlaying && !player.playWhenReady) persistCachedProgress()
            emitPositionTick()
            // 暂停本身不发,恢复才补一条(设计文档「决定 3」的表)。
            if (isPlaying) {
                progressSession?.onResumed(player.currentPosition, playerDurationMillis())
                startTicking()
            } else {
                tickJob?.cancel()
            }
            publishState()
        }

        /**
         * 倍速变了。刻度自己带锚点(见 [PositionTick]),所以这一条要立刻发出去 —— 弹幕时钟
         * 正是靠新锚点才不会把新倍速追认到已经过去的那段时间上。
         */
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            emitPositionTick()

        /**
         * 队列换到了另一条。
         *
         * **判据是下标变了,不是变化的原因。** 自动连播、通知栏按下一条、点队列里的某一条,
         * 三条路给出的 reason 不同(AUTO_TRANSITION 与 SEEK)而要做的事完全一样;照原因分支
         * 就得把同一段收尾写两遍,漏掉一条的表现是"从通知栏切走的那一条进度没记上"。
         *
         * 上一条的最终位置要用 [oldPosition] 里那个,不能现问播放器 —— 这一刻
         * `currentPosition` 已经属于下一条了,写下去就是把新的一条的 0 秒记成上一条的进度。
         *
         * **原因只在一处用得上**:`AUTO_TRANSITION` 是播放器自己走到了下一条,也就是上一条
         * 播完了,那一次定格上报要发 `played_time=-1`。收尾本身两种原因完全一样。
         */
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            emitPositionTick()
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) {
                // 同一条里的跳转:落点已经确认,立刻上报并把节流基准挪到这里。
                progressSession?.onSeeked(newPosition.positionMs, playerDurationMillis())
                return
            }
            persistCachedProgress(oldPosition.positionMs)
            closeProgressSession(
                atMillis = oldPosition.positionMs,
                completed = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
            )
            // 换条了,上一条的画质清单和续播提示都不再属于现在这一条。
            playInfo = null
            currentQuality = 0
            loadedLocalCopy = false
            cloudResumeMillis = null
            resolvingMediaId = newPosition.mediaItem?.mediaId
            publishState(loading = true)
            // 下一条多半在这之前几十秒就解析好了(见 [resolvedItems]),现在才轮到它落地。
            adoptResolved(newPosition.mediaItem?.mediaId)
        }

        /** 画面出来了才算这次打开走完。音频先出声,但用户等的是这一帧。 */
        override fun onRenderedFirstFrame() = finishOpenChain("firstFrame")

        override fun onPlayerError(error: PlaybackException) {
            // 直链可能在播放途中过期(403),这属于"被吞掉的失败":不留日志的话表现只是
            // 忽然不动了。
            BiliLog.w("播放出错 id=$loadedMediaId code=${error.errorCode}", error)
            resolvingMediaId = null
            // 直播落后于滑动窗口:旧分片被 CDN 丢掉,播放头指着的位置已经不在流里了。文档给的
            // 处理就是跳回窗口默认位置再 prepare,不必重新取流,也不该记进失败次数——这是直播
            // 正常运行的一部分。**只有 HLS 会走到这里**,FLV 是 progressive 流,没有窗口。
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                BiliLog.w("落后于直播窗口,跳回最前沿")
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            // 下播了就停:退避重试解决的是"暂时不通",而这一种等多久都不会好。
            val ended = generateSequence(error.cause) { it.cause }.any { it is LiveEndedException }
            if (ended) {
                lastError = getString(R.string.playback_error_live_ended)
                stopPlayback()
                return
            }
            // 取流失败现在也走这条路(见 [resolveStream]),而它带着一句写给用户看的原因。
            // 拿不到就退回按错误码报 —— 解码器初始化失败一类本来就没有更好的说法。
            val reason = error.cause?.message?.takeIf { it.isNotBlank() }
                ?: getString(R.string.playback_error_decode, error.errorCode)
            retryAfterFailure(reason, playWhenReady = true)
        }
    }

    /**
     * **只剩两件事:记住"用户想不想听",以及挡住循环。**
     *
     * 上/下一条、随机、元数据原先都在这里被接管,因为队列不在播放器里 —— 播放器只装当前
     * 这一条,timeline 答不上"有没有下一条"。队列住进 playlist 之后这些问题播放器自己就能答,
     * 而且答完会发对应的 `onXxxChanged`,MediaController 那份命令缓存于是跟得上,
     * 通知栏和 app 内的按钮走同一条标准命令。
     */
    private inner class QueuePlayer(player: Player) : ForwardingPlayer(player) {

        /**
         * 外部控制器按下的播放/暂停。**这两个覆写是 [playIntent] 唯一的正门。**
         *
         * 通知栏、锁屏、耳机线控、车机都经过 MediaSession 落到这里,界面里的播放/暂停按钮走
         * MediaController 也落到这里 —— 也就是说凡是用户亲手表达"放"或"停"的地方,全都在这条
         * 路上。反过来,切后台([pauseForAppBackground])、来电避让(播放器内部处理音频焦点)、
         * 页面离开([ACTION_PAGE_LEFT])都直接动 `player`,不经过这里,于是那个 bit 不受影响。
         */
        override fun play() {
            playIntent = true
            super.play()
        }

        override fun pause() {
            playIntent = false
            super.pause()
        }

        /** 有些控制器不发 play/pause 而是直接设这个标志,两条路要给出同一个结果。 */
        override fun setPlayWhenReady(playWhenReady: Boolean) {
            playIntent = playWhenReady
            super.setPlayWhenReady(playWhenReady)
        }

        /**
         * 随机开关本身归播放器,这里只把它记成下次新建队列的初值 —— 通知栏、车机和 app 内的
         * 按钮走的是同一条命令,所以记在哪条路上都一样,记一次就够。
         */
        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
            super.setShuffleModeEnabled(shuffleModeEnabled)
            persistShuffled(shuffleModeEnabled)
        }

        /** 播完即停是产品约束(DESIGN 2.4b),循环不接受外部设置。 */
        override fun setRepeatMode(repeatMode: Int) {
            if (repeatMode != Player.REPEAT_MODE_OFF) {
                BiliLog.w("不支持循环(DESIGN 2.4b),忽略 repeatMode=$repeatMode")
            }
        }
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(ACTION_OPEN_VIDEO, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_OPEN_LIVE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_PLAY_PART, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SET_QUALITY, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_RETRY, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_PAGE_LEFT, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_FLUSH_PROGRESS, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SLEEP_TIMER, Bundle.EMPTY))
                        .build()
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_OPEN_VIDEO -> openVideo(args)
                ACTION_OPEN_LIVE -> playLive(args)
                ACTION_PLAY_PART -> playPart(args.getLong(EXTRA_CID))
                ACTION_SET_QUALITY -> setQuality(args.getInt(EXTRA_QUALITY))
                ACTION_RETRY -> retryNow()
                // 暂停,但**不动 playIntent**(见 [ACTION_PAGE_LEFT])。顺手写一次本地进度:
                // 离开页面是这次观看最可能的终点。
                ACTION_PAGE_LEFT -> {
                    persistCachedProgress()
                    player.pause()
                }
                // 直播没有会话,这条命令在直播间自然什么都不做,不必判一句"是不是直播"。
                ACTION_FLUSH_PROGRESS -> progressSession?.flush()
                // 分钟数是三态里唯一带参数的那个:大于 0 即定时,[SLEEP_END_OF_ITEM] 即播完这条,
                // 其余(含缺省)即取消。用一个 Int 表达而不是再加一个布尔 extra —— 模式互斥之后
                // 两个字段能拼出的组合比模式还多,又要在这里判一次哪个说了算。
                ACTION_SLEEP_TIMER -> sleepTimer.start(
                    when (val minutes = args.getInt(EXTRA_SLEEP_MINUTES, SLEEP_TIMER_OFF)) {
                        SLEEP_END_OF_ITEM -> SleepTimerMode.EndOfItem
                        in 1..Int.MAX_VALUE -> SleepTimerMode.After(minutes)
                        else -> SleepTimerMode.Off
                    },
                )

                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        /** 打开一条视频。幂等,见 [openVideo]。 */
        const val ACTION_OPEN_VIDEO = "dev.bilby.OPEN_VIDEO"

        /** 打开一个直播间。幂等,见 [playLive]。 */
        const val ACTION_OPEN_LIVE = "dev.bilby.OPEN_LIVE"

        /** 换分 P。 */
        const val ACTION_PLAY_PART = "dev.bilby.PLAY_PART"

        /** 切清晰度。服务重取并停在原位置。 */
        const val ACTION_SET_QUALITY = "dev.bilby.SET_QUALITY"

        /** 见 [retryNow]。 */
        const val ACTION_RETRY = "dev.bilby.RETRY"

        /**
         * 播放页离开了组合。**暂停,但不动 [playIntent]。**
         *
         * 页面原先在 onDispose 里直接 `controller.pause()`,而那条路和用户按下暂停键是同一条
         * (都落到 [QueuePlayer.pause]),于是服务分不出"他不想听了"和"他去看别的了"——回到
         * 页面时只能一律停着。
         *
         * 分成两条命令而不是让服务去猜,是因为服务确实猜不出来:它看到的只是一次 pause。谁按的
         * 这个信息只有发命令的那一方有。
         */
        const val ACTION_PAGE_LEFT = "dev.bilby.PAGE_LEFT"

        /**
         * 把这次观看的当前位置立刻报上去,见 [ProgressSession.flush]。
         *
         * **和 [ACTION_PAGE_LEFT] 分开两条命令**,尽管现在的调用点是同一处 onDispose:后者
         * 早已不只在离开页面时发——打开发弹幕的输入层也发它,取的是"暂停,但不动 playIntent"
         * 那半层意思(见 `VideoScreen` 的 `openDanmakuInput`)。并进去的话每开一次输入层就多
         * 一条心跳,而那时用户明摆着还在这一页上。
         *
         * 命令里不带位置:位置归播放器,而播放器归服务。页面能提供的只有时机。
         */
        const val ACTION_FLUSH_PROGRESS = "dev.bilby.FLUSH_PROGRESS"

        const val ACTION_SLEEP_TIMER = "dev.bilby.SLEEP_TIMER"

        /** 分钟数,或下面两个哨兵之一。三种定时模式互斥,所以只需要这一个字段。 */
        const val EXTRA_SLEEP_MINUTES = "minutes"

        /** 播完当前这条就停,不设时长。 */
        const val SLEEP_END_OF_ITEM = -2

        /** 取消定时,也是 [EXTRA_SLEEP_MINUTES] 缺省时的取值。 */
        const val SLEEP_TIMER_OFF = -1

        const val EXTRA_BVID = "bvid"

        /** 要换到哪一 P,只属于 [ACTION_PLAY_PART]。**打开视频那条命令不带它**,见 [openVideo]。 */
        const val EXTRA_CID = "cid"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_TITLE = "title"
        const val EXTRA_UP_NAME = "upName"
        const val EXTRA_COVER_URL = "coverUrl"
        const val EXTRA_ROOM_ID = "roomId"

        /** 要哪一档。0 表示页面还没拿到档位清单,由服务按默认档要。见 [MediaItem.liveQn]。 */
        const val EXTRA_LIVE_QN = "liveQn"

        /**
         * 这个直播间只要声音。**它和 [EXTRA_LIVE_QN] 一样是装载参数**:值变了就重新取一次流,
         * 没变的那些趟只更新元数据(见 [playLive])。
         */
        const val EXTRA_LIVE_ONLY_AUDIO = "liveOnlyAudio"

        /**
         * 同一条最多试几次(含第一次)。3 次意味着最坏等 1 + 2 = 3 秒后放弃 —— 再多几档,
         * 一条已经删掉的视频要让人干等十几秒才等来那句"播不了"。
         */
        private const val MAX_ATTEMPTS = 3

        /** 退避的第一档,之后每次翻倍:1s、2s。 */
        private const val RETRY_BASE_DELAY_MILLIS = 1_000L

        /** 见 [startTicking]。 */
        private const val POSITION_TICK_INTERVAL_MILLIS = 500L

        @Volatile
        private var runningService: AudioPlaybackService? = null

        /**
         * 允不允许离开应用后继续播。**只有听视频算数**,看视频退到后台就该停。
         *
         * 放在这里而不是走 `SessionCommand`:自定义命令是异步投递的,而 [pauseForAppBackground]
         * 由 `Activity.onStop` 同步调用 —— "打开听视频后立刻锁屏"这条最常见的路径上,命令很
         * 可能还没到,服务读到的仍是 false,刚开的听视频当场被摁停。同进程的一个 volatile
         * 字段没有这个窗口,写完立刻可见。
         *
         * 这是策略,不是播放控制:播放控制(播/停/切/跳)一律仍走 MediaController。
         */
        @Volatile
        var backgroundPlaybackAllowed: Boolean = false
            private set

        fun setBackgroundPlaybackAllowed(allowed: Boolean) {
            backgroundPlaybackAllowed = allowed
        }

        /**
         * 应用退到后台。看视频就暂停,听视频继续。
         *
         * 这条路由确实绕过了 MediaController:要表达的东西在 MediaController 的命令集里没有
         * 对应项,而服务与 UI 同进程。服务在这里操作的是它自己的播放器,不是外部越过 session
         * 去控制它。
         */
        fun pauseForAppBackground() {
            runningService?.pauseForAppBackground()
        }

        private val _state = MutableStateFlow(AudioPlaybackUiState())

        /** UI 观察这个;控制动作走 MediaController,不要反过来改它。 */
        val state: StateFlow<AudioPlaybackUiState> = _state.asStateFlow()

        private val _positionTicks = MutableStateFlow(PositionTick())

        /**
         * 播放位置的权威读数,由服务这一侧发出,见 [PositionTick]。
         *
         * **和 [state] 分开一条流**:它每半秒变一次,并进 [state] 的话每一个读播放状态的
         * 组合都会跟着重组。读它的是弹幕时钟和弹幕分段拉取,两者要的都是位置本身。
         */
        val positionTicks: StateFlow<PositionTick> = _positionTicks.asStateFlow()

        private val _sleepTimerState = MutableStateFlow(SleepTimerState())
        val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()

        /**
         * 播放器的生命周期到此为止。
         *
         * 队列从第一个播放页打开开始存在,到 backstack 上再没有播放页为止 —— 调用方是
         * MainActivity,判据是"还有没有播放页",不是"这一页是被弹出还是被覆盖"。
         * 后者是导航层的判断,CLAUDE.md 记着它被做错过。
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, AudioPlaybackService::class.java))
        }

        /** UI 用它建 MediaController(播放/暂停/上下条/随机/定时/打开视频都走 controller)。 */
        fun sessionToken(context: Context): SessionToken =
            SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
    }
}
