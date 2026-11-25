package dev.bilby.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只测二分查找本身:边界(空列表、位置早于第一条、位置晚于最后一条)、命中、
 * 落在两句之间的空档里。跟 UI 的连线(转 State、跟播滚动)不在这里测,那属于胶水。
 */
class SubtitleCueTest {

    private fun cues() = listOf(
        SubtitleCue(fromMillis = 0, toMillis = 1_000, text = "a"),
        SubtitleCue(fromMillis = 2_000, toMillis = 3_000, text = "b"),
        SubtitleCue(fromMillis = 5_000, toMillis = 6_000, text = "c"),
    )

    @Test
    fun `空列表两个查找都给不存在`() {
        assertNull(emptyList<SubtitleCue>().cueAt(1_000))
        assertEquals(-1, emptyList<SubtitleCue>().indexNear(1_000))
    }

    @Test
    fun `位置命中某条时 cueAt 返回它`() {
        assertEquals("a", cues().cueAt(500)?.text)
        assertEquals("b", cues().cueAt(2_500)?.text)
        assertEquals("c", cues().cueAt(5_999)?.text)
    }

    @Test
    fun `位置落在两条之间的空档里,cueAt 为 null,indexNear 给最近讲完的那一条`() {
        val list = cues()
        assertNull(list.cueAt(1_500))
        assertEquals(0, list.indexNear(1_500))
    }

    @Test
    fun `位置早于第一条时 indexNear 是 -1,cueAt 也是 null`() {
        val list = cues()
        assertEquals(-1, list.indexNear(-100))
        assertNull(list.cueAt(-100))
    }

    @Test
    fun `位置晚于最后一条的 from 但已经播完,indexNear 停在最后一条`() {
        val list = cues()
        assertEquals(2, list.indexNear(10_000))
        // 早就放完了,不该继续高亮它。
        assertNull(list.cueAt(10_000))
    }

    @Test
    fun `边界值,fromMillis 命中而 toMillis 归下一次判定`() {
        val list = cues()
        // 左闭:等于 fromMillis 算命中这一条。
        assertEquals("b", list.cueAt(2_000)?.text)
        // 右开:等于 toMillis 已经不算这一条命中,但 indexNear 仍指向它(刚讲完)。
        assertNull(list.cueAt(3_000))
        assertEquals(1, list.indexNear(3_000))
    }

    @Test
    fun `长短字幕重叠时前一条不被短字幕提前截断`() {
        val list = listOf(
            SubtitleCue(fromMillis = 10_000, toMillis = 15_000, text = "long line"),
            SubtitleCue(fromMillis = 11_000, toMillis = 12_000, text = "short note"),
        )
        assertEquals("long line\nshort note", list.activeSubtitleText(11_500))
        // 13s 时短字幕已结束,但长字幕仍在有效期内
        assertEquals("long line", list.activeSubtitleText(13_000))
        assertEquals("long line", list.cueAt(13_000)?.text)
    }

    @Test
    fun `同时开始的双语字幕全部保留显示`() {
        val list = listOf(
            SubtitleCue(fromMillis = 2_000, toMillis = 5_000, text = "Hello"),
            SubtitleCue(fromMillis = 2_000, toMillis = 5_000, text = "你好"),
        )
        assertEquals("Hello\n你好", list.activeSubtitleText(3_000))
    }

    @Test
    fun `同语言家族字幕轨可跨视频回退匹配,关闭状态不自动开启`() {
        val tracks = listOf(
            SubtitleTrack(lan = "zh-CN", displayName = "中文（中国）", isAi = false, subtitleUrl = "https://a"),
            SubtitleTrack(lan = "ai-en", displayName = "英语（AI）", isAi = true, subtitleUrl = "https://b"),
        )
        assertEquals("zh-CN", tracks.findBestMatch("ai-zh")?.lan)
        assertEquals("ai-en", tracks.findBestMatch("en-US")?.lan)
        assertNull(tracks.findBestMatch(""))
        assertNull(tracks.findBestMatch("ja"))
    }
}
