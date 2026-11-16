package dev.bilby

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun `formatDurationSeconds pads minutes and hours and only shows hours when positive`() {
        assertEquals("00:00", formatDurationSeconds(0L))
        assertEquals("00:05", formatDurationSeconds(5L))
        assertEquals("04:05", formatDurationSeconds(245L))
        assertEquals("59:59", formatDurationSeconds(3599L))
        assertEquals("01:02:03", formatDurationSeconds(3723L))
        assertEquals("02:50:08", formatDurationSeconds(10208L))
    }

    @Test
    fun `normalizeDurationText converts over-60-minute strings and pads components`() {
        assertEquals("04:35:48", normalizeDurationText("275:48"))
        assertEquals("04:05", normalizeDurationText("4:5"))
        assertEquals("00:08", normalizeDurationText("0:08"))
        assertEquals("01:02:03", normalizeDurationText("1:2:3"))
        assertEquals("", normalizeDurationText(""))
        assertEquals("文章", normalizeDurationText("文章"))
    }
}
