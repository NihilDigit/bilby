package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.bilby.player.DesktopPlaybackHost
import dev.bilby.ui.LocalPlaybackHost

@Composable
internal actual fun rememberWindowBrightness(): LevelControl? = null

/** 调的是播放器自己的软件音量,见 [PlaybackHost.softwareVolume]。 */
@Composable
internal actual fun rememberMediaVolume(): LevelControl? {
    val host = LocalPlaybackHost.current.takeIf { it.hasSoftwareVolume } ?: return null
    return remember(host) {
        object : LevelControl {
            override fun current(): Float = if (host.softwareMuted.value) 0f else host.softwareVolume.value
            override fun set(fraction: Float) {
                host.setSoftwareVolume(fraction)
            }
        }
    }
}
