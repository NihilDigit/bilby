package dev.bilby.ui.follow

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.bilby.R
import dev.bilby.data.FollowGroup
import dev.bilby.data.SPECIAL_GROUP_ID
import dev.bilby.data.UpBrief
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.theme.Spacing

/**
 * 分组面板打开时的那一份状态。
 *
 * **[selected] 进来时必须是齐的**:写回是覆盖式的(见 `FollowRepository.replaceGroupsOf`),
 * 没显示出来的分组一律会被摘掉,所以 [loading] 期间不能让人按保存。
 *
 * [special] 就是 tagid 为 -10 的那个分组,和别的分组一样跟着这一份提交,不单独走接口。
 */
data class GroupPickerState(
    val up: UpBrief,
    val selected: Set<Long> = emptySet(),
    val special: Boolean = false,
    /**
     * 打开面板时读到的那一份,含 -10。**保存后靠它算人数增量** —— 谁进了哪几组、出了哪几组,
     * 客户端自己知道;写完立刻回头问服务端拿到的是写之前的数(实测:加进特别关注后人数不变,
     * 移出后反而 +1,重启才对)。
     */
    val original: Set<Long> = emptySet(),
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
)

/**
 * 给一个人设置分组。
 *
 * **进来先读齐他当前的全部分组**,读不到就不给保存 —— 接口是覆盖式的,拿一份不完整的名单
 * 写回去会把没显示出来的那些静默摘掉(notes/relation-groups.md 1.5)。所以 [state] 在
 * loading 和 error 两种情况下都不画那份清单。
 *
 * 「特别关注」在这份清单里就是普通一行(它本来就是 tagid -10),勾选与否由
 * [GroupPickerState.special] 承载 —— 写回时它比别的分组多一条路要走,见 `FollowRepository`。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupPickerSheet(
    state: GroupPickerState,
    groups: List<FollowGroup>,
    onToggle: (Long) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.follow_group_picker_title, state.up.name))
        when {
            state.loading -> InlineProgress(
                stringResource(R.string.follow_group_loading),
                Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            )

            state.error != null -> SheetMessage(state.error)
            else -> LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(groups, key = { it.id }) { group ->
                    val checked =
                        if (group.id == SPECIAL_GROUP_ID) state.special else group.id in state.selected
                    ListItem(
                        headlineContent = {
                            Text(
                                "${group.name}$MetaSeparator${group.count}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        // 整行可选,不给 Checkbox 单挂 onClick:读屏要把这一行念成一个可选中的
                        // 节点,而不是一段文字加一个孤立的控件(风格指南 §3)。
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth().toggleable(
                            value = checked,
                            enabled = !state.saving,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(group.id) },
                        ),
                    )
                }
            }
        }
        SheetActions(
            confirmText = stringResource(R.string.action_save),
            confirmEnabled = !state.loading && !state.saving && state.error == null,
            onConfirm = onSave,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 分组的新建、改名、删除。
 *
 * 「特别关注」和「默认分组」在这里只显示、不带动作([FollowGroup.isBuiltIn]):它们是接口
 * 固定给的两个,改名和删除都会被拒 —— 画一个点了必然失败的按钮比不画更糟。
 *
 * @param error 上一次写操作的失败原因。写在面板里而不是弹一个提示:这一页没有别的地方能说
 *   这件事,而失败之后用户多半要就着同一个名字再试一次。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupManagerSheet(
    groups: List<FollowGroup>,
    error: String?,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<FollowGroup?>(null) }
    var deleting by remember { mutableStateOf<FollowGroup?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.follow_groups_title))
        if (error != null) SheetMessage(error)
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            items(groups, key = { it.id }) { group ->
                ListItem(
                    headlineContent = {
                        Text(
                            "${group.name}$MetaSeparator${group.count}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingContent = if (group.isBuiltIn) null else {
                        {
                            Row {
                                IconButton(onClick = { renaming = group }) {
                                    Icon(
                                        Icons.Outlined.Edit,
                                        contentDescription = stringResource(
                                            R.string.follow_group_rename_action,
                                            group.name,
                                        ),
                                    )
                                }
                                IconButton(onClick = { deleting = group }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(
                                            R.string.follow_group_delete_action,
                                            group.name,
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            item(key = "create") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.follow_group_create)) },
                    leadingContent = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { creating = true },
                )
            }
        }
        SheetActions(
            confirmText = stringResource(R.string.action_done),
            confirmEnabled = true,
            onConfirm = onDismiss,
            onDismiss = null,
        )
    }

    if (creating) {
        GroupNameDialog(
            title = stringResource(R.string.follow_group_create),
            initial = "",
            onConfirm = {
                creating = false
                onCreate(it)
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { group ->
        GroupNameDialog(
            title = stringResource(R.string.follow_group_rename),
            initial = group.name,
            onConfirm = {
                renaming = null
                onRename(group.id, it)
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { group ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            // 只有标题,没有说明。删分组不动关注关系,组里的人退回默认分组 —— 那是这个动作的
            // 常识含义,写出来等于在解释什么叫分组(拉黑那个框是另一回事,见 BlockConfirmDialog)。
            title = { Text(stringResource(R.string.follow_group_delete_confirm_title, group.name)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    onDelete(group.id)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 新建和改名共用一个输入框:两者的区别只有标题和初值。 */
@Composable
private fun GroupNameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.follow_group_name_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    )
}

@Composable
private fun SheetMessage(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
    )
}

/**
 * 面板底部那一排按钮。**自己补 navigationBars padding** —— sheet 铺到屏幕底边,不补的话
 * 按钮会压在手势条上。
 */
@Composable
private fun SheetActions(
    confirmText: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.End,
    ) {
        if (onDismiss != null) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
        TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmText) }
    }
}
