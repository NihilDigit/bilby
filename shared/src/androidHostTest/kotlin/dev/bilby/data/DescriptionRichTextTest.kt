package dev.bilby.data

import dev.bilby.api.dto.DescSegmentDto
import dev.bilby.data.model.RichSpan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 简介解析的边界。错了不会报错,只会是链接截错一段、号码认成别的视频、时间点跳到别处。
 */
class DescriptionRichTextTest {

    @Test
    fun `@ 段带着 mid,名字前补上 @`() {
        // 形状取自 BV1q8Mq6dE7Q 的真实 desc_v2:type 2 的 raw_text 不带 @。
        val spans = parseDescriptionSpans(
            listOf(
                DescSegmentDto(rawText = "混音：", type = 1),
                DescSegmentDto(rawText = "阿塔斯_Altas", type = 2, bizId = 1840878197),
            ),
            plain = "混音：@阿塔斯_Altas",
        )
        assertEquals(RichSpan.Mention("@阿塔斯_Altas", 1840878197), spans.last())
    }

    @Test
    fun `网址不吞后面的中文标点`() {
        val spans = parseDescriptionSpans(emptyList(), "原曲 https://www.youtube.com/watch?v=DU1HjAPvHG8。谢谢")
        val link = spans.filterIsInstance<RichSpan.Link>().single()
        assertEquals("https://www.youtube.com/watch?v=DU1HjAPvHG8", link.url)
    }

    @Test
    fun `紧挨中文的 BV 号能认,粘着字母的不认`() {
        val spans = parseDescriptionSpans(emptyList(), "上期见bv1Y4411T7xx，另见BV1Y4411T7xxabc")
        val link = spans.filterIsInstance<RichSpan.Link>().single()
        // 前缀不分大小写,链接里统一成大写 BV:BV 号本体区分大小写,前缀不区分。
        assertEquals("https://www.bilibili.com/video/BV1Y4411T7xx", link.url)
    }

    @Test
    fun `不是合法时间点的编号留作文字`() {
        val spans = parseDescriptionSpans(emptyList(), "01:30 开场  第1:99期")
        val timestamps = spans.filterIsInstance<RichSpan.Timestamp>()
        assertEquals(listOf(90_000L), timestamps.map { it.millis })
    }
}
