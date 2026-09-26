package dev.bilby.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser
import dev.bilby.ui.player.DesktopPip
import dev.bilby.ui.player.PipEdge
import java.awt.Dimension
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 画中画:主窗口本身缩成屏幕右下角的一块小画面,去掉标题栏,默认置顶。
 *
 * **缩的是主窗口,不另开一个。** mpv 的画面经 mediamp 共用 Skiko 的 D3D 设备,画面挪到第二个
 * 窗口就要换一块 surface、重建交换链;无边框全屏那边已经见过交换链重建时 Skiko 崩溃(见
 * [WindowsFullscreen])。而且 Android 的画中画也是整个 Activity 缩成小窗,页面层只看"在不在
 * 小窗里"一个状态,两边共用同一套布局。
 *
 * 换样式的手法同 [WindowsFullscreen]:窗口一直是普通窗口,只改样式、位置与大小。标题栏与边框
 * 一并去掉,窗口矩形就是画面;移动靠拖画面,改大小靠 Compose 画的拖边(PipResizeHandles)。
 *
 * **系统边框(WS_THICKFRAME)不留。** 只去标题栏、留边框的话,Windows 10 起顶上会画出一条白边
 * (那是边框的上沿),左右两圈边框又让客户区比例偏离画面,画面两侧出黑条。要留系统边框就得
 * 接管 WM_NCCALCSIZE,等于在 JVM 里挂一个窗口过程回调,和 Skiko 争同一个窗口的消息;自己画
 * 拖边还能锁定比例,系统边框做不到。
 *
 * @param isMaximized 进小窗那一刻窗口是不是最大化的,退出时还原成最大化。
 * @param normalMinimumSize 平时的最小尺寸。小窗要比它小得多,进出时切换。
 */
internal class WindowsPip(
    private val window: ComposeWindow,
    private val fullscreen: WindowsFullscreen,
    private val isMaximized: () -> Boolean,
    private val normalMinimumSize: Dimension,
) : DesktopPip {

    override var active by mutableStateOf(false)
        private set

    // 不写成带 private set 的 var:那个 setter 在 JVM 上就叫 setPinned,和接口的方法撞名。
    private var pinnedState by mutableStateOf(true)
    override val pinned: Boolean get() = pinnedState

    private var saved: Saved? = null

    private class Saved(
        val style: Int,
        val exStyle: Int,
        val rect: RECT,
        val maximized: Boolean,
        val fullscreen: Boolean,
    )

    private var moveFromPointer: Point? = null
    private var moveFromWindow: Point? = null

    /** 这一次小窗的画面比例(宽 / 高),改大小时锁住它。 */
    private var ratio = DefaultAspect
    private var resizeEdge: PipEdge? = null
    private var resizeFromPointer: Point? = null
    private var resizeFromBounds: Rectangle? = null

    override fun enter(aspect: Float?) {
        if (saved != null) return
        // 全屏里进小窗:先退全屏拿回原来的样式,出小窗时再进回去。页面上的全屏状态一直没变,
        // 回来时窗口和它对得上。
        val wasFullscreen = fullscreen.isFullscreen
        if (wasFullscreen) fullscreen.exit()
        val maximized = isMaximized()
        val hwnd = hwnd()
        val user32 = User32.INSTANCE
        // 先还原再记位置:最大化状态下 GetWindowRect 取到的是铺满工作区的那个矩形。
        if (maximized) user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
        val style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        val exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        val rect = RECT().also { user32.GetWindowRect(hwnd, it) }
        saved = Saved(style, exStyle, rect, maximized, wasFullscreen)

        // 尺寸下限由 [resize] 自己按比例管,AWT 这一道只要别比它大。
        window.minimumSize = Dimension(1, 1)
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style and (WinUser.WS_CAPTION or WinUser.WS_THICKFRAME).inv())
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle and EdgeStyles.inv())

        // 长边 480dp,短边按画面比例;比例未知或离谱时按 16:9。放在当前显示器工作区的右下角,
        // 让开任务栏,离边 16dp。
        val scale = window.graphicsConfiguration.defaultTransform.scaleX
        val ratio = aspect?.takeIf { it in MinAspect..MaxAspect } ?: DefaultAspect
        this.ratio = ratio
        val longSide = PipLongSideDp * scale
        val (width, height) = if (ratio >= 1f) {
            longSide to longSide / ratio
        } else {
            longSide * ratio to longSide
        }
        val margin = (PipMarginDp * scale).roundToInt()
        val monitor = WinUser.MONITORINFO()
        user32.GetMonitorInfo(user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitor)
        monitor.rcWork.run {
            val w = width.roundToInt()
            val h = height.roundToInt()
            user32.SetWindowPos(hwnd, null, right - margin - w, bottom - margin - h, w, h, RepositionFlags)
        }
        window.isAlwaysOnTop = pinned
        active = true
    }

    override fun exit() {
        val state = saved ?: return
        saved = null
        active = false
        window.isAlwaysOnTop = false
        val hwnd = hwnd()
        val user32 = User32.INSTANCE
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, state.style)
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, state.exStyle)
        state.rect.run {
            user32.SetWindowPos(hwnd, null, left, top, right - left, bottom - top, RepositionFlags)
        }
        window.minimumSize = normalMinimumSize
        if (state.maximized) user32.ShowWindow(hwnd, WinUser.SW_MAXIMIZE)
        if (state.fullscreen) fullscreen.enter(maximized = state.maximized)
    }

    /** 置顶这一项在小窗之间记住:关掉置顶的人多半下一次也不要。只记在这次运行里。 */
    override fun setPinned(pinned: Boolean) {
        pinnedState = pinned
        if (active) window.isAlwaysOnTop = pinned
    }

    /**
     * 拖动按指针在屏幕上的位置算,不按 Compose 给的位移:窗口跟着指针走,指针相对窗口几乎
     * 不动,按窗口内坐标算出来的位移每一帧都接近零,窗口会一顿一顿地跟不上。
     */
    override fun startMove() {
        moveFromPointer = MouseInfo.getPointerInfo()?.location
        moveFromWindow = window.location
    }

    override fun move() {
        val fromPointer = moveFromPointer ?: return
        val fromWindow = moveFromWindow ?: return
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        window.setLocation(fromWindow.x + pointer.x - fromPointer.x, fromWindow.y + pointer.y - fromPointer.y)
    }

    override fun startResize(edge: PipEdge) {
        resizeEdge = edge
        resizeFromPointer = MouseInfo.getPointerInfo()?.location
        resizeFromBounds = window.bounds
    }

    /**
     * 按指针走过的距离算新宽度,高度跟着比例走,对边(对角)不动;拖上下边时左右对称地收放。
     * 角上横竖两个方向各折算出一个宽度,取变化大的那个,手往哪边拉得多就听哪边的。
     *
     * 坐标全用 AWT 的逻辑坐标(窗口边界与 MouseInfo 同一套),不混用物理像素。
     */
    override fun resize() {
        val edge = resizeEdge ?: return
        val fromPointer = resizeFromPointer ?: return
        val from = resizeFromBounds ?: return
        val pointer = MouseInfo.getPointerInfo()?.location ?: return
        val dx = pointer.x - fromPointer.x
        val dy = pointer.y - fromPointer.y

        val widthFromX = from.width + edge.horizontal * dx.toFloat()
        val widthFromY = (from.height + edge.vertical * dy) * ratio
        val wanted = when {
            edge.vertical == 0 -> widthFromX
            edge.horizontal == 0 -> widthFromY
            abs(widthFromX - from.width) >= abs(widthFromY - from.width) -> widthFromX
            else -> widthFromY
        }
        val screen = window.graphicsConfiguration.bounds
        // 长边不短于 240、不长过所在显示器;两条换算成宽度再夹。
        val minWidth = if (ratio >= 1f) PipMinLongSide else PipMinLongSide * ratio
        val maxWidth = minOf(screen.width.toFloat(), screen.height * ratio)
        val width = wanted.coerceIn(minWidth, maxWidth.coerceAtLeast(minWidth))
        val height = width / ratio

        val x = when (edge.horizontal) {
            -1 -> from.x + from.width - width
            0 -> from.x + (from.width - width) / 2
            else -> from.x.toFloat()
        }
        val y = when (edge.vertical) {
            -1 -> from.y + from.height - height
            0 -> from.y + (from.height - height) / 2
            else -> from.y.toFloat()
        }
        window.setBounds(x.roundToInt(), y.roundToInt(), width.roundToInt(), height.roundToInt())
    }

    private fun hwnd() = HWND(Pointer(window.windowHandle))

    private companion object {
        const val PipLongSideDp = 480f
        const val PipMarginDp = 16f
        const val PipMinLongSide = 240f
        const val DefaultAspect = 16f / 9f
        const val MinAspect = 1f / 2.39f
        const val MaxAspect = 2.39f

        const val RepositionFlags = WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE or WinUser.SWP_FRAMECHANGED
    }
}
