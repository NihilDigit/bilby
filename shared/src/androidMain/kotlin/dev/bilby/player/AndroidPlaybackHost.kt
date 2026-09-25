package dev.bilby.player

import android.content.Context
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import dev.bilby.BiliLog
import kotlinx.coroutines.flow.StateFlow

/** Android 上的 [PlaybackHost]:状态读 [AudioPlaybackService] 的静态流,控制经 MediaController。 */
class AndroidPlaybackHost(context: Context) : PlaybackHost {

    private val appContext = context.applicationContext

    override val state: StateFlow<AudioPlaybackUiState> get() = AudioPlaybackService.state
    override val positionTicks: StateFlow<PositionTick> get() = AudioPlaybackService.positionTicks
    override val sleepTimerState: StateFlow<SleepTimerState> get() = AudioPlaybackService.sleepTimerState

    override fun currentPositionMillis(): Long? = AudioPlaybackService.currentPositionMillis()

    override fun setBackgroundPlaybackAllowed(allowed: Boolean) =
        AudioPlaybackService.setBackgroundPlaybackAllowed(allowed)

    override fun pauseForAppBackground() = AudioPlaybackService.pauseForAppBackground()

    override fun retainFrames(ids: Set<String>) = AudioPlaybackService.retainFrames(ids)

    override fun stop() = AudioPlaybackService.stop(appContext)

    /**
     * 连接的是 session 的完整默认命令集:少了 `COMMAND_SET_VIDEO_SURFACE` 时 controller 静默返回,
     * 表现为黑画面且日志里什么都没有(见 CLAUDE.md)。
     */
    override fun connect(onConnected: (PlayerHandle) -> Unit): PlayerConnection {
        val future = MediaController.Builder(appContext, AudioPlaybackService.sessionToken(appContext))
            .buildAsync()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { onConnected(MediaControllerHandle(it)) }
                    .onFailure { BiliLog.w("连接播放服务失败", it) }
            },
            ContextCompat.getMainExecutor(appContext),
        )
        return object : PlayerConnection {
            // **不 release 播放器**:它归服务所有,不归页面。页面离开只断开连接。
            override fun release() = MediaController.releaseFuture(future)
        }
    }
}

/** 把 MediaController 包成 [PlayerHandle]。平台代码(画面、画中画)要原物时取 [controller]。 */
class MediaControllerHandle(val controller: MediaController) : PlayerHandle {

    override val isPlaying: Boolean get() = controller.isPlaying
    override val playWhenReady: Boolean get() = controller.playWhenReady
    override val playbackState: Int get() = controller.playbackState
    override val currentPosition: Long get() = controller.currentPosition
    override val bufferedPosition: Long get() = controller.bufferedPosition
    override val duration: Long get() = controller.duration.takeIf { it != C.TIME_UNSET } ?: -1L
    override val playbackSpeed: Float get() = controller.playbackParameters.speed

    override val videoSize: VideoDimensions
        get() = controller.videoSize.let { VideoDimensions(it.width, it.height, it.pixelWidthHeightRatio) }

    override var shuffleModeEnabled: Boolean
        get() = controller.shuffleModeEnabled
        set(value) {
            controller.shuffleModeEnabled = value
        }

    override fun play() = controller.play()
    override fun pause() = controller.pause()
    override fun seekTo(positionMillis: Long) = controller.seekTo(positionMillis)
    override fun seekTo(index: Int, positionMillis: Long) = controller.seekTo(index, positionMillis)

    // 不带 MediaItem 的那一对:服务在这两条命令上先走分 P(QueuePlayer.handleSeek)。
    override fun seekToNext() = controller.seekToNext()
    override fun seekToPrevious() = controller.seekToPrevious()
    override fun setPlaybackSpeed(speed: Float) = controller.setPlaybackSpeed(speed)

    override fun setVideoDisabled(disabled: Boolean) {
        controller.trackSelectionParameters = controller.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled)
            .build()
    }

    private val listeners = HashMap<PlayerListener, Player.Listener>()

    override fun addListener(listener: PlayerListener) {
        val adapter = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = listener.onIsPlayingChanged(isPlaying)
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                listener.onPlayWhenReadyChanged(playWhenReady)
            override fun onPlaybackStateChanged(playbackState: Int) = listener.onPlaybackStateChanged(playbackState)
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
                listener.onPlaybackSpeedChanged(playbackParameters.speed)
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) = listener.onPositionDiscontinuity(
                oldPosition.positionMs,
                newPosition.positionMs,
                isSeek = reason == Player.DISCONTINUITY_REASON_SEEK,
            )
            override fun onEvents(player: Player, events: Player.Events) = listener.onEvents()
        }
        listeners[listener] = adapter
        controller.addListener(adapter)
    }

    override fun removeListener(listener: PlayerListener) {
        listeners.remove(listener)?.let(controller::removeListener)
    }

    override fun send(command: PlaybackCommand) {
        val (action, args) = command.toSessionCommand()
        controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
    }
}

private fun PlaybackCommand.toSessionCommand(): Pair<String, Bundle> = when (this) {
    is PlaybackCommand.ActivateFrame -> AudioPlaybackService.ACTION_ACTIVATE_FRAME to Bundle().apply {
        putString(AudioPlaybackService.EXTRA_FRAME_ID, frameId)
        putString(AudioPlaybackService.EXTRA_QUEUE_CONTEXT, queueContext)
        putString(AudioPlaybackService.EXTRA_BVID, bvid)
    }
    is PlaybackCommand.PlayPart -> AudioPlaybackService.ACTION_PLAY_PART to Bundle().apply {
        putLong(AudioPlaybackService.EXTRA_CID, cid)
        positionMillis?.let { putLong(AudioPlaybackService.EXTRA_POSITION_MILLIS, it) }
    }
    PlaybackCommand.RetryQueue -> AudioPlaybackService.ACTION_RETRY_QUEUE to Bundle.EMPTY
    is PlaybackCommand.ExtendQueue -> AudioPlaybackService.ACTION_EXTEND_QUEUE to Bundle().apply {
        putBoolean(AudioPlaybackService.EXTRA_BEFORE, before)
    }
    is PlaybackCommand.OpenLive -> AudioPlaybackService.ACTION_OPEN_LIVE to Bundle().apply {
        putLong(AudioPlaybackService.EXTRA_ROOM_ID, roomId)
        putInt(AudioPlaybackService.EXTRA_LIVE_QN, qn)
        putBoolean(AudioPlaybackService.EXTRA_LIVE_ONLY_AUDIO, onlyAudio)
        putString(AudioPlaybackService.EXTRA_TITLE, title)
        putString(AudioPlaybackService.EXTRA_UP_NAME, upName)
        putString(AudioPlaybackService.EXTRA_COVER_URL, coverUrl)
    }
    is PlaybackCommand.SetQuality -> AudioPlaybackService.ACTION_SET_QUALITY to Bundle().apply {
        putInt(AudioPlaybackService.EXTRA_QUALITY, quality)
    }
    is PlaybackCommand.SetAudioQuality -> AudioPlaybackService.ACTION_SET_AUDIO_QUALITY to Bundle().apply {
        putInt(AudioPlaybackService.EXTRA_QUALITY, quality)
    }
    PlaybackCommand.Retry -> AudioPlaybackService.ACTION_RETRY to Bundle.EMPTY
    PlaybackCommand.PageLeft -> AudioPlaybackService.ACTION_PAGE_LEFT to Bundle.EMPTY
    PlaybackCommand.FlushProgress -> AudioPlaybackService.ACTION_FLUSH_PROGRESS to Bundle.EMPTY
    is PlaybackCommand.SetSleepTimer -> AudioPlaybackService.ACTION_SLEEP_TIMER to Bundle().apply {
        // 三种模式压进一个 Int:分钟数,或服务那边定义的两个哨兵。
        val minutes = when (mode) {
            SleepTimerMode.Off -> AudioPlaybackService.SLEEP_TIMER_OFF
            SleepTimerMode.EndOfItem -> AudioPlaybackService.SLEEP_END_OF_ITEM
            is SleepTimerMode.After -> mode.minutes
        }
        putInt(AudioPlaybackService.EXTRA_SLEEP_MINUTES, minutes)
    }
    is PlaybackCommand.AdjustSleepTimer -> AudioPlaybackService.ACTION_SLEEP_TIMER to Bundle().apply {
        putInt(AudioPlaybackService.EXTRA_SLEEP_DELTA_MINUTES, deltaMinutes)
    }
}
