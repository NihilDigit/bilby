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
        // 划掉任务卡片时没在播就收摊;在播就留着——听视频的常态就是划走界面继续听。
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        prepareJob?.cancel()
        retryJob?.cancel()
        scope.cancel()
        session?.release()
        session = null
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
     * 队列里已经有这条(合集里换一集、点队列中的一条)就跳过去;没有就**重做队列**:
     * 先按合集找,不属于合集才退到 UP 投稿(DESIGN 2.4b),两条都拿不到就退成只有这一条的队列。
     */
    private fun openVideo(args: Bundle) {
        val bvid = args.getString(EXTRA_BVID).orEmpty()
        if (bvid.isEmpty()) {
            BiliLog.w("OPEN_VIDEO 没带 bvid,忽略")
            return
        }
        val cid = args.getLong(EXTRA_CID)

        if (queue.current()?.bvid == bvid) {
            // 同一条视频。换 P 才需要动,否则连状态都不用重发。
            if (cid != 0L && cid != loadedCid) playPart(cid) else publishState()
            return
        }
        if (queue.seekToBvid(bvid) != null) {
            playCurrent()
            return
        }

        val fallback = QueueItem(
            bvid = bvid,
            cid = cid,
            title = args.getString(EXTRA_TITLE).orEmpty(),
            upName = args.getString(EXTRA_UP_NAME).orEmpty(),
            coverUrl = args.getString(EXTRA_COVER_URL).orEmpty(),
            durationSeconds = 0,
        )
        val mid = args.getLong(EXTRA_MID)

        prepareJob?.cancel()
        retryJob?.cancel()
        publishState(loading = true)
        prepareJob = scope.launch {
            val shuffled = settings.playbackPrefs.first().shuffled
            val built = queueSourceRepository.fromSeason(bvid)
                ?: queueSourceRepository.fromUpSpace(mid, bvid)
            if (built != null) {
                queue = PlaybackQueue(built.items, built.startIndex, shuffled)
                sourceLabel = built.sourceLabel
                // 合集给的 cid 是这一集的 P1,而页面可能是带着某一 P 进来的。
                if (cid != 0L) queue.updateCurrentCid(cid)
            } else {
                // 合集和空间投稿都没拿到:仍然要有一份队列,否则"队列是唯一真相"就有了缺口。
                BiliLog.w("建队列失败,退成单条队列 bvid=$bvid")
                queue = PlaybackQueue(listOf(fallback))
                sourceLabel = ""
            }
            playCurrent()
        }
    }

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
                    is BiliResult.Ok -> detail.value.cid
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
            when (
                val result = videoRepository.getPlayUrl(
                    item.bvid,
                    cid,
                    preferredQuality = quality,
                    preferredCodecs = prefs.codec.codecIds,
                )
            ) {
                is BiliResult.Ok -> {
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
        )
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
        const val EXTRA_MID = "mid"
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
