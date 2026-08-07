package dev.danmaku.compose

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排布是编译期计算出来的确定性轨迹,不是运行时状态机 —— 这组测试只断言由此产生的性质
 * (同槽不碰撞、幂等、追加不破坏历史、准入判据的边界),不复述实现内部怎么算的。
 */
class DanmakuTimelineTest {

    private val charWidth = 10f

    private fun measureWidth(text: String): Float = text.length * charWidth

    private fun config(
        scrollTrackCount: Int = 4,
        topTrackCount: Int = 2,
        bottomTrackCount: Int = 2,
        overflowPolicy: OverflowPolicy = OverflowPolicy.DISCARD,
    ) = DanmakuTimelineConfig(
        screenWidthPx = 400f,
        scrollTrackCount = scrollTrackCount,
        topTrackCount = topTrackCount,
        bottomTrackCount = bottomTrackCount,
        baseSpeedPxPerMillis = 0.2f,
        fixedDurationMillis = 1_000L,
        overflowPolicy = overflowPolicy,
    )

    private fun randomPool(count: Int, seed: Int): List<Danmaku> {
        val random = Random(seed)
        val modes = DanmakuMode.entries
        return (0 until count).map { i ->
            Danmaku(
                id = "d$i",
                playTimeMillis = random.nextLong(0, 20_000),
                mode = modes[random.nextInt(modes.size)],
                color = 0xFFFFFF,
                text = "弹".repeat(random.nextInt(1, 12)),
            )
        }
    }

    // ---- 同槽无重叠 ----

    @Test
    fun `同一滚动轨道内任意时刻不发生空间重叠`() {
        val cfg = config()
        val timeline = DanmakuTimeline.compile(randomPool(300, seed = 1), cfg, ::measureWidth)

        var t = 0L
        while (t <= 25_000L) {
            assertNoScrollOverlapAt(timeline, t)
            t += 137L // 非整除步长,避免恰好踩在每条弹幕的整数边界上
        }
    }

    @Test
    fun `固定弹幕的区间调度不重叠`() {
        val cfg = config()
        val timeline = DanmakuTimeline.compile(randomPool(300, seed = 2), cfg, ::measureWidth)

        assertNoFixedOverlap(timeline, DanmakuMode.TOP)
        assertNoFixedOverlap(timeline, DanmakuMode.BOTTOM)
    }

    // ---- 幂等 ----

    @Test
    fun `compile 对同一输入两次输出逐位一致`() {
        val cfg = config()
        val pool = randomPool(150, seed = 3)

        val first = DanmakuTimeline.compile(pool, cfg, ::measureWidth).compiledEntries()
        val second = DanmakuTimeline.compile(pool, cfg, ::measureWidth).compiledEntries()

        assertEquals(first, second)
    }

    // ---- append 不破坏已编译部分 ----

    @Test
    fun `append 之后历史条目不变,新条目同样满足不重叠`() {
        val cfg = config()
        val (early, late) = randomPool(200, seed = 4).partition { it.playTimeMillis < 10_000L }
        val timeline = DanmakuTimeline.compile(early, cfg, ::measureWidth)
        val before = timeline.compiledEntries()

        late.sortedBy { it.playTimeMillis }.forEach(timeline::append)

        val after = timeline.compiledEntries()
        // 历史条目原样出现在追加后的结果里,顺序也不变 —— append 只在尾部生长。
        assertEquals(before, after.subList(0, before.size))

        var t = 0L
        while (t <= 25_000L) {
            assertNoScrollOverlapAt(timeline, t)
            t += 137L
        }
    }

    // ---- 准入判据的边界 ----

    @Test
    fun `准入判据边界 - 头部恰好追上前车尾部时拒绝`() {
        // 单轨道 + 无扰动 + 两条弹幕都不超过基准宽度,使速度恒为 baseSpeed,几何量可以手算。
        val cfg = DanmakuTimelineConfig(
            screenWidthPx = 100f,
            scrollTrackCount = 1,
            topTrackCount = 1,
            bottomTrackCount = 1,
            baseSpeedPxPerMillis = 1f,
            speedJitterFraction = 0f,
        )
        val front = Danmaku(id = "front", playTimeMillis = 0, mode = DanmakuMode.SCROLL, color = 0, text = "AB")
        // front 宽度 20px,速度 1px/ms:尾部到达左边界耗时 (100+20)/1 = 120ms。
        val timeline = DanmakuTimeline.compile(listOf(front), cfg, ::measureWidth)
        assertEquals(120L, timeline.compiledEntries().single().exitMillis)

        // back 头部到达左边界耗时 100/1=100ms;t0=19 时 edge=119 < 120,差一毫秒追上,拒绝。
        val back = Danmaku(id = "back", playTimeMillis = 19, mode = DanmakuMode.SCROLL, color = 0, text = "A")
        assertNull(timeline.append(back))
    }

    @Test
    fun `准入判据边界 - 头部恰好不追上前车尾部时接受`() {
        val cfg = DanmakuTimelineConfig(
            screenWidthPx = 100f,
            scrollTrackCount = 1,
            topTrackCount = 1,
            bottomTrackCount = 1,
            baseSpeedPxPerMillis = 1f,
            speedJitterFraction = 0f,
        )
        val front = Danmaku(id = "front", playTimeMillis = 0, mode = DanmakuMode.SCROLL, color = 0, text = "AB")
        val timeline = DanmakuTimeline.compile(listOf(front), cfg, ::measureWidth)

        // t0=20 时 edge=120 == tailOff(front)=120,边界相等,判据是 >=,应接受。
        val back = Danmaku(id = "back", playTimeMillis = 20, mode = DanmakuMode.SCROLL, color = 0, text = "A")
        val placed = timeline.append(back)

        assertNotNull(placed)
        assertEquals(0, placed!!.slot.track)
        assertEquals(20L, placed.t0Millis)
    }

    @Test
    fun `DISCARD 策略下放不下的弹幕直接丢弃,不占用轨道`() {
        val cfg = config(scrollTrackCount = 1, overflowPolicy = OverflowPolicy.DISCARD)
        val timeline = DanmakuTimeline.compile(
            listOf(Danmaku("a", 0, DanmakuMode.SCROLL, 0, "弹幕")),
            cfg,
            ::measureWidth,
        )
        val before = timeline.compiledEntries().size

        val rejected = timeline.append(Danmaku("b", 1, DanmakuMode.SCROLL, 0, "弹幕"))

        assertNull(rejected)
        assertEquals(before, timeline.compiledEntries().size)
    }

    // ---- 断言辅助:直接复用编译产物里的位置公式,不重新实现一遍算法 ----

    private fun headX(entry: CompiledDanmaku, t: Long): Float =
        400f - (t - entry.t0Millis) * entry.speedPxPerMillis

    private fun assertNoScrollOverlapAt(timeline: DanmakuTimeline, t: Long) {
        val visible = mutableListOf<CompiledDanmaku>()
        timeline.visibleAt(t) { if (it.slot.mode == DanmakuMode.SCROLL) visible.add(it) }

        visible.groupBy { it.slot.track }.values.forEach { onTrack ->
            val sorted = onTrack.sortedBy { it.t0Millis }
            for (i in 0 until sorted.size - 1) {
                val earlier = sorted[i]
                val later = sorted[i + 1]
                val earlierTail = headX(earlier, t) + measureWidth(earlier.danmaku.text)
                val laterHead = headX(later, t)
                assertTrue(
                    "轨道 ${earlier.slot} 在 t=$t 重叠: earlierTail=$earlierTail laterHead=$laterHead",
                    earlierTail <= laterHead + 0.01f,
                )
            }
        }
    }

    private fun assertNoFixedOverlap(timeline: DanmakuTimeline, mode: DanmakuMode) {
        timeline.compiledEntries()
            .filter { it.slot.mode == mode }
            .groupBy { it.slot.track }
            .values.forEach { onTrack ->
                val sorted = onTrack.sortedBy { it.t0Millis }
                for (i in 0 until sorted.size - 1) {
                    assertTrue(
                        "轨道 ${sorted[i].slot} 区间重叠: ${sorted[i]} vs ${sorted[i + 1]}",
                        sorted[i].exitMillis <= sorted[i + 1].t0Millis,
                    )
                }
            }
    }
}
