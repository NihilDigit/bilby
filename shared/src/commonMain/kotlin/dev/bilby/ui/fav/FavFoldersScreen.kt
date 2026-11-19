package dev.bilby.ui.fav

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.api.BiliResult
import dev.bilby.data.FavFolderDetail
import dev.bilby.data.FavOrder
import dev.bilby.data.FavRepository
import dev.bilby.data.FavVideo
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveListContent
import dev.bilby.ui.AdaptiveVideoGridContent
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.LocalPointerSource
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.PagedLayout
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.VideoCommonMenuItems
import dev.bilby.ui.components.VideoGridCard
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.rememberCopyAction
import dev.bilby.ui.components.videoCommonMenuItemCount
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

@Immutable
data class FavFoldersUiState(
    val folders: List<FavFolderDetail> = emptyList(),
    val loading: Boolean = true,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    /** 存资源 id,见 [dev.bilby.ui.errorTextRes]。 */
    val error: StringResource? = null,
    /** 跨全部收藏夹搜索的输入框内容与已提交关键词。 */
    val keyword: String = "",
    val appliedKeyword: String = "",
    val searchItems: List<FavVideo> = emptyList(),
    val searchLoading: Boolean = false,
    val searchAppending: Boolean = false,
    val searchRefreshing: Boolean = false,
    val searchHasMore: Boolean = false,
    val searchError: StringResource? = null,
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
    private var searchPage = 0
    private var searchGeneration = 0
    private var searchJob: Job? = null
    private val searchSeenAids = mutableSetOf<Long>()

    init {
        reload(indicator = false)
    }

    fun onKeywordChange(keyword: String) = _state.update { it.copy(keyword = keyword) }

    fun search() {
        val keyword = _state.value.keyword.trim()
        if (keyword.isEmpty() || keyword == _state.value.appliedKeyword) return
        searchGeneration++
        searchJob?.cancel()
        searchPage = 0
        searchSeenAids.clear()
        _state.update {
            it.copy(
                appliedKeyword = keyword,
                searchItems = emptyList(),
                searchLoading = false,
                searchAppending = false,
                searchRefreshing = false,
                searchHasMore = true,
                searchError = null,
            )
        }
        loadMoreSearch(replace = true)
    }

    fun closeSearch() {
        searchGeneration++
        searchJob?.cancel()
        searchPage = 0
        searchSeenAids.clear()
        _state.update {
            it.copy(
                keyword = "",
                appliedKeyword = "",
                searchItems = emptyList(),
                searchLoading = false,
                searchAppending = false,
                searchRefreshing = false,
                searchHasMore = false,
                searchError = null,
            )
        }
    }

    fun refreshSearch() {
        if (_state.value.appliedKeyword.isBlank()) return
        searchGeneration++
        searchJob?.cancel()
        searchPage = 0
        searchSeenAids.clear()
        _state.update {
            it.copy(
                searchRefreshing = true,
                searchLoading = false,
                searchAppending = false,
                searchHasMore = true,
                searchError = null,
            )
        }
        loadMoreSearch(replace = true)
    }

    fun retrySearch() {
        if (_state.value.appliedKeyword.isBlank()) return
        loadMoreSearch(replace = searchPage == 0)
    }

    fun loadMoreSearch(replace: Boolean = false) {
        val current = _state.value
        val keyword = current.appliedKeyword
        if (keyword.isBlank()) return
        if (current.searchLoading || current.searchAppending || (!replace && !current.searchHasMore)) return
        val next = if (replace) 1 else searchPage + 1
        val gen = searchGeneration
        _state.update {
            it.copy(
                searchLoading = replace || next == 1,
                searchAppending = !replace && next > 1,
                searchError = null,
            )
        }
        searchJob = viewModelScope.launch {
            try {
                val defaultFolderId = resolveDefaultFolderId()
                if (gen != searchGeneration) return@launch
                if (defaultFolderId == null) {
                    _state.update {
                        it.copy(searchItems = emptyList(), searchHasMore = false, searchError = null)
                    }
                    return@launch
                }
                val result = repository.folderContents(
                    mediaId = defaultFolderId,
                    page = next,
                    order = FavOrder.Mtime,
                    keyword = keyword,
                    searchAllFolders = true,
                )
                if (gen != searchGeneration) return@launch
                when (result) {
                    is BiliResult.Ok -> {
                        searchPage = next
                        val fresh = result.value.items.filter { searchSeenAids.add(it.aid) }
                        _state.update {
                            it.copy(
                                searchItems = if (replace || next == 1) fresh else it.searchItems + fresh,
                                searchHasMore = result.value.hasMore,
                                searchError = null,
                            )
                        }
                    }

                    else -> {
                        val reason = result.errorTextRes("跨收藏夹搜索(pn=$next)")
                        _state.update { it.copy(searchError = reason) }
                    }
                }
            } finally {
                if (gen == searchGeneration) {
                    _state.update {
                        it.copy(searchLoading = false, searchAppending = false, searchRefreshing = false)
                    }
                }
            }
        }
    }

    private suspend fun resolveDefaultFolderId(): Long? {
        val cached = _state.value.folders
        cached.firstOrNull { it.isDefault }?.id?.let { return it }
        cached.firstOrNull()?.id?.let { return it }
        val firstPage = (repository.folderPage(1) as? BiliResult.Ok)?.value?.items.orEmpty()
        return firstPage.firstOrNull { it.isDefault }?.id ?: firstPage.firstOrNull()?.id
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

private val FolderGridMaxCellWidth = 300.dp
private val FolderGridHalfGap = 6.dp

/**
 * 收藏夹列表。管理动作都在这一页:新建在顶栏,编辑和删除在每一格的菜单里。
 *
 * 点一格进的是这个收藏夹的内容(`FavFolderScreen`),取消收藏在那一页做 —— 那里才看得见
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
    onVideoClick: (FavVideo) -> Unit = {},
    onUpClick: (Long) -> Unit = {},
    onLoadMoreSearch: () -> Unit = {},
    onRetrySearch: () -> Unit = {},
    onRefreshSearch: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    if (state.appliedKeyword.isNotBlank()) {
        FavAllSearchResults(
            state = state,
            onVideoClick = onVideoClick,
            onUpClick = onUpClick,
            onLoadMore = onLoadMoreSearch,
            onRetry = onRetrySearch,
            onRefresh = onRefreshSearch,
            modifier = modifier,
            contentPadding = contentPadding,
        )
        return
    }
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current
    var listWidth by remember { mutableStateOf(0.dp) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { with(density) { listWidth = it.width.toDp() } },
    ) {
        AdaptiveListContent(
            modifier = Modifier.fillMaxSize(),
            maxCellWidth = FolderGridMaxCellWidth,
        ) { columns ->
            val multiColumn = columns != GridCells.Fixed(1) &&
                (listWidth == 0.dp || listWidth >= Dimens.VideoGridMinCellWidth * 2)
            val effectiveColumns = if (multiColumn) columns else GridCells.Fixed(1)
            val gridPadding = if (multiColumn) {
                PaddingValues(
                    start = contentPadding.calculateStartPadding(layoutDirection) + (Spacing.Comfortable - FolderGridHalfGap),
                    top = contentPadding.calculateTopPadding() + Spacing.Tight,
                    end = contentPadding.calculateEndPadding(layoutDirection) + (Spacing.Comfortable - FolderGridHalfGap),
                    bottom = contentPadding.calculateBottomPadding() + Spacing.Comfortable,
                )
            } else {
                contentPadding
            }

            RefreshBox(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                PagedColumn(
                    layout = PagedLayout.Grid(effectiveColumns),
                    items = state.folders,
                    // 新建和删除都在这一页做,删掉一格时下面几格平滑补位。
                    key = { it.id },
                    loading = state.loading,
                    appending = state.appending,
                    hasMore = state.hasMore,
                    error = state.error?.let { stringResource(it) },
                    emptyText = stringResource(Res.string.fav_folders_empty),
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    contentPadding = gridPadding,
                ) { folder ->
                    val menu: @Composable ColumnScope.(close: () -> Unit) -> Unit = { close ->
                        FavFolderMenuItems(
                            title = folder.title,
                            // 默认收藏夹删不掉,所以这里根本不给删除项 —— 让它可点再报错,等于把
                            // 一条服务端规则做成了一次失败。
                            deletable = !folder.isDefault,
                            close = close,
                            onEdit = { onEdit(folder) },
                            onDelete = { onDelete(folder) },
                        )
                    }
                    ContextMenuBox(
                        onClick = null,
                        menuLabel = stringResource(Res.string.fav_folder_actions, folder.title),
                        menu = menu,
                    ) { openMenu ->
                        if (multiColumn) {
                            FavFolderGridCard(
                                folder = folder,
                                onClick = { onOpenFolder(folder) },
                                onLongClick = openMenu,
                                overflow = { FavFolderMenu(folder.title, menu) },
                                modifier = Modifier.padding(FolderGridHalfGap),
                            )
                        } else {
                            FavFolderRow(
                                folder = folder,
                                onClick = { onOpenFolder(folder) },
                                onLongClick = openMenu,
                                trailing = { FavFolderMenu(folder.title, menu) },
                            )
                        }
                    }
                }
            }
        }
    }

    editor?.let { FavFolderEditorDialog(it, editorActions) }
    deletion?.let { FavFolderDeleteDialog(it, deletionActions) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavAllSearchResults(
    state: FavFoldersUiState,
    onVideoClick: (FavVideo) -> Unit,
    onUpClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val layoutDirection = LocalLayoutDirection.current
    AdaptiveVideoGridContent(modifier = modifier.fillMaxSize()) { columnCount ->
        val multiColumn = columnCount > 1
        val effectiveColumns = GridCells.Fixed(columnCount)
        val gridPadding = if (multiColumn) {
            PaddingValues(
                start = contentPadding.calculateStartPadding(layoutDirection) + (Spacing.Tight - 2.dp),
                top = contentPadding.calculateTopPadding(),
                end = contentPadding.calculateEndPadding(layoutDirection) + (Spacing.Tight - 2.dp),
                bottom = contentPadding.calculateBottomPadding() + Spacing.Comfortable,
            )
        } else {
            contentPadding
        }

        RefreshBox(
            refreshing = state.searchRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            PagedColumn(
                layout = PagedLayout.Grid(effectiveColumns),
                items = state.searchItems,
                key = { it.aid },
                loading = state.searchLoading,
                appending = state.searchAppending,
                hasMore = state.searchHasMore,
                error = state.searchError?.let { stringResource(it) },
                emptyText = stringResource(Res.string.fav_empty_search),
                onLoadMore = onLoadMore,
                onRetry = onRetry,
                contentPadding = gridPadding,
            ) { item ->
                val rowUi = item.toRowUi()
                val bvid = if (item.playable) item.bvid else ""
                val copyFallbackTitle = bvid.isEmpty() && !item.invalid && item.title.isNotBlank()
                val hasMenu = item.playable || item.upMid != 0L || copyFallbackTitle

                if (hasMenu) {
                    val copyTitleAction = rememberCopyAction()
                    ContextMenuBox(
                        onClick = null,
                        menuLabel = stringResource(Res.string.menu_item_actions),
                        menu = { close ->
                            val leadingCount = if (copyFallbackTitle) 1 else 0
                            val totalCount = leadingCount + videoCommonMenuItemCount(
                                bvid = bvid,
                                upMid = item.upMid,
                                onOpenUpSpace = onUpClick,
                                title = item.title,
                            )
                            if (copyFallbackTitle) {
                                DropdownMenuItem(
                                    onClick = {
                                        close()
                                        copyTitleAction(item.title)
                                    },
                                    text = { Text(stringResource(Res.string.action_copy_title)) },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                    shape = menuItemShape(0, totalCount),
                                )
                            }
                            VideoCommonMenuItems(
                                bvid = bvid,
                                title = item.title,
                                close = close,
                                upMid = item.upMid,
                                onOpenUpSpace = onUpClick,
                                coverUrl = item.coverUrl,
                                upName = item.upName,
                                durationSeconds = item.durationSeconds,
                                startIndex = leadingCount,
                                totalCount = totalCount,
                            )
                        },
                    ) { openMenu ->
                        if (multiColumn) {
                            VideoGridCard(
                                ui = rowUi,
                                enabled = item.playable,
                                onClick = { if (item.playable) onVideoClick(item) },
                                onLongClick = if (item.playable) openMenu else null,
                                onUpClick = if (item.upMid != 0L) ({ onUpClick(item.upMid) }) else null,
                                modifier = Modifier.padding(2.dp),
                            )
                        } else {
                            VideoRow(
                                item = rowUi,
                                enabled = item.playable,
                                onClick = { if (item.playable) onVideoClick(item) },
                                onLongClick = if (item.playable) openMenu else null,
                            )
                        }
                    }
                } else if (multiColumn) {
                    VideoGridCard(
                        ui = rowUi,
                        enabled = item.playable,
                        onClick = { if (item.playable) onVideoClick(item) },
                        modifier = Modifier.padding(2.dp),
                    )
                } else {
                    VideoRow(
                        item = rowUi,
                        enabled = item.playable,
                        onClick = { if (item.playable) onVideoClick(item) },
                    )
                }
            }
        }
    }
}

/**
 * 一项的管理菜单。M3E vertical menu 的外形与带图标的项,同首页溢出菜单。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavFolderMenu(folderTitle: String, menu: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val actionsLabel = stringResource(Res.string.fav_folder_actions, folderTitle)
    val isTouch = LocalPointerSource.current.isTouchLike
    Box {
        BilbyTooltip(text = actionsLabel) {
            IconButton(onClick = { expanded = true }) {
                Icon(
                    Icons.Outlined.MoreVert,
                    contentDescription = actionsLabel,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val popup: @Composable () -> Unit = {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = MenuDefaults.shape,
                containerColor = MenuDefaults.containerColor,
            ) {
                menu { expanded = false }
            }
        }
        if (isTouch) {
            popup()
        } else {
            MaterialExpressiveTheme(
                colorScheme = MaterialTheme.colorScheme,
                motionScheme = FavFoldersDesktopMenuMotionScheme,
                typography = MaterialTheme.typography,
                shapes = MaterialTheme.shapes,
                content = popup,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object FavFoldersDesktopMenuMotionScheme : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = tween(80)
}

/** 复制标题、编辑、删除三项,⋮ 与右键共用。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FavFolderMenuItems(
    title: String,
    deletable: Boolean,
    close: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val copyTitle = rememberCopyAction()
    val hasCopy = title.isNotBlank()
    val totalCount = (if (hasCopy) 1 else 0) + 1 + (if (deletable) 1 else 0)
    var index = 0
    if (hasCopy) {
        val currentIndex = index++
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.action_copy_title)) },
            onClick = {
                close()
                copyTitle(title)
            },
            leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
            shape = menuItemShape(currentIndex, totalCount),
        )
    }
    val editIndex = index++
    DropdownMenuItem(
        text = { Text(stringResource(Res.string.fav_folder_edit_info)) },
        onClick = {
            close()
            onEdit()
        },
        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
        shape = menuItemShape(editIndex, totalCount),
    )
    if (deletable) {
        val deleteIndex = index
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.fav_folder_delete_title)) },
            onClick = {
                close()
                onDelete()
            },
            leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            shape = menuItemShape(deleteIndex, totalCount),
        )
    }
}
