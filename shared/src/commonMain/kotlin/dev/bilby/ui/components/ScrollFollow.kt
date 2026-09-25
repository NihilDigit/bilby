package dev.bilby.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/**
 * "内容在长,就跟着底边走;用户自己滑了,就钉在他停的地方。"
 *
 * 助理跑起来之后内容是一段一段冒出来的,不跟随的话用户只看得到最初几步 —— 那几步恰好是
 * 信息量最小的。反过来,一旦用户主动往回滑,他就是在看某一步的中间结果,这时候任何自动
 * 滚动都是把他手里的东西抢走。判据只有一条:**这一次滚动是不是用户自己发起的**,
 * [NestedScrollSource.UserInput] 就是这个问题的答案,程序滚动不会带这个来源。
 *
 * 用户滑回底部就恢复跟随 —— 不需要另设一个"重新跟上"的按钮,回到底部这个动作本身
 * 就表达了同一件事。
 */
@Stable
class BottomFollow internal constructor(private val state: ScrollableState) {

    var following by mutableStateOf(true)
        private set

    val connection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput) following = !state.canScrollForward
            return Offset.Zero
        }

        // 甩出去的那一段不带 UserInput 来源(惯性滚动算 SideEffect),落定之后要再判一次:
        // 少了这里,用户一记快滑到底也不会恢复跟随,得再手动挪一下才行。
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            following = !state.canScrollForward
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberBottomFollow(state: ScrollableState): BottomFollow = remember(state) { BottomFollow(state) }

/**
 * 内容一变就把底边推回视口底部。
 *
 * 每次 layout 变化只补一段距离,靠 [snapshotFlow] 的下一次发射继续收敛 —— 滚动本身会引起
 * 新的 layout,而滚到底之后差值为 0,循环自然停住。一次算准的写法要在 `scrollToItem` 之后
 * 立刻读 `layoutInfo`,那时它还是上一帧的。
 *
 * @param enabled 助理跑完就该关掉:那之后内容不再增长,而答案要从第一行读起,不是从底边读起。
 */
@Composable
fun KeepScrolledToBottom(state: LazyListState, follow: BottomFollow, enabled: Boolean) {
    LaunchedEffect(state, follow, enabled) {
        if (!enabled) return@LaunchedEffect
        snapshotFlow { state.layoutInfo }.collect { info ->
            if (!follow.following) return@collect
            val lastIndex = info.totalItemsCount - 1
            if (lastIndex < 0) return@collect
            val last = info.visibleItemsInfo.lastOrNull()
            if (last == null || last.index != lastIndex) {
                state.scrollToItem(lastIndex)
                return@collect
            }
            val overshoot = last.offset + last.size - (info.viewportEndOffset - info.afterContentPadding)
            if (overshoot > 0) state.scrollBy(overshoot.toFloat())
        }
    }
}

@Composable
fun KeepScrolledToBottom(state: ScrollState, follow: BottomFollow, enabled: Boolean) {
    LaunchedEffect(state, follow, enabled) {
        if (!enabled) return@LaunchedEffect
        // maxValue 在首次 layout 之前是 Int.MAX_VALUE,那不是一个能滚到的位置。
        snapshotFlow { state.maxValue }.collect { max ->
            if (follow.following && max != Int.MAX_VALUE) state.scrollTo(max)
        }
    }
}
