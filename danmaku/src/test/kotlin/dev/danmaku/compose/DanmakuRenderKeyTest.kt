package dev.danmaku.compose

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 缓存 key 的正确性。这是分级绘制后端里唯一在单测里抓得住的东西:逐帧绘制要真实 Canvas 和
 * GPU,单测里测不了,不硬写。
 *
 * 两个方向都要盯:key **多**了不影响排版的字段(颜色),命中率会随颜色数成倍下降,画面正确
 * 但性能白丢;key **少**了影响排版的字段(字号、字体族、字重、locale),会拿别的排版去画,
 * 表现是宽度错、位置错,而排布期算出来的 widthPx 还是对的 —— 那种错很难从画面反推。
 */
class DanmakuRenderKeyTest {

    private fun danmaku(text: String, color: Int = 0xFFFFFF, fontSize: Int? = null) =
        Danmaku(id = "$text-$color-$fontSize", playTimeMillis = 0L, mode = DanmakuMode.SCROLL, color = color, text = text, fontSize = fontSize)

    @Test
    fun `same text different color shares one layout`() {
        val style = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val white = layoutKeyOf(danmaku("哈哈哈", color = 0xFFFFFF), style)
        val red = layoutKeyOf(danmaku("哈哈哈", color = 0xFF0000), style)
        assertEquals(white, red)
    }

    @Test
    fun `same text different color needs separate display lists`() {
        val style = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val white = layerKeyOf(danmaku("哈哈哈", color = 0xFFFFFF), style)
        val red = layerKeyOf(danmaku("哈哈哈", color = 0xFF0000), style)
        assertNotEquals(white, red)
    }

    @Test
    fun `same text different font size are two layouts`() {
        val style = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val base = layoutKeyOf(danmaku("哈哈哈"), style)
        val larger = layoutKeyOf(danmaku("哈哈哈", fontSize = 25), style)
        assertNotEquals(base, larger)
    }

    @Test
    fun `per danmaku font size wins over the global one`() {
        val style18 = DanmakuRenderStyle(globalFontSizeSp = 18f)
        val style25 = DanmakuRenderStyle(globalFontSizeSp = 25f)
        // 两条弹幕自带同一个字号,全局字号不同 —— 排版相同,必须是同一个 key。
        assertEquals(
            layoutKeyOf(danmaku("哈哈哈", fontSize = 20), style18),
            layoutKeyOf(danmaku("哈哈哈", fontSize = 20), style25),
        )
    }

    @Test
    fun `layout affecting style attributes are all in the key`() {
        val base = DanmakuRenderStyle(baseTextStyle = TextStyle(fontSize = 18.sp), globalFontSizeSp = null)
        val d = danmaku("哈哈哈")
        val baseKey = layoutKeyOf(d, base)

        val family = base.copy(baseTextStyle = base.baseTextStyle.copy(fontFamily = FontFamily.Monospace))
        val weight = base.copy(baseTextStyle = base.baseTextStyle.copy(fontWeight = FontWeight.Bold))
        val locale = base.copy(baseTextStyle = base.baseTextStyle.copy(localeList = LocaleList("ja-JP")))

        assertNotEquals(baseKey, layoutKeyOf(d, family))
        assertNotEquals(baseKey, layoutKeyOf(d, weight))
        assertNotEquals(baseKey, layoutKeyOf(d, locale))
    }
}
