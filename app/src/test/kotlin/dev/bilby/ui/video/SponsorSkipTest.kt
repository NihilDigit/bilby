package dev.bilby.ui.video

import dev.bilby.data.SponsorSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 只覆盖 nextSkipTarget 真会出错的三个分支,不把"落在片段中间"这种直观情况再断言一遍。
 */
class SponsorSkipTest {

    @Test
    fun `位置正好落在片段起点也要跳`() {
        val segments = listOf(segment(start = 5000, end = 8000))

        assertEquals(8000L, nextSkipTarget(positionMillis = 5000, segments = segments))
    }

    @Test
    fun `位置在两个相邻片段之间不跳`() {
        val segments = listOf(
            segment(start = 0, end = 3000),
            segment(start = 6000, end = 9000),
        )

        assertNull(nextSkipTarget(positionMillis = 4500, segments = segments))
    }

    @Test
    fun `片段末尾与视频结尾重合时照常跳到末尾`() {
        // 视频总长 10000ms,片尾片段一路延伸到最后一帧,没有"之后还有内容"可以对照。
        val segments = listOf(segment(start = 9000, end = 10000))

        assertEquals(10000L, nextSkipTarget(positionMillis = 9500, segments = segments))
    }

    private fun segment(start: Long, end: Long) =
        SponsorSegment(startMillis = start, endMillis = end, category = "outro", uuid = "")
}
