package dev.danmaku.compose

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 排布是编排期算出的确定性轨迹,不是运行时状态机。这组测试断言的是 slack 判据声称的那条
 * 等价性——`slack >= 0` **当且仅当**入口不重叠且屏内不追尾——以及轮询调度的序列性质,
 * 不复述实现内部怎么算。
 *
 * 几何量一律从 [DanmakuFlightPlan] 反推:虚拟宽度 = `speed × D - W`(固定 duration 模型的
 * 全部意义就在于这个反推成立),不在测试里重写一遍速度公式,否则公式写错时两边一起错。
 */
class DanmakuSchedulerTest {

    private val charWidthPx = 10f
    private val minGapPx = 12f

    private fun size(text: String) = DanmakuTextSize(text.length * charWidthPx, 20f)

    private fun layout(
        trackHeightPx: Float = 20f,
        scrollDurationMillis: Long = 1_000L,
        canvasHeightPx: Float = 200f,
    ) = DanmakuLayoutConfig(
        canvasWidthPx = 400f,
        canvasHeightPx = canvasHeightPx,
        trackHeightPx = trackHeightPx,
        // 显式钉住视口,不跟随默认值:默认档是产品决定(现在是 75%),改档不该让这些
        // 靠"几条轨道"立论的断言跟着变红。
        viewport = DanmakuViewport.topAnchored(0.5f),
        scrollDurationMillis = scrollDurationMillis,
        fixedDurationMillis = 1_000L,
        minGapPx = minGapPx,
    )

    /** 单轨:视口高 100(画布 200 × 50%),行高给满 100 就只剩一条滚动轨道。 */
    private fun singleTrackLayout() = layout(trackHeightPx = 100f)

    private fun scroll(id: String, timeMillis: Long, text: String) =
        Danmaku(id = id, playTimeMillis = timeMillis, mode = DanmakuMode.SCROLL, color = 0xFFFFFF, text = text)

    // ---- slack 判据的三类性质 ----

    @Test
    fun `slack 边界 - 同速`() = assertSlackBoundary(frontText = "四个字符", backText = "四个字符")

    @Test
    fun `slack 边界 - 前车更快`() = assertSlackBoundary(frontText = "一条很长的弹幕文本", backText = "短")

    @Test
    fun `slack 边界 - 后车更快`() = assertSlackBoundary(frontText = "短", backText = "一条很长的弹幕文本")

    /**
     * 在单轨上找出后车被接纳的最早时刻,并验证判据两侧的几何:接纳的那一刻起,前后车在
     * 屏内任意时刻的间距都不小于 g;早一毫秒(被拒绝的那一刻)则一定有某个时刻不满足。
     *
     * 被拒绝那一侧的轨迹拿轮询调度器生成——它跳过准入判断但用同一套速度模型,这样"如果放了
     * 会怎样"不必在测试里手算一遍速度。
     */
    private fun assertSlackBoundary(frontText: String, backText: String) {
        val cfg = singleTrackLayout()
        val front = scroll("front", 0L, frontText)

        var accepted: DanmakuFlightPlan? = null
        var acceptedAt = -1L
        for (t in 0L..cfg.scrollDurationMillis) {
            val scheduler = CollisionFreeScheduler(cfg)
            scheduler.schedule(front, size(frontText), 0)
            val plan = scheduler.schedule(scroll("back", t, backText), size(backText), 1)
            if (plan != null) {
                accepted = plan
                acceptedAt = t
                break
            }
        }
        assertNotNull("整个穿屏时长内都没有一个可接纳时刻,判据或配置有问题", accepted)
        assertTrue("t=0 就被接纳,这组宽度构不成边界", acceptedAt > 0L)

        val acceptedPlan = accepted!!
        val frontPlan = CollisionFreeScheduler(cfg).schedule(front, size(frontText), 0)!!
        val acceptedGap = minGapAfter(cfg, frontPlan, acceptedPlan)
        assertTrue("接纳时刻的最小间距 $acceptedGap 小于 g=$minGapPx", acceptedGap >= minGapPx - GAP_EPSILON)

        // 早一毫秒:轮询调度器照放不误,几何上就该出现小于 g 的时刻。判据比几何严一点点是
        // 浮点误差,严一整毫秒就是白丢弹幕——所以容差只放在"不满足"这一侧。
        val wouldBe = RoundRobinScheduler(cfg).schedule(scroll("back", acceptedAt - 1, backText), size(backText), 0)
        val rejectedGap = minGapAfter(cfg, frontPlan, wouldBe)
        assertTrue(
            "早一毫秒的最小间距 $rejectedGap 仍然满足 g=$minGapPx,判据比几何更严,白丢了弹幕",
            rejectedGap < minGapPx + GAP_EPSILON,
        )
    }

    /**
     * 后车发射到前车虚拟尾部离开左边界之间,两者虚拟包围盒的最小间距。
     *
     * 间距是时间的线性函数,理论上取两个端点即可;这里仍然逐毫秒扫,因为测试要验的正是
     * "只看端点就够"这个结论本身,拿它当前提就等于什么都没验。
     */
    private fun minGapAfter(
        cfg: DanmakuLayoutConfig,
        frontPlan: DanmakuFlightPlan,
        backPlan: DanmakuFlightPlan,
    ): Float {
        var min = Float.MAX_VALUE
        var t = backPlan.emitTimeMillis
        val until = frontPlan.emitTimeMillis + cfg.scrollDurationMillis
        while (t <= until) {
            min = minOf(min, virtualGapAt(cfg, frontPlan, backPlan, t))
            t++
        }
        return min
    }

    /** 前车虚拟尾部与后车虚拟头部之间的水平距离,负值表示已经重叠。 */
    private fun virtualGapAt(
        cfg: DanmakuLayoutConfig,
        front: DanmakuFlightPlan,
        back: DanmakuFlightPlan,
        t: Long,
    ): Float {
        val right = cfg.viewportPx.right
        val frontHead = right - (t - front.emitTimeMillis) * front.speedPxPerMillis
        val backHead = right - (t - back.emitTimeMillis) * back.speedPxPerMillis
        return backHead - (frontHead + virtualWidth(cfg, front))
    }

    /** 固定 duration 模型的反推:`speed × D = W + virtualWidth`。 */
    private fun virtualWidth(cfg: DanmakuLayoutConfig, plan: DanmakuFlightPlan): Float =
        plan.speedPxPerMillis * cfg.scrollDurationMillis - cfg.viewportPx.width

    @Test
    fun `随机弹幕池里同轨任意时刻都不重叠`() {
        val cfg = layout()
        val compiler = DanmakuCompiler(cfg, CollisionFreeScheduler(cfg)) { size(it.text) }
        compiler.setPool(randomPool(400, seed = 1))
        compiler.compileAll()

        val plans = compiler.timeline.plans().filter { it.mode == DanmakuMode.SCROLL }
        plans.groupBy { it.track }.values.forEach { onTrack ->
            val sorted = onTrack.sortedBy { it.emitTimeMillis }
            for (i in 0 until sorted.size - 1) {
                val gap = minGapAfter(cfg, sorted[i], sorted[i + 1])
                assertTrue("轨道 ${sorted[i].track} 上间距 $gap 小于 g", gap >= minGapPx - GAP_EPSILON)
            }
        }
    }

    // ---- 轮询调度 ----

    @Test
    fun `轮询按池内序号严格发轨,三种模式各自计数`() {
        val cfg = layout() // 视口高 100 / 行高 20 = 5 条滚动轨道
        assertEquals(5, cfg.scrollTrackCount)

        // 交错的池子:顶部弹幕的到达不该扰动滚动弹幕的轮询序列。序号由编排器从池子数出来,
        // 所以这条性质现在要连着编排器一起验,单看调度器只能验到"传什么序号得什么轨道"。
        val pool = mutableListOf<Danmaku>()
        repeat(12) { i ->
            pool += scroll("s$i", i * 10L, "弹幕")
            if (i % 3 == 0) pool += Danmaku("t$i", i * 10L, DanmakuMode.TOP, 0xFFFFFF, "顶")
        }
        val compiler = DanmakuCompiler(cfg, RoundRobinScheduler(cfg)) { size(it.text) }
        compiler.setPool(pool)
        compiler.compileAll()

        val byMode = compiler.timeline.plans().groupBy { it.mode }
        assertEquals(List(12) { it % cfg.scrollTrackCount }, byMode.getValue(DanmakuMode.SCROLL).map { it.track })
        assertEquals(List(4) { it % cfg.topTrackCount }, byMode.getValue(DanmakuMode.TOP).map { it.track })
    }

    @Test
    fun `底部轨道数不随显示区域变,滚动和顶部才随它变`() {
        val wide = layout()
        val narrow = wide.copy(viewport = DanmakuViewport.topAnchored(0.25f))

        assertTrue("显示区域收窄后滚动轨道没变少", narrow.scrollTrackCount < wide.scrollTrackCount)
        assertEquals("顶部弹幕在视口里,应该跟着一起变", narrow.scrollTrackCount, narrow.topTrackCount)
        // 底部弹幕锚画布底边、不在视口里:调显示区域是为了别让滚动弹幕糊住画面,不是把底部
        // 弹幕挪上去。这条断言就是防那次改回来的。
        assertEquals(wide.bottomTrackCount, narrow.bottomTrackCount)
    }

    @Test
    fun `轮询结果不随分段到达而变`() {
        val cfg = layout()
        val pool = randomPool(200, seed = 7)
        // 按时间切分,不按下标切:同一时间戳的弹幕必须整组落在同一批里,否则两次编译看到的
        // 是不同的排序输入,这个测试就会偶发失败——而分段本来就是按时间到达的。
        val (early, late) = pool.partition { it.playTimeMillis < 10_000L }

        val whole = DanmakuCompiler(cfg, RoundRobinScheduler(cfg)) { size(it.text) }
        whole.setPool(pool)
        whole.compileAll()

        val batched = DanmakuCompiler(cfg, RoundRobinScheduler(cfg)) { size(it.text) }
        batched.setPool(early)
        batched.compileAll()
        // 第二段用 advanceTo 追,不用 compileAll:后者会重建窗口,那就验不到"追加不重排"了。
        batched.setPool(early + late)
        batched.advanceTo(1_000_000L)

        assertEquals(whole.timeline.plans(), batched.timeline.plans())
    }

    // ---- 丢弃统计 ----

    @Test
    fun `标准档放不下就丢弃并计入统计,不限档不丢`() {
        val cfg = singleTrackLayout()
        val crowd = (0 until 5).map { scroll("c$it", 0L, "弹幕") }

        val standard = DanmakuCompiler(cfg, DanmakuDensity.STANDARD.createScheduler(cfg)) { size(it.text) }
        standard.setPool(crowd)
        val standardReport = standard.compileAll()
        assertEquals(5, standardReport.inputCount)
        assertEquals(1, standardReport.scheduledCount)
        assertEquals(4, standardReport.droppedByLayoutCount)
        assertEquals(1, standardReport.peakConcurrentCount)

        val unlimited = DanmakuCompiler(cfg, DanmakuDensity.UNLIMITED.createScheduler(cfg)) { size(it.text) }
        unlimited.setPool(crowd)
        val unlimitedReport = unlimited.compileAll()
        assertEquals(5, unlimitedReport.scheduledCount)
        assertEquals(0, unlimitedReport.droppedByLayoutCount)
        assertEquals(5, unlimitedReport.peakConcurrentCount)
    }

    @Test
    fun `固定弹幕轨道被占满时丢弃,不延迟`() {
        val cfg = singleTrackLayout()
        val scheduler = CollisionFreeScheduler(cfg)
        val first = Danmaku("a", 0L, DanmakuMode.BOTTOM, 0xFFFFFF, "底")
        val second = Danmaku("b", 500L, DanmakuMode.BOTTOM, 0xFFFFFF, "底")

        assertNotNull(scheduler.schedule(first, size("底"), 0))
        assertNull(scheduler.schedule(second, size("底"), 1))
        // 区间到点之后同一条轨道又能用,而且发射时间就是原始时间,没有被推迟。
        val third = Danmaku("c", 1_000L, DanmakuMode.BOTTOM, 0xFFFFFF, "底")
        assertEquals(1_000L, scheduler.schedule(third, size("底"), 2)!!.emitTimeMillis)
    }

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

    private companion object {
        /** 几何量在 400px 量级上用 Float 累加,亚像素级误差不算违反判据。 */
        const val GAP_EPSILON = 0.01f
    }
}
