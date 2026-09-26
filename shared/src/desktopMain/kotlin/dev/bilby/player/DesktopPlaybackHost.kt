package dev.bilby.player

import dev.bilby.AppContainer
import dev.bilby.BiliLog
import dev.bilby.BvidCodec
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.data.OpenedQueue
import dev.bilby.data.PlayInfo
import dev.bilby.data.QueueContext
import dev.bilby.data.QueueFeed
import dev.bilby.data.QueueSource
import dev.bilby.data.SettingsStore
import dev.bilby.data.decodeQueueContext
import dev.bilby.data.resumeAtMillisFor
import dev.bilby.elapsedRealtimeMillis
import dev.bilby.getString
import dev.bilby.resources.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import org.openani.mediamp.PlaybackErrorCode
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlaybackException

/**
 * 桌面上的播放服务。和 Android 的 AudioPlaybackService 一样,**进程里只有一个播放器,归这里
 * 所有**:页面只读 [state]、发命令。
 *
 * 队列、队列栈、上/下一条、随机、自动连播、续取与补全的规则都照搬 Android 服务,那边的注释
 * 是这些规则的出处,这里不再复述理由。差别只在队列存在哪:mpv 一次只装一条,队列是
 * [DesktopQueue] 这份纯状态,轮到哪一条才去取那一条的流。于是"播到时才取流"在这里是自然
 * 成立的(playurl 的直链带时效,见 Android 的 LazyMediaSource),不需要延迟源那一层包装;
 * Android 上"解析早于切条"的那一整类问题(`adoptResolved`)在这里也不存在。
 *
 * 心跳、续播位置、清晰度与音质的默认档和 Android 走同一套公共规则([ProgressSession]、
 * [LoadResolver]、[resumeAtMillisFor]、SettingsStore 的播放偏好)。
 *
 * @param container 取仓库用。平台对象先于容器创建,所以这里拿的是一个延后求值的入口。
 */
class DesktopPlaybackHost(private val container: () -> AppContainer) : PlaybackHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    private val _state = MutableStateFlow(AudioPlaybackUiState())
    override val state: StateFlow<AudioPlaybackUiState> = _state.asStateFlow()

    private val _positionTicks = MutableStateFlow(PositionTick())
    override val positionTicks: StateFlow<PositionTick> = _positionTicks.asStateFlow()

    // 定时到点是"这次听完了",和按下暂停同一类,所以连 playIntent 一起清。
    private val sleepTimer = SleepTimer(scope) {
        playIntent = false
        setPlayWhenReady(false)
    }
    override val sleepTimerState: StateFlow<SleepTimerState> get() = sleepTimer.state

    /** 第一次有页面连上来时才建:mpv 的初始化要加载原生库,没打开过播放页就不必付这个代价。 */
    private var player: DesktopPlayer? = null
    private var handle: MpvPlayerHandle? = null

    private val queue = DesktopQueue()

    /** 队列栈,按帧 id 存。活帧的队列就是 [queue],别的帧存着离开时的快照。 */
    private val frames = mutableMapOf<String, Frame>()
    private var liveFrame: Frame? = null

    /** 队列里那一条是直播时,它的装载参数。直播不是帧,队列只有它一条。 */
    private var liveRoom: PlaybackCommand.OpenLive? = null

    private var enrichJob: Job? = null

    /** 每补全一次 +1,迟到的补全结果靠它作废。 */
    private var openGeneration = 0
    private var extendBeforeJob: Job? = null
    private var extendAfterJob: Job? = null

    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var progressSession: ProgressSession? = null

    /**
     * 当前装进 mpv 的是哪一条(mediaId)、哪一 P。取流回来、打开之前置上,见 [loadCurrent]。
     * 换条时不清:此刻 mpv 里已经没有东西,但 [AudioPlaybackUiState.loadKey] 与 Android 一样
     * 要等新的一条落地才换,页面在这段窗口里靠它和队列当前条对不上来画占位。
     */
    private var loadedMediaId: String? = null
    private var loadedCid: Long = 0

    /** 装着的这条视频按顺序的全部分 P,上/下一条与自动连播据此先在 P 之间走。 */
    private var loadedParts: List<Long> = emptyList()

    private var playInfo: PlayInfo? = null

    /** 当前这一条实际在放的那一档。换条清掉,换 P、重试、切清晰度沿用。 */
    private var currentQuality = 0

    /** 这次播放里手动选的画质与音质,0 表示没选过。换一份队列清掉,见 Android 的 sessionQuality。 */
    private var sessionQuality = 0
    private var sessionAudio = 0

    /** 正在取流。换条、换 P、重试时立起,打开之后或失败时落下。 */
    private var loading = false
    private var lastError: String? = null

    /** 这一条播到末尾已经收过尾,同时就是 [AudioPlaybackUiState.stoppedAtEnd]。 */
    private var endHandled = false

    /**
     * 用户还想让它响着。只有用户亲手的播放/暂停([play]/[pause])和"这次听完了"(定时到点、
     * 队列走完)改它;离开页面的暂停不改,回到页面时据此续播。见 Android 的同名字段。
     */
    private var playIntent = false

    /**
     * 正在取流、还没交给 mpv 的那一次装载要不要起播,不在取流时为 null。
     *
     * 换条时旧的一条先从 mpv 里卸掉,新的一条要等 playurl 回来才打开,这段时间里 mpv 没有
     * 播放意图可存;而用户在这段时间里按下的暂停、离开页面的暂停都得有地方落,否则取流回来
     * 照样出声。
     */
    private var pendingPlayWhenReady: Boolean? = null

    private var cloudResumeMillis: Long? = null
    private var cloudResumeCid: Long = 0

    /**
     * 桌面还不放本地副本:从缓存列表进来的视频也走在线,所以本地那一支恒为空。网络状态桌面
     * 一律当作通着(见 DesktopPlatform.network),两个探测都用默认值。
     */
    private val loadResolver by lazy {
        val app = container()
        LoadResolver(
            localCopy = { _, _ -> null },
            parts = { bvid ->
                (app.videoRepository.getVideoDetail(bvid) as? BiliResult.Ok)?.value?.let { detail ->
                    VideoParts(detail.cid, detail.pages.map { it.cid })
                }
            },
            serverPart = app.subtitleRepository::lastPlayedCid,
        )
    }

    /** 队列栈的一帧,字段含义同 Android 服务的 Frame。 */
    private class Frame(val id: String, val context: QueueContext) {
        var label = ""
        var source: QueueSource? = null
        var feed: QueueFeed? = null
        var enriching = false
        var incomplete = false
        var extendingBefore = false
        var extendingAfter = false
        var snapshot: FrameSnapshot? = null
    }

    private class FrameSnapshot(
        val items: List<QueueItem>,
        val index: Int,
        val cid: Long,
        val positionMillis: Long,
        val shuffled: Boolean,
    )

    // 弹幕时钟逐帧调这里,所以读不经 JNI 的那一份,见 DesktopPlayer.positionMillis。
    override fun currentPositionMillis(): Long? = player?.takeIf { loadedMediaId != null }?.observedPositionMillis

    /**
     * 播放器自己的音量,0..1。桌面没有应用能直接调的系统媒体音量,调的是 mpv 的软件增益;
     * 上限取 100,不用 mpv 允许的放大区间,放大会削波。
     */
    var volume: Float
        get() = ((player?.mpv?.getPropertyDouble("volume") ?: 100.0) / 100.0).toFloat().coerceIn(0f, 1f)
        set(value) {
            player?.mpv?.setPropertyDouble("volume", value.coerceIn(0f, 1f) * 100.0)
        }

    // 桌面窗口最小化不暂停,没有"后台"这回事,这两个开关无事可做。
    override fun setBackgroundPlaybackAllowed(allowed: Boolean) = Unit
    override fun pauseForAppBackground() = Unit

    override fun retainFrames(ids: Set<String>) {
        frames.keys.retainAll(ids)
        val live = liveFrame ?: return
        if (live.id in ids) return
        liveFrame = null
        enrichJob?.cancel()
        extendBeforeJob?.cancel()
        extendAfterJob?.cancel()
        publishState()
    }

    override fun stop() {
        closeProgressSession()
        loadJob?.cancel()
        tickJob?.cancel()
        enrichJob?.cancel()
        extendBeforeJob?.cancel()
        extendAfterJob?.cancel()
        sleepTimer.cancel()
        player?.mediamp?.stopPlayback()
        frames.clear()
        liveFrame = null
        liveRoom = null
        queue.set(emptyList(), 0)
        clearLoaded()
        sessionQuality = 0
        sessionAudio = 0
        loading = false
        lastError = null
        endHandled = false
        playIntent = false
        pendingPlayWhenReady = null
        _state.value = AudioPlaybackUiState()
        _positionTicks.value = PositionTick()
    }

    override fun connect(onConnected: (PlayerHandle) -> Unit): PlayerConnection {
        val handle = handle ?: run {
            val created = DesktopPlayer(scope.coroutineContext)
            player = created
            watchEnded(created)
            MpvPlayerHandle(created, this, scope).also { handle = it }
        }
        onConnected(handle)
        // 断开不关播放器:它归这里所有,页面离开只是不再看它。
        return object : PlayerConnection {
            override fun release() = Unit
        }
    }

    internal fun handle(command: PlaybackCommand) {
        when (command) {
            is PlaybackCommand.ActivateFrame -> activateFrame(command)
            is PlaybackCommand.PlayPart -> if (liveRoom == null) playPart(command.cid, command.positionMillis ?: 0)
            is PlaybackCommand.SetQuality -> setQuality(command.quality)
            is PlaybackCommand.SetAudioQuality -> setAudioQuality(command.quality)
            PlaybackCommand.Retry -> reloadThisItem()
            is PlaybackCommand.OpenLive -> playLive(command)
            // 暂停,但不动 playIntent:回到页面时要接着放。
            PlaybackCommand.PageLeft -> setPlayWhenReady(false)
            PlaybackCommand.FlushProgress -> progressSession?.flush()
            is PlaybackCommand.SetSleepTimer -> sleepTimer.start(command.mode)
            is PlaybackCommand.AdjustSleepTimer -> sleepTimer.extend(command.deltaMinutes * 60_000L)
            PlaybackCommand.RetryQueue -> liveFrame?.let { frame -> queue.current?.let { enrichFrame(frame, it.bvid) } }
            is PlaybackCommand.ExtendQueue -> extendQueue(command.before)
        }
    }

    // ---- 界面的播放控制,由 MpvPlayerHandle 转进来 ----

    internal val playWhenReady: Boolean
        get() = pendingPlayWhenReady ?: player?.mediamp?.state?.value?.playWhenReady ?: false

    /** 用户亲手按下的播放。**这是 [playIntent] 的正门**,暂停同。 */
    internal fun play() {
        playIntent = true
        setPlayWhenReady(true)
    }

    internal fun pause() {
        playIntent = false
        setPlayWhenReady(false)
    }

    /** 放或停,不碰 [playIntent]。取流途中落在 [pendingPlayWhenReady] 上。 */
    private fun setPlayWhenReady(value: Boolean) {
        if (pendingPlayWhenReady != null) {
            pendingPlayWhenReady = value
            return
        }
        val mediamp = player?.mediamp ?: return
        if (value) mediamp.play() else mediamp.pause()
    }

    internal var shuffled: Boolean
        get() = queue.shuffled
        set(value) {
            if (queue.shuffled == value) return
            queue.shuffled = value
            // 开关本身归队列,这里只把它记成下次新建队列的初值。
            container().persistScope.launch {
                val settings = container().settings
                settings.savePlaybackPrefs(settings.playbackPrefs.first().copy(shuffled = value))
            }
            publishState()
        }

    /** 跳到队列里的第 [index] 条。已经是它就只在这一条里跳,同 Media3 的 `seekTo(index, position)`。 */
    internal fun seekToQueueIndex(index: Int, positionMillis: Long) {
        if (index == queue.index) {
            handle?.seekTo(positionMillis)
            return
        }
        moveTo(index, playWhenReady, startMillis = positionMillis.takeIf { it > 0 })
    }

    /** 下一条先在分 P 之间走,见 Android 的 QueuePlayer.handleSeek。 */
    internal fun seekToNext() {
        if (liveRoom != null) return
        neighbourPart(1)?.let {
            playPart(it)
            return
        }
        queue.nextIndex()?.let { moveTo(it, playWhenReady) }
    }

    /**
     * 上一条沿用 Media3 `seekToPrevious` 的语义:播过 [MAX_SEEK_TO_PREVIOUS_MILLIS] 先回本 P
     * 开头,否则先走上一 P,再走队列里的上一条,都没有也回开头。
     */
    internal fun seekToPrevious() {
        if (liveRoom != null) return
        val position = player?.positionMillis ?: 0L
        if (position <= MAX_SEEK_TO_PREVIOUS_MILLIS) {
            neighbourPart(-1)?.let {
                playPart(it)
                return
            }
            queue.previousIndex()?.let {
                moveTo(it, playWhenReady)
                return
            }
        }
        handle?.seekTo(0)
    }

    private val canPrevious: Boolean get() = queue.previousIndex() != null || neighbourPart(-1) != null
    private val canNext: Boolean get() = queue.nextIndex() != null || neighbourPart(1) != null

    // ---- 队列栈 ----

    /** 视频页到了前台,报出自己的帧。三种情况与 Android 的 activateFrame 相同。 */
    private fun activateFrame(command: PlaybackCommand.ActivateFrame) {
        val frameId = command.frameId
        val bvid = command.bvid
        if (frameId.isEmpty() || bvid.isEmpty()) {
            BiliLog.w("ActivateFrame 缺 frameId 或 bvid,忽略")
            return
        }
        // 指名在入口取走,理由见 [PartRequest]。
        val requestedCid = container().partRequest.consume(bvid)

        val live = liveFrame
        if (live?.id == frameId) {
            resumeFrame(live, bvid, requestedCid)
            return
        }
        val context = decodeQueueContext(command.queueContext) ?: QueueContext.Affiliation
        suspendLiveFrame()
        val frame = frames.getOrPut(frameId) { Frame(frameId, context) }
        val snapshot = frame.snapshot
        if (snapshot != null) restoreFrame(frame, snapshot) else openFrame(frame, bvid, requestedCid)
    }

    /** 回到了活帧那一页。非本意的停止在这里续播,云端进度在这里核对。 */
    private fun resumeFrame(frame: Frame, bvid: String, requestedCid: Long) {
        if (playIntent && !playWhenReady) setPlayWhenReady(true)
        val current = queue.current?.bvid
        if (current != null) {
            if (current == bvid && requestedCid != 0L && requestedCid != loadedCid) playPart(requestedCid)
            reconcileCloudProgress(current)
        }
        publishState()
        if (frame.incomplete && current != null) enrichFrame(frame, current)
    }

    /** 活帧退到栈里:存下它此刻的队列、当前条、分 P 与位置。 */
    private fun suspendLiveFrame() {
        val frame = liveFrame ?: return
        liveFrame = null
        enrichJob?.cancel()
        extendBeforeJob?.cancel()
        extendAfterJob?.cancel()
        if (frame.enriching) {
            frame.enriching = false
            frame.incomplete = true
        }
        if (liveRoom != null) return
        val current = queue.current ?: return
        frame.snapshot = FrameSnapshot(
            items = queue.items,
            index = queue.index,
            cid = if (loadedMediaId == current.bvid) loadedCid else 0,
            positionMillis = player?.positionMillis ?: 0L,
            shuffled = queue.shuffled,
        )
    }

    private fun restoreFrame(frame: Frame, snapshot: FrameSnapshot) {
        frame.snapshot = null
        replaceQueue(snapshot.items, snapshot.index, snapshot.cid, snapshot.positionMillis.takeIf { it > 0 })
        queue.shuffled = snapshot.shuffled
        // 帧在队列换完之后才认领,理由同 Android 的 restoreFrame。
        liveFrame = frame
        publishState()
        if (frame.incomplete) enrichFrame(frame, snapshot.items[snapshot.index].bvid)
    }

    /** 新的一帧:先装一份只有这条的临时队列并立刻起播,来源由 [enrichFrame] 在后台补上。 */
    private fun openFrame(frame: Frame, bvid: String, requestedCid: Long) {
        val temporary = QueueItem(bvid = bvid, title = "", upName = "", coverUrl = "", durationSeconds = 0)
        val kept = replaceQueue(listOf(temporary), 0, requestedCid, startMillis = null)
        liveFrame = frame
        if (kept && requestedCid != 0L && requestedCid != loadedCid) playPart(requestedCid)
        enrichFrame(frame, bvid)
    }

    /**
     * 换一份队列,从第 [index] 条起。**mpv 正在放的就是那一条时留着它**,不重新取流,只换两边;
     * 留下的那一条保留它已经补好的标题与封面。
     *
     * @return 是否留下了正在放的那一条。
     */
    private fun replaceQueue(items: List<QueueItem>, index: Int, requestedCid: Long, startMillis: Long?): Boolean {
        val current = queue.current
        if (liveRoom != null || current == null || current.bvid != items[index].bvid) {
            setQueue(items, index)
            loadCurrent(playWhenReady = true, requestedCid = requestedCid, startMillis = startMillis)
            return false
        }
        queue.set(items.toMutableList().also { it[index] = current }, index)
        if (playIntent && !playWhenReady) setPlayWhenReady(true)
        return true
    }

    /**
     * 按帧的入口上下文建队列,补到正在播的这一条前后。结果过两道校验才用,理由同 Android 的
     * enrichFrame:generation 与活帧挡"期间又打开了别的视频",当前 bvid 挡"期间队列被上/下一条挪过"。
     */
    private fun enrichFrame(frame: Frame, bvid: String) {
        enrichJob?.cancel()
        frame.incomplete = false
        frame.enriching = true
        val generation = ++openGeneration
        enrichJob = scope.launch {
            val opened = openQueue(frame.context, bvid)
            if (generation != openGeneration || liveFrame !== frame || queue.current?.bvid != bvid) return@launch
            frame.enriching = false
            if (opened == null || !fillQueueAround(bvid, opened.items)) {
                BiliLog.w("队列补全失败或来源里没有当前视频,留在单条队列 bvid=$bvid")
                frame.incomplete = true
                publishState()
                return@launch
            }
            frame.label = opened.label
            frame.source = opened.source
            frame.feed = opened.feed
            queue.shuffled = container().settings.playbackPrefs.first().shuffled
            publishState()
            extendNearEdges()
        }
        publishState()
    }

    private suspend fun openQueue(context: QueueContext, bvid: String): OpenedQueue? {
        val repository = container().queueSourceRepository
        if (context == QueueContext.Offline) return repository.offline(bvid)
        return repository.open(context, bvid)
            ?: repository.offline(bvid)?.also {
                BiliLog.w("队列:入口来源打不开,本地有副本,退到缓存库 bvid=$bvid")
            }
    }

    /** 把来源里当前这条的前后两段插进队列,正在播的那一条原样留着。 */
    private fun fillQueueAround(bvid: String, items: List<QueueItem>): Boolean {
        val unique = items.distinctBy { it.bvid }
        val here = unique.indexOfFirst { it.bvid == bvid }
        if (here < 0) return false
        // 先插后面那段再插前面那段:先插前面会把当前下标推走,后面那段就落错位置。
        queue.insert(queue.index + 1, unique.drop(here + 1))
        queue.insert(queue.index, unique.take(here))
        return true
    }

    private fun extendNearEdges() {
        if (queue.size == 0) return
        if (queue.index < EXTEND_THRESHOLD) extendQueue(before = true)
        if (queue.size - 1 - queue.index < EXTEND_THRESHOLD) extendQueue(before = false)
    }

    /** 往队列的一头续一段。同一头同时只有一个请求在飞,整段重复时接着往下续。 */
    private fun extendQueue(before: Boolean) {
        val frame = liveFrame ?: return
        val feed = frame.feed ?: return
        if (before) {
            if (frame.extendingBefore || !feed.hasBefore) return
            frame.extendingBefore = true
        } else {
            if (frame.extendingAfter || !feed.hasAfter) return
            frame.extendingAfter = true
        }
        publishState()
        val job = scope.launch {
            val loaded = try {
                if (before) feed.loadBefore() else feed.loadAfter()
            } finally {
                if (before) frame.extendingBefore = false else frame.extendingAfter = false
            }
            if (loaded == null) {
                BiliLog.w("队列续取失败 before=$before,留待下次触发")
                publishState()
                return@launch
            }
            val added = queue.insert(if (before) 0 else queue.size, loaded)
            publishState()
            if (added == 0 && loaded.isNotEmpty()) extendQueue(before)
        }
        if (before) extendBeforeJob = job else extendAfterJob = job
    }

    /**
     * 换一份队列。上一条留下的装载状态一并作废,手动选的画质与音质到此为止。旧的一条从 mpv
     * 里卸掉,理由同 [moveTo]。
     */
    private fun setQueue(items: List<QueueItem>, startIndex: Int) {
        closeProgressSession()
        player?.mediamp?.stopPlayback()
        liveRoom = null
        playIntent = true
        queue.set(items, startIndex)
        clearLoaded()
        sessionQuality = 0
        sessionAudio = 0
    }

    // ---- 直播 ----

    /** 打开一个直播间。幂等:只有装载参数变了才重新取流,其余几趟只更新展示信息。 */
    private fun playLive(command: PlaybackCommand.OpenLive) {
        val display = QueueItem(
            bvid = liveMediaId(command.roomId),
            title = command.title,
            upName = command.upName,
            coverUrl = command.coverUrl,
            durationSeconds = 0,
        )
        val current = liveRoom
        if (current != null && current.roomId == command.roomId) {
            liveRoom = command
            fillItemDisplay(display.bvid, display.title, display.upName, display.coverUrl)
            if (current.qn != command.qn || current.onlyAudio != command.onlyAudio) {
                loadCurrent(playWhenReady = true)
                return
            }
            if (playIntent && !playWhenReady) setPlayWhenReady(true)
            publishState()
            return
        }
        // 直播不是帧。盖在视频页上面时先把那一帧存下来,返回时它换回自己的队列。
        suspendLiveFrame()
        setQueue(listOf(display), 0)
        liveRoom = command
        loadCurrent(playWhenReady = true)
    }

    // ---- 装载 ----

    /**
     * 队列换到了第 [index] 条。旧的一条到此为止:定格补发它的最终位置,从 mpv 里卸掉。
     *
     * mpv 此刻装的还是旧的那一条,所以它的位置就是旧条目的最终位置,不必像 Android 那样
     * 从切条事件里取。卸掉而不是留着放到新的一条打开:页面已经跟着队列换到新的一条上了,
     * 旧的声音再响一两秒,听起来就是按了下一条没反应。
     *
     * @param completed 自动连播走到下一条,即上一条播完了,定格上报发完播。
     */
    private fun moveTo(index: Int, playWhenReady: Boolean, startMillis: Long? = null, completed: Boolean = false) {
        if (!queue.moveTo(index)) return
        closeProgressSession(atMillis = player?.positionMillis, completed = completed)
        player?.mediamp?.stopPlayback()
        if (playWhenReady) playIntent = true
        currentQuality = 0
        loadCurrent(playWhenReady, startMillis = startMillis)
        extendNearEdges()
    }

    /**
     * 装载队列当前这一条:换条、换 P、重试、切清晰度、直播换档都走这里。
     *
     * 旧的会话先定格关掉;同一条重来时旧的流留在 mpv 里接着放,到新的流打开的那一刻才换下,
     * 切清晰度因此没有静音的空档。
     */
    private fun loadCurrent(playWhenReady: Boolean, requestedCid: Long = 0L, startMillis: Long? = null) {
        val player = player ?: return
        val item = queue.current ?: return
        loadJob?.cancel()
        closeProgressSession()
        endHandled = false
        lastError = null
        loading = true
        pendingPlayWhenReady = playWhenReady
        setCloudResume(null)
        publishState()
        val live = liveRoom
        loadJob = scope.launch {
            val resolved = if (live != null) resolveLive(live) else resolveVideo(item.bvid, requestedCid, startMillis)
            if (resolved == null) {
                pendingPlayWhenReady = null
                return@launch
            }
            // **先报 loadKey,再打开。** 页面按 loadKey 决定挂不挂画面,而 mpv 的 D3D11 输出要等
            // 画面挂上才建得起来,打开会一直等到出第一帧。反过来排就是互相等:页面在等打开
            // 完成才挂画面,打开在等画面。Android 没有这层依赖,那边 loadKey 在装载完成后才置上。
            if (item.bvid != loadedMediaId) loadedParts = emptyList()
            loadedMediaId = item.bvid
            loadedCid = resolved.cid
            playInfo = resolved.playInfo
            currentQuality = resolved.quality
            publishState()
            val play = pendingPlayWhenReady ?: playWhenReady
            pendingPlayWhenReady = null
            PlayerLog.d("desktop open id=${item.bvid} cid=${resolved.cid} q=${resolved.quality} start=${resolved.startMillis}")
            try {
                player.open(resolved.source, play, resolved.startMillis)
            } catch (failure: PlaybackException) {
                // 不接住的话协程带着异常退出,界面停在转圈上,日志里只有线程的未捕获异常。
                // 连播走到一条打不开的视频时最容易撞上。
                // 不带错误码:mediamp 的错误是枚举,序号对谁都没有意义(Android 那边报的是
                // ExoPlayer 的错误码,查得到)。是哪一种留在下面这行日志里。
                val shown = if (failure.code == PlaybackErrorCode.IO) {
                    getString(Res.string.playback_error_network)
                } else {
                    getString(Res.string.playback_error_open)
                }
                fail("打开失败 id=${item.bvid} ${failure.code} ${failure.message}", shown)
                return@launch
            }
            loading = false
            startProgressSession(item.bvid, resolved.cid)
            publishState()
            if (live == null) loadPartsOf(item.bvid)
            startTicking()
        }
    }

    private class Resolved(
        val source: StreamSource,
        val cid: Long,
        val startMillis: Long,
        val playInfo: PlayInfo?,
        val quality: Int,
    )

    /** 视频那一支:放哪一 P 交给 [LoadResolver],取流的画质按 Android 的同一套次序。失败时返回 null。 */
    private suspend fun resolveVideo(bvid: String, requestedCid: Long, startMillis: Long?): Resolved? {
        val app = container()
        val cid = when (val plan = loadResolver.resolve(bvid, requestedCid)) {
            is LoadPlan.Online -> plan.cid
            else -> {
                fail("解析不出要放哪一 P bvid=$bvid", getString(Res.string.playback_error_detail))
                return null
            }
        }
        fillDisplayFromDetail(bvid)
        val prefs = app.settings.playerPrefs.first()
        val metered = app.platform.network.isMetered()
        val quality = currentQuality.takeIf { it != 0 }
            ?: sessionQuality.takeIf { it != 0 }
            ?: prefs.defaultQualityOn(metered)
        val audio = sessionAudio.takeIf { it != 0 } ?: prefs.defaultAudioOn(metered)
        val info = when (val result = app.videoRepository.getPlayUrl(bvid, cid, quality, prefs.codec.codecIds, audio)) {
            is BiliResult.Ok -> result.value
            is BiliResult.ApiError -> {
                fail("取流失败 bvid=$bvid code=${result.code} ${result.message}", getString(Res.string.playback_error_stream, result.message))
                return null
            }
            is BiliResult.Failure -> {
                fail("取流失败 bvid=$bvid ${result.cause.message}", getString(Res.string.playback_error_network))
                return null
            }
        }
        return Resolved(
            source = StreamSource(info.streams.videoUrl, info.streams.audioUrl),
            cid = cid,
            startMillis = startMillis ?: info.resumeAtMillisFor(cid),
            playInfo = info,
            // 实际选中的那一档,不是请求的那一档,见 Android 的 resolveOnlineStream。
            quality = info.streams.qualityId,
        )
    }

    private suspend fun resolveLive(command: PlaybackCommand.OpenLive): Resolved? {
        val playback = container().liveRepository.loadPlayback(command.roomId, command.qn, command.onlyAudio)
        val url = (playback as? BiliResult.Ok)?.value?.stream?.url
        if (url == null) {
            val detail = when (playback) {
                is BiliResult.ApiError -> "${playback.code} ${playback.message}"
                is BiliResult.Failure -> playback.cause.message ?: playback.cause.javaClass.simpleName
                is BiliResult.Ok -> "没有可放的流"
            }
            fail("直播取流失败 roomId=${command.roomId} $detail", getString(Res.string.playback_error_live_stream))
            return null
        }
        // 直播没有分 P,于是也没有进度会话,见 [startProgressSession]。
        return Resolved(StreamSource(url, referer = BiliConstants.LIVE_REFERER), cid = 0, startMillis = 0, playInfo = null, quality = 0)
    }

    /**
     * [shown] 是画面上那句,原始的错误码与异常只进日志:异常的 message 是系统原文,英文、带主机名,
     * 断网时画面上就是一句 `Unable to resolve host`。文案与 Android 服务同一套。
     */
    private fun fail(logged: String, shown: String) {
        BiliLog.w("桌面播放:$logged")
        loading = false
        lastError = shown
        publishState()
    }

    /** 条目还没有标题时才去问详情(空间投稿来源的列表不带标题与 UP 名)。 */
    private suspend fun fillDisplayFromDetail(bvid: String) {
        val at = queue.indexOf(bvid)
        if (at >= 0 && queue[at].title.isNotEmpty()) return
        val detail = (container().videoRepository.getVideoDetail(bvid) as? BiliResult.Ok)?.value ?: return
        fillItemDisplay(bvid, detail.title, detail.up.name, detail.coverUrl, detail.durationSeconds)
    }

    /** 把标题、UP 名、封面补到队列项上。空值不覆盖,理由同 Android 的 fillItemDisplay。 */
    private fun fillItemDisplay(bvid: String, title: String, upName: String, coverUrl: String, durationSeconds: Long = 0) {
        val at = queue.indexOf(bvid)
        if (at < 0) return
        val existing = queue[at]
        val merged = existing.copy(
            title = title.ifEmpty { existing.title },
            upName = upName.ifEmpty { existing.upName },
            coverUrl = coverUrl.ifEmpty { existing.coverUrl },
            durationSeconds = existing.durationSeconds.takeIf { it > 0 } ?: durationSeconds,
        )
        if (merged == existing) return
        queue.update(at, merged)
        publishState()
    }

    /** 分 P 清单不随装载一起等。回来时装着的已经不是这一条就作废。 */
    private fun loadPartsOf(bvid: String) {
        scope.launch {
            val parts = when (val detail = container().videoRepository.getVideoDetail(bvid)) {
                is BiliResult.Ok -> detail.value.pages.map { it.cid }
                else -> {
                    BiliLog.w("取不到分 P 清单,切换只在视频之间 bvid=$bvid")
                    emptyList()
                }
            }
            if (loadedMediaId != bvid) return@launch
            loadedParts = parts
            publishState()
        }
    }

    /**
     * 与 [loadedCid] 相隔 [offset] 的那一 P,没有则为 null。先认队列当前条还是不是装着的那一条:
     * 换条之后、新一条落地之前,[loadedCid] 仍是上一条的。
     */
    private fun neighbourPart(offset: Int): Long? {
        if (queue.current?.bvid != loadedMediaId) return null
        val here = loadedParts.indexOf(loadedCid)
        if (here < 0) return null
        return loadedParts.getOrNull(here + offset)
    }

    /** 换分 P。分 P 是这条视频内部的结构,不动队列位置。 */
    private fun playPart(cid: Long, positionMillis: Long = 0) {
        loadCurrent(
            playWhenReady = true,
            requestedCid = cid,
            // 位置只有云端续播提示会带,见 Android 的 playPart。
            startMillis = positionMillis.takeIf { it > 0 },
        )
        // 从这一刻起哪一 P 是没有答案的,弹幕、字幕、分 P 高亮都跟着这个值走,当场发出去。
        // 排在 loadCurrent 后面:它先按旧的那一 P 把会话定格关掉。
        loadedCid = 0
        publishState()
    }

    private fun setQuality(quality: Int) {
        currentQuality = quality
        sessionQuality = quality
        persistPickIfEnabled { settings, metered -> settings.saveDefaultQuality(quality, metered) }
        reloadAtCurrentPosition()
    }

    private fun setAudioQuality(quality: Int) {
        sessionAudio = quality
        persistPickIfEnabled { settings, metered -> settings.saveDefaultAudio(quality, metered) }
        reloadAtCurrentPosition()
    }

    /** 设置里打开了"播放页的选择改写默认值"时,把这一下写进当前网络那一格。 */
    private fun persistPickIfEnabled(save: suspend (SettingsStore, metered: Boolean) -> Unit) {
        val app = container()
        val metered = app.platform.network.isMetered()
        app.persistScope.launch {
            if (app.settings.playerPrefs.first().playerPickUpdatesDefault) save(app.settings, metered)
        }
    }

    /** 原地重新取流:同一条、同一 P、同一个位置,换的是画质或音质。 */
    private fun reloadAtCurrentPosition() {
        if (liveRoom != null) return
        loadCurrent(playWhenReady = true, requestedCid = currentItemCid(), startMillis = player?.positionMillis)
    }

    /** 当前这一条原地重来,视频沿用它那一 P,直播沿用它的档位。 */
    private fun reloadThisItem() {
        loadCurrent(playWhenReady = true, requestedCid = currentItemCid())
    }

    /** 重来一遍该沿用哪一 P,答不上来时为 0(交回 [LoadResolver] 重新解析)。 */
    private fun currentItemCid(): Long =
        loadedCid.takeIf { loadedMediaId != null && loadedMediaId == queue.current?.bvid } ?: 0L

    private fun clearLoaded() {
        loadedMediaId = null
        loadedCid = 0
        loadedParts = emptyList()
        playInfo = null
        currentQuality = 0
        setCloudResume(null)
    }

    // ---- 播完、刻度、进度 ----

    /** mpv 报出播到末尾。只认这一个事件,不从状态里轮询 Ended:换条的途中状态会停在上一条的 Ended 上。 */
    private fun watchEnded(player: DesktopPlayer) {
        scope.launch {
            player.mediamp.events.collect { event ->
                if (event is PlaybackEvent.MediaEnded) onReachedEnd()
            }
        }
    }

    /**
     * 播到了这一条的末尾。次序与 Android 相同:自动连播开着、没有"播完这条"的定时、没有下一 P
     * 时走到队列里的下一条;有下一 P 先放下一 P;其余停下。
     *
     * Android 把前一种交给播放器自己换条,后几种由 `pauseAtEndOfMediaItems` 拦下来再到这里;
     * 桌面没有播放器可交,几种都在这里分。
     */
    private suspend fun onReachedEnd() {
        if (endHandled) return
        endHandled = true
        val autoNext = container().settings.playbackPrefs.first().autoNext
        // 读设置是挂起的,这期间用户可能已经按了下一条或拖回去(两者都清掉 endHandled)。
        // 那之后再往下走就是替他又跳了一条。
        if (!endHandled) return
        val endOfItem = sleepTimer.state.value.mode == SleepTimerMode.EndOfItem
        val nextPart = neighbourPart(1)
        val nextItem = queue.nextIndex()
        if (autoNext && !endOfItem && nextPart == null && nextItem != null) {
            moveTo(nextItem, playWhenReady = true, completed = true)
            return
        }
        // 完播上报,但不关会话:这一条还装着,拖回去还能接着看。
        progressSession?.onCompleted()
        if (nextPart != null && autoNext && !endOfItem) {
            playPart(nextPart)
            return
        }
        sleepTimer.onItemFinished()
        playIntent = false
        publishState()
    }

    /**
     * 半秒一格:刻度、播放状态、心跳都在这里推进。mediamp 的状态流更新间隔不固定,
     * 这里按固定节拍读一次,和 Android 服务的刻度同频。
     */
    private fun startTicking() {
        tickJob?.cancel()
        val player = player ?: return
        tickJob = scope.launch {
            var wasPlaying = false
            while (true) {
                val playing = player.mediamp.state.value.isPlaying
                val position = player.positionMillis
                val duration = player.mediamp.mediaProperties.value?.durationMillis ?: 0L
                _positionTicks.value = PositionTick(
                    positionMillis = position,
                    durationMillis = duration,
                    isPlaying = playing,
                    speed = player.speed,
                    anchorMillis = elapsedRealtimeMillis(),
                )
                progressSession?.let { session ->
                    if (playing && !wasPlaying) session.onResumed(position, duration)
                    else if (playing) session.onPosition(position, duration)
                    else if (wasPlaying) session.flush()
                }
                // 停在末尾之后又放起来了,就不再是"播完了"。
                if (playing) endHandled = false
                _state.update { it.copy(isPlaying = playing, stoppedAtEnd = endHandled) }
                wasPlaying = playing
                delay(TICK_MILLIS)
            }
        }
    }

    /** 跳转要立刻报一次,不等下一格心跳,见 [ProgressSession.onSeeked]。 */
    internal fun onSeeked(positionMillis: Long) {
        val duration = player?.mediamp?.mediaProperties?.value?.durationMillis ?: 0L
        progressSession?.onSeeked(positionMillis, duration)
        endHandled = false
        _state.update { it.copy(stoppedAtEnd = false) }
    }

    private fun startProgressSession(bvid: String, cid: Long) {
        closeProgressSession()
        // 没有 cid 的只有直播,它的 mediaId 也换不出 aid:"直播不上报"由没有会话表达。
        if (cid == 0L) return
        val aid = BvidCodec.toAid(bvid)
        if (aid <= 0) return
        val reporter = container().heartbeatReporter
        progressSession = ProgressSession(aid, cid) { playedTimeSeconds, finished, onConfirmed ->
            reporter.report(aid, cid, bvid, playedTimeSeconds, finished) { scope.launch { onConfirmed() } }
        }
    }

    private fun closeProgressSession(atMillis: Long? = player?.positionMillis, completed: Boolean = false) {
        progressSession?.close(atMillis, completed)
        progressSession = null
    }

    /** 页面重开时问一次云端在这期间有没有被别处写过,规则同 Android 的 reconcileCloudProgress。 */
    private fun reconcileCloudProgress(bvid: String) {
        if (bvid != loadedMediaId) return
        val session = progressSession ?: return
        val cid = loadedCid
        scope.launch {
            val server = container().subtitleRepository.lastPlayed(bvid, cid)
            if (bvid != loadedMediaId || cid != loadedCid || progressSession !== session) return@launch
            val elsewhere = cloudProgressWrittenElsewhere(server, session.reported)
            if (elsewhere == null && cloudResumeMillis == null) return@launch
            setCloudResume(elsewhere, cid = server?.cid ?: 0)
            publishState()
        }
    }

    private fun setCloudResume(positionMillis: Long?, cid: Long = 0) {
        cloudResumeMillis = positionMillis
        cloudResumeCid = if (positionMillis == null) 0 else cid
    }

    private fun publishState() {
        val frame = liveFrame
        val feed = frame?.feed
        _state.value = AudioPlaybackUiState(
            nowPlaying = queue.current?.let { NowPlaying(it.title, it.upName, it.coverUrl) },
            loadKey = loadedMediaId,
            currentCid = loadedCid,
            isPlaying = player?.mediamp?.state?.value?.isPlaying == true,
            loading = loading,
            error = lastError,
            playInfo = playInfo,
            currentQuality = currentQuality,
            cloudResumeMillis = cloudResumeMillis,
            cloudResumeCid = cloudResumeCid,
            stoppedAtEnd = endHandled,
            queue = QueueState(
                current = queue.current,
                items = queue.items,
                size = queue.size,
                // 随机播放下这个数字是列表里的第几条,不是播放顺序里的第几个,理由同 Android。
                sourcePosition = feed?.offsetOfFirst?.takeIf { queue.size > 0 }?.let { it + queue.index + 1 },
                sourceTotal = feed?.total,
                shuffled = queue.shuffled,
                canPrevious = canPrevious,
                canNext = canNext,
                frameId = frame?.id,
                sourceLabel = frame?.label.orEmpty(),
                source = frame?.source,
                enriching = frame?.enriching == true,
                incomplete = frame?.incomplete == true,
                canExtendBefore = feed?.hasBefore == true,
                canExtendAfter = feed?.hasAfter == true,
                extendingBefore = frame?.extendingBefore == true,
                extendingAfter = frame?.extendingAfter == true,
            ),
        )
    }

    private companion object {
        const val TICK_MILLIS = 500L

        /** 当前条离队列一头不足几条时往那头续取。与 Android 同值。 */
        const val EXTEND_THRESHOLD = 3

        /** 上一条先回本 P 开头的界线,Media3 `C.DEFAULT_MAX_SEEK_TO_PREVIOUS_POSITION_MS` 同值。 */
        const val MAX_SEEK_TO_PREVIOUS_MILLIS = 3_000L
    }
}
