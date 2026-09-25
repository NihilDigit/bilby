package dev.bilby.player

import kotlinx.coroutines.flow.StateFlow

/** 播放器状态,取值与 media3 `Player.STATE_*` 相同,Android 端原样透传。 */
object PlayerPhase {
    const val IDLE = 1
    const val BUFFERING = 2
    const val READY = 3
    const val ENDED = 4
}

/** 画面尺寸。像素不是正方形时 [pixelWidthHeightRatio] 不为 1。未知时宽高为 0。 */
data class VideoDimensions(
    val width: Int = 0,
    val height: Int = 0,
    val pixelWidthHeightRatio: Float = 1f,
)

/** 播放器事件。只列界面用到的几种,名字与 media3 `Player.Listener` 对应。 */
interface PlayerListener {
    fun onIsPlayingChanged(isPlaying: Boolean) {}
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {}
    fun onPlaybackStateChanged(playbackState: Int) {}
    fun onPlaybackSpeedChanged(speed: Float) {}

    /** 位置跳变(seek、换条、自动跳过)。[isSeek] 为 true 表示是 seek 引起的。 */
    fun onPositionDiscontinuity(oldPositionMillis: Long, newPositionMillis: Long, isSeek: Boolean) {}

    /**
     * 一批事件处理完。Android 上 MediaController 同步 session 状态时有几处不发单项事件
     * (见 PlayerShell 读画面尺寸那一段),要读的字段在这里重读。
     */
    fun onEvents() {}
}

/**
 * 界面手里的播放器。**它不是播放器本身**:Android 上是连到播放服务的 MediaController,
 * 桌面上是进程内那个 mpv 播放器的一层外壳。界面只读状态、发命令,不 prepare、不 release。
 */
interface PlayerHandle {
    val isPlaying: Boolean

    /** "要不要放",不是"此刻在不在出声":缓冲中 [isPlaying] 为 false,它仍为 true。 */
    val playWhenReady: Boolean

    /** 见 [PlayerPhase]。 */
    val playbackState: Int
    val currentPosition: Long
    val bufferedPosition: Long

    /** 总时长,未知时为负数。 */
    val duration: Long
    val playbackSpeed: Float
    val videoSize: VideoDimensions
    var shuffleModeEnabled: Boolean

    fun play()
    fun pause()
    fun seekTo(positionMillis: Long)

    /** 跳到队列里的第 [index] 条,从 [positionMillis] 开始。 */
    fun seekTo(index: Int, positionMillis: Long)
    fun seekToNext()
    fun seekToPrevious()
    fun setPlaybackSpeed(speed: Float)

    /**
     * 关掉或打开视频轨。听视频时关掉:不关的话画面虽然不渲染,流还是照下,白费流量和电。
     * 用禁轨而不是重建只含音频的媒体源 —— 后者要重新 prepare 和 seek。
     */
    fun setVideoDisabled(disabled: Boolean)

    fun addListener(listener: PlayerListener)
    fun removeListener(listener: PlayerListener)

    /** 播放服务的自定义命令,见 [PlaybackCommand]。 */
    fun send(command: PlaybackCommand)
}

/**
 * 界面对播放服务说的话。除了标准的播放控制,换条、换 P、换清晰度都走这里 —— 取流归服务,
 * 页面只说要什么。每一条的语义见 Android 实现 AudioPlaybackService 里对应的 ACTION_*。
 */
sealed interface PlaybackCommand {
    /** 这一页到了前台。报的是帧,不是 bvid,见 AudioPlaybackService.activateFrame。 */
    data class ActivateFrame(val frameId: String, val queueContext: String, val bvid: String) : PlaybackCommand

    /** 换这条视频的一 P。[positionMillis] 为 null 时由装载层决定从哪儿放。 */
    data class PlayPart(val cid: Long, val positionMillis: Long? = null) : PlaybackCommand

    data object RetryQueue : PlaybackCommand

    /** 完整队列滚到一头,往那头续取。 */
    data class ExtendQueue(val before: Boolean) : PlaybackCommand

    data class OpenLive(
        val roomId: Long,
        val qn: Int,
        val onlyAudio: Boolean,
        val title: String,
        val upName: String,
        val coverUrl: String,
    ) : PlaybackCommand

    data class SetQuality(val quality: Int) : PlaybackCommand
    data class SetAudioQuality(val quality: Int) : PlaybackCommand
    data object Retry : PlaybackCommand

    /** 页面离开。与用户按下暂停不是一回事,见 AudioPlaybackService.ACTION_PAGE_LEFT。 */
    data object PageLeft : PlaybackCommand

    /** 先补一条进度。离开播放页多半就是这次观看的终点。 */
    data object FlushProgress : PlaybackCommand

    data class SetSleepTimer(val mode: SleepTimerMode) : PlaybackCommand
    data class AdjustSleepTimer(val deltaMinutes: Int) : PlaybackCommand
}

/** 连上的那一个 [PlayerHandle]。断开由拿到它的一方负责,见 [PlaybackHost.connect]。 */
interface PlayerConnection {
    fun release()
}

/**
 * 进程里唯一的播放服务对界面露出的那一面(DESIGN 2.4b:播放器归服务所有)。
 *
 * 状态以流的形式常驻,不需要连接;控制与画面要先 [connect]。
 */
interface PlaybackHost {
    val state: StateFlow<AudioPlaybackUiState>
    val positionTicks: StateFlow<PositionTick>
    val sleepTimerState: StateFlow<SleepTimerState>

    /** 此刻的播放位置,服务没起来时为 null。弹幕逐帧读它。 */
    fun currentPositionMillis(): Long?

    /**
     * 退到后台时要不要接着放。**同步写**:Android 上 Activity.onStop 会同步读它,
     * "打开听视频后立刻锁屏"那条路径上异步命令很可能还没送到。
     */
    fun setBackgroundPlaybackAllowed(allowed: Boolean)

    /** 前台没了。放不放下去由服务按当前装的东西决定。 */
    fun pauseForAppBackground()

    /** 导航栈上还在的视频页帧,其余帧的快照可以丢了。 */
    fun retainFrames(ids: Set<String>)

    /** 停止播放并结束服务(登出、最后一个视频页离开)。 */
    fun stop()

    /** 连上播放器。[onConnected] 在主线程上回调;连接失败时不回调,原因记日志。 */
    fun connect(onConnected: (PlayerHandle) -> Unit): PlayerConnection
}
