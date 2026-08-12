package dev.bilby.ui.fav

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Row
import dev.bilby.R
import dev.bilby.ui.theme.Spacing

/** 编辑对话框往回传的动作。挤成一个对象是因为它们只在这一个对话框里成套出现。 */
@Immutable
data class FavFolderEditorActions(
    val onTitleChange: (String) -> Unit,
    val onIntroChange: (String) -> Unit,
    val onPrivacyChange: (Boolean) -> Unit,
    val onSave: () -> Unit,
    val onDismiss: () -> Unit,
)

@Immutable
data class FavFolderDeletionActions(
    val onConfirm: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * 新建与编辑共用的对话框。
 *
 * 简介和公开性一起做,不是留到以后:两者都是接口的必传字段,不做也得替用户传一个值 ——
 * 而"默认公开"是替他做了一个他自己该做的决定。
 */
@Composable
fun FavFolderEditorDialog(state: FavFolderEditorState, actions: FavFolderEditorActions) {
    AlertDialog(
        onDismissRequest = actions.onDismiss,
        title = {
            Text(
                stringResource(
                    if (state.mediaId == null) R.string.fav_folder_create else R.string.fav_folder_edit,
                ),
            )
        },
        text = {
            if (state.loading) {
                CircularProgressIndicator()
                return@AlertDialog
            }
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Cozy)) {
                OutlinedTextField(
                    value = state.title,
                    onValueChange = actions.onTitleChange,
                    label = { Text(stringResource(R.string.fav_folder_title_label)) },
                    singleLine = true,
                    // 默认收藏夹的名称是固定的,改不了;它的公开性仍然能改,所以对话框照开。
                    enabled = !state.isDefaultFolder,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!state.isDefaultFolder) {
                    OutlinedTextField(
                        value = state.intro,
                        onValueChange = actions.onIntroChange,
                        label = { Text(stringResource(R.string.fav_folder_intro_label)) },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    text = stringResource(R.string.fav_folder_privacy_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrivacyOption(
                    label = stringResource(R.string.fav_folder_public),
                    selected = state.isPublic == true,
                    onSelect = { actions.onPrivacyChange(true) },
                )
                PrivacyOption(
                    label = stringResource(R.string.fav_folder_private),
                    selected = state.isPublic == false,
                    onSelect = { actions.onPrivacyChange(false) },
                )
                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSave, enabled = state.canSave) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 整行可选,不是给 [RadioButton] 单独挂 onClick:读屏要把这一行念成一个单选项。 */
@Composable
private fun PrivacyOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = Spacing.Hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * 删除收藏夹的确认。删除不可逆,所以拦在动作前面 —— 这一点和列表里的取消收藏正相反,
 * 那个走的是撤销(见 `FavFolderViewModel.remove`)。
 *
 * 确认按钮写"删除收藏夹"而不是"确定":两个按钮并排时,读到的那个词就是即将发生的事。
 */
@Composable
fun FavFolderDeleteDialog(state: FavFolderDeletion, actions: FavFolderDeletionActions) {
    AlertDialog(
        onDismissRequest = { if (!state.deleting) actions.onDismiss() },
        title = { Text(stringResource(R.string.fav_folder_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                Text(stringResource(R.string.fav_folder_delete_message, state.folder.title, state.folder.count))
                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onConfirm, enabled = !state.deleting) {
                Text(stringResource(R.string.fav_folder_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismiss, enabled = !state.deleting) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
