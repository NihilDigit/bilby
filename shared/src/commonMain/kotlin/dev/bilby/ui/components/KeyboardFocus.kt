package dev.bilby.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import dev.bilby.ui.imeTarget

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
