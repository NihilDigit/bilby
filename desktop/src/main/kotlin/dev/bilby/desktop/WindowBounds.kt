package dev.bilby.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import dev.bilby.BiliLog
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File
import java.util.Properties

/**
 * 窗口上次的大小、位置与是否最大化,关窗时写、启动时读。
 *
 * 读回来的位置先对一遍屏幕:上次开在副屏上、这次副屏拔了,原样恢复窗口就开在看不见的地方。
 * 标题栏那一截(左上角往里 [VisibleMarginDp])落在某块屏上才算数,否则居中打开。
 * 全屏不记:下次打开还是全屏,人多半已经忘了自己上次是怎么关的,找不到出口。
 */
internal class WindowBounds(private val file: File) {

    fun initialState(): WindowState {
        val saved = runCatching { read() }
            .onFailure { BiliLog.w("窗口位置读取失败,按默认打开", it) }
            .getOrNull()
        val size = saved?.size?.takeIf { it.width >= MinWidthDp.dp && it.height >= MinHeightDp.dp } ?: DefaultSize
        val position = saved?.position?.takeIf { onSomeScreen(it) } ?: WindowPosition.Aligned(androidx.compose.ui.Alignment.Center)
        return WindowState(
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = position,
            size = size,
        )
    }

    /** 最大化时只记"最大化"这一件事,不记那时的尺寸:还原之后要回到最大化之前的大小。 */
    fun save(state: WindowState) {
        val properties = runCatching { Properties().apply { file.inputStream().use(::load) } }.getOrDefault(Properties())
        when (state.placement) {
            WindowPlacement.Maximized -> properties["maximized"] = "true"
            WindowPlacement.Floating -> {
                properties["maximized"] = "false"
                properties["width"] = state.size.width.value.toString()
                properties["height"] = state.size.height.value.toString()
                (state.position as? WindowPosition.Absolute)?.let {
                    properties["x"] = it.x.value.toString()
                    properties["y"] = it.y.value.toString()
                }
            }
            WindowPlacement.Fullscreen -> return
        }
        runCatching { file.outputStream().use { properties.store(it, null) } }
            .onFailure { BiliLog.w("窗口位置保存失败", it) }
    }

    private class Saved(val size: DpSize?, val position: WindowPosition.Absolute?, val maximized: Boolean)

    private fun read(): Saved? {
        if (!file.isFile) return null
        val p = Properties().apply { file.inputStream().use(::load) }
        fun float(key: String) = p.getProperty(key)?.toFloatOrNull()
        val size = float("width")?.let { w -> float("height")?.let { h -> DpSize(w.dp, h.dp) } }
        val position = float("x")?.let { x -> float("y")?.let { y -> WindowPosition.Absolute(x.dp, y.dp) } }
        return Saved(size, position, p.getProperty("maximized") == "true")
    }

    /** 屏幕坐标在 AWT 里与 dp 同一单位(已按缩放折算),直接比。 */
    private fun onSomeScreen(position: WindowPosition.Absolute): Boolean {
        val probe = Rectangle(
            position.x.value.toInt() + VisibleMarginDp,
            position.y.value.toInt(),
            VisibleMarginDp,
            VisibleMarginDp,
        )
        return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
            .any { it.defaultConfiguration.bounds.intersects(probe) }
    }

    companion object {
        val DefaultSize = DpSize(1280.dp, 800.dp)

        /** 两栏布局与播放器控制条都排得开的最小尺寸。 */
        const val MinWidthDp = 480
        const val MinHeightDp = 480

        private const val VisibleMarginDp = 48
    }
}
