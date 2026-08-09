package dev.danmaku.compose

/**
 * 已排布弹幕的时间索引。**只读不判**:这里没有轨道占用状态,也没有任何"这条能不能放"的
 * 判断——那些在 [DanmakuScheduler] 里发生过一次,产出 [DanmakuFlightPlan] 之后就结束了。
 * emitter 只按全局播放时间查询,seek 就是换一个 t 重新查,不是重新计算轨迹。
 *
 * 追加只在尾部生长([DanmakuCompiler] 保证按时间非降序提交),已有条目永远不会因为后来的
 * 弹幕而改变。整段作废只有一条路——[clear],由窗口重建触发。
 *
 * 身份跨重建保持不变([DanmakuCompiler] 类文档),所以 [DanmakuHostState] 挂上之后不必重挂。
 * 代价是 [clear] 与查询共用一个可变列表:两者都在主线程、都不挂起,帧循环不可能停在
 * [visibleAt] 的中途被清空。
 */
class DanmakuTimeline internal constructor(val layout: DanmakuLayoutConfig) {

    // 按 emitTimeMillis 升序;visibleAt 靠这个顺序做二分。
    private val plans = mutableListOf<DanmakuFlightPlan>()

    /** 已收录条目里最长的可见时长,用于收窄 [visibleAt] 的二分起点。 */
    private var maxVisibleSpanMillis = 0L

    val size: Int get() = plans.size

    internal fun add(plan: DanmakuFlightPlan) {
        insertSorted(plan)
        maxVisibleSpanMillis = maxOf(maxVisibleSpanMillis, plan.visibleUntilMillis - plan.emitTimeMillis)
    }

    internal fun clear() {
        plans.clear()
        maxVisibleSpanMillis = 0L
    }

    /** 全部条目,按 emitTime 升序。测试与诊断用,不是渲染热路径。 */
    fun plans(): List<DanmakuFlightPlan> = plans.toList()

    /**
     * 零分配地求出 `playTimeMillis` 时刻可见的弹幕,逐条回调 [visitor]。二分定位到可能可见的
     * 窗口起点,再线性扫描到 `emitTimeMillis > playTimeMillis` 为止——不建列表、不做中间分配。
     */
    fun visibleAt(playTimeMillis: Long, visitor: (DanmakuFlightPlan) -> Unit) {
        if (plans.isEmpty()) return
        var i = lowerBound(playTimeMillis - maxVisibleSpanMillis)
        while (i < plans.size) {
            val plan = plans[i]
            if (plan.emitTimeMillis > playTimeMillis) return
            if (plan.visibleUntilMillis > playTimeMillis) visitor(plan)
            i++
        }
    }

    /**
     * 时间轴上第一条 `emitTimeMillis > afterMillis` 的弹幕,找不到返回 null。渲染 host 用它算出
     * "当前没有可见弹幕时,下一条何时进场",从而把帧循环挂起到那个时刻,而不是无差别地每帧
     * 轮询;不是渲染热路径本身(只在准备挂起的那一刻调用一次)。
     */
    fun nextEntryAfter(afterMillis: Long): DanmakuFlightPlan? = plans.getOrNull(lowerBound(afterMillis + 1))

    /**
     * 峰值同屏条数。按"进场 +1、离场 -1"扫一遍时间轴,不逐帧采样——采样会漏掉两次采样之间
     * 的尖峰,而这个数字的用途恰恰是解释卡顿。
     */
    fun peakConcurrency(): Int {
        if (plans.isEmpty()) return 0
        val exits = plans.map { it.visibleUntilMillis }.sorted()
        var peak = 0
        var live = 0
        var exitIndex = 0
        for (plan in plans) {
            while (exitIndex < exits.size && exits[exitIndex] <= plan.emitTimeMillis) {
                live--
                exitIndex++
            }
            live++
            if (live > peak) peak = live
        }
        return peak
    }

    private fun lowerBound(target: Long): Int {
        var lo = 0
        var hi = plans.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (plans[mid].emitTimeMillis < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun insertSorted(plan: DanmakuFlightPlan) {
        var lo = 0
        var hi = plans.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (plans[mid].emitTimeMillis <= plan.emitTimeMillis) lo = mid + 1 else hi = mid
        }
        plans.add(lo, plan)
    }
}
