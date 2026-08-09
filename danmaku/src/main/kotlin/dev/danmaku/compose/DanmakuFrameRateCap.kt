package dev.danmaku.compose

/**
 * 帧循环的更新上限。
 *
 * [DISPLAY] 不叫 UNLIMITED:它并非真正不受限,上限就是面板这一刻的刷新率,而系统只把刷新率
 * 请求当作偏好——面板能力、省电模式、更高优先级的 Surface 都可能让实际值低于请求值。
 */
enum class DanmakuFrameRateCap(val targetFps: Int) {
    FPS_30(30),
    FPS_60(60),

    /** 跟随屏幕:每个 Compose frame 都出一帧,并向系统请求高刷新率(见 [DanmakuHost])。 */
    DISPLAY(0),
    ;

    internal val frameIntervalNanos: Long
        get() = if (targetFps <= 0) 0L else 1_000_000_000L / targetFps
}

/**
 * 绝对时间轴上的 deadline 调度器:决定这一个 vsync 该不该出帧。
 *
 * **deadline 按固定步长推进,不从实际出帧时刻重新计时。** 上一版判的是"距离上次实际出帧是否
 * 超过目标间隔的 90%",那只在面板刷新率恰好是目标帧率整数倍时成立:144Hz 选 60fps 时两个
 * vsync 是 13.89ms(小于阈值,跳过)、三个是 20.83ms(通过),于是恒定按 3 个 vsync 出帧,
 * 实际只有约 48fps;90Hz 掉到 45,165Hz 掉到 55。误差来自"以实际出帧时刻为新的起点"——每次
 * 都把上一次的取整误差累加进去,永远追不回来。
 *
 * deadline 固定按 `interval` 推进之后,每次出帧的滞后不超过一个 vsync 且不累积,`T` 时间内的
 * 出帧数恒为 `T / interval ± 1`——144Hz 上表现为 2、2、3 个 vsync 交替,长期平均就是 60fps。
 */
internal class FrameDeadlineScheduler(private val intervalNanos: Long) {

    private var nextDeadlineNanos = Long.MIN_VALUE

    /** 帧循环挂起过(暂停、屏上没有弹幕)之后调用:时间轴上出现了长空档,不该补画。 */
    fun reset() {
        nextDeadlineNanos = Long.MIN_VALUE
    }

    fun shouldDraw(frameNanos: Long): Boolean {
        if (intervalNanos <= 0L) return true
        if (nextDeadlineNanos == Long.MIN_VALUE || frameNanos - nextDeadlineNanos > intervalNanos) {
            // 首帧,或者落后了不止一个周期(挂起归来、掉帧)。补画追不回已经过去的时间,
            // 只会一连出好几帧,所以直接把相位对齐到当前帧重新起算。
            nextDeadlineNanos = frameNanos + intervalNanos
            return true
        }
        if (frameNanos < nextDeadlineNanos) return false
        nextDeadlineNanos += intervalNanos
        return true
    }
}
