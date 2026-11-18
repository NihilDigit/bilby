package dev.bilby.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutTest {

    @Test
    fun `全屏按设定列数排布且半屏向上折算避免变成过宽两列`() {
        val display = 1536.dp

        // 全屏(扣除 80dp 侧栏约 1440dp)应正好保持设定的 5 列
        assertEquals(5, calculateVideoGridColumnCount(1440.dp, display, configuredColumns = 5))
        assertEquals(5, calculateVideoGridColumnCount(1536.dp, display, configuredColumns = 5))

        // 半屏(扣除侧栏约 680dp / 不扣 768dp)按比例向上折算为 3 列而不是 2 列
        assertEquals(3, calculateVideoGridColumnCount(680.dp, display, configuredColumns = 5))
        assertEquals(3, calculateVideoGridColumnCount(768.dp, display, configuredColumns = 5))

        // 侧栏展开后约 1040dp 折算为 4 列
        assertEquals(4, calculateVideoGridColumnCount(1040.dp, display, configuredColumns = 5))
    }

    @Test
    fun `支持调小或调大列数并受最小卡片宽度保护`() {
        val display = 1536.dp
        assertEquals(3, calculateVideoGridColumnCount(1440.dp, display, configuredColumns = 3))
        assertEquals(2, calculateVideoGridColumnCount(680.dp, display, configuredColumns = 3))
        assertEquals(7, calculateVideoGridColumnCount(1440.dp, display, configuredColumns = 7))
        assertEquals(4, calculateVideoGridColumnCount(680.dp, display, configuredColumns = 7))

        // 窄窗口(例如横屏手机 800dp)受最小卡片宽度下限保护，不会挤成 7 细条
        assertEquals(4, calculateVideoGridColumnCount(760.dp, 840.dp, configuredColumns = 7))
    }
}
