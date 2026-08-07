package dev.danmaku.compose

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * 放不下一条弹幕时的处理策略。做成枚举而不是静默丢弃,调用方要能感知丢弃率。
 */
enum class OverflowPolicy {
    /** 直接丢弃这条弹幕。 */
    DISCARD,

    /** 把出现时间往后推,直到某条轨道能接收;超过 [DanmakuTimelineConfig.maxDeferMillis] 仍放不下则丢弃。 */
    DEFER,

    /** 无视准入判据强行放置,允许与同轨相邻弹幕重叠。 */
    OVERLAP,
}

/**
 * 编译器的全部可调参数。宽度测量、屏幕尺寸、速度基准、轨道数、放不下时的策略 —— 全部由
 * 调用方注入,这一层不知道什么是字体、什么是屏幕密度。
 *
 * @param screenWidthPx 弹幕从右侧生成时的起始 x 坐标,也是"消失于左边界"判据里的基准宽度。
 * @param scrollTrackCount 滚动弹幕的轨道数。
 * @param topTrackCount 顶部固定弹幕的轨道数。
 * @param bottomTrackCount 底部固定弹幕的轨道数。
 * @param baseSpeedPxPerMillis 基准字符串([referenceText])在 1 倍速、无扰动时的移动速度。
 * @param referenceText 速度模型的基准字符串,默认取"哈哈哈哈"(参照弹幕文化里最常见的刷屏词)。
 * @param speedJitterFraction 速度扰动幅度 θ,实际速度在 `[1-θ, 1+θ]` 倍基准速度之间,
 *   用 `hash(id)` 确定性映射,不用真随机。
 * @param emptyTrackWeight 从未接收过弹幕的轨道在候选轨道里的权重,相对"已有弹幕但判定通过"
 *   轨道的权重 1 做降权。小于 1 时空轨道更少被选中,被留作难放置弹幕的储备。
 * @param fixedDurationMillis 顶/底固定弹幕在轨道上停留的时长。
 * @param overflowPolicy 放不下时的处理策略。
 * @param maxDeferMillis [OverflowPolicy.DEFER] 允许推迟的时间上限,超过仍放不下则丢弃这条弹幕。
 */
data class DanmakuTimelineConfig(
    val screenWidthPx: Float,
    val scrollTrackCount: Int,
    val topTrackCount: Int,
    val bottomTrackCount: Int,
    val baseSpeedPxPerMillis: Float,
    val referenceText: String = "哈哈哈哈",
    val speedJitterFraction: Float = 0.0875f,
    val emptyTrackWeight: Float = 0.2f,
    val fixedDurationMillis: Long = 4_000L,
    val overflowPolicy: OverflowPolicy = OverflowPolicy.DISCARD,
    val maxDeferMillis: Long = 2_000L,
)

/** 轨道编号空间按模式区分,避免调用方拿滚动轨道号去误查固定轨道,反之亦然。 */
data class DanmakuSlot(val mode: DanmakuMode, val track: Int)

/**
 * 一条弹幕编译后的轨迹。任意时刻的画面就是对这三个数字的求值:
 * - 滚动弹幕:`x = screenWidthPx - (t - t0Millis) * speedPxPerMillis`
 * - 固定弹幕:位置由 [slot] 静态决定,[speedPxPerMillis] 恒为 0,只在 `[t0Millis, exitMillis)`
 *   区间内可见。
 *
 * 这个类一旦生成就不再变化 —— seek 是换一个 t 重新求值,不是重新计算轨迹。
 */
data class CompiledDanmaku(
    val danmaku: Danmaku,
    val slot: DanmakuSlot,
    val t0Millis: Long,
    val speedPxPerMillis: Float,
    val exitMillis: Long,
)

/** log2(1.14):宽度每翻 8 倍,速度只快约 (8^log2(1.14) - 1) ≈ 48%。是缓幂律,不是线性正比。 */
private val SPEED_WIDTH_EXPONENT: Float = (ln(1.14) / ln(2.0)).toFloat()

/**
 * FNV-1a,32 位。系统里唯一的"随机源"全部经过这个函数,不用 [String.hashCode] —— 后者在 JVM
 * 上是规范保证的,但 Kotlin 多平台的 `String.hashCode()` 没有跨平台一致性的规范承诺,而这个模块
 * 迟早要搬到 commonMain 给 JVM/Native 共用同一份编译结果,哈希值本身必须与平台无关。
 */
private fun fnv1a(text: String): Int {
    var hash = -0x7ee3623b // 2166136261 的补码表示 (FNV offset basis)
    for (ch in text) {
        hash = hash xor ch.code
        hash *= 0x01000193 // FNV prime
    }
    return hash
}

/** 把 [fnv1a] 的输出映射到 `[0, 1)`,作为确定性的"随机数"。 */
private fun unitHash(id: String): Float =
    ((fnv1a(id).toLong() and 0xFFFFFFFFL).toDouble() / 4_294_967_296.0).toFloat()

/**
 * 弹幕时间轴:输入弹幕池与排布参数,一次性编译出每条弹幕的 `(slot, t0, speed)`,任意时刻的
 * 画面通过 [visibleAt] 求值得到,内部无逐帧状态。
 *
 * 排布依据"入场准入消灭碰撞,而不是入场后调度":一条弹幕能否进入某条轨道,只取决于它与该
 * 轨道**最近一条已放置弹幕**的准入判据是否通过 —— 已放置的弹幕永远不会因为后来的弹幕而移动,
 * 这也是为什么 [append] 只需要往尾部追加、不必重新编译已有部分。
 *
 * 通过 [Companion.compile] 一次性编译整池弹幕,或者持有一个实例、随实时弹幕到达持续 [append]
 * —— 两者是同一套算法:[Companion.compile] 就是按 `playTimeMillis` 升序对整池弹幕依次调用
 * [append]。
 */
class DanmakuTimeline private constructor(
    val config: DanmakuTimelineConfig,
    private val measureWidth: (String) -> Float,
) {
    // 滚动轨道只需要记录"该轨道最近一条弹幕"的两个时刻,不需要记录完整历史 —— 见类注释,
    // 已放置的弹幕轨迹不会变,后续准入判据只跟这两个时刻比较:
    //   tailOffMillis   尾部到达左边界(彻底清空该轨道)的时刻,防止后车在屏幕内追上前车。
    //   spawnClearMillis 尾部清空右侧生成点(x=screenWidthPx)的时刻,防止新弹幕生成时
    //                     直接摞在前车尚未让开的身体上 —— 只查 tailOffMillis 那一条判据
    //                     在前车明显更快时不够:前车早早追不上不代表新弹幕生成的瞬间没有
    //                     跟前车的躯干撞在生成点上,这是两个独立的重叠来源。
    private val scrollTailOffMillis = LongArray(config.scrollTrackCount) { NEVER_USED }
    private val scrollSpawnClearMillis = LongArray(config.scrollTrackCount) { NEVER_USED }
    private val scrollEverUsed = BooleanArray(config.scrollTrackCount)

    // 固定弹幕是区间调度、first-fit,只需要记录每条轨道当前占用区间的结束时刻 ——
    // 不需要"是否用过"这个额外状态,那是滚动轨道加权选择才要的。
    private val topLastEndMillis = LongArray(config.topTrackCount) { NEVER_USED }
    private val bottomLastEndMillis = LongArray(config.bottomTrackCount) { NEVER_USED }

    // 按 t0Millis 升序维护,insertSorted 保证这一点,visibleAt 靠这个顺序做二分查找。
    private val entries = mutableListOf<CompiledDanmaku>()

    /** 目前已编译弹幕里最长的可见时长,用于 [visibleAt] 收窄二分查找的起点。 */
    private var maxDurationMillis = 0L

    companion object {
        private const val NEVER_USED = Long.MIN_VALUE

        fun compile(
            pool: List<Danmaku>,
            config: DanmakuTimelineConfig,
            measureWidth: (String) -> Float,
        ): DanmakuTimeline {
            val timeline = DanmakuTimeline(config, measureWidth)
            pool.sortedBy { it.playTimeMillis }.forEach(timeline::append)
            return timeline
        }

        fun empty(config: DanmakuTimelineConfig, measureWidth: (String) -> Float): DanmakuTimeline =
            DanmakuTimeline(config, measureWidth)
    }

    /**
     * 追加一条弹幕(在线到达的实时弹幕,或批量编译时的下一条),贪心放到时间轴尾部。
     * 只影响 `t >= 这条弹幕的最终 t0` 的部分,不改动任何已放置弹幕的轨迹。
     *
     * 返回编译结果;按 [DanmakuTimelineConfig.overflowPolicy] 判定放不下时返回 null。
     */
    fun append(danmaku: Danmaku): CompiledDanmaku? {
        val placed = when (danmaku.mode) {
            DanmakuMode.SCROLL -> placeScroll(danmaku)
            DanmakuMode.TOP -> placeFixed(danmaku, DanmakuMode.TOP, topLastEndMillis)
            DanmakuMode.BOTTOM -> placeFixed(danmaku, DanmakuMode.BOTTOM, bottomLastEndMillis)
        } ?: return null

        insertSorted(placed)
        maxDurationMillis = maxOf(maxDurationMillis, placed.exitMillis - placed.t0Millis)
        return placed
    }

    /** 当前已编译的全部弹幕,按 t0 升序。仅用于测试/调试,不是渲染热路径。 */
    fun compiledEntries(): List<CompiledDanmaku> = entries.toList()

    /**
     * 零分配地求出 `playTimeMillis` 时刻可见的弹幕,逐条回调 [visitor]。用二分定位到可能
     * 可见的窗口起点(`t0Millis` 落在 `[playTimeMillis - maxDurationMillis, playTimeMillis]`
     * 之间的弹幕才可能仍然可见),再线性扫描到 `t0Millis > playTimeMillis` 为止 —— 不建列表、
     * 不做中间分配。
     */
    fun visibleAt(playTimeMillis: Long, visitor: (CompiledDanmaku) -> Unit) {
        if (entries.isEmpty()) return
        var i = lowerBound(playTimeMillis - maxDurationMillis)
        while (i < entries.size) {
            val entry = entries[i]
            if (entry.t0Millis > playTimeMillis) return
            if (entry.exitMillis > playTimeMillis) visitor(entry)
            i++
        }
    }

    /**
     * 时间轴上第一条 `t0Millis > afterMillis` 的弹幕,找不到返回 null。渲染 host 用它算出
     * "当前没有可见弹幕时,下一条何时进场",从而把帧循环挂起到那个时刻,而不是无差别地
     * 每帧轮询;不是渲染热路径本身(只在没有可见弹幕、准备挂起的那一刻调用一次)。
     */
    fun nextEntryAfter(afterMillis: Long): CompiledDanmaku? = entries.getOrNull(lowerBound(afterMillis + 1))

    private fun lowerBound(target: Long): Int {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].t0Millis < target) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun insertSorted(entry: CompiledDanmaku) {
        var lo = 0
        var hi = entries.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (entries[mid].t0Millis <= entry.t0Millis) lo = mid + 1 else hi = mid
        }
        entries.add(lo, entry)
    }

    // ---- 速度模型 ----

    /**
     * 速度 = 基准速度 × 宽度缓幂律 × 确定性扰动。短于基准的一律 1 倍速([coerceAtLeast]),
     * 扰动用 [unitHash] 而不是真随机,同一条弹幕永远同速。
     */
    private fun computeSpeed(danmaku: Danmaku): Float {
        val width = measureWidth(danmaku.text)
        val referenceWidth = measureWidth(config.referenceText).coerceAtLeast(1f)
        val widthMultiplier = (width / referenceWidth).pow(SPEED_WIDTH_EXPONENT).coerceAtLeast(1f)
        val jitter = 1f + (unitHash(danmaku.id) * 2f - 1f) * config.speedJitterFraction
        return (config.baseSpeedPxPerMillis * widthMultiplier * jitter).coerceAtLeast(MIN_SPEED)
    }

    // ---- 滚动弹幕 ----

    private fun placeScroll(danmaku: Danmaku): CompiledDanmaku? {
        val speed = computeSpeed(danmaku)
        val width = measureWidth(danmaku.text)
        var t0 = danmaku.playTimeMillis
        var candidates = scrollCandidates(t0, speed)

        if (candidates.isEmpty()) {
            when (config.overflowPolicy) {
                OverflowPolicy.DISCARD -> return null
                OverflowPolicy.OVERLAP -> candidates = scrollTailOffMillis.indices.toList()
                OverflowPolicy.DEFER -> {
                    val deferred = deferScroll(t0, speed) ?: return null
                    t0 = deferred
                    candidates = scrollCandidates(t0, speed)
                    if (candidates.isEmpty()) return null
                }
            }
        }

        val track = pickWeighted(danmaku.id, candidates, scrollEverUsed)
        val exit = t0 + tailOffSpanMillis(speed, width)
        scrollTailOffMillis[track] = exit
        scrollSpawnClearMillis[track] = t0 + spawnClearSpanMillis(speed, width)
        scrollEverUsed[track] = true
        return CompiledDanmaku(danmaku, DanmakuSlot(DanmakuMode.SCROLL, track), t0, speed, exit)
    }

    /**
     * 一条轨道能接纳新弹幕,当且仅当轨道为空,或者同时满足两条判据:
     * 1. **追及**:新弹幕头部到达左边界的时刻不早于该轨道上一条弹幕尾部到达左边界的时刻 ——
     *    同轨基础速度相同时这条判据单独就严格成立(两者不会在屏幕内相遇);速度存在扰动或
     *    宽度不同时,只看这一条不够,还需要下一条。
     * 2. **生成位不重叠**:上一条弹幕的尾部已经清空右侧生成点(`x = screenWidthPx`)—— 否则
     *    即便前车"跑得快、追不上",新弹幕生成的瞬间也可能直接摞在前车尚未让开的躯干上,
     *    这与追及是两个独立的重叠来源,前车更快时追及判据满足但生成位判据仍可能不满足。
     *
     * 两条判据都是关于"位置差"这个关于时间的线性函数在区间两端点的取值,线性函数在两端
     * 非负则区间内恒非负,因此两条同时满足就能保证区间内任意时刻都不重叠,不是近似。
     */
    private fun scrollCandidates(t0: Long, speed: Float): List<Int> {
        val edge = t0 + edgeSpanMillis(speed)
        return scrollTailOffMillis.indices.filter { track ->
            scrollTailOffMillis[track] == NEVER_USED ||
                (edge >= scrollTailOffMillis[track] && t0 >= scrollSpawnClearMillis[track])
        }
    }

    /** 所有轨道都被占满时,推到最早能腾出轨道的时刻;超过 [DanmakuTimelineConfig.maxDeferMillis] 放弃。 */
    private fun deferScroll(t0: Long, speed: Float): Long? {
        val readyAt = scrollTailOffMillis.indices.minOf { track ->
            maxOf(scrollTailOffMillis[track] - edgeSpanMillis(speed), scrollSpawnClearMillis[track])
        }
        val deferred = maxOf(t0, readyAt)
        return deferred.takeIf { it - t0 <= config.maxDeferMillis }
    }

    /** 头部到达左边界所需时长。 */
    private fun edgeSpanMillis(speed: Float): Long =
        (config.screenWidthPx / speed).toDouble().roundToLong()

    /** 尾部(含弹幕自身宽度)到达左边界所需时长,即这条弹幕彻底清空该轨道所需的时间。 */
    private fun tailOffSpanMillis(speed: Float, width: Float): Long =
        ((config.screenWidthPx + width) / speed).toDouble().roundToLong()

    /** 尾部清空右侧生成点(移动完自身宽度)所需时长。 */
    private fun spawnClearSpanMillis(speed: Float, width: Float): Long =
        (width / speed).toDouble().roundToLong()

    // ---- 固定弹幕(顶/底) ----

    /** 区间调度:每条弹幕占用所在轨道的 `[t0, t0 + fixedDurationMillis)`,按时间贪心分配。 */
    private fun placeFixed(
        danmaku: Danmaku,
        mode: DanmakuMode,
        lastEndMillis: LongArray,
    ): CompiledDanmaku? {
        var t0 = danmaku.playTimeMillis
        var candidates = fixedCandidates(t0, lastEndMillis)

        if (candidates.isEmpty()) {
            when (config.overflowPolicy) {
                OverflowPolicy.DISCARD -> return null
                OverflowPolicy.OVERLAP -> candidates = lastEndMillis.indices.toList()
                OverflowPolicy.DEFER -> {
                    val readyAt = lastEndMillis.indices.minOf { lastEndMillis[it] }
                    val deferred = maxOf(t0, readyAt)
                    if (deferred - t0 > config.maxDeferMillis) return null
                    t0 = deferred
                    candidates = fixedCandidates(t0, lastEndMillis)
                    if (candidates.isEmpty()) return null
                }
            }
        }

        // first-fit,不走加权选择:加权是为了不让滚动弹幕全挤在编号靠前的轨道,固定弹幕没有
        // 这个问题 —— 常规观感就是从最靠边那条开始往里填,candidates 已经是"轨道号升序"。
        val track = candidates.first()
        val exit = t0 + config.fixedDurationMillis
        lastEndMillis[track] = exit
        return CompiledDanmaku(danmaku, DanmakuSlot(mode, track), t0, 0f, exit)
    }

    private fun fixedCandidates(t0: Long, lastEndMillis: LongArray): List<Int> =
        lastEndMillis.indices.filter { track -> lastEndMillis[track] == NEVER_USED || t0 >= lastEndMillis[track] }

    // ---- 选轨:候选集合 + 确定性哈希选择,空轨道降权 ----

    /**
     * 在候选轨道里用 `hash(id)` 做加权选择,不是 first-fit —— first-fit 会让稀疏时段的弹幕
     * 全挤在编号靠前的轨道。从未用过的轨道按 [DanmakuTimelineConfig.emptyTrackWeight] 降权:
     * 它是留给难放置的弹幕的储备,均匀随机会提前耗尽这份储备,推高爆发期的丢弃率。
     */
    private fun pickWeighted(id: String, candidates: List<Int>, everUsed: BooleanArray): Int {
        if (candidates.size == 1) return candidates[0]
        val weights = FloatArray(candidates.size) { i ->
            if (everUsed[candidates[i]]) 1f else config.emptyTrackWeight
        }
        val total = weights.sum()
        val target = unitHash(id) * total
        var acc = 0f
        for (i in candidates.indices) {
            acc += weights[i]
            if (target < acc) return candidates[i]
        }
        return candidates.last()
    }
}

private const val MIN_SPEED = 0.0001f
