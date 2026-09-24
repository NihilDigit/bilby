package dev.bilby.ui.fav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.FavFolderDetail
import dev.bilby.data.FavOrder
import dev.bilby.data.FavRepository
import dev.bilby.data.FavVideo
import dev.bilby.formatDurationSeconds
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.RefreshBox
import dev.bilby.ui.components.SortMenu
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.formatCount
import dev.bilby.ui.errorTextRes
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FavFolderUiState(
    val items: List<FavVideo> = emptyList(),
    // false 而不是 true:loadMore 的并发守卫现在直接读这个字段(见 FavFolderViewModel),
    // 默认 true 会让 init{} 里的第一次调用把自己挡在门外。首屏 loading 由 loadMore 显式置位。
    val loading: Boolean = false,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    /** 存资源 id,见 [dev.bilby.ui.errorTextRes]。 */
    @StringRes val error: Int? = null,
    /** 收藏夹本身,来自内容接口的 `info`。第一页回来之前是 null。 */
    val info: FavFolderDetail? = null,
    val order: FavOrder = FavOrder.Mtime,
    /** 输入框里的字。**还没生效**,回车才会被抄进 [appliedKeyword],同空间页的投稿搜索。 */
    val keyword: String = "",
    val appliedKeyword: String = "",
    val cleaning: Boolean = false,
    /** 这个收藏夹刚被删掉。页面据此退回上一页,见 `MainActivity` 的 FavFolderRoute。 */
    val deleted: Boolean = false,
    val notice: FavNotice? = null,
)

/**
 * 没有可执行动作的一句提示。每次换一个新 id,`LaunchedEffect` 靠它重新弹 snackbar。
 *
 * @param reason 失败原因的资源 id(见 [dev.bilby.ui.errorTextRes]),成功类的提示为 null。
 */
data class FavNotice(val id: Long, val kind: FavNoticeKind, @StringRes val reason: Int? = null)

enum class FavNoticeKind { CleanDone, CleanFailed }

class FavFolderViewModel(
    private val mediaId: Long,
    private val repository: FavRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FavFolderUiState())
    val state: StateFlow<FavFolderUiState> = _state.asStateFlow()

    /**
     * 编辑与删除,同列表页那一份。编辑保存之后只重取 folder/info:改的是这个收藏夹的名字与
     * 简介,列表内容没有变。
     */
    val manager = FavFolderManager(
        repository = repository,
        scope = viewModelScope,
        onSaved = { refreshInfo() },
        onDeleted = { _state.update { it.copy(deleted = true) } },
    )

    private var page = 0

    /** 失效稿件的 bvid 是空的,不能拿它当 key;而 aid 一定有,收藏夹接口就是按 aid 组织的。 */
    private val seenAids = mutableSetOf<Long>()

    /**
     * reload 与 append 共用同一对游标(page、seenAids),不能并发改(性能计划 7.2):reload
     * 重置游标,这时一条还在飞的旧 append 落地必须当作过期丢弃;刷新成功后用第一页替换
     * 旧列表,而不是把两轮内容拼在一起。
     */
    private var generation = 0
    private var job: Job? = null

    init {
        loadMore()
    }

    fun loadMore(replace: Boolean = false) {
        val current = _state.value
        if (current.loading || current.appending || !current.hasMore) return
        val next = if (replace) 1 else page + 1
        val gen = generation
        _state.update {
            it.copy(
                loading = replace || next == 1,
                appending = !replace && next > 1,
            )
        }
        job = viewModelScope.launch {
            try {
                val result = repository.folderContents(mediaId, next, current.order, current.appliedKeyword)
                if (gen != generation) return@launch
                when (result) {
                    is BiliResult.Ok -> {
                        page = next
                        val fresh = result.value.items.filter { seenAids.add(it.aid) }
                        _state.update {
                            it.copy(
                                // 第一页一定是替换:刷新失败后从底部那行重试走的是 loadMore(),
                                // 此时游标已归零、旧列表还在,拼上去就是同一批 aid 出现两次,
                                // LazyColumn 按 key 直接崩。
                                items = if (replace || next == 1) fresh else it.items + fresh,
                                hasMore = result.value.hasMore,
                                info = result.value.info ?: it.info,
                                error = null,
                            )
                        }
                    }

                    else -> {
                        val reason = result.errorTextRes("取收藏夹内容(media_id=$mediaId, pn=$next)")
                        _state.update { it.copy(error = reason) }
                    }
                }
            } finally {
                if (gen == generation) _state.update { it.copy(loading = false, appending = false, refreshing = false) }
            }
        }
    }

    fun retry() {
        if (_state.value.items.isNotEmpty()) loadMore(replace = page == 0) else reload(refreshing = false)
    }

    /**
     * 下拉刷新。和 [retry] 是同一件事(整份重来),差别只在指示器:refreshing 得单独记。
     * 刷新期间保留旧列表,避免用户正在读的内容突然消失;成功后再用第一页替换它。
     */
    fun refresh() = reload(refreshing = true)

    fun setOrder(order: FavOrder) {
        if (order == _state.value.order) return
        _state.update { it.copy(order = order) }
        reload(refreshing = false)
    }

    fun onKeywordChange(keyword: String) = _state.update { it.copy(keyword = keyword) }

    /** 回车。生效的是输入框里此刻的字;与上一次生效的一样就不重拉。 */
    fun search() {
        val keyword = _state.value.keyword.trim()
        if (keyword == _state.value.appliedKeyword) return
        _state.update { it.copy(appliedKeyword = keyword) }
        reload(refreshing = false)
    }

    /** 退出搜索。清掉关键词;真筛过才重拉,只敲了字没回车的话列表本来就是全部。 */
    fun closeSearch() {
        val wasFiltering = _state.value.appliedKeyword.isNotEmpty()
        _state.update { it.copy(keyword = "", appliedKeyword = "") }
        if (wasFiltering) reload(refreshing = false)
    }

    private fun reload(refreshing: Boolean) {
        generation++
        job?.cancel()
        page = 0
        seenAids.clear()
        _state.update {
            it.copy(
                refreshing = refreshing,
                error = null,
                loading = false,
                appending = false,
                hasMore = true,
                // 换了排序或搜索词,旧列表不再是这份列表,留着它等于让人对着错的内容等;
                // 下拉刷新则还是同一份,留着。
                items = if (refreshing) it.items else emptyList(),
            )
        }
        loadMore(replace = true)
    }

    private fun refreshInfo() {
        viewModelScope.launch {
            when (val result = repository.folderInfo(mediaId)) {
                is BiliResult.Ok -> _state.update { it.copy(info = result.value) }
                // 标题没更新不影响这一页能用,记一行就够,不打断人。
                else -> result.errorTextRes("编辑后取收藏夹信息(media_id=$mediaId)")
            }
        }
    }

    /**
     * 清除失效内容。成功后整份重取:哪些算失效由服务端判,已经翻到的几页里标着失效的
     * 未必就是全部,本地摘不干净。
     */
    fun cleanInvalid() {
        if (_state.value.cleaning) return
        _state.update { it.copy(cleaning = true) }
        viewModelScope.launch {
            val result = repository.cleanInvalid(mediaId)
            _state.update { it.copy(cleaning = false) }
            if (result is BiliResult.Ok) {
                notice(FavNoticeKind.CleanDone, null)
                // 条数跟着第一页的 info 一起回来,不另取 folder/info。
                reload(refreshing = true)
            } else {
                notice(FavNoticeKind.CleanFailed, result.errorTextRes("清除失效内容(media_id=$mediaId)"))
            }
        }
    }

    fun dismissNotice(notice: FavNotice) {
        _state.update { if (it.notice?.id == notice.id) it.copy(notice = null) else it }
    }

    /** 失败的原因已经由 errorTextRes 连同 media_id 打进日志,这里只管弹出来。 */
    private fun notice(kind: FavNoticeKind, @StringRes reason: Int?) {
        _state.update { it.copy(notice = FavNotice(nextEventId(), kind, reason)) }
    }

    private var eventId = 0L

    private fun nextEventId(): Long = ++eventId
}

private val FavOrders = listOf(
    FavOrder.Mtime to R.string.fav_order_mtime,
    FavOrder.View to R.string.fav_order_view,
    FavOrder.Pubtime to R.string.fav_order_pubtime,
)

/**
 * **snackbar 的宿主在这一页自己身上**,不在 `MainActivity` 的 `Scaffold` 上:清除失效内容的
 * 结果提示只有这一页有,为它把 `SnackbarHostState` 穿到导航层不值(同一条判断见 `MainActivity`
 * 里那段 Toast 的注释)。
 *
 * **行尾没有取消收藏的按钮。** 收藏夹是攒下来的东西,不像稍后再看要常常清;行尾常驻一个叉号,
 * 滑动时手指最容易碰到它。失效稿件的清理在 ⋮ 菜单的「清除失效内容」。
 *
 * 顶栏(搜索、管理菜单)在 [FavFolderTopBar],这里是页头与列表。
 */
@Composable
fun FavFolderScreen(
    state: FavFolderUiState,
    editor: FavFolderEditorState?,
    deletion: FavFolderDeletion?,
    onItemClick: (FavVideo) -> Unit,
    /** 听收藏夹。给的是列表里第一条能放的,队列按同一份排序与搜索词建。 */
    onListen: (FavVideo) -> Unit,
    onOrderChanged: (FavOrder) -> Unit,
    onNoticeShown: (FavNotice) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    editorActions: FavFolderEditorActions,
    deletionActions: FavFolderDeletionActions,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val notice = state.notice
    val noticeText = notice?.let { it.text() }
    LaunchedEffect(notice?.id) {
        if (notice == null || noticeText == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(noticeText)
        onNoticeShown(notice)
    }

    val firstPlayable = state.items.firstOrNull { it.playable }
    Box(modifier = modifier.fillMaxSize()) {
        AdaptiveContent {
            RefreshBox(
                refreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                PagedColumn(
                    items = state.items,
                    key = { it.aid },
                    loading = state.loading,
                    appending = state.appending,
                    hasMore = state.hasMore,
                    error = state.error?.let { stringResource(it) },
                    // 空是因为生效中的那个关键词没搜到东西,不是因为这个收藏夹是空的。
                    emptyText = stringResource(
                        if (state.appliedKeyword.isBlank()) R.string.fav_empty else R.string.fav_empty_search,
                    ),
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    contentPadding = contentPadding,
                    header = {
                        // 页头跟着列表一起滚走,理由同空间页投稿栏的表头。
                        item(key = "header") {
                            FolderHeader(
                                info = state.info,
                                order = state.order,
                                canListen = firstPlayable != null,
                                onListen = { firstPlayable?.let(onListen) },
                                onOrderChanged = onOrderChanged,
                            )
                        }
                    },
                ) { item ->
                    VideoRow(
                        item = item.toRowUi(),
                        // 失效稿件、音频与剧集照常列出但不可点:前者悄悄隐藏会让人以为自己记错了
                        // 收藏过什么,后两者不在本应用的范围里(UGC-only)。
                        enabled = item.playable,
                        onClick = { if (item.playable) onItemClick(item) },
                    )
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding()),
        )
    }

    editor?.let { FavFolderEditorDialog(it, editorActions) }
    deletion?.let { FavFolderDeleteDialog(it, deletionActions) }
}

/**
 * 页头:简介、条数与公开性,下面一行是听收藏夹与排序。
 *
 * 标题不在这里重复 —— 顶栏已经写着。简介为空时那一行不画,不给占位文案。
 */
@Composable
private fun FolderHeader(
    info: FavFolderDetail?,
    order: FavOrder,
    canListen: Boolean,
    onListen: () -> Unit,
    onOrderChanged: (FavOrder) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (info != null) {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                if (info.intro.isNotBlank()) {
                    Text(
                        text = info.intro,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = favFolderMeta(info),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // 与空间页投稿栏、稍后再看同一个形状:左边听,右边排序,按文字基线对齐。
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.Tight)) {
            TextButton(
                onClick = onListen,
                enabled = canListen,
                modifier = Modifier.alignByBaseline(),
            ) {
                Icon(
                    Icons.Filled.Headphones,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconInline),
                )
                Text(
                    stringResource(R.string.fav_listen),
                    modifier = Modifier.padding(start = Spacing.Tight),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            SortMenu(
                options = FavOrders,
                selected = order,
                onSelect = onOrderChanged,
                modifier = Modifier.alignByBaseline(),
            )
        }
    }
}

@Composable
private fun FavNotice.text(): String {
    val reasonText = reason?.let { stringResource(it) }.orEmpty()
    return when (kind) {
        FavNoticeKind.CleanDone -> stringResource(R.string.fav_clean_done)
        FavNoticeKind.CleanFailed -> stringResource(R.string.fav_clean_failed, reasonText)
    }
}

@Composable
private fun FavVideo.toRowUi(): VideoRowUi {
    val kindLabel = when {
        isVideo -> null
        type == FavVideo.TYPE_AUDIO -> stringResource(R.string.media_kind_audio)
        else -> ogvTypeName.ifBlank { stringResource(R.string.media_kind_ogv) }
    }
    return VideoRowUi(
        title = if (invalid) stringResource(R.string.fav_invalid_video) else title,
        coverUrl = coverUrl,
        durationText = if (durationSeconds > 0) formatDurationSeconds(durationSeconds) else "",
        upName = upName,
        // 收藏时间,不是发布时间:这一页默认按收藏时间排,这一行说的是"什么时候收的"。
        dateText = formatRelativeTime(favTimeEpochSeconds),
        playText = formatCount(playCount),
        danmakuText = formatCount(danmakuCount),
        // 打不开的非视频条目在封面左上角说它是什么。
        typeBadge = kindLabel.orEmpty(),
    )
}
