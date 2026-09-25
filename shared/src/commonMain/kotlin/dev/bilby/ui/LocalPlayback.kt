package dev.bilby.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.bilby.player.PlaybackHost

/** 界面层拿播放服务的入口,由平台入口在组合树根部提供,值就是 [dev.bilby.Platform.playback]。 */
val LocalPlaybackHost = staticCompositionLocalOf<PlaybackHost> {
    error("PlaybackHost 未提供:平台入口要在组合树根部提供 LocalPlaybackHost")
}
