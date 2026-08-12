package dev.bilby.ui.fav

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.FavFolderDetail
import dev.bilby.data.FavRepository
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.FullScreenError
import dev.bilby.ui.components.FullScreenLoading
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavFoldersUiState(
    val folders: List<FavFolderDetail> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** 非空时新建/编辑对话框开着,两者是同一个对话框,见 [FavFolderEditorState.mediaId]。 */
    val editor: FavFolderEditorState? = null,
    val deletion: FavFolderDeletion? = null,
)

/**
 * @param mediaId null 表示新建。新建与编辑在接口上是同一个形状(只差 endpoint 和这个字段),
 *   在界面上也就没有理由长成两个对话框。
 * @param isPublic **新建时是 null,不是 true**:公开性是接口必传字段,但替用户默认成公开是
 *   替他做了一个他自己该做的决定。没选之前保存按钮不可用。
 * @param isDefaultFolder 默认收藏夹的名称和简介都改不了,只有公开性能改。
 * @param loading 编辑时先取一次 folder/info(理由见 `FavRepository.folderInfo`),这期间字段还没到。
 */
data class FavFolderEditorState(
    val mediaId: Long? = null,
    val title: String = "",
    val intro: String = "",
    val isPublic: Boolean? = null,
    val isDefaultFolder: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val canSave: Boolean get() = title.isNotBlank() && isPublic != null && !loading && !saving
}

data class FavFolderDeletion(
    val folder: FavFolderDetail,
    val deleting: Boolean = false,
    val error: String? = null,
)

class FavFoldersViewModel(private val repository: FavRepository) : ViewModel() {

    private val _state = MutableStateFlow(FavFoldersUiState())
    val state: StateFlow<FavFoldersUiState> = _state.asStateFlow()

    init {
        load(refreshing = false)
    }

    fun refresh() = load(refreshing = true)

    fun retry() = load(refreshing = false)

    private fun load(refreshing: Boolean) {
        _state.update { it.copy(loading = !refreshing, refreshing = refreshing, error = null) }
        viewModelScope.launch {
            when (val result = repository.folderDetails()) {
                is BiliResult.Ok -> _state.update {
                    it.copy(loading = false, refreshing = false, folders = result.value)
                }

                else -> {
                    val reason = result.reason()
                    BiliLog.w("取收藏夹列表失败: $reason")
                    _state.update { it.copy(loading = false, refreshing = false, error = reason) }
                }
            }
        }
    }

    fun startCreate() {
        _state.update { it.copy(editor = FavFolderEditorState()) }
    }

    fun startEdit(folder: FavFolderDetail) {
        _state.update {
            it.copy(
                editor = FavFolderEditorState(
                    mediaId = folder.id,
                    title = folder.title,
                    isPublic = folder.isPublic,
                    isDefaultFolder = folder.isDefault,
                    loading = true,
                ),
            )
        }
        viewModelScope.launch {
            when (val result = repository.folderInfo(folder.id)) {
                is BiliResult.Ok -> updateEditor(folder.id) {
                    it.copy(
                        title = result.value.title,
                        intro = result.value.intro,
                        isPublic = result.value.isPublic,
                        isDefaultFolder = result.value.isDefault,
                        loading = false,
                    )
                }

                else -> {
                    val reason = result.reason()
                    BiliLog.w("取收藏夹信息失败(media_id=${folder.id}): $reason")
                    updateEditor(folder.id) { it.copy(loading = false, error = reason) }
                }
            }
        }
    }

    fun changeTitle(value: String) = updateEditor { it.copy(title = value.take(TITLE_MAX_LENGTH)) }

    fun changeIntro(value: String) = updateEditor { it.copy(intro = value.take(INTRO_MAX_LENGTH)) }

    fun changePrivacy(isPublic: Boolean) = updateEditor { it.copy(isPublic = isPublic) }

    fun dismissEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun save() {
        val editor = _state.value.editor ?: return
        val isPublic = editor.isPublic ?: return
        if (!editor.canSave) return
        updateEditor { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val title = editor.title.trim()
            val result = if (editor.mediaId == null) {
                repository.createFolder(title, editor.intro, isPublic)
            } else {
                repository.editFolder(editor.mediaId, title, editor.intro, isPublic)
            }
            if (result is BiliResult.Ok) {
                _state.update { it.copy(editor = null) }
                // 这里重拉一次不违反"乐观更新不重新拉取":那条说的是点赞/投币/收藏的计数,
                // 本地算得出来才不该再问一遍。新建拿不到新收藏夹的 id,标题也可能被服务端
                // 规整过,列表只能重来。
                load(refreshing = true)
            } else {
                val reason = result.reason()
                BiliLog.w("保存收藏夹失败(media_id=${editor.mediaId}): $reason")
                updateEditor { it.copy(saving = false, error = reason) }
            }
        }
    }

    fun startDelete(folder: FavFolderDetail) {
        _state.update { it.copy(deletion = FavFolderDeletion(folder)) }
    }

    fun dismissDelete() {
        _state.update { it.copy(deletion = null) }
    }

    /** 失败时对话框留着并把原因写在里面:关掉再弹一句提示,用户得重新走一遍才能再试。 */
    fun confirmDelete() {
        val deletion = _state.value.deletion ?: return
        if (deletion.deleting) return
        _state.update { it.copy(deletion = deletion.copy(deleting = true, error = null)) }
        viewModelScope.launch {
            val result = repository.deleteFolders(listOf(deletion.folder.id))
            if (result is BiliResult.Ok) {
                _state.update {
                    it.copy(deletion = null, folders = it.folders - deletion.folder)
                }
            } else {
                val reason = result.reason()
                BiliLog.w("删除收藏夹失败(media_id=${deletion.folder.id}): $reason")
                _state.update {
                    it.copy(deletion = it.deletion?.copy(deleting = false, error = reason))
                }
            }
        }
    }

    private fun updateEditor(transform: (FavFolderEditorState) -> FavFolderEditorState) {
        _state.update { it.copy(editor = it.editor?.let(transform)) }
    }

    /**
     * folder/info 回来时对话框可能已经关掉、或者用户已经换去编辑另一个收藏夹,
     * 那两种情况下这份结果都是过期的,不能往当前对话框上盖。
     */
    private fun updateEditor(mediaId: Long, transform: (FavFolderEditorState) -> FavFolderEditorState) {
        _state.update { state ->
            val editor = state.editor
            if (editor == null || editor.mediaId != mediaId) state else state.copy(editor = transform(editor))
        }
    }

    private companion object {
        /** 官方端的输入上限,照抄 PiliPlus 的 fav_create 页。超出部分直接打不进去。 */
        const val TITLE_MAX_LENGTH = 20
        const val INTRO_MAX_LENGTH = 200
    }
}

/**
 * 收藏夹列表。管理动作都在这一页:新建在右下角,改名/简介/公开性和删除在每一行的菜单里。
 *
 * 点一行进的是这个收藏夹的内容(`FavFolderScreen`),取消收藏在那一页做 —— 那里才看得见
 * 是哪一条。
 */
@Composable
fun FavFoldersScreen(
    state: FavFoldersUiState,
    onOpenFolder: (FavFolderDetail) -> Unit,
    onCreate: () -> Unit,
    onEdit: (FavFolderDetail) -> Unit,
    onDelete: (FavFolderDetail) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    editorActions: FavFolderEditorActions,
    deletionActions: FavFolderDeletionActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val direction = LocalLayoutDirection.current
    // 列表底部要让开右下角那颗 FAB,否则最后一行被压住,而列表滚到底就再也让不开了。
    val listPadding = PaddingValues(
        start = contentPadding.calculateStartPadding(direction),
        end = contentPadding.calculateEndPadding(direction),
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding() + FabClearance,
    )

    Box(modifier = modifier.fillMaxSize()) {
        AdaptiveContent {
            when {
                state.loading && state.folders.isEmpty() -> FullScreenLoading()
                state.error != null && state.folders.isEmpty() -> FullScreenError(state.error, onRetry)
                else -> PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = listPadding) {
                        if (state.folders.isEmpty()) {
                            item(key = "empty") {
                                EmptyState(
                                    stringResource(R.string.fav_folders_empty),
                                    modifier = Modifier.fillParentMaxSize(),
                                )
                            }
                        }
                        items(state.folders, key = { it.id }) { folder ->
                            FavFolderRow(
                                folder = folder,
                                onClick = { onOpenFolder(folder) },
                                onEdit = { onEdit(folder) },
                                onDelete = { onDelete(folder) },
                            )
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = onCreate,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.Comfortable)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.fav_folder_create))
        }
    }

    state.editor?.let { FavFolderEditorDialog(it, editorActions) }
    state.deletion?.let { FavFolderDeleteDialog(it, deletionActions) }
}

/** FAB 的规格是 56dp,加上它上下两侧的外边距就是列表要让开的高度。 */
private val FabClearance = 56.dp + Spacing.Comfortable * 2

@Composable
private fun FavFolderRow(
    folder: FavFolderDetail,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val visibility = stringResource(
        if (folder.isPublic) R.string.fav_folder_public else R.string.fav_folder_private,
    )
    ListItem(
        headlineContent = {
            Text(
                folder.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.fav_folder_count, folder.count) + MetaSeparator + visibility,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            Icon(Icons.Outlined.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            FavFolderMenu(
                folderTitle = folder.title,
                // 默认收藏夹删不掉,所以这里根本不给删除项 —— 让它可点再报错,等于把
                // 一条服务端规则做成了一次失败。
                deletable = !folder.isDefault,
                onEdit = onEdit,
                onDelete = onDelete,
            )
        },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Composable
private fun FavFolderMenu(
    folderTitle: String,
    deletable: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.fav_folder_actions, folderTitle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.fav_folder_edit)) },
                onClick = {
                    expanded = false
                    onEdit()
                },
            )
            if (deletable) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}
