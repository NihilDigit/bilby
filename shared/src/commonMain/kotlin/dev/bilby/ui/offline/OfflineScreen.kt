package dev.bilby.ui.offline

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.bilby.formatDurationMillis
import dev.bilby.formatDurationSeconds
import dev.bilby.offline.OfflineDownloader
import dev.bilby.offline.OfflineItem
import dev.bilby.offline.OfflineStatus
import dev.bilby.offline.OfflineStore
import dev.bilby.player.isWatchedToEnd
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.AdaptiveVideoGridContent
import dev.bilby.ui.components.ContextMenuBox
import dev.bilby.ui.components.EmptyState
import dev.bilby.ui.components.ListScrollbar
import dev.bilby.ui.components.MetaSeparator
import dev.bilby.ui.components.SectionHeader
import dev.bilby.ui.components.VideoCommonMenuItems
import dev.bilby.ui.components.VideoGridCard
import dev.bilby.ui.components.VideoRow
import dev.bilby.ui.components.VideoRowUi
import dev.bilby.ui.components.chargingBadgeText
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.components.videoCommonMenuItemCount
import dev.bilby.ui.formatRelativeTime
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 已缓存的内容。
 *
 * 条目的真相在 [OfflineDownloader.items] 上(它同时盖着盘上已有的和正在下的那条),这里只
 * 多算一个已用空间 —— 那是一次目录遍历,不值得每次进度更新都重算。
 */
class OfflineViewModel(
    private val downloader: OfflineDownloader,
    private val store: OfflineStore,
) : ViewModel() {

    val items: StateFlow<List<OfflineItem>> = downloader.items

    private val _usedBytes = MutableStateFlow(0L)
    val usedBytes: StateFlow<Long> = _usedBytes.asStateFlow()

    init {
        refreshUsedBytes()
    }

    fun delete(item: OfflineItem) {
        downloader.delete(item)
        refreshUsedBytes()
    }

    /**
     * 批量删。逐条走 [OfflineDownloader.delete] 而不是另开一条批量路径:那条要先掐掉正在下的
     * 那些协程再删文件,顺序反了会在盘上留下查无此人的文件(见 delete 的说明)。已用空间只在
     * 全部删完之后算一次。
     */
    fun deleteAll(items: List<OfflineItem>) {
        items.forEach(downloader::delete)
        refreshUsedBytes()
    }

    fun retry(item: OfflineItem) = downloader.retry(item)

    private fun refreshUsedBytes() {
        viewModelScope.launch { _usedBytes.value = store.usedBytes() }
    }
}

/**
 * 缓存列表:能看、能播、能删。分两节 —— 正在缓存的在前,已缓存的在后,顶部提供统一概览控制卡。
 *
 * **在途的排在前面**,因为它们是这一页此刻唯一会变的东西,也是唯一可能需要人做点什么的
 * (失败了要重试);已缓存的是一份静止的存货。
 *
 * **封面底边的进度条只表示看到哪儿**,和稍后再看、历史记录同一个意思;正在下载的条目在行/卡底部单独画一条
 * [LinearProgressIndicator],让两种进度语义互不混用。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OfflineScreen(
    items: List<OfflineItem>,
    usedBytes: Long,
    onPlay: (OfflineItem) -> Unit,
    onDelete: (OfflineItem) -> Unit,
    onRetry: (OfflineItem) -> Unit,
    /** 听全部。给的是已缓存那一节的第一条,队列是整个缓存库(见 `QueueSourceRepository.offline`)。 */
    onListenAll: (OfflineItem) -> Unit,
    /**
     * 已选中的条目 id,null 表示不在多选态。
     */
    selectedIds: Set<String>? = null,
    onToggleSelection: (OfflineItem) -> Unit = {},
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var pendingDelete by remember { mutableStateOf<OfflineItem?>(null) }
    val layoutDirection = LocalLayoutDirection.current

    AdaptiveVideoGridContent(modifier = modifier) { columnCount ->
        if (items.isEmpty()) {
            EmptyState(
                message = stringResource(Res.string.offline_empty),
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            )
            return@AdaptiveVideoGridContent
        }

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
        val headerHorizontalPadding = if (multiColumn) Spacing.Tight + 2.dp else Spacing.Comfortable

        val (completed, inFlight) = items.partition { it.status == OfflineStatus.Completed }
        val rowScope = OfflineRowScope(
            selectedIds = selectedIds,
            onPlay = onPlay,
            onRetry = onRetry,
            onToggleSelection = onToggleSelection,
            onDeleteRequest = { item ->
                // **还没开始下的那条不问。** 确认框存在的理由是"删掉就要重下几百 MB",
                // 而排队中的这条一个字节都还没下,取消它的代价是零。
                if (item.status == OfflineStatus.Queued) onDelete(item) else pendingDelete = item
            },
        )

        val gridState = rememberLazyGridState()
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = effectiveColumns,
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = gridPadding,
            ) {
                item(key = "summary-card", span = { GridItemSpan(maxLineSpan) }) {
                    OfflineSummaryCard(
                        completedCount = completed.size,
                        inFlightCount = inFlight.size,
                        usedBytes = usedBytes,
                        canListenAll = completed.isNotEmpty() && selectedIds == null,
                        onListenAll = { completed.firstOrNull()?.let(onListenAll) },
                        modifier = Modifier
                            .padding(horizontal = headerHorizontalPadding, vertical = Spacing.Tight)
                            .animateItem(),
                    )
                }
                if (inFlight.isNotEmpty()) {
                    item(key = "header-in-flight", span = { GridItemSpan(maxLineSpan) }) {
                        ProvideTextStyle(LocalTextStyle.current.tabular) {
                            SectionHeader(
                                title = stringResource(Res.string.offline_section_in_flight, inFlight.size),
                                modifier = Modifier
                                    .padding(horizontal = headerHorizontalPadding)
                                    .animateItem(),
                            )
                        }
                    }
                    offlineRows(inFlight, rowScope, multiColumn)
                }
                if (completed.isNotEmpty()) {
                    if (inFlight.isNotEmpty()) {
                        item(key = "header-completed", span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(
                                title = stringResource(Res.string.offline_section_completed),
                                modifier = Modifier
                                    .padding(horizontal = headerHorizontalPadding)
                                    .animateItem(),
                            )
                        }
                    }
                    offlineRows(completed, rowScope, multiColumn)
                }
            }
            ListScrollbar(gridState, Modifier.align(Alignment.CenterEnd))
        }
    }

    // 删除要确认:文件删掉之后要重新下一次几百 MB,而这一行的删除图标就在整行可点区的旁边。
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(Res.string.offline_delete_confirm_title)) },
            text = { Text(stringResource(Res.string.offline_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) { Text(stringResource(Res.string.action_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(Res.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun OfflineSummaryCard(
    completedCount: Int,
    inFlightCount: Int,
    usedBytes: Long,
    canListenAll: Boolean,
    onListenAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val primarySummary = if (completedCount > 0) {
        stringResource(Res.string.offline_count, completedCount) + MetaSeparator + formatBytes(usedBytes)
    } else {
        stringResource(Res.string.offline_section_in_flight, inFlightCount) + MetaSeparator + formatBytes(usedBytes)
    }
    val secondarySummary = if (completedCount > 0 && inFlightCount > 0) {
        stringResource(Res.string.offline_section_in_flight, inFlightCount)
    } else {
        null
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Cozy),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = primarySummary,
                    style = MaterialTheme.typography.titleSmallEmphasized.tabular,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                secondarySummary?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (completedCount > 0) {
                FilledTonalButton(
                    onClick = onListenAll,
                    enabled = canListenAll,
                    contentPadding = PaddingValues(horizontal = Spacing.Cozy, vertical = Spacing.Hair),
                ) {
                    Icon(
                        Icons.Filled.Headphones,
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                    Text(
                        stringResource(Res.string.offline_listen_all),
                        style = MaterialTheme.typography.labelLargeEmphasized,
                        modifier = Modifier.padding(start = Spacing.Hair + 2.dp),
                    )
                }
            }
        }
    }
}

/** 两节共用的行为,打包成一份传给 [offlineRows],免得每节各抄一遍这几个回调。 */
private class OfflineRowScope(
    val selectedIds: Set<String>?,
    val onPlay: (OfflineItem) -> Unit,
    val onRetry: (OfflineItem) -> Unit,
    val onToggleSelection: (OfflineItem) -> Unit,
    val onDeleteRequest: (OfflineItem) -> Unit,
)

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
    val selected = scope.selectedIds != null && item.id in scope.selectedIds
    val haptics = LocalHapticFeedback.current
    val rowUi = item.toRowUi()
    // 选中态淡入,不是跳变:取 effects 档 —— 变的是颜色,没有任何东西在移动。
    val rowColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "offline-row-selected",
    )

    val onCardClick: () -> Unit = {
        when {
            selecting -> scope.onToggleSelection(item)
            item.status == OfflineStatus.Failed -> scope.onRetry(item)
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
                    Checkbox(checked = selected, onCheckedChange = null)
                }
            } else {
                IconButton(onClick = { scope.onDeleteRequest(item) }) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = stringResource(Res.string.offline_delete, item.title),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val itemBody: @Composable () -> Unit = {
        if (multiColumn) {
            Column(
                modifier = Modifier
                    .padding(2.dp)
                    .clip(MaterialTheme.shapes.large)
                    .background(rowColor),
            ) {
                VideoGridCard(
                    ui = rowUi,
                    onClick = onCardClick,
                    onLongClick = onCardLongClick,
                    upAvatarUrl = item.upFaceUrl.takeIf { it.isNotBlank() },
                    overflow = trailingOrOverflow,
                )
                if (item.status == OfflineStatus.Running) {
                    RunningDownloadBar(
                        progress = item.progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = Spacing.Tight, end = Spacing.Tight, bottom = Spacing.Tight),
                    )
                }
            }
        } else {
            Column(modifier = Modifier.background(rowColor)) {
                VideoRow(
                    item = rowUi,
                    onClick = onCardClick,
                    onLongClick = onCardLongClick,
                    trailing = { trailingOrOverflow() },
                )
                if (item.status == OfflineStatus.Running) {
                    RunningDownloadBar(
                        progress = item.progress,
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
            menu = { close ->
                val leadingCount = 2
                val totalCount = leadingCount + videoCommonMenuItemCount(
                    bvid = item.bvid,
                    title = item.title,
                )
                DropdownMenuItem(
                    onClick = {
                        close()
                        scope.onToggleSelection(item)
                    },
                    text = { Text(stringResource(Res.string.action_select)) },
                    leadingIcon = { Icon(Icons.Outlined.Checklist, contentDescription = null) },
                    shape = menuItemShape(0, totalCount),
                )
                DropdownMenuItem(
                    onClick = {
                        close()
                        scope.onDeleteRequest(item)
                    },
                    text = { Text(stringResource(Res.string.action_delete)) },
                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                    shape = menuItemShape(1, totalCount),
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
            },
        ) { _ ->
            itemBody()
        }
    }
}

@Composable
private fun RunningDownloadBar(
    progress: Float?,
    modifier: Modifier = Modifier,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress },
            trackColor = trackColor,
            modifier = modifier,
        )
    } else {
        LinearProgressIndicator(
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
