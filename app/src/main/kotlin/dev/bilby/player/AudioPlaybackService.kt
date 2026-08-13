package dev.bilby.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.bilby.BilbyApplication
import dev.bilby.BiliLog
import dev.bilby.PerfTrace
import dev.bilby.R
import dev.bilby.api.BiliResult
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
 * 源是队列时才有的那部分。**不是队列源时整个为 null**,而不是"一个空队列" —— 两者在界面上
 * 要表达的东西不同:空队列是"还没打开过东西",null 是"现在放的东西压根没有下一条"。
 */
data class QueueState(
    /** 队列当前这一条。 */
    val current: QueueItem? = null,
    /** 正在播的分 P。队列装的是视频,cid 是"这条视频放到哪一 P"。 */
    val currentCid: Long = 0,
    /** 队列内容,自然顺序(随机只改播放顺序,不改列表怎么摆)。 */
    val items: List<QueueItem> = emptyList(),
    /** 1-based,直接显示。队列空时为 0。 */
    val positionInQueue: Int = 0,
    val size: Int = 0,
    val shuffled: Boolean = false,
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
 * 正在播的直播间。房间号是它的身份,元数据来自房间详情而不是队列。
 *
 * [qn] 带着是为了重试:断流后要重新取一次流地址,不带的话只能按默认档要,画质会在用户
 * 没动过的情况下自己跳一档。
 */
internal class LiveSource(val roomId: Long, val qn: Int, val nowPlaying: NowPlaying)

data class AudioPlaybackUiState(
    /** 正在放什么。没打开过任何东西时为 null。 */
    val nowPlaying: NowPlaying? = null,
    /**
     * **播放器此刻真正装着的东西的标识**,与 [QueueState.current] 不是一回事:队列在收到打开
     * 命令的那一刻就指向新视频了,而播放器要等取流回来才切过去,这中间画面上还是上一条的
     * 最后一帧。
     *
     * 播放页据此决定挂画面还是画占位。用队列那一条来判会把上一条视频的残帧当成本页的画面。
     * 视频源填 bvid;别的源填自己的标识,格式由源自己定,调用方只做相等比较。
     */
    val loadKey: String? = null,
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
    /** 源是队列时非空。见 [QueueState]。 */
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
 * **播完即停**:[PlaybackQueue.next] 返回 null 时只是暂停,不循环、不从任何地方续接下一条。
 * 允许连播的前提是集合有限且由用户显式选定,续接推荐池就等于恢复了被禁的自动连播。
 *
 * **逐条取流**:播到某条时才调 [VideoRepository.getPlayUrl]。playurl 给的是带时效的 CDN
 * 直链,一次性把整个队列的地址取好,排在后面的那些等轮到时早就过期了,表现为播到某条突然
 * 403 而前面几条都正常——这种失败很难归因。这里也不做预取,理由同上:预取越早,过期风险越大。
 */
@UnstableApi
class AudioPlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var videoRepository: VideoRepository

    /** 只为了问"上次播到哪一 P" —— 那个字段只有 `x/player/wbi/v2` 有,而它归这个仓库。 */
    private lateinit var subtitleRepository: SubtitleRepository

    /** 只为了断流后重新取一次直播地址,见 [retryLive]。 */
    private lateinit var liveRepository: LiveRepository
    private lateinit var queueSourceRepository: QueueSourceRepository

    /** 只为了在取流之前问一句"这一条缓存过没有",见 [playCurrent]。 */
    private lateinit var offlineStore: OfflineStore
    private lateinit var settings: SettingsStore
    private lateinit var sleepTimer: SleepTimer
    private var session: MediaSession? = null

    private var queue = PlaybackQueue(emptyList())
    private var sourceLabel = ""
    private var queueSource: QueueSource? = null

    /**
     * 正在播的直播间。**非空时播放器装的是直播流,与 [queue] 互斥** —— 直播是单条无限流,
     * 没有"播完下一条",塞进队列会把"有界集合播完即停"那条约束弄坏,通知栏的上/下一条也会
     * 指向上一段视频。
     */
    private var live: LiveSource? = null
    private var prepareJob: Job? = null

    /**
     * 队列补全。**和取流那条完全分开**:起播不等它,它失败也只是队列短一格,不影响正在播的
     * 这一条。合在 [prepareJob] 里的话,取消一个就取消了另一个。
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

    /** 当前装进播放器的是哪一条视频的哪一 P。幂等判断看它。 */
    private var loadedBvid: String? = null
    private var loadedCid: Long = 0

    /**
     * 续播时被替换掉的那一 P。页面稍后会用它再发一遍打开命令(它只知道详情里的默认 cid),
     * 那一趟必须当作"没有换 P 的意思",否则刚接上的进度立刻被推回第一 P。
     */
    private var resumedPartBvid: String? = null
    private var resumedFromCid: Long = 0

    /**
     * 此刻放的是这条视频的**本地副本**。命中缓存时置上,装在线流或切到直播时清掉。
     *
     * 它只有一个用处:让页面随后送来的那一趟默认 cid 不要把本地副本顶掉(见 [openVideo])。
     * 不并进 [resumedFromCid] 那套,是因为那套要求预先知道页面会送来哪个 cid,而离线时
     * 拿不到详情,也就拿不到那个值。
     */
    private var offlineBvid: String? = null

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

        player = PlayerFactory.createPlayer(this).apply {
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
        scope.launch { sleepTimer.state.collect { _sleepTimerState.value = it } }
        scope.launch { settings.playbackPrefs.collect { autoNextEnabled = it.autoNext } }

        session = MediaSession.Builder(this, QueuePlayer(player))
            .setCallback(SessionCallback())
            .build()
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
        prepareJob?.cancel()
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
        _sleepTimerState.value = SleepTimerState()
        super.onDestroy()
    }

    /**
     * 播放页打开了一条视频。
     *
     * **这条命令是幂等的**,而且是结构性的幂等:它报的是 bvid,不是流地址。队列当前就是这条、
     * 播放器也正装着它时直接返回 —— 转屏、退出全屏、从听视频退回、通知栏切过一条之后再回到
     * 界面,走的都是这条分支。原先页面交的是流地址,"是不是同一次播放"只能靠字符串相等去猜,
     * 而 playurl 每次签名都不同,于是重试还得专门加一个标志位去绕过那道比较。
     *
     * 队列里已经有这条(合集里换一集、点队列中的一条)就跳过去;没有就**先装一份只有这条的
     * 临时队列并立刻起播**,真正的来源(合集,或退到 UP 投稿,DESIGN 2.4b)由 [enrichQueue]
     * 在后台补上。
     *
     * 起播曾经压在建队列后面。建队列要拉一次视频详情、再二分探测空间投稿(约 log2(页数) 次
     * 请求),这些都是"这条视频属于哪个集合"的元数据,和"这条视频怎么放出声"没有关系 ——
     * 每一次点开都要先等完一轮它们。队列仍然是唯一真相,只是它先短一格。
     */
    /**
     * 打开一个直播间。
     *
     * 和 [openVideo] 一样是幂等的,报的是房间号而不是流地址 —— 页面拿到房间详情后会再发一遍
     * 带标题的命令,那一趟只该更新元数据,不该把刚起好的流掐掉重来。
     *
     * **直播不进队列。** 队列在这里被清空,理由见 [live]:留着的话通知栏的上/下一条还指着
     * 上一段视频,按下去就从直播间跳走了。
     */
    private fun playLive(args: Bundle) {
        val url = args.getString(EXTRA_LIVE_URL).orEmpty()
        val roomId = args.getLong(EXTRA_ROOM_ID)
        if (url.isEmpty() || roomId == 0L) {
            BiliLog.w("OPEN_LIVE 缺 url 或 roomId,忽略")
            return
        }
        val next = LiveSource(
            roomId = roomId,
            qn = args.getInt(EXTRA_LIVE_QN),
            nowPlaying = NowPlaying(
                title = args.getString(EXTRA_TITLE).orEmpty(),
                subtitle = args.getString(EXTRA_UP_NAME).orEmpty(),
                coverUrl = args.getString(EXTRA_COVER_URL).orEmpty(),
            ),
        )
        if (live?.roomId == roomId && player.playbackState != Player.STATE_IDLE) {
            live = next
            publishState()
            return
        }

        prepareJob?.cancel()
        retryJob?.cancel()
        enrichJob?.cancel()
        failedAttempts = 0
        lastError = null
        live = next
        queue = PlaybackQueue(emptyList())
        sourceLabel = ""
        queueSource = null
        queueEnriching = false
        queueIncomplete = false
        loadedBvid = null
        loadedCid = 0
        offlineBvid = null
        // 清晰度菜单读的是视频那套 playInfo,直播的档位不同源,留着上一条视频的会给出一个
        // 点了没用的菜单。
        playInfo = null
        currentQuality = 0

        player.setMediaSource(PlayerFactory.createLiveMediaSource(url))
        player.prepare()
        player.playWhenReady = true
        publishState(loading = true)
    }

    private fun openVideo(args: Bundle) {
        val bvid = args.getString(EXTRA_BVID).orEmpty()
        // 从直播间回到视频:两种源互斥,先把直播那份摘掉,否则元数据和 loadKey 还指着房间。
        live = null
        if (bvid.isEmpty()) {
            BiliLog.w("OPEN_VIDEO 没带 bvid,忽略")
            return
        }
        val cid = args.getLong(EXTRA_CID)

        if (queue.current()?.bvid == bvid) {
            // **这一趟多半是来送元数据的。** 页面拿到 bvid 就发了第一遍命令(那时它还不知道
            // 这条视频叫什么),详情回来再发第二遍 —— 落到的就是这里。不采纳的话通知栏和队列
            // 面板上这条永远没有标题和封面。
            queue.fillCurrentMetadata(
                title = args.getString(EXTRA_TITLE).orEmpty(),
                upName = args.getString(EXTRA_UP_NAME).orEmpty(),
                coverUrl = args.getString(EXTRA_COVER_URL).orEmpty(),
            )
            // 换 P 才需要动播放器,否则只重发状态。
            //
            // **起播还在飞的时候一律不动。** 第二遍命令带的 cid 正是服务自己会从同一份详情里
            // 取到的那个,而此刻 loadedCid 还停在上一条视频上,照着它判就成了"要换 P" ——
            // 结果是把刚发出去的 playurl 取消掉重来一遍,起播反而更慢。真正的换 P 走的是
            // ACTION_PLAY_PART,不经过这里。
            //
            // **续播换过 P 之后,这条命令带的 cid 会把它换回去。** 页面手上只有详情里的默认
            // cid(P1),而服务已经按观看记录切到了第 7 P;照下面这个判断,P1 ≠ loadedCid,
            // 于是"换 P"回 P1,续播白做了。所以记下续播时被替换掉的那个 cid,来自页面的
            // 同一个值直接忽略。用户真的手动切 P 走的是 ACTION_PLAY_PART,不经过这里。
            val preparing = prepareJob?.isActive == true
            // **正在放本地副本时,来自页面的 cid 一律忽略。** 页面手上只有详情里的默认 cid
            // (通常是 P1),而缓存的可能是别的那一 P;照下面那个判断就成了"要换 P",于是把
            // 刚起好的本地副本顶掉、改走网络 —— 缓存看起来"播了一下又跳回去"。
            //
            // 这一档不能复用 `resumedFromCid`:那套要求预先知道页面会送来哪个 cid,而那个值
            // 只有联网拿到详情才有,离线时根本拿不到。真正的换 P 走 ACTION_PLAY_PART,
            // 不经过这里,所以忽略掉是安全的。
            val playingLocalCopy = bvid == offlineBvid
            val isSupersededDefault =
                playingLocalCopy || (bvid == resumedPartBvid && cid == resumedFromCid)
            if (cid != 0L && cid != loadedCid && !preparing && !isSupersededDefault) {
                playPart(cid)
            } else {
                // 回到这一页了。播放器停着而 [playIntent] 还立着,说明上次停下不是用户的意思
                // (多半是离开页面去看别的),接着播。**这一句就是"非本意的停止,回来续播"的
                // 全部实现** —— 它不需要知道自己是被弹回来的还是被重新露出来的,那两个问题
                // 正是之前三次尝试栽进去的地方。
                if (playIntent && !player.playWhenReady) player.playWhenReady = true
                publishState()
            }
            // 上一次补全失败就停在了单条队列上。这条命令在每次回到播放页时都会再发一遍,
            // 拿它当重试点,不必为此单开一条命令和一个按钮。
            if (queueIncomplete) enrichQueue(bvid)
            // 回到这一页也要重新核对一次云端进度,理由见 [reconcileIfLocalCopy]。
            // **这一趟是唯一的机会**:播放器还装着这一条,下面那些装载路径一条都走不到。
            reconcileIfLocalCopy(bvid)
            return
        }
        if (queue.seekToBvid(bvid) != null) {
            playCurrent(resumePart = true)
            return
        }

        finishOpenChain("superseded")
        openChain = PerfTrace.chain("openVideo").also { it.mark("command") }

        // 临时队列用命令里带着的东西现造,**带多少算多少**:页面在拿到详情之前就发第一遍
        // 命令了,那时它手里只有 bvid。缺的 cid 由 playCurrent 用详情补,标题和封面由第二遍
        // 命令补(见上面的幂等分支)。
        queue = PlaybackQueue(
            listOf(
                QueueItem(
                    bvid = bvid,
                    cid = cid,
                    title = args.getString(EXTRA_TITLE).orEmpty(),
                    upName = args.getString(EXTRA_UP_NAME).orEmpty(),
                    coverUrl = args.getString(EXTRA_COVER_URL).orEmpty(),
                    durationSeconds = 0,
                )
            )
        )
        sourceLabel = ""
        queueSource = null
        openChain?.mark("tempQueue")

        playCurrent(resumePart = true)
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
     * 替换本身按 bvid 定位当前项(见 [PlaybackQueue.replaceKeeping]),不依赖来源给的下标:
     * 定位不到时来源会降级成"从最新 N 条开始",那份列表里根本没有这条视频。
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
            if (generation != openGeneration || queue.current()?.bvid != bvid) {
                // 不动 queueEnriching:此刻它属于顶掉这次的那一轮补全。
                chain.mark("stale")
                chain.end()
                return@launch
            }
            queueEnriching = false
            if (built == null || !queue.replaceKeeping(bvid, built.items)) {
                // 宁可只有一条,也不能换上一份不含这条视频的队列:那会让页面带来的 cid 落到
                // 别人头上,playurl 回 -404「啥都木有」,而队列界面高亮的是第三条。
                BiliLog.w("队列补全失败或来源里没有当前视频,留在单条队列 bvid=$bvid")
                queueIncomplete = true
                chain.mark("failed")
                chain.end()
                publishQueueChange()
                return@launch
            }
            sourceLabel = built.sourceLabel
            queueSource = built.source
            queue.setShuffled(settings.playbackPrefs.first().shuffled)
            chain.count("items", queue.size.toLong())
            chain.end()
            publishQueueChange()
        }
        // 补全在飞这件事本身要发出去:上面 playCurrent 发的那一份还是"队列只有一条"。
        publishQueueChange()
    }

    /**
     * 本地有完整副本时,队列就是**整个缓存库**(owner 定)。
     *
     * 判据和 [playCurrent] 挑本地副本用的是同一条:盘上有这条视频的完整副本。两处必须一致 ——
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
        if (cached.none { it.bvid == bvid }) return null
        return QueueBuildResult(
            items = cached.map {
                QueueItem(
                    bvid = it.bvid,
                    cid = it.cid,
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

    /**
     * 取当前这条的流并播。失败不往下跳,退避后重试同一条,见 [retryAfterFailure]。
     *
     * [force] 是重试和切清晰度用的:那两种情况下播放器装着的还是这一条,不强制就会走
     * "已经是它了"的捷径。重试要连取流一起重来 —— 直链过期(403)正是最常见的那种失败,
     * 不重取一定还是失败。
     *
     * [positionOverrideMillis] 非 null 时用它当起播位置(切清晰度要停在原地),否则用
     * 服务端的续播点。本地不另存进度,续播只认服务端那一份(DESIGN 7)。
     */
    private fun playCurrent(
        playWhenReady: Boolean = true,
        force: Boolean = false,
        positionOverrideMillis: Long? = null,
        resumePart: Boolean = false,
    ) {
        // 换东西之前先把上一条的进度写下去。此刻 [offlineBvid] 和 [loadedCid] 还指着上一条,
        // 再往下一行就被顶掉了。
        persistCachedProgress()
        prepareJob?.cancel()
        retryJob?.cancel()
        // 换到新的一条就是一份新的额度;重试则要把已经失败的次数带着,否则退避永远停在第一档。
        if (!force) {
            failedAttempts = 0
            lastError = null
        }
        val item = queue.current() ?: run { stopPlayback(); return }

        // 打开一条要放的东西本身就是"我想听"。放在早退分支之前:转屏、退出全屏走的是那条,
        // 而那些同样不该把 bit 弄丢。
        playIntent = playWhenReady

        if (!force && loadedBvid == item.bvid && (item.cid == 0L || loadedCid == item.cid)) {
            player.playWhenReady = playWhenReady
            publishState()
            reconcileIfLocalCopy(item.bvid)
            return
        }

        publishState(loading = true)
        prepareJob = scope.launch {
            // **已缓存的就地播,而且这一句必须排在所有网络之前。**
            //
            // 它原先摆在补 cid 和续播分 P 后面,只越过了 playurl —— 而补 cid 走的
            // `getVideoDetail` 本身就是一次网络往返。从缓存列表点进来的队列项是现造的、没有
            // cid,于是第一步就去联网:真离线时那一步直接失败返回,本地那份一步都走不到;
            // 有网时能补出来,所以表现成"有的能播有的不能"。真机上出过。
            //
            // 因此按 **bvid** 查而不是 (bvid, cid):cid 正是那个要联网才拿得到的东西。
            // 哪一 P 由索引给(见 [OfflineStore.completedFor]),拿到之后当作这次要播的那一 P。
            offlineStore.completedFor(item.bvid, item.cid)?.let { cached ->
                queue.updateCurrentCid(cached.cid)
                // 标题/UP/封面从索引里填。**必须赶在 load() 之前**:MediaSession 是在装载那一刻
                // 来读 currentMetadata() 的,晚一步通知栏就挂着一条没有标题的媒体,而之后没有
                // 任何播放器事件会让它再读一次。离线时这也是元数据唯一的来源。
                queue.fillCurrentMetadata(cached.title, cached.upName, cached.coverUrl)
                // 画质菜单留空:本地只有下载时选的那一档,摆一个点了没用的菜单不如不摆。
                playInfo = null
                currentQuality = cached.qualityId
                val audio = offlineStore.audioFile(cached.bvid, cached.cid).takeIf { it.isFile }
                player.setMediaSource(
                    PlayerFactory.createLocalMediaSource(
                        offlineStore.videoFile(cached.bvid, cached.cid).path,
                        audio?.path,
                    ),
                )
                player.prepare()
                openChain?.mark("prepare")
                // 续播位置取索引里那份本地进度(见 [OfflineItem.watchedPositionMillis])。
                // **这里不问服务端**:问它要一次网络往返,而这条路径的全部意义就是没有网络时也能
                // 起播。放本地副本就用本地那份进度,和 PiliPlus 的分工一致。
                val startMillis = positionOverrideMillis
                    ?: resumePositionMillis(cached.watchedPositionMillis, cached.durationSeconds * 1000)
                if (startMillis > 0) player.seekTo(startMillis)
                loadedBvid = cached.bvid
                loadedCid = cached.cid
                cloudResumeMillis = null
                // 起播之后才去核对云端进度,而且是这次 prepare 的子协程:下一次 playCurrent 会
                // `prepareJob?.cancel()`,这份核对跟着一起走 —— 上一条的核对结果落到新的一条上
                // 就是串味。
                launch { reconcileCachedProgress(cached) }
                // 页面稍后还会拿着详情里的默认 cid 再发一遍打开命令(见 openVideo 的幂等分支)。
                // 缓存的可能正是别的那一 P,那一趟会把刚起好的本地副本顶掉、改走网络 —— 记下
                // "现在放的是这条视频的本地副本",让那一趟被忽略。
                offlineBvid = cached.bvid
                player.playWhenReady = playWhenReady
                publishState()
                return@launch
            }

            // 空间投稿来源的队列项没有 cid(列表接口不返回),约定由这里补:拿着 0 去取流
            // 会被服务端当成无效 cid,表现是每一条都"取流失败",队列静默空转。
            val requestedCid = item.cid.takeIf { it != 0L } ?: run {
                when (val detail = videoRepository.getVideoDetail(item.bvid)) {
                    is BiliResult.Ok -> {
                        // 顺手把展示信息补上。**必须赶在 load() 之前**:MediaSession 是在
                        // 装载那一刻来读 currentMetadata() 的,晚一步通知栏就会挂着一条没有
                        // 标题的媒体,而之后没有任何播放器事件会让它再读一次。
                        queue.fillCurrentMetadata(
                            title = detail.value.title,
                            upName = detail.value.up.name,
                            coverUrl = detail.value.coverUrl,
                        )
                        detail.value.cid
                    }
                    else -> {
                        BiliLog.w("补 cid 失败 bvid=${item.bvid}")
                        retryAfterFailure(getString(R.string.playback_error_detail), playWhenReady)
                        return@launch
                    }
                }
            }

            // 上次看到这条视频的哪一 P。**在取流之前问**:知道了才不用为错的那一 P 白取一次流。
            // 只有打开视频那一次、且这条视频真的有多 P 时才问(见 [lastPlayedPart])。
            val lastPlayedCid = if (resumePart) lastPlayedPart(item.bvid, requestedCid) else 0L
            val cid = if (lastPlayedCid != 0L && lastPlayedCid != requestedCid) {
                // 页面稍后会拿着详情里的默认 cid 再发一遍打开命令,那一趟必须当作"没有换 P
                // 的意思",否则刚接上的这一 P 立刻被推回去。
                resumedPartBvid = item.bvid
                resumedFromCid = requestedCid
                lastPlayedCid
            } else {
                requestedCid
            }
            queue.updateCurrentCid(cid)

            val prefs = settings.playerPrefs.first()
            // 当次播放里手动切过就用那一个;否则按此刻计不计费取对应的那一档。判据每次取流
            // 现算,所以出门断了 WiFi 之后**下一条**自然就降下来了,当前这条不动。
            val quality = currentQuality.takeIf { it != 0 }
                ?: prefs.defaultQualityOn(isOnMeteredNetwork())
            openChain?.mark("playurlStart")
            when (
                val result = videoRepository.getPlayUrl(
                    item.bvid,
                    cid,
                    preferredQuality = quality,
                    preferredCodecs = prefs.codec.codecIds,
                )
            ) {
                is BiliResult.Ok -> {
                    openChain?.mark("playurlEnd")
                    playInfo = result.value
                    currentQuality = quality
                    val streams = result.value.streams
                    // **秒数属于 [lastPlayedCid] 那一 P。** playurl 只给秒数不给 P
                    // (`PlayUrlDto.lastPlayCid` 真实响应不填,见 notes §8.2),所以装的不是
                    // 那一 P 时必须丢掉它 —— 否则在 P1 上会从 P7 的进度处起播。
                    val resumeMillis = when {
                        positionOverrideMillis != null -> positionOverrideMillis
                        lastPlayedCid != 0L && lastPlayedCid != cid -> 0L
                        else -> result.value.resumeAtMillisFor(cid)
                    }
                    load(
                        streams.videoUrl,
                        streams.audioUrl,
                        item.bvid,
                        cid,
                        resumeMillis,
                    )
                    player.playWhenReady = playWhenReady
                    publishState()
                }

                is BiliResult.ApiError -> {
                    BiliLog.w("取流失败 bvid=${item.bvid} code=${result.code} ${result.message}")
                    retryAfterFailure(
                        getString(R.string.playback_error_stream, result.message),
                        playWhenReady,
                    )
                }

                is BiliResult.Failure -> {
                    BiliLog.w("取流失败 bvid=${item.bvid}", result.cause)
                    retryAfterFailure(getString(R.string.playback_error_network), playWhenReady)
                }
            }
        }
    }

    private fun load(
        videoUrl: String,
        audioUrl: String?,
        bvid: String,
        cid: Long,
        startPositionMillis: Long,
    ) {
        // 走到这里就是在线流,本地副本那个标记必须清掉,否则换到别的一 P 会被当成"忽略"。
        offlineBvid = null
        player.setMediaSource(PlayerFactory.createMediaSource(videoUrl, audioUrl))
        player.prepare()
        openChain?.mark("prepare")
        if (startPositionMillis > 0) player.seekTo(startPositionMillis)
        loadedBvid = bvid
        loadedCid = cid
    }

    /**
     * 上次看到这条视频的哪一 P,0 表示不用换。
     *
     * **先看这条视频有没有多 P,再决定要不要发那次请求。** `x/player/wbi/v2` 是一次额外的
     * 网络往返,而绝大多数视频只有一 P —— 那种情况下问了也只能得到当前这一 P。详情本身走的
     * 是带缓存与并发合并的那条路径,起播时刚请求过,这一步基本是白拿。
     *
     * 拿回来的 cid 还要在 `pages` 里找得到才用:服务端的记录可能指向一条已经被 UP 删掉的
     * 分 P,照着它取流会得到 -404。
     */
    private suspend fun lastPlayedPart(bvid: String, currentCid: Long): Long {
        val detail = videoRepository.getVideoDetail(bvid) as? BiliResult.Ok ?: return 0L
        if (detail.value.pages.size <= 1) return 0L
        val lastCid = subtitleRepository.lastPlayedCid(bvid, currentCid)
        if (lastCid == 0L || lastCid == currentCid) return 0L
        if (detail.value.pages.none { it.cid == lastCid }) {
            BiliLog.w("续播记录指向的分 P 已不存在 bvid=$bvid cid=$lastCid")
            return 0L
        }
        return lastCid
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
     * 时间上赶得及:周期心跳每 5 秒才把本地位置报上去一次(BilbyPlayer 的
     * `PROGRESS_REPORT_INTERVAL_MILLIS`),而这一趟只是一次请求。不赶在它前面的话,云端那份
     * 记录会先被本地进度盖掉,提示就再也没有可比的对象了。
     *
     * 只对本地副本做:在线播放的进度本来就以服务端那份为准,没有第二份可比。
     */
    private fun reconcileIfLocalCopy(bvid: String) {
        if (bvid != offlineBvid) return
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
        if (loadedBvid != cached.bvid || loadedCid != cached.cid) return

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
     * [dev.bilby.data.resumeAtMillisFor])。[offlineBvid] 非空正是"此刻放的是本地副本"。
     *
     * `NonCancellable`:调用点都在"正要停下来"的时刻(暂停、播完、换一条),而那些时刻紧挨着
     * scope 被取消 —— 写盘是这次观看留下的唯一痕迹,不能跟着一起没。
     */
    private fun persistCachedProgress() {
        val bvid = offlineBvid ?: return
        val cid = loadedCid
        val position = player.currentPosition.coerceAtLeast(0)
        if (position <= 0) return
        scope.launch(NonCancellable) { offlineStore.recordProgress(bvid, cid, position) }
    }

    /** 跳到队列里的另一条:点队列中的一项、合集里换一集。 */
    private fun seekToBvid(bvid: String) {
        if (queue.current()?.bvid == bvid) return
        if (queue.seekToBvid(bvid) == null) {
            BiliLog.w("SEEK_TO_BVID:队列里没有 bvid=$bvid")
            return
        }
        playCurrent()
    }

    /** 换分 P。**分 P 是这条视频内部的结构,不是队列里的另一条**(CLAUDE.md),所以不动队列位置。 */
    private fun playPart(cid: Long) {
        queue.updateCurrentCid(cid)
        playCurrent(force = true)
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
        playCurrent(force = true, positionOverrideMillis = player.currentPosition.coerceAtLeast(0))
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
        val room = live
        retryJob = scope.launch {
            delay(delayMillis)
            // 直播和队列是互斥的两种源(见 [live]),重试也得分开走:直播时队列是空的,
            // [playCurrent] 第一句 `queue.current() ?: stopPlayback()` 就把直播的重试
            // 变成了"断一下就彻底停"。
            if (room != null) retryLive(room) else playCurrent(playWhenReady, force = true)
        }
    }

    /**
     * 断流后重开直播。**重新取一次流地址,不重用手上那条** —— 理由和视频那边一样:直链带
     * 时效,过期是最常见的那种失败,拿同一条地址再 prepare 一次必然还是同样的错。
     *
     * 房间已经下播时不再退避重试:那不是"暂时不通",等多久都不会好,如实说一句然后停。
     */
    private suspend fun retryLive(room: LiveSource) {
        // qn=0 是"页面还没拿到档位就发了命令",按默认档要,别把 0 原样传出去。
        val qn = room.qn.takeIf { it > 0 } ?: LiveRepository.DEFAULT_QN
        val playback = liveRepository.loadPlayback(room.roomId, qn)
        if (playback !is BiliResult.Ok) {
            retryAfterFailure(getString(R.string.playback_error_live_stream), playWhenReady = true)
            return
        }
        val url = playback.value.stream?.url?.takeIf { playback.value.isLive }
        if (url == null) {
            BiliLog.w("直播已下播 roomId=${room.roomId},不再重试")
            lastError = getString(R.string.playback_error_live_ended)
            stopPlayback()
            return
        }
        player.setMediaSource(PlayerFactory.createLiveMediaSource(url))
        player.prepare()
        player.playWhenReady = true
        publishState(loading = true)
    }

    /** 界面上"重试"按下。手动重试是一份新的额度,退避从头算起。 */
    private fun retryNow() {
        failedAttempts = 0
        lastError = null
        playCurrent(playWhenReady = true, force = true)
    }

    /**
     * 切顺序/随机。队列在服务这边,顺序的真相自然也在这边;页面那份 `shuffled` 只是
     * "下次新建队列用哪个初值"的偏好。
     *
     * 不让 UI 走 MediaController 的 `setShuffleModeEnabled`:那条路的开关值缓存在 controller
     * 里,而 [QueuePlayer] 截住了 ExoPlayer 的 shuffle、从不发 `onShuffleModeEnabledChanged`,
     * 缓存永远停在 false —— 第二次点击会被 controller 自己吃掉。
     */
    private fun setShuffled(shuffled: Boolean) {
        queue.setShuffled(shuffled)
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

    private fun publishState(loading: Boolean = false) {
        val current = queue.current()
        _state.value = AudioPlaybackUiState(
            nowPlaying = nowPlaying(),
            loadKey = live?.let { "$LOAD_KEY_LIVE_PREFIX${it.roomId}" } ?: loadedBvid,
            isPlaying = player.isPlaying,
            loading = loading,
            error = lastError,
            playInfo = playInfo,
            currentQuality = currentQuality,
            // 直播源没有队列。给一个空队列而不是 null 的话,界面分不出"没有下一条"和
            // "还没打开过东西"。
            cloudResumeMillis = cloudResumeMillis,
            queue = if (live != null) null else QueueState(
                current = current,
                currentCid = loadedCid,
                items = queue.itemsNatural(),
                positionInQueue = if (queue.size > 0) queue.currentIndex + 1 else 0,
                size = queue.size,
                shuffled = queue.shuffled,
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

    /** 通知栏与锁屏显示的元数据。流本身不带 tag,只能由队列提供。 */
    /**
     * 正在放什么。**通知栏和界面读同一份** —— 这两处曾经各自去问队列,于是"元数据从哪来"
     * 有两个答案,而队列之外的源(直播)一个都答不上。
     */
    private fun nowPlaying(): NowPlaying? = live?.nowPlaying ?: queue.current()?.let {
        NowPlaying(title = it.title, subtitle = it.upName, coverUrl = it.coverUrl)
    }

    private fun currentMetadata(): MediaMetadata {
        val now = nowPlaying() ?: return MediaMetadata.EMPTY
        return MediaMetadata.Builder()
            .setTitle(now.title)
            .setArtist(now.subtitle)
            .setArtworkUri(now.coverUrl.takeIf { it.isNotEmpty() }?.toUri())
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
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
            if (playbackState != Player.STATE_ENDED) {
                publishState()
                return
            }
            // 播到底了。位置此刻就是时长,写下去之后 [isWatchedToEnd] 认得出它 —— 不需要为
            // "看完"单独存一个标记,也就不会出现标记和位置各说各话。
            persistCachedProgress()
            // "播完当前这条后"睡:此时不能再切下一条,否则定时关闭形同虚设。
            if (sleepTimer.onItemFinished()) {
                stopPlayback()
                return
            }
            // 关掉自动前进就在这里停住。**只挡这一条路** —— 手动的下一条走 [ACTION_NEXT] 和
            // [QueuePlayer.seekToNextMediaItem],那是用户当场表达的意思,不该被设置挡住。
            if (!autoNextEnabled) {
                stopPlayback()
                return
            }
            if (queue.next() != null) playCurrent() else stopPlayback()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // 真停下来了就把本地副本的进度写下去。**缓冲造成的 isPlaying=false 不写** ——
            // 那时 playWhenReady 还立着,而缓冲一分钟能有好几次,每次写下的位置和上一次没区别。
            if (!isPlaying && !player.playWhenReady) persistCachedProgress()
            publishState()
        }

        /** 画面出来了才算这次打开走完。音频先出声,但用户等的是这一帧。 */
        override fun onRenderedFirstFrame() = finishOpenChain("firstFrame")

        override fun onPlayerError(error: PlaybackException) {
            // 直链可能在播放途中过期(403),这属于"被吞掉的失败":不留日志的话表现只是
            // 忽然不动了。
            BiliLog.w("播放出错 bvid=$loadedBvid code=${error.errorCode}", error)
            retryAfterFailure(
                getString(R.string.playback_error_decode, error.errorCode),
                playWhenReady = true,
            )
        }
    }

    /**
     * 播放器只装当前这一条(逐条取流的必然结果),所以"有没有下一条""随机开没开"这些
     * 得由队列回答,而不是由播放器的 timeline 回答。用 ForwardingPlayer 把这几个问题接管过来,
     * MediaSession 拿到的就是队列视角:通知栏的上/下一条按钮、耳机线控的双击三击都能用。
     *
     * 队列从打开播放页那一刻起就存在,所以这些按钮不再需要"先进听视频"才活过来。
     */
    private inner class QueuePlayer(player: Player) : ForwardingPlayer(player) {

        override fun getMediaMetadata(): MediaMetadata = currentMetadata()

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

        override fun hasNextMediaItem(): Boolean = queue.currentIndex + 1 < queue.size

        override fun hasPreviousMediaItem(): Boolean = queue.currentIndex > 0

        override fun seekToNextMediaItem() {
            if (queue.next() != null) playCurrent()
        }

        override fun seekToNext() = seekToNextMediaItem()

        override fun seekToPreviousMediaItem() {
            if (queue.previous() != null) playCurrent()
        }

        override fun seekToPrevious() = seekToPreviousMediaItem()

        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNextMediaItem())
                .addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNextMediaItem())
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPreviousMediaItem())
                .addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPreviousMediaItem())
                .build()

        override fun isCommandAvailable(command: Int): Boolean =
            availableCommands.contains(command)

        override fun getShuffleModeEnabled(): Boolean = queue.shuffled

        /** 外部控制器(通知栏、车机)走这条;app 内的开关走 [ACTION_SET_SHUFFLE],理由见 [setShuffled]。 */
        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = setShuffled(shuffleModeEnabled)

        override fun getRepeatMode(): Int = Player.REPEAT_MODE_OFF

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
                        .add(SessionCommand(ACTION_SEEK_TO_BVID, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_PLAY_PART, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SET_QUALITY, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SET_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_RETRY, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_PAGE_LEFT, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_NEXT, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_PREVIOUS, Bundle.EMPTY))
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
                ACTION_SEEK_TO_BVID -> seekToBvid(args.getString(EXTRA_BVID).orEmpty())
                ACTION_PLAY_PART -> playPart(args.getLong(EXTRA_CID))
                ACTION_SET_QUALITY -> setQuality(args.getInt(EXTRA_QUALITY))
                ACTION_SET_SHUFFLE -> setShuffled(args.getBoolean(EXTRA_SHUFFLED))
                ACTION_RETRY -> retryNow()
                // 暂停,但**不动 playIntent**(见 [ACTION_PAGE_LEFT])。顺手写一次本地进度:
                // 离开页面是这次观看最可能的终点。
                ACTION_PAGE_LEFT -> {
                    persistCachedProgress()
                    player.pause()
                }
                ACTION_NEXT -> if (queue.next() != null) playCurrent()
                ACTION_PREVIOUS -> if (queue.previous() != null) playCurrent()
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

        /** 跳到队列里的另一条(点队列项、合集换一集)。 */
        const val ACTION_SEEK_TO_BVID = "dev.bilby.SEEK_TO_BVID"

        /** 换分 P。 */
        const val ACTION_PLAY_PART = "dev.bilby.PLAY_PART"

        /** 切清晰度。服务重取并停在原位置。 */
        const val ACTION_SET_QUALITY = "dev.bilby.SET_QUALITY"

        /** 见 [setShuffled]。 */
        const val ACTION_SET_SHUFFLE = "dev.bilby.SET_SHUFFLE"

        /** 见 [retryNow]。 */
        const val ACTION_RETRY = "dev.bilby.RETRY"

        /**
         * 上/下一条。**app 内的按钮走这两条,不走 `player.seekToNext()`。**
         *
         * MediaController 会缓存一份 `availableCommands`,而那是它连接那一刻的快照:页面刚
         * 打开时队列还空着,`COMMAND_SEEK_TO_NEXT` 没被授予;之后队列建起来了,但
         * [QueuePlayer] 只是覆写了 `getAvailableCommands`,从不发 `onAvailableCommandsChanged`,
         * controller 那份缓存永远停在旧值 —— 调用被它自己静默丢掉,按钮点了没反应。
         *
         * 通知栏和耳机线控不受影响:那条路由 MediaSession 直接打到 [QueuePlayer],不经过缓存。
         */
        const val ACTION_NEXT = "dev.bilby.NEXT"
        const val ACTION_PREVIOUS = "dev.bilby.PREVIOUS"

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

        const val ACTION_SLEEP_TIMER = "dev.bilby.SLEEP_TIMER"

        /** 分钟数,或下面两个哨兵之一。三种定时模式互斥,所以只需要这一个字段。 */
        const val EXTRA_SLEEP_MINUTES = "minutes"

        /** 播完当前这条就停,不设时长。 */
        const val SLEEP_END_OF_ITEM = -2

        /** 取消定时,也是 [EXTRA_SLEEP_MINUTES] 缺省时的取值。 */
        const val SLEEP_TIMER_OFF = -1

        const val EXTRA_BVID = "bvid"
        const val EXTRA_CID = "cid"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_SHUFFLED = "shuffled"
        const val EXTRA_TITLE = "title"
        const val EXTRA_UP_NAME = "upName"
        const val EXTRA_COVER_URL = "coverUrl"
        const val EXTRA_LIVE_URL = "liveUrl"
        const val EXTRA_ROOM_ID = "roomId"

        /** 页面这一刻放的档位。断流重取时按它要,见 [LiveSource.qn]。 */
        const val EXTRA_LIVE_QN = "liveQn"

        /** [AudioPlaybackUiState.loadKey] 里直播源的前缀,和 bvid 区分开。 */
        const val LOAD_KEY_LIVE_PREFIX = "live:"

        /**
         * 同一条最多试几次(含第一次)。3 次意味着最坏等 1 + 2 = 3 秒后放弃 —— 再多几档,
         * 一条已经删掉的视频要让人干等十几秒才等来那句"播不了"。
         */
        private const val MAX_ATTEMPTS = 3

        /** 退避的第一档,之后每次翻倍:1s、2s。 */
        private const val RETRY_BASE_DELAY_MILLIS = 1_000L

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
