package dev.bilby.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bilby.player.PlayerHandle

/**
 * 画面本身。Android 上是 media3 的 PlayerSurface,Surface 经 MediaController 送到 session 那一侧;
 * 桌面上是 mpv 渲染进 Compose 的那块纹理。
 */
@Composable
expect fun VideoSurface(player: PlayerHandle, modifier: Modifier = Modifier)

/** 画中画。只有 Android 有,桌面上 [supported] 恒为 false,按钮不出现。 */
interface PictureInPicture {
    val supported: Boolean

    /** 进画中画。[aspect] 是画面的宽高比,未知时交给系统。 */
    fun enter(aspect: Float?)
}

@Composable
expect fun rememberPictureInPicture(): PictureInPicture

/** 窗口此刻在不在画中画里,随进出变化。 */
@Composable
expect fun rememberIsInPipMode(): Boolean

/**
 * 画面此刻的宽高比,还没量到时是 null。按像素宽高比校正过:部分流的像素不是正方形,只看
 * 宽高会把小窗开成错的形状。
 */
fun PlayerHandle?.videoAspect(): Float? =
    this?.videoSize?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width * it.pixelWidthHeightRatio / it.height }
