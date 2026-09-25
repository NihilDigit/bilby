package dev.bilby.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.openani.mediamp.MediaStatus
import org.openani.mediamp.PlaybackEvent
import org.openani.mediamp.PlayerState

/**
 * 桌面上的 [PlayerHandle]:同一个 mpv 播放器的外壳。状态读 mediamp 的流,mediamp 覆盖不到的
 * (倍速、缓冲位置、关视频轨)直接问 mpv。
 *
 * 事件由 mediamp 的状态流与事件流派发,倍速没有流,和 Android 端一样在变了之后报一次。
 */
class MpvPlayerHandle internal constructor(
    val player: DesktopPlayer,
    private val host: DesktopPlaybackHost,
    private val scope: CoroutineScope,
) : PlayerHandle {

    private val mediamp get() = player.mediamp
    private val listeners = LinkedHashSet<PlayerListener>()
    private var dispatchJob: Job? = null

    override val isPlaying: Boolean get() = mediamp.state.value.isPlaying
    override val playWhenReady: Boolean get() = mediamp.state.value.playWhenReady
    override val playbackState: Int get() = mediamp.state.value.phase()
    override val currentPosition: Long get() = player.positionMillis

    /** 已缓冲到哪儿:mpv 的 demuxer 缓存末端,秒。没有缓存信息时退回当前位置。 */
    override val bufferedPosition: Long
        get() = (player.mpv.getPropertyDouble("demuxer-cache-time") * 1000).toLong()
            .takeIf { it > 0 } ?: currentPosition

    override val duration: Long get() = mediamp.mediaProperties.value?.durationMillis ?: -1L
    override val playbackSpeed: Float get() = player.speed

    override val videoSize: VideoDimensions
        get() = mediamp.mediaProperties.value.let { VideoDimensions(it?.videoWidth ?: 0, it?.videoHeight ?: 0) }

    // 桌面没有队列,随机无从生效;记下来只为读回去一致。
    override var shuffleModeEnabled: Boolean = false

    override fun play() = mediamp.play()
    override fun pause() = mediamp.pause()

    override fun seekTo(positionMillis: Long) {
        mediamp.seekTo(positionMillis)
        host.onSeeked(positionMillis)
    }

    override fun seekTo(index: Int, positionMillis: Long) {
        if (index == 0) seekTo(positionMillis)
    }

    override fun seekToNext() = Unit
    override fun seekToPrevious() = Unit

    override fun setPlaybackSpeed(speed: Float) {
        player.mpv.setPropertyDouble("speed", speed.toDouble())
        listeners.toList().forEach { it.onPlaybackSpeedChanged(speed) }
    }

    override fun setVideoDisabled(disabled: Boolean) {
        player.mpv.setPropertyString("vid", if (disabled) "no" else "auto")
    }

    override fun addListener(listener: PlayerListener) {
        listeners += listener
        if (dispatchJob == null) dispatchJob = startDispatch()
    }

    override fun removeListener(listener: PlayerListener) {
        listeners -= listener
        if (listeners.isEmpty()) {
            dispatchJob?.cancel()
            dispatchJob = null
        }
    }

    override fun send(command: PlaybackCommand) = host.handle(command)

    private fun startDispatch(): Job = scope.launch {
        launch {
            var previous: PlayerState? = null
            combine(mediamp.state, mediamp.mediaProperties) { state, _ -> state }.collect { state ->
                val last = previous
                val snapshot = listeners.toList()
                if (last == null || last.isPlaying != state.isPlaying) {
                    snapshot.forEach { it.onIsPlayingChanged(state.isPlaying) }
                }
                if (last == null || last.playWhenReady != state.playWhenReady) {
                    snapshot.forEach { it.onPlayWhenReadyChanged(state.playWhenReady) }
                }
                if (last == null || last.phase() != state.phase()) {
                    snapshot.forEach { it.onPlaybackStateChanged(state.phase()) }
                }
                snapshot.forEach { it.onEvents() }
                previous = state
            }
        }
        launch {
            mediamp.events.collect { event ->
                if (event is PlaybackEvent.SeekCompleted) {
                    val position = player.positionMillis
                    listeners.toList().forEach { it.onPositionDiscontinuity(position, position, isSeek = true) }
                }
            }
        }
        // 画面尺寸跟着 mediaProperties 走,上面已经在 onEvents 里重读;这里只防着首帧之前
        // 尺寸还没报上来的那一段,短时间内多派发几次 onEvents。
        repeat(STARTUP_SIZE_POLLS) {
            delay(STARTUP_SIZE_POLL_MILLIS)
            listeners.toList().forEach { it.onEvents() }
        }
    }

    private companion object {
        const val STARTUP_SIZE_POLLS = 10
        const val STARTUP_SIZE_POLL_MILLIS = 300L
    }
}

/** mediamp 的状态折成 [PlayerPhase]。缓冲与打开中都算 BUFFERING,界面据此转圈。 */
private fun PlayerState.phase(): Int = when (mediaStatus) {
    is MediaStatus.Opening -> PlayerPhase.BUFFERING
    is MediaStatus.Ready -> if (isBuffering) PlayerPhase.BUFFERING else PlayerPhase.READY
    is MediaStatus.Ended -> PlayerPhase.ENDED
    else -> PlayerPhase.IDLE
}
