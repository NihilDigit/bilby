package dev.bilby.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.bilby.ui.comment.settleCommentSubmission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmoteInsertTest {
    @Test
    fun `inserts emote token at cursor and advances cursor past token`() {
        val initial = TextFieldValue(text = "你好世界", selection = TextRange(2))
        val updated = initial.insertAtCursor("[doge]")
        assertEquals("你好[doge]世界", updated.text)
        assertEquals(TextRange(8), updated.selection)
    }

    @Test
    fun `replaces selected range with emote token`() {
        val initial = TextFieldValue(text = "abXXXXcd", selection = TextRange(2, 6))
        val updated = initial.insertAtCursor("[妙啊]")
        assertEquals("ab[妙啊]cd", updated.text)
        assertEquals(TextRange(6), updated.selection)
    }

    @Test
    fun `refuses partial insertion when exceeding max length`() {
        val initial = TextFieldValue(text = "12345678", selection = TextRange(8))
        val unchanged = initial.insertAtCursor("[doge]", maxLength = 10)
        assertEquals(initial, unchanged)
    }

    @Test
    fun `settled keyboard height preserves tall keyboards above 360dp and freezes while hiding`() {
        val tracker = SettledKeyboardHeightTracker()
        // 高分屏带工具栏软键盘净高 392dp(不被 360dp 截断)
        assertEquals(392f, tracker.onFrame(currentImeDp = 392f, targetImeDp = 392f), 0f)
        // 切换表情面板后键盘逐帧收起:392 -> 310 -> 200 -> 80 -> 0,每帧 (current + complement) 恒为 392dp
        for (current in listOf(392f, 310f, 200f, 80f, 0f)) {
            val complement = tracker.complementPanelDp(currentImeDp = current, targetImeDp = 0f)
            assertEquals("total height must remain 392dp at current=$current", 392f, current + complement, 0.001f)
        }
    }

    @Test
    fun `comment submission settlement only clears matching target draft and preserves other active reply`() {
        val drafts = mutableMapOf(101L to "给 A 的回复", 202L to "给 B 的草稿")
        // A 的请求在途时用户已切去编辑 B (activeTarget = 202L);A 成功回来只删 A 的草稿,不删 B、也不关 B 的浮层
        val shouldClose = settleCommentSubmission(
            drafts = drafts,
            sentTarget = 101L,
            sentText = "给 A 的回复",
            composerOpen = true,
            activeTarget = 202L,
        )
        assertFalse(shouldClose)
        assertFalse(101L in drafts)
        assertEquals("给 B 的草稿", drafts[202L])

        // 当前浮层仍在 B 上且 B 发送成功 -> 清掉 B 草稿并请求关闭浮层
        val closeB = settleCommentSubmission(
            drafts = drafts,
            sentTarget = 202L,
            sentText = "给 B 的草稿",
            composerOpen = true,
            activeTarget = 202L,
        )
        assertTrue(closeB)
        assertTrue(drafts.isEmpty())
    }
}
