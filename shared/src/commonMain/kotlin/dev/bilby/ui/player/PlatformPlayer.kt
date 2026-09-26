package dev.bilby.ui.player

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.bilby.player.PlayerHandle

/**
 * 画面本身。Android 上是 media3 的 PlayerSurface,Surface 经 MediaController 送到 session 那一侧;
 * 桌面上是 mpv 渲染进 Compose 的那块纹理。
 */
@Composable
expect fun VideoSurface(player: PlayerHandle, modifier: Modifier = Modifier)

/**
 * 画中画。Android 是系统的小窗;桌面是把主窗口本身缩小、去掉标题栏(见桌面入口的 WindowsPip)。
 * 两边都是"整个窗口只剩画面",页面层只认 [rememberIsInPipMode],不分平台。
 */
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
 * 自己画按钮的画中画(桌面)交给播放器壳的窗口级动作。小窗里的播放、暂停、加载与平时是同一个壳,
 * 只是换成精简形态(见 PlayerShell 的 pip);壳够不着窗口,置顶、回到窗口、拖动靠这里。
 *
 * Android 的小窗由系统接管,触摸不会交给应用,[rememberPipWindow] 给 null。
 */
interface PipWindow {
    /** 小窗是否置顶。快照状态。 */
    val pinned: Boolean

    fun setPinned(pinned: Boolean)

    /** 回到原来的窗口。不在小窗里时什么也不做。 */
    fun exit()

    /** 拖动窗口:按下时记下起点,之后每次移动按指针在屏幕上的位置重摆。 */
    fun startMove()

    fun move()

    /** 拖边改大小,锁定画面比例。按下时记下起点与拖的是哪条边,之后每次移动重算。 */
    fun startResize(edge: PipEdge)

    fun resize()
}

/**
 * 小窗的一条边或一个角。[horizontal] 与 [vertical] 是这条边朝外的方向:左 −1、右 1、不动 0,
 * 上 −1、下 1。拖动时对边(对角)不动。
 */
enum class PipEdge(val horizontal: Int, val vertical: Int) {
    Left(-1, 0),
    Right(1, 0),
    Top(0, -1),
    Bottom(0, 1),
    TopLeft(-1, -1),
    TopRight(1, -1),
    BottomLeft(-1, 1),
    BottomRight(1, 1),
}

@Composable
expect fun rememberPipWindow(): PipWindow?

/** 小窗四边四角的拖动区,盖在最上层。只有 [rememberPipWindow] 非 null 的平台会调到。 */
@Composable
internal expect fun BoxScope.PipResizeHandles(window: PipWindow)

/**
 * 画面此刻的宽高比,还没量到时是 null。按像素宽高比校正过:部分流的像素不是正方形,只看
 * 宽高会把小窗开成错的形状。
 */
fun PlayerHandle?.videoAspect(): Float? =
    this?.videoSize?.takeIf { it.width > 0 && it.height > 0 }
        ?.let { it.width * it.pixelWidthHeightRatio / it.height }
