package dev.bilby.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 链接解析值得测:输入来自剪贴板和别的应用,形状由 B 站决定,我们只能穷举见过的那些。
 * av→BV 更是错了也不会报错,只会安静地打开一条别人的视频。
 */
class BilbyLinkTest {

    @Test
    fun `av 号按 2023 版算法转 BV`() {
        // 这一对是公开可查的:两版算法对老号给出同一个结果,拿它当锚点。
        assertEquals("BV17x411w7KC", BvidCodec.fromAid(170_001L))
        assertEquals("BV1xx411c7mD", BvidCodec.fromAid(2L))
        // 超过旧算法上限(1 shl 27)的新号,旧算法根本表示不了。
        assertEquals("BV1uawaeCEPq", BvidCodec.fromAid(113_866_688_676_587L))
    }

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
