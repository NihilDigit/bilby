package dev.bilby.ui.player

import dev.bilby.player.VideoDimensions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoAspectTest {
    @Test
    fun `首次进入与换流的未知尺寸没有兜底比例`() {
        assertNull(VideoDimensions().displayAspect())
        assertNull(VideoDimensions(1080, 0).displayAspect())
        assertNull(VideoDimensions(0, 1920).displayAspect())
        assertNull(VideoDimensions(-1, 1920).displayAspect())
    }

    @Test
    fun `连续竖屏与横竖切换按每次当前尺寸计算`() {
        val sizes = listOf(
            VideoDimensions(1080, 1920),
            VideoDimensions(1080, 1920),
            VideoDimensions(),
            VideoDimensions(720, 1280),
            VideoDimensions(1080, 1440),
            VideoDimensions(1920, 1080),
            VideoDimensions(720, 1280),
        )
        assertEquals(
            listOf(9f / 16f, 9f / 16f, null, 9f / 16f, 3f / 4f, 16f / 9f, 9f / 16f),
            sizes.map { it.displayAspect() },
        )
    }

    @Test
    fun `非方形像素只折算一次显示比例`() {
        assertEquals(16f / 9f, VideoDimensions(1440, 1080, 4f / 3f).displayAspect()!!, 0.00001f)
        assertEquals(0.5f, VideoDimensions(1080, 1080, 0.5f).displayAspect()!!, 0f)
    }

    @Test
    fun `无效像素比例不会产生无限或非数布局约束`() {
        for (par in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertEquals(9f / 16f, VideoDimensions(1080, 1920, par).displayAspect()!!, 0f)
        }
        assertNull(VideoDimensions(Int.MAX_VALUE, 1, Float.MAX_VALUE).displayAspect())
    }
}
