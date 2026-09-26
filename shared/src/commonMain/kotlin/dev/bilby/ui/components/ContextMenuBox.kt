package dev.bilby.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.round

/**
 * 点击走 [onClick],右键或长按在按下的位置弹出 [menu]。给网格里的行用:每格挂一颗 ⋮
 * 时,整屏是一列列竖排的点。
 *
 * 只在菜单里的动作另有入口时用它。右键和长按都没有视觉提示,不能是唯一的路。
 *
 * 位置在 Initial 阶段记下,不消费主键:clickable 还要收到这一次按下,长按回调本身又不带坐标。
 * 右键在同一阶段消费掉,免得 clickable 把它当成一次点击。
 *
 * @param menuLabel 读屏念的长按动作名。无障碍服务里长按是一个具名动作,菜单靠它才找得到。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContextMenuBox(
    onClick: () -> Unit,
    menuLabel: String,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val lastPress = remember { PressPosition() }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        val position = event.changes.first().position
                        lastPress.value = position
                        if (event.buttons.isSecondaryPressed) {
                            event.changes.forEach { it.consume() }
                            menuAt = position
                        }
                    }
                }
            }
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClickLabel = menuLabel,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuAt = lastPress.value
                },
            ),
    ) {
        content()
        menuAt?.let { at ->
            // 零尺寸的锚点放在按下的位置,DropdownMenu 贴着它展开。
            Box(modifier = Modifier.offset { at.round() }) {
                DropdownMenu(expanded = true, onDismissRequest = { menuAt = null }) {
                    menu { menuAt = null }
                }
            }
        }
    }
}

/** 只给手势回调读写,不参与组合,所以不用 State。 */
private class PressPosition {
    var value: Offset = Offset.Zero
}
