package dev.bilby.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接解析值得测:输入来自剪贴板和别的应用,形状由 B 站决定,我们只能穷举见过的那些。
 * av→BV 那一步单独测,见 [dev.bilby.BvidCodecTest]。
 */
class BilbyLinkTest {

    @Test
    fun `认得出视频、直播和空间`() {
        assertEquals(
            Video("BV1xx411c7mD"),
            BilbyLink.destinationOf("https://www.bilibili.com/video/BV1xx411c7mD"),
        )
        assertEquals(
            Video("BV17x411w7KC"),
            BilbyLink.destinationOf("https://m.bilibili.com/video/av170001"),
        )
        assertEquals(LiveRoom(21452505L), BilbyLink.destinationOf("https://live.bilibili.com/21452505"))
        assertEquals(Space(2L), BilbyLink.destinationOf("https://space.bilibili.com/2"))
    }

    @Test
    fun `查询串和结尾斜杠不影响判断`() {
        assertEquals(
            Video("BV1xx411c7mD"),
            BilbyLink.destinationOf("https://www.bilibili.com/video/BV1xx411c7mD/?p=3&t=12"),
        )
        assertEquals(
            LiveRoom(1L),
            BilbyLink.destinationOf("https://live.bilibili.com/1?broadcast_type=0"),
        )
    }

    /**
     * 专栏的两套编号必须分开。认错一套的表现不是打不开,而是**打开另一篇文章** ——
     * cv123 和 opus/123 都存在,内容毫无关系。
     */
    @Test
    fun `专栏分得清 opus 与 cv 两套编号`() {
        assertEquals(
            ArticlePage("998", isRead = false),
            BilbyLink.destinationOf("https://www.bilibili.com/opus/998"),
        )
        assertEquals(
            ArticlePage("123", isRead = true),
            BilbyLink.destinationOf("https://www.bilibili.com/read/cv123"),
        )
        // 手机端给的是不带 cv 前缀的那种。
        assertEquals(
            ArticlePage("123", isRead = true),
            BilbyLink.destinationOf("https://m.bilibili.com/read/123/?from=share"),
        )
        // 编号位不是数字就不认,不要构造一个取不到内容的目的地。
        assertNull(BilbyLink.destinationOf("https://www.bilibili.com/read/mobile"))
    }

    @Test
    fun `非 UGC 与站外链接一律不认`() {
        // 番剧、影视、课堂是 Non-Goal(版权),不是"还没做"。
        assertNull(BilbyLink.destinationOf("https://www.bilibili.com/bangumi/play/ep123456"))
        assertNull(BilbyLink.destinationOf("https://www.bilibili.com/cheese/play/ss123"))
        assertNull(BilbyLink.destinationOf("https://example.com/video/BV1xx411c7mD"))
        // 长得像 B 站的域名不能放行:前缀式和后缀式各来一条。
        assertNull(BilbyLink.destinationOf("https://bilibili.com.evil.example/video/BV1xx411c7mD"))
        assertNull(BilbyLink.destinationOf("https://evilbilibili.com/video/BV1xx411c7mD"))
    }

    @Test
    fun `从分享出来的那段话里挑出链接`() {
        val shared = "【标题很长】 https://b23.tv/AbCdEf 复制这段内容打开哔哩哔哩"
        assertEquals("https://b23.tv/AbCdEf", BilbyLink.extractUrl(shared))
        assertTrue(BilbyLink.isShortLink("https://b23.tv/AbCdEf"))
        assertTrue(!BilbyLink.isShortLink("https://www.bilibili.com/video/BV1xx411c7mD"))
    }
}
