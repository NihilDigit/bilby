package dev.bilby.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.data.PlayEndMode
import dev.bilby.data.QueueOrder
import dev.bilby.data.VideoPart
import dev.bilby.formatDurationSeconds
import dev.bilby.player.QueueItem
import dev.bilby.player.QueueState
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.components.InlineProgress
import dev.bilby.ui.components.ListCover
import dev.bilby.ui.components.ListScrollbar
import dev.bilby.ui.components.PlayingIndicator
import dev.bilby.ui.components.menuItemShape
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import org.jetbrains.compose.resources.StringResource

/**
 * 切集能落到的目标。**两种,因为队列和分 P 是两条不同的命令**:跳队列是标准 Player 命令
 * (`seekTo(index, 0)`),换分 P 是同稿件内的自定义命令
 * ([dev.bilby.player.AudioPlaybackService.ACTION_PLAY_PART])。
 *
 * 分派收在一处([EpisodeRow] 的调用方给的那个 `onSelect`),三种形态因此不必各写一遍
 * "这一下该发哪条命令"。
 */
sealed interface EpisodeTarget {

    /** 队列里的另一条视频。 */
    @JvmInline
    value class Video(val bvid: String) : EpisodeTarget

    /** 当前这条视频的另一 P。 */
    @JvmInline
    value class Part(val cid: Long) : EpisodeTarget
}

/** 二级条目:当前这条视频的一个分 P。 */
@Immutable
data class EpisodePart(
    val cid: Long,
    /** 展示用的 P 号,来自详情的 `page`,不是列表下标。 */
    val ordinal: Int,
    val title: String,
    val isCurrent: Boolean,
)

/**
 * 一级条目:队列里的一条视频。
 *
 * **[parts] 只有正在播的那一条可能非空。** 分 P 清单来自视频详情,而页面手上只有当前这条的
 * 详情;队列里其余各条要各打一次请求才知道有几 P,不值得为一个多数条目用不上的二级列表在
 * 打开面板时先发十几个请求。缓存面板早就是这么做的(见 `ui/video/OfflineCacheSheet.kt`),
 * 这里沿用同一条约束,不发明第二种。
 *
 * 单 P 视频的 [parts] 同样是空:一个只有 P1 的二级列表是纯噪声。
 */
@Immutable
data class EpisodeRow(
    val bvid: String,
    val title: String,
    val upName: String,
    val coverUrl: String,
    val durationSeconds: Long,
    val isCurrent: Boolean,
    val parts: List<EpisodePart> = emptyList(),
)

/** 队列交互式编辑操作，由拥有活帧队列或待播草稿的页面传入。 */
@Stable
data class QueueEditActions(
    val onMove: (bvid: String, toIndex: Int) -> Unit,
    val onRemove: (bvid: String) -> Unit,
    val onPlayNext: ((QueueItem) -> Unit)? = null,
    val onClearOthers: (() -> Unit)? = null,
    val onReset: (() -> Unit)? = null,
    val allowRemoveLast: Boolean = false,
)

private fun EpisodeRow.toQueueItem(): QueueItem = QueueItem(
    bvid = bvid,
    title = title,
    upName = upName,
    coverUrl = coverUrl,
    durationSeconds = durationSeconds,
)

/**
 * 统一格式化队列位置读数(`X / N`)。
 * - 已知精确来源偏移与总数时输出 `sourcePosition / sourceTotal`;
 * - 仅知总数的中间窗口态输出 `queue_position_windowed`(`%1$d/%2$d  共 %3$d`,不含 `·`);
 * - 其余可定位情形回退为 `displayPosition / displayTotal`。
 */
@Composable
fun QueuePositionText(queue: QueueState): String? {
    val srcPos = queue.sourcePosition
    val srcTotal = queue.sourceTotal
    if (srcPos != null && srcTotal != null) {
        return "$srcPos / $srcTotal"
    }
    val idx = queue.currentIndex
    if (srcTotal != null && idx in queue.items.indices) {
        return stringResource(Res.string.queue_position_windowed, idx + 1, queue.items.size, srcTotal)
    }
    val dispPos = queue.displayPosition
    val dispTotal = queue.displayTotal
    return if (dispPos != null && dispTotal != null) {
        "$dispPos / $dispTotal"
    } else {
        null
    }
}

/**
 * 把队列与当前这条的分 P 拼成一份切集清单。**三种形态(详情页、听视频、全屏)共用这一份**,
 * 差别只在怎么画:详情页把 [EpisodeRow.parts] 摊成一排 chip,另外两种把它摊成二级列表。
 */
fun buildEpisodeRows(
    queue: List<QueueItem>,
    currentBvid: String?,
    pageBvid: String?,
    parts: List<VideoPart>,
    pageDurationSeconds: Long,
    currentCid: Long,
): List<EpisodeRow> = queue.map { item ->
    val isCurrent = item.bvid == currentBvid
    EpisodeRow(
        bvid = item.bvid,
        title = item.title,
        upName = item.upName,
        coverUrl = item.coverUrl,
        durationSeconds = item.durationSeconds.takeIf { it > 0 }
            ?: if (item.bvid == pageBvid) pageDurationSeconds else 0L,
        isCurrent = isCurrent,
        parts = if (isCurrent && item.bvid == pageBvid && parts.size > 1) {
            parts.map { part ->
                EpisodePart(
                    cid = part.cid,
                    ordinal = part.index,
                    title = part.title,
                    isCurrent = part.cid == currentCid,
                )
            }
        } else {
            emptyList()
        },
    )
}

/**
 * 队列两头还能不能续、正不正在续、是否续取失败及续取动作。
 */
@Stable
class QueueEdges(
    val canLoadBefore: Boolean,
    val canLoadAfter: Boolean,
    val loadingBefore: Boolean,
    val loadingAfter: Boolean,
    val onLoad: (before: Boolean) -> Unit,
    val errorBefore: Boolean = false,
    val errorAfter: Boolean = false,
    val remainingBefore: Int? = null,
    val remainingAfter: Int? = null,
)

/** 正在播的那一条在清单里的下标,没有则 -1。滚动定位与「N / M」都用它。 */
fun List<EpisodeRow>.currentIndex(): Int = indexOfFirst { it.isCurrent }

/**
 * 切集清单:一列视频,正在播的那一条底下摊开它的分 P。
 *
 * 听视频的队列 Sheet、完整队列面板与全屏切集面板共用它。
 */
@Composable
fun EpisodeList(
    rows: List<EpisodeRow>,
    onSelect: (EpisodeTarget) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    state: LazyListState = rememberLazyListState(),
    /** 队列面板才有;没有时两头不续。 */
    edges: QueueEdges? = null,
    /** 正在放还是停着,决定当前那条尾部的指示跳不跳。 */
    playing: Boolean = false,
    /** 队列编辑回调；为空时仅支持只读点选。 */
    editActions: QueueEditActions? = null,
    /** 是否处于行内排序/移除编辑态。 */
    editing: Boolean = false,
    errorBefore: Boolean = false,
    errorAfter: Boolean = false,
    remainingBefore: Int? = null,
    remainingAfter: Int? = null,
) {
    val currentIndex = remember(rows) { rows.currentIndex() }
    val currentBvid = rows.getOrNull(currentIndex)?.bvid
    val hasErrorBefore = errorBefore || (edges?.errorBefore == true)
    val hasErrorAfter = errorAfter || (edges?.errorAfter == true)
    val resolvedRemainingBefore = remainingBefore ?: edges?.remainingBefore
    val resolvedRemainingAfter = remainingAfter ?: edges?.remainingAfter
    val showBeforeEdge = edges != null && (edges.loadingBefore || hasErrorBefore || edges.canLoadBefore)
    val showAfterEdge = edges != null && (edges.loadingAfter || hasErrorAfter || edges.canLoadAfter)

    var initialCenterDone by remember(currentBvid) { mutableStateOf(currentIndex < 0) }
    val latestShowBefore by rememberUpdatedState(showBeforeEdge)

    // 列表按自然顺序摆着不动,切集时让高亮那条滚到中间。只在当前条换了时居中,不看条数。
    LaunchedEffect(currentBvid) {
        if (currentIndex < 0) {
            initialCenterDone = true
            return@LaunchedEffect
        }
        try {
            snapshotFlow { state.layoutInfo.totalItemsCount }.first { it > 0 }
            // 顶上若有续取状态行则多占一个下标。
            val target = currentIndex + if (latestShowBefore) 1 else 0
            state.scrollToItem(target)
            val info = state.layoutInfo
            val row = info.visibleItemsInfo.firstOrNull { it.index == target } ?: return@LaunchedEffect
            state.scrollToItem(target, -(info.viewportSize.height - row.size) / 2)
        } finally {
            initialCenterDone = true
        }
    }

    if (edges != null) {
        val latest by rememberUpdatedState(edges)
        val latestErrBefore by rememberUpdatedState(hasErrorBefore)
        val latestErrAfter by rememberUpdatedState(hasErrorAfter)
        val latestCentered by rememberUpdatedState(initialCenterDone)
        LaunchedEffect(state) {
            snapshotFlow {
                if (!latestCentered) return@snapshotFlow false to false
                val info = state.layoutInfo
                val visible = info.visibleItemsInfo
                val atTop = visible.firstOrNull()?.index == 0
                val atBottom = info.totalItemsCount > 0 && visible.lastOrNull()?.index == info.totalItemsCount - 1
                // 续取失败锁存期间严禁自动重试,防止 -412 风控紧循环(CLAUDE.md)。
                val wantBefore = atTop && latest.canLoadBefore && !latest.loadingBefore && !latestErrBefore
                val wantAfter = atBottom && latest.canLoadAfter && !latest.loadingAfter && !latestErrAfter
                wantBefore to wantAfter
            }.distinctUntilChanged().collect { (wantBefore, wantAfter) ->
                if (wantBefore) latest.onLoad(true)
                if (wantAfter) latest.onLoad(false)
            }
        }
    }

    // 视频与分 P 摊平成一组分段:分 P 是当前那条的内部结构,排在同一组里,首尾圆角才落在
    // 整份清单的两头,而不是分 P 前后各断开一次。
    val segmentCount = rows.size + rows.sumOf { it.parts.size }
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding,
        ) {
            if (showBeforeEdge && edges != null) {
                item(key = "edge-before", contentType = "edge") {
                    QueueEdgeRow(
                        before = true,
                        loading = edges.loadingBefore,
                        error = hasErrorBefore,
                        remainingCount = resolvedRemainingBefore,
                        onLoad = { edges.onLoad(true) },
                    )
                }
            }
            var segment = 0
            rows.forEachIndexed { videoIndex, row ->
                val rowSegment = segment++
                item(key = row.bvid, contentType = "video") {
                    QueueRowItem(
                        row = row,
                        playing = playing,
                        index = rowSegment,
                        count = segmentCount,
                        onClick = { onSelect(EpisodeTarget.Video(row.bvid)) },
                        videoIndex = videoIndex,
                        videoCount = rows.size,
                        editActions = editActions,
                        editing = editing,
                    )
                }
                // 二级:当前这条的分 P。**key 带上 bvid**,否则连播走到另一条多 P 视频时,
                // 两组分 P 的 cid 都是 Long,复用会把上一条的选中态带过来。
                val firstPartSegment = segment
                segment += row.parts.size
                itemsIndexed(
                    row.parts,
                    key = { _, part -> "${row.bvid}-${part.cid}" },
                    contentType = { _, _ -> "part" },
                ) { i, part ->
                    PartListItem(
                        part = part,
                        index = firstPartSegment + i,
                        count = segmentCount,
                        onClick = { onSelect(EpisodeTarget.Part(part.cid)) },
                    )
                }
            }
            if (showAfterEdge && edges != null) {
                item(key = "edge-after", contentType = "edge") {
                    QueueEdgeRow(
                        before = false,
                        loading = edges.loadingAfter,
                        error = hasErrorAfter,
                        remainingCount = resolvedRemainingAfter,
                        onLoad = { edges.onLoad(false) },
                    )
                }
            }
        }
        ListScrollbar(state, Modifier.align(Alignment.CenterEnd))
    }
}

/**
 * 队列里的一条视频。当前正在播放项使用 `secondaryContainer` 容器与 `bodyMediumEmphasized` 标题强调,
 * 封面右下角时长通过等宽数字(`tnum`)稳定对齐。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QueueRowItem(
    row: EpisodeRow,
    playing: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    videoIndex: Int = index,
    videoCount: Int = count,
    editActions: QueueEditActions? = null,
    editing: Boolean = false,
) {
    val canPlayNext = editActions?.onPlayNext != null &&
        !row.isCurrent &&
        videoCount > 1 &&
        (!editActions.allowRemoveLast || videoIndex > 0)
    val canMoveUp = editActions != null && videoIndex > 0
    val canMoveDown = editActions != null && videoIndex in 0 until (videoCount - 1)
    val canRemove = editActions != null && (videoCount > 1 || editActions.allowRemoveLast)

    val itemContent: @Composable () -> Unit = {
        SegmentedListItem(
            selected = row.isCurrent,
            onClick = onClick,
            shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
            colors = ListItemDefaults.segmentedColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                selectedLeadingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                selectedTrailingContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            modifier = Modifier.padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap),
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = {
                ProvideTextStyle(LocalTextStyle.current.tabular) {
                    ListCover(
                        url = row.coverUrl,
                        width = QueueCoverWidth,
                        cornerRadius = QueueCoverCorner,
                        durationText = if (row.durationSeconds > 0) formatDurationSeconds(row.durationSeconds) else "",
                    )
                }
            },
            trailingContent = when {
                editing && editActions != null -> {
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (row.isCurrent) {
                                PlayingIndicator(
                                    active = playing,
                                    contentDescription = stringResource(
                                        if (playing) Res.string.video_queue_now_playing else Res.string.video_queue_paused,
                                    ),
                                    modifier = Modifier
                                        .padding(end = Spacing.Hair)
                                        .size(Dimens.IconInline),
                                )
                            }
                            IconButton(
                                onClick = { editActions.onMove(row.bvid, videoIndex - 1) },
                                enabled = canMoveUp,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowUpward,
                                    contentDescription = stringResource(Res.string.queue_move_up),
                                    modifier = Modifier.size(Dimens.IconInline),
                                )
                            }
                            IconButton(
                                onClick = { editActions.onMove(row.bvid, videoIndex + 1) },
                                enabled = canMoveDown,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.ArrowDownward,
                                    contentDescription = stringResource(Res.string.queue_move_down),
                                    modifier = Modifier.size(Dimens.IconInline),
                                )
                            }
                            IconButton(
                                onClick = { editActions.onRemove(row.bvid) },
                                enabled = canRemove,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(Res.string.queue_remove),
                                    modifier = Modifier.size(Dimens.IconInline),
                                )
                            }
                        }
                    }
                }
                row.isCurrent -> {
                    {
                        PlayingIndicator(
                            active = playing,
                            contentDescription = stringResource(
                                if (playing) Res.string.video_queue_now_playing else Res.string.video_queue_paused,
                            ),
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                    }
                }
                else -> null
            },
        ) {
            Text(
                text = row.title,
                style = if (row.isCurrent) {
                    MaterialTheme.typography.bodyMediumEmphasized
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val hasCommon = row.bvid.startsWith("BV", ignoreCase = true)
    val editCount = (if (canPlayNext) 1 else 0) +
        (if (canMoveUp) 1 else 0) +
        (if (canMoveDown) 1 else 0) +
        (if (canRemove) 1 else 0)
    if (hasCommon || editCount > 0) {
        dev.bilby.ui.components.ContextMenuBox(
            onClick = null,
            menuLabel = stringResource(Res.string.menu_item_actions),
            menu = { close ->
                val commonCount = if (hasCommon) {
                    dev.bilby.ui.components.videoCommonMenuItemCount(
                        bvid = row.bvid,
                        includeQueueActions = false,
                        title = row.title,
                    )
                } else {
                    0
                }
                val totalCount = editCount + commonCount
                var cursor = 0
                if (canPlayNext) {
                    DropdownMenuItem(
                        onClick = {
                            close()
                            editActions.onPlayNext(row.toQueueItem())
                        },
                        text = { Text(stringResource(Res.string.queue_play_next)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null) },
                        shape = menuItemShape(cursor++, totalCount),
                    )
                }
                if (canMoveUp) {
                    DropdownMenuItem(
                        onClick = {
                            close()
                            editActions.onMove(row.bvid, videoIndex - 1)
                        },
                        text = { Text(stringResource(Res.string.queue_move_up)) },
                        leadingIcon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                        shape = menuItemShape(cursor++, totalCount),
                    )
                }
                if (canMoveDown) {
                    DropdownMenuItem(
                        onClick = {
                            close()
                            editActions.onMove(row.bvid, videoIndex + 1)
                        },
                        text = { Text(stringResource(Res.string.queue_move_down)) },
                        leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                        shape = menuItemShape(cursor++, totalCount),
                    )
                }
                if (canRemove) {
                    DropdownMenuItem(
                        onClick = {
                            close()
                            editActions.onRemove(row.bvid)
                        },
                        text = { Text(stringResource(Res.string.queue_remove)) },
                        leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                        shape = menuItemShape(cursor++, totalCount),
                    )
                }
                if (hasCommon) {
                    dev.bilby.ui.components.VideoCommonMenuItems(
                        bvid = row.bvid,
                        title = row.title,
                        close = close,
                        coverUrl = row.coverUrl,
                        upName = row.upName,
                        durationSeconds = row.durationSeconds,
                        includeQueueActions = false,
                        startIndex = cursor,
                        totalCount = totalCount,
                    )
                }
            },
        ) { _ ->
            itemContent()
        }
    } else {
        itemContent()
    }
}

/**
 * 队列边界三态透明提示行:
 * 1. 加载中:`InlineProgress` + 正在加载前/后条目;
 * 2. 失败态:整宽可点重试(`queue_edge_load_failed` + `action_retry`);
 * 3. 待续取:`ExpandLess`/`ExpandMore` + 剩余条数提示,支持点击立即加载。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun QueueEdgeRow(
    before: Boolean,
    loading: Boolean,
    error: Boolean,
    remainingCount: Int?,
    onLoad: () -> Unit,
) {
    when {
        loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Dimens.MinTouchTarget)
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
                contentAlignment = Alignment.Center,
            ) {
                InlineProgress(
                    text = stringResource(
                        if (before) Res.string.queue_edge_loading_before else Res.string.queue_edge_loading_after,
                    ),
                )
            }
        }

        error -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button, onClick = onLoad)
                    .heightIn(min = Dimens.MinTouchTarget)
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight, Alignment.CenterHorizontally),
            ) {
                Text(
                    text = stringResource(Res.string.queue_edge_load_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(Res.string.action_retry),
                    style = MaterialTheme.typography.labelLargeEmphasized,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        else -> {
            val label = if (before) {
                if (remainingCount != null && remainingCount > 0) {
                    stringResource(Res.string.queue_edge_has_before_count, remainingCount)
                } else {
                    stringResource(Res.string.queue_edge_has_before)
                }
            } else {
                if (remainingCount != null && remainingCount > 0) {
                    stringResource(Res.string.queue_edge_has_after_count, remainingCount)
                } else {
                    stringResource(Res.string.queue_edge_has_after)
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(role = Role.Button, onClick = onLoad)
                    .heightIn(min = Dimens.MinTouchTarget)
                    .padding(horizontal = Spacing.Comfortable, vertical = Spacing.Tight),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair, Alignment.CenterHorizontally),
            ) {
                Icon(
                    imageVector = if (before) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(Dimens.IconInline),
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMediumEmphasized.tabular,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * 二级行,和视频行同在一组分段里。**只有文字**,不给封面:分 P 共用整条视频的一张封面,每行
 * 印一遍等于一列完全相同的图。封面那一格空着留位,文字因此和上面视频标题落在同一条竖线上,
 * 读得出是父子。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PartListItem(part: EpisodePart, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        selected = false,
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index = index, count = count),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .padding(top = if (index == 0) 0.dp else ListItemDefaults.SegmentedGap)
            .semantics { selected = part.isCurrent },
        verticalAlignment = Alignment.CenterVertically,
        leadingContent = { Spacer(modifier = Modifier.width(QueueCoverWidth)) },
    ) {
        Text(
            stringResource(Res.string.video_part_label, part.ordinal, part.title),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = (if (part.isCurrent) {
                MaterialTheme.typography.bodyMediumEmphasized
            } else {
                MaterialTheme.typography.bodyMedium
            }).tabular,
            color = if (part.isCurrent) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** 队列条目的封面宽度。16:9 下约 54dp 高,和两行标题齐平。 */
private val QueueCoverWidth = 96.dp

/** 队列条目封面的圆角。列表项自己的圆角在 4dp 到 16dp 之间变,封面取中间一档。 */
private val QueueCoverCorner = 8.dp

fun PlayEndMode.labelRes(): StringResource = when (this) {
    PlayEndMode.Pause -> Res.string.queue_end_pause
    PlayEndMode.Continue -> Res.string.queue_end_continue
    PlayEndMode.RepeatOne -> Res.string.queue_end_repeat_one
}

fun QueueOrder.labelRes(): StringResource = when (this) {
    QueueOrder.Forward -> Res.string.queue_order_forward
    QueueOrder.Reverse -> Res.string.queue_order_reverse
    QueueOrder.Shuffle -> Res.string.queue_order_shuffle
}

/**
 * 队列头部的两个播放策略开关,看视频(简介队列、完整队列、全屏切集面板)与听视频共用同一组控件:
 * - 完播行为:「播完暂停 → 连播 → 洗脑循环」三态一键轮换。
 * - 队列顺序:「正序 → 倒序 → 随机」三态一键轮换。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RowScope.QueuePlaybackControls(
    endMode: PlayEndMode,
    order: QueueOrder,
    onCycleEndMode: () -> Unit,
    onCycleOrder: () -> Unit,
    showOrder: Boolean = true,
) {
    val endLabel = stringResource(endMode.labelRes())
    ButtonGroup(
        overflowIndicator = {},
        horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        val endInteraction = remember { MutableInteractionSource() }
        customItem(
            buttonGroupContent = {
                TextButton(
                    onClick = onCycleEndMode,
                    shapes = ButtonDefaults.shapes(),
                    interactionSource = endInteraction,
                    contentPadding = PaddingValues(horizontal = Spacing.Tight),
                    modifier = Modifier
                        .animateWidth(endInteraction)
                        .defaultMinSize(minWidth = 1.dp)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .semantics { stateDescription = endLabel },
                ) {
                    Icon(
                        imageVector = when (endMode) {
                            PlayEndMode.Pause -> Icons.Outlined.PauseCircleOutline
                            PlayEndMode.Continue -> Icons.Outlined.SkipNext
                            PlayEndMode.RepeatOne -> Icons.Filled.RepeatOne
                        },
                        contentDescription = null,
                        modifier = Modifier.size(Dimens.IconInline),
                    )
                    Text(
                        text = endLabel,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(start = Spacing.Hair),
                    )
                }
            },
            menuContent = {},
        )
        if (showOrder) {
            val orderLabel = stringResource(order.labelRes())
            val orderInteraction = remember { MutableInteractionSource() }
            customItem(
                buttonGroupContent = {
                    TextButton(
                        onClick = onCycleOrder,
                        shapes = ButtonDefaults.shapes(),
                        interactionSource = orderInteraction,
                        contentPadding = PaddingValues(horizontal = Spacing.Tight),
                        modifier = Modifier
                            .animateWidth(orderInteraction)
                            .defaultMinSize(minWidth = 1.dp)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .semantics { stateDescription = orderLabel },
                    ) {
                        Icon(
                            imageVector = when (order) {
                                QueueOrder.Forward -> Icons.Filled.ArrowDownward
                                QueueOrder.Reverse -> Icons.Filled.ArrowUpward
                                QueueOrder.Shuffle -> Icons.Filled.Shuffle
                            },
                            contentDescription = null,
                            modifier = Modifier.size(Dimens.IconInline),
                        )
                        Text(
                            text = orderLabel,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(start = Spacing.Hair),
                        )
                    }
                },
                menuContent = {},
            )
        }
    }
}

@Composable
fun QueuePlaybackControlsRow(
    endMode: PlayEndMode,
    order: QueueOrder,
    onCycleEndMode: () -> Unit,
    onCycleOrder: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.End,
    showOrder: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = horizontalArrangement,
    ) {
        QueuePlaybackControls(
            endMode = endMode,
            order = order,
            onCycleEndMode = onCycleEndMode,
            onCycleOrder = onCycleOrder,
            showOrder = showOrder,
        )
    }
}
