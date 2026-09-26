package dev.bilby.desktop

import androidx.compose.ui.awt.ComposeWindow
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.RECT
import com.sun.jna.platform.win32.WinUser

/**
 * 无边框全屏:去掉标题栏与边框,把窗口铺满它所在的那块显示器。做法照 vlcj 的
 * `Win32FullScreenHandler`,Animeko 的桌面端也是这么做的。
 *
 * **不用 Compose 的 `WindowPlacement.Fullscreen`。** 那一档在 Windows 上走独占式全屏,
 * 切换时 Skiko 要重建 D3D 交换链,而 mpv 的画面正经 mediamp 共享着 Skiko 的 D3D 设备:
 * 实测切全屏时 Skiko 在 `Direct3DContextHandler.flush` 里崩溃(EXCEPTION_ILLEGAL_INSTRUCTION),
 * 之后画布不再跟随窗口尺寸,界面卡在左上角一块。这里窗口一直是普通窗口,只是换了样式和大小,
 * 交换链照常按尺寸变化调整。
 */
internal class WindowsFullscreen(private val window: ComposeWindow) {

    /** 进全屏之前的样式与位置,退出时原样放回。非 null 即处于全屏。 */
    private var saved: Saved? = null

    val isFullscreen: Boolean get() = saved != null

    private class Saved(val style: Int, val exStyle: Int, val rect: RECT, val maximized: Boolean)

    /** @param maximized 进全屏时窗口是不是最大化的。退出时要还原成最大化,而不是最大化那一刻的尺寸。 */
    fun enter(maximized: Boolean) {
        if (saved != null) return
        val hwnd = hwnd()
        val user32 = User32.INSTANCE
        // 先还原再记位置:最大化状态下 GetWindowRect 取到的是铺满工作区的那个矩形。
        if (maximized) user32.ShowWindow(hwnd, WinUser.SW_RESTORE)
        val style = user32.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        val exStyle = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        val rect = RECT().also { user32.GetWindowRect(hwnd, it) }
        saved = Saved(style, exStyle, rect, maximized)

        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, style and (WinUser.WS_CAPTION or WinUser.WS_THICKFRAME).inv())
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, exStyle and EdgeStyles.inv())
        val monitor = WinUser.MONITORINFO()
        user32.GetMonitorInfo(user32.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST), monitor)
        monitor.rcMonitor.run {
            user32.SetWindowPos(hwnd, null, left, top, right - left, bottom - top, RepositionFlags)
        }
    }

    fun exit() {
        val state = saved ?: return
        saved = null
        val hwnd = hwnd()
        val user32 = User32.INSTANCE
        user32.SetWindowLong(hwnd, WinUser.GWL_STYLE, state.style)
        user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, state.exStyle)
        state.rect.run {
            user32.SetWindowPos(hwnd, null, left, top, right - left, bottom - top, RepositionFlags)
        }
        if (state.maximized) user32.ShowWindow(hwnd, WinUser.SW_MAXIMIZE)
    }

    private fun hwnd() = HWND(Pointer(window.windowHandle))

    private companion object {
        const val RepositionFlags = WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE or WinUser.SWP_FRAMECHANGED
    }
}

// WinUser 里没有这几个扩展样式的常量。
private const val WS_EX_DLGMODALFRAME = 0x00000001
private const val WS_EX_WINDOWEDGE = 0x00000100
private const val WS_EX_CLIENTEDGE = 0x00000200
private const val WS_EX_STATICEDGE = 0x00020000

/** 窗口四周那一圈边的扩展样式。去掉之后窗口矩形就是画面,无边框全屏与小窗都用。 */
internal const val EdgeStyles = WS_EX_DLGMODALFRAME or WS_EX_WINDOWEDGE or WS_EX_CLIENTEDGE or WS_EX_STATICEDGE
