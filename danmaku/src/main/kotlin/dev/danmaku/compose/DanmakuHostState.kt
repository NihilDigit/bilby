package dev.danmaku.compose

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 弹幕帧循环的状态与调度。持有一个 [DanmakuClock] 和一份已编排的 [DanmakuTimeline],
 * 通过 [run] 驱动一个持续到协程被取消为止的帧循环,每次应该重绘时回调一次。
 *
 * 这一层是 emitter:只按全局播放时间查询 timeline,不判碰撞、不碰轨道状态。
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
    val frameRateCap: DanmakuFrameRateCap = DanmakuFrameRateCap.FPS_60,
) {
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)

    /**
     * 排布时用的画布尺寸与视口。`DanmakuHost` 一方面拿它画(轨道 y、裁剪矩形、滚动起点全部
     * 取自这里,不再各算一份),另一方面拿画布尺寸跟 Canvas 实际尺寸比对——两者不一致时所有
     * 位置都会算错,而画面上只表现成"位置怪怪的",指不出原因。
     */
    val layout: DanmakuLayoutConfig get() = timeline.layout

    /**
     * 遍历 `positionMillis` 时刻会在屏的弹幕。渲染层拿它做**预热**:提前一两秒把文字排版和
     * display list 准备好,好让绘制帧一次测量都不做。
     *
     * 和 [run] 的帧回调分开是因为两者问的是不同的问题 —— 帧回调问"现在画什么",这里问"马上
     * 要用到什么"。把预热塞进帧回调的 `visible` 列表里做不到:那个列表按定义只含已经在屏的,
     * 而预热的全部意义是在上屏**之前**把贵的活干完。
     */
    fun forEachVisibleAt(positionMillis: Long, visitor: (DanmakuFlightPlan) -> Unit) {
        timeline.visibleAt(positionMillis, visitor)
    }

    /** 通知帧循环重新评估当前状态。见类注释:哪些事件需要调用这个。 */
    fun notifyChanged() {
        wake.trySend(Unit)
    }

    /**
     * 持续运行帧循环,每次应该重绘时回调 [onFrame],直到所在协程被取消(通常是宿主
     * Composable 离开组合,由 `LaunchedEffect` 负责取消)。[onFrame] 拿到的 `visible`
     * 列表每次调用前都会被清空重填,不在调用之间保留身份 —— 需要跨帧持有就自己拷贝。
     */
    suspend fun run(onFrame: (visible: List<DanmakuFlightPlan>, positionMillis: Long) -> Unit) {
        val buffer = mutableListOf<DanmakuFlightPlan>()
        val frameScheduler = FrameDeadlineScheduler(frameRateCap.frameIntervalNanos)
        // 直接读 clock.positionMillis,不在这一层再插值。历史上这里有一个按锚点 + 经过时间 ×
        // 倍速做外推的 PositionInterpolator,理由是"positionMillis 通常是粗粒度轮询来源"——
        // 那个前提对 Bilby 的播放器不成立:`clock` 包的是同进程 ExoPlayer 或 MediaController,
        // 两者的 `getCurrentPosition()` 本身就是每次调用现算,内部同样按"锚点位置 + 经过时间 ×
        // 倍速"做外推,也就是说这里再插值一层等于叠了第二个各推各的估计器——两层锚点在权威
        // 更新落地的时刻不同步,倍速刚变化那一小段会互相打架,表现为"抖一下"。既然下层已经
        // 连续,上层插值不会让位置更平滑,只会多引入一处分歧,删掉即可。
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
                // 挂起期间时间轴上出现了一大段空档,相位要重新对齐,不能拿旧 deadline 补画。
                frameScheduler.reset()
                continue
            }

            withFrameNanos { frameNanos ->
                // 跳帧判断整个都在回调内部:不出帧就只更新 deadline,不重新采样、不回调。
                if (!frameScheduler.shouldDraw(frameNanos)) return@withFrameNanos

                coarsePosition = clock.positionMillis
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
            ((next.emitTimeMillis - position) / clock.playbackSpeed.coerceAtLeast(MIN_PLAYBACK_SPEED)).toLong()
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
