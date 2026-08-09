package dev.danmaku.compose

/**
 * 一条弹幕排布完成后的不可变结果。调度器产出它之后,轨道占用状态就可以丢掉了——emitter 按
 * 全局播放时间查询,renderer 把它投影成坐标,两者都不读也不写任何轨道信息,seek 只需要
 * "timeline + 播放时间"就能恢复画面。
 *
 * 位置公式(滚动):`x = viewport.right - (t - emitTimeMillis) * speedPxPerMillis`,文字占据
 * `[x, x + widthPx]`。固定弹幕 [speedPxPerMillis] 恒为 0,横向居中。
 *
 * @param durationMillis **占位**时长:滚动弹幕是统一穿屏时长 D,即虚拟包围盒(文字 + 尾部
 *   留白)走完 `视口宽 + 虚拟宽` 所需的时间。slack 判据用的是这个量。
 * @param visibleUntilMillis 真实文字彻底离开视口左边界的时刻,总是 <= `emit + duration`
 *   (尾部留白是虚的,画不出来)。emitter 用它决定还要不要画,用 [durationMillis] 会白画
 *   一段空气。
 */
data class DanmakuFlightPlan(
    val danmaku: Danmaku,
    val mode: DanmakuMode,
    val track: Int,
    val emitTimeMillis: Long,
    val durationMillis: Long,
    val visibleUntilMillis: Long,
    val speedPxPerMillis: Float,
    val widthPx: Float,
    val heightPx: Float,
)
