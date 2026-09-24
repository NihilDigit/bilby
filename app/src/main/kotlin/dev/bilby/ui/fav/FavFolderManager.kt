package dev.bilby.ui.fav

import androidx.annotation.StringRes
import dev.bilby.api.BiliResult
import dev.bilby.data.FavFolderDetail
import dev.bilby.data.FavRepository
import dev.bilby.ui.errorTextRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    @StringRes val error: Int? = null,
) {
    val canSave: Boolean get() = title.isNotBlank() && isPublic != null && !loading && !saving
}

data class FavFolderDeletion(
    val folder: FavFolderDetail,
    val deleting: Boolean = false,
    @StringRes val error: Int? = null,
)

/**
 * 新建、编辑、删除收藏夹。收藏夹列表页与收藏夹内容页各持一份,两页的对话框因此是同一套。
 *
 * **抽成一个类而不是两个 ViewModel 各写一遍**:编辑前先取 folder/info、结果过期要丢、失败
 * 留在对话框里 —— 这几条判断抄两份,改一处漏一处的时候两页的对话框就不是一个行为了。
 *
 * @param onSaved 保存成功。参数是被编辑的那个收藏夹,新建时是 null(接口不回新建的 id)。
 * @param onDeleted 删除成功。
 */
class FavFolderManager(
    private val repository: FavRepository,
    private val scope: CoroutineScope,
    private val onSaved: (mediaId: Long?) -> Unit,
    private val onDeleted: (FavFolderDetail) -> Unit,
) {

    private val _editor = MutableStateFlow<FavFolderEditorState?>(null)

    /** 非空时新建/编辑对话框开着,两者是同一个对话框,见 [FavFolderEditorState.mediaId]。 */
    val editor: StateFlow<FavFolderEditorState?> = _editor.asStateFlow()

    private val _deletion = MutableStateFlow<FavFolderDeletion?>(null)
    val deletion: StateFlow<FavFolderDeletion?> = _deletion.asStateFlow()

    val editorActions = FavFolderEditorActions(
        onTitleChange = { value -> updateEditor { it.copy(title = value.take(TITLE_MAX_LENGTH)) } },
        onIntroChange = { value -> updateEditor { it.copy(intro = value.take(INTRO_MAX_LENGTH)) } },
        onPrivacyChange = { isPublic -> updateEditor { it.copy(isPublic = isPublic) } },
        onSave = ::save,
        onDismiss = { _editor.value = null },
    )

    val deletionActions = FavFolderDeletionActions(
        onConfirm = ::confirmDelete,
        onDismiss = { _deletion.value = null },
    )

    fun startCreate() {
        _editor.value = FavFolderEditorState()
    }

    fun startEdit(folder: FavFolderDetail) {
        _editor.value = FavFolderEditorState(
            mediaId = folder.id,
            title = folder.title,
            isPublic = folder.isPublic,
            isDefaultFolder = folder.isDefault,
            loading = true,
        )
        scope.launch {
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
                    val reason = result.errorTextRes("取收藏夹信息(media_id=${folder.id})")
                    updateEditor(folder.id) { it.copy(loading = false, error = reason) }
                }
            }
        }
    }

    private fun save() {
        val editor = _editor.value ?: return
        val isPublic = editor.isPublic ?: return
        if (!editor.canSave) return
        updateEditor { it.copy(saving = true, error = null) }
        scope.launch {
            val title = editor.title.trim()
            val result = if (editor.mediaId == null) {
                repository.createFolder(title, editor.intro, isPublic)
            } else {
                repository.editFolder(editor.mediaId, title, editor.intro, isPublic)
            }
            if (result is BiliResult.Ok) {
                _editor.value = null
                onSaved(editor.mediaId)
            } else {
                val reason = result.errorTextRes("保存收藏夹(media_id=${editor.mediaId})")
                updateEditor { it.copy(saving = false, error = reason) }
            }
        }
    }

    fun startDelete(folder: FavFolderDetail) {
        _deletion.value = FavFolderDeletion(folder)
    }

    /** 失败时对话框留着并把原因写在里面:关掉再弹一句提示,用户得重新走一遍才能再试。 */
    private fun confirmDelete() {
        val deletion = _deletion.value ?: return
        if (deletion.deleting) return
        _deletion.value = deletion.copy(deleting = true, error = null)
        scope.launch {
            val result = repository.deleteFolders(listOf(deletion.folder.id))
            if (result is BiliResult.Ok) {
                _deletion.value = null
                onDeleted(deletion.folder)
            } else {
                val reason = result.errorTextRes("删除收藏夹(media_id=${deletion.folder.id})")
                _deletion.update { it?.copy(deleting = false, error = reason) }
            }
        }
    }

    private fun updateEditor(transform: (FavFolderEditorState) -> FavFolderEditorState) {
        _editor.update { it?.let(transform) }
    }

    /**
     * folder/info 回来时对话框可能已经关掉、或者用户已经换去编辑另一个收藏夹,
     * 那两种情况下这份结果都是过期的,不能往当前对话框上盖。
     */
    private fun updateEditor(mediaId: Long, transform: (FavFolderEditorState) -> FavFolderEditorState) {
        _editor.update { editor -> if (editor == null || editor.mediaId != mediaId) editor else transform(editor) }
    }

    private companion object {
        /** 官方端的输入上限,照抄 PiliPlus 的 fav_create 页。超出部分直接打不进去。 */
        const val TITLE_MAX_LENGTH = 20
        const val INTRO_MAX_LENGTH = 200
    }
}
