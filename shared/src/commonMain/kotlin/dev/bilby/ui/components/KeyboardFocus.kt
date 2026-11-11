package dev.bilby.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import dev.bilby.ui.imeTarget

/**
 * 追踪整棵界面里是否有任意可编辑文本输入框正处于聚焦态。
 *
 * 桌面端的单键与组合快捷键(空格播放、`F` 全屏、`M` 静音、`D` 弹幕、`/` 搜索、`Alt+←` 返回、全局 `Ctrl+V` 跳链)
 * 都先检查 [isEditableFocused],防止打字、编辑评论或填写地址时按键被外层快捷键吃掉。
 */
class EditableFocusTracker {
    private var focusedCount by mutableIntStateOf(0)
    val isEditableFocused: Boolean get() = focusedCount > 0

    internal fun onEnter() {
        focusedCount++
    }

    internal fun onExit() {
        focusedCount = (focusedCount - 1).coerceAtLeast(0)
    }
}

val LocalEditableFocusTracker = compositionLocalOf { EditableFocusTracker() }

/** 挂在 `BasicTextField` 或 `OutlinedTextField` 上,在聚焦期间登记到 [LocalEditableFocusTracker]。 */
fun Modifier.trackEditableFocus(): Modifier = composed {
    val tracker = LocalEditableFocusTracker.current
    var counted by remember { mutableStateOf(false) }
    DisposableEffect(tracker) {
        onDispose {
            if (counted) {
                counted = false
                tracker.onExit()
            }
        }
    }
    onFocusChanged { state ->
        val now = state.isFocused
        if (now && !counted) {
            counted = true
            tracker.onEnter()
        } else if (!now && counted) {
            counted = false
            tracker.onExit()
        }
    }
}

/**
 * 键盘开始收起就放掉焦点。输入法开着时返回键先被它拿去收键盘,光标却留在框里,框也一直是聚焦
 * 的底色;页面上多半没有别的能接住焦点的东西,人点哪里都收不掉它。
 *
 * 看的是键盘的动画目标,收起一开始它就是 0(`isImeVisible` 要等动画播完)。只在这一次聚焦期间
 * 键盘确实弹出过才认:刚聚焦那一帧目标还是 0,硬件键盘和桌面上它始终是 0。评论面板与弹幕胶囊
 * 各自按同一个判据退出输入。
 */
@Composable
fun ReleaseFocusWhenKeyboardHides(focused: Boolean) {
    val focusManager = LocalFocusManager.current
    val imeUp = WindowInsets.imeTarget.getBottom(LocalDensity.current) > 0
    var imeWasUp by remember { mutableStateOf(false) }
    LaunchedEffect(focused, imeUp) {
        when {
            !focused -> imeWasUp = false
            imeUp -> imeWasUp = true
            imeWasUp -> {
                imeWasUp = false
                focusManager.clearFocus()
            }
        }
    }
}
