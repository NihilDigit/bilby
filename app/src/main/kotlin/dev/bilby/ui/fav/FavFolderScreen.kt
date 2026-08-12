package dev.bilby.ui.fav

import dev.bilby.formatDurationSeconds
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.BiliLog
import dev.bilby.R
import dev.bilby.api.BiliResult
import dev.bilby.data.FavRepository
import dev.bilby.data.FavVideo
import dev.bilby.ui.AdaptiveContent
import dev.bilby.ui.components.PagedColumn
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

data class FavFolderUiState(
    val items: List<FavVideo> = emptyList(),
    // false 而不是 true:loadMore 的并发守卫现在直接读这个字段(见 FavFolderViewModel),
    // 默认 true 会让 init{} 里的第一次调用把自己挡在门外。首屏 loading 由 loadMore 显式置位。
    val loading: Boolean = false,
    val appending: Boolean = false,
    val refreshing: Boolean = false,
    val hasMore: Boolean = true,
    val error: String? = null,
    val undo: FavRemoveUndo? = null,
    val notice: FavNotice? = null,
)

/**
 * 一次已经生效的取消收藏,等着用户撤销。
 *
 * @param index 原来在列表里的位置。撤销后放回原处而不是追加到末尾 —— 用户是在某一屏上按的,
 *   条目跳到几十条之外等于没撤销回来。
 * @param id 每次移出都换一个新的,`LaunchedEffect` 靠它重新弹 snackbar:连着移出两条时,
 *   只认条目本身的话第二条不会触发。
 */
data class FavRemoveUndo(val id: Long, val video: FavVideo, val index: Int)

/** 没有可执行动作的一句提示,同样按 id 重弹。[detail] 是接口给的原因。 */
data class FavNotice(val id: Long, val kind: FavNoticeKind, val detail: String)

enum class FavNoticeKind { RemoveFailed, UndoFailed }

class FavFolderViewModel(
    private val mediaId: Long,
    private val repository: FavRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(FavFolderUiState())
    val state: StateFlow<FavFolderUiState> = _state.asStateFlow()

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
                when (val result = repository.folderContents(mediaId, next)) {
                    is BiliResult.Ok -> {
                        if (gen != generation) return@launch
                        page = next
                        val fresh = result.value.items.filter { seenAids.add(it.aid) }
                        _state.update {
                            it.copy(
                                items = if (replace) fresh else it.items + fresh,
                                hasMore = result.value.hasMore,
                                error = null,
                            )
                        }
                    }

                    is BiliResult.ApiError -> if (gen == generation) fail("${result.message}(${result.code})")
                    is BiliResult.Failure -> if (gen == generation) fail(result.cause.message ?: "网络错误")
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
            )
        }
        loadMore(replace = true)
    }

    private fun fail(message: String) {
        BiliLog.w("取收藏夹内容失败(media_id=$mediaId): $message")
        _state.update { it.copy(loading = false, appending = false, refreshing = false, error = message) }
    }

    /**
     * 取消收藏。**先从列表里拿掉再发请求,失败才放回去**,并且不重拉这一页(乐观更新的硬约定)。
     *
     * 不弹确认框:batch-deal 的反向操作就是把同一条 resources 放进 add_media_ids,撤销无损。
     * 确认框拦在每一次操作前面,撤销只在做错的那一次动一下。
     */
    fun remove(video: FavVideo) {
        val index = _state.value.items.indexOf(video)
        if (index < 0) return
        _state.update { it.copy(items = it.items - video) }
        viewModelScope.launch {
            val result = repository.removeFromFolder(mediaId, video.aid)
            if (result is BiliResult.Ok) {
                _state.update { it.copy(undo = FavRemoveUndo(nextEventId(), video, index)) }
            } else {
                _state.update { it.copy(items = it.items.withItemAt(index, video)) }
                notice(FavNoticeKind.RemoveFailed, result.reason(), "取消收藏失败")
            }
        }
    }

    /** 撤销同样是乐观的:先放回列表,请求失败再拿掉一次,列表最终和服务端一致。 */
    fun undoRemove(undo: FavRemoveUndo) {
        if (_state.value.undo?.id != undo.id) return
        _state.update { it.copy(undo = null, items = it.items.withItemAt(undo.index, undo.video)) }
        viewModelScope.launch {
            val result = repository.restoreToFolder(mediaId, undo.video.aid)
            if (result !is BiliResult.Ok) {
                _state.update { it.copy(items = it.items - undo.video) }
                notice(FavNoticeKind.UndoFailed, result.reason(), "撤销取消收藏失败")
            }
        }
    }

    /** snackbar 自己消失了。撤销的机会过去,状态清掉,免得转屏后又弹一次。 */
    fun dismissUndo(undo: FavRemoveUndo) {
        _state.update { if (it.undo?.id == undo.id) it.copy(undo = null) else it }
    }

    fun dismissNotice(notice: FavNotice) {
        _state.update { if (it.notice?.id == notice.id) it.copy(notice = null) else it }
    }

    private fun notice(kind: FavNoticeKind, detail: String, logPrefix: String) {
        BiliLog.w("$logPrefix(media_id=$mediaId): $detail")
        _state.update { it.copy(notice = FavNotice(nextEventId(), kind, detail)) }
    }

    private var eventId = 0L

    private fun nextEventId(): Long = ++eventId
}

/** 放回原来的位置;原位置已经不存在(期间又翻了页)就落到末尾。 */
private fun <T> List<T>.withItemAt(index: Int, item: T): List<T> =
    toMutableList().apply { add(index.coerceIn(0, size), item) }

/** internal:收藏夹列表页那份 ViewModel 也要把同样的失败写成同样的一句话。 */
internal fun BiliResult<*>.reason(): String = when (this) {
    is BiliResult.ApiError -> "$message($code)"
    is BiliResult.Failure -> cause.message ?: "网络错误"
    is BiliResult.Ok -> ""
}

/**
 * **snackbar 的宿主在这一页自己身上**,不在 `MainActivity` 的 `Scaffold` 上:撤销这件事只有
 * 这一页有,而各页面各有自己的 Scaffold,为它把 `SnackbarHostState` 穿到导航层不值(同一条
 * 判断见 `MainActivity` 里那段 Toast 的注释)。
 */
@Composable
fun FavFolderScreen(
    state: FavFolderUiState,
    onItemClick: (FavVideo) -> Unit,
    onRemove: (FavVideo) -> Unit,
    onUndoRemove: (FavRemoveUndo) -> Unit,
    onUndoExpired: (FavRemoveUndo) -> Unit,
    onNoticeShown: (FavNotice) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val removedText = stringResource(R.string.fav_removed)
    val undoLabel = stringResource(R.string.fav_undo)

    val undo = state.undo
    LaunchedEffect(undo?.id) {
        if (undo == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(message = removedText, actionLabel = undoLabel)
        if (result == SnackbarResult.ActionPerformed) onUndoRemove(undo) else onUndoExpired(undo)
    }

    val notice = state.notice
    val noticeText = notice?.let { stringResource(it.kind.textRes, it.detail) }
    LaunchedEffect(notice?.id) {
        if (notice == null || noticeText == null) return@LaunchedEffect
        snackbarHostState.showSnackbar(noticeText)
        onNoticeShown(notice)
    }

    Box(modifier = modifier.fillMaxSize()) {
        AdaptiveContent {
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                PagedColumn(
                    items = state.items,
                    key = { it.aid },
                    loading = state.loading,
                    appending = state.appending,
                    hasMore = state.hasMore,
                    error = state.error,
                    emptyText = stringResource(R.string.fav_empty),
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                    contentPadding = contentPadding,
                ) { item ->
                    VideoRow(
                        item = item.toRowUi(),
                        // 失效稿件照常列出但不可点:UP 删稿或转私密之后收藏夹里还留着这一条,
                        // 悄悄隐藏会让人以为自己记错了收藏过什么。
                        enabled = !item.invalid,
                        onClick = { if (!item.invalid) onItemClick(item) },
                        trailing = {
                            // 图标用 Close 而不是 Delete:这里是"从这个收藏夹里拿掉",视频还在。
                            // 失效稿件更要留着这个入口 —— 它正是用户想清掉的那一类。
                            IconButton(onClick = { onRemove(item) }) {
                                Icon(
                                    Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.fav_remove, item.title),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
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
}

private val FavNoticeKind.textRes: Int
    get() = when (this) {
        FavNoticeKind.RemoveFailed -> R.string.fav_remove_failed
        FavNoticeKind.UndoFailed -> R.string.fav_undo_failed
    }

@Composable
private fun FavVideo.toRowUi(): VideoRowUi {
    val invalidLabel = stringResource(R.string.fav_invalid_video)
    return VideoRowUi(
        title = if (invalid) invalidLabel else title,
        coverUrl = coverUrl,
        durationText = formatDurationSeconds(durationSeconds),
        upName = upName,
        // 收藏夹接口不给收藏时间,也不给弹幕数。传 null 而不是空串 —— StatRow 对 null 是
        // 整项不画,对空串是画个图标后面空着。
        dateText = null,
        playText = playCount.toString(),
        danmakuText = null,
    )
}
