package dev.danmaku.compose

/**
 * 渲染侧的缓存统计。和 [ProcessingReport] 分开是分层要求:那份是编排层的产物(纯 stdlib,
 * 要能整体搬进 commonMain),这份记的是平台排版与 display list 的行为,只在渲染层存在。
 *
 * 计数器都是单调累加的,不在帧之间清零 —— 命中率要能跨整段播放看趋势,清零会把"刚 seek 完
 * 那几帧全是未命中"这种最值得看的现象洗掉。唯一的瞬时量是 [liveLayerCount]。
 *
 * 只在主线程(帧回调与绘制块)读写,不加同步。
 */
class DanmakuRenderStats {

    /** 排版缓存命中次数:准备一条弹幕时,同文本同排版属性的 `TextLayoutResult` 已经在表里。 */
    var layoutHitCount: Long = 0L
        private set

    /** 排版缓存未命中次数,每次对应一次真实的 `TextMeasurer.measure`。 */
    var layoutMissCount: Long = 0L
        private set

    /** 新建 `GraphicsLayer` 并录制 display list 的次数。 */
    var layerCreatedCount: Long = 0L
        private set

    /** 直接复用已录制 display list 的次数,每帧每条可见弹幕计一次。 */
    var layerReusedCount: Long = 0L
        private set

    /** 释放 `GraphicsLayer` 的次数(淘汰 + 整体释放)。泄漏表现为它长期远小于创建数。 */
    var layerReleasedCount: Long = 0L
        private set

    /** 当前还活着的 `GraphicsLayer` 数量。这是瞬时量,不累加。 */
    var liveLayerCount: Int = 0
        private set

    /**
     * 绘制帧里不得已当场准备(排版 + 录制)的次数。预热窗口的意义就是让它保持为 0(除了刚
     * seek 完那一两帧),持续增长说明预热预算太小或者窗口太短 —— 这是这份统计里唯一需要报警
     * 的数。它不等于 [layoutMissCount]:当场准备时排版本身可能是命中的,贵的是录制那一步。
     */
    var latePrepareCount: Long = 0L
        private set

    val layoutHitRate: Float
        get() {
            val total = layoutHitCount + layoutMissCount
            return if (total == 0L) 0f else layoutHitCount.toFloat() / total
        }

    internal fun onLayoutHit() {
        layoutHitCount++
    }

    internal fun onLayoutMiss() {
        layoutMissCount++
    }

    internal fun onLayerCreated(live: Int) {
        layerCreatedCount++
        liveLayerCount = live
    }

    internal fun onLayerReused() {
        layerReusedCount++
    }

    internal fun onLayerReleased(live: Int) {
        layerReleasedCount++
        liveLayerCount = live
    }

    internal fun onLatePrepare() {
        latePrepareCount++
    }

    override fun toString(): String =
        "DanmakuRenderStats(layout hit=$layoutHitCount miss=$layoutMissCount rate=$layoutHitRate, " +
            "layer created=$layerCreatedCount reused=$layerReusedCount released=$layerReleasedCount " +
            "live=$liveLayerCount, latePrepare=$latePrepareCount)"
}
