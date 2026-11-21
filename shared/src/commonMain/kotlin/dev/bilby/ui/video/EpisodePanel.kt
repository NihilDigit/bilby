package dev.bilby.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.bilby.data.PlayEndMode
import dev.bilby.data.QueueOrder
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.player.EpisodeList
import dev.bilby.ui.player.EpisodeRow
import dev.bilby.ui.player.EpisodeTarget
import dev.bilby.ui.player.PlayerSidePanel
import dev.bilby.ui.player.PlayerSidePanelHeader
import dev.bilby.ui.player.QueueEdges
import dev.bilby.ui.player.QueueEditActions
import dev.bilby.ui.player.QueuePlaybackControls
import dev.bilby.ui.theme.Spacing

/**
 * 全屏下的切集面板。外壳(右侧划出、遮罩、返回先关)是 [PlayerSidePanel],和播放设置面板
 * 同一层。
 *
 * **点一条即关。** 目的达成之后这块面板挡的是画面本身,而全屏的全部意义就是画面。连续切几集
 * 的场合有,但比"切一集接着看"少得多,为它留着一块常驻的遮挡不划算。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.EpisodePanel(
    visible: Boolean,
    rows: List<EpisodeRow>,
    sourceLabel: String,
    endMode: PlayEndMode = PlayEndMode.Continue,
    order: QueueOrder = QueueOrder.Forward,
    onCycleEndMode: () -> Unit = {},
    onCycleOrder: () -> Unit = {},
    onSelect: (EpisodeTarget) -> Unit,
    onDismiss: () -> Unit,
    edges: QueueEdges? = null,
    playing: Boolean = false,
    editActions: QueueEditActions? = null,
    positionText: String? = null,
    loading: Boolean = false,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(rows.size, visible) {
        if (!visible || rows.size <= 1) editing = false
    }

    val multiPartBadge = rows.singleOrNull()?.parts?.takeIf { it.size > 1 }?.let { parts ->
        "${(parts.indexOfFirst { it.isCurrent } + 1).coerceAtLeast(1)} / ${parts.size}"
    }
    val resolvedTitle = sourceLabel.ifEmpty {
        if (multiPartBadge != null) {
            stringResource(Res.string.video_parts)
        } else {
            stringResource(Res.string.video_episodes)
        }
    }
    val resolvedBadge = if (rows.size == 1 && multiPartBadge != null && sourceLabel.isEmpty()) {
        multiPartBadge
    } else {
        positionText ?: multiPartBadge
    }
    val headerLoading = loading ||
        edges?.loadingBefore == true ||
        edges?.loadingAfter == true ||
        edges?.loadingAll == true

    PlayerSidePanel(visible = visible, onDismiss = onDismiss) {
        // 队列来源与「X / N」位置读数统一走 PlayerSidePanelHeader(带 48dp 关闭按钮)。
        // 全屏下不给跳目录页的入口:那要先退出全屏离开当前画面。
        PlayerSidePanelHeader(
            title = resolvedTitle,
            badgeText = resolvedBadge,
            loading = headerLoading,
            onClose = onDismiss,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.Comfortable, end = Spacing.Tight),
        ) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Hair),
            ) {
                if (editActions != null && rows.size > 1) {
                    TextButton(
                        onClick = { editing = !editing },
                        contentPadding = PaddingValues(horizontal = Spacing.Tight),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                    ) {
                        Text(
                            text = stringResource(if (editing) Res.string.action_done else Res.string.queue_edit),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    if (editing && editActions.onClearOthers != null) {
                        TextButton(
                            onClick = {
                                editActions.onClearOthers.invoke()
                                editing = false
                            },
                            contentPadding = PaddingValues(horizontal = Spacing.Tight),
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                        ) {
                            Text(
                                text = stringResource(Res.string.queue_clear_others),
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
                editActions?.onReset?.let { onReset ->
                    TextButton(
                        onClick = {
                            onReset()
                            editing = false
                        },
                        contentPadding = PaddingValues(horizontal = Spacing.Tight),
                        modifier = Modifier.defaultMinSize(minWidth = 1.dp),
                    ) {
                        Text(
                            text = stringResource(Res.string.queue_reset),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
            QueuePlaybackControls(
                endMode = endMode,
                order = order,
                onCycleEndMode = onCycleEndMode,
                onCycleOrder = onCycleOrder,
                showOrder = rows.size > 1,
            )
        }
        EpisodeList(
            rows = rows,
            edges = edges,
            playing = playing,
            editActions = editActions,
            editing = editing,
            errorBefore = edges?.errorBefore == true,
            errorAfter = edges?.errorAfter == true,
            remainingBefore = edges?.remainingBefore,
            remainingAfter = edges?.remainingAfter,
            onSelect = {
                onSelect(it)
                onDismiss()
            },
            contentPadding = PaddingValues(
                horizontal = Spacing.Tight,
                vertical = Spacing.Tight,
            ),
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** 队列非空时即提供面板入口,单条单 P 也可进入面板修改完播策略或重置队列。 */
fun List<EpisodeRow>.hasSomethingToSwitch(): Boolean = isNotEmpty()
