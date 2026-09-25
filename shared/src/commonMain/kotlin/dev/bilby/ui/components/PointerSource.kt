package dev.bilby.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 最近一次输入是不是能"拉"的那类(手指、触控笔)。交互约定跟着输入设备走,不跟着平台走:
 * 触屏笔记本上的手指该能下拉刷新,接了鼠标的平板上的滚轮不该。
 *
 * 读它的是下拉刷新的开关:鼠标与触控板没有"拉住再松手"这个动作,滚轮滚到顶再多滚一格
 * 就会被当成一次下拉。
 *
 * **只在类别切换时写快照状态。** 每个事件都写的话就是每个事件一次重组;实际只在人从鼠标
 * 换到手指(或反过来)的那一下才变。没见过任何指针时按触摸算,那是纯触屏设备的初始状态。
 */
class PointerSource {
    var isTouchLike: Boolean by mutableStateOf(true)
        private set

    internal fun observe(type: PointerType) {
        val touchLike = type == PointerType.Touch || type == PointerType.Stylus || type == PointerType.Unknown
        if (touchLike != isTouchLike) isTouchLike = touchLike
    }
}

val LocalPointerSource = staticCompositionLocalOf { PointerSource() }

/**
 * 标签页的分页器只让手指横划翻页。鼠标按住画面一拖就换一页,是桌面上最常见的误操作:
 * 本想选中文字、拖动进度,或者只是按下时手抖了一下。用鼠标换页有顶上的标签。
 *
 * 挂在分页器自己的 modifier 上:它是分页器滚动的上层,分页器每一步拖动先问它
 * ([onPreScroll]),鼠标来源的横向量在这里全部吃掉,分页器就不动。原始指针位移不碰,
 * 页面里的滑块、进度条照样能用鼠标拖。
 */
@Composable
fun Modifier.touchOnlyPaging(): Modifier {
    val pointers = LocalPointerSource.current
    val connection = remember(pointers) { TouchOnlyPaging(pointers) }
    return nestedScroll(connection)
}

private class TouchOnlyPaging(private val pointers: PointerSource) : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (source == NestedScrollSource.UserInput && !pointers.isTouchLike) Offset(available.x, 0f) else Offset.Zero
}

/**
 * 挂在根部,在 [PointerEventPass.Initial] 只看不吃。进出事件不算:手指操作时它们也可能带着
 * 鼠标类型到达,会把来源记错。
 */
fun Modifier.trackPointerSource(source: PointerSource): Modifier = pointerInput(source) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Enter || event.type == PointerEventType.Exit) continue
            event.changes.firstOrNull()?.let { source.observe(it.type) }
        }
    }
}
