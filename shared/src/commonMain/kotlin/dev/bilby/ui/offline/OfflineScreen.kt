package dev.bilby.ui.offline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalToggleButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.formatDurationMillis
import dev.bilby.formatDurationSeconds
import dev.bilby.offline.OfflineCollectionGroup
import dev.bilby.offline.OfflineDownloader
import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineQueueSummary
import dev.bilby.offline.OfflineStatus
import dev.bilby.offline.OfflineStore
import dev.bilby.offline.OfflineUpGroup
import dev.bilby.offline.groupByUp
import dev.bilby.offline.partitionByCollection
import dev.bilby.offline.queueSummary
import dev.bilby.player.isWatchedToEnd
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveVideoGridContent
import dev.bilby.ui.BackHandler
import dev.bilby.ui.isExpanded
import dev.bilby.ui.rememberBilbyWindowSize
import dev.bilby.ui.components.Avatar
import dev.bilby.ui.components.BiliAsyncImage
import dev.bilby.ui.components.BilbyTooltip
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.CoverAspectRatio
import dev.bilby.ui.components.CoverCornerRadius
import dev.bilby.ui.components.DestructiveConfirmButton
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.ListScrollbar
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.VideoCommonMenuItems
import dev.bilby.ui.components.VideoCover
import dev.bilby.ui.components.VideoGridCard
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.chargingBadgeText
import dev.bilby.ui.components.matchesLibraryQuery
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.videoCommonMenuItemCount
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.FixedColors
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.rememberReducedMotion
import dev.bilby.ui.theme.tabular
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 已缓存的内容与下载队列管理。
 *
 * 条目的真相在 [OfflineDownloader.items] 上(它同时盖着盘上已有的和正在下的那条),这里只
 * 多算一个已用空间 —— 删除、清空队列或有新条目下载完成时在磁盘操作真正落地之后重算一次,
 * 避免删除协程尚未执行 `store.delete` 就提前统计出旧字节数。
 */
class OfflineViewModel(
    private val downloader: OfflineDownloader,
    private val store: OfflineStore,
) : ViewModel() {

    val items: StateFlow<List<OfflineItem>> = downloader.items

    private val _usedBytes = MutableStateFlow(0L)
    val usedBytes: StateFlow<Long> = _usedBytes.asStateFlow()

    init {
        viewModelScope.launch {
            downloader.items
                .map { list -> list.count { it.status == OfflineStatus.Completed } }
                .distinctUntilChanged()
                .collect { _usedBytes.value = store.usedBytes() }
        }
    }

    fun delete(item: OfflineItem) {
        viewModelScope.launch {
            downloader.deleteAndAwait(item)
            _usedBytes.value = store.usedBytes()
        }
    }

    /**
     * 批量删。等待下载器在应用作用域把所有条目逐条停掉并删完磁盘文件后,一次性刷新已用空间。
     */
    fun deleteAll(items: List<OfflineItem>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            downloader.deleteAllAndAwait(items)
            _usedBytes.value = store.usedBytes()
        }
    }

    fun pause(item: OfflineItem) = downloader.pause(item.bvid, item.cid)

    fun resume(item: OfflineItem) = downloader.resume(item.bvid, item.cid)

    fun pauseSelected(ids: Set<String>) {
        if (ids.isEmpty()) return
        items.value.forEach { item ->
            if (item.id in ids && (item.status == OfflineStatus.Queued || item.status == OfflineStatus.Running)) {
                downloader.pause(item.bvid, item.cid)
            }
        }
    }

    fun resumeSelected(ids: Set<String>) {
        if (ids.isEmpty()) return
        items.value.forEach { item ->
            if (item.id in ids && (item.status == OfflineStatus.Paused || item.status == OfflineStatus.Failed)) {
                downloader.resume(item.bvid, item.cid)
            }
        }
    }

    fun pauseAll() = downloader.pauseAll()

    fun resumeAll() = downloader.resumeAll()

    fun retryAllFailed() = downloader.retryAllFailed()

    fun clearQueue() {
        viewModelScope.launch {
            downloader.clearQueueAndAwait()
            _usedBytes.value = store.usedBytes()
        }
    }

    fun retry(item: OfflineItem) = downloader.retry(item)
}

/** 已缓存内容的三种浏览维度。 */
private enum class OfflineViewTab { All, Collections, Creators }

/** 待确认整组删除的目标(合集或 UP 主)。 */
private data class PendingGroupDelete(
    val isCollection: Boolean,
    val items: List<OfflineItem>,
    val totalBytes: Long,
)

/**
 * 缓存列表与下载队列管理:能暂停/继续/清空队列,按「全部 / 合集 / UP 主」浏览已缓存视频。
 *
 * **在途的排在前面**,因为它们是这一页此刻唯一会变的东西(暂停、继续、重试、清空队列);
 * 已缓存的按所选视图分组或平铺呈现:「合集」视图中无归属合集的单集直接平铺在合集卡片之后,
 * 不做人为的「其他视频」套壳。
 *
 * **封面底边的进度条只表示看到哪儿**,和稍后再看、历史记录同一个意思;正在下载或已暂停留有
 * 分片的条目在行/卡底部单独画一条 [LinearProgressIndicator],让两种进度语义互不混用。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OfflineScreen(
    items: List<OfflineItem>,
    usedBytes: Long,
    onPlay: (OfflineItem) -> Unit,
    onDelete: (OfflineItem) -> Unit,
    onRetry: (OfflineItem) -> Unit,
    /** 保留空默认以兼容未同步清理调用点的过渡期;列表头「听全部」按钮已按产品要求移除。 */
    @Suppress("UNUSED_PARAMETER")
    onListenAll: (OfflineItem) -> Unit = {},
    onPause: (OfflineItem) -> Unit = {},
    onResume: (OfflineItem) -> Unit = {},
    onPauseAll: () -> Unit = {},
    onResumeAll: () -> Unit = {},
    onRetryAllFailed: () -> Unit = {},
    onClearQueue: () -> Unit = {},
    onDeleteAll: (List<OfflineItem>) -> Unit = { list -> list.forEach(onDelete) },
    /**
     * 已选中的条目 id,null 表示不在多选态。
     */
    selectedIds: Set<String>? = null,
    onToggleSelection: (OfflineItem) -> Unit = {},
    searchQuery: String = "",
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var pendingDelete by remember { mutableStateOf<OfflineItem?>(null) }
    var pendingGroupDelete by remember { mutableStateOf<PendingGroupDelete?>(null) }
    var confirmingClearQueue by remember { mutableStateOf(false) }

    var activeTab by rememberSaveable { mutableStateOf(OfflineViewTab.All) }
    var openedSeasonId by rememberSaveable { mutableStateOf<Long?>(null) }
    var openedUpKeyId by rememberSaveable { mutableStateOf<String?>(null) }

    val layoutDirection = LocalLayoutDirection.current
    val trimmedQuery = searchQuery.trim()
    val visibleItems = remember(items, trimmedQuery) {
        if (trimmedQuery.isEmpty()) {
            items
        } else {
            items.filter {
                matchesLibraryQuery(trimmedQuery, it.title, it.partTitle, it.upName, it.bvid, it.seasonTitle)
            }
        }
    }

    val allCompleted = remember(items) { items.filter { it.status == OfflineStatus.Completed } }
    val collectionPartition = remember(allCompleted) { allCompleted.partitionByCollection() }
    val upGroups = remember(allCompleted) { allCompleted.groupByUp() }

    val activeCollection = remember(openedSeasonId, collectionPartition) {
        openedSeasonId?.let { id -> collectionPartition.collections.firstOrNull { it.seasonId == id } }
    }
    val activeUp = remember(openedUpKeyId, upGroups) {
        openedUpKeyId?.let { id -> upGroups.firstOrNull { it.id == id } }
    }
    val inSubView = activeCollection != null || activeUp != null

    // 当前打开的合集或 UP 主缓存被删空时自动退回上一级,避免停留在空子视图里。
    LaunchedEffect(openedSeasonId, activeCollection) {
        if (openedSeasonId != null && activeCollection == null) openedSeasonId = null
    }
    LaunchedEffect(openedUpKeyId, activeUp) {
        if (openedUpKeyId != null && activeUp == null) openedUpKeyId = null
    }

    // 多选态由外层 BackHandler 先接管;非多选态下按返回键退出合集/UP 主子视图。
    BackHandler(enabled = inSubView && selectedIds == null) {
        openedSeasonId = null
        openedUpKeyId = null
    }

    AdaptiveVideoGridContent(modifier = modifier) { columnCount ->
        if (items.isEmpty()) {
            EmptyState(
                message = stringResource(Res.string.offline_empty),
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            return@AdaptiveVideoGridContent
        }
        if (visibleItems.isEmpty() && !inSubView) {
            EmptyState(
                message = stringResource(Res.string.offline_search_empty),
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            return@AdaptiveVideoGridContent
        }

        val windowSize = rememberBilbyWindowSize()
        val multiColumn = columnCount > 1
        val twoColumnTransfers = windowSize.isExpanded
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
        val headerHorizontalPadding = if (multiColumn) Spacing.Tight + 2.dp else Spacing.Comfortable

        val (completed, inFlight) = remember(visibleItems) {
            visibleItems.partition { it.status == OfflineStatus.Completed }
        }
        val queueSummary = remember(inFlight) { inFlight.queueSummary() }

        val rowScope = remember(selectedIds, onPlay, onPause, onResume, onRetry, onToggleSelection, onDelete) {
            OfflineRowScope(
                selectedIds = selectedIds,
                onPlay = onPlay,
                onPause = onPause,
                onResume = onResume,
                onRetry = onRetry,
                onToggleSelection = onToggleSelection,
                onDeleteRequest = { item ->
                    // **还没开始下的那条不问。** 确认框存在的理由是"删掉就要重下几百 MB",
                    // 而排队中且未写入字节的这条取消代价是零。
                    if (item.status == OfflineStatus.Queued && item.downloadedBytes == 0L) {
                        onDelete(item)
                    } else {
                        pendingDelete = item
                    }
                },
            )
        }

        val gridState = rememberLazyGridState()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = effectiveColumns,
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = gridPadding,
            ) {
                when {
                    activeCollection != null -> {
                        val subItems = if (trimmedQuery.isEmpty()) {
                            activeCollection.items
                        } else {
                            activeCollection.items.filter {
                                matchesLibraryQuery(trimmedQuery, it.title, it.partTitle, it.upName, it.bvid)
                            }
                        }
                        item(key = "subview-collection-header", span = { GridItemSpan(maxLineSpan) }) {
                            OfflineSubViewHeaderCard(
                                overline = stringResource(Res.string.space_collection_season),
                                title = activeCollection.seasonTitle,
                                meta = stringResource(Res.string.offline_collection_episodes, activeCollection.items.size) +
                                    MetaSeparator + formatBytes(activeCollection.totalBytes),
                                avatarUrl = null,
                                actionsEnabled = selectedIds == null,
                                onBack = { openedSeasonId = null },
                                onRequestDeleteAll = {
                                    pendingGroupDelete = PendingGroupDelete(
                                        isCollection = true,
                                        items = activeCollection.items,
                                        totalBytes = activeCollection.totalBytes,
                                    )
                                },
                                modifier = Modifier
                                    .padding(horizontal = headerHorizontalPadding, vertical = Spacing.Tight)
                                    .animateItem(),
                            )
                        }
                        if (subItems.isEmpty()) {
                            item(key = "subview-search-empty", span = { GridItemSpan(maxLineSpan) }) {
                                EmptyState(
                                    message = stringResource(Res.string.offline_search_empty),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.Spacious),
                                )
                            }
                        } else {
                            offlineRows(subItems, rowScope, multiColumn)
                        }
                    }

                    activeUp != null -> {
                        val subItems = if (trimmedQuery.isEmpty()) {
                            activeUp.items
                        } else {
                            activeUp.items.filter {
                                matchesLibraryQuery(trimmedQuery, it.title, it.partTitle, it.upName, it.bvid)
                            }
                        }
                        item(key = "subview-up-header", span = { GridItemSpan(maxLineSpan) }) {
                            OfflineSubViewHeaderCard(
                                overline = stringResource(Res.string.offline_tab_creators),
                                title = activeUp.upName.ifBlank { stringResource(Res.string.offline_unknown_up) },
                                meta = stringResource(Res.string.offline_count, activeUp.items.size) +
                                    MetaSeparator + formatBytes(activeUp.totalBytes),
                                avatarUrl = activeUp.upFaceUrl,
                                actionsEnabled = selectedIds == null,
                                onBack = { openedUpKeyId = null },
                                onRequestDeleteAll = {
                                    pendingGroupDelete = PendingGroupDelete(
                                        isCollection = false,
                                        items = activeUp.items,
                                        totalBytes = activeUp.totalBytes,
                                    )
                                },
                                modifier = Modifier
                                    .padding(horizontal = headerHorizontalPadding, vertical = Spacing.Tight)
                                    .animateItem(),
                            )
                        }
                        if (subItems.isEmpty()) {
                            item(key = "subview-search-empty", span = { GridItemSpan(maxLineSpan) }) {
                                EmptyState(
                                    message = stringResource(Res.string.offline_search_empty),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.Spacious),
                                )
                            }
                        } else {
                            offlineRows(subItems, rowScope, multiColumn)
                        }
                    }

                    trimmedQuery.isNotEmpty() -> {
                        if (inFlight.isNotEmpty()) {
                            offlineTransferRows(
                                rows = inFlight,
                                scope = rowScope,
                                multiColumn = multiColumn,
                                twoColumnTransfers = twoColumnTransfers,
                                horizontalPadding = headerHorizontalPadding,
                            )
                        }
                        if (completed.isNotEmpty()) {
                            offlineRows(completed, rowScope, multiColumn)
                        }
                    }

                    else -> {
                        if (inFlight.isNotEmpty()) {
                            item(key = "queue-card", span = { GridItemSpan(maxLineSpan) }) {
                                OfflineQueueCard(
                                    summary = queueSummary,
                                    storageSummary = if (completed.isEmpty() && usedBytes > 0L) {
                                        formatBytes(usedBytes)
                                    } else {
                                        null
                                    },
                                    enabled = selectedIds == null,
                                    onPauseAll = onPauseAll,
                                    onResumeAll = onResumeAll,
                                    onRetryAllFailed = onRetryAllFailed,
                                    onRequestClearQueue = { confirmingClearQueue = true },
                                    modifier = Modifier
                                        .padding(
                                            start = headerHorizontalPadding,
                                            end = headerHorizontalPadding,
                                            top = Spacing.Tight,
                                            bottom = Spacing.Hair,
                                        )
                                        .animateItem(),
                                )
                            }
                            offlineTransferRows(
                                rows = inFlight,
                                scope = rowScope,
                                multiColumn = multiColumn,
                                twoColumnTransfers = twoColumnTransfers,
                                horizontalPadding = headerHorizontalPadding,
                            )
                        }

                        if (completed.isNotEmpty()) {
                            item(key = "completed-view-switcher", span = { GridItemSpan(maxLineSpan) }) {
                                OfflineCompletedHeader(
                                    selected = activeTab,
                                    completedCount = completed.size,
                                    usedBytes = usedBytes,
                                    multiColumn = multiColumn,
                                    onSelect = { tab ->
                                        activeTab = tab
                                        openedSeasonId = null
                                        openedUpKeyId = null
                                    },
                                    modifier = Modifier
                                        .padding(
                                            start = headerHorizontalPadding,
                                            end = headerHorizontalPadding,
                                            top = if (inFlight.isNotEmpty()) Spacing.Cozy else Spacing.Tight,
                                            bottom = Spacing.Tight,
                                        )
                                        .animateItem(),
                                )
                            }

                            when (activeTab) {
                                OfflineViewTab.All -> {
                                    offlineRows(completed, rowScope, multiColumn)
                                }

                                OfflineViewTab.Collections -> {
                                    if (collectionPartition.collections.isNotEmpty()) {
                                        items(
                                            items = collectionPartition.collections,
                                            key = { "collection-${it.seasonId}" },
                                        ) { group ->
                                            if (multiColumn) {
                                                OfflineCollectionGridCard(
                                                    group = group,
                                                    onClick = { openedSeasonId = group.seasonId },
                                                    onRequestDelete = {
                                                        pendingGroupDelete = PendingGroupDelete(
                                                            isCollection = true,
                                                            items = group.items,
                                                            totalBytes = group.totalBytes,
                                                        )
                                                    },
                                                    modifier = Modifier
                                                        .padding(Spacing.Hair)
                                                        .animateItem(),
                                                )
                                            } else {
                                                OfflineCollectionRow(
                                                    group = group,
                                                    onClick = { openedSeasonId = group.seasonId },
                                                    onRequestDelete = {
                                                        pendingGroupDelete = PendingGroupDelete(
                                                            isCollection = true,
                                                            items = group.items,
                                                            totalBytes = group.totalBytes,
                                                        )
                                                    },
                                                    modifier = Modifier.animateItem(),
                                                )
                                            }
                                        }
                                    }
                                    // 无归属合集的单集直接平铺在合集卡片之后,不套「其他视频」分组壳。
                                    if (collectionPartition.ungrouped.isNotEmpty()) {
                                        if (collectionPartition.collections.isNotEmpty()) {
                                            item(key = "collections-ungrouped-gap", span = { GridItemSpan(maxLineSpan) }) {
                                                Spacer(modifier = Modifier.height(Spacing.Tight))
                                            }
                                        }
                                        offlineRows(collectionPartition.ungrouped, rowScope, multiColumn)
                                    }
                                }

                                OfflineViewTab.Creators -> {
                                    items(
                                        items = upGroups,
                                        key = { "up-${it.id}" },
                                    ) { group ->
                                        OfflineUpCard(
                                            group = group,
                                            onClick = { openedUpKeyId = group.id },
                                            onRequestDelete = {
                                                pendingGroupDelete = PendingGroupDelete(
                                                    isCollection = false,
                                                    items = group.items,
                                                    totalBytes = group.totalBytes,
                                                )
                                            },
                                            modifier = Modifier
                                                .padding(
                                                    horizontal = if (multiColumn) Spacing.Hair else Spacing.Comfortable,
                                                    vertical = Spacing.Hair,
                                                )
                                                .animateItem(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ListScrollbar(gridState, Modifier.align(Alignment.CenterEnd))
        }
    }

    // 单条删除确认:文件删掉之后要重新下一次几百 MB。
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.offline_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.offline_delete_confirm_message)) },
            confirmButton = {
                DestructiveConfirmButton(
                    text = stringResource(Res.string.action_delete),
                    onClick = {
                        pendingDelete = null
                        onDelete(target)
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    // 清空下载队列确认:仅移除未完成任务与临时分片,不影响已完成视频。
    if (confirmingClearQueue) {
        val incompleteCount = items.count { it.status != OfflineStatus.Completed }
        AlertDialog(
            onDismissRequest = { confirmingClearQueue = false },
            title = { Text(stringResource(Res.string.offline_queue_clear_confirm_title)) },
            text = { Text(stringResource(Res.string.offline_queue_clear_confirm_message, incompleteCount)) },
            confirmButton = {
                DestructiveConfirmButton(
                    text = stringResource(Res.string.offline_queue_clear),
                    onClick = {
                        confirmingClearQueue = false
                        onClearQueue()
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmingClearQueue = false }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }

    // 整组(合集或 UP 主)删除确认。
    pendingGroupDelete?.let { target ->
        val sizeText = formatBytes(target.totalBytes)
        AlertDialog(
            onDismissRequest = { pendingGroupDelete = null },
            title = {
                Text(
                    stringResource(
                        if (target.isCollection) {
                            Res.string.offline_delete_collection_confirm_title
                        } else {
                            Res.string.offline_delete_creator_confirm_title
                        },
                    ),
                )
            },
            text = {
                Text(
                    stringResource(
                        Res.string.offline_delete_group_confirm_message,
                        target.items.size,
                        sizeText,
                    ),
                )
            },
            confirmButton = {
                DestructiveConfirmButton(
                    text = stringResource(Res.string.action_delete),
                    onClick = {
                        pendingGroupDelete = null
                        onDeleteAll(target.items)
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { pendingGroupDelete = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OfflineQueueCard(
    summary: OfflineQueueSummary,
    storageSummary: String?,
    enabled: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onRetryAllFailed: () -> Unit,
    onRequestClearQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val breakdownParts = buildList {
        if (summary.activeCount > 0) {
            add(stringResource(Res.string.offline_queue_active_count, summary.activeCount))
        }
        if (summary.pausedCount > 0) {
            add(stringResource(Res.string.offline_queue_paused_count, summary.pausedCount))
        }
        if (summary.failedCount > 0) {
            add(stringResource(Res.string.offline_queue_failed_count, summary.failedCount))
        }
        if (!storageSummary.isNullOrBlank()) {
            add(storageSummary)
        }
    }
    val breakdown = breakdownParts.joinToString(MetaSeparator)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = QueueHeaderVerticalPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = Spacing.Cozy),
            ) {
                Text(
                    text = stringResource(Res.string.offline_queue_title, summary.totalCount),
                    style = MaterialTheme.typography.titleSmallEmphasized.tabular,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                if (breakdown.isNotEmpty()) {
                    Text(
                        text = MetaSeparator + breakdown,
                        style = MaterialTheme.typography.bodyMedium.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (summary.hasActive) {
                    FilledTonalButton(
                        onClick = onPauseAll,
                        enabled = enabled,
                        contentPadding = QueueActionPadding,
                    ) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                        Spacer(Modifier.width(Spacing.Hair))
                        Text(
                            stringResource(Res.string.offline_queue_pause_all),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                if (summary.hasPaused) {
                    FilledTonalButton(
                        onClick = onResumeAll,
                        enabled = enabled,
                        contentPadding = QueueActionPadding,
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                        Spacer(Modifier.width(Spacing.Hair))
                        Text(
                            stringResource(Res.string.offline_queue_resume_all),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                if (summary.hasFailed) {
                    FilledTonalButton(
                        onClick = onRetryAllFailed,
                        enabled = enabled,
                        contentPadding = QueueActionPadding,
                    ) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                        Spacer(Modifier.width(Spacing.Hair))
                        Text(
                            stringResource(Res.string.offline_queue_retry_failed),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
                TextButton(
                    onClick = onRequestClearQueue,
                    enabled = enabled,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    contentPadding = QueueActionPadding,
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                    Spacer(Modifier.width(Spacing.Hair))
                    Text(
                        stringResource(Res.string.offline_queue_clear),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OfflineCompletedHeader(
    selected: OfflineViewTab,
    completedCount: Int,
    usedBytes: Long,
    multiColumn: Boolean,
    onSelect: (OfflineViewTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = OfflineViewTab.entries
    val labels = listOf(
        stringResource(Res.string.offline_tab_all),
        stringResource(Res.string.offline_tab_collections),
        stringResource(Res.string.offline_tab_creators),
    )
    val interactionSources = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    val tonalColors = FilledTonalToggleButtonDefaults.colors()
    val storageSummary = stringResource(Res.string.offline_count, completedCount) +
        MetaSeparator + formatBytes(usedBytes)

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        ButtonGroup(
            overflowIndicator = { menuState ->
                IconButton(onClick = { menuState.show() }) {
                    Icon(
                        Icons.Outlined.MoreHoriz,
                        contentDescription = stringResource(Res.string.menu_item_actions),
                    )
                }
            },
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
            modifier = if (multiColumn) {
                Modifier
                    .padding(end = Spacing.Cozy)
                    .widthIn(max = ViewSwitcherMaxWidth)
                    .fillMaxWidth()
                    .selectableGroup()
            } else {
                Modifier
                    .fillMaxWidth()
                    .selectableGroup()
            },
        ) {
            options.forEachIndexed { index, tab ->
                val label = labels[index]
                val interactionSource = interactionSources[index]
                customItem(
                    buttonGroupContent = {
                        ToggleButton(
                            checked = tab == selected,
                            onCheckedChange = { onSelect(tab) },
                            shapes = when (index) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                            colors = tonalColors,
                            contentPadding = PaddingValues(horizontal = Spacing.Cozy),
                            interactionSource = interactionSource,
                            modifier = Modifier
                                .weight(1f)
                                .animateWidth(interactionSource)
                                .semantics { role = Role.Tab },
                        ) {
                            Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    menuContent = { menuState ->
                        DropdownMenuItem(
                            onClick = {
                                menuState.dismiss()
                                onSelect(tab)
                            },
                            text = { Text(label) },
                            shape = menuItemShape(index, options.size),
                        )
                    },
                )
            }
        }

        Text(
            text = storageSummary,
            style = MaterialTheme.typography.bodyMedium.tabular,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OfflineSubViewHeaderCard(
    overline: String,
    title: String,
    meta: String,
    avatarUrl: String?,
    actionsEnabled: Boolean,
    onBack: () -> Unit,
    onRequestDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    Surface(
        color = colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.Hair, end = Spacing.Cozy, top = Spacing.Tight, bottom = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.action_back),
                    tint = colorScheme.onSurface,
                )
            }
            if (avatarUrl != null) {
                OfflineCreatorAvatar(url = avatarUrl, size = Dimens.AvatarRow)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2),
            ) {
                Text(
                    text = overline,
                    style = MaterialTheme.typography.labelSmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium.tabular,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onRequestDeleteAll,
                enabled = actionsEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = colorScheme.error),
                contentPadding = QueueActionPadding,
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(Dimens.IconInline),
                )
                Spacer(Modifier.width(Spacing.Hair))
                Text(stringResource(Res.string.action_delete), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** 16:10 叠层合集封面(阶梯文件夹唇边 + 16:10 封面 + 右下角等宽集数胶囊),对齐 CollectionsTab / FavFolderCover。 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OfflineCollectionCover(
    coverUrl: String,
    episodeCount: Int,
    cornerShape: Shape,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val lipShape = MaterialTheme.shapes.small.copy(
        bottomStart = CornerSize(0.dp),
        bottomEnd = CornerSize(0.dp),
    )
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .padding(horizontal = Spacing.Cozy)
                .fillMaxWidth()
                .height(FolderLipHeight)
                .clip(lipShape)
                .background(colorScheme.surfaceContainerHigh),
        )
        Spacer(modifier = Modifier.height(FolderLipGap))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(CoverAspectRatio)
                .clip(cornerShape)
                .background(colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl.isNotBlank()) {
                BiliAsyncImage(
                    url = coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                )
            }
            val badgeText = stringResource(Res.string.space_collection_season) + MetaSeparator + episodeCount
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmallEmphasized.tabular,
                color = FixedColors.OnMedia,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.Hair + Spacing.Hair / 2)
                    .background(FixedColors.ScrimOnMedia, MaterialTheme.shapes.extraSmall)
                    .padding(horizontal = Spacing.Hair + Spacing.Hair / 2, vertical = Spacing.Hair / 2),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun OfflineCollectionGridCard(
    group: OfflineCollectionGroup,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shapes = MaterialTheme.shapes
    val subtitle = stringResource(Res.string.offline_collection_episodes, group.items.size) +
        MetaSeparator + formatBytes(group.totalBytes)

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        modifier = modifier,
        menu = { close ->
            DropdownMenuItem(
                onClick = {
                    close()
                    onRequestDelete()
                },
                text = { Text(stringResource(Res.string.offline_delete_collection_action)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                shape = menuItemShape(0, 1),
            )
        },
    ) { openMenu ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .interactiveCard(
                    shape = shapes.largeIncreased,
                    baseColor = colorScheme.surfaceContainer,
                    hoverColor = lerp(colorScheme.surfaceContainer, colorScheme.surfaceContainerHigh, 0.6f),
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                )
                .padding(Spacing.Tight),
        ) {
            OfflineCollectionCover(
                coverUrl = group.coverUrl,
                episodeCount = group.items.size,
                cornerShape = shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = group.seasonTitle,
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = colorScheme.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    top = Spacing.Tight,
                    start = Spacing.Hair / 2,
                    end = Spacing.Hair / 2,
                ),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall.tabular,
                color = colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(
                    top = Spacing.Hair / 2,
                    start = Spacing.Hair / 2,
                    end = Spacing.Hair / 2,
                    bottom = Spacing.Hair / 2,
                ),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun OfflineCollectionRow(
    group: OfflineCollectionGroup,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val subtitle = stringResource(Res.string.offline_collection_episodes, group.items.size) +
        MetaSeparator + formatBytes(group.totalBytes)

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        modifier = modifier,
        menu = { close ->
            DropdownMenuItem(
                onClick = {
                    close()
                    onRequestDelete()
                },
                text = { Text(stringResource(Res.string.offline_delete_collection_action)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                shape = menuItemShape(0, 1),
            )
        },
    ) { openMenu ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .interactiveCard(
                    shape = MaterialTheme.shapes.large,
                    baseColor = Color.Transparent,
                    hoverColor = colorScheme.surfaceContainer,
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                )
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        ) {
            OfflineCollectionCover(
                coverUrl = group.coverUrl,
                episodeCount = group.items.size,
                cornerShape = RoundedCornerShape(CoverCornerRadius),
                modifier = Modifier.width(Dimens.ListCoverWidth),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                Text(
                    text = group.seasonTitle,
                    style = MaterialTheme.typography.bodyLargeEmphasized,
                    color = colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.tabular,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun OfflineUpCard(
    group: OfflineUpGroup,
    onClick: () -> Unit,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val displayName = group.upName.ifBlank { stringResource(Res.string.offline_unknown_up) }
    val subtitle = stringResource(Res.string.offline_count, group.items.size) +
        MetaSeparator + formatBytes(group.totalBytes)

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        modifier = modifier,
        menu = { close ->
            DropdownMenuItem(
                onClick = {
                    close()
                    onRequestDelete()
                },
                text = { Text(stringResource(Res.string.offline_delete_creator_action)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                shape = menuItemShape(0, 1),
            )
        },
    ) { openMenu ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .interactiveCard(
                    shape = MaterialTheme.shapes.large,
                    baseColor = colorScheme.surfaceContainer,
                    hoverColor = lerp(colorScheme.surfaceContainer, colorScheme.surfaceContainerHigh, 0.6f),
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                )
                .padding(horizontal = Spacing.Cozy, vertical = Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        ) {
            OfflineCreatorAvatar(url = group.upFaceUrl, size = Dimens.AvatarStack)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2),
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelMedium.tabular,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OfflineCreatorAvatar(
    url: String,
    size: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (url.isNotBlank()) {
        Avatar(url = url, size = size, modifier = modifier)
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.55f),
            )
        }
    }
}

/** 两节共用的行为,打包成一份传给 [offlineRows],免得每节各抄一遍这几个回调。 */
@Stable
private class OfflineRowScope(
    val selectedIds: Set<String>?,
    val onPlay: (OfflineItem) -> Unit,
    val onPause: (OfflineItem) -> Unit,
    val onResume: (OfflineItem) -> Unit,
    val onRetry: (OfflineItem) -> Unit,
    val onToggleSelection: (OfflineItem) -> Unit,
    val onDeleteRequest: (OfflineItem) -> Unit,
)

private fun LazyGridScope.offlineTransferRows(
    rows: List<OfflineItem>,
    scope: OfflineRowScope,
    multiColumn: Boolean,
    twoColumnTransfers: Boolean,
    horizontalPadding: Dp,
) {
    if (twoColumnTransfers && rows.size >= 2) {
        val pairs = rows.chunked(2)
        items(
            items = pairs,
            key = { pair -> "transfer-pair-${pair.joinToString("-") { it.id }}" },
            span = { GridItemSpan(maxLineSpan) },
        ) { pair ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = Spacing.Hair)
                    .animateItem(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                OfflineTransferCard(
                    item = pair[0],
                    scope = scope,
                    multiColumn = multiColumn,
                    modifier = Modifier.weight(1f),
                )
                if (pair.size > 1) {
                    OfflineTransferCard(
                        item = pair[1],
                        scope = scope,
                        multiColumn = multiColumn,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    } else {
        items(
            items = rows,
            key = { it.id },
            span = { GridItemSpan(maxLineSpan) },
        ) { item ->
            OfflineTransferCard(
                item = item,
                scope = scope,
                multiColumn = multiColumn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding, vertical = Spacing.Hair)
                    .animateItem(),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun OfflineTransferCard(
    item: OfflineItem,
    scope: OfflineRowScope,
    multiColumn: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val selecting = scope.selectedIds != null
    val isSelected = scope.selectedIds != null && item.id in scope.selectedIds
    val haptics = LocalHapticFeedback.current
    val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    val onCardClick: () -> Unit = {
        when {
            selecting -> scope.onToggleSelection(item)
            item.status == OfflineStatus.Failed -> scope.onRetry(item)
            item.status == OfflineStatus.Paused -> scope.onResume(item)
            else -> scope.onPlay(item)
        }
    }
    val onCardLongClick: () -> Unit = {
        if (!selecting) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.onToggleSelection(item)
    }

    val subtitle = listOfNotNull(
        item.upName.takeIf { it.isNotBlank() },
        item.partTitle.takeIf { it.isNotBlank() },
        item.qualityLabel.takeIf { it.isNotBlank() },
    ).joinToString(MetaSeparator)

    val byteSummary = if (item.totalBytes > 0L) {
        "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
    } else {
        null
    }
    val statusText = when (item.status) {
        OfflineStatus.Queued -> stringResource(Res.string.offline_status_queued)
        OfflineStatus.Running -> listOfNotNull(
            item.progress
                ?.let { stringResource(Res.string.offline_status_running, (it * 100).toInt()) }
                ?: stringResource(Res.string.offline_status_running_unknown),
            byteSummary,
            item.speedBytesPerSecond.takeIf { it > 0L }?.let { formatSpeed(it) },
        ).joinToString(MetaSeparator)
        OfflineStatus.Paused -> listOfNotNull(
            stringResource(Res.string.offline_status_paused),
            item.progress?.takeIf { it > 0f }?.let { "${(it * 100).toInt()}%" },
            byteSummary.takeIf { item.downloadedBytes > 0L },
        ).joinToString(MetaSeparator)
        OfflineStatus.Failed -> item.error ?: stringResource(Res.string.offline_status_failed)
        OfflineStatus.Completed -> formatBytes(item.effectiveBytes)
    }
    val statusColor = if (item.status == OfflineStatus.Failed) {
        colorScheme.error
    } else {
        colorScheme.onSurfaceVariant
    }
    val showDownloadBar = item.status == OfflineStatus.Running ||
        (item.status == OfflineStatus.Paused && (item.progress ?: 0f) > 0f)

    val selectionModifier = if (selecting) {
        Modifier.semantics { this.selected = isSelected }
    } else {
        Modifier
    }

    val cardModifier = Modifier
        .fillMaxWidth()
        .interactiveCard(
            shape = MaterialTheme.shapes.large,
            baseColor = if (isSelected) colorScheme.secondaryContainer else colorScheme.surfaceContainerLow,
            hoverColor = if (isSelected) colorScheme.secondaryContainer else colorScheme.surfaceContainer,
        )
        .combinedClickable(
            role = Role.Button,
            onClick = onCardClick,
            onLongClick = onCardLongClick,
        )
        .then(selectionModifier)

    val cardBody: @Composable () -> Unit = {
        if (multiColumn) {
            Row(
                modifier = cardModifier.padding(
                    start = Spacing.Cozy,
                    end = Spacing.Tight,
                    top = Spacing.Cozy,
                    bottom = Spacing.Cozy,
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
            ) {
                VideoCover(
                    url = item.coverUrl,
                    durationText = if (item.durationSeconds > 0) formatDurationSeconds(item.durationSeconds) else "",
                    typeBadge = chargingBadgeText(item.chargingOnly),
                    cornerRadius = TransferCoverCornerRadius,
                    modifier = Modifier.width(TransferCoverWidth),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLargeEmphasized,
                        color = colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (showDownloadBar) {
                        DownloadProgressBar(
                            progressProvider = { item.progress },
                            determinate = item.progress != null,
                            paused = item.status == OfflineStatus.Paused,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = Spacing.Hair / 2),
                        )
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium.tabular,
                        color = statusColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                AnimatedContent(
                    targetState = selecting,
                    transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
                    label = "offline-transfer-actions",
                ) { inSelection ->
                    if (inSelection) {
                        Box(
                            modifier = Modifier.size(Dimens.MinTouchTarget),
                            contentAlignment = Alignment.Center,
                        ) {
                            Checkbox(checked = isSelected, onCheckedChange = null)
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OfflineTransferStateButton(item = item, scope = scope)
                            val deleteLabel = stringResource(Res.string.offline_delete, item.title)
                            BilbyTooltip(text = deleteLabel) {
                                IconButton(onClick = { scope.onDeleteRequest(item) }) {
                                    Icon(
                                        Icons.Outlined.DeleteOutline,
                                        contentDescription = deleteLabel,
                                        tint = colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = cardModifier.padding(
                    start = Spacing.Cozy,
                    end = Spacing.Hair,
                    top = Spacing.Cozy,
                    bottom = Spacing.Tight,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
                ) {
                    VideoCover(
                        url = item.coverUrl,
                        durationText = if (item.durationSeconds > 0) formatDurationSeconds(item.durationSeconds) else "",
                        typeBadge = chargingBadgeText(item.chargingOnly),
                        cornerRadius = TransferCoverCornerRadius,
                        modifier = Modifier.width(TransferCoverCompactWidth),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair / 2),
                    ) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.bodyLargeEmphasized,
                            color = colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    AnimatedContent(
                        targetState = selecting,
                        transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
                        label = "offline-transfer-top-action",
                    ) { inSelection ->
                        if (inSelection) {
                            Box(
                                modifier = Modifier.size(Dimens.MinTouchTarget),
                                contentAlignment = Alignment.Center,
                            ) {
                                Checkbox(checked = isSelected, onCheckedChange = null)
                            }
                        } else {
                            OfflineTransferStateButton(item = item, scope = scope)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = if (selecting) Spacing.Tight else 0.dp),
                        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                    ) {
                        if (showDownloadBar) {
                            DownloadProgressBar(
                                progressProvider = { item.progress },
                                determinate = item.progress != null,
                                paused = item.status == OfflineStatus.Paused,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelMedium.tabular,
                            color = statusColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!selecting) {
                        val deleteLabel = stringResource(Res.string.offline_delete, item.title)
                        BilbyTooltip(text = deleteLabel) {
                            IconButton(onClick = { scope.onDeleteRequest(item) }) {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = deleteLabel,
                                    tint = colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (selecting) {
        Box(modifier = modifier) {
            cardBody()
        }
    } else {
        ContextMenuBox(
            onClick = null,
            menuLabel = stringResource(Res.string.menu_item_actions),
            modifier = modifier,
            menu = { close -> OfflineItemMenuContent(item = item, scope = scope, close = close) },
        ) { _ ->
            cardBody()
        }
    }
}

@Composable
private fun OfflineTransferStateButton(
    item: OfflineItem,
    scope: OfflineRowScope,
) {
    when (item.status) {
        OfflineStatus.Queued, OfflineStatus.Running -> {
            val pauseLabel = stringResource(Res.string.player_pause)
            BilbyTooltip(text = pauseLabel) {
                IconButton(onClick = { scope.onPause(item) }) {
                    Icon(
                        Icons.Filled.Pause,
                        contentDescription = pauseLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OfflineStatus.Paused -> {
            val resumeLabel = stringResource(Res.string.offline_action_resume)
            BilbyTooltip(text = resumeLabel) {
                IconButton(onClick = { scope.onResume(item) }) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = resumeLabel,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        OfflineStatus.Failed -> {
            val retryLabel = stringResource(Res.string.action_retry)
            BilbyTooltip(text = retryLabel) {
                IconButton(onClick = { scope.onRetry(item) }) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = retryLabel,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        OfflineStatus.Completed -> Unit
    }
}

@Composable
private fun ColumnScope.OfflineItemMenuContent(
    item: OfflineItem,
    scope: OfflineRowScope,
    close: () -> Unit,
) {
    val hasStateAction = item.status != OfflineStatus.Completed
    val leadingCount = if (hasStateAction) 3 else 2
    val totalCount = leadingCount + videoCommonMenuItemCount(
        bvid = item.bvid,
        title = item.title,
    )
    var nextIdx = 0
    if (hasStateAction) {
        when (item.status) {
            OfflineStatus.Queued, OfflineStatus.Running -> {
                DropdownMenuItem(
                    onClick = {
                        close()
                        scope.onPause(item)
                    },
                    text = { Text(stringResource(Res.string.player_pause)) },
                    leadingIcon = { Icon(Icons.Filled.Pause, contentDescription = null) },
                    shape = menuItemShape(nextIdx++, totalCount),
                )
            }
            OfflineStatus.Paused -> {
                DropdownMenuItem(
                    onClick = {
                        close()
                        scope.onResume(item)
                    },
                    text = { Text(stringResource(Res.string.offline_action_resume)) },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    shape = menuItemShape(nextIdx++, totalCount),
                )
            }
            OfflineStatus.Failed -> {
                DropdownMenuItem(
                    onClick = {
                        close()
                        scope.onRetry(item)
                    },
                    text = { Text(stringResource(Res.string.action_retry)) },
                    leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    shape = menuItemShape(nextIdx++, totalCount),
                )
            }
            OfflineStatus.Completed -> Unit
        }
    }
    DropdownMenuItem(
        onClick = {
            close()
            scope.onToggleSelection(item)
        },
        text = { Text(stringResource(Res.string.action_select)) },
        leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
        shape = menuItemShape(nextIdx++, totalCount),
    )
    DropdownMenuItem(
        onClick = {
            close()
            scope.onDeleteRequest(item)
        },
        text = { Text(stringResource(Res.string.action_delete)) },
        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
        shape = menuItemShape(nextIdx++, totalCount),
    )
    VideoCommonMenuItems(
        bvid = item.bvid,
        title = item.title,
        close = close,
        coverUrl = item.coverUrl,
        upName = item.upName,
        durationSeconds = item.durationSeconds,
        startIndex = leadingCount,
        totalCount = totalCount,
    )
}

private fun LazyGridScope.offlineRows(
    rows: List<OfflineItem>,
    scope: OfflineRowScope,
    multiColumn: Boolean,
) {
    items(rows, key = { it.id }) { item ->
        OfflineRow(item, scope, multiColumn, Modifier.animateItem())
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OfflineRow(
    item: OfflineItem,
    scope: OfflineRowScope,
    multiColumn: Boolean,
    modifier: Modifier = Modifier,
) {
    val selecting = scope.selectedIds != null
    val isSelected = scope.selectedIds != null && item.id in scope.selectedIds
    val haptics = LocalHapticFeedback.current
    val rowUi = item.toRowUi()
    // 选中态淡入,不是跳变:取 effects 档 —— 变的是颜色,没有任何东西在移动。
    val rowColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "offline-row-selected",
    )

    val onCardClick: () -> Unit = {
        when {
            selecting -> scope.onToggleSelection(item)
            item.status == OfflineStatus.Failed -> scope.onRetry(item)
            item.status == OfflineStatus.Paused -> scope.onResume(item)
            else -> scope.onPlay(item)
        }
    }
    val onCardLongClick: () -> Unit = {
        if (!selecting) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.onToggleSelection(item)
    }

    val trailingOrOverflow: @Composable () -> Unit = {
        val fadeSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
        AnimatedContent(
            targetState = selecting,
            transitionSpec = { fadeIn(fadeSpec) togetherWith fadeOut(fadeSpec) },
            label = "offline-row-trailing",
        ) { inSelection ->
            if (inSelection) {
                Box(modifier = Modifier.padding(horizontal = if (multiColumn) Spacing.Tight else 0.dp)) {
                    Checkbox(checked = isSelected, onCheckedChange = null)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OfflineTransferStateButton(item = item, scope = scope)
                    val deleteLabel = stringResource(Res.string.offline_delete, item.title)
                    BilbyTooltip(text = deleteLabel) {
                        IconButton(onClick = { scope.onDeleteRequest(item) }) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = deleteLabel,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    val showDownloadBar = item.status == OfflineStatus.Running ||
        (item.status == OfflineStatus.Paused && (item.progress ?: 0f) > 0f)
    val selectionModifier = if (selecting) {
        Modifier.semantics { this.selected = isSelected }
    } else {
        Modifier
    }
    val itemBody: @Composable () -> Unit = {
        if (multiColumn) {
            Column(
                modifier = Modifier
                    .padding(2.dp)
                    .clip(MaterialTheme.shapes.large)
                    .drawBehind { drawRect(rowColor) }
                    .then(selectionModifier),
            ) {
                VideoGridCard(
                    ui = rowUi,
                    onClick = onCardClick,
                    onLongClick = onCardLongClick,
                    upAvatarUrl = item.upFaceUrl.takeIf { it.isNotBlank() },
                    overflow = trailingOrOverflow,
                )
                if (showDownloadBar) {
                    DownloadProgressBar(
                        progressProvider = { item.progress },
                        determinate = item.progress != null,
                        paused = item.status == OfflineStatus.Paused,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.Tight, end = Spacing.Tight, bottom = Spacing.Tight),
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .drawBehind { drawRect(rowColor) }
                    .then(selectionModifier),
            ) {
                VideoRow(
                    item = rowUi,
                    onClick = onCardClick,
                    onLongClick = onCardLongClick,
                    trailing = { trailingOrOverflow() },
                )
                if (showDownloadBar) {
                    DownloadProgressBar(
                        progressProvider = { item.progress },
                        determinate = item.progress != null,
                        paused = item.status == OfflineStatus.Paused,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.Comfortable, end = Spacing.Comfortable, bottom = Spacing.Tight),
                    )
                }
            }
        }
    }

    if (selecting) {
        Box(modifier = modifier) {
            itemBody()
        }
    } else {
        ContextMenuBox(
            onClick = null,
            menuLabel = stringResource(Res.string.menu_item_actions),
            modifier = modifier,
            menu = { close -> OfflineItemMenuContent(item = item, scope = scope, close = close) },
        ) { _ ->
            itemBody()
        }
    }
}

@Composable
private fun DownloadProgressBar(
    progressProvider: () -> Float?,
    determinate: Boolean,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    val reducedMotion = rememberReducedMotion()
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val indicatorColor = if (paused) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.primary
    }
    if (determinate || reducedMotion) {
        LinearProgressIndicator(
            progress = { progressProvider() ?: 0f },
            color = indicatorColor,
            trackColor = trackColor,
            modifier = modifier,
        )
    } else {
        LinearProgressIndicator(
            color = indicatorColor,
            trackColor = trackColor,
            modifier = modifier,
        )
    }
}

/**
 * 缓存条目 → 列表行。
 *
 * **多 P 的那一 P 排在状态行最前面。** 同一条视频缓了两个 P 就是两行同名条目,谁是谁只有
 * 分 P 名答得上来。不接在标题后面:标题两行就截断,长标题会把分 P 名截掉,而它正是这两行
 * 之间唯一的区别。
 */
@Composable
fun OfflineItem.toRowUi(): VideoRowUi {
    val durationMillis = durationSeconds * 1000
    val watchedToEnd = isWatchedToEnd(watchedPositionMillis, durationMillis)
    val statusText = when (status) {
        OfflineStatus.Completed -> listOfNotNull(
            when {
                watchedToEnd -> stringResource(Res.string.history_finished)
                watchedPositionMillis > 0 ->
                    stringResource(Res.string.history_progress, formatDurationMillis(watchedPositionMillis))
                else -> null
            },
            qualityLabel.takeIf { it.isNotEmpty() },
            stringResource(Res.string.offline_with_danmaku).takeIf { hasDanmaku },
            formatBytes(downloadedBytes),
        ).joinToString(MetaSeparator)
        OfflineStatus.Queued -> stringResource(Res.string.offline_status_queued)
        // 速度和百分比一起给:并发之后同时有几条在动,只有百分比的话看不出带宽分给了谁,
        // 也看不出某一条是不是已经停住了。速度为 0 的那一瞬(刚起步)不显示,别闪一下 0。
        OfflineStatus.Running -> listOfNotNull(
            progress
                ?.let { stringResource(Res.string.offline_status_running, (it * 100).toInt()) }
                ?: stringResource(Res.string.offline_status_running_unknown),
            speedBytesPerSecond.takeIf { it > 0 }?.let { formatSpeed(it) },
        ).joinToString(MetaSeparator)
        OfflineStatus.Paused -> listOfNotNull(
            stringResource(Res.string.offline_status_paused),
            progress?.takeIf { it > 0f }?.let { "${(it * 100).toInt()}%" },
        ).joinToString(MetaSeparator)
        OfflineStatus.Failed -> error ?: stringResource(Res.string.offline_status_failed)
    }
    return VideoRowUi(
        title = title,
        coverUrl = coverUrl,
        durationText = if (durationSeconds > 0) formatDurationSeconds(durationSeconds) else "",
        upName = upName.takeIf { it.isNotEmpty() },
        dateText = formatRelativeTime(publishedAtEpochSeconds),
        meta = listOfNotNull(partTitle.takeIf { it.isNotBlank() }, statusText).joinToString(MetaSeparator),
        typeBadge = chargingBadgeText(chargingOnly),
        // 只有下完的才有"看到哪儿"可言。
        progressFraction = when {
            status != OfflineStatus.Completed -> null
            watchedToEnd -> 1f
            durationMillis > 0 && watchedPositionMillis > 0 -> watchedPositionMillis.toFloat() / durationMillis
            else -> null
        },
    )
}

/**
 * 下载速度。**这里要 KB 那一档**,和 [formatBytes] 相反:手机网络下几百 KB/s 是常态,
 * 写成 "0.4 MB/s" 就把速度之间的差别压没了 —— 而速度这一栏存在的意义正是看出差别。
 */
@Composable
private fun formatSpeed(bytesPerSecond: Long): String {
    val kb = bytesPerSecond / 1024.0
    return if (kb >= 1024) {
        stringResource(Res.string.speed_mb, kb / 1024)
    } else {
        stringResource(Res.string.speed_kb, kb.roundToInt())
    }
}

/**
 * 字节数。**没有 KB 这一档** —— 缓存的东西全是视频,最小的一条也在几十 MB,给它一个
 * "12345 KB" 只是把同一个数字写长。
 *
 * internal:「我的」页缓存那一节的标题行也显示已用空间。
 */
@Composable
internal fun formatBytes(bytes: Long): String {
    val mb = bytes / 1024.0 / 1024
    return if (mb >= 1024) {
        stringResource(Res.string.size_gb, mb / 1024)
    } else {
        stringResource(Res.string.size_mb, mb)
    }
}

private val FolderLipHeight = 6.dp
private val FolderLipGap = 2.dp
private val ViewSwitcherMaxWidth = 360.dp
private val QueueHeaderVerticalPadding = 10.dp
private val QueueActionPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair)
private val TransferCoverWidth = 112.dp
private val TransferCoverCompactWidth = 96.dp
private val TransferCoverCornerRadius = 8.dp
