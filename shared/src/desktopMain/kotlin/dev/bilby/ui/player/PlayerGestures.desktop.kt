package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.bilby.player.DesktopPlaybackHost
import dev.bilby.ui.LocalPlaybackHost

@Composable
internal actual fun rememberWindowBrightness(): LevelControl? = null

/** 调的是播放器自己的音量,见 [DesktopPlaybackHost.volume]。 */
@Composable
internal actual fun rememberMediaVolume(): LevelControl? {
    val host = LocalPlaybackHost.current as? DesktopPlaybackHost ?: return null
    return remember(host) {
        object : LevelControl {
            override fun current(): Float = host.volume
            override fun set(fraction: Float) {
                host.volume = fraction
            }
        }
    }
}
