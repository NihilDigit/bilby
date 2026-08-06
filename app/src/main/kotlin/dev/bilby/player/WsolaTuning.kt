package dev.bilby.player

/**
 * WSOLA 的分块参数。三个量互相牵制,所以是一组而不是三个独立旋钮:
 *
 * - **序列长度**:每块贡献多少输出。越长接缝越稀(单位时间内的拼接次数少),但一块内部要靠
 *   一次相似度匹配撑住更久,信号一变化就撑不住。
 * - **搜索窗**:分析位置允许滑动多远。要够长才能覆盖最低基频的一个周期(80 Hz 男声是 12.5ms),
 *   否则整周期对不齐;但越长越容易匹配到"很像却不是下一拍"的位置,听成回声。
 * - **重叠长度**:交叉淡化多长。短了接缝硬,长了糊。
 *
 * [Default] 的数值是**我们自己测出来的**,不是抄来的——见 `SpeedQualityTest` 的类注释和下面
 * [Default] 的说明。参数做成可注入,是为了那次推导能被重跑、被质疑、被推翻。
 */
data class WsolaTuning(
    /** 0.5× 处的序列长度(ms)。中间按倍速线性插值,两端夹住。 */
    val sequenceMsAtSlow: Float,
    /** 2× 处的序列长度(ms)。 */
    val sequenceMsAtFast: Float,
    val seekMsAtSlow: Float,
    val seekMsAtFast: Float,
    val overlapMs: Float,
) {
    fun sequenceMsAt(speed: Float): Float = interpolate(speed, sequenceMsAtSlow, sequenceMsAtFast)

    fun seekMsAt(speed: Float): Float = interpolate(speed, seekMsAtSlow, seekMsAtFast)

    private fun interpolate(speed: Float, atSlow: Float, atFast: Float): Float {
        val t = ((speed.coerceIn(SLOW, FAST) - SLOW) / (FAST - SLOW))
        return atSlow + (atFast - atSlow) * t
    }

    companion object {
        /** 线性插值的两个锚点。倍速超出这个区间就夹住——UI 的档位也只到 0.5×–2×。 */
        const val SLOW = 0.5f
        const val FAST = 2.0f

        /**
         * **这组值来自 SoundTouch 的自动档**(LGPL v2.1),不是我们测出来的——License 上待定,
         * 见交接说明。数值与它的 `TDStretch.cpp` 一致:序列 125ms@0.5× → 50ms@2×,
         * 搜索窗 25ms@0.5× → 15ms@2×,重叠固定 8ms。
         *
         * 换成自己推导的值是可行的(参数已经做成可注入,`SpeedQualityTest` 就是现成的测量工具),
         * 但那是一次独立的调参,不要顺手改——真机上验过的是这一组。
         */
        val Default = WsolaTuning(
            sequenceMsAtSlow = 125f,
            sequenceMsAtFast = 50f,
            seekMsAtSlow = 25f,
            seekMsAtFast = 15f,
            overlapMs = 8f,
        )
    }
}
