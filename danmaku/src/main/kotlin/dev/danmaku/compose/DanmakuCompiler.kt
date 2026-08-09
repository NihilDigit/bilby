package dev.danmaku.compose

import kotlin.time.TimeSource

/**
 * 编排入口:`测量 → 调度 → 不可变 timeline`。持有调度器(也就是持有轨道占用状态)的是它,
 * 不是 [DanmakuTimeline];渲染侧只拿 [timeline],因此拿不到、也改不了任何轨道信息。
 *
 * [timeline] 的身份跨窗口推进和窗口重建都保持不变——渲染 host 一旦挂上就不用重挂。
 *
 * **只编排播放位置附近的一段窗口,不编排整池。** 整池编排里约 94% 的时间花在 [measure] 上
 * (实测:9457 条 347ms,其中纯调度 17~20ms),而同屏最多只有几百条——也就是说测量量比需要的
 * 多了一个数量级以上,且全部压在主线程一帧里。窗口化把测量量压到"窗口内的条数",这是这套
 * 结构的全部意义:**窗口外的弹幕一次都不会被 [measure] 碰到**。
 *
 * ## 窗口能丢掉多少历史
 *
 * [CollisionFreeScheduler] 的滚动轨道状态是 `remaining = max(0, lastEmit + D - t)`,固定弹幕
 * 是 `t < endMillis`(`endMillis = lastEmit + fixedD`)。两者都在 `t >= lastEmit + D` 之后退化
 * 成"这条轨道没用过":slack 取到最大值 `W - g`,并列时都选编号最小的。所以**编排起点之前
 * 超过 D 毫秒的历史,对结果没有任何贡献**,可以整段丢掉。D 取 `max(scrollDuration,
 * fixedDuration)`,两种模式统一用一个数,不各算各的。
 *
 * 这条性质只覆盖"更早的历史"。它**不**等于"窗口结果与整池结果逐条相同":窗口起点之后头
 * D 毫秒里的那些弹幕,整池编排时看到的是真实的轨道占用,窗口编排看到的是空轨道,两边可能
 * 选到不同轨道;而这一点分歧会顺着轨道状态一直传下去,不会自己收敛。拿 9457 条/20 分钟的
 * 合成池实测:seek 之后标准档约有一半的弹幕落在与整池编排不同的轨道上,预热拉长到 2D、4D
 * 都不改善(这是可预期的,分歧的来源是起点那一瞬间,不是预热长度)。
 *
 * 能保证、也确实需要的是这三条:
 * 1. **顺播不受影响**。窗口只增量向前扩展,调度器状态连续,结果与整池编排逐条相同。
 * 2. **同一个落点重复进入结果相同**。窗口起点按 [WINDOW_ORIGIN_GRID_MILLIS] 对齐取整,
 *    落点差几十毫秒(seek 到关键帧的抖动)也落在同一格里,画面不会跳。
 * 3. **不多丢也不少丢弹幕**。轨道分配换了一种,但同一批弹幕仍然全部上屏——上面那个合成池
 *    里两边的丢弃条数完全相同。变的是弹幕在纵向的排法,不是有没有。
 *
 * 换句话说,seek 之后看到的画面是"另一种同样合法的排法",不是"整池编排那一种"。
 *
 * @param lookAheadMillis 窗口在播放位置之前预留多长。够覆盖两次 [advanceTo] 之间的播放时间
 *   即可,给大了就等于回到整池编排。
 * @param measure 文字尺寸测量。返回中立的宽高,平台排版对象留在渲染准备层,不进这一层。
 *   **必须与渲染时用的字体/字号完全同源**,否则排布按一套宽度算、画面按另一套画。
 */
class DanmakuCompiler(
    val layout: DanmakuLayoutConfig,
    private val scheduler: DanmakuScheduler,
    private val lookAheadMillis: Long = DEFAULT_LOOK_AHEAD_MILLIS,
    private val measure: (Danmaku) -> DanmakuTextSize,
) {
    val timeline: DanmakuTimeline = DanmakuTimeline(layout)

    /** 当前窗口(自上一次重建起)的累计统计。重建会把它清零——它描述的是这个窗口。 */
    var report: ProcessingReport = ProcessingReport()
        private set

    /** 按 `(playTimeMillis, id)` 稳定排序的池子,见 [setPool]。 */
    private var pool: List<Danmaku> = emptyList()

    /** `sequences[i]` 是 `pool[i]` 在同模式条目里的序号,喂给 [DanmakuScheduler.schedule]。 */
    private var sequences: IntArray = IntArray(0)

    /** 下一条待编排的下标。`pool[0 until nextIndex]` 里落在窗口内的部分已经在 [timeline] 里。 */
    private var nextIndex = 0

    /**
     * 从这一刻起画面才不受"窗口起点处轨道是空的"影响,早于它就得重建。初值 [Long.MAX_VALUE]
     * 表示还没建过窗口——第一次 [advanceTo] 必定落在它之前,于是必定重建。
     */
    private var validFromMillis = Long.MAX_VALUE

    private val warmupMillis: Long = maxOf(layout.scrollDurationMillis, layout.fixedDurationMillis)

    /**
     * 换一份弹幕池。排序在这里做一次,之后窗口推进只按下标走。
     *
     * 排序键是 `(playTimeMillis, id)`:同一时间戳的多条弹幕在两次编排之间必须得到相同的顺序,
     * 否则 seek 回来的画面和顺播的画面对不上——`sortedBy` 单靠时间戳只保证稳定于**输入顺序**,
     * 而输入顺序会随分段到达次序变化。
     *
     * 新池子是旧池子的时间尾部扩展时(分段按播放顺序到达的常态),已编排的部分连同它们的
     * 池内序号都不变,窗口不用重建;否则(用户倒着 seek 到一段还没拉过的更早时间,新分段插到
     * 已编排条目之前)标记重建,下一次 [advanceTo] 生效。判据是"旧的排序结果是不是新的前缀",
     * 不是"新来的时间戳有没有比已编排的更早"——后者漏掉了轮询档:中间插进一条弹幕会让它
     * 之后所有同模式条目的序号整体挪一位,时间上却完全合法。
     */
    fun setPool(next: List<Danmaku>) {
        val sorted = next.sortedWith(compareBy({ it.playTimeMillis }, { it.id }))
        if (sorted == pool) return
        val extendsCurrent = sorted.size >= pool.size && pool.indices.all { sorted[it] == pool[it] }
        pool = sorted
        sequences = perModeSequences(sorted)
        if (!extendsCurrent) invalidate()
    }

    /**
     * 保证 `positionMillis` 起、往后 [lookAheadMillis] 的画面已经排好,返回 [timeline] 是否
     * 发生了变化(变了就该叫醒帧循环)。
     *
     * 播放位置退回到窗口有效区之前(seek 回跳、跳到还没编排过的更早位置)时整段重建;顺播
     * 只走增量扩展那一步,不重建。
     */
    fun advanceTo(positionMillis: Long): Boolean {
        var changed = false
        if (positionMillis < validFromMillis) {
            rebuildFrom(windowOriginFor(positionMillis))
            changed = true
        }
        return extendTo(positionMillis + lookAheadMillis) || changed
    }

    /**
     * 整池编排:一次排完,不留窗口。**只给测试和对照用**,生产路径走 [advanceTo]——这个方法
     * 存在的意义就是让"窗口编排 vs 整池编排"这条断言有东西可比。
     */
    fun compileAll(): ProcessingReport {
        // 起点取一个不可能有弹幕落在其前的值,同时让 validFrom 也远在负半轴,后续 advanceTo
        // 不会误判成需要重建。
        rebuildFrom(Long.MIN_VALUE / 2)
        extendTo(Long.MAX_VALUE)
        return report
    }

    /** 标记当前窗口作废。真正的重建推迟到下一次 [advanceTo]——那时才知道要从哪儿重建。 */
    private fun invalidate() {
        validFromMillis = Long.MAX_VALUE
    }

    /**
     * 窗口起点:播放位置往回退一个 D(预热),再向下对齐到 [WINDOW_ORIGIN_GRID_MILLIS] 的整数
     * 倍。取整是为了让起点只是"落在哪一格"的函数——两次 seek 到同一处,播放器给的位置常常差
     * 几十毫秒(落到不同关键帧),不取整就会算出两个起点、排出两套轨道,画面跳一下。
     */
    private fun windowOriginFor(positionMillis: Long): Long {
        val warmStart = positionMillis - warmupMillis
        return warmStart.floorDiv(WINDOW_ORIGIN_GRID_MILLIS) * WINDOW_ORIGIN_GRID_MILLIS
    }

    private fun rebuildFrom(origin: Long) {
        scheduler.reset()
        timeline.clear()
        validFromMillis = origin + warmupMillis
        nextIndex = lowerBound(origin)
        report = ProcessingReport()
    }

    /** 把窗口尾部推到 `untilMillis`,返回是否真的排进了新条目。 */
    private fun extendTo(untilMillis: Long): Boolean {
        if (nextIndex >= pool.size || pool[nextIndex].playTimeMillis > untilMillis) return false
        val start = TimeSource.Monotonic.markNow()
        var input = 0
        var scheduled = 0
        var dropped = 0
        while (nextIndex < pool.size && pool[nextIndex].playTimeMillis <= untilMillis) {
            val danmaku = pool[nextIndex]
            val plan = scheduler.schedule(danmaku, measure(danmaku), sequences[nextIndex])
            if (plan == null) {
                dropped++
            } else {
                timeline.add(plan)
                scheduled++
            }
            input++
            nextIndex++
        }
        report = ProcessingReport(
            inputCount = report.inputCount + input,
            scheduledCount = report.scheduledCount + scheduled,
            droppedByLayoutCount = report.droppedByLayoutCount + dropped,
            peakConcurrentCount = timeline.peakConcurrency(),
            compileDurationMillis = report.compileDurationMillis + start.elapsedNow().inWholeMilliseconds,
        )
        return true
    }

    /** [pool] 里第一条 `playTimeMillis >= target` 的下标。 */
    private fun lowerBound(target: Long): Int {
        var lo = 0
        var hi = pool.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (pool[mid].playTimeMillis < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private companion object {
        /**
         * 窗口预留长度。30 秒足够盖住两次 [advanceTo] 之间的播放(调用方按秒级推进),同时把
         * 一次重建的测量量压在几百条量级——密度最高的池子(9457 条/20 分钟)在这个跨度里也只有
         * 两三百条。
         */
        const val DEFAULT_LOOK_AHEAD_MILLIS = 30_000L

        /** 窗口起点的对齐粒度,见 [windowOriginFor]。 */
        const val WINDOW_ORIGIN_GRID_MILLIS = 4_000L
    }
}

/** 每条弹幕在同模式条目里的序号。轮询档靠它得到与调用历史无关的轨道,见 [RoundRobinScheduler]。 */
private fun perModeSequences(pool: List<Danmaku>): IntArray {
    val counters = IntArray(DanmakuMode.entries.size)
    return IntArray(pool.size) { counters[pool[it].mode.ordinal]++ }
}
