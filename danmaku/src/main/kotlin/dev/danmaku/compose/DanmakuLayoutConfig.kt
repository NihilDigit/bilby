package dev.danmaku.compose

/**
 * 一条弹幕的中立尺寸。调度器只认这两个 float,不认 `TextLayoutResult`、`TextStyle` 或任何
 * 平台排版对象——平台 layout 由渲染准备层持有,进不了这一层。
 */
data class DanmakuTextSize(val widthPx: Float, val heightPx: Float)

/**
 * 排布的全部输入。画布尺寸、显示区域、行高、穿屏时长、最小间距——调度器不知道什么是字体、
 * 什么是屏幕密度,这些量全部由调用方算好注入。
 *
 * @param canvasWidthPx 弹幕画布的像素宽,渲染时必须与 Canvas 实际宽度一致(见 [DanmakuHost])。
 * @param canvasHeightPx 同上,像素高。底部弹幕的纵向范围以它为准,不是以 [viewport] 为准。
 * @param viewport 滚动与顶部弹幕的显示区域。**不覆盖底部弹幕**,理由见 [DanmakuViewport]。
 * @param trackHeightPx 一条轨道占用的行高,滚动与固定弹幕共用。
 * @param scrollDurationMillis 统一穿屏时长 D:任何长度的滚动弹幕都在这个时间内走完
 *   "视口宽 + 自身虚拟宽",长弹幕因此更快。slack 判据整个建立在这个量统一之上,见
 *   [CollisionFreeScheduler]。
 * @param fixedDurationMillis 顶/底固定弹幕停留时长。
 * @param minGapPx 同轨相邻弹幕的最小水平间距 g。
 * @param jitterFraction 尾部虚拟留白的上限比例。0 时所有弹幕严格同步,画面像队列;这里给的
 *   留白是**确定性**的(`hash(id)`),不是随机速度扰动——扰动会让轨道状态无法只用
 *   `(emitTime, speed)` 两个数表达,见 [CollisionFreeScheduler] 的说明。
 * @param bottomTrackFraction 底部弹幕最多占**画面**高度的比例。它需要自己的上限:底部弹幕不
 *   受 [viewport] 约束,没有这个数就能一路往上堆满整个画面。做成配置项而不是常量,是因为它
 *   将来要跟避让区(字幕条、播放控件)一起调整,那时它就是那个旋钮。
 */
data class DanmakuLayoutConfig(
    val canvasWidthPx: Float,
    val canvasHeightPx: Float,
    val trackHeightPx: Float,
    val viewport: DanmakuViewport = DanmakuViewport(),
    val scrollDurationMillis: Long = 8_000L,
    val fixedDurationMillis: Long = 4_000L,
    val minGapPx: Float = 12f,
    val jitterFraction: Float = 0.075f,
    val bottomTrackFraction: Float = 0.3f,
) {
    val viewportPx: DanmakuViewportPx = viewport.resolve(canvasWidthPx, canvasHeightPx)

    /**
     * 滚动轨道数,视口高度能放下几行就是几行。画布还没布局出来(尺寸为 0)时给 1 条占位,
     * 不除零——首帧过后立刻被真实值取代。
     */
    val scrollTrackCount: Int = trackCapacity(viewportPx.height)

    /**
     * 顶部轨道数。和滚动共用视口容量:顶部弹幕锚视口顶边往下堆,能堆到哪儿由视口说了算,
     * 不再另打折扣。
     *
     * 滚动与顶部彼此**不**互相占用,跨模式遮挡是已知的、这一版接受的行为(gap 分析 3.4
     * 「仍需定义的显示规则」第 1 条):真要互相避让得引入共享占用层,那是产品决定,不是这里
     * 顺手加的。
     */
    val topTrackCount: Int = scrollTrackCount

    /** 底部轨道数按**画面**高度算,不按视口——底部弹幕不在视口里。 */
    val bottomTrackCount: Int = trackCapacity(canvasHeightPx * bottomTrackFraction)

    fun trackCount(mode: DanmakuMode): Int = when (mode) {
        DanmakuMode.SCROLL -> scrollTrackCount
        DanmakuMode.TOP -> topTrackCount
        DanmakuMode.BOTTOM -> bottomTrackCount
    }

    private fun trackCapacity(availableHeightPx: Float): Int =
        if (trackHeightPx > 0f) (availableHeightPx / trackHeightPx).toInt().coerceAtLeast(1) else 1
}
