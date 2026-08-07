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
import dev.bilby.api.BiliResult
import dev.bilby.data.VideoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI 需要知道的一切。队列位置是 [positionInQueue] / [queueSize],即 N / M。 */
data class AudioPlaybackUiState(
    val active: Boolean = false,
    val current: QueueItem? = null,
    val isPlaying: Boolean = false,
    /** 1-based,直接显示。队列空时为 0。 */
    val positionInQueue: Int = 0,
    val queueSize: Int = 0,
    val shuffled: Boolean = false,
    /** 正在取流。这一步要走一次网络,不给反馈的话按下"下一条"后会有一两秒的静默。 */
    val loading: Boolean = false,
    /** 队列内容。听视频页要列出来,没必要为它再开一个通道。 */
    val items: List<QueueItem> = emptyList(),
    /**
     * 播放器此刻真正装着的那一条。和 [current] 不是一回事:[current] 是队列视角,看视频时
     * 队列为空,它恒为 null。
     *
     * 进度回传必须先拿它对一次身份。全 app 只有一个播放器(DESIGN 2.4b),翻到新视频时它还
     * 装着上一条,而位置和时长都是从播放器读的——不对身份就会把上一条的进度按新页的
     * bvid/cid 报上去。
     */
    val loaded: QueueItem? = null,
)

/**
 * 听视频的后台播放服务(DESIGN 2.4b)。息屏继续播、通知栏控制、耳机线控都由
 * MediaSession + 前台服务承担;通知用 Media3 自带的,不手搓。
 *
 * **播完即停**:[PlaybackQueue.next] 返回 null 时只是暂停,不循环、不从任何地方续接下一条。
 * 允许连播的前提是集合有限且由用户显式选定,续接推荐池就等于恢复了被禁的自动连播。
 *
 * **逐条取流**:播到某条时才调 [VideoRepository.getPlayUrl]。playurl 给的是带时效的 CDN
 * 直链,一次性把整个队列的地址取好,排在后面的那些等轮到时早就过期了,表现为播到某条突然
 * 403 而前面几条都正常——这种失败很难归因。这里也不做预取,理由同上:预取越早,过期风险越大。
 *
 * **全 app 只有这一个播放器**(DESIGN 2.4b「一个播放状态,两个 UI」)。播放页不再自己建
 * 播放器,它用 [ACTION_PLAY_VIDEO] 把要播的流交给这里,再通过 MediaController 控制。
 * 于是"两个播放器同时发声""看/听之间交接进度"不是被解决,而是不存在。
 */
@UnstableApi
class AudioPlaybackService : MediaSessionService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var player: ExoPlayer
    private lateinit var videoRepository: VideoRepository
    private lateinit var sleepTimer: SleepTimer
    private var session: MediaSession? = null

    private var queue = PlaybackQueue(emptyList())
    private var prepareJob: Job? = null

    /**
     * 播放页交过来的单条视频。它**不进队列**:队列是听视频模式的东西,而 [AudioPlaybackUiState.active]
     * 正是靠队列非空来决定要不要显示听视频的迷你条——看视频时把它塞进队列,迷你条就会跟着冒出来。
     */
    private var singleItem: QueueItem? = null

    /** 当前装进播放器的是哪一条、用的哪条流地址。两处"要不要重新装载"的判断都看它,见用处的注释。 */
    private var loadedItem: QueueItem? = null
    private var loadedVideoUrl: String? = null

    override fun onCreate() {
        super.onCreate()
        videoRepository = (application as BilbyApplication).container.videoRepository

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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 队列经静态字段递交而不是塞进 Intent:QueueItem 列表可能上百条,Intent 有大小上限,
        // 而服务与 UI 本来就同进程(没有 android:process,MediaController 才是跨进程那条路)。
        pendingQueue?.let { pending ->
            pendingQueue = null
            queue = PlaybackQueue(pending.items, pending.startIndex, pending.shuffled)
            playCurrent()
        }
        pendingBvid?.let { bvid ->
            pendingBvid = null
            if (queue.seekToBvid(bvid) != null) playCurrent()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 划掉任务卡片时没在播就收摊;在播就留着——听视频的常态就是划走界面继续听。
        if (!player.playWhenReady) stopSelf()
    }

    override fun onDestroy() {
        prepareJob?.cancel()
        scope.cancel()
        session?.release()
        session = null
        currentPlayer = null
        player.release()
        _state.value = AudioPlaybackUiState()
        _sleepTimerState.value = SleepTimerState()
        super.onDestroy()
    }

    /** 取当前这条的流并播。取流失败跳到下一条(队列有限,不会无限重试)。 */
    private fun playCurrent(playWhenReady: Boolean = true) {
        prepareJob?.cancel()
        singleItem = null
        val item = queue.current() ?: run { stopPlayback(); return }

        // 从播放页点"听视频"时,队列的第一条往往就是正在播的那条。同一个播放器上重新取流重新
        // prepare 会让声音断一下并回到开头,而这正是"交接进度"本该消失的那个问题。
        if (loadedItem.isSameVideoAs(item)) {
            player.playWhenReady = playWhenReady
            publishState()
            return
        }

        publishState(loading = true)
        prepareJob = scope.launch {
            // 空间投稿来源的队列项没有 cid(列表接口不返回),约定由这里补:拿着 0 去取流
            // 会被服务端当成无效 cid,表现是每一条都"取流失败被跳过",队列静默空转。
            val cid = item.cid.takeIf { it != 0L } ?: run {
                when (val detail = videoRepository.getVideoDetail(item.bvid)) {
                    is BiliResult.Ok -> detail.value.cid
                    else -> {
                        BiliLog.w("听视频补 cid 失败,跳过 bvid=${item.bvid}")
                        skipAfterFailure(playWhenReady)
                        return@launch
                    }
                }
            }

            when (val result = videoRepository.getPlayUrl(item.bvid, cid)) {
                is BiliResult.Ok -> {
                    val streams = result.value.streams
                    load(streams.videoUrl, streams.audioUrl, item.copy(cid = cid), startPositionMillis = 0)
                    player.playWhenReady = playWhenReady
                    publishState()
                }
                is BiliResult.ApiError -> {
                    BiliLog.w("听视频取流失败,跳过 bvid=${item.bvid} code=${result.code} ${result.message}")
                    skipAfterFailure(playWhenReady)
                }
                is BiliResult.Failure -> {
                    BiliLog.w("听视频取流失败,跳过 bvid=${item.bvid}", result.cause)
                    skipAfterFailure(playWhenReady)
                }
            }
        }
    }

    /**
     * 播放页交来一条视频。流是播放页那边已经取好的(它还要拿同一份响应填清晰度菜单),这里
     * 不重复取一次。
     *
     * 切清晰度也走这条:播放页换到别的清晰度后会带着新地址和当前位置再发一次。
     * 于是"换流"这个动作只有一个实现,而且只发生在持有播放器的这一侧。
     */
    private fun playSingleVideo(args: Bundle) {
        val videoUrl = args.getString(EXTRA_VIDEO_URL).orEmpty()
        if (videoUrl.isEmpty()) {
            BiliLog.w("播放页交来的流地址为空,忽略")
            return
        }
        val item = QueueItem(
            bvid = args.getString(EXTRA_BVID).orEmpty(),
            cid = args.getLong(EXTRA_CID),
            title = args.getString(EXTRA_TITLE).orEmpty(),
            upName = args.getString(EXTRA_UP_NAME).orEmpty(),
            coverUrl = args.getString(EXTRA_COVER_URL).orEmpty(),
            durationSeconds = 0,
        )

        // 播放页每次重建(转屏进全屏、进程内返回)都会重连并把同一份流再交一次。地址相同就是
        // 同一次播放,重新装载只会让画面黑一下并跳回 resume 点——真的换了清晰度地址一定不同。
        if (loadedVideoUrl == videoUrl) {
            singleItem = item
            publishState()
            return
        }

        // 队列的取流是异步的,不取消的话它回来时会把播放页刚装上的流盖掉。
        prepareJob?.cancel()

        // **队列只在换了视频时作废,换分 P 不作废。**
        //
        // 两个播放界面都只是这个服务的外壳:它们不持有"现在在放什么",只把要放的流交过来。
        // 所以"还算不算在队列里"该由这里判断,而不是由交流的那一方宣布。原先无条件清空,
        // 等于播放页每交一次流就说一句"现在没有队列了" —— 于是在听视频里换个 P,整份合集
        // 队列就没了,而换 P 根本没有离开这条视频。
        //
        // 队列装的是视频(不同 bvid),分 P 是同一条视频内部的结构(同 bvid 不同 cid),
        // 这条边界在 CLAUDE.md 里已经写明:多 P 视频和合集是两回事。
        if (queue.current()?.bvid != item.bvid) {
            queue = PlaybackQueue(emptyList())
        } else {
            // 还在队列里,只是换了 P:让这一格跟着记住换到了哪一 P。不更新的话按"下一条"
            // 再按"上一条"回来,队列会从它自带的默认 cid 出发,把人送回 P1。
            queue.updateCurrentCid(item.cid)
        }
        singleItem = item
        load(videoUrl, args.getString(EXTRA_AUDIO_URL), item, args.getLong(EXTRA_START_POSITION))
        player.playWhenReady = true
        publishState()
    }

    private fun load(videoUrl: String, audioUrl: String?, item: QueueItem, startPositionMillis: Long) {
        player.setMediaSource(PlayerFactory.createMediaSource(videoUrl, audioUrl))
        player.prepare()
        if (startPositionMillis > 0) player.seekTo(startPositionMillis)
        loadedItem = item
        loadedVideoUrl = videoUrl
    }

    /** cid 为 0 的队列项还没补上 cid(见 [QueueItem]),这时只能按 bvid 认。 */
    private fun QueueItem?.isSameVideoAs(other: QueueItem): Boolean {
        val loaded = this ?: return false
        if (loaded.bvid != other.bvid) return false
        return other.cid == 0L || loaded.cid == other.cid
    }

    private fun skipAfterFailure(playWhenReady: Boolean) {
        if (queue.next() != null) playCurrent(playWhenReady) else stopPlayback()
    }

    /** 队列走完。只暂停不停服务:用户可能想按上一条回去重听。 */
    /**
     * 从听视频退回播放页 —— 音频专属的那一段到此为止。
     *
     * **只清队列,不停播放。** 回到的是一个有画面的地方,同一条视频接着放;要停的是
     * "队列"这个身份:迷你条的可见性判据就是队列非空([AudioPlaybackUiState.active]),
     * 不清的话用过一次听视频,迷你条和通知栏就在全 app 常驻到进程结束。
     *
     * 当前这条从队列挪进 [singleItem],于是播放器装的东西没变、进度没动,变的只是
     * "它现在是播放页交来的一条,不是一份队列"。
     *
     * 划走 app 不走这里(见 [onTaskRemoved]):那种情况下听视频正是要继续的,
     * 两者是不同的离开。
     */
    private fun endListening() {
        if (queue.size == 0) return
        singleItem = loadedItem
        queue = PlaybackQueue(emptyList())
        publishState()
    }

    private fun stopPlayback() {
        player.playWhenReady = false
        publishState()
    }

    private fun publishState(loading: Boolean = false) {
        _state.value = AudioPlaybackUiState(
            active = queue.size > 0,
            current = queue.current(),
            isPlaying = player.isPlaying,
            items = queue.itemsNatural(),
            positionInQueue = if (queue.size > 0) queue.currentIndex + 1 else 0,
            queueSize = queue.size,
            shuffled = queue.shuffled,
            loading = loading,
            loaded = loadedItem,
        )
    }

    /** 通知栏与锁屏显示的元数据。流本身不带 tag,只能由队列(或播放页交来的那条)提供。 */
    private fun currentMetadata(): MediaMetadata {
        val item = queue.current() ?: singleItem ?: return MediaMetadata.EMPTY
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
            // 忽然跳到了下一条。
            //
            // 认 loadedItem 而不是 queue.current():播放页交来的那条不进队列(见 singleItem),
            // 按队列取会打出 bvid=null,而看视频出错恰恰是最需要知道是哪一条的场合。
            BiliLog.w("播放出错,跳过 bvid=${loadedItem?.bvid} code=${error.errorCode}", error)
            skipAfterFailure(playWhenReady = true)
        }
    }

    /**
     * 播放器只装当前这一条(逐条取流的必然结果),所以"有没有下一条""随机开没开"这些
     * 得由队列回答,而不是由播放器的 timeline 回答。用 ForwardingPlayer 把这几个问题接管过来,
     * MediaSession 拿到的就是队列视角:通知栏的上/下一条按钮、耳机线控的双击三击都能用。
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

        override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
            queue.setShuffled(shuffleModeEnabled)
            publishState()
        }

        override fun getRepeatMode(): Int = Player.REPEAT_MODE_OFF

        /** 播完即停是产品约束(DESIGN 2.4b),循环不接受外部设置。 */
        override fun setRepeatMode(repeatMode: Int) {
            if (repeatMode != Player.REPEAT_MODE_OFF) {
                BiliLog.w("听视频不支持循环(DESIGN 2.4b),忽略 repeatMode=$repeatMode")
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
                        .add(SessionCommand(ACTION_SLEEP_TIMER, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_PLAY_VIDEO, Bundle.EMPTY))
                        .add(SessionCommand(ACTION_END_LISTENING, Bundle.EMPTY))
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
                ACTION_SLEEP_TIMER -> {
                    when (val minutes = args.getInt(EXTRA_SLEEP_MINUTES, SLEEP_CANCEL)) {
                        SLEEP_CANCEL -> sleepTimer.cancel()
                        SLEEP_END_OF_ITEM -> sleepTimer.startEndOfItem()
                        else -> sleepTimer.startAfter(minutes)
                    }
                }

                ACTION_PLAY_VIDEO -> playSingleVideo(args)
                ACTION_END_LISTENING -> endListening()

                else -> return super.onCustomCommand(session, controller, customCommand, args)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    private class PendingQueue(
        val items: List<QueueItem>,
        val startIndex: Int,
        val shuffled: Boolean,
    )

    companion object {
        const val ACTION_SLEEP_TIMER = "dev.bilby.SLEEP_TIMER"
        const val EXTRA_SLEEP_MINUTES = "minutes"

        /** [EXTRA_SLEEP_MINUTES] 的两个哨兵值,其余正数按分钟计。 */
        const val SLEEP_CANCEL = -1
        const val SLEEP_END_OF_ITEM = 0

        /** 播放页把一条视频交给服务播(含切清晰度后重新交)。参数见下面这组 EXTRA。 */
        const val ACTION_PLAY_VIDEO = "dev.bilby.PLAY_VIDEO"

        /** 见 [endListening]。 */
        const val ACTION_END_LISTENING = "dev.bilby.END_LISTENING"
        const val EXTRA_BVID = "bvid"
        const val EXTRA_CID = "cid"
        const val EXTRA_VIDEO_URL = "videoUrl"
        const val EXTRA_AUDIO_URL = "audioUrl"

        /** 续播位置,只在真正装载新流时生效。 */
        const val EXTRA_START_POSITION = "startPosition"
        const val EXTRA_TITLE = "title"
        const val EXTRA_UP_NAME = "upName"
        const val EXTRA_COVER_URL = "coverUrl"

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

        @Volatile
        private var pendingQueue: PendingQueue? = null

        /**
         * 开始听视频。用 startService 而不是 startForegroundService:后者要求 5 秒内进前台,
         * 而这里第一件事是取流(一次网络往返),慢一点就是
         * ForegroundServiceDidNotStartInTimeException。调用方是前台 UI,普通 startService
         * 不受后台启动限制,真正进前台由 Media3 在播放开始时完成。
         */
        fun start(
            context: Context,
            items: List<QueueItem>,
            startIndex: Int = 0,
            shuffled: Boolean = false,
        ) {
            if (items.isEmpty()) {
                BiliLog.w("听视频:队列为空,不启动服务")
                return
            }
            pendingQueue = PendingQueue(items, startIndex, shuffled)
            context.startService(Intent(context, AudioPlaybackService::class.java))
        }

        @Volatile
        private var pendingBvid: String? = null

        /** 跳到队列里的某一条。和 [start] 一样用静态字段递交,理由见那里的注释。 */
        fun playFromQueue(context: Context, bvid: String) {
            pendingBvid = bvid
            context.startService(Intent(context, AudioPlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AudioPlaybackService::class.java))
        }

        /** UI 用它建 MediaController(播放/暂停/上下条/随机/定时都走 controller)。 */
        fun sessionToken(context: Context): SessionToken =
            SessionToken(context, ComponentName(context, AudioPlaybackService::class.java))
    }
}
