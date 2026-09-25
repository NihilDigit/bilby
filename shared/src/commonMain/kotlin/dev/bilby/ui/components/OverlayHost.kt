package dev.bilby.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 整个窗口最上面的一层,页面深处的组件可以把一块内容挂上去。
 *
 * 为的是写评论那张面板(见 [ComposerPanel]):它的遮罩要铺满整个窗口,点画面、点标签行、点弹幕
 * 胶囊都算"不写了"。而评论区只是播放页下半截 tab 里的一块,画在它自己身上的遮罩只盖得住它自己,
 * 点在它外面的那一下落到了画面和按钮上。
 *
 * **不用 `Popup`/`Dialog`**:那是另开一个窗口,而这层面板要和键盘同一段动作 —— 另开窗口正是
 * `ModalBottomSheet` 那一版对不齐的原因。这里仍在同一个窗口、同一棵组合树里,只是画在最后。
 *
 * 同一时刻只有一块内容;后挂上的顶掉先挂上的。
 */
class OverlayHostState internal constructor() {
    internal var content: (@Composable () -> Unit)? by mutableStateOf(null)
}

val LocalOverlayHost = staticCompositionLocalOf<OverlayHostState?> { null }

/** 提供宿主,并在 [content] 之上画出挂上来的那一块。放在整棵界面树的最外层。 */
@Composable
fun OverlayHost(content: @Composable () -> Unit) {
    val state = remember { OverlayHostState() }
    CompositionLocalProvider(LocalOverlayHost provides state) {
        content()
        state.content?.invoke()
    }
}

/**
 * 把 [content] 挂到窗口最上面,离开组合时摘下。没有宿主时(预览、没套宿主的调用方)就地画。
 *
 * [content] 在宿主那个位置组合,读到的是它那里的 CompositionLocal;要用到本地状态的,
 * 通过闭包带过去。
 */
@Composable
fun WindowOverlay(content: @Composable () -> Unit) {
    val host = LocalOverlayHost.current
    if (host == null) {
        content()
        return
    }
    val latest by rememberUpdatedState(content)
    DisposableEffect(host) {
        val slot: @Composable () -> Unit = { latest() }
        host.content = slot
        onDispose { if (host.content === slot) host.content = null }
    }
}
