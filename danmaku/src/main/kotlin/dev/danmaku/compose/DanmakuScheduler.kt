package dev.danmaku.compose

import kotlin.math.ceil
import kotlin.math.max

/**
 * 把一条弹幕排进轨道。**碰撞只在这一层发生**:调度器是整套流程里唯一持有轨道占用状态的东西,
 * 产出 [DanmakuFlightPlan] 之后那份状态就与画面无关了。
 *
 * 实现按密度分档,不是同一套算法加溢出分支——见 [DanmakuDensity]。
 *
 * 调用约定:[schedule] 必须按 `playTimeMillis` 非降序调用,轨道状态只记"最近一条",乱序输入
 * 会得到错误的排布(调用方发现乱序时重建窗口,见 [DanmakuCompiler])。
 *
 * 调度器允许从池子中间起步:[DanmakuCompiler] 只编排播放位置附近的一段窗口,seek 之后
 * [reset] 再从新起点重新喂。哪些结果因此会与"从头编排"不同,由 [DanmakuCompiler] 的窗口
 * 说明负责讲清楚,这一层只保证 [reset] 之后的状态与"从未用过"完全一致。
 */
interface DanmakuScheduler {
    /**
     * 排不下时返回 null(丢弃,**不延迟**)。
     *
     * @param sequence 这条弹幕在**整池同模式条目**里的稳定序号,由 [DanmakuCompiler] 从排序后
     *   的池子数出来,与调度调用次数无关。窗口化编排之后调度器随时会从池子中间重新起步,
     *   自增计数器的起点会跟着窗口起点漂移——同一个 seek 落点两次进入就选到不同轨道,画面
     *   跳一下。序号由调用方给,是把这份确定性钉在池子上,而不是钉在调用历史上。
     */
    fun schedule(danmaku: Danmaku, size: DanmakuTextSize, sequence: Int): DanmakuFlightPlan?

    /** 清空轨道状态,回到"空轨道"的起点。窗口重建走这条路。 */
    fun reset()
}

/**
 * 同屏密度档位。两档走两套独立的调度器,不是一套算法的两种溢出行为。
 *
 * 上一版把"不限"做成了碰撞算法的 `OVERLAP` 分支:先付完整的安全轨道扫描成本,全都失败之后
 * 才强行叠放,而且强行叠放后只记录最新那条尾弹幕——同轨上仍然可见的更早弹幕状态就丢了。
 * 既拿不到"不限"应有的 O(N) 编排成本,状态也不再正确。
 */
enum class DanmakuDensity {
    /** 标准:无碰撞排布,放不下就丢弃并计数。 */
    STANDARD,

    /** 不限密度(仍受设备性能限制):轮询发轨,不判碰撞、不丢弃。 */
    UNLIMITED,
    ;

    fun createScheduler(layout: DanmakuLayoutConfig): DanmakuScheduler = when (this) {
        STANDARD -> CollisionFreeScheduler(layout)
        UNLIMITED -> RoundRobinScheduler(layout)
    }
}

/** 速度下限,防止视口宽为 0(画布还没布局)时算出 0 速度、弹幕永远不动。 */
private const val MIN_SPEED_PX_PER_MILLIS = 0.0001f

/**
 * 固定穿屏时长下的运动参数。所有调度器共用同一套速度模型,"能不能放"才是它们的分歧点。
 *
 * ```
 * jitter       = hash(id) × jitterFraction   // 0% ~ 7.5%
 * padding      = jitter × measuredWidth      // 放在运动方向的尾部(右侧)
 * virtualWidth = measuredWidth + padding
 * speed        = (viewportWidth + virtualWidth) / D
 * ```
 *
 * 留白放尾部而不是给速度加扰动,是为了让轨道状态能压缩成 `(emitTime, speed)` 两个数:D 统一
 * 之后,`speed × D - W` 就能反推出虚拟宽度,轨道不必再记 width 或"尾部何时让开生成点"。
 * 一旦给速度加独立扰动或做 clamp,这个反推就不成立,slack 公式也跟着失效。
 */
private fun scrollMotion(
    danmaku: Danmaku,
    size: DanmakuTextSize,
    layout: DanmakuLayoutConfig,
): ScrollMotion {
    val jitter = unitHash(danmaku.id) * layout.jitterFraction
    val virtualWidth = size.widthPx * (1f + jitter)
    val speed = ((layout.viewportPx.width + virtualWidth) / layout.scrollDurationMillis)
        .coerceAtLeast(MIN_SPEED_PX_PER_MILLIS)
    // 真实文字比虚拟包围盒早一点离开左边界,尾部留白是虚的,画不出来。
    val visibleSpan = ceil((layout.viewportPx.width + size.widthPx) / speed).toLong()
    return ScrollMotion(speed = speed, visibleSpanMillis = visibleSpan)
}

private class ScrollMotion(val speed: Float, val visibleSpanMillis: Long)

private fun scrollPlan(
    danmaku: Danmaku,
    size: DanmakuTextSize,
    layout: DanmakuLayoutConfig,
    track: Int,
    motion: ScrollMotion,
): DanmakuFlightPlan = DanmakuFlightPlan(
    danmaku = danmaku,
    mode = DanmakuMode.SCROLL,
    track = track,
    emitTimeMillis = danmaku.playTimeMillis,
    durationMillis = layout.scrollDurationMillis,
    visibleUntilMillis = danmaku.playTimeMillis + motion.visibleSpanMillis,
    speedPxPerMillis = motion.speed,
    widthPx = size.widthPx,
    heightPx = size.heightPx,
)

private fun fixedPlan(
    danmaku: Danmaku,
    size: DanmakuTextSize,
    layout: DanmakuLayoutConfig,
    track: Int,
): DanmakuFlightPlan = DanmakuFlightPlan(
    danmaku = danmaku,
    mode = danmaku.mode,
    track = track,
    emitTimeMillis = danmaku.playTimeMillis,
    durationMillis = layout.fixedDurationMillis,
    visibleUntilMillis = danmaku.playTimeMillis + layout.fixedDurationMillis,
    speedPxPerMillis = 0f,
    widthPx = size.widthPx,
    heightPx = size.heightPx,
)

/**
 * 标准档:无碰撞排布 + 最小空白匹配。
 *
 * 设统一穿屏时长为 `D`、视口宽为 `W`、最小间距为 `g`,当前弹幕的发射时间和速度为 `t`、`v`,
 * 第 i 条轨道上尾弹幕的发射时间和速度为 `sᵢ`、`uᵢ`:
 *
 * ```
 * remainingᵢ = max(0, sᵢ + D - t)
 * slackᵢ     = W - g - max(uᵢ, v) × remainingᵢ
 * ```
 *
 * `slackᵢ >= 0` **当且仅当**发射瞬间不与前车重叠,并且后车不会在屏内追上前车。`max(uᵢ, v)`
 * 一项同时覆盖两个危险点:后车更慢时决定判据的是入口处的间距,后车更快时决定判据的是前车
 * 退出左边界的那一刻——两者是同一个线性函数在区间两端的取值,取端点较严的那个就够,不需要
 * 逐帧扫描。
 *
 * 在所有 `slack >= 0` 的轨道里选 slack **最小**的一条:以最少的额外空白接续前车,把宽裕的
 * 轨道留给后面更难安置的弹幕。代价相同时优先上方轨道(观感稳定,也让结果与遍历顺序无关)。
 * 从未用过的轨道 remaining 为 0、slack = `W - g`,自然是最大值,于是最后才被选中——空轨道
 * 储备不需要单独的权重参数,它是这条排序规则的推论。
 *
 * 没有安全轨道就丢弃,不延迟:延迟会让弹幕脱离它对应的那句台词,点播场景下不可接受
 * (gap 分析 3.4「不默认延迟」)。
 */
class CollisionFreeScheduler(private val layout: DanmakuLayoutConfig) : DanmakuScheduler {

    // 轨道状态就这两个数组。固定弹幕是区间调度,只需要占用结束时刻。
    private val scrollLastEmitMillis = LongArray(layout.scrollTrackCount)
    private val scrollLastSpeed = FloatArray(layout.scrollTrackCount)
    private val scrollUsed = BooleanArray(layout.scrollTrackCount)
    private val topEndMillis = LongArray(layout.topTrackCount)
    private val bottomEndMillis = LongArray(layout.bottomTrackCount)
    private val topUsed = BooleanArray(layout.topTrackCount)
    private val bottomUsed = BooleanArray(layout.bottomTrackCount)

    override fun reset() {
        scrollLastEmitMillis.fill(0L)
        scrollLastSpeed.fill(0f)
        scrollUsed.fill(false)
        topEndMillis.fill(0L)
        bottomEndMillis.fill(0L)
        topUsed.fill(false)
        bottomUsed.fill(false)
    }

    // sequence 用不上:这一档的轨道由碰撞判据决定,与弹幕在池中的位置无关。
    override fun schedule(danmaku: Danmaku, size: DanmakuTextSize, sequence: Int): DanmakuFlightPlan? = when (danmaku.mode) {
        DanmakuMode.SCROLL -> scheduleScroll(danmaku, size)
        DanmakuMode.TOP -> scheduleFixed(danmaku, size, topEndMillis, topUsed)
        DanmakuMode.BOTTOM -> scheduleFixed(danmaku, size, bottomEndMillis, bottomUsed)
    }

    private fun scheduleScroll(danmaku: Danmaku, size: DanmakuTextSize): DanmakuFlightPlan? {
        val motion = scrollMotion(danmaku, size, layout)
        val t = danmaku.playTimeMillis
        val limit = layout.viewportPx.width - layout.minGapPx

        var bestTrack = -1
        var bestSlack = Float.MAX_VALUE
        for (track in 0 until layout.scrollTrackCount) {
            // 这两条分支必须给出相同的 slack:"没用过"和"用过但 remaining 已归零"在判据上
            // 不可区分,窗口化编排(见 [DanmakuCompiler])正是靠这一点才能丢掉 D 毫秒之前的
            // 历史。给空轨道加任何额外偏好都会让丢历史变成可观察的行为差异。
            val remaining = if (scrollUsed[track]) {
                max(0L, scrollLastEmitMillis[track] + layout.scrollDurationMillis - t)
            } else {
                0L
            }
            val slack = limit - max(scrollLastSpeed[track], motion.speed) * remaining
            // 严格小于:代价相同时保留先遇到的(编号更小的)轨道。
            if (slack >= 0f && slack < bestSlack) {
                bestSlack = slack
                bestTrack = track
            }
        }
        if (bestTrack < 0) return null

        scrollLastEmitMillis[bestTrack] = t
        scrollLastSpeed[bestTrack] = motion.speed
        scrollUsed[bestTrack] = true
        return scrollPlan(danmaku, size, layout, bestTrack, motion)
    }

    /** 固定弹幕是区间调度、first-fit:常规观感就是从最靠边那条开始往里填。 */
    private fun scheduleFixed(
        danmaku: Danmaku,
        size: DanmakuTextSize,
        endMillis: LongArray,
        used: BooleanArray,
    ): DanmakuFlightPlan? {
        val t = danmaku.playTimeMillis
        for (track in endMillis.indices) {
            if (used[track] && t < endMillis[track]) continue
            endMillis[track] = t + layout.fixedDurationMillis
            used[track] = true
            return fixedPlan(danmaku, size, layout, track)
        }
        return null
    }
}

/**
 * 不限档:按 `sequence % trackCount` 轮询发射,不判碰撞、不丢弃、不延迟。
 *
 * 这一档**不读也不写**标准档的轨道占用信息,编排复杂度是 O(N),代价转移到每帧的可见文字数量。
 * 它不需要虚拟轨道、最小遮挡搜索或实时碰撞列表——那些做法会把预计算和状态维护重新加回来,
 * 偏离"不限即允许碰撞"的定义。
 *
 * 序号由调用方给,这里**不自增计数器**。计数器版本的轨道只取决于"这是第几次调用",窗口化
 * 编排一旦从池子中间起步,同一条弹幕的轨道就随窗口起点漂移,seek 前后画面会整体跳一层。
 * [DanmakuCompiler] 传进来的是同模式内的池内序号,三种模式各数各的——这既保住了原来"某一类
 * 弹幕的到达不扰动另一类分布"的性质,又让结果只是池子的函数。
 *
 * 速度仍按同一套固定 duration 模型算(见 [scrollMotion]):这里用到的字宽来自渲染侧本来就要
 * 做的测量,和"排布不依赖字宽"不矛盾——决定轨道的只有序号。改用 `W / D` 倒会让文字在 D 时刻
 * 还没走完,穿屏时长对不同长度的弹幕就不统一了。
 */
class RoundRobinScheduler(private val layout: DanmakuLayoutConfig) : DanmakuScheduler {

    /** 没有状态可清。留着是因为接口要求,也因为"重置之后结果不变"正是这一档的性质。 */
    override fun reset() = Unit

    override fun schedule(
        danmaku: Danmaku,
        size: DanmakuTextSize,
        sequence: Int,
    ): DanmakuFlightPlan {
        val track = trackOf(sequence, layout.trackCount(danmaku.mode))
        return if (danmaku.mode == DanmakuMode.SCROLL) {
            scrollPlan(danmaku, size, layout, track, scrollMotion(danmaku, size, layout))
        } else {
            fixedPlan(danmaku, size, layout, track)
        }
    }

    // 取模再补一次:序号是 Int,超长弹幕池溢出成负数之后仍然要落在 [0, trackCount)。
    private fun trackOf(sequence: Int, trackCount: Int): Int = (sequence % trackCount + trackCount) % trackCount
}
