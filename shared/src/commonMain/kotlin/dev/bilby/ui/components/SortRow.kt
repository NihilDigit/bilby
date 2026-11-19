package dev.bilby.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.bilby.stringResource
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import org.jetbrains.compose.resources.StringResource

/**
 * 列表上方的排序:整行只有靠右的一颗轻量化 [SortMenu] 文字下拉。评论区、搜索、收藏夹、关注列表共用同一风格。
 *
 * **靠右**,不是靠左。排序是这一块内容的次要操作,不是它的标题 —— 摆在左上角会和小节
 * 标题抢"这块是什么"的位置,而右上角是各处约定俗成放页级/块级动作的地方。
 *
 * 左边还要放别的东西时(空间页投稿表头左边是「听投稿」),直接用 [SortMenu] 自己排。
 *
 * @param options 值与其展示文案的 string 资源,按展示顺序传入。
 */
@Composable
fun <T> SortRow(
    options: List<Pair<T, StringResource>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SortMenu(
            options = options,
            selected = selected,
            onSelect = onSelect,
        )
    }
}

/**
 * 排序选择器:全站统一采用轻量化透明底 `TextButton` + `ArrowDropDown` 下拉菜单(同评论区 `SortBar`)。
 */
@Composable
fun <T> SortMenu(
    options: List<Pair<T, StringResource>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") preferButtonGroup: Boolean = false,
) {
    var open by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: return
    Box(modifier = modifier) {
        TextButton(
            onClick = { open = true },
            contentPadding = PaddingValues(start = Spacing.Cozy, end = Spacing.Tight),
            modifier = Modifier.defaultMinSize(minWidth = 1.dp),
        ) {
            Text(
                text = stringResource(selectedLabel),
                maxLines = 1,
                softWrap = false,
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
        }
        DesktopMenuMotionTheme {
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                shape = MenuDefaults.shape,
                containerColor = MenuDefaults.containerColor,
            ) {
                options.forEachIndexed { index, (value, label) ->
                    val isSelected = value == selected
                    DropdownMenuItem(
                        onClick = {
                            open = false
                            onSelect(value)
                        },
                        text = { Text(stringResource(label)) },
                        shape = menuItemShape(index, options.size),
                        trailingContent = if (isSelected) menuSelectedMark else null,
                        modifier = Modifier.selectedSemantics(isSelected),
                    )
                }
            }
        }
    }
}

/**
 * 对话框里的单选行。整行是一个可选中的语义节点(读屏念「单选按钮,已选中,30 分钟后停止」),
 * 而不是给 `RadioButton` 单挂 onClick —— 后者会让读屏把控件和文字当成两件无关的东西,
 * 而且只有那个小圆点能点。
 *
 * 和 [SortRow] 的分工:那个是列表上方常驻的排序,收在一颗下拉里;这个是对话框里要下决心的
 * 二选一或三选一,每项各占一行、有明确的选中标记。
 */
@Composable
fun ChoiceRow(selected: Boolean, onSelect: () -> Unit, label: String, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Dimens.MinTouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = Spacing.Tight))
    }
}
