package dev.bilby.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.bilby.R
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing

/** 输入框高度。比 `OutlinedTextField` 的 56dp 矮一档,又高于 48dp 的触摸下限。 */
private val FieldHeight = 48.dp

/**
 * 搜索输入框。
 *
 * **不用 `OutlinedTextField`**,理由是具体的三条,不是"看着更好":
 *
 * - **56dp + 一圈描边**。它是给表单里一列字段用的,一屏一个的搜索框顶着这个高度,
 *   加上描边,视觉重量比它旁边的内容还大。PiliPlus 的搜索框(`pages/search/view.dart`)
 *   直接是 `border: InputBorder.none` 的裸 TextField 挂在顶栏上。这里折中成
 *   "填充 + 大圆角、无描边":Bilby 的输入框不在顶栏,需要一个能被认出来的容器。
 * - **回车不搜索**。`singleLine = true` 只是不换行,不设 `ImeAction`,键盘上那个键什么
 *   都不做 —— 而 DESIGN 2.2 的快路原话就是"输入直接回车 = 原始 B 站搜索"。
 * - **有清空**。改一次搜索词可以直接点尾部的清除按钮,不必按十几下退格。
 *
 * **不用 material3 1.5.0 转正的 slot-based `SearchBar`/`SearchBarState`**:它自带
 * "展开成全屏建议列表"这套形态,而 Bilby 的搜索是对话式的(输入在下、一轮轮结果在上),
 * 用它就等于把这个 app 定死的形态换掉。上一轮的判断在这一轮重新核过,结论不变。
 *
 * @param onSearch 键盘上的搜索键。空输入时不回调 —— 否则会打出一次空查询。
 * @param focusRequester 传入时挂在内层 `BasicTextField` 上,配合调用方的
 *   `LaunchedEffect(...) { focusRequester.requestFocus() }` 做到"点开即弹键盘"
 *   (空间页的搜索图标按钮用到了这个)。
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    /** 输入框拿到/失去焦点。搜索页靠它决定要不要把历史顶上来。 */
    onFocusChange: (Boolean) -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = FieldHeight)
            // 层次靠 surfaceContainer 的色阶差,不靠描边和阴影(风格指南 §1.1)。
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.largeIncreased,
            )
            .padding(start = Spacing.Cozy, end = Spacing.Hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(Dimens.IconInline),
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && placeholder.isNotEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(
                    MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (value.isNotBlank()) {
                            keyboard?.hide()
                            onSearch()
                        }
                    },
                ),
                modifier = Modifier.fillMaxWidth()
                    .onFocusChanged { onFocusChange(it.isFocused) }
                    .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
            )
        }
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.action_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.IconInline),
                )
            }
        }
        trailing?.invoke(this)
    }
}
