package dev.bilby.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.bilby.ui.imeTarget
import dev.bilby.ui.screenOrientationKey
import kotlinx.coroutines.flow.collectLatest

/**
 * 贴底输入栏底下要让出的高度:键盘与导航栏取并集(键盘弹起时它的 inset 已经盖过导航栏,两个相加就是
 * 垫两层),减去外面已经让过的。
 *
 * **键盘升起的动画里,最多升到它上一次落定的高度。** 真机上量到过(HyperOS,2026-09-25):同一个
 * 输入场景第一次弹键盘,动画目标 913px,一路准确;第二次起动画目标报成 1012px,inset 跟着升到
 * 1011,动画结束才改报 913 —— 而键盘在屏幕上一直是同样高。输入栏贴着这个虚高的值走,就是"弹上去
 * 又往下退一段"。虚高出在输入法那一侧,这里只能绕开:记住上一次落定的真实高度
 * ([SettledKeyboardHeight],按横竖屏分开),动画途中不超过它。
 *
 * 键盘真的变高了(换输入法、切到表情面板)也不会被压住:动画结束那一刻按真实值落定,那一下
 * 不在任何插入动画里(当前值与动画目标相等),用弹簧滑过去,并更新记下的高度。键盘正在升降时
 * 不加弹簧:弹簧追不上,输入栏和键盘之间会漏出一条缝。
 *
 * 不用 `windowInsetsPadding`:它只能照原样跟。外面已经让过的部分由调用方在容器上挂
 * `onConsumedWindowInsetsChanged` 报进 [KeyboardFollowingInset.consumed]。
 *
 * 原先长在评论的弹出编辑面板里;面板换成常驻输入栏之后挪到这里,虚高照样会碰到。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberKeyboardFollowingInset(): KeyboardFollowingInset {
    val density = LocalDensity.current
    val state = remember { KeyboardFollowingInset() }
    val imeNow = WindowInsets.ime
    val imeTarget = WindowInsets.imeTarget
    val nav = WindowInsets.navigationBars
    val orientation = screenOrientationKey()
    LaunchedEffect(state, orientation) {
        snapshotFlow {
            val ime = imeNow.getBottom(density)
            val keyboardAnimating = ime != imeTarget.getBottom(density)
            if (!keyboardAnimating && ime > 0) SettledKeyboardHeight[orientation] = ime
            val cap = SettledKeyboardHeight[orientation]
            val capped = if (keyboardAnimating && cap != null) minOf(ime, cap) else ime
            // 并集再扣掉外面已经让过的,和 `union(...).exclude(...)` 同义,只是键盘那一份换成了
            // 压过顶的值。
            val px = (maxOf(capped, nav.getBottom(density)) - state.consumed.getBottom(density)).coerceAtLeast(0)
            px to keyboardAnimating
        }.collectLatest { (px, keyboardAnimating) ->
            val dp = with(density) { px.toDp() }
            // 第一次直接落位:刚出现时底下就该是导航栏的高度,不该从 0 滑上来。
            if (keyboardAnimating || !state.placed) {
                state.animatable.snapTo(dp)
                state.placed = true
            } else {
                state.animatable.animateTo(dp, spring(stiffness = Spring.StiffnessMediumLow))
            }
        }
    }
    return state
}

/**
 * 键盘上一次落定的高度(px),按横竖屏分开。进程内共享:虚高是从第二次弹键盘开始的,记在某一个
 * 输入栏身上的话,换一页就又是空的。
 */
private val SettledKeyboardHeight = mutableMapOf<Int, Int>()

internal class KeyboardFollowingInset {
    val animatable = Animatable(0.dp, Dp.VectorConverter)
    var placed = false
    var consumed: WindowInsets by mutableStateOf(WindowInsets(0))
    val value: Dp get() = animatable.value
}
