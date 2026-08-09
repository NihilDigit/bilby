package dev.bilby.danmaku

import dev.nihildigit.danmaku.DanmakuMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * B 站模式号 -> 库中立模型的映射,只测 notes/danmaku.md §1.4 明确定义的边界值,不复述
 * "1 映射到 SCROLL"这种能从代码一眼看出来的分支。
 */
class DanmakuMapperTest {

    @Test
    fun `1、2、3 都映射到滚动`() {
        listOf(1, 2, 3).forEach { mode ->
            assertEquals(DanmakuMode.SCROLL, raw(mode = mode).toDanmakuOrNull()?.mode)
        }
    }

    @Test
    fun `4 是底端,5 是顶端`() {
        assertEquals(DanmakuMode.BOTTOM, raw(mode = 4).toDanmakuOrNull()?.mode)
        assertEquals(DanmakuMode.TOP, raw(mode = 5).toDanmakuOrNull()?.mode)
    }

    @Test
    fun `6(逆向)退化成滚动,不是单独一种模式`() {
        assertEquals(DanmakuMode.SCROLL, raw(mode = 6).toDanmakuOrNull()?.mode)
    }

    @Test
    fun `7 不进普通弹幕那一层,8、9 整条丢弃`() {
        // mode 7 走 special 那条独立的路,这里给它一份合法的 content;8/9 的内容是脚本,
        // 明确不支持,两者都不该出现在任何一份输出里。
        val segment = listOf(
            raw(mode = 7, content = SPECIAL_CONTENT),
            raw(mode = 8, content = "some script"),
            raw(mode = 9, content = "def anim{}"),
        ).toMappedSegment()
        assertEquals(emptyList<Any>(), segment.normal)
        assertEquals(1, segment.special.size)
        listOf(7, 8, 9).forEach { mode ->
            assertNull(raw(mode = mode).toDanmakuOrNull())
        }
    }

    @Test
    fun `idStr 为空时退回数字 id 的字符串形式`() {
        val danmaku = raw(mode = 1, id = 42, idStr = "").toDanmakuOrNull()
        assertEquals("42", danmaku?.id)
    }

    @Test
    fun `fontSize 恒为 null,不透传 B 站原始档位`() {
        // B 站 protobuf 里的 fontSize 是网页播放器自己的档位(18/25/36 = 小/标准/大),单位是
        // 以 25 为基准的 CSS px,不是 sp——当成 sp 直接喂渲染层会画出巨大的字(见 DanmakuMapper
        // 的注释)。字号基准该由渲染层按画面尺寸定,mapper 这一层不该替它做主。
        assertNull(raw(mode = 1, fontSize = 0).toDanmakuOrNull()?.fontSize)
        assertNull(raw(mode = 1, fontSize = 25).toDanmakuOrNull()?.fontSize)
    }

    private fun raw(
        mode: Int,
        id: Long = 1,
        idStr: String = "1",
        fontSize: Int = 0,
        content: String = "test",
    ) = RawDanmakuElem(
        id = id,
        idStr = idStr,
        progressMillis = 1000,
        mode = mode,
        fontSize = fontSize,
        color = 0xFFFFFF,
        content = content,
    )

    private companion object {
        const val SPECIAL_CONTENT =
            """[100,200,"1-0",4,"hi",0,0,300,400,1000,0,1,"黑体",0]"""
    }
}
