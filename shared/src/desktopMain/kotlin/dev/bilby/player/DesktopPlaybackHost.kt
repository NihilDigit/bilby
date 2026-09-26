package dev.bilby.player

import dev.bilby.AppContainer
import dev.bilby.BiliLog
import dev.bilby.BvidCodec
import dev.bilby.api.BiliConstants
import dev.bilby.api.BiliResult
import dev.bilby.data.PlayInfo
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
import org.openani.mediamp.MediaStatus

/**
 * 桌面上的播放服务。和 Android 的 AudioPlaybackService 一样,**进程里只有一个播放器,归这里
 * 所有**:页面只读 [state]、发命令。
 *
 * 目前只放单条:页面报来哪一条就放哪一条,队列(合集连播、上下一条、随机)还没有桌面实现,
 * [QueueState] 里始终只有当前这一条,按钮随 canNext/canPrevious 为 false 而灰掉。
 * 心跳、续播位置、清晰度与音质的默认档和 Android 走同一套公共规则([ProgressSession]、
 * [resumeAtMillisFor]、SettingsStore 的播放偏好)。
 *
 * @param container 取仓库用。平台对象先于容器创建,所以这里拿的是一个延后求值的入口。
 */
class DesktopPlaybackHost(private val container: () -> AppContainer) : PlaybackHost {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    private val _state = MutableStateFlow(AudioPlaybackUiState())
    override val state: StateFlow<AudioPlaybackUiState> = _state.asStateFlow()

    private val _positionTicks = MutableStateFlow(PositionTick())
    override val positionTicks: StateFlow<PositionTick> = _positionTicks.asStateFlow()

    private val sleepTimer = SleepTimer(scope) { player?.mediamp?.pause() }
    override val sleepTimerState: StateFlow<SleepTimerState> get() = sleepTimer.state

    /** 第一次有页面连上来时才建:mpv 的初始化要加载原生库,没打开过播放页就不必付这个代价。 */
    private var player: DesktopPlayer? = null
    private var handle: MpvPlayerHandle? = null

    /** 当前装着的是什么,重试和换清晰度要按它重开。 */
    private var loaded: Loaded? = null
    private var loadJob: Job? = null
    private var tickJob: Job? = null
    private var progressSession: ProgressSession? = null

    private sealed interface Loaded {
        data class Video(val bvid: String, val cid: Long, val frameId: String?) : Loaded
        data class Live(val command: PlaybackCommand.OpenLive) : Loaded
    }

    // 弹幕时钟逐帧调这里,所以读不经 JNI 的那一份,见 DesktopPlayer.positionMillis。
    override fun currentPositionMillis(): Long? = player?.takeIf { loaded != null }?.observedPositionMillis

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

    // 只有一条,没有按帧保存的队列快照可丢。
    override fun retainFrames(ids: Set<String>) = Unit

    override fun stop() {
        closeProgressSession()
        loadJob?.cancel()
        tickJob?.cancel()
        sleepTimer.cancel()
        player?.mediamp?.stopPlayback()
        loaded = null
        _state.value = AudioPlaybackUiState()
        _positionTicks.value = PositionTick()
    }

    override fun connect(onConnected: (PlayerHandle) -> Unit): PlayerConnection {
        val handle = handle ?: run {
            val created = DesktopPlayer(scope.coroutineContext)
            player = created
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
            is PlaybackCommand.ActivateFrame -> {
                val current = loaded as? Loaded.Video
                // 同一帧、同一条已经装着:转屏、从别处回来,只续播。
                if (current != null && current.frameId == command.frameId && current.bvid == command.bvid) return
                openVideo(command.bvid, cid = 0L, frameId = command.frameId)
            }
            is PlaybackCommand.PlayPart -> (loaded as? Loaded.Video)?.let {
                openVideo(it.bvid, command.cid, it.frameId, startMillis = command.positionMillis)
            }
            is PlaybackCommand.SetQuality -> reopen(quality = command.quality)
            is PlaybackCommand.SetAudioQuality -> reopen(audioQuality = command.quality)
            PlaybackCommand.Retry -> reopen()
            is PlaybackCommand.OpenLive -> {
                if ((loaded as? Loaded.Live)?.command == command) return
                openLive(command)
            }
            PlaybackCommand.PageLeft -> player?.mediamp?.pause()
            PlaybackCommand.FlushProgress -> progressSession?.flush()
            is PlaybackCommand.SetSleepTimer -> sleepTimer.start(command.mode)
            is PlaybackCommand.AdjustSleepTimer -> sleepTimer.extend(command.deltaMinutes * 60_000L)
            // 没有队列,补全与续取无从谈起。
            PlaybackCommand.RetryQueue, is PlaybackCommand.ExtendQueue -> Unit
        }
    }

    private fun reopen(quality: Int = 0, audioQuality: Int = 0) {
        when (val current = loaded) {
            is Loaded.Video -> openVideo(
                current.bvid,
                current.cid,
                current.frameId,
                startMillis = player?.positionMillis,
                quality = quality,
                audioQuality = audioQuality,
            )
            is Loaded.Live -> openLive(current.command)
            null -> Unit
        }
    }

    private fun openVideo(
        bvid: String,
        cid: Long,
        frameId: String?,
        startMillis: Long? = null,
        quality: Int = 0,
        audioQuality: Int = 0,
    ) {
        val player = player ?: return
        closeProgressSession()
        loadJob?.cancel()
        val placeholder = QueueItem(bvid = bvid, title = "", upName = "", coverUrl = "", durationSeconds = 0)
        _state.update {
            it.copy(
                loading = true,
                error = null,
                stoppedAtEnd = false,
                queue = QueueState(current = placeholder, items = listOf(placeholder), size = 1, frameId = frameId),
            )
        }
        loadJob = scope.launch {
            val app = container()
            val detail = when (val result = app.videoRepository.getVideoDetail(bvid)) {
                is BiliResult.Ok -> result.value
                else -> return@launch fail("详情", result, getString(Res.string.playback_error_detail))
            }
            val requested = cid.takeIf { it != 0L } ?: app.partRequest.consume(bvid).takeIf { it != 0L }
            val prefs = app.settings.playerPrefs.first()
            val metered = app.platform.network.isMetered()
            val targetQuality = quality.takeIf { it != 0 } ?: prefs.defaultQualityOn(metered)
            val targetAudio = audioQuality.takeIf { it != 0 } ?: prefs.defaultAudioOn(metered)
            // 没指名分 P 时先按默认那一 P 取流,服务端回的 last_play_cid 若是别的 P 就换过去,
            // 与 Android 的装载解析同一个意思:回到上次看到的那一 P。
            var playCid = requested ?: detail.cid
            var info = playInfo(app, bvid, playCid, targetQuality, prefs.codec.codecIds, targetAudio)
                ?: return@launch
            if (requested == null && info.lastPlayCid != 0L && info.lastPlayCid != playCid &&
                detail.pages.any { it.cid == info.lastPlayCid }
            ) {
                playCid = info.lastPlayCid
                info = playInfo(app, bvid, playCid, targetQuality, prefs.codec.codecIds, targetAudio) ?: return@launch
            }
            val start = startMillis ?: info.resumeAtMillisFor(playCid)
            val item = QueueItem(
                bvid = bvid,
                title = detail.title,
                upName = detail.up.name,
                coverUrl = detail.coverUrl,
                durationSeconds = detail.durationSeconds,
            )
            // **先报 loadKey,再打开。** 页面按 loadKey 决定挂不挂画面,而 mpv 的 D3D11 输出要等
            // 画面挂上才建得起来,打开会一直等到出第一帧。反过来排就是互相等:页面在等打开
            // 完成才挂画面,打开在等画面。Android 没有这层依赖,那边 loadKey 在装载完成后才置上。
            loaded = Loaded.Video(bvid, playCid, frameId)
            _state.update {
                it.copy(
                    nowPlaying = NowPlaying(detail.title, detail.up.name, detail.coverUrl),
                    loadKey = bvid,
                    currentCid = playCid,
                    error = null,
                    playInfo = info,
                    currentQuality = info.streams.qualityId,
                    queue = QueueState(current = item, items = listOf(item), size = 1, frameId = frameId),
                )
            }
            PlayerLog.d("desktop open bvid=$bvid cid=$playCid q=${info.streams.qualityId} ${info.streams.codec} start=$start")
            player.open(StreamSource(info.streams.videoUrl, info.streams.audioUrl), playWhenReady = true, start)
            _state.update { it.copy(loading = false) }
            startProgressSession(bvid, playCid)
            startTicking()
        }
    }

    private suspend fun playInfo(
        app: AppContainer,
        bvid: String,
        cid: Long,
        quality: Int,
        codecs: List<Int>,
        audio: Int,
    ): PlayInfo? = when (val result = app.videoRepository.getPlayUrl(bvid, cid, quality, codecs, audio)) {
        is BiliResult.Ok -> result.value
        is BiliResult.ApiError -> {
            fail("取流", result, getString(Res.string.playback_error_stream, result.message))
            null
        }
        is BiliResult.Failure -> {
            fail("取流", result, getString(Res.string.playback_error_network))
            null
        }
    }

    private fun openLive(command: PlaybackCommand.OpenLive) {
        val player = player ?: return
        closeProgressSession()
        loadJob?.cancel()
        val item = QueueItem(
            bvid = liveMediaId(command.roomId),
            title = command.title,
            upName = command.upName,
            coverUrl = command.coverUrl,
            durationSeconds = 0,
        )
        _state.update {
            it.copy(
                nowPlaying = NowPlaying(command.title, command.upName, command.coverUrl),
                loading = true,
                error = null,
                queue = QueueState(current = item, items = listOf(item), size = 1),
            )
        }
        loadJob = scope.launch {
            val playback = container().liveRepository.loadPlayback(command.roomId, command.qn, command.onlyAudio)
            val url = (playback as? BiliResult.Ok)?.value?.stream?.url
                ?: return@launch fail("直播取流", playback, getString(Res.string.playback_error_live_stream))
            // 先报 loadKey 再打开,理由见 openVideo。
            loaded = Loaded.Live(command)
            _state.update { it.copy(loadKey = item.bvid, currentCid = 0, playInfo = null) }
            player.open(StreamSource(url, referer = BiliConstants.LIVE_REFERER), playWhenReady = true)
            _state.update { it.copy(loading = false) }
            startTicking()
        }
    }

    /**
     * [shown] 是画面上那句,原始的错误码与异常只进日志:异常的 message 是系统原文,英文、带主机名,
     * 断网时画面上就是一句 `Unable to resolve host`。文案与 Android 服务同一套。
     */
    private fun fail(step: String, result: BiliResult<*>, shown: String) {
        val detail = when (result) {
            is BiliResult.ApiError -> "${result.code} ${result.message}"
            is BiliResult.Failure -> result.cause.message ?: result.cause.javaClass.simpleName
            is BiliResult.Ok -> "没有可放的流"
        }
        BiliLog.w("桌面播放$step 失败:$detail")
        _state.update { it.copy(loading = false, error = shown) }
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
                val playerState = player.mediamp.state.value
                val playing = playerState.isPlaying
                val position = player.positionMillis
                val duration = player.mediamp.mediaProperties.value?.durationMillis ?: 0L
                val ended = playerState.mediaStatus is MediaStatus.Ended
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
                    if (ended) {
                        session.onCompleted()
                        closeProgressSession()
                        if (sleepTimer.state.value.mode == SleepTimerMode.EndOfItem) sleepTimer.cancel()
                    }
                }
                _state.update { it.copy(isPlaying = playing, stoppedAtEnd = ended) }
                wasPlaying = playing
                delay(TICK_MILLIS)
            }
        }
    }

    /** 跳转要立刻报一次,不等下一格心跳,见 [ProgressSession.onSeeked]。 */
    internal fun onSeeked(positionMillis: Long) {
        val duration = player?.mediamp?.mediaProperties?.value?.durationMillis ?: 0L
        progressSession?.onSeeked(positionMillis, duration)
        _state.update { it.copy(stoppedAtEnd = false) }
    }

    private fun startProgressSession(bvid: String, cid: Long) {
        closeProgressSession()
        val aid = BvidCodec.toAid(bvid)
        if (aid <= 0 || cid == 0L) return
        val reporter = container().heartbeatReporter
        progressSession = ProgressSession(aid, cid) { playedTimeSeconds, finished, onConfirmed ->
            reporter.report(aid, cid, bvid, playedTimeSeconds, finished) { scope.launch { onConfirmed() } }
        }
    }

    private fun closeProgressSession() {
        progressSession?.close(player?.positionMillis)
        progressSession = null
    }

    private companion object {
        const val TICK_MILLIS = 500L
    }
}
