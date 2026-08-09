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
import dev.bilby.data.PlayInfo
import dev.bilby.data.QueueSourceRepository
import dev.bilby.data.SettingsStore
import dev.bilby.data.VideoRepository
import dev.bilby.data.resumeAtMillisFor
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
 * 播放的全部真相。三个界面(内嵌播放、全屏、听视频)都只读它、不各自持有一份。
 *
 * 队列位置是 [positionInQueue] / [queueSize],即 N / M。
 */
data class AudioPlaybackUiState(
    /** 队列当前这一条。队列为空(还没打开过任何视频)时为 null。 */
    val current: QueueItem? = null,
    /** 正在播的分 P。队列装的是视频,cid 是"这条视频放到哪一 P"。 */
    val currentCid: Long = 0,
    /**
     * **播放器此刻真正装着的那条**,与 [current] 不是一回事:队列在收到打开命令的那一刻就
     * 指向新视频了,而播放器要等取流回来才切过去,这中间画面上还是上一条的最后一帧。
     *
     * 播放页据此决定挂画面还是画占位。用队列那一条来判会把上一条视频的残帧当成本页的画面。
     */
    val loadedBvid: String? = null,
    val isPlaying: Boolean = false,
    /** 1-based,直接显示。队列空时为 0。 */
    val positionInQueue: Int = 0,
    val queueSize: Int = 0,
    val shuffled: Boolean = false,
    /** 队列内容,自然顺序(随机只改播放顺序,不改列表怎么摆)。 */
    val items: List<QueueItem> = emptyList(),
    /** 队列的来源,如"合集《x》· 共 7 集"。 */
    val sourceLabel: String = "",
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
    /**
     * 队列还在补全,现在这份队列只有正在播的这一条。**播放不等它**,所以这不是"正在加载"
     * ([loading] 说的是取流);它给队列面板用,免得那一格看起来像"这个 UP 只有一条投稿"。
     */
    val queueEnriching: Boolean = false,
    /**
     * 队列补全失败了,现在这份队列只有正在播的这一条。**播放本身是好的**,失败的只是"这条
     * 视频属于哪个集合"。摆出来是因为队列里只剩一条这件事本身看不出是"这个 UP 只有一条投稿"
     * 还是"来源没拉到",而后者重试一下往往就好了。重试点是再发一次 [ACTION_OPEN_VIDEO]。
     */
    val queueIncomplete: Boolean = false,
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
    private lateinit var queueSourceRepository: QueueSourceRepository
    private lateinit var settings: SettingsStore
    private lateinit var sleepTimer: SleepTimer
    private var session: MediaSession? = null

    private var queue = PlaybackQueue(emptyList())
    private var sourceLabel = ""
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

    private var playInfo: PlayInfo? = null
    private var currentQuality: Int = 0

    override fun onCreate() {
        super.onCreate()
        runningService = this
        val container = (application as BilbyApplication).container
        videoRepository = container.videoRepository
        queueSourceRepository = container.queueSourceRepository
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
        currentPlayer = player

        sleepTimer = SleepTimer(scope) { player.pause() }
        scope.launch { sleepTimer.state.collect { _sleepTimerState.value = it } }

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
        currentPlayer = null
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
    private fun openVideo(args: Bundle) {
        val bvid = args.getString(EXTRA_BVID).orEmpty()
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
            val preparing = prepareJob?.isActive == true
            if (cid != 0L && cid != loadedCid && !preparing) playPart(cid) else publishState()
            // 上一次补全失败就停在了单条队列上。这条命令在每次回到播放页时都会再发一遍,
            // 拿它当重试点,不必为此单开一条命令和一个按钮。
            if (queueIncomplete) enrichQueue(bvid)
            return
        }
        if (queue.seekToBvid(bvid) != null) {
            playCurrent()
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
        openChain?.mark("tempQueue")

        playCurrent()
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
            val built = queueSourceRepository.forVideo(bvid)
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
            queue.setShuffled(settings.playbackPrefs.first().shuffled)
            chain.count("items", queue.size.toLong())
            chain.end()
            publishQueueChange()
        }
        // 补全在飞这件事本身要发出去:上面 playCurrent 发的那一份还是"队列只有一条"。
        publishQueueChange()
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
    ) {
        prepareJob?.cancel()
        retryJob?.cancel()
        // 换到新的一条就是一份新的额度;重试则要把已经失败的次数带着,否则退避永远停在第一档。
        if (!force) {
            failedAttempts = 0
            lastError = null
        }
        val item = queue.current() ?: run { stopPlayback(); return }

        if (!force && loadedBvid == item.bvid && (item.cid == 0L || loadedCid == item.cid)) {
            player.playWhenReady = playWhenReady
            publishState()
            return
        }

        publishState(loading = true)
        prepareJob = scope.launch {
            // 空间投稿来源的队列项没有 cid(列表接口不返回),约定由这里补:拿着 0 去取流
            // 会被服务端当成无效 cid,表现是每一条都"取流失败",队列静默空转。
            val cid = item.cid.takeIf { it != 0L } ?: run {
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
            queue.updateCurrentCid(cid)

            val prefs = settings.playerPrefs.first()
            val quality = currentQuality.takeIf { it != 0 } ?: prefs.defaultQuality
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
                    load(
                        streams.videoUrl,
                        streams.audioUrl,
                        item.bvid,
                        cid,
                        positionOverrideMillis ?: result.value.resumeAtMillisFor(cid),
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
        player.setMediaSource(PlayerFactory.createMediaSource(videoUrl, audioUrl))
        player.prepare()
        openChain?.mark("prepare")
        if (startPositionMillis > 0) player.seekTo(startPositionMillis)
        loadedBvid = bvid
        loadedCid = cid
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
     * 切清晰度。在播放页改画质就是改全局默认(DESIGN 2 节):设置页不重复放一个画质选项,
     * 也就没有"两处能改同一件事"的问题。
     *
     * NonCancellable 落盘:切完清晰度就退出页面是常见操作,而 DataStore 的 edit 是挂起函数。
     */
    private fun setQuality(quality: Int) {
        currentQuality = quality
        scope.launch(NonCancellable) { settings.saveDefaultQuality(quality) }
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
        retryJob = scope.launch {
            delay(delayMillis)
            playCurrent(playWhenReady, force = true)
        }
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

    /** 队列走完。只暂停不停服务:用户可能想按上一条回去重听。 */
    private fun stopPlayback() {
        player.playWhenReady = false
        publishState()
    }

    private fun publishState(loading: Boolean = false) {
        _state.value = AudioPlaybackUiState(
            current = queue.current(),
            currentCid = loadedCid,
            loadedBvid = loadedBvid,
            isPlaying = player.isPlaying,
            items = queue.itemsNatural(),
            positionInQueue = if (queue.size > 0) queue.currentIndex + 1 else 0,
            queueSize = queue.size,
            sourceLabel = sourceLabel,
            shuffled = queue.shuffled,
            loading = loading,
            error = lastError,
            playInfo = playInfo,
            currentQuality = currentQuality,
            queueEnriching = queueEnriching,
            queueIncomplete = queueIncomplete,
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
    private fun currentMetadata(): MediaMetadata {
        val item = queue.current() ?: return MediaMetadata.EMPTY
        return MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.upName)
            .setArtworkUri(item.coverUrl.takeIf { it.isNotEmpty() }?.toUri())
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
            // "播完当前这条后"睡:此时不能再切下一条,否则定时关闭形同虚设。
            if (sleepTimer.onItemFinished()) {
                stopPlayback()
                return
            }
            if (queue.next() != null) playCurrent() else stopPlayback()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) = publishState()

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
                        .add(SessionCommand(ACTION_SEEK_TO_BVID, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_PLAY_PART, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SET_QUALITY, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_SET_SHUFFLE, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_RETRY, Bundle.EMPTY))
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
                ACTION_SEEK_TO_BVID -> seekToBvid(args.getString(EXTRA_BVID).orEmpty())
                ACTION_PLAY_PART -> playPart(args.getLong(EXTRA_CID))
                ACTION_SET_QUALITY -> setQuality(args.getInt(EXTRA_QUALITY))
                ACTION_SET_SHUFFLE -> setShuffled(args.getBoolean(EXTRA_SHUFFLED))
                ACTION_RETRY -> retryNow()
                ACTION_NEXT -> if (queue.next() != null) playCurrent()
                ACTION_PREVIOUS -> if (queue.previous() != null) playCurrent()
                ACTION_SLEEP_TIMER -> {
                    val minutes = args.getInt(EXTRA_SLEEP_MINUTES, SLEEP_NO_DURATION).takeIf { it > 0 }
                    sleepTimer.start(minutes, args.getBoolean(EXTRA_SLEEP_FINISH_CURRENT, false))
                }

                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    companion object {
        /** 打开一条视频。幂等,见 [openVideo]。 */
        const val ACTION_OPEN_VIDEO = "dev.bilby.OPEN_VIDEO"

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

        const val ACTION_SLEEP_TIMER = "dev.bilby.SLEEP_TIMER"
        const val EXTRA_SLEEP_MINUTES = "minutes"
        const val EXTRA_SLEEP_FINISH_CURRENT = "finishCurrentItem"

        /** [EXTRA_SLEEP_MINUTES] 缺省/不设时长时的哨兵值。 */
        private const val SLEEP_NO_DURATION = -1

        const val EXTRA_BVID = "bvid"
        const val EXTRA_CID = "cid"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_SHUFFLED = "shuffled"
        const val EXTRA_TITLE = "title"
        const val EXTRA_UP_NAME = "upName"
        const val EXTRA_COVER_URL = "coverUrl"

        /**
         * 同一条最多试几次(含第一次)。3 次意味着最坏等 1 + 2 = 3 秒后放弃 —— 再多几档,
         * 一条已经删掉的视频要让人干等十几秒才等来那句"播不了"。
         */
        private const val MAX_ATTEMPTS = 3

        /** 退避的第一档,之后每次翻倍:1s、2s。 */
        private const val RETRY_BASE_DELAY_MILLIS = 1_000L

        /**
         * 播放页渲染画面用的真实播放器。
         *
         * **为什么画面不走 MediaController**:Media3 的 MediaController 不提供
         * COMMAND_SET_VIDEO_SURFACE —— Surface 是本地对象,给不了 session 那一侧,这是
         * Media3 的已知限制,不是我们没配好。所以画面只能接在真的 ExoPlayer 上。
         *
         * **这个口子只在同进程成立**:服务没有 android:process,和 UI 在同一个进程里,拿到的
         * 是同一个对象。真跨进程时这里必然读到 null,那条路要用别的方案(比如把 Surface 送到
         * 服务侧),不在本期范围。
         *
         * 只用来渲染。播放/暂停/seek/切下一条一律走 MediaController,别拿它当控制入口——
         * 绕过 session 改状态,通知栏和耳机线控看到的就是另一份真相。
         */
        @Volatile
        var currentPlayer: ExoPlayer? = null
            private set

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
         * 这条路由确实绕过了 MediaController,理由和 `currentPlayer` 那条一样:要表达的东西
         * 在 MediaController 的命令集里没有对应项,而服务与 UI 同进程。服务在这里操作的是
         * 它自己的播放器,不是外部越过 session 去控制它。
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
