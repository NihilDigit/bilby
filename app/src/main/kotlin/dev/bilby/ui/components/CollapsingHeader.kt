package dev.bilby.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
/**
 * @param canCollapse 这一刻允许不允许**收起**。展开不受它管:两个方向本来就分在
 *   [NestedScrollConnection] 的两个回调里(收起在 `onPreScroll`、展开在 `onPostScroll`),
 *   所以"只许展开、不许收起"这种单向限制天然落在这一个开关上,不必再加一个。
 *
 *   **收不收由它决定,而不是由调用方挂不挂 [connection] 决定。** 播放页里这个判断会随
 *   左右翻页变(见 VideoScreen 的 canCollapsePlayer),而按它增删 `Modifier.nestedScroll`
 *   等于在手势进行中改修饰符链,正在拖的那一下会被取消,表现是左右滑动卡在两页中间。
 */
@Stable
class CollapsingHeaderState(private val canCollapse: () -> Boolean = { true }) {

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
            if (!canCollapse() || delta >= 0f) return Offset.Zero
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

/**
 * @param canCollapse 见 [CollapsingHeaderState]。**读的是最新一次组合传进来的那个 lambda**,
 *   所以状态本身不必跟着重建 —— 重建会把已经收起的量清成 0。
 */
@Composable
fun rememberCollapsingHeaderState(canCollapse: () -> Boolean = { true }): CollapsingHeaderState {
    val latest by rememberUpdatedState(canCollapse)
    return remember { CollapsingHeaderState { latest() } }
}

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
