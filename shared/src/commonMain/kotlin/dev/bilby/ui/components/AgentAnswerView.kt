package dev.bilby.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.agent.AgentAnswer
import dev.bilby.agent.AnswerSource
import dev.bilby.agent.CitationClose
import dev.bilby.agent.CitationOpen
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.tabular

/**
 * 助理的回答:上面是回答正文,下面是带编号徽章(`[1]`, `[2]`)的分段引用卡片。
 *
 * - 正文支持桌面鼠标直接拖拽划选(`SelectionContainer`),右键或触屏长按弹出「复制回答 / 选择文本」。
 * - 引用视频采用 M3E [SegmentedListItem] 分段容器,行首配 `primaryContainer` 编号胶囊与紧凑封面,
 *   右键或窄屏溢出按钮均接入统一的 [VideoCommonMenuItems]。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun AgentAnswerView(
    answer: AgentAnswer,
    onVideoClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    citationContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val copyableText = remember(answer.text) { formatCopyableAnswer(answer.text) }
    var selectingText by rememberSaveable { mutableStateOf(false) }
    val copyAnswer = rememberCopyAction()
    val isTouch = LocalPointerSource.current.isTouchLike
    val copyAnswerLabel = stringResource(Res.string.action_copy_answer)
    val selectTextLabel = stringResource(Res.string.action_select_text)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
    ) {
        if (answer.text.isNotBlank()) {
            ContextMenuBox(
                onClick = null,
                menuLabel = copyAnswerLabel,
                menu = { close ->
                    DropdownMenuItem(
                        onClick = {
                            close()
                            copyAnswer(copyableText)
                        },
                        text = { Text(copyAnswerLabel) },
                        leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                        shape = menuItemShape(0, 2),
                    )
                    DropdownMenuItem(
                        onClick = {
                            close()
                            selectingText = true
                        },
                        text = { Text(selectTextLabel) },
                        leadingIcon = { Icon(Icons.Outlined.SelectAll, contentDescription = null) },
                        shape = menuItemShape(1, 2),
                    )
                },
            ) { openMenu ->
                val markdownContent: @Composable () -> Unit = {
                    MarkdownText(
                        text = answer.text,
                        onCitationClick = { number ->
                            answer.sources.getOrNull(number - 1)?.let { onVideoClick(it.bvid) }
                        },
                    )
                }
                if (isTouch) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {},
                                onLongClickLabel = copyAnswerLabel,
                                onLongClick = openMenu,
                            ),
                    ) {
                        markdownContent()
                    }
                } else {
                    SelectionContainer {
                        markdownContent()
                    }
                }
            }
        }

        if (answer.sources.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.agent_sources),
                style = MaterialTheme.typography.labelLargeEmphasized,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.Hair),
            )
            val totalSources = answer.sources.size
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                answer.sources.forEachIndexed { index, source ->
                    AgentCitationCard(
                        index = index,
                        total = totalSources,
                        source = source,
                        containerColor = citationContainerColor,
                        isTouch = isTouch,
                        onClick = { onVideoClick(source.bvid) },
                    )
                }
            }
        }
    }

    if (selectingText && copyableText.isNotEmpty()) {
        SelectableTextDialog(
            text = copyableText,
            onDismiss = { selectingText = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AgentCitationCard(
    index: Int,
    total: Int,
    source: AnswerSource,
    containerColor: Color,
    isTouch: Boolean,
    onClick: () -> Unit,
) {
    val displayTitle = source.trace?.title?.takeIf { it.isNotBlank() } ?: source.bvid
    val menuTitle = source.trace?.title.orEmpty()
    val coverUrl = source.trace?.coverUrl.orEmpty()
    val upName = source.trace?.upName?.takeIf { it.isNotBlank() }
    val colors = MaterialTheme.colorScheme
    var overflowOpen by remember { mutableStateOf(false) }

    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        menu = { close ->
            VideoCommonMenuItems(
                bvid = source.bvid,
                title = menuTitle,
                close = close,
                coverUrl = coverUrl,
                upName = upName.orEmpty(),
            )
        },
    ) { _ ->
        SegmentedListItem(
            onClick = onClick,
            shapes = ListItemDefaults.segmentedShapes(index = index, count = total),
            colors = ListItemDefaults.segmentedColors(
                containerColor = containerColor,
                selectedContainerColor = containerColor,
            ),
            verticalAlignment = Alignment.CenterVertically,
            leadingContent = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    Surface(
                        color = colors.primaryContainer,
                        contentColor = colors.onPrimaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = "[${index + 1}]",
                            style = MaterialTheme.typography.labelMediumEmphasized.tabular,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = Spacing.Tight, vertical = Spacing.Hair),
                        )
                    }
                    if (coverUrl.isNotEmpty()) {
                        ListCover(
                            url = coverUrl,
                            width = Dimens.CompactCoverWidth,
                            cornerRadius = CitationCoverCorner,
                        )
                    }
                }
            },
            trailingContent = if (isTouch) {
                {
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = stringResource(Res.string.menu_item_actions),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                            shape = MenuDefaults.shape,
                            containerColor = MenuDefaults.containerColor,
                        ) {
                            VideoCommonMenuItems(
                                bvid = source.bvid,
                                title = menuTitle,
                                close = { overflowOpen = false },
                                coverUrl = coverUrl,
                                upName = upName.orEmpty(),
                            )
                        }
                    }
                }
            } else {
                null
            },
            supportingContent = upName?.let {
                {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.bodyMediumEmphasized,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val CitationCoverCorner = 6.dp

private val CitationMarkerRegex = Regex("$CitationOpen(\\d+)$CitationClose")

private fun formatCopyableAnswer(raw: String): String =
    CitationMarkerRegex.replace(raw) { "[${it.groupValues[1]}]" }.trim()
