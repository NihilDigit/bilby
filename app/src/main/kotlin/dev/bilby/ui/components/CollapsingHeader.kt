package dev.bilby.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * 随滚动退出屏幕的页头。
 *
 * 依据是 M3 transitions 页 enter/exit 那一节:"Components can enter and exit from beyond the
 * screen bounds based on a scroll gesture. This allows for more screen space to browse." ——
 * 页头是内容而不是导航,长列表里让它一直占着一块高度,等于把可读的行数永久减掉几行。
 *
 * **收起靠缩掉它占的高度,不是拿别的东西盖住它。** 盖住的话下面的 tab 栏会悬在一段空白上,
 * 列表顶部也会被一块看不见的东西挡着。
 */
@Stable
class CollapsingHeaderState {

    /** 页头量出来的完整高度(px)。测量时回填。 */
    var heightPx by mutableFloatStateOf(0f)
        internal set

    /** 已经收起了多少(px,负值向上)。范围是 `[-heightPx, 0]`。 */
    var offsetPx by mutableFloatStateOf(0f)
        internal set

    /**
     * **上滑时先收页头,下滑时先滚列表。**
     *
     * 两个方向不对称是有原因的:上滑(手指往上、内容往上走)时先把页头收掉,才符合"腾出
     * 空间去浏览";而下滑时如果也优先展开页头,列表滚到一半往回拉就会先看到页头冒出来、
     * 列表却不动,像卡了一下。所以下滑走 `onPostScroll` —— 只有列表已经到顶、剩下的位移
     * 没人要,才拿来展开页头。
     */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val delta = available.y
            if (delta >= 0f) return Offset.Zero
            val next = (offsetPx + delta).coerceIn(-heightPx, 0f)
            val consumed = next - offsetPx
            offsetPx = next
            return Offset(0f, consumed)
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource,
        ): Offset {
            val delta = available.y
            if (delta <= 0f) return Offset.Zero
            val next = (offsetPx + delta).coerceIn(-heightPx, 0f)
            val taken = next - offsetPx
            offsetPx = next
            return Offset(0f, taken)
        }
    }

    /**
     * 把页头重新展开。
     *
     * 收起是用户滚出来的,展开回去往往是别的事情引起的(播放页里是"视频又开始播了",
     * 见 VideoScreen),那时候手指不在屏幕上,直接跳回 0 会闪一下,所以走动画。
     */
    suspend fun expand() {
        animate(initialValue = offsetPx, targetValue = 0f) { value, _ -> offsetPx = value }
    }

    /** 一个方向的滚动才有意义;横向的留给 pager。 */
    internal val orientation = Orientation.Vertical
}

@Composable
fun rememberCollapsingHeaderState(): CollapsingHeaderState = remember { CollapsingHeaderState() }

/**
 * 把这个可组合项变成会随滚动收起的页头。
 *
 * 用 `layout` 自己排:量出完整高度之后,只对外声明"还剩多少可见",内容按 [offsetPx] 往上挪。
 * 下面的 tab 栏与列表因此跟着往上顶,而不是被一块透明的东西挡着。
 */
fun Modifier.collapsingHeader(state: CollapsingHeaderState): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    // 测量期间只在真的变了的时候写状态,免得每帧都触发一次重组。
    if (state.heightPx != placeable.height.toFloat()) {
        state.heightPx = placeable.height.toFloat()
        // 页头变高(签名多了一行)时,已经收起的量可能超出新的上限。
        state.offsetPx = state.offsetPx.coerceIn(-state.heightPx, 0f)
    }
    val visibleHeight = (placeable.height + state.offsetPx).roundToInt().coerceAtLeast(0)
    layout(placeable.width, visibleHeight) {
        placeable.placeRelative(0, state.offsetPx.roundToInt())
    }
}
