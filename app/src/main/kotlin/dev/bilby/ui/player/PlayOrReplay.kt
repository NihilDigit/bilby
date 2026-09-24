package dev.bilby.ui.player

import androidx.media3.common.Player
import dev.bilby.player.AudioPlaybackService

/**
 * 播放键按下时的"放":停在末尾就从头来,否则接着放。
 *
 * 判据取服务的 [dev.bilby.player.AudioPlaybackUiState.stoppedAtEnd],不看 `STATE_ENDED`:被
 * `pauseAtEndOfMediaItems` 拦在末尾的非末条不进 ENDED,在那里直接 play() 会越过末尾走到
 * 下一条,而这一下人想的是把这一条再看一遍。`seekTo(0)` 落在当前条上,不会换条。
 */
fun Player.playOrReplay() {
    if (AudioPlaybackService.state.value.stoppedAtEnd) seekTo(0)
    play()
}
