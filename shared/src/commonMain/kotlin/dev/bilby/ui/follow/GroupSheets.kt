package dev.bilby.ui.follow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.data.FollowGroup
import dev.bilby.data.SPECIAL_GROUP_ID
import dev.bilby.data.UpBrief
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.PaneSheet
import dev.bilby.ui.components.trackEditableFocus
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular
import org.jetbrains.compose.resources.StringResource

/**
 * 分组面板打开时的那一份状态。
 *
 * **[selected] 进来时必须是齐的**:写回是覆盖式的(见 `FollowRepository.replaceGroupsOf`),
 * 没显示出来的分组一律会被摘掉,所以 [loading] 期间不能让人按保存。
 *
 * [special] 就是 tagid 为 -10 的那个分组,和别的分组一样跟着这一份提交,不单独走接口。
 */
@Immutable
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
    val error: StringResource? = null,
)

/**
 * 给一个人设置分组。
 *
 * **进来先读齐他当前的全部分组**,读不到就不给保存 —— 接口是覆盖式的,拿一份不完整的名单
 * 写回去会把没显示出来的那些静默摘掉(notes/relation-groups.md 1.5)。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupPickerSheet(
    state: GroupPickerState,
    groups: List<FollowGroup>,
    onToggle: (Long) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.follow_group_picker_title, state.up.name),
        skipPartiallyExpanded = true,
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_cancel))
                }
                Button(
                    onClick = onSave,
                    enabled = !state.loading && !state.saving && state.error == null,
                ) {
                    Text(stringResource(Res.string.action_save))
                }
            }
        },
    ) {
        when {
            state.loading -> InlineProgress(
                stringResource(Res.string.follow_group_loading),
                Modifier.padding(horizontal = Spacing.Loose, vertical = Spacing.Comfortable),
            )

            state.error != null -> SheetMessage(stringResource(state.error))
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(horizontal = Spacing.Comfortable),
            ) {
                itemsIndexed(groups, key = { _, it -> it.id }) { index, group ->
                    val checked =
                        if (group.id == SPECIAL_GROUP_ID) state.special else group.id in state.selected
                    SegmentedListItem(
                        checked = checked,
                        onCheckedChange = { onToggle(group.id) },
                        enabled = !state.saving,
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = groups.size),
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
                    ) {
                        Text(
                            text = "${group.name}$MetaSeparator${group.count}",
                            style = LocalTextStyle.current.tabular,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 分组的新建、改名、删除。
 *
 * 「特别关注」和「默认分组」在这里只显示、不带动作([FollowGroup.isBuiltIn]):它们是接口
 * 固定给的两个,改名和删除都会被拒 —— 画一个点了必然失败的按钮比不画更糟。
 * 新建与改名均在面板内就地展开输入行,不弹窗叠弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun GroupManagerSheet(
    groups: List<FollowGroup>,
    error: StringResource?,
    onCreate: (String) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var draftName by remember { mutableStateOf("") }
    var renamingId by remember { mutableStateOf<Long?>(null) }
    var deleting by remember { mutableStateOf<FollowGroup?>(null) }

    PaneSheet(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.follow_groups_title),
        skipPartiallyExpanded = true,
        headerTrailing = {
            TextButton(
                onClick = {
                    renamingId = null
                    draftName = ""
                    creating = true
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconInline),
                )
                Spacer(Modifier.width(Spacing.Hair))
                Text(stringResource(Res.string.follow_group_create))
            }
        },
        footer = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.action_done))
                }
            }
        },
    ) {
        if (error != null) SheetMessage(stringResource(error))
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(horizontal = Spacing.Comfortable),
        ) {
            if (creating) {
                item(key = "inline-create-group") {
                    InlineGroupNameRow(
                        value = draftName,
                        onValueChange = { draftName = it },
                        onSave = {
                            val trimmed = draftName.trim()
                            if (trimmed.isNotEmpty()) {
                                creating = false
                                draftName = ""
                                onCreate(trimmed)
                            }
                        },
                        onCancel = {
                            creating = false
                            draftName = ""
                        },
                        modifier = Modifier.padding(bottom = Spacing.Tight),
                    )
                }
            }
            itemsIndexed(groups, key = { _, it -> it.id }) { index, group ->
                if (renamingId == group.id) {
                    InlineGroupNameRow(
                        value = draftName,
                        onValueChange = { draftName = it },
                        onSave = {
                            val trimmed = draftName.trim()
                            if (trimmed.isNotEmpty()) {
                                renamingId = null
                                draftName = ""
                                onRename(group.id, trimmed)
                            }
                        },
                        onCancel = {
                            renamingId = null
                            draftName = ""
                        },
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
                    )
                } else {
                    SegmentedListItem(
                        shapes = ListItemDefaults.segmentedShapes(index = index, count = groups.size),
                        colors = ListItemDefaults.segmentedColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
                        trailingContent = if (group.isBuiltIn) null else {
                            {
                                Row {
                                    val renameLabel = stringResource(Res.string.follow_group_rename_action, group.name)
                                    val deleteLabel = stringResource(Res.string.follow_group_delete_action, group.name)
                                    BilbyTooltip(text = renameLabel) {
                                        IconButton(
                                            onClick = {
                                                creating = false
                                                draftName = group.name
                                                renamingId = group.id
                                            },
                                        ) {
                                            Icon(
                                                Icons.Outlined.Edit,
                                                contentDescription = renameLabel,
                                            )
                                        }
                                    }
                                    BilbyTooltip(text = deleteLabel) {
                                        IconButton(onClick = { deleting = group }) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = deleteLabel,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            text = "${group.name}$MetaSeparator${group.count}",
                            style = LocalTextStyle.current.tabular,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }

    deleting?.let { group ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            // 只有标题,没有说明。删分组不动关注关系,组里的人退回默认分组。
            title = { Text(stringResource(Res.string.follow_group_delete_confirm_title, group.name)) },
            confirmButton = {
                Button(
                    onClick = {
                        deleting = null
                        onDelete(group.id)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text(stringResource(Res.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun InlineGroupNameRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(stringResource(Res.string.follow_group_name_hint)) },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .trackEditableFocus(),
            )
            val saveLabel = stringResource(Res.string.action_save)
            val cancelLabel = stringResource(Res.string.action_cancel)
            BilbyTooltip(text = saveLabel) {
                IconButton(onClick = onSave, enabled = value.isNotBlank()) {
                    Icon(Icons.Outlined.Check, contentDescription = saveLabel)
                }
            }
            BilbyTooltip(text = cancelLabel) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Close, contentDescription = cancelLabel)
                }
            }
        }
    }
}

@Composable
private fun SheetMessage(text: String) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = Spacing.Loose, vertical = Spacing.Tight),
        )
    }
}
