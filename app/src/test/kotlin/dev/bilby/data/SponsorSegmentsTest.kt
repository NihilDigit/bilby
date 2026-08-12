package dev.bilby.data

import dev.bilby.api.dto.SponsorBlockSegmentDto
import dev.bilby.ui.video.nextSkipTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `computeSponsorSegments` 建立的是 `nextSkipTarget` 明写着要求的那个前提:**按起点升序、
 * 互不重叠**。前提破了不报错 —— 那个函数遇到乱序会提前返回 null,表现是"片段就是不跳",
 * 屏幕上没有任何东西能指向这里。所以测的是这条前提,不是逐行复述实现。
 *
 * 每个用例都配一个 `nextSkipTarget` 的断言:光看输出形状对不对,不如直接问"这样跳得对吗"。
 */
class SponsorSegmentsTest {

    @Test
    fun `服务端乱序下发时按起点排好`() {
        val segments = computeSponsorSegments(
            listOf(dto(60.0, 70.0), dto(10.0, 20.0), dto(40.0, 45.0)),
        )

        assertEquals(listOf(10_000L, 40_000L, 60_000L), segments.map { it.startMillis })
        // 排序没做的话,10s 那一段排在第二位,循环在第一段的起点(60s)就返回 null 了。
        assertEquals(20_000L, nextSkipTarget(positionMillis = 15_000, segments = segments))
    }

    @Test
    fun `后一段完全包在前一段里时不缩短终点`() {
        val segments = computeSponsorSegments(listOf(dto(10.0, 60.0), dto(20.0, 30.0)))

        assertEquals(1, segments.size)
        // 用 endMillis 直接覆盖(而不是取 max)会把终点缩到 30s,于是 30–60s 这段广告照放。
        assertEquals(60_000L, segments.single().endMillis)
        assertEquals(60_000L, nextSkipTarget(positionMillis = 25_000, segments = segments))
    }

    @Test
    fun `首尾相接的两段合成一段`() {
        val segments = computeSponsorSegments(listOf(dto(10.0, 20.0), dto(20.0, 30.0)))

        assertEquals(1, segments.size)
        // 不合并的话第一次跳到 20s,位置监听立刻又命中第二段再跳一次 —— 跳过提示会闪两回。
        assertEquals(30_000L, nextSkipTarget(positionMillis = 15_000, segments = segments))
    }

    @Test
    fun `零长片段被丢掉`() {
        val segments = computeSponsorSegments(listOf(dto(10.0, 10.0)))

        assertEquals(emptyList<SponsorSegment>(), segments)
    }

    /**
     * 留着零长片段的后果不是"多跳一次",是**跳不出去**:目标等于当前位置,seek 到原地之后
     * 位置监听再次命中同一段。这里直接把那个循环摆出来。
     */
    @Test
    fun `零长片段若不丢掉会让跳转停在原地`() {
        val degenerate = listOf(SponsorSegment(10_000, 10_000, "sponsor", ""))

        assertNull(nextSkipTarget(positionMillis = 10_000, segments = degenerate))
    }

    @Test
    fun `空降点与整片打标不当作可跳过区间`() {
        val segments = computeSponsorSegments(
            listOf(
                dto(10.0, 20.0, actionType = "poi_highlight"),
                dto(30.0, 40.0, actionType = "full"),
                dto(50.0, 60.0, actionType = null),
            ),
        )

        // actionType 缺省即 skip,所以只剩最后那一条。
        assertEquals(listOf(50_000L), segments.map { it.startMillis })
    }

    private fun dto(start: Double, end: Double, actionType: String? = "skip") =
        SponsorBlockSegmentDto(
            category = "sponsor",
            actionType = actionType,
            segment = listOf(start, end),
            uuid = "",
        )
}
