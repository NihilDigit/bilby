package dev.bilby.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 为横向滚动容器（Carousel / LazyRow 等）补充平滑滚轮驱动：
 * - 默认开启 [requireShift] = true，在纵向滚动页面内仅响应 `Shift + 滚轮` 或原生横向滚轮
 *   （`scrollDelta.x != 0f`），避免普通纵向滚轮被横向容器劫持；
 * - 连续多格滚轮通过累积未消费偏移并驱动协程平滑动画，避免 `dispatchRawDelta` 瞬移跳变或丢格。
 */
fun Modifier.verticalWheelScrollsRow(
    state: ScrollableState,
    requireShift: Boolean = true,
): Modifier = pointerInput(state, requireShift) {
    val stepPx = WheelStep.toPx()
    coroutineScope {
        var remainingPx = 0f
        var scrollJob: Job? = null
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                val change = event.changes.firstOrNull() ?: continue
                val delta = change.scrollDelta
                val isHorizontalWheel = abs(delta.x) > abs(delta.y) && delta.x != 0f
                val isAllowedVerticalWheel = abs(delta.y) > abs(delta.x) &&
                    (!requireShift || event.keyboardModifiers.isShiftPressed)
                val scrollUnits = when {
                    isHorizontalWheel -> delta.x
                    isAllowedVerticalWheel -> delta.y
                    else -> continue
                }
                val px = scrollUnits * stepPx
                val canMove = if (px > 0f) state.canScrollForward else state.canScrollBackward
                if (!canMove) {
                    if (scrollJob?.isActive != true) remainingPx = 0f
                    continue
                }
                change.consume()
                remainingPx += px
                scrollJob?.cancel()
                scrollJob = launch {
                    val target = remainingPx
                    var prev = 0f
                    state.scroll {
                        animate(
                            initialValue = 0f,
                            targetValue = target,
                            animationSpec = WheelScrollSpec,
                        ) { value, _ ->
                            val step = value - prev
                            prev = value
                            val consumed = scrollBy(step)
                            remainingPx -= step
                            if (consumed == 0f && step != 0f) {
                                remainingPx = 0f
                            }
                        }
                    }
                    remainingPx = 0f
                }
            }
        }
    }
}

/** 一格滚轮横移多少,约半张卡。 */
private val WheelStep = 96.dp

private val WheelScrollSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
