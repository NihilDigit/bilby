package dev.bilby.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 拦截返回。Android 上是 activity-compose 的同名函数,行为与迁移前一致;
 * 桌面上接 Compose Multiplatform 的返回事件(Esc 键)。
 */
@Composable
expect fun BackHandler(enabled: Boolean = true, onBack: () -> Unit)

/**
 * 向当前导航事件分发链发起一次完整返回(依次经过最内层启用的 [BackHandler],最后落到 `NavDisplay`),
 * 供桌面端鼠标侧键返回与 `Alt+←` 使用。
 */
@Composable
expect fun rememberBackTrigger(): () -> Unit

/** 拦截鼠标侧键返回(`XButton1` / `PointerButton.Back`)并触发 [onBack]。 */
expect fun Modifier.mouseBackNavigation(onBack: () -> Unit): Modifier
