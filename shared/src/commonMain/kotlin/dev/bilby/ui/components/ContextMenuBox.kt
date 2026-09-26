package dev.bilby.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MenuDefaults
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
 * 右键或长按在按下的位置弹出 [menu]。给宽屏网格里的行用,替掉行尾那颗 ⋮:每格一颗时整屏是
 * 一列列竖排的点,而宽屏上右键本来就是弹菜单的通用手势,不需要一个看得见的入口去提示它。
 * 窄屏仍画 ⋮,那里没有鼠标。
 *
 * 位置在 Initial 阶段记下,不消费主键:点击还要收到这一次按下,长按回调本身又不带坐标。
 * 右键在同一阶段消费掉,免得点击把它当成一次点击。
 *
 * @param onClick 由这一层接管点击与长按。null 时这一层不接点击,[content] 拿到 openMenu 挂到
 *   自己的长按上:视频行自带 combinedClickable,外面再套一层的话长按先被里面那层吃掉。
 * @param menuLabel 读屏念的长按动作名。无障碍服务里长按是一个具名动作,菜单靠它才找得到。
 *   只在这一层接管点击时生效。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContextMenuBox(
    onClick: (() -> Unit)?,
    menuLabel: String,
    modifier: Modifier = Modifier,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
    content: @Composable (openMenu: () -> Unit) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val lastPress = remember { PressPosition() }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val openMenu = {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        menuAt = lastPress.value
    }
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
            .then(
                if (onClick != null) {
                    Modifier.combinedClickable(
                        role = Role.Button,
                        onClick = onClick,
                        onLongClickLabel = menuLabel,
                        onLongClick = openMenu,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        content(openMenu)
        menuAt?.let { at ->
            // 零尺寸的锚点放在按下的位置,DropdownMenu 贴着它展开。外形同 ⋮ 弹出的那份
            // (M3E vertical menu),两个入口弹的是同一个菜单。
            Box(modifier = Modifier.offset { at.round() }) {
                DropdownMenu(
                    expanded = true,
                    onDismissRequest = { menuAt = null },
                    shape = MenuDefaults.shape,
                    containerColor = MenuDefaults.containerColor,
                ) {
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
