package dev.bilby.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * 竖向滚轮转成这一排的横滚。在 Initial 阶段截下:横排自己的滚动只认横向分量,竖向的会
 * 一路冒到外面的页面上去。
 *
 * 这一排还滚得动时消费掉;滚到头(或本来就放得下)不消费,页面照常往下滚。增量直接派发,
 * 不做动画:连续几格滚轮各起一段动画时,后一段会打断前一段,走过的距离比滚的格数少。
 */
fun Modifier.verticalWheelScrollsRow(state: ScrollableState): Modifier = pointerInput(state) {
    val stepPx = WheelStep.toPx()
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            val delta = change.scrollDelta
            if (abs(delta.y) <= abs(delta.x)) continue
            val px = delta.y * stepPx
            val canMove = if (px > 0) state.canScrollForward else state.canScrollBackward
            if (!canMove) continue
            change.consume()
            state.dispatchRawDelta(px)
        }
    }
}

/** 一格滚轮横移多少,约半张卡。 */
private val WheelStep = 96.dp
