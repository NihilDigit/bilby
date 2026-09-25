package dev.bilby.ui.components

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内表情的高度只有一条判据能出错:**上限是不是这一行的行高**。装扮表情按基准翻倍是 44sp,
 * 而动态正文的行高只有 26sp —— Compose 的 `lineHeight` 是硬定的行距,占位符比它高时会压到
 * 上下两行的字上,表现是表情糊在前后行的文字上、自己还被裁掉一截。
 *
 * 数值都取三处正文的真实取值(动态 16/26sp、楼中楼 20sp),照实现抄一遍公式测不出这件事。
 */
class InlineEmoteTest {

    /** 动态正文:bodyLarge 16sp,行高 26sp。 */
    private val dynamicBody = TextStyle(fontSize = 16.sp, lineHeight = 26.sp)

    /** 楼中楼比主楼矮一档。 */
    private val replyBody = TextStyle(fontSize = 13.sp, lineHeight = 20.sp)

    @Test
    fun `行高放得下时小表情拿基准值`() {
        assertEquals(22.sp, inlineEmoteSize(dynamicBody))
    }

    @Test
    fun `装扮表情被行高截住,不撑到两倍`() {
        assertEquals(26.sp, inlineEmoteSize(dynamicBody, scale = 2f))
    }

    /** 上限跟着传进来的 style 走,不是一个写死的数:楼中楼那一档里表情还要再收一截。 */
    @Test
    fun `楼中楼的表情比主楼小`() {
        val inReply = inlineEmoteSize(replyBody, scale = 2f)

        assertEquals(20.sp, inReply)
        assertTrue(inReply.value < inlineEmoteSize(dynamicBody, scale = 2f).value)
    }

    @Test
    fun `没有行高时不截断`() {
        val noLineHeight = TextStyle(fontSize = 16.sp, lineHeight = TextUnit.Unspecified)

        assertEquals(44.sp, inlineEmoteSize(noLineHeight, scale = 2f))
    }

    /**
     * 行高写成 em 时它是字号的倍数,和 sp 没有共同单位,拿 `TextUnit.value` 直接比大小会得出
     * "行高是 2"这种结论,把表情压成 2sp;取 `TextUnit.sp` 则直接抛异常。两样都不能发生。
     */
    @Test
    fun `行高按 em 给时既不截断也不抛异常`() {
        val emLineHeight = TextStyle(fontSize = 16.sp, lineHeight = 2.em)

        assertEquals(44.sp, inlineEmoteSize(emLineHeight, scale = 2f))
    }
}
