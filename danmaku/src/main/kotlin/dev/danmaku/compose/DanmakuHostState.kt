package dev.danmaku.compose

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 弹幕帧循环的状态与调度。持有一个 [DanmakuClock] 和一份已编译的 [DanmakuTimeline],
 * 通过 [run] 驱动一个持续到协程被取消为止的帧循环,每次应该重绘时回调一次。
 *
 * 帧循环在两种情况下整体挂起,不空转 vsync —— `while(true){ withFrameNanos }` 本身会形成
 * 自我维持的循环,而"UI surface 出帧 + 系统合成"这条链路很贵:
 * 1. 屏上没有可见弹幕(没什么可画);
 * 2. 暂停中,哪怕屏上还有弹幕 —— 暂停时位置冻结,每帧重画同一批一动不动的弹幕纯属浪费,
 *    而"暂停时屏上有弹幕"恰恰是最常见的情形(暂停本来就是为了看清楚屏上的东西)。
 * 这两条是"或"的关系,任意一条成立就挂起。挂起点等的是 [notifyChanged] 信号,或者(仅在
 * 播放中)时间轴上下一条已排定弹幕的到场时刻,谁先到算谁;详见 [run] 的实现注释。
 *
 * 时钟只轮询读值,不要求是 Compose `State`。外部导致可见性发生变化的事件 —— 恢复播放、
 * seek、在线追加了新弹幕 —— 需要调 [notifyChanged] 才能让挂起中的帧循环立刻醒来;忘记调用
 * 不会永远卡住,有一个 [IDLE_POLL_FALLBACK_MILLIS] 的兜底轮询兜底,只是响应会迟到那么久。
 */
class DanmakuHostState(
    private val clock: DanmakuClock,
    private val timeline: DanmakuTimeline,
    private val frameRateCap: DanmakuFrameRateCap = DanmakuFrameRateCap.FPS_60,
) {
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)
    private val interpolator = PositionInterpolator()

    /**
     * [timeline] 编译时用的画布宽度。`DanmakuHost` 拿它跟 Canvas 实际像素宽度比对 ——
     * 两者不一致时所有滚动弹幕的位置都会算错,而画面上只表现成"位置怪怪的",指不出原因,
     * 所以这个属性存在的意义就是让那次比对能做起来,不是提供给别的用途。
     */
    val compiledScreenWidthPx: Float get() = timeline.config.screenWidthPx

    /** 通知帧循环重新评估当前状态。见类注释:哪些事件需要调用这个。 */
    fun notifyChanged() {
        wake.trySend(Unit)
    }

    /**
     * 持续运行帧循环,每次应该重绘时回调 [onFrame],直到所在协程被取消(通常是宿主
     * Composable 离开组合,由 `LaunchedEffect` 负责取消)。[onFrame] 拿到的 `visible`
     * 列表每次调用前都会被清空重填,不在调用之间保留身份 —— 需要跨帧持有就自己拷贝。
     */
    suspend fun run(onFrame: (visible: List<CompiledDanmaku>, positionMillis: Long) -> Unit) {
        val buffer = mutableListOf<CompiledDanmaku>()
        var lastFrameNanos = 0L
        // 粗粒度、未插值的位置,只用来判断"现在是不是空的",决定要不要挂起 —— 插值需要一个
        // withFrameNanos 给出的单调时间戳做锚点,循环外还没有这个时间戳。
        var coarsePosition = clock.positionMillis

        while (true) {
            buffer.clear()
            timeline.visibleAt(coarsePosition) { buffer.add(it) }

            // 两个挂起条件是"或":没什么可画,或者画了也不会变(暂停)。两者独立判断,
            // 不要合并成互斥分支 —— "暂停且有可见弹幕"这个组合本身就要求先 onFrame 一次
            // 把冻结的画面交出去,再挂起,和"没有可见弹幕"那条走的是同一段收尾代码。
            if (buffer.isEmpty() || !clock.isPlaying) {
                onFrame(buffer, coarsePosition)
                awaitNextWakeUp(coarsePosition)
                coarsePosition = clock.positionMillis
                continue
            }

            withFrameNanos { frameNanos ->
                // 跳帧判断整个都在回调内部:不够就只记账,不重新采样、不回调 —— 零状态写入。
                val capNanos = frameRateCap.minFrameIntervalNanos
                if (capNanos > 0 && frameNanos - lastFrameNanos < capNanos) return@withFrameNanos
                lastFrameNanos = frameNanos

                coarsePosition = interpolator.sample(clock, frameNanos)
                buffer.clear()
                timeline.visibleAt(coarsePosition) { buffer.add(it) }
                onFrame(buffer, coarsePosition)
            }
        }
    }

    /**
     * 挂起到"下一条已排定弹幕的到场时刻"或者 [notifyChanged] 信号,谁先到算谁。
     * 暂停中,或者时间轴上已经没有更多弹幕时,已排定到场时刻不存在,退化成
     * [IDLE_POLL_FALLBACK_MILLIS] 的兜底轮询 —— 见类注释,这是"忘调 notifyChanged"的代价
     * 上限,不是主要的唤醒路径。
     */
    private suspend fun awaitNextWakeUp(position: Long) {
        val next = timeline.nextEntryAfter(position)
        val scheduledDelay = if (next != null && clock.isPlaying) {
            ((next.t0Millis - position) / clock.playbackSpeed.coerceAtLeast(MIN_PLAYBACK_SPEED)).toLong()
        } else {
            null
        }
        val timeoutMillis = (scheduledDelay ?: IDLE_POLL_FALLBACK_MILLIS)
            .coerceIn(0L, IDLE_POLL_FALLBACK_MILLIS)
        withTimeoutOrNull(timeoutMillis) { wake.receive() }
    }

    private companion object {
        const val IDLE_POLL_FALLBACK_MILLIS = 500L
        const val MIN_PLAYBACK_SPEED = 0.01f
    }
}

/**
 * 把 [DanmakuClock] 的粗粒度位置插值到帧级。每次权威采样(`positionMillis` 变化)落地时
 * 重新锚定,避免插值误差随时间累积;锚点之间按 `playbackSpeed` 线性外推,暂停时冻结在锚点。
 */
private class PositionInterpolator {
    private var anchorRawMillis = Long.MIN_VALUE
    private var anchorNanos = 0L

    fun sample(clock: DanmakuClock, nowNanos: Long): Long {
        val raw = clock.positionMillis
        if (raw != anchorRawMillis) {
            anchorRawMillis = raw
            anchorNanos = nowNanos
            return raw
        }
        if (!clock.isPlaying) return anchorRawMillis
        val elapsedMillis = (nowNanos - anchorNanos) / 1_000_000L
        return anchorRawMillis + (elapsedMillis * clock.playbackSpeed).toLong()
    }
}
