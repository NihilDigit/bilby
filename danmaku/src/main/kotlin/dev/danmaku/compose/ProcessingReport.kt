package dev.danmaku.compose

/**
 * 一次(或累计多次)编排的结果统计。
 *
 * 存在的理由是"丢弃必须可见":上一版用 `append` 返回 null 表示丢弃,而两个调用点都是
 * `forEach`,返回值直接落地了——用户既看不到丢弃率,也分不清"这段原本就没弹幕"和"引擎为了
 * 避碰丢了弹幕"。统计不是"没暴露",是压根没接住。所以这份报告由 [DanmakuCompiler] 返回,
 * 调用方拿到手才算编排完成。
 *
 * @param inputCount 提交进来的弹幕条数。
 * @param scheduledCount 实际排上轨道的条数。
 * @param droppedByLayoutCount 因为没有安全轨道被丢弃的条数(不限密度档恒为 0)。
 * @param peakConcurrentCount 峰值同屏条数。
 * @param compileDurationMillis 编排耗时,累计。
 */
data class ProcessingReport(
    val inputCount: Int = 0,
    val scheduledCount: Int = 0,
    val droppedByLayoutCount: Int = 0,
    val peakConcurrentCount: Int = 0,
    val compileDurationMillis: Long = 0L,
) {
    val dropRate: Float get() = if (inputCount == 0) 0f else droppedByLayoutCount.toFloat() / inputCount
}
