package dev.bilby.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/**
 * 排序控件:一排文字,透明底、无描边、无容器,可横滚。照 PiliPlus 的
 * `pages/search_panel/video/view.dart`(`SearchText`,`bgColor: Colors.transparent`)。
 *
 * 和 segmented button 的分工见风格指南 §2.1:**排序用这个**(低强调、选项数可变、可横滚),
 * segmented button 留给切换视图。选中态只换字重(`labelLargeEmphasized`)和颜色
 * (`primary`/`outline`),不放大字号 —— 放大会带着行高一起变,这一排就会跳。
 *
 * **纵向留白由这一排自己负责,调用方只给左右内边距。** 那 48dp 的触摸盒本身就是这一排的
 * 高度;外面再垫上下 padding,一行字就撑到 56dp 往上,在评论区那种一屏要放很多条的地方,
 * 这一条会读成一个独立的板块而不是列表的表头。
 *
 * @param options 值与其展示文案的 string 资源,按展示顺序传入。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> SortRow(
    options: List<Pair<T, Int>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    // **靠右**,不是靠左。排序是这一块内容的次要操作,不是它的标题 —— 摆在左上角会和小节
    // 标题抢"这块是什么"的位置,而右上角是各处约定俗成放页级/块级动作的地方。
    //
    // `fillMaxWidth` 要在 `horizontalScroll` 之前:先把行撑到父宽(否则行只有内容那么宽,
    // `Arrangement.End` 无处可推),再让内容在超宽时可横滚。
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        // spacedBy 带 alignment 参数:既保留项间距,又整体靠右。只写 Arrangement.End 会把间距丢掉。
        horizontalArrangement = Arrangement.spacedBy(Spacing.Comfortable, Alignment.End),
    ) {
        options.forEach { (value, labelRes) ->
            val isSelected = value == selected
            // 可点区域撑到 48dp 的触摸下限(风格指南 §3),但文字本身仍是 labelLarge 大小 ——
            // 视觉高度和触摸高度分开,跟 SeekBar 那条判据一样。
            Box(
                contentAlignment = Alignment.Center,
                // 整行可选:selectable + role 让读屏念出"单选按钮,已选中",而不是
                // 把文字和点击区当成两件无关的东西。
                modifier = Modifier
                    .heightIn(min = Dimens.MinTouchTarget)
                    .selectable(selected = isSelected, onClick = { onSelect(value) }, role = Role.RadioButton),
            ) {
                Text(
                    text = stringResource(labelRes),
                    style = if (isSelected) {
                        MaterialTheme.typography.labelLargeEmphasized
                    } else {
                        MaterialTheme.typography.labelLarge
                    },
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                )
            }
        }
    }
}

/**
 * 对话框里的单选行。整行是一个可选中的语义节点(读屏念「单选按钮,已选中,30 分钟后停止」),
 * 而不是给 `RadioButton` 单挂 onClick —— 后者会让读屏把控件和文字当成两件无关的东西,
 * 而且只有那个小圆点能点。
 *
 * 和 [SortRow] 的分工:那个是列表上方常驻的排序,低强调、可横滚;这个是对话框里要下决心的
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
