package dev.bilby.ui.toview

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.resources.*
import dev.bilby.stringResource
import org.jetbrains.compose.resources.StringResource
import dev.bilby.api.BiliResult
import dev.bilby.data.ToViewItem
import dev.bilby.data.ToViewKind
import dev.bilby.data.ToViewRepository
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
import dev.bilby.ui.theme.BilbyTheme
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ToViewUiState(
    val loading: Boolean = true,
    /** 首屏失败说哪一句,存资源 id(见 [dev.bilby.ui.errorTextRes])。列表已有内容时的失败走 [notice]。 */
    val error: StringResource? = null,
    val items: List<ToViewItem> = emptyList(),
    val count: Int = 0,
    val capacity: Int = ToViewRepository.CAPACITY,
    /** true 为最早添加的在前。队列跟着它走,见 [dev.bilby.data.QueueContext.ToView]。 */
    val asc: Boolean = false,
    val clearing: Boolean = false,
    /**
     * 刚被移出的那一条,给撤销用。删除成功才写进来,所以它同时也是"这次真的删掉了"的信号 ——
     * 界面拿它去弹撤销,弹过一次就调 [ToViewViewModel.consumeRemoved] 清掉。
     */
    val lastRemoved: ToViewItem? = null,
    val notice: ToViewNotice? = null,
)

/**
 * 一次没做成的操作,弹一次 snackbar。
 *
 * **不写进 [ToViewUiState.error]。** 那个字段渲染成列表底部的「重试」,而它重试的是整页重拉:
 * 移出失败之后点它,得到的是一次刷新,那条视频还在原地,读者会以为重试成功了。
 *
 * @param id 每次换新的,连着失败两次时 `LaunchedEffect` 才会再弹。
 * @param action 哪个操作没做成,文案里带一个 %1$s 装 [reason]。
 */
data class ToViewNotice(val id: Long, val action: StringResource, val reason: StringResource)

/** 顶栏菜单里的两种清空,对应接口的 clean_type(notes/comment-toview-history.md 2.6 节)。 */
enum class ToViewClear { Finished, Invalid }

/**
 * DESIGN 2.5:原生列表双向同步,不建本地队列,原生 100 条上限本身很小,一次拉满(见
 * ToViewRepository.CAPACITY)就是全部,所以这里没有分页/触底加载。
 */
class ToViewViewModel(private val repository: ToViewRepository) : ViewModel() {

    private val _state = MutableStateFlow(ToViewUiState())
    val state: StateFlow<ToViewUiState> = _state.asStateFlow()

    private var eventId = 0L

    init {
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        val asc = _state.value.asc
        viewModelScope.launch {
            val result = repository.loadList(asc)
            // 换排序之后又换回来,先发的那次晚落地时不能盖掉后发的。
            if (asc != _state.value.asc) return@launch
            when (result) {
                is BiliResult.Ok -> _state.update {
                    it.copy(loading = false, items = result.value.items, count = result.value.count)
                }

                else -> {
                    val reason = result.errorTextRes("取稍后再看")
                    _state.update {
                        // 手里已有一份时不把它换成整屏错误,只说一句这次没刷新成。
                        if (it.items.isEmpty()) {
                            it.copy(loading = false, error = reason)
                        } else {
                            it.copy(loading = false, notice = notice(Res.string.toview_refresh_failed, reason))
                        }
                    }
                }
            }
        }
    }

    fun retry() = refresh()

    fun setAsc(asc: Boolean) {
        if (asc == _state.value.asc) return
        _state.update { it.copy(asc = asc) }
        refresh()
    }

    /** 就地更新:删除成功后直接从列表摘掉这一条,不整页重拉(团队要求)。 */
    fun delete(item: ToViewItem) {
        viewModelScope.launch {
            when (val result = repository.delete(item.aid)) {
                is BiliResult.Ok -> _state.update {
                    it.copy(
                        items = it.items - item,
                        count = (it.count - 1).coerceAtLeast(0),
                        lastRemoved = item,
                    )
                }

                else -> {
                    val reason = result.errorTextRes("移出稍后再看(aid=${item.aid})")
                    _state.update { it.copy(notice = notice(Res.string.toview_remove_failed, reason)) }
                }
            }
        }
    }

    /**
     * 撤销一次移出。走的是"加入稍后再看"这个接口,不是把删除请求回滚 —— 服务端没有回滚。
     * 所以恢复之后这一条排在最近添加的那一端:对服务端而言它就是刚加进来的一条,原来的位置
     * 找不回来。按最早添加排时那一端是列表末尾。
     */
    fun undoDelete(item: ToViewItem) {
        _state.update { it.copy(lastRemoved = null) }
        viewModelScope.launch {
            when (val result = repository.add(item.bvid)) {
                is BiliResult.Ok -> _state.update {
                    val items = if (it.asc) it.items + item else listOf(item) + it.items
                    it.copy(items = items, count = it.count + 1)
                }

                else -> {
                    val reason = result.errorTextRes("撤销移出稍后再看(bvid=${item.bvid})")
                    _state.update { it.copy(notice = notice(Res.string.toview_undo_failed, reason)) }
                }
            }
        }
    }

    fun consumeRemoved() = _state.update { it.copy(lastRemoved = null) }

    fun dismissNotice(notice: ToViewNotice) =
        _state.update { if (it.notice?.id == notice.id) it.copy(notice = null) else it }

    fun clear(kind: ToViewClear) {
        // 没有已看完的条目时清空已看完是 no-op,不在空集上发一次没有可见反馈的请求。UI 层
        // 也会禁用入口,但 ViewModel 自己仍要守住这个条件,防止重复点击绕过 UI。
        val current = _state.value
        if (current.clearing) return
        if (kind == ToViewClear.Finished && current.items.none { it.isFinished }) return
        _state.update { it.copy(clearing = true) }
        viewModelScope.launch {
            val result = when (kind) {
                ToViewClear.Finished -> repository.clearFinished()
                ToViewClear.Invalid -> repository.clearInvalid()
            }
            if (result is BiliResult.Ok) {
                when (kind) {
                    ToViewClear.Finished -> _state.update {
                        val remaining = it.items.filterNot { item -> item.isFinished }
                        it.copy(clearing = false, items = remaining, count = remaining.size)
                    }
                    // 哪些条目算失效,列表接口不给判据(PiliPlus 同样是清完重拉),只能再取一次。
                    // 这不违反"乐观更新不重新拉取":那条管的是本地算得出的计数。
                    ToViewClear.Invalid -> {
                        _state.update { it.copy(clearing = false) }
                        refresh()
                    }
                }
            } else {
                val reason = result.errorTextRes("清空稍后再看($kind)")
                _state.update { it.copy(clearing = false, notice = notice(Res.string.toview_clear_failed, reason)) }
            }
        }
    }

    private fun notice(action: StringResource, reason: StringResource) = ToViewNotice(++eventId, action, reason)
}

/**
 * 稍后再看。清空在顶栏的菜单里(见 MainActivity),这里是容量、听全部、排序和列表。
 */
@Composable
fun ToViewScreen(
    state: ToViewUiState,
    onDelete: (ToViewItem) -> Unit,
    onItemClick: (ToViewItem) -> Unit,
    onRetry: () -> Unit,
    /** 听全部。给的是列表里第一条能放的,队列由播放页按同一个排序建。 */
    onListenAll: (ToViewItem) -> Unit,
    onAscChanged: (Boolean) -> Unit,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val firstPlayable = state.items.firstOrNull { it.playable }
    AdaptiveContent(modifier = modifier) {
        RefreshBox(
            refreshing = state.loading && state.items.isNotEmpty(),
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            PagedColumn(
                items = state.items,
                // 这一页的条目会被单条移出(下面那个 Close 按钮),动画认 aid。
                key = { it.aid },
                loading = state.loading,
                appending = false,
                // 一次拉满,没有下一页(见 ToViewRepository.CAPACITY)。
                hasMore = false,
                error = state.error?.let { stringResource(it) },
                emptyText = stringResource(Res.string.toview_empty),
                onLoadMore = {},
                onRetry = onRetry,
                contentPadding = contentPadding,
                header = {
                    // 容量与表头跟着列表一起滚走,不钉在顶上:钉住的两行加上顶栏占掉小半屏,
                    // 而这一页要看的是列表。判据同空间页投稿栏的表头。
                    Column {
                        CapacityMeter(state.count, state.capacity)
                        ListHeader(
                            asc = state.asc,
                            canListen = firstPlayable != null,
                            onListenAll = { firstPlayable?.let(onListenAll) },
                            onAscChanged = onAscChanged,
                        )
                    }
                },
            ) { item ->
                VideoRow(
                    item = item.toRowUi(),
                    // 剧集与课程照常列出但不可打开(UGC-only);移出按钮仍在,它们正是该清掉的那一类。
                    enabled = item.playable,
                    onClick = { if (item.playable) onItemClick(item) },
                    trailing = {
                        // 图标用 Close 而不是 Delete:这里是"从列表里拿掉",
                        // 不是把视频删了。垃圾桶图标承诺的破坏性比实际动作大。
                        IconButton(onClick = { onDelete(item) }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(Res.string.toview_remove, item.title),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        }
    }
}

private val AscOptions = listOf(
    false to Res.string.toview_order_recent,
    true to Res.string.toview_order_earliest,
)

/** 表头:左边听全部,右边排序。与空间页投稿栏同一个形状,按文字基线对齐的理由也同那边。 */
@Composable
private fun ListHeader(
    asc: Boolean,
    canListen: Boolean,
    onListenAll: () -> Unit,
    onAscChanged: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Tight),
    ) {
        TextButton(
            onClick = onListenAll,
            enabled = canListen,
            modifier = Modifier.alignByBaseline(),
        ) {
            Icon(
                Icons.Filled.Headphones,
                contentDescription = null,
                modifier = Modifier.size(Dimens.IconInline),
            )
            Text(
                stringResource(Res.string.toview_listen_all),
                modifier = Modifier.padding(start = Spacing.Tight),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        SortMenu(
            options = AscOptions,
            selected = asc,
            onSelect = onAscChanged,
            modifier = Modifier.alignByBaseline(),
        )
    }
}

/**
 * 容量条。上限是反囤积设计的一部分,要亮给用户看,不是藏起来的实现细节(DESIGN 2.5)。
 *
 * 除了数字还画一条进度条:"87 / 100" 要读一下才知道快满了,一条快到头的进度条是扫一眼的事。
 * 快满时转成 error 色 —— 这是少数几个用 error 色的地方,因为它确实要求用户做点什么(清一清)。
 */
@Composable
private fun CapacityMeter(count: Int, capacity: Int, modifier: Modifier = Modifier) {
    val fraction = if (capacity > 0) (count.toFloat() / capacity).coerceIn(0f, 1f) else 0f
    val nearlyFull = fraction >= 0.9f
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        Text(
            text = stringResource(Res.string.toview_capacity, count, capacity),
            style = MaterialTheme.typography.titleSmall,
            color = if (nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        // 这条不是等待指示,是容量刻度,所以留在 progress indicator 一侧,不换成 loading
        // indicator:它读的是"已经占了多少",没有任何东西正在进行。
        LinearProgressIndicator(
            progress = { fraction },
            color = if (nearlyFull) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** internal(不是 private):个人页的稍后再看预览区也要用同一份映射,见 ui/profile/ProfileScreen.kt。 */
@Composable
internal fun ToViewItem.toRowUi() = VideoRowUi(
    title = title,
    coverUrl = coverUrl,
    durationText = if (durationSeconds > 0) formatDurationSeconds(durationSeconds) else "",
    upName = upName,
    dateText = formatRelativeTime(pubdateEpochSeconds),
    playText = formatCount(playCount),
    danmakuText = formatCount(danmakuCount),
    meta = when {
        // 打不开的那几条先说为什么打不开,进度对它们没有意义。
        kind == ToViewKind.Pgc -> pgcLabel.ifBlank { stringResource(Res.string.media_kind_ogv) }
        kind == ToViewKind.Course -> stringResource(Res.string.media_kind_course)
        isFinished -> stringResource(Res.string.toview_finished)
        progressSeconds > 0 -> stringResource(Res.string.toview_progress, formatDurationSeconds(progressSeconds))
        else -> null
    },
    progressFraction = when {
        isFinished -> 1f
        durationSeconds > 0 && progressSeconds > 0 -> progressSeconds.toFloat() / durationSeconds
        else -> null
    },
)

// ---- Preview ----

private fun previewItem(aid: Long, title: String, progress: Long, kind: ToViewKind = ToViewKind.Video) = ToViewItem(
    aid = aid,
    bvid = "BV1aa$aid",
    coverUrl = "https://i0.hdslb.com/bfs/archive/preview.jpg",
    title = title,
    durationSeconds = 4503,
    upName = "某知名UP主",
    progressSeconds = progress,
    pubdateEpochSeconds = 0L,
    playCount = 123456,
    danmakuCount = 789,
    kind = kind,
)

@Preview(showBackground = true, name = "列表")
@Composable
private fun ToViewScreenPreview() {
    BilbyTheme {
        ToViewScreen(
            state = ToViewUiState(
                loading = false,
                items = listOf(
                    previewItem(1, "看到一半的视频", 300),
                    previewItem(2, "已经看完的视频", -1),
                    previewItem(3, "某部番剧的一集", 0, ToViewKind.Pgc),
                ),
                count = 3,
            ),
            onDelete = {},
            onItemClick = {},
            onRetry = {},
            onListenAll = {},
            onAscChanged = {},
        )
    }
}

@Preview(showBackground = true, name = "快满了")
@Composable
private fun ToViewScreenNearlyFullPreview() {
    BilbyTheme {
        ToViewScreen(
            state = ToViewUiState(
                loading = false,
                items = listOf(previewItem(1, "看到一半的视频", 300)),
                count = 95,
            ),
            onDelete = {},
            onItemClick = {},
            onRetry = {},
            onListenAll = {},
            onAscChanged = {},
        )
    }
}

@Preview(showBackground = true, name = "空")
@Composable
private fun ToViewScreenEmptyPreview() {
    BilbyTheme {
        ToViewScreen(ToViewUiState(loading = false), {}, {}, {}, {}, {})
    }
}
