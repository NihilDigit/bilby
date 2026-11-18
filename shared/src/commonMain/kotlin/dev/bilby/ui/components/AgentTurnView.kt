package dev.bilby.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.bilby.agent.AgentStep
import dev.bilby.agent.AgentTurnState
import dev.bilby.agent.StepKind
import dev.bilby.agent.TraceItem
import dev.bilby.resources.*
import dev.bilby.stringResource
import dev.bilby.ui.theme.Dimens
import dev.bilby.ui.theme.Spacing
import dev.bilby.ui.theme.interactiveCard
import dev.bilby.ui.theme.tabular
import org.jetbrains.compose.resources.StringResource

/**
 * 助理一轮的完整界面:收进一张 `surfaceContainer` (`shapes.largeIncreased`) 结构卡片中,
 * 顶部以 `surfaceContainerHigh` 胶囊展示检索步数,展开可查看实时工具轨迹与中间候选视频,下方承载回答与引用卡片。
 *
 * 搜索页对话流与播放页「找相关」共用同一份实现。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentTurnView(
    turn: AgentTurnState,
    onVideoClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    citationContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    var processExpanded by remember { mutableStateOf(true) }
    val hasAnswer = turn.answer != null
    LaunchedEffect(hasAnswer) {
        if (hasAnswer) processExpanded = false
    }

    Surface(
        color = containerColor,
        shape = MaterialTheme.shapes.largeIncreased,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.Comfortable),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.Cozy),
            verticalArrangement = Arrangement.spacedBy(Spacing.Cozy),
        ) {
            if (turn.steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.Tight)) {
                    ProcessHeader(
                        expanded = processExpanded,
                        stepCount = turn.steps.size,
                        running = turn.running && !hasAnswer,
                        pillColor = citationContainerColor,
                        onToggle = { processExpanded = !processExpanded },
                    )
                    AnimatedVisibility(
                        visible = processExpanded,
                        enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                            fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                        exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                            fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(Spacing.Hair)) {
                            turn.steps.forEach { step ->
                                StepRow(step = step, onVideoClick = onVideoClick)
                            }
                        }
                    }
                }
            }

            turn.answer?.let { answer ->
                AgentAnswerView(
                    answer = answer,
                    onVideoClick = onVideoClick,
                    citationContainerColor = citationContainerColor,
                )
            }

            when {
                turn.error != null -> Column(
                    verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
                ) {
                    Text(
                        text = turn.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry) {
                        Text(stringResource(Res.string.action_retry))
                    }
                }

                turn.running -> InlineProgress(
                    text = stringResource(Res.string.agent_running),
                    modifier = Modifier.padding(vertical = Spacing.Hair),
                )

                !hasAnswer -> Text(
                    text = stringResource(Res.string.agent_no_result),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.Hair),
                )
            }
        }
    }
}

/**
 * 用户问的那一句,靠右的气泡。搜索页的助理和找相关的追问共用;
 * 支持桌面指针直接划选文本,以及右键/长按弹出「复制提问 / 选择文本」。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AgentQuestionBubble(text: String, modifier: Modifier = Modifier) {
    var selectingText by rememberSaveable { mutableStateOf(false) }
    val copyQuestion = rememberCopyAction()
    val isTouch = LocalPointerSource.current.isTouchLike
    val copyQuestionLabel = stringResource(Res.string.action_copy_question)
    val selectTextLabel = stringResource(Res.string.action_select_text)

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ContextMenuBox(
            onClick = null,
            menuLabel = copyQuestionLabel,
            menu = { close ->
                DropdownMenuItem(
                    onClick = {
                        close()
                        copyQuestion(text)
                    },
                    text = { Text(copyQuestionLabel) },
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
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.largeIncreased,
                modifier = Modifier
                    .fillMaxWidth(BubbleWidthFraction)
                    .wrapContentWidth(Alignment.End)
                    .then(
                        if (isTouch) {
                            Modifier
                                .clip(MaterialTheme.shapes.largeIncreased)
                                .combinedClickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = {},
                                    onLongClickLabel = copyQuestionLabel,
                                    onLongClick = openMenu,
                                )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                val bubbleText: @Composable () -> Unit = {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = Spacing.Tight),
                    )
                }
                if (isTouch) {
                    bubbleText()
                } else {
                    SelectionContainer {
                        bubbleText()
                    }
                }
            }
        }
    }

    if (selectingText && text.isNotEmpty()) {
        SelectableTextDialog(
            text = text,
            onDismiss = { selectingText = false },
        )
    }
}

/** 用户气泡最宽占多少。留出的两成让"谁在说话"从形状上就读得出来。 */
private const val BubbleWidthFraction = 0.8f

/**
 * 检索过程胶囊(`surfaceContainerHigh` + `CircleShape`),显示 `已检索 N 步` 并支持点击展开/收起工具轨迹。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ProcessHeader(
    expanded: Boolean,
    stepCount: Int,
    running: Boolean,
    pillColor: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onToggle,
        color = pillColor,
        contentColor = colors.onSurfaceVariant,
        shape = CircleShape,
        modifier = modifier.minimumInteractiveComponentSize(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.Cozy, vertical = ProcessPillVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Hair + ProcessPillIconGap),
        ) {
            Icon(
                imageVector = if (running) Icons.Outlined.AutoAwesome else Icons.Default.Check,
                contentDescription = null,
                tint = if (running) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(ProcessPillIconSize),
            )
            Text(
                text = stringResource(Res.string.agent_steps_count, stepCount),
                style = MaterialTheme.typography.labelMediumEmphasized.tabular,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) Res.string.agent_process_collapse else Res.string.agent_process_expand,
                ),
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(ProcessPillIconSize),
            )
        }
    }
}

private val ProcessPillVerticalPadding = 6.dp
private val ProcessPillIconGap = 2.dp
private val ProcessPillIconSize = 16.dp

@Composable
private fun StepRow(step: AgentStep, onVideoClick: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.Hair / 2),
        verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
    ) {
        // 行首是动作类型的图标,文字只写对象(见 StepKind);还在跑的那一步图标换成转圈。
        Row(
            modifier = Modifier.padding(horizontal = Spacing.Hair),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            if (step.finished) {
                Icon(
                    imageVector = step.kind.icon(),
                    contentDescription = stringResource(step.kind.labelRes()),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(Dimens.IconInline),
                )
            } else {
                LoadingSpinner()
            }
            Text(
                text = step.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (step.items.isNotEmpty()) {
            // 中间结果可点:助理翻到一半用户看中了可以直接点走(DESIGN 3.4)。
            val traceRowState = rememberLazyListState()
            LazyRow(
                state = traceRowState,
                modifier = Modifier.verticalWheelScrollsRow(traceRowState),
                contentPadding = PaddingValues(horizontal = Spacing.Hair),
                horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
            ) {
                items(
                    items = step.items,
                    key = { it.bvid },
                    contentType = { "trace-card" },
                ) { trace ->
                    TraceCard(item = trace, onClick = { onVideoClick(trace.bvid) })
                }
            }
        }
    }
}

/** 每类动作一个图标。选的都是全应用里同一件事已经在用的那个:放大镜是搜索,人像是 UP 主。 */
private fun StepKind.icon(): ImageVector = when (this) {
    StepKind.SearchVideos -> Icons.Outlined.Search
    StepKind.SearchUsers -> Icons.Outlined.PersonSearch
    StepKind.Up -> Icons.Outlined.Person
    StepKind.UpVideos -> Icons.Outlined.VideoLibrary
    StepKind.Video -> Icons.Outlined.PlayCircleOutline
    StepKind.Comments -> Icons.Outlined.ChatBubbleOutline
    StepKind.Collection -> Icons.AutoMirrored.Outlined.PlaylistPlay
    StepKind.Related -> Icons.Outlined.Explore
    StepKind.Other -> Icons.Outlined.MoreHoriz
}

/** 图标给读屏的名字:文字只有对象,动作只在图标上,读屏也得知道是哪一类。 */
private fun StepKind.labelRes(): StringResource = when (this) {
    StepKind.SearchVideos -> Res.string.agent_step_search_videos
    StepKind.SearchUsers -> Res.string.agent_step_search_users
    StepKind.Up -> Res.string.agent_step_up
    StepKind.UpVideos -> Res.string.agent_step_up_videos
    StepKind.Video -> Res.string.agent_step_video
    StepKind.Comments -> Res.string.agent_step_comments
    StepKind.Collection -> Res.string.agent_step_collection
    StepKind.Related -> Res.string.agent_step_related
    StepKind.Other -> Res.string.agent_process
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TraceCard(item: TraceItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ContextMenuBox(
        onClick = null,
        menuLabel = stringResource(Res.string.menu_item_actions),
        modifier = modifier,
        menu = { close ->
            VideoCommonMenuItems(
                bvid = item.bvid,
                title = item.title,
                close = close,
                coverUrl = item.coverUrl,
                upName = item.upName,
            )
        },
    ) { openMenu ->
        Column(
            modifier = Modifier
                .width(Dimens.TraceCardWidth)
                .interactiveCard(
                    shape = MaterialTheme.shapes.medium,
                    baseColor = Color.Transparent,
                    hoverColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = onClick,
                    onLongClick = openMenu,
                )
                .padding(Spacing.Hair),
            verticalArrangement = Arrangement.spacedBy(Spacing.Hair),
        ) {
            ListCover(url = item.coverUrl, width = Dimens.TraceCardWidth - Spacing.Tight)
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
