package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bilby.player.MpvPlayerHandle
import dev.bilby.player.PlayerHandle
import org.openani.mediamp.mpv.compose.MpvMediampPlayerSurface

@Composable
actual fun VideoSurface(player: PlayerHandle, modifier: Modifier) {
    MpvMediampPlayerSurface((player as MpvPlayerHandle).player.mediamp, modifier)
}

private object NoPictureInPicture : PictureInPicture {
    override val supported: Boolean = false
    override fun enter(aspect: Float?) = Unit
}

@Composable
actual fun rememberPictureInPicture(): PictureInPicture = NoPictureInPicture

@Composable
actual fun rememberIsInPipMode(): Boolean = false
