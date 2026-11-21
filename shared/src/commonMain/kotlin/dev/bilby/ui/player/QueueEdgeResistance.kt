package dev.bilby.ui.player

import kotlin.math.min

/**
 * 队列边界翻页阻力状态机：让用户滚到当前已加载队列的首尾时自然停住，
 * 只有继续朝边界方向有意越界拉动 / 滚动超过阈值后才触发下一页加载。
 *
 * - 触屏 / 触控笔（`isTouchLike = true`）：在列表已到边界且本帧无剩余可滚距离（`consumedY == 0f`）时，
 *   继续拉动累积阻力进度（带阻尼），达到 `1f` 后**松手**才触发续取；未达阈值松手或反向回拉则平滑归零不触发。
 *   惯性甩动（`NestedScrollSource.SideEffect`）由调用方过滤，绝不误触。
 * - 鼠标滚轮 / 触控板（`isTouchLike = false`）：已到边界后需继续朝边界方向累积超过阈值
 *   （约 2–3 格滚轮）才触发续取；反向滚动立即归零，中途停手由外层协程在短暂停顿后回弹归零。
 */
internal class QueueEdgeResistanceLogic(
    touchThresholdPx: Float,
    pointerThresholdPx: Float,
) {
    var touchThresholdPx: Float = touchThresholdPx.coerceAtLeast(1f)
    var pointerThresholdPx: Float = pointerThresholdPx.coerceAtLeast(1f)

    /** 顶部边界（before = true）已累积的越界像素。 */
    var topRawPx: Float = 0f
        private set

    /** 底部边界（before = false）已累积的越界像素。 */
    var bottomRawPx: Float = 0f
        private set

    fun threshold(isTouchLike: Boolean): Float =
        if (isTouchLike) touchThresholdPx else pointerThresholdPx

    fun topProgress(isTouchLike: Boolean): Float =
        (topRawPx / threshold(isTouchLike)).coerceIn(0f, MAX_OVERSCROLL_RATIO)

    fun bottomProgress(isTouchLike: Boolean): Float =
        (bottomRawPx / threshold(isTouchLike)).coerceIn(0f, MAX_OVERSCROLL_RATIO)

    fun isTopArmed(isTouchLike: Boolean): Boolean = topRawPx >= threshold(isTouchLike)

    fun isBottomArmed(isTouchLike: Boolean): Boolean = bottomRawPx >= threshold(isTouchLike)

    fun reset(before: Boolean? = null) {
        if (before == null || before == true) topRawPx = 0f
        if (before == null || before == false) bottomRawPx = 0f
    }

    /**
     * `NestedScrollConnection.onPreScroll`（仅 `NestedScrollSource.UserInput`）：
     * - 触屏反向回拉时按像素消费以平滑收拢已拉出的阻力，不跳动列表；
     * - 键鼠反向滚动时直接清空残留阻力，不阻碍正常回滚。
     */
    fun onPreScroll(availableY: Float, isTouchLike: Boolean): Float {
        if (availableY == 0f) return 0f
        if (isTouchLike) {
            if (topRawPx > 0f && availableY < 0f) {
                val unwind = min(topRawPx, -availableY)
                topRawPx -= unwind
                return -unwind
            }
            if (bottomRawPx > 0f && availableY > 0f) {
                val unwind = min(bottomRawPx, availableY)
                bottomRawPx -= unwind
                return unwind
            }
            return 0f
        }
        if (topRawPx > 0f && availableY < 0f) topRawPx = 0f
        if (bottomRawPx > 0f && availableY > 0f) bottomRawPx = 0f
        return 0f
    }

    /**
     * `NestedScrollConnection.onPostScroll`（仅 `NestedScrollSource.UserInput`）：
     * 仅在列表已无剩余可滚距离（`consumedY == 0f`）时计入越界阻力，确保首次撞边自然停住。
     */
    fun onPostScroll(
        consumedY: Float,
        availableY: Float,
        isTouchLike: Boolean,
        canLoadBefore: Boolean,
        canLoadAfter: Boolean,
    ): EdgeScrollOutcome {
        if (availableY == 0f || consumedY != 0f) return EdgeScrollOutcome.None
        val thresholdPx = threshold(isTouchLike)
        val maxRaw = thresholdPx * MAX_OVERSCROLL_RATIO

        if (availableY > 0f) {
            bottomRawPx = 0f
            if (!canLoadBefore) {
                topRawPx = 0f
                return EdgeScrollOutcome.None
            }
            val wasArmed = topRawPx >= thresholdPx
            topRawPx = (topRawPx + availableY).coerceAtMost(maxRaw)
            val nowArmed = topRawPx >= thresholdPx
            if (!isTouchLike && nowArmed) {
                topRawPx = 0f
                return EdgeScrollOutcome(
                    consumedY = availableY,
                    triggeredBefore = true,
                    crossedTouchThreshold = false,
                )
            }
            return EdgeScrollOutcome(
                consumedY = availableY,
                triggeredBefore = null,
                crossedTouchThreshold = isTouchLike && !wasArmed && nowArmed,
            )
        } else {
            topRawPx = 0f
            if (!canLoadAfter) {
                bottomRawPx = 0f
                return EdgeScrollOutcome.None
            }
            val wasArmed = bottomRawPx >= thresholdPx
            bottomRawPx = (bottomRawPx + (-availableY)).coerceAtMost(maxRaw)
            val nowArmed = bottomRawPx >= thresholdPx
            if (!isTouchLike && nowArmed) {
                bottomRawPx = 0f
                return EdgeScrollOutcome(
                    consumedY = availableY,
                    triggeredBefore = false,
                    crossedTouchThreshold = false,
                )
            }
            return EdgeScrollOutcome(
                consumedY = availableY,
                triggeredBefore = null,
                crossedTouchThreshold = isTouchLike && !wasArmed && nowArmed,
            )
        }
    }

    /**
     * 触屏松手（`onPreFling`）：达到阻力阈值则触发对应方向续取并归零，否则仅归零。
     *
     * @return `true` 触发向前续取、`false` 触发向后续取、`null` 未达阈值不触发。
     */
    fun onTouchRelease(canLoadBefore: Boolean, canLoadAfter: Boolean): Boolean? {
        val trigger = when {
            topRawPx >= touchThresholdPx && canLoadBefore -> true
            bottomRawPx >= touchThresholdPx && canLoadAfter -> false
            else -> null
        }
        topRawPx = 0f
        bottomRawPx = 0f
        return trigger
    }

    /**
     * 处理在列表已到边界时未被 `LazyColumn` 消费的滚轮 / 触控板 `PointerEventType.Scroll` 事件。
     * `scrollDeltaY > 0` 表示向下滚（朝向队尾 after），`scrollDeltaY < 0` 表示向上滚（朝向队首 before）。
     */
    fun onWheelBoundaryScroll(
        scrollDeltaY: Float,
        wheelStepPx: Float,
        atTop: Boolean,
        atBottom: Boolean,
        canLoadBefore: Boolean,
        canLoadAfter: Boolean,
    ): EdgeScrollOutcome {
        if (scrollDeltaY == 0f || wheelStepPx <= 0f) return EdgeScrollOutcome.None
        if (scrollDeltaY < 0f && atTop) {
            val availableY = -scrollDeltaY * wheelStepPx
            return onPostScroll(
                consumedY = 0f,
                availableY = availableY,
                isTouchLike = false,
                canLoadBefore = canLoadBefore,
                canLoadAfter = false,
            )
        }
        if (scrollDeltaY > 0f && atBottom) {
            val availableY = -scrollDeltaY * wheelStepPx
            return onPostScroll(
                consumedY = 0f,
                availableY = availableY,
                isTouchLike = false,
                canLoadBefore = false,
                canLoadAfter = canLoadAfter,
            )
        }
        if (scrollDeltaY > 0f && topRawPx > 0f) topRawPx = 0f
        if (scrollDeltaY < 0f && bottomRawPx > 0f) bottomRawPx = 0f
        return EdgeScrollOutcome.None
    }

    companion object {
        const val MAX_OVERSCROLL_RATIO = 1.25f
    }
}

internal data class EdgeScrollOutcome(
    val consumedY: Float,
    val triggeredBefore: Boolean?,
    val crossedTouchThreshold: Boolean,
) {
    val consumed: Boolean get() = consumedY != 0f

    companion object {
        val None = EdgeScrollOutcome(0f, null, false)
    }
}
