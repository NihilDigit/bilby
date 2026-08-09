package dev.bilby.danmaku

import dev.danmaku.compose.SpecialDanmakuEasing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * mode 7 的 `content` 是站方定的 14 元 JSON 数组,格式会变而我们收不到任何通知。这组测试守的是
 * 两件事:**畸形输入不能把整段弹幕拖崩**,以及**能救的字段不要连累整条弹幕**——alpha 坏了仍
 * 该把字画出来,坐标坏了才是真画不出来。
 *
 * 归一化的除数(1920/1080)不在测试里重写,断言直接写期望的归一化结果:参考画幅写错时两边一起
 * 错的话,这组测试就白写了。
 */
class SpecialDanmakuParserTest {

    @Test
    fun `完整数组按下标映射,坐标归一化,时长由秒转毫秒`() {
        val special = parse("""[960,540,"1-0",4,"你好",30,45,1920,1080,2000,500,1,"黑体",1]""")!!
        assertEquals(0.5f, special.fromX, EPSILON)
        assertEquals(0.5f, special.fromY, EPSILON)
        assertEquals(1f, special.toX, EPSILON)
        assertEquals(1f, special.toY, EPSILON)
        assertEquals(4000L, special.durationMillis)
        // 位移的两个量本来就是毫秒,跟下标 3 的秒不同单位。
        assertEquals(2000L, special.translationDurationMillis)
        assertEquals(500L, special.translationDelayMillis)
        assertEquals(1f, special.fromAlpha, EPSILON)
        assertEquals(0f, special.toAlpha, EPSILON)
        assertEquals(30f, special.rotateZDegrees, EPSILON)
        assertEquals(45f, special.rotateYDegrees, EPSILON)
        assertTrue(special.hasStroke)
        assertEquals(SpecialDanmakuEasing.EASE_IN_CUBIC, special.easing)
        assertEquals("你好", special.text)
    }

    @Test
    fun `数字写成字符串一样能解析`() {
        // 同一个下标在不同弹幕里两种形式都见得到,不是我们能选的。
        val special = parse("""["960","540","1-1","4","你好","0","0","960","540","0","0","1","",""]""")!!
        assertEquals(0.5f, special.fromX, EPSILON)
        assertEquals(4000L, special.durationMillis)
    }

    @Test
    fun `字段数不足直接丢,不按缺省值猜`() {
        assertNull(parse("""[960,540,"1-0",4]"""))
    }

    @Test
    fun `尾部两个字段(字体、缓动)可以缺,缓动按线性`() {
        val special = parse("""[960,540,"1-0",4,"你好",0,0,960,540,0,0,1]""")!!
        assertEquals(SpecialDanmakuEasing.LINEAR, special.easing)
    }

    @Test
    fun `content 不是 JSON 数组时丢弃`() {
        assertNull(parse("普通弹幕文本"))
        assertNull(parse("""{"x":1}"""))
        assertNull(parse(""))
    }

    @Test
    fun `alpha 格式异常不连累整条弹幕,退回不透明`() {
        val special = parse("""[960,540,"完全不是数字",4,"你好",0,0,960,540,0,0,1,"",0]""")!!
        assertEquals(1f, special.fromAlpha, EPSILON)
        assertEquals(1f, special.toAlpha, EPSILON)
    }

    @Test
    fun `alpha 两端允许负号,从最后一个减号切`() {
        val special = parse("""[960,540,"-0.2-1",4,"你好",0,0,960,540,0,0,1,"",0]""")!!
        // 负数被夹回合法区间,而不是让整条 alpha 曲线作废。
        assertEquals(0f, special.fromAlpha, EPSILON)
        assertEquals(1f, special.toAlpha, EPSILON)
    }

    @Test
    fun `坐标越出参考画幅照原样保留`() {
        // 作者常让弹幕从画外飞进来,夹回 [0,1] 等于改内容。
        val special = parse("""[-1920,540,"1-1",4,"你好",0,0,3840,540,1000,0,1,"",0]""")!!
        assertEquals(-1f, special.fromX, EPSILON)
        assertEquals(2f, special.toX, EPSILON)
    }

    @Test
    fun `坐标是 NaN 或 Infinity 时丢弃`() {
        // 这两个值会一路传进 graphicsLayer 的变换矩阵,表现是整条弹幕连同图层一起消失,
        // 而不是"某个字段没生效"——查不出来,所以在这里就拦掉。
        assertNull(parse("""[NaN,540,"1-1",4,"你好",0,0,960,540,0,0,1,"",0]"""))
        assertNull(parse("""[960,540,"1-1",Infinity,"你好",0,0,960,540,0,0,1,"",0]"""))
    }

    @Test
    fun `时长非正的弹幕丢弃`() {
        assertNull(parse("""[960,540,"1-1",0,"你好",0,0,960,540,0,0,1,"",0]"""))
        assertNull(parse("""[960,540,"1-1",-4,"你好",0,0,960,540,0,0,1,"",0]"""))
    }

    @Test
    fun `文本为空的丢弃,前后空白会被 trim`() {
        assertNull(parse("""[960,540,"1-1",4,"   ",0,0,960,540,0,0,1,"",0]"""))
        assertEquals("你好", parse("""[960,540,"1-1",4," 你好 ",0,0,960,540,0,0,1,"",0]""")!!.text)
    }

    @Test
    fun `斜杠 n 是换行转义,不是两个普通字符`() {
        val special = parse("""[960,540,"1-1",4,"上/n下",0,0,960,540,0,0,1,"",0]""")!!
        assertEquals("上\n下", special.text)
    }

    @Test
    fun `描边位为 0 时关掉描边`() {
        assertFalse(parse("""[960,540,"1-1",4,"你好",0,0,960,540,0,0,0,"",0]""")!!.hasStroke)
    }

    @Test
    fun `字号按参考画幅高度归一,协议里没给时用默认值`() {
        assertEquals(25f / 1080f, parse(FULL, fontSize = 25)!!.fontSizeFraction, EPSILON)
        assertEquals(36f / 1080f, parse(FULL, fontSize = 36)!!.fontSizeFraction, EPSILON)
        assertEquals(25f / 1080f, parse(FULL, fontSize = 0)!!.fontSizeFraction, EPSILON)
    }

    private fun parse(content: String, fontSize: Int = 25) = RawDanmakuElem(
        id = 1,
        idStr = "1",
        progressMillis = 1000,
        mode = 7,
        fontSize = fontSize,
        color = 0xFFFFFF,
        content = content,
    ).toSpecialDanmakuOrNull(index = 0)

    private companion object {
        const val EPSILON = 1e-4f
        const val FULL = """[960,540,"1-0",4,"你好",0,0,960,540,0,0,1,"",0]"""
    }
}
