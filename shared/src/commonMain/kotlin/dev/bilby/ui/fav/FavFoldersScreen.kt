package dev.bilby.ui.fav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.bilby.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.resources.*
import org.jetbrains.compose.resources.StringResource
import dev.bilby.api.BiliResult
import dev.bilby.data.FavFolderDetail
import dev.bilby.data.FavRepository
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.errorTextRes
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavFoldersUiState(
    val folders: List<FavFolderDetail> = emptyList(),
    val loading: Boolean = true,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    /** 存资源 id,见 [dev.bilby.ui.errorTextRes]。 */
    val error: StringResource? = null,
)

/**
 * 收藏夹列表。按页取 created/list(带封面,list-all 不带,见 notes/fav.md §1)。
 *
 * 新建、编辑、删除交给 [manager],内容页用的是同一个类。
 */
class FavFoldersViewModel(private val repository: FavRepository) : ViewModel() {

    private val _state = MutableStateFlow(FavFoldersUiState())
    val state: StateFlow<FavFoldersUiState> = _state.asStateFlow()

    val manager = FavFolderManager(
        repository = repository,
        scope = viewModelScope,
        // 这里重拉一次不违反"乐观更新不重新拉取":那条说的是点赞/投币/收藏的计数,
        // 本地算得出来才不该再问一遍。新建拿不到新收藏夹的 id,标题也可能被服务端
        // 规整过,列表只能重来。
        onSaved = { reload(indicator = true) },
        onDeleted = { deleted -> _state.update { it.copy(folders = it.folders.filterNot { f -> f.id == deleted.id }) } },
    )

    private var page = 0

    /**
     * reload 与翻页共用 [page] 这一个游标:reload 把它归零,一次还在飞的旧翻页落地必须当作
     * 过期丢弃,理由同 `FavFolderViewModel`。
     */
    private var generation = 0
    private var job: Job? = null

    /** 一次请求在飞。不拿 loading/appending 判:静默重取时两者都是 false,列表却还在要第一页。 */
    private var fetching = false

    private var entered = false

    init {
        reload(indicator = false)
    }

    fun loadMore() {
        if (fetching || !_state.value.hasMore) return
        fetch(page + 1)
    }

    fun refresh() = reload(indicator = true)

    fun retry() = if (_state.value.folders.isEmpty()) reload(indicator = false) else loadMore()

    /**
     * 每次回到这一页整份重取,理由同「我的」(`ProfileViewModel.refresh`):这一页显示的条数、
     * 名字、封面在内容页、播放页的收藏面板和官方端都会变,逐一通知补不完。首次进入时
     * init 已经取过,跳过这一次。重取期间旧列表原样留着,不转圈。
     */
    fun onEnter() {
        if (entered) reload(indicator = false) else entered = true
    }

    private fun reload(indicator: Boolean) {
        generation++
        job?.cancel()
        fetching = false
        page = 0
        _state.update {
            it.copy(
                refreshing = indicator,
                loading = it.folders.isEmpty(),
                appending = false,
                hasMore = true,
                error = null,
            )
        }
        fetch(1)
    }

    private fun fetch(next: Int) {
        val gen = generation
        fetching = true
        if (next > 1) _state.update { it.copy(appending = true) }
        job = viewModelScope.launch {
            val result = repository.folderPage(next)
            if (gen != generation) return@launch
            fetching = false
            when (result) {
                is BiliResult.Ok -> {
                    page = next
                    _state.update {
                        val merged = if (next == 1) result.value.items else it.folders + result.value.items
                        it.copy(
                            // 翻页期间删掉一个夹子,后面整页前移一格,下一页的第一项就是上一页见过的。
                            folders = merged.distinctBy { folder -> folder.id },
                            hasMore = result.value.hasMore,
                            loading = false,
                            appending = false,
                            refreshing = false,
                            error = null,
                        )
                    }
                }

                else -> {
                    val reason = result.errorTextRes("取收藏夹列表第 $next 页")
                    _state.update { it.copy(loading = false, appending = false, refreshing = false, error = reason) }
                }
            }
        }
    }
}

/**
 * 收藏夹列表。管理动作都在这一页:新建在顶栏,编辑和删除在每一行的菜单里。
 *
 * 点一行进的是这个收藏夹的内容(`FavFolderScreen`),取消收藏在那一页做 —— 那里才看得见
 * 是哪一条。
 */
@Composable
fun FavFoldersScreen(
    state: FavFoldersUiState,
    editor: FavFolderEditorState?,
    deletion: FavFolderDeletion?,
    onOpenFolder: (FavFolderDetail) -> Unit,
    onEdit: (FavFolderDetail) -> Unit,
    onDelete: (FavFolderDetail) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    editorActions: FavFolderEditorActions,
    deletionActions: FavFolderDeletionActions,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Box(modifier = modifier.fillMaxSize()) {
        AdaptiveContent {
            RefreshBox(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                PagedColumn(
                    items = state.folders,
                    // 新建和删除都在这一页做,删掉一行时下面几行不该硬跳一格。
                    key = { it.id },
                    loading = state.loading,
                    appending = state.appending,
                    hasMore = state.hasMore,
                    error = state.error?.let { stringResource(it) },
                    emptyText = stringResource(Res.string.fav_folders_empty),
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    contentPadding = contentPadding,
                ) { folder ->
                    FavFolderRow(
                        folder = folder,
                        onClick = { onOpenFolder(folder) },
                        trailing = {
                            FavFolderMenu(
                                folderTitle = folder.title,
                                // 默认收藏夹删不掉,所以这里根本不给删除项 —— 让它可点再报错,等于把
                                // 一条服务端规则做成了一次失败。
                                deletable = !folder.isDefault,
                                onEdit = { onEdit(folder) },
                                onDelete = { onDelete(folder) },
                            )
                        },
                    )
                }
            }
        }
    }

    editor?.let { FavFolderEditorDialog(it, editorActions) }
    deletion?.let { FavFolderDeleteDialog(it, deletionActions) }
}

/**
 * 一行的管理菜单。M3E vertical menu 的外形与带图标的项,同首页溢出菜单;项的形状按首末取
 * (理由见 `FeedScreen` 的 `FeedEntryItem`),默认收藏夹只剩一项时取 standalone。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
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
                contentDescription = stringResource(Res.string.fav_folder_actions, folderTitle),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MenuDefaults.shape,
            containerColor = MenuDefaults.containerColor,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.fav_folder_edit_info)) },
                onClick = {
                    expanded = false
                    onEdit()
                },
                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                shape = if (deletable) MenuDefaults.leadingItemShape else MenuDefaults.standaloneItemShape,
            )
            if (deletable) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.fav_folder_delete_title)) },
                    onClick = {
                        expanded = false
                        onDelete()
                    },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    shape = MenuDefaults.trailingItemShape,
                )
            }
        }
    }
}
