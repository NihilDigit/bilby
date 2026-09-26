package dev.bilby.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager
import javax.swing.JFrame
import kotlin.math.abs
import kotlin.math.round

/**
 * 让 Compose 画面与窗口像素一比一,不被系统拉伸。与 Piko 的同名做法相同。
 *
 * skiko 0.150 的 Direct3D 交换链按 SkiaLayer 的逻辑尺寸乘缩放后截断取整,承载它的 Canvas 子窗口却在
 * 乘积带 .5 时多加 1 个逻辑像素(skiko 的 adjustSizeToContentScale,为了不在窗口边缘露白线)。子窗口
 * 客户区因此比 backbuffer 大 1 到 2 个物理像素,DXGI 把整帧拉伸上去,文字与细线全被重采样而发虚。
 * 150% 下逻辑宽高任一为奇数、125% 下多数尺寸都会中招,最大化也不例外;100% 与 200% 不受影响。
 *
 * 这里把 Compose 面板的逻辑尺寸向上取到「乘以缩放恰为整数」的倍数,逻辑尺寸、子窗口与 backbuffer 三者
 * 便相等。多出的不足一步(150% 下 1 个、125% 下至多 3 个逻辑像素)落在客户区外,被父窗口裁掉;向下取
 * 会在右下露出一条窗口底色,深色主题下看得见。改用 OpenGL 同样不拉伸,但 mpv 的画面经 mediamp 共用
 * Skiko 的 D3D 设备,渲染后端只能是 Direct3D。
 */
@Composable
internal fun PixelAlignedContentEffect(window: JFrame) {
    DisposableEffect(window) {
        val pane = window.contentPane
        val original = pane.layout
        pane.layout = PixelAlignedLayout
        pane.revalidate()
        onDispose { pane.layout = original }
    }
}

private object PixelAlignedLayout : LayoutManager {
    override fun addLayoutComponent(name: String?, comp: Component?) {}
    override fun removeLayoutComponent(comp: Component?) {}
    override fun preferredLayoutSize(parent: Container): Dimension = parent.components.firstOrNull()?.preferredSize ?: Dimension()
    override fun minimumLayoutSize(parent: Container): Dimension = parent.components.firstOrNull()?.minimumSize ?: Dimension()

    // 显示器缩放变化时 skiko 会 revalidate SkiaLayer,失效一路传到 JRootPane,这里随之按新缩放重排。
    override fun layoutContainer(parent: Container) {
        val step = pixelAlignedStep(parent.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0)
        val width = roundUp(parent.width, step)
        val height = roundUp(parent.height, step)
        for (child in parent.components) child.setBounds(0, 0, width, height)
    }

    /** 乘以 [scale] 得整数的最小逻辑像素数:125% 为 4,150% 为 2,200% 为 1。 */
    private fun pixelAlignedStep(scale: Double): Int =
        (1..8).firstOrNull { abs(it * scale - round(it * scale)) < 1e-4 } ?: 1

    private fun roundUp(value: Int, step: Int): Int = (value + step - 1) / step * step
}
